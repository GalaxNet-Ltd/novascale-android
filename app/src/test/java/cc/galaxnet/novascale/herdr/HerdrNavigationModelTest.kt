/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import org.junit.Assert.*
import org.junit.Test

class HerdrNavigationModelTest {
    private val navigation = HerdrNavigation(true, "boot", setOf("tab.focus", "tab.rename", "tab.close", "workspace.create", "pane.focus"),
        listOf(HerdrWorkspace("one", "Work", true), HerdrWorkspace("two", "Other", false)),
        listOf(HerdrTab("first", "one", "Shell", true), HerdrTab("second", "two", "Shell", false)),
        listOf(HerdrAgent("agent", "two", "second", "Agent", "blocked", false)))

    @Test fun tabStripFollowsWorkspaceWhileAgentsCanCrossWorkspaces() {
        assertEquals(listOf("first"), navigation.visibleTabs.map { it.id })
        assertTrue(navigation.allows(HerdrAction("pane.focus", "agent")))
        assertFalse(navigation.allows(HerdrAction("pane.focus", "removed")))
    }
    @Test fun mutationsRequireCapabilityReadinessAndExistingIdentity() {
        assertTrue(navigation.allows(HerdrAction("tab.close", "first")))
        assertFalse(navigation.allows(HerdrAction("tab.create", "one")))
        assertFalse(navigation.allows(HerdrAction("tab.close", "removed")))
        assertFalse(navigation.copy(ready = false).allows(HerdrAction("tab.close", "first")))
        assertFalse(navigation.copy(bootId = "").allows(HerdrAction("tab.close", "first")))
        assertFalse(navigation.allows(HerdrAction("workspace.close", "one")))
    }
    @Test fun renameRejectsControlAndEmptyLabelsButAllowsUnicode() {
        assertTrue(navigation.allows(HerdrAction("tab.rename", "first", "部署 · review")))
        listOf("", "   ", "line\nbreak", "null\u0000", "x".repeat(257)).forEach {
            assertFalse(navigation.allows(HerdrAction("tab.rename", "first", it)))
        }
    }
}
