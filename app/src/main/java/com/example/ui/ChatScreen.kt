package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ChatMessage
import com.example.data.DeniedAction
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.data.UsageStats
import com.example.ui.theme.HerdrAmber
import com.example.ui.theme.HerdrBlue
import com.example.ui.theme.HerdrEmerald
import com.example.ui.theme.HerdrPurple
import com.example.ui.theme.HerdrRed
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ChatWorkspace(
    tab: TabRecord,
    messages: List<ChatMessage>,
    isLoading: Boolean,
    isConnected: Boolean,
    modelLabel: String? = null,
    hasCustomModel: Boolean = false,
    onOpenModelPicker: (() -> Unit)? = null,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onRetryFullAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(tab.id) { mutableStateOf("") }
    val isWorking = tab.status == "working"

    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            messages.isNotEmpty() && lastVisible < messages.lastIndex - 1
        }
    }

    // Auto-scroll to latest message/delta when near bottom
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    val lastToolCount = messages.lastOrNull()?.toolCalls?.size ?: 0
    LaunchedEffect(tab.id, messages.size, lastTextLen / 40, lastToolCount) {
        if (messages.isNotEmpty() && !showScrollToBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 760.dp)
                        ) {
                            if (msg.role == "user") {
                                UserMessageBubble(msg)
                            } else {
                                AssistantMessageItem(
                                    tab = tab,
                                    message = msg,
                                    onRetryFullAccess = onRetryFullAccess,
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading && messages.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (messages.isEmpty()) {
                EmptyChatState(
                    tab = tab,
                    onPickPrompt = { prompt ->
                        if (isConnected && !isWorking) {
                            onSendMessage(prompt)
                        } else {
                            draft = prompt
                        }
                    },
                )
            }

            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .testTag("scroll_to_bottom_fab"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        ChatComposer(
            draft = draft,
            onDraftChange = { draft = it },
            placeholder = if (tab.agent == "shell") {
                stringResource(R.string.composer_shell_placeholder, tab.folderName)
            } else {
                stringResource(R.string.composer_agent_placeholder, tab.agentLabel)
            },
            isWorking = isWorking,
            isConnected = isConnected,
            statusDetail = if (isWorking) tab.statusDetail else null,
            modelLabel = modelLabel,
            hasCustomModel = hasCustomModel,
            onOpenModelPicker = onOpenModelPicker,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    onSendMessage(text)
                }
            },
            onInterrupt = onInterrupt,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatState(
    tab: TabRecord,
    onPickPrompt: (String) -> Unit,
) {
    val suggestions = remember(tab.agent) {
        if (tab.agent == "shell") {
            listOf("git status", "ls -la", "git log -n 5 --oneline")
        } else {
            listOf(
                "Summarize git status and recent commits",
                "Explain the architecture of this repo",
                "Run the test suite and report any issues",
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
            modifier = Modifier.size(60.dp),
        ) {
            Icon(
                imageVector = if (tab.agent == "shell") Icons.Rounded.Terminal else Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = tab.agentLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tab.cwd,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(22.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (prompt in suggestions) {
                AssistChip(
                    onClick = { onPickPrompt(prompt) },
                    label = {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (tab.agent == "shell") MonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UserMessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessageItem(
    tab: TabRecord,
    message: ChatMessage,
    onRetryFullAccess: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Agent label header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    imageVector = if (tab.agent == "shell") Icons.Rounded.Terminal else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(5.dp),
                )
            }
            Text(
                text = tab.agentLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (message.status == "streaming") {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = HerdrBlue,
                )
            }
        }

        // Collapsible Thinking block
        if (!message.thinking.isNullOrBlank()) {
            ThinkingAccordionCard(
                thinking = message.thinking,
                isStreaming = message.status == "streaming",
            )
        }

        // Collapsible Tool Calls
        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tc in message.toolCalls) {
                    ToolCallCard(toolCall = tc)
                }
            }
        }

        // Main response Markdown body
        if (message.text.isNotBlank()) {
            MarkdownText(
                markdown = message.text,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (
            message.status == "complete" &&
            message.error.isNullOrBlank() &&
            message.toolCalls.isEmpty() &&
            message.deniedActions.isEmpty() &&
            message.thinking.isNullOrBlank()
        ) {
            Text(
                text = stringResource(R.string.assistant_empty_response),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Permission Denied / Blocked inline card
        if (message.deniedActions.isNotEmpty()) {
            BlockedPermissionCard(
                deniedActions = message.deniedActions,
                onRetryFullAccess = onRetryFullAccess,
            )
        }

        // Error banner inside message
        if (!message.error.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = message.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        } else if (message.status == "interrupted") {
            Text(
                text = stringResource(R.string.status_interrupted),
                style = MaterialTheme.typography.labelSmall,
                color = HerdrAmber,
            )
        }

        // Footer: Usage metrics + copy full response action
        val usageSummary = remember(message.usage) { formatUsage(message.usage) }
        if (message.status != "streaming" && (message.text.isNotBlank() || usageSummary != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (usageSummary != null) {
                    Text(
                        text = usageSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (message.text.isNotBlank()) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.text))
                            copied = true
                        },
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_message),
                            modifier = Modifier.size(16.dp),
                            tint = if (copied) HerdrEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingAccordionCard(
    thinking: String,
    isStreaming: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, HerdrPurple.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = HerdrPurple,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isStreaming) {
                        stringResource(R.string.thinking_streaming_label)
                    } else {
                        stringResource(R.string.thinking_label)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = HerdrPurple,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(toolCall: ToolCallItem) {
    var expanded by remember(toolCall.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (toolCall.status) {
                    "running" -> CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = HerdrBlue,
                    )

                    "done" -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = HerdrEmerald,
                        modifier = Modifier.size(16.dp),
                    )

                    "denied" -> Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = HerdrAmber,
                        modifier = Modifier.size(16.dp),
                    )

                    "error" -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = HerdrRed,
                        modifier = Modifier.size(16.dp),
                    )

                    else -> Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (toolCall.inputSummary.isNotBlank()) {
                        Text(
                            text = toolCall.inputSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                    Text(
                        text = formatDuration(toolCall.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!toolCall.inputJson.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.tool_input_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = toolCall.inputJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.5.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    if (!toolCall.output.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.tool_output_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = toolCall.output,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.5.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedPermissionCard(
    deniedActions: List<DeniedAction>,
    onRetryFullAccess: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = HerdrAmber.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, HerdrAmber.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = HerdrAmber,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.permission_required_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = HerdrAmber,
                )
            }
            val names = deniedActions.joinToString(", ") { it.displayName }
            Text(
                text = stringResource(R.string.permission_required_body, names),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(
                onClick = onRetryFullAccess,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HerdrAmber,
                    contentColor = Color.Black,
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("allow_full_access_button"),
            ) {
                Text(
                    text = stringResource(R.string.action_allow_full_access),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    isWorking: Boolean,
    isConnected: Boolean,
    statusDetail: String?,
    modelLabel: String? = null,
    hasCustomModel: Boolean = false,
    onOpenModelPicker: (() -> Unit)? = null,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onOpenModelPicker != null && !modelLabel.isNullOrBlank() || !statusDetail.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onOpenModelPicker != null && !modelLabel.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (hasCustomModel) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(
                                1.dp,
                                if (hasCustomModel) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = onOpenModelPicker)
                                .testTag("model_picker_chip"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (hasCustomModel) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (hasCustomModel) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (hasCustomModel) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 200.dp),
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = stringResource(R.string.action_change_model),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    if (!statusDetail.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.8.dp,
                                color = HerdrBlue,
                            )
                            Text(
                                text = statusDetail,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = {
                            Text(
                                text = if (isConnected) {
                                    placeholder
                                } else {
                                    stringResource(R.string.composer_reconnecting)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("composer_input"),
                    )

                    Spacer(Modifier.width(4.dp))

                    if (isWorking) {
                        Surface(
                            shape = CircleShape,
                            color = HerdrRed,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onInterrupt)
                                .testTag("stop_agent_button"),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.action_stop_agent),
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    } else {
                        val canSend = draft.isNotBlank() && isConnected
                        Surface(
                            shape = CircleShape,
                            color = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(enabled = canSend, onClick = onSend)
                                .testTag("send_button"),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(R.string.action_send),
                                    tint = if (canSend) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    return if (ms < 1000L) {
        "${ms}ms"
    } else {
        String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}

private fun formatUsage(usage: UsageStats?): String? {
    if (usage == null) return null
    val parts = mutableListOf<String>()
    if (usage.inputTokens != null || usage.outputTokens != null) {
        val inTok = usage.inputTokens ?: 0
        val outTok = usage.outputTokens ?: 0
        parts.add("${inTok} in · ${outTok} out")
    }
    if (usage.costUsd != null && usage.costUsd > 0.0) {
        parts.add(String.format(Locale.US, "$%.4f", usage.costUsd))
    }
    if (usage.durationMs != null && usage.durationMs > 0L) {
        parts.add(formatDuration(usage.durationMs))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
