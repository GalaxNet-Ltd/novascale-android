// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.SshSettingsScreen
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.ui.NovaScaleTheme

/** Real edge-to-edge layout and system IME, without a network or saved credentials. */
internal object InsetsUiChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val peer = TailnetPeer("insets-fixture", "keyboard-fixture", "keyboard-fixture.example.ts.net", listOf("100.64.0.43"), true, true, "linux")
        test.runOnMainSync {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.setContent {
                NovaScaleTheme(darkTheme = false) {
                    Scaffold(topBar = { NovaScaleTopBar("SSH Settings", true, {}) }, bottomBar = {
                        NavigationBar { NavigationBarItem(selected = true, onClick = {}, icon = { Text("H") }, label = { Text("Home") }) }
                    }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { SshSettingsScreen(peer) {} }
                    }
                }
            }
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                result += node
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(test.uiAutomation.rootInActiveWindow)
            return result
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun tap(node: AccessibilityNodeInfo) {
            val box = bounds(node)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, box.exactCenterX(), box.exactCenterY(), 0)
                test.sendPointerSync(event); event.recycle()
            }
        }
        Thread.sleep(1000)
        val title = nodes().first { it.text?.toString() == "SSH Settings" }
        var statusInset = 0
        test.runOnMainSync { statusInset = ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.statusBars()).top }
        check(statusInset > 0 && bounds(title).top >= statusInset) { "Top bar overlaps status bar" }
        val passwordLabel = activity.getString(cc.galaxnet.novascale.R.string.auth_password)
        tap(nodes().first { it.text?.toString() == passwordLabel })
        Thread.sleep(400)
        // Port, username, then the newly displayed password field.
        tap(nodes().last { it.className?.toString() == "android.widget.EditText" })
        var imeBottom = 0
        var height = 0
        repeat(30) {
            test.runOnMainSync {
                imeBottom = ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                height = activity.window.decorView.height
            }
            if (imeBottom == 0) Thread.sleep(100)
        }
        check(imeBottom > 0) { "System keyboard did not open" }
        Thread.sleep(600) // Allow IME animation and focused-field relocation to settle.
        val field = nodes().first { it.className?.toString() == "android.widget.EditText" && it.isFocused }
        val fieldBounds = bounds(field)
        check(fieldBounds.top >= statusInset && fieldBounds.bottom <= height - imeBottom) {
            "Password field covered by IME: $fieldBounds, keyboard top=${height - imeBottom}"
        }
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "ssh-keyboard-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
}
