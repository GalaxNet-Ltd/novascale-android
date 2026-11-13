// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.content.Context
import androidx.compose.runtime.Composable

internal fun createDebugProStore(context: Context): DebugProStore? = null
@Composable internal fun DebugProControls(manager: PaywallManager, herdrUsage: (() -> HerdrUsageManager)? = null) = Unit
