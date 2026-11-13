/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cc.galaxnet.novascale.HomeScreen
import cc.galaxnet.novascale.HostDetailScreen
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetIdentity
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.herdr.HerdrRemoteSession
import cc.galaxnet.novascale.herdr.HerdrSessionPicker
import cc.galaxnet.novascale.herdr.HerdrSessionSummary
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.util.concurrent.atomic.AtomicReference

/** Real Compose controls, synthetic hosts. Never authenticates to the owner's tailnet. */
internal object HostRefinementUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (!node.refresh()) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(text: String): AccessibilityNodeInfo {
            repeat(60) { attempt ->
                label(text)?.let { return it }
                if (attempt % 10 == 9) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(100)
            }
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "round2-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val labels = mutableListOf<String>()
            find { node -> node.text?.let { labels.add(it.toString()) }; node.contentDescription?.let { labels.add(it.toString()) }; false }
            error("Missing refinement control: $text; labels=$labels")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = waitFor(text)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot activate $text" }
            test.waitForIdleSync()
            Thread.sleep(200)
        }
        fun enter(text: String) {
            val field = find { it.isEditable } ?: error("No input field")
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            test.waitForIdleSync()
            Thread.sleep(300)
        }
        fun screenshot(name: String) {
            Thread.sleep(300)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun text(id: Int) = activity.getString(id)
        val host = TraditionalHost("traditional:ux", "Production SSH", "192.168.1.20", "2222")
        val other = TraditionalHost("traditional:other", "Staging SSH", "192.168.1.30")
        val peer = TailnetPeer("ux-peer", "Build server", "build.example", listOf("100.64.0.2"), true, true, os = "linux")
        val group = HostGroup("ux-work", "Work")
        val hostKey = GroupHostKey.traditional(host.id)
        val otherKey = GroupHostKey.traditional(other.id)
        val peerKey = checkNotNull(GroupHostKey.tailnet("ux-profile", peer.stableNodeId))
        val groupState = HostGroupsState(listOf(group), mapOf(hostKey to setOf(group.id), peerKey to setOf(group.id)), true)
        val selectedTab = mutableStateOf(false)
        var resumed = false
        var closed = false
        test.runOnMainSync {
            activity.setContent { NovaScaleTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    HostFilterHarness(groupState) { filter ->
                        HomeScreen(TailnetState.Running(TailnetIdentity("self", "self.example", emptyList()), 1), listOf(peer),
                            selectedTab.value, { selectedTab.value = it }, null, true, emptyList(), {}, {}, {}, {}, {},
                            traditionalHosts = listOf(host, other), groupState = groupState, tailnetProfileId = "ux-profile", selectedGroupFilter = filter,
                            herdrSessions = listOf(HerdrSessionSummary("fixture", "Build server", "daily", true, false)),
                            onHerdrSession = { resumed = it == "fixture" }, onCloseHerdrSession = { closed = true })
                    }
                }
            } }
        }
        waitFor(host.name)
        waitFor(host.endpoint)
        screenshot("round2-hosts.png")
        enter("PROD 2222")
        waitFor(host.name)
        check(label(other.name) == null && label(peer.displayName) == null) { "Search failed to combine name and address" }
        click(text(R.string.hosts_search_clear))
        click(text(R.string.host_filter_open))
        click(activity.getString(R.string.hosts_group_select, group.name))
        waitFor(host.name)
        check(label(other.name) == null) { "One-tap group filter leaked an unrelated host" }
        enter("staging")
        waitFor(text(R.string.hosts_search_empty))
        check(label(other.name) == null) { "Search bypassed group filtering" }
        click(text(R.string.home_sessions))
        click("Herdr Remote · daily")
        check(resumed && label(text(R.string.detached_sessions_empty)) == null) { "Retained Herdr session is not resumable" }
        screenshot("round2-sessions.png")
        click(text(R.string.herdr_close))
        waitFor(text(R.string.herdr_close_description))
        click(text(R.string.action_cancel))
        check(!closed) { "Cancel closed the retained session" }

        val members = listOf(HostFilterMember(hostKey, host.name, host.endpoint, null),
            HostFilterMember(otherKey, other.name, other.endpoint, null), HostFilterMember(peerKey, peer.displayName, "100.64.0.2", true))
        val edited = AtomicReference<Pair<Set<GroupHostKey>, Set<GroupHostKey>>?>()
        test.runOnMainSync { activity.setContent { NovaScaleTheme(darkTheme = false) {
            HostGroupMembersDialog(group, groupState, members,
                onSave = { offered, selected -> edited.set(offered to selected) }, onDismiss = {})
        } } }
        waitFor(host.name)
        enter("build")
        click(peer.displayName)
        click(text(R.string.action_save))
        repeat(30) { if (edited.get() == null) Thread.sleep(100) }
        check(edited.get() == (setOf(hostKey, otherKey, peerKey) to setOf(hostKey))) { "Searched bulk edit lost unseen selections" }

        val assignments = mutableStateOf(HostGroupsState(loaded = true))
        val assigned = AtomicReference<Set<String>?>()
        test.runOnMainSync { activity.setContent { NovaScaleTheme(darkTheme = false) {
            HostGroupMembershipDialog(assignments.value, hostKey, host.name,
                onSave = { _, groups -> assigned.set(groups) },
                onCreate = { name -> HostGroup("new", name).also { assignments.value = HostGroupsState(listOf(it), loaded = true) } }, onDismiss = {})
        } } }
        click(text(R.string.host_filter_new_group))
        waitFor(text(R.string.host_groups_name))
        enter("Home lab")
        click(text(R.string.action_save))
        waitFor("Home lab")
        click(text(R.string.action_save))
        repeat(30) { if (assigned.get() == null) Thread.sleep(100) }
        check(assigned.get() == setOf("new")) { "New group was not selected during assignment" }

        val selected = mutableStateOf("default")
        val connected = AtomicReference<String?>()
        val executable = AtomicReference<String?>()
        val pickerVisible = mutableStateOf(true)
        var dismissed = false
        var refreshed = false
        test.runOnMainSync { activity.setContent { NovaScaleTheme(darkTheme = false) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (pickerVisible.value) HerdrSessionPicker("Build server", listOf(HerdrRemoteSession("daily", true, false), HerdrRemoteSession("archived", false, false)), "",
                    selected.value, { selected.value = it }, onConnect = { connected.set(it) },
                    onRefresh = { refreshed = true }, onExecutable = { executable.set(it) },
                    onDismiss = { dismissed = true; pickerVisible.value = false })
            }
        } } }
        waitFor(text(R.string.herdr_connect))
        Thread.sleep(400) // Wait for the native sheet's opening animation before measuring its detent.
        val screenHeight = activity.window.decorView.height
        fun bounds(label: String) = android.graphics.Rect().also { waitFor(label).getBoundsInScreen(it) }
        val halfTitle = bounds(text(R.string.herdr_choose_session))
        check(halfTitle.top > screenHeight * .45 && halfTitle.top < screenHeight * .7) { "Picker did not open at medium height: $halfTitle / $screenHeight" }
        check(bounds(text(R.string.herdr_connect)).bottom < screenHeight) { "Connect is below the half-sheet viewport" }
        screenshot("herdr-picker-half.png")
        val expand = AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
        check(find { node -> node.actionList.any { it.id == expand } }?.performAction(expand) == true)
        Thread.sleep(400)
        check(bounds(text(R.string.herdr_choose_session)).top < screenHeight * .3) { "Picker could not expand" }
        waitFor("archived")
        click("archived")
        check(connected.get() == null) { "Selecting a session connected implicitly" }
        screenshot("round2-herdr-picker.png")
        click(text(R.string.herdr_connect))
        check(connected.get() == "archived") { "Stopped session was not available for connection" }
        click(text(R.string.herdr_refresh))
        check(refreshed && selected.value == "archived")
        click(text(R.string.herdr_advanced))
        waitFor(text(R.string.herdr_executable))
        enter("/opt/tools/herdr")
        click(text(R.string.action_save))
        check(executable.get() == "/opt/tools/herdr")
        click(text(R.string.herdr_session_new))
        waitFor(text(R.string.herdr_session_name))
        enter("../invalid")
        val disabled = waitFor(text(R.string.action_save))
        check(!disabled.isEnabled || disabled.parent?.isEnabled == false) { "Invalid session name allowed" }
        enter("new-session")
        click(text(R.string.action_save))
        click(text(R.string.herdr_connect))
        check(connected.get() == "new-session")
        click(text(R.string.action_cancel))
        check(dismissed && connected.get() == "new-session") { "Dismissing the picker started another attachment" }

        test.runOnMainSync { activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                NovaScaleTheme(darkTheme = false) {
                    HerdrSessionPicker("Build server", listOf(HerdrRemoteSession("daily", true, false)), "",
                        selected.value, { selected.value = it }, onConnect = { connected.set(it) },
                        onRefresh = {}, onExecutable = {}, onDismiss = {})
                }
            }
        } }
        waitFor(text(R.string.herdr_connect)); Thread.sleep(400)
        check(bounds(text(R.string.herdr_choose_session)).top < screenHeight * .3) { "Large text should open the expanded picker" }
        check(bounds(text(R.string.herdr_connect)).bottom < screenHeight) { "Large-text Connect is not visible" }
        screenshot("herdr-picker-large-text.png")

        test.runOnMainSync { activity.setContent { NovaScaleTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                HostDetailScreen(peer, {}, {}, {}, {}, onHerdr = {}, onManageGroups = {},
                    pinned = true, pinEnabled = true, onTogglePin = {}, groupNames = listOf("Work", "Infrastructure"))
            }
        } } }
        waitFor(text(R.string.herdr_title))
        screenshot("round2-host-details-dark.png")
    }
}
