// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.content.Context
import androidx.room.Room
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.remote.AuthenticationKind
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshProfileStore
import cc.galaxnet.novascale.remote.SshTransport
import cc.galaxnet.novascale.remote.TraditionalHostDatabase
import cc.galaxnet.novascale.remote.TraditionalHostEntity
import cc.galaxnet.novascale.remote.TraditionalHostRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses a uniquely named disposable host; preserves all existing user records. */
internal object TraditionalHostChecks {
    fun run(test: Instrumentation) = runBlocking {
        val context = test.targetContext
        val repository = (context.applicationContext as NovaScaleApplication).traditionalHosts
        val password = "fixture-" + UUID.randomUUID()
        val config = RemoteConnectionConfig(host = "fixture.invalid", port = "2222", username = "fixture",
            authenticationKind = AuthenticationKind.PASSWORD, password = password, transport = SshTransport.DIRECT)
        val original = repository.hosts.value.map { it.id }.toSet()
        val host = repository.save(null, "Test " + UUID.randomUUID(), config)
        try {
            check(host.id !in original && repository.hosts.value.any { it == host })
            check(repository.load(host) == config) { "Direct host settings did not round-trip" }
            val profiles = context.getSharedPreferences("encrypted_ssh_profiles", Context.MODE_PRIVATE)
            check(profiles.all.values.none { it.toString().contains(password) }) { "Password was stored in plaintext" }
            val updatedConfig = config.copy(host = "other.invalid", transport = SshTransport.TAILNET)
            val updated = repository.save(host, "Updated fixture", updatedConfig)
            check(updated.id == host.id && updated.name == "Updated fixture")
            check(SshProfileStore(context).load(host.id) == updatedConfig) { "Edited transport was not persisted" }
            // Simulate process death after the Room commit but before the
            // SharedPreferences commit. The next read must finish the intent.
            val pendingConfig = updatedConfig.copy(host = "pending.invalid", password = "pending-" + UUID.randomUUID())
            val profileStore = SshProfileStore(context)
            withContext(Dispatchers.IO) {
                repository.database.hosts().save(TraditionalHostEntity(host.id, "Pending fixture", profileStore.encrypt(pendingConfig), false))
            }
            check(repository.load(host) == pendingConfig) { "Interrupted save was not replayed" }
            withContext(Dispatchers.IO) {
                check(repository.database.hosts().all().single { it.id == host.id }.pendingCredential == null)
            }
            val newest = pendingConfig.copy(host = "newest.invalid")
            repository.save(host, "Newest fixture", newest)
            check(repository.load(host) == newest) { "An older journal entry overwrote a later edit" }
            withContext(Dispatchers.IO) { repository.database.hosts().markDeleted(host.id) }
            check(repository.load(host) == null) { "Interrupted delete was not replayed" }
            check(profileStore.load(host.id) == null) { "Interrupted delete retained credentials" }
        } finally {
            repository.delete(host)
        }
        check(repository.hosts.value.none { it.id == host.id })
        check(SshProfileStore(context).load(host.id) == null) { "Deleted credentials remain stored" }
        check(repository.hosts.value.map { it.id }.toSet().containsAll(original)) { "Unrelated hosts changed" }
        migrationPreservesExistingHost(context, config)
    }

    private suspend fun migrationPreservesExistingHost(context: Context, config: RemoteConnectionConfig) = withContext(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString()
        val dbName = "traditional-migration-test-$suffix.db"
        val hostId = "traditional:test-$suffix"
        val profileStore = SshProfileStore(context)
        try {
            profileStore.save(hostId, config)
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS traditional_hosts (id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO traditional_hosts(id, name) VALUES (?, ?)", arrayOf(hostId, "Existing fixture"))
                db.version = 1
            }
            val migrated = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, dbName)
                .addMigrations(TraditionalHostRepository.MIGRATION_1_2, TraditionalHostRepository.MIGRATION_2_3, TraditionalHostRepository.MIGRATION_3_4).build()
            try {
                val row = migrated.hosts().all().single()
                check(row.id == hostId && row.name == "Existing fixture" && row.pendingCredential == null && !row.pendingDelete)
                check(profileStore.load(hostId) == config) { "Migration lost the existing encrypted SSH profile" }
            } finally { migrated.close() }
        } finally {
            profileStore.removeDurably(hostId)
            context.deleteDatabase(dbName)
        }
    }
}
