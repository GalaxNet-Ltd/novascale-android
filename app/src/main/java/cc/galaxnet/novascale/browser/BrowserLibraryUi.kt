// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaPrimaryButton
import cc.galaxnet.novascale.ui.NovaSecondaryButton
import cc.galaxnet.novascale.ui.NovaDestructiveButton

internal enum class BrowserLibraryPage { FAVORITES, HISTORY }

@Composable
private fun BrowserAccessNotice(access: BrowserAccess, onRecover: () -> Unit) {
    if (access == BrowserAccess.READY) return
    val message = when (access) {
        BrowserAccess.DISABLED -> R.string.browser_proxy_disabled_body
        BrowserAccess.TAILNET_REQUIRED -> R.string.browser_tailnet_required
        BrowserAccess.FAILED -> R.string.browser_proxy_failed_body
        BrowserAccess.UNSUPPORTED -> R.string.browser_proxy_unsupported
        else -> R.string.browser_proxy_preparing
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (access == BrowserAccess.STARTING) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val action = when (access) {
            BrowserAccess.DISABLED -> R.string.browser_enable_proxy
            BrowserAccess.TAILNET_REQUIRED -> R.string.browser_manage_tailnets
            BrowserAccess.FAILED -> R.string.action_retry
            else -> null
        }
        action?.let { NovaSecondaryButton(onRecover) { Text(stringResource(it)) } }
    }
}

@Composable
internal fun BrowserStartPage(state: BrowserUiState, sites: List<BrowserSite>, ready: Boolean, tabCount: Int,
    onAddress: (String) -> Unit, onGo: () -> Unit, onTabs: () -> Unit,
    onLibrary: (BrowserLibraryPage) -> Unit, onOpen: (BrowserSite) -> Unit, libraryError: Boolean = false, onRetry: () -> Unit = {},
    access: BrowserAccess = if (ready) BrowserAccess.READY else BrowserAccess.STARTING, onRecoverAccess: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_browser), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (tabCount > 0) IconButton(onClick = onTabs) {
                Icon(Icons.Outlined.Tab, pluralStringResource(R.plurals.browser_tab_count, tabCount, tabCount))
            }
        }
        if (libraryError) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.browser_library_error), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            NovaSecondaryButton(onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.url != null) Text(stringResource(R.string.browser_resume_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BrowserAddressField(state.address, onAddress, onGo, ready)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8080, 3000, 8123, 9090, 8096).forEach { port ->
                        SuggestionChip(onClick = { browserPortUrl(state.address, port)?.let(onAddress) },
                            enabled = browserPortUrl(state.address, port) != null, label = { Text(":$port") })
                    }
                }
                NovaPrimaryButton(onGo, Modifier.fillMaxWidth(), ready) { Text(stringResource(R.string.browser_go)) }
                BrowserAccessNotice(access, onRecoverAccess)
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Surface(shape = MaterialTheme.shapes.large) {
            Column {
                BrowserActionRow(R.string.browser_favorites, Icons.Outlined.StarOutline) { onLibrary(BrowserLibraryPage.FAVORITES) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BrowserActionRow(R.string.browser_history, Icons.Outlined.History) { onLibrary(BrowserLibraryPage.HISTORY) }
            }
        }
        Text(stringResource(R.string.browser_recent), style = MaterialTheme.typography.titleSmall)
        val recent = sites.filter { it.visited > 0 }.sortedByDescending { it.visited }.take(6)
        if (recent.isEmpty()) Text(stringResource(R.string.browser_library_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEach { site -> BrowserSiteRow(site, onOpen = { onOpen(site) }) }
    }
}

@Composable
private fun BrowserActionRow(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowserSiteRow(site: BrowserSite, onOpen: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = onOpen).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (site.favorite) Icons.Outlined.Star else Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(site.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(browserDisplayHost(site.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}

@Composable
internal fun BrowserAddressField(address: String, onAddress: (String) -> Unit, onGo: () -> Unit, ready: Boolean,
    modifier: Modifier = Modifier, selectAll: Boolean = false) {
    var value by remember { mutableStateOf(TextFieldValue(address, if (selectAll) TextRange(0, address.length) else TextRange(address.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(address) { if (address != value.text) value = TextFieldValue(address, TextRange(address.length)) }
    LaunchedEffect(selectAll) { if (selectAll) { value = value.copy(selection = TextRange(0, value.text.length)); focus.requestFocus() } }
    OutlinedTextField(value, { value = it; onAddress(it.text) }, modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
        label = { Text(stringResource(R.string.browser_address)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { if (ready) onGo() }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserAddressSheet(address: String, sites: List<BrowserSite>, ready: Boolean, error: Int?,
    onAddress: (String) -> Unit, onGo: () -> Unit, onOpen: (BrowserSite) -> Unit, onDismiss: () -> Unit,
    access: BrowserAccess = if (ready) BrowserAccess.READY else BrowserAccess.STARTING, onRecoverAccess: () -> Unit = {}) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 580.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browser_edit_address), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onAddress("") }) { Text(stringResource(R.string.browser_clear_address)) }
            }
            BrowserAddressField(address, onAddress, onGo, ready, selectAll = sheet.currentValue == SheetValue.Expanded)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("http", "https").forEach { scheme ->
                    FilterChip(selected = address.startsWith("$scheme://", true), onClick = { onAddress(browserChangeScheme(address, scheme)) },
                        label = { Text(scheme.uppercase()) })
                }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            BrowserAccessNotice(access, onRecoverAccess)
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                browserSuggestions(sites, address).forEach { site -> BrowserSiteRow(site, { onOpen(site) }) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaSecondaryButton(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                NovaPrimaryButton(onGo, Modifier.weight(1f), ready) { Text(stringResource(R.string.browser_go)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserLibrarySheet(page: BrowserLibraryPage, sites: List<BrowserSite>, error: Boolean, busy: Boolean,
    onOpen: (BrowserSite) -> Unit, onEdit: (BrowserSite) -> Unit, onRemove: (BrowserSite) -> Unit,
    onClear: () -> Unit, onDismiss: () -> Unit) {
    var search by remember(page) { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val title = when (page) { BrowserLibraryPage.FAVORITES -> R.string.browser_favorites; BrowserLibraryPage.HISTORY -> R.string.browser_history }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.action_done)) }
            }
            if (error) Text(stringResource(R.string.browser_library_error), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.browser_local_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.browser_search_library)) })
            val shown = sites.filter { if (page == BrowserLibraryPage.FAVORITES) it.favorite else it.visited > 0 }
                .filter { it.title.contains(search, true) || it.url.contains(search, true) }
                .let { if (page == BrowserLibraryPage.HISTORY) it.sortedByDescending { site -> site.visited } else it.sortedBy { site -> site.title.lowercase() } }
            LazyColumn(Modifier.weight(1f, false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (shown.isEmpty()) item { Text(stringResource(if (search.isNotEmpty()) R.string.browser_no_results else if (page == BrowserLibraryPage.FAVORITES) R.string.browser_favorites_empty else R.string.browser_library_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.id }) { site ->
                    var menu by remember(site.id) { mutableStateOf(false) }
                    BrowserSiteRow(site, { onOpen(site) }) {
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_site_options, site.title)) }
                            DropdownMenu(menu, { menu = false }) {
                                if (page == BrowserLibraryPage.FAVORITES) DropdownMenuItem(text = { Text(stringResource(R.string.browser_edit_favorite)) }, onClick = { menu = false; onEdit(site) })
                                DropdownMenuItem(text = { Text(stringResource(if (page == BrowserLibraryPage.FAVORITES) R.string.browser_remove_favorite else R.string.browser_remove_history)) }, onClick = { menu = false; onRemove(site) })
                        }
                    }
                }
            }
        }
        if (page != BrowserLibraryPage.FAVORITES) NovaSecondaryButton({ confirmClear = true }, Modifier.fillMaxWidth(), !busy && sites.any { it.visited > 0 }) {
            Text(stringResource(R.string.browser_clear_history))
        }
        Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.browser_clear_history)) }, text = { Text(stringResource(R.string.browser_clear_history_description)) },
        confirmButton = { NovaDestructiveButton({ confirmClear = false; onClear() }, enabled = !busy) { Text(stringResource(R.string.browser_clear_history)) } },
        dismissButton = { NovaSecondaryButton({ confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun BrowserFavoriteDialog(site: BrowserSite, busy: Boolean, error: Boolean, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(site) { mutableStateOf(site.title) }
    var url by remember(site) { mutableStateOf(site.url) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (site.favorite) R.string.browser_edit_favorite else R.string.browser_add_favorite)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.browser_site_name)) }, enabled = !busy)
            OutlinedTextField(url, { url = it.take(8192) }, label = { Text(stringResource(R.string.browser_address)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            if (error) Text(stringResource(R.string.browser_library_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { NovaPrimaryButton({ onSave(url, title) }, enabled = !busy && runCatching { browserUrl(url) }.isSuccess) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { NovaSecondaryButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun BrowserFindBar(query: String, match: Int, total: Int, onQuery: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
            label = { Text(stringResource(R.string.browser_find_query)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onNext(true) }))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (total == 0) R.string.browser_find_none else R.string.browser_find_count, match, total), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton({ onNext(false) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.browser_find_previous)) }
            IconButton({ onNext(true) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.browser_find_next)) }
            IconButton(onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_done)) }
        }
    }
}

internal fun browserChangeScheme(address: String, scheme: String): String {
    require(scheme == "http" || scheme == "https")
    return "$scheme://" + address.trim().substringAfter("://", address.trim())
}
