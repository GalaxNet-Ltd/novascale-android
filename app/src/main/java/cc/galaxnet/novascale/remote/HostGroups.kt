/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class HostGroup(val id: String, val name: String)

/** Tailnet membership is scoped to the backend profile and stable node ID. */
@ConsistentCopyVisibility
internal data class GroupHostKey private constructor(val kind: String, val scope: String, val hostId: String) {
    companion object {
        fun traditional(id: String): GroupHostKey {
            require(id.isNotBlank())
            return GroupHostKey("traditional", "", id)
        }

        fun tailnet(profileId: String, stableNodeId: String): GroupHostKey? =
            if (profileId.isBlank() || stableNodeId.isBlank()) null
            else GroupHostKey("tailnet", profileId, stableNodeId)

        internal fun fromRow(kind: String, scope: String, hostId: String): GroupHostKey? = when {
            kind == "traditional" && scope.isEmpty() && hostId.isNotBlank() -> traditional(hostId)
            kind == "tailnet" -> tailnet(scope, hostId)
            else -> null
        }
    }
}

internal data class HostGroupsState(
    val groups: List<HostGroup> = emptyList(),
    val memberships: Map<GroupHostKey, Set<String>> = emptyMap(),
    val loaded: Boolean = false,
)

/** Room owns both group names and memberships; all mutations commit atomically. */
internal class HostGroupRepository(
    private val database: TraditionalHostDatabase,
    private val scope: CoroutineScope,
    private val paywall: cc.galaxnet.novascale.billing.PaywallManager? = null,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HostGroupsState())
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val storageError = mutableError.asStateFlow()

    init { refreshSoon() }

    internal fun refreshSoon() {
        scope.launch(Dispatchers.IO) {
            runCatching { mutex.withLock { refresh() } }
        }
    }

    private fun refresh() {
        try {
            val groups = database.groups().all().map { HostGroup(it.id, it.name) }
            val memberships = database.groups().memberships().mapNotNull { row ->
                GroupHostKey.fromRow(row.kind, row.scope, row.hostId)?.let { it to row.groupId }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            mutableState.value = HostGroupsState(groups, memberships, loaded = true)
            mutableError.value = false
        } catch (error: Exception) {
            mutableError.value = true
            throw error
        }
    }

    private suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) {
        paywall?.awaitReady()
        mutex.withLock {
            val value = database.runInTransaction<T> { block() }
            refresh()
            value
        }
    }

    suspend fun create(name: String): HostGroup = mutate {
        val group = HostGroup(UUID.randomUUID().toString(), validatedName(name))
        paywall?.requireAccess(cc.galaxnet.novascale.billing.ProFeature.HOST_GROUPS, database.groups().all().size)
        database.groups().insert(HostGroupEntity(group.id, group.name))
        group
    }

    suspend fun rename(groupId: String, name: String) = mutate {
        require(database.groups().rename(groupId, validatedName(name)) == 1) { "Group no longer exists" }
    }

    suspend fun delete(groupId: String) = mutate {
        require(database.groups().delete(groupId) == 1) { "Group no longer exists" }
    }

    suspend fun setMembership(key: GroupHostKey, groupId: String, included: Boolean) = mutate {
        val dao = database.groups()
        val groupIds = dao.all().map { it.id }.toSet()
        require(groupId in groupIds) { "Group no longer exists" }
        if (included) {
            requireHostExists(key)
            dao.insertMembership(HostGroupMembershipEntity(groupId, key.kind, key.scope, key.hostId))
        } else {
            dao.removeMembership(groupId, key.kind, key.scope, key.hostId)
        }
    }

    suspend fun setGroups(key: GroupHostKey, groupIds: Set<String>) = mutate {
        val dao = database.groups()
        val existing = dao.all().map { it.id }.toSet()
        require(groupIds.all { it in existing }) { "Group no longer exists" }
        if (groupIds.isNotEmpty()) requireHostExists(key)
        dao.removeHost(key.kind, key.scope, key.hostId)
        groupIds.forEach { dao.insertMembership(HostGroupMembershipEntity(it, key.kind, key.scope, key.hostId)) }
    }

    /** Edit only the hosts offered by this picker; absent peers and other profiles survive. */
    suspend fun setMembers(groupId: String, offered: Set<GroupHostKey>, selected: Set<GroupHostKey>) = mutate {
        require(selected.all { it in offered })
        val dao = database.groups()
        require(dao.all().any { it.id == groupId }) { "Group no longer exists" }
        selected.forEach(::requireHostExists)
        offered.forEach { key ->
            if (key in selected) dao.insertMembership(HostGroupMembershipEntity(groupId, key.kind, key.scope, key.hostId))
            else dao.removeMembership(groupId, key.kind, key.scope, key.hostId)
        }
    }

    private fun requireHostExists(key: GroupHostKey) {
        if (key.kind == "traditional") require(database.hosts().exists(key.hostId) == 1) { "Host no longer exists" }
    }

    private fun validatedName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none { char -> Character.isISOControl(char) }) { "Invalid group name" }
    }

    companion object { const val MAX_NAME_LENGTH = 80 }
}
