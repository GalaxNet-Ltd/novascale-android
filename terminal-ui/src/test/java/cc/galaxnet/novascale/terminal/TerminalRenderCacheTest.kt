// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalRenderCacheTest {
    @Test fun evictionHonorsMemoryAndRecentUseAndReleasesResources() {
        val released = mutableListOf<String>()
        val cache = TerminalRenderCache<Int, String>(3, 10, String::length, released::add)
        cache.put(1, "aaaa"); cache.put(2, "bbbb")
        assertEquals("aaaa", cache[1])
        cache.put(3, "cccc")
        assertNull(cache[2]); assertEquals(listOf("bbbb"), released)
        assertFalse(cache.put(4, "too big to cache"))
        assertEquals(8, cache.weight)
        cache.clear()
        assertEquals(0, cache.weight)
        assertEquals(setOf("aaaa", "bbbb", "cccc"), released.toSet())
    }
    @Test fun replacementAndCountLimitDoNotRetainOldValues() {
        val released = mutableListOf<String>()
        val cache = TerminalRenderCache<Int, String>(2, 100, String::length, released::add)
        cache.put(1, "old"); cache.put(1, "new")
        cache.put(2, "second"); cache.put(3, "third")
        assertEquals(listOf("old", "new"), released)
        assertEquals(11, cache.weight)
        assertEquals(2, cache.size)
    }
}
