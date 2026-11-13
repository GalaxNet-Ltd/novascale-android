/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.herdr

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class HerdrRemoteSession(val name: String, val running: Boolean, val isDefault: Boolean)

internal fun herdrSessionOptions(sessions: List<HerdrRemoteSession>, selected: String): List<HerdrRemoteSession> =
    (sessions + HerdrRemoteSession("default", false, true) + HerdrRemoteSession(selected, false, selected == "default"))
        .filter { isValidHerdrSessionName(it.name) }.distinctBy { it.name }
        .sortedWith(compareByDescending<HerdrRemoteSession> { it.name == "default" }.thenByDescending { it.running }.thenBy { it.name })

internal fun isValidHerdrExecutable(value: String): Boolean =
    value.length < 4096 && value.none { it == '\u0000' || it == '\r' || it == '\n' }

/** The exact attachment and target epoch represented by a displayed snapshot. */
internal data class HerdrInputTarget(val sessionId: Long, val epoch: Long)

internal fun herdrInputAllowed(
    connected: Boolean,
    activeSessionId: Long?,
    current: HerdrInputTarget?,
    displayed: HerdrInputTarget?,
): Boolean = connected && displayed != null && displayed.epoch > 0 &&
    displayed == current && displayed.sessionId == activeSessionId

internal data class HerdrResize(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)
internal data class HerdrDisplaySettings(val improveTextContrast: Boolean, val focused: Boolean)

/** One sender owns the JNI command path; input is bounded and resizes keep only the latest size. */
internal class HerdrInputDispatcher(
    scope: CoroutineScope,
    private val deliver: suspend (ByteArray, HerdrInputTarget) -> Boolean,
    private val onFailure: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deliverResize: suspend (HerdrResize) -> Boolean = { true },
    private val deliverTheme: suspend (NativeTerminalTheme) -> Boolean = { true },
    private val deliverAttach: suspend (String) -> Boolean = { true },
    private val deliverAction: suspend (HerdrActionRequest) -> Boolean = { true },
    private val deliverResume: suspend () -> Boolean = { true },
    private val deliverDisplay: suspend (HerdrDisplaySettings) -> Boolean = { true },
    private val deliverProbe: suspend (String) -> Boolean = { true },
) {
    private sealed interface Command {
        data class Input(val bytes: ByteArray, val target: HerdrInputTarget) : Command
        data class Attach(val name: String) : Command
        data class Resize(val size: HerdrResize) : Command
        data class Theme(val theme: NativeTerminalTheme) : Command
        data class Display(val settings: HerdrDisplaySettings) : Command
        data class Action(val request: HerdrActionRequest) : Command
        data object Resume : Command
        data class Probe(val nonce: String) : Command
    }
    private val queue = Channel<Command>(INPUT_QUEUE_CAPACITY)
    private val sizes = Channel<HerdrResize>(Channel.CONFLATED)
    private val themes = Channel<NativeTerminalTheme>(Channel.CONFLATED)
    private val displays = Channel<HerdrDisplaySettings>(Channel.CONFLATED)
    private val stopped = AtomicBoolean(false)
    private val worker = scope.launch(dispatcher) {
        try {
            while (true) {
                val command = select<Command> {
                    sizes.onReceive { Command.Resize(it) }
                    themes.onReceive { Command.Theme(it) }
                    displays.onReceive { Command.Display(it) }
                    queue.onReceive { it }
                }
                val accepted = when (command) {
                    is Command.Input -> deliver(command.bytes, command.target)
                    is Command.Attach -> deliverAttach(command.name)
                    is Command.Resize -> deliverResize(command.size)
                    is Command.Theme -> deliverTheme(command.theme)
                    is Command.Display -> deliverDisplay(command.settings)
                    is Command.Action -> deliverAction(command.request)
                    Command.Resume -> deliverResume()
                    is Command.Probe -> deliverProbe(command.nonce)
                }
                if (!accepted) {
                    fail()
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail()
        }
    }

    fun offer(bytes: ByteArray, target: HerdrInputTarget): Boolean {
        if (stopped.get()) return false
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES || target.epoch <= 0) {
            fail()
            return false
        }
        // Callers may reuse their buffers after this returns. The copy is made
        // only for bounded payloads, and the queue retains at most 16 of them.
        if (queue.trySend(Command.Input(bytes.copyOf(), target)).isSuccess) return true
        fail()
        return false
    }

    fun offerAttach(name: String): Boolean = enqueue(Command.Attach(name))
    fun offerAction(request: HerdrActionRequest): Boolean = enqueue(Command.Action(request))
    fun offerResume(): Boolean = enqueue(Command.Resume)
    fun offerProbe(nonce: String): Boolean = enqueue(Command.Probe(nonce))

    fun offerResize(size: HerdrResize): Boolean {
        if (stopped.get()) return false
        if (sizes.trySend(size).isSuccess) return true
        fail()
        return false
    }

    fun offerTheme(theme: NativeTerminalTheme): Boolean {
        if (stopped.get()) return false
        if (themes.trySend(theme).isSuccess) return true
        fail()
        return false
    }

    fun offerDisplay(settings: HerdrDisplaySettings): Boolean {
        if (stopped.get()) return false
        if (displays.trySend(settings).isSuccess) return true
        fail()
        return false
    }

    private fun enqueue(command: Command): Boolean {
        if (stopped.get()) return false
        if (queue.trySend(command).isSuccess) return true
        fail()
        return false
    }

    private fun fail() {
        if (stopped.compareAndSet(false, true)) {
            queue.cancel()
            sizes.cancel()
            themes.cancel()
            displays.cancel()
            onFailure()
        }
    }

    suspend fun stop() {
        stopped.set(true)
        queue.cancel()
        sizes.cancel()
        themes.cancel()
        displays.cancel()
        worker.cancelAndJoin()
    }

    private companion object {
        const val INPUT_QUEUE_CAPACITY = 16
        const val MAX_INPUT_BYTES = 256 * 1024
    }
}

/** Identifies a remote session without retaining its authentication secrets. */
internal data class HerdrSessionIdentity(
    val tailnetId: String,
    val hostId: String,
    val port: Int,
    val username: String,
    val sessionName: String,
) {
    init {
        require(tailnetId.isNotBlank() && hostId.isNotBlank() && username.isNotBlank())
        require(port in 1..65535 && isValidHerdrSessionName(sessionName))
    }
}

internal fun isValidHerdrSessionName(name: String): Boolean =
    name.length in 1..64 && name != "." && name != ".." && name.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
    }

internal data class HerdrState(
    val status: UiText = uiText(R.string.terminal_status_preparing),
    val busy: Boolean = false,
    val connected: Boolean = false,
    val choosingSession: Boolean = false,
    val sessions: List<HerdrRemoteSession> = emptyList(),
    val sessionName: String? = null,
    val snapshot: TerminalRenderSnapshot? = null,
    val snapshotTarget: HerdrInputTarget? = null,
    val navigation: HerdrNavigation = HerdrNavigation(),
    val actionPending: Long? = null,
    val actionError: UiText? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val recovering: Boolean = false,
)

internal data class HerdrSessionSummary(val key: String, val hostName: String, val sessionName: String, val connected: Boolean, val busy: Boolean)

/** One attachment; UI disposal must not call close when retaining a session. */
internal class HerdrController(
    context: Context,
    private val tailnet: TailnetBackend,
    private val scope: CoroutineScope,
    private val usage: cc.galaxnet.novascale.billing.HerdrUsageManager? = null,
    private val hostIdentity: String = "",
) : AutoCloseable {
    private val knownHosts = KnownHostStore(context.applicationContext)
    private val mutableState = MutableStateFlow(HerdrState())
    val state = mutableState.asStateFlow()
    private val mutableClipboardWrites = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val clipboardWrites = mutableClipboardWrites.asSharedFlow()
    private val mutableNotices = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val notices = mutableNotices.asSharedFlow()
    private var session: NativeSession? = null
    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var inputDispatcher: HerdrInputDispatcher? = null
    private var config: RemoteConnectionConfig? = null
    val activeTransport: SshTransport?
        get() = if (session != null || connectJob?.isActive == true) config?.transport else null
    val configuredTransport: SshTransport? get() = config?.transport
    var executable = ""
        private set
    private var selectedName: String? = null
    private var actionSerial = 0L
    private var connectionGeneration = 0L
    private var columns = 80
    private var rows = 24
    private var cellWidth = 8
    private var cellHeight = 16
    private var theme: NativeTerminalTheme? = null
    private var improveTextContrast = true
    private var visitId: String? = null
    private var visitIdentity: String? = null
    private var visitConnected = false
    private var presented = false
    private var selectionJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoverySerial = 0L
    private var needsRecovery = false
    private var replacingConnection = false
    private var healthProbe: Pair<String, CompletableDeferred<Boolean>>? = null
    private var restoreViewportAfterRecovery = false

    val usageState get() = usage?.state
    fun setPresented(value: Boolean) {
        val changed = presented != value
        presented = value
        if (changed) {
            inputDispatcher?.offerDisplay(HerdrDisplaySettings(improveTextContrast, presented))
            if (!value) {
                if (visitConnected && mutableState.value.snapshot != null) needsRecovery = true
                cancelRecovery(clearNeeded = false)
            } else recoverIfNeeded()
        }
        scope.launch { syncUsage() }
    }
    private suspend fun syncUsage() {
        val id = visitId ?: return
        try { usage?.setActive(id, presented && mutableState.value.connected && !mutableState.value.recovering) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableNotices.tryEmit(uiText(R.string.pro_storage_error)) }
    }
    private suspend fun admit(config: RemoteConnectionConfig, executable: String, name: String) {
        val meter = usage ?: return
        val fields = listOf(config.transport.name, config.tailnetProfileId.orEmpty(), hostIdentity,
            config.host.trim(), config.portNumber.toString(), config.username.trim(), executable.ifBlank { "herdr" }, name)
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
        if (visitId != null && visitIdentity == identity) return
        finishVisit()
        visitId = meter.reserve(identity)
        visitIdentity = identity
        visitConnected = false
    }
    private suspend fun finishVisit() {
        val id = visitId ?: return
        usage?.end(id)
        visitId = null; visitIdentity = null; visitConnected = false
    }
    private suspend fun failedVisit() {
        syncUsage()
        if (!visitConnected) {
            try { finishVisit() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableNotices.tryEmit(uiText(R.string.pro_storage_error)) }
        }
    }

    private fun cancelRecovery(clearNeeded: Boolean = true) {
        recoverySerial++
        recoveryJob?.cancel()
        recoveryJob = null
        healthProbe = null
        if (replacingConnection) {
            connectJob?.cancel()
            session?.requestClose()
        }
        replacingConnection = false
        if (clearNeeded) { needsRecovery = false; restoreViewportAfterRecovery = false }
        mutableState.value = mutableState.value.copy(recovering = false)
    }

    private fun recoverIfNeeded() {
        val saved = config ?: return
        val name = selectedName ?: return
        if (!needsRecovery || !presented || recoveryJob?.isActive == true) return
        val serial = ++recoverySerial
        mutableState.value = mutableState.value.copy(recovering = true,
            status = uiText(R.string.herdr_restoring), snapshotTarget = null)
        recoveryJob = scope.launch {
            try {
                syncUsage()
                val healthy = if (session != null && mutableState.value.connected) {
                    val nonce = java.util.UUID.randomUUID().toString()
                    val response = CompletableDeferred<Boolean>()
                    healthProbe = nonce to response
                    inputDispatcher?.offerProbe(nonce) == true && withTimeoutOrNull(5_000) {
                        if (!response.await()) false else state.first {
                            !it.connected || it.snapshotTarget != null && it.navigation.ready && it.actionPending == null
                        }.connected
                    } == true
                } else false
                ensureActive()
                if (!healthy) {
                    // The server owns the shells. Replace only this client and
                    // retain the selected remote session and its usage visit.
                    replacingConnection = true
                    startConnection(saved, executable, name, preservePresentation = true)
                    val result = withTimeoutOrNull(20_000) {
                        connectJob?.join()
                        state.first { it.hostKeyPrompt != null || !it.busy && !it.connected || it.connected && it.snapshotTarget != null }
                    }
                    if (result == null) {
                        connectJob?.cancelAndJoin()
                        closeNative()
                        mutableState.value = mutableState.value.copy(connected = false, busy = false,
                            snapshotTarget = null, status = uiText(R.string.herdr_recovery_failed))
                    }
                } else mutableState.value = mutableState.value.copy(status = uiText(R.string.status_connected))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                connectJob?.cancelAndJoin()
                closeNative()
                mutableState.value = mutableState.value.copy(connected = false, busy = false,
                    snapshotTarget = null, status = uiText(R.string.herdr_recovery_failed))
            } finally {
                if (serial == recoverySerial) {
                    healthProbe = null
                    replacingConnection = false
                    needsRecovery = false
                    recoveryJob = null
                    mutableState.value = mutableState.value.copy(recovering = false)
                    if (restoreViewportAfterRecovery && mutableState.value.connected) {
                        mutableState.value = mutableState.value.copy(snapshotTarget = null)
                        inputDispatcher?.offerResume()
                    }
                    restoreViewportAfterRecovery = false
                    syncUsage()
                }
            }
        }
    }

    fun connect(config: RemoteConnectionConfig, executable: String = "", sessionName: String? = null) {
        cancelRecovery()
        startConnection(config, executable, sessionName)
    }

    private fun startConnection(config: RemoteConnectionConfig, executable: String, sessionName: String?, preservePresentation: Boolean = false) {
        config.validate()?.let { mutableState.value = HerdrState(status = it); return }
        require(sessionName == null || isValidHerdrSessionName(sessionName))
        require(isValidHerdrExecutable(executable))
        val generation = ++connectionGeneration
        selectionJob?.cancel()
        session?.requestClose()
        this.config = config
        this.executable = executable
        selectedName = sessionName
        val previous = connectJob
        previous?.cancel()
        // Clear the old picker's/terminal's readiness before cleanup suspends.
        // Otherwise a rapid selection can target the session being retired.
        val connecting = if (preservePresentation) mutableState.value.copy(status = uiText(R.string.herdr_restoring),
            busy = true, connected = false, recovering = true, snapshotTarget = null, actionPending = null, actionError = null, hostKeyPrompt = null)
        else HerdrState(status = uiText(R.string.terminal_status_dialing, config.endpoint), busy = true, sessionName = sessionName)
        mutableState.value = connecting
        connectJob = scope.launch {
            syncUsage()
            previous?.join()
            closeNative()
            coroutineContext.ensureActive()
            mutableState.value = connecting
            var created: NativeSession? = null
            try {
                if (sessionName != null) admit(config, executable, sessionName) else finishVisit()
                val expected = knownHosts.fingerprint(config)
                val start = withContext(Dispatchers.IO) {
                    NativeSession.prepareHerdr(config.request(expected, columns, rows, theme), executable, sessionName,
                        improveTextContrast = improveTextContrast, focused = presented)
                }
                created = startRemoteSession(config, tailnet, start)
                coroutineContext.ensureActive()
                val active = checkNotNull(created)
                session = active
                created = null
                inputDispatcher = HerdrInputDispatcher(
                    scope = scope,
                    deliver = { bytes, target ->
                        // A view can detach or switch target while an item is
                        // queued. Discard it before JNI if it is no longer
                        // authorized; Rust checks the epoch again on receipt.
                        val allowed = withContext(Dispatchers.Main.immediate) {
                            val current = mutableState.value
                            session === active && connectionGeneration == generation && !current.recovering && current.actionPending == null && herdrInputAllowed(
                                current.connected, active.id, current.snapshotTarget, target,
                            )
                        }
                        !allowed || active.sendHerdrInput(bytes, target.epoch)
                    },
                    onFailure = {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (session === active && connectionGeneration == generation) {
                                mutableState.value = mutableState.value.copy(
                                    status = uiText(R.string.terminal_status_failed),
                                    busy = false, connected = false, snapshotTarget = null,
                                )
                                val probe = healthProbe?.second
                                closeNative()
                                probe?.complete(false)
                            }
                        }
                    },
                    deliverResize = { size ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.resize(size.columns, size.rows, size.cellWidth, size.cellHeight)
                    },
                    deliverTheme = { next ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.setTerminalTheme(next)
                    },
                    deliverDisplay = { next ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.setHerdrDisplay(next.improveTextContrast, next.focused)
                    },
                    deliverAttach = { name ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.attachHerdr(name)
                    },
                    deliverResume = {
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.resumeHerdr()
                    },
                    deliverProbe = { nonce ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.probeHerdr(nonce)
                    },
                    deliverAction = { request ->
                        val valid = withContext(Dispatchers.Main.immediate) { session === active && connectionGeneration == generation }
                        !valid || active.sendHerdrAction(request.id, request.bootId, request.action.method, request.action.targetId, request.action.label)
                    },
                )
                // Apply changes made while dialing or showing the session picker.
                theme?.let { inputDispatcher?.offerTheme(it) }
                inputDispatcher?.offerDisplay(HerdrDisplaySettings(improveTextContrast, presented))
                mutableState.value = mutableState.value.copy(status = uiText(R.string.terminal_status_negotiating))
                eventJob = scope.launch(Dispatchers.IO) { poll(active, generation) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: cc.galaxnet.novascale.billing.ProLimitReached) {
                mutableState.value = HerdrState(status = uiText(R.string.pro_herdr_limit), sessionName = sessionName)
            } catch (error: Exception) {
                if (connectionGeneration == generation) {
                    mutableState.value = HerdrState(status = error.message?.asUiText() ?: uiText(R.string.terminal_status_start_failed), sessionName = sessionName)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { created?.close() }
                if (session == null && connectionGeneration == generation) withContext(NonCancellable) { failedVisit() }
            }
        }
    }

    fun connectIfNeeded(config: RemoteConnectionConfig) {
        if (mutableState.value.recovering) { restoreViewportAfterRecovery = true; return }
        if (session == null && connectJob?.isActive != true) reconnect(config)
        else if (mutableState.value.connected) {
            // The Sessions list retains the bridge. Opening its screen must
            // restore the live viewport once, independently of layout/IME resizes.
            mutableState.value = mutableState.value.copy(snapshotTarget = null)
            inputDispatcher?.offerResume()
        }
    }

    fun reconnect(config: RemoteConnectionConfig) = connect(config, executable, selectedName)

    fun discover(config: RemoteConnectionConfig, executable: String = this.executable) = connect(config, executable)

    fun selectSession(name: String) {
        require(isValidHerdrSessionName(name))
        if (!mutableState.value.choosingSession) return
        session ?: return
        if (selectionJob?.isActive == true) return
        val generation = connectionGeneration
        val saved = config ?: return
        mutableState.value = mutableState.value.copy(busy = true)
        selectionJob = scope.launch {
            try {
                admit(saved, executable, name)
                ensureActive()
                if (connectionGeneration != generation || session == null) return@launch
                selectedName = name
                mutableState.value = mutableState.value.copy(choosingSession = false, busy = true, sessionName = name)
                inputDispatcher?.offerAttach(name)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: cc.galaxnet.novascale.billing.ProLimitReached) {
                mutableState.value = mutableState.value.copy(busy = false)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, status = uiText(R.string.pro_storage_error))
            }
        }
    }

    fun performAction(action: HerdrAction, bootId: String) {
        val current = mutableState.value
        if (!current.connected || current.recovering || current.actionPending != null || current.navigation.bootId != bootId || !current.navigation.allows(action)) return
        val id = ++actionSerial
        mutableState.value = current.copy(actionPending = id, actionError = null, snapshotTarget = null)
        inputDispatcher?.offerAction(HerdrActionRequest(id, bootId, action))
    }

    fun dismissActionError() { mutableState.value = mutableState.value.copy(actionError = null) }

    fun acceptHostKey() {
        val saved = config ?: return
        val prompt = mutableState.value.hostKeyPrompt ?: return
        knownHosts.trust(saved, prompt.fingerprint)
        connect(saved, executable, selectedName)
    }

    fun rejectHostKey() {
        mutableState.value = mutableState.value.copy(hostKeyPrompt = null, busy = false, status = uiText(R.string.host_key_not_trusted))
        scope.launch { closeNative(); failedVisit() }
    }

    fun send(bytes: ByteArray, displayedTarget: HerdrInputTarget?) {
        val active = session ?: return
        val state = mutableState.value
        if (bytes.isEmpty() || state.recovering || state.actionPending != null || !herdrInputAllowed(state.connected, active.id, state.snapshotTarget, displayedTarget)) return
        val target = checkNotNull(displayedTarget)
        // The dispatcher captures the session and epoch and bounds retained
        // input without spawning a coroutine per keystroke.
        inputDispatcher?.offer(bytes, target)
    }

    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        this.columns = columns.coerceIn(20, 500)
        this.rows = rows.coerceIn(5, 300)
        this.cellWidth = cellWidth.coerceIn(1, 512)
        this.cellHeight = cellHeight.coerceIn(1, 512)
        session ?: return
        // Input from the old geometry is no longer safe until the newly sized
        // frame is submitted to the view. Multiple layout events coalesce.
        mutableState.value = mutableState.value.copy(snapshotTarget = null)
        inputDispatcher?.offerResize(HerdrResize(this.columns, this.rows, this.cellWidth, this.cellHeight))
    }

    fun applyTheme(theme: NativeTerminalTheme) {
        if (this.theme == theme) return
        this.theme = theme
        session ?: return
        inputDispatcher?.offerTheme(theme)
    }

    fun setImproveTextContrast(enabled: Boolean) {
        if (improveTextContrast == enabled) return
        improveTextContrast = enabled
        inputDispatcher?.offerDisplay(HerdrDisplaySettings(improveTextContrast, presented))
    }

    private suspend fun poll(active: NativeSession, generation: Long) {
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val event = active.pollEvent() ?: continue
                val navigation = if (event.type == "herdr_projection") event.payload?.let(HerdrNavigation::parse) else null
                val snapshot = if (event.type in setOf("ready", "snapshot_changed", "herdr_action_result", "herdr_health")) {
                    runCatching { active.herdrTerminalSnapshot() }.getOrNull()
                } else null
                withContext(Dispatchers.Main.immediate) {
                    if (session !== active || connectionGeneration != generation) return@withContext
                    when (event.type) {
                        "herdr_health" -> if (event.payload?.optString("nonce") == healthProbe?.first) {
                            val healthy = event.payload?.optBoolean("healthy") == true
                            val epoch = snapshot?.second
                            // A pong may precede the IME/foreground resize frame.
                            // Wait for that frame within the same probe deadline.
                            if (healthy && epoch != null) mutableState.value = mutableState.value.copy(snapshot = snapshot.first,
                                snapshotTarget = HerdrInputTarget(active.id, epoch))
                            healthProbe?.second?.complete(healthy)
                        }
                        "herdr_sessions" -> {
                            val list = event.payload?.optJSONArray("sessions")
                            val sessions = buildList {
                                if (list != null) for (i in 0 until list.length()) {
                                    val item = list.optJSONObject(i) ?: continue
                                    val name = item.optString("name")
                                    if (isValidHerdrSessionName(name)) add(HerdrRemoteSession(name, item.optBoolean("running"), item.optBoolean("default")))
                                }
                            }.distinctBy { it.name }
                            val choosing = selectedName == null
                            mutableState.value = mutableState.value.copy(sessions = sessions, choosingSession = choosing, busy = !choosing)
                        }
                        "herdr_projection" -> {
                            val epoch = event.payload?.optLong("target_epoch", 0)?.takeIf { it > 0 }
                            val previous = mutableState.value.snapshotTarget
                            mutableState.value = mutableState.value.copy(
                                navigation = navigation ?: HerdrNavigation(),
                                snapshotTarget = previous?.takeIf { it.sessionId == active.id && it.epoch == epoch },
                            )
                        }
                        "herdr_action_result" -> if (event.payload?.optLong("request_id") == mutableState.value.actionPending) {
                            val code = event.payload?.optString("code")
                            val error = when (code) {
                                "ok" -> null
                                "timeout", "invalid_response" -> uiText(R.string.herdr_action_unknown)
                                "stale", "busy" -> uiText(R.string.herdr_action_stale)
                                "unsupported" -> uiText(R.string.herdr_action_unsupported)
                                else -> event.payload?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }?.take(512)?.asUiText()
                                    ?: uiText(R.string.herdr_action_refused)
                            }
                            // Unchanged-target actions can complete without painting. Re-read the
                            // authoritative frame/epoch instead of restoring a pre-action target.
                            mutableState.value = mutableState.value.copy(actionPending = null, actionError = error,
                                snapshot = snapshot?.first ?: mutableState.value.snapshot,
                                snapshotTarget = snapshot?.second?.let { HerdrInputTarget(active.id, it) })
                        }
                        "ready" -> { visitConnected = true; mutableState.value = mutableState.value.copy(
                            status = uiText(R.string.status_connected), busy = false, connected = true,
                            choosingSession = false,
                            snapshot = snapshot?.first,
                            snapshotTarget = snapshot?.second?.let { HerdrInputTarget(active.id, it) },
                        ); syncUsage() }
                        "snapshot_changed" -> if (snapshot != null) mutableState.value = mutableState.value.copy(
                            snapshot = snapshot.first,
                            snapshotTarget = snapshot.second?.let { HerdrInputTarget(active.id, it) },
                        )
                        "clipboard_write" -> event.payload?.optString("text")?.takeIf { it.isNotEmpty() && it.length <= 1024 * 1024 }?.let { mutableClipboardWrites.tryEmit(it) }
                        "herdr_input_stale" -> mutableNotices.tryEmit(uiText(R.string.herdr_input_stale))
                        "host_key_unknown", "host_key_changed" -> event.fingerprint?.let { fingerprint ->
                            mutableState.value = mutableState.value.copy(
                                status = uiText(if (event.type == "host_key_changed") R.string.host_key_changed_warning else R.string.host_key_confirmation),
                                busy = false, connected = false,
                                hostKeyPrompt = HostKeyPrompt(fingerprint, event.algorithm, event.expectedFingerprint),
                            )
                        }
                        "error", "closed", "event_stream_closed" -> { healthProbe?.second?.complete(false); mutableState.value = mutableState.value.copy(
                            status = if (mutableState.value.hostKeyPrompt != null) mutableState.value.status else event.message?.asUiText() ?: uiText(R.string.terminal_status_closed),
                            busy = false, connected = false, choosingSession = false, snapshotTarget = null, actionPending = null,
                        ) }
                    }
                }
                if (event.type in setOf("error", "closed", "event_stream_closed")) break
            }
        } finally {
            active.requestClose()
            val dispatcher = withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (session === active) {
                    session = null
                    if (mutableState.value.connected) mutableState.value = mutableState.value.copy(
                        connected = false, busy = false, snapshotTarget = null, actionPending = null,
                        status = uiText(R.string.terminal_status_failed))
                    healthProbe?.second?.complete(false)
                    inputDispatcher.also { inputDispatcher = null }
                } else null
            }
            withContext(NonCancellable) { dispatcher?.stop() }
            withContext(NonCancellable + Dispatchers.IO) { active.close() }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (connectionGeneration == generation) failedVisit()
            }
        }
    }

    private suspend fun closeNative() {
        // Capture ownership before suspending. Another cleanup path must not
        // clear or close a replacement that appears while this one is waiting.
        val old = session
        session = null
        val poller = eventJob
        eventJob = null
        val dispatcher = inputDispatcher
        inputDispatcher = null
        old?.requestClose()
        withContext(NonCancellable) {
            poller?.cancelAndJoin()
            dispatcher?.stop()
            withContext(Dispatchers.IO) { old?.close() }
        }
    }

    override fun close() {
        cancelRecovery()
        connectionGeneration++
        selectionJob?.cancel()
        session?.requestClose()
        val previous = connectJob
        previous?.cancel()
        mutableState.value = HerdrState(status = uiText(R.string.terminal_status_disconnected), sessionName = selectedName)
        connectJob = scope.launch {
            previous?.join()
            closeNative()
            try { finishVisit() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableNotices.tryEmit(uiText(R.string.pro_storage_error)) }
        }
    }
}
