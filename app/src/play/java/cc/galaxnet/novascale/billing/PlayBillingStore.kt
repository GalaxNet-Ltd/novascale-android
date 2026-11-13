// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import cc.galaxnet.novascale.BuildConfig
import com.android.billingclient.api.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-wide client, owned by NovaScaleApplication. No purchase token/debug-message logging. */
internal class PlayBillingStore(context: Context) : BillingStore {
    private val updates = Channel<StoreEvent>(Channel.BUFFERED)
    override val events = updates.receiveAsFlow()
    private val connectMutex = Mutex()
    private val client = BillingClient.newBuilder(context.applicationContext)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, purchases ->
            val event = if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                StoreEvent.Updated(purchases.mapNotNull(::purchase))
            } else StoreEvent.Failed(problem(result))
            updates.trySend(event)
        }.build()
    private var details: ProductDetails? = null
    private var offerToken: String? = null

    private suspend fun <T : Any> limited(block: suspend () -> T): T =
        withTimeoutOrNull(15_000) { block() } ?: throw BillingFailure(BillingProblem.NETWORK)

    private suspend fun connect() = connectMutex.withLock {
        if (client.isReady) return@withLock
        limited {
            suspendCancellableCoroutine<Unit> { continuation ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!continuation.isActive) return
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                        else continuation.resumeWithException(BillingFailure(problem(result)))
                    }
                    override fun onBillingServiceDisconnected() = Unit
                })
            }
        }
    }

    override suspend fun offer(): StoreOffer = withContext(Dispatchers.Main.immediate) {
        connect()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP).build(),
        )).build()
        limited {
            suspendCancellableCoroutine { continuation ->
                client.queryProductDetailsAsync(params) { result, response ->
                    if (!continuation.isActive) return@queryProductDetailsAsync
                    val product = response.productDetailsList.singleOrNull { it.productId == PRO_PRODUCT_ID }
                    // Console setup specifies exactly one permanent "buy" option; do not accidentally sell a rental.
                    val option = product?.oneTimePurchaseOfferDetailsList?.singleOrNull { it.purchaseOptionId == "buy" }
                    if (result.responseCode != BillingClient.BillingResponseCode.OK || product == null || option == null) {
                        details = null; offerToken = null
                        continuation.resumeWithException(BillingFailure(BillingProblem.UNAVAILABLE))
                    } else {
                        details = product; offerToken = option.offerToken
                        continuation.resume(StoreOffer(PRO_PRODUCT_ID, option.formattedPrice))
                    }
                }
            }
        }
    }

    override suspend fun purchases(): List<StorePurchase> = withContext(Dispatchers.Main.immediate) {
        connect()
        limited {
            suspendCancellableCoroutine { continuation ->
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, owned ->
                    if (!continuation.isActive) return@queryPurchasesAsync
                    if (BuildConfig.BILLING_DIAGNOSTICS) logOwnership(result, owned)
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(owned.mapNotNull(::purchase))
                    else continuation.resumeWithException(BillingFailure(problem(result)))
                }
            }
        }
    }

    /** Internal-test only: no tokens, receipt bodies, signatures or raw order/account IDs. */
    private fun logOwnership(result: BillingResult, owned: List<Purchase>) {
        runCatching {
            Log.i("NovaBilling", "ownership response=${result.responseCode} count=${owned.size}")
            for (value in owned.filter { PRO_PRODUCT_ID in it.products }) {
                val order = value.orderId?.takeIf { it.isNotBlank() }?.let {
                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                        .take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                } ?: "missing"
                Log.i("NovaBilling", "pro orderHash=$order state=${value.purchaseState} acknowledged=${value.isAcknowledged}")
            }
        } // Diagnostics must not affect entitlement reconciliation.
    }

    suspend fun launch(activity: Activity): LaunchResult = withContext(Dispatchers.Main.immediate) {
        if (activity.isFinishing || activity.isDestroyed) return@withContext LaunchResult.UNAVAILABLE
        offer() // Refresh ProductDetails immediately before checkout; do not cache them across app launches.
        if (activity.isFinishing || activity.isDestroyed) return@withContext LaunchResult.UNAVAILABLE
        val product = details ?: return@withContext LaunchResult.UNAVAILABLE
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        offerToken?.takeIf { it.isNotBlank() }?.let(item::setOfferToken)
        when (client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build()).responseCode) {
            BillingClient.BillingResponseCode.OK -> LaunchResult.LAUNCHED
            BillingClient.BillingResponseCode.USER_CANCELED -> LaunchResult.CANCELLED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.ALREADY_OWNED
            else -> LaunchResult.UNAVAILABLE
        }
    }

    override suspend fun acknowledge(purchase: StorePurchase): Unit = withContext(Dispatchers.Main.immediate) {
        if (purchase.acknowledged) return@withContext
        connect()
        limited {
            suspendCancellableCoroutine { continuation ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.token).build()) { result ->
                    if (!continuation.isActive) return@acknowledgePurchase
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                    else continuation.resumeWithException(BillingFailure(problem(result)))
                }
            }
        }
    }

    private fun purchase(value: Purchase): StorePurchase? {
        val status = when (value.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseStatus.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseStatus.PENDING
            else -> return null
        }
        return StorePurchase(value.products.toSet(), value.purchaseToken, status, value.originalJson, value.signature, value.isAcknowledged)
    }
    private fun problem(result: BillingResult): BillingProblem = when (result.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> BillingProblem.CANCELLED
        BillingClient.BillingResponseCode.NETWORK_ERROR, BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingProblem.NETWORK
        else -> BillingProblem.UNAVAILABLE
    }
}
