// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.tailnet.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Isolated metadata, encrypted settings and node directories; never uses the owner's login. */
internal object TailnetProfilesChecks {
    fun run(test: Instrumentation) = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val context = FixtureContext(test.targetContext, suffix)
        val dbName = "tailnets-fixture-$suffix.db"
        val hostDbName = "tailnets-hosts-fixture-$suffix.db"
        val db = Room.databaseBuilder(context, TailnetProfileDatabase::class.java, dbName).build()
        val hostDb = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, hostDbName).build()
        val legacyUrl = "https://headscale.fixture.invalid/Base"
        val legacyId = tailnetConfiguration(context, legacyUrl, "fixture").profileId
        val directId = "traditional:fixture"
        val peerId = "overlapping-peer"
        val old = RemoteConnectionConfig(host = "100.64.0.9", username = "original", password = "fixture-only",
            authenticationKind = AuthenticationKind.PASSWORD)
        val direct = RemoteConnectionConfig(host = "direct.invalid", username = "direct", transport = SshTransport.DIRECT)
        val legacySettings = SshProfileStore(context)
        val trust = KnownHostStore(context)
        var migrated = 0
        fun repository(database: TailnetProfileDatabase) = TailnetProfileRepository(context,
            migrateSecrets = { id ->
                check(id == legacyId)
                legacySettings.migrateLegacyTailnet(context, id, setOf(directId))
                migrated++
            }, removeMetadata = { id ->
                hostDb.runInTransaction { hostDb.groups().removeScope(id); hostDb.pins().removeScope(id) }
            }, database = database, legacyControlUrl = { legacyUrl })
        try {
            withContext(Dispatchers.IO) {
                profileDirectory(context, legacyId).mkdirs()
                File(profileDirectory(context, legacyId), "identity-marker").writeText("persisted-fixture-identity")
                legacySettings.save(peerId, old)
                legacySettings.save(directId, direct)
                trust.trust(old, "SHA256:legacy-fixture")
                trust.trust(direct, "SHA256:direct-fixture")
            }
            val repo = repository(db)
            var snapshot = repo.load()
            check(snapshot.activeId == legacyId && snapshot.profiles.size == 1)
            check(migrated == 1)
            val original = snapshot.active
            withContext(Dispatchers.IO) {
                val saved = checkNotNull(SshProfileStore(context, legacyId).load(peerId))
                check(saved.password == "fixture-only" && saved.tailnetProfileId == legacyId)
                check(trust.fingerprint(saved) == "SHA256:legacy-fixture")
                check(legacySettings.load(peerId) == null)
                check(legacySettings.load(directId)?.username == "direct")
                check(trust.fingerprint(direct) == "SHA256:direct-fixture")
            }
            snapshot = repo.add("Work", DEFAULT_CONTROL_SERVER)
            val work = snapshot.profiles.last()
            snapshot = repo.add("Personal", DEFAULT_CONTROL_SERVER)
            val personal = snapshot.profiles.last()
            check(work.id != personal.id && work.controlUrl == personal.controlUrl)
            check(work.configuration(context, "fixture").stateDirectory != personal.configuration(context, "fixture").stateDirectory)
            snapshot = repo.select(work.id)
            check(snapshot.activeId == work.id)
            withContext(Dispatchers.IO) {
                check(!SshProfileStore(context, work.id).contains(peerId))
                check(trust.fingerprint(old.copy(tailnetProfileId = work.id)) == null)
                SshProfileStore(context, work.id).save(peerId, old.copy(username = "work"))
                trust.trust(old.copy(tailnetProfileId = work.id), "SHA256:work-fixture")
                check(SshProfileStore(context, personal.id).load(peerId) == null)
                check(SshProfileStore(context, legacyId).load(peerId)?.username == "original")
                val secrets = ProxySecretStore(context)
                check(secrets.load("${work.id}:node") != secrets.load("${personal.id}:node"))
                hostDb.groups().insert(HostGroupEntity("group-fixture", "Fixture group"))
                for (id in listOf(work.id, personal.id, legacyId)) {
                    hostDb.groups().insertMembership(HostGroupMembershipEntity("group-fixture", "tailnet", id, peerId))
                    hostDb.pins().insert(PinnedHostEntity("tailnet", id, peerId))
                }
            }
            check(runCatching { repo.remove(work.id) }.isFailure)
            repo.rename(work.id, "Work renamed")
            withContext(Dispatchers.IO) { db.close() }
            val reopened = Room.databaseBuilder(context, TailnetProfileDatabase::class.java, dbName).build()
            try {
                val restoredRepo = repository(reopened)
                snapshot = restoredRepo.load()
                check(snapshot.activeId == work.id && snapshot.active.name == "Work renamed")
                check(migrated == 1) { "Migration replayed after committed initialization" }
                restoredRepo.select(original.id)
                restoredRepo.remove(work.id)
                withContext(Dispatchers.IO) {
                    check(!profileDirectory(context, work.id).exists())
                    check(SshProfileStore(context, work.id).load(peerId) == null)
                    check(trust.fingerprint(old.copy(tailnetProfileId = work.id)) == null)
                    check(hostDb.groups().memberships().map { it.scope }.toSet() == setOf(personal.id, legacyId))
                    check(hostDb.pins().all().map { it.scope }.toSet() == setOf(personal.id, legacyId))
                    check(File(profileDirectory(context, legacyId), "identity-marker").readText() == "persisted-fixture-identity")
                    // Crash after marking an inactive deletion: reopen must complete it.
                    reopened.profiles().markDeleted(personal.id)
                }
                snapshot = restoredRepo.load()
                check(snapshot.profiles.single().id == legacyId)
                check(runCatching { restoredRepo.remove(legacyId) }.isFailure)
                withContext(Dispatchers.IO) {
                    check(SshProfileStore(context, legacyId).load(peerId)?.password == "fixture-only")
                    check(hostDb.pins().all().single().scope == legacyId)
                }
            } finally { withContext(Dispatchers.IO) { reopened.close() } }
            realNodeLifecycle(context)
        } finally {
            withContext(Dispatchers.IO) {
                db.close(); hostDb.close()
                context.deleteDatabase(dbName); context.deleteDatabase(hostDbName)
                context.clearFixture()
            }
        }
    }
    private suspend fun realNodeLifecycle(context: Context) {
        val backend = GoTailnetBackend(context)
        val first = TailnetProfile("native-${UUID.randomUUID()}", "Native first", "https://127.0.0.1:9")
        val second = TailnetProfile("native-${UUID.randomUUID()}", "Native second", "https://127.0.0.1:9")
        try {
            backend.start(first.configuration(context, "novascale-fixture"))
            check(backend.currentProfileId == first.id && backend.currentPeers.isEmpty())
            withContext(Dispatchers.IO) { backend.close() }
            backend.start(second.configuration(context, "novascale-fixture"))
            check(backend.currentProfileId == second.id && backend.currentPeers.isEmpty())
            val stale = runCatching { backend.dial(cc.galaxnet.novascale.core.TailnetDestination("100.64.0.9", 22, first.id)) }
            check(stale.isFailure) { "A stale profile was allowed to dial through the new node" }
            withContext(Dispatchers.IO) { backend.close() }
            backend.start(first.configuration(context, "novascale-fixture"))
            check(backend.currentProfileId == first.id && backend.currentPeers.isEmpty())
        } finally { withContext(Dispatchers.IO) { backend.close() } }
    }

}

private class FixtureContext(base: Context, private val suffix: String) : ContextWrapper(base) {
    private val names = mutableSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir() = File(super.getNoBackupFilesDir(), "tailnet-fixture-$suffix").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        names += name
        return super.getSharedPreferences("fixture-$suffix-$name", mode)
    }
    override fun deleteSharedPreferences(name: String) = super.deleteSharedPreferences("fixture-$suffix-$name")
    fun clearFixture() {
        names.forEach { deleteSharedPreferences(it) }
        noBackupFilesDir.deleteRecursively()
    }
}
