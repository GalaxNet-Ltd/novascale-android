// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.content.Context
import androidx.room.Room
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostPinRepository
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshProfileStore
import cc.galaxnet.novascale.remote.TraditionalHostDatabase
import cc.galaxnet.novascale.remote.TraditionalHostRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** All test records are uniquely named and removed without touching owner data. */
internal object HostPinsChecks {
    fun run(test: Instrumentation) = runBlocking {
        val context = test.targetContext
        val hosts = (context.applicationContext as NovaScaleApplication).traditionalHosts
        val pins = hosts.pins
        val suffix = UUID.randomUUID().toString()
        val host = hosts.save(null, "Pin fixture $suffix", RemoteConnectionConfig(host = "fixture.invalid", username = "fixture"))
        val secondHost = hosts.save(null, "Other pin fixture $suffix", RemoteConnectionConfig(host = "other.invalid", username = "fixture"))
        val traditional = GroupHostKey.traditional(host.id)
        val secondTraditional = GroupHostKey.traditional(secondHost.id)
        // These synthetic peers are deliberately absent from any discovery list.
        val peerA = checkNotNull(GroupHostKey.tailnet("profile-a-$suffix", "node-1"))
        val peerB = checkNotNull(GroupHostKey.tailnet("profile-b-$suffix", "node-1"))
        try {
            pins.setPinned(peerA, true)
            pins.setPinned(traditional, true)
            pins.setPinned(peerB, true)
            pins.setPinned(secondTraditional, true)
            pins.setPinned(peerA, true)
            fun testPins() = pins.state.value.pins.map { it.key }.filter { it in setOf(peerA, peerB, traditional) }
            check(testPins().toSet() == setOf(peerA, traditional, peerB) && testPins().size == 3) { "Pin membership or idempotency failed" }
            pins.setPinned(peerA, false)
            check(testPins().toSet() == setOf(peerB, traditional)) { "Unpin altered a different profile's peer" }
            // Simulate a process stop after the deletion journal was written.
            withContext(Dispatchers.IO) { hosts.database.hosts().markDeleted(host.id) }
            check(hosts.load(host) == null)
            withTimeout(10_000) { pins.state.first { state -> state.pins.none { it.key == traditional } } }
            check(testPins().toSet() == setOf(peerB)) { "Host deletion changed unrelated pins" }
            check(runCatching { pins.setPinned(traditional, true) }.isFailure) { "Deleted traditional host could be pinned" }
            hosts.delete(secondHost)
            withTimeout(10_000) { pins.state.first { state -> state.pins.none { it.key == secondTraditional } } }
        } finally {
            pins.setPinned(peerA, false)
            pins.setPinned(peerB, false)
            hosts.delete(host)
            hosts.delete(secondHost)
        }
        migrationAndReopen(context)
    }

    private suspend fun migrationAndReopen(context: Context) = withContext(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString()
        val dbName = "host-pins-migration-$suffix.db"
        val hostId = "traditional:test-$suffix"
        val profile = "profile-$suffix"
        val peer = checkNotNull(GroupHostKey.tailnet(profile, "unavailable-node"))
        val secondPeer = checkNotNull(GroupHostKey.tailnet("other-$suffix", "unavailable-node"))
        val pending = SshProfileStore(context).encrypt(RemoteConnectionConfig(host = "pending.invalid", username = "fixture"))
        try {
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE traditional_hosts (id TEXT NOT NULL, name TEXT NOT NULL, pendingCredential TEXT, pendingDelete INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE host_groups (id TEXT NOT NULL, name TEXT COLLATE NOCASE NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX index_host_groups_name ON host_groups (name)")
                db.execSQL("CREATE TABLE host_group_memberships (groupId TEXT NOT NULL, kind TEXT NOT NULL, scope TEXT NOT NULL, hostId TEXT NOT NULL, PRIMARY KEY(groupId,kind,scope,hostId), FOREIGN KEY(groupId) REFERENCES host_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_host_group_memberships_kind_scope_hostId ON host_group_memberships (kind,scope,hostId)")
                db.execSQL("INSERT INTO traditional_hosts VALUES (?, ?, ?, 0)", arrayOf(hostId, "Existing fixture", pending))
                db.execSQL("INSERT INTO host_groups VALUES ('group-a', 'Existing group')")
                db.execSQL("INSERT INTO host_group_memberships VALUES ('group-a', 'tailnet', ?, 'unavailable-node')", arrayOf(profile))
                db.version = 3
            }
            val migrated = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, dbName)
                .addMigrations(TraditionalHostRepository.MIGRATION_3_4).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                check(migrated.hosts().all().single().pendingCredential == pending)
                check(migrated.groups().all().single().name == "Existing group")
                check(migrated.groups().memberships().single().hostId == "unavailable-node")
                check(migrated.pins().all().isEmpty())
                val pins = HostPinRepository(migrated, scope)
                pins.setPinned(peer, true)
                pins.setPinned(secondPeer, true)
            } finally { scope.cancel(); migrated.close() }
            val reopened = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, dbName)
                .addMigrations(TraditionalHostRepository.MIGRATION_3_4).build()
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val restored = HostPinRepository(reopened, reopenedScope)
                val state = withTimeout(10_000) { restored.state.first { it.loaded } }
                check(state.pins.map { it.key }.toSet() == setOf(secondPeer, peer)) { "Scoped pins did not survive reopen" }
                check(reopened.groups().memberships().single().scope == profile)
                check(reopened.hosts().all().single().pendingCredential == pending)
            } finally { reopenedScope.cancel(); reopened.close() }
        } finally { context.deleteDatabase(dbName) }
    }
}
