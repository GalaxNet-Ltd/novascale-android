/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import android.content.Context
import android.graphics.Typeface
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.CursorShape
import cc.galaxnet.novascale.nativecore.NativeTerminalTheme
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val DEFAULT_TERMINAL_FONT_SIZE_SP = 8f
internal const val MIN_TERMINAL_FONT_SIZE_SP = 8f
internal const val MAX_TERMINAL_FONT_SIZE_SP = 32f

internal data class TerminalSettings(
    val themeId: String = TerminalThemeCatalog.default.id,
    val fontId: String = TerminalFontOption.SYSTEM.id,
    val fontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP,
    val cursorShapeId: String = TerminalCursorShapeOption.BEAM.id,
    val herdrImproveTextContrast: Boolean = true,
) {
    val font: TerminalFontOption
        get() = TerminalFontOption.fromId(fontId)

    val cursorShape: TerminalCursorShapeOption
        get() = TerminalCursorShapeOption.fromId(cursorShapeId)

    fun normalized() = copy(
        themeId = themeId.takeIf(String::isNotBlank) ?: TerminalThemeCatalog.default.id,
        fontId = font.id,
        fontSizeSp = fontSizeSp.coerceIn(MIN_TERMINAL_FONT_SIZE_SP, MAX_TERMINAL_FONT_SIZE_SP),
        cursorShapeId = cursorShape.id,
    )
}

internal enum class TerminalCursorShapeOption(
    val id: String,
    val displayName: String,
    val shapeOverride: CursorShape?,
) {
    APPLICATION("application", "Application", null),
    BLOCK("block", "Block", CursorShape.BLOCK),
    BEAM("beam", "Beam", CursorShape.BEAM),
    UNDERLINE("underline", "Underline", CursorShape.UNDERLINE),
    ;

    val previewShape: CursorShape
        get() = shapeOverride ?: CursorShape.BLOCK

    companion object {
        fun fromId(id: String): TerminalCursorShapeOption =
            entries.firstOrNull { it.id == id } ?: BEAM
    }
}

internal enum class TerminalFontOption(
    val id: String,
    val displayName: String,
    private val fontResource: Int?,
) {
    SYSTEM("system", "System monospace", null),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", R.font.jetbrains_mono),
    JETBRAINS_MONO_NERD("jetbrains_mono_nerd", "JetBrains Mono Nerd Font", R.font.jetbrains_mono_nerd),
    FIRA_CODE("fira_code", "Fira Code", R.font.fira_code),
    SOURCE_CODE_PRO("source_code_pro", "Source Code Pro", R.font.source_code_pro),
    ;

    fun typeface(context: Context): Typeface = fontResource
        ?.let(context.resources::getFont)
        ?: Typeface.MONOSPACE

    companion object {
        fun fromId(id: String): TerminalFontOption = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

internal data class TerminalThemePalette(
    val id: String,
    val displayName: String,
    val ansi: List<Int>,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val cursorText: Int = background,
    val selection: Int = ansi.getOrElse(4) { foreground },
    val selectedText: Int = foreground,
    val imported: Boolean = false,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(ansi.size == 16)
    }

    fun toNativeTheme() = NativeTerminalTheme(
        foregroundRgb = foreground and 0x00ffffff,
        backgroundRgb = background and 0x00ffffff,
        cursorRgb = cursor and 0x00ffffff,
        ansiRgb = ansi.map { it and 0x00ffffff },
    )

}

/**
 * Mirrors the iOS terminal chrome treatment: preserve the terminal background's hue and
 * saturation, then lift dark themes or lower light themes enough to separate controls from the
 * terminal canvas without introducing an unrelated application color.
 */
internal fun adjustTerminalChromeBackground(argb: Int): Int {
    val alpha = argb ushr 24 and 0xff
    val red = (argb ushr 16 and 0xff) / 255f
    val green = (argb ushr 8 and 0xff) / 255f
    val blue = (argb and 0xff) / 255f
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max == 0f) 0f else delta / max
    val brightness = (max + if (max < 0.5f) 0.08f else -0.06f).coerceIn(0f, 1f)

    val chroma = brightness * saturation
    val segment = hue / 60f
    val secondary = chroma * (1f - abs(segment % 2f - 1f))
    val (hueRed, hueGreen, hueBlue) = when (segment.toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val match = brightness - chroma

    return (alpha shl 24) or
        (((hueRed + match) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
        (((hueGreen + match) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
        ((hueBlue + match) * 255f).roundToInt().coerceIn(0, 255)
}

internal object TerminalThemeCatalog {
    val builtIns = listOf(
        theme(
            id = "nova_dark",
            name = "Nova Dark",
            ansi = listOf(
                0xFF1D1F21, 0xFFCC6666, 0xFFB5BD68, 0xFFF0C674,
                0xFF81A2BE, 0xFFB294BB, 0xFF8ABEB7, 0xFFC5C8C6,
                0xFF666666, 0xFFD54E53, 0xFFB9CA4A, 0xFFE7C547,
                0xFF7AA6DA, 0xFFC397D8, 0xFF70C0B1, 0xFFEAEAEA,
            ),
            background = 0xFF0D1117,
            foreground = 0xFFE6EDF3,
            cursor = 0xFFF0F3F6,
            selection = 0xFF264F78,
        ),
        theme(
            id = "nova_light",
            name = "Nova Light",
            ansi = listOf(
                0xFF20242C, 0xFFC62828, 0xFF2E7D32, 0xFFF57F17,
                0xFF1565C0, 0xFF7B1FA2, 0xFF00838F, 0xFFE7E9ED,
                0xFF60656F, 0xFFE53935, 0xFF43A047, 0xFFF9A825,
                0xFF1E88E5, 0xFF8E24AA, 0xFF00ACC1, 0xFFFFFFFF,
            ),
            background = 0xFFF8F9FC,
            foreground = 0xFF20242C,
            cursor = 0xFF233EC7,
            selection = 0xFFCAD4FF,
        ),
        theme(
            id = "solarized_dark",
            name = "Solarized Dark",
            ansi = listOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
            background = 0xFF002B36,
            foreground = 0xFF839496,
            cursor = 0xFF93A1A1,
            selection = 0xFF073642,
        ),
        theme(
            id = "dracula",
            name = "Dracula",
            ansi = listOf(
                0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
            ),
            background = 0xFF282A36,
            foreground = 0xFFF8F8F2,
            cursor = 0xFFF8F8F2,
            selection = 0xFF44475A,
        ),
        theme(
            id = "nord",
            name = "Nord",
            ansi = listOf(
                0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
            ),
            background = 0xFF2E3440,
            foreground = 0xFFD8DEE9,
            cursor = 0xFFD8DEE9,
            selection = 0xFF434C5E,
        ),
    )

    val default: TerminalThemePalette = builtIns.first()

    fun resolve(id: String, imported: List<TerminalThemePalette>): TerminalThemePalette =
        (builtIns + imported).firstOrNull { it.id == id } ?: default

    private fun theme(
        id: String,
        name: String,
        ansi: List<Long>,
        background: Long,
        foreground: Long,
        cursor: Long,
        selection: Long,
    ) = TerminalThemePalette(
        id = id,
        displayName = name,
        ansi = ansi.map(Long::toInt),
        background = background.toInt(),
        foreground = foreground.toInt(),
        cursor = cursor.toInt(),
        selection = selection.toInt(),
    )
}

internal fun encodeImportedTerminalThemes(themes: List<TerminalThemePalette>): String = themes
    .take(MAX_IMPORTED_THEMES)
    .joinToString("\n") { theme ->
        val colors = theme.ansi + listOf(
            theme.background,
            theme.foreground,
            theme.cursor,
            theme.cursorText,
            theme.selection,
            theme.selectedText,
        )
        listOf(
            IMPORTED_THEME_FORMAT_VERSION,
            theme.id.encodeThemeField(),
            theme.displayName.encodeThemeField(),
            colors.joinToString(",") { it.unsignedHex() },
        ).joinToString("\t")
    }

internal fun decodeImportedTerminalThemes(raw: String?): List<TerminalThemePalette> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence()
        .take(MAX_IMPORTED_THEMES)
        .mapNotNull { line -> runCatching { decodeImportedTheme(line) }.getOrNull() }
        .filter { it.id.startsWith("iterm_") }
        .distinctBy(TerminalThemePalette::id)
        .toList()
}

private const val MAX_IMPORTED_THEMES = 15
private const val IMPORTED_THEME_FORMAT_VERSION = "1"

private fun decodeImportedTheme(raw: String): TerminalThemePalette {
    val fields = raw.split('\t')
    require(fields.size == 4 && fields[0] == IMPORTED_THEME_FORMAT_VERSION)
    val colors = fields[3].split(',').map { it.toLong(16).toInt() }
    require(colors.size == 22)
    return TerminalThemePalette(
        id = fields[1].decodeThemeField(),
        displayName = fields[2].decodeThemeField(),
        ansi = colors.take(16),
        background = colors[16],
        foreground = colors[17],
        cursor = colors[18],
        cursorText = colors[19],
        selection = colors[20],
        selectedText = colors[21],
        imported = true,
    )
}

private fun String.encodeThemeField(): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String.decodeThemeField(): String = String(
    Base64.getUrlDecoder().decode(this),
    StandardCharsets.UTF_8,
)

private fun Int.unsignedHex(): String = "%08x".format(toLong() and 0xffffffffL)
