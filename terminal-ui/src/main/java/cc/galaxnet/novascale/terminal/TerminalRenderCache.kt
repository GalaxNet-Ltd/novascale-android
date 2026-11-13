// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

/** Per-surface cache: terminal content is never shared between sessions or written to disk. */
internal class TerminalRenderCache<K, V>(private val maxEntries: Int, private val maxWeight: Int,
    private val weightOf: (V) -> Int, private val dispose: (V) -> Unit = {}) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    var weight = 0
        private set
    val size get() = entries.size
    operator fun get(key: K): V? = entries[key]
    fun put(key: K, value: V): Boolean {
        remove(key)
        val weight = weightOf(value).coerceAtLeast(1)
        if (weight > maxWeight) return false
        while (entries.isNotEmpty() && (entries.size >= maxEntries || this.weight + weight > maxWeight)) {
            remove(entries.keys.first())
        }
        entries[key] = value
        this.weight += weight
        return true
    }
    fun remove(key: K) {
        entries.remove(key)?.let { weight -= weightOf(it).coerceAtLeast(1); dispose(it) }
    }
    fun clear() { entries.values.forEach(dispose); entries.clear(); weight = 0 }
}

/** Non-content counters for instrumentation and profiling. */
data class TerminalRenderStats(val rowBuilds: Long, val rowHits: Long, val textMeasurements: Long,
    val displayListRecords: Long, val displayListHits: Long, val cachedRows: Int,
    val rowCacheBytes: Int, val measuredTextEntries: Int, val measuredTextBytes: Int)
