// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned purchase state. Screen recreation never restarts checkout or grants Pro. */
internal class PaywallManager(
    private val store: BillingStore,
    private val verifier: PurchaseVerifier,
    private val cache: EntitlementCache,
    private val scope: CoroutineScope,
    configured: Boolean,
    community: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
    private val debugStore: DebugProStore? = null,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PaywallState(configured = configured, community = community, pro = community))
    val state = mutableState.asStateFlow()
    val debugAvailable get() = debugStore != null
    private val mutableRequest = MutableStateFlow<PaywallRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private var refreshJob: Job? = null
    private var acknowledgementRetry: Job? = null
    private var acknowledgementAttempts = 0
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val previous = if (community) null else cache.read()
                val debugPro = debugStore?.read()
                mutableState.update { it.copy(initialized = true, pro = community || previous?.pro == true, debugPro = debugPro) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(initialized = true, problem = BillingProblem.STORAGE) } }
            finally { loaded.complete(Unit) }
        }
        scope.launch {
            store.events.collect { event ->
                loaded.await()
                mutex.withLock {
                    when (event) {
                        is StoreEvent.Updated -> guarded { reconcile(event.purchases, authoritative = false) }
                        is StoreEvent.Failed -> mutableState.update {
                            it.copy(purchasing = false, problem = event.problem.takeUnless { p -> p == BillingProblem.CANCELLED })
                        }
                    }
                }
            }
        }
    }

    fun show(feature: ProFeature? = null, used: Int? = null) {
        mutableRequest.value = PaywallRequest(feature, used)
        refresh()
    }
    fun dismiss() { mutableRequest.value = null }

    suspend fun awaitReady() { loaded.await() }
    suspend fun setDebugPro(value: Boolean?) {
        val store = checkNotNull(debugStore) { "Debug overrides are unavailable" }
        // A sheet disappearing must not cancel between persistence and state publication.
        scope.async {
            loaded.await()
            mutex.withLock {
                store.write(value)
                mutableState.update { it.copy(debugPro = value, restored = false) }
            }
        }.await()
    }

    fun requireAccess(feature: ProFeature, used: Int) {
        if (!allows(feature, used)) throw ProLimitReached(feature)
    }

    /** Call at the feature's serialized mutation, using its current count, before changing state. */
    fun allows(feature: ProFeature, used: Int, showPaywall: Boolean = true): Boolean {
        val allowed = state.value.initialized && ProPolicy.allows(state.value.hasPro, feature, used)
        if (!allowed && showPaywall) show(feature, used)
        return allowed
    }

    fun refresh(restore: Boolean = false) {
        if (state.value.community || state.value.debugPro != null || !state.value.configured || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            loaded.await()
            if (state.value.debugPro != null) return@launch
            mutex.withLock {
                mutableState.update { it.copy(refreshing = true, problem = null, restored = false) }
                try {
                    // Ownership restoration must work even when the product is unavailable for new sale.
                    val ownershipChecked = guarded { reconcile(store.purchases(), authoritative = true) }
                    try {
                        val offer = store.offer()
                        mutableState.update { it.copy(offer = offer) }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update {
                        it.copy(offer = null, problem = it.problem ?: BillingProblem.UNAVAILABLE.takeUnless { _ -> it.pro })
                    } }
                    if (restore && ownershipChecked && state.value.pro && !state.value.pending) {
                        mutableState.update { it.copy(restored = true) }
                    }
                } finally { mutableState.update { it.copy(refreshing = false) } }
            }
        }
    }

    /** The Activity stays in the caller's short launch operation, never in saved manager state. */
    fun purchase(launch: suspend () -> LaunchResult) {
        if (!state.value.canBuy) return
        mutableState.update { it.copy(purchasing = true, problem = null, restored = false) }
        scope.launch {
            try {
                when (launch()) {
                    LaunchResult.LAUNCHED -> Unit // The store callback or foreground reconciliation completes this.
                    LaunchResult.CANCELLED -> mutableState.update { it.copy(purchasing = false) }
                    LaunchResult.ALREADY_OWNED -> { mutableState.update { it.copy(purchasing = false) }; refresh(restore = true) }
                    LaunchResult.UNAVAILABLE -> mutableState.update { it.copy(purchasing = false, problem = BillingProblem.UNAVAILABLE) }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(purchasing = false) }; throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(purchasing = false, problem = BillingProblem.UNAVAILABLE) }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        try { block(); return true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BillingFailure) {
            mutableState.update { it.copy(purchasing = false, problem = failure.problem) }
        } catch (_: Exception) {
            mutableState.update { it.copy(purchasing = false, problem = BillingProblem.VERIFICATION) }
        }
        return false
    }

    private suspend fun reconcile(purchases: List<StorePurchase>, authoritative: Boolean) {
        val relevant = purchases.filter { PRO_PRODUCT_ID in it.productIds }
        if (relevant.isEmpty() && !authoritative) return
        var pro = false
        var pending = false
        val needsAcknowledgement = mutableListOf<StorePurchase>()
        for (purchase in relevant.distinctBy { it.token }) {
            if (purchase.status == PurchaseStatus.PENDING) { pending = true; continue }
            when (verifier.verify(purchase)) {
                VerifiedPurchase.PRO -> { pro = true; if (!purchase.acknowledged) needsAcknowledgement += purchase }
                VerifiedPurchase.PENDING -> pending = true
                VerifiedPurchase.REVOKED -> Unit
            }
        }
        // Pending payment does not remove a previously verified grant.
        val nextPro = pro || (pending && state.value.pro)
        mutableState.update { it.copy(pro = nextPro, pending = pending && !pro, purchasing = false, problem = null) }
        try { cache.write(CachedEntitlement(nextPro, now())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(problem = BillingProblem.STORAGE) } }
        // Deliver/persist first, then acknowledge this non-consumable. Never call consumeAsync.
        try {
            for (purchase in needsAcknowledgement) store.acknowledge(purchase)
            acknowledgementAttempts = 0
            acknowledgementRetry?.cancel(); acknowledgementRetry = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(problem = BillingProblem.NETWORK) }
            if (acknowledgementRetry?.isActive != true && acknowledgementAttempts < 4) {
                val delayMillis = longArrayOf(2_000, 10_000, 30_000, 120_000)[acknowledgementAttempts++]
                acknowledgementRetry = scope.launch { delay(delayMillis); acknowledgementRetry = null; refresh() }
            }
        }
        // Keep the sheet open to clearly confirm purchase/restore; never repeat a remote action here.
    }
}
