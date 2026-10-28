/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cc.galaxnet.novascale.terminalfeature

import android.content.Context
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asUiText
import cc.galaxnet.novascale.ui.uiText
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TerminalSessionSummary(
    val key: String,
    val hostId: String,
    val peer: TailnetPeer,
    val hostDisplayName: String,
    val startedAtEpochMillis: Long,
    val tabCount: Int,
    val attachedViewCount: Int,
) {
    val detached: Boolean
        get() = attachedViewCount == 0
}

internal data class TerminalSessionTab(
    val id: Int,
    val title: UiText,
    val runtime: TerminalRuntime,
    val config: RemoteConnectionConfig,
)

/**
 * Application-owned terminal runtimes. Compose screens attach and detach views;
 * only explicit close, logout/tailnet replacement, or process death owns the
 * SSH runtime's end of life.
 */
internal class TerminalSessionRegistry private constructor(
    private val runtimeFactory: () -> TerminalRuntime,
    private val backgroundServiceUpdater: (enabled: Boolean, sessionCount: Int) -> Unit,
    private val tabTitleFactory: (Int) -> UiText,
) : AutoCloseable {
    constructor(
        context: Context,
        tailnet: TailnetBackend,
        applicationScope: CoroutineScope,
    ) : this(
        runtimeFactory = {
            TerminalConnectionController(
                context = context.applicationContext,
                tailnet = tailnet,
                scope = applicationScope,
            )
        },
        backgroundServiceUpdater = { enabled, count ->
            TerminalBackgroundService.update(context.applicationContext, enabled, count)
        },
        tabTitleFactory = { id -> uiText(R.string.terminal_default_tab, id) },
    )

    internal constructor(
        runtimeFactory: () -> TerminalRuntime,
        backgroundServiceUpdater: (Boolean, Int) -> Unit = { _, _ -> },
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit,
    ) : this(
        runtimeFactory,
        backgroundServiceUpdater,
        { defaultTerminalTabTitle(it).asUiText() },
    )

    private val groups = linkedMapOf<String, TerminalSessionGroup>()
    private val mutableSessions = MutableStateFlow<List<TerminalSessionSummary>>(emptyList())
    val sessions: StateFlow<List<TerminalSessionSummary>> = mutableSessions.asStateFlow()

    private var keepInBackground = false
    private var lastBackgroundServiceState: Pair<Boolean, Int>? = null

    fun getOrCreate(
        key: String,
        hostId: String,
        peer: TailnetPeer,
        config: RemoteConnectionConfig,
    ): TerminalSessionGroup {
        require(key.isNotBlank())
        require(hostId.isNotBlank())
        groups[key]?.let { group ->
            group.updateConnectionConfig(config)
            return group
        }
        return TerminalSessionGroup(
            key = key,
            hostId = hostId,
            peer = peer,
            initialConfig = config,
            runtimeFactory = runtimeFactory,
            tabTitleFactory = tabTitleFactory,
            onChanged = ::publish,
            onCloseRequested = ::closeGroup,
        ).also {
            groups[key] = it
            publish()
        }
    }

    fun newSessionKey(hostId: String): String {
        require(hostId.isNotBlank())
        return "$hostId:${UUID.randomUUID()}"
    }

    fun setKeepInBackground(enabled: Boolean) {
        keepInBackground = enabled
        updateBackgroundService()
    }

    fun closeGroup(key: String) {
        val group = groups.remove(key) ?: return
        group.closeRuntimes()
        publish()
    }

    fun closeAll() {
        val snapshot = groups.values.toList()
        groups.clear()
        snapshot.forEach(TerminalSessionGroup::closeRuntimes)
        publish()
    }

    private fun publish() {
        mutableSessions.value = groups.values.map(TerminalSessionGroup::summary)
        updateBackgroundService()
    }

    private fun updateBackgroundService() {
        val next = keepInBackground to groups.values.sumOf { it.tabs.value.size }
        if (lastBackgroundServiceState == next) return
        lastBackgroundServiceState = next
        backgroundServiceUpdater(next.first, next.second)
    }

    override fun close() = closeAll()
}

internal class TerminalSessionGroup(
    val key: String,
    val hostId: String,
    val peer: TailnetPeer,
    initialConfig: RemoteConnectionConfig,
    private val runtimeFactory: () -> TerminalRuntime,
    private val tabTitleFactory: (Int) -> UiText,
    private val onChanged: () -> Unit,
    private val onCloseRequested: (String) -> Unit,
) {
    private val startedAtEpochMillis = System.currentTimeMillis()
    private val mutableTabs = MutableStateFlow(
        listOf(
            TerminalSessionTab(
                id = 1,
                title = tabTitleFactory(1),
                runtime = runtimeFactory(),
                config = initialConfig,
            ),
        ),
    )
    val tabs: StateFlow<List<TerminalSessionTab>> = mutableTabs.asStateFlow()

    private val mutableSelectedTabId = MutableStateFlow(1)
    val selectedTabId: StateFlow<Int> = mutableSelectedTabId.asStateFlow()

    private val attachedViews = linkedSetOf<String>()
    private var activeViewId: String? = null
    private var nextTabId = 2
    private var closed = false

    fun attachView(attachmentId: String = UUID.randomUUID().toString()): AutoCloseable {
        check(!closed) { "Terminal session is closed" }
        attachedViews += attachmentId
        activeViewId = attachmentId
        onChanged()
        return AutoCloseable {
            if (attachedViews.remove(attachmentId)) {
                if (activeViewId == attachmentId) activeViewId = attachedViews.lastOrNull()
                onChanged()
            }
        }
    }

    fun activateView(attachmentId: String) {
        if (attachmentId in attachedViews) activeViewId = attachmentId
    }

    fun resizeFromView(
        tabId: Int,
        attachmentId: String,
        columns: Int,
        rows: Int,
        cellWidth: Int = 8,
        cellHeight: Int = 16,
    ) {
        if (activeViewId != attachmentId) return
        mutableTabs.value.firstOrNull { it.id == tabId }?.runtime?.resizeWithMetrics(columns, rows, cellWidth, cellHeight)
    }

    fun selectTab(tabId: Int) {
        if (mutableTabs.value.any { it.id == tabId }) mutableSelectedTabId.value = tabId
    }

    fun ensureConnected() {
        mutableTabs.value.forEach { tab -> tab.runtime.connectIfNeeded(tab.config) }
    }

    /** Saved-profile edits affect future connections, while live SSH sessions stay attached. */
    fun updateConnectionConfig(config: RemoteConnectionConfig) {
        if (closed || mutableTabs.value.all { it.config == config }) return
        mutableTabs.value = mutableTabs.value.map { it.copy(config = config) }
    }

    fun addTab(connectImmediately: Boolean) {
        if (closed || mutableTabs.value.size >= MAX_TABS) return
        val id = nextTabId++
        val tab = TerminalSessionTab(
            id = id,
            title = tabTitleFactory(id),
            runtime = runtimeFactory(),
            config = mutableTabs.value.first().config,
        )
        mutableTabs.value = mutableTabs.value + tab
        mutableSelectedTabId.value = tab.id
        if (connectImmediately) tab.runtime.connectIfNeeded(tab.config)
        onChanged()
    }

    fun renameTab(tabId: Int, title: String): Boolean {
        val normalized = title.trim().take(MAX_TAB_TITLE_LENGTH)
        if (normalized.isEmpty()) return false
        var renamed = false
        mutableTabs.value = mutableTabs.value.map { tab ->
            if (tab.id == tabId) {
                renamed = true
                tab.copy(title = normalized.asUiText())
            } else {
                tab
            }
        }
        if (renamed) onChanged()
        return renamed
    }

    fun closeTab(tabId: Int): Boolean {
        val tab = mutableTabs.value.firstOrNull { it.id == tabId } ?: return false
        if (mutableTabs.value.size == 1) {
            onCloseRequested(key)
            return true
        }
        tab.runtime.close()
        mutableTabs.value = mutableTabs.value - tab
        if (mutableSelectedTabId.value == tabId) {
            mutableSelectedTabId.value = mutableTabs.value.first().id
        }
        onChanged()
        return false
    }

    fun closeSession() {
        onCloseRequested(key)
    }

    internal fun summary(): TerminalSessionSummary = TerminalSessionSummary(
        key = key,
        hostId = hostId,
        peer = peer,
        hostDisplayName = peer.displayName,
        startedAtEpochMillis = startedAtEpochMillis,
        tabCount = mutableTabs.value.size,
        attachedViewCount = attachedViews.size,
    )

    internal fun closeRuntimes() {
        if (closed) return
        closed = true
        mutableTabs.value.forEach { it.runtime.close() }
        attachedViews.clear()
        activeViewId = null
    }

    companion object {
        const val MAX_TABS = 6
        const val MAX_TAB_TITLE_LENGTH = 40
    }
}

internal fun defaultTerminalTabTitle(id: Int): String = "Terminal $id"
