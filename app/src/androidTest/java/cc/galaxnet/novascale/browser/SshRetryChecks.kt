// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.terminalfeature.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Real Rust SSH auth and Kotlin session ownership, with synthetic credentials only. */
internal object SshRetryChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity, port: Int, fingerprint: String) {
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

            test.runOnMainSync { runtime.send("fixture-kitty\r".toByteArray()) }
            val graphics = runBlocking { withTimeout(20_000) { runtime.state.first {
                val text = it.snapshot?.accessibleText().orEmpty()
                "KITTY_UPLOAD_OK" in text && "KITTY_PLACEMENT_OK" in text
            } } }.snapshot!!
            check(graphics.images.single().image.let { it.width == 1254 && it.height == 1254 && it.rgba.size == 1254 * 1254 * 4 }) {
                "Large chunked PNG upload or separate placement failed"
            }
            test.runOnMainSync { runtime.send("KITTY_STILL_INTERACTIVE\r".toByteArray()) }
            runBlocking { withTimeout(5_000) { runtime.state.first {
                it.snapshot?.accessibleText()?.contains("KITTY_STILL_INTERACTIVE") == true
            } } }

            test.runOnMainSync { runtime.send("fixture-hold-ready\r".toByteArray()) }
            runBlocking { withTimeout(5_000) { runtime.state.first { it.snapshot?.accessibleText()?.contains("SYNC-READY") == true } } }
            test.runOnMainSync { runtime.send("fixture-hold\r".toByteArray()) }
            Thread.sleep(250)
            check(runtime.state.value.snapshot?.accessibleText()?.contains("SYNC-PENDING") == false) {
                "Synchronized output exposed an incomplete frame"
            }
            // No new socket bytes, resize, snapshot polling or user input occurs.
            // A native deadline must emit the update while the server is idle.
            runBlocking { withTimeout(3_000) { runtime.state.first { it.snapshot?.accessibleText()?.contains("SYNC-PENDING") == true } } }
            check(runtime.state.value.connected) { "Render hold timeout disconnected SSH" }

            val showing = mutableStateOf(true)
            val exits = AtomicInteger()
            test.runOnMainSync {
                activity.setContent {
                    NovaScaleTheme(darkTheme = true) {
                        if (showing.value) TerminalScreen(
                            terminalSessions = registry!!, sessionKey = "retry-fixture", hostId = peer.stableNodeId,
                            peer = peer, tailnetState = backend.currentState, initialConfig = corrected,
                            terminalSettings = TerminalSettings(), terminalTheme = TerminalThemeCatalog.default,
                            onTerminalFontSize = {}, onExit = { exits.incrementAndGet(); showing.value = false },
                            onOpenNewWindow = {}, onEditSshSettings = {},
                        ) else Text("Shell exit fixture complete")
                    }
                }
            }
            test.waitForIdleSync()
            fun awaitCondition(check: () -> Boolean) = runBlocking {
                withTimeout(15_000) { while (!check()) delay(25) }
            }
            SessionResumeUiChecks.backgroundAndResume(test, activity)
            check(runtime.state.value.connected && registry!!.sessions.value.single().attachedViewCount == 1) {
                "Backgrounding detached or disconnected the SSH session"
            }
            fun terminalTop(): Int {
                var top = -1
                test.runOnMainSync {
                    fun visit(view: android.view.View) {
                        if (view is cc.galaxnet.novascale.terminal.NovaTerminalView && view.isShown && view.height > 0) {
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            top = location[1]
                        } else if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                    }
                    visit(activity.window.decorView)
                }
                return top
            }
            awaitCondition { terminalTop() >= 0 }
            val singleTabTop = terminalTop()
            repeat(2) { pass ->
                test.runOnMainSync { group.addTab(connectImmediately = true) }
                val exiting = group.tabs.value.last()
                awaitCondition { exiting.runtime.state.value.connected }
                awaitCondition { terminalTop() > singleTabTop }
                val rowHeight = (terminalTop() - singleTabTop) / activity.resources.displayMetrics.density
                check(rowHeight in 39f..41f) { "SSH tab bar uses $rowHeight dp of terminal height" }
                test.runOnMainSync {
                    // The second pass exits an unselected tab while the first stays active.
                    if (pass == 1) group.selectTab(1)
                    exiting.runtime.send("exit 37\r".toByteArray())
                }
                awaitCondition { group.tabs.value.none { it.id == exiting.id } }
                check(group.selectedTabId.value == 1 && runtime.state.value.connected)
                check(registry!!.sessions.value.single().tabCount == 1 && exits.get() == 0)
            }
            test.runOnMainSync { runtime.send("fixture-drop\r".toByteArray()) }
            awaitCondition { !runtime.state.value.connected && !runtime.state.value.busy }
            check(group.tabs.value.single().runtime === runtime && !group.isClosed.value) {
                "Transport failure removed the retryable tab"
            }
            test.runOnMainSync { runtime.connectIfNeeded(corrected) }
            awaitCondition { runtime.state.value.connected }
            test.runOnMainSync { runtime.send("exit\r".toByteArray()) }
            awaitCondition { exits.get() == 1 }
            test.waitForIdleSync()
            check(group.isClosed.value && registry!!.sessions.value.isEmpty()) { "Last exited shell left a session registered" }
            check(exits.get() == 1) { "Last-tab exit navigated more than once" }
        } finally {
            test.runOnMainSync { registry?.close(); scope.cancel() }
            if (originalFingerprint == null) known.forget(wrong) else known.trust(wrong, originalFingerprint)
        }
    }
}
