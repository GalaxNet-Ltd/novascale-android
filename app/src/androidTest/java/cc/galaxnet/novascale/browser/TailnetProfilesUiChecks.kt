// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.tailnet.*
import cc.galaxnet.novascale.ui.NovaScaleTheme

internal object TailnetProfilesUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(text: String): AccessibilityNodeInfo {
            repeat(60) { label(text)?.let { return it }; Thread.sleep(100) }
            error("Missing tailnet control: $text")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = waitFor(text)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot activate $text" }
            test.waitForIdleSync(); Thread.sleep(250)
        }
        fun enter(value: String, last: Boolean = false) {
            val fields = mutableListOf<AccessibilityNodeInfo>()
            find { node -> if (node.isEditable) fields.add(node); false }
            val field = if (last) fields.last() else fields.first()
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
            test.waitForIdleSync(); Thread.sleep(200)
        }
        fun text(id: Int) = activity.getString(id)
        fun screenshot(name: String) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val a = TailnetProfile("fixture-a", "Personal", DEFAULT_CONTROL_SERVER)
        val b = TailnetProfile("fixture-b", "Work", "https://headscale.example")
        val state = mutableStateOf(TailnetProfilesUiState(TailnetProfileSnapshot(listOf(a, b), a.id)))
        var allowAdd = false
        var addRequests = 0
        var switches = 0
        var details = 0
        var added: TailnetProfile? = null
        var removed: String? = null
        test.runOnMainSync { activity.setContent { NovaScaleTheme(darkTheme = false) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                TailnetProfilesScreen(state.value, TailnetState.Running(TailnetIdentity("fixture", "phone.example", emptyList()), 2),
                    onSelect = { id -> switches++; state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(activeId = id)) },
                    onRequestAdd = { addRequests++; allowAdd },
                    onAdd = { name, url -> added = TailnetProfile("fixture-c", name, url)
                        state.value = state.value.copy(snapshot = state.value.snapshot!!.let { it.copy(profiles = it.profiles + added!!) }) },
                    onRename = { id, name -> state.value = state.value.copy(snapshot = state.value.snapshot!!.let {
                        it.copy(profiles = it.profiles.map { profile -> if (profile.id == id) profile.copy(name = name) else profile }) }) },
                    onRemove = { id -> removed = id; state.value = state.value.copy(snapshot = state.value.snapshot!!.let {
                        it.copy(profiles = it.profiles.filterNot { it.id == id }) }) },
                    onActiveDetails = { details++ }, onRetry = {})
            }
        } } }
        waitFor(a.name)
        screenshot("tailnets-switcher.png")
        click(a.name); check(details == 1 && switches == 0)
        click(b.name)
        waitFor(text(R.string.tailnets_switch_description))
        click(text(R.string.action_cancel)); check(switches == 0)
        click(b.name); click(text(R.string.tailnets_switch)); check(switches == 1)
        click(activity.getString(R.string.tailnets_options, a.name))
        click(text(R.string.tailnets_rename)); enter("Personal renamed")
        click(text(R.string.action_save)); waitFor("Personal renamed")
        click(activity.getString(R.string.tailnets_options, b.name))
        waitFor(text(R.string.tailnets_remove_active_hint))
        val delete = waitFor(text(R.string.action_delete))
        check(!delete.isEnabled || delete.parent?.isEnabled == false) { "Active tailnet can be removed" }
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        click(text(R.string.tailnets_add))
        check(addRequests == 1 && find { it.isEditable } == null && added == null) { "Denied Add opened an editor" }
        allowAdd = true
        click(text(R.string.tailnets_add))
        enter("Customer 网络")
        click(text(R.string.tailnets_custom)); enter("http://invalid.example", last = true)
        click(text(R.string.action_save)); waitFor(text(R.string.tailnets_invalid)); check(added == null)
        enter("https://customer.example/Base", last = true)
        screenshot("tailnets-add.png")
        click(text(R.string.action_save)); check(added?.controlUrl == "https://customer.example/Base")
        waitFor("Customer 网络")
        click(activity.getString(R.string.tailnets_options, "Personal renamed"))
        click(text(R.string.action_delete)); waitFor(text(R.string.tailnets_remove_description))
        click(text(R.string.action_cancel)); check(removed == null)
        click(activity.getString(R.string.tailnets_options, "Personal renamed"))
        click(text(R.string.action_delete)); click(text(R.string.action_delete)); check(removed == a.id)
        test.runOnMainSync { state.value = state.value.copy(busy = true) }
        Thread.sleep(250)
        screenshot("tailnets-switching.png")
        check(switches == 1)
    }
}
