/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGroupFilterTest {
    @Test
    fun filtersApplyToBothHostTypesAndSupportMultipleMemberships() {
        val memberships = setOf("work", "production")
        for (tailnet in listOf(true, false)) {
            assertTrue(hostMatchesGroupFilter(GROUP_FILTER_ALL, tailnet, memberships))
            assertTrue(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "work", tailnet, memberships))
            assertTrue(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "production", tailnet, memberships))
            assertFalse(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "personal", tailnet, memberships))
            assertFalse(hostMatchesGroupFilter(GROUP_FILTER_UNGROUPED, tailnet, memberships))
            assertTrue(hostMatchesGroupFilter(GROUP_FILTER_UNGROUPED, tailnet, emptySet()))
            assertFalse(hostMatchesGroupFilter("unknown", tailnet, memberships))
        }
        assertTrue(hostMatchesGroupFilter(GROUP_FILTER_TAILNET, true, memberships))
        assertFalse(hostMatchesGroupFilter(GROUP_FILTER_TAILNET, false, memberships))
        assertTrue(hostMatchesGroupFilter(GROUP_FILTER_TRADITIONAL, false, memberships))
        assertFalse(hostMatchesGroupFilter(GROUP_FILTER_TRADITIONAL, true, memberships))
    }

    @Test
    fun peerIdentityRequiresStableNodeAndProfileAndNeverMatchesAnotherProfile() {
        assertTrue(GroupHostKey.tailnet("", "node") == null)
        assertTrue(GroupHostKey.tailnet("control", "") == null)
        val first = GroupHostKey.tailnet("official", "node")!!
        val second = GroupHostKey.tailnet("custom", "node")!!
        val traditional = GroupHostKey.traditional("node")
        val memberships = mapOf(first to setOf("work"))
        assertTrue(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "work", true, memberships[first].orEmpty()))
        assertFalse(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "work", true, memberships[second].orEmpty()))
        assertFalse(hostMatchesGroupFilter(GROUP_FILTER_PREFIX + "work", false, memberships[traditional].orEmpty()))
    }
}
