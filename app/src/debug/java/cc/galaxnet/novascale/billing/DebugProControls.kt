// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.galaxnet.novascale.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.proDebugData by preferencesDataStore("pro-debug")
internal fun createDebugProStore(context: Context): DebugProStore = object : DebugProStore {
    private val data = context.applicationContext.proDebugData
    private val key = booleanPreferencesKey("pro_override")
    override suspend fun read() = data.data.first()[key]
    override suspend fun write(value: Boolean?) { data.edit { if (value == null) it.remove(key) else it[key] = value } }
}

@Composable
internal fun DebugProControls(manager: PaywallManager, herdrUsage: (() -> HerdrUsageManager)? = null) {
    if (!manager.debugAvailable) return
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pro_debug_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pro_debug_description), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf(null to R.string.pro_debug_default, false to R.string.pro_debug_free, true to R.string.pro_debug_pro)) {
                    FilterChip(state.debugPro == value, enabled = state.initialized && !busy,
                        onClick = {
                            busy = true; failed = false
                            scope.launch {
                                try { manager.setDebugPro(value) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                                finally { busy = false }
                            }
                        }, label = { Text(stringResource(label)) })
                }
            }
            if (failed) Text(stringResource(R.string.pro_storage_error), color = MaterialTheme.colorScheme.error)
        }
    }
    herdrUsage?.let { DebugHerdrUsageStats(it(), unlimited = state.hasPro) }
}
