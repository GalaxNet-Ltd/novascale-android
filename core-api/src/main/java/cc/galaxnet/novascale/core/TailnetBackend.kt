/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.core

/** Kotlin-facing boundary for the single shared embedded tailnet node. */
interface TailnetBackend : AutoCloseable {
    val currentProfileId: String? get() = null
    val currentState: TailnetState
    val currentPeers: List<TailnetPeer>

    fun addStateListener(listener: (TailnetState) -> Unit): AutoCloseable

    fun addPeerListener(listener: (List<TailnetPeer>) -> Unit): AutoCloseable

    suspend fun start(configuration: TailnetConfiguration)

    suspend fun beginInteractiveLogin(): LoginRequest

    suspend fun beginAuthKeyLogin(key: String) { throw UnsupportedOperationException("Auth key login unavailable") }
    fun cancelAuthKeyLogin() {}

    suspend fun peers(): List<TailnetPeer>

    suspend fun ping(address: String): TailnetPingResult

    suspend fun dial(destination: TailnetDestination): DialedStream

    fun cancelDial(connectionId: String)

    suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint

    fun stopBrowserProxy(proxyId: String)

    suspend fun startLocalProxy(port: Int, username: String, password: String, realm: String): LocalProxyEndpoint =
        throw UnsupportedOperationException("Local proxy unavailable")

    fun stopLocalProxy(id: String) {}

    suspend fun logout()
}

data class TailnetConfiguration(
    val profileId: String,
    val stateDirectory: String,
    val hostname: String,
    val controlUrl: String? = null,
) {
    init {
        require(profileId.isNotBlank())
        require(stateDirectory.isNotBlank())
        require(hostname.isNotBlank())
        val parsedControlUrl = controlUrl?.let(java.net.URI::create)
        require(
            parsedControlUrl == null ||
                parsedControlUrl.scheme.equals("https", ignoreCase = true) &&
                !parsedControlUrl.host.isNullOrBlank() &&
                parsedControlUrl.userInfo == null &&
                parsedControlUrl.query == null &&
                parsedControlUrl.fragment == null
        ) {
            "Custom control URLs must be absolute HTTPS URLs without credentials, queries, or fragments"
        }
    }
}

sealed interface TailnetState {
    data object Stopped : TailnetState
    data object Starting : TailnetState
    data class LoginRequired(val request: LoginRequest?, val awaitingApproval: Boolean = false) : TailnetState
    data class Running(val self: TailnetIdentity, val peerCount: Int) : TailnetState
    data class Failed(val category: FailureCategory, val redactedMessage: String) : TailnetState
}

enum class FailureCategory {
    CONFIGURATION,
    AUTHENTICATION,
    NETWORK,
    INTERNAL,
}

data class LoginRequest(val url: String) {
    init {
        require(url.startsWith("https://")) { "Login URLs must use HTTPS" }
    }
}

data class TailnetIdentity(
    val stableNodeId: String,
    val dnsName: String,
    val addresses: List<String>,
    val displayName: String = dnsName.substringBefore('.'),
    val loginName: String? = null,
)

data class TailnetPeer(
    val stableNodeId: String,
    val displayName: String,
    val dnsName: String?,
    val addresses: List<String>,
    val online: Boolean,
    val sshAvailable: Boolean,
    val os: String? = null,
)

enum class TailnetConnectionPath {
    DIRECT,
    DERP_RELAY,
    PEER_RELAY,
}

enum class TailnetPingFailure {
    TIMEOUT,
    UNREACHABLE,
}

/**
 * One discovery ping through the application-owned Tailscale node.
 *
 * A failed ping can still carry path information observed while attempting
 * the request. Native error strings never cross this boundary.
 */
data class TailnetPingResult(
    val address: String,
    val latencyMilliseconds: Double?,
    val failure: TailnetPingFailure?,
    val path: TailnetConnectionPath?,
    val directEndpoint: String? = null,
    val relayRegion: String? = null,
    val peerRelayEndpoint: String? = null,
) {
    init {
        require(address.isNotBlank())
        require(latencyMilliseconds == null || latencyMilliseconds >= 0.0)
        require((latencyMilliseconds == null) == (failure != null))
        require(directEndpoint == null || path == TailnetConnectionPath.DIRECT)
        require(relayRegion == null || path == TailnetConnectionPath.DERP_RELAY)
        require(peerRelayEndpoint == null || path == TailnetConnectionPath.PEER_RELAY)
    }

    val isReachable: Boolean
        get() = latencyMilliseconds != null && failure == null
}

data class TailnetDestination(val host: String, val port: Int, val profileId: String? = null) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
    }
}

data class TailnetOrigin(val host: String, val port: Int) {
    init {
        require(host.isNotBlank())
        require('*' !in host && '/' !in host && '@' !in host) { "Browser origins must be exact hosts" }
        require(port in 1..65535)
    }
}

data class BrowserProxyEndpoint(
    val proxyId: String,
    val proxyUrl: String,
) {
    init {
        require(proxyId.isNotBlank())
        val uri = java.net.URI(proxyUrl)
        require(
            uri.scheme == "http" &&
                uri.host?.startsWith("127.") == true &&
                uri.host != "127.0.0.1" &&
                uri.port in 1..65535
        ) {
            "Browser proxy must be an ephemeral IPv4 loopback endpoint"
        }
    }
}

/**
 * A full-duplex descriptor returned once by Go and transferred once to Rust.
 * Kotlin must invalidate [fd] immediately after native adoption succeeds.
 */
data class DialedStream(
    val connectionId: String,
    val fd: Int,
) {
    init {
        require(connectionId.isNotBlank())
        require(fd >= 0)
    }
}

/** Contains no authentication secrets. */
data class LocalProxyEndpoint(val id: String, val address: String, val usedFallback: Boolean) {
    val httpUrl: String get() = "http://$address"
}
