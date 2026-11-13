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
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }

        var previousAction = "opening setup"
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = null
            repeat(40) { if (node == null) { node = find(label); if (node == null) Thread.sleep(100) } }
            val rect = Rect()
            if (node == null) {
                val labels = mutableListOf<String>()
                fun collect(current: AccessibilityNodeInfo?) {
                    if (current == null) return
                    current.text?.let { labels += it.toString() }
                    for (index in 0 until current.childCount) collect(current.getChild(index))
                }
                collect(test.uiAutomation.rootInActiveWindow)
                error("Missing action: $label after $previousAction; fixture labels=$labels")
            }
            node!!.getBoundsInScreen(rect)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                test.sendPointerSync(event); event.recycle()
            }
            Thread.sleep(300)
            previousAction = label
        }
        fun enabled(label: String): Boolean {
            var node: AccessibilityNodeInfo? = find(label) ?: error("Missing action: $label")
            while (node != null) {
                if (!node.isEnabled) return false
                node = node.parent
            }
            return true
        }

        val state = mutableStateOf<TailnetState>(TailnetState.LoginRequired(null))
        var submitted: String? = null
        var cancelled = false
        var connected = false
        var submittedServer: String? = null
        val profile = mutableStateOf(cc.galaxnet.novascale.tailnet.TailnetProfile("fixture-auth", "Authentication",
            cc.galaxnet.novascale.tailnet.DEFAULT_CONTROL_SERVER))
        val backend = java.lang.reflect.Proxy.newProxyInstance(TailnetBackend::class.java.classLoader,
            arrayOf(TailnetBackend::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AuthFixtureBackend"
                "getCurrentState" -> state.value
                "start" -> { submittedServer = (args!![0] as TailnetConfiguration).controlUrl; state.value = TailnetState.LoginRequired(null); Unit }
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
                            profile = profile.value,
                            onChangeServer = { url -> profile.value = profile.value.copy(id = profile.value.id + "-edited", controlUrl = url) }
                        ) { connected = true }
                    }
                }
            }
        }
        Thread.sleep(600)
        fun editServer(text: String) {
            fun field(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
                for (index in 0 until node.childCount) field(node.getChild(index))?.let { return it }
                return null
            }
            val input = field(test.uiAutomation.rootInActiveWindow) ?: error("No editable login server")
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            Thread.sleep(200)
        }
        click(activity.getString(R.string.login_server_edit))
        click(activity.getString(R.string.login_server_clear))
        check(!enabled(activity.getString(R.string.action_save))) { "Empty URL was saveable" }
        editServer("http://insecure.example")
        check(find(activity.getString(R.string.login_server_invalid)) != null)
        check(!enabled(activity.getString(R.string.action_save)))
        click(activity.getString(R.string.action_cancel))
        check(profile.value.controlUrl == cc.galaxnet.novascale.tailnet.DEFAULT_CONTROL_SERVER) { "Cancel changed the profile" }
        click(activity.getString(R.string.login_server_edit))
        editServer("https://headscale.example")
        click(activity.getString(R.string.auth_reset_server))
        check(!enabled(activity.getString(R.string.action_save))) { "Unchanged server could replace the profile" }
        editServer("https://headscale.example")
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "login-server-editor.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        click(activity.getString(R.string.action_save))
        check(profile.value.controlUrl == "https://headscale.example")
        check(find(activity.getString(R.string.login_server_edit)) != null) { "Editing server left setup" }
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
        // Another window can switch profiles while this login form stays open.
        // Its pending credential must not be submitted to the newly active server.
        test.runOnMainSync { profile.value = profile.value.copy(id = "other-window-profile") }
        Thread.sleep(300)
        check(passwordNode(test.uiAutomation.rootInActiveWindow) == null) { "Previous profile's auth method survived switching" }
        click(activity.getString(R.string.auth_key_method))
        check(passwordNode(test.uiAutomation.rootInActiveWindow)?.text?.contains(key) != true) { "Previous profile's auth key survived switching" }
        click(activity.getString(R.string.auth_key_connect))
        check(submitted == null && passwordNode(test.uiAutomation.rootInActiveWindow) != null) {
            "Previous profile left a submittable credential"
        }
        click(activity.getString(R.string.cd_paste))
        click(activity.getString(R.string.auth_key_connect))
        Thread.sleep(300)
        check(submitted == key) { "Key did not reach backend" }
        check(submittedServer == "https://headscale.example") { "Login used the old default server" }
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
