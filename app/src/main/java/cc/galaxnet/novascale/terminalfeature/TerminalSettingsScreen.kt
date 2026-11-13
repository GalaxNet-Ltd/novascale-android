/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.CursorShape
import cc.galaxnet.novascale.ui.NovaSectionLabel
import cc.galaxnet.novascale.ui.NovaSpacing
import cc.galaxnet.novascale.ui.NovaTheme
import cc.galaxnet.novascale.ui.asString
import kotlin.math.roundToInt

@Composable
internal fun TerminalSettingsScreen(
    state: TerminalSettingsUiState,
    onTheme: (TerminalThemePalette) -> Unit,
    onFont: (TerminalFontOption) -> Unit,
    onFontSize: (Float) -> Unit,
    onCursorShape: (TerminalCursorShapeOption) -> Unit,
    onImportItermTheme: (android.net.Uri) -> Unit,
    onRemoveImportedTheme: (TerminalThemePalette) -> Unit,
    onReset: () -> Unit,
    onHerdrImproveTextContrast: (Boolean) -> Unit,
) {
    val importTheme = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportItermTheme(uri)
    }
    val theme = state.selectedTheme
    var pendingFontSize by remember(state.settings.fontSizeSp) {
        mutableFloatStateOf(state.settings.fontSizeSp)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NovaSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
    ) {
        TerminalAppearancePreview(
            theme = theme,
            font = state.settings.font,
            fontSizeSp = pendingFontSize,
            cursorShape = state.settings.cursorShape,
        )

        NovaSectionLabel(stringResource(R.string.appearance_theme))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            state.themes.forEach { option ->
                TerminalThemeChip(
                    theme = option,
                    selected = option.id == theme.id,
                    onClick = { onTheme(option) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovaPrimaryButton(
                onClick = {
                    importTheme.launch(
                        arrayOf("application/xml", "text/xml", "application/octet-stream", "text/plain"),
                    )
                },
            ) {
                Text(stringResource(R.string.appearance_import_iterm))
            }
            if (theme.imported) {
                TextButton(onClick = { onRemoveImportedTheme(theme) }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
        Text(
            stringResource(R.string.appearance_import_description),
            style = MaterialTheme.typography.bodySmall,
            color = NovaTheme.colors.textSecondary,
        )

        HorizontalDivider()
        NovaSectionLabel(stringResource(R.string.appearance_font_family))
        Column(verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm)) {
            TerminalFontOption.entries.forEach { font ->
                TerminalFontRow(
                    font = font,
                    selected = font == state.settings.font,
                    onClick = { onFont(font) },
                )
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovaSectionLabel(stringResource(R.string.appearance_font_size), Modifier.weight(1f))
            Text(
                "${pendingFontSize.roundToInt()} sp",
                style = MaterialTheme.typography.titleMedium,
                color = NovaTheme.colors.primary,
            )
        }
        Slider(
            value = pendingFontSize,
            onValueChange = { pendingFontSize = it.roundToInt().toFloat() },
            onValueChangeFinished = { onFontSize(pendingFontSize) },
            valueRange = MIN_TERMINAL_FONT_SIZE_SP..MAX_TERMINAL_FONT_SIZE_SP,
            steps = (MAX_TERMINAL_FONT_SIZE_SP - MIN_TERMINAL_FONT_SIZE_SP).roundToInt() - 1,
        )
        Text(
            stringResource(R.string.appearance_zoom_description),
            style = MaterialTheme.typography.bodySmall,
            color = NovaTheme.colors.textSecondary,
        )

        HorizontalDivider()
        NovaSectionLabel(stringResource(R.string.appearance_cursor_shape))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            TerminalCursorShapeOption.entries.forEach { option ->
                TerminalCursorShapeChip(
                    option = option,
                    selected = option == state.settings.cursorShape,
                    onClick = { onCursorShape(option) },
                )
            }
        }
        Text(
            stringResource(R.string.appearance_cursor_description),
            style = MaterialTheme.typography.bodySmall,
            color = NovaTheme.colors.textSecondary,
        )

        HorizontalDivider()
        NovaSectionLabel(stringResource(R.string.herdr_appearance_section))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(state.settings.herdrImproveTextContrast, role = Role.Switch, onValueChange = onHerdrImproveTextContrast),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.md)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.herdr_improve_text_contrast), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.herdr_improve_text_contrast_help), style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary)
            }
            Switch(checked = state.settings.herdrImproveTextContrast, onCheckedChange = null)
        }

        state.message?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = NovaTheme.colors.primary)
        }
        TextButton(onClick = onReset) { Text(stringResource(R.string.appearance_reset)) }
    }
}

@Composable
private fun TerminalAppearancePreview(
    theme: TerminalThemePalette,
    font: TerminalFontOption,
    fontSizeSp: Float,
    cursorShape: TerminalCursorShapeOption,
) {
    val family = terminalComposeFont(font)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(theme.background),
        border = BorderStroke(1.dp, NovaTheme.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NovaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("user@novascale", color = Color(theme.ansi[2]), fontFamily = family, fontSize = fontSizeSp.sp)
                Text(":~/projects$ ", color = Color(theme.foreground), fontFamily = family, fontSize = fontSizeSp.sp)
                Text("git status", color = Color(theme.ansi[6]), fontFamily = family, fontSize = fontSizeSp.sp)
                TerminalCursorPreview(
                    shape = cursorShape.previewShape,
                    color = Color(theme.cursor),
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            Text(
                "✓ main  →  clean   λ terminal-ready",
                color = Color(theme.ansi[10]),
                fontFamily = family,
                fontSize = fontSizeSp.sp,
                maxLines = 1,
            )
            Text(
                stringResource(
                    R.string.appearance_preview_summary,
                    theme.localizedDisplayName(),
                    font.localizedDisplayName(),
                    cursorShape.localizedDisplayName(),
                ),
                color = Color(theme.foreground).copy(alpha = 0.65f),
                fontFamily = family,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun TerminalCursorShapeChip(
    option: TerminalCursorShapeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // This is a shape selector on an app surface, not a terminal-color sample.
    // The full terminal preview above still uses the chosen theme's cursor color.
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .width(126.dp)
            .heightIn(min = 72.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) NovaTheme.colors.primary else NovaTheme.colors.borderSubtle,
        ),
    ) {
        Box {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), tint = foreground)
            Column(
                modifier = Modifier.fillMaxWidth().padding(NovaSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
            ) {
                TerminalCursorPreview(option.previewShape, foreground)
                Text(
                    option.localizedDisplayName(),
                    color = foreground,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun TerminalCursorPreview(
    shape: CursorShape,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 13.dp, height = 21.dp)) {
        val stroke = 2.dp.toPx()
        when (shape) {
            CursorShape.BLOCK -> drawRect(
                color = color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
            CursorShape.BEAM -> drawLine(
                color = color,
                start = Offset(stroke / 2f, 0f),
                end = Offset(stroke / 2f, size.height),
                strokeWidth = stroke,
            )
            CursorShape.UNDERLINE -> drawLine(
                color = color,
                start = Offset(0f, size.height - stroke / 2f),
                end = Offset(size.width, size.height - stroke / 2f),
                strokeWidth = stroke,
            )
        }
    }
}

@Composable
private fun TerminalThemeChip(
    theme: TerminalThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(width = 132.dp, height = 82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Color(theme.background),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) NovaTheme.colors.primary else NovaTheme.colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(NovaSpacing.sm),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                theme.localizedDisplayName(),
                color = Color(theme.foreground),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(1, 2, 3, 4, 5, 6).forEach { index ->
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = RoundedCornerShape(3.dp),
                        color = Color(theme.ansi[index]),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun TerminalFontRow(
    font: TerminalFontOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NovaTheme.colors.primary.copy(alpha = 0.14f) else NovaTheme.colors.backgroundSecondary,
        border = BorderStroke(
            1.dp,
            if (selected) NovaTheme.colors.primary else NovaTheme.colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    font.localizedDisplayName(),
                    fontFamily = terminalComposeFont(font),
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = NovaTheme.colors.textPrimary,
                )
                Text(
                    if (font == TerminalFontOption.JETBRAINS_MONO_NERD) {
                        stringResource(R.string.font_nerd_description)
                    } else {
                        "0O 1lI {} [] => != ~/"
                    },
                    fontFamily = terminalComposeFont(font),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                )
            }
            if (selected) {
                Text(
                    stringResource(R.string.status_selected),
                    color = NovaTheme.colors.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun terminalComposeFont(font: TerminalFontOption): FontFamily = when (font) {
    TerminalFontOption.SYSTEM -> FontFamily.Monospace
    TerminalFontOption.JETBRAINS_MONO -> FontFamily(Font(R.font.jetbrains_mono))
    TerminalFontOption.JETBRAINS_MONO_NERD -> FontFamily(Font(R.font.jetbrains_mono_nerd))
    TerminalFontOption.FIRA_CODE -> FontFamily(Font(R.font.fira_code))
    TerminalFontOption.SOURCE_CODE_PRO -> FontFamily(Font(R.font.source_code_pro))
}

@Composable
internal fun TerminalCursorShapeOption.localizedDisplayName(): String = stringResource(
    when (this) {
        TerminalCursorShapeOption.APPLICATION -> R.string.cursor_application
        TerminalCursorShapeOption.BLOCK -> R.string.cursor_block
        TerminalCursorShapeOption.BEAM -> R.string.cursor_beam
        TerminalCursorShapeOption.UNDERLINE -> R.string.cursor_underline
    },
)

@Composable
internal fun TerminalFontOption.localizedDisplayName(): String = stringResource(
    when (this) {
        TerminalFontOption.SYSTEM -> R.string.font_system_monospace
        TerminalFontOption.JETBRAINS_MONO -> R.string.font_jetbrains_mono
        TerminalFontOption.JETBRAINS_MONO_NERD -> R.string.font_jetbrains_mono_nerd
        TerminalFontOption.FIRA_CODE -> R.string.font_fira_code
        TerminalFontOption.SOURCE_CODE_PRO -> R.string.font_source_code_pro
    },
)

@Composable
internal fun TerminalThemePalette.localizedDisplayName(): String = when (id) {
    "nova_dark" -> stringResource(R.string.theme_nova_dark)
    "nova_light" -> stringResource(R.string.theme_nova_light)
    "solarized_dark" -> stringResource(R.string.theme_solarized_dark)
    "dracula" -> stringResource(R.string.theme_dracula)
    "nord" -> stringResource(R.string.theme_nord)
    else -> displayName
}
