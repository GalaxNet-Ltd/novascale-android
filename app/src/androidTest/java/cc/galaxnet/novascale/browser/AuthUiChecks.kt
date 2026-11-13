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

internal object AuthUiChecks {
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

        val state = mutableStateOf<TailnetState>(TailnetState.LoginRequired(null))
        var submitted: String? = null
        var cancelled = false
        var connected = false
        val backend = java.lang.reflect.Proxy.newProxyInstance(TailnetBackend::class.java.classLoader,
            arrayOf(TailnetBackend::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AuthFixtureBackend"
                "getCurrentState" -> state.value
                "start" -> { state.value = TailnetState.LoginRequired(null); Unit }
                "beginAuthKeyLogin" -> { submitted = args!![0] as String; state.value = TailnetState.Starting; Unit }
                "beginInteractiveLogin" -> LoginRequest("https://example.invalid/auth-fixture")
                "cancelAuthKeyLogin" -> { cancelled = true; Unit }
                else -> Unit
            }
        } as TailnetBackend
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        TailnetLoginScreen(backend, state.value, true,
                            profile = cc.galaxnet.novascale.tailnet.TailnetProfile("fixture-auth", "Authentication", cc.galaxnet.novascale.tailnet.DEFAULT_CONTROL_SERVER)) { connected = true }
                    }
                }
            }
        }
        Thread.sleep(600)
        click(activity.getString(R.string.auth_key_method))
        fun passwordNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if (node.isPassword && node.isEditable) return node
            for (i in 0 until node.childCount) passwordNode(node.getChild(i))?.let { return it }
            return null
        }
        val key = "fixture-key-not-a-real-credential"
        check(passwordNode(test.uiAutomation.rootInActiveWindow) != null) { "Missing secure key field" }
        test.runOnMainSync {
            activity.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Auth fixture", key))
        }
        click(activity.getString(R.string.cd_paste))
        click(activity.getString(R.string.auth_key_connect))
        Thread.sleep(300)
        check(submitted == key) { "Key did not reach backend" }
        check(find(activity.getString(R.string.auth_key_wait_description)) != null)
        check(passwordNode(test.uiAutomation.rootInActiveWindow) == null) { "Key form still visible while waiting" }
        test.runOnMainSync { state.value = TailnetState.LoginRequired(null, awaitingApproval = true) }
        Thread.sleep(300)
        check(find(activity.getString(R.string.auth_waiting_approval)) != null)
        click(activity.getString(R.string.auth_back_setup))
        check(cancelled) { "Back to setup did not cancel key request" }
        check(passwordNode(test.uiAutomation.rootInActiveWindow)?.text?.contains(key) != true) { "Key retained in form" }

        click(activity.getString(R.string.auth_browser_method))
        val monitor = test.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply { addDataScheme("https") },
            Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            click(activity.getString(R.string.start_and_sign_in))
            Thread.sleep(300)
            check(find(activity.getString(R.string.auth_browser_wait_description)) != null) {
                val labels = mutableListOf<String>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    node.text?.let { labels += it.toString() }
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                collect(test.uiAutomation.rootInActiveWindow)
                "Browser waiting screen missing; labels=$labels; monitor hits=${monitor.hits}"
            }
            check(find(activity.getString(R.string.auth_reopen_browser)) != null)
            check(monitor.hits > 0) { "No external browser handoff" }
            test.runOnMainSync { state.value = TailnetState.Running(TailnetIdentity("self", "self.example", listOf("100.64.0.1")), 0) }
            Thread.sleep(300)
            check(connected) { "Running state did not finish login" }
        } finally { test.removeMonitor(monitor) }
    }
}
