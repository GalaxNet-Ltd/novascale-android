// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** NovaScale frame v2. Row directory entries identify immutable content across scrolling. */
internal object TerminalFrameParser {
    const val MAGIC = 0x4654564e
    private const val HEADER = 80

    fun parse(bytes: ByteArray, decoder: TerminalSnapshotDecoder): TerminalRenderSnapshot {
        require(bytes.size in HEADER..32 * 1024 * 1024) { "Invalid terminal frame size" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int == MAGIC && input.int == 2) { "Unsupported terminal frame" }
        val revision = input.long
        val base = input.long
        val columns = input.int
        val rows = input.int
        require(revision > 0 && base >= 0 && base <= revision && (base == 0L || base == decoder.frameRevision)) {
            "Terminal frame base is unavailable"
        }
        require(columns in 1..500 && rows in 1..300)
        val cursorX = input.int
        val cursorY = input.int
        val cursorFlags = input.int
        val foreground = input.int
        val background = input.int
        val cursorColor = input.int
        val modes = input.int
        val changedRows = input.int
        val imageOffset = input.int
        require(input.int == 0) { "Unsupported terminal frame flags" }
        val cellWidth = input.int
        val cellHeight = input.int
        require(cellWidth > 0 && cellHeight > 0 && changedRows in 0..rows)
        require(imageOffset in (HEADER + rows * 24)..(bytes.size - 24))
        require(base != 0L || changedRows == rows) { "Full terminal frame is incomplete" }
        val identities = ArrayList<TerminalRowIdentity>(rows)
        val generations = LongArray(rows)
        repeat(rows) { row ->
            val identity = TerminalRowIdentity(input.long, input.long)
            require(identity.first != 0L || identity.second != 0L)
            identities += identity
            generations[row] = input.long
            require(generations[row] in 1..revision)
        }
        require(identities.toSet().size == rows) { "Duplicate terminal row identity" }
        val result = arrayOfNulls<TerminalRowSnapshot>(rows)
        val textDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        repeat(changedRows) {
            require(imageOffset - input.position() >= 8) { "Terminal row is truncated" }
            val rowIndex = input.int
            val length = input.int
            require(rowIndex in 0 until rows && result[rowIndex] == null && length >= columns * 20)
            require(length <= imageOffset - input.position()) { "Terminal row exceeds frame" }
            val end = input.position() + length
            val runs = ArrayList<TerminalGlyphRun>()
            val text = StringBuilder(columns)
            val offsets = IntArray(columns)
            var startColumn = 0
            var startText = 0
            var previousForeground = 0
            var previousBackground = 0
            var previousStyle = 0
            var previousUnderline = 0
            fun finish(column: Int) {
                if (column <= startColumn) return
                runs += TerminalGlyphRun(
                    startColumn, column - startColumn,
                    text.substring(startText).ifEmpty { " " }, previousForeground, previousBackground,
                    bold = previousStyle and 1 != 0, italic = previousStyle and 2 != 0,
                    faint = previousStyle and 4 != 0, underline = previousStyle and 0x700 != 0,
                    strikethrough = previousStyle and 64 != 0, overline = previousStyle and 128 != 0,
                    underlineColorArgb = previousUnderline,
                )
            }
            repeat(columns) { column ->
                require(end - input.position() >= 20) { "Terminal cell is truncated" }
                var fg = input.int
                var bg = input.int
                val flags = input.int
                var underline = input.int
                val count = input.int
                require(count >= 0 && count <= end - input.position()) { "Terminal grapheme exceeds row" }
                val spacer = flags and Int.MIN_VALUE != 0
                require(if (spacer) count == 0 else count > 0)
                require(flags and 0x7ffff800 == 0) { "Unsupported terminal cell attributes" }
                if (flags and 16 != 0) { val swap = fg; fg = bg; bg = swap }
                if (flags and 32 != 0) { fg = bg; underline = bg }
                val style = (flags and 0x7ef) and (if (flags and 32 != 0) 4.inv() else -1)
                if (column > 0 && (fg != previousForeground || bg != previousBackground ||
                        style != previousStyle || underline != previousUnderline)) {
                    finish(column); startColumn = column; startText = text.length
                }
                previousForeground = fg; previousBackground = bg
                previousStyle = style; previousUnderline = underline
                if (count == 1 && bytes[input.position()].toInt() in 0..127) {
                    text.append(input.get().toInt().toChar())
                } else if (count != 0) {
                    val data = input.slice().apply { limit(count) }
                    val decoded = try { textDecoder.decode(data) } catch (error: java.nio.charset.CharacterCodingException) {
                        throw IllegalArgumentException("Invalid terminal UTF-8", error)
                    }
                    text.append(decoded)
                    input.position(input.position() + count)
                }
                offsets[column] = text.length
            }
            finish(columns)
            require(input.position() == end) { "Unexpected terminal row data" }
            result[rowIndex] = TerminalRowSnapshot(rowIndex, runs,
                TerminalCellTextLayout.fromRow(text.toString(), offsets), identities[rowIndex], generations[rowIndex])
        }
        require(input.position() == imageOffset) { "Unexpected terminal frame data" }
        repeat(rows) { row ->
            if (result[row] == null) {
                val cached = decoder.frameRows[identities[row]]
                require(base > 0 && cached != null && cached.contentRevision == generations[row] &&
                    cached.cellText?.columnCount == columns) { "Terminal row base is unavailable" }
                result[row] = if (cached.rowIndex == row) cached else cached.copy(rowIndex = row)
            }
        }
        val cursor = if (cursorFlags and 1 == 0) null else {
            require(cursorX in 0 until columns && cursorY in 0 until rows)
            val shape = when ((cursorFlags ushr 8) and 255) {
                0 -> CursorShape.BEAM
                1, 3 -> CursorShape.BLOCK
                2 -> CursorShape.UNDERLINE
                else -> throw IllegalArgumentException("Invalid terminal cursor shape")
            }
            TerminalCursor(cursorX, cursorY, shape)
        }
        val completeRows = result.map { requireNotNull(it) }
        val frame = TerminalRenderSnapshot(columns, rows, completeRows, cursor, foreground, background,
            modes, decoder.parseImages(input, imageOffset), cursorColorArgb = cursorColor)
        // Commit only after the whole packet, including image assets, is valid.
        decoder.frameRows = completeRows.associateBy { requireNotNull(it.identity) }
        decoder.frameRevision = revision
        return frame
    }
}
