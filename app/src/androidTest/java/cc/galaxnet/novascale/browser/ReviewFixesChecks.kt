// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.OnboardingScreen
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.LocalProxyEndpoint
import cc.galaxnet.novascale.terminalfeature.TerminalCursorShapeChip
import cc.galaxnet.novascale.terminalfeature.TerminalCursorShapeOption
import cc.galaxnet.novascale.ui.*
import java.io.File
import java.net.ServerSocket

/** No account or real tailnet; exercises browser gating and the actual UI controls. */
internal object ReviewFixesChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun freshRoot(): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
            return test.uiAutomation.rootInActiveWindow
        }
        fun find(text: String, root: AccessibilityNodeInfo? = freshRoot()): AccessibilityNodeInfo? {
            root ?: return null
            root.refresh()
            if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
            for (index in 0 until root.childCount) find(text, root.getChild(index))?.let { return it }
            return null
        }
        fun text(id: Int) = activity.getString(id)
        fun until(message: String, predicate: () -> Boolean) {
            repeat(80) { test.waitForIdleSync(); if (predicate()) return; Thread.sleep(100) }
            test.uiAutomation.takeScreenshot()?.let { image ->
                File(test.targetContext.cacheDir, "review-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            error(message)
        }
        fun button(id: Int): AccessibilityNodeInfo? {
            var node = find(text(id))
            while (node != null) {
                // Text and its clickable ancestor are cached separately on API 33.
                node.refresh()
                if (node.isClickable) return node
                node = node.parent
            }
            return null
        }
        fun click(id: Int) = until("Missing action: ${text(id)}") {
            button(id)?.let { it.isEnabled && it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        fun screenshot(name: String) {
            test.waitForIdleSync(); Thread.sleep(400)
            test.uiAutomation.takeScreenshot()?.let { image ->
                File(test.targetContext.cacheDir, "review-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } ?: error("Screenshot unavailable")
        }
        val dark = mutableStateOf(false)
        ServerSocket(0).use { listener ->
            // No page is navigated in this fixture. The full browser suite tests
            // authenticated HTTP/CONNECT with the same production identity factory.
            fun session(profile: String) = ProxyIdentity(profile, "device-node").session(
                LocalProxyEndpoint("endpoint-$profile", "127.0.0.1:${listener.localPort}", false), "fixture", "synthetic", "fixture-realm")
            val profile = mutableStateOf("work-tailnet")
            val proxy = mutableStateOf(ProxyUiState(stopReason = ProxyStopReason.DISABLED))
            val visible = mutableStateOf(true)
            val model = BrowserViewModel().apply { address("http://dashboard:3000") }
            var enabled = 0
            var retries = 0
            var managed = false
            test.runOnMainSync {
                activity.setContent { NovaScaleTheme(dark.value) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    BrowserContent(proxy.value.session, { proxy.value.session === it }, model, visible = visible.value,
                        profileId = profile.value, access = browserAccess(proxy.value, profile.value),
                        onEnableProxy = { enabled++; proxy.value = ProxyUiState(ProxyStatus.RUNNING, session(profile.value)) },
                        onRetryProxy = { retries++; proxy.value = ProxyUiState(ProxyStatus.RUNNING, session(profile.value)) },
                        onManageTailnets = { managed = true })
                } } }
            }
            until("Disabled proxy alert missing") { find(text(R.string.browser_proxy_disabled_title)) != null }
            screenshot("proxy-disabled")
            click(R.string.action_cancel)
            until("Disabled browser Go did not return after dismissing alert") { button(R.string.browser_go)?.isEnabled == false }
            check(enabled == 0 && model.active.requestedUrl == null)
            click(R.string.browser_enable_proxy)
            until("Enable confirmation missing") { find(text(R.string.browser_proxy_disabled_title)) != null }
            click(R.string.browser_enable_proxy)
            until("Go stayed disabled for a running proxy") { button(R.string.browser_go)?.isEnabled == true }
            check(enabled == 1 && model.active.requestedUrl == null) { "Enabling proxy navigated without Go" }
            screenshot("proxy-ready")

            test.runOnMainSync { profile.value = "other-tailnet" }
            until("Old profile proxy was accepted") { button(R.string.browser_go)?.isEnabled == false }
            test.runOnMainSync { proxy.value = ProxyUiState(ProxyStatus.RUNNING, session(profile.value)) }
            until("New profile proxy did not become ready") { button(R.string.browser_go)?.isEnabled == true }
            test.runOnMainSync { proxy.value = ProxyUiState(ProxyStatus.FAILED) }
            click(R.string.action_retry)
            until("Retry did not recover Go") { button(R.string.browser_go)?.isEnabled == true }
            check(retries == 1)
            test.runOnMainSync { proxy.value = ProxyUiState() }
            click(R.string.browser_manage_tailnets)
            until("Disconnected browser did not open tailnet management") { managed && button(R.string.browser_go)?.isEnabled == false }
            test.runOnMainSync { visible.value = false; proxy.value = ProxyUiState(stopReason = ProxyStopReason.DISABLED) }
            Thread.sleep(300)
            check(find(text(R.string.browser_proxy_disabled_title)) == null)
            test.runOnMainSync { visible.value = true }
            until("Re-entry did not explain the disabled proxy") { find(text(R.string.browser_proxy_disabled_title)) != null }
            test.runOnMainSync { activity.setContent {} }
        }

        val cursor = mutableStateOf(TerminalCursorShapeOption.APPLICATION)
        val dialog = mutableStateOf(false)
        test.runOnMainSync {
            activity.setContent { NovaScaleTheme(dark.value) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text(R.string.appearance_cursor_shape), style = MaterialTheme.typography.titleLarge)
                    TerminalCursorShapeOption.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { option -> TerminalCursorShapeChip(option, option == cursor.value) { cursor.value = option } }
                        }
                    }
                    NovaDestructiveButton({ dialog.value = true }) { Text(text(R.string.close_terminal_title)) }
                    if (dialog.value) AlertDialog(onDismissRequest = { dialog.value = false },
                        title = { Text(text(R.string.close_terminal_title)) },
                        confirmButton = { NovaDestructiveButton({ dialog.value = false }) { Text(text(R.string.action_close)) } },
                        dismissButton = { NovaSecondaryButton({ dialog.value = false }) { Text(text(R.string.keep_session)) } })
                }
            } } }
        }
        screenshot("cursor-light")
        click(R.string.cursor_beam)
        until("Beam cursor was not selected") { cursor.value == TerminalCursorShapeOption.BEAM }
        test.runOnMainSync { dark.value = true }
        screenshot("cursor-dark")
        click(R.string.close_terminal_title)
        screenshot("destructive-dark")
        click(R.string.keep_session)
        test.runOnMainSync { dark.value = false; dialog.value = true }
        screenshot("destructive-light")
        test.runOnMainSync { activity.setContent { NovaScaleTheme(false) { OnboardingScreen(false, false) {} } } }
        screenshot("onboarding-tailnets")
        click(R.string.onboard_next); click(R.string.onboard_next)
        until("Herdr onboarding page missing") { find(text(R.string.onboard_herdr_title)) != null }
        screenshot("onboarding-herdr")
        test.runOnMainSync { activity.setContent {} }
    }
}
