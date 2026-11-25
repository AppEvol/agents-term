package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.R

val DisplayFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)

val BodyFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
)

val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold),
)

val HerdrTypography = appTypography(display = DisplayFontFamily, body = BodyFontFamily)

private fun style(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/** The app's type scale, set in the given families. */
fun appTypography(display: FontFamily, body: FontFamily) = Typography(
    displayLarge = style(display, FontWeight.Bold, 36.sp, 42.sp, (-0.5).sp),
    headlineMedium = style(display, FontWeight.Bold, 26.sp, 32.sp, (-0.25).sp),
    headlineSmall = style(display, FontWeight.Bold, 22.sp, 28.sp, (-0.2).sp),
    titleLarge = style(display, FontWeight.SemiBold, 20.sp, 26.sp),
    titleMedium = style(display, FontWeight.SemiBold, 16.sp, 22.sp),
    titleSmall = style(body, FontWeight.SemiBold, 14.sp, 20.sp),
    bodyLarge = style(body, FontWeight.Normal, 15.5.sp, 23.sp),
    bodyMedium = style(body, FontWeight.Normal, 14.sp, 20.sp),
    bodySmall = style(body, FontWeight.Normal, 12.5.sp, 18.sp),
    labelLarge = style(body, FontWeight.SemiBold, 13.sp, 18.sp),
    labelMedium = style(body, FontWeight.Medium, 12.sp, 16.sp),
    labelSmall = style(body, FontWeight.Medium, 11.sp, 14.sp, 0.25.sp),
)
