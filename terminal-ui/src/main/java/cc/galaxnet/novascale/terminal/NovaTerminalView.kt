// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import cc.galaxnet.novascale.core.*
import kotlin.math.abs
import kotlin.math.floor

/** NovaScale's independently implemented Android surface for the immutable Ghostty snapshot contract. */
class NovaTerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr), ComponentCallbacks2 {
    private val fonts = TerminalFonts()
    private val renderer = TerminalRowRenderer(fonts)
    private val images = TerminalImageRenderer(this)
    private val pointer = TerminalTouch(this)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame: TerminalRenderSnapshot? = null
    private var typeface = Typeface.MONOSPACE
    internal var fontSizeSp = 8f
        private set
    internal val cellHeight get() = fonts.cellHeight
    internal val mouseTracking get() = TerminalMouse.enabled(frame?.mouseFlags ?: 0)
    private var cursorColor: Int? = null
    private var cursorShape: CursorShape? = null
    private var selectionColor = 0xff3465a4.toInt()
    private var selectionText = 0xffffffff.toInt()
    private var appearance: String? = null
    private var anchor: TerminalCellPosition? = null
    private var extent: TerminalCellPosition? = null
    private var dragAnchor = false
    private var actionMode: ActionMode? = null
    private var input: ((ByteArray) -> Unit)? = null
    private var resize: ((Int, Int) -> Unit)? = null
    private var resizePixels: ((Int, Int, Int, Int) -> Unit)? = null
    private var localScroll: ((Int) -> Unit)? = null
    private var remoteScroll: ((ByteArray) -> Unit)? = null
    private var modifiersConsumed: (() -> Unit)? = null
    private var windowFocused: (() -> Unit)? = null
    private var sizeChanged: ((Float) -> Unit)? = null
    private var binding: Any? = Any()
    private var inputBinding: Any? = Any()
    private var lastGeometry: List<Int>? = null
    private var oneShot = TerminalKeyModifiers()
    private var accent = 0
    private var connection: TerminalInputConnection? = null
    private var accessibilityPending = false
    private val accessibilityUpdate = Runnable {
        accessibilityPending = false
        if (isShown && isAttachedToWindow) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isSaveEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        updateFont()
    }

    fun submitSnapshot(value: TerminalRenderSnapshot) {
        val previous = frame
        if (previous == value) return
        if (previous?.mouseFlags != value.mouseFlags) pointer.cancel(sendRelease = false)
        if (previous?.rows != value.rows || previous?.columns != value.columns) clearSelection()
        frame = value
        images.submit(value.images)
        if (previous?.rows != value.rows && !accessibilityPending &&
            context.getSystemService(AccessibilityManager::class.java).isEnabled) {
            accessibilityPending = true
            postDelayed(accessibilityUpdate, 100)
        }
        postInvalidateOnAnimation()
    }

    fun setTerminalCallbacks(onInput: ((ByteArray) -> Unit)?, onResize: ((Int, Int) -> Unit)?,
        resizeToken: Any? = null, onResizePixels: ((Int, Int, Int, Int) -> Unit)? = null,
        onScroll: ((Int) -> Unit)? = null, onModifiersConsumed: (() -> Unit)? = null,
        onRemoteScroll: ((ByteArray) -> Unit)? = null, inputToken: Any? = resizeToken) {
        val inputChanged = inputBinding != inputToken || input == null && onInput != null
        if (inputChanged || onInput == null) {
            pointer.cancel()
            connection?.cancel(); connection = null
            accent = 0; clearSelection()
            inputBinding = inputToken
            clearRenderCaches()
        }
        if (binding != resizeToken) { lastGeometry = null; binding = resizeToken }
        if (resize == null && onResize != null || resizePixels == null && onResizePixels != null) lastGeometry = null
        input = onInput; resize = onResize; resizePixels = onResizePixels
        localScroll = onScroll; remoteScroll = onRemoteScroll; modifiersConsumed = onModifiersConsumed
        deliverGeometry()
        if (inputChanged) restartInputIfFocused()
    }
    fun setOneShotModifiers(control: Boolean, alt: Boolean, shift: Boolean = false) {
        val next = TerminalKeyModifiers(control, alt, shift)
        // A word already being composed belongs before the shortcut. Do not apply
        // newly armed Ctrl/Alt to its first character or let the IME repeat it.
        val resetEditor = next != oneShot && (control || alt) && connection?.commitBeforeShortcut() == true
        oneShot = next
        if (resetEditor) resetImeEditor()
    }
    fun setWindowFocusGainedListener(listener: (() -> Unit)?) { windowFocused = listener }
    fun setTextSizeChangedListener(listener: ((Float) -> Unit)?) { sizeChanged = listener }
    fun setTerminalTypeface(value: Typeface) { if (typeface != value) { typeface = value; updateFont() } }
    fun setCursorColor(argb: Int) { if (cursorColor != argb) { cursorColor = argb; postInvalidateOnAnimation() } }
    fun applyAppearance(token: String, typeface: Typeface, textSizeSp: Float, cursorArgb: Int,
        cursorShape: CursorShape?, selectionArgb: Int, selectedTextArgb: Int) {
        if (appearance == token) return
        appearance = token
        this.typeface = typeface; fontSizeSp = textSizeSp.coerceIn(8f, 32f)
        cursorColor = cursorArgb; this.cursorShape = cursorShape
        selectionColor = selectionArgb; selectionText = selectedTextArgb
        updateFont(); postInvalidateOnAnimation()
    }
    fun setTextSizeSp(value: Float) {
        if (!value.isFinite()) return
        val next = value.coerceIn(8f, 32f)
        if (abs(fontSizeSp - next) < .01f) return
        fontSizeSp = next; appearance = null
        updateFont(); sizeChanged?.invoke(next)
    }
    private fun updateFont() {
        if (fonts.configure(typeface, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics))) {
            renderer.clear(); clearSelection(); lastGeometry = null
            deliverGeometry(); postInvalidateOnAnimation()
        }
    }
    private fun deliverGeometry() {
        if (width <= 0 || height <= 0 || resize == null && resizePixels == null) return
        val dimensions = listOf((width / fonts.cellWidth).toInt().coerceIn(2, 500),
            (height / fonts.cellHeight).toInt().coerceIn(1, 300), fonts.cellWidth.toInt(), fonts.cellHeight.toInt())
        if (dimensions == lastGeometry) return
        lastGeometry = dimensions
        resize?.invoke(dimensions[0], dimensions[1])
        resizePixels?.invoke(dimensions[0], dimensions[1], dimensions[2], dimensions[3])
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) { pointer.cancel(); renderer.clear(); clearSelection(); deliverGeometry() }
    }

    fun requestTerminalInput(showSoftwareKeyboard: Boolean? = null) {
        requestFocus()
        if (showSoftwareKeyboard ?: shouldRequestSoftwareKeyboard(hasHardwareKeyboard())) {
            post { if (isAttachedToWindow && hasFocus()) context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    fun releaseTerminalInput() {
        pointer.cancel(); connection?.cancel(); connection = null; accent = 0
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }
    private fun hasHardwareKeyboard(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true
    }
    private fun restartInputIfFocused() {
        if (input != null && isAttachedToWindow && hasFocus() && hasWindowFocus()) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0; outAttrs.initialSelEnd = 0
        connection?.cancel()
        return TerminalInputConnection(this).also { connection = it }
    }
    internal val hasShortcutModifiers get() = oneShot.control || oneShot.alt
    internal fun resetImeEditor() {
        connection?.cancel(); connection = null
        restartInputIfFocused()
    }
    internal fun inputText(text: String, consumeOneShot: Boolean = true) {
        if (text.isEmpty()) return
        send(terminalTextInput(text, oneShot, frame?.keyboardEnhancementFlags ?: 0))
        if (consumeOneShot) consumeModifiers()
    }
    internal fun inputKey(code: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isModifierKey(code)) return true
        if (event.isCtrlPressed && event.isShiftPressed && code == KeyEvent.KEYCODE_C && anchor != null) return editAction(android.R.id.copy)
        if (event.isCtrlPressed && event.isShiftPressed && code == KeyEvent.KEYCODE_V || event.isShiftPressed && code == KeyEvent.KEYCODE_INSERT) return pasteFromClipboard()
        val mods = TerminalKeyModifiers(oneShot.control || event.isCtrlPressed, oneShot.alt || event.isAltPressed,
            oneShot.shift || event.isShiftPressed)
        val flags = frame?.keyboardEnhancementFlags ?: 0
        val navigation = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "A"; KeyEvent.KEYCODE_DPAD_DOWN -> "B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "C"; KeyEvent.KEYCODE_DPAD_LEFT -> "D"
            KeyEvent.KEYCODE_MOVE_HOME -> "H"; KeyEvent.KEYCODE_MOVE_END -> "F"
            else -> null
        }
        val numbered = when (code) { KeyEvent.KEYCODE_INSERT -> 2; KeyEvent.KEYCODE_FORWARD_DEL -> 3
            KeyEvent.KEYCODE_PAGE_UP -> 5; KeyEvent.KEYCODE_PAGE_DOWN -> 6; else -> null }
        val text = when (code) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"; KeyEvent.KEYCODE_ESCAPE -> "\u001b"; KeyEvent.KEYCODE_TAB -> "\t"
            else -> null
        }
        val bytes = when {
            navigation != null -> terminalNavigationInput(navigation, mods)
            numbered != null -> ("\u001b[$numbered" + (if (mods.active) ";${mods.parameter}" else "") + "~").toByteArray()
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> terminalFunctionInput(code - KeyEvent.KEYCODE_F1 + 1, mods)
            text != null -> terminalTextInput(text, mods, flags)
            else -> {
                val char = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
                if (char == 0) return false
                if (char and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    accent = char and KeyCharacterMap.COMBINING_ACCENT_MASK
                    return true
                }
                val value = if (accent == 0) String(Character.toChars(char)) else {
                    val previous = accent; accent = 0
                    val combined = KeyEvent.getDeadChar(previous, char)
                    if (combined != 0) String(Character.toChars(combined)) else String(Character.toChars(previous)) + String(Character.toChars(char))
                }
                terminalTextInput(value, mods, flags)
            }
        }
        clearSelection(); send(bytes); consumeModifiers(); return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = inputKey(keyCode, event) || super.onKeyDown(keyCode, event)
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean =
        if (event.isCtrlPressed || event.isAltPressed) inputKey(keyCode, event) || super.onKeyShortcut(keyCode, event)
        else super.onKeyShortcut(keyCode, event)
    private fun consumeModifiers() { oneShot = TerminalKeyModifiers(); modifiersConsumed?.invoke() }
    private fun send(bytes: ByteArray) { if (bytes.isNotEmpty()) input?.invoke(bytes) }

    fun pasteFromClipboard(): Boolean {
        if (!hasFocus()) requestTerminalInput(false)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return false
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        val value = if (frame?.bracketedPasteEnabled == true) "\u001b[200~$normalized\u001b[201~" else normalized
        clearSelection(); send(value.toByteArray()); consumeModifiers(); return true
    }
    fun copyRemoteText(text: String) {
        if (hasFocus()) context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    internal fun editAction(id: Int): Boolean = when (id) {
        android.R.id.paste -> pasteFromClipboard()
        android.R.id.copy -> {
            val bounds = selectionBounds()
            if (bounds != null) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", frame?.selectedText(bounds.first, bounds.second).orEmpty()))
                clearSelection(); true
            } else false
        }
        android.R.id.selectAll -> {
            frame?.let { anchor = TerminalCellPosition(0, 0); extent = TerminalCellPosition(it.rowCount - 1, it.columns - 1); showSelection() }
            true
        }
        else -> false
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean = pointer.touch(event)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = pointer.generic(event) || super.onGenericMotionEvent(event)
    internal fun mouse(button: Int, x: Float, y: Float, meta: Int, release: Boolean = false, motion: Boolean = false) {
        val cell = cell(x, y) ?: return
        TerminalMouse.encode(frame?.mouseFlags ?: 0, button, cell.column + 1, cell.row + 1, release, motion,
            meta and KeyEvent.META_SHIFT_ON != 0, meta and KeyEvent.META_ALT_ON != 0, meta and KeyEvent.META_CTRL_ON != 0,
            (x + 1).toInt().coerceAtLeast(1), (y + 1).toInt().coerceAtLeast(1))?.let(::send)
    }
    internal fun scrollRows(count: Int, x: Float, y: Float, meta: Int) {
        if (count == 0) return
        clearSelection()
        val cell = cell(x, y) ?: return
        val remote = remoteScroll
        if (remote != null || mouseTracking) {
            val bytes = TerminalMouse.encode(if (remote != null) 4 or 16 else frame?.mouseFlags ?: 0,
                if (count < 0) 64 else 65, cell.column + 1, cell.row + 1,
                shift = meta and KeyEvent.META_SHIFT_ON != 0, alt = meta and KeyEvent.META_ALT_ON != 0,
                control = meta and KeyEvent.META_CTRL_ON != 0) ?: return
            val batch = java.io.ByteArrayOutputStream(bytes.size * abs(count).coerceAtMost(32))
            repeat(abs(count).coerceAtMost(32)) { batch.write(bytes) }
            if (remote != null) remote(batch.toByteArray()) else send(batch.toByteArray())
        } else localScroll?.invoke(count)
    }

    private fun cell(x: Float, y: Float): TerminalCellPosition? = frame?.let {
        TerminalCellPosition(floor(y / fonts.cellHeight).toInt().coerceIn(0, it.rowCount - 1),
            floor(x / fonts.cellWidth).toInt().coerceIn(0, it.columns - 1))
    }
    internal fun selectWord(x: Float, y: Float) {
        val snapshot = frame ?: return
        val point = cell(x, y) ?: return
        var start = point.column
        while (start > 0 && snapshot.cellTextAt(TerminalCellPosition(point.row, start)).isEmpty()) start--
        var end = start
        fun word(column: Int) = snapshot.cellTextAt(TerminalCellPosition(point.row, column)).any { it.isLetterOrDigit() || it == '_' }
        if (word(start)) {
            while (start > 0 && word(start - 1)) start--
            while (end + 1 < snapshot.columns && word(end + 1)) end++
        }
        while (end + 1 < snapshot.columns && snapshot.cellTextAt(TerminalCellPosition(point.row, end + 1)).isEmpty()) end++
        anchor = TerminalCellPosition(point.row, start); extent = TerminalCellPosition(point.row, end)
        requestFocus(); showSelection()
    }
    private fun selectionBounds(): Pair<TerminalCellPosition, TerminalCellPosition>? {
        val a = anchor ?: return null; val b = extent ?: return null
        return minOf(a, b) to maxOf(a, b)
    }
    internal fun startSelectionDrag(x: Float, y: Float): Boolean {
        val bounds = selectionBounds() ?: return false
        val radius = 28f * resources.displayMetrics.density
        fun distance(position: TerminalCellPosition, end: Boolean): Float =
            abs(x - (position.column + if (end) 1 else 0) * fonts.cellWidth) +
                abs(y - (position.row + 1) * fonts.cellHeight)
        val first = distance(bounds.first, false); val last = distance(bounds.second, true)
        if (minOf(first, last) > radius * 2) { clearSelection(); return false }
        anchor = bounds.first; extent = bounds.second; dragAnchor = first <= last
        return true
    }
    internal fun moveSelection(x: Float, y: Float) {
        val point = cell(x, y) ?: return
        if (dragAnchor) anchor = point else extent = point
        actionMode?.invalidateContentRect(); postInvalidateOnAnimation()
    }
    internal fun endSelectionDrag() { actionMode?.invalidateContentRect() }
    private fun showSelection() {
        if (actionMode == null) actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                menu.add(0, android.R.id.paste, 1, android.R.string.paste)
                menu.add(0, android.R.id.selectAll, 2, android.R.string.selectAll)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = editAction(item.itemId)
            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionMode === mode) { actionMode = null; anchor = null; extent = null; postInvalidateOnAnimation() }
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val bounds = selectionBounds() ?: return outRect.set(0, 0, width, height)
                outRect.set((bounds.first.column * fonts.cellWidth).toInt(), (bounds.first.row * fonts.cellHeight).toInt(),
                    ((bounds.second.column + 1) * fonts.cellWidth).toInt(), ((bounds.second.row + 1) * fonts.cellHeight).toInt())
            }
        }, ActionMode.TYPE_FLOATING)
        postInvalidateOnAnimation()
    }
    private fun clearSelection() {
        if (anchor == null && actionMode == null) return
        anchor = null; extent = null
        val previous = actionMode; actionMode = null; previous?.finish()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = frame ?: return
        // Compose AndroidView can draw with the parent's clip. drawColor ignores
        // translation, so an unbounded fill would erase the SSH/Herdr header.
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            drawTerminal(canvas, snapshot)
        } finally { canvas.restoreToCount(saved) }
    }
    private fun drawTerminal(canvas: Canvas, snapshot: TerminalRenderSnapshot) {
        images.submit(snapshot.images)
        canvas.drawColor(snapshot.defaultBackgroundArgb)
        images.draw(canvas, snapshot.images, fonts.cellWidth, fonts.cellHeight, Int.MIN_VALUE, -1073741825)
        renderer.backgrounds(canvas, snapshot)
        images.draw(canvas, snapshot.images, fonts.cellWidth, fonts.cellHeight, -1073741824, -1)
        renderer.text(canvas, snapshot)
        images.draw(canvas, snapshot.images, fonts.cellWidth, fonts.cellHeight, 0, Int.MAX_VALUE)
        selectionBounds()?.let { (start, end) ->
            for (row in start.row..end.row) {
                val left = (if (row == start.row) start.column else 0) * fonts.cellWidth
                val right = (if (row == end.row) end.column + 1 else snapshot.columns) * fonts.cellWidth
                val top = row * fonts.cellHeight
                overlay.color = selectionColor
                canvas.drawRect(left, top, right, top + fonts.cellHeight, overlay)
                val saved = canvas.save()
                canvas.clipRect(left, top, right, top + fonts.cellHeight)
                renderer.text(canvas, snapshot, selectionText, row)
                canvas.restoreToCount(saved)
            }
            overlay.color = selectionColor
            val radius = 6f * resources.displayMetrics.density
            canvas.drawCircle(start.column * fonts.cellWidth, ((start.row + 1) * fonts.cellHeight + radius).coerceAtMost(height - radius), radius, overlay)
            canvas.drawCircle((end.column + 1) * fonts.cellWidth, ((end.row + 1) * fonts.cellHeight + radius).coerceAtMost(height - radius), radius, overlay)
        }
        snapshot.cursor?.takeIf { it.visible && anchor == null }?.let { cursor ->
            val left = cursor.column * fonts.cellWidth; val top = cursor.row * fonts.cellHeight
            val shape = cursorShape ?: cursor.shape
            overlay.color = snapshot.cursorColorArgb ?: cursorColor ?: snapshot.defaultForegroundArgb
            val edge = (resources.displayMetrics.density).coerceAtLeast(1f)
            canvas.drawRect(left, if (shape == CursorShape.UNDERLINE) top + fonts.cellHeight - edge else top,
                left + if (shape == CursorShape.BEAM) edge else fonts.cellWidth, top + fonts.cellHeight, overlay)
            if (shape == CursorShape.BLOCK) {
                val saved = canvas.save(); canvas.clipRect(left, top, left + fonts.cellWidth, top + fonts.cellHeight)
                renderer.text(canvas, snapshot, snapshot.defaultBackgroundArgb, cursor.row); canvas.restoreToCount(saved)
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true; info.isScrollable = true
        info.text = frame?.accessibleText().orEmpty()
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_COPY -> editAction(android.R.id.copy)
        AccessibilityNodeInfo.ACTION_PASTE -> pasteFromClipboard()
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
            scrollRows((if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) -1 else 1) * (frame?.rowCount ?: 1), width / 2f, height / 2f, 0); true
        }
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
            fun position(offset: Int): TerminalCellPosition? {
                val snapshot = frame ?: return null
                var remaining = offset.coerceAtLeast(0)
                for (row in 0 until snapshot.rowCount) {
                    for (column in 0 until snapshot.columns) {
                        val count = snapshot.cellTextAt(TerminalCellPosition(row, column)).length
                        if (count > remaining) return TerminalCellPosition(row, column)
                        remaining -= count
                    }
                    if (remaining == 0) return TerminalCellPosition(row, snapshot.columns - 1)
                    remaining--
                }
                return TerminalCellPosition(snapshot.rowCount - 1, snapshot.columns - 1)
            }
            val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) ?: -1
            val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) ?: -1
            if (start < 0 || end <= start) clearSelection() else {
                anchor = position(start); extent = position(end - 1); showSelection()
            }
            true
        }
        else -> super.performAccessibilityAction(action, arguments)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            restartInputIfFocused()
            windowFocused?.invoke()
        } else { pointer.cancel(); connection?.cancel(); connection = null; accent = 0 }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.registerComponentCallbacks(this)
        images.submit(frame?.images.orEmpty())
    }
    override fun onDetachedFromWindow() {
        pointer.cancel(); connection?.cancel(); connection = null; clearSelection()
        context.applicationContext.unregisterComponentCallbacks(this)
        removeCallbacks(accessibilityUpdate); accessibilityPending = false
        clearRenderCaches()
        super.onDetachedFromWindow()
    }
    fun clearRenderCaches() { renderer.clear(); fonts.clearMeasurements(); images.clear(); postInvalidateOnAnimation() }
    /** Allows a controlled cache on/off comparison without replacing the renderer or native core. */
    fun setRenderCachingEnabled(enabled: Boolean) { renderer.cacheEnabled = enabled; postInvalidateOnAnimation() }
    fun renderingStats(): TerminalRenderStats = renderer.stats()
    override fun onLowMemory() = clearRenderCaches()
    override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clearRenderCaches() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); updateFont() }
}
