package com.appevol.agentsterm.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.roundToInt

/**
 * A vertical gradient with any number of unevenly placed stops, drawn as a stack of two-color
 * bands rather than as one multi-stop shader.
 *
 * Do not replace this with `Brush.verticalGradient(colorStops = ...)`: a gradient with more than
 * a few stops takes a different shader path that crashes the software GPU of the CI emulator
 * (`-gpu swiftshader_indirect`), which takes the whole instrumented test run down with it.
 * Plain two- and three-color gradients are fine.
 */
@Immutable
class BandedGradient(private vararg val stops: Pair<Float, Color>) {
    /** Fills the rectangle at [topLeft] of [size], or the whole drawing area by default. */
    fun draw(scope: DrawScope, topLeft: Offset = Offset.Zero, size: Size = scope.size) {
        for (i in 0 until stops.lastIndex) {
            val (fromStop, fromColor) = stops[i]
            val (toStop, toColor) = stops[i + 1]
            // Whole pixels, so neighbouring bands meet without a seam.
            val top = topLeft.y + (size.height * fromStop).roundToInt()
            val bottom = topLeft.y + (size.height * toStop).roundToInt()
            if (bottom <= top) continue
            scope.drawRect(
                brush = Brush.verticalGradient(listOf(fromColor, toColor), startY = top, endY = bottom),
                topLeft = Offset(topLeft.x, top),
                size = Size(size.width, bottom - top),
            )
        }
    }
}

fun Modifier.background(gradient: BandedGradient): Modifier = drawBehind { gradient.draw(this) }

/** Paints a bar's background: its banded gradient if it has one, otherwise its brush. */
fun Modifier.barBackground(style: BarStyle): Modifier = if (style.bands != null) background(style.bands) else background(style.background)
