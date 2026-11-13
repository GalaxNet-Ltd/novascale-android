/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import android.content.Context
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.nativecore.NativeSession
import cc.galaxnet.novascale.nativecore.NativeTerminalTheme
import cc.galaxnet.novascale.remote.HostKeyPrompt
import cc.galaxnet.novascale.remote.KnownHostStore
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshTransport
import cc.galaxnet.novascale.remote.startRemoteSession
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asUiText
import cc.galaxnet.novascale.ui.uiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TerminalConnectionState(
    val status: UiText = uiText(R.string.terminal_status_preparing),
    val busy: Boolean = false,
    val connected: Boolean = false,
    val snapshot: TerminalRenderSnapshot? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
)

internal interface TerminalRuntime : AutoCloseable {
    val clipboardWrites: kotlinx.coroutines.flow.Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()
    val state: StateFlow<TerminalConnectionState>
    /** Main-thread notification of a confirmed remote shell exit, not a lost connection. */
    var onExited: (() -> Unit)?
    /** Route of the actual in-flight/live connection, independent of later profile edits. */
    val activeTransport: SshTransport? get() = null

    fun connect(config: RemoteConnectionConfig)

    fun connectIfNeeded(config: RemoteConnectionConfig)

    fun acceptHostKey()

    fun rejectHostKey()

    fun send(bytes: ByteArray)

    fun resize(newColumns: Int, newRows: Int)

    fun resizeWithMetrics(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = resize(columns, rows)

    fun scroll(rows: Int)

    fun applyTheme(theme: NativeTerminalTheme)

    fun disconnect()
}

internal class TerminalConnectionController(
    context: Context,
    private val tailnet: TailnetBackend,
    private val scope: CoroutineScope,
) : TerminalRuntime {
    private val clipboardEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val clipboardWrites: kotlinx.coroutines.flow.Flow<String> = clipboardEvents
    private val knownHosts = KnownHostStore(context)
    private val mutableState = MutableStateFlow(TerminalConnectionState())
    override val state: StateFlow<TerminalConnectionState> = mutableState.asStateFlow()
    override var onExited: (() -> Unit)? = null

    private var nativeSession: NativeSession? = null
    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var lastConfig: RemoteConnectionConfig? = null
    private var columns = 80
    private var rows = 24
    private var terminalTheme: NativeTerminalTheme? = null
    override val activeTransport: SshTransport?
        get() = if (nativeSession != null || connectJob?.isActive == true) lastConfig?.transport else null

    override fun connect(config: RemoteConnectionConfig) {
        config.validate()?.let { error ->
            mutableState.value = mutableState.value.copy(status = error)
            return
        }
        lastConfig = config
        connectJob?.cancel()
        val job = scope.launch {
            closeNative()
            mutableState.value = TerminalConnectionState(
                status = uiText(R.string.terminal_status_dialing, config.endpoint),
                busy = true,
            )
            try {
                val expected = knownHosts.fingerprint(config)
                val start = withContext(Dispatchers.IO) {
                    NativeSession.prepareSsh(config.request(expected, columns, rows, terminalTheme))
                }
                val session = startRemoteSession(config, tailnet, start)
                nativeSession = session
                // A preference change can race the dial/handshake. Re-send the
                // latest palette after ownership is published so that change is
                // never lost between request construction and session creation.
                terminalTheme?.let { latest ->
                    withContext(Dispatchers.IO) { session.setTerminalTheme(latest) }
                }
                mutableState.value = mutableState.value.copy(
                    status = uiText(R.string.terminal_status_negotiating),
                )
                eventJob = scope.launch(Dispatchers.IO) { pollEvents(session) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = TerminalConnectionState(
                    status = error.message?.asUiText()
                        ?: uiText(R.string.terminal_status_start_failed),
                )
            }
        }
        connectJob = job
        job.invokeOnCompletion {
            if (connectJob === job) connectJob = null
        }
    }

    override fun connectIfNeeded(config: RemoteConnectionConfig) {
        if (nativeSession != null || connectJob?.isActive == true || mutableState.value.connected) return
        connect(config)
    }

    override fun acceptHostKey() {
        val config = lastConfig ?: return
        val prompt = mutableState.value.hostKeyPrompt ?: return
        knownHosts.trust(config, prompt.fingerprint)
        connect(config)
    }

    override fun rejectHostKey() {
        mutableState.value = mutableState.value.copy(
            hostKeyPrompt = null,
            busy = false,
            connected = false,
            status = uiText(R.string.host_key_not_trusted),
        )
        scope.launch { closeNative() }
    }

    override fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch(Dispatchers.IO) { nativeSession?.sendInput(bytes) }
    }

    private var cellWidth = 8
    private var cellHeight = 16

    override fun resize(newColumns: Int, newRows: Int) = resizeWithMetrics(newColumns, newRows, cellWidth, cellHeight)

    override fun resizeWithMetrics(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        this.columns = columns.coerceIn(20, 500)
        this.rows = rows.coerceIn(5, 300)
        this.cellWidth = cellWidth.coerceIn(1, 512)
        this.cellHeight = cellHeight.coerceIn(1, 512)
        val size = intArrayOf(this.columns, this.rows, this.cellWidth, this.cellHeight)
        scope.launch(Dispatchers.IO) { nativeSession?.resize(size[0], size[1], size[2], size[3]) }
    }

    override fun scroll(rows: Int) {
        if (rows == 0) return
        scope.launch(Dispatchers.IO) { nativeSession?.scrollTerminal(rows.coerceIn(-300, 300)) }
    }

    override fun applyTheme(theme: NativeTerminalTheme) {
        terminalTheme = theme
        scope.launch(Dispatchers.IO) { nativeSession?.setTerminalTheme(theme) }
    }

    override fun disconnect() {
        scope.launch {
            closeNative()
            mutableState.value = TerminalConnectionState(
                status = uiText(R.string.terminal_status_disconnected),
            )
        }
    }

    private suspend fun pollEvents(session: NativeSession) {
        while (scope.isActive && nativeSession === session) {
            val event = session.pollEvent(750) ?: continue
            when (event.type) {
                "authenticated" -> update(session) {
                    it.copy(status = uiText(R.string.terminal_status_authenticated))
                }
                "ready" -> {
                    val snapshot = runCatching { session.terminalSnapshot() }.getOrNull()
                    update(session) {
                        it.copy(
                            status = uiText(R.string.status_connected),
                            busy = false,
                            connected = true,
                            snapshot = snapshot,
                        )
                    }
                }
                "clipboard_write" -> {
                    event.payload?.optString("text")?.takeIf { it.isNotEmpty() && it.length <= 1024 * 1024 }?.let { clipboardEvents.tryEmit(it) }
                }
                "snapshot_changed" -> {
                    val snapshot = runCatching { session.terminalSnapshot() }.getOrNull() ?: continue
                    update(session) { it.copy(snapshot = snapshot) }
                }
                "host_key_unknown", "host_key_changed" -> {
                    val fingerprint = event.fingerprint ?: continue
                    update(session) {
                        it.copy(
                            status = if (event.type == "host_key_changed") {
                                uiText(R.string.host_key_changed_warning)
                            } else {
                                uiText(R.string.host_key_confirmation)
                            },
                            busy = false,
                            connected = false,
                            hostKeyPrompt = HostKeyPrompt(
                                fingerprint = fingerprint,
                                algorithm = event.algorithm,
                                expectedFingerprint = event.expectedFingerprint,
                            ),
                        )
                    }
                }
                "error" -> {
                    finishSession(session) {
                        it.copy(
                            status = if (it.hostKeyPrompt != null) it.status else event.message?.asUiText()
                                ?: uiText(R.string.terminal_status_failed),
                            busy = false,
                            connected = false,
                            snapshot = null,
                        )
                    }
                    break
                }
                "closed", "event_stream_closed" -> {
                    finishSession(session, shellExited = event.type == "closed" && event.exitStatus != null) {
                        it.copy(
                            status = event.exitStatus?.let {
                                uiText(R.string.terminal_status_exited, it)
                            } ?: uiText(R.string.terminal_status_closed),
                            busy = false,
                            connected = false,
                        )
                    }
                    break
                }
            }
        }
    }

    private suspend fun closeNative() {
        eventJob?.cancel()
        eventJob = null
        val session = nativeSession
        nativeSession = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { session?.close() } }
    }

    private suspend fun finishSession(session: NativeSession, shellExited: Boolean = false,
        block: (TerminalConnectionState) -> TerminalConnectionState) {
        try {
            withContext(Dispatchers.Main.immediate) {
                if (nativeSession === session) {
                    val wasConnected = mutableState.value.connected
                    nativeSession = null
                    eventJob = null
                    mutableState.value = block(mutableState.value)
                    if (shellExited && wasConnected) onExited?.invoke()
                }
            }
        } finally {
            // Retire the native entry even if exit handling cancels this poller.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { session.close() } }
        }
    }

    private suspend fun update(session: NativeSession, block: (TerminalConnectionState) -> TerminalConnectionState) {
        withContext(Dispatchers.Main.immediate) {
            if (nativeSession === session) mutableState.value = block(mutableState.value)
        }
    }

    override fun close() {
        connectJob?.cancel()
        connectJob = null
        eventJob?.cancel()
        eventJob = null
        val session = nativeSession
        nativeSession = null
        mutableState.value = mutableState.value.copy(
            status = uiText(R.string.terminal_status_disconnected),
            busy = false,
            connected = false,
        )
        scope.launch(Dispatchers.IO) { runCatching { session?.close() } }
    }
}
