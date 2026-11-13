// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale

import cc.galaxnet.novascale.ui.NovaActionRow
import cc.galaxnet.novascale.ui.NovaIcons
import cc.galaxnet.novascale.ui.NovaTheme
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Pre-33 locales are a DataStore preference; 33+ uses Android's authoritative app setting. */
@Composable
internal fun AppLanguage(preferences: AppPreferences, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= 33) {
        val context = LocalContext.current
        LaunchedEffect(preferences) {
            // Migrate once if the device upgraded from Android 12 or earlier.
            val legacy = preferences.appLanguage.first()
            if (legacy.isNotEmpty()) {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(legacy)
                preferences.setAppLanguage("")
            }
        }
        content()
        return
    }
    val language by preferences.appLanguage.collectAsStateWithLifecycle(initialValue = null)
    val tag = language ?: return // Wait for disk before drawing text in the wrong language.
    val base = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configuration = remember(systemConfiguration, tag) {
        Configuration(systemConfiguration).apply {
            if (tag.isNotEmpty()) setLocales(LocaleList.forLanguageTags(tag))
        }
    }
    val localized = remember(base, configuration) { android.view.ContextThemeWrapper(base, 0).apply { applyOverrideConfiguration(configuration) } }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, content = content)
}

@Composable
internal fun LanguageSettings(preferences: AppPreferences) {
    var show by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val legacy by preferences.appLanguage.collectAsStateWithLifecycle(initialValue = "")
    // Reading configuration makes changes from system Settings observable on return.
    LocalConfiguration.current
    val current = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else legacy
    val choices = listOf("" to stringResource(R.string.language_system), "en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文")
    cc.galaxnet.novascale.ui.NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
        NovaActionRow(
            title = stringResource(R.string.app_language),
            description = choices.firstOrNull { it.first == current }?.second ?: current,
            icon = NovaIcons.Language,
            iconColor = NovaTheme.colors.primary,
            modifier = Modifier.clickable { show = true },
        )
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(stringResource(R.string.app_language)) }, text = {
        Column(Modifier.selectableGroup()) {
            choices.forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = tag == current, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = {
                    show = false
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
                    } else scope.launch { preferences.setAppLanguage(tag) }
                }).padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = tag == current, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(android.R.string.cancel)) } })
}
