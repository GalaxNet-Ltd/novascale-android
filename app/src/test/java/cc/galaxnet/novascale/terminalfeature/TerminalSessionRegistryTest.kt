/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cc.galaxnet.novascale.terminalfeature

import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.AuthenticationKind
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.nativecore.NativeTerminalTheme
import cc.galaxnet.novascale.ui.asUiText
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionRegistryTest {
    @Test
    fun editingPasswordRetriesTheExistingGroupWithTheSavedCredentials() {
        val runtime = FakeTerminalRuntime()
        val registry = TerminalSessionRegistry(runtimeFactory = { runtime }, testing = Unit)
        val peer = testPeer("retry-host", "Retry host")
        val wrong = RemoteConnectionConfig(host = "retry-host", username = "tester",
            authenticationKind = AuthenticationKind.PASSWORD, password = "wrong-fixture-password")
        val group = registry.getOrCreate("same-session", "retry-host", peer, wrong)
        group.ensureConnected()
        runtime.failAuthentication()
        val corrected = wrong.copy(password = "correct-fixture-password")

        val returned = registry.getOrCreate("same-session", "retry-host", peer, corrected)
        assertSame(group, returned)
        assertSame(runtime, returned.tabs.value.single().runtime)
        returned.ensureConnected()
        assertEquals(listOf(wrong, corrected), runtime.connectionRequests)
        assertEquals(corrected, returned.tabs.value.single().config)
        assertFalse(runtime.closed)
        assertEquals(1, registry.sessions.value.size)
    }

    @Test
    fun profileEditsDoNotReconnectHealthyTabsAndNewTabsUseTheEdit() {
        val runtimes = mutableListOf<FakeTerminalRuntime>()
        val registry = TerminalSessionRegistry(runtimeFactory = { FakeTerminalRuntime().also(runtimes::add) }, testing = Unit)
        val peer = testPeer("live-host", "Live host")
        val original = RemoteConnectionConfig(host = "live-host", username = "before")
        val group = registry.getOrCreate("live-session", "live-host", peer, original)
        group.addTab(connectImmediately = true)
        group.ensureConnected()
        runtimes.forEach { it.markConnected() }
        val updated = original.copy(username = "after", port = "2222")

        registry.getOrCreate("live-session", "live-host", peer, updated).ensureConnected()
        assertTrue(group.tabs.value.all { it.config == updated })
        assertTrue(runtimes.all { it.connectionRequests == listOf(original) && !it.closed })
        group.addTab(connectImmediately = true)
        assertEquals(listOf(updated), runtimes.last().connectionRequests)
        assertEquals(3, group.tabs.value.size)
    }

    @Test
    fun detachingViewPreservesRuntimeAndReusesGroup() {
        val runtimes = mutableListOf<FakeTerminalRuntime>()
        val registry = TerminalSessionRegistry(
            runtimeFactory = { FakeTerminalRuntime().also(runtimes::add) },
            testing = Unit,
        )
        val config = RemoteConnectionConfig(host = "server.tailnet.ts.net")
        val peer = testPeer("node-1", "Server")
        val group = registry.getOrCreate("session-1", "node-1", peer, config)

        val attachment = group.attachView()
        assertFalse(registry.sessions.value.single().detached)
        attachment.close()

        assertTrue(registry.sessions.value.single().detached)
        assertFalse(runtimes.single().closed)
        assertSame(group, registry.getOrCreate("session-1", "node-1", peer, config))
    }

    @Test
    fun multipleViewsAndTabsShareOneApplicationOwnedGroup() {
        val runtimes = mutableListOf<FakeTerminalRuntime>()
        val registry = TerminalSessionRegistry(
            runtimeFactory = { FakeTerminalRuntime().also(runtimes::add) },
            testing = Unit,
        )
        val group = registry.getOrCreate(
            key = "session-2",
            hostId = "node-2",
            peer = testPeer("node-2", "Build host"),
            config = RemoteConnectionConfig(host = "100.64.0.8"),
        )
        val firstView = group.attachView("window-1")
        val secondView = group.attachView("window-2")
        group.addTab(connectImmediately = false)

        assertEquals(2, registry.sessions.value.single().attachedViewCount)
        assertEquals(2, registry.sessions.value.single().tabCount)
        group.resizeFromView(tabId = 1, attachmentId = "window-1", columns = 80, rows = 24)
        group.resizeFromView(tabId = 1, attachmentId = "window-2", columns = 120, rows = 40)
        assertEquals(listOf(120 to 40), runtimes.first().resizeEvents)

        group.activateView("window-1")
        group.resizeFromView(tabId = 1, attachmentId = "window-1", columns = 90, rows = 30)
        assertEquals(listOf(120 to 40, 90 to 30), runtimes.first().resizeEvents)

        firstView.close()
        assertFalse(registry.sessions.value.single().detached)
        secondView.close()
        assertTrue(registry.sessions.value.single().detached)
    }

    @Test
    fun explicitCloseOwnsRuntimeShutdown() {
        val runtimes = mutableListOf<FakeTerminalRuntime>()
        val serviceUpdates = mutableListOf<Pair<Boolean, Int>>()
        val registry = TerminalSessionRegistry(
            runtimeFactory = { FakeTerminalRuntime().also(runtimes::add) },
            backgroundServiceUpdater = { enabled, count -> serviceUpdates += enabled to count },
            testing = Unit,
        )
        val group = registry.getOrCreate(
            key = "session-3",
            hostId = "node-3",
            peer = testPeer("node-3", "Database"),
            config = RemoteConnectionConfig(host = "database.tailnet.ts.net"),
        )
        registry.setKeepInBackground(true)
        group.closeSession()

        assertTrue(runtimes.single().closed)
        assertTrue(registry.sessions.value.isEmpty())
        assertEquals(true to 1, serviceUpdates[serviceUpdates.lastIndex - 1])
        assertEquals(true to 0, serviceUpdates.last())
    }

    @Test
    fun oneHostCanOwnSeveralIndependentSessionGroups() {
        val runtimes = mutableListOf<FakeTerminalRuntime>()
        val registry = TerminalSessionRegistry(
            runtimeFactory = { FakeTerminalRuntime().also(runtimes::add) },
            testing = Unit,
        )
        val hostId = "node-4"
        val peer = testPeer(hostId, "Compiler")
        val config = RemoteConnectionConfig(host = "compiler.tailnet.ts.net")
        val firstKey = registry.newSessionKey(hostId)
        val secondKey = registry.newSessionKey(hostId)

        registry.getOrCreate(firstKey, hostId, peer, config)
        registry.getOrCreate(secondKey, hostId, peer, config)

        assertNotEquals(firstKey, secondKey)
        assertEquals(2, registry.sessions.value.size)
        assertEquals(listOf(hostId, hostId), registry.sessions.value.map { it.hostId })
        assertEquals(2, runtimes.size)
    }

    @Test
    fun tabsHaveStableTitlesAndCanBeRenamed() {
        val registry = TerminalSessionRegistry(
            runtimeFactory = { FakeTerminalRuntime() },
            testing = Unit,
        )
        val group = registry.getOrCreate(
            key = "session-tabs",
            hostId = "node-tabs",
            peer = testPeer("node-tabs", "Build host"),
            config = RemoteConnectionConfig(host = "build.tailnet.ts.net"),
        )

        group.addTab(connectImmediately = false)
        assertEquals(
            listOf("Terminal 1".asUiText(), "Terminal 2".asUiText()),
            group.tabs.value.map { it.title },
        )

        assertTrue(group.renameTab(group.tabs.value.last().id, " logs "))
        assertEquals("logs".asUiText(), group.tabs.value.last().title)
        assertFalse(group.renameTab(group.tabs.value.last().id, "   "))
        assertEquals("logs".asUiText(), group.tabs.value.last().title)
    }
}

private fun testPeer(hostId: String, displayName: String) = TailnetPeer(
    stableNodeId = hostId,
    displayName = displayName,
    dnsName = "$hostId.tailnet.ts.net",
    addresses = listOf("100.64.0.8"),
    online = true,
    sshAvailable = true,
    os = "linux",
)

private class FakeTerminalRuntime : TerminalRuntime {
    private val mutableState = MutableStateFlow(TerminalConnectionState())
    override val state: StateFlow<TerminalConnectionState> = mutableState
    var closed = false
    val resizeEvents = mutableListOf<Pair<Int, Int>>()
    val connectionRequests = mutableListOf<RemoteConnectionConfig>()

    override fun connect(config: RemoteConnectionConfig) {
        connectionRequests += config
        mutableState.value = TerminalConnectionState(busy = true)
    }
    override fun connectIfNeeded(config: RemoteConnectionConfig) {
        if (!mutableState.value.connected && !mutableState.value.busy) connect(config)
    }
    fun failAuthentication() { mutableState.value = TerminalConnectionState() }
    fun markConnected() { mutableState.value = TerminalConnectionState(connected = true) }
    override fun acceptHostKey() = Unit
    override fun rejectHostKey() = Unit
    override fun send(bytes: ByteArray) = Unit
    override fun resize(newColumns: Int, newRows: Int) {
        resizeEvents += newColumns to newRows
    }
    override fun scroll(rows: Int) = Unit
    override fun applyTheme(theme: NativeTerminalTheme) = Unit
    override fun disconnect() = Unit

    override fun close() {
        closed = true
    }
}
