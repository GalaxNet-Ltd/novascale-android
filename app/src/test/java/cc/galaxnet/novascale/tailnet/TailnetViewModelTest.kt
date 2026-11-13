/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.tailnet

import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.BrowserProxyEndpoint
import cc.galaxnet.novascale.core.DialedStream
import cc.galaxnet.novascale.core.LoginRequest
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetConfiguration
import cc.galaxnet.novascale.core.TailnetConnectionPath
import cc.galaxnet.novascale.core.TailnetDestination
import cc.galaxnet.novascale.core.TailnetIdentity
import cc.galaxnet.novascale.core.TailnetOrigin
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetPingResult
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.ui.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailnetViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun peerEventsReplaceThePersistentUiSnapshot() {
        val initial = peer("node-1", "Builder")
        val updated = peer("node-2", "Database")
        val backend = FakeTailnetBackend(
            initialState = running(peerCount = 1),
            initialPeers = listOf(initial),
        )
        val viewModel = TailnetViewModel(backend, configuration())

        assertEquals(listOf(initial), viewModel.uiState.value.peers)
        backend.emitPeers(listOf(updated))

        assertEquals(listOf(updated), viewModel.uiState.value.peers)
    }

    @Test
    fun stoppedBackendRestoresOnceFromTheViewModel() = runTest(dispatcher) {
        val configuration = configuration()
        val backend = FakeTailnetBackend(TailnetState.Stopped)

        val model = TailnetViewModel(backend, configuration)

        assertEquals(listOf(configuration), backend.startRequests)
        assertEquals(TailnetState.Starting, model.uiState.value.connection)
    }

    @Test
    fun refreshPublishesBackendPeersAndClearsPresentationError() = runTest(dispatcher) {
        val refreshed = peer("node-3", "Compiler")
        val backend = FakeTailnetBackend(running(0)).apply {
            peerResult = Result.success(listOf(refreshed))
        }
        val viewModel = TailnetViewModel(backend, configuration())

        viewModel.refreshPeers()

        assertEquals(listOf(refreshed), viewModel.uiState.value.peers)
        assertFalse(viewModel.uiState.value.refreshingPeers)
        assertNull(viewModel.uiState.value.peerMessage)
    }

    @Test
    fun refreshFailureIsUiStateAndKeepsTheLastSnapshot() = runTest(dispatcher) {
        val existing = peer("node-4", "Shell")
        val backend = FakeTailnetBackend(running(1), listOf(existing)).apply {
            peerResult = Result.failure(IllegalStateException("sensitive backend detail"))
        }
        val viewModel = TailnetViewModel(backend, configuration())

        viewModel.refreshPeers()

        assertEquals(listOf(existing), viewModel.uiState.value.peers)
        assertEquals(
            uiText(R.string.host_list_refresh_failed),
            viewModel.uiState.value.peerMessage,
        )
    }

    @Test
    fun onlineOnlyIsTheDefaultAndCanBeToggledWithoutReloadingPeers() {
        val backend = FakeTailnetBackend(running(1), listOf(peer("node-5", "Router")))
        val viewModel = TailnetViewModel(backend, configuration())

        assertTrue(viewModel.uiState.value.showOnlineOnly)
        viewModel.toggleOnlineOnly()

        assertFalse(viewModel.uiState.value.showOnlineOnly)
        assertEquals(0, backend.peerRequests)
    }

    @Test
    fun pingViewModelCollectsRouteAndLatencyRounds() = runTest(dispatcher) {
        val backend = FakeTailnetBackend(running(1)).apply {
            pingResult = TailnetPingResult(
                address = "100.64.0.2",
                latencyMilliseconds = 12.5,
                failure = null,
                path = TailnetConnectionPath.DIRECT,
                directEndpoint = "192.0.2.8:41641",
            )
        }
        val viewModel = TailnetPingViewModel(
            backend = backend,
            address = "100.64.0.2",
            totalRounds = 3,
            intervalMillis = 0,
        )

        viewModel.start()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.samples.size)
        assertEquals(TailnetConnectionPath.DIRECT, viewModel.uiState.value.latestPathResult?.path)
        assertEquals("12.5 ms", viewModel.uiState.value.latestLatencyText)
        assertFalse(viewModel.uiState.value.isRunning)
    }
}

private class FakeTailnetBackend(
    initialState: TailnetState,
    initialPeers: List<TailnetPeer> = emptyList(),
) : TailnetBackend {
    private val stateListeners = mutableListOf<(TailnetState) -> Unit>()
    private val peerListeners = mutableListOf<(List<TailnetPeer>) -> Unit>()

    override var currentState: TailnetState = initialState
        private set
    override var currentPeers: List<TailnetPeer> = initialPeers
        private set

    val startRequests = mutableListOf<TailnetConfiguration>()
    var peerResult: Result<List<TailnetPeer>> = Result.success(initialPeers)
    var peerRequests = 0
    var pingResult: TailnetPingResult? = null

    override fun addStateListener(listener: (TailnetState) -> Unit): AutoCloseable {
        stateListeners += listener
        listener(currentState)
        return AutoCloseable { stateListeners -= listener }
    }

    override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit): AutoCloseable {
        peerListeners += listener
        listener(currentPeers)
        return AutoCloseable { peerListeners -= listener }
    }

    override suspend fun start(configuration: TailnetConfiguration) {
        startRequests += configuration
    }

    override suspend fun beginInteractiveLogin(): LoginRequest = error("Not used")

    override suspend fun peers(): List<TailnetPeer> {
        peerRequests += 1
        return peerResult.getOrThrow().also(::emitPeers)
    }

    override suspend fun ping(address: String): TailnetPingResult =
        pingResult ?: error("No ping result configured")

    override suspend fun dial(destination: TailnetDestination): DialedStream = error("Not used")

    override fun cancelDial(connectionId: String) = Unit

    override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint = error("Not used")

    override fun stopBrowserProxy(proxyId: String) = Unit

    override suspend fun logout() = Unit

    override fun close() = Unit

    fun emitPeers(peers: List<TailnetPeer>) {
        currentPeers = peers
        peerListeners.forEach { it(peers) }
    }
}

private fun configuration() = TailnetConfiguration(
    profileId = "profile",
    stateDirectory = "/private/tailnet",
    hostname = "novascale-test",
)

private fun running(peerCount: Int) = TailnetState.Running(
    self = TailnetIdentity("self", "device.tailnet.ts.net", listOf("100.64.0.1")),
    peerCount = peerCount,
)

private fun peer(id: String, name: String) = TailnetPeer(
    stableNodeId = id,
    displayName = name,
    dnsName = "$name.tailnet.ts.net",
    addresses = listOf("100.64.0.2"),
    online = true,
    sshAvailable = true,
    os = "linux",
)
