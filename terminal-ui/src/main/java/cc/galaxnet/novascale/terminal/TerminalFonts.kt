// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

internal class TerminalFonts {
    private data class MeasureKey(val text: String, val style: Int)
    private data class Measure(val advance: Float, val bytes: Int)
    private val advances = TerminalRenderCache<MeasureKey, Measure>(2048, 256 * 1024, { it.bytes })
    private var face: Typeface? = null
    private var size = 0f
    var measurements = 0L
        private set
    var cellWidth = 1f
        private set
    var cellHeight = 1f
        private set
    var baseline = 1f
        private set
    private val styles = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG) }

    fun configure(typeface: Typeface, pixels: Float): Boolean {
        if (face == typeface && size == pixels) return false
        face = typeface; size = pixels
        styles.forEachIndexed { style, paint ->
            paint.typeface = Typeface.create(typeface, style)
            paint.textSize = pixels
        }
        val normal = styles[0]
        val metrics = normal.fontMetrics
        cellWidth = ceil(normal.measureText("M")).coerceAtLeast(1f)
        baseline = ceil(-metrics.ascent)
        cellHeight = ceil(metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
        clearMeasurements()
        return true
    }
    fun paint(style: Int, color: Int): Paint = styles[style].also { it.color = color }
    fun advance(text: String, style: Int): Float {
        val key = MeasureKey(text, style)
        advances[key]?.let { return it.advance }
        measurements++
        val width = styles[style].measureText(text)
        advances.put(key, Measure(width, text.length * 2 + 64))
        return width
    }
    fun clearMeasurements() = advances.clear()
    val cachedMeasurements get() = advances.size
    val measurementBytes get() = advances.weight
}
