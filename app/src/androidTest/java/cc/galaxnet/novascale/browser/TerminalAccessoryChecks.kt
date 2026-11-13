// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cc.galaxnet.novascale.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.terminal.NovaTerminalView
import cc.galaxnet.novascale.terminalfeature.TerminalAccessoryKeyboard
import cc.galaxnet.novascale.terminalfeature.TerminalChromeColors
import cc.galaxnet.novascale.ui.NovaScaleTheme

/** Production Compose controls and Android input, using synthetic data and no SSH connection. */
internal object TerminalAccessoryChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val accessibility = test.uiAutomation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = accessibility
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val flags = mutableIntStateOf(0)
        val tab = mutableIntStateOf(1)
        val pasteEnabled = mutableStateOf(false)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lateinit var terminal: NovaTerminalView
        test.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(density.density, 1.15f)) {
                    NovaScaleTheme(darkTheme = true) {
                        var control by remember(tab.intValue) { mutableStateOf(false) }
                        var alt by remember(tab.intValue) { mutableStateOf(false) }
                        var shift by remember(tab.intValue) { mutableStateOf(false) }
                        Column(Modifier.width(339.dp).fillMaxHeight().safeDrawingPadding()) {
                            AndroidView(factory = { context ->
                                NovaTerminalView(context).also { terminal = it }
                            }, update = { view ->
                                view.setOneShotModifiers(control, alt, shift)
                                view.setTerminalCallbacks(onInput = { sent += it.decodeToString() }, onResize = null,
                                    onModifiersConsumed = { control = false; alt = false; shift = false })
                                view.submitSnapshot(TerminalRenderSnapshot(80, 24, emptyList(), mouseFlags = flags.intValue shl 9))
                            }, modifier = Modifier.weight(1f).fillMaxWidth())
                            key(tab.intValue) {
                                TerminalAccessoryKeyboard(
                                    colors = TerminalChromeColors(Color(0xFF10151B), Color.Black, Color.White,
                                        Color.LightGray, Color.Gray, Color.Cyan, Color.Green, Color.Red),
                                    controlActive = control, altActive = alt, shiftActive = shift,
                                    bracketedPasteEnabled = pasteEnabled.value, keyboardFlags = flags.intValue,
                                    onDetach = {}, onCloseSession = {},
                                    onModifiersChanged = { c, a, s ->
                                        control = c; alt = a; shift = s
                                        terminal.setOneShotModifiers(c, a, s)
                                    },
                                    onSend = { sent += it.decodeToString() }, onPaste = {},
                                    onKeepTerminalFocus = { terminal.requestTerminalInput(showSoftwareKeyboard = false) },
                                    onCollapseInput = { terminal.releaseTerminalInput() },
                                )
                            }
                        }
                    }
                }
            }
        }
        test.waitForIdleSync()

        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun localized(label: String): String = when (label) {
            "Shift" -> activity.getString(R.string.terminal_shift)
            "Arrow navigation" -> activity.getString(R.string.terminal_navigation)
            "Left arrow" -> activity.getString(R.string.terminal_arrow_left)
            "Insert newline (requires bracketed paste)" -> activity.getString(R.string.terminal_insert_newline)
            else -> label
        }
        fun bounds(label: String): Rect {
            val target = localized(label)
            repeat(40) {
                (find(test.uiAutomation.rootInActiveWindow, target) ?: test.uiAutomation.windows.firstNotNullOfOrNull { find(it.root, target) })?.let { node ->
                    return Rect().also(node::getBoundsInScreen)
                }
                SystemClock.sleep(100)
            }
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "terminal-accessory-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            error("Missing accessory control: $label")
        }
        var downTime = 0L
        fun pointer(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            test.sendPointerSync(event)
            event.recycle()
        }
        fun click(label: String) {
            val rect = bounds(label)
            pointer(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
            pointer(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
            test.waitForIdleSync()
        }
        fun settle() { test.waitForIdleSync(); SystemClock.sleep(150) }

        // Wait for the accessibility tree to replace the preceding Canvas fixture.
        bounds("Shift")
        settle()
        // The complete primary row must fit on the S24's 339 dp viewport at 1.15 font scale.
        val primary = listOf("Esc", "Ctl", "Alt", "Tab", "Arrow navigation", activity.getString(R.string.cd_paste))
        val primaryBounds = primary.map(::bounds)
        val end = bounds(activity.getString(R.string.cd_hide_keyboard)).right
        check(primaryBounds.all { it.left >= 0 && it.right <= end + 2 }) { "Primary keys overflow the compact viewport" }
        val density = activity.resources.displayMetrics.density
        val detach = bounds(activity.getString(R.string.cd_detach_terminal))
        val paste = bounds(activity.getString(R.string.cd_paste))
        check(detach.exactCenterX() <= 24 * density && end - paste.exactCenterX() <= 24 * density) {
            "Primary row leaves unused space at its outer edges"
        }
        // Accessibility may expand a 36 dp key to a 48 dp hit target. Compare row centers.
        val rowSpacing = bounds("Shift").exactCenterY() - bounds("Esc").exactCenterY()
        check(rowSpacing <= 42 * activity.resources.displayMetrics.density) { "Accessory rows are too far apart: $rowSpacing" }
        // Fixed input keys are accessible while symbols scroll. Newline must fail closed.
        val initialNewline = find(test.uiAutomation.rootInActiveWindow, localized("Insert newline (requires bracketed paste)"))
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "terminal-accessory-initial.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        check(initialNewline?.isEnabled == false) { "Newline must start disabled" }
        click("Shift"); click("Tab")
        check(sent.toList() == listOf("\u001b[Z")) { "Legacy Shift+Tab: $sent" }
        sent.clear()
        test.runOnMainSync { flags.intValue = 1 }
        settle()
        click("Shift"); click("Tab")
        check(sent.toList() == listOf("\u001b[9;2u")) { "Enhanced Shift+Tab: $sent" }
        sent.clear()
        test.runOnMainSync { pasteEnabled.value = true }
        settle()
        click("Insert newline (requires bracketed paste)")
        check(sent.single() == "\u001b[200~\n\u001b[201~") { "Newline submitted the prompt" }
        sent.clear()

        // Tap offers discrete arrows without submitting a navigation key itself.
        click("Arrow navigation")
        check(sent.isEmpty())
        click("Left arrow")
        check(sent.single() == "\u001b[D")
        sent.clear()

        val pad = bounds("Arrow navigation")
        val x = pad.exactCenterX(); val y = pad.exactCenterY()
        val travel = 28f * activity.resources.displayMetrics.density
        click("Shift")
        pointer(MotionEvent.ACTION_DOWN, x, y)
        pointer(MotionEvent.ACTION_MOVE, x + travel, y)
        SystemClock.sleep(200)
        check(sent.single() == "\u001b[1;2C") { "Drag ignored Shift or repeated too early: $sent" }
        SystemClock.sleep(650)
        check(sent.size >= 2 && sent.drop(1).all { it == "\u001b[C" }) { "Drag repeat/one-shot state: $sent" }
        pointer(MotionEvent.ACTION_MOVE, x, y - travel)
        SystemClock.sleep(100)
        check(sent.last() == "\u001b[A") { "Drag did not change direction: $sent" }
        pointer(MotionEvent.ACTION_MOVE, x, y)
        val centeredCount = sent.size
        SystemClock.sleep(750)
        check(sent.size == centeredCount) { "Center dead zone kept repeating" }
        pointer(MotionEvent.ACTION_UP, x, y)
        settle()
        check(sent.size == centeredCount) { "Drag release generated a tap or key" }
        sent.clear()

        // Cancellation and tab disposal must stop repeat jobs.
        pointer(MotionEvent.ACTION_DOWN, x, y)
        pointer(MotionEvent.ACTION_MOVE, x - travel, y)
        pointer(MotionEvent.ACTION_CANCEL, x - travel, y)
        val canceledCount = sent.size
        SystemClock.sleep(750)
        check(sent.size == canceledCount) { "Canceled gesture kept repeating" }
        sent.clear()
        pointer(MotionEvent.ACTION_DOWN, x, y)
        pointer(MotionEvent.ACTION_MOVE, x, y + travel)
        test.runOnMainSync { tab.intValue++ }
        settle()
        val oldTabCount = sent.size
        SystemClock.sleep(750)
        pointer(MotionEvent.ACTION_UP, x, y + travel)
        check(sent.size == oldTabCount) { "Old tab leaked repeat input" }
        sent.clear()

        pointer(MotionEvent.ACTION_DOWN, x, y)
        pointer(MotionEvent.ACTION_MOVE, x + travel, y)
        settle()
        val backgroundCount = sent.size
        test.runOnMainSync { owner.registry.currentState = Lifecycle.State.CREATED }
        SystemClock.sleep(750)
        check(sent.size == backgroundCount) { "Suspended lifecycle kept repeating" }
        test.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
        pointer(MotionEvent.ACTION_UP, x + travel, y)
        sent.clear()

        // One-shot Shift reaches IME text, editor actions and hardware navigation too.
        test.runOnMainSync {
            val input = terminal.onCreateInputConnection(EditorInfo())
            terminal.setOneShotModifiers(false, false, true)
            input.commitText("abc", 1)
            input.commitText("d", 1)
            terminal.setOneShotModifiers(false, false, true)
            input.performEditorAction(EditorInfo.IME_ACTION_NONE)
            terminal.setOneShotModifiers(false, false, true)
            terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        }
        check(sent.toList() == listOf("Abc", "d", "\u001b[13;2u", "\u001b[1;2D", "\u001b[D")) { "Android input modifier paths: $sent" }
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "terminal-accessory-check.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
