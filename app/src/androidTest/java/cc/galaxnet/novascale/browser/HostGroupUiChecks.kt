/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.HomeScreen
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetIdentity
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostFilterMember
import cc.galaxnet.novascale.remote.HostGroup
import cc.galaxnet.novascale.remote.HostGroupMembershipDialog
import cc.galaxnet.novascale.remote.HostGroupsState
import cc.galaxnet.novascale.remote.TraditionalHost
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.util.concurrent.atomic.AtomicReference

/** Synthetic hosts and callbacks exercise the real Compose UI without touching app data or a tailnet. */
internal object HostGroupUiChecks {
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
            repeat(50) {
                find(label)?.let { return it }
                Thread.sleep(100)
            }
            error("Missing accessible element: $label")
        }

        fun click(label: String) {
            val node = waitFor(label)
            var action: AccessibilityNodeInfo? = node
            while (action != null && !action.isClickable) action = action.parent
            if (action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                check(!rect.isEmpty) { "Empty bounds for $label" }
                val now = SystemClock.uptimeMillis()
                for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), kind, rect.exactCenterX(), rect.exactCenterY(), 0)
                    test.sendPointerSync(event)
                    event.recycle()
                }
            }
            test.waitForIdleSync()
            Thread.sleep(250)
        }

        val groupA = HostGroup("a", "Fixture work")
        val groupB = HostGroup("b", "Fixture home")
        val traditional = TraditionalHost("traditional:group-ui", "Fixture SSH")
        val other = TraditionalHost("traditional:other-ui", "Fixture other SSH")
        val peer = TailnetPeer("node-group", "Fixture peer", "peer.example", listOf("100.64.0.2"), true, true)
        val offline = TailnetPeer("node-offline", "Fixture offline", "offline.example", listOf("100.64.0.3"), false, true)
        val traditionalKey = GroupHostKey.traditional(traditional.id)
        val peerKey = checkNotNull(GroupHostKey.tailnet("fixture-profile", peer.stableNodeId))
        val groupState = mutableStateOf(HostGroupsState(
            groups = listOf(groupA, groupB),
            memberships = mapOf(traditionalKey to setOf(groupA.id, groupB.id), peerKey to setOf(groupA.id)),
            loaded = true,
        ))
        val connection = mutableStateOf<TailnetState>(TailnetState.Running(TailnetIdentity("self", "self.example", emptyList()), 2))
        var managed = false
        var added = false
        var openedMember: GroupHostKey? = null
        val members = listOf(
            HostFilterMember(traditionalKey, traditional.name, "SSH", null),
            HostFilterMember(peerKey, peer.displayName, "100.64.0.2", true),
        )
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostFilterHarness(groupState.value, members, onCreate = { managed = true }, onOpenMember = { openedMember = it }, onAdd = { added = true }) { filter ->
                        HomeScreen(
                            tailnetState = connection.value,
                            peers = listOf(peer, offline),
                            sessionsSelected = false,
                            onSelectSessions = {}, peerMessage = null, showOnlineOnly = true,
                            terminalSessions = emptyList(), onLogin = {}, onToggleOnlineOnly = {},
                            onTerminalSession = {}, onCloseTerminalSession = {}, onHost = {},
                            traditionalHosts = listOf(traditional, other),
                            onAddTraditionalHost = { added = true },
                            groupState = groupState.value, tailnetProfileId = "fixture-profile",
                            selectedGroupFilter = filter,
                        )
                    }
                }
            }
        }
        test.waitForIdleSync()
        fun screenshot(name: String) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val open = activity.getString(R.string.host_filter_open)
        fun filter(name: String) = activity.getString(R.string.host_groups_filter, name)
        val toolbarBounds = android.graphics.Rect().also(waitFor(open)::getBoundsInScreen)
        val minTarget = (48 * activity.resources.displayMetrics.density - 1).toInt()
        check(toolbarBounds.width() >= minTarget && toolbarBounds.height() >= minTarget) { "Host filter target is smaller than 48 dp: $toolbarBounds" }
        screenshot("fixture-host-toolbar.png")
        click(open)
        waitFor(activity.getString(R.string.host_filter_title))
        screenshot("fixture-host-filter-sheet.png")
        click(activity.getString(R.string.host_filter_expand_group, groupA.name))
        waitFor(traditional.name)
        click(traditional.name)
        check(openedMember == traditionalKey) { "Expanded group member did not navigate to its host" }
        click(open)
        click(activity.getString(R.string.host_filter_group_options, groupA.name))
        click(activity.getString(R.string.host_filter_show_group))
        waitFor(traditional.name)
        waitFor(peer.displayName)
        waitFor(filter(groupA.name))
        check(find(other.name) == null && find(offline.displayName) == null) { "Group filter leaked unrelated hosts" }

        click(filter(groupA.name))
        click(activity.getString(R.string.host_groups_ungrouped))
        waitFor(other.name)
        check(find(traditional.name) == null && find(peer.displayName) == null) { "Ungrouped filter included grouped hosts" }

        click(filter(activity.getString(R.string.host_groups_ungrouped)))
        click(activity.getString(R.string.host_filter_group_options, groupA.name))
        click(activity.getString(R.string.host_filter_show_group))
        test.runOnMainSync { connection.value = TailnetState.LoginRequired(null) }
        test.waitForIdleSync()
        waitFor(traditional.name)
        click(filter(groupA.name))
        click(activity.getString(R.string.host_filter_new_group))
        click(activity.getString(R.string.traditional_host_add))
        check(managed && added) { "Signed-out user cannot manage groups and traditional hosts" }

        // Removing the selected group returns the filter to All, so hosts do not disappear.
        test.runOnMainSync { groupState.value = HostGroupsState(groups = listOf(groupB), loaded = true) }
        waitFor(open)
        waitFor(other.name)

        val saved = AtomicReference<Pair<GroupHostKey, Set<String>>?>()
        var dismissed = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostGroupMembershipDialog(
                        state = HostGroupsState(listOf(groupA, groupB), mapOf(traditionalKey to setOf(groupA.id)), loaded = true),
                        hostKey = traditionalKey, hostName = traditional.name,
                        onSave = { key, ids -> saved.set(key to ids) }, onDismiss = { dismissed = true },
                    )
                }
            }
        }
        waitFor(groupA.name)
        click(groupA.name)
        click(groupB.name)
        click(activity.getString(R.string.action_save))
        repeat(30) { if (saved.get() == null) Thread.sleep(100) }
        check(saved.get() == (traditionalKey to setOf(groupB.id)) && dismissed) { "Membership dialog did not save selected groups" }

        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostGroupMembershipDialog(HostGroupsState(listOf(groupA), loaded = true), null, "Unidentified peer",
                        onSave = { _, _ -> error("Must not save") }, onDismiss = {})
                }
            }
        }
        waitFor(activity.getString(R.string.host_groups_unavailable))
        val save = waitFor(activity.getString(R.string.action_save))
        check(!save.isEnabled || save.parent?.isEnabled == false) { "Unidentified peer allowed a group assignment" }

        // Opening before Room has loaded must not initialize an empty selection
        // that overwrites a host's existing memberships when Save is pressed.
        val loading = mutableStateOf(HostGroupsState())
        val reloaded = AtomicReference<Set<String>?>()
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostGroupMembershipDialog(loading.value, traditionalKey, traditional.name,
                        onSave = { _, ids -> reloaded.set(ids) }, onDismiss = {})
                }
            }
        }
        val disabledSave = waitFor(activity.getString(R.string.action_save))
        check(!disabledSave.isEnabled || disabledSave.parent?.isEnabled == false) { "Loading group memberships allowed an early save" }
        test.runOnMainSync {
            loading.value = HostGroupsState(listOf(groupA), mapOf(traditionalKey to setOf(groupA.id)), loaded = true)
        }
        waitFor(groupA.name)
        click(activity.getString(R.string.action_save))
        repeat(30) { if (reloaded.get() == null) Thread.sleep(100) }
        check(reloaded.get() == setOf(groupA.id)) { "Loading replaced the existing membership with an empty selection" }

        val storageError = mutableStateOf(true)
        val recovered = AtomicReference<Set<String>?>()
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostGroupMembershipDialog(
                        HostGroupsState(listOf(groupB), mapOf(traditionalKey to setOf(groupB.id)), loaded = true),
                        traditionalKey, traditional.name, storageError = storageError.value,
                        onSave = { _, ids -> recovered.set(ids) }, onDismiss = {},
                    )
                }
            }
        }
        waitFor(activity.getString(R.string.host_groups_storage_error))
        val errorSave = waitFor(activity.getString(R.string.action_save))
        check(!errorSave.isEnabled || errorSave.parent?.isEnabled == false) { "Storage error allowed a save" }
        test.runOnMainSync { storageError.value = false }
        waitFor(groupB.name)
        click(activity.getString(R.string.action_save))
        repeat(30) { if (recovered.get() == null) Thread.sleep(100) }
        check(recovered.get() == setOf(groupB.id)) { "Storage recovery lost the existing membership" }
    }
}
