// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet

import cc.galaxnet.novascale.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailnetProfileControllerTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun switchesOneNodeWithoutLoggingOutAndRestoresSavedSelection() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.restore()
        controller.idle()
        controller.activate("b")
        controller.idle()
        assertEquals(listOf("start:a", "invalidate", "close:a", "start:b", "finished"), backend.actions)
        assertEquals("b", storage.snapshot.activeId)
        assertEquals(0, backend.logouts)
        val restored = controller(storage, RecordingBackend(), backgroundScope)
        restored.restore(); restored.idle()
        assertEquals("b", restored.state.value.active?.id)
    }

    @Test fun failedTargetRemainsSelectedAndCanBeRetriedWithoutUsingAnotherAccount() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        backend.failId = "b"
        controller.activate("b"); controller.idle()
        assertEquals("b", controller.state.value.active?.id)
        assertEquals("b", storage.snapshot.activeId)
        assertNotNull(controller.state.value.error)
        assertEquals("finished", backend.actions.last())
        backend.failId = null
        controller.retry(); controller.idle()
        assertNull(controller.state.value.error)
        assertEquals("b", backend.currentProfileId)
    }

    @Test fun closeFailureKeepsTheIntendedProfileSelectedWithoutStartingASecondNode() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        backend.failClose = true
        controller.activate("b"); controller.idle()
        assertEquals("b", controller.state.value.active?.id)
        assertEquals("b", storage.snapshot.activeId)
        assertNotNull(controller.state.value.error)
        assertFalse(backend.actions.contains("start:b"))
        assertEquals("finished", backend.actions.last())
    }

    @Test fun pendingLoginIsCancelledBeforeSwitchAndRapidSecondSwitchIsIgnored() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        val login = launch { controller.login("a", null) }
        runCurrent()
        assertTrue(backend.awaitingLogin)
        controller.activate("b")
        controller.activate("a")
        controller.idle()
        assertTrue(login.isCancelled)
        assertFalse(backend.awaitingLogin)
        assertEquals("b", backend.currentProfileId)
        assertEquals("b", controller.state.value.active?.id)
    }

    @Test fun logoutCancelsPendingLoginBeforeWaitingForItsMutex() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        val login = launch { controller.login("a", null) }
        runCurrent()
        assertTrue(backend.awaitingLogin)
        controller.logout()
        assertTrue(login.isCancelled)
        assertFalse(backend.awaitingLogin)
        assertEquals(1, backend.logouts)
        assertEquals("a", controller.state.value.active?.id)
    }

    @Test fun failedPersistenceLeavesCurrentNodeAndScreensIntact() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        storage.failSelection = true
        controller.activate("b"); controller.idle()
        assertEquals("a", backend.currentProfileId)
        assertEquals(listOf("start:a"), backend.actions)
        assertEquals("a", controller.state.value.active?.id)
    }

    @Test fun addingRenamingAndRemovingInactiveProfilesNeverReconnects() = runTest(dispatcher) {
        val storage = MemoryProfiles()
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        controller.add("Work", DEFAULT_CONTROL_SERVER); controller.idle()
        controller.rename("b", "Personal"); controller.idle()
        controller.remove("b"); controller.idle()
        assertEquals(listOf("start:a"), backend.actions)
        assertEquals("a", storage.snapshot.activeId)
    }

    @Test fun replacementCommitsOnlyAfterNewNodeStartsAndRollsBackFailedStart() = runTest(dispatcher) {
        for (fails in listOf(false, true)) {
            val storage = MemoryProfiles()
            val backend = RecordingBackend()
            val controller = controller(storage, backend, backgroundScope)
            controller.restore(); controller.idle()
            if (fails) backend.failId = "replacement"
            controller.replace("Replacement", DEFAULT_CONTROL_SERVER); controller.idle()
            assertEquals(if (fails) "a" else "replacement", storage.snapshot.activeId)
            assertEquals(if (fails) "a" else "replacement", backend.currentProfileId)
            assertEquals(fails, storage.discarded)
            assertEquals(!fails, storage.committed)
            assertEquals(fails, controller.state.value.error != null)
        }
    }

    @Test fun deniedProfileSwitchLeavesNetworkAndActiveSelectionUntouched() = runTest(dispatcher) {
        val storage = MemoryProfiles().apply { restricted = true }
        val backend = RecordingBackend()
        val controller = controller(storage, backend, backgroundScope)
        controller.restore(); controller.idle()
        controller.activate("b"); controller.idle()
        assertEquals("a", storage.snapshot.activeId)
        assertEquals(listOf("start:a"), backend.actions)
        assertNull(controller.state.value.error)
    }

    private suspend fun TailnetProfileController.idle() { state.first { !it.busy } }
    private fun controller(storage: MemoryProfiles, backend: RecordingBackend, scope: CoroutineScope) = TailnetProfileController(
        storage, backend, scope, { TailnetConfiguration(it.id, "/private/${it.id}", "fixture") },
        beforeSwitch = { backend.actions += "invalidate" }, afterSwitch = { backend.actions += "finished" },
    )
}

private class MemoryProfiles : TailnetProfileStorage {
    var snapshot = TailnetProfileSnapshot(listOf(
        TailnetProfile("a", "First", DEFAULT_CONTROL_SERVER), TailnetProfile("b", "Second", DEFAULT_CONTROL_SERVER)), "a")
    var failSelection = false
    var restricted = false
    var discarded = false
    var committed = false
    override suspend fun load() = snapshot
    override suspend fun select(id: String): TailnetProfileSnapshot {
        if (restricted) throw cc.galaxnet.novascale.billing.ProLimitReached(cc.galaxnet.novascale.billing.ProFeature.TAILNETS)
        check(!failSelection)
        snapshot = snapshot.copy(activeId = id)
        return snapshot
    }
    override suspend fun stageReplacement(name: String, controlUrl: String) = TailnetProfile("replacement", name, controlUrl)
    override suspend fun commitReplacement(oldId: String, replacement: TailnetProfile): TailnetProfileSnapshot {
        committed = true
        snapshot = TailnetProfileSnapshot(snapshot.profiles.filterNot { it.id == oldId } + replacement, replacement.id)
        return snapshot
    }
    override suspend fun discardReplacement() { discarded = true }
    override suspend fun add(name: String, controlUrl: String): TailnetProfileSnapshot {
        snapshot = snapshot.copy(profiles = snapshot.profiles + TailnetProfile("c", name, controlUrl)); return snapshot
    }
    override suspend fun rename(id: String, name: String): TailnetProfileSnapshot {
        snapshot = snapshot.copy(profiles = snapshot.profiles.map { if (it.id == id) it.copy(name = name) else it }); return snapshot
    }
    override suspend fun remove(id: String): TailnetProfileSnapshot {
        snapshot = snapshot.copy(profiles = snapshot.profiles.filterNot { it.id == id }); return snapshot
    }
}
private class RecordingBackend : TailnetBackend {
    val actions = mutableListOf<String>()
    var logouts = 0
    var failId: String? = null
    var failClose = false
    var awaitingLogin = false
    override var currentProfileId: String? = null
    override var currentState: TailnetState = TailnetState.Stopped
    override val currentPeers = emptyList<TailnetPeer>()
    override fun addStateListener(listener: (TailnetState) -> Unit) = AutoCloseable { }
    override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit) = AutoCloseable { }
    override suspend fun start(configuration: TailnetConfiguration) {
        actions += "start:${configuration.profileId}"
        check(configuration.profileId != failId)
        currentProfileId = configuration.profileId
        currentState = TailnetState.LoginRequired(null)
    }
    override fun close() { actions += "close:$currentProfileId"; currentProfileId = null; currentState = TailnetState.Stopped; check(!failClose) }
    override suspend fun logout() { logouts++ }
    override suspend fun beginInteractiveLogin(): LoginRequest {
        awaitingLogin = true
        try { awaitCancellation() } finally { awaitingLogin = false }
    }
    override suspend fun peers() = currentPeers
    override suspend fun ping(address: String): TailnetPingResult = error("Unused")
    override suspend fun dial(destination: TailnetDestination): DialedStream = error("Unused")
    override fun cancelDial(connectionId: String) {}
    override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint = error("Unused")
    override fun stopBrowserProxy(proxyId: String) {}
}
