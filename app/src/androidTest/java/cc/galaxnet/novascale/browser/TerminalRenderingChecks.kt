// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.terminal.NovaTerminalView

/** Real Android text/IME and cached-versus-uncached pixels, not mocks of the drawing implementation. */
internal object TerminalRenderingChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        lateinit var view: NovaTerminalView
        val sent = mutableListOf<String>()
        val columns = 48
        fun frame(change: String = "A", color: Int = 0xffeeeeee.toInt()): TerminalRenderSnapshot {
            val rows = (0 until 20).map { row ->
                val cells = MutableList(columns) { " " }
                val label = "row $row ${if (row == 3) change else "A"} "
                label.forEachIndexed { i, char -> cells[i] = char.toString() }
                cells[20] = "界"; cells[21] = ""; cells[22] = "e\u0301"
                cells[23] = "👩\u200d💻"; cells[24] = ""
                TerminalRowSnapshot(row, listOf(TerminalGlyphRun(0, columns, cells.joinToString(""), color,
                    if (row == 3) 0xff203040.toInt() else 0xff101820.toInt(), bold = row % 3 == 0,
                    italic = row % 3 == 1, underline = row % 4 == 0, faint = row % 5 == 0)), TerminalCellTextLayout.fromCells(cells))
            }
            return TerminalRenderSnapshot(columns, 20, rows, TerminalCursor(10, 10), defaultBackgroundArgb = 0xff101820.toInt())
        }
        var snapshot = frame()
        test.runOnMainSync {
            view = NovaTerminalView(activity)
            view.setTextSizeSp(10f)
            view.setTerminalCallbacks({ sent += it.decodeToString() }, null, resizeToken = "first")
            activity.setContentView(view)
            view.layout(0, 0, 640, 480)
            view.submitSnapshot(snapshot)
        }
        test.waitForIdleSync(); SystemClock.sleep(150)
        var focused = false
        repeat(30) {
            test.runOnMainSync { focused = view.hasWindowFocus() }
            if (!focused) SystemClock.sleep(100)
        }
        check(focused) { "Renderer fixture requires the foreground, unlocked test window" }
        fun pixels(cache: Boolean): IntArray {
            lateinit var pixels: IntArray
            test.runOnMainSync {
                view.setRenderCachingEnabled(cache)
                val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                pixels = IntArray(640 * 480)
                bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 480); bitmap.recycle()
            }
            return pixels
        }
        check(pixels(false).contentEquals(pixels(true))) { "Cached initial text differs from uncached rendering" }
        test.runOnMainSync {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val warm = view.renderingStats()
            repeat(30) {
                view.submitSnapshot(frame().copy(cursor = TerminalCursor(it % columns, 10)))
                view.draw(canvas)
            }
            val stable = view.renderingStats()
            check(stable.rowBuilds == warm.rowBuilds && stable.textMeasurements == warm.textMeasurements) { "Cursor-only frames rebuilt unchanged text" }
            snapshot = frame("B")
            view.submitSnapshot(snapshot); view.draw(canvas)
            check(view.renderingStats().rowBuilds == stable.rowBuilds + 1) { "An edited row did not invalidate independently" }
            bitmap.recycle()
        }
        check(pixels(false).contentEquals(pixels(true))) { "Cached edited row differs from uncached rendering" }
        test.runOnMainSync { snapshot = frame("B", 0xff71b8fa.toInt()); view.submitSnapshot(snapshot); view.setTextSizeSp(15f); view.setTerminalTypeface(Typeface.MONOSPACE) }
        check(pixels(false).contentEquals(pixels(true))) { "Color/font invalidation left stale glyphs" }
        for (font in cc.galaxnet.novascale.terminalfeature.TerminalFontOption.entries) {
            test.runOnMainSync { view.setTerminalTypeface(font.typeface(activity)) }
            check(pixels(false).contentEquals(pixels(true))) { "Cached ${font.name} rendering differs after changing fonts" }
        }

        // A real window draw must retain GPU display lists across unchanged frames.
        val identified = frame().let { value -> value.copy(rows = value.rows.map { row ->
            row.copy(identity = TerminalRowIdentity(1, row.rowIndex.toLong() + 1), contentRevision = 1)
        }) }
        test.runOnMainSync { view.setTerminalTypeface(Typeface.MONOSPACE); view.setTextSizeSp(10f); view.submitSnapshot(identified); view.invalidate() }
        SystemClock.sleep(150)
        val hardware = view.renderingStats()
        repeat(5) { test.runOnMainSync { view.invalidate() }; SystemClock.sleep(35) }
        val repeated = view.renderingStats()
        if (Build.VERSION.SDK_INT >= 29) {
            check(hardware.displayListRecords > 0 && repeated.displayListHits > hardware.displayListHits) { "Hardware row caches were not used" }
            check(repeated.displayListRecords == hardware.displayListRecords) { "Unchanged hardware rows were recorded again" }
        }
        // Moving rows retain their identity/content, so scrolling must reuse
        // their GPU commands at the new viewport position.
        val scrolled = identified.copy(rows = (identified.rows.drop(1) + identified.rows.first())
            .mapIndexed { index, row -> row.copy(rowIndex = index) })
        test.runOnMainSync { view.submitSnapshot(scrolled) }
        SystemClock.sleep(150)
        val moved = view.renderingStats()
        check(moved.rowBuilds == repeated.rowBuilds && moved.displayListRecords == repeated.displayListRecords) {
            "Moving native row identities rebuilt unchanged content"
        }
        fun hardwarePixels(cache: Boolean): Bitmap {
            test.runOnMainSync { view.setRenderCachingEnabled(cache); view.invalidate() }
            SystemClock.sleep(100)
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val latch = java.util.concurrent.CountDownLatch(1)
            var status = -1
            test.runOnMainSync {
                val origin = IntArray(2); view.getLocationInWindow(origin)
                android.view.PixelCopy.request(activity.window,
                    android.graphics.Rect(origin[0], origin[1], origin[0] + 640, origin[1] + 480), bitmap,
                    { result -> status = result; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            check(latch.await(3, java.util.concurrent.TimeUnit.SECONDS) && status == android.view.PixelCopy.SUCCESS)
            return bitmap
        }
        val uncachedHardware = hardwarePixels(false)
        val cachedHardware = hardwarePixels(true)
        val beforePixels = IntArray(640 * 480); val afterPixels = IntArray(640 * 480)
        uncachedHardware.getPixels(beforePixels, 0, 640, 0, 0, 640, 480)
        cachedHardware.getPixels(afterPixels, 0, 640, 0, 0, 640, 480)
        var difference = 0L
        for (i in beforePixels.indices) for (shift in listOf(0, 8, 16)) {
            difference += kotlin.math.abs(((beforePixels[i] ushr shift) and 255) - ((afterPixels[i] ushr shift) and 255))
        }
        check(difference.toDouble() / (beforePixels.size * 3) < .5) { "GPU display-list pixels differ from direct Canvas: $difference" }
        java.io.File(activity.cacheDir, "terminal-renderer-hardware.png").outputStream().use {
            cachedHardware.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        uncachedHardware.recycle(); cachedHardware.recycle()
        test.runOnMainSync {
            view.requestFocus()
            val ime = view.onCreateInputConnection(EditorInfo())
            ime.setComposingText("ni", 1); ime.setComposingText("你", 1)
            check(sent.isEmpty()) { "IME preedit leaked to the remote terminal" }
            ime.commitText("你", 1); ime.finishComposingText()
            check(sent == listOf("你")) { "IME commit was lost or duplicated" }
            sent.clear()
            ime.setComposingText("👩\u200d💻", 1)
            ime.deleteSurroundingTextInCodePoints(1, 0)
            check(sent.isEmpty()) { "Local composition deletion became remote backspace" }
            view.setTerminalCallbacks({ sent += it.decodeToString() }, null, resizeToken = "second")
            check(!ime.commitText("stale", 1) && sent.isEmpty()) { "Disposed input connection reached another session" }
        }
        test.waitForIdleSync(); SystemClock.sleep(150)
        test.runOnMainSync {
            // Inspect the connection requested by the real InputMethodManager after rebinding.
            // Creating one manually here would hide a missing restartInput lifecycle call.
            val connectionField = NovaTerminalView::class.java.getDeclaredField("connection").apply { isAccessible = true }
            val restarted = checkNotNull(connectionField.get(view) as? android.view.inputmethod.InputConnection) {
                "InputMethodManager did not reconnect the focused terminal after a session change"
            }
            restarted.commitText("fresh", 1)
            check(sent == listOf("fresh")); sent.clear()
            restarted.setComposingText("pending workspace text", 1)
            view.setTerminalCallbacks({ sent += it.decodeToString() }, null, resizeToken = "second", inputToken = "new workspace")
            check(!restarted.commitText("stale workspace", 1) && sent.isEmpty()) { "Preedit crossed a Herdr target boundary" }
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 20)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 27)
            }
            check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
            check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
            check(view.hasWindowFocus()) { "Test window lost focus before clipboard verification" }
            val clip = checkNotNull(activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip) { "Test clipboard unavailable" }
            val copied = clip.getItemAt(0).text.toString()
            check(copied.startsWith("界e\u0301👩\u200d💻")) { "Selection split a terminal grapheme: $copied" }
            view.clearRenderCaches()
            check(view.renderingStats().cachedRows == 0 && view.renderingStats().measuredTextBytes == 0)
        }
        check(pixels(false).contentEquals(pixels(true))) { "Cache trimming changed rendering" }
        val timing = mutableMapOf<Boolean, Long>()
        test.runOnMainSync {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for (cached in listOf(false, true)) {
                view.setRenderCachingEnabled(cached)
                repeat(10) { view.draw(canvas) }
                val start = System.nanoTime()
                repeat(100) { view.draw(canvas) }
                timing[cached] = (System.nanoTime() - start) / 100
            }
            // Distinct incoming rows must not grow either cache without bound.
            repeat(160) { index -> view.submitSnapshot(frame("entry-$index")); view.draw(canvas) }
            val stats = view.renderingStats()
            check(stats.cachedRows <= 256 && stats.rowCacheBytes <= 4 * 1024 * 1024)
            check(stats.measuredTextEntries <= 2048 && stats.measuredTextBytes <= 256 * 1024)
            bitmap.recycle()
        }
        java.io.File(activity.cacheDir, "terminal-renderer-check.txt").writeText(
            "PASS: cached/uncached pixels, row/font invalidation, GPU reuse, IME composition, selection and bounds\n" +
                "software_canvas_ns_per_frame uncached=${timing[false]} cached=${timing[true]}\n" + view.renderingStats())
    }
}
