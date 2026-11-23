package com.example.data

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

data class WorkspaceState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionError: String? = null,
    val serverInfo: ServerInfo? = null,
    val tabs: List<TabRecord> = emptyList(),
    val selectedTabId: String? = null,
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
                    msg.copy(toolCalls = tools)
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
                existing.map { if (it.id == msg.id) msg else it }
            } else {
                existing + msg
            }
            state.copy(messagesByTab = state.messagesByTab + (tabId to updated))
        }

        else -> state
    }
}

class HerdrRepository(
    private val api: HerdrApiClient,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        WorkspaceState(config = settings.loadConnection())
    )
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var shouldStayConnected = false
    private var reconnectAttempt = 0

    fun initializeAutoConnect() {
        val cfg = settings.loadConnection()
        _state.update { it.copy(config = cfg) }
        if (cfg.isValid) {
            connect(cfg)
        }
    }

    suspend fun testAndConnect(config: ConnectionConfig): Result<ServerInfo> {
        return runCatching {
            val info = api.getInfo(config)
            settings.saveConnection(config)
            _state.update {
                it.copy(
                    config = config,
                    serverInfo = info,
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
        shouldStayConnected = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        openSocketNow(config)
    }

    fun disconnect() {
        shouldStayConnected = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _state.update {
            it.copy(connectionState = ConnectionState.DISCONNECTED)
        }
    }

    fun ensureConnectedOnForeground() {
        val s = _state.value
        if (s.config.isValid && shouldStayConnected && s.connectionState != ConnectionState.CONNECTED) {
            connect(s.config)
        } else if (s.config.isValid && s.connectionState == ConnectionState.CONNECTED) {
            s.selectedTabId?.let { refreshTabMessages(it) }
        }
    }

    private fun openSocketNow(config: ConnectionConfig) {
        webSocket?.cancel()
        _state.update {
            it.copy(
                config = config,
                connectionState = ConnectionState.CONNECTING,
                connectionError = null,
            )
        }

        val lastSavedTabId = settings.loadLastTabId()
        webSocket = api.openWebSocket(
            config = config,
            onOpen = {
                reconnectAttempt = 0
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        connectionError = null,
                    )
                }
            },
            onEvent = { envelope ->
                var selectedAfter: String? = null
                _state.update { current ->
                    val next = reduceWorkspaceEvent(current, envelope, preferredTabId = lastSavedTabId)
                    selectedAfter = next.selectedTabId
                    next
                }
                if (envelope.type == "snapshot") {
                    selectedAfter?.let { tabId ->
                        refreshTabMessages(tabId)
                    }
                }
            },
            onClosed = { reason ->
                _state.update {
                    it.copy(
                        connectionState = if (shouldStayConnected) ConnectionState.ERROR else ConnectionState.DISCONNECTED,
                        connectionError = reason ?: "Disconnected from VM",
                    )
                }
                if (shouldStayConnected) {
                    scheduleReconnect(config)
                }
            },
        )
    }

    private fun scheduleReconnect(config: ConnectionConfig) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (1500L * (1 shl reconnectAttempt.coerceAtMost(4))).coerceAtMost(15_000L)
            reconnectAttempt++
            delay(delayMs)
            if (shouldStayConnected) {
                openSocketNow(config)
            }
        }
    }

    fun selectTab(tabId: String) {
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
                    _state.update { cur ->
                        cur.copy(
                            tabs = cur.tabs.map { if (it.id == res.tab.id) res.tab else it },
                            messagesByTab = cur.messagesByTab + (tabId to res.messages),
                            loadingTabIds = cur.loadingTabIds - tabId,
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(loadingTabIds = it.loadingTabIds - tabId) }
                }
        }
    }

    suspend fun createTab(request: CreateTabRequest): Result<TabRecord> {
        val cfg = _state.value.config
        return runCatching {
            val tab = api.createTab(cfg, request)
            settings.saveLastTabId(tab.id)
            _state.update { cur ->
                val updated = if (cur.tabs.any { it.id == tab.id }) cur.tabs else cur.tabs + tab
                cur.copy(
                    tabs = updated,
                    selectedTabId = tab.id,
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
            _state.update { cur ->
                cur.copy(tabs = cur.tabs.map { if (it.id == tab.id) tab else it })
            }
            tab
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to update tab")
        }
    }

    suspend fun deleteTab(tabId: String): Result<Unit> {
        val cfg = _state.value.config
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
        return runCatching {
            api.sendMessage(cfg, tabId, text, overridePermissionMode)
            Unit
        }.onFailure { err ->
            showBanner(err.message ?: "Failed to send message")
        }
    }

    suspend fun interruptTab(tabId: String) {
        val cfg = _state.value.config
        runCatching { api.interruptTab(cfg, tabId) }
            .onFailure { err -> showBanner(err.message ?: "Failed to interrupt") }
    }

    suspend fun listDirectories(path: String?): Result<FsListResponse> {
        val cfg = _state.value.config
        return runCatching { api.listDirectories(cfg, path) }
    }

    fun clearBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    private fun showBanner(msg: String) {
        _state.update { it.copy(bannerMessage = msg) }
    }
}
