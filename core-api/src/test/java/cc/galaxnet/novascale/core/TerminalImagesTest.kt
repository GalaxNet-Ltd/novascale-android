// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
package cc.galaxnet.novascale.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalImagesTest {
    private fun frame(revision: Long, asset: Boolean = true, sourceWidth: Int = 1, width: Int = 1): ByteArray {
        val bytes = ByteArray(56 + 11 + 4 + 24 + (if (asset) 20 else 0) + 48)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 1); b.putInt(4, 1); b.putInt(44, 67); b.putInt(52, 1 shl 14)
        b.position(67); b.putInt(0)
        b.putInt(0x5846474e); b.putInt(1); b.putLong(revision); b.putInt(if (asset) 1 else 0); b.putInt(1)
        if (asset) { b.putInt(7); b.putInt(width); b.putInt(1); b.putInt(4); b.putInt(-1) }
        b.putInt(7); b.putInt(0)
        b.putFloat(0f); b.putFloat(-0.5f); b.putFloat(2f); b.putFloat(1f)
        b.putInt(0); b.putInt(0); b.putInt(sourceWidth); b.putInt(1); b.putInt(1); b.putInt(0)
        return bytes
    }

    @Test fun cachedFramesReuseAssetsAndReplacementChangesIdentity() {
        val decoder = TerminalSnapshotDecoder()
        val first = decoder.parse(frame(2)).images.single()
        assertEquals(2L, decoder.imageRevision)
        val next = decoder.parse(frame(2, asset = false)).images.single()
        assertSame(first.image, next.image)
        assertEquals(-0.5f, next.top, 0f)
        assertNotSame(first.image, decoder.parse(frame(3)).images.single().image)
    }

    @Test fun invalidAssetsAndPlacementBoundsFailClosedAndResetCache() {
        val decoder = TerminalSnapshotDecoder()
        decoder.parse(frame(2))
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(frame(3, sourceWidth = 2)) }
        assertEquals(0L, decoder.imageRevision)
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(frame(3, asset = false)) }
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(frame(3, width = 4097)) }
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(frame(3).dropLast(1).toByteArray()) }
        val bad = frame(3)
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(71, 123)
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(bad) }
    }
}
