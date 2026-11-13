// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet

import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TailnetProfilesUiState(
    val snapshot: TailnetProfileSnapshot? = null,
    val busy: Boolean = false,
    val error: Int? = null,
) {
    val active: TailnetProfile? get() = snapshot?.active
}

/** One application-owned authority; activity destruction cannot interrupt a switch. */
internal class TailnetProfileController(
    private val storage: TailnetProfileStorage,
    private val backend: TailnetBackend,
    private val scope: CoroutineScope,
    private val configuration: (TailnetProfile) -> TailnetConfiguration,
    private val beforeSwitch: () -> Unit,
    private val afterSwitch: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(TailnetProfilesUiState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val invalidationListeners = CopyOnWriteArrayList<() -> Unit>()
    private var restored = false
    private var loginJob: Job? = null

    fun addInvalidationListener(listener: () -> Unit): AutoCloseable {
        invalidationListeners += listener
        return AutoCloseable { invalidationListeners -= listener }
    }

    fun restore() {
        if (restored || mutableState.value.busy) return
        restored = true
        submit {
            val snapshot = storage.load()
            publish(snapshot)
            if (backend.currentProfileId != snapshot.activeId || backend.currentState == TailnetState.Stopped) {
                backend.start(configuration(snapshot.active))
            }
        }
    }
    fun retry() {
        if (state.value.snapshot == null) { restored = false; restore() }
        else activate(checkNotNull(state.value.active).id, reconnect = true)
    }
    fun add(name: String, controlUrl: String) = submit { publish(storage.add(name, controlUrl)) }
    fun rename(id: String, name: String) = submit { publish(storage.rename(id, name)) }
    fun remove(id: String) = submit { publish(storage.remove(id)) }

    fun activate(id: String, reconnect: Boolean = false, useAsFree: Boolean = false) {
        val snapshot = state.value.snapshot ?: return
        if (id == snapshot.activeId && !reconnect) return
        if (snapshot.profiles.none { it.id == id }) return
        submit {
            // Persist the selected identity first. A crash resumes this identity, never
            // silently falls back to a different account after a failed connection.
            val selected = if (useAsFree) storage.selectFree(id) else storage.select(id)
            invalidate()
            publish(selected)
            try {
                withContext(Dispatchers.IO) { backend.close() }
                backend.start(configuration(selected.active))
            } finally { afterSwitch() }
        }
    }
    fun replace(name: String, controlUrl: String) = submit {
        val previous = checkNotNull(state.value.active)
        val replacement = storage.stageReplacement(name, controlUrl)
        invalidate()
        try {
            withContext(Dispatchers.IO) { backend.close() }
            backend.start(configuration(replacement))
            check(backend.currentState !is TailnetState.Failed)
            publish(storage.commitReplacement(previous.id, replacement))
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { backend.close() }
                storage.discardReplacement()
                backend.start(configuration(previous))
            }
            throw failure
        } finally { afterSwitch() }
    }
    suspend fun logout() = withContext(Dispatchers.Main.immediate) {
        check(!state.value.busy)
        mutableState.update { it.copy(busy = true, error = null) }
        cancelLogin()
        // The UI awaits the result; its destruction must not cancel node teardown.
        scope.async {
            try {
                mutex.withLock {
                    invalidate()
                    try { backend.logout() } finally { afterSwitch() }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
        }.await()
    }

    /** Login belongs to the selected profile. Cancellation or switching drops its URL. */
    suspend fun login(profileId: String, authKey: String?): LoginRequest? {
        val caller = currentCoroutineContext()[Job]
        check(loginJob == null || loginJob?.isActive != true)
        loginJob = caller
        try {
            return mutex.withLock {
                val active = checkNotNull(state.value.active)
                check(active.id == profileId && !state.value.busy)
                backend.start(configuration(active))
                when {
                    backend.currentState is TailnetState.Running -> null
                    authKey != null -> { backend.beginAuthKeyLogin(authKey); null }
                    else -> backend.beginInteractiveLogin()
                }.also {
                    currentCoroutineContext().ensureActive()
                    check(state.value.active?.id == profileId && backend.currentProfileId == profileId)
                }
            }
        } finally { if (loginJob === caller) loginJob = null }
    }
    fun cancelLogin() { loginJob?.cancel(); loginJob = null; backend.cancelAuthKeyLogin() }
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private fun invalidate() {
        cancelLogin()
        beforeSwitch()
        invalidationListeners.forEach { it() }
    }
    private fun publish(snapshot: TailnetProfileSnapshot) { mutableState.update { it.copy(snapshot = snapshot) } }
    private fun submit(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        // Cancel login before taking its mutex, so a slow auth URL cannot block switching.
        cancelLogin()
        scope.launch {
            try { mutex.withLock { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: cc.galaxnet.novascale.billing.ProLimitReached) { /* The shared purchase sheet explains the allowance. */ }
            catch (_: Exception) { mutableState.update { it.copy(error = R.string.tailnets_operation_failed) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
