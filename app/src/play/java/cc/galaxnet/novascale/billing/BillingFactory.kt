// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.content.Context
import cc.galaxnet.novascale.BuildConfig
import kotlinx.coroutines.CoroutineScope

internal fun createBilling(context: Context, scope: CoroutineScope): BillingComponents {
    val verifier = SignedPurchaseVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY, BuildConfig.APPLICATION_ID)
    val configured = verifier.configured
    val store = if (configured) PlayBillingStore(context) else null
    val manager = PaywallManager(store ?: UnavailableBillingStore(),
        verifier, RoomEntitlementCache(context), scope, configured, debugStore = createDebugProStore(context))
    return BillingComponents(manager) { activity -> store?.launch(activity) ?: LaunchResult.UNAVAILABLE }
}
