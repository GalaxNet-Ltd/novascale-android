// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import cc.galaxnet.novascale.core.GhosttySnapshotParser
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.nativecore.NativeCore
import cc.galaxnet.novascale.terminal.NovaTerminalView

/** Real Android gesture detectors and Canvas view, with captured SSH input bytes. */
internal object TerminalGestureChecks {
    fun run(instrumentation: Instrumentation, activity: Activity) {
        val probe = GhosttySnapshotParser.parse(NativeCore.nativeTerminalProbe())
        check(probe.accessibleText().contains("Ghostty")) { "Native mouse-mode snapshot probe failed" }
        val sent = mutableListOf<String>()
        var scrolled = 0
        lateinit var view: NovaTerminalView
        var down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float, secondX: Float? = null, secondY: Float? = null) {
            val count = if (secondX == null) 1 else 2
            val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                this.x = if (i == 0) x else secondX!!; this.y = if (i == 0) y else secondY!!; pressure = 1f; size = 1f
            } }
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, pointers, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.onTouchEvent(e); e.recycle()
        }
        instrumentation.runOnMainSync {
            view = NovaTerminalView(activity)
            activity.setContentView(view)
            view.layout(0, 0, 800, 600)
            view.submitSnapshot(TerminalRenderSnapshot(80, 24, emptyList(), mouseFlags = 4 or 16))
            view.setTerminalCallbacks(onInput = { sent += it.decodeToString() }, onResize = { _, _ -> }, onScroll = { scrolled += it })
        }
        // Input belongs to the settled viewport; the surface cancels gestures across a resize.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            event(MotionEvent.ACTION_DOWN, 30f, 40f); event(MotionEvent.ACTION_UP, 30f, 40f)
        }
        SystemClock.sleep(400)
        instrumentation.runOnMainSync {
            check(sent.size == 2 && sent[0].startsWith("\u001b[<0;") && sent[0].endsWith("M") && sent[1].endsWith("m")) { "Tap did not emit a balanced SGR click: $sent" }
            sent.clear()
            event(MotionEvent.ACTION_DOWN, 30f, 40f)
            event(MotionEvent.ACTION_MOVE, 30f, 160f)
            event(MotionEvent.ACTION_UP, 30f, 160f)
            check(sent.isNotEmpty() && sent.all { it.startsWith("\u001b[<64;") }) { "Swipe must scroll, never select: $sent" }
            sent.clear()
            event(MotionEvent.ACTION_DOWN, 30f, 40f)
        }
        SystemClock.sleep(650)
        instrumentation.runOnMainSync {
            event(MotionEvent.ACTION_MOVE, 100f, 100f)
            event(MotionEvent.ACTION_MOVE, 160f, 140f)
            event(MotionEvent.ACTION_CANCEL, 160f, 140f)
            check(sent.first().startsWith("\u001b[<0;") && sent.any { it.startsWith("\u001b[<32;") } && sent.last().endsWith("m")) { "Drag/cancel failed: $sent" }
            sent.clear()
            event(MotionEvent.ACTION_DOWN, 30f, 40f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30f, 40f, 130f, 40f)
            event(MotionEvent.ACTION_MOVE, 30f, 160f, 130f, 160f)
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30f, 160f, 130f, 160f)
            event(MotionEvent.ACTION_UP, 30f, 160f)
            check(sent.first().startsWith("\u001b[<2;") && sent.last().startsWith("\u001b[<2;") && sent.last().endsWith("m")) { "Two-finger right-button hold/release failed: $sent" }
            check(scrolled == 0) { "Mouse mode changed local scrollback" }
            sent.clear()
            event(MotionEvent.ACTION_DOWN, 30f, 40f)
        }
        SystemClock.sleep(650)
        instrumentation.runOnMainSync {
            event(MotionEvent.ACTION_MOVE, 100f, 100f)
            sent.clear()
            view.submitSnapshot(TerminalRenderSnapshot(80, 24, emptyList()))
            event(MotionEvent.ACTION_UP, 100f, 100f)
            check(sent.isEmpty()) { "Mode reset leaked an old mouse release to the shell" }
            scrolled = 0
            event(MotionEvent.ACTION_DOWN, 30f, 40f)
            event(MotionEvent.ACTION_MOVE, 30f, 160f)
            event(MotionEvent.ACTION_UP, 30f, 160f)
            check(sent.isEmpty() && scrolled != 0) { "Normal shell must retain local scrolling" }
            sent.clear()
            view.requestFocus()
            view.copyRemoteText("copied from terminal\nsecond line")
            view.setOneShotModifiers(control = true, alt = true)
            check(view.pasteFromClipboard()) { "Clipboard paste returned empty" }
            check(sent.single() == "copied from terminal\rsecond line") { "Paste was modified by Ctrl/Alt" }
            sent.clear()
            view.submitSnapshot(TerminalRenderSnapshot(80, 24, emptyList(), mouseFlags = 256))
            check(view.pasteFromClipboard())
            check(sent.single() == "\u001b[200~copied from terminal\rsecond line\u001b[201~") { "Bracketed paste missing" }
            view.releaseTerminalInput()
        }
    }
}
