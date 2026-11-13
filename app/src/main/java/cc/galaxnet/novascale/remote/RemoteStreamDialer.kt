/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.remote

import android.os.ParcelFileDescriptor
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetDestination
import cc.galaxnet.novascale.nativecore.NativeSession
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val DIRECT_CONNECT_TIMEOUT_MS = 15_000

/**
 * Runs connection setup off the UI thread, adopting one connected FD in Rust.
 * The caller owns the resulting session; cancellation before delivery closes it.
 */
internal suspend fun startRemoteSession(
    config: RemoteConnectionConfig,
    tailnet: TailnetBackend,
    start: (Int) -> NativeSession,
): NativeSession {
    var created: NativeSession? = null
    val callerJob = currentCoroutineContext()[Job]
    try {
        val descriptor = when (config.transport) {
            SshTransport.TAILNET -> dialTailnet(tailnet, TailnetDestination(config.host.trim(), config.portNumber, config.tailnetProfileId))
            SshTransport.DIRECT -> dialDirect(config.host.trim(), config.portNumber)
        }
        descriptor.use {
            withContext(NonCancellable + Dispatchers.IO) {
                // Check the original job, since this small ownership-transfer
                // block is intentionally noncancellable.
                callerJob?.ensureActive()
                created = start(it.detachFd())
            }
        }
        currentCoroutineContext().ensureActive()
        return checkNotNull(created).also { created = null }
    } finally {
        created?.let { session ->
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
        }
    }
}

/**
 * Go's dial call cannot be interrupted before it returns a connection ID.
 * Keep it in a cleanup worker; a cancelled caller resumes immediately, while
 * a late FD is adopted and closed without ever starting native SSH.
 */
internal suspend fun dialTailnet(
    tailnet: TailnetBackend,
    destination: TailnetDestination,
): ParcelFileDescriptor = suspendCancellableCoroutine { continuation ->
    CoroutineScope(NonCancellable + Dispatchers.IO).launch {
        try {
            val stream = tailnet.dial(destination)
            val descriptor = ParcelFileDescriptor.adoptFd(stream.fd)
            continuation.resume(descriptor) { _, resource, _ ->
                runCatching { resource.close() }
                runCatching { tailnet.cancelDial(stream.connectionId) }
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

/** Connect/resolve on IO; cancellation closes the in-flight socket immediately. */
internal suspend fun dialDirect(
    host: String,
    port: Int,
    socketFactory: () -> Socket = { Socket() },
): ParcelFileDescriptor = suspendCancellableCoroutine { continuation ->
        val socket = socketFactory()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = InetSocketAddress(host, port)
                if (!continuation.isActive) return@launch
                socket.connect(endpoint, DIRECT_CONNECT_TIMEOUT_MS)
                // API 28 fromSocket() can alias the Socket's descriptor. dup()
                // always produces an independently owned descriptor before the
                // original and intermediate wrappers are closed.
                val descriptor = ParcelFileDescriptor.fromSocket(socket).use { it.dup() }
                continuation.resume(descriptor) { _, resource, _ -> runCatching { resource.close() } }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                runCatching { socket.close() }
            }
        }
        continuation.invokeOnCancellation {
            runCatching { socket.close() }
            worker.cancel()
        }
    }
