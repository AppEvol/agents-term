package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Herdr Obsidian-Slate + Electric Cobalt & Emerald Palette
val HerdrBlue = Color(0xFF4F8CFF)
val HerdrBlueSoft = Color(0xFF8AB4F8)
val HerdrPurple = Color(0xFF9E7BFF)
val HerdrAmber = Color(0xFFF5A623)
val HerdrEmerald = Color(0xFF34D399)
val HerdrRed = Color(0xFFEF5350)
val HerdrIdleSlate = Color(0xFF7B8794)

// Dark surfaces
val DarkBackground = Color(0xFF0B0F17)
val DarkSurface = Color(0xFF111722)
val DarkSurfaceVariant = Color(0xFF1B2333)
val DarkSurfaceContainerLowest = Color(0xFF070A10)
val DarkSurfaceContainerLow = Color(0xFF111722)
val DarkSurfaceContainer = Color(0xFF171F2E)
val DarkSurfaceContainerHigh = Color(0xFF202A3D)
val DarkSurfaceContainerHighest = Color(0xFF2A364F)
val DarkOnSurface = Color(0xFFE8EEF8)
val DarkOnSurfaceVariant = Color(0xFF9AA8C2)
val DarkOutline = Color(0xFF364461)
val DarkOutlineVariant = Color(0xFF232D42)
val DarkPrimaryContainer = Color(0xFF1A3358)
val DarkOnPrimaryContainer = Color(0xFFD6E6FF)
val DarkSecondaryContainer = Color(0xFF2A1F4A)
val DarkOnSecondaryContainer = Color(0xFFE7DEFF)

// Light surfaces
val LightPrimary = Color(0xFF1D4ED8)
val LightPrimaryContainer = Color(0xFFDBEAFE)
val LightOnPrimaryContainer = Color(0xFF1E3A8A)
val LightSecondary = Color(0xFF6D28D9)
val LightSecondaryContainer = Color(0xFFEDE9FE)
val LightOnSecondaryContainer = Color(0xFF4C1D95)
val LightTertiary = Color(0xFF059669)
val LightBackground = Color(0xFFF6F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF2F8)
val LightSurfaceContainerLowest = Color(0xFFF1F5F9)
val LightSurfaceContainerLow = Color(0xFFF8FAFC)
val LightSurfaceContainer = Color(0xFFEEF2F7)
val LightSurfaceContainerHigh = Color(0xFFE2E8F0)
val LightSurfaceContainerHighest = Color(0xFFD5DFEC)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)
val LightError = Color(0xFFDC2626)

// Semantic colors tuned for the light palette, where the dark palette's pastels wash out.
val LightInfo = Color(0xFF1D4ED8)
val LightSuccess = Color(0xFF047857)
val LightWarning = Color(0xFFB45309)
val LightAccent = Color(0xFF6D28D9)
val LightIdle = Color(0xFF64748B)

// Windows XP "Luna" (blue) palette. The values are the ones the XP visual style draws with.
val LunaBlue = Color(0xFF0054E3)
val LunaNavy = Color(0xFF0A246A)
val LunaSelection = Color(0xFF316AC5)
val LunaSelectionSoft = Color(0xFFC1D2EE)
val LunaBeige = Color(0xFFECE9D8)
val LunaBeigeLight = Color(0xFFF7F5EC)
val LunaBeigeDeep = Color(0xFFE2DEC9)
val LunaBeigeDeeper = Color(0xFFD6D1BA)
val LunaBeigeShadow = Color(0xFFACA899)
val LunaGroupBorder = Color(0xFFD0D0BF)
val LunaInputBorder = Color(0xFF7F9DB9)
val LunaInk = Color(0xFF000000)
val LunaInkMuted = Color(0xFF4A473A)
val LunaInkDisabled = Color(0xFFACA899)
val LunaGroupTitle = Color(0xFF0046D5)
val LunaGreen = Color(0xFF1F7A16)
val LunaOrange = Color(0xFFB85C00)
val LunaRed = Color(0xFFC4210F)
val LunaPurple = Color(0xFF6B3FA0)
val LunaIdle = Color(0xFF7A7868)
val LunaTooltip = Color(0xFFFFFFE1)

// The Luna title bar: a bright highlight along the top edge, a long mid-blue body, and a dark lip
// along the bottom edge.
val LunaTitleBarBrush = Brush.verticalGradient(
    0.00f to Color(0xFF0997FF),
    0.08f to Color(0xFF0053EE),
    0.40f to Color(0xFF0050EE),
    0.88f to Color(0xFF0066FF),
    0.93f to Color(0xFF0066FF),
    0.95f to Color(0xFF005BFF),
    0.96f to Color(0xFF003DD7),
    1.00f to Color(0xFF003DD7),
)
val LunaTitleTextShadow = Color(0xFF0F1089)
val LunaOnTitleBarMuted = Color(0xFFD6E4FF)

// Caption buttons (minimize, maximize) on the title bar.
val LunaCaptionButtonColors = listOf(Color(0xFF6BA5FF), Color(0xFF2A6CEC), Color(0xFF1A4BC4))

// The red close button, and the frame around a window.
val LunaCloseButtonColors = listOf(Color(0xFFF1A689), Color(0xFFD5462A), Color(0xFFB02A12))
val LunaWindowFrame = Color(0xFF0855DD)
val LunaWindowFrameEdge = Color(0xFF001EA0)

// Push buttons: a white-to-warm-grey face inside a navy border.
val LunaButtonBorder = Color(0xFF003C74)
val LunaButtonBorderDisabled = Color(0xFFC9C7BA)
val LunaButtonFaceBrush = Brush.verticalGradient(0.00f to Color.White, 0.86f to Color(0xFFECEBE5), 1.00f to Color(0xFFD8D0C4))
val LunaButtonPressedBrush = Brush.verticalGradient(
    0.00f to Color(0xFFCDCAC3),
    0.08f to Color(0xFFE3E3DB),
    0.94f to Color(0xFFE5E5DE),
    1.00f to Color(0xFFF2F2F1),
)
val LunaButtonFaceDisabled = Color(0xFFF5F4EA)

// The ring inside the default button of a dialog.
val LunaDefaultRingColors = listOf(Color(0xFFCEE7FF), Color(0xFFBCD4F6), Color(0xFF89ADE4))

// Check boxes and radio buttons.
val LunaCheckBorder = Color(0xFF1D5281)
val LunaCheckBorderDisabled = Color(0xFFCAC8BB)
val LunaCheckFaceColors = listOf(Color(0xFFDCDCD7), Color.White)
val LunaCheckMark = Color(0xFF21A121)

// The Start button.
val LunaStartGreen = Color(0xFF3C923A)
val LunaStartBrush = Brush.verticalGradient(
    0.00f to Color(0xFF7CC576),
    0.08f to Color(0xFF47A341),
    0.50f to Color(0xFF3C923A),
    0.90f to Color(0xFF2F7E2E),
    1.00f to Color(0xFF25681F),
)
val LunaStartBorder = Color(0xFF1E5A1A)
val LunaStartTextShadow = Color(0xFF1B4D16)

// Tab controls: the selected tab wears an orange cap.
val LunaTabBorder = Color(0xFF91A7B4)
val LunaTabPageBorder = Color(0xFF919B9C)
val LunaTabPage = Color(0xFFFCFCFE)
val LunaTabFaceBrush = Brush.verticalGradient(
    0.00f to Color.White,
    0.26f to Color(0xFFFAFAF9),
    0.95f to Color(0xFFF0F0EA),
    1.00f to Color(0xFFECEBE5),
)
val LunaTabCapEdge = Color(0xFFE68B2C)
val LunaTabCap = Color(0xFFFFC73C)

// The Explorer task pane: a blue gutter holding pale panels with gradient headers.
val LunaTaskPaneColors = listOf(Color(0xFF7BA2E7), Color(0xFF6375D6))
val LunaTaskHeaderColors = listOf(Color.White, Color(0xFFC6D3F7))
val LunaTaskHeaderText = Color(0xFF215DC6)
val LunaTaskBody = Color(0xFFD6DFF7)

// The sky, clouds (x, y and radius as fractions of the size) and hills of the XP backdrop.
val LunaSkyColors = listOf(Color(0xFF1E5ACB), Color(0xFF3F86E6), Color(0xFF9CCBF7))
val LunaClouds = listOf(
    Triple(0.63f, 0.21f, 0.13f),
    Triple(0.71f, 0.25f, 0.17f),
    Triple(0.80f, 0.22f, 0.12f),
    Triple(0.31f, 0.31f, 0.09f),
    Triple(0.37f, 0.33f, 0.11f),
)
val LunaFarHillColors = listOf(Color(0xFF6FB63A), Color(0xFF3E8A1E))
val LunaNearHillColors = listOf(Color(0xFF8ED04B), Color(0xFF4C9F22))

// The marquee progress bar's green blocks.
val LunaProgressBorder = Color(0xFF686868)
val LunaProgressBrush = Brush.verticalGradient(
    0.00f to Color(0xFFACEDAD),
    0.14f to Color(0xFF7BE47D),
    0.28f to Color(0xFF4CDA50),
    0.42f to Color(0xFF2ED330),
    0.57f to Color(0xFF42D845),
    0.71f to Color(0xFF76E275),
    0.85f to Color(0xFF8FE791),
    1.00f to Color.White,
)

// Status colors that stay readable on the blue title bar.
val LunaTitleInfo = Color(0xFFBFDCFF)
val LunaTitleSuccess = Color(0xFF9BF57A)
val LunaTitleWarning = Color(0xFFFFD75E)
val LunaTitleDanger = Color(0xFFFFB3A8)
val LunaTitleAccent = Color(0xFFE0CCFF)
val LunaTitleIdle = Color(0xFFC9D8F5)
