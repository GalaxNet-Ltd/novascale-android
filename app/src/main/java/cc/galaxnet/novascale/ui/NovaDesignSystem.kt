/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.galaxnet.novascale.R

@Immutable
data class NovaSemanticColors(
    val primary: Color,
    val primaryShade: Color,
    val accent: Color,
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderSubtle: Color,
    val statusConnected: Color,
    val statusDisconnected: Color,
    val statusWarning: Color,
    val statusInfo: Color,
    val menuPrimary: Color,
    val menuGreen: Color,
    val menuFile: Color,
    val menuAppearance: Color,
    val menuSupport: Color,
    val platformApple: Color,
    val platformLinux: Color,
    val platformWindows: Color,
    val platformOther: Color,
)

private val NovaLightColors = NovaSemanticColors(
    primary = Color(0xFF3656C9),
    primaryShade = Color(0xFF233EC7),
    accent = Color(0xFF52E099),
    backgroundPrimary = Color(0xFFF3F4F6),
    backgroundSecondary = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF465363),
    borderSubtle = Color(0xFFCBD2DC),
    statusConnected = Color(0xFF087F5B),
    statusDisconnected = Color(0xFFD9262C),
    statusWarning = Color(0xFFA35400),
    statusInfo = Color(0xFF176DA5),
    menuPrimary = Color(0xFF3656C9),
    menuGreen = Color(0xFF087F5B),
    menuFile = Color(0xFF956600),
    menuAppearance = Color(0xFF7545AC),
    menuSupport = Color(0xFF966216),
    platformApple = Color(0xFF33363D),
    platformLinux = Color(0xFF29A366),
    platformWindows = Color(0xFF248FDB),
    platformOther = Color(0xFF50525E),
)

private val NovaDarkColors = NovaSemanticColors(
    primary = Color(0xFF99B4FF),
    primaryShade = Color(0xFF3B57E3),
    accent = Color(0xFF1C5C38),
    backgroundPrimary = Color(0xFF0B0F14),
    backgroundSecondary = Color(0xFF151B23),
    textPrimary = Color(0xFFF3F6FA),
    textSecondary = Color(0xFFB8C3D1),
    borderSubtle = Color(0xFF354150),
    statusConnected = Color(0xFF4DD89C),
    statusDisconnected = Color(0xFFE8595E),
    statusWarning = Color(0xFFFFB454),
    statusInfo = Color(0xFF569ED2),
    menuPrimary = Color(0xFF99B4FF),
    menuGreen = Color(0xFF3BC48B),
    menuFile = Color(0xFFE4BE25),
    menuAppearance = Color(0xFFCAA6F3),
    menuSupport = Color(0xFFF3C574),
    platformApple = Color(0xFF25282C),
    platformLinux = Color(0xFF238A57),
    platformWindows = Color(0xFF1E78B8),
    platformOther = Color(0xFF41434E),
)

private val LocalNovaColors = staticCompositionLocalOf { NovaLightColors }

object NovaSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val screenMargin = md
    val rowSpacing = 12.dp
}

object NovaSizes {
    val statusIndicator = 8.dp
    val statusIndicatorSmall = 6.dp
    val compactIconButton = 32.dp
    val minimumTouchTarget = 48.dp
    val hostCardMinHeight = 64.dp
}

object NovaTheme {
    val colors: NovaSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNovaColors.current

    val spacing = NovaSpacing
    val sizes = NovaSizes
}

/** Small NovaScale-owned vector set used by the focused Android shell. */
object NovaIcons {
    val Hosts: ImageVector by lazy {
        ImageVector.Builder(
            name = "Hosts",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 17f)
                lineTo(3f, 17f)
                close()
                moveTo(5f, 6f)
                lineTo(5f, 15f)
                lineTo(19f, 15f)
                lineTo(19f, 6f)
                close()
                moveTo(11f, 17f)
                lineTo(13f, 17f)
                lineTo(13f, 20f)
                lineTo(17f, 20f)
                lineTo(17f, 22f)
                lineTo(7f, 22f)
                lineTo(7f, 20f)
                lineTo(11f, 20f)
                close()
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 5f)
                lineTo(14f, 5f)
                lineTo(14f, 3f)
                lineTo(17f, 3f)
                lineTo(17f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 7f)
                lineTo(17f, 7f)
                lineTo(17f, 9f)
                lineTo(14f, 9f)
                lineTo(14f, 7f)
                lineTo(3f, 7f)
                close()
                moveTo(3f, 11f)
                lineTo(7f, 11f)
                lineTo(7f, 9f)
                lineTo(10f, 9f)
                lineTo(10f, 15f)
                lineTo(7f, 15f)
                lineTo(7f, 13f)
                lineTo(3f, 13f)
                close()
                moveTo(12f, 17f)
                lineTo(18f, 17f)
                lineTo(18f, 15f)
                lineTo(21f, 15f)
                lineTo(21f, 21f)
                lineTo(18f, 21f)
                lineTo(18f, 19f)
                lineTo(12f, 19f)
                close()
            }
        }.build()
    }

    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder("ArrowBack", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 11f)
                lineTo(7.83f, 11f)
                lineTo(13.42f, 5.41f)
                lineTo(12f, 4f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                lineTo(13.42f, 18.59f)
                lineTo(7.83f, 13f)
                lineTo(20f, 13f)
                close()
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.3f, 5.71f)
                lineTo(12f, 12f)
                lineTo(5.7f, 5.71f)
                lineTo(4.29f, 7.12f)
                lineTo(10.59f, 13.41f)
                lineTo(4.29f, 19.71f)
                lineTo(5.7f, 21.12f)
                lineTo(12f, 14.83f)
                lineTo(18.3f, 21.12f)
                lineTo(19.71f, 19.71f)
                lineTo(13.41f, 13.41f)
                lineTo(19.71f, 7.12f)
                close()
            }
        }.build()
    }

    val Add: ImageVector by lazy {
        ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f)
                lineTo(13f, 13f)
                lineTo(13f, 19f)
                lineTo(11f, 19f)
                lineTo(11f, 13f)
                lineTo(5f, 13f)
                lineTo(5f, 11f)
                lineTo(11f, 11f)
                lineTo(11f, 5f)
                lineTo(13f, 5f)
                lineTo(13f, 11f)
                lineTo(19f, 11f)
                close()
            }
        }.build()
    }

    val NewWindow: ImageVector by lazy {
        ImageVector.Builder("NewWindow", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 4f)
                lineTo(16f, 4f)
                lineTo(16f, 8f)
                lineTo(20f, 8f)
                lineTo(20f, 20f)
                lineTo(8f, 20f)
                lineTo(8f, 16f)
                lineTo(4f, 16f)
                close()
                moveTo(6f, 6f)
                lineTo(6f, 14f)
                lineTo(8f, 14f)
                lineTo(8f, 8f)
                lineTo(14f, 8f)
                lineTo(14f, 6f)
                close()
                moveTo(10f, 10f)
                lineTo(10f, 18f)
                lineTo(18f, 18f)
                lineTo(18f, 10f)
                close()
            }
        }.build()
    }

    val Detach: ImageVector by lazy {
        ImageVector.Builder("Detach", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(5f, 5f)
                lineTo(12f, 5f)
                lineTo(12f, 3f)
                lineTo(5f, 3f)
                curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
                lineTo(3f, 19f)
                curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                lineTo(19f, 21f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                lineTo(21f, 12f)
                lineTo(19f, 12f)
                close()
                moveTo(14f, 3f)
                lineTo(14f, 5f)
                lineTo(17.59f, 5f)
                lineTo(7.76f, 14.83f)
                lineTo(9.17f, 16.24f)
                lineTo(19f, 6.41f)
                lineTo(19f, 10f)
                lineTo(21f, 10f)
                lineTo(21f, 3f)
                close()
            }
        }.build()
    }

    val Terminal: ImageVector = Icons.Outlined.Terminal
    val NavigationHosts: ImageVector = Icons.Outlined.Computer
    val NavigationSettings: ImageVector = Icons.Outlined.Settings
    val Files: ImageVector = Icons.Outlined.Folder
    val Ssh: ImageVector = Icons.Outlined.Key
    val Ping: ImageVector = Icons.Outlined.NetworkPing
    val Info: ImageVector = Icons.Outlined.Info
    val Privacy: ImageVector = Icons.Outlined.PrivacyTip
    val Terms: ImageVector = Icons.Outlined.Description
    val License: ImageVector = Icons.Outlined.Gavel
    val Source: ImageVector = Icons.Outlined.Code
    val Appearance: ImageVector = Icons.Outlined.Palette
    val Disconnect: ImageVector = Icons.Outlined.PowerSettingsNew
    val ChevronRight: ImageVector = Icons.Outlined.ChevronRight
    val Server: ImageVector = Icons.Outlined.Dns
    val Language: ImageVector = Icons.Outlined.Translate
    val BackgroundSessions: ImageVector = Icons.Outlined.Sync
    val Gestures: ImageVector = Icons.Outlined.TouchApp
    val Proxy: ImageVector = Icons.Outlined.Route
    val Version: ImageVector = Icons.Outlined.NewReleases
}

private val NovaTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleLarge = Typography().titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Normal),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.Medium),
) .let { base ->
    val mono = FontFamily(Font(R.font.jetbrains_mono))
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        displayMedium = base.displayMedium.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        displaySmall = base.displaySmall.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        headlineLarge = base.headlineLarge.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        headlineMedium = base.headlineMedium.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        headlineSmall = base.headlineSmall.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        titleLarge = base.titleLarge.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        titleMedium = base.titleMedium.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        titleSmall = base.titleSmall.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        bodyLarge = base.bodyLarge.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        bodyMedium = base.bodyMedium.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        bodySmall = base.bodySmall.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        labelLarge = base.labelLarge.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        labelMedium = base.labelMedium.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        labelSmall = base.labelSmall.copy(fontFamily = mono, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
    )
}

private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun NovaScaleTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) NovaDarkColors else NovaLightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = semantic.primary,
            onPrimary = Color(0xFF091425),
            primaryContainer = Color(0xFF233653),
            onPrimaryContainer = Color(0xFFD9E4FF),
            secondary = semantic.statusConnected,
            tertiary = semantic.accent,
            background = semantic.backgroundPrimary,
            surface = semantic.backgroundSecondary,
            surfaceVariant = semantic.backgroundSecondary,
            surfaceContainerLowest = semantic.backgroundPrimary,
            surfaceContainerLow = semantic.backgroundSecondary,
            surfaceContainer = Color(0xFF1C2430),
            surfaceContainerHigh = Color(0xFF232D3A),
            surfaceContainerHighest = Color(0xFF2B3746),
            onBackground = semantic.textPrimary,
            onSurface = semantic.textPrimary,
            onSurfaceVariant = semantic.textSecondary,
            outline = semantic.textSecondary,
            outlineVariant = semantic.borderSubtle,
            // Red labels must also contrast with neutral dialog/card surfaces.
            error = Color(0xFFFF8A8E),
            onError = Color(0xFF240305),
        )
    } else {
        lightColorScheme(
            primary = semantic.primary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE7EDFF),
            onPrimaryContainer = Color(0xFF203A94),
            secondary = semantic.statusConnected,
            tertiary = semantic.accent,
            background = semantic.backgroundPrimary,
            surface = semantic.backgroundSecondary,
            surfaceVariant = semantic.backgroundSecondary,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = semantic.backgroundPrimary,
            surfaceContainer = Color(0xFFEDF0F4),
            surfaceContainerHigh = Color(0xFFE6EAF0),
            surfaceContainerHighest = Color(0xFFDDE3EB),
            onBackground = semantic.textPrimary,
            onSurface = semantic.textPrimary,
            onSurfaceVariant = semantic.textSecondary,
            outline = semantic.textSecondary,
            outlineVariant = semantic.borderSubtle,
            error = Color(0xFFB42329),
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalNovaColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NovaTypography,
            shapes = NovaShapes,
        ) {
            Surface(color = semantic.backgroundPrimary, content = content)
        }
    }
}

@Composable
fun NovaGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = NovaTheme.colors.backgroundSecondary,
        border = BorderStroke(1.dp, NovaTheme.colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun NovaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = NovaSpacing.xs, bottom = NovaSpacing.xs),
        style = MaterialTheme.typography.labelLarge,
        color = NovaTheme.colors.textSecondary,
    )
}

@Composable
fun NovaStatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(NovaSizes.statusIndicatorSmall),
        shape = RoundedCornerShape(50),
        color = if (connected) NovaTheme.colors.statusConnected else NovaTheme.colors.statusDisconnected,
        content = {},
    )
}

@Composable
fun NovaPlatformTile(os: String?, modifier: Modifier = Modifier) {
    val normalized = os.orEmpty().lowercase()
    val platform = when {
        "android" in normalized -> NovaPlatform.Android
        "iphone" in normalized || normalized == "ios" -> NovaPlatform.Apple
        "ipad" in normalized || "ipados" in normalized -> NovaPlatform.Apple
        "mac" in normalized || "darwin" in normalized -> NovaPlatform.Apple
        "windows" in normalized -> NovaPlatform.Windows
        "linux" in normalized ||
            "ubuntu" in normalized ||
            "debian" in normalized ||
            "fedora" in normalized ||
            "centos" in normalized ||
            "alpine" in normalized -> NovaPlatform.Linux
        else -> NovaPlatform.Other
    }
    val background = when (platform) {
        NovaPlatform.Apple -> NovaTheme.colors.platformApple
        NovaPlatform.Linux, NovaPlatform.Android -> NovaTheme.colors.platformLinux
        NovaPlatform.Windows -> NovaTheme.colors.platformWindows
        NovaPlatform.Other -> NovaTheme.colors.platformOther
    }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (platform == NovaPlatform.Linux) {
                    Brush.linearGradient(
                        listOf(NovaTheme.colors.accent, NovaTheme.colors.primary),
                    )
                } else {
                    Brush.linearGradient(listOf(background, background))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (platform.drawable != null) {
            Icon(
                painter = painterResource(platform.drawable),
                contentDescription = stringResource(platform.contentDescriptionRes),
                modifier = Modifier.size(platform.iconSize),
                tint = Color.White,
            )
        } else {
            Icon(
                imageVector = NovaIcons.Server,
                contentDescription = stringResource(platform.contentDescriptionRes),
                modifier = Modifier.size(platform.iconSize),
                tint = Color.White,
            )
        }
    }
}

private enum class NovaPlatform(
    @DrawableRes val drawable: Int?,
    @StringRes val contentDescriptionRes: Int,
    val iconSize: androidx.compose.ui.unit.Dp,
) {
    Android(R.drawable.ic_platform_android, R.string.cd_android, 20.dp),
    Apple(R.drawable.ic_platform_apple, R.string.cd_apple, 18.dp),
    Linux(R.drawable.ic_platform_linux, R.string.cd_linux, 21.dp),
    Windows(R.drawable.ic_platform_windows, R.string.cd_windows, 19.dp),
    Other(null, R.string.cd_host, 19.dp),
}

@Composable
fun NovaActionRow(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleMaxLines: Int = 1,
    descriptionMaxLines: Int = 1,
    trailing: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = NovaIcons.ChevronRight,
            contentDescription = null,
            tint = NovaTheme.colors.textSecondary,
        )
    },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.rowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(NovaSizes.compactIconButton),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconColor,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NovaTheme.colors.textPrimary,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTheme.colors.textSecondary,
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
