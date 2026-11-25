package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AppTheme
import com.example.ui.theme.SemanticColors
import com.example.ui.theme.statusColor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Marks a tab whose latest result the user has not opened yet. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tab_unread)
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = label },
    )
}

/**
 * Status dot that emits a pulsing radar halo while a tab is actively working.
 */
@Composable
fun TabStatusDot(
    status: String,
    dotSize: Dp = 8.dp,
    modifier: Modifier = Modifier,
    colors: SemanticColors = AppTheme.colors,
) {
    val color = statusColor(status, colors)
    if (status == "working") {
        val transition = rememberInfiniteTransition(label = "tab_working_dot")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "tab_working_halo",
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(dotSize * 1.9f),
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val scale = 1f + 1.1f * progress
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - progress) * 0.6f
                    }
                    .clip(CircleShape)
                    .background(color),
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Three-dot wave animation showing live agent activity.
 */
@Composable
fun WorkingPulseDots(
    color: Color = AppTheme.colors.info,
    dotSize: Dp = 5.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "working_pulse_dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "working_dots_phase",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dotSize * 0.7f),
    ) {
        for (i in 0..2) {
            val shifted = (phase - i * 0.2f + 1f) % 1f
            val wave = ((sin(shifted * 2.0 * PI) + 1.0) / 2.0).toFloat()
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val scale = 0.72f + 0.36f * wave
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.32f + 0.68f * wave
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private const val SANDCLOCK_CYCLE_MILLIS = 2000

/** Share of the cycle spent draining; the rest is the flip. */
private const val SANDCLOCK_DRAIN_SHARE = 0.72f

/**
 * Sandclock that drains its sand, flips over and starts again. Stands in for an indeterminate
 * spinner while a tool call runs, and reports itself as one to accessibility services.
 */
@Composable
fun SandclockIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "sandclock")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SANDCLOCK_CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sandclock_cycle",
    )
    Canvas(
        modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val drained = (cycle / SANDCLOCK_DRAIN_SHARE).coerceAtMost(1f)
        val flip = ((cycle - SANDCLOCK_DRAIN_SHARE) / (1f - SANDCLOCK_DRAIN_SHARE)).coerceAtLeast(0f)
        // A drained glass turned half a turn looks like a full one, so the loop restarts unseen.
        rotate(degrees = 180f * FastOutSlowInEasing.transform(flip)) {
            drawSandclock(color = color, drained = drained)
        }
    }
}

private fun DrawScope.drawSandclock(color: Color, drained: Float) {
    val stroke = size.minDimension * 0.09f
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val halfWidth = size.width * 0.32f
    val top = stroke / 2f
    val bottom = size.height - stroke / 2f
    val bulbHeight = centerY - top

    // Sand keeps its area: a bulb holding a share of the sand is filled to sqrt(share) of its height.
    val remaining = sqrt(1f - drained)
    val sandHalfWidth = halfWidth * remaining
    val topSand = Path().apply {
        moveTo(centerX - sandHalfWidth, centerY - bulbHeight * remaining)
        lineTo(centerX + sandHalfWidth, centerY - bulbHeight * remaining)
        lineTo(centerX, centerY)
        close()
    }
    val pileTop = centerY + bulbHeight * remaining
    val bottomSand = Path().apply {
        moveTo(centerX - sandHalfWidth, pileTop)
        lineTo(centerX + sandHalfWidth, pileTop)
        lineTo(centerX + halfWidth, bottom)
        lineTo(centerX - halfWidth, bottom)
        close()
    }
    val sandColor = color.copy(alpha = color.alpha * 0.55f)
    drawPath(topSand, sandColor)
    drawPath(bottomSand, sandColor)
    if (drained > 0f && drained < 1f) {
        drawLine(
            color = sandColor,
            start = Offset(centerX, centerY),
            end = Offset(centerX, pileTop),
            strokeWidth = stroke * 0.7f,
        )
    }

    val glass = Path().apply {
        moveTo(centerX - halfWidth, top)
        lineTo(centerX + halfWidth, top)
        lineTo(centerX - halfWidth, bottom)
        lineTo(centerX + halfWidth, bottom)
        close()
    }
    drawPath(glass, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    val capHalfWidth = halfWidth + stroke
    for (y in listOf(top, bottom)) {
        drawLine(
            color = color,
            start = Offset(centerX - capHalfWidth, y),
            end = Offset(centerX + capHalfWidth, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
