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

internal object DetailsUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val state = mutableStateOf<TailnetState>(TailnetState.Running(
            TailnetIdentity("fixture", "dev-phone.tail.example", listOf("100.64.0.42", "fd7a:115c:a1e0::42"), "dev-phone", "owner@example.invalid"), 2))
        var loggedOut = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        TailnetDetailsScreen(state.value, {}, {}, { loggedOut = true; state.value = TailnetState.Stopped })
                    }
                }
            }
        }
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
        Thread.sleep(800)
        for (label in listOf("dev-phone", "100.64.0.42", "fd7a:115c:a1e0::42", "owner@example.invalid")) {
            check(find(label) != null) { "Missing identity: $label" }
        }
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "tailnet-details-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val logout = find(activity.getString(R.string.log_out)) ?: error("Missing logout action")
        val rect = Rect(); logout.getBoundsInScreen(rect)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            test.sendPointerSync(event); event.recycle()
        }
        Thread.sleep(500)
        check(loggedOut) { "Logout was not invoked" }
        check(find("owner@example.invalid") == null) { "Logged-out view kept stale identity" }
        check(find(activity.getString(R.string.connect_tailnet)) != null) { "Logged-out view has no connect action" }
        test.runOnMainSync {
            activity.setContent { NovaScaleTheme(darkTheme = false) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { VersionsScreen() } } }
        }
        Thread.sleep(500)
        check(find(BuildConfig.VERSION_NAME) != null && find(BuildConfig.TAILSCALE_VERSION) != null) { "Version details missing" }
    }
}
