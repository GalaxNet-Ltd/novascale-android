// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallManagerTest {
    private class Cache(var value: CachedEntitlement? = null) : EntitlementCache {
        var broken = false
        override suspend fun read() = value
        override suspend fun write(value: CachedEntitlement) {
            if (broken) error("storage failed")
            this.value = value
        }
    }
    private class Store : BillingStore {
        override val events = MutableSharedFlow<StoreEvent>()
        var owned = emptyList<StorePurchase>()
        var failure: BillingProblem? = null
        var missingOffer = false
        var reads = 0
        var acknowledgements = 0
        var acknowledgementFails = false
        override suspend fun acknowledge(purchase: StorePurchase) {
            acknowledgements++
            if (acknowledgementFails) throw BillingFailure(BillingProblem.NETWORK)
        }
        override suspend fun purchases(): List<StorePurchase> {
            reads++
            failure?.let { throw BillingFailure(it) }
            return owned
        }
        override suspend fun offer(): StoreOffer {
            if (missingOffer) throw BillingFailure(BillingProblem.UNAVAILABLE)
            return StoreOffer(PRO_PRODUCT_ID, "€2.99")
        }
    }
    private fun purchased(token: String = "fixture-token") = StorePurchase(setOf(PRO_PRODUCT_ID), token, PurchaseStatus.PURCHASED)

    @Test fun `only approved allowances are limited and Pro does not change resource limits`() {
        assertTrue(ProPolicy.allows(false, ProFeature.TAILNETS, 0))
        assertFalse(ProPolicy.allows(false, ProFeature.TAILNETS, 1))
        for (feature in listOf(ProFeature.HOST_GROUPS, ProFeature.HERDR_VISITS)) {
            assertTrue(ProPolicy.allows(false, feature, 2))
            assertFalse(ProPolicy.allows(false, feature, 3))
            assertTrue(ProPolicy.allows(true, feature, Int.MAX_VALUE))
            assertFalse(ProPolicy.allows(false, feature, Int.MAX_VALUE, Int.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) { ProPolicy.allows(false, ProFeature.TAILNETS, -1) }
    }

    @Test fun `successful purchase verifies before granting and persists for restart`() = runTest {
        val store = Store(); val cache = Cache(); var verified = 0
        val manager = PaywallManager(store, PurchaseVerifier { verified++; VerifiedPurchase.PRO }, cache, backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        assertTrue(manager.state.value.canBuy)
        manager.purchase { LaunchResult.LAUNCHED }; runCurrent()
        assertTrue(manager.state.value.purchasing)
        store.events.emit(StoreEvent.Updated(listOf(purchased(), purchased())))
        runCurrent()
        assertEquals(1, verified)
        assertEquals(1, store.acknowledgements)
        assertTrue(manager.state.value.pro)
        assertFalse(manager.state.value.purchasing)
        assertTrue(cache.value!!.pro)
        val offline = Store().apply { failure = BillingProblem.NETWORK }
        val restarted = PaywallManager(offline, PurchaseVerifier { error("must not verify offline") }, cache, backgroundScope, true)
        runCurrent(); restarted.refresh(); runCurrent()
        assertTrue(restarted.state.value.pro)
        assertEquals(BillingProblem.NETWORK, restarted.state.value.problem)
    }

    @Test fun `pending purchases never grant or verify and complete through a later store event`() = runTest {
        val store = Store(); var calls = 0
        store.owned = listOf(StorePurchase(setOf(PRO_PRODUCT_ID), "pending", PurchaseStatus.PENDING))
        val manager = PaywallManager(store, PurchaseVerifier { calls++; VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        assertFalse(manager.state.value.pro); assertTrue(manager.state.value.pending)
        assertFalse(manager.state.value.canBuy); assertEquals(0, calls)
        assertEquals(0, store.acknowledgements)
        store.events.emit(StoreEvent.Updated(listOf(purchased("pending")))); runCurrent()
        assertTrue(manager.state.value.pro); assertFalse(manager.state.value.pending); assertEquals(1, calls)
    }

    @Test fun `verifier pending response overrides purchased callback until verification succeeds`() = runTest {
        val store = Store().apply { owned = listOf(purchased()) }
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PENDING }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        assertFalse(manager.state.value.pro); assertTrue(manager.state.value.pending)
    }

    @Test fun `verification outages keep prior grants but cannot grant a fresh installation`() = runTest {
        for (previous in listOf(false, true)) {
            val cache = Cache(CachedEntitlement(previous, 1))
            val store = Store().apply { owned = listOf(purchased()) }
            val manager = PaywallManager(store, PurchaseVerifier { throw BillingFailure(BillingProblem.NETWORK) }, cache, backgroundScope, true)
            runCurrent(); manager.refresh(); runCurrent()
            assertEquals(previous, manager.state.value.pro)
            assertEquals(previous, cache.value!!.pro)
        }
    }

    @Test fun `confirmed revocation and successful empty ownership query remove cached Pro`() = runTest {
        for (owned in listOf(emptyList(), listOf(purchased()))) {
            val cache = Cache(CachedEntitlement(true, 1))
            val store = Store().apply { this.owned = owned }
            val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.REVOKED }, cache, backgroundScope, true)
            runCurrent(); manager.refresh(); runCurrent()
            assertFalse(manager.state.value.pro); assertFalse(cache.value!!.pro)
        }
    }

    @Test fun `cancelled and unrelated callbacks do not remove purchased access`() = runTest {
        val store = Store(); val cache = Cache(CachedEntitlement(true, 1))
        val manager = PaywallManager(store, PurchaseVerifier { error("unrelated purchase") }, cache, backgroundScope, true)
        runCurrent()
        store.events.emit(StoreEvent.Failed(BillingProblem.CANCELLED)); runCurrent()
        store.events.emit(StoreEvent.Updated(listOf(StorePurchase(setOf("other"), "other", PurchaseStatus.PURCHASED)))); runCurrent()
        assertTrue(manager.state.value.pro); assertNull(manager.state.value.problem)
    }

    @Test fun `restoration works when product is no longer offered for sale`() = runTest {
        val store = Store().apply { owned = listOf(purchased()); missingOffer = true }
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(restore = true); runCurrent()
        assertTrue(manager.state.value.pro); assertTrue(manager.state.value.restored)
        assertNull(manager.state.value.problem); assertNull(manager.state.value.offer)
    }

    @Test fun `failed restore keeps cached access without claiming restoration succeeded`() = runTest {
        val store = Store().apply { failure = BillingProblem.NETWORK }
        val cache = Cache(CachedEntitlement(true, 1))
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, cache, backgroundScope, true)
        runCurrent(); manager.refresh(restore = true); runCurrent()
        assertTrue(manager.state.value.pro); assertFalse(manager.state.value.restored)
        assertEquals(BillingProblem.NETWORK, manager.state.value.problem)
        store.failure = null
        store.owned = listOf(purchased())
        manager.refresh(restore = true); runCurrent()
        assertTrue(manager.state.value.pro); assertTrue(manager.state.value.restored)
        assertNull(manager.state.value.problem)
    }

    @Test fun `already owned response restores instead of launching a second charge`() = runTest {
        val store = Store()
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        store.owned = listOf(purchased())
        manager.purchase { LaunchResult.ALREADY_OWNED }; runCurrent()
        assertTrue(manager.state.value.pro); assertTrue(manager.state.value.restored)
    }

    @Test fun `double tapping purchase launches one checkout and cancellation permits retry`() = runTest {
        val store = Store(); var launches = 0
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        manager.purchase { launches++; LaunchResult.LAUNCHED }
        manager.purchase { launches++; LaunchResult.LAUNCHED }
        runCurrent(); assertEquals(1, launches)
        store.events.emit(StoreEvent.Failed(BillingProblem.CANCELLED)); runCurrent()
        assertTrue(manager.state.value.canBuy); assertNull(manager.state.value.problem)
    }

    @Test fun `unconfigured checkout cannot launch and community never queries the store`() = runTest {
        for (community in listOf(false, true)) {
            val store = Store()
            val manager = PaywallManager(store, PurchaseVerifier { error("not configured") }, Cache(), backgroundScope, false, community)
            runCurrent(); manager.show(ProFeature.HOST_GROUPS, 3); manager.purchase { error("must not launch") }; runCurrent()
            assertEquals(0, store.reads); assertFalse(manager.state.value.canBuy)
            assertEquals(community, manager.state.value.pro)
            assertEquals(community, manager.allows(ProFeature.HOST_GROUPS, 3, showPaywall = false))
            manager.dismiss(); assertNull(manager.request.value)
        }
    }

    @Test fun `debug override survives caller disposal without changing store ownership`() = runTest {
        val written = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var persisted: Boolean? = null
        val manager = PaywallManager(Store(), PurchaseVerifier { VerifiedPurchase.PRO }, Cache(CachedEntitlement(true, 1)),
            backgroundScope, false, debugStore = object : DebugProStore {
                override suspend fun read(): Boolean? = null
                override suspend fun write(value: Boolean?) {
                    persisted = value; written.complete(Unit); finishWrite.await()
                }
            })
        runCurrent()
        val screen = launch { manager.setDebugPro(false) }
        written.await(); screen.cancelAndJoin()
        finishWrite.complete(Unit); runCurrent()
        assertEquals(false, persisted); assertFalse(manager.state.value.hasPro)
        assertTrue(manager.state.value.pro)
        assertFalse(manager.allows(ProFeature.TAILNETS, 1, showPaywall = false))
    }

    @Test fun `cache write failure does not lose a paid grant for the current process`() = runTest {
        val cache = Cache().apply { broken = true }
        val store = Store().apply { owned = listOf(purchased()) }
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, cache, backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        assertTrue(manager.state.value.pro); assertEquals(BillingProblem.STORAGE, manager.state.value.problem)
    }

    @Test fun `acknowledgement failure preserves delivered access and retries`() = runTest {
        val store = Store().apply { owned = listOf(purchased()); acknowledgementFails = true }
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent()
        assertTrue(manager.state.value.pro); assertEquals(1, store.acknowledgements)
        store.acknowledgementFails = false
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, store.acknowledgements); assertTrue(manager.state.value.pro)
    }

    @Test fun `already acknowledged purchases and invalid signatures never acknowledge`() = runTest {
        val store = Store().apply { owned = listOf(StorePurchase(setOf(PRO_PRODUCT_ID), "token", PurchaseStatus.PURCHASED, acknowledged = true)) }
        val manager = PaywallManager(store, PurchaseVerifier { VerifiedPurchase.PRO }, Cache(), backgroundScope, true)
        runCurrent(); manager.refresh(); runCurrent(); assertEquals(0, store.acknowledgements)
        val invalid = PaywallManager(store, PurchaseVerifier { throw BillingFailure(BillingProblem.VERIFICATION) }, Cache(), backgroundScope, true)
        runCurrent(); invalid.refresh(); runCurrent()
        assertFalse(invalid.state.value.pro); assertEquals(0, store.acknowledgements)
    }
}
