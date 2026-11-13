/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.StringRes
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.nativecore.NativeSessionRequest
import cc.galaxnet.novascale.nativecore.NativeTerminalTheme
import cc.galaxnet.novascale.nativecore.SshAuthentication
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.uiText
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal enum class AuthenticationKind(@StringRes val labelRes: Int) {
    NONE(R.string.auth_none_tailscale),
    PASSWORD(R.string.auth_password),
    PRIVATE_KEY(R.string.auth_private_key),
}

internal enum class SshTransport { TAILNET, DIRECT }

internal data class RemoteConnectionConfig(
    val host: String = "",
    val transport: SshTransport = SshTransport.TAILNET,
    val tailnetProfileId: String? = null,
    val port: String = "22",
    val username: String = "",
    val authenticationKind: AuthenticationKind = AuthenticationKind.NONE,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
) {
    fun validate(): UiText? = when {
        host.isBlank() -> uiText(R.string.validation_choose_host)
        port.toIntOrNull() !in 1..65535 -> uiText(R.string.validation_port)
        username.isBlank() -> uiText(R.string.validation_username)
        authenticationKind == AuthenticationKind.PASSWORD && password.isEmpty() ->
            uiText(R.string.validation_password)
        authenticationKind == AuthenticationKind.PRIVATE_KEY && privateKey.isBlank() ->
            uiText(R.string.validation_private_key)
        else -> null
    }

    fun request(
        expectedHostKey: String?,
        columns: Int = 80,
        rows: Int = 24,
        terminalTheme: NativeTerminalTheme? = null,
    ) =
        NativeSessionRequest(
            host = host.trim(),
            username = username.trim(),
            authentication = when (authenticationKind) {
                AuthenticationKind.NONE -> SshAuthentication.None
                AuthenticationKind.PASSWORD -> SshAuthentication.Password(password)
                AuthenticationKind.PRIVATE_KEY -> SshAuthentication.PrivateKey(
                    privateKey = privateKey,
                    passphrase = passphrase.ifEmpty { null },
                )
            },
            expectedHostKey = expectedHostKey,
            columns = columns.coerceIn(20, 500),
            rows = rows.coerceIn(5, 300),
            terminalTheme = terminalTheme,
        )

    val portNumber: Int
        get() = port.toInt()

    val endpoint: String
        get() = "${host.trim()}:$portNumber"
}

/** Fingerprints are not secrets and are kept separately from encrypted credentials. */
internal class KnownHostStore(context: Context) {
    private val appContext = context.applicationContext
    private fun preferences(config: RemoteConnectionConfig) = appContext.getSharedPreferences(
        scopedPreferenceName("known_ssh_hosts", if (config.transport == SshTransport.TAILNET) config.tailnetProfileId else null), Context.MODE_PRIVATE)

    fun fingerprint(config: RemoteConnectionConfig): String? =
        preferences(config).getString(config.endpoint, null)

    fun trust(config: RemoteConnectionConfig, fingerprint: String) {
        require(fingerprint.startsWith("SHA256:"))
        preferences(config).edit().putString(config.endpoint, fingerprint).apply()
    }

    fun forget(config: RemoteConnectionConfig) {
        preferences(config).edit().remove(config.endpoint).apply()
    }
}

/** Per-host SSH settings encrypted with a non-exportable Android Keystore key. */
internal class SshProfileStore(context: Context, private val profileId: String? = null) {
    private val preferences = context.getSharedPreferences(scopedPreferenceName(PREFERENCES_NAME, profileId), Context.MODE_PRIVATE)

    fun contains(hostId: String): Boolean = preferences.contains(preferenceKey(hostId))

    fun load(hostId: String): RemoteConnectionConfig? {
        val encoded = preferences.getString(preferenceKey(hostId), null) ?: return null
        return decode(encoded)?.let { config ->
            if (profileId != null && config.transport == SshTransport.TAILNET) config.copy(tailnetProfileId = profileId) else config
        }
    }

    private fun decode(encoded: String): RemoteConnectionConfig? = runCatching {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == FORMAT_VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP)),
            )
            val document = JSONObject(
                String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8),
            )
            RemoteConnectionConfig(
                host = document.optString("host"),
                transport = runCatching { SshTransport.valueOf(document.optString("transport")) }
                    .getOrDefault(SshTransport.TAILNET),
                tailnetProfileId = document.optString("tailnetProfileId").takeIf { it.isNotBlank() },
                port = document.optString("port", "22"),
                username = document.optString("username"),
                authenticationKind = runCatching {
                    AuthenticationKind.valueOf(document.optString("authenticationKind"))
                }.getOrDefault(AuthenticationKind.NONE),
                password = document.optString("password"),
                privateKey = document.optString("privateKey"),
                passphrase = document.optString("passphrase"),
            )
        }.getOrNull()

    fun save(hostId: String, config: RemoteConnectionConfig) {
        preferences.edit().putString(preferenceKey(hostId), encrypt(config)).apply()
    }

    /** Encrypted journal value; never store the plaintext configuration in Room. */
    fun encrypt(config: RemoteConnectionConfig): String {
        val document = JSONObject()
            .put("host", config.host.trim())
            .put("transport", config.transport.name)
            .put("tailnetProfileId", config.tailnetProfileId)
            .put("port", config.port)
            .put("username", config.username.trim())
            .put("authenticationKind", config.authenticationKind.name)
            .put("password", config.password)
            .put("privateKey", config.privateKey)
            .put("passphrase", config.passphrase)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(document.toString().toByteArray(StandardCharsets.UTF_8))
        return listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }

    fun writeEncryptedDurably(hostId: String, encoded: String) {
        check(preferences.edit().putString(preferenceKey(hostId), encoded).commit()) { "SSH profile write failed" }
    }

    fun removeDurably(hostId: String) {
        check(preferences.edit().remove(preferenceKey(hostId)).commit()) { "SSH profile deletion failed" }
    }

    fun remove(hostId: String) {
        preferences.edit().remove(preferenceKey(hostId)).apply()
    }

    fun migrateLegacyTailnet(context: Context, legacyId: String, traditionalIds: Set<String>) {
        check(profileId == null)
        val protectedKeys = traditionalIds.map(::preferenceKey).toSet()
        val target = context.getSharedPreferences(scopedPreferenceName(PREFERENCES_NAME, legacyId), Context.MODE_PRIVATE)
        val destination = target.edit()
        val source = preferences.edit()
        for ((key, value) in preferences.all) {
            if (value !is String || !key.startsWith("host_")) continue
            if (key in protectedKeys) {
                val config = decode(value) ?: continue
                if (config.transport == SshTransport.TAILNET && config.tailnetProfileId == null) {
                    source.putString(key, encrypt(config.copy(tailnetProfileId = legacyId)))
                }
            } else {
                // Ciphertext is moved without exposing credentials. Unreadable values stay
                // unreadable, confined to the original profile, and can be repaired there.
                destination.putString(key, value)
                source.remove(key)
            }
        }
        check(destination.commit())
        check(source.commit())
        val oldTrust = context.getSharedPreferences("known_ssh_hosts", Context.MODE_PRIVATE)
        val scopedTrust = context.getSharedPreferences(scopedPreferenceName("known_ssh_hosts", legacyId), Context.MODE_PRIVATE).edit()
        val remainingTrust = oldTrust.edit()
        val traditionalEndpoints = traditionalIds.mapNotNull { load(it)?.endpoint }.toSet()
        for ((endpoint, fingerprint) in oldTrust.all) if (fingerprint is String) {
            scopedTrust.putString(endpoint, fingerprint)
            if (endpoint !in traditionalEndpoints) remainingTrust.remove(endpoint)
        }
        check(scopedTrust.commit())
        check(remainingTrust.commit())
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun preferenceKey(hostId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hostId.toByteArray(StandardCharsets.UTF_8))
        return "host_" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFERENCES_NAME = "encrypted_ssh_profiles"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "novascale_ssh_profiles_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = "v1"
    }
}

internal data class HostKeyPrompt(
    val fingerprint: String,
    val algorithm: String?,
    val expectedFingerprint: String?,
) {
    val changed: Boolean
        get() = expectedFingerprint != null
}

/** IDs are local opaque identifiers, never paths or control-server URLs. */
internal fun scopedPreferenceName(base: String, profileId: String?): String =
    if (profileId == null) base else {
        require(profileId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        "${base}_$profileId"
    }
