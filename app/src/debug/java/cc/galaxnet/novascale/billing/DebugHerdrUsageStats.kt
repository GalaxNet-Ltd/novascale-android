// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.galaxnet.novascale.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Read-only aggregates from the admission ledger; no host/session identifiers or telemetry. */
@Composable
internal fun DebugHerdrUsageStats(usage: HerdrUsageManager, unlimited: Boolean) {
    val state by usage.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.herdr_debug_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (unlimited) R.string.herdr_debug_unlimited else R.string.herdr_debug_free),
                style = MaterialTheme.typography.bodySmall)
            if (state.storageError) {
                Text(stringResource(R.string.herdr_debug_storage_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!state.loaded) {
                Text(stringResource(R.string.herdr_debug_loading), style = MaterialTheme.typography.bodySmall)
            } else {
                val date = remember(state.day, locale) {
                    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                    LocalDate.ofEpochDay(state.day).format(formatter) to LocalDate.ofEpochDay(state.day).plusDays(1).format(formatter)
                }
                Text(stringResource(R.string.herdr_debug_day, date.first, date.second), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.herdr_debug_counts, state.used, ProFeature.HERDR_VISITS.freeAllowance,
                    state.reserved, state.remaining), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.herdr_debug_progress, state.partialMs / 1_000.0,
                    HerdrUsageManager.MEANINGFUL_MS / 1_000), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (state.partialMs.toFloat() / HerdrUsageManager.MEANINGFUL_MS).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.herdr_debug_attachments, state.retainedVisits, state.activeVisits, state.coveredVisits),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.herdr_debug_explanation), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
