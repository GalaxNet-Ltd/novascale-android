// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
package cc.galaxnet.novascale.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import cc.galaxnet.novascale.core.TerminalImage
import cc.galaxnet.novascale.core.TerminalImagePlacement
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Decodes immutable RGBA assets off the main thread; placements remain renderer-neutral. */
internal class TerminalImageRenderer(private val view: View) {
    private var generation = 0
    private var pending: Future<*>? = null
    private var assets: Set<TerminalImage> = emptySet()
    private var bitmaps: Map<TerminalImage, Bitmap> = emptyMap()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()

    fun submit(placements: List<TerminalImagePlacement>) {
        val next = placements.mapTo(mutableSetOf()) { it.image }
        if (next == assets) return
        pending?.cancel(true)
        assets = next
        val version = ++generation
        val ready = bitmaps.filterKeys { it in next }
        bitmaps = ready
        if (next.isEmpty()) return
        pending = workers.submit imageWork@{
            val decoded = ready.toMutableMap()
            for (asset in next) {
                if (Thread.currentThread().isInterrupted) return@imageWork
                if (asset in decoded) continue
                val pixels = IntArray(asset.width * asset.height)
                for (i in pixels.indices) {
                    if (i % 4096 == 0 && Thread.currentThread().isInterrupted) return@imageWork
                    val offset = i * 4
                    val bytes = asset.rgba
                    pixels[i] = ((bytes[offset + 3].toInt() and 255) shl 24) or
                        ((bytes[offset].toInt() and 255) shl 16) or
                        ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
                }
                decoded[asset] = Bitmap.createBitmap(pixels, asset.width, asset.height, Bitmap.Config.ARGB_8888)
            }
            view.post {
                if (version == generation) {
                    bitmaps = decoded
                    view.postInvalidateOnAnimation()
                }
            }
        }
    }

    fun draw(canvas: Canvas, placements: List<TerminalImagePlacement>, cellWidth: Float, cellHeight: Float, minZ: Int, maxZ: Int) {
        for (p in placements) {
            if (p.z !in minZ..maxZ) continue
            val bitmap = bitmaps[p.image] ?: continue
            source.set(p.sourceX, p.sourceY, p.sourceX + p.sourceWidth, p.sourceY + p.sourceHeight)
            destination.set(p.left * cellWidth, p.top * cellHeight, (p.left + p.width) * cellWidth, (p.top + p.height) * cellHeight)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
    }

    fun clear() {
        generation++
        pending?.cancel(true)
        pending = null
        assets = emptySet()
        // Canvas/render-thread work can still reference a Bitmap. Let Android reclaim it.
        bitmaps = emptyMap()
    }

    private companion object {
        val workers = Executors.newFixedThreadPool(2) { job -> Thread(job, "NovaTerminalImages").apply { isDaemon = true } }
    }
}
