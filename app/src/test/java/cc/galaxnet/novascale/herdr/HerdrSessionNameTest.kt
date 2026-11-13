/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrSessionNameTest {
    @Test fun acceptsNamesInTheRemoteSessionAlphabet() {
        for (name in listOf("default", "work-1", "a_B.c", "a".repeat(64))) {
            assertTrue(name, isValidHerdrSessionName(name))
        }
    }

    @Test fun rejectsPathDelimitersUnicodeAndOutOfRangeNames() {
        for (name in listOf("", ".", "..", "../work", "x/y", "a b", "a\nb", "é", "a".repeat(65))) {
            assertFalse(name, isValidHerdrSessionName(name))
        }
    }

    @Test fun identitySeparatesProfilesAccountsPortsHostsAndNamedSessions() {
        val a = HerdrSessionIdentity("profile", "host", 22, "alice", "default")
        assertNotEquals(a, a.copy(tailnetId = "other-profile"))
        assertNotEquals(a, a.copy(hostId = "other-host"))
        assertNotEquals(a, a.copy(port = 2222))
        assertNotEquals(a, a.copy(username = "bob"))
        assertNotEquals(a, a.copy(sessionName = "work"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun identityRejectsInvalidRemoteSessionNames() {
        HerdrSessionIdentity("profile", "host", 22, "alice", "../other")
    }

    @Test fun inputRequiresCurrentVisibleEpochAndSameAttachment() {
        val displayed = HerdrInputTarget(4, 12)
        assertTrue(herdrInputAllowed(true, 4, displayed, displayed))
        assertFalse(herdrInputAllowed(false, 4, displayed, displayed))
        assertFalse(herdrInputAllowed(true, 4, null, displayed))
        assertFalse(herdrInputAllowed(true, 4, HerdrInputTarget(4, 13), displayed))
        // A new connection may restart its epoch sequence; session identity
        // prevents an old view's callback authorizing input on that connection.
        assertFalse(herdrInputAllowed(true, 5, HerdrInputTarget(5, 12), displayed))
        assertFalse(herdrInputAllowed(true, 4, displayed, null))
        assertFalse(herdrInputAllowed(true, 4, HerdrInputTarget(4, 0), HerdrInputTarget(4, 0)))
    }
}
