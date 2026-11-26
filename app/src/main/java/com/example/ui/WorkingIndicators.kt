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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.ui.theme.LunaMarquee
import com.example.ui.theme.SemanticColors
import com.example.ui.theme.isLunaControls
import com.example.ui.theme.statusColor
import kotlin.math.PI
import kotlin.math.sin

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
    if (isLunaControls) {
        LunaMarquee(phase = phase, modifier = modifier)
        return
    }
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

private const val THINKING_ROBOT_MAX_DOTS = 3
private const val THINKING_ROBOT_STEP_MILLIS = 380

/**
 * Robot with a thought bubble that fills with zero to three dots, then empties and starts again.
 * Marks the "agent is thinking" line under a streaming reply, so it reads differently from the
 * [WorkingPulseDots] in the "Working" badge above it.
 */
@Composable
fun ThinkingRobotIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val steps = THINKING_ROBOT_MAX_DOTS + 1
    val transition = rememberInfiniteTransition(label = "thinking_robot")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = steps.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = THINKING_ROBOT_STEP_MILLIS * steps, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinking_robot_cycle",
    )
    // Decorative: the status text beside it already says what the agent is doing.
    Canvas(modifier = modifier) {
        drawThinkingRobot(color = color, dots = cycle.toInt().coerceIn(0, THINKING_ROBOT_MAX_DOTS))
    }
}

private fun DrawScope.drawThinkingRobot(color: Color, dots: Int) {
    val w = size.width
    val h = size.height
    val stroke = h * 0.075f
    val outline = Stroke(width = stroke, join = StrokeJoin.Round)

    // Robot head, bottom left: antenna, outlined face, two eyes and a mouth.
    val headLeft = stroke / 2f
    val headRight = w * 0.40f
    val headTop = h * 0.42f
    val headBottom = h - stroke / 2f
    val headCenterX = (headLeft + headRight) / 2f
    val headWidth = headRight - headLeft
    val headHeight = headBottom - headTop
    drawRoundRect(
        color = color,
        topLeft = Offset(headLeft, headTop),
        size = Size(headWidth, headHeight),
        cornerRadius = CornerRadius(h * 0.12f),
        style = outline,
    )
    val antennaTip = Offset(headCenterX, h * 0.22f)
    drawLine(color, Offset(headCenterX, headTop), antennaTip, strokeWidth = stroke)
    drawCircle(color, radius = stroke, center = antennaTip)
    val eyeY = headTop + headHeight * 0.38f
    for (side in listOf(-1f, 1f)) {
        drawCircle(color, radius = stroke * 0.9f, center = Offset(headCenterX + side * headWidth * 0.22f, eyeY))
    }
    val mouthY = headTop + headHeight * 0.72f
    drawLine(
        color = color,
        start = Offset(headCenterX - headWidth * 0.2f, mouthY),
        end = Offset(headCenterX + headWidth * 0.2f, mouthY),
        strokeWidth = stroke * 0.8f,
        cap = StrokeCap.Round,
    )

    // Thought bubble, top right, trailing two small puffs down toward the head.
    val bubbleLeft = w * 0.54f
    val bubbleRight = w - stroke / 2f
    val bubbleTop = stroke / 2f
    val bubbleBottom = h * 0.60f
    val bubbleHeight = bubbleBottom - bubbleTop
    drawRoundRect(
        color = color,
        topLeft = Offset(bubbleLeft, bubbleTop),
        size = Size(bubbleRight - bubbleLeft, bubbleHeight),
        cornerRadius = CornerRadius(bubbleHeight / 2f),
        style = outline,
    )
    drawCircle(color, radius = stroke * 0.95f, center = Offset(w * 0.56f, h * 0.76f))
    drawCircle(color, radius = stroke * 0.6f, center = Offset(w * 0.47f, h * 0.86f))

    // The progress dots keep fixed slots, so the bubble fills left to right.
    val slot = (bubbleRight - bubbleLeft) / (THINKING_ROBOT_MAX_DOTS + 1)
    val dotY = bubbleTop + bubbleHeight / 2f
    for (i in 0 until dots) {
        drawCircle(color, radius = stroke * 0.8f, center = Offset(bubbleLeft + slot * (i + 1), dotY))
    }
}

private const val TOOL_WRENCH_SWING_MILLIS = 620
private const val TOOL_WRENCH_SWING_DEGREES = 24f

/**
 * Wrench that rocks back and forth as if turning a bolt. Stands in for an indeterminate spinner
 * while a tool call runs, and reports itself as one to accessibility services.
 */
@Composable
fun ToolWorkingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "tool_wrench")
    val swing by transition.animateFloat(
        initialValue = -TOOL_WRENCH_SWING_DEGREES,
        targetValue = TOOL_WRENCH_SWING_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TOOL_WRENCH_SWING_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool_wrench_swing",
    )
    Icon(
        imageVector = Icons.Rounded.Build,
        contentDescription = null,
        tint = color,
        modifier = modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .graphicsLayer { rotationZ = swing },
    )
}
