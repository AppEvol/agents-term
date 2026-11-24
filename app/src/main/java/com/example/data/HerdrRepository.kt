package com.example.data

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
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicBoolean

data class WorkspaceState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionError: String? = null,
    val serverInfo: ServerInfo? = null,
    val tabs: List<TabRecord> = emptyList(),
    val selectedTabId: String? = null,
    val lastCwd: String? = null,
    val messagesByTab: Map<String, List<ChatMessage>> = emptyMap(),
    val loadingTabIds: Set<String> = emptySet(),
    val bannerMessage: String? = null,
) {
    val activeTab: TabRecord?
        get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()

    val activeMessages: List<ChatMessage>
        get() = activeTab?.let { messagesByTab[it.id] }.orEmpty()

    val attentionCount: Int
        get() = tabs.count { it.unread || it.status == "blocked" || it.status == "error" }
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
            state.copy(
                connectionState = ConnectionState.CONNECTED,
                connectionError = null,
                serverInfo = event.server ?: state.serverInfo,
                tabs = incomingTabs,
                selectedTabId = nextSelected,
            )
        }

        "tab_created" -> {
            val tab = event.tab ?: return state
            val updated = if (state.tabs.any { it.id == tab.id }) {
                state.tabs.map { if (it.id == tab.id) tab else it }
            } else {
                state.tabs + tab
            }
            state.copy(
                tabs = updated,
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
            state.copy(tabs = updated)
        }

        "tab_deleted" -> {
            val id = event.tabId ?: return state
            val remaining = state.tabs.filterNot { it.id == id }
            val nextSelected = if (state.selectedTabId == id) remaining.lastOrNull()?.id else state.selectedTabId
            state.copy(
                tabs = remaining,
                selectedTabId = nextSelected,
                messagesByTab = state.messagesByTab - id,
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
        if (result.isFailure) {
            result = listDirectories(path)
        }
        return result
    }

    suspend fun loadHomeFallback(): Result<ResolvedDirectoryListing> {
        return loadWithRetry(normalizedHome).map { homeListing ->
            ResolvedDirectoryListing(listing = homeListing, fellBackToHome = true)
        }
    }

    val initialResult = loadWithRetry(candidate)
    if (initialResult.isFailure) {
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

class HerdrRepository(
    private val api: HerdrApiClient,
    private val settings: ConnectionSettings,
    private val debugRecorder: DebugRecorder? = null,
    private val onOperationalError: ((String) -> Unit)? = null,
    private val reconnectBaseDelayMs: Long = DEFAULT_RECONNECT_BASE_DELAY_MS,
    private val reconnectMaxDelayMs: Long = DEFAULT_RECONNECT_MAX_DELAY_MS,
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
        }
    )
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private val socketLock = Any()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var shouldStayConnected = false
    private var reconnectAttempt = 0
    private var socketGeneration = 0L
    private var lastConnectAttemptMs = 0L

    /** Answers app state inspection requests from the VM; null disables the feature entirely. */
    @Volatile
    var appStateHandler: AppStateRequestHandler? = null

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

    suspend fun testAndConnect(config: ConnectionConfig): Result<ServerInfo> {
        return runCatching {
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
                    connectionState = ConnectionState.ERROR,
                    connectionError = err.message ?: "Unable to reach VM",
                )
            }
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
            socketToClose = webSocket
            webSocket = null
            _state.update {
                it.copy(connectionState = ConnectionState.DISCONNECTED)
            }
        }
        socketToClose?.close(1000, "User disconnected")
    }

    fun ensureConnectedOnForeground() {
        val s = _state.value
        if (!s.config.isValid) return
        val action = synchronized(socketLock) {
            when {
                !shouldStayConnected -> ForegroundAction.NONE
                s.connectionState == ConnectionState.CONNECTED -> ForegroundAction.REFRESH_ACTIVE_TAB
                s.connectionState == ConnectionState.CONNECTING &&
                    webSocket != null &&
                    (clockMs() - lastConnectAttemptMs) < FOREGROUND_CONNECT_GRACE_MS -> ForegroundAction.NONE
                else -> ForegroundAction.RECONNECT
            }
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
            if (expectedGeneration != null && expectedGeneration != socketGeneration) return
            reconnectJob?.cancel()
            reconnectJob = null
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
                var selectedAfter: String? = null
                val applied = synchronized(socketLock) {
                    if (generation != socketGeneration || !shouldStayConnected) {
                        false
                    } else {
                        _state.update { current ->
                            val next = reduceWorkspaceEvent(current, envelope, preferredTabId = lastSavedTabId)
                            selectedAfter = next.selectedTabId
                            next
                        }
                        true
                    }
                }
                if (applied && envelope.type == "snapshot") {
                    // The daemon sends `snapshot` first on every connection, so this is where the
                    // app announces its reporter status (the socket is assigned by then).
                    sendAppHello()
                    selectedAfter?.let { tabId ->
                        refreshTabMessages(tabId)
                    }
                }
            },
            onClosed = { reason ->
                if (!socketClosed.compareAndSet(false, true)) return@openWebSocket
                val shouldReconnect = synchronized(socketLock) {
                    if (generation != socketGeneration) return@openWebSocket
                    webSocket = null
                    val stayConnected = shouldStayConnected
                    _state.update {
                        it.copy(
                            connectionState = if (stayConnected) ConnectionState.ERROR else ConnectionState.DISCONNECTED,
                            connectionError = reason ?: "Disconnected from VM",
                        )
                    }
                    stayConnected
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
            if (expectedGeneration != socketGeneration || !shouldStayConnected) return
            reconnectJob?.cancel()
            val delayMs = (reconnectBaseDelayMs * (1 shl reconnectAttempt.coerceAtMost(4)))
                .coerceAtMost(reconnectMaxDelayMs)
            reconnectAttempt++
            reconnectJob = scope.launch {
                delay(delayMs)
                openSocketNow(config, expectedGeneration = expectedGeneration)
            }
        }
    }

    private enum class ForegroundAction {
        NONE,
        RECONNECT,
        REFRESH_ACTIVE_TAB,
    }

    fun selectTab(tabId: String) {
        debugRecorder?.record("ui", "select_tab", "Selected tab $tabId")
        settings.saveLastTabId(tabId)
        _state.update { it.copy(selectedTabId = tabId) }
        refreshTabMessages(tabId)
        val cfg = _state.value.config
        if (cfg.isValid) {
            scope.launch {
                runCatching { api.markRead(cfg, tabId) }
            }
        }
    }

    fun refreshTabMessages(tabId: String) {
        val cfg = _state.value.config
        if (!cfg.isValid) return
        scope.launch {
            _state.update { it.copy(loadingTabIds = it.loadingTabIds + tabId) }
            runCatching { api.getTabMessages(cfg, tabId) }
                .onSuccess { res ->
                    debugRecorder?.record(
                        "state",
                        "refresh_tab_messages_ok",
                        "Loaded ${res.messages.size} messages for tab $tabId (status=${res.tab.status})",
                    )
                    _state.update { cur ->
                        cur.copy(
                            tabs = cur.tabs.map { if (it.id == res.tab.id) res.tab else it },
                            messagesByTab = cur.messagesByTab + (tabId to res.messages),
                            loadingTabIds = cur.loadingTabIds - tabId,
                        )
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

    suspend fun createTab(request: CreateTabRequest): Result<TabRecord> {
        val cfg = _state.value.config
        debugRecorder?.record(
            "ui",
            "create_tab",
            "Creating tab agent=${request.agent} cwd=${request.cwd} mode=${request.permissionMode} " +
                "hasSystemPrompt=${!request.systemPrompt.isNullOrBlank()}",
        )
        return runCatching {
            val tab = api.createTab(cfg, request)
            settings.saveLastTabId(tab.id)
            val resolvedCwd = tab.cwd.trim().takeIf { it.isNotEmpty() }
            if (resolvedCwd != null) {
                settings.saveLastCwd(cfg.host, resolvedCwd)
            }
            _state.update { cur ->
                val updated = if (cur.tabs.any { it.id == tab.id }) cur.tabs else cur.tabs + tab
                cur.copy(
                    tabs = updated,
                    selectedTabId = tab.id,
                    lastCwd = resolvedCwd ?: cur.lastCwd,
                    messagesByTab = cur.messagesByTab + (tab.id to emptyList()),
                )
            }
            tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to create tab")
        }
    }

    suspend fun updateTab(tabId: String, request: UpdateTabRequest): Result<TabRecord> {
        val cfg = _state.value.config
        return runCatching {
            val tab = api.updateTab(cfg, tabId, request)
            val updatedLastCwd = if (!request.cwd.isNullOrBlank()) {
                tab.cwd.trim().takeIf { it.isNotEmpty() }?.also {
                    settings.saveLastCwd(cfg.host, it)
                }
            } else {
                null
            }
            _state.update { cur ->
                cur.copy(
                    tabs = cur.tabs.map { if (it.id == tab.id) tab else it },
                    lastCwd = updatedLastCwd ?: cur.lastCwd,
                )
            }
            tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to update tab")
        }
    }

    suspend fun updateTabModel(tabId: String, model: String?): Result<TabRecord> {
        val normalized = model?.trim().orEmpty()
        debugRecorder?.record(
            "ui",
            "update_tab_model",
            "Updating tab=$tabId model=${normalized.ifEmpty { "(default)" }}",
        )
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

    suspend fun refreshAgentModels(
        agent: String,
        forceRefresh: Boolean = false,
    ): Result<AgentModelsResponse> {
        val cfg = _state.value.config
        if (!cfg.isValid) {
            return Result.failure(IllegalStateException("Not connected to a VM daemon"))
        }
        return runCatching {
            val resp = api.getAgentModels(cfg, agent, refresh = forceRefresh)
            _state.update { cur ->
                val info = cur.serverInfo ?: return@update cur
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
                cur.copy(serverInfo = info.copy(agents = updatedAgents))
            }
            resp
        }
    }

    suspend fun deleteTab(tabId: String): Result<Unit> {
        val cfg = _state.value.config
        debugRecorder?.record("ui", "delete_tab", "Deleting tab $tabId")
        return runCatching {
            api.deleteTab(cfg, tabId)
            _state.update { cur ->
                val remaining = cur.tabs.filterNot { it.id == tabId }
                val nextSelected = if (cur.selectedTabId == tabId) remaining.lastOrNull()?.id else cur.selectedTabId
                settings.saveLastTabId(nextSelected)
                cur.copy(
                    tabs = remaining,
                    selectedTabId = nextSelected,
                    messagesByTab = cur.messagesByTab - tabId,
                )
            }
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to delete tab")
        }
    }

    suspend fun sendMessage(
        tabId: String,
        text: String,
        overridePermissionMode: String? = null,
    ): Result<Unit> {
        val cfg = _state.value.config
        debugRecorder?.record(
            "ui",
            "send_message",
            "Sending message to tab=$tabId len=${text.length} overrideMode=${overridePermissionMode ?: "none"}",
            payload = text.take(500),
        )
        return runCatching {
            api.sendMessage(cfg, tabId, text, overridePermissionMode)
            Unit
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to send message")
        }
    }

    suspend fun interruptTab(tabId: String) {
        val cfg = _state.value.config
        debugRecorder?.record("ui", "interrupt_tab", "Interrupting tab=$tabId")
        runCatching { api.interruptTab(cfg, tabId) }
            .onFailure { err -> showBanner(err.message ?: "Failed to interrupt") }
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
        return runCatching { api.listDirectories(cfg, path) }
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
        return runCatching { api.uploadDebugDump(cfg, request) }
    }

    fun clearBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    fun showBanner(msg: String) {
        debugRecorder?.record("error", "banner_message", msg)
        _state.update { it.copy(bannerMessage = msg) }
        onOperationalError?.invoke(msg)
    }

    companion object {
        private const val DEFAULT_RECONNECT_BASE_DELAY_MS = 1_500L
        private const val DEFAULT_RECONNECT_MAX_DELAY_MS = 15_000L
        private const val FOREGROUND_CONNECT_GRACE_MS = 2_000L
        const val APP_STATE_REQUEST_TYPE = "app_state_request"
    }
}
