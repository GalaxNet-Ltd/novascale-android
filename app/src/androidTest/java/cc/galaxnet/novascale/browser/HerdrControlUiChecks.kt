/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.os.Bundle
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.herdr.*
import cc.galaxnet.novascale.terminalfeature.TerminalThemeCatalog
import cc.galaxnet.novascale.terminalfeature.chromeColors
import cc.galaxnet.novascale.ui.NovaScaleTheme
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

internal object HerdrControlUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (!node.refresh()) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(test.uiAutomation.rootInActiveWindow)
        }
        fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(text: String): AccessibilityNodeInfo {
            repeat(60) { label(text)?.let { return it }; Thread.sleep(100) }
            error("Missing Herdr control: $text")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = waitFor(text)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            test.waitForIdleSync(); Thread.sleep(200)
        }
        fun enter(text: String) {
            check(find { it.isEditable }?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }) == true)
            test.waitForIdleSync()
        }
        fun screenshot(name: String) {
            Thread.sleep(250)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun text(id: Int) = activity.getString(id)
        val projection = HerdrNavigation.parse(JSONObject("""{
          "ready":true,"boot_id":"fixture","methods":["tab.focus","tab.create","tab.rename","tab.close","workspace.focus","workspace.create","pane.focus"],
          "workspaces":[{"workspace_id":"one","label":"Development","focused":true},{"workspace_id":"two","label":"Operations"}],
          "tabs":[{"tab_id":"first","workspace_id":"one","label":"Shell","focused":true},{"tab_id":"second","workspace_id":"one","label":"Review"},{"tab_id":"third","workspace_id":"two","label":"Hidden tab"}],
          "agents":[{"pane_id":"agent-a","workspace_id":"one","tab_id":"first","name":"Build reviewer","agent_status":"working","focused":true},
            {"pane_id":"agent-b","workspace_id":"two","tab_id":"third","title":"Deployment review","agent_status":"blocked"}]
        }"""))
        check(projection.visibleTabs.size == 2 && projection.agents.last().label == "Deployment review")
        val state = mutableStateOf(projection)
        val busy = mutableStateOf(false)
        val keyboardField = mutableStateOf(false)
        val terminalTop = java.util.concurrent.atomic.AtomicInteger(-1)
        val controlsHeight = java.util.concurrent.atomic.AtomicInteger(-1)
        val sent = AtomicReference<HerdrAction?>()
        val colors = TerminalThemeCatalog.default.chromeColors()
        test.runOnMainSync {
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            activity.setContent { NovaScaleTheme(darkTheme = false) {
            Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.foreground) {
                Column(Modifier.safeDrawingPadding()) { Box(Modifier.onGloballyPositioned { controlsHeight.set(it.size.height) }) {
                    HerdrNativeControls(state.value, busy.value, "Fixture host", "daily", colors,
                    onAction = { action, boot -> check(boot == "fixture"); sent.set(action) },
                    onDetach = {}, onKeyboard = {}, onClose = {}, onReconnect = {}) }
                    Box(Modifier.fillMaxSize().onGloballyPositioned { terminalTop.set(it.positionInWindow().y.toInt()) }) {
                        if (keyboardField.value) OutlinedTextField("", {}, label = { Text("Fixture keyboard") })
                    }
                }
            }
        } } }
        waitFor("Shell"); waitFor("Review")
        val beforeAction = terminalTop.get()
        check(beforeAction >= 0)
        val chromeDp = controlsHeight.get() / activity.resources.displayMetrics.density
        check(chromeDp in 97f..99f) { "Herdr header and tab row use $chromeDp dp" }
        test.runOnMainSync { busy.value = true }
        test.waitForIdleSync(); Thread.sleep(250)
        check(terminalTop.get() == beforeAction) { "Navigation progress moved/resized the terminal" }
        test.runOnMainSync { busy.value = false }
        test.waitForIdleSync(); Thread.sleep(250)
        check(terminalTop.get() == beforeAction) { "Navigation completion moved/resized the terminal" }
        check(label("Hidden tab") == null)
        screenshot("native-herdr-controls.png")
        click("Review")
        check(sent.get() == HerdrAction("tab.focus", "second"))
        click(text(R.string.herdr_actions)); click(text(R.string.herdr_agents))
        waitFor("Deployment review"); waitFor(text(R.string.herdr_agent_working) + " · Shell")
        screenshot("native-herdr-agents.png")
        click("Deployment review")
        check(sent.get() == HerdrAction("pane.focus", "agent-b"))
        click(text(R.string.herdr_workspaces)); click("Operations")
        check(sent.get() == HerdrAction("workspace.focus", "two"))
        click(text(R.string.herdr_workspaces)); click(text(R.string.herdr_workspace_new))
        check(sent.get() == HerdrAction("workspace.create", "one"))
        click(text(R.string.herdr_tab_new))
        check(sent.get() == HerdrAction("tab.create", "one"))
        click(activity.getString(R.string.herdr_tab_options, "Shell")); click(text(R.string.herdr_tab_rename))
        enter("Renamed"); click(text(R.string.action_save))
        check(sent.get() == HerdrAction("tab.rename", "first", "Renamed"))
        sent.set(null)
        click(activity.getString(R.string.herdr_tab_options, "Shell")); click(text(R.string.herdr_tab_close))
        waitFor(text(R.string.herdr_tab_close_help)); click(text(R.string.action_cancel))
        check(sent.get() == null)
        test.runOnMainSync { state.value = projection.copy(methods = emptySet()) }
        test.waitForIdleSync(); Thread.sleep(200)
        var disabled: AccessibilityNodeInfo? = waitFor(text(R.string.herdr_tab_new))
        while (disabled != null && !disabled.isClickable) disabled = disabled.parent
        check(disabled?.isEnabled == false) { "Unsupported create action remained enabled" }
        test.runOnMainSync { state.value = projection; keyboardField.value = true }
        repeat(3) {
            click("Fixture keyboard")
            var imeVisible = false
            for (attempt in 0 until 50) {
                test.runOnMainSync {
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (imeVisible) break
                Thread.sleep(100)
            }
            check(imeVisible) { "Fixture keyboard did not open" }
            click(text(R.string.herdr_workspaces))
            Thread.sleep(500)
            val action = waitFor(text(R.string.herdr_workspace_new))
            val bounds = Rect().also(action::getBoundsInScreen)
            check(action.isVisibleToUser && bounds.height() > 0 && bounds.bottom < activity.resources.displayMetrics.heightPixels) {
                "Workspace action moved off-screen after opening from the keyboard: $bounds"
            }
            click(text(R.string.action_done))
        }
    }
}
