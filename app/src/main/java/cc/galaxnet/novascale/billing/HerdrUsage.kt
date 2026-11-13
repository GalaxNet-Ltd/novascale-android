// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class HerdrVisit(val id: String, val identity: String, val charged: Boolean)
internal data class HerdrUsageRecord(val day: Long, val used: Int = 0, val partialMs: Long = 0,
    val visits: Map<String, HerdrVisit> = emptyMap())
internal interface HerdrUsageStore {
    suspend fun read(): HerdrUsageRecord?
    suspend fun write(value: HerdrUsageRecord)
}
internal data class HerdrUsageState(val used: Int = 0, val reserved: Int = 0, val day: Long = 0, val loaded: Boolean = false,
    val storageError: Boolean = false, val partialMs: Long = 0, val retainedVisits: Int = 0, val activeVisits: Int = 0) {
    val remaining get() = (ProFeature.HERDR_VISITS.freeAllowance - used - reserved).coerceAtLeast(0)
    // Includes visits already counted and those admitted with Pro, even after downgrade.
    val coveredVisits get() = retainedVisits - reserved
}

/** Application-owned admissions. Only a visible, resumed and connected attachment accrues time. */
internal class HerdrUsageManager(
    private val store: HerdrUsageStore,
    private val paywall: PaywallManager,
    scope: CoroutineScope,
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Live(val active: Boolean, val since: Long)
    private val mutex = Mutex()
    private var record: HerdrUsageRecord? = null
    private val live = mutableMapOf<String, Live>()
    private val mutableState = MutableStateFlow(HerdrUsageState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                try { tick() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.value = mutableState.value.copy(storageError = true) }
                delay(1_000)
            }
        }
    }

    private suspend fun <T> update(block: (HerdrUsageRecord) -> Pair<HerdrUsageRecord, T>): T {
        paywall.awaitReady()
        return mutex.withLock {
            val previousLive = live.toMap()
            try {
                val previous = record ?: store.read() ?: HerdrUsageRecord(today())
                // A rollback or westward timezone change never resets the same day twice.
                val day = maxOf(previous.day, today())
                if (day > previous.day) live.replaceAll { _, timing -> timing.copy(since = monotonicMs()) }
                val current = if (day > previous.day) previous.copy(day = day, used = 0, partialMs = 0) else previous
                val (next, value) = block(current)
                if (record == null || next != previous) store.write(next)
                record = next
                mutableState.value = HerdrUsageState(used = next.used,
                    reserved = live.keys.count { next.visits[it]?.charged == false }, day = next.day, loaded = true,
                    partialMs = next.partialMs, retainedVisits = live.size, activeVisits = live.values.count { it.active })
                value
            } catch (failure: Exception) {
                live.clear(); live.putAll(previousLive)
                if (failure !is CancellationException && failure !is ProLimitReached) {
                    mutableState.value = mutableState.value.copy(storageError = true)
                }
                throw failure
            }
        }
    }

    suspend fun reserve(identity: String): String = update { current ->
        val existing = current.visits.values.firstOrNull { it.identity == identity }
        if (existing?.id in live) return@update current to checkNotNull(existing).id
        val exempt = paywall.state.value.hasPro || existing?.charged == true
        val reserved = live.keys.count { current.visits[it]?.charged == false }
        if (!exempt) paywall.requireAccess(ProFeature.HERDR_VISITS, current.used + reserved)
        val visit = existing?.copy(charged = exempt) ?: HerdrVisit(UUID.randomUUID().toString(), identity, exempt)
        live[visit.id] = Live(false, monotonicMs())
        current.copy(visits = current.visits + (visit.id to visit)) to visit.id
    }

    suspend fun setActive(id: String, active: Boolean) = update { current ->
        val next = accrue(current)
        if (id in live) live[id] = Live(active, monotonicMs())
        next to Unit
    }

    /** Explicit local close (or failed first attach) ends recovery; remote processes are untouched. */
    suspend fun end(id: String) = update { current ->
        val next = accrue(current)
        live.remove(id)
        next.copy(visits = next.visits - id) to Unit
    }

    suspend fun tick() = update { accrue(it) to Unit }

    private fun accrue(current: HerdrUsageRecord): HerdrUsageRecord {
        var next = current
        val now = monotonicMs()
        for ((id, timing) in live.toMap()) {
            val visit = next.visits[id] ?: continue
            live[id] = timing.copy(since = now)
            if (visit.charged) continue
            if (paywall.state.value.hasPro) {
                next = next.copy(visits = next.visits + (id to visit.copy(charged = true)))
            } else if (timing.active) {
                val elapsed = (now - timing.since).coerceAtLeast(0)
                val total = next.partialMs + elapsed
                if (total >= MEANINGFUL_MS) {
                    next = next.copy(used = next.used + 1, partialMs = 0,
                        visits = next.visits + (id to visit.copy(charged = true)))
                } else next = next.copy(partialMs = total)
            }
        }
        return next
    }

    companion object { const val MEANINGFUL_MS = 30_000L }
}
