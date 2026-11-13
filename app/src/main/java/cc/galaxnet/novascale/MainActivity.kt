/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import cc.galaxnet.novascale.ui.NovaScaleTheme

open class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as NovaScaleApplication).billing.manager.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system starting window uses the launch theme; app windows use the UI theme.
        setTheme(R.style.Theme_NovaScale)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val novaScale = application as NovaScaleApplication
        setContent {
            AppLanguage(novaScale.preferences) {
                NovaScaleTheme(darkTheme = isSystemInDarkTheme()) {
                    NovaScaleApp(
                        tailnetBackend = novaScale.tailnetBackend,
                        terminalSessions = novaScale.terminalSessions,
                        preferences = novaScale.preferences,
                        initialTerminalPeer = TerminalWindowIntent.peer(intent),
                        initialTerminalSessionKey = TerminalWindowIntent.sessionKey(intent),
                    )
                }
            }
        }
    }
}
