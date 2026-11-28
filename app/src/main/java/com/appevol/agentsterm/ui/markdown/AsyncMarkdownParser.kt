package com.appevol.agentsterm.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.appevol.agentsterm.data.AssistantDisplayBlock
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.MessageSegment
import com.appevol.agentsterm.data.ToolCallItem
import com.appevol.agentsterm.data.buildAssistantDisplayBlocks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Maximum estimated parse work (in characters) handled synchronously on the UI thread for exact
 * first-frame height and zero frame lag. Larger static documents and large growing streaming
 * tails are dispatched off the UI thread with latest-wins coalescing and monotonic revisions.
 */
internal const val DEFAULT_SYNC_PARSE_THRESHOLD_CHARS = 4_096

/**
 * Dispatcher used for off-UI-thread Markdown parsing. Defaults to [Dispatchers.Default]; tests
 * can override it to drive background parses deterministically.
 */
internal val LocalMarkdownParseDispatcher = staticCompositionLocalOf<CoroutineDispatcher> {
    Dispatchers.Default
}

/**
 * Threshold (in estimated parse chars) below which Markdown parses synchronously; tests can set
 * this to `0` to force all non-empty parses onto [LocalMarkdownParseDispatcher].
 */
internal val LocalMarkdownSyncParseThreshold = staticCompositionLocalOf {
    DEFAULT_SYNC_PARSE_THRESHOLD_CHARS
}

internal data class MarkdownParseRequest(
    val revision: Long,
    val markdown: String,
    val isStreaming: Boolean,
)

/**
 * Coordinates synchronous small/O(1) parses with off-UI-thread parsing on [parseDispatcher] for
 * large static documents and large growing streaming blocks.
 *
 * Ownership of the underlying [StreamingMarkdownState] is strictly single-owner without blocking
 * locks on the UI thread:
 * - While [workerActive] is `false`, only the UI thread reads or mutates [state].
 * - While [workerActive] is `true`, only the single background drain coroutine mutates [state],
 *   and incoming updates coalesce into [pendingRequest] (latest-wins) with monotonic revisions.
 *
 * Composition calls [requestBlocks] (side-effect free); callers invoke [launchIfNeeded] from a
 * [SideEffect] so coroutine launches never happen during an uncommitted composition.
 */
internal class AsyncMarkdownSession(
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val syncThresholdChars: Int = DEFAULT_SYNC_PARSE_THRESHOLD_CHARS,
    private val onAsyncCommit: (List<MdBlock>) -> Unit = {},
) {
    private val state = StreamingMarkdownState()
    private val latestRevision = AtomicLong(0L)
    private val pendingRequest = AtomicReference<MarkdownParseRequest?>(null)

    private var workerActive: Boolean = false
    private var needsWorkerLaunch: Boolean = false
    private var workerJob: Job? = null

    private var committedRevision: Long = 0L
    private var committedMarkdown: String = ""
    private var committedStreaming: Boolean = false
    private var committedBlocks: List<MdBlock> = emptyList()

    /** Number of background parse dispatches triggered by this session; exposed for tests. */
    internal var asyncDispatchCount: Int = 0
        private set

    val blocks: List<MdBlock>
        get() = committedBlocks

    /**
     * Returns blocks for `(markdown, isStreaming)`.
     *
     * Small initial documents, small streaming tails, and O(1) open-fence appends run
     * synchronously when no worker is active. Otherwise queues a latest-wins async request and
     * returns the most recently committed blocks until the background parse completes.
     */
    fun requestBlocks(markdown: String, isStreaming: Boolean): List<MdBlock> {
        if (markdown == committedMarkdown && isStreaming == committedStreaming) {
            return committedBlocks
        }
        if (!workerActive &&
            (markdown.isEmpty() || state.estimatedWorkChars(markdown, isStreaming) <= syncThresholdChars)
        ) {
            val rev = latestRevision.incrementAndGet()
            val result = state.update(markdown, isStreaming)
            committedRevision = rev
            committedMarkdown = markdown
            committedStreaming = isStreaming
            committedBlocks = result
            return result
        }
        val request = MarkdownParseRequest(
            revision = latestRevision.incrementAndGet(),
            markdown = markdown,
            isStreaming = isStreaming,
        )
        pendingRequest.set(request)
        if (!workerActive) {
            needsWorkerLaunch = true
        }
        return committedBlocks
    }

    /** Launches the single drain worker if [requestBlocks] queued async work. Call from [SideEffect]. */
    fun launchIfNeeded() {
        if (!needsWorkerLaunch || workerActive) return
        needsWorkerLaunch = false
        workerActive = true
        asyncDispatchCount++
        workerJob = scope.launch {
            try {
                drainPendingRequests()
            } finally {
                workerActive = false
                if (pendingRequest.get() != null) {
                    needsWorkerLaunch = true
                }
            }
        }
    }

    private suspend fun drainPendingRequests() {
        while (true) {
            val request = pendingRequest.getAndSet(null) ?: break
            if (request.revision < latestRevision.get()) continue
            val parsed = withContext(parseDispatcher) {
                if (request.revision < latestRevision.get()) {
                    null
                } else {
                    state.update(request.markdown, request.isStreaming)
                }
            } ?: continue
            if (request.revision >= committedRevision) {
                committedRevision = request.revision
                committedMarkdown = request.markdown
                committedStreaming = request.isStreaming
                committedBlocks = parsed
                onAsyncCommit(parsed)
            }
        }
    }

    fun cancel() {
        pendingRequest.set(null)
        needsWorkerLaunch = false
        workerJob?.cancel()
        workerJob = null
    }
}

/**
 * Parses [markdown] using [AsyncMarkdownSession] so small renders complete synchronously on frame
 * 1 while large documents and large streaming blocks parse on [LocalMarkdownParseDispatcher].
 */
@Composable
internal fun rememberParsedMarkdownBlocks(
    markdown: String,
    isStreaming: Boolean,
): List<MdBlock> {
    val scope = rememberCoroutineScope()
    val dispatcher = LocalMarkdownParseDispatcher.current
    val syncThreshold = LocalMarkdownSyncParseThreshold.current
    var asyncCommitVersion by remember { mutableLongStateOf(0L) }
    val session = remember(scope, dispatcher, syncThreshold) {
        AsyncMarkdownSession(
            scope = scope,
            parseDispatcher = dispatcher,
            syncThresholdChars = syncThreshold,
            onAsyncCommit = { asyncCommitVersion++ },
        )
    }
    DisposableEffect(session) {
        onDispose { session.cancel() }
    }
    // Reading asyncCommitVersion subscribes this composition to background parse completions.
    val blocks = remember(session, markdown, isStreaming, asyncCommitVersion) {
        session.requestBlocks(markdown, isStreaming)
    }
    SideEffect {
        session.launchIfNeeded()
    }
    return blocks
}

/**
 * Per-tab cache of parsed Markdown blocks and assistant display blocks used by `ChatScreen` to
 * virtualize top-level [MdBlock]s directly in the chat `LazyColumn`.
 */
internal class ChatMarkdownCache(
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher,
    private val syncThresholdChars: Int = DEFAULT_SYNC_PARSE_THRESHOLD_CHARS,
) {
    private val sessions = HashMap<String, AsyncMarkdownSession>()
    private val displayBlockCache = HashMap<String, CachedDisplayBlocks>()
    private val pendingLaunches = LinkedHashSet<AsyncMarkdownSession>()

    /** Monotonic Compose state bumped whenever an off-UI parse finishes and commits. */
    var commitVersion by mutableLongStateOf(0L)
        private set

    fun displayBlocksFor(message: ChatMessage): List<AssistantDisplayBlock> {
        val cached = displayBlockCache[message.id]
        if (cached != null && cached.matches(message)) {
            return cached.blocks
        }
        val computed = buildAssistantDisplayBlocks(message)
        displayBlockCache[message.id] = CachedDisplayBlocks(
            text = message.text,
            segments = message.segments,
            toolCalls = message.toolCalls,
            blocks = computed,
        )
        return computed
    }

    fun blocksFor(key: String, markdown: String, isStreaming: Boolean): List<MdBlock> {
        val session = sessions.getOrPut(key) {
            AsyncMarkdownSession(
                scope = scope,
                parseDispatcher = parseDispatcher,
                syncThresholdChars = syncThresholdChars,
                onAsyncCommit = { commitVersion++ },
            )
        }
        val blocks = session.requestBlocks(markdown, isStreaming)
        pendingLaunches.add(session)
        return blocks
    }

    /** Launches queued background parses and evicts sessions for removed messages. Call from [SideEffect]. */
    fun flushPending(activeMessageIds: Set<String>) {
        if (pendingLaunches.isNotEmpty()) {
            for (session in pendingLaunches) {
                session.launchIfNeeded()
            }
            pendingLaunches.clear()
        }
        if (displayBlockCache.size > activeMessageIds.size) {
            displayBlockCache.keys.retainAll(activeMessageIds)
        }
        if (sessions.isNotEmpty()) {
            val iter = sessions.iterator()
            while (iter.hasNext()) {
                val (key, session) = iter.next()
                val msgId = key.substringBefore(':')
                if (msgId !in activeMessageIds) {
                    session.cancel()
                    iter.remove()
                }
            }
        }
    }

    fun cancelAll() {
        pendingLaunches.clear()
        for (session in sessions.values) {
            session.cancel()
        }
    }

    private data class CachedDisplayBlocks(
        val text: String,
        val segments: List<MessageSegment>,
        val toolCalls: List<ToolCallItem>,
        val blocks: List<AssistantDisplayBlock>,
    ) {
        fun matches(message: ChatMessage): Boolean = text == message.text && segments == message.segments && toolCalls == message.toolCalls
    }
}
