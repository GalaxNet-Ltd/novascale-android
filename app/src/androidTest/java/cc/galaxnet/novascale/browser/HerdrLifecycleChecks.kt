/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.herdr.HerdrController
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.ui.resolve
import kotlinx.coroutines.*

/** Real SSH/JNI/controller lifecycle, using only the script's isolated hosts and sessions. */
internal object HerdrLifecycleChecks {
    fun run(test: Instrumentation, ports: List<Int>, fingerprint: String, executable: String, name: String) {
        require(ports.size == 2 && ports.all { it > 0 } && name.startsWith("novascale-test-"))
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
        val backend = (test.targetContext.applicationContext as NovaScaleApplication).tailnetBackend
        val configs = ports.map { RemoteConnectionConfig(host = "127.0.0.1", port = it.toString(),
            username = "fixture", transport = SshTransport.DIRECT, authenticationKind = AuthenticationKind.NONE) }
        val knownHosts = KnownHostStore(test.targetContext)
        val previousKeys = configs.map(knownHosts::fingerprint)
        val controllers = mutableListOf<HerdrController>()
        fun controller() = HerdrController(test.targetContext, backend, scope).also(controllers::add)
        fun until(label: String, active: HerdrController, predicate: () -> Boolean) {
            repeat(300) {
                var complete = false
                test.runOnMainSync {
                    complete = predicate()
                    if (!complete && active.activeTransport == null) {
                        error("$label failed: ${active.state.value.status.resolve(test.targetContext)}")
                    }
                }
                if (complete) return
                Thread.sleep(50)
            }
            error("Herdr lifecycle timed out: $label")
        }
        fun ready(active: HerdrController, label: String) = until(label, active) {
            active.state.value.let { it.connected && it.navigation.ready && it.snapshotTarget != null }
        }
        try {
            configs.forEach { knownHosts.trust(it, fingerprint) }
            val retained = controller()
            test.runOnMainSync { retained.connect(configs[0], executable, name) }
            ready(retained, "retained attachment")
            val retainedId = checkNotNull(retained.state.value.snapshotTarget).sessionId
            repeat(4) { round ->
                val next = controller()
                val config = configs[round % configs.size]
                val initial = if (round % 2 == 0) name else "$name-other"
                val selected = if (initial == name) "$name-other" else name
                // The previous round closes immediately before this new host
                // is opened. No delay/retry may hide an admission failure.
                test.runOnMainSync { next.connect(config, executable, initial) }
                ready(next, "new host attachment $round")
                test.runOnMainSync { next.discover(config, executable) }
                until("same-host picker $round", next) { next.state.value.choosingSession }
                test.runOnMainSync { next.selectSession(selected) }
                ready(next, "selected other session $round")
                check(next.state.value.sessionName == selected)
                test.runOnMainSync {
                    next.send("printf 'LIFECYCLE_%s\\n' $round\r".toByteArray(), next.state.value.snapshotTarget)
                }
                until("input after switching $round", next) {
                    next.state.value.snapshot?.accessibleText()?.contains("LIFECYCLE_$round") == true
                }
                ready(retained, "retained attachment after replacement $round")
                check(retained.state.value.snapshotTarget?.sessionId == retainedId) {
                    "Replacing an attachment disturbed the retained session"
                }
                test.runOnMainSync { next.close() }
            }
        } finally {
            test.runOnMainSync { controllers.forEach { it.close() } }
            runBlocking { owner.cancelAndJoin() }
            configs.zip(previousKeys).forEach { (config, previous) ->
                if (previous == null) knownHosts.forget(config) else knownHosts.trust(config, previous)
            }
        }
    }
}
