// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.billing.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

/** Real manager/UI/Room with a fixture store. Never opens checkout or uses an owner's account. */
internal object BillingUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val databaseName = "billing-fixture-${UUID.randomUUID()}.db"
        val cache = RoomEntitlementCache(test.targetContext, databaseName)
        val events = MutableSharedFlow<StoreEvent>(extraBufferCapacity = 4)
        var owned = emptyList<StorePurchase>()
        var acknowledgeCount = 0
        val store = object : BillingStore {
            override val events = events
            override suspend fun offer() = StoreOffer(PRO_PRODUCT_ID, "$2.99")
            override suspend fun purchases() = owned
            override suspend fun acknowledge(purchase: StorePurchase) { acknowledgeCount++ }
        }
        lateinit var manager: PaywallManager
        fun find(label: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                // Refresh virtual children as well as the root; scroll events can leave cached viewport bounds stale.
                if (!node.refresh()) return null
                if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun scroll(forward: Boolean) {
            fun visit(node: AccessibilityNodeInfo?): Boolean {
                node ?: return false
                if (!node.refresh()) return false
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (node.isScrollable && node.performAction(action)) return true
                for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
                return false
            }
            visit(test.uiAutomation.rootInActiveWindow)
        }
        fun waitFor(label: String): AccessibilityNodeInfo {
            repeat(60) { attempt ->
                find(label)?.let { return it }
                if (attempt % 10 == 9) scroll(forward = attempt % 20 == 9)
                Thread.sleep(100)
            }
            error("Missing billing element: $label")
        }
        fun click(label: String) {
            waitFor(label)
            // Modal dismissal/reopening and purchase callbacks replace accessibility nodes asynchronously.
            repeat(60) {
                var node = find(label)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync(); return
                }
                Thread.sleep(100)
            }
            error("Billing action never became actionable: $label")
        }
        fun buttonBounds(label: String): Rect {
            var node = checkNotNull(find(label)) { "Footer action is off screen: $label" }
            while (!node.isClickable) node = checkNotNull(node.parent)
            return Rect().also { node.getBoundsInScreen(it) }
        }
        fun awaitCondition(message: String, condition: () -> Boolean) {
            repeat(60) { if (condition()) return; Thread.sleep(100) }
            error(message)
        }
        var cancelNextPurchase = true
        var launchCount = 0
        try {
            test.runOnMainSync {
                manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, cache, scope, configured = true)
                manager.show(ProFeature.HOST_GROUPS, 3)
                val components = BillingComponents(manager) {
                    launchCount++
                    if (cancelNextPurchase) LaunchResult.CANCELLED else {
                        owned = listOf(StorePurchase(setOf(PRO_PRODUCT_ID), "fixture-token", PurchaseStatus.PENDING))
                        events.tryEmit(StoreEvent.Updated(owned)); LaunchResult.LAUNCHED
                    }
                }
                activity.setContent {
                    NovaScaleTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            PaywallHost(components)
                        }
                    }
                }
            }
            waitFor(test.targetContext.getString(R.string.pro_groups_limit))
            val buy = test.targetContext.getString(R.string.pro_buy_price, "$2.99")
            val later = test.targetContext.getString(R.string.pro_later)
            val forkCopy = test.targetContext.getString(R.string.pro_open_source_description)
            waitFor(buy)
            val initialBuyBounds = buttonBounds(buy)
            val initialLaterBounds = buttonBounds(later)
            waitFor(forkCopy)
            check(buttonBounds(buy) == initialBuyBounds && buttonBounds(later) == initialLaterBounds) {
                "Paywall footer moved with the scrollable content"
            }
            click(later)
            awaitCondition("Later did not dismiss the sheet") { manager.request.value == null }
            awaitCondition("Dismissed sheet is still visible") { find(later) == null }
            check(owned.isEmpty() && !manager.state.value.purchasing)
            test.runOnMainSync { manager.show(ProFeature.HOST_GROUPS, 3) }
            waitFor(buy)
            click(buy)
            awaitCondition("Checkout did not recover after cancellation") { launchCount == 1 && manager.state.value.canBuy }
            check(!manager.state.value.pro)
            cancelNextPurchase = false
            click(buy)
            awaitCondition("Pending payment was not retained") { manager.state.value.pending }
            check(!manager.state.value.pro && acknowledgeCount == 0)
            test.runOnMainSync {
                owned = listOf(StorePurchase(setOf(PRO_PRODUCT_ID), "fixture-token", PurchaseStatus.PURCHASED))
                events.tryEmit(StoreEvent.Updated(owned))
            }
            awaitCondition("Verified purchase was not delivered and acknowledged") { manager.state.value.pro && acknowledgeCount == 1 }
            waitFor(test.targetContext.getString(R.string.pro_unlocked))
            click(test.targetContext.getString(R.string.pro_restore))
            awaitCondition("Restore did not update purchase state") { manager.state.value.restored }
            waitFor(test.targetContext.getString(R.string.pro_restored))
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(test.targetContext.cacheDir, "billing-restored.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scope.cancel()
            cache.close()
            // Verify durable metadata survives reopening the actual Room database.
            val reopened = RoomEntitlementCache(test.targetContext, databaseName)
            try { check(runBlocking { reopened.read() }?.pro == true) } finally { reopened.close() }

            // Small viewport + large type: verify the actual content/footer layout, not a screenshot-only approximation.
            var laterClicks = 0
            var buyClicks = 0
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                        NovaScaleTheme(darkTheme = false) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                androidx.compose.material3.Surface(Modifier.width(320.dp).height(360.dp)) {
                                    PaywallContent(PaywallState(initialized = true, configured = true,
                                        offer = StoreOffer(PRO_PRODUCT_ID, "$2.99")), PaywallRequest(ProFeature.HERDR_VISITS, 3),
                                        onPurchase = { buyClicks++ }, onRestore = {}, onRetry = {}, onDismiss = { laterClicks++ })
                                }
                            }
                        }
                    }
                }
            }
            waitFor(later)
            val smallBuyBounds = buttonBounds(buy)
            val smallLaterBounds = buttonBounds(later)
            check(!smallBuyBounds.isEmpty && !Rect.intersects(smallBuyBounds, smallLaterBounds))
            check(smallBuyBounds.top >= smallLaterBounds.bottom) { "Large-text actions should stack without squeezing the price" }
            var sawForkCopy = find(forkCopy) != null
            for (page in 0 until 30) {
                if (sawForkCopy) break
                scroll(forward = true); test.waitForIdleSync(); Thread.sleep(300)
                check(buttonBounds(buy) == smallBuyBounds && buttonBounds(later) == smallLaterBounds) {
                    "Large-text footer moved or became unreachable"
                }
                sawForkCopy = find(forkCopy) != null
            }
            check(sawForkCopy) { "Open-source copy is unreachable with large text" }
            click(later); click(buy)
            check(laterClicks == 1 && buyClicks == 1)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(test.targetContext.cacheDir, "billing-large-text.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            File(test.targetContext.cacheDir, "billing-failure-tree.txt").writeText(buildString {
                fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
                    node ?: return
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    appendLine("${" ".repeat(depth)}visible=${node.isVisibleToUser} bounds=$bounds text=${node.text}")
                    for (i in 0 until node.childCount) appendNode(node.getChild(i), depth + 1)
                }
                appendNode(test.uiAutomation.rootInActiveWindow, 0)
            })
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(test.targetContext.cacheDir, "billing-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw failure
        } finally {
            scope.cancel()
            cache.close()
            test.targetContext.deleteDatabase(databaseName)
        }
    }
}
