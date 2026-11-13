// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.HostDetailScreen
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.ui.NovaIcons
import cc.galaxnet.novascale.ui.NovaScaleTheme

internal object UiLayoutChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity, dark: Boolean = false) {
        val peer = TailnetPeer("fixture", "dev-server", "dev-server.example.ts.net", listOf("100.64.0.42"), true, true, "linux")
        var settingsOpened = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        NovaScaleTopBar("dev-server", true, {}, actions = {
                            IconButton(onClick = { settingsOpened = true }) { Icon(NovaIcons.Ssh, "SSH Settings") }
                        })
                        HostDetailScreen(peer, {}, {}, {}, {})
                    }
                }
            }
        }
        Thread.sleep(800)
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        var root = test.uiAutomation.rootInActiveWindow
        for (attempt in 0 until 40) {
            if (find(root, "Terminal") != null) break
            Thread.sleep(100)
            root = test.uiAutomation.rootInActiveWindow
        }
        for (label in listOf("Terminal", "Web Access", "Files", "Ping")) check(find(root, label) != null) { "Missing host card: $label" }
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, if (dark) "host-cards-dark-check.png" else "host-cards-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val button = find(root, "SSH Settings") ?: error("Missing SSH toolbar action")
        val bounds = android.graphics.Rect(); button.getBoundsInScreen(bounds)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val e = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            test.sendPointerSync(e); e.recycle()
        }
        test.waitForIdleSync()
        check(settingsOpened) { "SSH toolbar action did not open settings" }
    }
}
