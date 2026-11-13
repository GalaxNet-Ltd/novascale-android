// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.tailnet.TailnetProfilePreferences
import cc.galaxnet.novascale.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TailnetDetailsScreen(state: TailnetState, onControlServer: () -> Unit,
    onLogin: () -> Unit, onLogout: suspend () -> Unit,
    profile: cc.galaxnet.novascale.tailnet.TailnetProfile? = null, operationBusy: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val legacyProfile = remember(context) { TailnetProfilePreferences(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val identity = (state as? TailnetState.Running)?.self
    Column(modifier.verticalScroll(rememberScrollState()).padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md)) {
        NovaSectionLabel(profile?.name ?: stringResource(R.string.section_tailnet))
        NovaGroupedCard(Modifier.fillMaxWidth()) {
            Column {
                DetailValue(stringResource(R.string.detail_status), state.label())
                if (identity != null) {
                    DetailValue(stringResource(R.string.detail_node_name), identity.displayName)
                    DetailValue(stringResource(R.string.detail_dns_name), identity.dnsName)
                    DetailValue(stringResource(R.string.detail_addresses), identity.addresses.joinToString("\n"))
                    DetailValue(stringResource(R.string.detail_login_user), identity.loginName ?: stringResource(R.string.detail_no_user), last = true)
                } else if (state is TailnetState.LoginRequired) {
                    Text(stringResource(R.string.detail_connect_hint), Modifier.padding(NovaSpacing.md),
                        style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                }
            }
        }
        if (state is TailnetState.Failed) Text(state.redactedMessage, color = MaterialTheme.colorScheme.error)
        NovaGroupedCard(Modifier.fillMaxWidth()) {
            NovaActionRow(title = stringResource(R.string.tailnets_manage), description = profile?.controlUrl ?: legacyProfile.controlServer,
                icon = NovaIcons.Server, iconColor = NovaTheme.colors.statusInfo,
                modifier = Modifier.clickable(enabled = !busy && !operationBusy, onClick = onControlServer), descriptionMaxLines = 3)
        }
        if (state != TailnetState.Starting) NovaGroupedCard(Modifier.fillMaxWidth()) {
            NovaActionRow(
                title = stringResource(if (busy) R.string.logging_out else if (identity != null) R.string.log_out else R.string.connect_tailnet),
                icon = if (identity != null) NovaIcons.Disconnect else NovaIcons.Server,
                iconColor = if (identity != null) NovaTheme.colors.statusDisconnected else NovaTheme.colors.statusConnected,
                modifier = Modifier.clickable(enabled = !busy && !operationBusy) {
                    if (identity == null) onLogin() else scope.launch {
                        busy = true; error = false
                        try { onLogout() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { busy = false }
                    }
                }, trailing = {},
            )
        }
        if (error) Text(stringResource(R.string.logout_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun VersionsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NovaSpacing.screenMargin)) {
        NovaGroupedCard(Modifier.fillMaxWidth()) {
            Column {
                DetailValue(stringResource(R.string.version_app), BuildConfig.VERSION_NAME)
                DetailValue(stringResource(R.string.version_build), BuildConfig.VERSION_CODE.toString())
                DetailValue(stringResource(R.string.version_tailscale), BuildConfig.TAILSCALE_VERSION, last = true)
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(NovaSpacing.md), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NovaTheme.colors.textSecondary)
        SelectionContainer {
            Text(value.ifBlank { stringResource(R.string.detail_unavailable) }, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (!last) HorizontalDivider(color = NovaTheme.colors.borderSubtle)
}
