/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.tailnet

import android.content.Context
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.DialedStream
import cc.galaxnet.novascale.core.BrowserProxyEndpoint
import cc.galaxnet.novascale.core.FailureCategory
import cc.galaxnet.novascale.core.LoginRequest
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetConnectionPath
import cc.galaxnet.novascale.core.TailnetConfiguration
import cc.galaxnet.novascale.core.TailnetDestination
import cc.galaxnet.novascale.core.TailnetIdentity
import cc.galaxnet.novascale.core.TailnetOrigin
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetPingFailure
import cc.galaxnet.novascale.core.TailnetPingResult
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.gobridge.novatailnet.Node
import cc.galaxnet.novascale.gobridge.novatailnet.Novatailnet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** The only Kotlin class allowed to know about generated gomobile types. */
class GoTailnetBackend(context: Context) : TailnetBackend {
    private val appContext = context.applicationContext
    private val androidNetworkInterfaces = AndroidNetworkInterfaces(appContext)
    private val listeners = CopyOnWriteArrayList<(TailnetState) -> Unit>()
    private val peerListeners = CopyOnWriteArrayList<(List<TailnetPeer>) -> Unit>()
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "novascale-tailnet-events").apply { isDaemon = true }
    }
    private val pingExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "novascale-tailnet-ping").apply { isDaemon = true }
    }
    private val lifecycleLock = Any()

    @Volatile
    private var eventGeneration = 0L

    @Volatile
    private var node: Node? = null

    @Volatile
    private var activeConfiguration: TailnetConfiguration? = null

    @Volatile
    private var pendingLogin: CompletableDeferred<LoginRequest>? = null

    @Volatile
    private var latestLogin: LoginRequest? = null

    @Volatile
    override var currentState: TailnetState = TailnetState.Stopped
        private set

    @Volatile
    override var currentPeers: List<TailnetPeer> = emptyList()
        private set

    override fun addStateListener(listener: (TailnetState) -> Unit): AutoCloseable {
        listeners += listener
        listener(currentState)
        return AutoCloseable { listeners -= listener }
    }

    override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit): AutoCloseable {
        peerListeners += listener
        listener(currentPeers)
        return AutoCloseable { peerListeners -= listener }
    }

    override suspend fun start(configuration: TailnetConfiguration) = withContext(Dispatchers.IO) {
        val retryFailedNode = currentState is TailnetState.Failed
        updateState(TailnetState.Starting)
        try {
            androidNetworkInterfaces.start()
            var resetPeerSnapshot = false
            val activeNode = synchronized(lifecycleLock) {
                if (node != null && (activeConfiguration != configuration || retryFailedNode)) {
                    eventGeneration += 1
                    runCatching { node?.close() }
                    node = null
                    activeConfiguration = null
                    latestLogin = null
                    pendingLogin?.cancel()
                    pendingLogin = null
                    resetPeerSnapshot = true
                }
                node ?: Novatailnet.newNode(
                    configuration.stateDirectory,
                    configuration.hostname,
                    configuration.controlUrl.orEmpty(),
                ).also {
                    node = it
                    activeConfiguration = configuration
                    latestLogin = null
                }
            }
            if (resetPeerSnapshot) updatePeers(emptyList())
            activeNode.start()
            startEventPump(activeNode)
            // Avoid racing the asynchronous IPN event pump when a persisted
            // node is already authenticated. Without this snapshot the UI can
            // unnecessarily start a fresh interactive login.
            runCatching { parseStatus(activeNode.statusJSON(10_000)) }
                .getOrNull()
                ?.let { applyStatus(activeNode, it) }
            Unit
        } catch (error: Exception) {
            synchronized(lifecycleLock) {
                eventGeneration += 1
                runCatching { node?.close() }
                node = null
                activeConfiguration = null
                latestLogin = null
            }
            updatePeers(emptyList())
            val message = startupFailureMessage(error)
            updateState(TailnetState.Failed(FailureCategory.NETWORK, message))
            throw TailnetBackendException(message, error)
        }
    }

    override suspend fun beginAuthKeyLogin(key: String) = withContext(Dispatchers.IO) {
        val activeNode = node ?: throw TailnetBackendException(appContext.getString(R.string.error_tailnet_not_started))
        latestLogin = null
        updateState(TailnetState.Starting)
        try {
            if (cc.galaxnet.novascale.BuildConfig.DEBUG) android.util.Log.d("NovaTailnet", "Submitting auth-key login")
            activeNode.beginAuthKeyLogin(key)
        }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw TailnetBackendException(appContext.getString(R.string.auth_key_failed)) }
    }

    override fun cancelAuthKeyLogin() { node?.cancelAuthKeyLogin() }

    override suspend fun beginInteractiveLogin(): LoginRequest = withContext(Dispatchers.IO) {
        latestLogin?.let { return@withContext it }
        val activeNode = node ?: throw TailnetBackendException(
            appContext.getString(R.string.error_tailnet_not_started),
        )
        val request = CompletableDeferred<LoginRequest>()
        pendingLogin = request
        try {
            activeNode.beginInteractiveLogin()
            runCatching { parseStatus(activeNode.statusJSON(10_000)) }
                .getOrNull()
                ?.loginRequest
                ?.let {
                    synchronized(lifecycleLock) {
                        if (node === activeNode) { latestLogin = it; request.complete(it) }
                    }
                }
            withTimeout(30_000) { request.await() }.also {
                check(node === activeNode) { "Tailnet changed during login" }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw TailnetBackendException(loginFailureMessage(error), error)
        } finally {
            if (pendingLogin === request) pendingLogin = null
        }
    }

    override suspend fun peers(): List<TailnetPeer> = withContext(Dispatchers.IO) {
        val activeNode = node ?: return@withContext emptyList()
        try {
            parseStatus(activeNode.statusJSON(10_000)).peers.also { peers ->
                synchronized(lifecycleLock) { if (node === activeNode) updatePeers(peers) }
            }
        } catch (error: Exception) {
            throw TailnetBackendException(appContext.getString(R.string.error_read_peers), error)
        }
    }

    override suspend fun ping(address: String): TailnetPingResult =
        suspendCancellableCoroutine { continuation ->
            val activeNode = node
            if (activeNode == null) {
                continuation.resumeWith(
                    Result.failure(
                        TailnetBackendException(
                            appContext.getString(R.string.error_tailnet_not_started),
                        ),
                    ),
                )
                return@suspendCancellableCoroutine
            }
            val requestId = UUID.randomUUID().toString()
            continuation.invokeOnCancellation {
                activeNode.cancelPing(requestId)
            }
            pingExecutor.execute {
                val result = runCatching {
                    parsePingResult(
                        raw = activeNode.pingJSON(requestId, address, 2_000),
                        requestedAddress = address,
                    )
                }.recoverCatching { error ->
                    throw TailnetBackendException(
                        appContext.getString(R.string.error_ping_peer),
                        error,
                    )
                }
                continuation.resumeWith(result)
            }
        }

    override suspend fun dial(destination: TailnetDestination): DialedStream = withContext(Dispatchers.IO) {
        val activeNode = node ?: throw TailnetBackendException(
            appContext.getString(R.string.error_tailnet_not_started),
        )
        try {
            synchronized(lifecycleLock) {
                if (node !== activeNode || (destination.profileId != null && activeConfiguration?.profileId != destination.profileId)) {
                    throw TailnetBackendException(appContext.getString(R.string.tailnets_connection_changed))
                }
            }
            val handle = activeNode.dialTCP("${destination.host}:${destination.port}", 30_000)
            val rawFd = handle.takeFD()
            require(rawFd in 0..Int.MAX_VALUE.toLong()) { "Native descriptor is out of range" }
            synchronized(lifecycleLock) {
                if (node !== activeNode || (destination.profileId != null && activeConfiguration?.profileId != destination.profileId)) {
                    android.os.ParcelFileDescriptor.adoptFd(rawFd.toInt()).close()
                    throw TailnetBackendException(appContext.getString(R.string.tailnets_connection_changed))
                }
                DialedStream(connectionId = handle.connectionID(), fd = rawFd.toInt())
            }
        } catch (error: Exception) {
            if (error is TailnetBackendException) throw error
            throw TailnetBackendException(appContext.getString(R.string.error_dial_tailnet), error)
        }
    }

    override fun cancelDial(connectionId: String) {
        node?.cancelDial(connectionId)
    }

    override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint =
        withContext(Dispatchers.IO) {
            require(origins.isNotEmpty() && origins.size <= 16)
            val activeNode = node ?: throw TailnetBackendException(
                appContext.getString(R.string.error_tailnet_not_started),
            )
            val policy = JSONObject()
                .put("version", 1)
                .put(
                    "origins",
                    JSONArray().apply {
                        origins.forEach { origin ->
                            put(JSONObject().put("host", origin.host).put("port", origin.port))
                        }
                    },
                )
            try {
                val result = JSONObject(activeNode.startBrowserProxy(policy.toString()))
                BrowserProxyEndpoint(
                    proxyId = result.getString("id"),
                    proxyUrl = result.getString("proxyUrl"),
                )
            } catch (error: Exception) {
                throw TailnetBackendException(
                    appContext.getString(R.string.error_start_browser_proxy),
                    error,
                )
            }
        }

    override fun stopBrowserProxy(proxyId: String) {
        if (proxyId.isNotBlank()) node?.stopBrowserProxy(proxyId)
    }

    override val currentProfileId: String? get() = activeConfiguration?.profileId

    override suspend fun startLocalProxy(port: Int, username: String, password: String, realm: String): cc.galaxnet.novascale.core.LocalProxyEndpoint {
        val activeNode = node ?: throw TailnetBackendException(appContext.getString(R.string.error_tailnet_not_started))
        var id: String? = null
        try {
            return withContext(Dispatchers.IO) {
                val result = JSONObject(activeNode.startLocalProxy(port.toLong(), username, password, realm))
                id = result.getString("id")
                cc.galaxnet.novascale.core.LocalProxyEndpoint(id!!, result.getString("address"), result.getBoolean("fallback"))
            }
        } catch (error: Exception) {
            id?.let { activeNode.stopLocalProxy(it) }
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw TailnetBackendException(appContext.getString(R.string.error_start_browser_proxy))
        }
    }

    override fun stopLocalProxy(id: String) { node?.stopLocalProxy(id) }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            node?.logout()
            latestLogin = null
            updatePeers(emptyList())
            updateState(TailnetState.LoginRequired(null))
        } catch (error: Exception) {
            throw TailnetBackendException(appContext.getString(R.string.error_log_out), error)
        }
    }

    override fun close() {
        try {
            synchronized(lifecycleLock) {
                eventGeneration += 1
                try {
                    node?.close()
                } finally {
                    node = null
                    activeConfiguration = null
                    latestLogin = null
                    pendingLogin?.cancel()
                    pendingLogin = null
                }
            }
        } finally {
            // Even a teardown error must not expose the previous account's peers
            // or restart a browser proxy under its obsolete identity.
            updatePeers(emptyList())
            updateState(TailnetState.Stopped)
        }
    }

    private fun startEventPump(activeNode: Node) {
        val generation = synchronized(lifecycleLock) {
            eventGeneration += 1
            eventGeneration
        }
        eventExecutor.execute {
            while (node === activeNode && eventGeneration == generation) {
                val rawEvent = try {
                    activeNode.nextEvent(1_000)
                } catch (_: Exception) {
                    if (node === activeNode && eventGeneration == generation) {
                        updateState(
                            TailnetState.Failed(
                                FailureCategory.INTERNAL,
                                appContext.getString(R.string.error_tailnet_event_stopped),
                            ),
                        )
                    }
                    break
                }
                if (rawEvent.isNotEmpty() && node === activeNode && eventGeneration == generation) handleEvent(activeNode, JSONObject(rawEvent))
            }
        }
    }

    private fun handleEvent(activeNode: Node, event: JSONObject) {
        val type = event.optString("type")
        if (type == "peers_changed" || type == "login_finished" ||
            (type == "state" && event.optString("state") == "running")) {
            refreshRunningState(activeNode)
            return
        }
        synchronized(lifecycleLock) {
            if (node !== activeNode) return
            when (type) {
                "login" -> {
                    val url = event.optString("loginUrl")
                    if (url.startsWith("https://")) {
                        val request = LoginRequest(url)
                        latestLogin = request
                        pendingLogin?.complete(request)
                        updateState(TailnetState.LoginRequired(request,
                            (currentState as? TailnetState.LoginRequired)?.awaitingApproval == true))
                    }
                }
                "state" -> when (event.optString("state")) {
                    "needslogin", "needsmachineauth" -> {
                        updatePeers(emptyList())
                        updateState(TailnetState.LoginRequired(latestLogin, event.optString("state") == "needsmachineauth"))
                    }
                    "starting", "nostate" -> updateState(TailnetState.Starting)
                    "stopped" -> {
                        updatePeers(emptyList())
                        updateState(TailnetState.Stopped)
                    }
                    "failed" -> updateState(TailnetState.Failed(FailureCategory.NETWORK,
                        event.optString("message", appContext.getString(R.string.error_tailnet_failed))))
                }
            }
        }
    }

    private fun refreshRunningState(activeNode: Node) {
        try {
            val status = parseStatus(activeNode.statusJSON(10_000))
            applyStatus(activeNode, status)
        } catch (_: Exception) {
            // A transient status race is followed by another IPN event.
        }
    }

    private fun updateState(value: TailnetState) {
        if (cc.galaxnet.novascale.BuildConfig.DEBUG && currentState != value) {
            val label = when (value) {
                TailnetState.Stopped -> "stopped"
                TailnetState.Starting -> "starting"
                is TailnetState.LoginRequired -> if (value.awaitingApproval) "awaiting device approval" else "login required"
                is TailnetState.Running -> "running"
                is TailnetState.Failed -> "failed"
            }
            // Never log state.toString(): it includes account, node, and login URL data.
            android.util.Log.d("NovaTailnet", "State: $label")
        }
        currentState = value
        listeners.forEach { listener -> listener(value) }
    }

    private fun updatePeers(value: List<TailnetPeer>) {
        val snapshot = value.toList()
        if (currentPeers == snapshot) return
        currentPeers = snapshot
        peerListeners.forEach { listener -> listener(snapshot) }
    }

    private fun parseStatus(raw: String): ParsedStatus {
        val document = JSONObject(raw)
        val peers = document.optJSONArray("peers").toPeers()
        val self = document.optJSONObject("self")?.toIdentity()
        val loginRequest = document.optString("authUrl")
            .takeIf { it.startsWith("https://") }
            ?.let(::LoginRequest)
        return ParsedStatus(document.optString("backendState"), self, peers, loginRequest)
    }

    private fun applyStatus(activeNode: Node, status: ParsedStatus) = synchronized(lifecycleLock) {
        if (node !== activeNode) return@synchronized
        applyCurrentStatus(status)
    }

    private fun applyCurrentStatus(status: ParsedStatus) {
        status.loginRequest?.let { latestLogin = it }
        if (backendCanBeReportedConnected(status.backendState, status.self != null)) {
            latestLogin = null
            updatePeers(status.peers)
            updateState(TailnetState.Running(requireNotNull(status.self), status.peers.size))
            return
        }
        when (status.backendState.lowercase()) {
            "running" -> updateState(TailnetState.Starting)
            "needslogin", "needsmachineauth" -> {
                updatePeers(emptyList())
                updateState(TailnetState.LoginRequired(status.loginRequest ?: latestLogin, status.backendState.equals("needsmachineauth", ignoreCase = true)))
            }
            "starting", "nostate" -> updateState(TailnetState.Starting)
            "stopped" -> {
                updatePeers(emptyList())
                updateState(TailnetState.Stopped)
            }
        }
    }

    private fun JSONArray?.toPeers(): List<TailnetPeer> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val peer = getJSONObject(index)
                add(
                    TailnetPeer(
                        stableNodeId = peer.optString("stableNodeId"),
                        displayName = peer.optString("displayName"),
                        dnsName = peer.optString("dnsName").ifBlank { null },
                        addresses = peer.optJSONArray("addresses").toStrings(),
                        online = peer.optBoolean("online"),
                        sshAvailable = peer.optBoolean("sshAvailable"),
                        os = peer.optString("os").ifBlank { null },
                    ),
                )
            }
        }
    }

    private fun JSONObject.toIdentity() = TailnetIdentity(
        stableNodeId = optString("stableNodeId"),
        dnsName = optString("dnsName").ifBlank { optString("displayName") },
        addresses = optJSONArray("addresses").toStrings(),
        displayName = optString("displayName").ifBlank { optString("dnsName").substringBefore('.') },
        loginName = optString("loginName").ifBlank { null },
    )

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> getString(index) }
    }

    private fun parsePingResult(raw: String, requestedAddress: String): TailnetPingResult {
        val document = JSONObject(raw)
        val failure = when (document.optString("failure")) {
            "" -> null
            "timeout" -> TailnetPingFailure.TIMEOUT
            else -> TailnetPingFailure.UNREACHABLE
        }
        val path = when (document.optString("path")) {
            "direct" -> TailnetConnectionPath.DIRECT
            "derp" -> TailnetConnectionPath.DERP_RELAY
            "peer-relay" -> TailnetConnectionPath.PEER_RELAY
            else -> null
        }
        return TailnetPingResult(
            address = document.optString("ip").ifBlank { requestedAddress },
            latencyMilliseconds = if (failure == null && document.has("latencyMillis")) {
                document.getDouble("latencyMillis")
            } else {
                null
            },
            failure = failure,
            path = path,
            directEndpoint = document.optString("directEndpoint").ifBlank { null },
            relayRegion = document.optString("relayRegion").ifBlank { null },
            peerRelayEndpoint = document.optString("peerRelayEndpoint").ifBlank { null },
        )
    }

    private data class ParsedStatus(
        val backendState: String,
        val self: TailnetIdentity?,
        val peers: List<TailnetPeer>,
        val loginRequest: LoginRequest?,
    )

    private fun startupFailureMessage(error: Throwable): String {
        val detail = error.rootMessage().lowercase()
        return when {
            "certificate" in detail || "tls" in detail ->
                appContext.getString(R.string.error_control_tls)
            "no such host" in detail || "name resolution" in detail ->
                appContext.getString(R.string.error_control_dns)
            "connection refused" in detail ->
                appContext.getString(R.string.error_control_refused)
            "timeout" in detail || "deadline exceeded" in detail ->
                appContext.getString(R.string.error_control_timeout)
            "state directory" in detail || "control url" in detail || "hostname" in detail ->
                appContext.getString(R.string.error_tailnet_profile_invalid)
            else -> appContext.getString(R.string.error_tailnet_start)
        }
    }

    private fun loginFailureMessage(error: Throwable): String {
        val detail = error.rootMessage().lowercase()
        return when {
            "certificate" in detail || "tls" in detail ->
                appContext.getString(R.string.error_control_tls)
            "no such host" in detail || "name resolution" in detail ->
                appContext.getString(R.string.error_control_dns)
            "connection refused" in detail ->
                appContext.getString(R.string.error_control_refused)
            "timeout" in detail || "timed out" in detail ->
                appContext.getString(R.string.error_login_url_timeout)
            else -> appContext.getString(R.string.error_browser_login)
        }
    }

    private fun Throwable.rootMessage(): String {
        var current: Throwable = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message.orEmpty()
    }
}

class TailnetBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun backendCanBeReportedConnected(backendState: String, hasIdentity: Boolean): Boolean =
    backendState.equals("Running", ignoreCase = true) && hasIdentity
