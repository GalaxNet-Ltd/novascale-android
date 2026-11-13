// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.*
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.ui.NovaScaleTheme

internal object HomeUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun find(text: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.refresh()
                if (node.text?.toString()?.contains(text) == true) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }

        fun click(label: String) {
            var node: AccessibilityNodeInfo? = null
            repeat(40) { if (node == null) { node = find(label); if (node == null) Thread.sleep(100) } }
            val rect = Rect()
            (node ?: error("Missing action: $label")).getBoundsInScreen(rect)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                test.sendPointerSync(event); event.recycle()
            }
            Thread.sleep(300)
        }
        var completed = false
        test.runOnMainSync {
            activity.setContent { NovaScaleTheme(darkTheme = false) { OnboardingScreen(false, false) { completed = true } } }
        }
        Thread.sleep(600)
        click(activity.getString(R.string.onboard_next))
        check(!completed)
        click(activity.getString(R.string.onboard_back))
        check(find(activity.getString(R.string.onboard_network_title)) != null)
        click(activity.getString(R.string.onboard_next))
        click(activity.getString(R.string.onboard_next))
        check(find(activity.getString(R.string.onboard_herdr_title)) != null)
        click(activity.getString(R.string.onboard_next))
        click(activity.getString(R.string.onboard_start))
        check(completed) { "Onboarding did not complete" }

        val sessions = mutableStateOf(false)
        val connection = mutableStateOf<TailnetState>(TailnetState.Running(TailnetIdentity("self", "self.example", listOf("100.64.0.1")), 0))
        var login = false
        var addTraditionalHost = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HomeScreen(connection.value, emptyList(), sessions.value, { sessions.value = it },
                            null, true, emptyList(), { login = true }, {}, {}, {}, {},
                            onAddTraditionalHost = { addTraditionalHost = true })
                    }
                }
            }
        }
        Thread.sleep(600)
        check(find(activity.getString(R.string.detached_sessions_empty)) == null) { "Sessions clutter Hosts" }
        click(activity.getString(R.string.home_sessions))
        check(find(activity.getString(R.string.detached_sessions_empty)) != null)
        click(activity.getString(R.string.section_hosts))
        test.runOnMainSync { connection.value = TailnetState.Starting }
        Thread.sleep(300)
        check(find(activity.getString(R.string.tailnet_starting)) != null)
        check(find(activity.getString(R.string.home_login_description)) == null) { "Loading showed sign-in instructions" }
        check(find(activity.getString(R.string.continue_sign_in)) == null) { "Loading showed sign-in action" }
        test.runOnMainSync { connection.value = TailnetState.LoginRequired(null) }
        Thread.sleep(300)
        click(activity.getString(R.string.traditional_host_add))
        check(addTraditionalHost) { "Signed-out Hosts cannot add a traditional host" }
        click(activity.getString(R.string.continue_sign_in))
        check(login) { "Signed-out host page has no login action" }
        var opened: String? = null
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    HostWebAccessSheet(TailnetPeer("fixture", "Grafana", "grafana.tail.example", listOf("100.64.0.1"), true, true),
                        onDismiss = {}, onOpen = { opened = it })
                }
            }
        }
        Thread.sleep(600)
        check(opened == null) { "Host sheet opened without confirmation" }
        click("9090")
        check(opened == null) { "Port selection navigated" }
        click(activity.getString(R.string.web_open_browser))
        check(opened == "http://grafana:9090") { "Host sheet did not use the selected port: $opened" }

    }
}
