// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Verify Google's signed receipt locally. Ownership/refunds still come from fresh Play queries. */
internal class SignedPurchaseVerifier(encodedPublicKey: String, private val packageName: String) : PurchaseVerifier {
    private val publicKey: PublicKey? = runCatching {
        require(encodedPublicKey.length in 1..8192)
        val bytes = Base64.getDecoder().decode(encodedPublicKey.filterNot(Char::isWhitespace))
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()
    val configured get() = publicKey != null

    override suspend fun verify(purchase: StorePurchase): VerifiedPurchase = withContext(Dispatchers.Default) {
        try {
            val key = publicKey ?: throw BillingFailure(BillingProblem.UNAVAILABLE)
            require(purchase.originalJson.length in 1..65536 && purchase.signature.length in 1..4096)
            // RSA/SHA-1 is the Google Play receipt wire format, not an algorithm chosen for new signatures.
            val checker = Signature.getInstance("SHA1withRSA")
            checker.initVerify(key)
            checker.update(purchase.originalJson.toByteArray(Charsets.UTF_8))
            require(checker.verify(Base64.getDecoder().decode(purchase.signature.filterNot(Char::isWhitespace))))
            val receipt = JSONObject(purchase.originalJson)
            require(receipt.getString("packageName") == packageName)
            val productIds = receipt.optJSONArray("productIds")?.let { values ->
                (0 until values.length()).map { values.getString(it) }.toSet()
            } ?: setOf(receipt.getString("productId"))
            require(productIds == purchase.productIds && PRO_PRODUCT_ID in productIds)
            val token = receipt.optString("token").ifBlank { receipt.optString("purchaseToken") }
            require(token.isNotBlank() && token == purchase.token)
            // Client receipt state 0 means purchased. Do not rely on a missing field's default.
            require(receipt.getInt("purchaseState") == 0 && purchase.status == PurchaseStatus.PURCHASED)
            VerifiedPurchase.PRO
        } catch (known: BillingFailure) { throw known }
        catch (_: Exception) { throw BillingFailure(BillingProblem.VERIFICATION) }
    }
}
