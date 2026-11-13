// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Bundle
import android.content.Intent
import cc.galaxnet.novascale.AppPreferences
import cc.galaxnet.novascale.MainActivity
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.LocalProxyEndpoint
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class ProxySettings(val enabled: Boolean = true, val port: Int = 0, val background: Boolean = false)
internal enum class ProxyStatus { STOPPED, STARTING, RUNNING, FAILED }
internal enum class ProxyStopReason { DISABLED, TAILNET_REQUIRED, INACTIVE }
internal data class ProxyIdentity(val profileId: String, val nodeId: String) {
    // Retain the existing credential scope without using it as a tailnet ID.
    val credentialKey: String get() = "$profileId:$nodeId"
    fun session(endpoint: LocalProxyEndpoint, username: String, password: String, realm: String) =
        ProxySession(endpoint, username, password, realm, profileId)
}
// Intentionally not a data class: generated toString/copy must not expose secrets.
internal class ProxySession(val endpoint: LocalProxyEndpoint, val username: String, val password: String, val realm: String, val profileId: String)
internal data class ProxyUiState(val status: ProxyStatus = ProxyStatus.STOPPED, val session: ProxySession? = null,
    val stopReason: ProxyStopReason = ProxyStopReason.TAILNET_REQUIRED)

/** Application-owned runtime. Screens observe it; leaving a tab never owns shutdown. */
internal class LocalProxyController(
    private val app: Application,
    private val backend: TailnetBackend,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ProxyUiState())
    val state = mutableState.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private val identity = MutableStateFlow<ProxyIdentity?>(null)
    private val generation = MutableStateFlow(0)
    private val switchingProfile = MutableStateFlow(false)
    private val secrets = ProxySecretStore(app)
    private var activities = 0
    private var rotation = 0
    private var requestedRotation = 0
    private var idleJob: Job? = null

    init {
        backend.addStateListener { value ->
            identity.value = if (value is TailnetState.Running) backend.currentProfileId?.let { ProxyIdentity(it, value.self.stableNodeId) } else null
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activities++; idleJob?.cancel(); foreground.value = true
            }
            override fun onActivityStopped(activity: Activity) {
                activities--
                if (activities == 0) idleJob = scope.launch {
                    // Avoid revoking on a configuration change or activity handoff.
                    delay(700); if (activities == 0) foreground.value = false
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        scope.launch {
            combine(identity, preferences.proxySettings, foreground, generation, switchingProfile) { profile, settings, visible, epoch, switching ->
                Request(if (switching) null else profile, settings, visible || settings.background, epoch, requestedRotation)
            }.distinctUntilChanged().collectLatest { request ->
                val profile = request.profile
                if (profile == null || !request.settings.enabled || !request.visible) {
                    mutableState.value = ProxyUiState(stopReason = when {
                        !request.settings.enabled -> ProxyStopReason.DISABLED
                        !request.visible || switchingProfile.value -> ProxyStopReason.INACTIVE
                        else -> ProxyStopReason.TAILNET_REQUIRED
                    }); return@collectLatest
                }
                var endpoint: LocalProxyEndpoint? = null
                try {
                    mutableState.value = ProxyUiState(ProxyStatus.STARTING)
                    val password = withContext(Dispatchers.IO) { secrets.load(profile.credentialKey, request.rotation != rotation) }
                    if (switchingProfile.value || identity.value != request.profile) return@collectLatest
                    rotation = request.rotation
                    val realm = "NovaScale-${UUID.randomUUID()}"
                    endpoint = backend.startLocalProxy(request.settings.port, "novascale", password, realm)
                    ensureActive()
                    if (switchingProfile.value || identity.value != request.profile) return@collectLatest
                    val session = profile.session(endpoint, "novascale", password, realm)
                    if (request.settings.background) ProxyBackgroundService.start(app, endpoint.address)
                    mutableState.value = ProxyUiState(ProxyStatus.RUNNING, session)
                    if (endpoint.usedFallback) notifyFallback(request.settings.port, endpoint.address)
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.value = ProxyUiState(ProxyStatus.FAILED) }
                finally {
                    if (mutableState.value.status != ProxyStatus.FAILED) mutableState.value = ProxyUiState(stopReason = ProxyStopReason.INACTIVE)
                    endpoint?.let { withContext(NonCancellable + Dispatchers.IO) { backend.stopLocalProxy(it.id) } }
                    ProxyBackgroundService.stop(app)
                }
            }
        }
    }
    fun beginProfileChange() {
        switchingProfile.value = true
        mutableState.value = ProxyUiState(stopReason = ProxyStopReason.INACTIVE)
    }
    fun endProfileChange() { switchingProfile.value = false }
    fun retry() { generation.value++ }
    fun enable() { scope.launch {
        try { preferences.setProxyEnabled(true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = ProxyUiState(ProxyStatus.FAILED) }
    } }
    fun regeneratePassword() { requestedRotation++; generation.value++ }
    fun stop() { scope.launch { preferences.setProxyEnabled(false) } }

    private fun notifyFallback(preferred: Int, actual: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("proxy_port", app.getString(R.string.proxy_notifications), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 1844, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(app, "proxy_port")
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(app.getString(R.string.proxy_port_fallback_title))
            .setContentText(app.getString(R.string.proxy_port_fallback, preferred, actual))
            .setContentIntent(open).setAutoCancel(true).build()
        // Settings always retains the fallback warning if notifications are denied.
        runCatching { manager.notify(1844, notification) }
    }
    private data class Request(val profile: ProxyIdentity?, val settings: ProxySettings, val visible: Boolean, val epoch: Int, val rotation: Int)
}
