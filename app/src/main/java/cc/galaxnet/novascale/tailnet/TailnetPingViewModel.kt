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
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetConnectionPath
import cc.galaxnet.novascale.core.TailnetPingFailure
import cc.galaxnet.novascale.core.TailnetPingResult
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TailnetPingSample(
    val sequence: Int,
    val result: TailnetPingResult,
)

internal data class TailnetPingUiState(
    val samples: List<TailnetPingSample> = emptyList(),
    val currentRound: Int = 0,
    val totalRounds: Int = 10,
    val isRunning: Boolean = false,
) {
    val successfulLatencies: List<Double>
        get() = samples.mapNotNull { it.result.latencyMilliseconds }

    val latestResult: TailnetPingResult?
        get() = samples.lastOrNull()?.result

    val latestPathResult: TailnetPingResult?
        get() = samples.lastOrNull { it.result.path != null }?.result

    val latestLatencyText: String
        get() = successfulLatencies.lastOrNull()?.asLatency() ?: "Waiting for response…"

    val averageLatencyText: String
        get() = successfulLatencies.takeIf { it.isNotEmpty() }
            ?.average()
            ?.asLatency()
            ?: "—"

    val minimumLatencyText: String
        get() = successfulLatencies.minOrNull()?.asLatency() ?: "—"

    val maximumLatencyText: String
        get() = successfulLatencies.maxOrNull()?.asLatency() ?: "—"
}

internal class TailnetPingViewModel(
    private val backend: TailnetBackend,
    private val address: String,
    private val totalRounds: Int = 10,
    private val intervalMillis: Long = 1_000,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TailnetPingUiState(totalRounds = totalRounds))
    val uiState: StateFlow<TailnetPingUiState> = mutableUiState.asStateFlow()
    private var pingJob: Job? = null

    fun start() {
        if (pingJob?.isActive == true) return
        mutableUiState.value = TailnetPingUiState(totalRounds = totalRounds, isRunning = true)
        val launchedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (round in 1..totalRounds) {
                    mutableUiState.update { it.copy(currentRound = round) }
                    val result = try {
                        backend.ping(address)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        TailnetPingResult(
                            address = address,
                            latencyMilliseconds = null,
                            failure = TailnetPingFailure.UNREACHABLE,
                            path = null,
                        )
                    }
                    mutableUiState.update { state ->
                        state.copy(
                            samples = state.samples + TailnetPingSample(round, result),
                        )
                    }
                    if (round < totalRounds) delay(intervalMillis)
                }
            } finally {
                if (pingJob === coroutineContext[Job]) {
                    mutableUiState.update { it.copy(isRunning = false) }
                    pingJob = null
                }
            }
        }
        pingJob = launchedJob
        launchedJob.start()
    }

    fun retry() {
        stop()
        start()
    }

    fun stop() {
        val activeJob = pingJob
        pingJob = null
        activeJob?.cancel()
        mutableUiState.update { it.copy(isRunning = false) }
    }

    override fun onCleared() {
        stop()
    }

    class Factory(
        private val backend: TailnetBackend,
        private val address: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TailnetPingViewModel::class.java))
            return TailnetPingViewModel(backend, address) as T
        }
    }
}

internal fun TailnetConnectionPath.displayName(region: String? = null): String = when (this) {
    TailnetConnectionPath.DIRECT -> "Direct connection"
    TailnetConnectionPath.PEER_RELAY -> "Peer relayed connection"
    TailnetConnectionPath.DERP_RELAY -> region
        ?.takeIf { it.isNotBlank() }
        ?.let { "DERP relay (${it.uppercase(Locale.ROOT)})" }
        ?: "DERP relayed connection"
}

private fun Double.asLatency(): String = String.format(Locale.ROOT, "%.1f ms", this)
