// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: GPL-3.0-only
package cc.galaxnet.novascale.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyInputTest {
    @Test fun shiftTabFollowsTheNegotiatedKeyboardMode() {
        val shift = TerminalKeyModifiers(shift = true)
        assertEquals("\u001b[Z", terminalTextInput("\t", shift).decodeToString())
        assertEquals("\u001b[9;2u", terminalTextInput("\t", shift, keyboardFlags = 1).decodeToString())
        assertEquals("\u001b[9;8u", terminalTextInput("\t", TerminalKeyModifiers(true, true, true), 1).decodeToString())
        assertEquals("\u001b[13;2u", terminalTextInput("\n", shift, 1).decodeToString())
        assertEquals("\u001b[97;2u", terminalTextInput("A", shift, keyboardFlags = 9).decodeToString())
    }

    @Test fun navigationCombinesAllModifierBits() {
        for (mask in 0..7) {
            val modifiers = TerminalKeyModifiers(mask and 4 != 0, mask and 2 != 0, mask and 1 != 0)
            val expected = if (mask == 0) "\u001b[D" else "\u001b[1;${mask + 1}D"
            assertEquals(expected, terminalNavigationInput("D", modifiers).decodeToString())
        }
    }

    @Test fun functionKeysRetainTheirStandardAndModifiedEncodings() {
        assertEquals("\u001bOP", terminalFunctionInput(1, TerminalKeyModifiers()).decodeToString())
        assertEquals("\u001b[24~", terminalFunctionInput(12, TerminalKeyModifiers()).decodeToString())
        assertEquals("\u001b[1;2Q", terminalFunctionInput(2, TerminalKeyModifiers(shift = true)).decodeToString())
        assertEquals("\u001b[15;7~", terminalFunctionInput(5, TerminalKeyModifiers(control = true, alt = true)).decodeToString())
    }

    @Test fun textModifiersApplyOnceWithoutCorruptingUnicodeOrImeBatches() {
        assertEquals("Abc", terminalTextInput("abc", TerminalKeyModifiers(shift = true)).decodeToString())
        assertEquals("\u001ba", terminalTextInput("a", TerminalKeyModifiers(alt = true)).decodeToString())
        assertArrayEquals(byteArrayOf(27, 31), terminalTextInput("_", TerminalKeyModifiers(true, true)))
        assertEquals("中文😀", terminalTextInput("中文😀", TerminalKeyModifiers(control = true)).decodeToString())
        assertEquals("\r", terminalTextInput("\r\n", TerminalKeyModifiers()).decodeToString())
        assertArrayEquals(byteArrayOf(), terminalTextInput("", TerminalKeyModifiers(shift = true)))
    }

    @Test fun snapshotKeepsMousePasteAndActiveScreenKeyboardModesIndependent() {
        val snapshot = TerminalRenderSnapshot(80, 24, emptyList(), mouseFlags = 20 or 256 or (9 shl 9))
        assertEquals(true, snapshot.bracketedPasteEnabled)
        assertEquals(9, snapshot.keyboardEnhancementFlags)
        assertEquals(0, snapshot.copy(mouseFlags = 20).keyboardEnhancementFlags)
    }
}
