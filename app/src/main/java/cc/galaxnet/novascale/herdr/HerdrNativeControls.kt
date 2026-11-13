/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import cc.galaxnet.novascale.ui.NovaPrimaryButton
import cc.galaxnet.novascale.ui.NovaSecondaryButton
import cc.galaxnet.novascale.ui.NovaDestructiveButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.terminalfeature.TerminalChromeColors
import cc.galaxnet.novascale.ui.NovaIcons

internal fun herdrAgentStatusLabel(status: String): Int = when (status) {
    "working" -> R.string.herdr_agent_working
    "blocked" -> R.string.herdr_agent_attention
    "done" -> R.string.herdr_agent_done
    "idle" -> R.string.herdr_agent_idle
    else -> R.string.herdr_agent_unknown
}

/** Native navigation uses endpoint IDs. No terminal shortcuts or shell commands. */
@Composable
internal fun HerdrNativeControls(
    navigation: HerdrNavigation, busy: Boolean, hostName: String, sessionName: String,
    colors: TerminalChromeColors,
    onAction: (HerdrAction, String) -> Unit,
    onDetach: () -> Unit, onKeyboard: () -> Unit, onClose: () -> Unit, onReconnect: () -> Unit,
    connectionStatus: String? = null,
) {
    var agents by remember { mutableStateOf(false) }
    var workspaces by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<HerdrTab?>(null) }
    var renameText by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf<HerdrTab?>(null) }
    val tabs = navigation.visibleTabs
    val focused = navigation.agents.firstOrNull { it.focused }
    val attention = navigation.agents.any { !it.focused && it.status == "blocked" }
    val can: (String) -> Boolean = { !busy && navigation.supports(it) }
    val action: (HerdrAction) -> Unit = { onAction(it, navigation.bootId) }
    val label = focused?.label ?: hostName
    val status = focused?.let { stringResource(herdrAgentStatusLabel(it.status)) }
    val contextLabel = connectionStatus ?: listOfNotNull(status, navigation.workspace?.label ?: sessionName).joinToString(" · ")
    val agentDescription = stringResource(R.string.herdr_agents) + ", " + label +
        (status?.let { ", $it" } ?: "") + if (attention) ", " + stringResource(R.string.herdr_agent_attention) else ""

    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDetach) { Icon(NovaIcons.Detach, stringResource(R.string.herdr_detach)) }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { agents = true }
                .semantics { contentDescription = agentDescription }.padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(if (attention) Icons.Outlined.ErrorOutline else Icons.Outlined.ExpandMore, null,
                        Modifier.size(16.dp), tint = if (attention) MaterialTheme.colorScheme.error else colors.secondary)
                }
                Text(contextLabel, style = MaterialTheme.typography.bodySmall, color = colors.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { workspaces = true }) { Icon(Icons.Outlined.GridView, stringResource(R.string.herdr_workspaces)) }
            IconButton(onClick = onKeyboard) { Icon(Icons.Outlined.Keyboard, stringResource(R.string.herdr_keyboard)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.herdr_actions)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("$hostName · $sessionName", maxLines = 2, overflow = TextOverflow.Ellipsis) }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { Text(stringResource(R.string.herdr_agents)) }, onClick = { menu = false; agents = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.herdr_workspaces)) }, onClick = { menu = false; workspaces = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.herdr_reconnect)) }, onClick = { menu = false; onReconnect() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.herdr_close)) }, onClick = { menu = false; onClose() })
                }
            }
        }
        val scroll = rememberLazyListState()
        val selected = tabs.indexOfFirst { it.focused }
        LaunchedEffect(selected, tabs.map { it.id }) { if (selected >= 0) scroll.animateScrollToItem(selected) }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), state = scroll) {
                    items(tabs, key = { it.id }) { tab ->
                        var options by remember { mutableStateOf(false) }
                        Column {
                            Row(Modifier.drawBehind {
                                if (tab.focused) drawLine(colors.foreground, Offset(0f, size.height - 1.dp.toPx()),
                                    Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 2.dp.toPx())
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.label, Modifier.heightIn(min = 40.dp).widthIn(min = 48.dp, max = 150.dp)
                                    .selectable(tab.focused, enabled = can("tab.focus"), role = Role.Tab,
                                        onClick = { action(HerdrAction("tab.focus", tab.id)) })
                                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 12.dp, vertical = 6.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Box {
                                    IconButton(onClick = { options = true }, enabled = can("tab.rename") || can("tab.close"), modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.herdr_tab_options, tab.label), Modifier.size(18.dp))
                                    }
                                    DropdownMenu(options, onDismissRequest = { options = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.herdr_tab_rename)) }, enabled = can("tab.rename"),
                                            onClick = { options = false; rename = tab; renameText = tab.label })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.herdr_tab_close)) }, enabled = can("tab.close"),
                                            onClick = { options = false; closing = tab })
                                    }
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { navigation.workspace?.let { action(HerdrAction("tab.create", it.id)) } },
                    enabled = can("tab.create") && navigation.workspace != null, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.herdr_tab_new))
                }
            }
        }
        // Navigation must not resize the terminal as the progress indicator appears/disappears.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.foreground)
        }
    }
    if (agents) HerdrPickerSheet(stringResource(R.string.herdr_agents), onDismiss = { agents = false }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            if (navigation.agents.isEmpty()) item { Text(stringResource(R.string.herdr_agents_empty)) }
            navigation.agents.groupBy { it.workspaceId }.forEach { (workspaceId, members) ->
                item(key = "workspace:$workspaceId") {
                    Text(navigation.workspaces.firstOrNull { it.id == workspaceId }?.label ?: workspaceId,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(members, key = { it.id }) { agent ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(agent.focused,
                        enabled = can("pane.focus"), role = Role.RadioButton,
                        onClick = { action(HerdrAction("pane.focus", agent.id)); agents = false }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(when (agent.status) {
                            "blocked" -> Icons.Outlined.ErrorOutline
                            "working" -> Icons.Outlined.PlayArrow
                            "done" -> Icons.Outlined.CheckCircleOutline
                            else -> Icons.Outlined.Terminal
                        }, null, tint = if (agent.status == "blocked") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(agent.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(herdrAgentStatusLabel(agent.status)) + " · " +
                                (navigation.tabs.firstOrNull { it.id == agent.tabId }?.label ?: agent.tabId),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (agent.focused) Icon(Icons.Outlined.Check, stringResource(R.string.herdr_selected))
                    }
                }
            }
        }
    }

    if (workspaces) HerdrPickerSheet(stringResource(R.string.herdr_workspaces), onDismiss = { workspaces = false }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            items(navigation.workspaces, key = { it.id }) { workspace ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(workspace.focused, enabled = can("workspace.focus"), role = Role.RadioButton,
                    onClick = { action(HerdrAction("workspace.focus", workspace.id)); workspaces = false }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(workspace.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (workspace.focused) Icon(Icons.Outlined.Check, stringResource(R.string.herdr_selected))
                }
            }
        }
        NovaPrimaryButton(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            enabled = can("workspace.create") && navigation.workspace != null, onClick = {
            navigation.workspace?.let { action(HerdrAction("workspace.create", it.id)) }; workspaces = false
        }) { Text(stringResource(R.string.herdr_workspace_new)) }
    }

    rename?.let { tab -> AlertDialog(onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.herdr_tab_rename)) },
        text = { OutlinedTextField(renameText, { renameText = it.take(256) }, singleLine = true, label = { Text(stringResource(R.string.herdr_tab_name)) }) },
        confirmButton = { NovaPrimaryButton(enabled = can("tab.rename") && validHerdrTabLabel(renameText) && tabs.any { it.id == tab.id }, onClick = {
            action(HerdrAction("tab.rename", tab.id, renameText.trim())); rename = null
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onClick = { rename = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    closing?.let { tab -> AlertDialog(onDismissRequest = { closing = null },
        title = { Text(stringResource(R.string.herdr_tab_close_named, tab.label)) },
        text = { Text(stringResource(if (navigation.tabs.count { it.workspaceId == tab.workspaceId } == 1) R.string.herdr_tab_close_last_help else R.string.herdr_tab_close_help)) },
        confirmButton = { NovaDestructiveButton(enabled = can("tab.close") && tabs.any { it.id == tab.id }, onClick = {
            action(HerdrAction("tab.close", tab.id)); closing = null
        }) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { NovaSecondaryButton(onClick = { closing = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}
