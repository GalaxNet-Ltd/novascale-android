/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.HostDetailScreen
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.remote.AuthenticationKind
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshTransport
import cc.galaxnet.novascale.remote.TraditionalHostDetailScreen
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** Exercises debug Herdr host actions using synthetic host data only. */
internal object HerdrHostUiChecks {
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
        fun scrollForward(): Boolean {
            fun visit(node: AccessibilityNodeInfo?): Boolean {
                node ?: return false
                for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun click(label: String) {
            repeat(6) {
                var node: AccessibilityNodeInfo? = null
                repeat(10) { if (node == null) { node = find(label); if (node == null) Thread.sleep(100) } }
                var action = node
                while (action != null && !action.isClickable) action = action.parent
                if (action?.isVisibleToUser == true && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync()
                    return
                }
                if (!scrollForward()) Thread.sleep(150)
                test.waitForIdleSync()
            }
            error("Herdr action could not be activated: $label")
        }
        val title = activity.getString(R.string.herdr_title)
        val peer = TailnetPeer("fixture-node", "Fixture peer", "peer.invalid", listOf("100.64.0.5"), true, true)
        var tailnetOpened = false
        test.runOnMainSync {
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HostDetailScreen(peer, {}, {}, {}, {}, onHerdr = { tailnetOpened = true })
                    }
                }
            }
        }
        click(title)
        check(tailnetOpened) { "Tailnet host did not dispatch its Herdr action" }

        val repository = (test.targetContext.applicationContext as NovaScaleApplication).traditionalHosts
        val config = RemoteConnectionConfig(host = "herdr-fixture.invalid", port = "2222", username = "fixture",
            authenticationKind = AuthenticationKind.PASSWORD, password = "fixture-only", transport = SshTransport.DIRECT)
        val host = runBlocking { repository.save(null, "Herdr UI fixture ${UUID.randomUUID()}", config) }
        val opened = AtomicReference<RemoteConnectionConfig?>()
        try {
            test.runOnMainSync {
                activity.setContent {
                    NovaScaleTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TraditionalHostDetailScreen(host, repository, {}, {}, {}, {}, onHerdr = { opened.set(it) })
                        }
                    }
                }
            }
            var detailsReady = false
            repeat(50) {
                if (!detailsReady) {
                    detailsReady = find(host.name) != null
                    if (!detailsReady) Thread.sleep(100)
                }
            }
            check(detailsReady) { "Traditional host details did not appear" }
            click(title)
            check(opened.get() == config) { "Traditional host did not dispatch its saved SSH configuration to Herdr" }
        } finally {
            runBlocking { repository.delete(host) }
        }
    }
}
