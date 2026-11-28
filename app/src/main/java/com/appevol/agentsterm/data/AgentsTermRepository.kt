package com.appevol.agentsterm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicBoolean

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = runCatching(block)
    .onFailure { err -> if (err is CancellationException) throw err }

const val MAX_OPEN_TABS = 10
const val MAX_CLOSED_TABS = 50
const val CLOSED_TAB_RETENTION_MS = 24L * 60L * 60L * 1000L
private const val DEFAULT_RECONNECT_BASE_DELAY_MS = 1_500L
private const val DEFAULT_RECONNECT_MAX_DELAY_MS = 15_000L
private const val DEFAULT_DELTA_FLUSH_INTERVAL_MS = 60L
private const val FOREGROUND_CONNECT_GRACE_MS = 2_000L

data class WorkspaceState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionError: String? = null,
    val serverInfo: ServerInfo? = null,
    val tabs: List<TabRecord> = emptyList(),
    val closedTabs: List<TabRecord> = emptyList(),
    val selectedTabId: String? = null,
    val lastCwd: String? = null,
    val messagesByTab: Map<String, List<ChatMessage>> = emptyMap(),
    val loadingTabIds: Set<String> = emptySet(),
    val reopenedTabIds: Set<String> = emptySet(),
    val bannerMessage: String? = null,
    val daemonUpdateStatus: DaemonUpdateStatus? = null,
    val precomputedFinishedTabIds: Set<String>? = null,
    val throughput: List<ModelThroughput> = emptyList(),
) {
    val canCreateTab: Boolean
        get() = tabs.size < MAX_OPEN_TABS

    val activeTab: TabRecord?
        get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()

    val activeMessages: List<ChatMessage>
        get() = activeTab?.let { messagesByTab[it.id] }.orEmpty()

    /**
     * The drawer button's badge: tabs whose latest turn ended without the user opening the result.
     * The tab on screen never counts, since its result is already in front of the user. Each tab
     * counted here carries an unread dot in the drawer.
     */
    val attentionCount: Int
        get() = tabs.count { isTabUnread(it) }

    fun isTabUnread(tab: TabRecord): Boolean = tab.unread && tab.id != activeTab?.id

    /** Every tab is in exactly one of the three [TabActivity] states. */
    fun activityOf(tab: TabRecord): TabActivity = when {
        tab.status == "working" -> TabActivity.WORKING
        isTabFinished(tab) -> TabActivity.FINISHED
        else -> TabActivity.WAITING
    }

    fun isTabFinished(tab: TabRecord): Boolean = precomputedFinishedTabIds?.let { tab.id in it }
        ?: isTabTaskFinished(
            tab = tab,
            messages = messagesByTab[tab.id].orEmpty(),
            manuallyReopened = tab.id in reopenedTabIds,
        )

    val finishedTabIds: Set<String>
        get() = precomputedFinishedTabIds ?: tabs.filter { isTabFinished(it) }.map { it.id }.toSet()
}

/**
 * Pure event reducer for WebSocket server events — unit-testable without Android runtime.
 */
fun reduceWorkspaceEvent(
    state: WorkspaceState,
    event: WsServerEnvelope,
    preferredTabId: String? = null,
): WorkspaceState {
    return when (event.type) {
        "snapshot" -> {
            val incomingTabs = event.tabs.orEmpty()
            val validCurrent = state.selectedTabId?.takeIf { id -> incomingTabs.any { it.id == id } }
            val validPreferred = preferredTabId?.takeIf { id -> incomingTabs.any { it.id == id } }
            val validServerActive = event.activeTabId?.takeIf { id -> incomingTabs.any { it.id == id } }
            val nextSelected = validCurrent ?: validPreferred ?: validServerActive ?: incomingTabs.firstOrNull()?.id
            val nextServer = event.server ?: state.serverInfo
            val nextUpdateStatus = when {
                nextServer?.supportsSelfUpdate != true -> null
                state.daemonUpdateStatus?.isRestarting == true -> null
                else -> state.daemonUpdateStatus
            }
            state.copy(
                connectionState = ConnectionState.CONNECTED,
                connectionError = null,
                serverInfo = nextServer,
                throughput = event.throughput.orEmpty(),
                tabs = incomingTabs,
                closedTabs = event.closedTabs.orEmpty(),
                selectedTabId = nextSelected,
                daemonUpdateStatus = nextUpdateStatus,
            )
        }

        "model_throughput" -> state.copy(throughput = event.throughput.orEmpty())

        "tab_created" -> {
            val tab = event.tab ?: return state
            val updated = if (state.tabs.any { it.id == tab.id }) {
                state.tabs.map { if (it.id == tab.id) tab else it }
            } else {
                state.tabs + tab
            }
            val nextClosed = event.closedTabs ?: state.closedTabs.filterNot { it.id == tab.id }
            state.copy(
                tabs = updated,
                closedTabs = nextClosed,
                selectedTabId = state.selectedTabId ?: tab.id,
            )
        }

        "tab_updated" -> {
            val tab = event.tab ?: return state
            val updated = if (state.tabs.any { it.id == tab.id }) {
                state.tabs.map { if (it.id == tab.id) tab else it }
            } else {
                state.tabs + tab
            }
            val nextReopened = if (tab.status == "working" || tab.status == "done") {
                state.reopenedTabIds - tab.id
            } else {
                state.reopenedTabIds
            }
            state.copy(tabs = updated, reopenedTabIds = nextReopened)
        }

        "tab_deleted" -> {
            val id = event.tabId ?: return state
            val remaining = state.tabs.filterNot { it.id == id }
            val nextSelected = if (state.selectedTabId == id) remaining.lastOrNull()?.id else state.selectedTabId
            val nextClosed = event.closedTabs ?: event.closedTab?.let { archived ->
                (listOf(archived) + state.closedTabs.filterNot { it.id == id }).take(MAX_CLOSED_TABS)
            } ?: state.closedTabs
            state.copy(
                tabs = remaining,
                closedTabs = nextClosed,
                selectedTabId = nextSelected,
                messagesByTab = state.messagesByTab - id,
                reopenedTabIds = state.reopenedTabIds - id,
            )
        }

        "message_created" -> {
            val tabId = event.tabId ?: return state
            val msg = event.message ?: return state
            val existing = state.messagesByTab[tabId].orEmpty()
            val updated = if (existing.any { it.id == msg.id }) {
                existing.map { if (it.id == msg.id) msg else it }
            } else {
                existing + msg
            }
            state.copy(messagesByTab = state.messagesByTab + (tabId to updated))
        }

        "message_delta" -> {
            val tabId = event.tabId ?: return state
            val msgId = event.messageId ?: return state
            val existing = state.messagesByTab[tabId] ?: return state
            val updated = existing.map { msg ->
                if (msg.id == msgId) {
                    msg.copy(
                        text = if (event.textDelta != null) msg.text + event.textDelta else msg.text,
                        thinking = if (event.thinkingDelta != null) {
                            (msg.thinking.orEmpty()) + event.thinkingDelta
                        } else {
                            msg.thinking
                        },
                        segments = if (event.textDelta != null) {
                            appendTextDeltaToSegments(msg.segments, event.textDelta)
                        } else {
                            msg.segments
                        },
                    )
                } else {
                    msg
                }
            }
            state.copy(messagesByTab = state.messagesByTab + (tabId to updated))
        }

        "tool_update" -> {
            val tabId = event.tabId ?: return state
            val msgId = event.messageId ?: return state
            val tc = event.toolCall ?: return state
            val existing = state.messagesByTab[tabId] ?: return state
            val updated = existing.map { msg ->
                if (msg.id == msgId) {
                    val tools = if (msg.toolCalls.any { it.id == tc.id }) {
                        msg.toolCalls.map { if (it.id == tc.id) tc else it }
                    } else {
                        msg.toolCalls + tc
                    }
                    msg.copy(
                        toolCalls = tools,
                        segments = appendToolToSegments(msg.segments, tc.id),
                    )
                } else {
                    msg
                }
            }
            state.copy(messagesByTab = state.messagesByTab + (tabId to updated))
        }

        "message_updated" -> {
            val tabId = event.tabId ?: return state
            val msg = event.message ?: return state
            val existing = state.messagesByTab[tabId].orEmpty()
            val updated = if (existing.any { it.id == msg.id }) {
                existing.map { cur ->
                    if (cur.id == msg.id) reconcileUpdatedMessageSegments(cur, msg) else cur
                }
            } else {
                existing + msg
            }
            state.copy(messagesByTab = state.messagesByTab + (tabId to updated))
        }

        "daemon_update_status" -> {
            val update = event.update ?: return state
            state.copy(
                daemonUpdateStatus = if (update.state == "idle") null else update,
            )
        }

        else -> state
    }
}

data class ResolvedDirectoryListing(
    val listing: FsListResponse,
    val fellBackToHome: Boolean,
)

/**
 * Loads the preferred initial folder for a new agent tab, falling back to the VM user's home
 * directory when the preferred folder has been deleted or is no longer a valid directory.
 *
 * Works with both daemons that expose `exists: false` / HTTP errors and older daemons that return
 * HTTP 200 with an empty directory list for missing paths (verified via the parent directory).
 */
suspend fun resolveInitialDirectoryListing(
    preferredPath: String?,
    homeCwd: String?,
    listDirectories: suspend (String?) -> Result<FsListResponse>,
): Result<ResolvedDirectoryListing> {
    val normalizedHome = homeCwd?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeFsPath)
    val candidate = preferredPath?.trim()?.takeIf { it.isNotEmpty() } ?: normalizedHome
    val normalizedCandidate = candidate?.let(::normalizeFsPath)

    suspend fun loadWithRetry(path: String?): Result<FsListResponse> {
        var result = listDirectories(path)
        if (result.isFailure && result.exceptionOrNull()?.isAuthError != true) {
            result = listDirectories(path)
        }
        return result
    }

    suspend fun loadHomeFallback(): Result<ResolvedDirectoryListing> = loadWithRetry(normalizedHome).map { homeListing ->
        ResolvedDirectoryListing(listing = homeListing, fellBackToHome = true)
    }

    val initialResult = loadWithRetry(candidate)
    if (initialResult.isFailure) {
        if (initialResult.exceptionOrNull()?.isAuthError == true) {
            return initialResult.map { ResolvedDirectoryListing(it, fellBackToHome = false) }
        }
        if (normalizedCandidate != null && normalizedCandidate != normalizedHome) {
            return loadHomeFallback()
        }
        return initialResult.map { ResolvedDirectoryListing(it, fellBackToHome = false) }
    }

    val listing = initialResult.getOrThrow()
    val resolvedPath = normalizeFsPath(listing.path)
    if (normalizedHome != null && resolvedPath == normalizedHome) {
        return Result.success(ResolvedDirectoryListing(listing, fellBackToHome = false))
    }

    when (listing.exists) {
        false -> return loadHomeFallback()
        true -> return Result.success(ResolvedDirectoryListing(listing, fellBackToHome = false))
        null -> Unit
    }

    if (listing.directories.isNotEmpty() || listing.parent == null) {
        return Result.success(ResolvedDirectoryListing(listing, fellBackToHome = false))
    }

    val baseName = resolvedPath.substringAfterLast('/')
    if (baseName.isEmpty() || baseName.startsWith(".")) {
        // Daemons omit hidden entries from parent directory listings, so do not falsely treat
        // a hidden directory as deleted when the daemon does not report `exists`.
        return Result.success(ResolvedDirectoryListing(listing, fellBackToHome = false))
    }

    val parentResult = listDirectories(listing.parent)
    if (parentResult.isSuccess) {
        val parentListing = parentResult.getOrThrow()
        val existsInParent = parentListing.directories.any { entry ->
            normalizeFsPath(entry.path) == resolvedPath || entry.name == baseName
        }
        if (!existsInParent) {
            return loadHomeFallback()
        }
    }

    return Result.success(ResolvedDirectoryListing(listing, fellBackToHome = false))
}

private fun applyCreatedTab(
    state: WorkspaceState,
    tab: TabRecord,
    resolvedCwd: String?,
): WorkspaceState = state.copy(
    tabs = if (state.tabs.any { it.id == tab.id }) state.tabs else state.tabs + tab,
    selectedTabId = tab.id,
    lastCwd = resolvedCwd ?: state.lastCwd,
    messagesByTab = state.messagesByTab + (tab.id to emptyList()),
)

private fun applyReplacedTab(
    state: WorkspaceState,
    tab: TabRecord,
    updatedLastCwd: String? = null,
): WorkspaceState = state.copy(
    tabs = state.tabs.map { if (it.id == tab.id) tab else it },
    lastCwd = updatedLastCwd ?: state.lastCwd,
)

private fun applyDeletedTab(
    state: WorkspaceState,
    tabId: String,
    resp: DeleteTabResponse?,
): WorkspaceState {
    val remaining = state.tabs.filterNot { it.id == tabId }
    val nextSelected = if (state.selectedTabId == tabId) remaining.lastOrNull()?.id else state.selectedTabId
    val nextClosed = resp?.closedTabs ?: resp?.closedTab?.let { archived ->
        (listOf(archived) + state.closedTabs.filterNot { it.id == tabId }).take(MAX_CLOSED_TABS)
    } ?: state.closedTabs
    return state.copy(
        tabs = remaining,
        closedTabs = nextClosed,
        selectedTabId = nextSelected,
        messagesByTab = state.messagesByTab - tabId,
        reopenedTabIds = state.reopenedTabIds - tabId,
    )
}

private fun applyRevivedTab(
    state: WorkspaceState,
    resp: ReviveTabResponse,
): WorkspaceState {
    val revived = resp.tab
    val updatedTabs = if (state.tabs.any { it.id == revived.id }) {
        state.tabs.map { if (it.id == revived.id) revived else it }
    } else {
        state.tabs + revived
    }
    return state.copy(
        tabs = updatedTabs,
        closedTabs = resp.closedTabs ?: state.closedTabs.filterNot { it.id == revived.id },
        selectedTabId = revived.id,
        messagesByTab = state.messagesByTab + (revived.id to resp.messages),
    )
}

private fun applyRefreshedAgentModels(
    state: WorkspaceState,
    agent: String,
    resp: AgentModelsResponse,
): WorkspaceState {
    val info = state.serverInfo ?: return state
    val updatedAgents = info.agents.map { existing ->
        if (existing.type == agent) {
            existing.copy(
                defaultModel = resp.defaultModel ?: existing.defaultModel,
                models = resp.models.ifEmpty { existing.models },
            )
        } else {
            existing
        }
    }
    return state.copy(serverInfo = info.copy(agents = updatedAgents))
}

private enum class ForegroundAction {
    NONE,
    RECONNECT,
    REFRESH_ACTIVE_TAB,
}

private fun resolveForegroundAction(
    connectionState: ConnectionState,
    shouldStayConnected: Boolean,
    hasActiveSocket: Boolean,
    msSinceConnectAttempt: Long,
    graceMs: Long,
): ForegroundAction {
    val withinConnectGrace = connectionState == ConnectionState.CONNECTING &&
        hasActiveSocket &&
        msSinceConnectAttempt < graceMs
    return when {
        !shouldStayConnected -> ForegroundAction.NONE
        connectionState == ConnectionState.AUTH_ERROR -> ForegroundAction.NONE
        connectionState == ConnectionState.CONNECTED -> ForegroundAction.REFRESH_ACTIVE_TAB
        withinConnectGrace -> ForegroundAction.NONE
        else -> ForegroundAction.RECONNECT
    }
}

private data class EnvelopeTransition(
    val nextState: WorkspaceState,
    val selectedBefore: String?,
    val selectedAfter: String?,
    val unreadVisibleTabId: String?,
)

private fun applyWorkspaceEnvelope(
    current: WorkspaceState,
    pendingDeltas: PendingDeltaBuffer,
    envelope: WsServerEnvelope,
    preferredTabId: String?,
): EnvelopeTransition {
    val prepped = pendingDeltas.prepareStateForEvent(current, envelope)
    val reduced = reduceWorkspaceEvent(prepped, envelope, preferredTabId = preferredTabId)
    val visibleId = reduced.activeTab?.takeIf { it.unread }?.id
    val next = if (visibleId == null) {
        reduced
    } else {
        // Cleared locally too, so later events do not ask again while the
        // daemon's own update is still on its way.
        reduced.copy(tabs = reduced.tabs.map { if (it.id == visibleId) it.copy(unread = false) else it })
    }
    return EnvelopeTransition(
        nextState = next,
        selectedBefore = current.selectedTabId,
        selectedAfter = reduced.selectedTabId,
        unreadVisibleTabId = visibleId,
    )
}

private fun applyTabMessagesLoaded(
    state: WorkspaceState,
    tabId: String,
    res: TabMessagesResponse,
): WorkspaceState = state.copy(
    tabs = state.tabs.map { if (it.id == res.tab.id) res.tab else it },
    messagesByTab = state.messagesByTab + (tabId to res.messages),
    loadingTabIds = state.loadingTabIds - tabId,
)

private fun applyDeletedTabWithPendingFlush(
    state: WorkspaceState,
    pendingDeltas: PendingDeltaBuffer,
    tabId: String,
    resp: DeleteTabResponse?,
): WorkspaceState {
    val remaining = state.tabs.filterNot { it.id == tabId }
    val candidateSelected = if (state.selectedTabId == tabId) remaining.lastOrNull()?.id else state.selectedTabId
    val flushed = candidateSelected?.let { pendingDeltas.applyTo(state, tabId = it, consume = true) } ?: state
    return applyDeletedTab(flushed, tabId, resp)
}

private fun computeReconnectDelayMs(
    baseDelayMs: Long,
    maxDelayMs: Long,
    attempt: Int,
): Long = (baseDelayMs * (1 shl attempt.coerceAtMost(4))).coerceAtMost(maxDelayMs)

private fun applySocketClosed(
    state: WorkspaceState,
    stayConnected: Boolean,
    isAuthFailure: Boolean,
    isInForeground: Boolean,
    reason: String?,
): WorkspaceState {
    val nextState = when {
        !stayConnected -> ConnectionState.DISCONNECTED
        isAuthFailure -> ConnectionState.AUTH_ERROR
        !isInForeground -> ConnectionState.DISCONNECTED
        else -> ConnectionState.ERROR
    }
    val nextError = if (nextState == ConnectionState.DISCONNECTED && stayConnected) {
        null
    } else {
        reason ?: "Disconnected from VM"
    }
    return state.copy(
        connectionState = nextState,
        connectionError = nextError,
    )
}

class AgentsTermRepository(
    private val api: AgentsTermApiClient,
    private val settings: ConnectionSettings,
    private val debugRecorder: DebugRecorder? = null,
    private val onOperationalError: ((String) -> Unit)? = null,
    private val reconnectBaseDelayMs: Long = DEFAULT_RECONNECT_BASE_DELAY_MS,
    private val reconnectMaxDelayMs: Long = DEFAULT_RECONNECT_MAX_DELAY_MS,
    private val deltaFlushIntervalMs: Long = DEFAULT_DELTA_FLUSH_INTERVAL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow(
        settings.loadConnection().let { cfg ->
            WorkspaceState(
                config = cfg,
                lastCwd = settings.loadLastCwd(cfg.host),
            )
        },
    )
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private val socketLock = Any()
    private val createTabMutex = Mutex()
    private val pendingDeltas = PendingDeltaBuffer(scope, deltaFlushIntervalMs, clockMs)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var shouldStayConnected = false
    private var isInForeground = true
    private var reconnectAttempt = 0
    private var socketGeneration = 0L
    private var lastConnectAttemptMs = 0L

    init {
        api.isAppInForeground = { synchronized(socketLock) { isInForeground } }
    }

    /** Answers app state inspection requests from the VM; null disables the feature entirely. */
    @Volatile
    var appStateHandler: AppStateRequestHandler? = null

    /** Returns a point-in-time snapshot that includes any buffered deltas not yet flushed to UI. */
    fun snapshotStateWithPendingDeltas(): WorkspaceState = synchronized(socketLock) {
        pendingDeltas.applyTo(_state.value, consume = false)
    }

    private fun flushActiveTabDeltas(expectedGeneration: Long) {
        synchronized(socketLock) {
            pendingDeltas.onScheduledFlushEntered()
            if (expectedGeneration != socketGeneration || !shouldStayConnected) return
            val activeTabId = _state.value.activeTab?.id ?: return
            var flushed = false
            _state.update { current ->
                val next = pendingDeltas.applyTo(current, tabId = activeTabId, consume = true)
                if (next !== current) flushed = true
                next
            }
            if (flushed) pendingDeltas.markActiveFlushed()
        }
    }

    private fun handleMessageDelta(envelope: WsServerEnvelope, generation: Long) {
        synchronized(socketLock) {
            if (generation != socketGeneration || !shouldStayConnected) return
            pendingDeltas.recordDelta(
                envelope = envelope,
                activeTabId = _state.value.activeTab?.id,
                flushNow = { id -> _state.update { cur -> pendingDeltas.applyTo(cur, tabId = id, consume = true) } },
                onScheduledFlush = { flushActiveTabDeltas(generation) },
            )
        }
    }

    fun initializeAutoConnect() {
        val cfg = settings.loadConnection()
        _state.update {
            it.copy(
                config = cfg,
                lastCwd = settings.loadLastCwd(cfg.host),
            )
        }
        if (cfg.isValid) {
            connect(cfg)
        }
    }

    suspend fun testAndConnect(config: ConnectionConfig): Result<ServerInfo> = runCatchingCancellable {
        val info = api.getInfo(config)
        settings.saveConnection(config)
        val savedLastCwd = settings.loadLastCwd(config.host)
        _state.update {
            it.copy(
                config = config,
                serverInfo = info,
                lastCwd = savedLastCwd,
                connectionError = null,
            )
        }
        connect(config)
        info
    }.onFailure { err ->
        _state.update {
            it.copy(
                connectionState = if (err.isAuthError) ConnectionState.AUTH_ERROR else ConnectionState.ERROR,
                connectionError = err.message ?: "Unable to reach VM",
            )
        }
    }

    fun connect(config: ConnectionConfig = _state.value.config) {
        if (!config.isValid) return
        synchronized(socketLock) {
            shouldStayConnected = true
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        openSocketNow(config)
    }

    fun disconnect() {
        val socketToClose: WebSocket?
        synchronized(socketLock) {
            shouldStayConnected = false
            socketGeneration++
            reconnectJob?.cancel()
            reconnectJob = null
            pendingDeltas.clear()
            socketToClose = webSocket
            webSocket = null
            _state.update {
                it.copy(connectionState = ConnectionState.DISCONNECTED)
            }
        }
        socketToClose?.close(1000, "User disconnected")
    }

    fun onBackground() {
        synchronized(socketLock) {
            isInForeground = false
            reconnectJob?.cancel()
            reconnectJob = null
        }
    }

    fun ensureConnectedOnForeground() {
        val s = _state.value
        if (!s.config.isValid) {
            synchronized(socketLock) { isInForeground = true }
            return
        }
        val action = synchronized(socketLock) {
            isInForeground = true
            resolveForegroundAction(
                connectionState = s.connectionState,
                shouldStayConnected = shouldStayConnected,
                hasActiveSocket = webSocket != null,
                msSinceConnectAttempt = clockMs() - lastConnectAttemptMs,
                graceMs = FOREGROUND_CONNECT_GRACE_MS,
            )
        }
        when (action) {
            ForegroundAction.RECONNECT -> connect(s.config)
            ForegroundAction.REFRESH_ACTIVE_TAB -> s.selectedTabId?.let { refreshTabMessages(it) }
            ForegroundAction.NONE -> Unit
        }
    }

    private fun openSocketNow(
        config: ConnectionConfig,
        expectedGeneration: Long? = null,
    ) {
        val previousSocket: WebSocket?
        val generation: Long
        synchronized(socketLock) {
            if (!shouldStayConnected) return
            if (expectedGeneration != null && (expectedGeneration != socketGeneration || !isInForeground)) return
            reconnectJob?.cancel()
            reconnectJob = null
            pendingDeltas.clear()
            generation = ++socketGeneration
            lastConnectAttemptMs = clockMs()
            previousSocket = webSocket
            webSocket = null
            _state.update {
                it.copy(
                    config = config,
                    lastCwd = if (it.config.host == config.host) it.lastCwd else settings.loadLastCwd(config.host),
                    connectionState = ConnectionState.CONNECTING,
                    connectionError = null,
                )
            }
        }
        previousSocket?.cancel()

        val lastSavedTabId = settings.loadLastTabId()
        val socketClosed = AtomicBoolean(false)
        val newSocket = api.openWebSocket(
            config = config,
            onOpen = {
                synchronized(socketLock) {
                    if (generation != socketGeneration || !shouldStayConnected) return@openWebSocket
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.CONNECTED,
                            connectionError = null,
                        )
                    }
                }
            },
            onEvent = { envelope ->
                if (envelope.type == APP_STATE_REQUEST_TYPE) {
                    handleAppStateRequest(envelope, generation)
                    return@openWebSocket
                }
                if (envelope.type == "message_delta") {
                    handleMessageDelta(envelope, generation)
                    return@openWebSocket
                }
                var transition: EnvelopeTransition? = null
                synchronized(socketLock) {
                    if (generation == socketGeneration && shouldStayConnected) {
                        _state.update { current ->
                            applyWorkspaceEnvelope(current, pendingDeltas, envelope, lastSavedTabId)
                                .also { transition = it }
                                .nextState
                        }
                    }
                }
                val applied = transition ?: return@openWebSocket
                if (envelope.type == "snapshot") {
                    // The daemon sends `snapshot` first on every connection, so this is where the
                    // app announces its reporter status (the socket is assigned by then).
                    sendAppHello()
                    applied.selectedAfter?.let { refreshTabMessages(it) }
                } else if (envelope.type == "tab_created" && applied.selectedBefore == null) {
                    applied.selectedAfter?.let { refreshTabMessages(it) }
                }
                // The daemon flags a tab unread whenever its turn ends, including the tab on
                // screen. Clear it here, or it shows up as unread once the user switches away.
                applied.unreadVisibleTabId?.let { markTabRead(config, it) }
            },
            onClosed = { reason, httpStatusCode ->
                if (!socketClosed.compareAndSet(false, true)) return@openWebSocket
                val shouldReconnect = synchronized(socketLock) {
                    if (generation != socketGeneration) return@openWebSocket
                    webSocket = null
                    val isAuthFailure = isAuthStatusCode(httpStatusCode)
                    val stayConnected = shouldStayConnected
                    _state.update {
                        applySocketClosed(
                            state = it,
                            stayConnected = stayConnected,
                            isAuthFailure = isAuthFailure,
                            isInForeground = isInForeground,
                            reason = reason,
                        )
                    }
                    stayConnected && !isAuthFailure && isInForeground
                }
                if (shouldReconnect) {
                    scheduleReconnect(config, expectedGeneration = generation)
                }
            },
        )

        val superseded = synchronized(socketLock) {
            if (generation != socketGeneration || !shouldStayConnected) {
                true
            } else {
                if (!socketClosed.get()) {
                    webSocket = newSocket
                }
                false
            }
        }
        if (superseded) {
            newSocket.cancel()
        }
    }

    /** Sends the current `app_hello` (e.g. after the user toggles remote inspection). */
    fun sendAppHello() {
        val handler = appStateHandler ?: return
        val socket = synchronized(socketLock) { webSocket } ?: return
        socket.send(handler.helloJson())
    }

    private fun handleAppStateRequest(envelope: WsServerEnvelope, generation: Long) {
        val requestId = envelope.requestId ?: return
        val handler = appStateHandler ?: return
        scope.launch {
            val response = handler.handleRequest(
                requestId = requestId,
                command = envelope.command.orEmpty(),
                args = envelope.args.orEmpty(),
            )
            val socket = synchronized(socketLock) {
                // If the socket was replaced, the daemon has already failed this request.
                if (generation == socketGeneration) webSocket else null
            }
            val sent = socket?.send(response) ?: false
            if (!sent) {
                debugRecorder?.record(
                    category = "app_state",
                    event = "app_state_response_dropped",
                    detail = "Could not send response for '${envelope.command}' (socket closed or replaced)",
                )
            }
        }
    }

    private fun scheduleReconnect(
        config: ConnectionConfig,
        expectedGeneration: Long,
    ) {
        synchronized(socketLock) {
            if (expectedGeneration != socketGeneration || !shouldStayConnected || !isInForeground) return
            reconnectJob?.cancel()
            val delayMs = computeReconnectDelayMs(reconnectBaseDelayMs, reconnectMaxDelayMs, reconnectAttempt++)
            reconnectJob = scope.launch {
                delay(delayMs)
                openSocketNow(config, expectedGeneration = expectedGeneration)
            }
        }
    }

    fun selectTab(tabId: String) {
        debugRecorder?.record("ui", "select_tab", "Selected tab $tabId")
        settings.saveLastTabId(tabId)
        synchronized(socketLock) {
            pendingDeltas.cancelScheduledFlush()
            _state.update { current ->
                val flushed = pendingDeltas.applyTo(current, tabId = tabId, consume = true)
                flushed.copy(selectedTabId = tabId)
            }
            pendingDeltas.markActiveFlushed()
        }
        refreshTabMessages(tabId)
        markTabRead(_state.value.config, tabId)
    }

    private fun markTabRead(cfg: ConnectionConfig, tabId: String) {
        if (!cfg.isValid) return
        scope.launch {
            runCatchingCancellable { api.markRead(cfg, tabId) }
        }
    }

    fun refreshTabMessages(tabId: String) {
        val cfg = _state.value.config
        if (!cfg.isValid) return
        scope.launch {
            _state.update { it.copy(loadingTabIds = it.loadingTabIds + tabId) }
            runCatchingCancellable { api.getTabMessages(cfg, tabId) }
                .onSuccess { res ->
                    debugRecorder?.record(
                        "state",
                        "refresh_tab_messages_ok",
                        "Loaded ${res.messages.size} messages for tab $tabId (status=${res.tab.status})",
                    )
                    synchronized(socketLock) {
                        pendingDeltas.clear(tabId = tabId)
                        _state.update { applyTabMessagesLoaded(it, tabId, res) }
                    }
                }
                .onFailure { err ->
                    debugRecorder?.record(
                        "error",
                        "refresh_tab_messages_failed",
                        "Failed to load messages for tab $tabId: ${err.message ?: err::class.java.simpleName}",
                    )
                    _state.update { it.copy(loadingTabIds = it.loadingTabIds - tabId) }
                }
        }
    }

    suspend fun createTab(request: CreateTabRequest): Result<TabRecord> = createTabMutex.withLock {
        val workspace = _state.value
        val cfg = workspace.config
        debugRecorder?.record(
            "ui",
            "create_tab",
            "Creating tab agent=${request.agent} cwd=${request.cwd} mode=${request.permissionMode} " +
                "hasSystemPrompt=${!request.systemPrompt.isNullOrBlank()}",
        )
        runCatchingCancellable {
            check(workspace.canCreateTab) { "The limit is $MAX_OPEN_TABS open tabs. Close a tab to make room." }
            val tab = api.createTab(cfg, request)
            settings.saveLastTabId(tab.id)
            val resolvedCwd = tab.cwd.trim().takeIf { it.isNotEmpty() }
            if (resolvedCwd != null) settings.saveLastCwd(cfg.host, resolvedCwd)
            _state.update { applyCreatedTab(it, tab, resolvedCwd) }
            tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to create tab")
        }
    }

    suspend fun updateTab(tabId: String, request: UpdateTabRequest): Result<TabRecord> {
        val cfg = _state.value.config
        return runCatchingCancellable {
            val tab = api.updateTab(cfg, tabId, request)
            val updatedLastCwd = if (!request.cwd.isNullOrBlank()) {
                tab.cwd.trim().takeIf { it.isNotEmpty() }?.also { settings.saveLastCwd(cfg.host, it) }
            } else {
                null
            }
            _state.update { applyReplacedTab(it, tab, updatedLastCwd) }
            tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to update tab")
        }
    }

    suspend fun updateTabModel(tabId: String, model: String?): Result<TabRecord> {
        val normalized = model?.trim().orEmpty()
        debugRecorder?.record("ui", "update_tab_model", "Updating tab=$tabId model=${normalized.ifEmpty { "(default)" }}")
        return updateTab(tabId, UpdateTabRequest(model = normalized))
    }

    suspend fun updateTabSystemPrompt(tabId: String, systemPrompt: String?): Result<TabRecord> {
        val normalized = systemPrompt?.trim().orEmpty()
        debugRecorder?.record(
            "ui",
            "update_tab_system_prompt",
            "Updating tab=$tabId systemPrompt=${if (normalized.isEmpty()) "(empty)" else "len=${normalized.length}"}",
        )
        return updateTab(tabId, UpdateTabRequest(systemPrompt = normalized))
    }

    suspend fun updateTabStatus(tabId: String, status: String): Result<TabRecord> {
        debugRecorder?.record("ui", "update_tab_status", "Updating tab=$tabId status=$status")
        _state.update { cur ->
            val nextReopened = if (status == "idle") cur.reopenedTabIds + tabId else cur.reopenedTabIds - tabId
            cur.copy(reopenedTabIds = nextReopened)
        }
        return updateTab(tabId, UpdateTabRequest(status = status))
    }

    suspend fun refreshAgentModels(agent: String, forceRefresh: Boolean = false): Result<AgentModelsResponse> {
        val cfg = _state.value.config
        if (!cfg.isValid) return Result.failure(IllegalStateException("Not connected to a VM daemon"))
        return runCatchingCancellable {
            api.getAgentModels(cfg, agent, refresh = forceRefresh).also { resp ->
                _state.update { applyRefreshedAgentModels(it, agent, resp) }
            }
        }
    }

    suspend fun fetchAgentUsage(agent: String): Result<AgentUsageResponse> {
        val cfg = _state.value.config
        if (!cfg.isValid) return Result.failure(IllegalStateException("Not connected to a VM daemon"))
        return runCatchingCancellable { api.getAgentUsage(cfg, agent) }
    }

    suspend fun deleteTab(tabId: String): Result<TabRecord?> {
        val cfg = _state.value.config
        debugRecorder?.record("ui", "delete_tab", "Deleting tab $tabId")
        return runCatchingCancellable {
            val resp = api.deleteTab(cfg, tabId)
            var nextSelected: String? = null
            synchronized(socketLock) {
                pendingDeltas.clear(tabId = tabId)
                _state.update { cur ->
                    applyDeletedTabWithPendingFlush(cur, pendingDeltas, tabId, resp).also { nextSelected = it.selectedTabId }
                }
            }
            settings.saveLastTabId(nextSelected)
            resp?.closedTab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to delete tab")
        }
    }

    suspend fun refreshClosedTabs(): Result<List<TabRecord>> {
        val cfg = _state.value.config
        if (!cfg.isValid) return Result.failure(IllegalStateException("Not connected to a VM daemon"))
        return runCatchingCancellable {
            val resp = api.listClosedTabs(cfg)
            _state.update { it.copy(closedTabs = resp.closedTabs) }
            resp.closedTabs
        }
    }

    suspend fun reviveClosedTab(tabId: String): Result<TabRecord> = createTabMutex.withLock {
        val workspace = _state.value
        val cfg = workspace.config
        debugRecorder?.record("ui", "revive_closed_tab", "Reviving closed tab $tabId")
        runCatchingCancellable {
            check(workspace.canCreateTab) { "The limit is $MAX_OPEN_TABS open tabs. Close a tab to make room." }
            val resp = api.reviveClosedTab(cfg, tabId)
            settings.saveLastTabId(resp.tab.id)
            _state.update { applyRevivedTab(it, resp) }
            resp.tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to revive tab")
            runCatchingCancellable { refreshClosedTabs() }
        }
    }

    suspend fun sendMessage(
        tabId: String,
        text: String,
        overridePermissionMode: String? = null,
        attachmentIds: List<String> = emptyList(),
    ): Result<Unit> {
        val cfg = _state.value.config
        debugRecorder?.record(
            "ui",
            "send_message",
            "Sending message to tab=$tabId len=${text.length} overrideMode=${overridePermissionMode ?: "none"}",
            payload = text.take(500),
        )
        return runCatchingCancellable {
            val resp = api.sendMessage(cfg, tabId, text, overridePermissionMode, attachmentIds)
            resp.tab?.let { updated -> _state.update { applyReplacedTab(it, updated) } }
            Unit
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to send message")
        }
    }

    suspend fun sendStdin(
        tabId: String,
        data: String? = null,
        eof: Boolean = false,
    ): Result<Unit> {
        val cfg = _state.value.config
        debugRecorder?.record(
            "ui",
            "send_stdin",
            "Sending stdin to tab=$tabId len=${data?.length ?: 0} eof=$eof",
        )
        return runCatchingCancellable {
            api.sendStdin(cfg, tabId, data = data, eof = eof)
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to send input")
        }
    }

    suspend fun interruptTab(
        tabId: String,
        processQueue: Boolean = false,
        text: String? = null,
        overridePermissionMode: String? = null,
        attachmentIds: List<String> = emptyList(),
    ) {
        val cfg = _state.value.config
        debugRecorder?.record(
            "ui",
            "interrupt_tab",
            "Interrupting tab=$tabId processQueue=$processQueue hasText=${!text.isNullOrBlank()}",
        )
        runCatchingCancellable {
            api.interruptTab(
                config = cfg,
                tabId = tabId,
                processQueue = processQueue,
                text = text,
                overridePermissionMode = overridePermissionMode,
                attachmentIds = attachmentIds,
            )?.let { updated -> _state.update { applyReplacedTab(it, updated) } }
        }.onFailure { err -> showBanner(err.message ?: "Failed to interrupt") }
    }

    suspend fun removeQueuedMessage(tabId: String, queuedId: String) {
        val cfg = _state.value.config
        debugRecorder?.record("ui", "remove_queued_message", "Removing queued=$queuedId from tab=$tabId")
        runCatchingCancellable {
            api.removeQueuedMessage(cfg, tabId, queuedId)?.let { updated ->
                _state.update { applyReplacedTab(it, updated) }
            }
        }.onFailure { err -> showBanner(err.message ?: "Failed to remove queued message") }
    }

    suspend fun loadInitialDirectories(preferredPath: String?): Result<FsListResponse> {
        val cfg = _state.value.config
        val homeCwd = _state.value.serverInfo?.defaultCwd
        val outcome = resolveInitialDirectoryListing(
            preferredPath = preferredPath,
            homeCwd = homeCwd,
            listDirectories = { path -> listDirectories(path) },
        )
        outcome.onSuccess { resolved ->
            if (resolved.fellBackToHome && resolved.listing.path.isNotBlank()) {
                debugRecorder?.record(
                    "state",
                    "cwd_fallback_home",
                    "Preferred folder '${preferredPath ?: "default"}' missing/deleted; fell back to '${resolved.listing.path}'",
                )
                settings.saveLastCwd(cfg.host, resolved.listing.path)
                _state.update { it.copy(lastCwd = resolved.listing.path) }
            }
        }
        return outcome.map { it.listing }
    }

    suspend fun listDirectories(path: String?): Result<FsListResponse> {
        val cfg = _state.value.config
        return runCatchingCancellable { api.listDirectories(cfg, path) }
            .onSuccess { res ->
                debugRecorder?.record(
                    "state",
                    "list_directories_ok",
                    "listDirectories(${path ?: "default"}) -> path=${res.path} parent=${res.parent} dirs=${res.directories.size}",
                )
            }
            .onFailure { err ->
                debugRecorder?.record(
                    "error",
                    "list_directories_failed",
                    "listDirectories(${path ?: "default"}) failed: ${err.message ?: err::class.java.simpleName}",
                )
            }
    }

    suspend fun uploadDebugDump(request: DebugDumpUploadRequest): Result<DebugDumpUploadResponse> {
        val cfg = _state.value.config
        if (!cfg.isValid) {
            return Result.failure(IllegalStateException("Not connected to a VM daemon"))
        }
        return runCatchingCancellable { api.uploadDebugDump(cfg, request) }
    }

    fun updateDaemonUpdateStatus(status: DaemonUpdateStatus?) {
        _state.update { it.copy(daemonUpdateStatus = status?.takeUnless { s -> s.state == "idle" }) }
    }

    fun clearBanner() = _state.update { it.copy(bannerMessage = null) }

    fun showBanner(msg: String) {
        debugRecorder?.record("error", "banner_message", msg)
        _state.update { it.copy(bannerMessage = msg) }
        onOperationalError?.invoke(msg)
    }

    companion object {
        const val APP_STATE_REQUEST_TYPE = "app_state_request"
    }
}
