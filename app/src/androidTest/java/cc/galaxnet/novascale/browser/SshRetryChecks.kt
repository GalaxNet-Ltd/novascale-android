// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.terminalfeature.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Real Rust SSH auth and Kotlin session ownership, with synthetic credentials only. */
internal object SshRetryChecks {
    fun run(test: Instrumentation, port: Int, fingerprint: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dials = AtomicInteger()
        val identity = TailnetIdentity("fixture", "fixture.invalid", emptyList())
        val backend = object : TailnetBackend {
            override val currentState = TailnetState.Running(identity, 1)
            override val currentPeers = emptyList<TailnetPeer>()
            override fun addStateListener(listener: (TailnetState) -> Unit) = AutoCloseable {}
            override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit) = AutoCloseable {}
            override suspend fun start(configuration: TailnetConfiguration) = Unit
            override suspend fun beginInteractiveLogin(): LoginRequest = error("Unused")
            override suspend fun peers() = currentPeers
            override suspend fun ping(address: String): TailnetPingResult = error("Unused")
            override suspend fun dial(destination: TailnetDestination) = withContext(Dispatchers.IO) {
                val attempt = dials.incrementAndGet()
                val socket = Socket("127.0.0.1", port)
                val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
                socket.close()
                DialedStream("fixture-$attempt", fd)
            }
            override fun cancelDial(connectionId: String) = Unit
            override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint = error("Unused")
            override fun stopBrowserProxy(proxyId: String) = Unit
            override suspend fun logout() = Unit
            override fun close() = Unit
        }
        val context = test.targetContext
        val wrong = RemoteConnectionConfig("ssh-retry-fixture.invalid", username = "fixture",
            authenticationKind = AuthenticationKind.PASSWORD, password = "fixture-wrong")
        val corrected = wrong.copy(password = "fixture-correct")
        val peer = TailnetPeer("ssh-retry-fixture", "SSH retry fixture", "ssh-retry-fixture.invalid", emptyList(), true, true)
        val known = KnownHostStore(context)
        val originalFingerprint = known.fingerprint(wrong)
        var registry: TerminalSessionRegistry? = null
        lateinit var group: TerminalSessionGroup
        lateinit var runtime: TerminalRuntime
        try {
            known.trust(wrong, fingerprint)
            test.runOnMainSync {
                registry = TerminalSessionRegistry(runtimeFactory = { TerminalConnectionController(context, backend, scope) }, testing = Unit)
                group = registry!!.getOrCreate("retry-fixture", peer.stableNodeId, peer, wrong)
                runtime = group.tabs.value.single().runtime
                group.ensureConnected()
            }
            fun awaitFailure(attempt: Int) = runBlocking {
                withTimeout(15_000) {
                    while (dials.get() < attempt) delay(10)
                    runtime.state.first { !it.busy && !it.connected }
                }
            }
            val first = awaitFailure(1)
            check(first.hostKeyPrompt == null) { "Fixture host key was not accepted" }
            check(dials.get() == 1) { "First failure did not dial" }
            // A plain Retry must work after failure, without leaving the screen.
            test.runOnMainSync { runtime.connectIfNeeded(wrong) }
            awaitFailure(2)
            check(dials.get() == 2) { "Failed native session blocked Retry" }
            test.runOnMainSync {
                val returned = registry!!.getOrCreate("retry-fixture", peer.stableNodeId, peer, corrected)
                check(returned === group && returned.tabs.value.single().runtime === runtime)
                returned.ensureConnected()
            }
            runBlocking { withTimeout(15_000) { runtime.state.first { it.connected && it.snapshot != null } } }
            check(dials.get() == 3) { "Corrected password did not reconnect" }
            test.runOnMainSync { registry!!.getOrCreate("retry-fixture", peer.stableNodeId, peer, corrected).ensureConnected() }
            Thread.sleep(300)
            check(dials.get() == 3 && runtime.state.value.connected) { "Reattachment restarted a healthy session" }
        } finally {
            test.runOnMainSync { registry?.close(); scope.cancel() }
            if (originalFingerprint == null) known.forget(wrong) else known.trust(wrong, originalFingerprint)
        }
    }
}
