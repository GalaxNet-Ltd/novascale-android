// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.files.SftpViewModel
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Delayed synthetic tailnet dials; no tailnet, SSH server, or user data. */
internal object SftpLifecycleChecks {
    fun run(test: Instrumentation) = runBlocking {
        val pairs = List(3) { ParcelFileDescriptor.createSocketPair() }
        val started = List(3) { CompletableDeferred<Unit>() }
        val released = List(3) { CompletableDeferred<Unit>() }
        val count = AtomicInteger()
        val canceled = AtomicInteger()
        val identity = TailnetIdentity("sftp-lifecycle", "fixture.invalid", emptyList())
        val backend = object : TailnetBackend {
            override val currentState = TailnetState.Running(identity, 1)
            override val currentPeers = emptyList<TailnetPeer>()
            override fun addStateListener(listener: (TailnetState) -> Unit) = AutoCloseable {}
            override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit) = AutoCloseable {}
            override suspend fun start(configuration: TailnetConfiguration) = Unit
            override suspend fun beginInteractiveLogin(): LoginRequest = error("Unused")
            override suspend fun peers() = currentPeers
            override suspend fun ping(address: String): TailnetPingResult = error("Unused")
            override suspend fun dial(destination: TailnetDestination): DialedStream {
                val index = count.getAndIncrement()
                check(index in pairs.indices)
                started[index].complete(Unit)
                released[index].await()
                return DialedStream("sftp-lifecycle-$index", pairs[index][0].detachFd())
            }
            override fun cancelDial(connectionId: String) { canceled.incrementAndGet() }
            override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint = error("Unused")
            override fun stopBrowserProxy(proxyId: String) = Unit
            override suspend fun logout() = Unit
            override fun close() = Unit
        }
        val config = RemoteConnectionConfig(host = "sftp-lifecycle.invalid", username = "fixture")
        lateinit var model: SftpViewModel
        test.runOnMainSync { model = SftpViewModel(test.targetContext, backend); model.connect(config) }
        try {
            withTimeout(10_000) { started[0].await() }
            test.runOnMainSync { model.leaveScreen() }
            check(!model.state.value.busy && !model.state.value.connected)
            released[0].complete(Unit)
            withContext(Dispatchers.IO) { awaitEof(pairs[0][1]) }
            withTimeout(5_000) { while (canceled.get() < 1) delay(10) }
            check(!model.state.value.busy && !model.state.value.connected) { "Late dial reactivated a closed screen" }

            test.runOnMainSync { model.connect(config.copy(port = "2201")) }
            withTimeout(10_000) { started[1].await() }
            test.runOnMainSync { model.connect(config.copy(port = "2202")) }
            withTimeout(10_000) { started[2].await() }
            released[2].complete(Unit)
            // SSH identification proves the replacement was started while the
            // older dial remained stalled, without waiting for authentication.
            withContext(Dispatchers.IO) { awaitInput(pairs[2][1]) }
            released[1].complete(Unit)
            withContext(Dispatchers.IO) { awaitEof(pairs[1][1]) }
            withTimeout(5_000) { while (canceled.get() < 2) delay(10) }
            check(model.state.value.busy && !model.state.value.connected) { "Stale dial overwrote the active attempt" }
            check(!isHangup(pairs[2][1])) { "Stale cleanup closed the replacement connection" }
        } finally {
            released.forEach { it.complete(Unit) }
            test.runOnMainSync { model.leaveScreen() }
            pairs.forEach { pair -> pair.forEach { runCatching { it.close() } } }
        }
    }

    private fun poll(descriptor: ParcelFileDescriptor, timeout: Int): Short {
        val item = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
        }
        Os.poll(arrayOf(item), timeout)
        return item.revents
    }
    private fun isHangup(descriptor: ParcelFileDescriptor): Boolean =
        poll(descriptor, 0).toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR) != 0
    private fun awaitInput(descriptor: ParcelFileDescriptor) {
        check(poll(descriptor, 10_000).toInt() and OsConstants.POLLIN != 0) { "Replacement SSH did not start" }
        val buf = ByteArray(256)
        check(Os.read(descriptor.fileDescriptor, buf, 0, buf.size) > 0)
    }
    private fun awaitEof(descriptor: ParcelFileDescriptor) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val flags = poll(descriptor, 1000).toInt()
            if (flags and (OsConstants.POLLIN or OsConstants.POLLHUP) != 0) {
                if (Os.read(descriptor.fileDescriptor, ByteArray(256), 0, 256) == 0) return
            }
        }
        error("Late descriptor was not closed")
    }
}
