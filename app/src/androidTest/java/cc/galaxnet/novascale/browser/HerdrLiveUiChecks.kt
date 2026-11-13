/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.Bundle
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.herdr.HerdrController
import cc.galaxnet.novascale.herdr.HerdrScreen
import cc.galaxnet.novascale.terminal.NovaTerminalView
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.terminalfeature.TerminalSettings
import cc.galaxnet.novascale.terminalfeature.TerminalThemeCatalog
import cc.galaxnet.novascale.ui.NovaScaleTheme
import kotlinx.coroutines.*
import cc.galaxnet.novascale.billing.*

/** Only used with scripts/test-herdr-native.py's private SSH/XDG fixture. */
internal object HerdrLiveUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity, port: Int, fingerprint: String, executable: String, name: String, recovery: Boolean = false) {
        require(port > 0 && fingerprint.startsWith("SHA256:") && executable.isNotBlank() && name.startsWith("novascale-test-"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val backend = (test.targetContext.applicationContext as NovaScaleApplication).tailnetBackend
        var ledger: HerdrUsageRecord? = null
        val usageClock = java.util.concurrent.atomic.AtomicLong(0)
        val paywall = PaywallManager(UnavailableBillingStore(), PurchaseVerifier { error("Fixture only") },
            object : EntitlementCache {
                override suspend fun read(): CachedEntitlement? = null
                override suspend fun write(value: CachedEntitlement) = Unit
            }, scope, configured = false)
        val usage = HerdrUsageManager(object : HerdrUsageStore {
            override suspend fun read() = ledger
            override suspend fun write(value: HerdrUsageRecord) { ledger = value }
        }, paywall, scope, { 100 }, usageClock::get)
        val controller = HerdrController(test.targetContext, backend, scope, usage, "isolated-live-fixture")
        val notices = java.util.concurrent.atomic.AtomicInteger(0)
        scope.launch { controller.notices.collect { notices.incrementAndGet() } }
        val config = RemoteConnectionConfig(host = "127.0.0.1", port = port.toString(), username = "fixture",
            transport = SshTransport.DIRECT, authenticationKind = AuthenticationKind.NONE)
        val knownHosts = KnownHostStore(test.targetContext)
        val previousKey = knownHosts.fingerprint(config)
        val showing = mutableStateOf(true)
        val appearance = mutableStateOf(TerminalSettings())
        val theme = mutableStateOf(TerminalThemeCatalog.default)
        fun until(message: String, check: () -> Boolean) {
            repeat(300) { if (check()) return; Thread.sleep(100) }
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "round2-herdr-live-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            error("Herdr live UI timed out: $message")
        }
        fun find(label: String, unselectedAction: Boolean = false): AccessibilityNodeInfo? {
            fun actionable(node: AccessibilityNodeInfo): Boolean {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) {
                    if (target.isSelected || target.isChecked) return false
                    target = target.parent
                }
                return target?.let { it.isEnabled && !it.isSelected && !it.isChecked } == true
            }
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (!node.refresh()) return null
                if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                    (!unselectedAction || actionable(node))) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun click(label: String, settle: Boolean = true, unselectedAction: Boolean = false) {
            repeat(60) {
                var node = find(label, unselectedAction)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync()
                    if (settle) Thread.sleep(200)
                    return
                }
                Thread.sleep(100)
            }
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "round2-herdr-live-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val tree = StringBuilder()
            fun dump(node: AccessibilityNodeInfo?, depth: Int = 0) {
                node ?: return
                node.refresh()
                val bounds = Rect().also(node::getBoundsInScreen)
                tree.appendLine("${" ".repeat(depth)}${node.className}: ${node.text} / ${node.contentDescription}; " +
                    "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
                    "selected=${node.isSelected} checked=${node.isChecked} bounds=$bounds")
                for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
            }
            dump(test.uiAutomation.rootInActiveWindow)
            java.io.File(activity.cacheDir, "round2-herdr-live-failure.txt").writeText(tree.toString())
            error("Cannot click $label; pending=${controller.state.value.actionPending}, ready=${controller.state.value.navigation.ready}, tabs=${controller.state.value.navigation.visibleTabs.map { it.label }}")
        }
        fun enter(text: String) {
            fun field(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.isEditable) return node
                for (i in 0 until node.childCount) field(node.getChild(i))?.let { return it }
                return null
            }
            val input = field(test.uiAutomation.rootInActiveWindow) ?: error("Missing rename field")
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            test.waitForIdleSync()
        }
        fun tapSelectedTab(label: String) {
            // Android accessibility omits ACTION_CLICK on an already selected tab.
            // A physical tap still reaches selectable and exercises the no-op endpoint action.
            val node = checkNotNull(find(label))
            check(node.isSelected && node.isEnabled)
            val bounds = Rect().also(node::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            test.waitForIdleSync()
        }
        fun ready() {
            var stableTarget: cc.galaxnet.novascale.herdr.HerdrInputTarget? = null
            var stableSince = SystemClock.uptimeMillis()
            until("coherent terminal after layout settles") { controller.state.value.let {
                check(it.actionError == null) { "Native endpoint action failed" }
                val coherent = it.connected && !it.recovering && it.snapshotTarget != null && it.navigation.ready && it.actionPending == null
                if (!coherent || stableTarget != it.snapshotTarget) {
                    stableTarget = it.snapshotTarget
                    stableSince = SystemClock.uptimeMillis()
                }
                coherent && SystemClock.uptimeMillis() - stableSince >= 500
            } }
        }
        fun terminalView(): NovaTerminalView {
            fun visit(view: View): NovaTerminalView? {
                if (view is NovaTerminalView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))?.let { return it }
                return null
            }
            return checkNotNull(visit(activity.window.decorView))
        }
        fun imeVisible(): Boolean {
            var visible = false
            test.runOnMainSync { visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return visible
        }
        fun keyboardAndAccessory(visible: Boolean) {
            until("keyboard and accessory ${if (visible) "shown" else "hidden"} together") {
                imeVisible() == visible && (find(activity.getString(R.string.cd_hide_keyboard)) != null) == visible
            }
            test.waitForIdleSync()
        }
        fun focusedTerminal() {
            until("terminal receives initial input focus") {
                var focused = false
                test.runOnMainSync { focused = terminalView().isFocused }
                focused
            }
        }
        fun captureKeyboard(name: String) {
            test.uiAutomation.takeScreenshot()?.let { image ->
                java.io.File(activity.cacheDir, "herdr-keyboard-$name.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            } ?: error("Keyboard screenshot unavailable")
        }
        fun swipeHistory(older: Boolean) {
            val bounds = Rect()
            test.runOnMainSync { check(terminalView().getGlobalVisibleRect(bounds)) }
            val down = SystemClock.uptimeMillis()
            val start = bounds.top + bounds.height() * if (older) .2f else .8f
            val end = bounds.top + bounds.height() * if (older) .8f else .2f
            for (step in 0..9) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 9 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), start + (end - start) * step / 9, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(event, true)); event.recycle()
                SystemClock.sleep(16)
            }
        }
        fun wheelHistory(up: Boolean) {
            test.runOnMainSync {
                val view = terminalView()
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = view.width / 2f; y = view.height / 2f
                    setAxisValue(MotionEvent.AXIS_VSCROLL, if (up) 1f else -1f)
                }
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(pointer), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                check(view.dispatchGenericMotionEvent(event)); event.recycle()
            }
        }
        try {
            // The expected key comes from the newly generated fixture listener,
            // never from an unverified remote connection.
            knownHosts.trust(config, fingerprint)
            test.runOnMainSync {
                // Match MainActivity so the IME resizes the production screen
                // instead of intercepting swipes in a covered terminal area.
                activity.enableEdgeToEdge()
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                controller.connect(config, executable, name)
                activity.setContent { NovaScaleTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (showing.value) HerdrScreen(controller, config, appearance.value, theme.value,
                            onDetach = { showing.value = false }, onClose = { controller.close(); showing.value = false }, hostName = "Local Herdr fixture")
                        else Text("Detached fixture")
                    }
                } }
            }
            ready()
            until("connected foreground visit active") { usage.state.value.reserved == 1 && usage.state.value.activeVisits == 1 }
            usageClock.addAndGet(30_000)
            runBlocking { usage.tick() }
            check(usage.state.value.used == 1) { "Connected foreground usage was not charged" }
            val visitId = checkNotNull(ledger).visits.keys.single()
            focusedTerminal()
            // Phone keyboards open on entry. Emulator/attached alphabetic
            // keyboards may keep the IME hidden; neither may leave an orphan row.
            val hardwareKeyboard = InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true
            }
            keyboardAndAccessory(if (hardwareKeyboard) imeVisible() else true)
            click(activity.getString(R.string.herdr_keyboard))
            keyboardAndAccessory(true)
            // Inputs aimed at an obsolete surface during IME resize are discarded.
            ready()
            captureKeyboard("shown")
            val keyboardSession = controller.state.value.snapshotTarget!!.sessionId
            var keyboardHeight = 0
            test.runOnMainSync { keyboardHeight = terminalView().height }
            click("Ctl")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            keyboardAndAccessory(false)
            ready()
            check(showing.value && controller.state.value.snapshotTarget?.sessionId == keyboardSession) {
                "Dismissing the keyboard detached Herdr"
            }
            test.runOnMainSync { check(terminalView().height > keyboardHeight) { "Hidden keyboard left unused terminal space" } }
            captureKeyboard("hidden")
            SessionResumeUiChecks.backgroundAndResume(test, activity)
            ready()
            keyboardAndAccessory(false)
            click(activity.getString(R.string.herdr_keyboard))
            keyboardAndAccessory(true)
            check(find("Ctl")?.isSelected == false) { "Hidden accessory retained its one-shot Ctrl modifier" }
            click(activity.getString(R.string.cd_hide_keyboard))
            keyboardAndAccessory(false)
            click(activity.getString(R.string.herdr_keyboard))
            keyboardAndAccessory(true)
            ready()
            // Escape starts a readline Meta chord; Ctrl+C clears it before
            // typing a command so the fixture does not invoke history search.
            click("Esc")
            ready()
            click("Ctl")
            ready()
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_C)
            test.waitForIdleSync()
            until("accessory Ctrl+C acknowledged") { controller.state.value.snapshot?.accessibleText()?.contains("^C") == true }
            ready()
            Thread.sleep(300)
            // End the marker with a newline so a shell prompt redraw on tab
            // reactivation cannot overwrite a partial output line.
            test.sendStringSync("echo NOVA''SCALE_UI")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            until("hardware/accessory input output") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCALE_UI") == true }
            val beforeBackground = checkNotNull(controller.state.value.snapshotTarget).sessionId
            SessionResumeUiChecks.backgroundAndResume(test, activity)
            ready()
            check(showing.value && controller.state.value.snapshotTarget?.sessionId == beforeBackground) {
                "Backgrounding detached or replaced the Herdr session"
            }
            check(ledger?.visits?.keys?.single() == visitId && usage.state.value.used == 1) {
                "Foreground return started another metered visit"
            }
            if (recovery) {
                test.runOnMainSync { controller.send("NOVA_BG_PROOF=retained\r".toByteArray(), controller.state.value.snapshotTarget) }
                fun typeAndSee(marker: String) {
                    ready()
                    test.runOnMainSync {
                        val view = terminalView()
                        view.requestTerminalInput(showSoftwareKeyboard = false)
                        check(view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                            .commitText("echo RESUME''_$marker'_'${'$'}NOVA_BG_PROOF\r", 1))
                    }
                    until("input after background: $marker") {
                        controller.state.value.snapshot?.accessibleText()?.contains("RESUME_${marker}_retained") == true
                    }
                }
                SessionResumeUiChecks.backgroundAndResume(test, activity, backgroundMs = 65_000)
                ready()
                check(controller.state.value.snapshotTarget?.sessionId == beforeBackground) { "Healthy idle bridge was replaced" }
                typeAndSee("HEALTHY")
                // The isolated SSH server then discards this bridge's data while
                // continuing to answer SSH keepalives. A new bridge is unaffected.
                test.runOnMainSync { controller.send(
                    "(sleep 1; touch nova_fixture_stall; echo STALLED) &\r".toByteArray(), controller.state.value.snapshotTarget) }
                SessionResumeUiChecks.backgroundAndResume(test, activity, backgroundMs = 10_000)
                until("stalled bridge replaced after foreground") {
                    controller.state.value.let { it.connected && it.snapshotTarget?.sessionId?.let { id -> id != beforeBackground } == true }
                }
                typeAndSee("RECOVERED")
                val recoveredId = controller.state.value.snapshotTarget!!.sessionId
                test.runOnMainSync { controller.send(
                    "(sleep 1; touch nova_fixture_stall; echo STALLED) &\r".toByteArray(), controller.state.value.snapshotTarget) }
                SessionResumeUiChecks.backgroundAndResume(test, activity, backgroundMs = 2_500)
                until("foreground probe started") { controller.state.value.recovering }
                test.runOnMainSync { showing.value = false }
                test.waitForIdleSync()
                Thread.sleep(6_000)
                check(!controller.state.value.recovering && controller.state.value.connected &&
                    controller.state.value.snapshotTarget?.sessionId.let { it == null || it == recoveredId }) {
                    "Detached screen continued automatic recovery"
                }
                test.runOnMainSync { showing.value = true }
                until("reattached screen recovered") {
                    controller.state.value.let { it.connected && !it.recovering && it.snapshotTarget?.sessionId?.let { id -> id != recoveredId } == true }
                }
                typeAndSee("REATTACHED")
                check(showing.value && controller.state.value.sessionName == name)
                check(ledger?.visits?.keys?.single() == visitId && usage.state.value.used == 1) { "Recovery consumed another daily visit" }
                return
            }
            test.runOnMainSync { controller.send("python3 nova_fixture_colors.py\r".toByteArray(), controller.state.value.snapshotTarget) }
            fun colorRun(marker: String) = controller.state.value.snapshot?.rows?.flatMap { it.runs }?.firstOrNull { marker in it.text }
            until("dark startup colors") { colorRun("NOVA_CONTRAST")?.foregroundArgb == theme.value.foreground }
            val explicit = checkNotNull(colorRun("NOVA_EXPLICIT"))
            val light = TerminalThemeCatalog.resolve("nova_light", emptyList())
            test.runOnMainSync { theme.value = light }
            until("theme updates retained terminal") { controller.state.value.snapshot?.defaultBackgroundArgb == light.background }
            until("normal and faint text remain readable after a live theme change") {
                val normal = colorRun("NOVA_CONTRAST") ?: return@until false
                val dim = colorRun("NOVA_DIM") ?: return@until false
                val dimColor = android.graphics.Color.rgb(
                    android.graphics.Color.red(dim.foregroundArgb) * 2 / 3,
                    android.graphics.Color.green(dim.foregroundArgb) * 2 / 3,
                    android.graphics.Color.blue(dim.foregroundArgb) * 2 / 3)
                normal.backgroundArgb == 0xff303959.toInt() && dim.faint &&
                    ColorUtils.calculateContrast(normal.foregroundArgb, normal.backgroundArgb) >= 4.5 &&
                    ColorUtils.calculateContrast(dimColor, dim.backgroundArgb) >= 3.0
            }
            check(colorRun("NOVA_EXPLICIT")?.let { it.foregroundArgb == explicit.foregroundArgb && it.backgroundArgb == explicit.backgroundArgb } == true)
            test.runOnMainSync { appearance.value = appearance.value.copy(herdrImproveTextContrast = false) }
            until("contrast off restores original default foreground") { colorRun("NOVA_CONTRAST")?.foregroundArgb == light.foreground }
            test.runOnMainSync { appearance.value = appearance.value.copy(herdrImproveTextContrast = true) }
            until("contrast on repaints without new remote output") { colorRun("NOVA_CONTRAST")?.foregroundArgb?.let { it != light.foreground } == true }
            test.runOnMainSync { theme.value = TerminalThemeCatalog.default }
            until("returning to dark theme restores original foreground") { colorRun("NOVA_CONTRAST")?.foregroundArgb == theme.value.foreground }
            ready()
            test.runOnMainSync { controller.send("seq 1 200; echo NOVA''SCROLL_BOTTOM\r".toByteArray(), controller.state.value.snapshotTarget) }
            until("scroll fixture output") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCROLL_BOTTOM") == true }
            swipeHistory(older = true)
            until("finger swipe reveals remote history") {
                val text = controller.state.value.snapshot?.accessibleText().orEmpty()
                text.isNotEmpty() && "NOVASCROLL_BOTTOM" !in text
            }
            val afterSwipe = controller.state.value.snapshot!!.accessibleText()
            wheelHistory(up = true)
            until("pointer wheel reveals older remote history") { controller.state.value.snapshot?.accessibleText() != afterSwipe }
            fun firstHistoryRow(): Int? = controller.state.value.snapshot?.accessibleText()?.lineSequence()
                ?.map { it.trim { character -> character.isWhitespace() || character in '\u2500'..'\u259f' } }
                ?.mapNotNull { it.toIntOrNull() }?.firstOrNull()
            val historyStart = checkNotNull(firstHistoryRow()) {
                "Expected numbered fixture history: " + controller.state.value.snapshot?.accessibleText()?.take(800)
            }
            // Rapid events share stale displayed surfaces. Every three-line wheel
            // tick must accumulate on the server, including a direction reversal.
            repeat(12) { wheelHistory(up = true) }
            until("rapid scroll preserves every relative row") { firstHistoryRow() == historyStart - 36 }
            repeat(4) { wheelHistory(up = false) }
            repeat(2) { wheelHistory(up = true) }
            until("direction reversal preserves input order") { firstHistoryRow() == historyStart - 30 }
            repeat(10) { wheelHistory(up = false) }
            until("wheel round trip restores the initial history position") { firstHistoryRow() == historyStart }
            repeat(4) { swipeHistory(older = false) }
            until("swipe returns to live bottom") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCROLL_BOTTOM") == true }
            test.runOnMainSync { controller.send("python3 nova_fixture_mouse.py\r".toByteArray(), controller.state.value.snapshotTarget) }
            until("mouse reporting app ready") { controller.state.value.snapshot?.accessibleText()?.contains("APP_WHEEL_READY") == true }
            wheelHistory(up = true)
            until("application received semantic wheel input") { controller.state.value.snapshot?.accessibleText()?.contains("APP_WHEEL_OK") == true }
            test.runOnMainSync { controller.send("echo NOVA''SCALE_UI\r".toByteArray(), controller.state.value.snapshotTarget) }
            until("input remains usable after scrolling") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCALE_UI") == true }
            val initialWorkspace = checkNotNull(controller.state.value.navigation.workspace)
            val originalTab = controller.state.value.navigation.visibleTabs.single()
            // The server initially gives the workspace and first tab the same directory label.
            // Use a unique fixture label so accessibility clicks cannot open the header's agent picker.
            click(activity.getString(R.string.herdr_tab_options, originalTab.label))
            click(activity.getString(R.string.herdr_tab_rename))
            enter("Original tab")
            click(activity.getString(R.string.action_save))
            ready()
            val initialTab = originalTab.copy(label = "Original tab")
            click(activity.getString(R.string.herdr_tab_new))
            ready()
            until("new native tab") { controller.state.value.navigation.visibleTabs.size == 2 }
            val newTab = controller.state.value.navigation.visibleTabs.first { it.id != initialTab.id }
            click(activity.getString(R.string.herdr_tab_options, newTab.label))
            click(activity.getString(R.string.herdr_tab_rename))
            enter("Review tab")
            click(activity.getString(R.string.action_save))
            ready()
            until("renamed remote tab") { controller.state.value.navigation.tabs.any { it.id == newTab.id && it.label == "Review tab" } }
            click(activity.getString(R.string.herdr_keyboard))
            ready()
            test.sendStringSync("echo NATIVE''_TABS")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            until("input after native navigation") { controller.state.value.snapshot?.accessibleText()?.contains("NATIVE_TABS") == true }
            click(activity.getString(R.string.herdr_workspaces))
            click(activity.getString(R.string.herdr_workspace_new))
            ready()
            until("new native workspace") { controller.state.value.navigation.workspaces.size == 2 }
            val otherWorkspace = checkNotNull(controller.state.value.navigation.workspace)
            val lastTab = controller.state.value.navigation.visibleTabs.single()
            click(activity.getString(R.string.herdr_tab_options, lastTab.label))
            click(activity.getString(R.string.herdr_tab_close))
            until("last-tab warning") { find(activity.getString(R.string.herdr_tab_close_last_help)) != null }
            click(activity.getString(R.string.action_cancel))
            check(controller.state.value.navigation.visibleTabs.single().id == lastTab.id)
            click(activity.getString(R.string.herdr_workspaces))
            click(initialWorkspace.label, unselectedAction = true)
            ready()
            until("workspace restored") { controller.state.value.navigation.workspace?.id == initialWorkspace.id }
            val noticesBeforeSwitching = notices.get()
            // Type through the actual editor as soon as the new displayed target
            // is ready, without ready()'s layout-settling delay. Controller state
            // may be ahead of the AndroidView and its input connection.
            val inputBinding = NovaTerminalView::class.java.getDeclaredField("inputBinding").apply { isAccessible = true }
            val inputConnection = NovaTerminalView::class.java.getDeclaredField("connection").apply { isAccessible = true }
            fun typeAfterSwitch(marker: String, focused: () -> Boolean) {
                until("new window input target $marker") {
                    var submitted = false
                    test.runOnMainSync {
                        val current = controller.state.value
                        val target = current.snapshotTarget
                        val view = terminalView()
                        if (focused() && current.actionPending == null && current.navigation.ready && target != null &&
                            inputBinding.get(view) == target && view.hasWindowFocus() && view.isFocused) {
                            // The keyboard may remain hidden after dismissing a sheet.
                            // Simulate its editor handshake in that case, then use the
                            // view's bound input callback rather than the controller.
                            val ime = inputConnection.get(view) as? android.view.inputmethod.InputConnection
                                ?: view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                            submitted = ime.commitText("echo FAST''_$marker\r", 1)
                        }
                    }
                    submitted
                }
                until("immediate input reached $marker") { controller.state.value.snapshot?.accessibleText()?.contains("FAST_$marker") == true }
                check(notices.get() == noticesBeforeSwitching) { "Switching windows rejected input with a retry notice" }
            }
            repeat(3) { index ->
                click(initialTab.label, settle = false, unselectedAction = true)
                typeAfterSwitch("FIRST_$index") { controller.state.value.navigation.visibleTabs.any { it.id == initialTab.id && it.focused } }
                click("Review tab", settle = false)
                typeAfterSwitch("SECOND_$index") { controller.state.value.navigation.visibleTabs.any { it.id == newTab.id && it.focused } }
                tapSelectedTab("Review tab")
                typeAfterSwitch("SAME_$index") { controller.state.value.navigation.visibleTabs.any { it.id == newTab.id && it.focused } }
                click(activity.getString(R.string.herdr_workspaces))
                // Both fixture workspaces use the same directory name. Resolve the
                // label's selectable parent and choose the unchecked row.
                click(otherWorkspace.label, settle = false, unselectedAction = true)
                typeAfterSwitch("OTHER_$index") { controller.state.value.navigation.workspace?.id == otherWorkspace.id }
                click(activity.getString(R.string.herdr_workspaces))
                click(initialWorkspace.label, settle = false, unselectedAction = true)
                typeAfterSwitch("BACK_$index") { controller.state.value.navigation.workspace?.id == initialWorkspace.id }
            }
            click(activity.getString(R.string.herdr_tab_options, "Review tab"))
            click(activity.getString(R.string.herdr_tab_close))
            click(activity.getString(R.string.action_close))
            ready()
            until("remote tab closed") { controller.state.value.navigation.tabs.none { it.id == newTab.id } }
            check(controller.state.value.navigation.visibleTabs.single().id == initialTab.id)
            until("original pane preserved") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCALE_UI") == true }
            click(activity.getString(R.string.herdr_tab_new))
            ready()
            until("shell-exit fixture tab") { controller.state.value.navigation.visibleTabs.size == 2 }
            val exitingTab = controller.state.value.navigation.visibleTabs.first { it.id != initialTab.id }
            click(activity.getString(R.string.herdr_tab_options, exitingTab.label))
            click(activity.getString(R.string.herdr_tab_rename))
            enter("Exit fixture")
            click(activity.getString(R.string.action_save))
            ready()
            test.runOnMainSync { controller.send("exit\r".toByteArray(), controller.state.value.snapshotTarget) }
            until("exited Herdr shell removed from navigation") { controller.state.value.navigation.tabs.none { it.id == exitingTab.id } }
            until("exited Herdr tab removed from bar") { find("Exit fixture") == null }
            check(controller.state.value.navigation.visibleTabs.single().id == initialTab.id)
            ready()
            val original = controller.state.value.snapshotTarget!!.sessionId
            repeat(2) { pass ->
                val marker = "NOVASCALE_BOTTOM_$pass"
                ready()
                focusedTerminal()
                keyboardAndAccessory(if (hardwareKeyboard) imeVisible() else true)
                test.runOnMainSync {
                    controller.send("python3 nova_fixture_scroll.py $pass\r".toByteArray(), controller.state.value.snapshotTarget)
                }
                until("latest fixture output $pass") { controller.state.value.snapshot?.accessibleText()?.contains(marker) == true }
                click(activity.getString(R.string.herdr_detach))
                until("detach UI") { find("Detached fixture") != null }
                check(controller.state.value.connected) { "Detach closed the connection" }
                until("remote client scrolled the hidden pane $pass") {
                    val text = controller.state.value.snapshot?.accessibleText().orEmpty()
                    "HISTORY_ROW_" in text && marker !in text
                }
                test.runOnMainSync { showing.value = true }
                until("retained reopen shows latest output $pass") {
                    controller.state.value.snapshot?.accessibleText()?.contains(marker) == true
                }
                ready()
                check(controller.state.value.snapshotTarget!!.sessionId == original) { "UI return replaced the retained connection" }
                check(checkNotNull(ledger).visits.keys.single() == visitId && usage.state.value.used == 1) { "Retained reopen charged a new visit" }
            }
            // Replacing the transport for recovery retains the logical paid visit.
            test.runOnMainSync { controller.reconnect(config) }
            ready()
            check(checkNotNull(ledger).visits.keys.single() == visitId && usage.state.value.used == 1)
            // Exercise the production reconnect button after a controlled local
            // transport close. The selected name and non-default executable must survive.
            test.runOnMainSync { controller.close() }
            click(activity.getString(R.string.herdr_reconnect))
            ready()
            check(checkNotNull(ledger).visits.keys.single() != visitId) { "Explicit close reused an ended visit" }
            check(usage.state.value.used == 1 && usage.state.value.reserved == 1)
            check(controller.state.value.sessionName == name && controller.executable == executable) { "Reconnect changed session settings" }
            check(controller.state.value.snapshotTarget!!.sessionId != original)
            until("same remote pane after reconnect") { controller.state.value.snapshot?.accessibleText()?.contains("NOVASCALE_BOTTOM_1") == true }
            // The accessory belongs to the IME, including after a new terminal
            // view is mounted on a device with an attached hardware keyboard.
            click(activity.getString(R.string.herdr_keyboard))
            keyboardAndAccessory(true)
            ready()
            Thread.sleep(300)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, "round2-herdr-live.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click(activity.getString(R.string.cd_close_terminal))
            click(activity.getString(R.string.action_cancel))
            check(controller.state.value.connected) { "Cancelling close disconnected" }
        } finally {
            test.runOnMainSync { controller.close() }
            // Native cleanup suspends on the IO dispatcher; let it complete
            // before ending the scope and removing the temporary key.
            runBlocking { delay(300) }
            scope.cancel()
            if (previousKey == null) knownHosts.forget(config) else knownHosts.trust(config, previousKey)
        }
        HerdrAppearanceChecks.run(test, activity)
    }
}
