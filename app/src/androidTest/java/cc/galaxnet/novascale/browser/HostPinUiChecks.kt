/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.HomeScreen
import cc.galaxnet.novascale.HostDetailScreen
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetIdentity
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostGroup
import cc.galaxnet.novascale.remote.HostGroupsState
import cc.galaxnet.novascale.remote.HostPinAction
import cc.galaxnet.novascale.remote.HostPinsState
import cc.galaxnet.novascale.remote.PinnedHost
import cc.galaxnet.novascale.remote.TraditionalHost
import cc.galaxnet.novascale.ui.NovaScaleTheme

/** Verifies pin ordering and accessibility with synthetic hosts, without a tailnet. */
internal object HostPinUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun find(label: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun waitFor(label: String): AccessibilityNodeInfo {
            repeat(50) { find(label)?.let { return it }; Thread.sleep(100) }
            error("Missing accessible element: $label")
        }
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = waitFor(label)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot activate $label" }
            test.waitForIdleSync()
            Thread.sleep(200)
        }
        fun top(label: String): Int = Rect().also(waitFor(label)::getBoundsInScreen).top
        fun assertOrder(vararg names: String) {
            val positions = names.map(::top)
            check(positions.zipWithNext().all { (a, b) -> a < b }) { "Host order is wrong: ${names.toList()} at $positions" }
        }
        val tradPlain = TraditionalHost("traditional:plain", "SSH plain")
        val tradPinned = TraditionalHost("traditional:pinned", "SSH pinned")
        val peerPlain = TailnetPeer("peer-plain", "Peer plain", "plain.example", listOf("100.64.0.1"), true, true)
        val peerPinned = TailnetPeer("peer-pinned", "Peer pinned", "pinned.example", listOf("100.64.0.2"), true, true)
        val peerOffline = TailnetPeer("peer-offline", "Peer offline", "offline.example", listOf("100.64.0.3"), false, true)
        val profile = "fixture-profile"
        val tradKey = GroupHostKey.traditional(tradPinned.id)
        val plainTradKey = GroupHostKey.traditional(tradPlain.id)
        val peerKey = checkNotNull(GroupHostKey.tailnet(profile, peerPinned.stableNodeId))
        val plainPeerKey = checkNotNull(GroupHostKey.tailnet(profile, peerPlain.stableNodeId))
        val offlineKey = checkNotNull(GroupHostKey.tailnet(profile, peerOffline.stableNodeId))
        val group = HostGroup("mixed", "Fixture mixed")
        val groups = HostGroupsState(listOf(group), mapOf(
            tradKey to setOf(group.id), plainTradKey to setOf(group.id),
            peerKey to setOf(group.id), plainPeerKey to setOf(group.id),
        ), loaded = true)
        // Intentionally reverse pin order: UI must keep the original source order within each partition.
        val pins = HostPinsState(listOf(PinnedHost(peerKey), PinnedHost(offlineKey), PinnedHost(tradKey)), loaded = true)
        val connection = mutableStateOf<TailnetState>(TailnetState.Running(TailnetIdentity("self", "self.example", emptyList()), 3))
        val onlineOnly = mutableStateOf(true)
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostFilterHarness(groups) { filter ->
                        HomeScreen(
                            tailnetState = connection.value, peers = listOf(peerPlain, peerPinned, peerOffline),
                            sessionsSelected = false, onSelectSessions = {}, peerMessage = null,
                            showOnlineOnly = onlineOnly.value, terminalSessions = emptyList(),
                            onLogin = {}, onToggleOnlineOnly = { onlineOnly.value = !onlineOnly.value },
                            onTerminalSession = {}, onCloseTerminalSession = {}, onHost = {},
                            traditionalHosts = listOf(tradPlain, tradPinned),
                            groupState = groups, tailnetProfileId = profile, selectedGroupFilter = filter,
                            pinState = pins,
                        )
                    }
                }
            }
        }
        waitFor(activity.getString(R.string.host_pinned))
        assertOrder(tradPinned.name, peerPinned.displayName, tradPlain.name, peerPlain.displayName)
        check(find(peerOffline.displayName) == null) { "Online-only filter showed an offline pin" }

        val open = activity.getString(R.string.host_filter_open)
        fun filter(name: String) = activity.getString(R.string.host_groups_filter, name)
        click(open)
        click(activity.getString(R.string.host_filter_group_options, group.name))
        click(activity.getString(R.string.host_filter_show_group))
        check(find(activity.getString(R.string.host_pinned)) == null) { "Pins section appeared in a group filter" }
        assertOrder(tradPlain.name, tradPinned.name, peerPlain.displayName, peerPinned.displayName)

        click(filter(group.name))
        click(activity.getString(R.string.host_groups_all))
        test.runOnMainSync { onlineOnly.value = false }
        waitFor(peerOffline.displayName)
        assertOrder(tradPinned.name, peerPinned.displayName, peerOffline.displayName, tradPlain.name)

        test.runOnMainSync { connection.value = TailnetState.LoginRequired(null) }
        test.waitForIdleSync()
        waitFor(activity.getString(R.string.connect_tailnet))
        waitFor(tradPinned.name)
        check(find(peerPinned.displayName) == null) { "Signed-out page showed an unavailable peer" }
        check(top(tradPinned.name) < top(tradPlain.name)) { "Signed-out traditional pin was not first" }

        val pinned = mutableStateOf(false)
        val enabled = mutableStateOf(false)
        var activations = 0
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostPinAction(pinned.value, enabled.value) { activations++; pinned.value = !pinned.value }
                }
            }
        }
        val pinLabel = activity.getString(R.string.host_pin)
        val unpinLabel = activity.getString(R.string.host_unpin)
        val disabled = waitFor(pinLabel)
        check(!disabled.isEnabled || disabled.parent?.isEnabled == false) { "Disabled pin action was accessible as enabled" }
        test.runOnMainSync { enabled.value = true }
        test.waitForIdleSync()
        var pinReady = false
        repeat(50) {
            if (!pinReady) {
                val node = find(pinLabel)
                pinReady = node?.isEnabled == true && node.parent?.isEnabled != false
                if (!pinReady) Thread.sleep(100)
            }
        }
        check(pinReady) { "Pin action did not become enabled" }
        click(pinLabel)
        waitFor(unpinLabel)
        click(unpinLabel)
        check(activations == 2 && !pinned.value) { "Pin/unpin actions failed" }

        val unknown = peerPlain.copy(stableNodeId = "")
        var attemptedPin = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostDetailScreen(unknown, {}, {}, {}, {}, pinUnavailable = true, onTogglePin = { attemptedPin = true })
                }
            }
        }
        waitFor(activity.getString(R.string.host_pin_unavailable))
        check(find(pinLabel) == null && !attemptedPin) { "Unidentified peer exposed a pin action" }
    }
}
