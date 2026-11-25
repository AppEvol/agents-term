package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.R

/** True inside the confirm slot of a [ThemedAlertDialog]: buttons there are the dialog's default. */
internal val LocalIsDefaultButton = compositionLocalOf { false }

private val LunaWindowShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

/**
 * A dialog with a title, a body and its buttons. In the XP look it is a small window: a blue
 * title bar with a red close button, over a beige body.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemedAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    if (!isLunaControls) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
        return
    }
    val titleBar = AppTheme.chrome.titleBar
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .shadow(12.dp, LunaWindowShape)
                .clip(LunaWindowShape)
                .background(LunaWindowFrame)
                .border(1.dp, LunaWindowFrameEdge, LunaWindowShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .barBackground(titleBar)
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalContentColor provides titleBar.content) {
                        ProvideTextStyle(titleBarTitleStyle(MaterialTheme.typography.titleSmall), title)
                    }
                }
                LunaCloseButton(onClick = onDismissRequest)
            }
            Column(
                modifier = Modifier
                    .padding(start = 3.dp, end = 3.dp, bottom = 3.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                    }
                    // XP order: the default button first, Cancel last.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CompositionLocalProvider(LocalIsDefaultButton provides true, confirmButton)
                        dismissButton?.invoke()
                    }
                }
            }
        }
    }
}

/** The red close button at the end of an XP title bar. */
@Composable
private fun LunaCloseButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val label = stringResource(R.string.action_close)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(Brush.linearGradient(LunaCloseButtonColors))
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
