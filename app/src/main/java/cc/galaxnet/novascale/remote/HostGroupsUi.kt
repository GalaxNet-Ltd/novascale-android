/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val GROUP_FILTER_ALL = "all"
internal const val GROUP_FILTER_TAILNET = "tailnet"
internal const val GROUP_FILTER_TRADITIONAL = "traditional"
internal const val GROUP_FILTER_UNGROUPED = "ungrouped"
internal const val GROUP_FILTER_PREFIX = "group:"

@Composable
internal fun HostPinAction(pinned: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, enabled = enabled) {
        Text(stringResource(if (pinned) R.string.host_unpin else R.string.host_pin))
    }
}

/** Null keys remain visible under All and Ungrouped, but cannot be assigned. */
internal fun hostMatchesGroupFilter(filter: String, isTailnet: Boolean, groupIds: Set<String>): Boolean = when (filter) {
    GROUP_FILTER_ALL -> true
    GROUP_FILTER_TAILNET -> isTailnet
    GROUP_FILTER_TRADITIONAL -> !isTailnet
    GROUP_FILTER_UNGROUPED -> groupIds.isEmpty()
    else -> filter.startsWith(GROUP_FILTER_PREFIX) && filter.removePrefix(GROUP_FILTER_PREFIX) in groupIds
}

@Composable
internal fun HostGroupMembershipDialog(
    state: HostGroupsState,
    hostKey: GroupHostKey?,
    hostName: String,
    storageError: Boolean = false,
    onSave: suspend (GroupHostKey, Set<String>) -> Unit,
    onCreate: (suspend (String) -> HostGroup)? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember(hostKey) { mutableStateOf<Set<String>>(emptySet()) }
    var initialized by remember(hostKey) { mutableStateOf(false) }
    LaunchedEffect(hostKey, state.loaded, storageError) {
        if (state.loaded && !storageError && !initialized) {
            selected = hostKey?.let { state.memberships[it] }.orEmpty()
            initialized = true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val paywall = (androidx.compose.ui.platform.LocalContext.current.applicationContext as cc.galaxnet.novascale.NovaScaleApplication).billing.manager
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.host_groups_assign_title, hostName)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                when {
                    hostKey == null -> Text(stringResource(R.string.host_groups_unavailable))
                    storageError -> Text(stringResource(R.string.host_groups_storage_error), color = MaterialTheme.colorScheme.error)
                    !initialized || !state.loaded -> CircularProgressIndicator()
                    state.groups.isEmpty() -> Text(stringResource(if (onCreate == null) R.string.host_groups_assign_empty else R.string.hosts_group_assign_new))
                    else -> state.groups.forEach { group ->
                        val checked = group.id in selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, enabled = !busy,
                            onValueChange = { value -> selected = if (value) selected + group.id else selected - group.id }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(group.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (onCreate != null && hostKey != null && initialized && state.loaded && !storageError) {
                    TextButton(enabled = !busy, onClick = { if (paywall.allows(cc.galaxnet.novascale.billing.ProFeature.HOST_GROUPS, state.groups.size)) creating = true }) { Text(stringResource(R.string.host_filter_new_group)) }
                }
                if (error) Text(stringResource(R.string.host_groups_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { NovaPrimaryButton(enabled = hostKey != null && initialized && state.loaded && !storageError && state.groups.isNotEmpty() && !busy, onClick = {
            busy = true
            scope.launch {
                try { onSave(checkNotNull(hostKey), selected); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (creating && onCreate != null) HostGroupNameDialog(null, state.loaded && !storageError,
        onSave = { name -> selected = selected + onCreate(name).id }, onDismiss = { creating = false })
}
