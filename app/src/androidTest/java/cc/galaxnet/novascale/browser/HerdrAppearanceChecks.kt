// Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.galaxnet.novascale.AppPreferences
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.terminalfeature.TerminalSettingsScreen
import cc.galaxnet.novascale.terminalfeature.TerminalSettingsViewModel
import cc.galaxnet.novascale.ui.NovaScaleTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Real settings UI and DataStore, restoring the previous device preference. */
internal object HerdrAppearanceChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val preferences = (test.targetContext.applicationContext as NovaScaleApplication).preferences
        val original = runBlocking { preferences.terminalSettings.first().herdrImproveTextContrast }
        val store = ViewModelStore()
        try {
            test.runOnMainSync {
                val model = ViewModelProvider(store, TerminalSettingsViewModel.Factory(activity, preferences))[TerminalSettingsViewModel::class.java]
                activity.setContent {
                    val state by model.uiState.collectAsStateWithLifecycle()
                    NovaScaleTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TerminalSettingsScreen(state, model::selectTheme, model::selectFont, model::setFontSize,
                                model::selectCursorShape, model::importItermTheme, model::removeImportedTheme, model::reset,
                                model::setHerdrImproveTextContrast)
                        }
                    }
                }
            }
            test.waitForIdleSync()
            Thread.sleep(600)
            val label = activity.getString(R.string.herdr_improve_text_contrast)
            fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                node ?: return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
                return null
            }
            fun toggle(): AccessibilityNodeInfo? = find(test.uiAutomation.rootInActiveWindow) {
                it.isVisibleToUser && it.text?.toString()?.contains(label) == true
            }
            repeat(12) {
                if (toggle() == null) {
                    find(test.uiAutomation.rootInActiveWindow) { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    test.waitForIdleSync(); Thread.sleep(100)
                }
            }
            var node = checkNotNull(toggle()) { "Herdr contrast setting is missing" }
            while (!node.isClickable) node = checkNotNull(node.parent)
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var persisted = false
            repeat(50) {
                if (!persisted) {
                    persisted = runBlocking { AppPreferences(activity).terminalSettings.first().herdrImproveTextContrast == !original }
                    if (!persisted) Thread.sleep(100)
                }
            }
            check(persisted) { "Herdr contrast toggle did not persist" }
        } finally {
            test.runOnMainSync { store.clear() }
            runBlocking { preferences.setHerdrImproveTextContrast(original) }
        }
    }
}
