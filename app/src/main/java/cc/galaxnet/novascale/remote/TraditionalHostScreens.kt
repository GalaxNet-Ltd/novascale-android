/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import cc.galaxnet.novascale.ui.NovaDestructiveButton

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import cc.galaxnet.novascale.ui.NovaGroupedCard
import kotlinx.coroutines.CancellationException
import cc.galaxnet.novascale.BuildConfig
import cc.galaxnet.novascale.HostActionGrid
import cc.galaxnet.novascale.HostTile
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaIcons
import cc.galaxnet.novascale.ui.NovaSectionLabel
import cc.galaxnet.novascale.ui.NovaTheme
import kotlinx.coroutines.launch

@Composable
internal fun TraditionalHostRows(hosts: List<TraditionalHost>, onAdd: () -> Unit, onHost: (TraditionalHost) -> Unit, showAdd: Boolean = true, pinned: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showAdd) TextButton(onClick = onAdd) { Text(stringResource(R.string.traditional_host_add)) }
        hosts.forEach { host ->
            HostIdentityRow(host.name,
                host.endpoint.ifEmpty { stringResource(R.string.host_filter_traditional_subtitle) },
                route = stringResource(if (host.transport == SshTransport.DIRECT) R.string.traditional_direct else R.string.traditional_tailnet),
                pinned = pinned, onClick = { onHost(host) })
        }
    }
}

@Composable
internal fun TraditionalHostEditScreen(repository: TraditionalHostRepository, host: TraditionalHost?, onSaved: (TraditionalHost) -> Unit, onDismiss: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var name by remember(host) { mutableStateOf(host?.name.orEmpty()) }
    var config by remember(host) { mutableStateOf(RemoteConnectionConfig(username = "root", authenticationKind = AuthenticationKind.PASSWORD, transport = SshTransport.DIRECT)) }
    var loaded by remember(host) { mutableStateOf(host == null) }
    var error by remember(host) { mutableStateOf(false) }
    var saving by remember(host) { mutableStateOf(false) }
    LaunchedEffect(host) {
        if (host != null) {
            try {
                val saved = repository.load(host)
                if (saved == null) error = true else config = saved
                loaded = true // Missing credentials can be replaced from this form.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
    }
    fun save() {
        if (!loaded || saving) return
        if (name.isBlank() || config.validate() != null) { error = true; return }
        saving = true
        scope.launch {
            try { onSaved(repository.save(host, name, config)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { saving = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (onDismiss != null) {
            Text(stringResource(R.string.traditional_host_add),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge)
            if (error) Text(stringResource(R.string.traditional_host_save_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(128) }, Modifier.fillMaxWidth(), enabled = loaded && !saving,
                singleLine = true, label = { Text(stringResource(R.string.traditional_host_name)) })
            Text(stringResource(R.string.traditional_network_path), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = config.transport == SshTransport.DIRECT, onClick = { config = config.copy(transport = SshTransport.DIRECT, tailnetProfileId = null) },
                    enabled = loaded && !saving, label = { Text(stringResource(R.string.traditional_direct)) })
                FilterChip(selected = config.transport == SshTransport.TAILNET, onClick = { config = config.copy(transport = SshTransport.TAILNET, tailnetProfileId = null) },
                    enabled = loaded && !saving, label = { Text(stringResource(R.string.traditional_tailnet)) })
            }
            Text(stringResource(if (config.transport == SshTransport.DIRECT) R.string.traditional_direct_help else R.string.traditional_tailnet_help),
                style = MaterialTheme.typography.bodySmall)
            RemoteConnectionForm(config, { config = it }, emptyList(), loaded && !saving,
                stringResource(R.string.action_save), onConnect = ::save, showSubmit = onDismiss == null)
            if (error && onDismiss == null) Text(stringResource(R.string.traditional_host_save_error), color = MaterialTheme.colorScheme.error)
        }
        if (onDismiss != null) {
            androidx.compose.material3.HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !saving) {
                    Text(stringResource(R.string.action_cancel))
                }
                cc.galaxnet.novascale.ui.NovaPrimaryButton(onClick = ::save, modifier = Modifier.weight(1f),
                    enabled = loaded && !saving && name.isNotBlank() && config.validate() == null) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
internal fun TraditionalHostDetailScreen(
    host: TraditionalHost,
    repository: TraditionalHostRepository,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTerminal: (RemoteConnectionConfig) -> Unit,
    onFiles: (RemoteConnectionConfig) -> Unit,
    onHerdr: (RemoteConnectionConfig) -> Unit,
    onManageGroups: (() -> Unit)? = null,
    pinned: Boolean = false,
    pinEnabled: Boolean = false,
    pinError: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    groupNames: List<String> = emptyList(),
) {
    var config by remember(host) { mutableStateOf<RemoteConnectionConfig?>(null) }
    var missing by remember(host) { mutableStateOf(false) }
    var deleting by remember(host) { mutableStateOf(false) }
    LaunchedEffect(host) {
        try { config = repository.load(host); missing = config == null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { missing = true }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.traditional_delete_title)) },
        text = { Text(stringResource(R.string.traditional_delete_description, host.name)) },
        confirmButton = { NovaDestructiveButton(onClick = { deleting = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { NovaSecondaryButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaGroupedCard(Modifier.fillMaxWidth()) {
            HostIdentityRow(host.name, config?.endpoint ?: host.endpoint,
                route = stringResource(if ((config?.transport ?: host.transport) == SshTransport.DIRECT) R.string.traditional_direct else R.string.traditional_tailnet))
        }
        if (config == null && !missing) CircularProgressIndicator()
        config?.let { selected ->
            NovaSectionLabel(stringResource(R.string.section_actions))
            HostActionGrid(listOf(
                HostTile(R.string.feature_terminal, "SSH", NovaIcons.Terminal, NovaTheme.colors.menuPrimary) { onTerminal(selected) },
            ) + (if (BuildConfig.DEBUG) listOf(
                HostTile(R.string.herdr_title, stringResource(R.string.herdr_action_description), Icons.Outlined.Widgets, NovaTheme.colors.menuGreen) { onHerdr(selected) },
            ) else emptyList()) + listOf(
                HostTile(R.string.feature_files, "SFTP", NovaIcons.Files, NovaTheme.colors.menuFile) { onFiles(selected) },
            ))
        }
        if (missing) Text(stringResource(R.string.traditional_host_load_error), color = MaterialTheme.colorScheme.error)
        HostOrganizationCard(groupNames, onManageGroups, pinned, pinEnabled, onTogglePin)
        if (pinError) Text(stringResource(R.string.host_pin_storage_error), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.traditional_host_edit)) }
            TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}
