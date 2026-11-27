package com.appevol.agentsterm.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appevol.agentsterm.data.AppThemeId

private val MidnightColors = darkColorScheme(
    primary = HerdrBlueSoft,
    onPrimary = Color(0xFF07162B),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Color(0xFFB8A1FF),
    onSecondary = Color(0xFF1E103D),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = HerdrEmerald,
    onTertiary = Color(0xFF04261B),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = HerdrRed,
)

private val DaylightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
)

// White content panes and menus on beige window chrome, with the pale-blue XP selection highlight.
private val LunaColors = lightColorScheme(
    primary = LunaBlue,
    onPrimary = Color.White,
    primaryContainer = LunaSelectionSoft,
    onPrimaryContainer = LunaNavy,
    inversePrimary = LunaBlue,
    secondary = LunaSelection,
    onSecondary = Color.White,
    secondaryContainer = LunaSelectionSoft,
    onSecondaryContainer = LunaNavy,
    tertiary = LunaGreen,
    onTertiary = Color.White,
    background = LunaBeige,
    onBackground = LunaInk,
    surface = LunaBeige,
    onSurface = LunaInk,
    surfaceVariant = LunaBeigeDeep,
    onSurfaceVariant = LunaInkMuted,
    // Raised surfaces stay beige instead of picking up a blue cast.
    surfaceTint = LunaBeige,
    // Snackbars look like XP's pale-yellow tooltips.
    inverseSurface = LunaTooltip,
    inverseOnSurface = LunaInk,
    surfaceBright = Color.White,
    surfaceDim = LunaBeigeDeeper,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LunaBeigeLight,
    surfaceContainer = Color.White,
    surfaceContainerHigh = LunaBeigeDeep,
    surfaceContainerHighest = LunaBeigeDeeper,
    outline = LunaInputBorder,
    outlineVariant = LunaGroupBorder,
    error = LunaRed,
    onError = Color.White,
)

private val MidnightSemantic = SemanticColors(
    info = HerdrBlue,
    success = HerdrEmerald,
    warning = HerdrAmber,
    onWarning = Color.Black,
    danger = HerdrRed,
    accent = HerdrPurple,
    idle = HerdrIdleSlate,
)

private val DaylightSemantic = SemanticColors(
    info = LightInfo,
    success = LightSuccess,
    warning = LightWarning,
    onWarning = Color.White,
    danger = LightError,
    accent = LightAccent,
    idle = LightIdle,
)

private val LunaSemantic = SemanticColors(
    info = LunaBlue,
    success = LunaGreen,
    warning = LunaOrange,
    onWarning = Color.White,
    danger = LunaRed,
    accent = LunaPurple,
    idle = LunaIdle,
)

private val LunaTitleBarSemantic = SemanticColors(
    info = LunaTitleInfo,
    success = LunaTitleSuccess,
    warning = LunaTitleWarning,
    onWarning = Color.Black,
    danger = LunaTitleDanger,
    accent = LunaTitleAccent,
    idle = LunaTitleIdle,
)

private val RoundedShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

private val LunaMaxCorner = 4.dp

// XP windows are square with barely rounded controls; only the window's top corners are rounder.
private val LunaShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(LunaMaxCorner),
    large = RoundedCornerShape(LunaMaxCorner),
    extraLarge = RoundedCornerShape(8.dp),
)

/** Chrome for themes without a distinct title bar: bars are plain surfaces of the color scheme. */
private fun flatChrome(scheme: ColorScheme, semantic: SemanticColors, isDark: Boolean) = ChromeStyle(
    titleBar = BarStyle(
        background = SolidColor(scheme.surface),
        content = scheme.onSurface,
        mutedContent = scheme.onSurfaceVariant,
        semantic = semantic,
    ),
    panelHeader = BarStyle(
        background = SolidColor(scheme.surfaceContainer),
        content = scheme.onSurface,
        mutedContent = scheme.onSurfaceVariant,
        semantic = semantic,
    ),
    controls = ControlStyle.MATERIAL,
    selectedRow = null,
    primaryAction = scheme.primary,
    onPrimaryAction = scheme.onPrimary,
    maxCorner = Dp.Infinity,
    lightStatusBarIcons = isDark,
)

private val LunaBar = BarStyle(
    background = Brush.verticalGradient(LunaTitleBarFallbackColors),
    bands = LunaTitleBarBands,
    content = Color.White,
    mutedContent = LunaOnTitleBarMuted,
    semantic = LunaTitleBarSemantic,
    titleShadow = Shadow(color = LunaTitleTextShadow, offset = Offset(2f, 2f), blurRadius = 1f),
)

val MidnightTheme = AppThemeSpec(
    id = AppThemeId.MIDNIGHT,
    isDark = true,
    colorScheme = MidnightColors,
    colors = MidnightSemantic,
    chrome = flatChrome(MidnightColors, MidnightSemantic, isDark = true),
    typography = HerdrTypography,
    shapes = RoundedShapes,
)

val DaylightTheme = AppThemeSpec(
    id = AppThemeId.DAYLIGHT,
    isDark = false,
    colorScheme = DaylightColors,
    colors = DaylightSemantic,
    chrome = flatChrome(DaylightColors, DaylightSemantic, isDark = false),
    typography = HerdrTypography,
    shapes = RoundedShapes,
)

val XpLunaTheme = AppThemeSpec(
    id = AppThemeId.XP_LUNA,
    isDark = false,
    colorScheme = LunaColors,
    colors = LunaSemantic,
    chrome = ChromeStyle(
        titleBar = LunaBar,
        controls = ControlStyle.LUNA,
        // The solid blue bar XP puts behind the selected item of a list or menu.
        selectedRow = BarStyle(
            background = SolidColor(LunaSelection),
            content = Color.White,
            mutedContent = LunaOnTitleBarMuted,
            semantic = LunaTitleBarSemantic,
        ),
        panelHeader = LunaBar,
        // The green of the Start button.
        primaryAction = LunaStartGreen,
        onPrimaryAction = Color.White,
        maxCorner = LunaMaxCorner,
        lightStatusBarIcons = true,
    ),
    // XP set its UI in Tahoma; the platform sans-serif is the closest font every phone has.
    typography = appTypography(display = FontFamily.SansSerif, body = FontFamily.SansSerif),
    shapes = LunaShapes,
)

/** The spec for [id]. [systemInDarkTheme] only matters for [AppThemeId.SYSTEM]. */
fun appThemeSpec(id: AppThemeId, systemInDarkTheme: Boolean): AppThemeSpec = when (id) {
    AppThemeId.XP_LUNA -> XpLunaTheme
    AppThemeId.MIDNIGHT -> MidnightTheme
    AppThemeId.DAYLIGHT -> DaylightTheme
    AppThemeId.SYSTEM -> if (systemInDarkTheme) MidnightTheme else DaylightTheme
}
