// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import cc.galaxnet.novascale.nativecore.NativeCore
import cc.galaxnet.novascale.nativecore.NativeSession
import cc.galaxnet.novascale.nativecore.NativeSessionRequest
import cc.galaxnet.novascale.nativecore.SshAuthentication
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Local sockets only; no tailnet or real user session is involved. */
internal object HerdrNativeChecks {
    var memorySample: String = ""
        private set

    fun run(sshPort: Int, fingerprint: String, executable: String = "", name: String = "", stress: Boolean = false) {
        memorySample = ""
        DirectConnectionChecks.run()
        invalidRequestClosesTransferredDescriptor()
        oversizedStartClosesTransferredDescriptor()
        cancellationClosesStalledHandshake()
        commandAndSnapshotBoundaries()
        temporaryAdmissionLimit()
        if (sshPort > 0) {
            require(fingerprint.startsWith("SHA256:"))
            hostKeyIsRejected(sshPort, fingerprint, null, "host_key_unknown")
            hostKeyIsRejected(sshPort, fingerprint, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "host_key_changed")
            if (executable.isNotEmpty() || name.isNotEmpty()) {
                require(executable.startsWith("/") && name.startsWith("novascale-test-"))
                liveBridge(sshPort, fingerprint, executable, name, stress)
            }
        }
    }

    private class LiveProbe(val session: NativeSession) {
        var boot: String? = null
        var focusedPane: String? = null
        var lastStale: String? = null
        var maxSnapshotMillis: Long = 0

        fun waitFor(label: String, timeoutMillis: Long = 20_000, onStale: (() -> Unit)? = null, onSnapshot: ((Long?) -> Unit)? = null, predicate: (String, Int, Int, Long?) -> Boolean): Long {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            val observed = mutableListOf<String>()
            while (SystemClock.elapsedRealtime() < deadline) {
                val event = session.pollEvent(250) ?: continue
                if (observed.size < 30) observed.add(event.type)
                if (event.type == "herdr_input_stale") {
                    lastStale = event.payload?.toString()
                    onStale?.invoke()
                }
                if (event.type == "herdr_projection") {
                    event.payload?.optString("boot_id")?.takeIf { it.isNotBlank() && it != "null" }?.let { boot = it }
                    event.payload?.optString("focused_pane_id")?.takeIf { it.isNotBlank() && it != "null" }?.let { focusedPane = it }
                }
                check(event.type != "error") { "Native Herdr error: ${event.category} ${event.message}" }
                check(event.type != "closed") { "Herdr closed before $label; events=$observed" }
                if (event.type != "ready" && event.type != "snapshot_changed") continue
                val started = SystemClock.elapsedRealtimeNanos()
                val (snapshot, epoch) = session.herdrTerminalSnapshot()
                val duration = (SystemClock.elapsedRealtimeNanos() - started + 999_999) / 1_000_000
                maxSnapshotMillis = maxOf(maxSnapshotMillis, duration)
                onSnapshot?.invoke(epoch)
                if (predicate(snapshot.accessibleText(), snapshot.columns, snapshot.rowCount, epoch)) return requireNotNull(epoch)
            }
            error("Timed out waiting for $label; events=$observed; stale=$lastStale")
        }
    }

    private fun liveBridge(port: Int, fingerprint: String, executable: String, name: String, stress: Boolean) {
        fun attach(): LiveProbe {
            val fd = Socket("127.0.0.1", port).use { ParcelFileDescriptor.fromSocket(it).detachFd() }
            val request = NativeSessionRequest(
                host = "herdr-local-fixture.invalid", username = "fixture", authentication = SshAuthentication.None,
                expectedHostKey = fingerprint,
            )
            return LiveProbe(NativeSession.startHerdr(fd, request, executable, name))
        }
        val first = attach()
        val firstBoot: String
        val firstPane: String
        try {
            val epoch = first.waitFor("initial frame") { _, _, _, target -> target != null }
            firstBoot = requireNotNull(first.boot) { "Missing Herdr server boot identity" }
            firstPane = requireNotNull(first.focusedPane) { "Missing focused Herdr pane" }
            check(first.session.sendHerdrInput("printf NOVA_STALE_746\r".toByteArray(), epoch + 1000))
            check(first.session.sendHerdrInput("printf 'NOVA%s_%s' SCALE 713\r".toByteArray(), epoch))
            first.waitFor("command output") { text, _, _, target ->
                if (text.contains("NOVASCALE_713")) {
                    check(!text.contains("NOVA_STALE_746")) { "Stale target input reached remote shell" }
                    target != null
                } else false
            }
            check(first.session.resize(100, 30))
            first.waitFor("resize") { _, columns, rows, target -> columns == 100 && rows == 30 && target != null }
            if (stress) {
                first.lastStale = null
                val before = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss
                check(first.session.resize(500, 300))
                val stressEpoch = first.waitFor("maximum-size resize", 45_000) { _, columns, rows, target ->
                    columns == 500 && rows == 300 && target != null
                }
                val readyCommand = "printf 'NOVA_%s' STRESS_READY\r".toByteArray()
                check(first.session.sendHerdrInput(readyCommand, stressEpoch))
                var retries = 0
                var readinessRetryPending = false
                var readinessEpoch = stressEpoch
                fun retryReadiness(epoch: Long?) {
                    if (readinessRetryPending && epoch != null && epoch != readinessEpoch && retries++ < 3) {
                        check(first.session.sendHerdrInput(readyCommand, epoch))
                        readinessEpoch = epoch
                        readinessRetryPending = false
                    }
                }
                val verifiedEpoch = first.waitFor("stress input readiness", 30_000, onStale = {
                    // The harmless readiness probe may race a resize update.
                    // Explicitly retry it only after sampling the latest frame.
                    val discardedEpoch = first.lastStale?.let { JSONObject(it).optLong("submittedEpoch", -1) }
                    if (discardedEpoch == readinessEpoch) {
                        readinessRetryPending = true
                        retryReadiness(first.session.herdrTerminalSnapshot().second)
                    }
                }, onSnapshot = ::retryReadiness) { text, _, _, target ->
                    target != null && text.contains("NOVA_STRESS_READY")
                }
                // The marker is assembled by Python so it is absent from the
                // echoed shell command; seeing it proves output was consumed.
                val command = "python3 -c 'import sys;w=sys.stdout.write;w(\"\\x1b[H\");" +
                    "[w(\"\\x1b[38;2;%d;%d;%dmX\"%(i%256,(i//256)%256,(i//65536)%256)) for i in range(149000)];" +
                    "w(\"\\x1b[0m\\nNOVA_\"+\"STRESS_DONE\\n\")'\r"
                val stressCommand = command.toByteArray()
                var submittedEpoch = verifiedEpoch
                var stressRetryPending = false
                var stressRetries = 0
                fun retryStress(epoch: Long?) {
                    if (stressRetryPending && epoch != null && epoch != submittedEpoch && stressRetries++ < 3) {
                        check(first.session.sendHerdrInput(stressCommand, epoch))
                        submittedEpoch = epoch
                        stressRetryPending = false
                    }
                }
                check(first.session.sendHerdrInput(stressCommand, submittedEpoch))
                first.waitFor("high-output color stress", 75_000, onStale = {
                    val discardedEpoch = first.lastStale?.let { JSONObject(it).optLong("submittedEpoch", -1) }
                    if (discardedEpoch == submittedEpoch) {
                        stressRetryPending = true
                        retryStress(first.session.herdrTerminalSnapshot().second)
                    }
                }, onSnapshot = ::retryStress) { text, columns, rows, target ->
                    columns == 500 && rows == 300 && target != null && text.contains("NOVA_STRESS_DONE")
                }
                val after = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss
                val concurrent = attach()
                val withTwo: Int
                val maxSnapshotMillis: Long
                try {
                    concurrent.waitFor("second concurrent attachment") { _, _, _, target -> target != null }
                    check(concurrent.session.resize(500, 300))
                    concurrent.waitFor("second maximum-size resize", 45_000) { _, columns, rows, target ->
                        columns == 500 && rows == 300 && target != null
                    }
                    withTwo = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss
                    maxSnapshotMillis = maxOf(first.maxSnapshotMillis, concurrent.maxSnapshotMillis)
                } finally { concurrent.session.close() }
                memorySample = "Herdr 500x300 point-in-time PSS: before=${before}KiB one=${after}KiB two=${withTwo}KiB; maximum JNI snapshot+decode=${maxSnapshotMillis}ms."
                check(first.session.resize(100, 30))
                first.waitFor("resize after stress") { _, columns, rows, target -> columns == 100 && rows == 30 && target != null }
            }
        } finally { first.session.close() }

        val second = attach()
        try {
            // Resizing the shared terminal can move prior output into remote
            // scrollback. Compare identity, then verify fresh interaction.
            val epoch = second.waitFor("reattach") { _, _, _, target -> target != null }
            check(second.boot == firstBoot) { "Reattach changed the remote Herdr server" }
            check(second.focusedPane == firstPane) { "Reattach changed the focused remote pane" }
            check(second.session.sendHerdrInput("printf 'NOVA%s_%s' SCALE 714\r".toByteArray(), epoch))
            second.waitFor("reattached command output") { text, _, _, target -> text.contains("NOVASCALE_714") && target != null }
        } finally { second.session.close() }
    }

    private fun invalidRequestClosesTransferredDescriptor() {
        val pair = ParcelFileDescriptor.createSocketPair()
        pair[1].use { peer ->
            val fd = pair[0].detachFd()
            val result = JSONObject(NativeCore.nativeStartHerdr(fd, "{}"))
            check(!result.optBoolean("ok")) { "Invalid Herdr start request was accepted" }
            assertPeerEof(peer, "invalid request")
        }
    }

    private fun cancellationClosesStalledHandshake() {
        val pair = ParcelFileDescriptor.createSocketPair()
        pair[1].use { peer ->
            val request = NativeSessionRequest(host = "fixture.invalid", username = "fixture", authentication = SshAuthentication.None)
            val session = NativeSession.startHerdr(pair[0].detachFd(), request, "", null)
            try {
                // The peer deliberately never sends an SSH banner, so the native
                // handshake remains pending until the explicit close.
                awaitPeerBytes(peer)
            } finally {
                session.close()
            }
            assertPeerEof(peer, "cancelled handshake")
        }
    }

    private fun oversizedStartClosesTransferredDescriptor() {
        val pair = ParcelFileDescriptor.createSocketPair()
        pair[1].use { peer ->
            val fd = pair[0].detachFd()
            // Larger than the JNI pre-conversion limit, with otherwise valid
            // fields. Rejection must still consume the transferred descriptor.
            val request = JSONObject()
                .put("host", "a".repeat(1024 * 1024 + 1))
                .put("username", "fixture")
                .put("authentication", JSONObject().put("type", "none"))
                .toString()
            val result = JSONObject(NativeCore.nativeStartHerdr(fd, request))
            check(!result.optBoolean("ok")) { "Oversized Herdr request was accepted" }
            assertPeerEof(peer, "oversized start request")
        }
    }

    private fun commandAndSnapshotBoundaries() {
        val pair = ParcelFileDescriptor.createSocketPair()
        pair[1].use { peer ->
            val request = NativeSessionRequest(host = "fixture.invalid", username = "fixture", authentication = SshAuthentication.None)
            val session = NativeSession.startHerdr(pair[0].detachFd(), request, "", null)
            try {
                awaitPeerBytes(peer) // The connection is held in the SSH handshake.
                fun rejected(raw: String) = check(!NativeCore.nativeSendCommand(session.id, raw)) {
                    "Malformed, oversized, or unsupported Herdr command was accepted"
                }
                rejected("{")
                rejected("{\"type\":\"input\",\"base64\":\"AA==\",\"herdr_target_epoch\":0}")
                rejected("{\"type\":\"input\",\"base64\":\"AA==\"}")
                rejected("{\"type\":\"resize\",\"columns\":501,\"rows\":24,\"cell_width\":8,\"cell_height\":16}")
                val overUtf16 = JSONObject().put("type", "input")
                    .put("base64", "A".repeat(352 * 1024 + 1))
                    .put("herdr_target_epoch", 1).toString()
                rejected(overUtf16)
                // The UTF-16 length is below the JNI cap, but the UTF-8 byte
                // length exceeds it; the second check must also fail closed.
                val overUtf8 = JSONObject().put("type", "input")
                    .put("base64", "é".repeat(200 * 1024))
                    .put("herdr_target_epoch", 1).toString()
                rejected(overUtf8)

                val wrongKind = ParcelFileDescriptor.createSocketPair()
                wrongKind[0].use { protected ->
                    wrongKind[1].use {
                        val wrongFd = protected.fd
                        for (kind in listOf("sftp_upload", "sftp_download")) {
                            rejected(JSONObject().put("type", kind).put("request_id", "fixture")
                                .put("path", "/tmp/fixture").put("local_fd", wrongFd).toString())
                            // Wrong-kind parsing must never adopt/close another
                            // descriptor merely because JSON names its number.
                            Os.fstat(protected.fileDescriptor)
                        }
                    }
                }

                check(NativeCore.nativeSnapshot(session.id).isEmpty()) { "Ordinary snapshot bypassed Herdr guard" }
                check(NativeCore.nativeSnapshotWithImages(session.id, 0).isEmpty()) { "Ordinary image snapshot bypassed Herdr guard" }
                val snapshot = NativeCore.nativeHerdrSnapshotWithEpoch(session.id, 0)
                check(snapshot.size in 64..(32 * 1024 * 1024 + 8)) { "Unexpected bounded Herdr snapshot size" }
                check(ByteBuffer.wrap(snapshot, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long == 0L) {
                    "Unconnected snapshot authorized input"
                }

                val validResize = "{\"type\":\"resize\",\"columns\":80,\"rows\":24,\"cell_width\":8,\"cell_height\":16}"
                repeat(16) { check(NativeCore.nativeSendCommand(session.id, validResize)) { "Herdr queue filled too early" } }
                // A regressed blocking enqueue must fail the fixture rather
                // than hanging instrumentation indefinitely.
                val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "herdr-queue-check").apply { isDaemon = true } }
                try {
                    val overflow = executor.submit<Boolean> { NativeCore.nativeSendCommand(session.id, validResize) }
                    check(!overflow.get(3, TimeUnit.SECONDS)) { "A full Herdr queue accepted another command" }
                } finally { executor.shutdownNow() }
            } finally { session.close() }
            assertPeerEof(peer, "command boundary session")
        }
    }

    private fun temporaryAdmissionLimit() {
        val request = NativeSessionRequest(host = "fixture.invalid", username = "fixture", authentication = SshAuthentication.None).toJson()
        val first = ParcelFileDescriptor.createSocketPair()
        val second = ParcelFileDescriptor.createSocketPair()
        var firstId = 0L
        var secondId = 0L
        try {
            val firstResult = JSONObject(NativeCore.nativeStartHerdr(first[0].detachFd(), request))
            check(firstResult.optBoolean("ok")) { "First provisional Herdr slot was unavailable" }
            firstId = firstResult.getLong("sessionId")
            val secondResult = JSONObject(NativeCore.nativeStartHerdr(second[0].detachFd(), request))
            check(secondResult.optBoolean("ok")) { "Second provisional Herdr slot was unavailable" }
            secondId = secondResult.getLong("sessionId")
            val denied = ParcelFileDescriptor.createSocketPair()
            denied[1].use { deniedPeer ->
                val result = JSONObject(NativeCore.nativeStartHerdr(denied[0].detachFd(), request))
                check(!result.optBoolean("ok") && result.optString("error").contains("resource limit")) {
                    "Third provisional Herdr slot was not rejected"
                }
                assertPeerEof(deniedPeer, "resource limit")
            }
            NativeCore.nativeRelease(firstId)
            firstId = 0
            // Admit immediately, before even waiting for the old socket EOF.
            // A real capacity failure must not be hidden by fixture retries.
            repeat(6) {
                val candidate = ParcelFileDescriptor.createSocketPair()
                candidate[1].use { candidatePeer ->
                    val result = JSONObject(NativeCore.nativeStartHerdr(candidate[0].detachFd(), request))
                    check(result.optBoolean("ok")) { "Closed Herdr slot could not be immediately reused" }
                    NativeCore.nativeRequestClose(result.getLong("sessionId"))
                    NativeCore.nativeRelease(result.getLong("sessionId"))
                    assertPeerEof(candidatePeer, "readmission candidate")
                }
            }
            assertPeerEof(first[1], "first admitted session")
        } finally {
            if (firstId != 0L) NativeCore.nativeRelease(firstId)
            if (secondId != 0L) NativeCore.nativeRelease(secondId)
            first[0].close(); first[1].close()
            second[0].close(); second[1].close()
        }
    }

    private fun hostKeyIsRejected(port: Int, fingerprint: String, expected: String?, eventType: String) {
        val fd = Socket("127.0.0.1", port).use { ParcelFileDescriptor.fromSocket(it).detachFd() }
        val request = NativeSessionRequest(
            host = "herdr-local-fixture.invalid", username = "fixture", authentication = SshAuthentication.None,
            expectedHostKey = expected,
        )
        val session = NativeSession.startHerdr(fd, request, "", null)
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var seen = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val event = session.pollEvent(250) ?: continue
                if (event.type == eventType) {
                    check(event.fingerprint == fingerprint) { "Unexpected fixture host fingerprint" }
                    check(event.expectedFingerprint == expected) { "Incorrect expected host fingerprint" }
                    seen = true
                    break
                }
                check(event.type != "ready" && event.type != "herdr_sessions") { "Untrusted host was accepted" }
            }
            check(seen) { "Expected $eventType was not emitted" }
        } finally { session.close() }
    }

    private fun awaitPeerBytes(peer: ParcelFileDescriptor) {
        val poll = StructPollfd().apply { fd = peer.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        check(Os.poll(arrayOf(poll), 5_000) > 0) { "Native client did not start SSH" }
        val buffer = ByteArray(4096)
        check(Os.read(peer.fileDescriptor, buffer, 0, buffer.size) > 0) { "Native SSH banner missing" }
    }

    private fun assertPeerEof(peer: ParcelFileDescriptor, reason: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        val buffer = ByteArray(4096)
        while (SystemClock.elapsedRealtime() < deadline) {
            val poll = StructPollfd().apply { fd = peer.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(poll), 250) <= 0) continue
            if (Os.read(peer.fileDescriptor, buffer, 0, buffer.size) == 0) return
        }
        error("Transferred descriptor did not close after $reason")
    }
}
