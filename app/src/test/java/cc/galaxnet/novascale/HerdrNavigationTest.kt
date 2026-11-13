/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale

import cc.galaxnet.novascale.core.TailnetPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HerdrNavigationTest {
    private val peer = TailnetPeer("node-1", "Build host", "host.example", listOf("100.64.0.5"), true, true)

    @Test
    fun unsavedSshConfigurationRetainsHerdrAsThePendingAction() {
        assertEquals(
            AppDestination.SshSettings(peer, PendingHostAction.HERDR),
            tailnetHerdrDestination(peer, "profile", hasSavedProfile = false),
        )
    }

    @Test
    fun savedSshConfigurationOpensStableProfileScopedHerdrController() {
        val destination = tailnetHerdrDestination(peer, "profile", hasSavedProfile = true)
        assertEquals(AppDestination.Herdr(peer, tailnetHerdrKey("profile", peer)), destination)
        assertNotEquals(tailnetHerdrKey("profile", peer), tailnetHerdrKey("other-profile", peer))
    }
}
