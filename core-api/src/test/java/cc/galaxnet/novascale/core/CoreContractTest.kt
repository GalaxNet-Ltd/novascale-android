package cc.galaxnet.novascale.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreContractTest {
    @Test fun mouseModesCrossSnapshotBoundaryWithoutChangingLayout() {
        val bytes = ByteArray(56 + 11)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 1); buffer.putInt(4, 1)
        assertEquals(0, GhosttySnapshotParser.parse(bytes).mouseFlags)
        buffer.putInt(52, 4 or 16)
        assertEquals(20, GhosttySnapshotParser.parse(bytes).mouseFlags)
    }

    @Test
    fun customControlServerRequiresHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            TailnetConfiguration("default", "/private/state", "novascale", "http://control.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TailnetConfiguration("default", "/private/state", "novascale", "https://user:secret@control.test")
        }
        TailnetConfiguration("custom", "/private/state", "novascale", "https://control.test/headscale")
    }

    @Test
    fun browserProxyContractRequiresExactLoopbackCapability() {
        assertThrows(IllegalArgumentException::class.java) {
            TailnetOrigin("*.tailnet.ts.net", 443)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserProxyEndpoint("id", "http://127.0.0.1:8080")
        }
        BrowserProxyEndpoint("id", "http://127.42.17.9:32000")
    }

    @Test
    fun pingResultKeepsRoutesTypedAndFailuresRedacted() {
        val direct = TailnetPingResult(
            address = "100.64.0.2",
            latencyMilliseconds = 8.25,
            failure = null,
            path = TailnetConnectionPath.DIRECT,
            directEndpoint = "192.0.2.4:41641",
        )
        val timeout = TailnetPingResult(
            address = "100.64.0.3",
            latencyMilliseconds = null,
            failure = TailnetPingFailure.TIMEOUT,
            path = TailnetConnectionPath.DERP_RELAY,
            relayRegion = "FRA",
        )

        assertEquals(true, direct.isReachable)
        assertEquals(false, timeout.isReachable)
        assertThrows(IllegalArgumentException::class.java) {
            direct.copy(path = TailnetConnectionPath.DERP_RELAY)
        }
    }

    @Test
    fun terminalAccessibilityTextPreservesRowOrder() {
        val snapshot = TerminalRenderSnapshot(
            columns = 8,
            rowCount = 2,
            rows = listOf(
                TerminalRowSnapshot(1, listOf(run("world"))),
                TerminalRowSnapshot(0, listOf(run("hello"))),
            ),
        )

        assertEquals("hello\nworld", snapshot.accessibleText())
    }

    @Test
    fun ghosttySnapshotParsesStyledGraphemeCells() {
        val header = 14 * Int.SIZE_BYTES
        val cellSize = 11
        val extras = header + 2 * cellSize
        val bytes = ByteArray(extras + 4 * Int.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 2)
        buffer.putInt(4, 1)
        buffer.putInt(8, 1)
        buffer.putInt(12, 0)
        buffer.putInt(16, 1)
        buffer.putInt(20, 13)
        buffer.putInt(24, 17)
        buffer.putInt(28, 23)
        buffer.putInt(32, 230)
        buffer.putInt(36, 237)
        buffer.putInt(40, 243)
        buffer.putInt(44, extras)
        buffer.putInt(48, 1)
        putCell(buffer, header, 'e'.code, flags = (1 shl 0) or (1 shl 6))
        putCell(buffer, header + cellSize, 'x'.code, flags = 0)
        buffer.putInt(extras, 1)
        buffer.putInt(extras + 4, 0)
        buffer.putInt(extras + 8, 1)
        buffer.putInt(extras + 12, 0x301)

        val snapshot = GhosttySnapshotParser.parse(bytes)

        assertEquals("e\u0301x", snapshot.accessibleText())
        assertEquals(true, snapshot.rows.single().runs.first().bold)
        assertEquals(1, snapshot.cursor?.column)
        assertEquals(CursorShape.BEAM, snapshot.cursor?.shape)
        assertEquals("e\u0301", snapshot.cellTextAt(TerminalCellPosition(0, 0)))
        assertEquals("x", snapshot.cellTextAt(TerminalCellPosition(0, 1)))
    }

    @Test
    fun styledRunsKeepGraphemesAndSpacerCellsAtStyleBoundaries() {
        val header = 14 * Int.SIZE_BYTES
        val cellSize = 11
        val extras = header + 5 * cellSize
        val bytes = ByteArray(extras + 4 * Int.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 5); buffer.putInt(4, 1); buffer.putInt(44, extras)
        putCell(buffer, header, 'A'.code, flags = 1)
        putCell(buffer, header + cellSize, '界'.code, flags = 1 or (1 shl 6))
        putCell(buffer, header + 2 * cellSize, 0, flags = 1 or (1 shl 7))
        putCell(buffer, header + 3 * cellSize, 'x'.code, flags = 1 shl 3)
        putCell(buffer, header + 4 * cellSize, 0, flags = (1 shl 2) or (1 shl 7))
        buffer.putInt(extras, 1); buffer.putInt(extras + 4, 1)
        buffer.putInt(extras + 8, 1); buffer.putInt(extras + 12, 0x301)

        val snapshot = GhosttySnapshotParser.parse(bytes)
        val runs = snapshot.rows.single().runs
        assertEquals(3, runs.size)
        assertEquals("A界\u0301", runs[0].text)
        assertEquals(3, runs[0].columnCount)
        assertEquals(true, runs[0].bold)
        assertEquals("x", runs[1].text)
        assertEquals(0xff0d1117.toInt(), runs[1].foregroundArgb)
        assertEquals(0xffe6edf3.toInt(), runs[1].backgroundArgb)
        assertEquals(" ", runs[2].text)
        assertEquals(true, runs[2].underline)
        assertEquals("", snapshot.cellTextAt(TerminalCellPosition(0, 2)))
        assertEquals("A界\u0301x", snapshot.accessibleText().trimEnd())
    }

    @Test
    fun terminalSelectionCopiesExactCellsAndTrimsOnlyLineEndPadding() {
        val snapshot = TerminalRenderSnapshot(
            columns = 6,
            rowCount = 2,
            rows = listOf(
                TerminalRowSnapshot(
                    rowIndex = 0,
                    runs = listOf(run("a界 b ")),
                    cellText = TerminalCellTextLayout.fromCells(listOf("a", "界", "", " ", "b", " ")),
                ),
                TerminalRowSnapshot(
                    rowIndex = 1,
                    runs = listOf(run("next  ")),
                    cellText = TerminalCellTextLayout.fromCells(listOf("n", "e", "x", "t", " ", " ")),
                ),
            ),
        )

        assertEquals(
            "界 b\nnext",
            snapshot.selectedText(TerminalCellPosition(0, 1), TerminalCellPosition(1, 5)),
        )
        assertEquals(
            "界 b\nnext",
            snapshot.selectedText(TerminalCellPosition(1, 5), TerminalCellPosition(0, 1)),
        )
        assertEquals(
            "a界 b\nnext",
            snapshot.selectedText(TerminalCellPosition(-2, -5), TerminalCellPosition(9, 30)),
        )
    }

    private fun putCell(buffer: ByteBuffer, offset: Int, codepoint: Int, flags: Int) {
        buffer.putInt(offset, codepoint)
        buffer.put(offset + 4, 0xe6.toByte())
        buffer.put(offset + 5, 0xed.toByte())
        buffer.put(offset + 6, 0xf3.toByte())
        buffer.put(offset + 7, 0x0d)
        buffer.put(offset + 8, 0x11)
        buffer.put(offset + 9, 0x17)
        buffer.put(offset + 10, flags.toByte())
    }

    private fun run(text: String) = TerminalGlyphRun(
        startColumn = 0,
        columnCount = text.length,
        text = text,
        foregroundArgb = 0xFFFFFFFF.toInt(),
        backgroundArgb = 0xFF000000.toInt(),
    )
}
