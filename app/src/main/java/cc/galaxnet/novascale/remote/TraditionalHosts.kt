/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Display-only projection. Credentials remain exclusively in the encrypted profile. */
internal data class TraditionalHost(
    val id: String,
    val name: String,
    val address: String = "",
    val port: String = "22",
    val transport: SshTransport = SshTransport.DIRECT,
) {
    val endpoint: String get() = if (address.isBlank()) "" else
        (if (address.contains(':')) "[$address]" else address) + ":$port"
}

/** Structured identity in Room; connection and credential data in Keystore-encrypted profiles. */
internal class TraditionalHostRepository(private val context: Context, scope: CoroutineScope,
    private val paywall: cc.galaxnet.novascale.billing.PaywallManager? = null,
    private val activeProfileId: () -> String? = { null },
) {
    internal val database = Room.databaseBuilder(context.applicationContext, TraditionalHostDatabase::class.java, "traditional-hosts.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    internal val groups = HostGroupRepository(database, scope, paywall)
    internal val pins = HostPinRepository(database, scope)
    private val profiles = SshProfileStore(context.applicationContext)
    private val mutex = Mutex()
    private val mutableHosts = MutableStateFlow<List<TraditionalHost>>(emptyList())
    val hosts = mutableHosts.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val storageError = mutableError.asStateFlow()

    init { scope.launch(Dispatchers.IO) { runCatching { mutex.withLock { recover() } } } }

    /** Replay is idempotent; durable preference writes always precede clearing the journal. */
    private fun recover() {
        try {
            for (host in database.hosts().all()) {
                if (host.pendingDelete) {
                    profiles.removeDurably(host.id)
                    database.runInTransaction {
                        database.groups().removeHost("traditional", "", host.id)
                        database.pins().delete("traditional", "", host.id)
                        database.hosts().delete(host.id)
                    }
                    groups.refreshSoon()
                    pins.refreshSoon()
                } else if (host.pendingCredential != null) {
                    profiles.writeEncryptedDurably(host.id, host.pendingCredential)
                    database.hosts().clearPending(host.id)
                }
            }
            mutableHosts.value = database.hosts().all().filterNot { it.pendingDelete }.map { row ->
                // An unreadable profile must not hide a host or prevent its repair/deletion.
                val config = runCatching { profiles.load(row.id) }.getOrNull()
                TraditionalHost(row.id, row.name, config?.host.orEmpty(), config?.port ?: "22",
                    config?.transport ?: SshTransport.DIRECT)
            }
            mutableError.value = false
        } catch (error: Exception) {
            mutableError.value = true
            throw error
        }
    }

    suspend fun migrateLegacyTailnet(profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            recover()
            profiles.migrateLegacyTailnet(context, profileId, database.hosts().all().map { it.id }.toSet())
        }
    }

    suspend fun removeTailnetMetadata(profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.runInTransaction {
                database.groups().removeScope(profileId)
                database.pins().removeScope(profileId)
            }
            groups.refreshSoon()
            pins.refreshSoon()
        }
    }

    suspend fun load(host: TraditionalHost): RemoteConnectionConfig? = withContext(Dispatchers.IO) {
        mutex.withLock {
            recover()
            if (database.hosts().all().none { it.id == host.id }) null else profiles.load(host.id)
        }
    }

    suspend fun save(existing: TraditionalHost?, name: String, config: RemoteConnectionConfig): TraditionalHost = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 128 && config.validate() == null)
        mutex.withLock {
            recover()
            val host = TraditionalHost(existing?.id ?: "traditional:" + UUID.randomUUID(), name.trim(),
                config.host, config.port, config.transport)
            require(existing == null || database.hosts().all().any { it.id == existing.id }) { "Host no longer exists" }
            // The DB transaction is one row write containing metadata and the
            // encrypted intent. A restart can safely finish either operation.
            val scoped = if (config.transport == SshTransport.TAILNET) config.copy(
                tailnetProfileId = config.tailnetProfileId ?: activeProfileId(),
            ) else config.copy(tailnetProfileId = null)
            val encrypted = profiles.encrypt(scoped)
            database.hosts().save(TraditionalHostEntity(host.id, host.name, encrypted, false))
            recover()
            host
        }
    }

    suspend fun delete(host: TraditionalHost) = withContext(Dispatchers.IO) {
        mutex.withLock {
            recover()
            database.hosts().markDeleted(host.id)
            recover()
        }
    }

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE traditional_hosts ADD COLUMN pendingCredential TEXT")
                database.execSQL("ALTER TABLE traditional_hosts ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")
            }
        }
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `host_groups` (`id` TEXT NOT NULL, `name` TEXT COLLATE NOCASE NOT NULL, PRIMARY KEY(`id`))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_host_groups_name` ON `host_groups` (`name`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `host_group_memberships` (`groupId` TEXT NOT NULL, `kind` TEXT NOT NULL, `scope` TEXT NOT NULL, `hostId` TEXT NOT NULL, PRIMARY KEY(`groupId`, `kind`, `scope`, `hostId`), FOREIGN KEY(`groupId`) REFERENCES `host_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_host_group_memberships_kind_scope_hostId` ON `host_group_memberships` (`kind`, `scope`, `hostId`)")
            }
        }
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `pinned_hosts` (`kind` TEXT NOT NULL, `scope` TEXT NOT NULL, `hostId` TEXT NOT NULL, PRIMARY KEY(`kind`, `scope`, `hostId`))")
            }
        }
    }
}
