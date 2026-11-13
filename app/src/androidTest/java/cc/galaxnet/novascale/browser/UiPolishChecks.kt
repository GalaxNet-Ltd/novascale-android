// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.io.File

/** Actual composables at phone sizes; fixtures contain no owner network data. */
internal object UiPolishChecks {
    @OptIn(ExperimentalMaterial3Api::class)
    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val page = mutableStateOf(0)
        val selected = mutableStateOf(GROUP_FILTER_ALL)
        val groups = HostGroupsState(groups = listOf(HostGroup("dev", "Development"), HostGroup("home", "Home lab")), loaded = true)
        val sites = listOf(BrowserSite("1", "http://grafana:3000", "Grafana", 1, true), BrowserSite("2", "https://dashboard.example/", "Development dashboard", 2, false))
        test.runOnMainSync {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    NovaScaleTheme(dark.value) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.safeDrawingPadding()) {
                                if (page.value == 0) BrowserStartPage(BrowserUiState(address = "http://grafana"), sites, true, 2, {}, {}, {}, {}, {})
                                else {
                                    NovaScaleTopBar(if (selected.value == GROUP_FILTER_ALL) "Hosts" else "Development", false, {},
                                        subtitle = if (selected.value == GROUP_FILTER_ALL) "Development tailnet" else null,
                                        compact = selected.value != GROUP_FILTER_ALL)
                                    if (page.value == 1) HostFilterSheetContent(groups, false, selected.value, emptyList(),
                                        { selected.value = it }, {}, {}, {}, {}, { _, _ -> }, {})
                                    else ModalBottomSheet(onDismissRequest = { page.value = 0 }) {
                                        HostFilterSheetContent(groups, false, selected.value, emptyList(),
                                            { selected.value = it }, {}, {}, {}, {}, { _, _ -> }, {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun screenshot(name: String) {
            test.waitForIdleSync(); Thread.sleep(500)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(test.targetContext.cacheDir, "ui-polish-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } ?: error("Screenshot unavailable")
        }
        screenshot("browser-light")
        test.runOnMainSync { dark.value = true }; screenshot("browser-dark")
        test.runOnMainSync { scale.value = 2f }; screenshot("browser-large")
        test.runOnMainSync { scale.value = 1f; page.value = 1; dark.value = false }; screenshot("groups-light")
        test.runOnMainSync { selected.value = GROUP_FILTER_PREFIX + "dev"; dark.value = true }; screenshot("groups-dark")
        test.runOnMainSync { page.value = 2 }; screenshot("half-sheet")
        test.runOnMainSync { page.value = 1; scale.value = 2f }; screenshot("groups-large")
        test.runOnMainSync { activity.setContent {} }
    }
}
