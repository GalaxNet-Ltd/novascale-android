/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import cc.galaxnet.novascale.ui.NovaPrimaryButton
import cc.galaxnet.novascale.ui.NovaSecondaryButton
import cc.galaxnet.novascale.ui.NovaDestructiveButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaStatusDot
import cc.galaxnet.novascale.ui.NovaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class HostFilterMember(val key: GroupHostKey, val name: String, val subtitle: String, val online: Boolean?)

@Composable
internal fun rememberHostGroupFilter(state: HostGroupsState, storageError: Boolean): MutableState<String> {
    val selected = rememberSaveable { mutableStateOf(GROUP_FILTER_ALL) }
    LaunchedEffect(state.loaded, storageError, state.groups, selected.value) {
        if (state.loaded && !storageError && selected.value.startsWith(GROUP_FILTER_PREFIX) &&
            state.groups.none { GROUP_FILTER_PREFIX + it.id == selected.value }) selected.value = GROUP_FILTER_ALL
    }
    return selected
}

@Composable
internal fun hostFilterName(selected: String, groups: List<HostGroup>): String = when (selected) {
    GROUP_FILTER_ALL -> stringResource(R.string.host_groups_all)
    GROUP_FILTER_TAILNET -> stringResource(R.string.host_groups_tailnet)
    GROUP_FILTER_TRADITIONAL -> stringResource(R.string.host_groups_traditional)
    GROUP_FILTER_UNGROUPED -> stringResource(R.string.host_groups_ungrouped)
    else -> groups.firstOrNull { GROUP_FILTER_PREFIX + it.id == selected }?.name ?: stringResource(R.string.host_groups_all)
}

/** Leading Hosts action; the active name is shown only when a filter is selected. */
@Composable
internal fun HostFilterToolbar(selected: String, groups: List<HostGroup>, showLabel: Boolean = true, onOpen: () -> Unit) {
    val filterName = hostFilterName(selected, groups)
    val description = if (selected == GROUP_FILTER_ALL) stringResource(R.string.host_filter_open)
        else stringResource(R.string.host_groups_filter, filterName)
    Row(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(Icons.Outlined.FilterList, contentDescription = null, tint = NovaTheme.colors.textPrimary)
        if (showLabel && selected != GROUP_FILTER_ALL) Text(filterName, Modifier.widthIn(max = 96.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Stateless controls within the sheet, exposed to instrumentation with synthetic hosts. */
@Composable
internal fun HostFilterSheetContent(
    state: HostGroupsState,
    storageError: Boolean,
    selected: String,
    members: List<HostFilterMember>,
    onSelect: (String) -> Unit,
    onOpenMember: (GroupHostKey) -> Unit,
    onCreate: () -> Unit,
    onRename: (HostGroup) -> Unit,
    onDelete: (HostGroup) -> Unit,
    onRemoveMember: (HostGroup, HostFilterMember) -> Unit,
    onDismiss: () -> Unit,
    onManageMembers: ((HostGroup) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.host_filter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.host_filter_section), style = MaterialTheme.typography.labelLarge, color = NovaTheme.colors.textSecondary)
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        listOf(GROUP_FILTER_ALL, GROUP_FILTER_TAILNET, GROUP_FILTER_TRADITIONAL, GROUP_FILTER_UNGROUPED).forEach { filter ->
            val isSelected = selected == filter
            Row(Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onSelect(filter) }
                .semantics { this.selected = isSelected }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(when (filter) {
                    GROUP_FILTER_ALL -> Icons.Outlined.GridView
                    GROUP_FILTER_TAILNET -> Icons.Outlined.DeviceHub
                    GROUP_FILTER_TRADITIONAL -> Icons.Outlined.Computer
                    else -> Icons.Outlined.FolderOpen
                }, contentDescription = null, tint = NovaTheme.colors.textSecondary)
                Text(hostFilterName(filter, state.groups), Modifier.weight(1f), color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                if (isSelected) Icon(Icons.Outlined.Check, contentDescription = null, tint = NovaTheme.colors.primary)
            }
            if (filter != GROUP_FILTER_UNGROUPED) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        }
        Text(stringResource(R.string.host_groups), style = MaterialTheme.typography.labelLarge,
            color = NovaTheme.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
        when {
            storageError -> Text(stringResource(R.string.host_groups_storage_error), color = MaterialTheme.colorScheme.error)
            !state.loaded -> CircularProgressIndicator(Modifier.size(24.dp))
            state.groups.isEmpty() -> Text(stringResource(R.string.host_groups_empty), color = NovaTheme.colors.textSecondary)
        }
        if (state.loaded && !storageError) state.groups.forEach { group ->
            val isExpanded = group.id in expanded
            val groupKeys = state.memberships.filterValues { group.id in it }.keys
            val visibleMembers = members.filter { it.key in groupKeys }
            val expansionDescription = stringResource(if (isExpanded) R.string.host_filter_collapse_group else R.string.host_filter_expand_group, group.name)
            val selectionDescription = stringResource(R.string.hosts_group_select, group.name)
            val isSelected = selected == GROUP_FILTER_PREFIX + group.id
            var menu by remember(group.id) { mutableStateOf(false) }
            Surface(color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button) {
                    onSelect(GROUP_FILTER_PREFIX + group.id)
                }.semantics { contentDescription = selectionDescription; this.selected = isSelected }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = NovaTheme.colors.textSecondary)
                    Column(Modifier.weight(1f)) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.host_filter_member_count, groupKeys.size, groupKeys.size),
                            style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                    }
                    if (isSelected) Icon(Icons.Outlined.Check, null, tint = NovaTheme.colors.primary)
                }
                IconButton(onClick = { expanded = if (isExpanded) expanded - group.id else expanded + group.id }) {
                    Icon(if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, contentDescription = expansionDescription)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.host_filter_group_options, group.name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.host_filter_show_group)) }, onClick = { menu = false; onSelect(GROUP_FILTER_PREFIX + group.id) })
                        onManageMembers?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.hosts_group_members)) }, onClick = { menu = false; action(group) }) }
                        DropdownMenuItem(text = { Text(stringResource(R.string.host_groups_rename)) }, onClick = { menu = false; onRename(group) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete(group) })
                    }
                }
            }
            }
            if (isExpanded) {
                if (visibleMembers.isEmpty()) Text(stringResource(R.string.host_filter_no_members),
                    Modifier.padding(start = 42.dp, top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                visibleMembers.forEach { member ->
                    Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenMember(member.key) }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            member.online?.let { NovaStatusDot(it) }
                            Column(Modifier.weight(1f)) {
                                Text(member.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(member.subtitle, style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { onRemoveMember(group, member) }) {
                            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.host_filter_remove_member, member.name, group.name))
                        }
                    }
                }
                if (groupKeys.size > visibleMembers.size) Text(stringResource(R.string.hosts_group_unavailable),
                    Modifier.padding(start = 42.dp), style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                onManageMembers?.let { action -> TextButton(onClick = { action(group) }, modifier = Modifier.padding(start = 32.dp)) {
                    Text(stringResource(R.string.hosts_group_members))
                } }
            }
        }
        NovaPrimaryButton(onClick = onCreate, enabled = state.loaded && !storageError,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Icon(Icons.Outlined.AddCircleOutline, null)
            Text(stringResource(R.string.host_filter_new_group), Modifier.padding(start = 8.dp))
        }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostFilterSheet(
    repository: HostGroupRepository,
    state: HostGroupsState,
    storageError: Boolean,
    selected: String,
    members: List<HostFilterMember>,
    onSelect: (String) -> Unit,
    onOpenMember: (GroupHostKey) -> Unit,
    onRemoveMember: (HostGroup, HostFilterMember) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    val paywall = (androidx.compose.ui.platform.LocalContext.current.applicationContext as cc.galaxnet.novascale.NovaScaleApplication).billing.manager
    var editing by remember { mutableStateOf<HostGroup?>(null) }
    var deleting by remember { mutableStateOf<HostGroup?>(null) }
    var managing by remember { mutableStateOf<HostGroup?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        HostFilterSheetContent(state, storageError, selected, members, onSelect, onOpenMember,
            onCreate = { if (paywall.allows(cc.galaxnet.novascale.billing.ProFeature.HOST_GROUPS, state.groups.size)) creating = true }, onRename = { editing = it }, onDelete = { deleting = it },
            onRemoveMember = onRemoveMember, onDismiss = onDismiss, onManageMembers = { managing = it })
    }
    if (creating || editing != null) {
        HostGroupNameDialog(editing, state.loaded && !storageError, onSave = { name ->
            val group = editing
            if (group == null) repository.create(name) else repository.rename(group.id, name)
        }) { creating = false; editing = null }
    }
    deleting?.let { group -> HostGroupDeleteDialog(repository, group, state.loaded && !storageError) { deleting = null } }
    managing?.let { group ->
        HostGroupMembersDialog(group, state, members, onSave = { offered, selected ->
            repository.setMembers(group.id, offered, selected)
        }, onDismiss = { managing = null })
    }
}

@Composable
internal fun HostGroupNameDialog(group: HostGroup?, enabled: Boolean, onSave: suspend (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(group) { mutableStateOf(group?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (group == null) R.string.host_filter_new_group else R.string.host_groups_rename)) },
        text = { Column {
            OutlinedTextField(name, { name = it.take(HostGroupRepository.MAX_NAME_LENGTH) }, label = { Text(stringResource(R.string.host_groups_name)) }, singleLine = true, enabled = !busy)
            if (error) Text(stringResource(R.string.hosts_group_name_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { NovaPrimaryButton(enabled = enabled && name.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                try { onSave(name.trim()); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: cc.galaxnet.novascale.billing.ProLimitReached) { /* Keep the entered name behind the upgrade sheet. */ }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun HostGroupMembersDialog(
    group: HostGroup, state: HostGroupsState, members: List<HostFilterMember>,
    onSave: suspend (Set<GroupHostKey>, Set<GroupHostKey>) -> Unit, onDismiss: () -> Unit,
) {
    // Freeze the edit scope, not just the currently searched rows. Missing peers
    // and hosts belonging to other profiles are never removed by this dialog.
    val offered = remember(group.id) { members.distinctBy { it.key } }
    var selected by remember(group.id) { mutableStateOf(offered.filter { group.id in state.memberships[it.key].orEmpty() }.map { it.key }.toSet()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = offered.filter { hostMatchesSearch(query, it.name, it.subtitle) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.hosts_group_members_title, group.name)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.hosts_search)) }, enabled = !busy)
            if (offered.isEmpty()) Text(stringResource(R.string.hosts_group_members_empty))
            else if (shown.isEmpty()) Text(stringResource(R.string.hosts_search_empty))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(shown, key = { "${it.key.kind}:${it.key.scope}:${it.key.hostId}" }) { member ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(member.key in selected,
                        enabled = !busy, role = Role.Checkbox, onValueChange = { checked ->
                            selected = if (checked) selected + member.key else selected - member.key
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(member.key in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(member.subtitle, style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (error) Text(stringResource(R.string.host_groups_save_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { NovaPrimaryButton(enabled = !busy && offered.isNotEmpty(), onClick = {
            busy = true
            scope.launch {
                try { onSave(offered.map { it.key }.toSet(), selected); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun HostGroupDeleteDialog(repository: HostGroupRepository, group: HostGroup, enabled: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.host_groups_delete_title)) },
        text = { Column {
            Text(stringResource(R.string.host_groups_delete_description, group.name))
            if (error) Text(stringResource(R.string.host_groups_save_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { NovaDestructiveButton(enabled = enabled && !busy, onClick = {
            busy = true
            scope.launch {
                try { repository.delete(group.id); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { NovaSecondaryButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}
