// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet

import cc.galaxnet.novascale.ui.NovaDestructiveButton

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.label
import cc.galaxnet.novascale.ui.*

@Composable
internal fun TailnetProfilesScreen(
    state: TailnetProfilesUiState,
    connection: TailnetState,
    onSelect: (String) -> Unit,
    onAdd: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onActiveDetails: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    showHeading: Boolean = true,
    pro: Boolean = true,
    onReplace: ((String, String) -> Unit)? = null,
    onUseFree: ((String) -> Unit)? = null,
    onRequestAdd: () -> Boolean = { true },
) {
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TailnetProfile?>(null) }
    var switching by remember { mutableStateOf<TailnetProfile?>(null) }
    var removing by remember { mutableStateOf<TailnetProfile?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<Pair<String, String>?>(null) }
    var freeChoice by remember { mutableStateOf<TailnetProfile?>(null) }
    val ready = state.snapshot != null && !state.busy
    if (editing) TailnetProfileEditor(null, onDismiss = { editing = false }, onSave = { name, server ->
        editing = false
        onAdd(name, server)
    })
    if (replacing) TailnetProfileEditor(null, onDismiss = { replacing = false }, onSave = { name, server ->
        replacing = false; replacement = name to server
    })
    replacement?.let { target -> AlertDialog(onDismissRequest = { replacement = null },
        title = { Text(stringResource(R.string.pro_replace_tailnet)) },
        text = { Text(stringResource(R.string.pro_replace_tailnet_description)) },
        confirmButton = { NovaPrimaryButton(enabled = ready, onClick = { replacement = null; onReplace?.invoke(target.first, target.second) }) {
            Text(stringResource(R.string.pro_replace_tailnet))
        } }, dismissButton = { NovaSecondaryButton(onClick = { replacement = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    freeChoice?.let { target -> AlertDialog(onDismissRequest = { freeChoice = null },
        title = { Text(stringResource(R.string.pro_use_free_tailnet)) },
        text = { Text(stringResource(R.string.pro_use_free_tailnet_description)) },
        confirmButton = { NovaPrimaryButton(enabled = ready, onClick = { freeChoice = null; onUseFree?.invoke(target.id) }) {
            Text(stringResource(R.string.tailnets_switch))
        } }, dismissButton = { NovaSecondaryButton(onClick = { freeChoice = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    renaming?.let { profile -> TailnetProfileEditor(profile, onDismiss = { renaming = null }, onSave = { name, _ ->
        renaming = null; onRename(profile.id, name)
    }) }
    switching?.let { profile -> AlertDialog(
        onDismissRequest = { switching = null },
        title = { Text(stringResource(R.string.tailnets_switch_title, profile.name)) },
        text = { Text(stringResource(R.string.tailnets_switch_description)) },
        confirmButton = { NovaPrimaryButton(enabled = ready, onClick = { switching = null; onSelect(profile.id) }) {
            Text(stringResource(R.string.tailnets_switch))
        } },
        dismissButton = { NovaSecondaryButton(onClick = { switching = null }) { Text(stringResource(R.string.action_cancel)) } },
    ) }
    removing?.let { profile -> AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text(stringResource(R.string.tailnets_remove_title, profile.name)) },
        text = { Text(stringResource(R.string.tailnets_remove_description)) },
        confirmButton = { NovaDestructiveButton(enabled = ready, onClick = { removing = null; onRemove(profile.id) }) {
            Text(stringResource(R.string.action_delete))
        } },
        dismissButton = { NovaSecondaryButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
    ) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md)) {
        if (showHeading) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tailnets_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(enabled = ready, onClick = { if (onRequestAdd()) editing = true }) {
                Icon(Icons.Outlined.Add, stringResource(R.string.tailnets_add))
            }
        }
        Text(stringResource(R.string.tailnets_one_active), style = MaterialTheme.typography.bodySmall,
            color = NovaTheme.colors.textSecondary)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            NovaPrimaryButton(enabled = !state.busy, onClick = onRetry) { Text(stringResource(R.string.tailnets_retry)) }
        }
        state.snapshot?.profiles?.forEach { profile ->
            val active = profile.id == state.snapshot.activeId
            var menu by remember(profile.id) { mutableStateOf(false) }
            NovaGroupedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().semantics { selected = active }, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled = ready) {
                        if (active) onActiveDetails() else switching = profile
                    }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (active) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (active) stringResource(R.string.tailnets_active) else null,
                            tint = if (active && connection is TailnetState.Running) NovaTheme.colors.statusConnected else NovaTheme.colors.textSecondary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (normalizeControlUrl(profile.controlUrl) == null) "Tailscale" else profile.controlUrl.removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (active) connection.label() else stringResource(R.string.tailnets_saved),
                                style = MaterialTheme.typography.labelMedium, color = if (active) NovaTheme.colors.primary else NovaTheme.colors.textSecondary)
                        }
                    }
                    Box {
                        IconButton(enabled = ready, onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.tailnets_options, profile.name))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (!pro && !active && profile.id !in state.snapshot.freeIds && onUseFree != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pro_use_free_tailnet)) },
                                    onClick = { menu = false; freeChoice = profile })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.tailnets_rename)) },
                                onClick = { menu = false; renaming = profile })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, enabled = !active,
                                onClick = { menu = false; removing = profile })
                            if (active) DropdownMenuItem(text = { Text(stringResource(R.string.tailnets_remove_active_hint),
                                style = MaterialTheme.typography.bodySmall) }, enabled = false, onClick = {})
                        }
                    }
                }
            }
        }
        NovaPrimaryButton(enabled = ready, onClick = { if (onRequestAdd()) editing = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tailnets_add))
        }
        if (onReplace != null) TextButton(enabled = ready, onClick = { replacing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pro_replace_tailnet))
        }
    }
}

@Composable
private fun TailnetProfileEditor(profile: TailnetProfile?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var custom by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val validName = runCatching { validatedTailnetName(name) }.isSuccess
    val validServer = !custom || (server.isNotBlank() && runCatching { validatedControlServer(server) }.isSuccess)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (profile == null) R.string.tailnets_add else R.string.tailnets_rename)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.tailnets_name)) }, isError = attempted && !validName)
            if (profile == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!custom, { custom = false }, label = { Text("Tailscale") })
                    FilterChip(custom, { custom = true }, label = { Text(stringResource(R.string.tailnets_custom)) })
                }
                if (custom) OutlinedTextField(server, { server = it.take(2048) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.label_control_server)) },
                    placeholder = { Text("https://headscale.example") }, isError = attempted && !validServer,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false))
                Text(stringResource(R.string.tailnets_add_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (attempted && (!validName || !validServer)) Text(stringResource(R.string.tailnets_invalid), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { NovaPrimaryButton(onClick = {
            attempted = true
            if (validName && validServer) onSave(name.trim(), if (custom) server.trim() else DEFAULT_CONTROL_SERVER)
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
