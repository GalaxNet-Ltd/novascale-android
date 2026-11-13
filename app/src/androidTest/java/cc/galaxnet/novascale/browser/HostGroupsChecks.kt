// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.content.Context
import androidx.room.Room
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.remote.AuthenticationKind
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostGroupRepository
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.SshProfileStore
import cc.galaxnet.novascale.remote.TraditionalHostDatabase
import cc.galaxnet.novascale.remote.TraditionalHostRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Unique disposable group/host IDs keep the owner's real data intact. */
internal object HostGroupsChecks {
    fun run(test: Instrumentation) = runBlocking {
        val context = test.targetContext
        val hosts = (context.applicationContext as NovaScaleApplication).traditionalHosts
        val groups = hosts.groups
        val suffix = UUID.randomUUID().toString()
        val originalHostIds = hosts.hosts.value.map { it.id }.toSet()
        val config = RemoteConnectionConfig(host = "fixture.invalid", username = "fixture", authenticationKind = AuthenticationKind.NONE)
        val host = hosts.save(null, "Host $suffix", config)
        val direct = GroupHostKey.traditional(host.id)
        val peerA = checkNotNull(GroupHostKey.tailnet("profile-a-$suffix", "node-1"))
        val peerB = checkNotNull(GroupHostKey.tailnet("profile-b-$suffix", "node-1"))
        check(GroupHostKey.tailnet("", "node-1") == null && GroupHostKey.tailnet("profile", "") == null)
        val first = groups.create("First $suffix")
        val second = groups.create("Second $suffix")
        try {
            groups.setGroups(direct, setOf(first.id, second.id))
            groups.setGroups(peerA, setOf(first.id))
            groups.setGroups(peerB, setOf(second.id))
            check(groups.state.value.memberships[direct] == setOf(first.id, second.id))
            check(groups.state.value.memberships[peerA] == setOf(first.id))
            check(groups.state.value.memberships[peerB] == setOf(second.id))
            check(runCatching { groups.create("first $suffix") }.isFailure) { "Duplicate case-insensitive group name accepted" }
            groups.rename(first.id, "Renamed $suffix")
            check(groups.state.value.groups.any { it.id == first.id && it.name == "Renamed $suffix" })
            check(groups.state.value.memberships[direct] == setOf(first.id, second.id)) { "Rename removed membership" }
            check(runCatching { groups.setGroups(direct, setOf("missing")) }.isFailure)
            check(groups.state.value.memberships[direct] == setOf(first.id, second.id)) { "Invalid assignment changed membership" }
            groups.setMembers(first.id, setOf(direct), emptySet())
            check(groups.state.value.memberships[direct] == setOf(second.id)) { "Bulk edit changed another group" }
            check(groups.state.value.memberships[peerA] == setOf(first.id)) { "Bulk edit removed an absent peer" }
            check(groups.state.value.memberships[peerB] == setOf(second.id)) { "Bulk edit crossed profile identities" }
            groups.setMembers(first.id, setOf(direct, peerA), setOf(direct, peerA))
            check(runCatching { groups.setMembers(first.id, setOf(direct), setOf(peerB)) }.isFailure) { "Bulk edit accepted a host outside its scope" }
            val absent = GroupHostKey.traditional("traditional:missing-$suffix")
            check(runCatching { groups.setMembers(first.id, setOf(direct, absent), setOf(absent)) }.isFailure)
            check(groups.state.value.memberships[direct] == setOf(first.id, second.id)) { "Failed bulk edit did not roll back" }
            groups.delete(second.id)
            check(groups.state.value.memberships[direct] == setOf(first.id))
            check(groups.state.value.memberships[peerB] == null) { "Deleting group did not cascade" }
            // Retain another kind of member while deleting the traditional host.
            hosts.delete(host)
            withTimeout(10_000) { groups.state.first { direct !in it.memberships } }
            check(groups.state.value.memberships[peerA] == setOf(first.id)) { "Host deletion affected unrelated peers" }
            check(runCatching { groups.setGroups(direct, setOf(first.id)) }.isFailure) { "Deleted host accepted membership" }
        } finally {
            hosts.delete(host)
            if (groups.state.value.groups.any { it.id == first.id }) groups.delete(first.id)
            if (groups.state.value.groups.any { it.id == second.id }) groups.delete(second.id)
        }
        check(hosts.hosts.value.map { it.id }.toSet().containsAll(originalHostIds))
        migrationPreservesExistingHostAndJournal(context)
    }

    private suspend fun migrationPreservesExistingHostAndJournal(context: Context) = withContext(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString()
        val dbName = "host-groups-migration-$suffix.db"
        val hostId = "traditional:test-$suffix"
        val pending = SshProfileStore(context).encrypt(RemoteConnectionConfig(host = "pending.invalid", username = "fixture"))
        try {
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE traditional_hosts (id TEXT NOT NULL, name TEXT NOT NULL, pendingCredential TEXT, pendingDelete INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO traditional_hosts (id, name, pendingCredential, pendingDelete) VALUES (?, ?, ?, 0)", arrayOf(hostId, "Existing fixture", pending))
                db.version = 2
            }
            val migrated = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, dbName)
                .addMigrations(TraditionalHostRepository.MIGRATION_1_2, TraditionalHostRepository.MIGRATION_2_3, TraditionalHostRepository.MIGRATION_3_4).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val peer = checkNotNull(GroupHostKey.tailnet("profile-$suffix", "stable-node"))
            lateinit var groupId: String
            try {
                val row = migrated.hosts().all().single()
                check(row.id == hostId && row.name == "Existing fixture" && row.pendingCredential == pending && !row.pendingDelete)
                check(migrated.groups().all().isEmpty() && migrated.groups().memberships().isEmpty())
                val groups = HostGroupRepository(migrated, scope)
                val group = groups.create("Persistent group")
                groupId = group.id
                groups.setGroups(peer, setOf(groupId))
            } finally { scope.cancel(); migrated.close() }
            val reopened = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, dbName)
                .addMigrations(TraditionalHostRepository.MIGRATION_1_2, TraditionalHostRepository.MIGRATION_2_3, TraditionalHostRepository.MIGRATION_3_4).build()
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                check(reopened.hosts().all().single().pendingCredential == pending)
                val restored = HostGroupRepository(reopened, reopenedScope)
                val state = withTimeout(10_000) { restored.state.first { it.groups.isNotEmpty() } }
                check(state.groups.single().id == groupId && state.memberships[peer] == setOf(groupId)) {
                    "Group or membership did not survive database reopen"
                }
            } finally { reopenedScope.cancel(); reopened.close() }
        } finally { context.deleteDatabase(dbName) }
    }
}
