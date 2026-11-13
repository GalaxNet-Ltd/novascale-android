// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import kotlinx.coroutines.flow.Flow

internal const val PRO_PRODUCT_ID = "novascale_pro_lifetime"

internal enum class ProFeature(val freeAllowance: Int) {
    TAILNETS(1), HOST_GROUPS(3), HERDR_VISITS(3),
}

/** Commercial policy is deliberately public and independent of transport/resource limits. */
internal object ProPolicy {
    fun allows(isPro: Boolean, feature: ProFeature, used: Int, additional: Int = 1): Boolean {
        require(used >= 0 && additional >= 0)
        return isPro || used.toLong() + additional <= feature.freeAllowance
    }
}

internal enum class BillingProblem { UNAVAILABLE, NETWORK, VERIFICATION, STORAGE, CANCELLED }
internal class BillingFailure(val problem: BillingProblem) : Exception(problem.name)
internal class ProLimitReached(val feature: ProFeature) : Exception(feature.name)
internal interface DebugProStore {
    suspend fun read(): Boolean?
    suspend fun write(value: Boolean?)
}
internal enum class PurchaseStatus { PURCHASED, PENDING }
// No data-class toString: purchase tokens must not appear in diagnostics.
internal class StorePurchase(val productIds: Set<String>, val token: String, val status: PurchaseStatus,
    val originalJson: String = "", val signature: String = "", val acknowledged: Boolean = false)
internal data class StoreOffer(val productId: String, val formattedPrice: String)
internal enum class VerifiedPurchase { PRO, PENDING, REVOKED }
internal enum class LaunchResult { LAUNCHED, CANCELLED, ALREADY_OWNED, UNAVAILABLE }
internal sealed interface StoreEvent {
    class Updated(val purchases: List<StorePurchase>) : StoreEvent
    data class Failed(val problem: BillingProblem) : StoreEvent
}
internal interface BillingStore {
    val events: Flow<StoreEvent>
    suspend fun offer(): StoreOffer
    suspend fun purchases(): List<StorePurchase>
    suspend fun acknowledge(purchase: StorePurchase)
}
internal fun interface PurchaseVerifier { suspend fun verify(purchase: StorePurchase): VerifiedPurchase }
internal data class CachedEntitlement(val pro: Boolean, val verifiedAt: Long)
internal interface EntitlementCache {
    suspend fun read(): CachedEntitlement?
    suspend fun write(value: CachedEntitlement)
}

internal data class PaywallState(
    val initialized: Boolean = false,
    val pro: Boolean = false,
    val community: Boolean = false,
    val configured: Boolean = false,
    val refreshing: Boolean = false,
    val purchasing: Boolean = false,
    val pending: Boolean = false,
    val offer: StoreOffer? = null,
    val problem: BillingProblem? = null,
    val restored: Boolean = false,
    val debugPro: Boolean? = null,
) {
    val hasPro: Boolean get() = debugPro ?: pro
    val communityEdition: Boolean get() = community && debugPro == null
    val canBuy: Boolean get() = debugPro == null && initialized && configured && !pro && !refreshing && !purchasing && !pending && offer != null
}
internal data class PaywallRequest(val feature: ProFeature? = null, val used: Int? = null)
