/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import cc.galaxnet.novascale.core.TailnetPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class HostVisibilityTest {
    @Test
    fun onlineFilterKeepsTheBackendSnapshotIntact() {
        val online = peer("online", true)
        val offline = peer("offline", false)
        val snapshot = listOf(online, offline)

        assertEquals(listOf(online), visibleTailnetPeers(snapshot, showOnlineOnly = true))
        assertEquals(snapshot, visibleTailnetPeers(snapshot, showOnlineOnly = false))
        assertEquals(2, snapshot.size)
    }

    private fun peer(name: String, online: Boolean) = TailnetPeer(
        stableNodeId = name,
        displayName = name,
        dnsName = "$name.example.ts.net",
        addresses = listOf("100.64.0.2"),
        online = online,
        sshAvailable = true,
        os = "linux",
    )
}
