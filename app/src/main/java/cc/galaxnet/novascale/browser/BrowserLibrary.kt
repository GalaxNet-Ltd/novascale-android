// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class BrowserSite(val id: String, val url: String, val title: String, val visited: Long, val favorite: Boolean)

/** URLs/titles are encrypted; the existing backup rules exclude this database.
 * One application-owned repository serializes visits, edits and profile removal. */
internal class BrowserLibrary(context: Context, internal val database: BrowserLibraryDatabase =
    Room.databaseBuilder(context.applicationContext, BrowserLibraryDatabase::class.java, "browser-library.db").build(),
    private val keyAlias: String = "novascale.browser.library.v1",
) {
    private val mutex = Mutex()
    private val removedProfiles = mutableSetOf<String>()
    private val dao get() = database.sites()
    private val secret: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(keyAlias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun load(profile: String): List<BrowserSite> = access(profile) { read(profile) }

    suspend fun visit(profile: String, url: String, title: String, now: Long = System.currentTimeMillis()): List<BrowserSite> = access(profile) {
        val address = validAddress(url)
        val old = read(profile).firstOrNull { it.url == address }
        val site = BrowserSite(old?.id ?: UUID.randomUUID().toString(), address,
            if (old?.favorite == true) old.title else cleanTitle(title, address), now.coerceAtLeast(1), old?.favorite == true)
        database.runInTransaction { write(profile, site); dao.trimFavoriteVisits(profile); dao.trim(profile) }
        read(profile)
    }

    suspend fun saveFavorite(profile: String, url: String, title: String, editingId: String? = null): List<BrowserSite> = access(profile) {
        val address = validAddress(url)
        val entries = read(profile)
        val edited = editingId?.let { id -> entries.firstOrNull { it.id == id } ?: error("Favorite no longer exists") }
        val sameUrl = entries.firstOrNull { it.url == address }
        val existing = sameUrl ?: edited
        val site = BrowserSite(existing?.id ?: UUID.randomUUID().toString(), address, cleanTitle(title, address),
            sameUrl?.visited ?: if (edited?.url == address) edited.visited else 0, true)
        database.runInTransaction {
            if (edited != null && edited.id != site.id) dao.delete(profile, edited.id)
            write(profile, site)
        }
        read(profile)
    }

    suspend fun removeFavorite(profile: String, id: String): List<BrowserSite> = access(profile) {
        read(profile).firstOrNull { it.id == id }?.let {
            if (it.visited > 0) write(profile, it.copy(favorite = false)) else dao.delete(profile, id)
        }
        read(profile)
    }

    suspend fun removeVisit(profile: String, id: String): List<BrowserSite> = access(profile) {
        read(profile).firstOrNull { it.id == id }?.let {
            if (it.favorite) write(profile, it.copy(visited = 0)) else dao.delete(profile, id)
        }
        read(profile)
    }

    suspend fun clearHistory(profile: String): List<BrowserSite> = access(profile) {
        database.runInTransaction { dao.clearHistory(profile); dao.clearVisits(profile) }
        read(profile)
    }

    suspend fun removeProfile(profile: String) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.deleteProfile(profile); removedProfiles.add(profile); Unit }
    }

    private suspend fun <T> access(profile: String, block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(profile.isNotBlank() && profile.length <= 80 && profile !in removedProfiles)
            block()
        }
    }
    private fun read(profile: String) = dao.all(profile).map { row ->
        require(row.payload.size in 29..40000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, row.payload.copyOfRange(0, 12)))
        cipher.updateAAD("$profile:${row.id}".toByteArray())
        val json = JSONObject(String(cipher.doFinal(row.payload.copyOfRange(12, row.payload.size)), Charsets.UTF_8))
        BrowserSite(row.id, json.getString("url"), json.getString("title"), row.visited, row.favorite)
    }
    private fun write(profile: String, site: BrowserSite) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        cipher.updateAAD("$profile:${site.id}".toByteArray())
        val bytes = JSONObject().put("url", site.url).put("title", site.title).toString().toByteArray()
        dao.save(BrowserSiteEntity(site.id, profile, cipher.iv + cipher.doFinal(bytes), site.visited, site.favorite))
    }
    private fun validAddress(url: String) = browserUrl(url).also { require(it.length <= 8192) }
    private fun cleanTitle(title: String, url: String) = title.filterNot(Char::isISOControl).trim().take(200).ifEmpty { browserDisplayHost(url) }
}

internal fun browserDisplayHost(url: String): String = runCatching { java.net.URI(url).rawAuthority }.getOrNull().orEmpty().ifEmpty { url }

internal fun browserSuggestions(sites: List<BrowserSite>, query: String): List<BrowserSite> {
    val term = query.trim().removePrefix("https://").removePrefix("http://")
    return sites.filter { term.isEmpty() || it.title.contains(term, true) || it.url.contains(term, true) }
        .sortedWith(compareByDescending<BrowserSite> { it.favorite }.thenByDescending { it.visited })
        .distinctBy { it.url }.take(8)
}
