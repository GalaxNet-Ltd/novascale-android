/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import cc.galaxnet.novascale.remote.HostIdentityRow
import cc.galaxnet.novascale.remote.HostOrganizationCard
import cc.galaxnet.novascale.remote.hostMatchesSearch
import cc.galaxnet.novascale.remote.GROUP_FILTER_TRADITIONAL
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.SideEffect
import cc.galaxnet.novascale.herdr.HerdrSessionSummary
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.galaxnet.novascale.core.TailnetBackend
import cc.galaxnet.novascale.core.TailnetConnectionPath
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.core.TailnetPingFailure
import cc.galaxnet.novascale.core.TailnetState
import cc.galaxnet.novascale.files.SftpScreen
import cc.galaxnet.novascale.files.FilePreviewSettings
import cc.galaxnet.novascale.files.FilePreviewSettingsViewModel
import cc.galaxnet.novascale.files.FilePreviewSizeLimit
import cc.galaxnet.novascale.nativecore.ghosttyTerminalProbe
import cc.galaxnet.novascale.nativecore.rustCoreBuildInfo
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.remote.RemoteConnectionForm
import cc.galaxnet.novascale.remote.SshProfileStore
import cc.galaxnet.novascale.remote.TraditionalHost
import cc.galaxnet.novascale.remote.TraditionalHostRepository
import cc.galaxnet.novascale.remote.SshTransport
import cc.galaxnet.novascale.remote.TraditionalHostRows
import cc.galaxnet.novascale.remote.TraditionalHostEditScreen
import cc.galaxnet.novascale.remote.TraditionalHostDetailScreen
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostGroupsState
import cc.galaxnet.novascale.remote.HostPinsState
import cc.galaxnet.novascale.remote.HostFilterMember
import cc.galaxnet.novascale.remote.HostFilterSheet
import cc.galaxnet.novascale.remote.HostFilterToolbar
import cc.galaxnet.novascale.remote.rememberHostGroupFilter
import cc.galaxnet.novascale.remote.HostGroupMembershipDialog
import cc.galaxnet.novascale.remote.GROUP_FILTER_ALL
import cc.galaxnet.novascale.remote.hostMatchesGroupFilter
import cc.galaxnet.novascale.herdr.HerdrController
import cc.galaxnet.novascale.herdr.HerdrScreen
import androidx.compose.runtime.mutableStateMapOf
import cc.galaxnet.novascale.tailnet.DEFAULT_CONTROL_SERVER
import cc.galaxnet.novascale.tailnet.TailnetProfilePreferences
import cc.galaxnet.novascale.tailnet.TailnetProfile
import cc.galaxnet.novascale.tailnet.TailnetProfileController
import cc.galaxnet.novascale.tailnet.TailnetProfilesScreen
import cc.galaxnet.novascale.tailnet.TailnetPingUiState
import cc.galaxnet.novascale.tailnet.TailnetPingViewModel
import cc.galaxnet.novascale.tailnet.TailnetViewModel
import cc.galaxnet.novascale.tailnet.displayName
import cc.galaxnet.novascale.tailnet.tailnetConfiguration
import cc.galaxnet.novascale.terminalfeature.TerminalScreen
import cc.galaxnet.novascale.terminalfeature.TerminalSettingsScreen
import cc.galaxnet.novascale.terminalfeature.TerminalSettingsViewModel
import cc.galaxnet.novascale.terminalfeature.TerminalSessionRegistry
import cc.galaxnet.novascale.terminalfeature.TerminalSessionSummary
import cc.galaxnet.novascale.terminalfeature.adjustTerminalChromeBackground
import cc.galaxnet.novascale.terminalfeature.localizedDisplayName
import cc.galaxnet.novascale.ui.NovaActionRow
import cc.galaxnet.novascale.ui.NovaGroupedCard
import cc.galaxnet.novascale.ui.NovaIcons
import cc.galaxnet.novascale.ui.NovaPlatformTile
import cc.galaxnet.novascale.ui.NovaSectionLabel
import cc.galaxnet.novascale.ui.NovaSpacing
import cc.galaxnet.novascale.ui.NovaStatusDot
import cc.galaxnet.novascale.ui.NovaTheme
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asUiText
import cc.galaxnet.novascale.ui.asString
import cc.galaxnet.novascale.ui.uiText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AppSection(
    @StringRes val labelRes: Int,
    @StringRes val screenTitleRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, R.string.screen_hosts, NovaIcons.NavigationHosts),
    BROWSER(R.string.nav_browser, R.string.nav_browser, androidx.compose.material.icons.Icons.Outlined.Language),
    SETTINGS(R.string.nav_settings, R.string.nav_settings, NovaIcons.NavigationSettings),
}

internal enum class PendingHostAction { NONE, TERMINAL, FILES, HERDR }

internal sealed interface AppDestination {
    data object Login : AppDestination
    data object Tailnets : AppDestination
    data object TailnetDetails : AppDestination
    data object Versions : AppDestination
    data class Host(val peer: TailnetPeer) : AppDestination
    data class TraditionalHostDetail(val host: TraditionalHost) : AppDestination
    data class TraditionalHostEdit(val host: TraditionalHost?) : AppDestination
    data class TraditionalTerminal(val host: TraditionalHost, val peer: TailnetPeer, val sessionKey: String) : AppDestination
    data class TraditionalFiles(val host: TraditionalHost, val peer: TailnetPeer) : AppDestination
    data class TraditionalHerdr(val host: TraditionalHost, val peer: TailnetPeer) : AppDestination
    data class Ping(val peer: TailnetPeer) : AppDestination
    data class Terminal(
        val peer: TailnetPeer,
        val sessionKey: String,
        val returnToHost: Boolean,
    ) : AppDestination
    data class Files(val peer: TailnetPeer) : AppDestination
    data class Herdr(val peer: TailnetPeer, val controllerKey: String) : AppDestination
    data class SshSettings(
        val peer: TailnetPeer,
        val continueTo: PendingHostAction = PendingHostAction.NONE,
        val terminalSessionKey: String? = null,
        val terminalReturnToHost: Boolean = true,
    ) : AppDestination
    data object TerminalSettings : AppDestination
    data object ProxySettings : AppDestination
    data object FilePreviewSettings : AppDestination
    data object Source : AppDestination
    data class LegalDocument(val document: PackagedLegalDocument) : AppDestination
}

internal data class HerdrAttachment(val controller: HerdrController, val destination: AppDestination, val hostName: String)

internal data class NovaScaleNavigationState(
    val section: AppSection = AppSection.HOME,
    val homeDestination: AppDestination? = null,
    val settingsDestination: AppDestination? = null,
) {
    val destination: AppDestination?
        get() = destinationFor(section)

    fun destinationFor(section: AppSection): AppDestination? = when (section) {
        AppSection.BROWSER -> null
        AppSection.HOME -> homeDestination
        AppSection.SETTINGS -> settingsDestination
    }
}

internal class NovaScaleNavigationModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(NovaScaleNavigationState())
    val uiState: StateFlow<NovaScaleNavigationState> = mutableUiState.asStateFlow()

    fun selectSection(section: AppSection) {
        mutableUiState.update { it.copy(section = section) }
    }

    fun open(destination: AppDestination?) {
        mutableUiState.update { current ->
            when (current.section) {
                AppSection.BROWSER -> current
                AppSection.HOME -> current.copy(homeDestination = destination)
                AppSection.SETTINGS -> current.copy(settingsDestination = destination)
            }
        }
    }

    fun open(section: AppSection, destination: AppDestination?) {
        mutableUiState.update { current ->
            when (section) {
                AppSection.BROWSER -> current.copy(section = section)
                AppSection.HOME -> current.copy(
                    section = section,
                    homeDestination = destination,
                )
                AppSection.SETTINGS -> current.copy(
                    section = section,
                    settingsDestination = destination,
                )
            }
        }
    }

    private var profileId: String? = null
    fun bindProfile(id: String?) {
        if (id == null) return
        if (profileId != null && profileId != id) invalidateTailnetDestinations()
        profileId = id
    }

    fun invalidateTailnetDestinations() {
        fun keep(destination: AppDestination?): AppDestination? = when (destination) {
            is AppDestination.TraditionalTerminal, is AppDestination.TraditionalHerdr, is AppDestination.TraditionalFiles, AppDestination.Tailnets -> destination
            else -> null
        }
        mutableUiState.update { current ->
            current.copy(homeDestination = keep(current.homeDestination), settingsDestination = keep(current.settingsDestination))
        }
    }

    fun resetToHome() {
        mutableUiState.value = NovaScaleNavigationState()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NovaScaleApp(
    tailnetBackend: TailnetBackend,
    terminalSessions: TerminalSessionRegistry,
    preferences: AppPreferences,
    initialTerminalPeer: TailnetPeer? = null,
    initialTerminalSessionKey: String? = null,
) {
    val onboardingComplete by preferences.onboardingComplete.collectAsStateWithLifecycle(initialValue = null)
    val onboardingScope = rememberCoroutineScope()
    var onboardingError by remember { mutableStateOf(false) }
    var completingOnboarding by remember { mutableStateOf(false) }
    if (onboardingComplete != true) {
        if (onboardingComplete == false) {
            OnboardingScreen(busy = completingOnboarding, error = onboardingError, onComplete = {
                completingOnboarding = true
                onboardingScope.launch {
                    try { preferences.completeOnboarding() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { onboardingError = true }
                    finally { completingOnboarding = false }
                }
            })
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val context = LocalContext.current
    val proxyController = (context.applicationContext as NovaScaleApplication).localProxy
    val billing = (context.applicationContext as NovaScaleApplication).billing
    val proState by billing.manager.state.collectAsStateWithLifecycle()
    cc.galaxnet.novascale.billing.PaywallHost(billing, herdrUsage = { (context.applicationContext as NovaScaleApplication).herdrUsage })
    val navigation: NovaScaleNavigationModel = viewModel()
    val profiles = (context.applicationContext as NovaScaleApplication).tailnetProfiles
    val profilesState by profiles.state.collectAsStateWithLifecycle()
    val selectedProfileId = profilesState.active?.id
    navigation.bindProfile(selectedProfileId)
    val sshProfiles = remember(context, selectedProfileId) { SshProfileStore(context, selectedProfileId) }
    LaunchedEffect(profiles) { profiles.restore() }
    val traditionalHosts = (context.applicationContext as NovaScaleApplication).traditionalHosts
    val savedTraditionalHosts by traditionalHosts.hosts.collectAsStateWithLifecycle()
    val traditionalStorageError by traditionalHosts.storageError.collectAsStateWithLifecycle()
    val groupRepository = traditionalHosts.groups
    val groupState by groupRepository.state.collectAsStateWithLifecycle()
    val groupStorageError by groupRepository.storageError.collectAsStateWithLifecycle()
    val pinRepository = traditionalHosts.pins
    val pinState by pinRepository.state.collectAsStateWithLifecycle()
    val pinStorageError by pinRepository.storageError.collectAsStateWithLifecycle()
    val pinPending = remember { mutableStateMapOf<GroupHostKey, Boolean>() }
    var showHostFilter by remember { mutableStateOf(false) }
    var addingTraditionalHost by remember { mutableStateOf(false) }
    var groupFilter by rememberHostGroupFilter(groupState, groupStorageError)
    var groupHost by remember { mutableStateOf<Pair<GroupHostKey?, String>?>(null) }
    val herdrScope = rememberCoroutineScope()
    val herdrControllers = (context.applicationContext as NovaScaleApplication).herdrControllers

    val herdrSummaries = herdrControllers.entries.mapNotNull { (id, attachment) ->
        // The home list observes connection metadata, never per-frame terminal snapshots.
        key(id) {
            val summary by remember(attachment) {
                attachment.controller.state.map { state -> state.sessionName?.let {
                    HerdrSessionSummary(id, attachment.hostName, it, state.connected, state.busy)
                } }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)
            summary
        }
    }

    fun togglePin(key: GroupHostKey) {
        if (!pinState.loaded || pinStorageError || pinPending[key] == true) return
        val next = pinState.pins.none { it.key == key }
        pinPending[key] = true
        herdrScope.launch {
            try { pinRepository.setPinned(key, next) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(context, R.string.host_pin_save_error, android.widget.Toast.LENGTH_SHORT).show() }
            finally { pinPending.remove(key) }
        }
    }

    val tailnetViewModelFactory = remember(tailnetBackend) { TailnetViewModel.Factory(tailnetBackend) }
    val tailnetViewModel: TailnetViewModel = viewModel(factory = tailnetViewModelFactory)
    val terminalSettingsViewModelFactory = remember(context, preferences) {
        TerminalSettingsViewModel.Factory(context, preferences)
    }
    val terminalSettingsViewModel: TerminalSettingsViewModel = viewModel(factory = terminalSettingsViewModelFactory)
    val filePreviewSettingsViewModel: FilePreviewSettingsViewModel = viewModel(
        factory = remember(preferences) { FilePreviewSettingsViewModel.Factory(preferences) },
    )
    val navigationState by navigation.uiState.collectAsStateWithLifecycle()
    val tailnetUiState by tailnetViewModel.uiState.collectAsStateWithLifecycle()
    val terminalSettingsState by terminalSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val filePreviewSettings by filePreviewSettingsViewModel.settings.collectAsStateWithLifecycle()
    val section = navigationState.section
    val destination = navigationState.destination
    val tailnetState = tailnetUiState.connection
    val tailnetPeers = tailnetUiState.peers
    val currentTailnetProfile = selectedProfileId
    val terminalSessionSummaries by terminalSessions.sessions.collectAsStateWithLifecycle()
    var closeSftpSession by remember { mutableStateOf<(() -> Unit)?>(null) }
    var sftpContentFullScreen by remember { mutableStateOf(false) }
    var sftpTransport by remember { mutableStateOf(SshTransport.TAILNET) }

    val traditionalSubtitle = stringResource(R.string.host_filter_traditional_subtitle)
    val noAddress = stringResource(R.string.no_tailnet_address)
    val filterMembers = remember(savedTraditionalHosts, tailnetPeers, tailnetState, currentTailnetProfile, traditionalSubtitle, noAddress) {
        buildList {
            savedTraditionalHosts.forEach { host -> add(HostFilterMember(GroupHostKey.traditional(host.id), host.name, host.endpoint.ifEmpty { traditionalSubtitle }, null)) }
            if (tailnetState is TailnetState.Running) tailnetPeers.forEach { peer ->
                GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), peer.stableNodeId)?.let { key ->
                    add(HostFilterMember(key, peer.displayName, peer.dnsName ?: peer.addresses.firstOrNull() ?: noAddress, peer.online))
                }
            }
        }
    }
    if (showHostFilter) HostFilterSheet(
        repository = groupRepository,
        state = groupState,
        storageError = groupStorageError,
        selected = groupFilter,
        members = filterMembers,
        onSelect = { groupFilter = it; showHostFilter = false },
        onOpenMember = { key ->
            showHostFilter = false
            if (key.kind == "traditional") {
                savedTraditionalHosts.firstOrNull { it.id == key.hostId }?.let { navigation.open(AppDestination.TraditionalHostDetail(it)) }
            } else {
                tailnetPeers.firstOrNull { GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), it.stableNodeId) == key }
                    ?.let { navigation.open(AppDestination.Host(it)) }
            }
        },
        onRemoveMember = { group, member ->
            herdrScope.launch {
                try { groupRepository.setMembership(member.key, group.id, false) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { android.widget.Toast.makeText(context, R.string.host_groups_save_error, android.widget.Toast.LENGTH_SHORT).show() }
            }
        },
        onDismiss = { showHostFilter = false },
    )
    groupHost?.let { (key, name) ->
        HostGroupMembershipDialog(groupState, key, name, storageError = groupStorageError, onSave = groupRepository::setGroups, onCreate = groupRepository::create) { groupHost = null }
    }

    LaunchedEffect(initialTerminalPeer, initialTerminalSessionKey) {
        if (initialTerminalPeer != null && destination == null && initialTerminalSessionKey != null &&
            terminalSessions.sessions.value.any { it.key == initialTerminalSessionKey }) {
            navigation.open(
                AppSection.HOME,
                if (initialTerminalPeer.hostId().startsWith("traditional:")) {
                    AppDestination.TraditionalTerminal(
                        TraditionalHost(initialTerminalPeer.hostId(), initialTerminalPeer.displayName),
                        initialTerminalPeer,
                        initialTerminalSessionKey,
                    )
                } else AppDestination.Terminal(
                    peer = initialTerminalPeer,
                    sessionKey = initialTerminalSessionKey,
                    returnToHost = false,
                ),
            )
        }
    }

    fun goBack() {
        if (destination is AppDestination.Files || destination is AppDestination.TraditionalFiles) {
            closeSftpSession?.invoke()
            closeSftpSession = null
            sftpContentFullScreen = false
        }
        navigation.open(when (val current = destination) {
            is AppDestination.Terminal -> if (current.returnToHost) AppDestination.Host(current.peer) else null
            is AppDestination.TraditionalHostEdit -> current.host?.let { AppDestination.TraditionalHostDetail(it) }
            is AppDestination.TraditionalTerminal -> AppDestination.TraditionalHostDetail(current.host)
            is AppDestination.TraditionalFiles -> AppDestination.TraditionalHostDetail(current.host)
            is AppDestination.TraditionalHerdr -> AppDestination.TraditionalHostDetail(current.host)
            is AppDestination.Herdr -> AppDestination.Host(current.peer)
            is AppDestination.Files -> AppDestination.Host(current.peer)
            is AppDestination.Ping -> AppDestination.Host(current.peer)
            is AppDestination.SshSettings -> if (
                current.continueTo == PendingHostAction.TERMINAL && !current.terminalReturnToHost
            ) {
                null
            } else {
                AppDestination.Host(current.peer)
            }
            AppDestination.TailnetDetails -> AppDestination.Tailnets
            AppDestination.Tailnets -> null
            AppDestination.Login -> if (section == AppSection.SETTINGS) AppDestination.TailnetDetails else null
            is AppDestination.LegalDocument -> AppDestination.Source
            else -> null
        })
    }

    fun openTerminal(peer: TailnetPeer) {
        navigation.open(if (requiresSshSettings(sshProfiles.contains(peer.hostId()))) {
            AppDestination.SshSettings(peer, PendingHostAction.TERMINAL)
        } else {
            AppDestination.Terminal(
                peer = peer,
                sessionKey = terminalSessions.newSessionKey(peer.hostId()),
                returnToHost = true,
            )
        })
    }

    fun reattachTerminal(session: TerminalSessionSummary) {
        navigation.open(
            AppSection.HOME,
            if (session.hostId.startsWith("traditional:")) AppDestination.TraditionalTerminal(
                TraditionalHost(session.hostId, session.hostDisplayName), session.peer, session.key,
            ) else AppDestination.Terminal(
                peer = session.peer,
                sessionKey = session.key,
                returnToHost = false,
            ),
        )
    }

    fun openFiles(peer: TailnetPeer) {
        navigation.open(if (requiresSshSettings(sshProfiles.contains(peer.hostId()))) {
            AppDestination.SshSettings(peer, PendingHostAction.FILES)
        } else {
            AppDestination.Files(peer)
        })
    }

    fun openHerdr(peer: TailnetPeer) {
        if (!BuildConfig.DEBUG) return
        navigation.open(tailnetHerdrDestination(peer, currentTailnetProfile, sshProfiles.contains(peer.hostId())))
    }

    BackHandler(enabled = destination != null, onBack = ::goBack)

    val browserModel: cc.galaxnet.novascale.browser.BrowserViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showTailnetSheet by remember { mutableStateOf(false) }
    var webAccessPeer by remember { mutableStateOf<TailnetPeer?>(null) }
    var replayOnboarding by rememberSaveable { mutableStateOf(false) }
    if (BuildConfig.DEBUG && replayOnboarding) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { replayOnboarding = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = NovaTheme.colors.backgroundPrimary) {
                OnboardingScreen(false, false) { replayOnboarding = false }
            }
        }
    }
    DisposableEffect(profiles, navigation, browserModel) {
        val registration = profiles.addInvalidationListener {
            herdrControllers.toMap().filterValues { it.controller.activeTransport == SshTransport.TAILNET }.forEach { (id, attachment) ->
                herdrControllers.remove(id)
                attachment.controller.close()
            }
            if (sftpTransport == SshTransport.TAILNET) {
                closeSftpSession?.invoke()
                closeSftpSession = null
                sftpContentFullScreen = false
            }
            browserModel.closeAll()
            webAccessPeer = null
            groupHost = null
            showHostFilter = false
            groupFilter = GROUP_FILTER_ALL
            // All windows drop destinations bound to the old peer snapshot. Direct
            // SSH windows stay attached to their application-owned sessions.
            val current = navigation.uiState.value.destination
            navigation.invalidateTailnetDestinations()
            val retainedDirect = when (current) {
                is AppDestination.TraditionalTerminal -> terminalSessions.sessions.value.any { it.key == current.sessionKey }
                is AppDestination.TraditionalHerdr -> herdrControllers.containsKey(current.host.id)
                is AppDestination.TraditionalFiles -> sftpTransport == SshTransport.DIRECT
                else -> false
            }
            if (!retainedDirect && current != AppDestination.Tailnets) {
                navigation.open(null)
            }
        }
        onDispose { registration.close() }
    }
    @Composable
    fun ProfileList(modifier: Modifier = Modifier.fillMaxSize(), showHeading: Boolean = true) {
        TailnetProfilesScreen(profilesState, tailnetState,
            pro = proState.hasPro, onReplace = profiles::replace, onUseFree = { profiles.activate(it, useAsFree = true) },
            onSelect = { showTailnetSheet = false; profiles.activate(it) },
            onRequestAdd = { billing.manager.allows(cc.galaxnet.novascale.billing.ProFeature.TAILNETS, profilesState.snapshot?.profiles?.size ?: 0) },
            onAdd = profiles::add, onRename = profiles::rename, onRemove = profiles::remove,
            onActiveDetails = {
                showTailnetSheet = false
                navigation.open(if (tailnetState is TailnetState.Running) AppDestination.TailnetDetails else AppDestination.Login)
            },
            onRetry = profiles::retry, modifier = modifier, showHeading = showHeading)
    }
    if (showTailnetSheet) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showTailnetSheet = false }, sheetState = sheetState) {
            ProfileList(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .8f))
        }
    }
    if (addingTraditionalHost) {
        // Keep form edge-scroll/fling gestures from dragging and rebounding the sheet.
        cc.galaxnet.novascale.ui.NovaModalSheet(onDismiss = { addingTraditionalHost = false }, gesturesEnabled = false) {
            TraditionalHostEditScreen(traditionalHosts, null, onDismiss = { addingTraditionalHost = false }, onSaved = { host ->
                addingTraditionalHost = false
                navigation.open(AppDestination.TraditionalHostDetail(host))
            })
        }
    }
    webAccessPeer?.let { peer ->
        cc.galaxnet.novascale.browser.HostWebAccessSheet(peer, onDismiss = { webAccessPeer = null }, onOpen = { url ->
            webAccessPeer = null
            browserModel.newTab(url)
            browserModel.active.pendingOpen = url
            navigation.selectSection(AppSection.BROWSER)
        })
    }
    var homeSessionsSelected by rememberSaveable { mutableStateOf(false) }
    var browserIsBrowsing by remember { mutableStateOf(false) }
    var browserWasOpened by remember { mutableStateOf(false) }
    var browserNavigationVisible by remember { mutableStateOf(false) }
    val browserVisible = section == AppSection.BROWSER && destination == null
    val terminalIsFullScreen = destination is AppDestination.Terminal || destination is AppDestination.TraditionalTerminal || destination is AppDestination.TraditionalHerdr || destination is AppDestination.Herdr
    val contentIsFullScreen = terminalIsFullScreen || (browserVisible && browserIsBrowsing && !browserNavigationVisible) ||
        ((destination is AppDestination.Files || destination is AppDestination.TraditionalFiles) && sftpContentFullScreen)

    Scaffold(
        containerColor = if (terminalIsFullScreen) {
            Color(adjustTerminalChromeBackground(terminalSettingsState.selectedTheme.background))
        } else {
            NovaTheme.colors.backgroundPrimary
        },
        topBar = {
            if (!contentIsFullScreen && section != AppSection.BROWSER) {
                NovaScaleTopBar(
                    title = destination.titleOrNull()
                        ?: if (section == AppSection.HOME && !homeSessionsSelected && groupFilter != GROUP_FILTER_ALL) cc.galaxnet.novascale.remote.hostFilterName(groupFilter, groupState.groups) else stringResource(section.screenTitleRes),
                    subtitle = if (section == AppSection.HOME && destination == null && (homeSessionsSelected || groupFilter == GROUP_FILTER_ALL)) profilesState.active?.name else null,
                    compact = section == AppSection.HOME && destination == null && !homeSessionsSelected && groupFilter != GROUP_FILTER_ALL,
                    showBack = destination != null,
                    onBack = ::goBack,
                    leading = {
                        if (section == AppSection.HOME && destination == null && !homeSessionsSelected) {
                            HostFilterToolbar(groupFilter, groupState.groups, showLabel = false) { showHostFilter = true }
                        }
                    },
                    actions = {
                        if (section == AppSection.HOME && destination == null) {
                            if (!homeSessionsSelected) IconButton(onClick = { addingTraditionalHost = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.traditional_host_add))
                            }
                            val statusDescription = stringResource(R.string.tailnets_status_description, profilesState.active?.name.orEmpty(), tailnetState.label())
                            IconButton(onClick = { showTailnetSheet = true },
                                modifier = Modifier.semantics { contentDescription = statusDescription }) {
                                if (profilesState.busy || tailnetState == TailnetState.Starting) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(NovaIcons.Server,
                                        contentDescription = null,
                                        tint = if (tailnetState is TailnetState.Running) NovaTheme.colors.statusConnected
                                            else NovaTheme.colors.statusDisconnected)
                                }
                            }
                        }
                        (destination as? AppDestination.Host)?.let { host ->
                            IconButton(onClick = { navigation.open(AppDestination.SshSettings(host.peer)) }) {
                                Icon(NovaIcons.Ssh, contentDescription = stringResource(R.string.feature_ssh_settings))
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!contentIsFullScreen) {
                NavigationBar(
                    modifier = Modifier.height(64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    containerColor = NovaTheme.colors.backgroundSecondary,
                    tonalElevation = 0.dp,
                ) {
                    AppSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { navigation.selectSection(item) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NovaTheme.colors.primary,
                                selectedTextColor = NovaTheme.colors.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = NovaTheme.colors.textSecondary,
                                unselectedTextColor = NovaTheme.colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            // Keep the browser owner alive across app sections. Hidden browser UI
            // detaches its WebView; the foreground proxy session retains the page.
            if (browserVisible || browserWasOpened) {
                SideEffect { browserWasOpened = true }
                cc.galaxnet.novascale.browser.BrowserScreen(
                    controller = proxyController,
                    model = browserModel,
                    onBrowsingChanged = { browserIsBrowsing = it },
                    visible = browserVisible,
                    appNavigationVisible = browserNavigationVisible,
                    onToggleAppNavigation = { browserNavigationVisible = !browserNavigationVisible },
                    onManageTailnets = { navigation.open(AppSection.SETTINGS, AppDestination.Tailnets) },
                )
            }
            when (val current = destination) {
                null -> when (section) {
                    AppSection.HOME -> HomeScreen(
                        tailnetState = tailnetState,
                        peers = tailnetPeers,
                        sessionsSelected = homeSessionsSelected,
                        onSelectSessions = { homeSessionsSelected = it },
                        peerMessage = tailnetUiState.peerMessage,
                        showOnlineOnly = tailnetUiState.showOnlineOnly,
                        terminalSessions = terminalSessionSummaries,
                        herdrSessions = herdrSummaries,
                        onHerdrSession = { id -> herdrControllers[id]?.let { navigation.open(it.destination) } },
                        onCloseHerdrSession = { id -> herdrControllers.remove(id)?.controller?.close() },
                        onLogin = { navigation.open(AppDestination.Login) },
                        onToggleOnlineOnly = tailnetViewModel::toggleOnlineOnly,
                        onTerminalSession = ::reattachTerminal,
                        onCloseTerminalSession = terminalSessions::closeGroup,
                        onHost = { navigation.open(AppDestination.Host(it)) },
                        traditionalHosts = savedTraditionalHosts,
                        traditionalStorageError = traditionalStorageError,
                        onAddTraditionalHost = { addingTraditionalHost = true },
                        onTraditionalHost = { navigation.open(AppDestination.TraditionalHostDetail(it)) },
                        groupState = groupState,
                        tailnetProfileId = currentTailnetProfile,
                        selectedGroupFilter = groupFilter,
                        groupStorageError = groupStorageError,
                        pinState = pinState,
                        pinStorageError = pinStorageError,
                    )
                    AppSection.BROWSER -> Unit
                    AppSection.SETTINGS -> SettingsScreen(
                        tailnetState = tailnetState,
                        preferences = preferences,
                        terminalAppearance = stringResource(
                            R.string.appearance_preview_summary,
                            terminalSettingsState.selectedTheme.localizedDisplayName(),
                            terminalSettingsState.settings.font.localizedDisplayName(),
                            terminalSettingsState.settings.cursorShape.localizedDisplayName(),
                        ),
                        onTailnetDetails = { navigation.open(AppDestination.Tailnets) },
                        onVersions = { navigation.open(AppDestination.Versions) },
                        onReplayOnboarding = { replayOnboarding = true },
                        onTerminalSettings = { navigation.open(AppDestination.TerminalSettings) },
                        filePreviewSettings = filePreviewSettings,
                        onFilePreviewSettings = { navigation.open(AppDestination.FilePreviewSettings) },
                        onProxySettings = { navigation.open(AppDestination.ProxySettings) },
                        onSource = { navigation.open(AppDestination.Source) },
                        onPro = { billing.manager.show() },
                    )
                }
                AppDestination.TailnetDetails -> TailnetDetailsScreen(
                    state = tailnetState,
                    onLogout = { profiles.logout() },
                    profile = profilesState.active,
                    operationBusy = profilesState.busy,
                    onControlServer = { navigation.open(AppDestination.Tailnets) },
                    onLogin = { navigation.open(AppDestination.Login) },
                )
                AppDestination.Versions -> VersionsScreen()
                AppDestination.ProxySettings -> cc.galaxnet.novascale.browser.ProxySettingsScreen(preferences, proxyController)
                AppDestination.Login -> TailnetLoginScreen(
                    tailnet = tailnetBackend,
                    tailnetState = tailnetState,
                    profile = profilesState.active,
                    profiles = profiles,
                    onManage = { navigation.open(AppDestination.Tailnets) },
                    returnOnConnected = true,
                    onConnected = {
                        navigation.resetToHome()
                    },
                )
                AppDestination.Tailnets -> ProfileList(showHeading = false)
                is AppDestination.Host -> HostDetailScreen(
                    peer = current.peer,
                    onTerminal = { openTerminal(current.peer) },
                    onFiles = { openFiles(current.peer) },
                    onHerdr = if (BuildConfig.DEBUG) ({ openHerdr(current.peer) }) else null,
                    onPing = { navigation.open(AppDestination.Ping(current.peer)) },
                    onWeb = { webAccessPeer = current.peer
                    },
                    onManageGroups = {
                        groupHost = GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId) to current.peer.displayName
                    },
                    groupNames = groupState.groups.filter { it.id in groupState.memberships[GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId)].orEmpty() }.map { it.name },
                    pinned = GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId)?.let { key -> pinState.pins.any { it.key == key } } == true,
                    pinEnabled = pinState.loaded && !pinStorageError && GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId)?.let { pinPending[it] != true } == true,
                    pinError = pinStorageError,
                    pinUnavailable = GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId) == null,
                    onTogglePin = { GroupHostKey.tailnet(currentTailnetProfile.orEmpty(), current.peer.stableNodeId)?.let(::togglePin) },
                )
                is AppDestination.TraditionalHostEdit -> TraditionalHostEditScreen(
                    repository = traditionalHosts,
                    host = current.host,
                    onSaved = { host ->
                        herdrControllers.remove(host.id)?.controller?.close()
                        navigation.open(AppDestination.TraditionalHostDetail(host))
                    },
                )
                is AppDestination.TraditionalHostDetail -> TraditionalHostDetailScreen(
                    host = current.host,
                    repository = traditionalHosts,
                    onEdit = { navigation.open(AppDestination.TraditionalHostEdit(current.host)) },
                    onManageGroups = { groupHost = GroupHostKey.traditional(current.host.id) to current.host.name },
                    groupNames = groupState.groups.filter { it.id in groupState.memberships[GroupHostKey.traditional(current.host.id)].orEmpty() }.map { it.name },
                    pinned = pinState.pins.any { it.key == GroupHostKey.traditional(current.host.id) },
                    pinEnabled = pinState.loaded && !pinStorageError && pinPending[GroupHostKey.traditional(current.host.id)] != true,
                    pinError = pinStorageError,
                    onTogglePin = { togglePin(GroupHostKey.traditional(current.host.id)) },
                    onDelete = {
                        herdrScope.launch {
                            herdrControllers.remove(current.host.id)?.controller?.close()
                            terminalSessions.sessions.value.filter { it.hostId == current.host.id }.forEach { terminalSessions.closeGroup(it.key) }
                            runCatching { traditionalHosts.delete(current.host) }
                                .onSuccess { navigation.open(null) }
                                .onFailure {
                                    android.widget.Toast.makeText(context, R.string.traditional_host_save_error, android.widget.Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onTerminal = { config ->
                        val peer = current.host.asPeer(config)
                        navigation.open(AppDestination.TraditionalTerminal(current.host, peer, terminalSessions.newSessionKey(current.host.id)))
                    },
                    onFiles = { config -> navigation.open(AppDestination.TraditionalFiles(current.host, current.host.asPeer(config))) },
                    onHerdr = { config ->
                        if (BuildConfig.DEBUG) navigation.open(AppDestination.TraditionalHerdr(current.host, current.host.asPeer(config)))
                    },
                )
                is AppDestination.TraditionalTerminal -> TraditionalConfigContent(current.host, traditionalHosts) { config -> TerminalScreen(
                    terminalSessions = terminalSessions,
                    sessionKey = current.sessionKey,
                    hostId = current.host.id,
                    peer = current.peer,
                    tailnetState = tailnetState,
                    initialConfig = config,
                    terminalSettings = terminalSettingsState.settings,
                    terminalTheme = terminalSettingsState.selectedTheme,
                    onTerminalFontSize = terminalSettingsViewModel::setFontSize,
                    onExit = { navigation.open(AppDestination.TraditionalHostDetail(current.host)) },
                    onOpenNewWindow = { context.startActivity(TerminalWindowIntent.create(context, current.peer, current.sessionKey)) },
                    onEditSshSettings = { navigation.open(AppDestination.TraditionalHostEdit(current.host)) },
                ) }
                is AppDestination.TraditionalFiles -> TraditionalConfigContent(current.host, traditionalHosts) { config -> SftpScreen(
                    tailnet = tailnetBackend,
                    tailnetState = tailnetState,
                    initialConfig = config,
                    previewSettings = filePreviewSettings,
                    onSessionCloser = { closeSftpSession = it; sftpTransport = config.transport },
                    onContentFullScreen = { sftpContentFullScreen = it },
                    onEditSshSettings = { navigation.open(AppDestination.TraditionalHostEdit(current.host)) },
                ) }
                is AppDestination.TraditionalHerdr -> if (BuildConfig.DEBUG) TraditionalConfigContent(current.host, traditionalHosts) { config ->
                    val controller = remember(current.host.id) {
                        herdrControllers.getOrPut(current.host.id) { HerdrAttachment((context.applicationContext as NovaScaleApplication).createHerdrController(current.host.id), current, current.host.name) }.controller
                    }
                    HerdrScreen(
                        controller = controller,
                        config = config,
                        hostName = current.host.name,
                        settings = terminalSettingsState.settings,
                        theme = terminalSettingsState.selectedTheme,
                        onDetach = { navigation.open(AppDestination.TraditionalHostDetail(current.host)) },
                        onClose = {
                            herdrControllers.remove(current.host.id)?.controller?.close()
                            navigation.open(AppDestination.TraditionalHostDetail(current.host))
                        },
                    )
                }
                is AppDestination.Herdr -> if (BuildConfig.DEBUG) {
                    val controller = remember(current.controllerKey) {
                        herdrControllers.getOrPut(current.controllerKey) { HerdrAttachment((context.applicationContext as NovaScaleApplication).createHerdrController(current.controllerKey), current, current.peer.displayName) }.controller
                    }
                    HerdrScreen(
                        controller = controller,
                        config = savedConfiguration(current.peer, currentTailnetProfile),
                        hostName = current.peer.displayName,
                        settings = terminalSettingsState.settings,
                        theme = terminalSettingsState.selectedTheme,
                        onDetach = { navigation.open(AppDestination.Host(current.peer)) },
                        onClose = {
                            herdrControllers.remove(current.controllerKey)?.controller?.close()
                            navigation.open(AppDestination.Host(current.peer))
                        },
                    )
                }
                is AppDestination.Ping -> TailnetPingScreen(
                    tailnet = tailnetBackend,
                    peer = current.peer,
                )
                is AppDestination.Terminal -> TerminalScreen(
                    terminalSessions = terminalSessions,
                    sessionKey = current.sessionKey,
                    hostId = current.peer.hostId(),
                    peer = current.peer,
                    tailnetState = tailnetState,
                    initialConfig = savedConfiguration(current.peer, currentTailnetProfile),
                    terminalSettings = terminalSettingsState.settings,
                    terminalTheme = terminalSettingsState.selectedTheme,
                    onTerminalFontSize = terminalSettingsViewModel::setFontSize,
                    onExit = {
                        navigation.open(if (current.returnToHost) AppDestination.Host(current.peer) else null)
                    },
                    onOpenNewWindow = {
                        context.startActivity(
                            TerminalWindowIntent.create(context, current.peer, current.sessionKey),
                        )
                    },
                    onEditSshSettings = {
                        navigation.open(
                            AppDestination.SshSettings(
                                peer = current.peer,
                                continueTo = PendingHostAction.TERMINAL,
                                terminalSessionKey = current.sessionKey,
                                terminalReturnToHost = current.returnToHost,
                            ),
                        )
                    },
                )
                is AppDestination.Files -> SftpScreen(
                    tailnet = tailnetBackend,
                    tailnetState = tailnetState,
                    initialConfig = savedConfiguration(current.peer, currentTailnetProfile),
                    previewSettings = filePreviewSettings,
                    onSessionCloser = { closeSftpSession = it; sftpTransport = SshTransport.TAILNET },
                    onContentFullScreen = { sftpContentFullScreen = it },
                    onEditSshSettings = {
                        navigation.open(AppDestination.SshSettings(current.peer, PendingHostAction.FILES))
                    },
                )
                is AppDestination.SshSettings -> SshSettingsScreen(
                    peer = current.peer,
                    profileId = currentTailnetProfile,
                    onSaved = {
                        herdrControllers.remove(tailnetHerdrKey(currentTailnetProfile, current.peer))?.controller?.close()
                        navigation.open(when (current.continueTo) {
                            PendingHostAction.TERMINAL -> AppDestination.Terminal(
                                peer = current.peer,
                                sessionKey = current.terminalSessionKey
                                    ?: terminalSessions.newSessionKey(current.peer.hostId()),
                                returnToHost = current.terminalReturnToHost,
                            )
                            PendingHostAction.FILES -> AppDestination.Files(current.peer)
                            PendingHostAction.HERDR -> if (BuildConfig.DEBUG) {
                                tailnetHerdrDestination(current.peer, currentTailnetProfile, hasSavedProfile = true)
                            } else AppDestination.Host(current.peer)
                            PendingHostAction.NONE -> AppDestination.Host(current.peer)
                        })
                    },
                )
                AppDestination.TerminalSettings -> TerminalSettingsScreen(
                    state = terminalSettingsState,
                    onTheme = terminalSettingsViewModel::selectTheme,
                    onFont = terminalSettingsViewModel::selectFont,
                    onFontSize = terminalSettingsViewModel::setFontSize,
                    onCursorShape = terminalSettingsViewModel::selectCursorShape,
                    onImportItermTheme = terminalSettingsViewModel::importItermTheme,
                    onRemoveImportedTheme = terminalSettingsViewModel::removeImportedTheme,
                    onReset = terminalSettingsViewModel::reset,
                    onHerdrImproveTextContrast = terminalSettingsViewModel::setHerdrImproveTextContrast,
                )
                AppDestination.FilePreviewSettings -> FilePreviewSettingsScreen(
                    settings = filePreviewSettings,
                    onTapToPreview = filePreviewSettingsViewModel::setTapToPreview,
                    onSizeLimit = filePreviewSettingsViewModel::setSizeLimit,
                )
                AppDestination.Source -> SourceScreen(
                    onDocument = { navigation.open(AppDestination.LegalDocument(it)) },
                )
                is AppDestination.LegalDocument -> LegalDocumentScreen(current.document)
            }
        }
    }
}

internal fun requiresSshSettings(hasSavedProfile: Boolean): Boolean = !hasSavedProfile

internal fun tailnetHerdrKey(profileId: String?, peer: TailnetPeer): String =
    "tailnet:${profileId.orEmpty()}:${peer.hostId()}"

internal fun tailnetHerdrDestination(peer: TailnetPeer, profileId: String?, hasSavedProfile: Boolean): AppDestination =
    if (requiresSshSettings(hasSavedProfile)) AppDestination.SshSettings(peer, PendingHostAction.HERDR)
    else AppDestination.Herdr(peer, tailnetHerdrKey(profileId, peer))

@Composable
internal fun NovaScaleTopBar(
    title: String, showBack: Boolean, onBack: () -> Unit,
    subtitle: String? = null,
    compact: Boolean = false,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = NovaTheme.colors.backgroundPrimary,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = if (compact) 48.dp else 56.dp)
                .padding(horizontal = NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        NovaIcons.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            } else leading()
            Column(Modifier.weight(1f).padding(horizontal = NovaSpacing.sm, vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = NovaTheme.colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            actions()
        }
    }
}

@Composable
internal fun HomeScreen(
    tailnetState: TailnetState,
    peers: List<TailnetPeer>,
    sessionsSelected: Boolean,
    onSelectSessions: (Boolean) -> Unit,
    peerMessage: UiText?,
    showOnlineOnly: Boolean,
    terminalSessions: List<TerminalSessionSummary>,
    onLogin: () -> Unit,
    onToggleOnlineOnly: () -> Unit,
    onTerminalSession: (TerminalSessionSummary) -> Unit,
    onCloseTerminalSession: (String) -> Unit,
    onHost: (TailnetPeer) -> Unit,
    traditionalHosts: List<TraditionalHost> = emptyList(),
    traditionalStorageError: Boolean = false,
    onAddTraditionalHost: () -> Unit = {},
    onTraditionalHost: (TraditionalHost) -> Unit = {},
    groupState: HostGroupsState = HostGroupsState(),
    tailnetProfileId: String? = null,
    groupStorageError: Boolean = false,
    selectedGroupFilter: String = GROUP_FILTER_ALL,
    pinState: HostPinsState = HostPinsState(),
    pinStorageError: Boolean = false,
    herdrSessions: List<HerdrSessionSummary> = emptyList(),
    onHerdrSession: (String) -> Unit = {},
    onCloseHerdrSession: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val groupFilter = selectedGroupFilter
    val hostListState = rememberLazyListState()
    LaunchedEffect(query, groupFilter, sessionsSelected) { hostListState.scrollToItem(0) }
    val visiblePeers = remember(peers, showOnlineOnly, groupState, groupFilter, tailnetProfileId, query) {
        visibleTailnetPeers(peers, showOnlineOnly).filter { peer ->
            val key = GroupHostKey.tailnet(tailnetProfileId.orEmpty(), peer.stableNodeId)
            hostMatchesGroupFilter(groupFilter, true, key?.let { groupState.memberships[it] }.orEmpty()) &&
                hostMatchesSearch(query, peer.displayName, peer.dnsName.orEmpty(), *peer.addresses.toTypedArray())
        }
    }
    val visibleTraditionalHosts = remember(traditionalHosts, groupState, groupFilter, query) {
        traditionalHosts.filter { host ->
            hostMatchesGroupFilter(groupFilter, false, groupState.memberships[GroupHostKey.traditional(host.id)].orEmpty()) &&
                hostMatchesSearch(query, host.name, host.address, host.endpoint)
        }
    }
    val pinnedKeys = remember(pinState.pins) { pinState.pins.map { it.key }.toSet() }
    val pinnedTraditional = if (groupFilter == GROUP_FILTER_ALL) {
        visibleTraditionalHosts.filter { GroupHostKey.traditional(it.id) in pinnedKeys }
    } else emptyList()
    val pinnedPeers = if (groupFilter == GROUP_FILTER_ALL && tailnetState is TailnetState.Running) {
        visiblePeers.filter { GroupHostKey.tailnet(tailnetProfileId.orEmpty(), it.stableNodeId) in pinnedKeys }
    } else emptyList()
    val remainingTraditional = if (pinnedTraditional.isEmpty()) visibleTraditionalHosts else
        visibleTraditionalHosts.filterNot { GroupHostKey.traditional(it.id) in pinnedKeys }
    val remainingPeers = if (pinnedPeers.isEmpty()) visiblePeers else
        visiblePeers.filterNot { GroupHostKey.tailnet(tailnetProfileId.orEmpty(), it.stableNodeId) in pinnedKeys }
    var pendingCloseHerdr by remember { mutableStateOf<HerdrSessionSummary?>(null) }
    pendingCloseHerdr?.let { session ->
        AlertDialog(onDismissRequest = { pendingCloseHerdr = null },
            title = { Text(stringResource(R.string.herdr_close_title)) },
            text = { Text(stringResource(R.string.herdr_close_description)) },
            confirmButton = { TextButton(onClick = { pendingCloseHerdr = null; onCloseHerdrSession(session.key) }) {
                Text(stringResource(R.string.herdr_close), color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { pendingCloseHerdr = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    var pendingCloseSession by remember { mutableStateOf<TerminalSessionSummary?>(null) }
    pendingCloseSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingCloseSession = null },
            title = {
                Text(
                    stringResource(
                        R.string.close_named_session_title,
                        session.hostDisplayName,
                    ),
                )
            },
            text = {
                Text(
                    if (session.tabCount > 1) {
                        stringResource(
                            R.string.close_multiple_terminals_description,
                            session.tabCount,
                        )
                    } else {
                        stringResource(R.string.close_single_terminal_description)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCloseSession = null
                        onCloseTerminalSession(session.key)
                    },
                ) {
                    Text(
                        stringResource(R.string.action_close),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseSession = null }) {
                    Text(stringResource(R.string.keep_session))
                }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (sessionsSelected) 1 else 0, containerColor = Color.Transparent) {
            Tab(selected = !sessionsSelected, onClick = { onSelectSessions(false) },
                text = { Text(stringResource(R.string.section_hosts)) })
            Tab(selected = sessionsSelected, onClick = { onSelectSessions(true) },
                text = { Text(stringResource(R.string.home_sessions)) })
        }
        if (!sessionsSelected) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true, label = { Text(stringResource(R.string.hosts_search)) },
                placeholder = { Text(stringResource(R.string.hosts_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.hosts_search_clear))
                } })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = hostListState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sessionsSelected) {
                item { NovaSectionLabel(stringResource(R.string.section_active_sessions), Modifier.padding(top = 12.dp)) }
                if (terminalSessions.isEmpty() && herdrSessions.isEmpty()) item {
                    NovaGroupedCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detached_sessions_empty), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = NovaTheme.colors.textSecondary)
                    }
                }
                items(herdrSessions, key = { "herdr:" + it.key }) { session ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { onHerdrSession(session.key) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Widgets, null, tint = NovaTheme.colors.menuGreen)
                                Column(Modifier.weight(1f)) {
                                    Text(session.hostName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.herdr_title) + " · " + session.sessionName,
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(if (session.connected) R.string.herdr_session_running else if (session.busy) R.string.terminal_status_preparing else R.string.herdr_reconnect),
                                        style = MaterialTheme.typography.labelSmall, color = NovaTheme.colors.textSecondary)
                                }
                            }
                            IconButton(onClick = { pendingCloseHerdr = session }) { Icon(Icons.Outlined.Close, stringResource(R.string.herdr_close)) }
                        }
                    }
                }
                items(terminalSessions, key = { "terminal:" + it.key }) { session ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) {
                        ActiveTerminalSessionRow(session, onOpen = { onTerminalSession(session) }, onClose = { pendingCloseSession = session })
                    }
                }
            } else {
                if (groupStorageError) item { Text(stringResource(R.string.host_groups_storage_error), color = MaterialTheme.colorScheme.error) }
                if (pinStorageError) item { Text(stringResource(R.string.host_pin_storage_error), color = MaterialTheme.colorScheme.error) }
                if (traditionalStorageError) item { Text(stringResource(R.string.traditional_host_load_error), color = MaterialTheme.colorScheme.error) }
                if (tailnetState is TailnetState.Running && groupFilter != GROUP_FILTER_TRADITIONAL) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.home_online_nodes, peers.count { it.online }, peers.count { it.online }),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
                        FilterChip(selected = showOnlineOnly, onClick = onToggleOnlineOnly,
                            label = { Text(stringResource(R.string.online_only)) })
                    }
                }
                if (pinnedTraditional.isNotEmpty() || pinnedPeers.isNotEmpty()) item { NovaSectionLabel(stringResource(R.string.host_pinned)) }
                items(pinnedTraditional, key = { "pin:" + it.id }) { host ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) { TraditionalHostRows(listOf(host), onAddTraditionalHost, onTraditionalHost, showAdd = false, pinned = true) }
                }
                items(pinnedPeers, key = { "pin:" + it.hostId() }) { peer ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) { PeerRow(peer, onClick = { onHost(peer) }, pinned = true) }
                }
                if (remainingTraditional.isNotEmpty()) item { NovaSectionLabel(stringResource(R.string.host_groups_traditional), Modifier.padding(top = 8.dp)) }
                items(remainingTraditional, key = { it.id }) { host ->
                    NovaGroupedCard(Modifier.fillMaxWidth()) { TraditionalHostRows(listOf(host), onAddTraditionalHost, onTraditionalHost, showAdd = false) }
                }
                if (tailnetState is TailnetState.Running && remainingPeers.isNotEmpty()) {
                    item { NovaSectionLabel(stringResource(R.string.host_groups_tailnet), Modifier.padding(top = 8.dp)) }
                    items(remainingPeers, key = { it.hostId() }) { peer ->
                        NovaGroupedCard(Modifier.fillMaxWidth()) { PeerRow(peer, onClick = { onHost(peer) }) }
                    }
                }
                val noMatches = visibleTraditionalHosts.isEmpty() && (tailnetState !is TailnetState.Running || visiblePeers.isEmpty())
                if (noMatches && (query.isNotBlank() || groupFilter != GROUP_FILTER_ALL || tailnetState is TailnetState.Running)) item {
                    NovaGroupedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Search, null, tint = NovaTheme.colors.textSecondary)
                            Text(stringResource(if (query.isNotBlank()) R.string.hosts_search_empty else R.string.host_groups_no_matching_hosts),
                                color = NovaTheme.colors.textSecondary)
                            if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text(stringResource(R.string.hosts_search_clear)) }
                            else if (showOnlineOnly && peers.any { !it.online } && tailnetState is TailnetState.Running && groupFilter != GROUP_FILTER_TRADITIONAL) {
                                TextButton(onClick = onToggleOnlineOnly) { Text(stringResource(R.string.show_offline_hosts)) }
                            } else if (traditionalHosts.isEmpty()) TextButton(onClick = onAddTraditionalHost) { Text(stringResource(R.string.traditional_host_add)) }
                        }
                    }
                }
                if (tailnetState !is TailnetState.Running && groupFilter != GROUP_FILTER_TRADITIONAL) item {
                    SignedOutHosts(tailnetState, onLogin, onAddHost = onAddTraditionalHost.takeIf { traditionalHosts.isEmpty() && groupFilter == GROUP_FILTER_ALL && query.isBlank() })
                }
                peerMessage?.let { message -> item { Text(message.asString(), color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

internal fun visibleTailnetPeers(
    peers: List<TailnetPeer>,
    showOnlineOnly: Boolean,
): List<TailnetPeer> = if (showOnlineOnly) peers.filter(TailnetPeer::online) else peers

@Composable
private fun ActiveTerminalSessionRow(
    session: TerminalSessionSummary,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val startedAt = remember(session.startedAtEpochMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(session.startedAtEpochMillis))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onOpen)
            .padding(start = NovaSpacing.md, end = NovaSpacing.xs, top = NovaSpacing.xs, bottom = NovaSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NovaIcons.Terminal,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NovaTheme.colors.menuPrimary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
        ) {
            Text(
                session.hostDisplayName,
                style = MaterialTheme.typography.titleMedium,
                color = NovaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.session_started_summary,
                    startedAt,
                    terminalTabCountLabel(session.tabCount),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NovaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                NovaIcons.Close,
                contentDescription = stringResource(
                    R.string.cd_close_named_terminal,
                    session.hostDisplayName,
                ),
                tint = NovaTheme.colors.textSecondary,
            )
        }
        Icon(
            NovaIcons.ChevronRight,
            contentDescription = null,
            tint = NovaTheme.colors.textSecondary,
        )
    }
}

@Composable
internal fun terminalTabCountLabel(tabCount: Int): String =
    pluralStringResource(R.plurals.terminal_tab_count, tabCount, tabCount)

@Composable
private fun TailnetStatusCard(state: TailnetState) {
    NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = NovaSpacing.md, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (state) {
                    is TailnetState.Running -> NovaTheme.colors.statusConnected
                    TailnetState.Starting -> NovaTheme.colors.statusWarning
                    is TailnetState.Failed -> NovaTheme.colors.statusDisconnected
                    else -> NovaTheme.colors.textSecondary
                }
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(
                    state.label(),
                    modifier = Modifier.padding(start = NovaSpacing.sm),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            when (state) {
                is TailnetState.Failed -> Text(
                    state.redactedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.statusDisconnected,
                )
                is TailnetState.LoginRequired -> Text(
                    stringResource(R.string.complete_auth_to_load_hosts),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun PeerRow(peer: TailnetPeer, onClick: () -> Unit, pinned: Boolean = false) {
    HostIdentityRow(peer.displayName, peer.addresses.firstOrNull() ?: peer.dnsName ?: stringResource(R.string.no_tailnet_address),
        os = peer.os, online = peer.online, pinned = pinned, onClick = onClick)
}

@Composable
internal fun HostDetailScreen(
    peer: TailnetPeer,
    onTerminal: () -> Unit,
    onFiles: () -> Unit,
    onPing: () -> Unit,
    onWeb: () -> Unit,
    onHerdr: (() -> Unit)? = null,
    onManageGroups: (() -> Unit)? = null,
    pinned: Boolean = false,
    pinEnabled: Boolean = false,
    pinUnavailable: Boolean = false,
    pinError: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    groupNames: List<String> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        NovaGroupedCard(Modifier.fillMaxWidth()) {
            HostIdentityRow(peer.displayName, peer.dnsName ?: peer.addresses.firstOrNull() ?: stringResource(R.string.no_tailnet_address),
                os = peer.os, online = peer.online,
                route = stringResource(if (peer.online) R.string.status_connected else R.string.status_disconnected))
        }
        NovaSectionLabel(stringResource(R.string.section_actions))
        HostActionGrid(listOf(
            HostTile(R.string.feature_terminal, "SSH", NovaIcons.Terminal, NovaTheme.colors.menuPrimary, onTerminal),
        ) + (if (BuildConfig.DEBUG && onHerdr != null) listOf(
            HostTile(R.string.herdr_title, stringResource(R.string.herdr_action_description), Icons.Outlined.Widgets, NovaTheme.colors.menuGreen, onHerdr),
        ) else emptyList()) + listOf(
            HostTile(R.string.host_web_access, "HTTP / HTTPS", androidx.compose.material.icons.Icons.Outlined.Language, NovaTheme.colors.menuGreen, onWeb),
            HostTile(R.string.feature_files, "SFTP", NovaIcons.Files, NovaTheme.colors.menuFile, onFiles),
            HostTile(R.string.feature_ping, stringResource(R.string.host_diagnostics), NovaIcons.Ping, NovaTheme.colors.statusInfo, onPing),
        ))
        HostOrganizationCard(groupNames, onManageGroups, pinned, pinEnabled, if (pinUnavailable) null else onTogglePin)
        if (pinError) Text(stringResource(R.string.host_pin_storage_error), color = MaterialTheme.colorScheme.error)
        if (pinUnavailable) Text(stringResource(R.string.host_pin_unavailable), color = NovaTheme.colors.textSecondary)
    }
}

internal data class HostTile(val title: Int, val subtitle: String, val icon: ImageVector, val tint: Color, val action: () -> Unit)

@Composable
internal fun HostActionGrid(items: List<HostTile>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth.value + 12) / 156).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile ->
                        Surface(onClick = tile.action, modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = MaterialTheme.shapes.medium, color = NovaTheme.colors.backgroundSecondary,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NovaTheme.colors.borderSubtle)) {
                            Column(Modifier.heightIn(min = 108.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Icon(tile.icon, null, tint = tile.tint)
                                    Icon(NovaIcons.ChevronRight, null, tint = NovaTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                                Text(stringResource(tile.title), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun TailnetPingScreen(
    tailnet: TailnetBackend,
    peer: TailnetPeer,
) {
    val address = peer.addresses.firstOrNull()
    if (address == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(NovaSpacing.screenMargin),
            contentAlignment = Alignment.Center,
        ) {
            NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.ping_missing_address),
                    modifier = Modifier.padding(NovaSpacing.md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaTheme.colors.textSecondary,
                )
            }
        }
        return
    }

    val factory = remember(tailnet, address) {
        TailnetPingViewModel.Factory(tailnet, address)
    }
    val pingViewModel: TailnetPingViewModel = viewModel(
        key = "tailnet-ping-${peer.hostId()}-$address",
        factory = factory,
    )
    val state by pingViewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(pingViewModel) {
        pingViewModel.start()
        onDispose { pingViewModel.stop() }
    }

    TailnetPingContent(
        peer = peer,
        address = address,
        state = state,
        onRetry = pingViewModel::retry,
    )
}

@Composable
private fun TailnetPingContent(
    peer: TailnetPeer,
    address: String,
    state: TailnetPingUiState,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val pathResult = state.latestPathResult
    val directEndpointClipboardLabel = stringResource(R.string.direct_endpoint)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(NovaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NovaSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovaPlatformTile(peer.os)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
                    ) {
                        Text(
                            stringResource(R.string.pinging_host, peer.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            color = NovaTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            address,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = NovaTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            state.successfulLatencies.lastOrNull()?.latencyText()
                                ?: stringResource(R.string.waiting_for_response),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            color = NovaTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                        if (state.isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(top = NovaSpacing.xs)
                                    .size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
                ) {
                    TailnetPathBadge(pathResult)
                    Text(
                        if (state.currentRound == 0) {
                            stringResource(R.string.waiting_for_response)
                        } else {
                            stringResource(
                                R.string.ping_round,
                                state.currentRound,
                                state.totalRounds,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRetry, enabled = !state.isRunning) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                pathResult?.directEndpoint?.let { endpoint ->
                    HorizontalDivider(color = NovaTheme.colors.borderSubtle)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.direct_endpoint),
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaTheme.colors.textSecondary,
                            )
                            Text(
                                endpoint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = NovaTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        TextButton(
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            directEndpointClipboardLabel,
                                            endpoint,
                                        ),
                                    )
                            },
                        ) {
                            Text(stringResource(R.string.action_copy))
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm)) {
            PingStatCell(
                stringResource(R.string.ping_stat_last),
                state.successfulLatencies.lastOrNull()?.latencyText() ?: "—",
            )
            PingStatCell(
                stringResource(R.string.ping_stat_average),
                state.successfulLatencies.takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.latencyText()
                    ?: "—",
            )
            PingStatCell(
                stringResource(R.string.ping_stat_minimum),
                state.successfulLatencies.minOrNull()?.latencyText() ?: "—",
            )
            PingStatCell(
                stringResource(R.string.ping_stat_maximum),
                state.successfulLatencies.maxOrNull()?.latencyText() ?: "—",
            )
        }

        PingLatencyChart(state.successfulLatencies)

        if (
            state.samples.isNotEmpty() &&
            state.successfulLatencies.isEmpty() &&
            state.latestResult?.failure != null
        ) {
            val failureText = when (state.latestResult?.failure) {
                TailnetPingFailure.TIMEOUT ->
                    stringResource(R.string.ping_timeout, peer.displayName)
                else -> stringResource(R.string.ping_no_answer)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = NovaTheme.colors.statusDisconnected.copy(alpha = 0.12f),
            ) {
                Column(
                    modifier = Modifier.padding(NovaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.ping_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaTheme.colors.textPrimary,
                    )
                    Text(
                        failureText,
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTheme.colors.textSecondary,
                    )
                }
            }
        }

        if (state.samples.isNotEmpty()) {
            NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    state.samples.forEachIndexed { index, sample ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "#${sample.sequence}",
                                modifier = Modifier.size(width = 32.dp, height = 20.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = NovaTheme.colors.textSecondary,
                            )
                            NovaStatusDot(sample.result.isReachable)
                            Text(
                                sample.result.latencyMilliseconds?.latencyText()
                                    ?: stringResource(R.string.ping_failed),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = NovaTheme.colors.textPrimary,
                                maxLines = 1,
                            )
                            Text(
                                sample.result.path?.localizedDisplayName(sample.result.relayRegion)
                                    ?: stringResource(R.string.route_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = NovaTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index != state.samples.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 64.dp),
                                color = NovaTheme.colors.borderSubtle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TailnetPathBadge(result: cc.galaxnet.novascale.core.TailnetPingResult?) {
    val path = result?.path
    val color = when (path) {
        TailnetConnectionPath.DIRECT -> NovaTheme.colors.statusConnected
        TailnetConnectionPath.PEER_RELAY -> NovaTheme.colors.statusInfo
        TailnetConnectionPath.DERP_RELAY -> NovaTheme.colors.statusWarning
        null -> NovaTheme.colors.textSecondary
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NovaSpacing.sm, vertical = NovaSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(
                path?.localizedDisplayName(result.relayRegion)
                    ?: stringResource(R.string.checking_route),
                style = MaterialTheme.typography.labelSmall,
                color = NovaTheme.colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowScope.PingStatCell(title: String, value: String) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        color = NovaTheme.colors.backgroundSecondary,
        border = androidx.compose.foundation.BorderStroke(1.dp, NovaTheme.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NovaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = NovaTheme.colors.textSecondary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = NovaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PingLatencyChart(values: List<Double>) {
    val lineColor = NovaTheme.colors.primary
    val gridColor = NovaTheme.colors.borderSubtle
    NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(144.dp)
                .padding(NovaSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                repeat(5) { index ->
                    val y = size.height * index / 4f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (values.isNotEmpty()) {
                    val minimum = values.minOrNull() ?: 0.0
                    val maximum = values.maxOrNull() ?: minimum
                    val range = (maximum - minimum).coerceAtLeast(1.0)
                    val points = values.mapIndexed { index, value ->
                        val x = if (values.size == 1) {
                            size.width / 2f
                        } else {
                            size.width * index / (values.size - 1).toFloat()
                        }
                        val y = size.height - (((value - minimum) / range).toFloat() * size.height)
                        Offset(x, y.coerceIn(0f, size.height))
                    }
                    if (points.size > 1) {
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                    }
                    points.forEach { point ->
                        drawCircle(
                            color = lineColor,
                            radius = 3.dp.toPx(),
                            center = point,
                        )
                    }
                }
            }
            if (values.isEmpty()) {
                Text(
                    stringResource(R.string.no_successful_pings),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Double.latencyText(): String =
    stringResource(R.string.latency_milliseconds, this)

@Composable
private fun TailnetConnectionPath.localizedDisplayName(region: String? = null): String =
    when (this) {
        TailnetConnectionPath.DIRECT -> stringResource(R.string.connection_direct)
        TailnetConnectionPath.PEER_RELAY -> stringResource(R.string.connection_peer_relay)
        TailnetConnectionPath.DERP_RELAY -> region
            ?.takeIf { it.isNotBlank() }
            ?.let {
                stringResource(
                    R.string.connection_derp_relay_region,
                    it.uppercase(Locale.ROOT),
                )
            }
            ?: stringResource(R.string.connection_derp_relay)
    }

@Composable
private fun HostActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    iconColor: Color = NovaTheme.colors.primary,
) {
    NovaGroupedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        NovaActionRow(
            title = title,
            description = description,
            icon = icon,
            iconColor = iconColor,
        )
    }
}

@Composable
internal fun SshSettingsScreen(peer: TailnetPeer, profileId: String? = null, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context, profileId) { SshProfileStore(context, profileId) }
    val hostId = peer.hostId()
    val defaultConfig = remember(peer) { RemoteConnectionConfig(host = peer.sshHost(), username = "root", tailnetProfileId = profileId) }
    var config by remember(peer) { mutableStateOf(defaultConfig) }
    var loaded by remember(peer) { mutableStateOf(false) }
    var saved by remember(peer) { mutableStateOf(store.contains(hostId)) }
    var message by remember(peer) { mutableStateOf<UiText?>(null) }
    var messageIsError by remember(peer) { mutableStateOf(false) }

    LaunchedEffect(peer) {
        config = withContext(Dispatchers.IO) { store.load(hostId) } ?: defaultConfig
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        Column {
            NovaSectionLabel(stringResource(R.string.section_host_information))
            NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NovaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
                ) {
                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        peer.sshHost(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = NovaTheme.colors.textSecondary,
                    )
                }
            }
        }
        Column {
            NovaSectionLabel(stringResource(R.string.section_ssh_configuration))
            NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(NovaSpacing.md)) {
                    RemoteConnectionForm(
                        config = config,
                        onConfigChange = { config = it },
                        peers = emptyList(),
                        enabled = loaded,
                        connectLabel = stringResource(R.string.save_settings),
                        showHostField = false,
                        onConnect = {
                            val validation = config.validate()
                            if (validation != null) {
                                message = validation
                                messageIsError = true
                            } else {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { store.save(hostId, config) } }
                                        .onSuccess {
                                            saved = true
                                            messageIsError = false
                                            message = uiText(R.string.ssh_settings_saved)
                                            onSaved()
                                        }
                                        .onFailure {
                                            messageIsError = true
                                            message = uiText(R.string.ssh_settings_save_failed)
                                        }
                                }
                            }
                        },
                    )
                }
            }
        }
        message?.let {
            Text(
                it.asString(),
                color = if (messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
        }
        if (saved) {
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(hostId) }
                        config = defaultConfig
                        saved = false
                        messageIsError = false
                        message = uiText(R.string.ssh_settings_removed)
                    }
                },
            ) { Text(stringResource(R.string.remove_saved_settings)) }
        }
    }
}

@Composable
internal fun TailnetLoginScreen(
    tailnet: TailnetBackend,
    tailnetState: TailnetState,
    returnOnConnected: Boolean,
    profile: TailnetProfile? = null,
    profiles: TailnetProfileController? = null,
    onManage: (() -> Unit)? = null,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val preferences = remember(context) { TailnetProfilePreferences(context) }
    val profileBusy = profiles?.state?.collectAsStateWithLifecycle()?.value?.busy == true
    var controlServer by rememberSaveable(profile?.id) { mutableStateOf(profile?.controlUrl ?: preferences.controlServer) }
    var useAuthKey by rememberSaveable { mutableStateOf(false) }
    // Secrets deliberately never enter saved state or preferences.
    var authKey by remember { mutableStateOf("") }
    var awaiting by rememberSaveable { mutableStateOf(false) }
    var browserOpened by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }
    var openedLoginUrl by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val loginState = tailnetState as? TailnetState.LoginRequired
    val loginUrl = loginState?.request?.url ?: openedLoginUrl

    fun openLogin(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            openedLoginUrl = url
            browserOpened = true
            message = null
        } catch (_: ActivityNotFoundException) {
            message = uiText(R.string.login_no_browser)
        }
    }
    fun returnToSetup() {
        job?.cancel()
        if (profiles != null) profiles.cancelLogin() else tailnet.cancelAuthKeyLogin()
        awaiting = false
        busy = false
        browserOpened = false
        openedLoginUrl = null
        authKey = ""
    }
    BackHandler(awaiting) { returnToSetup() }
    LaunchedEffect(awaiting) {
        slow = false
        if (awaiting) { kotlinx.coroutines.delay(30_000); slow = true }
    }
    LaunchedEffect(tailnetState) {
        when (tailnetState) {
            is TailnetState.Running -> {
                awaiting = false
                authKey = ""
                if (returnOnConnected) onConnected()
                else message = uiText(R.string.control_server_connected)
            }
            is TailnetState.Failed -> {
                awaiting = false
                message = if (useAuthKey) uiText(R.string.auth_key_failed) else tailnetState.redactedMessage.asUiText()
            }
            else -> Unit
        }
    }

    if (awaiting) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator()
            Text(stringResource(if (loginState?.awaitingApproval == true) R.string.auth_waiting_approval else R.string.auth_connecting),
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(when {
                loginState?.awaitingApproval == true -> R.string.auth_approval_description
                browserOpened -> R.string.auth_browser_wait_description
                useAuthKey -> R.string.auth_key_wait_description
                else -> R.string.login_requesting_url
            }), style = MaterialTheme.typography.bodyLarge)
            if (slow) Text(stringResource(R.string.auth_wait_slow), color = NovaTheme.colors.textSecondary)
            message?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            if (!useAuthKey && loginUrl != null) {
                NovaPrimaryButton(onClick = { openLogin(loginUrl) }) { Text(stringResource(R.string.auth_reopen_browser)) }
            }
            TextButton(onClick = ::returnToSetup) { Text(stringResource(R.string.auth_back_setup)) }
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (profile != null) stringResource(R.string.tailnets_login_profile, profile.name)
            else stringResource(R.string.connect_tailnet), style = MaterialTheme.typography.headlineMedium)
        TailnetStatusCard(tailnetState)
        message?.let { Text(it.asString()) }
        if (profile != null) {
            Text(profile.controlUrl, style = MaterialTheme.typography.bodySmall, color = NovaTheme.colors.textSecondary)
            onManage?.let { TextButton(onClick = it) { Text(stringResource(R.string.tailnets_manage)) } }
        } else {
        TextButton(
            onClick = { controlServer = DEFAULT_CONTROL_SERVER; authKey = ""; message = null },
            enabled = !busy && controlServer.trim().trimEnd('/') != DEFAULT_CONTROL_SERVER.trimEnd('/'),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.auth_reset_server)) }
        OutlinedTextField(controlServer, { controlServer = it }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.label_control_server)) },
            supportingText = { Text(stringResource(R.string.control_server_description)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!useAuthKey, { useAuthKey = false; authKey = "" }, label = { Text(stringResource(R.string.auth_browser_method)) })
            FilterChip(useAuthKey, { useAuthKey = true }, label = { Text(stringResource(R.string.auth_key_method)) })
        }
        if (useAuthKey) {
            OutlinedTextField(authKey, { authKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.auth_key_method)) },
                trailingIcon = {
                    TextButton(onClick = {
                        val pasted = runCatching {
                            context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
                        }.getOrNull()
                        if (pasted.isNullOrEmpty()) {
                            message = uiText(R.string.auth_clipboard_empty)
                        } else {
                            authKey = pasted
                            message = null
                        }
                    }) { Text(stringResource(R.string.cd_paste)) }
                },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { Text(stringResource(R.string.auth_key_description)) })
        } else Text(stringResource(R.string.login_browser_explanation))
        NovaPrimaryButton(enabled = !busy && (profiles == null || (profile != null && !profileBusy)) && (!useAuthKey || authKey.isNotBlank()) &&
            (tailnetState !is TailnetState.Running || controlServer.trim().trimEnd('/') != preferences.controlServer.trim().trimEnd('/')),
            onClick = {
                focus.clearFocus()
                keyboard?.hide()
                val submittedKey = if (useAuthKey) authKey.trim() else null
                authKey = ""
                awaiting = true
                busy = true
                message = null
                browserOpened = false
                job = scope.launch {
                    try {
                        if (profiles != null && profile != null) {
                            val request = profiles.login(profile.id, submittedKey)
                            if (request != null) openLogin(request.url)
                            else if (tailnet.currentState is TailnetState.Running) { awaiting = false; onConnected() }
                        } else {
                        val selectedServer = controlServer.trim().ifEmpty { DEFAULT_CONTROL_SERVER }
                        tailnet.start(tailnetConfiguration(context, selectedServer, androidTailnetHostname()))
                        preferences.controlServer = selectedServer
                        if (tailnet.currentState is TailnetState.Running) {
                            awaiting = false
                            if (returnOnConnected) onConnected() else message = uiText(R.string.control_server_connected)
                        } else if (submittedKey != null) {
                            tailnet.beginAuthKeyLogin(submittedKey)
                        } else {
                            openLogin(tailnet.beginInteractiveLogin().url)
                        }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        awaiting = false
                        message = uiText(if (submittedKey != null) R.string.auth_key_failed else R.string.tailnet_start_failed)
                    } finally { busy = false }
                }
            }) {
            Text(stringResource(if (useAuthKey) R.string.auth_key_connect else if (tailnetState is TailnetState.Running)
                R.string.switch_control_server else R.string.start_and_sign_in))
        }
    }
}

@Composable
private fun SettingsScreen(
    tailnetState: TailnetState,
    preferences: AppPreferences,
    terminalAppearance: String,
    filePreviewSettings: FilePreviewSettings,
    onTailnetDetails: () -> Unit,
    onVersions: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onTerminalSettings: () -> Unit,
    onFilePreviewSettings: () -> Unit,
    onProxySettings: () -> Unit,
    onSource: () -> Unit,
    onPro: () -> Unit,
) {
    var appleLinkFailed by remember { mutableStateOf(false) }
    var showGestureGuide by remember { mutableStateOf(false) }
    if (showGestureGuide) cc.galaxnet.novascale.terminalfeature.TerminalGestureGuide { showGestureGuide = false }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entitlement by (context.applicationContext as NovaScaleApplication).billing.manager.state.collectAsStateWithLifecycle()
    val keepTerminalsInBackground by preferences.keepTerminalSessionsInBackground.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        HostActionCard(
            stringResource(if (entitlement.communityEdition) R.string.pro_community_title else R.string.pro_title),
            stringResource(if (entitlement.communityEdition) R.string.pro_community_description else if (entitlement.hasPro) R.string.pro_status_active else R.string.pro_status_free),
            NovaIcons.Info, onPro, iconColor = NovaTheme.colors.menuSupport,
        )
        cc.galaxnet.novascale.billing.DebugProControls((context.applicationContext as NovaScaleApplication).billing.manager,
            herdrUsage = { (context.applicationContext as NovaScaleApplication).herdrUsage })
        NovaSectionLabel(stringResource(R.string.section_tailnet))
        HostActionCard(
            stringResource(R.string.tailnets_title), stringResource(R.string.tailnets_one_active), NovaIcons.Server, onTailnetDetails, iconColor = NovaTheme.colors.menuGreen,
        )
        NovaSectionLabel(stringResource(R.string.proxy_title), Modifier.padding(top = NovaSpacing.sm))
        HostActionCard(stringResource(R.string.proxy_title), stringResource(R.string.proxy_settings_summary), NovaIcons.Proxy, onProxySettings, iconColor = NovaTheme.colors.statusInfo)
        NovaSectionLabel(
            stringResource(R.string.section_terminal),
            Modifier.padding(top = NovaSpacing.sm),
        )
        HostActionCard(
            stringResource(R.string.terminal_appearance),
            terminalAppearance,
            NovaIcons.Appearance,
            onTerminalSettings, iconColor = NovaTheme.colors.menuAppearance,
        )
        HostActionCard(
            stringResource(R.string.terminal_gestures),
            stringResource(R.string.terminal_gestures_summary),
            NovaIcons.Gestures,
            { showGestureGuide = true },
        )
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            NovaActionRow(
                title = stringResource(R.string.keep_sessions_background),
                description = stringResource(R.string.keep_sessions_background_description),
                icon = NovaIcons.BackgroundSessions,
                iconColor = NovaTheme.colors.primary,
                titleMaxLines = 2,
                descriptionMaxLines = 3,
                trailing = {
                    Switch(
                        checked = keepTerminalsInBackground,
                        onCheckedChange = { enabled ->
                            if (
                                enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            scope.launch { preferences.setKeepTerminalSessionsInBackground(enabled) }
                        },
                    )
                },
            )
        }
        NovaSectionLabel(
            stringResource(R.string.section_files),
            Modifier.padding(top = NovaSpacing.sm),
        )
        HostActionCard(
            stringResource(R.string.file_previews),
            stringResource(
                R.string.preview_summary,
                filePreviewSettings.sizeLimit.localizedLabel(),
                stringResource(
                    if (filePreviewSettings.tapToPreview) R.string.tap_opens_preview
                    else R.string.preview_from_file_actions,
                ),
            ),
            NovaIcons.Files,
            onFilePreviewSettings, iconColor = NovaTheme.colors.menuFile,
        )
        NovaSectionLabel(stringResource(R.string.section_misc), Modifier.padding(top = NovaSpacing.sm))
        LanguageSettings(preferences)
        NovaSectionLabel(
            stringResource(R.string.section_about),
            Modifier.padding(top = NovaSpacing.sm),
        )
        HostActionCard(
            stringResource(R.string.versions_title), BuildConfig.VERSION_NAME,
            NovaIcons.Version, onVersions, iconColor = NovaTheme.colors.statusInfo,
        )
        HostActionCard(
            stringResource(R.string.open_source),
            stringResource(R.string.open_source_description),
            NovaIcons.Info,
            onSource, iconColor = NovaTheme.colors.textSecondary,
        )
        NovaSectionLabel(stringResource(R.string.section_apple_version), Modifier.padding(top = NovaSpacing.sm))
        HostActionCard(stringResource(R.string.apple_version_link), stringResource(R.string.apple_version_description),
            androidx.compose.material.icons.Icons.Outlined.Language,
            { appleLinkFailed = !context.openReleaseWebPage(APPLE_APP_STORE_URL) })
        if (appleLinkFailed) Text(stringResource(R.string.legal_link_unavailable), color = MaterialTheme.colorScheme.error)
        if (BuildConfig.DEBUG) {
            NovaSectionLabel(stringResource(R.string.section_debug))
            HostActionCard(stringResource(R.string.debug_replay_onboarding),
                stringResource(R.string.debug_replay_description), NovaIcons.Info, onReplayOnboarding)
        }
    }
}

@Composable
private fun FilePreviewSettingsScreen(
    settings: FilePreviewSettings,
    onTapToPreview: (Boolean) -> Unit,
    onSizeLimit: (FilePreviewSizeLimit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        NovaSectionLabel(stringResource(R.string.section_file_actions))
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NovaSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tap_to_preview),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.tap_to_preview_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTheme.colors.textSecondary,
                    )
                }
                Switch(
                    checked = settings.tapToPreview,
                    onCheckedChange = onTapToPreview,
                )
            }
        }
        NovaSectionLabel(
            stringResource(R.string.temporary_download_limit),
            Modifier.padding(top = NovaSpacing.sm),
        )
        Text(
            stringResource(R.string.temporary_download_description),
            style = MaterialTheme.typography.bodySmall,
            color = NovaTheme.colors.textSecondary,
        )
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                FilePreviewSizeLimit.entries.forEachIndexed { index, limit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSizeLimit(limit) }
                            .heightIn(min = 54.dp)
                            .padding(horizontal = NovaSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.sizeLimit == limit,
                            onClick = { onSizeLimit(limit) },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = NovaSpacing.sm),
                        ) {
                            Text(limit.localizedLabel(), style = MaterialTheme.typography.bodyLarge)
                            if (limit == FilePreviewSizeLimit.UNLIMITED) {
                                Text(
                                    stringResource(R.string.preview_unlimited_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NovaTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                    if (index != FilePreviewSizeLimit.entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = NovaTheme.colors.borderSubtle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraditionalConfigContent(
    host: TraditionalHost,
    repository: TraditionalHostRepository,
    content: @Composable (RemoteConnectionConfig) -> Unit,
) {
    var loaded by remember(host) { mutableStateOf(false) }
    var config by remember(host) { mutableStateOf<RemoteConnectionConfig?>(null) }
    LaunchedEffect(host) {
        config = runCatching { repository.load(host) }.getOrNull()
        loaded = true
    }
    val saved = config
    if (saved != null) content(saved)
    else if (loaded) Text(stringResource(R.string.traditional_host_load_error))
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun savedConfiguration(peer: TailnetPeer, profileId: String?): RemoteConnectionConfig {
    val context = LocalContext.current
    val store = remember(context, profileId) { SshProfileStore(context, profileId) }
    return remember(peer, profileId) {
        val saved = store.load(peer.hostId())
        saved?.copy(host = saved.host.ifBlank { peer.sshHost() }, tailnetProfileId = profileId)
            ?: RemoteConnectionConfig(host = peer.sshHost(), tailnetProfileId = profileId)
    }
}

private fun TailnetPeer.hostId(): String = stableNodeId.ifBlank {
    dnsName ?: addresses.firstOrNull() ?: displayName
}

private fun TraditionalHost.asPeer(config: RemoteConnectionConfig): TailnetPeer = TailnetPeer(
    stableNodeId = id, displayName = name, dnsName = config.host.trim(), addresses = emptyList(),
    online = true, sshAvailable = true,
)

private fun TailnetPeer.sshHost(): String =
    dnsName?.trimEnd('.') ?: addresses.firstOrNull().orEmpty()

@Composable
private fun AppDestination?.titleOrNull(): String? = when (this) {
    AppDestination.Login -> stringResource(R.string.screen_tailnet_login)
    AppDestination.Tailnets -> stringResource(R.string.tailnets_title)
    AppDestination.TailnetDetails -> stringResource(R.string.tailnet_details)
    AppDestination.Versions -> stringResource(R.string.versions_title)
    is AppDestination.Host -> peer.displayName
    is AppDestination.TraditionalHostDetail -> host.name
    is AppDestination.TraditionalHostEdit -> stringResource(if (host == null) R.string.traditional_host_add else R.string.traditional_host_edit)
    is AppDestination.TraditionalTerminal -> stringResource(R.string.screen_terminal_host, host.name)
    is AppDestination.TraditionalFiles -> stringResource(R.string.screen_files_host, host.name)
    is AppDestination.TraditionalHerdr -> stringResource(R.string.herdr_title)
    is AppDestination.Herdr -> stringResource(R.string.herdr_title)
    is AppDestination.Ping -> stringResource(R.string.screen_tailscale_ping)
    is AppDestination.Terminal ->
        stringResource(R.string.screen_terminal_host, peer.displayName)
    is AppDestination.Files -> stringResource(R.string.screen_files_host, peer.displayName)
    is AppDestination.SshSettings -> stringResource(R.string.screen_ssh_host, peer.displayName)
    AppDestination.TerminalSettings -> stringResource(R.string.screen_terminal_settings)
    AppDestination.FilePreviewSettings -> stringResource(R.string.file_previews)
    AppDestination.ProxySettings -> stringResource(R.string.proxy_title)
    AppDestination.Source -> stringResource(R.string.open_source)
    is AppDestination.LegalDocument -> stringResource(document.titleRes)
    null -> null
}

@Composable
internal fun TailnetState.label(): String = when (this) {
    TailnetState.Stopped -> stringResource(R.string.status_offline)
    TailnetState.Starting -> stringResource(R.string.status_starting)
    is TailnetState.LoginRequired -> stringResource(if (awaitingApproval) R.string.auth_waiting_approval else R.string.status_login_required)
    is TailnetState.Running -> pluralStringResource(
        R.plurals.tailnet_connected_hosts,
        peerCount,
        peerCount,
    )
    is TailnetState.Failed -> stringResource(R.string.status_connection_failed)
}

internal fun androidTailnetHostname(): String {
    val device = Build.MODEL
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .take(40)
        .ifBlank { "android" }
    return "novascale-$device"
}

@Composable
private fun FilePreviewSizeLimit.localizedLabel(): String =
    if (this == FilePreviewSizeLimit.UNLIMITED) {
        stringResource(R.string.preview_unlimited)
    } else {
        label
    }

private const val APPLE_APP_STORE_URL = "https://apps.apple.com/app/id6749938291"

internal fun Context.openReleaseWebPage(rawUrl: String): Boolean {
    val url = validReleaseWebUrl(rawUrl) ?: return false
    return try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
private fun SourceScreen(
    onDocument: (PackagedLegalDocument) -> Unit,
) {
    val context = LocalContext.current
    val nativeCore = remember { runCatching { rustCoreBuildInfo() }.getOrNull() }
    val terminalProbe = remember { runCatching { ghosttyTerminalProbe() }.getOrNull() }
    val sourceUrl = remember { validReleaseWebUrl(BuildConfig.SOURCE_CODE_URL) }
    var linkFailed by remember { mutableStateOf(false) }

    fun openLink(url: String) {
        linkFailed = !context.openReleaseWebPage(url)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        Text(stringResource(R.string.source_title), style = MaterialTheme.typography.headlineMedium)

        NovaSectionLabel(stringResource(R.string.section_legal))
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                NovaActionRow(
                    title = stringResource(R.string.privacy_policy),
                    description = stringResource(R.string.privacy_policy_description),
                    icon = NovaIcons.Privacy,
                    iconColor = NovaTheme.colors.menuPrimary,
                    modifier = Modifier.clickable { openLink(BuildConfig.PRIVACY_POLICY_URL) },
                )
                HorizontalDivider(color = NovaTheme.colors.borderSubtle)
                NovaActionRow(
                    title = stringResource(R.string.terms_of_service),
                    description = stringResource(R.string.terms_of_service_description),
                    icon = NovaIcons.Terms,
                    iconColor = NovaTheme.colors.menuGreen,
                    modifier = Modifier.clickable { openLink(BuildConfig.TERMS_OF_SERVICE_URL) },
                )
            }
        }
        if (linkFailed) {
            Text(
                stringResource(R.string.legal_link_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = NovaTheme.colors.statusDisconnected,
            )
        }

        NovaSectionLabel(stringResource(R.string.section_open_source))
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                sourceUrl?.let {
                    NovaActionRow(
                        title = stringResource(R.string.source_code),
                        description = it,
                        icon = NovaIcons.Source,
                        iconColor = NovaTheme.colors.menuPrimary,
                        modifier = Modifier.clickable { openLink(it) },
                    )
                    HorizontalDivider(color = NovaTheme.colors.borderSubtle)
                }
                NovaActionRow(
                    title = stringResource(R.string.application_license),
                    description = stringResource(R.string.application_license_description),
                    icon = NovaIcons.License,
                    iconColor = NovaTheme.colors.primary,
                    modifier = Modifier.clickable {
                        onDocument(PackagedLegalDocument.APPLICATION_LICENSE)
                    },
                )
                HorizontalDivider(color = NovaTheme.colors.borderSubtle)
                NovaActionRow(
                    title = stringResource(R.string.third_party_notices),
                    description = stringResource(R.string.third_party_notices_description),
                    icon = NovaIcons.Info,
                    iconColor = NovaTheme.colors.menuGreen,
                    modifier = Modifier.clickable {
                        onDocument(PackagedLegalDocument.THIRD_PARTY_NOTICES)
                    },
                )
            }
        }
        sourceUrl?.let {
            Text(
                stringResource(R.string.source_corresponding_source),
                style = MaterialTheme.typography.bodySmall,
                color = NovaTheme.colors.textSecondary,
            )
        }

        NovaSectionLabel(stringResource(R.string.section_build_information))
        NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(NovaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
            ) {
                Text(stringResource(R.string.source_license))
                Text(stringResource(R.string.label_version, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.source_application_id, BuildConfig.APPLICATION_ID))
                Text(
                    nativeCore?.let {
                        stringResource(
                            R.string.source_rust_core,
                            it.getString("rustCore"),
                            it.getString("russh"),
                            it.getString("russhSftp"),
                            it.getString("ghostty"),
                        )
                    } ?: stringResource(R.string.source_rust_unavailable),
                )
                Text(
                    terminalProbe?.let {
                        stringResource(R.string.source_ghostty_probe, it)
                    } ?: stringResource(R.string.source_ghostty_probe_failed),
                )
                Text(stringResource(R.string.source_fonts))
                Text(stringResource(R.string.source_palettes))
                Text(stringResource(R.string.source_sora))
                Text(stringResource(R.string.source_tree_sitter))
                Text(stringResource(R.string.source_previews))
            }
        }
    }
}

@Composable
private fun LegalDocumentScreen(document: PackagedLegalDocument) {
    val context = LocalContext.current
    val loadFailure = stringResource(R.string.legal_document_load_failed)
    val content = remember(context, document, loadFailure) {
        runCatching {
            context.assets.open(document.assetPath).bufferedReader().use { it.readText() }
        }.getOrElse {
            loadFailure
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
    ) {
        SelectionContainer {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = NovaTheme.colors.textPrimary,
            )
        }
    }
}
