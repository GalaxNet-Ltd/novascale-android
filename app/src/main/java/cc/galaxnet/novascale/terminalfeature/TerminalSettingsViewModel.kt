/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.galaxnet.novascale.AppPreferences
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.uiText
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TerminalSettingsUiState(
    val settings: TerminalSettings = TerminalSettings(),
    val importedThemes: List<TerminalThemePalette> = emptyList(),
    val message: UiText? = null,
) {
    val themes: List<TerminalThemePalette>
        get() = TerminalThemeCatalog.builtIns + importedThemes

    val selectedTheme: TerminalThemePalette
        get() = TerminalThemeCatalog.resolve(settings.themeId, importedThemes)
}

internal class TerminalSettingsViewModel(
    private val context: Context,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val message = MutableStateFlow<UiText?>(null)

    val uiState: StateFlow<TerminalSettingsUiState> = combine(
        preferences.terminalSettings,
        preferences.importedTerminalThemes,
        message,
    ) { settings, themes, status ->
        TerminalSettingsUiState(settings, themes, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TerminalSettingsUiState(),
    )

    fun selectTheme(theme: TerminalThemePalette) {
        viewModelScope.launch { preferences.setTerminalTheme(theme.id) }
    }

    fun selectFont(font: TerminalFontOption) {
        viewModelScope.launch { preferences.setTerminalFont(font) }
    }

    fun setFontSize(fontSizeSp: Float) {
        viewModelScope.launch { preferences.setTerminalFontSize(fontSizeSp) }
    }

    fun selectCursorShape(cursorShape: TerminalCursorShapeOption) {
        viewModelScope.launch { preferences.setTerminalCursorShape(cursorShape) }
    }

    fun setHerdrImproveTextContrast(enabled: Boolean) {
        viewModelScope.launch { preferences.setHerdrImproveTextContrast(enabled) }
    }

    fun importItermTheme(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val bytes = readTheme(uri)
                    ItermColorThemeParser.parse(name, bytes)
                }
            }.onSuccess { theme ->
                preferences.addImportedTerminalTheme(theme)
                message.value = uiText(R.string.theme_imported, theme.displayName)
            }.onFailure {
                message.value = (it as? ThemeImportFailure)?.uiText
                    ?: if (it is IllegalArgumentException) {
                        uiText(R.string.theme_invalid)
                    } else {
                        uiText(R.string.theme_import_failed)
                    }
            }
        }
    }

    fun removeImportedTheme(theme: TerminalThemePalette) {
        if (!theme.imported) return
        viewModelScope.launch {
            preferences.removeImportedTerminalTheme(theme.id)
            message.value = uiText(R.string.theme_removed, theme.displayName)
        }
    }

    fun reset() {
        viewModelScope.launch {
            preferences.resetTerminalSettings()
            message.value = uiText(R.string.appearance_reset_complete)
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf(String::isNotBlank)?.let { return it }
                }
            }
        return uri.lastPathSegment ?: "Imported.itermcolors"
    }

    private fun readTheme(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ThemeImportFailure(uiText(R.string.theme_open_failed))
        return input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > ItermColorThemeParser.MAX_THEME_BYTES) {
                    throw ThemeImportFailure(uiText(R.string.theme_too_large))
                }
            }
            output.toByteArray()
        }
    }

    private class ThemeImportFailure(val uiText: UiText) : Exception()

    class Factory(
        context: Context,
        private val preferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TerminalSettingsViewModel::class.java))
            return TerminalSettingsViewModel(applicationContext, preferences) as T
        }
    }
}
