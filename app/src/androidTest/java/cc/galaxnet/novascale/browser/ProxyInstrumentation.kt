// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawingPadding
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No tailnet credentials or external network required. Exercises the installed WebView. */
class ProxyInstrumentation : Instrumentation() {
    private var liveRetryHost = ""
    private var accessoryOnly = false
    private var sshRetryOnly = false
    private var herdrNativeOnly = false
    private var herdrStress = false
    private var sshPort = 0
    private var sshPort2 = 0
    private var sshFingerprint = ""
    private var herdrExecutable = ""
    private var herdrSession = ""
    private var graphicsOnly = false
    private var keyboardOnly = false
    private var mouseOnly = false
    private var renderingOnly = false
    private var terminalRegressionsOnly = false
    private var sessionResumeOnly = false
    private var uiOnly = false
    private var darkUi = false
    private var insetsOnly = false
    private var authOnly = false
    private var tailnetProfilesOnly = false
    private var tailnetProfilesUiOnly = false
    private var homeOnly = false
    private var detailsOnly = false
    private var traditionalHostOnly = false
    private var hostGroupsOnly = false
    private var hostPinsOnly = false
    private var hostPinsUiOnly = false
    private var herdrHostUiOnly = false
    private var hostGroupsUiOnly = false
    private var herdrControlsOnly = false
    private var hostRefinementOnly = false
    private var herdrLiveUiOnly = false
    private var herdrRecovery = false
    private var billingOnly = false
    private var proOnly = false
    private var browserLibraryOnly = false
    private var reviewFixesOnly = false
    private var sftpLinksOnly = false
    private var uiPolishOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); reviewFixesOnly = arguments?.getString("suite") == "review-fixes"; sftpLinksOnly = arguments?.getString("suite") == "sftp-links"; uiPolishOnly = arguments?.getString("suite") == "ui-polish"; browserLibraryOnly = arguments?.getString("suite") == "browser-library"; herdrRecovery = arguments?.getString("herdrRecovery") == "true"; sessionResumeOnly = arguments?.getString("suite") == "session-resume"; terminalRegressionsOnly = arguments?.getString("suite") == "terminal-regressions"; renderingOnly = arguments?.getString("suite") == "renderer"; proOnly = arguments?.getString("suite") == "pro-enforcement"; billingOnly = arguments?.getString("suite") == "billing"; tailnetProfilesOnly = arguments?.getString("suite") == "tailnet-profiles"; tailnetProfilesUiOnly = arguments?.getString("suite") == "tailnet-profiles-ui"; herdrControlsOnly = arguments?.getString("suite") == "herdr-controls"; herdrLiveUiOnly = arguments?.getString("suite") == "herdr-live-ui"; hostRefinementOnly = arguments?.getString("suite") == "host-refinement"; liveRetryHost = if (arguments?.getString("suite") == "ssh-retry-live") arguments.getString("host").orEmpty() else ""; accessoryOnly = arguments?.getString("suite") == "accessory"; sshRetryOnly = arguments?.getString("suite") == "ssh-retry"; herdrNativeOnly = arguments?.getString("suite") == "herdr-native"; sshPort = arguments?.getString("sshPort")?.toIntOrNull() ?: 0; sshPort2 = arguments?.getString("sshPort2")?.toIntOrNull() ?: 0; sshFingerprint = arguments?.getString("sshFingerprint").orEmpty(); herdrExecutable = arguments?.getString("herdrExecutable").orEmpty(); herdrSession = arguments?.getString("herdrSession").orEmpty(); herdrStress = arguments?.getString("herdrStress") == "true"; graphicsOnly = arguments?.getString("suite") == "graphics"; keyboardOnly = arguments?.getString("suite") == "keyboard"; authOnly = arguments?.getString("suite") == "auth"; homeOnly = arguments?.getString("suite") == "home"; detailsOnly = arguments?.getString("suite") == "details"; traditionalHostOnly = arguments?.getString("suite") == "traditional-host"; hostGroupsOnly = arguments?.getString("suite") == "host-groups"; hostPinsOnly = arguments?.getString("suite") == "host-pins"; hostPinsUiOnly = arguments?.getString("suite") == "host-pins-ui"; herdrHostUiOnly = arguments?.getString("suite") == "herdr-host-ui"; hostGroupsUiOnly = arguments?.getString("suite") == "host-groups-ui"; insetsOnly = arguments?.getString("suite") == "insets"; mouseOnly = arguments?.getString("suite") == "mouse"; uiOnly = arguments?.getString("suite") in listOf("ui", "ui-dark"); darkUi = arguments?.getString("suite") == "ui-dark"; start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, ProxyTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                if (reviewFixesOnly) {
                    ReviewFixesChecks.run(this, activity as ProxyTestActivity)
                } else if (sftpLinksOnly) {
                    SftpSymlinkChecks.run(this, activity as ProxyTestActivity, sshPort, sshFingerprint)
                } else if (uiPolishOnly) {
                    UiPolishChecks.run(this, activity as ProxyTestActivity)
                } else if (browserLibraryOnly) {
                    BrowserLibraryChecks.run(this)
                } else if (sessionResumeOnly) {
                    SessionResumeUiChecks.run(this)
                } else if (terminalRegressionsOnly) {
                    TerminalRegressionChecks.run(this, activity as ProxyTestActivity)
                } else if (renderingOnly) {
                    TerminalRenderingChecks.run(this, activity as ProxyTestActivity)
                } else if (proOnly) {
                    ProEnforcementChecks.run(this, activity as ProxyTestActivity)
                } else if (billingOnly) {
                    BillingUiChecks.run(this, activity as ProxyTestActivity)
                } else if (herdrControlsOnly) {
                    HerdrControlUiChecks.run(this, activity as ProxyTestActivity)
                } else if (herdrLiveUiOnly) {
                    HerdrLiveUiChecks.run(this, activity as ProxyTestActivity, sshPort, sshFingerprint, herdrExecutable, herdrSession, recovery = herdrRecovery)
                    if (!herdrRecovery) HerdrLifecycleChecks.run(this, listOf(sshPort, sshPort2), sshFingerprint, herdrExecutable, herdrSession)
                } else if (hostRefinementOnly) {
                    HostRefinementUiChecks.run(this, activity as ProxyTestActivity)
                } else if (liveRetryHost.isNotEmpty()) {
                    LiveSshRetryChecks.run(this, liveRetryHost)
                } else if (accessoryOnly) {
                    TerminalAccessoryChecks.run(this, activity as ProxyTestActivity)
                } else if (sshRetryOnly) {
                    check(sshPort > 0 && sshFingerprint.startsWith("SHA256:"))
                    SshRetryChecks.run(this, activity as ProxyTestActivity, sshPort, sshFingerprint)
                } else if (herdrNativeOnly) {
                    SftpLifecycleChecks.run(this)
                    HerdrNativeChecks.run(sshPort, sshFingerprint, herdrExecutable, herdrSession, herdrStress)
                } else if (graphicsOnly) {
                    TerminalGraphicsChecks.run(this, activity as ProxyTestActivity)
                } else if (keyboardOnly) {
                    TerminalAccessoryChecks.run(this, activity as ProxyTestActivity)
                    TerminalGestureChecks.run(this, activity)
                } else if (tailnetProfilesOnly) {
                    TailnetProfilesChecks.run(this)
                } else if (tailnetProfilesUiOnly) {
                    TailnetProfilesUiChecks.run(this, activity as ProxyTestActivity)
                } else if (authOnly) {
                    AuthUiChecks.run(this, activity as ProxyTestActivity)
                } else if (homeOnly) {
                    HomeUiChecks.run(this, activity as ProxyTestActivity)
                } else if (traditionalHostOnly) {
                    TraditionalHostChecks.run(this)
                } else if (hostGroupsOnly) {
                    HostGroupsChecks.run(this)
                } else if (hostPinsOnly) {
                    HostPinsChecks.run(this)
                } else if (hostPinsUiOnly) {
                    HostPinUiChecks.run(this, activity as ProxyTestActivity)
                } else if (herdrHostUiOnly) {
                    HerdrHostUiChecks.run(this, activity as ProxyTestActivity)
                } else if (hostGroupsUiOnly) {
                    HostGroupUiChecks.run(this, activity as ProxyTestActivity)
                } else if (detailsOnly) {
                    DetailsUiChecks.run(this, activity as ProxyTestActivity)
                } else if (insetsOnly) {
                    InsetsUiChecks.run(this, activity as ProxyTestActivity)
                } else if (uiOnly) {
                    UiLayoutChecks.run(this, activity as ProxyTestActivity, darkUi)
                } else if (mouseOnly) {
                    TerminalGestureChecks.run(this, activity)
                } else {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
                hostileApp(activity as ProxyTestActivity)
                exercise(activity, "http://fixture.invalid/page", false)
                exercise(activity, "https://fixture.invalid/page", true)
                exercise(activity, "http://fixture.invalid/page", false, hostOpen = true)
                }
            } finally { runOnMainSync { activity.finish() } }
            result.putString("stream", if (reviewFixesOnly) "PASS: browser proxy disable/enable/retry/profile gating, cursor choices, destructive styles and onboarding.\n" else if (sftpLinksOnly) "PASS: real SFTP folder/file/relative/absolute/chained links, target-preserving saves, preview/download, unavailable links, rename/unlink and arrow badges.\n" else if (uiPolishOnly) "PASS: UI review captures at normal and 200% text size.\n" else if (browserLibraryOnly) "PASS: encrypted browser library, persistence, profile isolation, concurrent visits, retention and cleanup.\n" else if (sessionResumeOnly) "PASS: launcher/Recents return, recreation, independent terminal windows and explicit detach.\n" else if (terminalRegressionsOnly) "PASS: terminal viewport clipping and composed/hardware shortcuts.\n" else if (renderingOnly) "PASS: independent terminal renderer, bounded caches, pixels, composition and selection.\n" else if (proOnly) "PASS: Pro limits, concurrent group admission, tailnet migration/replacement, Room usage and debug controls; no checkout." else if (billingOnly) "PASS: native paywall, cancelled checkout, pending payment, verified unlock, restore and Room persistence; fixture store only.\n" else if (tailnetProfilesOnly) "PASS: profile migration, same-server identities, encrypted SSH/trust/proxy isolation, selection persistence and deletion recovery.\n" else if (tailnetProfilesUiOnly) "PASS: native tailnet switcher, add/rename/remove and switch cancellation.\n" else if (herdrControlsOnly) "PASS: native Herdr agent/workspace/tab controls, action targets, close cancellation and capability gating.\n" else if (herdrLiveUiOnly) "PASS: Herdr live UI input, retained detach, same-session reconnect and close cancellation.\n" else if (hostRefinementOnly) "PASS: host search, direct group filter, bulk assignment, inline group creation, retained Herdr sessions and explicit session selection.\n" else if (liveRetryHost.isNotEmpty()) "PASS: owner-host rejected wrong password then accepted saved credentials in the same runtime; saved profile unchanged.\n" else if (accessoryOnly) "PASS: compact accessory layout, modifiers, arrows and cancellation.\n" else if (sshRetryOnly) "PASS: real SSH password failure, Retry, corrected credentials in the same group/runtime and healthy reattachment.\n" else if (herdrNativeOnly) "PASS: Herdr JNI descriptor ownership, stalled-handshake cancellation, and optional controlled SSH host-key rejection.\n${HerdrNativeChecks.memorySample}\n" else if (graphicsOnly) "PASS: native Kitty RGBA/PNG/zlib/chunks, crop/scale, replacement, clear/delete/reset, alternate screen, scrollback, malformed/oversized/local-transfer rejection and Canvas pixel rendering.\n" else if (keyboardOnly) "PASS: native keyboard modes; Shift, newline, tap/drag arrows, repeat, cancellation and tab disposal; IME/hardware input.\n" else if (authOnly) "PASS: secure auth-key entry, pending/approval states, cancellation, browser handoff, connected completion.\n" else if (homeOnly) "PASS: onboarding navigation, Hosts/Sessions separation, signed-out login.\n" else if (traditionalHostOnly) "PASS: traditional host CRUD, encrypted credentials and route persistence.\n" else if (hostGroupsOnly) "PASS: host group CRUD, membership, scoping and migration.\n" else if (hostPinsOnly) "PASS: host pin membership, scoping, deletion and migration.\n" else if (hostPinsUiOnly) "PASS: pinned host UI ordering and accessible controls.\n" else if (herdrHostUiOnly) "PASS: Herdr host actions for tailnet and traditional hosts.\n" else if (hostGroupsUiOnly) "PASS: host group UI filtering and accessible assignment.\n" else if (detailsOnly) "PASS: local tailnet identity, logout transition and version details.\n" else if (insetsOnly) "PASS: edge-to-edge top bar and SSH password above IME.\n" else if (uiOnly) "PASS: host cards and SSH toolbar.\n" else if (mouseOnly) "PASS: native Ghostty mouse modes; confirmed tap, swipe wheel, hold-drag/cancel, two-finger right click, normal scrollback.\n" else "PASS: second-UID native proxy rejection; WebView authenticated HTTP and HTTPS CONNECT; manual Go, app navigation toggle, retained DOM/history/scroll across app sections, browser tabs, favorites, Find in Page and fullscreen cleanup; no tailnet login used.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message} at ${error.stackTrace.firstOrNull { it.className.startsWith("cc.galaxnet") } ?: error.stackTrace.firstOrNull()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun hostileApp(activity: ProxyTestActivity) {
        cc.galaxnet.novascale.tailnet.AndroidNetworkInterfaces(targetContext).publishNow()
        val directory = java.io.File(targetContext.cacheDir, "proxy-instrumentation-${System.nanoTime()}")
        val node = cc.galaxnet.novascale.gobridge.novatailnet.Novatailnet.newNode(directory.absolutePath, "proxy-fixture", "https://control.invalid")
        try {
            val endpoint = org.json.JSONObject(node.startLocalProxy(0, "fixture", "fixture-password-not-a-real-secret-1234", "fixture"))
            val port = endpoint.getString("address").substringAfterLast(':').toInt()
            runOnMainSync {
                activity.startActivityForResult(Intent().setClassName(context.packageName, "cc.galaxnet.novascale.browser.HostileProxyActivity").putExtra("port", port), 42)
            }
            check(activity.hostileResult.await(30, TimeUnit.SECONDS)) { "Second-app test timed out" }
            check(activity.hostilePassed) { "Second app did not receive authentication rejection" }
            check(activity.hostileUid >= 0 && activity.hostileUid != android.os.Process.myUid()) { "Probe did not run under a different UID" }
        } finally { node.close(); directory.deleteRecursively() }
    }
    private fun exercise(activity: Activity, url: String, connect: Boolean, hostOpen: Boolean = false) {
        // Match MainActivity's manifest contract; the generic test host defaults to pan.
        runOnMainSync {
            activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val realm = "nova-test-${listener.localPort}"
        val password = "fixture-only-not-a-real-credential"
        val expected = "Basic " + Base64.getEncoder().encodeToString("nova:$password".toByteArray())
        val authorized = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val workers = Executors.newCachedThreadPool()
        workers.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                workers.execute {
                    socket.use {
                        it.soTimeout = 10_000
                        val input = it.getInputStream().bufferedReader()
                        val first = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val output = it.getOutputStream()
                        if (headers["proxy-authorization"] != expected) {
                            output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"$realm\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            if (first.startsWith("CONNECT ") != connect) failure.set("Unexpected request method")
                            // CONNECT auth is the gate here; intentionally do not establish TLS.
                            output.write("HTTP/1.1 ${if (connect) "502 Bad Gateway" else "200 OK"}\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                            authorized.countDown()
                        }
                    }
                }
            }
        }
        val libraryDb = androidx.room.Room.inMemoryDatabaseBuilder(targetContext, BrowserLibraryDatabase::class.java).build()
        val libraryAlias = "novascale.browser.fixture.${java.util.UUID.randomUUID()}"
        val library = BrowserLibrary(targetContext, libraryDb, libraryAlias)
        var webView: WebView? = null
        val model = BrowserViewModel().apply { address(url); if (hostOpen) { newTab(url); active.pendingOpen = url } }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val appNavigationVisible = androidx.compose.runtime.mutableStateOf(false)
        try {
            val configured = CountDownLatch(1)
            runOnMainSync {
                ProxyController.getInstance().setProxyOverride(
                    ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${listener.localPort}").removeImplicitRules().build(),
                    activity.mainExecutor,
                ) { configured.countDown() }
            }
            check(configured.await(10, TimeUnit.SECONDS)) { "Proxy setup timed out" }
            val session = ProxyIdentity("fixture", "different-device-id").session(
                cc.galaxnet.novascale.core.LocalProxyEndpoint("fixture", "127.0.0.1:${listener.localPort}", false), "nova", password, realm)
            runOnMainSync {
                if (connect) {
                    webView = createBrowserWebView(activity, session, isCurrent = { true }, changed = { _, _ -> }).apply {
                        activity.setContentView(this); loadUrl(url)
                    }
                } else {
                    (activity as ProxyTestActivity).setContent {
                        cc.galaxnet.novascale.ui.NovaScaleTheme(darkTheme = false) {
                            androidx.compose.material3.Surface(Modifier.safeDrawingPadding()) {
                                BrowserContent(session, { true }, model, visible = visible.value,
                                    appNavigationVisible = appNavigationVisible.value,
                                    onToggleAppNavigation = { appNavigationVisible.value = !appNavigationVisible.value }, library = library,
                                    profileId = "fixture")
                            }
                        }
                    }
                }
            }
            if (!connect && !hostOpen) {
                Thread.sleep(1500)
                check(authorized.count == 1L) { "Browser navigated before Go" }
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "browser-compose-before.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == targetContext.getString(cc.galaxnet.novascale.R.string.browser_go)) return node
                    for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                    return null
                }
                val node = find(uiAutomation.rootInActiveWindow) ?: error("Go button is not visible")
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    sendPointerSync(event); event.recycle()
                }
            }
            check(authorized.await(20, TimeUnit.SECONDS)) { "${if (connect) "CONNECT" else "HTTP"} authentication timed out; ${failure.get()}" }
            check(failure.get() == null) { failure.get().orEmpty() }
            if (!connect) {
                Thread.sleep(500)
                val root = uiAutomation.rootInActiveWindow
                fun hasAddress(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString() == url) return true
                    return (0 until node.childCount).any { hasAddress(node.getChild(it)) }
                }
                check(hasAddress(root)) { "Address bar disappeared after navigation" }
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "browser-compose-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val exited = CountDownLatch(1)
                runOnMainSync {
                    fun findWebView(view: android.view.View): WebView? {
                        if (view is WebView) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val browser = findWebView(activity.window.decorView) ?: error("Missing WebView")
                    browser.webChromeClient!!.onShowCustomView(android.widget.FrameLayout(activity), WebChromeClient.CustomViewCallback { exited.countDown() })
                }
                Thread.sleep(300)
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                check(exited.await(5, TimeUnit.SECONDS)) { "Fullscreen video did not release on Back" }
                fun currentPage(): WebView? {
                    fun find(view: android.view.View): WebView? {
                        if (view is WebView) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    return find(activity.window.decorView)
                }
                var original: WebView? = null
                val first = model.active
                fun tapText(text: String, click: Boolean = true) {
                    fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (node == null) return null
                        // API 33 can cache the old Compose content description after a toggle.
                        node.refresh()
                        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
                        for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                        return null
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
                    var node = find(uiAutomation.rootInActiveWindow)
                    repeat(40) { if (node == null) { Thread.sleep(100); if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache(); node = find(uiAutomation.rootInActiveWindow) } }
                    val bounds = android.graphics.Rect()
                    checkNotNull(node) {
                        uiAutomation.takeScreenshot()?.let { bitmap ->
                            java.io.File(targetContext.cacheDir, "browser-control-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        }
                        val labels = mutableListOf<String>()
                        fun collect(n: android.view.accessibility.AccessibilityNodeInfo?) {
                            if (n == null) return
                            n.text?.let { labels += it.toString() }; n.contentDescription?.let { labels += it.toString() }
                            for (i in 0 until n.childCount) collect(n.getChild(i))
                        }
                        collect(uiAutomation.rootInActiveWindow)
                        "Missing browser control: $text; visible labels=$labels; browsing=${model.active.requestedUrl != null}"
                    }.getBoundsInScreen(bounds)
                    if (!click) return
                    val now = android.os.SystemClock.uptimeMillis()
                    try {
                        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                            sendPointerSync(event); event.recycle()
                        }
                    } catch (error: Exception) {
                        uiAutomation.takeScreenshot()?.let { bitmap ->
                            java.io.File(targetContext.cacheDir, "browser-tap-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        }
                        throw IllegalStateException("Could not tap $text at $bounds", error)
                    }
                    Thread.sleep(400)
                }
                tapText(url)
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_clear_address))
                runOnMainSync {
                    check(model.active.state.value.address.isEmpty()) { "Clear left an address prefix" }
                    check(model.active.requestedUrl != null) { "Clear navigated away from the page" }
                }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.action_cancel))
                Thread.sleep(500)
                runOnMainSync { original = currentPage() }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "browser-menu-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_show_app_navigation))
                check(appNavigationVisible.value) { "App navigation toggle failed" }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_hide_app_navigation))
                check(!appNavigationVisible.value) { "Hide app navigation toggle failed" }
                fun javascript(script: String): String {
                    val done = CountDownLatch(1)
                    var result = ""
                    runOnMainSync { original!!.evaluateJavascript(script) { result = it; done.countDown() } }
                    check(done.await(5, TimeUnit.SECONDS)) { "JavaScript timed out" }
                    return result
                }
                javascript("window.novaPreviewDraft = 'unsaved development preview'; history.pushState({}, '', '/preview'); document.body.style.height='5000px'; window.scrollTo(0, 350)")
                Thread.sleep(300)
                val before = javascript("JSON.stringify([window.novaPreviewDraft, location.pathname, history.length, window.scrollY])")
                runOnMainSync { visible.value = false }
                Thread.sleep(400)
                runOnMainSync { check(currentPage() == null) { "Hidden app section retained visible browser UI" }; visible.value = true }
                Thread.sleep(400)
                runOnMainSync { check(currentPage() === original) { "App section switching recreated WebView" } }
                val after = javascript("JSON.stringify([window.novaPreviewDraft, location.pathname, history.length, window.scrollY])")
                check(before == after) { "App section switch lost DOM, URL, history or scroll position: $before -> $after" }

                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                sendStatus(0, Bundle().apply { putString("stream", "Checking Add favorite dialog.\n") })
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_add_favorite))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.action_save))
                Thread.sleep(600)
                val favorite = kotlinx.coroutines.runBlocking { library.load("fixture").single { it.favorite } }
                check(favorite.url.startsWith("http://fixture.invalid")) { "Favorite lost its address" }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_favorites))
                Thread.sleep(300)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "browser-favorites-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.action_done))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                sendStatus(0, Bundle().apply { putString("stream", "Checking Find in Page.\n") })
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_find))
                fun editable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.isEditable) return node
                    for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                    return null
                }
                checkNotNull(editable(uiAutomation.rootInActiveWindow)).performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "OK") })
                Thread.sleep(700)
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_find_count, 1, 1), click = false)
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_find_next))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_find_previous))
                check(javascript("window.scrollY").toDouble() < 100) { "Find controls did not reveal the match" }
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "browser-find-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.action_done))
                tapText(targetContext.resources.getQuantityString(cc.galaxnet.novascale.R.plurals.browser_tab_count, 1, 1))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_new_tab))
                runOnMainSync { check(model.tabs.size == 2) { "New tab button did not create a tab" } }
                Thread.sleep(400)
                runOnMainSync { check(currentPage() == null) { "Blank tab loaded a page" }; model.select(first) }
                Thread.sleep(400)
                runOnMainSync { check(currentPage() === original) { "Switching tabs recreated the page and lost history" } }
                runOnMainSync { model.newTab("http://fixture.invalid/second"); model.active.requestedUrl = model.open() }
                Thread.sleep(600)
                runOnMainSync { check(currentPage() != null && currentPage() !== original) { "Second tab did not get an independent WebView" }; model.select(first) }
                Thread.sleep(400)
                runOnMainSync { check(currentPage() === original) { "First tab was not retained" } }
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_actions))
                tapText(targetContext.getString(cc.galaxnet.novascale.R.string.browser_close_tab))
                runOnMainSync { check(first !in model.tabs && model.tabs.size == 2) { "Close tab did not remove the selected tab" } }


            }
        } finally {
            val cleared = CountDownLatch(1)
            runOnMainSync {
                if (!connect) (activity as ProxyTestActivity).setContent { }
                webView?.destroy()
                ProxyController.getInstance().clearProxyOverride(activity.mainExecutor) { cleared.countDown() }
            }
            cleared.await(10, TimeUnit.SECONDS)
            listener.close(); workers.shutdownNow()
            libraryDb.close()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(libraryAlias) }
        }
    }
}
