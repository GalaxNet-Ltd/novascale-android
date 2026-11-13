// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.system.Os
import cc.galaxnet.novascale.remote.dialDirect
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Loopback and synthetic cancellation only; no tailnet or external host. */
internal object DirectConnectionChecks {
    fun run() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val descriptor = dialDirect("127.0.0.1", listener.localPort)
            val peer = withContext(Dispatchers.IO) { listener.accept() }
            peer.use {
                descriptor.use {
                    check(Os.write(it.fileDescriptor, byteArrayOf(0x6e, 0x76), 0, 2) == 2)
                    check(withContext(Dispatchers.IO) { peer.getInputStream().read() } == 0x6e)
                    check(withContext(Dispatchers.IO) { peer.getInputStream().read() } == 0x76)
                    peer.getOutputStream().write(byteArrayOf(0x61))
                    val buffer = ByteArray(1)
                    check(withContext(Dispatchers.IO) { Os.read(it.fileDescriptor, buffer, 0, 1) } == 1)
                    check(buffer[0] == 0x61.toByte())
                }
                check(withContext(Dispatchers.IO) { peer.getInputStream().read() } == -1)
            }
        }
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val blocked = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                started.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                throw SocketException("closed")
            }
            override fun close() { closed.countDown(); super.close() }
        }
        val pending = launch { dialDirect("127.0.0.1", 22) { blocked }.close() }
        check(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        pending.cancelAndJoin()
        check(closed.await(1, TimeUnit.SECONDS))
    }
}
