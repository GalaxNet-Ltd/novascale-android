/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.tailnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetConfiguration
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TailnetUiState(
    val connection: TailnetState,
    val peers: List<TailnetPeer>,
    val showOnlineOnly: Boolean = true,
    val refreshingPeers: Boolean = false,
    val peerMessage: UiText? = null,
)

/**
 * Lifecycle-aware UI boundary for the application-owned tailnet model.
 *
 * The backend owns the node, IPN watcher, and durable in-process peer snapshot.
 * This ViewModel converts those callbacks into immutable UI state and owns UI
 * operations such as automatic restoration and explicit peer refresh.
 */
internal class TailnetViewModel(
    private val backend: TailnetBackend,
    restoreConfiguration: TailnetConfiguration? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        TailnetUiState(
            connection = backend.currentState,
            peers = backend.currentPeers,
        ),
    )
    val uiState: StateFlow<TailnetUiState> = mutableUiState.asStateFlow()

    private val stateRegistration: AutoCloseable
    private val peerRegistration: AutoCloseable

    init {
        stateRegistration = backend.addStateListener { state ->
            mutableUiState.update { it.copy(connection = state) }
        }
        peerRegistration = backend.addPeerListener { peers ->
            mutableUiState.update { it.copy(peers = peers) }
        }
        if (restoreConfiguration != null && shouldRestoreTailnetAtLaunch(backend.currentState)) {
            // Restoration is pending before start() dispatches to IO. Do not flash a login prompt.
            mutableUiState.update { it.copy(connection = TailnetState.Starting) }
            viewModelScope.launch {
                // The backend publishes its own redacted Failed state.
                runCatching { backend.start(restoreConfiguration) }
            }
        }
    }

    fun refreshPeers() {
        if (mutableUiState.value.refreshingPeers) return
        mutableUiState.update { it.copy(refreshingPeers = true, peerMessage = null) }
        viewModelScope.launch {
            runCatching { backend.peers() }
                .onSuccess {
                    mutableUiState.update { state ->
                        state.copy(refreshingPeers = false, peerMessage = null)
                    }
                }
                .onFailure {
                    mutableUiState.update { state ->
                        state.copy(
                            refreshingPeers = false,
                            peerMessage = uiText(R.string.host_list_refresh_failed),
                        )
                    }
                }
        }
    }

    fun toggleOnlineOnly() {
        mutableUiState.update { it.copy(showOnlineOnly = !it.showOnlineOnly) }
    }

    override fun onCleared() {
        stateRegistration.close()
        peerRegistration.close()
    }

    class Factory(
        private val backend: TailnetBackend,
        private val restoreConfiguration: TailnetConfiguration? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TailnetViewModel::class.java))
            return TailnetViewModel(backend, restoreConfiguration) as T
        }
    }
}

internal fun shouldRestoreTailnetAtLaunch(state: TailnetState): Boolean = state is TailnetState.Stopped
