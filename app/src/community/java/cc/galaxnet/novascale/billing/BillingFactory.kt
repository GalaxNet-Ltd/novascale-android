// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Community builds have all local features without a store connection or billing SDK. */
internal fun createBilling(context: Context, scope: CoroutineScope): BillingComponents = BillingComponents(
    PaywallManager(UnavailableBillingStore(), PurchaseVerifier { throw BillingFailure(BillingProblem.UNAVAILABLE) },
        RoomEntitlementCache(context), scope, configured = false, community = true, debugStore = createDebugProStore(context)),
    launch = { LaunchResult.UNAVAILABLE },
)
