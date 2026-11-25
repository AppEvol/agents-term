package com.example.ui.markdown

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/*
 * Markdown needs a real bold. A family that lists one regular file under every weight (as the
 * app theme's families do) tells Android it already has a bold font, so nothing is drawn bolder.
 * These families pair the regular file with a real heavier one. Italic has no file of its own:
 * Android slants the upright font.
 */

internal val MarkdownBodyFont = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
)

internal val MarkdownHeadingFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
)

internal val MarkdownMonoFont = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.SemiBold),
)
