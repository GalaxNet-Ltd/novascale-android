/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import android.content.Context
import android.content.Intent
import cc.galaxnet.novascale.core.TailnetPeer

/** Creates an explicit second document/activity backed by the shared registry. */
internal object TerminalWindowIntent {
    private const val EXTRA_STABLE_NODE_ID = "terminal_peer_node_id"
    private const val EXTRA_DISPLAY_NAME = "terminal_peer_display_name"
    private const val EXTRA_DNS_NAME = "terminal_peer_dns_name"
    private const val EXTRA_ADDRESSES = "terminal_peer_addresses"
    private const val EXTRA_ONLINE = "terminal_peer_online"
    private const val EXTRA_SSH_AVAILABLE = "terminal_peer_ssh_available"
    private const val EXTRA_OS = "terminal_peer_os"
    private const val EXTRA_SESSION_KEY = "terminal_session_key"

    fun create(context: Context, peer: TailnetPeer, sessionKey: String): Intent =
        Intent(context, TerminalWindowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_STABLE_NODE_ID, peer.stableNodeId)
            .putExtra(EXTRA_DISPLAY_NAME, peer.displayName)
            .putExtra(EXTRA_DNS_NAME, peer.dnsName)
            .putStringArrayListExtra(EXTRA_ADDRESSES, ArrayList(peer.addresses))
            .putExtra(EXTRA_ONLINE, peer.online)
            .putExtra(EXTRA_SSH_AVAILABLE, peer.sshAvailable)
            .putExtra(EXTRA_OS, peer.os)
            .putExtra(EXTRA_SESSION_KEY, sessionKey)

    fun sessionKey(intent: Intent): String? =
        intent.getStringExtra(EXTRA_SESSION_KEY)?.takeIf(String::isNotBlank)

    fun peer(intent: Intent): TailnetPeer? {
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: return null
        val stableNodeId = intent.getStringExtra(EXTRA_STABLE_NODE_ID).orEmpty()
        val addresses = intent.getStringArrayListExtra(EXTRA_ADDRESSES).orEmpty()
        if (stableNodeId.isBlank() && addresses.isEmpty() && intent.getStringExtra(EXTRA_DNS_NAME).isNullOrBlank()) {
            return null
        }
        return TailnetPeer(
            stableNodeId = stableNodeId,
            displayName = displayName,
            dnsName = intent.getStringExtra(EXTRA_DNS_NAME),
            addresses = addresses,
            online = intent.getBooleanExtra(EXTRA_ONLINE, true),
            sshAvailable = intent.getBooleanExtra(EXTRA_SSH_AVAILABLE, true),
            os = intent.getStringExtra(EXTRA_OS),
        )
    }
}
