/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import org.json.JSONObject

internal data class HerdrWorkspace(val id: String, val label: String, val focused: Boolean)
internal data class HerdrTab(val id: String, val workspaceId: String, val label: String, val focused: Boolean)
internal data class HerdrAgent(val id: String, val workspaceId: String, val tabId: String, val label: String, val status: String, val focused: Boolean)
internal data class HerdrAction(val method: String, val targetId: String, val label: String? = null)
internal data class HerdrActionRequest(val id: Long, val bootId: String, val action: HerdrAction)

internal fun validHerdrTabLabel(label: String): Boolean = label.isNotBlank() && label.length <= 256 && label.none(Character::isISOControl)

/** Small immutable UI projection; the protocol and terminal frames stay native. */
internal data class HerdrNavigation(
    val ready: Boolean = false,
    val bootId: String = "",
    val methods: Set<String> = emptySet(),
    val workspaces: List<HerdrWorkspace> = emptyList(),
    val tabs: List<HerdrTab> = emptyList(),
    val agents: List<HerdrAgent> = emptyList(),
) {
    val workspace get() = workspaces.firstOrNull { it.focused }
    val visibleTabs get() = tabs.filter { it.workspaceId == workspace?.id }
    fun supports(method: String) = ready && bootId.isNotEmpty() && method in methods
    fun allows(action: HerdrAction): Boolean = supports(action.method) && when (action.method) {
        "workspace.focus", "workspace.create", "tab.create" -> action.label == null && workspaces.any { it.id == action.targetId }
        "tab.focus", "tab.close" -> action.label == null && tabs.any { it.id == action.targetId }
        "tab.rename" -> tabs.any { it.id == action.targetId } && action.label?.let(::validHerdrTabLabel) == true
        "pane.focus" -> action.label == null && agents.any { it.id == action.targetId }
        else -> false
    }

    companion object {
        fun parse(json: JSONObject): HerdrNavigation {
            fun objects(key: String): List<JSONObject> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until minOf(array.length(), 4096)).mapNotNull(array::optJSONObject)
            }
            fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).take(512)
            fun JSONObject.id(key: String) = text(key).takeIf { it.isNotBlank() && it.length <= 256 }.orEmpty()
            val workspaces = objects("workspaces").map { HerdrWorkspace(it.id("workspace_id"), it.text("label"), it.optBoolean("focused")) }
                .filter { it.id.isNotEmpty() }.distinctBy { it.id }
            val tabs = objects("tabs").map { HerdrTab(it.id("tab_id"), it.id("workspace_id"), it.text("label"), it.optBoolean("focused")) }
                .filter { it.id.isNotEmpty() }.distinctBy { it.id }
            val agents = objects("agents").map { item ->
                val id = item.id("pane_id")
                val label = listOf("name", "title", "display_agent", "agent").firstNotNullOfOrNull { key -> item.text(key).takeIf(String::isNotBlank) } ?: id
                HerdrAgent(id, item.id("workspace_id"), item.id("tab_id"), label, item.text("agent_status"), item.optBoolean("focused"))
            }.filter { it.id.isNotEmpty() }.distinctBy { it.id }
            val methods = json.optJSONArray("methods")?.let { a -> (0 until minOf(a.length(), 256)).map { a.optString(it).take(128) }.toSet() }.orEmpty()
            return HerdrNavigation(json.optBoolean("ready"), json.id("boot_id"), methods, workspaces, tabs, agents)
        }
    }
}
