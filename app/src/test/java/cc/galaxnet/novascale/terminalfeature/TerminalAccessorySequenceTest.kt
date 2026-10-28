package cc.galaxnet.novascale.terminalfeature

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.compose.ui.geometry.Offset
import org.junit.Test

class TerminalAccessorySequenceTest {
    @Test fun arrowPadUsesADeadZoneAndTheDominantDragAxis() {
        assertNull(terminalArrowDirection(Offset(7f, -7f), 8f))
        assertEquals(ArrowDirection.Left, terminalArrowDirection(Offset(-20f, 10f), 8f))
        assertEquals(ArrowDirection.Right, terminalArrowDirection(Offset(20f, 10f), 8f))
        assertEquals(ArrowDirection.Up, terminalArrowDirection(Offset(10f, -20f), 8f))
        assertEquals(ArrowDirection.Down, terminalArrowDirection(Offset(10f, 20f), 8f))
        assertNull(terminalArrowDirection(Offset.Zero, 8f))
    }

    @Test fun shiftTabAndArrowReachRemotePrompts() {
        assertEquals("\u001b[Z", terminalAccessorySequence("\t", false, false, false, shift = true).decodeToString())
        assertEquals("\u001b[9;2u", terminalAccessorySequence("\t", false, false, false, shift = true, keyboardFlags = 1).decodeToString())
        assertEquals("\u001b[1;2A", terminalAccessorySequence("A", false, false, true, shift = true).decodeToString())
        assertEquals("\u001b[24~", terminalAccessorySequence("F12", false, false, false).decodeToString())
    }

    @Test fun newlineIsOnlySentAsABracketedPaste() {
        assertNull(terminalNewlineSequence(false))
        assertEquals("\u001b[200~\n\u001b[201~", terminalNewlineSequence(true)?.decodeToString())
    }
    @Test
    fun plainArrowUsesNormalCsiSequence() {
        assertArrayEquals(
            "\u001b[A".toByteArray(),
            terminalAccessorySequence("A", control = false, alt = false, navigation = true),
        )
    }

    @Test
    fun controlAltArrowUsesCombinedCsiModifier() {
        assertArrayEquals(
            "\u001b[1;7D".toByteArray(),
            terminalAccessorySequence("D", control = true, alt = true, navigation = true),
        )
    }

    @Test
    fun controlSymbolAndAltPrefixAreEncoded() {
        assertArrayEquals(
            byteArrayOf(0x1b, 0x1f),
            terminalAccessorySequence("_", control = true, alt = true, navigation = false),
        )
    }

    @Test
    fun tabBarOnlyConsumesTerminalSpaceForMultipleTabs() {
        assertFalse(shouldShowTerminalTabBar(tabCount = 1))
        assertTrue(shouldShowTerminalTabBar(tabCount = 2))
        assertTrue(shouldShowTerminalTabBar(tabCount = 6))
    }
}
