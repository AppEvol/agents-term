package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.example.data.AppThemeId

/** Status and accent colors that Material's color scheme has no slot for. */
@Immutable
data class SemanticColors(
    /** In progress, informational. */
    val info: Color,
    /** Finished, connected, copied. */
    val success: Color,
    /** Blocked, needs attention. */
    val warning: Color,
    /** Text and icons on a [warning]-filled control. */
    val onWarning: Color,
    /** Errors and destructive actions. */
    val danger: Color,
    /** Secondary accent, used for the model's thinking. */
    val accent: Color,
    /** Idle, disconnected. */
    val idle: Color,
)

/** A filled band (title bar, panel header): its background and the colors that read on it. */
@Immutable
data class BarStyle(
    val background: Brush,
    val content: Color,
    val mutedContent: Color,
    val semantic: SemanticColors,
)

/** The parts of a theme's look that go beyond colors and type. */
@Immutable
data class ChromeStyle(
    /** Top app bars. Drawn behind the status bar too. */
    val titleBar: BarStyle,
    /** The header card of the tab drawer and sidebar. */
    val panelHeader: BarStyle,
    /** The main call to action, "New tab". */
    val primaryAction: Color,
    val onPrimaryAction: Color,
    /** Corner radii are capped at this, so one value squares off the whole app. */
    val maxCorner: Dp,
    /** Whether the status bar icons should be light, to read on [titleBar]. */
    val lightStatusBarIcons: Boolean,
)

/** Everything that defines one theme. Add a theme by building one of these in Themes.kt. */
@Immutable
data class AppThemeSpec(
    val id: AppThemeId,
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val colors: SemanticColors,
    val chrome: ChromeStyle,
    val typography: Typography,
    val shapes: Shapes,
)

internal val LocalAppThemeSpec = staticCompositionLocalOf { MidnightTheme }

/** The current theme's extras, alongside `MaterialTheme` for the standard color scheme and type. */
object AppTheme {
    val spec: AppThemeSpec
        @Composable @ReadOnlyComposable
        get() = LocalAppThemeSpec.current

    val colors: SemanticColors
        @Composable @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.colors

    val chrome: ChromeStyle
        @Composable @ReadOnlyComposable
        get() = LocalAppThemeSpec.current.chrome
}

/** A rounded shape whose radius the theme may cap. Use this instead of `RoundedCornerShape`. */
@Composable
@ReadOnlyComposable
fun themedCorner(radius: Dp): Shape = RoundedCornerShape(radius.coerceAtMost(AppTheme.chrome.maxCorner))

@Composable
@ReadOnlyComposable
fun themedCorner(topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp): Shape {
    val max = AppTheme.chrome.maxCorner
    return RoundedCornerShape(
        topStart = topStart.coerceAtMost(max),
        topEnd = topEnd.coerceAtMost(max),
        bottomEnd = bottomEnd.coerceAtMost(max),
        bottomStart = bottomStart.coerceAtMost(max),
    )
}
