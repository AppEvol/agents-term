package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.data.AppThemeId

/** The color for a tab status, from [colors] (pass a bar's `semantic` colors when drawing on that bar). */
fun statusColor(status: String, colors: SemanticColors): Color = when (status) {
    "working" -> colors.info
    "blocked" -> colors.warning
    "done" -> colors.success
    "error" -> colors.danger
    else -> colors.idle
}

fun statusLabel(status: String): String = when (status) {
    "working" -> "Working"
    "blocked" -> "Blocked"
    "done" -> "Finished"
    "error" -> "Error"
    else -> "Idle"
}

@Composable
fun HerdrTheme(
    theme: AppThemeId = AppThemeId.Default,
    content: @Composable () -> Unit,
) {
    val spec = appThemeSpec(theme, isSystemInDarkTheme())
    CompositionLocalProvider(LocalAppThemeSpec provides spec) {
        MaterialTheme(
            colorScheme = spec.colorScheme,
            typography = spec.typography,
            shapes = spec.shapes,
            content = content,
        )
    }
}

/** Paints the theme's title bar behind a top app bar that uses [titleBarColors]. */
@Composable
fun TitleBarBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.background(AppTheme.chrome.titleBar.background)) { content() }
}

/** Top app bar colors for a bar wrapped in [TitleBarBackground]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun titleBarColors(): TopAppBarColors {
    val content = AppTheme.chrome.titleBar.content
    return TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
        navigationIconContentColor = content,
        titleContentColor = content,
        actionIconContentColor = content,
    )
}
