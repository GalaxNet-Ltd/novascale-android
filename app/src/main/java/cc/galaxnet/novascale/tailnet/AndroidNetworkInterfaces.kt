/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cc.galaxnet.novascale.gobridge.novatailnet.Novatailnet
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supplies Android's public network-interface view to the Go core.
 *
 * Ordinary Android applications cannot open the netlink route socket used by
 * Go's default interface enumeration. This follows the boundary used by the
 * official Tailscale Android client: Kotlin reads [NetworkInterface], and Go
 * consumes a small JSON snapshot through gomobile.
 */
internal class AndroidNetworkInterfaces(context: Context) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val registered = AtomicBoolean(false)
    private val publisher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "novascale-network-interfaces").apply { isDaemon = true }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedulePublish()

        override fun onLost(network: Network) = schedulePublish()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = schedulePublish()

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) = schedulePublish()
    }

    /** Registers once, and always publishes synchronously before Go starts. */
    fun start() {
        publishNow()
        if (registered.compareAndSet(false, true)) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            try {
                connectivityManager.registerNetworkCallback(request, callback)
            } catch (error: RuntimeException) {
                registered.set(false)
                throw error
            }
        }
    }

    fun publishNow() {
        Novatailnet.updateInterfacesJSON(createSnapshot())
    }

    private fun schedulePublish() {
        publisher.execute {
            // A callback can race with an interface being removed. The next
            // connectivity callback or start attempt will publish again.
            runCatching(::publishNow)
        }
    }

    private fun createSnapshot(): String {
        val output = JSONArray()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return output.toString()
        for (networkInterface in Collections.list(interfaces)) {
            runCatching { networkInterface.toJson() }
                .getOrNull()
                ?.let(output::put)
        }
        return output.toString()
    }

    private fun NetworkInterface.toJson(): JSONObject {
        val addresses = JSONArray()
        interfaceAddresses.forEach { interfaceAddress ->
            val hostAddress = interfaceAddress.address?.hostAddress ?: return@forEach
            addresses.put(
                JSONObject()
                    .put("ip", hostAddress)
                    .put("prefixLen", interfaceAddress.networkPrefixLength.toInt()),
            )
        }
        return JSONObject()
            .put("name", name)
            .put("index", index)
            .put("mtu", mtu)
            .put("up", isUp)
            .put("broadcast", supportsMulticast())
            .put("loopback", isLoopback)
            .put("pointToPoint", isPointToPoint)
            .put("multicast", supportsMulticast())
            .put("addrs", addresses)
    }
}
