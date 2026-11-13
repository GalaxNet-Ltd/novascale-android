/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaActionRow
import cc.galaxnet.novascale.ui.NovaGroupedCard
import cc.galaxnet.novascale.ui.NovaTheme

/** Selection is explicit: inspecting a session never opens or starts it. */
@Composable
internal fun HerdrSessionPicker(
    hostName: String, sessions: List<HerdrRemoteSession>, executable: String,
    selected: String, onSelect: (String) -> Unit,
    onConnect: (String) -> Unit, onRefresh: () -> Unit, onExecutable: (String) -> Unit,
    onDismiss: () -> Unit, ready: Boolean = true, busy: Boolean = false, status: String? = null,
    allowanceContent: @Composable () -> Unit = {},
) {
    var creating by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val options = remember(sessions, selected) { herdrSessionOptions(sessions, selected) }
    HerdrPickerSheet(stringResource(R.string.herdr_choose_session), onDismiss, dismissLabel = R.string.action_cancel) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hostName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = NovaTheme.colors.textSecondary)
                IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.herdr_refresh)) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!ready) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                item { allowanceContent() }
                items(options, key = { it.name }) { session ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(session.name == selected,
                            enabled = ready && !busy, role = Role.RadioButton, onClick = { onSelect(session.name) })
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(session.name == selected, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(if (session.name == "default") stringResource(R.string.herdr_default_session) else session.name,
                                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(if (session.running) R.string.herdr_session_running else R.string.herdr_session_stopped),
                                    style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                item {
                    NovaActionRow(stringResource(R.string.herdr_session_new), Icons.Outlined.Add, NovaTheme.colors.primary,
                        Modifier.clickable(enabled = ready && !busy, role = Role.Button) { creating = true })
                }
                item {
                    NovaActionRow(stringResource(R.string.herdr_advanced), Icons.Outlined.Settings, NovaTheme.colors.textSecondary,
                        Modifier.clickable(enabled = !busy, role = Role.Button) { advanced = true }, description = executable.ifBlank { "herdr" })
                }
            }
            NovaPrimaryButton(onClick = { onConnect(selected) }, enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.herdr_connect))
            }
        }
    }
    if (creating) HerdrTextDialog(
        title = stringResource(R.string.herdr_session_new), label = stringResource(R.string.herdr_session_name),
        initial = "", help = stringResource(R.string.herdr_session_new_help),
        error = stringResource(R.string.herdr_session_invalid), valid = ::isValidHerdrSessionName,
        onSave = { onSelect(it); creating = false }, onDismiss = { creating = false },
    )
    if (advanced) HerdrTextDialog(
        title = stringResource(R.string.herdr_advanced), label = stringResource(R.string.herdr_executable),
        initial = executable, help = stringResource(R.string.herdr_executable_help),
        error = stringResource(R.string.herdr_executable_invalid), valid = ::isValidHerdrExecutable,
        onSave = { advanced = false; onExecutable(it) }, onDismiss = { advanced = false },
    )
}

@Composable
private fun HerdrTextDialog(
    title: String, label: String, initial: String, help: String, error: String, valid: (String) -> Boolean,
    onSave: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(help, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it.take(4096) }, singleLine = true, label = { Text(label) },
                isError = text.isNotEmpty() && !valid(text), supportingText = { if (text.isNotEmpty() && !valid(text)) Text(error) })
        }
    }, confirmButton = { NovaPrimaryButton(enabled = valid(text), onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
