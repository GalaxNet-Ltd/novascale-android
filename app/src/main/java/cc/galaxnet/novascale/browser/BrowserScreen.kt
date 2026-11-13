// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import cc.galaxnet.novascale.R
import kotlinx.coroutines.launch
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.ui.NovaPrimaryButton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URI
import kotlin.coroutines.resume

internal data class BrowserUiState(val address: String = "http://", val url: String? = null, val progress: Int = 0,
    val title: String = "", val secure: Boolean = false, val back: Boolean = false, val forward: Boolean = false, val error: Int? = null)
internal class BrowserTab(val id: Long, address: String = "http://") {
    val state = MutableStateFlow(BrowserUiState(address = address))
    var pendingOpen by mutableStateOf<String?>(null)
    var requestedUrl by mutableStateOf<String?>(null)
    var favicon by mutableStateOf<Bitmap?>(null)
    var desktop by mutableStateOf(false)
}

internal class BrowserViewModel : ViewModel() {
    private var profile: String? = null
    fun bindProfile(id: String?) {
        if (profile != null && id != profile) closeAll()
        profile = id
    }
    private var nextId = 1L
    val tabs = mutableStateListOf<BrowserTab>()
    // The landing-page draft is not an open browser tab.
    var active by mutableStateOf(BrowserTab(nextId++))
        private set
    val state get() = active.state
    fun select(tab: BrowserTab) { if (tab in tabs) active = tab }
    fun newTab(address: String = "http://") {
        active = BrowserTab(nextId++, address).also(tabs::add)
    }
    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.remove(tab)
        if (tabs.isEmpty()) active = BrowserTab(nextId++)
        else if (active === tab) active = tabs[index.coerceAtMost(tabs.lastIndex)]
    }
    fun closeAll() {
        tabs.clear()
        active = BrowserTab(nextId++)
    }
    fun address(value: String) { state.value = state.value.copy(address = value) }
    fun open(): String? {
        val url = runCatching { browserUrl(state.value.address) }.getOrNull()
        state.value = state.value.copy(url = url ?: state.value.url, error = if (url == null) R.string.browser_invalid_url else null)
        if (url != null && active !in tabs) tabs.add(active)
        return url
    }
}

internal fun browserHostAddress(dnsName: String?, addresses: List<String>): String {
    val host = dnsName?.trimEnd('.')?.substringBefore('.')?.takeIf { it.isNotBlank() }
        ?: addresses.firstOrNull().orEmpty()
    return "http://" + if (":" in host) "[$host]" else host
}

internal fun browserUrl(raw: String): String {
    val value = raw.trim().let { if ("://" in it) it else "http://$it" }
    val uri = URI(value)
    require(uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    require(uri.port == -1 || uri.port in 1..65535)
    val host = uri.host.lowercase().trim('[', ']').trimEnd('.')
    require(host != "localhost" && !host.endsWith(".localhost") && host != "::1" && host != "0.0.0.0" && !host.startsWith("127."))
    return value
}

// All browser windows use the same application proxy. Serializing acknowledgments
// prevents a cancelled setup callback overwriting a newer endpoint. Never clear
// to DIRECT on teardown: a stale rule must point to a closed listener.
private object BrowserProxyOverride {
    val mutex = Mutex()
    private var profile: String? = null
    @SuppressLint("RequiresFeature")
    suspend fun configure(context: android.content.Context, session: ProxySession) = withContext(NonCancellable) {
        mutex.withLock {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            if (profile != session.profileId) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    CookieManager.getInstance().removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
                }
                WebStorage.getInstance().deleteAllData()
                profile = session.profileId
            }
            suspendCancellableCoroutine { continuation ->
                ProxyController.getInstance().setProxyOverride(
                    ProxyConfig.Builder().addProxyRule(session.endpoint.httpUrl).removeImplicitRules().build(), context.mainExecutor,
                ) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
    }
}

@Composable
internal fun BrowserScreen(controller: LocalProxyController, model: BrowserViewModel = viewModel(),
    onBrowsingChanged: (Boolean) -> Unit = {}, visible: Boolean = true,
    appNavigationVisible: Boolean = false, onToggleAppNavigation: () -> Unit = {}, onManageTailnets: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as NovaScaleApplication
    val profiles by application.tailnetProfiles.state.collectAsStateWithLifecycle()
    val proxy by controller.state.collectAsStateWithLifecycle()
    BrowserContent(proxy.session, { controller.state.value.session === it }, model, onBrowsingChanged,
        visible, appNavigationVisible, onToggleAppNavigation, application.browserLibrary, profiles.active?.id,
        access = browserAccess(proxy, profiles.active?.id), onEnableProxy = controller::enable,
        onRetryProxy = controller::retry, onManageTailnets = onManageTailnets)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserContent(session: ProxySession?, isSessionCurrent: (ProxySession) -> Boolean,
    model: BrowserViewModel = viewModel(), onBrowsingChanged: (Boolean) -> Unit = {},
    visible: Boolean = true, appNavigationVisible: Boolean = false, onToggleAppNavigation: () -> Unit = {},
    library: BrowserLibrary? = null, profileId: String? = session?.profileId,
    access: BrowserAccess = if (session == null) BrowserAccess.TAILNET_REQUIRED else BrowserAccess.READY,
    onEnableProxy: () -> Unit = {}, onRetryProxy: () -> Unit = {}, onManageTailnets: () -> Unit = {}) {
    model.bindProfile(profileId)
    val tab = model.active
    val tabState = tab.state
    val state by tabState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember(profileId) { mutableStateOf<List<BrowserSite>>(emptyList()) }
    var libraryError by remember(profileId) { mutableStateOf(false) }
    var libraryBusy by remember(profileId) { mutableStateOf(false) }
    var libraryPage by remember(profileId) { mutableStateOf<BrowserLibraryPage?>(null) }
    var favoriteEditor by remember(profileId) { mutableStateOf<BrowserSite?>(null) }
    fun updateLibrary(block: suspend (BrowserLibrary, String) -> List<BrowserSite>, saved: () -> Unit = {}) {
        val repo = library ?: return
        val profile = profileId ?: return
        if (libraryBusy) return
        libraryBusy = true; libraryError = false
        scope.launch {
            try { sites = block(repo, profile); saved() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { libraryError = true }
            finally { libraryBusy = false }
        }
    }
    LaunchedEffect(profileId, library) {
        sites = emptyList(); libraryError = false
        if (profileId != null && library != null) {
            try { sites = library.load(profileId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { libraryError = true }
        }
    }
    var finding by remember(tab) { mutableStateOf(false) }
    var findQuery by remember(tab) { mutableStateOf("") }
    var findMatch by remember(tab) { mutableStateOf(0) }
    var findTotal by remember(tab) { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var ready by remember { mutableStateOf<String?>(null) }
    var proxyConfigurationFailed by remember(session, foreground, profileId) { mutableStateOf(false) }
    val effectiveAccess = when {
        access != BrowserAccess.READY -> access
        proxyConfigurationFailed -> BrowserAccess.UNSUPPORTED
        session != null && foreground && session.profileId == profileId && ready == session.endpoint.id && isSessionCurrent(session) -> BrowserAccess.READY
        else -> BrowserAccess.STARTING
    }
    var disabledAlert by remember { mutableStateOf(false) }
    LaunchedEffect(visible, access) { disabledAlert = visible && access == BrowserAccess.DISABLED }
    fun recoverAccess() {
        when (effectiveAccess) {
            BrowserAccess.DISABLED -> disabledAlert = true
            BrowserAccess.FAILED -> onRetryProxy()
            BrowserAccess.TAILNET_REQUIRED -> onManageTailnets()
            else -> Unit
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var requestedUrl by tab::requestedUrl
    val pages = remember(session, foreground, profileId) { mutableMapOf<Long, WebView>() }
    val disposedPages = remember(session, foreground, profileId) { booleanArrayOf(false) }
    var switchingTabs by remember { mutableStateOf(false) }
    DisposableEffect(session, foreground, profileId) {
        // Pages belong to this foreground proxy session, never to a different profile.
        model.tabs.forEach { item -> item.requestedUrl = null; item.state.value = item.state.value.copy(address = item.state.value.url ?: item.state.value.address) }
        onDispose {
            model.tabs.forEach { it.pendingOpen = null }
            disposedPages[0] = true
            pages.values.forEach { it.stopLoading(); it.destroy() }
            pages.clear()
        }
    }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var desktop by tab::desktop
    var video by remember { mutableStateOf<android.view.View?>(null) }
    var videoCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun closeFind() { finding = false; focus.clearFocus(); keyboard?.hide() }
    fun closeVideo() { val callback = videoCallback; videoCallback = null; video = null; callback?.onCustomViewHidden() }
    fun closeTab(closed: BrowserTab) {
        if (closed === tab) closeVideo()
        val page = pages.remove(closed.id)
        if (closed !== tab || page?.parent == null) { page?.stopLoading(); page?.destroy() }
        model.close(closed)
    }
    fun navigate() {
        if (effectiveAccess != BrowserAccess.READY || !foreground || session == null ||
            session.profileId != profileId || ready != session.endpoint.id || !isSessionCurrent(session)) {
            recoverAccess(); return
        }
        finding = false
        model.open()?.let { url ->
            editing = false; focus.clearFocus(); keyboard?.hide()
            requestedUrl = url
            pages[tab.id]?.loadUrl(url)
        }
    }
    fun openSite(site: BrowserSite) { model.address(site.url); libraryPage = null; navigate() }
    DisposableEffect(webView, tab, finding) {
        val page = webView
        if (finding && !disposedPages[0] && pages[tab.id] === page) page?.setFindListener { index, count, done ->
            if (done) { findMatch = if (count > 0) index + 1 else 0; findTotal = count }
        }
        onDispose {
            if (!disposedPages[0] && pages[tab.id] === page) { page?.setFindListener(null); page?.clearMatches() }
        }
    }
    LaunchedEffect(findQuery, webView, finding) {
        if (finding) {
            findMatch = 0; findTotal = 0
            if (findQuery.isEmpty()) webView?.clearMatches() else { kotlinx.coroutines.delay(120); webView?.findAllAsync(findQuery) }
        }
    }
    DisposableEffect(requestedUrl) {
        onBrowsingChanged(requestedUrl != null)
        onDispose { onBrowsingChanged(false) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); closeVideo() }
    }
    LaunchedEffect(session, foreground, profileId) {
        ready = null
        if (!foreground || session == null) closeVideo()
        if (session != null && foreground && session.profileId == profileId) {
            try { BrowserProxyOverride.configure(context, session); ready = session.endpoint.id }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { proxyConfigurationFailed = true }
        }
    }
    // A host-sheet Open is explicit navigation; wait for the authenticated proxy.
    LaunchedEffect(tab, ready, visible, foreground, tab.pendingOpen) {
        if (visible && foreground && session != null && ready == session.endpoint.id) {
            tab.pendingOpen?.let { url ->
                tab.pendingOpen = null
                model.address(url)
                navigate()
            }
        }
    }
    LaunchedEffect(visible) {
        if (!visible) {
            closeVideo()
            editing = false; menu = false; switchingTabs = false; libraryPage = null; favoriteEditor = null; finding = false
            pages.values.forEach { it.clearFocus(); it.onPause() }
        }
    }
    // Keep ownership/effects above this boundary, but remove all hidden UI,
    // dialogs and Back handlers so other app sections remain interactive.
    if (!visible) return
    BackHandler(enabled = requestedUrl != null || editing || video != null || finding) {
        when { video != null -> closeVideo(); editing -> editing = false; finding -> closeFind()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> requestedUrl = null }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (requestedUrl == null) {
            BrowserStartPage(state, sites, effectiveAccess == BrowserAccess.READY,
                model.tabs.size, model::address, ::navigate, { switchingTabs = true }, { libraryPage = it }, ::openSite, libraryError,
                { updateLibrary({ repo, profile -> repo.load(profile) }) }, effectiveAccess, ::recoverAccess)
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (session != null && foreground && session.profileId == profileId && ready == session.endpoint.id) key(session, tab.id) {
                    AndroidView(factory = { viewContext ->
                        (pages[tab.id] ?: createBrowserWebView(viewContext, session,
                            isCurrent = { isSessionCurrent(session) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
                            changed = { view, error -> tabState.value = tabState.value.copy(
                                url = view.url?.takeUnless { it == "about:blank" } ?: tabState.value.url,
                                title = view.title.orEmpty(),
                                secure = view.url?.startsWith("https://") == true && view.certificate != null && error == null,
                                back = view.canGoBack(), forward = view.canGoForward(), progress = view.progress,
                                error = error ?: tabState.value.error) },
                            started = { tabState.value = tabState.value.copy(error = null, secure = false) },
                            finished = { page, url ->
                                if (library != null && profileId != null && isSessionCurrent(session)) scope.launch {
                                    try { sites = library.visit(profileId, url, page.title.orEmpty()) }
                                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { libraryError = true }
                                }
                            },
                            favicon = { bitmap -> tab.favicon = bitmap },
                            fullscreen = { custom, callback ->
                                if (model.active === tab) { closeVideo(); video = custom; videoCallback = callback }
                                else callback?.onCustomViewHidden()
                            },
                        ).also { page ->
                            if (tab.desktop) page.settings.userAgentString = WebSettings.getDefaultUserAgent(context)
                                .replace("Android", "X11; Linux x86_64").replace("Mobile", "")
                            pages[tab.id] = page
                            requestedUrl?.let(page::loadUrl)
                        }).also { webView = it; it.onResume() }
                    }, onRelease = {
                        closeVideo()
                        if (!disposedPages[0]) {
                            if (pages[tab.id] === it) it.onPause() else { it.stopLoading(); it.destroy() }
                        }
                        if (webView === it) webView = null
                    },
                        modifier = Modifier.fillMaxSize().clipToBounds())
                }
            }
            Surface(Modifier.zIndex(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 4.dp) {
                Column {
                    if (state.progress in 1..99) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    state.error?.let { problem -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(problem), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        NovaPrimaryButton({ webView?.reload() }) { Text(stringResource(R.string.action_retry)) }
                    } }
                    if (finding) BrowserFindBar(findQuery, findMatch, findTotal, { findQuery = it }, { webView?.findNext(it) }, ::closeFind)
                    if (!finding) {
                    Surface(onClick = { model.address(state.url ?: state.address); editing = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (state.secure && state.error == null) Icons.Outlined.Lock else Icons.Outlined.Language,
                                contentDescription = stringResource(if (state.secure && state.error == null) R.string.browser_https else R.string.browser_http), modifier = Modifier.size(18.dp))
                            Text(state.url ?: state.address, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Edit, stringResource(R.string.browser_edit_address), modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(enabled = state.back, onClick = { webView?.goBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                        IconButton(enabled = state.forward, onClick = { webView?.goForward() }) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.browser_forward)) }
                        IconButton(onClick = { if (state.progress in 1..99) webView?.stopLoading() else webView?.reload() }) {
                            Icon(if (state.progress in 1..99) Icons.Outlined.Close else Icons.Outlined.Refresh,
                                stringResource(if (state.progress in 1..99) R.string.action_stop else R.string.action_reload))
                        }
                        IconButton(onClick = { switchingTabs = true }) {
                            Icon(Icons.Outlined.Tab, pluralStringResource(R.plurals.browser_tab_count, model.tabs.size, model.tabs.size))
                        }
                        IconButton(onClick = onToggleAppNavigation) {
                            Icon(if (appNavigationVisible) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                stringResource(if (appNavigationVisible) R.string.browser_hide_app_navigation else R.string.browser_show_app_navigation))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_actions)) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_find)) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = { menu = false; finding = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_add_favorite)) }, enabled = library != null && profileId != null,
                                    leadingIcon = { Icon(Icons.Outlined.StarOutline, null) }, onClick = {
                                        menu = false; libraryError = false
                                        favoriteEditor = sites.firstOrNull { it.url == state.url }
                                            ?: BrowserSite("", state.url ?: state.address, state.title.ifBlank { browserDisplayHost(state.url ?: state.address) }, 0, false)
                                    })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_favorites)) }, onClick = { menu = false; libraryPage = BrowserLibraryPage.FAVORITES })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_history)) }, onClick = { menu = false; libraryPage = BrowserLibraryPage.HISTORY })
                                                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_start_page)) },
                                    leadingIcon = { Icon(Icons.Outlined.Home, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menu = false; requestedUrl = null })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_copy_link)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) }, onClick = {
                                    menu = false
                                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("URL", state.url ?: state.address))
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_share)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, null, tint = MaterialTheme.colorScheme.primary) }, onClick = {
                                    menu = false
                                    context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, state.url ?: state.address)
                                    }, null))
                                })
                                DropdownMenuItem(text = { Text(stringResource(if (desktop) R.string.browser_mobile else R.string.browser_desktop)) },
                                    leadingIcon = { Icon(if (desktop) Icons.Outlined.Smartphone else Icons.Outlined.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary) }, onClick = {
                                    menu = false; desktop = !desktop
                                    webView?.apply {
                                        settings.userAgentString = if (desktop) WebSettings.getDefaultUserAgent(context).replace("Android", "X11; Linux x86_64").replace("Mobile", "") else WebSettings.getDefaultUserAgent(context)
                                        reload()
                                    }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.browser_close_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menu = false; closeTab(tab) })
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (appNavigationVisible) R.string.browser_hide_app_navigation else R.string.browser_show_app_navigation)) },
                                    leadingIcon = { Icon(if (appNavigationVisible) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = { menu = false; onToggleAppNavigation() },
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    if (switchingTabs) ModalBottomSheet(onDismissRequest = { switchingTabs = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pluralStringResource(R.plurals.browser_tab_count, model.tabs.size, model.tabs.size), style = MaterialTheme.typography.titleLarge)
            model.tabs.toList().forEach { item ->
                val itemState by item.state.collectAsStateWithLifecycle()
                Surface(onClick = { closeVideo(); model.select(item); switchingTabs = false },
                    modifier = Modifier.semantics { selected = item === tab },
                    color = if (item === tab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        item.favicon?.let { bitmap -> androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, Modifier.size(24.dp).padding(end = 4.dp)) }
                        if (item === tab) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 8.dp)) {
                            Text(itemState.title.ifBlank { stringResource(R.string.browser_new_tab) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(browserDisplayHost(itemState.url ?: itemState.address), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { closeTab(item) }) { Icon(Icons.Outlined.Close, stringResource(R.string.browser_close_tab)) }
                    }
                }
            }
            NovaPrimaryButton(onClick = { closeVideo(); model.newTab(); switchingTabs = false }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.browser_new_tab))
            }
        }
    }
    if (editing) BrowserAddressSheet(state.address, sites, effectiveAccess == BrowserAccess.READY, state.error,
        model::address, ::navigate, ::openSite, { editing = false }, effectiveAccess, ::recoverAccess)
    libraryPage?.let { page -> BrowserLibrarySheet(page, sites, libraryError, libraryBusy,
        ::openSite, { libraryError = false; favoriteEditor = it },
        { site -> updateLibrary({ repo, profile -> if (page == BrowserLibraryPage.FAVORITES) repo.removeFavorite(profile, site.id) else repo.removeVisit(profile, site.id) }) },
        { updateLibrary({ repo, profile -> repo.clearHistory(profile) }) },
        { libraryPage = null }) }
    if (disabledAlert) AlertDialog(onDismissRequest = { disabledAlert = false },
        title = { Text(stringResource(R.string.browser_proxy_disabled_title)) },
        text = { Text(stringResource(R.string.browser_proxy_disabled_body)) },
        confirmButton = { NovaPrimaryButton(onClick = { disabledAlert = false; onEnableProxy() }) {
            Text(stringResource(R.string.browser_enable_proxy))
        } },
        dismissButton = { cc.galaxnet.novascale.ui.NovaSecondaryButton(onClick = { disabledAlert = false }) {
            Text(stringResource(R.string.action_cancel))
        } })
    favoriteEditor?.let { site -> BrowserFavoriteDialog(site, libraryBusy, libraryError,
        { url, title -> updateLibrary({ repo, profile -> repo.saveFavorite(profile, url, title, site.id.takeIf { it.isNotEmpty() }) }, { favoriteEditor = null }) },
        { favoriteEditor = null }) }
    video?.let { custom ->
        androidx.compose.ui.window.Dialog(onDismissRequest = ::closeVideo,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                AndroidView(factory = { custom }, modifier = Modifier.fillMaxSize(), onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) })
                IconButton(onClick = ::closeVideo, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.browser_exit_video), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}


internal fun browserPortUrl(address: String, port: Int): String? = runCatching {
    val uri = URI(browserUrl(address))
    val origin = URI(uri.scheme, null, uri.host, port, null, null, null).toASCIIString()
    browserUrl(origin + uri.rawPath.orEmpty().ifEmpty { "/" } +
        (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: ""))
}.getOrNull()
