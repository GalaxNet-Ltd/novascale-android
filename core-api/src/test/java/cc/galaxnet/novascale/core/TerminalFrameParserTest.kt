// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalFrameParserTest {
    private data class Row(val id: Long, val revision: Long, val text: String?)
    private fun packet(revision: Long, base: Long, rows: List<Row>, style: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(TerminalFrameParser.MAGIC).putInt(2).putLong(revision).putLong(base)
            .putInt(1).putInt(rows.size).putInt(-1).putInt(-1).putInt(0)
            .putInt(-1).putInt(0xff000000.toInt()).putInt(-1).putInt(0)
            .putInt(rows.count { it.text != null }).putInt(0).putInt(0).putInt(8).putInt(16)
        rows.forEach { b.putLong(1).putLong(it.id).putLong(it.revision) }
        rows.forEachIndexed { index, row -> row.text?.let {
            val text = it.toByteArray()
            b.putInt(index).putInt(20 + text.size).putInt(-1).putInt(0xff000000.toInt())
                .putInt(style).putInt(-1).putInt(text.size).put(text)
        } }
        b.putInt(64, b.position())
        b.putInt(0x5846474e).putInt(1).putLong(1).putInt(0).putInt(0)
        return b.array().copyOf(b.position())
    }

    @Test fun unchangedRowsKeepTheirObjectsAndScrollingKeepsTheirIdentity() {
        val decoder = TerminalSnapshotDecoder()
        val first = decoder.parse(packet(1, 0, listOf(Row(10, 1, "e\u0301"), Row(11, 1, "界"))))
        val next = decoder.parse(packet(2, 1, listOf(Row(10, 1, null), Row(11, 2, "好"))))
        assertSame(first.rows[0], next.rows[0])
        assertEquals("好", next.cellTextAt(TerminalCellPosition(1, 0)))
        val moved = decoder.parse(packet(3, 2, listOf(Row(11, 2, null), Row(12, 3, "!"))))
        assertEquals(next.rows[1].identity, moved.rows[0].identity)
        assertSame(next.rows[1].cellText, moved.rows[0].cellText)
        assertEquals(0, moved.rows[0].rowIndex)
        assertEquals("好\n!", moved.accessibleText())
    }

    @Test fun wrongBaseClearsCachesAndFullFrameRecovers() {
        val decoder = TerminalSnapshotDecoder()
        decoder.parse(packet(1, 0, listOf(Row(10, 1, "a"))))
        assertThrows(IllegalArgumentException::class.java) {
            decoder.parse(packet(3, 2, listOf(Row(10, 1, null))))
        }
        assertEquals(0, decoder.frameRevision)
        assertEquals(0, decoder.imageRevision)
        assertEquals("b", decoder.parse(packet(4, 0, listOf(Row(20, 4, "b")))).accessibleText())
    }

    @Test fun missingOrMismatchedRowCannotBeReused() {
        for (row in listOf(Row(10, 2, null), Row(11, 1, null))) {
            val decoder = TerminalSnapshotDecoder()
            decoder.parse(packet(1, 0, listOf(Row(10, 1, "a"))))
            assertThrows(IllegalArgumentException::class.java) { decoder.parse(packet(2, 1, listOf(row))) }
        }
    }

    @Test fun partialPacketsAndDuplicateIdentitiesAreRejected() {
        val full = packet(1, 0, listOf(Row(10, 1, "👩\u200d💻")))
        for (length in full.indices) {
            assertThrows(IllegalArgumentException::class.java) { TerminalSnapshotDecoder().parse(full.copyOf(length)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalSnapshotDecoder().parse(packet(1, 0, listOf(Row(10, 1, "a"), Row(10, 1, "b"))))
        }
    }

    @Test fun failedImageSectionDoesNotPublishNewRows() {
        val decoder = TerminalSnapshotDecoder()
        decoder.parse(packet(1, 0, listOf(Row(10, 1, "a"))))
        val bad = packet(2, 1, listOf(Row(10, 2, "b"))).apply { this[lastIndex] = 1 }
        assertThrows(IllegalArgumentException::class.java) { decoder.parse(bad) }
        assertTrue(decoder.frameRows.isEmpty())
        assertEquals(0, decoder.frameRevision)
    }

    @Test fun concealAndStyleSurvivePublicApiSerialization() {
        val decoder = TerminalSnapshotDecoder()
        val hidden = decoder.parse(packet(1, 0, listOf(Row(10, 1, "a")), 32 or 4)).rows.single().runs.single()
        assertEquals(hidden.foregroundArgb, hidden.backgroundArgb)
        assertFalse(hidden.faint)
        val styled = decoder.parse(packet(2, 0, listOf(Row(10, 2, "b")), 1 or 2 or 64 or 128 or 256)).rows.single().runs.single()
        assertTrue(styled.bold && styled.italic && styled.underline && styled.strikethrough && styled.overline)
    }
}
