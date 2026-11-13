/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import android.app.Application
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.files.SftpPreviewCache
import cc.galaxnet.novascale.tailnet.GoTailnetBackend
import cc.galaxnet.novascale.terminalfeature.TerminalSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class NovaScaleApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val tailnetBackend: TailnetBackend by lazy { GoTailnetBackend(this) }
    internal val billing by lazy { cc.galaxnet.novascale.billing.createBilling(this, applicationScope) }
    internal val preferences: AppPreferences by lazy { AppPreferences(this) }
    internal val traditionalHosts: cc.galaxnet.novascale.remote.TraditionalHostRepository by lazy { cc.galaxnet.novascale.remote.TraditionalHostRepository(this, applicationScope, billing.manager) { tailnetProfiles.state.value.active?.id } }
    internal val terminalSessions: TerminalSessionRegistry by lazy {
        TerminalSessionRegistry(this, tailnetBackend, applicationScope)
    }

    internal val tailnetProfiles: cc.galaxnet.novascale.tailnet.TailnetProfileController by lazy {
        cc.galaxnet.novascale.tailnet.TailnetProfileController(
            cc.galaxnet.novascale.tailnet.TailnetProfileRepository(this,
                traditionalHosts::migrateLegacyTailnet, { id -> traditionalHosts.removeTailnetMetadata(id); browserLibrary.removeProfile(id) }, paywall = billing.manager),
            tailnetBackend, applicationScope,
            configuration = { it.configuration(this, androidTailnetHostname()) },
            beforeSwitch = {
                localProxy.beginProfileChange()
                terminalSessions.closeTailnetSessions()
                herdrControllers.toMap().filterValues { it.controller.configuredTransport == cc.galaxnet.novascale.remote.SshTransport.TAILNET }.forEach { (id, attachment) ->
                    herdrControllers.remove(id); attachment.controller.close()
                }
            },
            afterSwitch = { localProxy.endProfileChange() },
        )
    }

    internal val herdrUsage by lazy { cc.galaxnet.novascale.billing.HerdrUsageManager(
        cc.galaxnet.novascale.billing.RoomHerdrUsageStore(this), billing.manager, applicationScope) }
    internal val herdrControllers = androidx.compose.runtime.mutableStateMapOf<String, HerdrAttachment>()
    private var herdrAppearance = cc.galaxnet.novascale.terminalfeature.TerminalThemeCatalog.default.toNativeTheme() to true
    internal fun createHerdrController(identity: String) = cc.galaxnet.novascale.herdr.HerdrController(
        this, tailnetBackend, applicationScope, herdrUsage, identity).also {
        it.applyTheme(herdrAppearance.first)
        it.setImproveTextContrast(herdrAppearance.second)
    }

    internal val browserLibrary by lazy { cc.galaxnet.novascale.browser.BrowserLibrary(this) }

    internal val localProxy by lazy { cc.galaxnet.novascale.browser.LocalProxyController(this, tailnetBackend, preferences, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        localProxy
        SftpPreviewCache.clear(this)
        applicationScope.launch {
            combine(preferences.terminalSettings, preferences.importedTerminalThemes) { settings, imported ->
                cc.galaxnet.novascale.terminalfeature.TerminalThemeCatalog.resolve(settings.themeId, imported).toNativeTheme() to
                    settings.herdrImproveTextContrast
            }.distinctUntilChanged().collect { appearance ->
                herdrAppearance = appearance
                herdrControllers.values.toList().forEach {
                    it.controller.applyTheme(appearance.first)
                    it.controller.setImproveTextContrast(appearance.second)
                }
            }
        }
        applicationScope.launch {
            preferences.keepTerminalSessionsInBackground.collectLatest { enabled ->
                terminalSessions.setKeepInBackground(enabled)
            }
        }
    }
}
