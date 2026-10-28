/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cc.galaxnet.novascale.core

/** Immutable, batched terminal state consumed by any Android renderer. */
data class TerminalRenderSnapshot(
    val columns: Int,
    val rowCount: Int,
    val rows: List<TerminalRowSnapshot>,
    val cursor: TerminalCursor? = null,
    val defaultForegroundArgb: Int = 0xFFE6EDF3.toInt(),
    val defaultBackgroundArgb: Int = 0xFF0D1117.toInt(),
    val mouseFlags: Int = 0,
    val images: List<TerminalImagePlacement> = emptyList(),
) {
    /** Mode bits from Ghostty's active screen; no escape-sequence parsing in the UI. */
    val bracketedPasteEnabled: Boolean get() = mouseFlags and 256 != 0
    val keyboardEnhancementFlags: Int get() = (mouseFlags ushr 9) and 31

    init {
        require(columns > 0)
        require(rowCount > 0)
        require(rows.map { it.rowIndex }.distinct().size == rows.size) {
            "Each snapshot row must be unique"
        }
        require(rows.all { it.rowIndex in 0 until rowCount })
        require(cursor == null || (cursor.column in 0 until columns && cursor.row in 0 until rowCount))
    }

    fun accessibleText(): String = rows
        .sortedBy(TerminalRowSnapshot::rowIndex)
        .joinToString(separator = "\n") { row ->
            row.runs.sortedBy(TerminalGlyphRun::startColumn).joinToString(separator = "") { it.text }
        }

    /** Returns the visible terminal text inside an inclusive, cell-based selection. */
    fun selectedText(anchor: TerminalCellPosition, extent: TerminalCellPosition): String {
        val clampedAnchor = anchor.clampedTo(this)
        val clampedExtent = extent.clampedTo(this)
        val start = minOf(clampedAnchor, clampedExtent)
        val end = maxOf(clampedAnchor, clampedExtent)
        val rowsByIndex = rows.associateBy(TerminalRowSnapshot::rowIndex)
        return (start.row..end.row).joinToString(separator = "\n") { rowIndex ->
            val fromColumn = if (rowIndex == start.row) start.column else 0
            val toColumn = if (rowIndex == end.row) end.column else columns - 1
            rowsByIndex[rowIndex]
                ?.textBetween(fromColumn, toColumn, columns)
                ?.trimEnd()
                .orEmpty()
        }.trimEnd()
    }

    fun cellTextAt(position: TerminalCellPosition): String {
        if (position.row !in 0 until rowCount || position.column !in 0 until columns) return ""
        return rows.firstOrNull { it.rowIndex == position.row }
            ?.textAt(position.column, columns)
            .orEmpty()
    }
}

data class TerminalRowSnapshot(
    val rowIndex: Int,
    val runs: List<TerminalGlyphRun>,
    /** Compact text-to-cell map. Wide-character spacer cells have a zero-length text range. */
    val cellText: TerminalCellTextLayout? = null,
) {
    internal fun textAt(column: Int, columns: Int): String {
        cellText?.takeIf { it.columnCount == columns }?.let { return it.textAt(column) }
        return fallbackCells(columns)[column]
    }

    internal fun textBetween(fromColumn: Int, toColumn: Int, columns: Int): String {
        cellText?.takeIf { it.columnCount == columns }?.let {
            return it.textBetween(fromColumn, toColumn)
        }
        return fallbackCells(columns).subList(fromColumn, toColumn + 1).joinToString(separator = "")
    }

    private fun fallbackCells(columns: Int): List<String> {
        // Snapshots constructed by older callers do not have cell data. Keeping the entire run
        // at its first column still preserves complete-row copying; native snapshots always use
        // the exact per-cell representation produced by [GhosttySnapshotParser].
        return MutableList(columns) { " " }.also { cells ->
            runs.forEach { run ->
                if (run.startColumn in cells.indices) cells[run.startColumn] = run.text
                for (column in run.startColumn + 1 until run.startColumn + run.columnCount) {
                    if (column in cells.indices) cells[column] = ""
                }
            }
        }
    }
}

/** A row string plus UTF-16 end offsets, avoiding one allocated String per terminal cell. */
class TerminalCellTextLayout private constructor(
    private val text: String,
    private val cellEndOffsets: IntArray,
) {
    val columnCount: Int
        get() = cellEndOffsets.size

    init {
        require(cellEndOffsets.isNotEmpty())
        require(cellEndOffsets.last() == text.length)
        require(cellEndOffsets.asSequence().zipWithNext().all { (left, right) -> left <= right })
    }

    fun textAt(column: Int): String {
        require(column in cellEndOffsets.indices)
        val start = if (column == 0) 0 else cellEndOffsets[column - 1]
        return text.substring(start, cellEndOffsets[column])
    }

    fun textBetween(fromColumn: Int, toColumn: Int): String {
        require(fromColumn in cellEndOffsets.indices && toColumn in fromColumn until columnCount)
        val start = if (fromColumn == 0) 0 else cellEndOffsets[fromColumn - 1]
        return text.substring(start, cellEndOffsets[toColumn])
    }

    companion object {
        fun fromCells(cells: List<String>): TerminalCellTextLayout {
            require(cells.isNotEmpty())
            val text = StringBuilder()
            val endOffsets = IntArray(cells.size)
            cells.forEachIndexed { index, cell ->
                text.append(cell)
                endOffsets[index] = text.length
            }
            return TerminalCellTextLayout(text.toString(), endOffsets)
        }

        internal fun fromRow(text: String, cellEndOffsets: IntArray) =
            TerminalCellTextLayout(text, cellEndOffsets)
    }
}

data class TerminalCellPosition(
    val row: Int,
    val column: Int,
) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int =
        compareValuesBy(this, other, TerminalCellPosition::row, TerminalCellPosition::column)

    internal fun clampedTo(snapshot: TerminalRenderSnapshot) = TerminalCellPosition(
        row = row.coerceIn(0, snapshot.rowCount - 1),
        column = column.coerceIn(0, snapshot.columns - 1),
    )
}

/** A shaped text run; [columnCount] is independent of UTF-16 text length. */
data class TerminalGlyphRun(
    val startColumn: Int,
    val columnCount: Int,
    val text: String,
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val faint: Boolean = false,
) {
    init {
        require(startColumn >= 0)
        require(columnCount > 0)
        require(text.isNotEmpty())
    }
}

data class TerminalCursor(
    val column: Int,
    val row: Int,
    val shape: CursorShape = CursorShape.BLOCK,
    val visible: Boolean = true,
)

enum class CursorShape {
    BLOCK,
    BEAM,
    UNDERLINE,
}

/** Parser for NovaScale's versioned libghostty-vt render snapshot ABI. */
object GhosttySnapshotParser {
    private const val HEADER_BYTES = 14 * Int.SIZE_BYTES
    private const val CELL_BYTES = 11
    private const val FLAG_BOLD = 1 shl 0
    private const val FLAG_ITALIC = 1 shl 1
    private const val FLAG_UNDERLINE = 1 shl 2
    private const val FLAG_INVERSE = 1 shl 3
    private const val FLAG_FAINT = 1 shl 5
    private const val FLAG_GRAPHEME = 1 shl 6
    private const val FLAG_SPACER = 1 shl 7

    fun parse(bytes: ByteArray, decoder: TerminalSnapshotDecoder = TerminalSnapshotDecoder()): TerminalRenderSnapshot {
        require(bytes.size >= HEADER_BYTES) { "Terminal snapshot header is truncated" }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val columns = buffer.getInt(0)
        val rows = buffer.getInt(4)
        require(columns in 1..500 && rows in 1..300) { "Terminal snapshot dimensions are invalid" }
        val cellsEnd = HEADER_BYTES.toLong() + columns.toLong() * rows * CELL_BYTES
        require(cellsEnd <= bytes.size) { "Terminal snapshot cells are truncated" }
        val extrasOffset = buffer.getInt(44)
        require(extrasOffset == 0 || extrasOffset == cellsEnd.toInt()) { "Invalid extras boundary" }
        val extras = parseExtras(buffer, extrasOffset, columns * rows)
        val graphicsOffset = if (extrasOffset == 0) cellsEnd.toInt() else
            extrasOffset + 4 + extras.values.sumOf { 8 + it.size * 4 }
        val images = if (buffer.getInt(52) and (1 shl 14) != 0) decoder.parseImages(buffer, graphicsOffset) else emptyList()
        val defaultBackground = argb(buffer.getInt(20), buffer.getInt(24), buffer.getInt(28))
        val defaultForeground = argb(buffer.getInt(32), buffer.getInt(36), buffer.getInt(40))

        val renderedRows: List<TerminalRowSnapshot> = buildList(capacity = rows) {
            for (row in 0 until rows) {
                val runs = mutableListOf<TerminalGlyphRun>()
                val rowText = StringBuilder(columns)
                val cellEndOffsets = IntArray(columns)
                var current: MutableRun? = null
                for (column in 0 until columns) {
                    val index = row * columns + column
                    val offset = HEADER_BYTES + index * CELL_BYTES
                    val codepoint = buffer.getInt(offset)
                    val flags = bytes[offset + 10].toInt() and 0xff
                    var foreground = argb(
                        bytes[offset + 4].toInt() and 0xff,
                        bytes[offset + 5].toInt() and 0xff,
                        bytes[offset + 6].toInt() and 0xff,
                    )
                    var background = argb(
                        bytes[offset + 7].toInt() and 0xff,
                        bytes[offset + 8].toInt() and 0xff,
                        bytes[offset + 9].toInt() and 0xff,
                    )
                    if (flags and FLAG_INVERSE != 0) {
                        val swap = foreground
                        foreground = background
                        background = swap
                    }
                    val style = CellStyle(
                        foreground = foreground,
                        background = background,
                        bold = flags and FLAG_BOLD != 0,
                        italic = flags and FLAG_ITALIC != 0,
                        underline = flags and FLAG_UNDERLINE != 0,
                        faint = flags and FLAG_FAINT != 0,
                    )
                    if (current?.style != style) {
                        current?.toSnapshot()?.let(runs::add)
                        current = MutableRun(column, style)
                    }
                    val run = checkNotNull(current)
                    run.columnCount += 1
                    if (flags and FLAG_SPACER == 0) {
                        val resolvedCodepoint = codepoint.takeIf(::isScalarValue) ?: 0xfffd
                        rowText.appendCodePoint(resolvedCodepoint)
                        run.text.appendCodePoint(resolvedCodepoint)
                        if (flags and FLAG_GRAPHEME != 0) {
                            extras[index]?.forEach { extra ->
                                val resolvedExtra = extra.takeIf(::isScalarValue) ?: 0xfffd
                                rowText.appendCodePoint(resolvedExtra)
                                run.text.appendCodePoint(resolvedExtra)
                            }
                        }
                    }
                    cellEndOffsets[column] = rowText.length
                }
                current?.toSnapshot()?.let(runs::add)
                add(
                    TerminalRowSnapshot(
                        rowIndex = row,
                        runs = runs,
                        cellText = TerminalCellTextLayout.fromRow(rowText.toString(), cellEndOffsets),
                    ),
                )
            }
        }

        val cursorX = buffer.getInt(8)
        val cursorY = buffer.getInt(12)
        val cursorShape = when (buffer.getInt(48)) {
            1 -> CursorShape.BEAM
            2 -> CursorShape.UNDERLINE
            else -> CursorShape.BLOCK
        }
        val cursor = if (
            buffer.getInt(16) != 0 && cursorX in 0 until columns && cursorY in 0 until rows
        ) TerminalCursor(cursorX, cursorY, cursorShape) else null
        return TerminalRenderSnapshot(
            columns = columns,
            rowCount = rows,
            rows = renderedRows,
            cursor = cursor,
            defaultForegroundArgb = defaultForeground,
            defaultBackgroundArgb = defaultBackground,
            mouseFlags = buffer.getInt(52),
            images = images,
        )
    }

    private fun parseExtras(
        buffer: java.nio.ByteBuffer,
        offset: Int,
        cellCount: Int,
    ): Map<Int, IntArray> {
        if (offset == 0) return emptyMap()
        require(offset >= HEADER_BYTES && offset + Int.SIZE_BYTES <= buffer.limit()) {
            "Terminal snapshot extras offset is invalid"
        }
        var cursor = offset
        val recordCount = buffer.getInt(cursor)
        cursor += Int.SIZE_BYTES
        require(recordCount in 0..cellCount) { "Terminal snapshot extras count is invalid" }
        return buildMap(recordCount) {
            repeat(recordCount) {
                require(cursor + 2 * Int.SIZE_BYTES <= buffer.limit()) { "Terminal grapheme record is truncated" }
                val cell = buffer.getInt(cursor)
                val count = buffer.getInt(cursor + Int.SIZE_BYTES)
                cursor += 2 * Int.SIZE_BYTES
                require(cell in 0 until cellCount && count in 1..128) { "Terminal grapheme record is invalid" }
                require(cursor.toLong() + count.toLong() * Int.SIZE_BYTES <= buffer.limit()) {
                    "Terminal grapheme data is truncated"
                }
                require(cell !in this) { "Duplicate grapheme record" }
                put(cell, IntArray(count) { buffer.getInt(cursor + it * Int.SIZE_BYTES) })
                cursor += count * Int.SIZE_BYTES
            }
        }
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or ((red and 0xff) shl 16) or ((green and 0xff) shl 8) or (blue and 0xff)

    private fun isScalarValue(value: Int): Boolean =
        value in 0..0x10ffff && value !in 0xd800..0xdfff

    private data class CellStyle(
        val foreground: Int,
        val background: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val faint: Boolean,
    )

    private class MutableRun(
        private val startColumn: Int,
        val style: CellStyle,
    ) {
        var columnCount: Int = 0
        val text = StringBuilder()

        fun toSnapshot(): TerminalGlyphRun = TerminalGlyphRun(
            startColumn = startColumn,
            columnCount = columnCount,
            text = text.takeIf { it.isNotEmpty() }?.toString() ?: " ",
            foregroundArgb = style.foreground,
            backgroundArgb = style.background,
            bold = style.bold,
            italic = style.italic,
            underline = style.underline,
            faint = style.faint,
        )
    }
}
