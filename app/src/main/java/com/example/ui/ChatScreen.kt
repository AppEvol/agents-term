package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.AssistantDisplayBlock
import com.example.data.ChatMessage
import com.example.data.DeniedAction
import com.example.data.QueuedMessage
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.data.UsageStats
import com.example.data.buildAssistantDisplayBlocks
import com.example.data.formatToolCallSummary
import com.example.data.formatToolRunSummary
import com.example.ui.markdown.MarkdownText
import com.example.ui.theme.AppTheme
import com.example.ui.theme.LunaInputBorder
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedButton
import com.example.ui.theme.isLunaControls
import com.example.ui.theme.themedCorner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private const val CHAT_BOTTOM_SENTINEL_KEY = "__chat_bottom_sentinel__"

private data class ChatScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val canScrollForward: Boolean,
    val isScrollInProgress: Boolean,
)

private data class ChatAutoScrollSignal(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastTextLength: Int,
    val lastThinkingLength: Int,
    val lastToolCount: Int,
    val lastToolStatus: String?,
    val lastSegmentCount: Int,
    val lastSegmentTextLength: Int,
    val lastStatus: String?,
    val hasError: Boolean,
    val deniedActionCount: Int,
    val sendGeneration: Int,
)

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
    onForceInterrupt: (String?) -> Unit = { onInterrupt() },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onRetryFullAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberSaveable(tab.id, saver = LazyListState.Saver) {
        LazyListState()
    }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(tab.id) { mutableStateOf("") }
    val isWorking = tab.status == "working"
    val latestMessages by rememberUpdatedState(messages)

    var autoScrollPinned by remember(tab.id) { mutableStateOf(true) }
    var isProgrammaticScroll by remember(tab.id) { mutableStateOf(false) }
    var sendGeneration by remember(tab.id) { mutableIntStateOf(0) }

    val scrollAwayConnection = remember(tab.id) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Dragging downward (available.y > 0) scrolls up toward earlier messages.
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    autoScrollPinned = false
                }
                return Offset.Zero
            }
        }
    }

    // Track manual scrolls (including non-touch scrolls) to pause/resume auto-scroll.
    LaunchedEffect(tab.id, listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            ChatScrollSnapshot(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                canScrollForward = listState.canScrollForward,
                isScrollInProgress = listState.isScrollInProgress,
            )
        }.collect { snap ->
            val movedBackward = snap.firstVisibleItemIndex < prevIndex ||
                (snap.firstVisibleItemIndex == prevIndex && snap.firstVisibleItemScrollOffset < prevOffset)
            if (!isProgrammaticScroll) {
                if (!snap.canScrollForward && !snap.isScrollInProgress) {
                    autoScrollPinned = true
                } else if (snap.canScrollForward && movedBackward) {
                    autoScrollPinned = false
                }
            }
            prevIndex = snap.firstVisibleItemIndex
            prevOffset = snap.firstVisibleItemScrollOffset
        }
    }

    val showScrollToBottom by remember(tab.id, listState) {
        derivedStateOf {
            val isStreaming = latestMessages.lastOrNull()?.status == "streaming"
            latestMessages.isNotEmpty() &&
                listState.canScrollForward &&
                !isProgrammaticScroll &&
                (!autoScrollPinned || !isStreaming)
        }
    }

    // Auto-scroll to the bottom sentinel when pinned and new messages or streaming deltas arrive.
    val lastMessage = messages.lastOrNull()
    val autoScrollSignal = ChatAutoScrollSignal(
        messageCount = messages.size,
        lastMessageId = lastMessage?.id,
        lastTextLength = lastMessage?.text?.length ?: 0,
        lastThinkingLength = lastMessage?.thinking?.length ?: 0,
        lastToolCount = lastMessage?.toolCalls?.size ?: 0,
        lastToolStatus = lastMessage?.toolCalls?.lastOrNull()?.status,
        lastSegmentCount = lastMessage?.segments?.size ?: 0,
        lastSegmentTextLength = lastMessage?.segments?.lastOrNull()?.text?.length ?: 0,
        lastStatus = lastMessage?.status,
        hasError = !lastMessage?.error.isNullOrBlank(),
        deniedActionCount = lastMessage?.deniedActions?.size ?: 0,
        sendGeneration = sendGeneration,
    )
    LaunchedEffect(tab.id, autoScrollSignal) {
        if (messages.isEmpty() || !autoScrollPinned) return@LaunchedEffect
        if (listState.isScrollInProgress) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
        if (latestMessages.isNotEmpty() && autoScrollPinned) {
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(latestMessages.size)
            } finally {
                isProgrammaticScroll = false
            }
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
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollAwayConnection)
                    .testTag("chat_message_list"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 14.dp,
                    end = 16.dp,
                    bottom = if (messages.isEmpty()) 14.dp else 0.dp,
                ),
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
                                .widthIn(max = 760.dp),
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
                if (messages.isNotEmpty()) {
                    item(key = CHAT_BOTTOM_SENTINEL_KEY) {
                        Spacer(Modifier.height(1.dp))
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
                            if (latestMessages.isNotEmpty()) {
                                autoScrollPinned = true
                                isProgrammaticScroll = true
                                try {
                                    listState.animateScrollToItem(latestMessages.size)
                                    if (autoScrollPinned && listState.canScrollForward) {
                                        listState.scrollToItem(latestMessages.size)
                                    }
                                } finally {
                                    isProgrammaticScroll = false
                                }
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
            placeholder = when {
                isWorking && tab.agent == "shell" ->
                    stringResource(R.string.composer_queue_shell_placeholder)

                isWorking ->
                    stringResource(R.string.composer_queue_agent_placeholder)

                tab.agent == "shell" ->
                    stringResource(R.string.composer_shell_placeholder, tab.folderName)

                else ->
                    stringResource(R.string.composer_agent_placeholder, tab.agentLabel)
            },
            isWorking = isWorking,
            isConnected = isConnected,
            statusDetail = if (isWorking) tab.statusDetail else null,
            queuedMessages = tab.queuedMessages,
            modelLabel = modelLabel,
            hasCustomModel = hasCustomModel,
            onOpenModelPicker = onOpenModelPicker,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    autoScrollPinned = true
                    sendGeneration += 1
                    onSendMessage(text)
                }
            },
            onForceInterrupt = { draftText ->
                if (!draftText.isNullOrBlank()) {
                    draft = ""
                }
                onForceInterrupt(draftText)
            },
            onRemoveQueuedMessage = onRemoveQueuedMessage,
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
            shape = themedCorner(20.dp, 20.dp, 6.dp, 20.dp),
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
                Surface(
                    shape = themedCorner(10.dp),
                    color = AppTheme.colors.info.copy(alpha = 0.14f),
                    modifier = Modifier.testTag("streaming_working_badge"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        WorkingPulseDots(
                            color = AppTheme.colors.info,
                            dotSize = 4.dp,
                        )
                        Text(
                            text = stringResource(R.string.status_working_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.info,
                        )
                    }
                }
            }
        }

        // Collapsible Thinking block
        if (!message.thinking.isNullOrBlank()) {
            ThinkingAccordionCard(
                thinking = message.thinking,
                isStreaming = message.status == "streaming",
            )
        }

        // Ordered text and grouped tool-call runs
        val displayBlocks = remember(message.segments, message.toolCalls, message.text) {
            buildAssistantDisplayBlocks(message)
        }
        if (displayBlocks.isNotEmpty()) {
            for (block in displayBlocks) {
                when (block) {
                    is AssistantDisplayBlock.TextBlock -> {
                        MarkdownText(
                            markdown = block.text,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is AssistantDisplayBlock.ToolRunBlock -> {
                        // One composable for a run of any length, keyed by its first call, so the
                        // card grows in place when a second call joins instead of being swapped.
                        key(block.toolCalls.first().id) {
                            ToolCallRunCard(
                                toolCalls = block.toolCalls,
                                isLive = message.status == "streaming" && block === displayBlocks.last(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        } else if (
            message.status == "streaming" &&
            message.thinking.isNullOrBlank()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .testTag("streaming_activity_indicator"),
            ) {
                WorkingPulseDots(
                    color = AppTheme.colors.info,
                    dotSize = 5.dp,
                )
                Text(
                    text = tab.statusDetail?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.status_working_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (
            message.status == "complete" &&
            message.error.isNullOrBlank() &&
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
                shape = themedCorner(12.dp),
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
                color = AppTheme.colors.warning,
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
                            tint = if (copied) AppTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
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
        shape = themedCorner(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, AppTheme.colors.accent.copy(alpha = 0.35f)),
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
                    tint = AppTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isStreaming) {
                        stringResource(R.string.thinking_streaming_label)
                    } else {
                        stringResource(R.string.thinking_label)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.accent,
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

/**
 * A run of consecutive tool calls. A single call is just its row; two or more get a summary header
 * that expands to the full list.
 *
 * While collapsed, a grouped run keeps a single stable preview row: the latest call in flight, or
 * the most recent call while the turn is still streaming (`isLive`), so parallel bursts and
 * inter-call gaps never bounce the card height or flash its active chrome.
 */
@Composable
private fun ToolCallRunCard(
    toolCalls: List<ToolCallItem>,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isGroup = toolCalls.size > 1
    val runningCalls = remember(toolCalls) { toolCalls.filter { it.status == "running" } }
    val hasRunning = runningCalls.isNotEmpty()
    // Keep active styling steady across brief inter-call gaps while this live run is still open.
    val isActive = hasRunning || isLive
    val hasError = remember(toolCalls) { toolCalls.any { it.status == "error" } }
    val hasDenied = remember(toolCalls) { toolCalls.any { it.status == "denied" } }
    val summaryText = remember(toolCalls) { formatToolRunSummary(toolCalls) }
    val totalDurationMs = remember(toolCalls) {
        toolCalls.mapNotNull { it.durationMs }.filter { it > 0L }.sum().takeIf { it > 0L }
    }
    val collapsedCalls = when {
        !isGroup -> toolCalls
        hasRunning -> runningCalls.takeLast(1)
        isLive -> toolCalls.takeLast(1)
        else -> emptyList()
    }

    val containerColor = when {
        isActive -> MaterialTheme.colorScheme.surfaceContainer
        hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = when {
        isActive -> AppTheme.colors.info.copy(alpha = 0.45f)
        hasError -> AppTheme.colors.danger.copy(alpha = 0.4f)
        hasDenied -> AppTheme.colors.warning.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Surface(
        shape = themedCorner(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = if (isGroup) modifier.testTag("tool_run_group") else modifier,
    ) {
        // Avoid Modifier.animateContentSize() here: SizeAnimationModifierNode returns the prior
        // frame's height while launching its animation coroutine (even with animations disabled),
        // which clips new rows for a frame and fights LazyColumn auto-scroll and inner
        // AnimatedVisibility transitions.
        Column {
            if (isGroup) {
                ToolCallRunHeader(
                    summaryText = summaryText,
                    totalDurationMs = totalDurationMs,
                    isActive = isActive,
                    hasError = hasError,
                    hasDenied = hasDenied,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
            }
            val visibleCalls = if (expanded) toolCalls else collapsedCalls
            for (tc in visibleCalls) {
                // Keyed by call so a row keeps its own expanded state as the list around it changes.
                key(tc.id) {
                    if (isGroup) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                    val isCollapsedPreview = isGroup && !expanded
                    ToolCallRow(
                        toolCall = tc,
                        emphasized = tc.status == "running" || tc.status == "error" || tc.status == "denied",
                        reserveSummaryLine = isCollapsedPreview,
                        modifier = if (isCollapsedPreview) Modifier.testTag("tool_run_active_call") else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallRunHeader(
    summaryText: String,
    totalDurationMs: Long?,
    isActive: Boolean,
    hasError: Boolean,
    hasDenied: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("tool_run_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Same box for the spinner and the icons, so the label does not shift when they swap.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(15.dp)) {
            when {
                isActive -> SandclockIndicator(
                    color = AppTheme.colors.info,
                    modifier = Modifier.size(15.dp),
                )

                hasError -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = AppTheme.colors.danger,
                )

                hasDenied -> Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = AppTheme.colors.warning,
                )

                else -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AppTheme.colors.success.copy(alpha = 0.78f),
                )
            }
        }

        Text(
            text = summaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (isActive) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!isActive && totalDurationMs != null) {
            Text(
                text = formatDuration(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }

        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ToolCallRow(
    toolCall: ToolCallItem,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    reserveSummaryLine: Boolean = false,
) {
    var expanded by remember(toolCall.id) { mutableStateOf(false) }
    val displaySummary = remember(toolCall.inputSummary, toolCall.inputJson) {
        formatToolCallSummary(toolCall)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("tool_call_card_${toolCall.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(15.dp)) {
                when (toolCall.status) {
                    "running" -> Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        tint = AppTheme.colors.info,
                    )

                    "done" -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = AppTheme.colors.success.copy(alpha = if (emphasized) 1f else 0.72f),
                    )

                    "denied" -> Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = AppTheme.colors.warning,
                    )

                    "error" -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = AppTheme.colors.danger,
                    )

                    else -> Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFontFamily,
                    fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (displaySummary.isNotBlank() || reserveSummaryLine) {
                    Text(
                        text = displaySummary.ifBlank { " " },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (emphasized) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (displaySummary.isBlank()) Modifier.clearAndSetSemantics {} else Modifier,
                    )
                }
            }

            if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                Text(
                    text = formatDuration(toolCall.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
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

@Composable
private fun BlockedPermissionCard(
    deniedActions: List<DeniedAction>,
    onRetryFullAccess: () -> Unit,
) {
    Surface(
        shape = themedCorner(14.dp),
        color = AppTheme.colors.warning.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, AppTheme.colors.warning.copy(alpha = 0.55f)),
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
                    tint = AppTheme.colors.warning,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.permission_required_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.warning,
                )
            }
            val names = deniedActions.joinToString(", ") { it.displayName }
            Text(
                text = stringResource(R.string.permission_required_body, names),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ThemedButton(
                onClick = onRetryFullAccess,
                shape = themedCorner(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.warning,
                    contentColor = AppTheme.colors.onWarning,
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
private fun QueuedMessagesTray(
    queuedMessages: List<QueuedMessage>,
    isWorking: Boolean,
    onRemove: (String) -> Unit,
) {
    Surface(
        shape = themedCorner(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, AppTheme.colors.info.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queued_messages_tray"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = AppTheme.colors.info,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.queue_header_count, queuedMessages.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.info,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (isWorking) {
                        stringResource(R.string.queue_hint_hold_to_run)
                    } else {
                        stringResource(R.string.queue_hint_tap_to_run)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 128.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                queuedMessages.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("queued_message_item_${item.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onRemove(item.id) }
                                .testTag("remove_queued_${item.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_remove_queued),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
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
    queuedMessages: List<QueuedMessage> = emptyList(),
    modelLabel: String? = null,
    hasCustomModel: Boolean = false,
    onOpenModelPicker: (() -> Unit)? = null,
    onSend: () -> Unit,
    onForceInterrupt: (String?) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    var isHolding by remember { mutableStateOf(false) }
    val holdProgress = remember { Animatable(0f) }
    var holdHintCounter by remember { mutableStateOf(0) }
    var showHoldHint by remember { mutableStateOf(false) }

    LaunchedEffect(holdHintCounter) {
        if (holdHintCounter > 0) {
            showHoldHint = true
            delay(2_400L)
            showHoldHint = false
        }
    }

    LaunchedEffect(isWorking) {
        if (!isWorking) {
            isHolding = false
            showHoldHint = false
            holdProgress.snapTo(0f)
        }
    }

    val currentDraft by rememberUpdatedState(draft)
    val currentHasQueued by rememberUpdatedState(queuedMessages.isNotEmpty())
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnForceInterrupt by rememberUpdatedState(onForceInterrupt)
    val currentOnShowHoldHint by rememberUpdatedState({ holdHintCounter++ })

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (queuedMessages.isNotEmpty()) {
                    QueuedMessagesTray(
                        queuedMessages = queuedMessages,
                        isWorking = isWorking,
                        onRemove = onRemoveQueuedMessage,
                    )
                }

                if (
                    (onOpenModelPicker != null && !modelLabel.isNullOrBlank()) ||
                    !statusDetail.isNullOrBlank() ||
                    isHolding ||
                    showHoldHint
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onOpenModelPicker != null && !modelLabel.isNullOrBlank()) {
                            Surface(
                                shape = themedCorner(16.dp),
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
                                    .clip(themedCorner(16.dp))
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

                        if (isHolding) {
                            val hasPendingToRun = draft.isNotBlank() || queuedMessages.isNotEmpty()
                            Text(
                                text = if (hasPendingToRun) {
                                    stringResource(R.string.holding_interrupt_run_status)
                                } else {
                                    stringResource(R.string.holding_interrupt_status)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.danger,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (showHoldHint) {
                            Text(
                                text = if (queuedMessages.isNotEmpty()) {
                                    stringResource(R.string.hold_hint_interrupt_run)
                                } else {
                                    stringResource(R.string.hold_hint_stop)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.warning,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hold_to_interrupt_hint"),
                            )
                        } else if (!statusDetail.isNullOrBlank()) {
                            Text(
                                text = statusDetail,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("composer_status_detail"),
                            )
                        }
                    }
                }

                val composerBorderColor = if (isWorking && isHolding) {
                    AppTheme.colors.danger.copy(alpha = 0.85f)
                } else if (isLunaControls) {
                    LunaInputBorder
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }

                Surface(
                    shape = themedCorner(26.dp),
                    color = if (isLunaControls) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(if (isWorking && isHolding) 1.25.dp else 1.dp, composerBorderColor),
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

                        val hasDraft = draft.isNotBlank()
                        val hasQueued = queuedMessages.isNotEmpty()
                        val canSendIdle = !isWorking && (hasDraft || hasQueued) && isConnected
                        val showStopIcon = isWorking && !hasDraft && !hasQueued
                        val buttonBg = when {
                            !isConnected -> MaterialTheme.colorScheme.surfaceContainerHighest
                            isWorking && isHolding -> AppTheme.colors.danger.copy(alpha = 0.22f + 0.78f * holdProgress.value)
                            isWorking && hasDraft -> MaterialTheme.colorScheme.primary
                            isWorking && hasQueued -> MaterialTheme.colorScheme.primaryContainer
                            isWorking -> AppTheme.colors.danger.copy(alpha = 0.14f)
                            canSendIdle -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        val buttonBorder = when {
                            !isConnected -> null

                            isWorking && !isHolding && !hasDraft && hasQueued ->
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))

                            isWorking && !isHolding && !hasDraft && !hasQueued ->
                                BorderStroke(1.dp, AppTheme.colors.danger.copy(alpha = 0.45f))

                            else -> null
                        }
                        val iconTint = when {
                            !isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                            isWorking && isHolding -> if (holdProgress.value > 0.35f) Color.White else AppTheme.colors.danger
                            isWorking && hasDraft -> MaterialTheme.colorScheme.onPrimary
                            isWorking && hasQueued -> MaterialTheme.colorScheme.onPrimaryContainer
                            isWorking -> AppTheme.colors.danger
                            canSendIdle -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val buttonDescription = when {
                            isWorking && hasDraft -> stringResource(R.string.action_queue_message)
                            isWorking && hasQueued -> stringResource(R.string.action_hold_interrupt_queue)
                            isWorking -> stringResource(R.string.action_stop_agent)
                            else -> stringResource(R.string.action_send)
                        }
                        val holdActionLabel = stringResource(R.string.action_hold_interrupt_queue)
                        // XP has no round buttons.
                        val sendButtonShape = if (isLunaControls) themedCorner(4.dp) else CircleShape

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(48.dp)
                                .clip(sendButtonShape)
                                .semantics {
                                    contentDescription = buttonDescription
                                    if (isConnected) {
                                        onClick(label = buttonDescription) {
                                            if (!isWorking) {
                                                if (currentDraft.isNotBlank()) {
                                                    currentOnSend()
                                                    true
                                                } else if (currentHasQueued) {
                                                    currentOnForceInterrupt(null)
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else if (currentDraft.isNotBlank()) {
                                                currentOnSend()
                                                true
                                            } else {
                                                currentOnShowHoldHint()
                                                true
                                            }
                                        }
                                        if (isWorking) {
                                            onLongClick(label = holdActionLabel) {
                                                val textToInclude = currentDraft.trim().takeIf { it.isNotEmpty() }
                                                currentOnForceInterrupt(textToInclude)
                                                true
                                            }
                                        }
                                    }
                                }
                                .pointerInput(isWorking, isConnected) {
                                    if (!isConnected) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (!isWorking) {
                                            val up = waitForUpOrCancellation()
                                            if (up != null) {
                                                if (currentDraft.isNotBlank()) {
                                                    currentOnSend()
                                                } else if (currentHasQueued) {
                                                    currentOnForceInterrupt(null)
                                                }
                                            }
                                            return@awaitEachGesture
                                        }

                                        val holdMs = viewConfiguration.longPressTimeoutMillis.coerceAtLeast(400L)
                                        isHolding = true
                                        val progressJob = scope.launch {
                                            holdProgress.snapTo(0f)
                                            holdProgress.animateTo(
                                                targetValue = 1f,
                                                animationSpec = tween(
                                                    durationMillis = holdMs.toInt(),
                                                    easing = LinearEasing,
                                                ),
                                            )
                                        }
                                        val slop = viewConfiguration.touchSlop
                                        val releasedBeforeTimeout = withTimeoutOrNull(holdMs) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                if (change == null || !change.pressed) {
                                                    return@withTimeoutOrNull true
                                                }
                                                val pos = change.position
                                                if (pos.x < -slop || pos.y < -slop ||
                                                    pos.x > size.width + slop || pos.y > size.height + slop
                                                ) {
                                                    return@withTimeoutOrNull false
                                                }
                                            }
                                        }
                                        progressJob.cancel()
                                        isHolding = false
                                        scope.launch { holdProgress.snapTo(0f) }

                                        when (releasedBeforeTimeout) {
                                            null -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val textToInclude = currentDraft.trim().takeIf { it.isNotEmpty() }
                                                currentOnForceInterrupt(textToInclude)
                                                waitForUpOrCancellation()
                                            }

                                            true -> {
                                                if (currentDraft.isNotBlank()) {
                                                    currentOnSend()
                                                } else {
                                                    currentOnShowHoldHint()
                                                }
                                            }

                                            false -> Unit
                                        }
                                    }
                                }
                                .testTag("send_button"),
                        ) {
                            Surface(
                                shape = sendButtonShape,
                                color = buttonBg,
                                border = buttonBorder,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (showStopIcon) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            if (isWorking && holdProgress.value > 0f) {
                                CircularProgressIndicator(
                                    progress = { holdProgress.value },
                                    color = AppTheme.colors.danger,
                                    trackColor = AppTheme.colors.danger.copy(alpha = 0.22f),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String = if (ms < 1000L) {
    "${ms}ms"
} else {
    String.format(Locale.US, "%.1fs", ms / 1000.0)
}

private fun formatUsage(usage: UsageStats?): String? {
    if (usage == null) return null
    val parts = mutableListOf<String>()
    if (usage.inputTokens != null || usage.outputTokens != null) {
        val inTok = usage.inputTokens ?: 0
        val outTok = usage.outputTokens ?: 0
        parts.add("$inTok in · $outTok out")
    }
    if (usage.costUsd != null && usage.costUsd > 0.0) {
        parts.add(String.format(Locale.US, "$%.4f", usage.costUsd))
    }
    if (usage.durationMs != null && usage.durationMs > 0L) {
        parts.add(formatDuration(usage.durationMs))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
