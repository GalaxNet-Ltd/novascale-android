/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import cc.galaxnet.novascale.ui.NovaDestructiveButton

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.remote.HostKeyConfirmationDialog
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshTransport
import cc.galaxnet.novascale.ui.NovaGroupedCard
import cc.galaxnet.novascale.ui.NovaSpacing
import cc.galaxnet.novascale.ui.NovaTheme
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asString
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SftpScreen(
    tailnet: TailnetBackend,
    tailnetState: TailnetState,
    initialConfig: RemoteConnectionConfig,
    previewSettings: FilePreviewSettings,
    onSessionCloser: ((() -> Unit) -> Unit),
    onContentFullScreen: (Boolean) -> Unit,
    onEditSshSettings: () -> Unit,
) {
    val context = LocalContext.current
    val model = viewModel<SftpViewModel>(
        key = "sftp:${initialConfig.transport}:${initialConfig.tailnetProfileId}:${initialConfig.host}:${initialConfig.port}",
        factory = SftpViewModel.factory(context, tailnet),
    )
    val state by model.state.collectAsStateWithLifecycle()
    var autoConnectStarted by remember(initialConfig.host) { mutableStateOf(false) }

    DisposableEffect(model) {
        onSessionCloser(model::leaveScreen)
        onDispose { onContentFullScreen(false) }
    }
    LaunchedEffect(state.preview != null, state.editingPath != null) {
        onContentFullScreen(state.preview != null || state.editingPath != null)
    }
    LaunchedEffect(tailnetState, initialConfig.host, state.connected, state.busy) {
        if (
            !autoConnectStarted &&
            (initialConfig.transport == SshTransport.DIRECT || tailnetState is TailnetState.Running) &&
            !state.connected &&
            !state.busy &&
            initialConfig.validate() == null
        ) {
            autoConnectStarted = true
            model.connect(initialConfig)
        }
    }
    state.hostKeyPrompt?.let { prompt ->
        HostKeyConfirmationDialog(
            prompt = prompt,
            endpoint = runCatching { initialConfig.endpoint }.getOrDefault(initialConfig.host),
            onTrust = model::acceptHostKey,
            onReject = model::rejectHostKey,
        )
    }
    BackHandler(enabled = state.preview != null) {
        model.closePreview()
    }

    when {
        !state.connected -> SftpConnect(
            state = state,
            config = initialConfig,
            tailnetRunning = (initialConfig.transport == SshTransport.DIRECT || tailnetState is TailnetState.Running),
            onConnect = { model.connect(initialConfig) },
            onEditSshSettings = onEditSshSettings,
        )
        state.preview != null -> {
            val preview = checkNotNull(state.preview)
            SftpFilePreviewScreen(
                preview = preview,
                transfer = state.transfer?.takeIf { it.purpose == SftpTransferPurpose.PREVIEW },
                onClose = model::closePreview,
                onCancel = model::closePreview,
                onRetry = {
                    model.preview(
                        preview.entry,
                        previewSettings.sizeLimit.maximumBytes,
                    )
                },
            )
        }
        state.editingPath != null -> SftpEditor(state, model)
        else -> SftpBrowser(state, model, previewSettings)
    }
}

@Composable
private fun SftpConnect(
    state: SftpConnectionState,
    config: RemoteConnectionConfig,
    tailnetRunning: Boolean,
    onConnect: () -> Unit,
    onEditSshSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        Text(stringResource(R.string.feature_files), style = MaterialTheme.typography.headlineMedium)
        Text(
            runCatching { config.endpoint }.getOrDefault(config.host),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTheme.colors.textSecondary,
            fontFamily = FontFamily.Monospace,
        )
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(NovaSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(NovaSpacing.rowSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = NovaTheme.colors.menuFile,
                    )
                }
                Text(state.status.asString(), modifier = Modifier.weight(1f))
            }
        }
        if (!tailnetRunning) {
            Text(
                stringResource(R.string.files_requires_tailnet),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm)) {
                if (tailnetRunning && config.validate() == null) {
                    NovaPrimaryButton(onClick = onConnect) { Text(stringResource(R.string.action_retry)) }
                }
                TextButton(onClick = onEditSshSettings) {
                    Text(stringResource(R.string.feature_ssh_settings))
                }
            }
        }
    }
}

private enum class CreateKind { FILE, FOLDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpBrowser(
    state: SftpConnectionState,
    model: SftpViewModel,
    previewSettings: FilePreviewSettings,
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var actionsExpanded by remember { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sortDialogVisible by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var renameEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var actionEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::upload)
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) model.download(entry, uri)
    }

    createKind?.let { kind ->
        SftpNameDialog(
            title = stringResource(
                if (kind == CreateKind.FOLDER) R.string.new_folder else R.string.new_file,
            ),
            confirmLabel = stringResource(R.string.create),
            initialValue = "",
            onDismiss = { createKind = null },
            onConfirm = { name ->
                model.create(name, directory = kind == CreateKind.FOLDER)
            },
        )
    }
    renameEntry?.let { entry ->
        SftpNameDialog(
            title = stringResource(R.string.rename_named, entry.name),
            confirmLabel = stringResource(R.string.action_rename),
            initialValue = entry.name,
            onDismiss = { renameEntry = null },
            onConfirm = { model.rename(entry, it) },
        )
    }
    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.delete_named_title, entry.name)) },
            text = {
                Text(
                    if (entry.isSymbolicLink) {
                        stringResource(R.string.sftp_delete_link_description)
                    } else if (entry.isDirectory) {
                        stringResource(R.string.delete_folder_description)
                    } else {
                        stringResource(R.string.delete_file_description)
                    },
                )
            },
            confirmButton = {
                NovaDestructiveButton(
                    onClick = {
                        deleteEntry = null
                        model.delete(entry)
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                NovaSecondaryButton(onClick = { deleteEntry = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (sortDialogVisible) {
        AlertDialog(
            onDismissRequest = { sortDialogVisible = false },
            title = { Text(stringResource(R.string.sort_files)) },
            text = {
                Column {
                    SftpSortOrder.entries.forEach { order ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                model.setSortOrder(order)
                                sortDialogVisible = false
                            },
                        ) {
                            Text(
                                text = stringResource(order.labelRes),
                                modifier = Modifier.weight(1f),
                                color = if (order == state.sortOrder) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    NovaTheme.colors.textPrimary
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
    actionEntry?.let { entry ->
        SftpEntryActionsSheet(
            entry = entry,
            onDismiss = { actionEntry = null },
            onPreview = {
                actionEntry = null
                model.preview(entry, previewSettings.sizeLimit.maximumBytes)
            },
            onEdit = {
                actionEntry = null
                model.open(entry)
            },
            onDownload = {
                actionEntry = null
                pendingDownload = entry
                downloadLauncher.launch(entry.name)
            },
            onRename = {
                actionEntry = null
                renameEntry = entry
            },
            onCopyPath = {
                actionEntry = null
                clipboard.setText(AnnotatedString(entry.path))
            },
            onDelete = {
                actionEntry = null
                deleteEntry = entry
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SftpBreadcrumbs(
                    currentPath = state.currentPath,
                    enabled = !state.busy,
                    onPath = {
                        focusManager.clearFocus()
                        model.list(it)
                    },
                )
                Text(
                    text = state.status.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) {
                        focusManager.clearFocus()
                        model.updateSearch("")
                    }
                },
            ) {
                Icon(
                    if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (searchVisible) R.string.cd_close_search else R.string.cd_search_folder,
                    ),
                )
            }
            IconButton(
                onClick = { model.list(state.currentPath) },
                enabled = !state.busy,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                )
            }
            Box {
                IconButton(onClick = { actionsExpanded = true }, enabled = state.transfer == null) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.cd_file_actions),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.upload_file)) },
                        leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                        onClick = {
                            actionsExpanded = false
                            uploadLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_file)) },
                        leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        onClick = {
                            actionsExpanded = false
                            createKind = CreateKind.FILE
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        onClick = {
                            actionsExpanded = false
                            createKind = CreateKind.FOLDER
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(
                                if (state.showHiddenFiles) R.string.hide_hidden_files
                                else R.string.show_hidden_files,
                            ))
                        },
                        leadingIcon = { Icon(Icons.Outlined.FilePresent, null) },
                        onClick = {
                            model.setShowHiddenFiles(!state.showHiddenFiles)
                            actionsExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.sort_value,
                                    stringResource(state.sortOrder.labelRes),
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null) },
                        onClick = {
                            actionsExpanded = false
                            sortDialogVisible = true
                        },
                    )
                }
            }
        }

        if (searchVisible) {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = model::updateSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xs),
                placeholder = { Text(stringResource(R.string.cd_search_folder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (state.searchText.isNotEmpty()) {
                        IconButton(onClick = { model.updateSearch("") }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.cd_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
            )
        }

        state.transfer?.let { transfer ->
            TransferCard(transfer = transfer, onCancel = model::cancelTransfer)
        }

        HorizontalDivider()
        if (state.displayedEntries.isEmpty() && !state.busy) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NovaSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = NovaTheme.colors.textSecondary,
                )
                Text(
                    text = stringResource(
                        if (state.searchText.isNotBlank()) {
                            R.string.no_matching_files
                        } else {
                            R.string.folder_empty
                        },
                    ),
                    modifier = Modifier.padding(top = NovaSpacing.sm),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!state.showHiddenFiles && state.entries.any { it.name.startsWith('.') }) {
                    TextButton(onClick = { model.setShowHiddenFiles(true) }) {
                        Text(stringResource(R.string.show_hidden_files))
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.displayedEntries, key = { it.path }) { entry ->
                    SftpEntryRow(
                        entry = entry,
                        enabled = !state.busy,
                        onOpen = {
                            focusManager.clearFocus()
                            if (!entry.isDirectory && previewSettings.tapToPreview) {
                                model.preview(entry, previewSettings.sizeLimit.maximumBytes)
                            } else {
                                model.open(entry)
                            }
                        },
                        onActions = { actionEntry = entry },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = NovaTheme.colors.borderSubtle,
                    )
                }
            }
        }
    }
}

internal data class SftpBreadcrumb(val label: String, val path: String)

internal fun sftpBreadcrumbs(path: String): List<SftpBreadcrumb> {
    val normalized = normalizeRemotePath(path)
    if (normalized == "/") return listOf(SftpBreadcrumb("/", "/"))
    val segments = normalized.trim('/').split('/').filter(String::isNotEmpty)
    var current = ""
    return buildList {
        add(SftpBreadcrumb("/", "/"))
        segments.forEach { segment ->
            current += "/$segment"
            add(SftpBreadcrumb(segment, current))
        }
    }
}

@Composable
private fun SftpBreadcrumbs(
    currentPath: String,
    enabled: Boolean,
    onPath: (String) -> Unit,
) {
    val breadcrumbs = remember(currentPath) { sftpBreadcrumbs(currentPath) }
    val scrollState = rememberScrollState()
    LaunchedEffect(currentPath, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            val current = index == breadcrumbs.lastIndex
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                },
                modifier = Modifier.clickable(
                    enabled = enabled && !current,
                    onClick = { onPath(breadcrumb.path) },
                ),
            ) {
                Text(
                    text = breadcrumb.label,
                    modifier = Modifier.padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (current) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
            }
            if (!current) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = NovaTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(transfer: SftpTransfer, onCancel: () -> Unit) {
    NovaGroupedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
    ) {
        Column(
            modifier = Modifier.padding(NovaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (transfer.direction == SftpTransferDirection.UPLOAD) {
                        Icons.Outlined.Upload
                    } else {
                        Icons.Outlined.Download
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .padding(start = NovaSpacing.sm)
                        .weight(1f),
                ) {
                    Text(transfer.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        transferDescription(transfer),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTheme.colors.textSecondary,
                    )
                }
                TextButton(onClick = onCancel, enabled = !transfer.canceling) {
                    Text(stringResource(
                        if (transfer.canceling) R.string.transfer_canceling else R.string.action_cancel,
                    ))
                }
            }
            transfer.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SftpEntryRow(
    entry: SftpEntry,
    enabled: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    Surface(color = NovaTheme.colors.backgroundSecondary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(start = NovaSpacing.md, top = NovaSpacing.sm, bottom = NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.FilePresent,
                    contentDescription = stringResource(if (entry.isSymbolicLink) R.string.sftp_symbolic_link
                        else if (entry.isDirectory) R.string.cd_folder else R.string.cd_file),
                    modifier = Modifier.size(26.dp).align(Alignment.Center),
                    tint = if (entry.isDirectory) NovaTheme.colors.menuFile else NovaTheme.colors.menuPrimary,
                )
                if (entry.isSymbolicLink) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomStart).size(16.dp)
                            .background(NovaTheme.colors.backgroundSecondary, CircleShape),
                        tint = NovaTheme.colors.textPrimary)
                }
            }
            Column(
                modifier = Modifier
                    .padding(start = NovaSpacing.rowSpacing)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.details(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onActions, enabled = enabled) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.cd_actions_for_file, entry.name),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpEntryActionsSheet(
    entry: SftpEntry,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = NovaSpacing.lg)) {
            Column(
                modifier = Modifier.padding(
                    start = NovaSpacing.md,
                    end = NovaSpacing.md,
                    bottom = NovaSpacing.sm,
                ),
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.isDirectory && !entry.isUnavailableLink) {
                SftpActionSheetRow(
                    Icons.Outlined.Visibility,
                    stringResource(R.string.preview),
                    onPreview,
                )
                SftpActionSheetRow(
                    Icons.Outlined.Edit,
                    stringResource(R.string.action_edit),
                    onEdit,
                )
                SftpActionSheetRow(
                    Icons.Outlined.Download,
                    stringResource(R.string.download),
                    onDownload,
                )
            }
            SftpActionSheetRow(
                Icons.Outlined.Edit,
                stringResource(R.string.action_rename),
                onRename,
            )
            SftpActionSheetRow(
                Icons.Outlined.FilePresent,
                stringResource(R.string.copy_path),
                onCopyPath,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = NovaSpacing.xs))
            SftpActionSheetRow(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.action_delete),
                onClick = onDelete,
                destructive = true,
            )
        }
    }
}

@Composable
private fun SftpActionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else NovaTheme.colors.textPrimary
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SftpNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> UiText?,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    var error by remember(title, initialValue) { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    error = null
                },
                label = { Text(stringResource(R.string.label_name)) },
                supportingText = { error?.let { Text(it.asString()) } },
                isError = error != null,
                singleLine = true,
            )
        },
        confirmButton = {
            NovaDestructiveButton(
                onClick = {
                    error = onConfirm(value)
                    if (error == null) onDismiss()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            NovaSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SftpEditor(state: SftpConnectionState, model: SftpViewModel) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var editor by remember(state.editingPath) { mutableStateOf<SftpCodeEditor?>(null) }
    var searchVisible by remember(state.editingPath) { mutableStateOf(false) }
    var searchText by remember(state.editingPath) { mutableStateOf("") }
    var wordWrap by remember(state.editingPath) { mutableStateOf(false) }
    var closeDialogVisible by remember(state.editingPath) { mutableStateOf(false) }
    val editingPath = state.editingPath.orEmpty()
    val language = remember(editingPath) { codeLanguageForPath(editingPath) }
    val resignEditorInput: () -> Unit = {
        editor?.releaseEditorInput()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val requestClose: () -> Unit = {
        resignEditorInput()
        if (state.editorDirty) closeDialogVisible = true else model.closeEditor()
    }

    BackHandler {
        if (!state.busy) requestClose()
    }
    DisposableEffect(editingPath) {
        onDispose(resignEditorInput)
    }
    if (closeDialogVisible) {
        AlertDialog(
            onDismissRequest = { closeDialogVisible = false },
            title = { Text(stringResource(R.string.save_changes_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.save_changes_description,
                        editingPath.substringAfterLast('/'),
                    ),
                )
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            closeDialogVisible = false
                            resignEditorInput()
                            model.closeEditor()
                        },
                    ) {
                        Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            closeDialogVisible = false
                            resignEditorInput()
                            model.saveEditor(closeAfterSave = true)
                        },
                    ) { Text(stringResource(R.string.action_save)) }
                }
            },
            dismissButton = {
                NovaSecondaryButton(onClick = { closeDialogVisible = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = requestClose, enabled = !state.busy) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back_to_files),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(editingPath.substringAfterLast('/'))
                        if (state.editorDirty) append(" •")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.status.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NovaPrimaryButton(
                onClick = { model.saveEditor() },
                enabled = state.editorDirty && !state.busy,
            ) {
                Text(stringResource(if (state.busy) R.string.saving else R.string.action_save))
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { editor?.undo() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = stringResource(R.string.cd_undo),
                )
            }
            IconButton(onClick = { editor?.redo() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = stringResource(R.string.cd_redo),
                )
            }
            IconButton(
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (searchVisible) R.string.cd_close_find else R.string.label_find,
                    ),
                    tint = if (searchVisible) MaterialTheme.colorScheme.primary else NovaTheme.colors.textPrimary,
                )
            }
            IconButton(onClick = { wordWrap = editor?.toggleWordWrap() ?: wordWrap }) {
                Icon(
                    Icons.AutoMirrored.Outlined.WrapText,
                    contentDescription = stringResource(
                        if (wordWrap) R.string.cd_disable_word_wrap
                        else R.string.cd_enable_word_wrap,
                    ),
                    tint = if (wordWrap) MaterialTheme.colorScheme.primary else NovaTheme.colors.textPrimary,
                )
            }
            Text(
                if (language == SftpCodeLanguage.PLAIN_TEXT) {
                    stringResource(R.string.plain_text)
                } else {
                    stringResource(R.string.tree_sitter_language, language.label)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = NovaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (searchVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { value ->
                        searchText = value
                        editor?.searchFor(value)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_find)) },
                )
                TextButton(onClick = { editor?.findPrevious() }) {
                    Text(stringResource(R.string.action_previous))
                }
                TextButton(onClick = { editor?.findNext() }) {
                    Text(stringResource(R.string.action_next))
                }
                TextButton(
                    onClick = {
                        searchVisible = false
                        editor?.searchFor("")
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    },
                ) { Text(stringResource(R.string.action_done)) }
            }
        }
        AndroidView(
            factory = { context ->
                SftpCodeEditor(context).also { view ->
                    editor = view
                    view.onTextChanged = model::updateEditor
                    view.bindDocument(editingPath, state.editingText)
                }
            },
            update = { view ->
                editor = view
                view.onTextChanged = model::updateEditor
                view.bindDocument(editingPath, state.editingText)
                if (searchVisible && searchText.isNotBlank()) view.searchFor(searchText)
            },
            onRelease = { view ->
                if (editor === view) editor = null
                view.onTextChanged = null
                view.releaseEditorInput()
                view.release()
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun SftpEntry.details(): String {
    val context = LocalContext.current
    return buildList {
        if (isSymbolicLink) {
            add(stringResource(when {
                isUnavailableLink -> R.string.sftp_link_target_unavailable
                isDirectory -> R.string.sftp_folder_link
                else -> R.string.sftp_file_link
            }))
        }
        if (isDirectory && !isSymbolicLink) {
            add(stringResource(R.string.file_details_folder))
        } else if (!isDirectory && !isUnavailableLink) {
            this@details.size?.let { add(Formatter.formatShortFileSize(context, it)) }
        }
        modifiedAt?.takeIf { it > 0 }?.let {
            add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1_000)))
        }
        permissions?.takeIf { it > 0 }?.let { add("0${it.toString(8).takeLast(3)}") }
    }.joinToString(" · ").ifEmpty {
        stringResource(if (isDirectory) R.string.file_details_folder else R.string.file_details_file)
    }
}

@Composable
private fun transferDescription(transfer: SftpTransfer): String {
    val direction = stringResource(
        if (transfer.direction == SftpTransferDirection.UPLOAD) {
            R.string.transfer_uploading
        } else {
            R.string.transfer_downloading
        },
    )
    val total = transfer.totalBytes
    return if (total != null && total > 0) {
        stringResource(
            R.string.transfer_progress,
            direction,
            formatBytes(transfer.transferredBytes),
            formatBytes(total),
        )
    } else {
        stringResource(
            R.string.transfer_progress_unknown,
            direction,
            formatBytes(transfer.transferredBytes),
        )
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KiB".format(bytes / 1_024.0)
    bytes < 1_073_741_824 -> "%.1f MiB".format(bytes / 1_048_576.0)
    else -> "%.1f GiB".format(bytes / 1_073_741_824.0)
}
