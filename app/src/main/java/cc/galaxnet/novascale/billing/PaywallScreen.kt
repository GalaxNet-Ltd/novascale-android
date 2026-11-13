// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.galaxnet.novascale.BuildConfig
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.openReleaseWebPage
import cc.galaxnet.novascale.ui.NovaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaywallHost(components: BillingComponents, herdrUsage: (() -> HerdrUsageManager)? = null) {
    val manager = components.manager
    val request by manager.request.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (request != null) cc.galaxnet.novascale.ui.NovaModalSheet(onDismiss = manager::dismiss) {
        PaywallContent(state, request!!,
            onPurchase = { context.activity()?.let { activity -> manager.purchase { components.launch(activity) } } },
            onRestore = { manager.refresh(restore = true) }, onRetry = { manager.refresh() }, onDismiss = manager::dismiss,
            debugControls = { DebugProControls(manager, herdrUsage) })
    }
}

@Composable
internal fun PaywallContent(state: PaywallState, request: PaywallRequest,
    onPurchase: () -> Unit, onRestore: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
    debugControls: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    // Only the body scrolls. The sheet supplies the safe bottom inset for this footer.
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (state.communityEdition) R.string.pro_community_title else R.string.pro_title),
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (state.communityEdition) R.string.pro_community_description else R.string.pro_support_description),
                style = MaterialTheme.typography.bodyMedium)
            request.feature?.takeUnless { state.hasPro }?.let { feature ->
                Text(stringResource(when (feature) {
                    ProFeature.TAILNETS -> R.string.pro_tailnets_limit
                    ProFeature.HOST_GROUPS -> R.string.pro_groups_limit
                    ProFeature.HERDR_VISITS -> R.string.pro_herdr_limit
                }), color = NovaTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            for ((title, detail) in listOf(
                R.string.pro_tailnets to R.string.pro_tailnets_detail,
                R.string.pro_groups to R.string.pro_groups_detail,
                R.string.pro_herdr to R.string.pro_herdr_detail,
            )) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = NovaTheme.colors.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                    }
                }
            }
            if (state.refreshing || state.purchasing || !state.initialized) LinearProgressIndicator(Modifier.fillMaxWidth())
            if ((!state.configured && !state.hasPro) || state.problem != null) Text(stringResource(when (state.problem) {
                BillingProblem.NETWORK -> R.string.pro_network_error
                BillingProblem.VERIFICATION -> R.string.pro_verification_error
                BillingProblem.STORAGE -> R.string.pro_storage_error
                else -> R.string.pro_unavailable
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            if (state.hasPro) {
                Text(stringResource(if (state.restored) R.string.pro_restored else if (state.communityEdition) R.string.pro_community_included else R.string.pro_unlocked),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = NovaTheme.colors.primary)
            } else {
                if (state.pending) Text(stringResource(R.string.pro_pending), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.pro_purchase_scope), style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
            }
            Text(stringResource(R.string.pro_open_source_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pro_open_source_description), style = MaterialTheme.typography.bodyMedium,
                color = NovaTheme.colors.textSecondary)
            if (state.configured && state.problem != null) NovaPrimaryButton(onClick = onRetry, enabled = !state.refreshing && !state.purchasing) {
                Text(stringResource(R.string.pro_retry))
            }
            if (!state.communityEdition) NovaSecondaryButton(onClick = onRestore, enabled = state.configured && !state.refreshing && !state.purchasing,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pro_restore)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { context.openReleaseWebPage(BuildConfig.PRIVACY_POLICY_URL) }) { Text(stringResource(R.string.pro_privacy)) }
                TextButton(onClick = { context.openReleaseWebPage(BuildConfig.TERMS_OF_SERVICE_URL) }) { Text(stringResource(R.string.pro_terms)) }
            }
            debugControls()
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (state.hasPro) {
                NovaPrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
            } else {
                val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (stacked) 1 else 2,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .align(androidx.compose.ui.Alignment.CenterVertically)) {
                        Text(stringResource(R.string.pro_later), textAlign = TextAlign.Center)
                    }
                    NovaPrimaryButton(onClick = onPurchase, enabled = state.canBuy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(state.offer?.let { stringResource(R.string.pro_buy_price, it.formattedPrice) }
                            ?: stringResource(R.string.pro_buy), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}
