// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignedPurchaseVerifierTest {
    companion object {
        private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val encoded = Base64.getEncoder().encodeToString(key.public.encoded)
        private const val PACKAGE = "cc.galaxnet.novascale"
    }
    private fun receipt() = JSONObject().put("packageName", PACKAGE).put("productId", PRO_PRODUCT_ID)
        .put("purchaseState", 0).put("purchaseToken", "fixture-token")
    private fun signed(json: String): StorePurchase {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(key.private); signer.update(json.toByteArray(Charsets.UTF_8))
        return StorePurchase(setOf(PRO_PRODUCT_ID), "fixture-token", PurchaseStatus.PURCHASED,
            json, Base64.getEncoder().encodeToString(signer.sign()))
    }

    @Test fun `valid signed permanent purchase matches package product token and state`() = runTest {
        val verifier = SignedPurchaseVerifier(encoded, PACKAGE)
        assertTrue(verifier.configured)
        assertEquals(VerifiedPurchase.PRO, verifier.verify(signed(receipt().toString())))
        val legacyToken = receipt().apply { remove("purchaseToken"); put("token", "fixture-token") }
        assertEquals(VerifiedPurchase.PRO, verifier.verify(signed(legacyToken.toString())))
    }

    @Test fun `authentic signatures for a different app product token or state are rejected`() = runTest {
        for ((field, value) in listOf("packageName" to "other.app", "productId" to "other.product",
            "purchaseToken" to "other-token", "purchaseState" to 4, "purchaseState" to 1)) {
            val failure = runCatching { SignedPurchaseVerifier(encoded, PACKAGE).verify(signed(receipt().put(field, value).toString())) }.exceptionOrNull()
            assertTrue(failure is BillingFailure)
            assertEquals(BillingProblem.VERIFICATION, (failure as BillingFailure).problem)
        }
    }

    @Test fun `tampered payload signature or key never grants`() = runTest {
        val purchase = signed(receipt().toString())
        val altered = listOf(
            StorePurchase(purchase.productIds, purchase.token, purchase.status, purchase.originalJson + " ", purchase.signature),
            StorePurchase(purchase.productIds, purchase.token, purchase.status, purchase.originalJson, "malformed"),
            StorePurchase(purchase.productIds, "changed-token", purchase.status, purchase.originalJson, purchase.signature),
        )
        for (item in altered) assertTrue(runCatching { SignedPurchaseVerifier(encoded, PACKAGE).verify(item) }.exceptionOrNull() is BillingFailure)
        for (publicKey in listOf("", "not-a-key")) {
            val verifier = SignedPurchaseVerifier(publicKey, PACKAGE)
            assertFalse(verifier.configured)
            assertTrue(runCatching { verifier.verify(purchase) }.exceptionOrNull() is BillingFailure)
        }
    }

    @Test fun `missing purchased state does not default to a completed payment`() = runTest {
        val json = receipt().apply { remove("purchaseState") }.toString()
        assertTrue(runCatching { SignedPurchaseVerifier(encoded, PACKAGE).verify(signed(json)) }.exceptionOrNull() is BillingFailure)
    }
}
