// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
        val measuredRows = AtomicInteger()
        val measuredColumns = AtomicInteger()
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
                registry = TerminalSessionRegistry(runtimeFactory = {
                    val real = TerminalConnectionController(context, backend, scope)
                    object : TerminalRuntime by real {
                        override fun resizeWithMetrics(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
                            measuredColumns.set(columns.coerceIn(20, 500))
                            measuredRows.set(rows.coerceIn(5, 300))
                            real.resizeWithMetrics(columns, rows, cellWidth, cellHeight)
                        }
                    }
                }, testing = Unit)
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

            test.runOnMainSync { runtime.send("fixture-prompt\r".toByteArray()) }
            runBlocking { withTimeout(5_000) { runtime.state.first { it.snapshot?.accessibleText()?.contains("PROMPT-BOTTOM") == true } } }
            Thread.sleep(300) // Drain output before resizing; the fixture will now stay silent.
            fun awaitSize(columns: Int, rows: Int) = runBlocking {
                try {
                    withTimeout(3_000) { runtime.state.first { it.snapshot?.let { frame ->
                        frame.columns == columns && frame.rowCount == rows && frame.accessibleText().contains("PROMPT-BOTTOM")
                    } == true } }
                } catch (timeout: TimeoutCancellationException) {
                    val frame = runtime.state.value.snapshot
                    error("Idle SSH resize did not publish $columns x $rows; renderer still has ${frame?.columns} x ${frame?.rowCount}")
                }
            }
            // No input, theme update or new remote bytes may rescue a stale snapshot.
            test.runOnMainSync { runtime.resizeWithMetrics(80, 12, 8, 16) }
            awaitSize(80, 12)
            test.runOnMainSync {
                repeat(80) { runtime.resizeWithMetrics(70 + it % 5, 10 + it % 45, 8, 16) }
                runtime.resizeWithMetrics(80, 24, 8, 16)
            }
            awaitSize(80, 24)
            Thread.sleep(300)
            check(runtime.state.value.snapshot?.rowCount == 24) { "An obsolete resize won over the final geometry" }

            val showing = mutableStateOf(true)
            val exits = AtomicInteger()
            test.runOnMainSync {
                activity.enableEdgeToEdge()
                activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                activity.setContent {
                    NovaScaleTheme(darkTheme = true) {
                        Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (showing.value) TerminalScreen(
                            terminalSessions = registry!!, sessionKey = "retry-fixture", hostId = peer.stableNodeId,
                            peer = peer, tailnetState = backend.currentState, initialConfig = corrected,
                            terminalSettings = TerminalSettings(), terminalTheme = TerminalThemeCatalog.default,
                            onTerminalFontSize = {}, onExit = { exits.incrementAndGet(); showing.value = false },
                            onOpenNewWindow = {}, onEditSshSettings = {},
                        ) else Text("Shell exit fixture complete")
                        } }
                    }
                }
            }
            test.waitForIdleSync()
            var stage = "initial attachment"
            var geometryDiagnostic = ""
            fun awaitCondition(check: () -> Boolean) = runBlocking {
                try {
                    withTimeout(15_000) { while (!check()) delay(25) }
                } catch (timeout: TimeoutCancellationException) {
                    error("SSH UI timeout at $stage: $geometryDiagnostic")
                }
            }
            fun visibleTerminal(): cc.galaxnet.novascale.terminal.NovaTerminalView? {
                var result: cc.galaxnet.novascale.terminal.NovaTerminalView? = null
                fun visit(view: android.view.View) {
                    if (view is cc.galaxnet.novascale.terminal.NovaTerminalView && view.isShown && view.height > 0) result = view
                    else if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(activity.window.decorView)
                return result
            }
            fun promptFitsWithKeyboard(): Boolean {
                var fits = false
                test.runOnMainSync {
                    val view = visibleTerminal() ?: return@runOnMainSync
                    val frame = runtime.state.value.snapshot ?: return@runOnMainSync
                    val ime = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    geometryDiagnostic = "ime=$ime view=${view.width}x${view.height} measured=${measuredColumns.get()}x${measuredRows.get()} frame=${frame.columns}x${frame.rowCount} cursor=${frame.cursor?.row} prompt=${frame.accessibleText().contains("PROMPT-BOTTOM")}"
                    fits = ime && frame.rowCount == measuredRows.get() && frame.columns == measuredColumns.get() &&
                        frame.accessibleText().contains("PROMPT-BOTTOM") && frame.cursor?.let {
                            it.row < measuredRows.get()
                        } == true
                }
                return fits
            }
            fun requestSoftwareKeyboardForHardwareFixture() {
                // The emulator exposes an alphabetic hardware keyboard. Production
                // correctly leaves its IME closed; explicitly open it for this test.
                val hardwareKeyboard = android.view.InputDevice.getDeviceIds().any { id ->
                    android.view.InputDevice.getDevice(id)?.let {
                        !it.isVirtual && it.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    } == true
                }
                if (hardwareKeyboard) {
                    awaitCondition {
                        var visible = false
                        test.runOnMainSync { visible = visibleTerminal() != null }
                        visible
                    }
                    test.runOnMainSync { visibleTerminal()?.requestTerminalInput(showSoftwareKeyboard = true) }
                }
            }
            fun awaitSettledPrompt() {
                var stableSince = 0L
                awaitCondition {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!promptFitsWithKeyboard()) stableSince = 0L
                    else if (stableSince == 0L) stableSince = now
                    stableSince != 0L && now - stableSince >= 800
                }
            }
            requestSoftwareKeyboardForHardwareFixture()
            awaitSettledPrompt()
            // Put the complete prompt at the bottom of the keyboard-sized PTY.
            // From here through all five reattachments the host sends no more bytes.
            test.runOnMainSync { runtime.send("fixture-prompt\r".toByteArray()) }
            awaitCondition { runtime.state.value.snapshot?.rows?.lastOrNull()?.runs?.any {
                it.text.contains("PROMPT-BOTTOM")
            } == true }
            repeat(5) { pass ->
                stage = "detach ${pass + 1}"
                test.runOnMainSync {
                    visibleTerminal()?.let { view ->
                        view.releaseTerminalInput()
                        view.clearFocus()
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(view.windowToken, 0)
                    }
                    showing.value = false
                }
                awaitCondition { registry!!.sessions.value.single().attachedViewCount == 0 }
                Thread.sleep(350)
                stage = "reattach ${pass + 1}"
                test.runOnMainSync { showing.value = true }
                requestSoftwareKeyboardForHardwareFixture()
                awaitSettledPrompt()
                check(dials.get() == 3) { "Keyboard reattachment reconnected SSH" }
            }
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "ssh-reattach-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            stage = "background and resume"
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
