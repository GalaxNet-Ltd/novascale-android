/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.nativecore.NativeEvent
import cc.galaxnet.novascale.nativecore.NativeSession
import cc.galaxnet.novascale.remote.HostKeyPrompt
import cc.galaxnet.novascale.remote.KnownHostStore
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.startRemoteSession
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asUiText
import cc.galaxnet.novascale.ui.uiPlural
import cc.galaxnet.novascale.ui.uiText
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SftpEntry(
    val name: String,
    val path: String,
    val kind: String,
    val size: Long?,
    val modifiedAt: Long?,
    val permissions: Long?,
    val targetKind: String? = null,
) {
    val isSymbolicLink: Boolean
        get() = kind == "symlink"
    val isDirectory: Boolean
        get() = kind == "directory" || (isSymbolicLink && targetKind == "directory")
    val isUnavailableLink: Boolean
        get() = isSymbolicLink && targetKind != "directory" && targetKind != "file"
    // A folder link must be unlinked, never removed as a directory.
    val deleteAsDirectory: Boolean
        get() = kind == "directory"
}

internal enum class SftpSortOrder(@androidx.annotation.StringRes val labelRes: Int) {
    NAME_ASCENDING(R.string.sort_name_ascending),
    NAME_DESCENDING(R.string.sort_name_descending),
    MODIFIED_DESCENDING(R.string.sort_newest),
    MODIFIED_ASCENDING(R.string.sort_oldest),
    SIZE_DESCENDING(R.string.sort_largest),
    SIZE_ASCENDING(R.string.sort_smallest),
}

internal enum class SftpTransferDirection {
    UPLOAD,
    DOWNLOAD,
}

internal enum class SftpTransferPurpose {
    USER,
    PREVIEW,
}

internal data class SftpTransfer(
    val requestId: String,
    val direction: SftpTransferDirection,
    val fileName: String,
    val purpose: SftpTransferPurpose = SftpTransferPurpose.USER,
    val byteLimit: Long? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long? = null,
    val canceling: Boolean = false,
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (transferredBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

internal data class SftpConnectionState(
    val status: UiText = uiText(R.string.sftp_status_preparing),
    val busy: Boolean = false,
    val connected: Boolean = false,
    val currentPath: String = "/",
    val entries: List<SftpEntry> = emptyList(),
    val searchText: String = "",
    val showHiddenFiles: Boolean = false,
    val sortOrder: SftpSortOrder = SftpSortOrder.NAME_ASCENDING,
    val transfer: SftpTransfer? = null,
    val preview: SftpFilePreview? = null,
    val editingPath: String? = null,
    val editingText: String = "",
    val editorDirty: Boolean = false,
    val hostKeyPrompt: HostKeyPrompt? = null,
) {
    val displayedEntries: List<SftpEntry>
        get() = filterAndSortSftpEntries(entries, searchText, showHiddenFiles, sortOrder)
}

internal class SftpViewModel(
    context: Context,
    private val tailnet: TailnetBackend,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val knownHosts = KnownHostStore(appContext)
    private val mutableState = MutableStateFlow(SftpConnectionState())
    val state: StateFlow<SftpConnectionState> = mutableState.asStateFlow()

    private var nativeSession: NativeSession? = null
    private var connectJob: Job? = null
    private var connectionGeneration = 0L
    private var eventJob: Job? = null
    private var lastConfig: RemoteConnectionConfig? = null
    private var partialDownloadUri: Uri? = null
    private var previewDownloadPath: String? = null
    private val previewLimitExceededRequests = mutableSetOf<String>()
    private var closeEditorAfterSave = false

    fun connect(config: RemoteConnectionConfig) {
        config.validate()?.let { error ->
            mutableState.value = mutableState.value.copy(status = error)
            return
        }
        val generation = invalidateConnectionAttempt()
        lastConfig = config
        connectJob = viewModelScope.launch {
            closeNative()
            coroutineContext.ensureActive()
            if (generation != connectionGeneration) return@launch
            mutableState.value = SftpConnectionState(
                status = uiText(R.string.sftp_status_dialing, config.endpoint),
                busy = true,
            )
            try {
                val expected = knownHosts.fingerprint(config)
                val start = withContext(Dispatchers.IO) { NativeSession.prepareSftp(config.request(expected)) }
                val session = startRemoteSession(config, tailnet, start)
                // A cancelled or superseded attempt must never publish a live
                // session after leave, retry, or host-key rejection.
                if (generation != connectionGeneration || !coroutineContext.isActive) {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { session.close() } }
                    return@launch
                }
                nativeSession = session
                eventJob = viewModelScope.launch(Dispatchers.IO) { pollEvents(session) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == connectionGeneration) {
                    mutableState.value = SftpConnectionState(
                        status = error.message?.asUiText()
                            ?: uiText(R.string.sftp_status_start_failed),
                    )
                }
            }
        }
    }

    private fun invalidateConnectionAttempt(): Long {
        connectionGeneration += 1
        connectJob?.cancel()
        connectJob = null
        return connectionGeneration
    }

    fun acceptHostKey() {
        val config = lastConfig ?: return
        val prompt = mutableState.value.hostKeyPrompt ?: return
        knownHosts.trust(config, prompt.fingerprint)
        connect(config)
    }

    fun rejectHostKey() {
        invalidateConnectionAttempt()
        val old = detachNative()
        mutableState.value = mutableState.value.copy(
            hostKeyPrompt = null,
            busy = false,
            connected = false,
            status = uiText(R.string.host_key_not_trusted),
        )
        CoroutineScope(NonCancellable + Dispatchers.IO).launch { runCatching { old?.close() } }
    }

    fun list(path: String) {
        val normalized = normalizeRemotePath(path)
        mutableState.value = mutableState.value.copy(
            status = uiText(R.string.sftp_status_loading, normalized),
            busy = true,
        )
        runNativeCommand { list(normalized) }
    }

    fun open(entry: SftpEntry) {
        if (rejectUnavailableLink(entry)) return
        if (entry.isDirectory) {
            mutableState.value = mutableState.value.copy(searchText = "")
            list(entry.path)
        } else {
            mutableState.value = mutableState.value.copy(
                status = uiText(R.string.sftp_status_loading, entry.name),
                busy = true,
            )
            runNativeCommand { read(entry.path) }
        }
    }

    private fun rejectUnavailableLink(entry: SftpEntry): Boolean {
        if (!entry.isUnavailableLink) return false
        mutableState.value = mutableState.value.copy(status = uiText(R.string.sftp_link_unavailable))
        return true
    }

    fun parentDirectory() {
        mutableState.value = mutableState.value.copy(searchText = "")
        list(parentRemotePath(mutableState.value.currentPath))
    }

    fun updateSearch(value: String) {
        mutableState.value = mutableState.value.copy(searchText = value)
    }

    fun setShowHiddenFiles(show: Boolean) {
        mutableState.value = mutableState.value.copy(showHiddenFiles = show)
    }

    fun setSortOrder(order: SftpSortOrder) {
        mutableState.value = mutableState.value.copy(sortOrder = order)
    }

    fun create(name: String, directory: Boolean): UiText? {
        val error = validateSftpName(name)
        if (error != null) return error
        val path = joinRemotePath(mutableState.value.currentPath, name.trim())
        mutableState.value = mutableState.value.copy(
            status = uiText(
                if (directory) R.string.sftp_status_creating_folder
                else R.string.sftp_status_creating_file,
            ),
            busy = true,
        )
        runNativeCommand {
            if (directory) createDirectory(path) else createFile(path)
        }
        return null
    }

    fun rename(entry: SftpEntry, newName: String): UiText? {
        val error = validateSftpName(newName)
        if (error != null) return error
        val destination = joinRemotePath(parentRemotePath(entry.path), newName.trim())
        if (destination == entry.path) return null
        mutableState.value = mutableState.value.copy(
            status = uiText(R.string.sftp_status_renaming, entry.name),
            busy = true,
        )
        runNativeCommand { rename(entry.path, destination) }
        return null
    }

    fun delete(entry: SftpEntry) {
        mutableState.value = mutableState.value.copy(
            status = uiText(R.string.sftp_status_deleting, entry.name),
            busy = true,
        )
        runNativeCommand { delete(entry.path, entry.deleteAsDirectory) }
    }

    fun upload(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val metadata = runCatching { contentResolver.documentMetadata(uri) }.getOrElse { error ->
                update {
                    it.copy(
                        status = error.message?.asUiText()
                            ?: uiText(R.string.sftp_read_selected_failed),
                        busy = false,
                    )
                }
                return@launch
            }
            val nameError = validateSftpName(metadata.name)
            if (nameError != null) {
                update { it.copy(status = nameError, busy = false) }
                return@launch
            }
            val session = nativeSession ?: return@launch
            val remotePath = joinRemotePath(mutableState.value.currentPath, metadata.name)
            val requestId = java.util.UUID.randomUUID().toString()
            update {
                it.copy(
                    status = uiText(R.string.sftp_status_uploading, metadata.name),
                    busy = true,
                    transfer = SftpTransfer(
                        requestId = requestId,
                        direction = SftpTransferDirection.UPLOAD,
                        fileName = metadata.name,
                        totalBytes = metadata.size,
                    ),
                )
            }
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    session.upload(remotePath, descriptor.detachFd(), metadata.size, requestId)
                } ?: error("Unable to open the selected file")
            }.onFailure { error ->
                update {
                    it.copy(
                        status = error.message?.asUiText()
                            ?: uiText(R.string.sftp_upload_start_failed),
                        busy = false,
                        transfer = null,
                    )
                }
            }
        }
    }

    fun download(entry: SftpEntry, destination: Uri) {
        if (rejectUnavailableLink(entry)) return
        check(!entry.isDirectory)
        viewModelScope.launch(Dispatchers.IO) {
            val session = nativeSession ?: return@launch
            val requestId = java.util.UUID.randomUUID().toString()
            partialDownloadUri = destination
            update {
                it.copy(
                    status = uiText(R.string.sftp_status_downloading, entry.name),
                    busy = true,
                    transfer = SftpTransfer(
                        requestId = requestId,
                        direction = SftpTransferDirection.DOWNLOAD,
                        fileName = entry.name,
                        totalBytes = entry.size,
                    ),
                )
            }
            runCatching {
                contentResolver.openFileDescriptor(destination, "w")?.use { descriptor ->
                    session.download(entry.path, descriptor.detachFd(), requestId)
                } ?: error("Unable to open the destination")
            }.onFailure { error ->
                deletePartialDownload()
                update {
                    it.copy(
                        status = error.message?.asUiText()
                            ?: uiText(R.string.sftp_download_start_failed),
                        busy = false,
                        transfer = null,
                    )
                }
            }
        }
    }

    fun preview(entry: SftpEntry, maximumBytes: Long?) {
        if (rejectUnavailableLink(entry)) return
        check(!entry.isDirectory)
        closePreview()
        val preview = SftpFilePreview(entry = entry)
        if (maximumBytes != null && entry.size != null && entry.size > maximumBytes) {
            mutableState.value = mutableState.value.copy(
                preview = preview.copy(error = previewSizeError(maximumBytes)),
                status = uiText(R.string.preview_over_limit_status),
                busy = false,
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val session = nativeSession
            if (session == null) {
                update {
                    it.copy(
                        preview = preview.copy(error = uiText(R.string.sftp_not_connected)),
                        status = uiText(R.string.sftp_not_connected_status),
                        busy = false,
                    )
                }
                return@launch
            }
            val requestId = java.util.UUID.randomUUID().toString()
            val localFile = runCatching {
                SftpPreviewCache.createFile(appContext, entry.name)
            }.getOrElse { error ->
                update {
                    it.copy(
                        preview = preview.copy(
                            error = error.message?.asUiText()
                                ?: uiText(R.string.preview_prepare_failed),
                        ),
                        status = uiText(R.string.preview_prepare_failed_status),
                        busy = false,
                    )
                }
                return@launch
            }
            previewDownloadPath = localFile.absolutePath
            update {
                it.copy(
                    status = uiText(R.string.preview_preparing, entry.name),
                    busy = true,
                    preview = preview,
                    transfer = SftpTransfer(
                        requestId = requestId,
                        direction = SftpTransferDirection.DOWNLOAD,
                        purpose = SftpTransferPurpose.PREVIEW,
                        fileName = entry.name,
                        totalBytes = entry.size,
                        byteLimit = maximumBytes,
                    ),
                )
            }
            runCatching {
                ParcelFileDescriptor.open(
                    localFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { descriptor ->
                    session.download(entry.path, descriptor.detachFd(), requestId)
                }
            }.onFailure { error ->
                deletePreviewDownload()
                update {
                    it.copy(
                        preview = preview.copy(
                            error = error.message?.asUiText()
                                ?: uiText(R.string.preview_download_start_failed),
                        ),
                        status = uiText(R.string.preview_download_failed_status),
                        busy = false,
                        transfer = null,
                    )
                }
            }
        }
    }

    fun closePreview() {
        val current = mutableState.value
        val transfer = current.transfer?.takeIf { it.purpose == SftpTransferPurpose.PREVIEW }
        if (current.preview == null && transfer == null) return
        if (transfer != null && !transfer.canceling) {
            viewModelScope.launch(Dispatchers.IO) {
                nativeSession?.cancelSftpTransfer(transfer.requestId)
            }
        }
        deletePreviewDownload()
        mutableState.value = current.copy(
            preview = null,
            busy = if (transfer != null) true else current.busy,
            transfer = transfer?.copy(canceling = true) ?: current.transfer,
            status = if (transfer != null) {
                uiText(R.string.preview_canceling_download)
            } else {
                current.status
            },
        )
    }

    fun cancelTransfer() {
        val transfer = mutableState.value.transfer ?: return
        if (transfer.canceling) return
        mutableState.value = mutableState.value.copy(
            status = uiText(R.string.transfer_canceling_status),
            transfer = transfer.copy(canceling = true),
        )
        viewModelScope.launch(Dispatchers.IO) {
            nativeSession?.cancelSftpTransfer(transfer.requestId)
        }
    }

    fun updateEditor(text: String) {
        mutableState.value = mutableState.value.copy(editingText = text, editorDirty = true)
    }

    fun closeEditor() {
        closeEditorAfterSave = false
        mutableState.value = mutableState.value.copy(
            editingPath = null,
            editingText = "",
            editorDirty = false,
        )
    }

    fun saveEditor(closeAfterSave: Boolean = false) {
        val current = mutableState.value
        val path = current.editingPath ?: return
        closeEditorAfterSave = closeAfterSave
        mutableState.value = current.copy(
            status = uiText(R.string.sftp_status_saving, path),
            busy = true,
        )
        runNativeCommand { write(path, current.editingText.toByteArray(Charsets.UTF_8)) }
    }

    fun leaveScreen() {
        invalidateConnectionAttempt()
        val old = detachNative()
        mutableState.value = SftpConnectionState(
            status = uiText(R.string.sftp_status_disconnected),
        )
        CoroutineScope(NonCancellable + Dispatchers.IO).launch { runCatching { old?.close() } }
    }

    private fun runNativeCommand(block: NativeSession.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                (nativeSession ?: error("SFTP session is not connected")).block()
            }.onFailure { error ->
                closeEditorAfterSave = false
                update {
                    it.copy(
                        status = error.message?.asUiText()
                            ?: uiText(R.string.sftp_operation_failed),
                        busy = false,
                        transfer = null,
                    )
                }
            }
        }
    }

    private suspend fun pollEvents(session: NativeSession) {
        while (viewModelScope.isActive && nativeSession === session) {
            val event = session.pollEvent(750) ?: continue
            when (event.type) {
                "authenticated" -> update {
                    it.copy(status = uiText(R.string.sftp_status_authenticated))
                }
                "sftp_ready" -> {
                    update {
                        it.copy(
                            status = uiText(R.string.sftp_status_connected),
                            busy = false,
                            connected = true,
                        )
                    }
                    session.list("/")
                }
                "sftp_list" -> handleList(event)
                "sftp_read" -> handleRead(event)
                "sftp_write" -> handleWrite(event)
                "sftp_create", "sftp_rename", "sftp_delete" -> handleMutation(event, session)
                "sftp_transfer_progress" -> handleTransferProgress(event)
                "sftp_upload", "sftp_download" -> handleTransferFinished(event, session)
                "host_key_unknown", "host_key_changed" -> handleHostKey(event)
                "error" -> update {
                    deletePreviewDownload()
                    it.copy(
                        status = event.message?.asUiText()
                            ?: uiText(R.string.sftp_status_failed),
                        busy = false,
                        connected = false,
                        transfer = null,
                        preview = it.preview?.copy(
                            localPath = null,
                            error = event.message?.asUiText()
                                ?: uiText(R.string.sftp_status_failed),
                        ),
                    )
                }
                "closed", "event_stream_closed" -> {
                    deletePartialDownload()
                    deletePreviewDownload()
                    update {
                        it.copy(
                            status = uiText(R.string.sftp_status_closed),
                            busy = false,
                            connected = false,
                            transfer = null,
                            preview = it.preview?.copy(
                                localPath = null,
                                error = uiText(R.string.sftp_session_closed_error),
                            ),
                        )
                    }
                    break
                }
            }
        }
    }

    private suspend fun handleHostKey(event: NativeEvent) {
        val fingerprint = event.fingerprint ?: return
        update {
            it.copy(
                status = if (event.type == "host_key_changed") {
                    uiText(R.string.host_key_changed_warning)
                } else {
                    uiText(R.string.host_key_confirmation)
                },
                busy = false,
                connected = false,
                hostKeyPrompt = HostKeyPrompt(
                    fingerprint,
                    event.algorithm,
                    event.expectedFingerprint,
                ),
            )
        }
    }

    private suspend fun handleList(event: NativeEvent) {
        val payload = event.payload ?: return
        payload.errorOrNull()?.let { error ->
            update { it.copy(status = error.asUiText(), busy = false) }
            return
        }
        val array = payload.optJSONArray("entries") ?: return
        val entries = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    SftpEntry(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        kind = item.optString("kind"),
                        size = item.optionalLong("size"),
                        modifiedAt = item.optionalLong("modifiedAt"),
                        permissions = item.optionalLong("permissions"),
                        targetKind = item.optString("targetKind").takeIf { it in setOf("directory", "file", "other") },
                    ),
                )
            }
        }
        update {
            it.copy(
                status = uiPlural(R.plurals.item_count, entries.size),
                busy = false,
                currentPath = payload.optString("path", "/"),
                entries = entries,
            )
        }
    }

    private suspend fun handleRead(event: NativeEvent) {
        val payload = event.payload ?: return
        payload.errorOrNull()?.let { error ->
            update { it.copy(status = error.asUiText(), busy = false) }
            return
        }
        val decoded = runCatching {
            val bytes = Base64.decode(payload.getString("base64"), Base64.DEFAULT)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            update { it.copy(status = uiText(R.string.file_not_utf8), busy = false) }
            return
        }
        update {
            it.copy(
                status = uiText(R.string.sftp_status_editing, payload.optString("path")),
                busy = false,
                editingPath = payload.optString("path"),
                editingText = decoded,
                editorDirty = false,
            )
        }
    }

    private suspend fun handleWrite(event: NativeEvent) {
        val payload = event.payload ?: return
        val error = payload.errorOrNull()
        val shouldCloseEditor = error == null && closeEditorAfterSave
        closeEditorAfterSave = false
        update {
            it.copy(
                status = error?.asUiText()
                    ?: uiText(R.string.sftp_status_saved, payload.optString("path")),
                busy = false,
                editingPath = if (shouldCloseEditor) null else it.editingPath,
                editingText = if (shouldCloseEditor) "" else it.editingText,
                editorDirty = if (error == null) false else it.editorDirty,
            )
        }
    }

    private suspend fun handleMutation(event: NativeEvent, session: NativeSession) {
        val payload = event.payload ?: return
        payload.errorOrNull()?.let { error ->
            update { it.copy(status = error.asUiText(), busy = false) }
            return
        }
        val path = payload.optString("destinationPath")
            .ifBlank { payload.optString("path") }
        update {
            it.copy(
                status = uiText(R.string.sftp_status_updated, path.substringAfterLast('/')),
                busy = true,
            )
        }
        session.list(mutableState.value.currentPath)
    }

    private suspend fun handleTransferProgress(event: NativeEvent) {
        val requestId = event.requestId ?: return
        val payload = event.payload ?: return
        var shouldCancelForLimit = false
        update { current ->
            val transfer = current.transfer?.takeIf { it.requestId == requestId } ?: return@update current
            val transferredBytes = payload.optionalLong("bytes") ?: transfer.transferredBytes
            val totalBytes = payload.optionalLong("totalBytes") ?: transfer.totalBytes
            val limit = transfer.byteLimit
            shouldCancelForLimit = transfer.purpose == SftpTransferPurpose.PREVIEW &&
                limit != null &&
                maxOf(transferredBytes, totalBytes ?: 0) > limit &&
                !transfer.canceling
            current.copy(
                transfer = transfer.copy(
                    transferredBytes = transferredBytes,
                    totalBytes = totalBytes,
                    canceling = transfer.canceling || shouldCancelForLimit,
                ),
            )
        }
        if (shouldCancelForLimit) {
            previewLimitExceededRequests += requestId
            nativeSession?.cancelSftpTransfer(requestId)
        }
    }

    private suspend fun handleTransferFinished(event: NativeEvent, session: NativeSession) {
        val requestId = event.requestId ?: return
        val payload = event.payload ?: return
        val transfer = mutableState.value.transfer?.takeIf { it.requestId == requestId } ?: return
        val error = payload.errorOrNull()
        val canceled = payload.optBoolean("canceled")
        if (transfer.purpose == SftpTransferPurpose.PREVIEW) {
            val exceededLimit = previewLimitExceededRequests.remove(requestId)
            val downloadedPath = previewDownloadPath
            val downloadedPreview = mutableState.value.preview
            if (error != null || canceled || mutableState.value.preview == null) {
                deletePreviewDownload()
            } else {
                previewDownloadPath = null
            }
            val previewError = when {
                exceededLimit -> previewSizeError(transfer.byteLimit ?: FilePreviewSizeLimit.default.maximumBytes!!)
                error != null -> error.asUiText()
                canceled && mutableState.value.preview != null ->
                    uiText(R.string.preview_download_canceled)
                else -> null
            }
            val fallbackText = if (
                previewError == null &&
                !canceled &&
                downloadedPreview?.kind == SftpPreviewKind.EXTERNAL &&
                downloadedPath != null
            ) {
                readPreviewText(java.io.File(downloadedPath))
            } else {
                null
            }
            if (fallbackText != null && downloadedPreview != null) {
                SftpPreviewCache.deletePreview(downloadedPath)
                var openedInEditor = false
                update { current ->
                    if (current.preview?.entry?.path != downloadedPreview.entry.path) {
                        current
                    } else {
                        openedInEditor = true
                        current.copy(
                            status = uiText(
                                R.string.sftp_status_editing,
                                downloadedPreview.entry.path,
                            ),
                            busy = false,
                            transfer = null,
                            preview = null,
                            editingPath = downloadedPreview.entry.path,
                            editingText = fallbackText,
                            editorDirty = false,
                        )
                    }
                }
                if (openedInEditor) return
            }
            update { current ->
                current.copy(
                    status = when {
                        current.preview == null ->
                            uiText(R.string.preview_closed_status, transfer.fileName)
                        previewError != null -> previewError
                        else -> uiText(R.string.previewing_status, transfer.fileName)
                    },
                    busy = false,
                    transfer = null,
                    preview = current.preview?.copy(
                        localPath = downloadedPath.takeIf { previewError == null && !canceled },
                        error = previewError,
                    ),
                )
            }
            return
        }
        if (transfer.direction == SftpTransferDirection.DOWNLOAD) {
            if (error != null || canceled) deletePartialDownload() else partialDownloadUri = null
        }
        val status: UiText = when {
            canceled -> uiText(R.string.transfer_canceled_status, transfer.fileName)
            error != null -> error.asUiText()
            transfer.direction == SftpTransferDirection.UPLOAD ->
                uiText(R.string.upload_complete, transfer.fileName)
            else -> uiText(R.string.download_complete, transfer.fileName)
        }
        if (transfer.direction == SftpTransferDirection.UPLOAD && error == null && !canceled) {
            update { it.copy(status = status, busy = true, transfer = null) }
            session.list(mutableState.value.currentPath)
        } else {
            update { it.copy(status = status, busy = false, transfer = null) }
        }
    }

    /** Detach synchronously so a delayed cleanup cannot close a new session. */
    private fun detachNative(): NativeSession? {
        eventJob?.cancel()
        eventJob = null
        val session = nativeSession
        nativeSession = null
        deletePartialDownload()
        deletePreviewDownload()
        previewLimitExceededRequests.clear()
        return session
    }

    private suspend fun closeNative() {
        val old = detachNative()
        withContext(NonCancellable + Dispatchers.IO) { runCatching { old?.close() } }
    }

    private fun deletePartialDownload() {
        val uri = partialDownloadUri ?: return
        partialDownloadUri = null
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun deletePreviewDownload() {
        val path = previewDownloadPath ?: mutableState.value.preview?.localPath
        previewDownloadPath = null
        SftpPreviewCache.deletePreview(path)
    }

    private suspend fun update(block: (SftpConnectionState) -> SftpConnectionState) {
        withContext(Dispatchers.Main.immediate) {
            mutableState.value = block(mutableState.value)
        }
    }

    override fun onCleared() {
        invalidateConnectionAttempt()
        eventJob?.cancel()
        deletePartialDownload()
        deletePreviewDownload()
        previewLimitExceededRequests.clear()
        runCatching { nativeSession?.close() }
        nativeSession = null
    }

    companion object {
        fun factory(context: Context, tailnet: TailnetBackend): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SftpViewModel(context, tailnet) as T
                }
            }
    }
}

private fun previewSizeError(maximumBytes: Long): UiText =
    uiText(R.string.preview_size_error, formatPreviewBytes(maximumBytes))

private fun formatPreviewBytes(bytes: Long): String =
    if (bytes % (1_024 * 1_024) == 0L) "${bytes / (1_024 * 1_024)} MiB" else "$bytes bytes"

internal fun filterAndSortSftpEntries(
    entries: List<SftpEntry>,
    searchText: String,
    showHiddenFiles: Boolean,
    sortOrder: SftpSortOrder,
): List<SftpEntry> {
    val query = searchText.trim()
    val comparator = when (sortOrder) {
        SftpSortOrder.NAME_ASCENDING -> compareBy<SftpEntry> { it.name.lowercase() }
        SftpSortOrder.NAME_DESCENDING -> compareByDescending { it.name.lowercase() }
        SftpSortOrder.MODIFIED_DESCENDING -> compareByDescending { it.modifiedAt ?: Long.MIN_VALUE }
        SftpSortOrder.MODIFIED_ASCENDING -> compareBy { it.modifiedAt ?: Long.MAX_VALUE }
        SftpSortOrder.SIZE_DESCENDING -> compareByDescending { it.size ?: Long.MIN_VALUE }
        SftpSortOrder.SIZE_ASCENDING -> compareBy { it.size ?: Long.MAX_VALUE }
    }
    return entries
        .asSequence()
        .filter { showHiddenFiles || !it.name.startsWith('.') }
        .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<SftpEntry> { it.isDirectory }.then(comparator))
        .toList()
}

internal fun validateSftpName(name: String): UiText? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> uiText(R.string.validation_enter_name)
        trimmed == "." || trimmed == ".." -> uiText(R.string.validation_different_name)
        '/' in trimmed || '\u0000' in trimmed -> uiText(R.string.validation_name_slash)
        else -> null
    }
}

internal fun normalizeRemotePath(path: String): String =
    path.trim().ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/$it" }

internal fun parentRemotePath(path: String): String {
    val normalized = normalizeRemotePath(path).trimEnd('/')
    return normalized.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
}

internal fun joinRemotePath(parent: String, name: String): String =
    if (normalizeRemotePath(parent) == "/") "/$name" else "${normalizeRemotePath(parent).trimEnd('/')}/$name"

private data class DocumentMetadata(val name: String, val size: Long?)

private fun ContentResolver.documentMetadata(uri: Uri): DocumentMetadata {
    var displayName: String? = null
    var size: Long? = null
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: error("The selected document has no file name")
    return DocumentMetadata(name, size?.takeIf { it >= 0 })
}

private fun JSONObject.optionalLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.errorOrNull(): String? =
    optString("error").takeIf(String::isNotBlank)
