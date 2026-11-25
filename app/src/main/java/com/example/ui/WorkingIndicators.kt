package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.HerdrBlue
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
) {
    val color = statusColor(status)
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
    color: Color = HerdrBlue,
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
