// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.Build
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.remote.AuthenticationKind
import cc.galaxnet.novascale.remote.KnownHostStore
import cc.galaxnet.novascale.remote.SshProfileStore
import cc.galaxnet.novascale.tailnet.TailnetProfilePreferences
import cc.galaxnet.novascale.tailnet.tailnetConfiguration
import cc.galaxnet.novascale.terminalfeature.*
import cc.galaxnet.novascale.ui.resolve
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Opt-in owner-device probe. Credentials stay in the app process; profiles are never changed. */
internal object LiveSshRetryChecks {
    fun run(test: Instrumentation, host: String) = runBlocking {
        require(host.isNotBlank())
        val context = test.targetContext
        val app = context.applicationContext as NovaScaleApplication
        val backend = app.tailnetBackend
        val device = Build.MODEL.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(40).ifBlank { "android" }
        if (backend.currentState !is TailnetState.Running) {
            backend.start(tailnetConfiguration(context, TailnetProfilePreferences(context).controlServer, "novascale-$device"))
        }
        val peer = withTimeout(30_000) {
            while (backend.currentPeers.none { it.displayName == host || it.dnsName?.trimEnd('.')?.substringBefore('.') == host }) delay(100)
            backend.currentPeers.first { it.displayName == host || it.dnsName?.trimEnd('.')?.substringBefore('.') == host }
        }
        val hostId = peer.stableNodeId.ifBlank { peer.dnsName ?: peer.addresses.firstOrNull() ?: peer.displayName }
        val correct = requireNotNull(SshProfileStore(context).load(hostId)) { "Host needs saved SSH settings" }
        check(correct.authenticationKind == AuthenticationKind.PASSWORD && correct.validate() == null) { "Host needs valid saved password authentication" }
        check(KnownHostStore(context).fingerprint(correct) != null) { "Verify the host key in the app first" }
        val wrong = correct.copy(password = UUID.randomUUID().toString() + "-intentionally-wrong")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var registry: TerminalSessionRegistry? = null
        lateinit var group: TerminalSessionGroup
        lateinit var runtime: TerminalRuntime
        try {
            test.runOnMainSync {
                registry = TerminalSessionRegistry(runtimeFactory = { TerminalConnectionController(context, backend, scope) }, testing = Unit)
                group = registry!!.getOrCreate("live-owner-retry", hostId, peer, wrong)
                runtime = group.tabs.value.single().runtime
                group.ensureConnected()
            }
            val failure = withTimeout(45_000) {
                runtime.state.first { !it.busy && !it.connected && it.status.resolve(context).contains("authentication", ignoreCase = true) }
            }
            check(failure.hostKeyPrompt == null) { "Host key requires review" }
            test.runOnMainSync {
                val returned = registry!!.getOrCreate("live-owner-retry", hostId, peer, correct)
                check(returned === group && returned.tabs.value.single().runtime === runtime)
                returned.ensureConnected()
            }
            withTimeout(45_000) { runtime.state.first { it.connected && it.snapshot != null } }
            // Assert the encrypted saved profile stayed unchanged throughout the probe.
            check(SshProfileStore(context).load(hostId) == correct) { "Saved profile changed during probe" }
        } finally {
            test.runOnMainSync { registry?.close() }
            withTimeoutOrNull(3_000) { scope.coroutineContext.job.children.toList().joinAll() }
            scope.cancel()
        }
    }
}
