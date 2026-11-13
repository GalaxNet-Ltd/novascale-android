// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderNode
import android.os.Build
import cc.galaxnet.novascale.core.TerminalGlyphRun
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.core.TerminalRowSnapshot
import cc.galaxnet.novascale.core.TerminalRowIdentity
import kotlin.math.ceil

/** Cached layout plus retained GPU commands. Images and transient overlays stay outside row nodes. */
internal class TerminalRowRenderer(val fonts: TerminalFonts) {
    private data class Glyph(val text: String, val column: Int, val columns: Int,
        val style: Int, val foreground: Int, val underline: Boolean, val advance: Float,
        val underlineColor: Int, val strikethrough: Boolean, val overline: Boolean)
    private data class RowKey(val identity: TerminalRowIdentity?, val fallbackIndex: Int)
    private class Row(val source: TerminalRowSnapshot, val columns: Int, val glyphs: List<Glyph>,
        val estimatedBytes: Int) {
        var display: RowDisplay? = null
        var bytes = estimatedBytes
        fun release() { display?.close(); display = null }
    }
    private val rows = TerminalRenderCache<RowKey, Row>(256, 4 * 1024 * 1024, { it.bytes }, { it.release() })
    private val fill = Paint()
    private var rowBuilds = 0L
    private var rowHits = 0L
    private var records = 0L
    private var displayHits = 0L
    var cacheEnabled = true
        set(value) { if (field != value) { field = value; clear() } }

    fun clear() = rows.clear()
    fun stats() = TerminalRenderStats(rowBuilds, rowHits, fonts.measurements, records, displayHits,
        rows.size, rows.weight, fonts.cachedMeasurements, fonts.measurementBytes)

    private fun row(source: TerminalRowSnapshot, columns: Int, hardware: Boolean): Row {
        val key = RowKey(source.identity, if (source.identity == null) source.rowIndex else 0)
        if (cacheEnabled) rows[key]?.let {
            val sameContent = if (source.identity != null) it.source.contentRevision == source.contentRevision
                else it.source.runs == source.runs && it.source.cellText == source.cellText
            if (it.columns == columns && sameContent) {
                rowHits++
                if (hardware && Build.VERSION.SDK_INT >= 29 && it.display?.valid != true) {
                    // Reinsert after accounting for the native display-list memory.
                    rows.remove(key)
                } else return it
            }
        }
        rowBuilds++
        val glyphs = ArrayList<Glyph>()
        fun add(run: TerminalGlyphRun, text: String, column: Int, count: Int) {
            if (text.isEmpty()) return
            val style = (if (run.bold) TypefaceStyle.BOLD else 0) or (if (run.italic) TypefaceStyle.ITALIC else 0)
            val color = if (run.faint) Color.rgb(Color.red(run.foregroundArgb) * 2 / 3,
                Color.green(run.foregroundArgb) * 2 / 3, Color.blue(run.foregroundArgb) * 2 / 3) else run.foregroundArgb
            glyphs += Glyph(text, column, count, style, color, run.underline, fonts.advance(text, style),
                run.underlineColorArgb ?: color, run.strikethrough, run.overline)
        }
        for (run in source.runs) {
            val cells = source.cellText
            if (cells == null || cells.columnCount != columns) {
                add(run, run.text, run.startColumn, run.columnCount)
                continue
            }
            val end = minOf(columns, run.startColumn + run.columnCount)
            var column = run.startColumn
            while (column < end) {
                val text = cells.textAt(column)
                if (text.isEmpty()) { column++; continue }
                var next = column + 1
                // Ordinary ASCII cells share one draw operation; graphemes keep their cell boundaries.
                if (text.length == 1 && text[0] in ' '..'~') {
                    val batch = StringBuilder(text)
                    while (next < end) {
                        val cell = cells.textAt(next)
                        if (cell.length != 1 || cell[0] !in ' '..'~') break
                        batch.append(cell); next++
                    }
                    add(run, batch.toString(), column, next - column)
                } else {
                    while (next < columns && cells.textAt(next).isEmpty()) next++
                    add(run, text, column, next - column)
                }
                column = next
            }
        }
        val bytes = 128 + columns * 4 + source.runs.sumOf { 64 + it.text.length * 4 } +
            glyphs.sumOf { 80 + it.text.length * 2 }
        val result = Row(source, columns, glyphs, bytes)
        if (cacheEnabled && hardware && Build.VERSION.SDK_INT >= 29) {
            result.display = RowDisplay(ceil(columns * fonts.cellWidth).toInt(), ceil(fonts.cellHeight).toInt(),
                { drawBackgrounds(it, result) }, { drawGlyphs(it, result, null) })
            records += 2
            result.bytes += result.display!!.bytes.coerceAtMost(Int.MAX_VALUE.toLong() - result.bytes).toInt()
        }
        if (!cacheEnabled || !rows.put(key, result)) result.release()
        return result
    }

    fun backgrounds(canvas: Canvas, frame: TerminalRenderSnapshot) {
        for (source in frame.rows) {
            val row = row(source, frame.columns, canvas.isHardwareAccelerated)
            val save = canvas.save()
            canvas.translate(0f, source.rowIndex * fonts.cellHeight)
            if (canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 29 && row.display?.valid == true) {
                row.display!!.background(canvas); displayHits++
            } else drawBackgrounds(canvas, row)
            canvas.restoreToCount(save)
        }
    }
    fun text(canvas: Canvas, frame: TerminalRenderSnapshot, overrideColor: Int? = null, onlyRow: Int? = null) {
        for (source in frame.rows) {
            if (onlyRow != null && source.rowIndex != onlyRow) continue
            val row = row(source, frame.columns, canvas.isHardwareAccelerated)
            val save = canvas.save()
            canvas.translate(0f, source.rowIndex * fonts.cellHeight)
            if (overrideColor == null && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 29 && row.display?.valid == true) {
                row.display!!.text(canvas); displayHits++
            } else drawGlyphs(canvas, row, overrideColor)
            canvas.restoreToCount(save)
        }
    }
    private fun drawBackgrounds(canvas: Canvas, row: Row) {
        for (run in row.source.runs) {
            fill.color = run.backgroundArgb
            canvas.drawRect(run.startColumn * fonts.cellWidth, 0f,
                minOf(row.columns, run.startColumn + run.columnCount) * fonts.cellWidth, fonts.cellHeight, fill)
        }
    }
    private fun drawGlyphs(canvas: Canvas, row: Row, color: Int?) {
        for (glyph in row.glyphs) {
            val x = glyph.column * fonts.cellWidth
            val width = glyph.columns * fonts.cellWidth
            val paint = fonts.paint(glyph.style, color ?: glyph.foreground)
            val save = canvas.save()
            canvas.clipRect(x, 0f, x + width, fonts.cellHeight)
            canvas.translate(x, 0f)
            if (glyph.advance > 0f) canvas.scale(width / glyph.advance, 1f)
            canvas.drawText(glyph.text, 0f, fonts.baseline, paint)
            canvas.restoreToCount(save)
            if (glyph.underline) {
                fill.color = color ?: glyph.underlineColor
                canvas.drawRect(x, fonts.cellHeight - 2f, x + width, fonts.cellHeight - 1f, fill)
            }
            if (glyph.strikethrough) {
                fill.color = color ?: glyph.foreground
                canvas.drawRect(x, fonts.cellHeight / 2f, x + width, fonts.cellHeight / 2f + 1f, fill)
            }
            if (glyph.overline) {
                fill.color = color ?: glyph.foreground
                canvas.drawRect(x, 0f, x + width, 1f, fill)
            }
        }
    }
    private object TypefaceStyle { const val BOLD = 1; const val ITALIC = 2 }

    @TargetApi(29)
    private class RowDisplay(width: Int, height: Int, background: (Canvas) -> Unit, text: (Canvas) -> Unit) {
        private val bg = RenderNode("NovaScale row background")
        private val fg = RenderNode("NovaScale row text")
        init {
            for ((node, draw) in listOf(bg to background, fg to text)) {
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(width, height)
                try { draw(recording) } finally { node.endRecording() }
            }
        }
        val valid get() = bg.hasDisplayList() && fg.hasDisplayList()
        val bytes get() = bg.computeApproximateMemoryUsage() + fg.computeApproximateMemoryUsage()
        fun background(canvas: Canvas) = canvas.drawRenderNode(bg)
        fun text(canvas: Canvas) = canvas.drawRenderNode(fg)
        fun close() { bg.discardDisplayList(); fg.discardDisplayList() }
    }
}
