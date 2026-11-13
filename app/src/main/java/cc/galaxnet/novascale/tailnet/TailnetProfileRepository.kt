// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet

import android.content.Context
import androidx.room.Room
import cc.galaxnet.novascale.core.TailnetConfiguration
import cc.galaxnet.novascale.remote.scopedPreferenceName
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TailnetProfile(val id: String, val name: String, val controlUrl: String) {
    fun configuration(context: Context, hostname: String) = TailnetConfiguration(
        id, profileDirectory(context, id).absolutePath, hostname, normalizeControlUrl(controlUrl),
    )
}
internal data class TailnetProfileSnapshot(val profiles: List<TailnetProfile>, val activeId: String, val freeIds: Set<String> = emptySet()) {
    val active: TailnetProfile get() = profiles.first { it.id == activeId }
}
internal interface TailnetProfileStorage {
    suspend fun load(): TailnetProfileSnapshot
    suspend fun add(name: String, controlUrl: String): TailnetProfileSnapshot
    suspend fun rename(id: String, name: String): TailnetProfileSnapshot
    suspend fun select(id: String): TailnetProfileSnapshot
    suspend fun remove(id: String): TailnetProfileSnapshot
    suspend fun selectFree(id: String): TailnetProfileSnapshot = select(id)
    suspend fun stageReplacement(name: String, controlUrl: String): TailnetProfile = error("Replacement unavailable")
    suspend fun commitReplacement(oldId: String, replacement: TailnetProfile): TailnetProfileSnapshot = error("Replacement unavailable")
    suspend fun discardReplacement() = Unit
}

/** Metadata only. Each node's private state and secrets retain a distinct opaque ID. */
internal class TailnetProfileRepository(
    private val context: Context,
    private val migrateSecrets: suspend (String) -> Unit,
    private val removeMetadata: suspend (String) -> Unit,
    internal val database: TailnetProfileDatabase = Room.databaseBuilder(
        context.applicationContext, TailnetProfileDatabase::class.java, "tailnet-profiles.db",
    ).addMigrations(MIGRATION_1_2).build(),
    private val legacyControlUrl: () -> String = { TailnetProfilePreferences(context).controlServer },
    private val paywall: cc.galaxnet.novascale.billing.PaywallManager? = null,
) : TailnetProfileStorage {
    private val mutex = Mutex()
    private val dao get() = database.profiles()

    override suspend fun load(): TailnetProfileSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (dao.all().isEmpty()) {
                val url = validatedControlServer(legacyControlUrl())
                val legacy = tailnetConfiguration(context, url, "novascale-android")
                // Write/move secrets before recording migration; replay is idempotent.
                migrateSecrets(legacy.profileId)
                database.runInTransaction {
                    dao.insert(TailnetProfileEntity(legacy.profileId, controlServerName(url), url, 0, false))
                    // An older default login may coexist with the selected custom server.
                    if (legacy.profileId != "tailscale" && profileDirectory(context, "tailscale").exists()) {
                        dao.insert(TailnetProfileEntity("tailscale", "Tailscale", DEFAULT_CONTROL_SERVER, 1, false))
                    }
                    dao.select(TailnetSelectionEntity(1, legacy.profileId))
                }
            }
            recoverDeletes()
            ensureAccess()
            snapshot()
        }
    }

    override suspend fun add(name: String, controlUrl: String) = mutate {
        val title = validatedTailnetName(name)
        val url = validatedControlServer(controlUrl)
        paywall?.requireAccess(cc.galaxnet.novascale.billing.ProFeature.TAILNETS, dao.all().count { !it.pendingDelete })
        // Never key new identities by server URL: two accounts can use one server.
        dao.insert(TailnetProfileEntity(UUID.randomUUID().toString(), title, url, System.currentTimeMillis(), false))
    }
    override suspend fun rename(id: String, name: String) = mutate {
        check(dao.rename(id, validatedTailnetName(name)) == 1)
    }
    override suspend fun select(id: String) = mutate {
        require(dao.all().any { it.id == id && !it.pendingDelete })
        val current = snapshot()
        if (id != current.activeId && id !in current.freeIds) {
            paywall?.requireAccess(cc.galaxnet.novascale.billing.ProFeature.TAILNETS, 1)
        }
        dao.select(TailnetSelectionEntity(1, id))
    }
    override suspend fun selectFree(id: String) = mutate {
        require(dao.all().any { it.id == id && !it.pendingDelete })
        val access = checkNotNull(dao.access())
        dao.saveAccess(TailnetAccessEntity(1, id, access.legacyIds))
        dao.select(TailnetSelectionEntity(1, id))
    }
    override suspend fun stageReplacement(name: String, controlUrl: String): TailnetProfile = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverDeletes()
            val profile = TailnetProfile(UUID.randomUUID().toString(), validatedTailnetName(name), validatedControlServer(controlUrl))
            dao.insert(TailnetProfileEntity(profile.id, profile.name, profile.controlUrl, System.currentTimeMillis(), true))
            profile
        }
    }
    override suspend fun commitReplacement(oldId: String, replacement: TailnetProfile): TailnetProfileSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.runInTransaction {
                check(dao.activeId() == oldId && dao.all().any { it.id == replacement.id && it.pendingDelete })
                val access = checkNotNull(dao.access())
                dao.commitStaged(replacement.id)
                dao.select(TailnetSelectionEntity(1, replacement.id))
                dao.saveAccess(TailnetAccessEntity(1, replacement.id, access.legacyIds))
                dao.markDeleted(oldId)
            }
            // Cleanup is journaled. Its failure must not roll back an already committed selection.
            runCatching { recoverDeletes() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            snapshot()
        }
    }
    override suspend fun discardReplacement() = withContext(Dispatchers.IO) { mutex.withLock { recoverDeletes() } }
    override suspend fun remove(id: String): TailnetProfileSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(id != dao.activeId()) { "Switch to another tailnet before removal" }
            recoverDeletes()
            if (dao.all().none { it.id == id }) return@withLock snapshot()
            require(dao.all().count { !it.pendingDelete } > 1)
            require(dao.all().any { it.id == id && !it.pendingDelete })
            dao.markDeleted(id)
            recoverDeletes()
            snapshot()
        }
    }
    private suspend fun mutate(block: () -> Unit): TailnetProfileSnapshot = withContext(Dispatchers.IO) {
        paywall?.awaitReady()
        mutex.withLock {
            recoverDeletes()
            database.runInTransaction { ensureAccess(); block() }
            snapshot()
        }
    }
    private fun snapshot(): TailnetProfileSnapshot {
        val profiles = dao.all().filterNot { it.pendingDelete }.map { TailnetProfile(it.id, it.name, it.controlUrl) }
        val active = checkNotNull(dao.activeId())
        check(profiles.any { it.id == active })
        val access = dao.access()
        val legacy = access?.legacyIds?.let { org.json.JSONArray(it) }
        val freeIds = buildSet {
            access?.freeProfileId?.let(::add)
            if (legacy != null) for (i in 0 until legacy.length()) add(legacy.getString(i))
        }
        return TailnetProfileSnapshot(profiles, active, freeIds)
    }
    private fun ensureAccess() {
        if (dao.access() == null) dao.saveAccess(TailnetAccessEntity(1, checkNotNull(dao.activeId()),
            org.json.JSONArray(dao.all().filterNot { it.pendingDelete }.map { it.id }).toString()))
    }
    private suspend fun recoverDeletes() {
        for (row in dao.all().filter { it.pendingDelete }) {
            check(row.id != dao.activeId())
            val directory = profileDirectory(context, row.id)
            check(!directory.exists() || directory.deleteRecursively())
            for (base in listOf("encrypted_ssh_profiles", "known_ssh_hosts")) {
                val name = scopedPreferenceName(base, row.id)
                check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                context.deleteSharedPreferences(name)
            }
            cc.galaxnet.novascale.browser.ProxySecretStore(context).removeProfile(row.id)
            removeMetadata(row.id)
            dao.finishDelete(row.id)
        }
    }
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS tailnet_access (singleton INTEGER NOT NULL PRIMARY KEY, freeProfileId TEXT NOT NULL, legacyIds TEXT NOT NULL)")
            }
        }
    }
}

internal fun profileDirectory(context: Context, id: String): File {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    return File(context.noBackupFilesDir, "tailnets/$id")
}
internal fun validatedTailnetName(raw: String): String = raw.trim().also {
    require(it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl))
}
internal fun validatedControlServer(raw: String): String {
    val value = normalizeControlUrl(raw) ?: return DEFAULT_CONTROL_SERVER
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535))
    return value
}
private fun controlServerName(url: String): String =
    if (normalizeControlUrl(url) == null) "Tailscale" else URI(url).host
