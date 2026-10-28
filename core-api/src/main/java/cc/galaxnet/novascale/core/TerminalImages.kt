// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
package cc.galaxnet.novascale.core

import java.nio.ByteBuffer

class TerminalImage(val id: Long, val width: Int, val height: Int, val rgba: ByteArray)

data class TerminalImagePlacement(
    val image: TerminalImage,
    val z: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val placementId: Long,
    val internalId: Boolean,
)

/** Session-owned cache. Call only from the session's serialized snapshot consumer. */
class TerminalSnapshotDecoder {
    var imageRevision: Long = 0
        private set
    private var images: Map<Long, TerminalImage> = emptyMap()

    fun parse(bytes: ByteArray): TerminalRenderSnapshot = try {
        GhosttySnapshotParser.parse(bytes, this)
    } catch (error: IllegalArgumentException) {
        // Request complete assets on the next frame after any failed decode.
        imageRevision = 0
        images = emptyMap()
        throw error
    }

    internal fun parseImages(buffer: ByteBuffer, offset: Int): List<TerminalImagePlacement> {
        require(offset >= 56 && buffer.limit() - offset >= 24) { "Graphics header is truncated" }
        buffer.position(offset)
        require(buffer.int == 0x5846474e && buffer.int == 1) { "Unsupported graphics snapshot" }
        val revision = buffer.long
        val count = buffer.int
        val placements = buffer.int
        require(revision > 0 && count in 0..128 && placements in 0..512)
        val assets = if (revision == imageRevision && count == 0) images else buildMap {
            var total = 0L
            repeat(count) {
                require(buffer.remaining() >= 16) { "Image header is truncated" }
                val id = buffer.int.toLong() and 0xffffffffL
                val width = buffer.int
                val height = buffer.int
                val size = buffer.int
                require(width in 1..4096 && height in 1..4096 && size.toLong() == width.toLong() * height * 4)
                total += size
                require(size in 1..8 * 1024 * 1024 && total <= 12 * 1024 * 1024 && size <= buffer.remaining())
                require(id !in this) { "Duplicate image asset" }
                val rgba = ByteArray(size)
                buffer.get(rgba)
                put(id, TerminalImage(id, width, height, rgba))
            }
        }
        require(buffer.remaining().toLong() == placements.toLong() * 48) { "Graphics placement data is truncated" }
        val result = List(placements) {
            val id = buffer.int.toLong() and 0xffffffffL
            val image = requireNotNull(assets[id]) { "Image asset is missing" }
            val z = buffer.int
            val left = buffer.float; val top = buffer.float
            val width = buffer.float; val height = buffer.float
            require(listOf(left, top, width, height).all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000f })
            require(width > 0 && height > 0)
            val x = buffer.int; val y = buffer.int; val sw = buffer.int; val sh = buffer.int
            require(x >= 0 && y >= 0 && sw > 0 && sh > 0 && x.toLong() + sw <= image.width && y.toLong() + sh <= image.height)
            val placementId = buffer.int.toLong() and 0xffffffffL
            val internal = buffer.int
            require(internal in 0..1)
            TerminalImagePlacement(image, z, left, top, width, height, x, y, sw, sh, placementId, internal == 1)
        }.sortedWith(compareBy<TerminalImagePlacement> { it.z }.thenBy { it.image.id }.thenBy { it.placementId }.thenBy { it.internalId })
        imageRevision = revision
        images = assets
        return result
    }
}
