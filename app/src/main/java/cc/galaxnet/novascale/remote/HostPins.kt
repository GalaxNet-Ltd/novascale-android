/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PinnedHost(val key: GroupHostKey)
internal data class HostPinsState(val pins: List<PinnedHost> = emptyList(), val loaded: Boolean = false)

/** Durable device-local pins, including currently unavailable tailnet peers. */
internal class HostPinRepository(
    private val database: TraditionalHostDatabase,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HostPinsState())
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val storageError = mutableError.asStateFlow()

    init { refreshSoon() }

    internal fun refreshSoon() {
        scope.launch(Dispatchers.IO) { runCatching { mutex.withLock { refresh() } } }
    }

    private fun refresh() {
        try {
            val pins = database.pins().all().mapNotNull { row ->
                GroupHostKey.fromRow(row.kind, row.scope, row.hostId)?.let { PinnedHost(it) }
            }
            mutableState.value = HostPinsState(pins, loaded = true)
            mutableError.value = false
        } catch (error: Exception) {
            mutableError.value = true
            throw error
        }
    }

    private suspend fun mutate(block: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.runInTransaction { block() }
            refresh()
        }
    }

    suspend fun setPinned(key: GroupHostKey, pinned: Boolean) = mutate {
        val dao = database.pins()
        if (!pinned) {
            dao.delete(key.kind, key.scope, key.hostId)
        } else {
            if (key.kind == "traditional") require(database.hosts().exists(key.hostId) == 1) { "Host no longer exists" }
            dao.insert(PinnedHostEntity(key.kind, key.scope, key.hostId))
        }
    }
}
