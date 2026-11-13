/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import org.junit.Assert.*
import org.junit.Test

class HerdrSessionOptionsTest {
    @Test fun includesStoppedNamedSessionsAndKeepsRunningDefault() {
        val sessions = listOf(HerdrRemoteSession("saved", false, false), HerdrRemoteSession("work", true, false), HerdrRemoteSession("default", true, true))
        val options = herdrSessionOptions(sessions, "new-session")
        assertEquals(listOf("default", "work", "new-session", "saved"), options.map { it.name })
        assertTrue(options.first().running)
        assertEquals(4, options.map { it.name }.toSet().size)
    }
    @Test fun rejectsInvalidDiscoveryAndExecutableControlCharacters() {
        val options = herdrSessionOptions(listOf(HerdrRemoteSession("../bad", true, false)), "default")
        assertEquals(listOf("default"), options.map { it.name })
        assertTrue(isValidHerdrExecutable(""))
        assertTrue(isValidHerdrExecutable("/home/user/My Tools/herdr"))
        assertFalse(isValidHerdrExecutable("herdr\nother"))
        assertFalse(isValidHerdrExecutable("bad\u0000path"))
        assertFalse(isValidHerdrExecutable("a".repeat(4096)))
    }
}
