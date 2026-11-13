/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.galaxnet.novascale.files.FilePreviewSettings
import cc.galaxnet.novascale.files.FilePreviewSizeLimit
import cc.galaxnet.novascale.terminalfeature.DEFAULT_TERMINAL_FONT_SIZE_SP
import cc.galaxnet.novascale.terminalfeature.TerminalFontOption
import cc.galaxnet.novascale.terminalfeature.TerminalCursorShapeOption
import cc.galaxnet.novascale.terminalfeature.TerminalSettings
import cc.galaxnet.novascale.terminalfeature.TerminalThemeCatalog
import cc.galaxnet.novascale.terminalfeature.TerminalThemePalette
import cc.galaxnet.novascale.terminalfeature.decodeImportedTerminalThemes
import cc.galaxnet.novascale.terminalfeature.encodeImportedTerminalThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.novaScaleSettings: DataStore<Preferences> by preferencesDataStore(
    name = "novascale_settings",
)

internal class AppPreferences(private val context: Context) {
    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    val onboardingComplete: Flow<Boolean> = context.novaScaleSettings.data.map { it[onboardingKey] ?: false }
    suspend fun completeOnboarding() { context.novaScaleSettings.edit { it[onboardingKey] = true } }

    val appLanguage: Flow<String> = context.novaScaleSettings.data.map { it[APP_LANGUAGE].orEmpty() }
    suspend fun setAppLanguage(tag: String) {
        require(tag in listOf("", "en", "zh-Hans", "zh-Hant"))
        context.novaScaleSettings.edit { it[APP_LANGUAGE] = tag }
    }

    val proxySettings: Flow<cc.galaxnet.novascale.browser.ProxySettings> = context.novaScaleSettings.data.map { values ->
        cc.galaxnet.novascale.browser.ProxySettings(
            enabled = values[PROXY_ENABLED] ?: true,
            port = values[PROXY_PORT] ?: 0,
            background = values[PROXY_BACKGROUND] ?: false,
        )
    }

    suspend fun setProxyEnabled(enabled: Boolean) { context.novaScaleSettings.edit { it[PROXY_ENABLED] = enabled } }
    suspend fun setProxyPort(port: Int) {
        require(port in 0..65535)
        context.novaScaleSettings.edit { it[PROXY_PORT] = port }
    }
    suspend fun setProxyBackground(enabled: Boolean) { context.novaScaleSettings.edit { it[PROXY_BACKGROUND] = enabled } }

    val keepTerminalSessionsInBackground: Flow<Boolean> = context.novaScaleSettings.data.map { values ->
        values[KEEP_TERMINALS_IN_BACKGROUND] ?: false
    }

    val terminalSettings: Flow<TerminalSettings> = context.novaScaleSettings.data.map { values ->
        TerminalSettings(
            themeId = values[TERMINAL_THEME] ?: TerminalThemeCatalog.default.id,
            fontId = values[TERMINAL_FONT] ?: TerminalFontOption.SYSTEM.id,
            fontSizeSp = values[TERMINAL_FONT_SIZE] ?: DEFAULT_TERMINAL_FONT_SIZE_SP,
            cursorShapeId = values[TERMINAL_CURSOR_SHAPE] ?: TerminalCursorShapeOption.BEAM.id,
            herdrImproveTextContrast = values[HERDR_IMPROVE_TEXT_CONTRAST] ?: true,
        ).normalized()
    }

    val importedTerminalThemes: Flow<List<TerminalThemePalette>> =
        context.novaScaleSettings.data.map { values ->
            decodeImportedTerminalThemes(values[IMPORTED_TERMINAL_THEMES])
        }

    val filePreviewSettings: Flow<FilePreviewSettings> =
        context.novaScaleSettings.data.map { values ->
            FilePreviewSettings(
                tapToPreview = values[FILE_TAP_TO_PREVIEW] ?: false,
                sizeLimit = FilePreviewSizeLimit.fromId(values[FILE_PREVIEW_SIZE_LIMIT]),
            )
        }

    suspend fun setKeepTerminalSessionsInBackground(enabled: Boolean) {
        context.novaScaleSettings.edit { values ->
            values[KEEP_TERMINALS_IN_BACKGROUND] = enabled
        }
    }

    suspend fun setTerminalTheme(themeId: String) {
        context.novaScaleSettings.edit { it[TERMINAL_THEME] = themeId }
    }

    suspend fun setTerminalFont(font: TerminalFontOption) {
        context.novaScaleSettings.edit { it[TERMINAL_FONT] = font.id }
    }

    suspend fun setTerminalFontSize(fontSizeSp: Float) {
        context.novaScaleSettings.edit {
            it[TERMINAL_FONT_SIZE] = TerminalSettings(fontSizeSp = fontSizeSp).normalized().fontSizeSp
        }
    }

    suspend fun setTerminalCursorShape(cursorShape: TerminalCursorShapeOption) {
        context.novaScaleSettings.edit { it[TERMINAL_CURSOR_SHAPE] = cursorShape.id }
    }

    suspend fun setHerdrImproveTextContrast(enabled: Boolean) {
        context.novaScaleSettings.edit { it[HERDR_IMPROVE_TEXT_CONTRAST] = enabled }
    }

    suspend fun addImportedTerminalTheme(theme: TerminalThemePalette) {
        require(theme.imported && theme.id.startsWith("iterm_"))
        context.novaScaleSettings.edit { values ->
            val themes = decodeImportedTerminalThemes(values[IMPORTED_TERMINAL_THEMES])
                .filterNot { it.id == theme.id }
                .plus(theme)
            values[IMPORTED_TERMINAL_THEMES] = encodeImportedTerminalThemes(themes)
            values[TERMINAL_THEME] = theme.id
        }
    }

    suspend fun removeImportedTerminalTheme(themeId: String) {
        context.novaScaleSettings.edit { values ->
            val themes = decodeImportedTerminalThemes(values[IMPORTED_TERMINAL_THEMES])
                .filterNot { it.id == themeId }
            values[IMPORTED_TERMINAL_THEMES] = encodeImportedTerminalThemes(themes)
            if (values[TERMINAL_THEME] == themeId) values[TERMINAL_THEME] = TerminalThemeCatalog.default.id
        }
    }

    suspend fun resetTerminalSettings() {
        context.novaScaleSettings.edit { values ->
            values[TERMINAL_THEME] = TerminalThemeCatalog.default.id
            values[TERMINAL_FONT] = TerminalFontOption.SYSTEM.id
            values[TERMINAL_FONT_SIZE] = DEFAULT_TERMINAL_FONT_SIZE_SP
            values[TERMINAL_CURSOR_SHAPE] = TerminalCursorShapeOption.BEAM.id
            values.remove(HERDR_IMPROVE_TEXT_CONTRAST)
        }
    }

    suspend fun setFileTapToPreview(enabled: Boolean) {
        context.novaScaleSettings.edit { it[FILE_TAP_TO_PREVIEW] = enabled }
    }

    suspend fun setFilePreviewSizeLimit(limit: FilePreviewSizeLimit) {
        context.novaScaleSettings.edit { it[FILE_PREVIEW_SIZE_LIMIT] = limit.id }
    }

    private companion object {
        private val APP_LANGUAGE = stringPreferencesKey("app_language")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_BACKGROUND = booleanPreferencesKey("proxy_background")
        val KEEP_TERMINALS_IN_BACKGROUND = booleanPreferencesKey("keep_terminal_sessions_in_background")
        val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val TERMINAL_FONT = stringPreferencesKey("terminal_font")
        val TERMINAL_FONT_SIZE = floatPreferencesKey("terminal_font_size")
        val TERMINAL_CURSOR_SHAPE = stringPreferencesKey("terminal_cursor_shape")
        val HERDR_IMPROVE_TEXT_CONTRAST = booleanPreferencesKey("herdr_improve_text_contrast")
        val IMPORTED_TERMINAL_THEMES = stringPreferencesKey("imported_terminal_themes")
        val FILE_TAP_TO_PREVIEW = booleanPreferencesKey("file_tap_to_preview")
        val FILE_PREVIEW_SIZE_LIMIT = stringPreferencesKey("file_preview_size_limit")
    }
}
