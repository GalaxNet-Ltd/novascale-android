// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.app.Activity
import kotlinx.coroutines.flow.emptyFlow

internal class BillingComponents(val manager: PaywallManager, val launch: suspend (Activity) -> LaunchResult)
internal class UnavailableBillingStore : BillingStore {
    override val events = emptyFlow<StoreEvent>()
    override suspend fun offer(): StoreOffer = throw BillingFailure(BillingProblem.UNAVAILABLE)
    override suspend fun purchases(): List<StorePurchase> = throw BillingFailure(BillingProblem.UNAVAILABLE)
    override suspend fun acknowledge(purchase: StorePurchase): Unit = throw BillingFailure(BillingProblem.UNAVAILABLE)
}
