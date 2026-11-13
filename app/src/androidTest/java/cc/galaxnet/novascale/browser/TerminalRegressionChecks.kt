// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.terminal.NovaTerminalView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises embedding and IME composition, rather than only full-window draws and commitText. */
internal object TerminalRegressionChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val failures = mutableListOf<String>()
        fun verify(name: String, block: () -> Unit) {
            test.runOnMainSync { runCatching(block).exceptionOrNull()?.let { failures += "$name: ${it.message}" } }
        }
        verify("viewport clipping") {
            val terminal = NovaTerminalView(activity).apply {
                layout(0, 0, 160, 100)
                submitSnapshot(TerminalRenderSnapshot(80, 50, emptyList(), defaultBackgroundArgb = 0xff101820.toInt()))
            }
            val bitmap = Bitmap.createBitmap(240, 220, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val surrounding = 0xffcc3366.toInt()
                canvas.drawColor(surrounding)
                canvas.translate(40f, 60f)
                val clipBefore = canvas.clipBounds
                terminal.draw(canvas)
                check(bitmap.getPixel(80, 30) == surrounding) { "Terminal background erased the header" }
                check(bitmap.getPixel(80, 190) == surrounding) { "Terminal drawing escaped below its viewport" }
                check(bitmap.getPixel(10, 80) == surrounding && bitmap.getPixel(230, 80) == surrounding)
                check(canvas.clipBounds == clipBefore) { "Terminal clip leaked into later sibling drawing" }
            } finally { bitmap.recycle() }
        }
        for ((name, control, alt, input, expected) in listOf(
            Shortcut("Ctrl+R", true, false, "r", "\u0012"),
            Shortcut("Ctrl+C", true, false, "c", "\u0003"),
            Shortcut("Alt+B", false, true, "b", "\u001bb"),
        )) verify("composing $name") {
            val sent = mutableListOf<String>()
            val terminal = NovaTerminalView(activity).apply {
                setTerminalCallbacks({ sent += it.decodeToString() }, null)
                setOneShotModifiers(control, alt)
            }
            val ime = terminal.onCreateInputConnection(EditorInfo())
            ime.setComposingText(input, 1)
            check(sent == listOf(expected)) { "Shortcut was buffered instead of sent immediately" }
            ime.finishComposingText(); ime.commitText(input, 1)
            check(sent == listOf(expected)) { "IME completion duplicated a consumed shortcut" }
            terminal.onCreateInputConnection(EditorInfo()).commitText("next", 1)
            check(sent == listOf(expected, "next")) { "Modifier leaked into later input" }
        }
        verify("hardware Ctrl+R") {
            val sent = mutableListOf<String>()
            val terminal = NovaTerminalView(activity).apply { setTerminalCallbacks({ sent += it.decodeToString() }, null) }
            val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, 0, KeyEvent.META_CTRL_ON)
            check(terminal.dispatchKeyShortcutEvent(event)) { "Focused view did not handle the hardware shortcut" }
            check(sent == listOf("\u0012"))
        }
        verify("pending word before Ctrl+R") {
            val sent = mutableListOf<String>()
            val terminal = NovaTerminalView(activity).apply { setTerminalCallbacks({ sent += it.decodeToString() }, null) }
            val old = terminal.onCreateInputConnection(EditorInfo())
            old.setComposingText("prefix", 1)
            terminal.setOneShotModifiers(true, false)
            check(sent == listOf("prefix")) { "New modifier changed or discarded an existing composed word" }
            check(!old.commitText("prefixr", 1)) { "Old IME composition crossed the shortcut boundary" }
            terminal.onCreateInputConnection(EditorInfo()).setComposingText("r", 1)
            check(sent == listOf("prefix", "\u0012"))
        }
        verify("plain composition and Shift") {
            val sent = mutableListOf<String>()
            val terminal = NovaTerminalView(activity).apply { setTerminalCallbacks({ sent += it.decodeToString() }, null) }
            val ime = terminal.onCreateInputConnection(EditorInfo())
            ime.setComposingText("ni", 1); ime.setComposingText("你", 1)
            check(sent.isEmpty())
            ime.commitText("你", 1); ime.finishComposingText()
            check(sent == listOf("你"))
            terminal.setOneShotModifiers(false, false, true)
            ime.commitText("a", 1); ime.commitText("b", 1)
            check(sent == listOf("你", "A", "b"))
        }

        val headerColor = 0xff7536b4.toInt()
        var header = Rect()
        val systemInput = mutableListOf<String>()
        lateinit var view: NovaTerminalView
        test.runOnMainSync {
            activity.setContent {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(52.dp).background(Color(headerColor)).onGloballyPositioned {
                        val r = it.boundsInWindow()
                        header = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                    }) { Text("SSH / Herdr controls", color = Color.White) }
                    AndroidView(factory = { NovaTerminalView(it).also { view = it; it.setTerminalCallbacks({ bytes -> systemInput += bytes.decodeToString() }, null) } },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        update = { it.submitSnapshot(TerminalRenderSnapshot(80, 50, emptyList(), defaultBackgroundArgb = 0xff101820.toInt())) })
                    Box(Modifier.fillMaxWidth().height(52.dp).background(Color.Green))
                }
            }
        }
        test.waitForIdleSync(); SystemClock.sleep(200)
        for (keyboard in listOf(false, true)) {
            if (keyboard) { test.runOnMainSync { view.requestTerminalInput(true) }; SystemClock.sleep(700) }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            test.runOnMainSync {
                // Sample the right edge, away from the header's text.
                val patch = Rect(header.right - 40, header.top + 8, header.right - 8, header.top + 40)
                PixelCopy.request(activity.window, patch, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
            }
            check(done.await(3, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
            if (bitmap.getPixel(16, 16) != headerColor) failures += "Compose header was painted over (keyboard=$keyboard)"
            bitmap.recycle()
        }
        val connectionField = NovaTerminalView::class.java.getDeclaredField("connection").apply { isAccessible = true }
        verify("focused IME shortcut") {
            val ime = checkNotNull(connectionField.get(view) as? android.view.inputmethod.InputConnection)
            view.setOneShotModifiers(true, false)
            ime.setComposingText("r", 1)
            check(systemInput == listOf("\u0012"))
            ime.commitText("r", 1); ime.finishComposingText()
            check(systemInput == listOf("\u0012"))
        }
        test.waitForIdleSync(); SystemClock.sleep(150)
        verify("IME resumes after shortcut") {
            val ime = checkNotNull(connectionField.get(view) as? android.view.inputmethod.InputConnection)
            ime.commitText("query", 1)
            check(systemInput == listOf("\u0012", "query")) { "Input method failed to continue after Ctrl+R" }
        }
        test.runOnMainSync { view.releaseTerminalInput() }
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }

    private data class Shortcut(val name: String, val control: Boolean, val alt: Boolean, val input: String, val expected: String)
}
