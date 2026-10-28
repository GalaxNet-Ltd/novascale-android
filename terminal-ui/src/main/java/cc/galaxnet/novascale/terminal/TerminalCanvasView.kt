/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Canvas metrics, fixed-cell drawing, resize calculation, gesture handling,
 * and IME patterns are adapted from Termux terminal-view at the commit recorded
 * in third_party/termux/README.md. NovaScale consumes Ghostty snapshots and
 * does not import the Termux terminal emulator.
 */
package cc.galaxnet.novascale.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import cc.galaxnet.novascale.core.CursorShape
import cc.galaxnet.novascale.core.TerminalCellPosition
import cc.galaxnet.novascale.core.TerminalGlyphRun
import cc.galaxnet.novascale.core.TerminalKeyModifiers
import cc.galaxnet.novascale.core.terminalTextInput
import cc.galaxnet.novascale.core.terminalNavigationInput
import cc.galaxnet.novascale.core.terminalFunctionInput
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

class TerminalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = spToPx(DEFAULT_TEXT_SIZE_SP)
    }
    private var textSizeSp = DEFAULT_TEXT_SIZE_SP
    private var terminalTypeface: Typeface = Typeface.MONOSPACE
    private var cursorColorArgb: Int? = null
    private var cursorShapeOverride: CursorShape? = null
    private var selectionBackgroundArgb = DEFAULT_SELECTION_BACKGROUND
    private var selectedTextArgb = DEFAULT_SELECTED_TEXT
    private var appearanceToken: String? = null
    private var snapshot: TerminalRenderSnapshot? = null
    private var selection: TerminalSelection? = null
    private var selectionActionMode: ActionMode? = null
    private var draggedSelectionEndpoint: SelectionEndpoint? = null
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 1f
    private var resizeToken: Any? = null
    private var lastColumns = 0
    private var lastRows = 0
    private val scrollAccumulator = TerminalScrollAccumulator()
    private var combiningAccent = 0
    private var inputListener: ((ByteArray) -> Unit)? = null
    private val imageRenderer = TerminalImageRenderer(this)
    private var resizePixelsListener: ((Int, Int, Int, Int) -> Unit)? = null
    private var resizeListener: ((columns: Int, rows: Int) -> Unit)? = null
    private var scrollListener: ((rows: Int) -> Unit)? = null
    private var modifiersConsumedListener: (() -> Unit)? = null
    private var windowFocusGainedListener: (() -> Unit)? = null
    private var textSizeChangedListener: ((Float) -> Unit)? = null
    private var oneShotControl = false
    private var oneShotAlt = false
    private var oneShotShift = false
    private var mouseButton: Int? = null
    private var mouseX = 0f
    private var mouseY = 0f
    private var mouseMeta = 0
    private var lastMouseCell: Pair<Int, Int>? = null
    private var multiTouch = false
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var pinching = false
    private val mouseEnabled get() = TerminalMouse.enabled(snapshot?.mouseFlags ?: 0)

    private fun reportMouse(button: Int, x: Float, y: Float, meta: Int = 0,
        release: Boolean = false, motion: Boolean = false) {
        val frame = snapshot ?: return
        val px = (x - paddingLeft).coerceIn(0f, frame.columns * cellWidth - 1f)
        val py = (y - paddingTop).coerceIn(0f, frame.rowCount * cellHeight - 1f)
        val cell = Pair((px / cellWidth).toInt() + 1, (py / cellHeight).toInt() + 1)
        if (motion && frame.mouseFlags and 128 == 0 && lastMouseCell == cell) return
        val bytes = TerminalMouse.encode(frame.mouseFlags, button, cell.first, cell.second, release, motion,
            meta and KeyEvent.META_SHIFT_ON != 0, meta and KeyEvent.META_ALT_ON != 0,
            meta and KeyEvent.META_CTRL_ON != 0, px.toInt() + 1, py.toInt() + 1) ?: return
        lastMouseCell = cell
        mouseX = x; mouseY = y; mouseMeta = meta
        emit(bytes)
    }

    private fun releaseMouse() {
        mouseButton?.let { reportMouse(it, mouseX, mouseY, mouseMeta, release = true) }
        mouseButton = null; lastMouseCell = null
    }

    private fun scrollAt(rows: Int, event: MotionEvent) {
        if (mouseEnabled && event.metaState and KeyEvent.META_SHIFT_ON == 0 && selection == null) {
            repeat(kotlin.math.abs(rows).coerceAtMost(100)) {
                reportMouse(if (rows < 0) 64 else 65, event.x, event.y, event.metaState)
            }
        } else if (rows != 0) scrollListener?.invoke(rows)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                releaseMouse()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setTextSizeSp((textSizeSp * detector.scaleFactor).coerceIn(8f, 40f))
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                textSizeChangedListener?.invoke(textSizeSp)
            }
        },
    ).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                scrollAccumulator.reset()
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (mouseEnabled && event.metaState and KeyEvent.META_SHIFT_ON == 0) {
                    reportMouse(0, event.x, event.y, event.metaState)
                    reportMouse(0, event.x, event.y, event.metaState, release = true)
                }
                requestTerminalInput()
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (mouseEnabled && current.metaState and KeyEvent.META_SHIFT_ON == 0 && mouseButton != null) {
                    reportMouse(mouseButton!!, current.x, current.y, current.metaState, motion = true)
                } else {
                    val rows = scrollAccumulator.consume(distanceY, cellHeight)
                    if (rows != 0) scrollAt(rows, current)
                }
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                releaseMouse()
                beginSelection(event.x, event.y)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (mouseEnabled && event.metaState and KeyEvent.META_SHIFT_ON == 0) {
                    mouseButton = 0
                    reportMouse(0, event.x, event.y, event.metaState)
                } else beginSelection(event.x, event.y)
            }
        },
    )

    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, ACTION_COPY, 0, context.getString(android.R.string.copy))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, ACTION_SELECT_ALL, 1, context.getString(android.R.string.selectAll))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(ACTION_COPY)?.isEnabled = selectedText().isNotEmpty()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            ACTION_COPY -> {
                if (copySelectionToClipboard()) mode.finish()
                true
            }

            ACTION_SELECT_ALL -> {
                val frame = snapshot ?: return false
                selection = TerminalSelection(
                    start = TerminalCellPosition(0, 0),
                    end = TerminalCellPosition(frame.rowCount - 1, frame.columns - 1),
                )
                draggedSelectionEndpoint = SelectionEndpoint.END
                mode.invalidate()
                mode.invalidateContentRect()
                invalidate()
                true
            }

            else -> false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionActionMode === mode) selectionActionMode = null
            selection = null
            draggedSelectionEndpoint = null
            invalidate()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            selectionContentRect(outRect)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateFontMetrics()
    }

    fun submitSnapshot(value: TerminalRenderSnapshot) {
        if (((snapshot?.mouseFlags ?: 0) and 255) != (value.mouseFlags and 255)) {
            // The application has already changed modes; never send an old-mode release
            // after it exits to a shell. Its mode reset ends the old pointer sequence.
            mouseButton = null; lastMouseCell = null
        } else if (snapshot?.columns != value.columns || snapshot?.rowCount != value.rowCount) {
            releaseMouse()
        }
        snapshot = value
        imageRenderer.submit(value.images)
        selection = selection?.clampedTo(value)
        selectionActionMode?.invalidate()
        selectionActionMode?.invalidateContentRect()
        contentDescription = value.accessibleText()
        postInvalidateOnAnimation()
    }

    fun setTerminalCallbacks(
        onInput: ((ByteArray) -> Unit)?,
        onResize: ((columns: Int, rows: Int) -> Unit)?,
        onScroll: ((rows: Int) -> Unit)? = null,
        onModifiersConsumed: (() -> Unit)? = null,
        onResizePixels: ((Int, Int, Int, Int) -> Unit)? = null,
        resizeToken: Any? = null,
    ) {
        inputListener = onInput
        resizeListener = onResize
        resizePixelsListener = onResizePixels
        scrollListener = onScroll
        modifiersConsumedListener = onModifiersConsumed
        val newSession = this.resizeToken != resizeToken
        this.resizeToken = resizeToken
        notifySizeIfChanged(force = newSession)
    }

    fun setOneShotModifiers(control: Boolean, alt: Boolean, shift: Boolean = false) {
        oneShotControl = control
        oneShotAlt = alt
        oneShotShift = shift
    }

    fun setWindowFocusGainedListener(listener: (() -> Unit)?) {
        windowFocusGainedListener = listener
        if (hasWindowFocus()) listener?.invoke()
    }

    fun setTextSizeChangedListener(listener: ((Float) -> Unit)?) {
        textSizeChangedListener = listener
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) windowFocusGainedListener?.invoke() else releaseMouse()
    }

    /**
     * Keeps this View as the single IME editor even when a Compose accessory
     * button was tapped. A physical keyboard keeps the terminal focused but
     * does not summon the software keyboard; the accessory remains in layout.
     */
    fun requestTerminalInput(
        showSoftwareKeyboard: Boolean = shouldRequestSoftwareKeyboard(hasExternalHardwareKeyboard()),
    ): Boolean {
        if (!requestFocus()) return false
        if (showSoftwareKeyboard) {
            post {
                if (hasWindowFocus()) {
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(
                        this,
                        InputMethodManager.SHOW_IMPLICIT,
                    )
                }
            }
        }
        return true
    }

    /** Relinquishes the IME editor before Compose navigates away. */
    fun releaseTerminalInput() {
        clearSelection()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    fun setTerminalTypeface(value: Typeface) {
        if (terminalTypeface == value) return
        terminalTypeface = value
        paint.typeface = value
        updateFontMetrics()
        requestLayout()
        notifySizeIfChanged(force = true)
        invalidate()
    }

    fun setCursorColor(argb: Int) {
        if (cursorColorArgb == argb) return
        cursorColorArgb = argb
        invalidate()
    }

    fun applyAppearance(
        token: String,
        typeface: Typeface,
        textSizeSp: Float,
        cursorArgb: Int,
        cursorShape: CursorShape?,
        selectionArgb: Int,
        selectedTextArgb: Int,
    ) {
        if (appearanceToken == token) return
        appearanceToken = token
        setTerminalTypeface(typeface)
        setTextSizeSp(textSizeSp)
        setCursorColor(cursorArgb)
        cursorShapeOverride = cursorShape
        selectionBackgroundArgb = selectionArgb
        this.selectedTextArgb = selectedTextArgb
        invalidate()
    }

    fun setTextSizeSp(value: Float) {
        require(value in 8f..40f)
        if (textSizeSp == value) return
        textSizeSp = value
        paint.textSize = spToPx(value)
        updateFontMetrics()
        requestLayout()
        notifySizeIfChanged(force = true)
        invalidate()
    }

    fun pasteFromClipboard(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return false
        consumeOneShotModifiers()
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        val paste = if (snapshot?.bracketedPasteEnabled == true) "\u001b[200~$normalized\u001b[201~" else normalized
        emit(paste.toByteArray(Charsets.UTF_8))
        return true
    }

    fun copyRemoteText(text: String) {
        if (text.isEmpty() || text.length > 1024 * 1024) return
        val clip = ClipData.newPlainText("Terminal", text).apply {
            description.extras = PersistableBundle().apply { putBoolean(CLIPBOARD_IS_SENSITIVE_KEY, true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(attributes: EditorInfo): InputConnection {
        attributes.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        attributes.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) emitText(text)
                editable?.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                val content: Editable = editable ?: return super.finishComposingText()
                if (content.isNotEmpty()) emitText(content)
                content.clear()
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { emitText("\u007f") }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                emitText("\r")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                this@TerminalCanvasView.dispatchKeyEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        specialKey(keyCode, event)?.let {
            emit(it)
            if (oneShotModifiers().active) consumeOneShotModifiers()
            return true
        }
        val meta = event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_ALT_RIGHT_ON)
        var codepoint = event.getUnicodeChar(meta)
        if (codepoint == 0) return super.onKeyDown(keyCode, event)
        if (codepoint and KeyCharacterMap.COMBINING_ACCENT != 0) {
            combiningAccent = codepoint and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (combiningAccent != 0) {
            codepoint = KeyCharacterMap.getDeadChar(combiningAccent, codepoint).takeIf { it != 0 } ?: codepoint
            combiningAccent = 0
        }
        val usesOneShotModifier = oneShotModifiers().active
        val modifiers = TerminalKeyModifiers(
            control = event.isCtrlPressed || oneShotControl,
            alt = oneShotAlt || (event.isAltPressed && event.metaState and KeyEvent.META_ALT_RIGHT_ON == 0),
            shift = event.isShiftPressed || oneShotShift,
        )
        emit(terminalTextInput(String(Character.toChars(codepoint)), modifiers, snapshot?.keyboardEnhancementFlags ?: 0))
        if (usesOneShotModifier) consumeOneShotModifiers()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL) && mouseButton != null) {
            mouseX = event.x; mouseY = event.y; mouseMeta = event.metaState
            releaseMouse(); return true
        }
        if (selection != null) return handleSelectionTouch(event)
        if (!multiTouch && mouseButton == 0 && !event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            reportMouse(0, event.x, event.y, event.metaState, motion = true)
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && mouseEnabled && event.metaState and KeyEvent.META_SHIFT_ON == 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    requestFocus()
                    mouseButton = when {
                        event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 2
                        event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> 1
                        else -> 0
                    }
                    reportMouse(mouseButton!!, event.x, event.y, event.metaState)
                }
                MotionEvent.ACTION_MOVE -> reportMouse(mouseButton ?: 3, event.x, event.y, event.metaState, motion = true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseMouse()
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false; pinching = false
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            releaseMouse()
            scrollAccumulator.reset()
            if (!multiTouch) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestureDetector.onTouchEvent(cancel); cancel.recycle()
                multiStartX = (event.getX(0) + event.getX(1)) / 2
                multiStartY = (event.getY(0) + event.getY(1)) / 2
            }
            multiTouch = true
            if (event.pointerCount == 2 && mouseEnabled) {
                mouseButton = 2
                reportMouse(2, multiStartX, multiStartY, event.metaState)
            }
        }
        scaleDetector.onTouchEvent(event)
        if (multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 2 && !pinching) {
                val x = (event.getX(0) + event.getX(1)) / 2
                val y = (event.getY(0) + event.getY(1)) / 2
                if (mouseButton == 2) reportMouse(2, x, y, event.metaState, motion = true)
                else if (!mouseEnabled) {
                    val rows = scrollAccumulator.consume(multiStartY - y, cellHeight)
                    if (rows != 0) scrollListener?.invoke(rows)
                }
                multiStartX = x; multiStartY = y
            }
            if (event.actionMasked in listOf(MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) releaseMouse()
        } else if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mouseX = event.x; mouseY = event.y; mouseMeta = event.metaState
            releaseMouse(); scrollAccumulator.reset()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            scrollAt(terminalWheelRows(event.getAxisValue(MotionEvent.AXIS_VSCROLL)), event)
            return true
        }
        if (event.action == MotionEvent.ACTION_HOVER_MOVE && mouseEnabled && event.metaState and KeyEvent.META_SHIFT_ON == 0 && selection == null) {
            reportMouse(3, event.x, event.y, event.metaState, motion = true)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        notifySizeIfChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        snapshot?.let { imageRenderer.submit(it.images) }
    }

    override fun onDetachedFromWindow() {
        releaseMouse()
        releaseTerminalInput()
        imageRenderer.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = snapshot
        val checkpoint = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = frame?.defaultBackgroundArgb ?: DEFAULT_BACKGROUND
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (frame != null) {
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            val graphicsClip = canvas.save()
            canvas.clipRect(0f, 0f, frame.columns * cellWidth, frame.rowCount * cellHeight)
            imageRenderer.draw(canvas, frame.images, cellWidth, cellHeight, Int.MIN_VALUE, -1073741825)
            for (row in frame.rows) {
                val top = row.rowIndex * cellHeight
                for (run in row.runs) drawRunBackground(canvas, frame, run, top)
            }
            imageRenderer.draw(canvas, frame.images, cellWidth, cellHeight, -1073741824, -1)
            for (row in frame.rows) {
                val top = row.rowIndex * cellHeight
                for (run in row.runs) drawRunText(canvas, run, top, top + baselineOffset)
            }
            imageRenderer.draw(canvas, frame.images, cellWidth, cellHeight, 0, Int.MAX_VALUE)
            canvas.restoreToCount(graphicsClip)
            drawSelection(canvas, frame)
            frame.cursor?.takeIf { it.visible && selection == null }?.let { cursor ->
                val left = cursor.column * cellWidth
                val top = cursor.row * cellHeight
                paint.color = cursorColorArgb ?: frame.defaultForegroundArgb
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, resources.displayMetrics.density)
                when (cursorShapeOverride ?: cursor.shape) {
                    CursorShape.BLOCK -> canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
                    CursorShape.BEAM -> canvas.drawLine(left, top, left, top + cellHeight, paint)
                    CursorShape.UNDERLINE -> canvas.drawLine(
                        left,
                        top + cellHeight - 1,
                        left + cellWidth,
                        top + cellHeight - 1,
                        paint,
                    )
                }
                paint.style = Paint.Style.FILL
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawRunBackground(
        canvas: Canvas,
        frame: TerminalRenderSnapshot,
        run: TerminalGlyphRun,
        top: Float,
    ) {
        val left = run.startColumn * cellWidth
        val expectedWidth = run.columnCount * cellWidth
        if (run.backgroundArgb != frame.defaultBackgroundArgb) {
            paint.color = run.backgroundArgb
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + expectedWidth, top + cellHeight, paint)
        }

    }

    private fun drawRunText(
        canvas: Canvas,
        run: TerminalGlyphRun,
        top: Float,
        baseline: Float,
        foregroundOverride: Int? = null,
    ) {
        val left = run.startColumn * cellWidth
        val expectedWidth = run.columnCount * cellWidth
        paint.typeface = terminalTypeface
        paint.isFakeBoldText = run.bold
        paint.textSkewX = if (run.italic) -0.35f else 0f
        paint.isUnderlineText = run.underline
        paint.color = foregroundOverride ?: if (run.faint) dim(run.foregroundArgb) else run.foregroundArgb
        val measured = paint.measureText(run.text)
        if (measured > 0f && abs(measured - expectedWidth) > cellWidth * 0.01f) {
            canvas.save()
            canvas.clipRect(left, top, left + expectedWidth, top + cellHeight)
            canvas.translate(left, 0f)
            canvas.scale(expectedWidth / measured, 1f)
            canvas.drawText(run.text, 0f, baseline, paint)
            canvas.restore()
        } else {
            canvas.drawText(run.text, left, baseline, paint)
        }
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.isUnderlineText = false
    }

    private fun drawSelection(canvas: Canvas, frame: TerminalRenderSnapshot) {
        val current = selection ?: return
        val (start, end) = current.normalized()
        for (rowIndex in start.row..end.row) {
            val startColumn = if (rowIndex == start.row) start.column else 0
            val endColumn = if (rowIndex == end.row) end.column else frame.columns - 1
            val left = startColumn * cellWidth
            val right = (endColumn + 1) * cellWidth
            val top = rowIndex * cellHeight
            val bottom = top + cellHeight
            paint.style = Paint.Style.FILL
            paint.color = selectionBackgroundArgb
            canvas.drawRect(left, top, right, bottom, paint)

            val row = frame.rows.firstOrNull { it.rowIndex == rowIndex } ?: continue
            val checkpoint = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            val baseline = top + baselineOffset
            row.runs.forEach { run ->
                drawRunText(canvas, run, top, baseline, foregroundOverride = selectedTextArgb)
            }
            canvas.restoreToCount(checkpoint)
        }
        drawSelectionHandles(canvas, start, end)
    }

    private fun drawSelectionHandles(
        canvas: Canvas,
        start: TerminalCellPosition,
        end: TerminalCellPosition,
    ) {
        val radius = max(dpToPx(SELECTION_HANDLE_RADIUS_DP), cellWidth * 0.28f)
        paint.style = Paint.Style.FILL
        paint.color = selectionBackgroundArgb
        canvas.drawCircle(
            (start.column * cellWidth).coerceAtLeast(radius),
            ((start.row + 1) * cellHeight).coerceAtMost(height - paddingTop - radius),
            radius,
            paint,
        )
        canvas.drawCircle(
            ((end.column + 1) * cellWidth).coerceAtMost(width - paddingLeft - radius),
            ((end.row + 1) * cellHeight).coerceAtMost(height - paddingTop - radius),
            radius,
            paint,
        )
    }

    private fun beginSelection(x: Float, y: Float) {
        val frame = snapshot ?: return
        var position = terminalCellAt(x, y, frame) ?: return
        if (!frame.isSelectableCell(position)) {
            position = nearestSelectableCell(frame, position) ?: return
        }
        var startColumn = position.column
        var endColumn = position.column
        while (
            startColumn > 0 &&
            frame.isSelectableCell(TerminalCellPosition(position.row, startColumn - 1))
        ) {
            startColumn -= 1
        }
        while (
            endColumn < frame.columns - 1 &&
            frame.isSelectableCell(TerminalCellPosition(position.row, endColumn + 1))
        ) {
            endColumn += 1
        }
        selection = TerminalSelection(
            start = TerminalCellPosition(position.row, startColumn),
            end = TerminalCellPosition(position.row, endColumn),
        )
        draggedSelectionEndpoint = SelectionEndpoint.END
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        selectionActionMode?.finish()
        selectionActionMode = startActionMode(selectionActionModeCallback, ActionMode.TYPE_FLOATING)
        invalidate()
    }

    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        val frame = snapshot ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = terminalCellAt(event.x, event.y, frame) ?: return true
                draggedSelectionEndpoint = selection?.closestEndpoint(position, frame.columns)
                selectionActionMode?.hide(SELECTION_TOOLBAR_HIDE_MILLIS)
            }

            MotionEvent.ACTION_MOVE -> {
                val position = terminalCellAt(event.x, event.y, frame) ?: return true
                val current = selection ?: return true
                selection = when (draggedSelectionEndpoint) {
                    SelectionEndpoint.START -> current.copy(start = position)
                    SelectionEndpoint.END, null -> current.copy(end = position)
                }
                selectionActionMode?.invalidate()
                selectionActionMode?.invalidateContentRect()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedSelectionEndpoint = null
                selectionActionMode?.invalidate()
                selectionActionMode?.invalidateContentRect()
            }
        }
        return true
    }

    private fun terminalCellAt(
        x: Float,
        y: Float,
        frame: TerminalRenderSnapshot,
    ): TerminalCellPosition? {
        if (!x.isFinite() || !y.isFinite()) return null
        return TerminalCellPosition(
            row = ((y - paddingTop) / cellHeight).toInt().coerceIn(0, frame.rowCount - 1),
            column = ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, frame.columns - 1),
        )
    }

    private fun nearestSelectableCell(
        frame: TerminalRenderSnapshot,
        origin: TerminalCellPosition,
    ): TerminalCellPosition? {
        for (distance in 1 until frame.columns) {
            val left = origin.column - distance
            if (left >= 0) {
                val candidate = TerminalCellPosition(origin.row, left)
                if (frame.isSelectableCell(candidate)) return candidate
            }
            val right = origin.column + distance
            if (right < frame.columns) {
                val candidate = TerminalCellPosition(origin.row, right)
                if (frame.isSelectableCell(candidate)) return candidate
            }
        }
        return null
    }

    private fun selectedText(): String {
        val frame = snapshot ?: return ""
        val current = selection ?: return ""
        return frame.selectedText(current.start, current.end)
    }

    private fun copySelectionToClipboard(): Boolean {
        val text = selectedText()
        if (text.isEmpty()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText("Terminal selection", text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(CLIPBOARD_IS_SENSITIVE_KEY, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        announceForAccessibility("Copied selected terminal text")
        return true
    }

    private fun selectionContentRect(outRect: Rect) {
        val frame = snapshot
        val current = selection
        if (frame == null || current == null) {
            outRect.set(0, 0, width, height)
            return
        }
        val (start, end) = current.normalized()
        val left = if (start.row == end.row) start.column * cellWidth else 0f
        val right = if (start.row == end.row) (end.column + 1) * cellWidth else frame.columns * cellWidth
        outRect.set(
            (paddingLeft + left).toInt().coerceIn(0, width),
            (paddingTop + start.row * cellHeight).toInt().coerceIn(0, height),
            (paddingLeft + right).toInt().coerceIn(0, width),
            (paddingTop + (end.row + 1) * cellHeight).toInt().coerceIn(0, height),
        )
    }

    private fun clearSelection() {
        if (selection == null && selectionActionMode == null) return
        val mode = selectionActionMode
        selectionActionMode = null
        if (mode != null) mode.finish()
        selection = null
        draggedSelectionEndpoint = null
        invalidate()
    }

    private fun updateFontMetrics() {
        cellWidth = max(1f, paint.measureText("X"))
        val metrics = paint.fontMetrics
        cellHeight = max(1f, ceil(paint.fontSpacing.toDouble()).toFloat())
        baselineOffset = cellHeight - max(0f, metrics.descent)
    }

    private fun notifySizeIfChanged(force: Boolean = false) {
        if (width <= paddingLeft + paddingRight || height <= paddingTop + paddingBottom) return
        val columns = max(20, ((width - paddingLeft - paddingRight) / cellWidth).toInt())
        val rows = max(5, ((height - paddingTop - paddingBottom) / cellHeight).toInt())
        if (force || columns != lastColumns || rows != lastRows) {
            lastColumns = columns
            lastRows = rows
            val pixels = resizePixelsListener
            if (pixels != null) pixels(columns, rows, ceil(cellWidth.toDouble()).toInt(), ceil(cellHeight.toDouble()).toInt())
            else resizeListener?.invoke(columns, rows)
        }
    }

    private fun specialKey(keyCode: Int, event: KeyEvent): ByteArray? {
        val modifiers = TerminalKeyModifiers(
            control = event.isCtrlPressed || oneShotControl,
            alt = event.isAltPressed || oneShotAlt,
            shift = event.isShiftPressed || oneShotShift,
        )
        val suffix = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D"
            KeyEvent.KEYCODE_MOVE_HOME -> "H"
            KeyEvent.KEYCODE_MOVE_END -> "F"
            else -> null
        }
        if (suffix != null) return terminalNavigationInput(suffix, modifiers)
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return terminalFunctionInput(keyCode - KeyEvent.KEYCODE_F1 + 1, modifiers)
        }
        val tildeCode = when (keyCode) {
            KeyEvent.KEYCODE_INSERT -> 2
            KeyEvent.KEYCODE_FORWARD_DEL -> 3
            KeyEvent.KEYCODE_PAGE_UP -> 5
            KeyEvent.KEYCODE_PAGE_DOWN -> 6
            else -> null
        }
        if (tildeCode != null) {
            val parameter = if (modifiers.active) ";${modifiers.parameter}" else ""
            return "\u001b[$tildeCode$parameter~".toByteArray()
        }
        val text = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> "\u001b"
            else -> return null
        }
        return terminalTextInput(text, modifiers, snapshot?.keyboardEnhancementFlags ?: 0)
    }

    private fun oneShotModifiers() = TerminalKeyModifiers(oneShotControl, oneShotAlt, oneShotShift)

    private fun emitText(value: CharSequence) {
        if (value.isEmpty()) return
        val modifiers = oneShotModifiers()
        emit(terminalTextInput(value.toString(), modifiers, snapshot?.keyboardEnhancementFlags ?: 0))
        if (modifiers.active) consumeOneShotModifiers()
    }

    private fun consumeOneShotModifiers() {
        oneShotControl = false
        oneShotAlt = false
        oneShotShift = false
        modifiersConsumedListener?.invoke()
    }

    private fun emit(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            clearSelection()
            inputListener?.invoke(bytes)
        }
    }

    private fun hasExternalHardwareKeyboard(): Boolean {
        val inputManager = context.getSystemService(InputManager::class.java) ?: return false
        return inputManager.inputDeviceIds
            .asSequence()
            .mapNotNull(inputManager::getInputDevice)
            .any { device ->
                // API 28 cannot distinguish external keyboards; any physical one suffices.
                !device.isVirtual &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || device.isExternal) &&
                    device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                    device.supportsSource(InputDevice.SOURCE_KEYBOARD)
            }
    }

    private fun dim(color: Int): Int = Color.rgb(
        Color.red(color) * 2 / 3,
        Color.green(color) * 2 / 3,
        Color.blue(color) * 2 / 3,
    )

    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private fun dpToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    companion object {
        private const val DEFAULT_TEXT_SIZE_SP = 16f
        private const val DEFAULT_BACKGROUND = 0xFF0D1117.toInt()
        private const val DEFAULT_SELECTION_BACKGROUND = 0xFF264F78.toInt()
        private const val DEFAULT_SELECTED_TEXT = 0xFFFFFFFF.toInt()
        private const val ACTION_COPY = 1
        private const val ACTION_SELECT_ALL = 2
        private const val SELECTION_TOOLBAR_HIDE_MILLIS = 350L
        private const val SELECTION_HANDLE_RADIUS_DP = 4.5f
        private const val CLIPBOARD_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
    }
}

private enum class SelectionEndpoint {
    START,
    END,
}

private data class TerminalSelection(
    val start: TerminalCellPosition,
    val end: TerminalCellPosition,
) {
    fun normalized(): Pair<TerminalCellPosition, TerminalCellPosition> =
        if (start <= end) start to end else end to start

    fun clampedTo(frame: TerminalRenderSnapshot) = copy(
        start = start.clampedTo(frame),
        end = end.clampedTo(frame),
    )

    fun closestEndpoint(position: TerminalCellPosition, columns: Int): SelectionEndpoint {
        val targetIndex = position.row * columns + position.column
        val startDistance = abs(targetIndex - (start.row * columns + start.column))
        val endDistance = abs(targetIndex - (end.row * columns + end.column))
        return if (startDistance <= endDistance) SelectionEndpoint.START else SelectionEndpoint.END
    }
}

private fun TerminalCellPosition.clampedTo(frame: TerminalRenderSnapshot) = TerminalCellPosition(
    row = row.coerceIn(0, frame.rowCount - 1),
    column = column.coerceIn(0, frame.columns - 1),
)

private fun TerminalRenderSnapshot.isSelectableCell(position: TerminalCellPosition): Boolean {
    val value = cellTextAt(position)
    if (value.any { !it.isWhitespace() }) return true
    // Ghostty's second cell for a wide glyph is deliberately empty. Treat that spacer as part of
    // the word so the highlight covers the glyph's full terminal width.
    return value.isEmpty() && position.column > 0 &&
        cellTextAt(position.copy(column = position.column - 1)).any { !it.isWhitespace() }
}

internal fun shouldRequestSoftwareKeyboard(hasExternalHardwareKeyboard: Boolean): Boolean =
    !hasExternalHardwareKeyboard

/** Accumulates sub-cell drag distances so slow gestures still scroll precisely. */
internal class TerminalScrollAccumulator {
    private var remainder = 0f

    fun consume(pixelDelta: Float, cellHeight: Float): Int {
        if (!pixelDelta.isFinite() || !cellHeight.isFinite() || cellHeight <= 0f) return 0
        remainder += pixelDelta
        val rows = (remainder / cellHeight).toInt()
        remainder -= rows * cellHeight
        return rows
    }

    fun reset() {
        remainder = 0f
    }
}

/** Android reports wheel-up as positive; Ghostty defines viewport-up as negative. */
internal fun terminalWheelRows(axisValue: Float): Int =
    if (axisValue == 0f || !axisValue.isFinite()) 0 else -axisValue.sign.toInt() * 3
