/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.tailnet

import android.content.Context
import cc.galaxnet.novascale.core.TailnetConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal const val DEFAULT_CONTROL_SERVER = "https://login.tailscale.com"

internal fun tailnetConfiguration(
    context: Context,
    rawControlUrl: String,
    hostname: String,
): TailnetConfiguration {
    val controlUrl = normalizeControlUrl(rawControlUrl)
    val profileId = controlUrl?.let(::customProfileId) ?: "tailscale"
    return TailnetConfiguration(
        profileId = profileId,
        stateDirectory = File(context.noBackupFilesDir, "tailnets/$profileId").absolutePath,
        hostname = hostname,
        controlUrl = controlUrl,
    )
}

internal fun normalizeControlUrl(rawControlUrl: String): String? {
    val value = rawControlUrl.trim().trimEnd('/')
    if (value.isEmpty()) return null
    return when (value.lowercase(Locale.ROOT)) {
        "https://login.tailscale.com", "https://controlplane.tailscale.com" -> null
        else -> value
    }
}

private fun customProfileId(controlUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(controlUrl.lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
        .take(10)
        .joinToString("") { "%02x".format(it) }
    return "custom-$digest"
}

internal class TailnetProfilePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("tailnet_profile", Context.MODE_PRIVATE)

    var controlServer: String
        get() = preferences.getString("control_server", DEFAULT_CONTROL_SERVER) ?: DEFAULT_CONTROL_SERVER
        set(value) {
            preferences.edit().putString("control_server", value).apply()
        }
}
