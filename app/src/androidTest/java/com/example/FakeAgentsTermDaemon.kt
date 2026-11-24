package com.example

import com.example.data.AgentModelOption
import com.example.data.AgentModelsResponse
import com.example.data.AvailableAgentInfo
import com.example.data.ChatMessage
import com.example.data.CreateTabRequest
import com.example.data.DEFAULT_AGENT_SYSTEM_PROMPT
import com.example.data.DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT
import com.example.data.DebugDumpUploadRequest
import com.example.data.DebugDumpUploadResponse
import com.example.data.DeniedAction
import com.example.data.FsDirectoryEntry
import com.example.data.FsListResponse
import com.example.data.HerdrMoshi
import com.example.data.InterruptTabRequest
import com.example.data.MessageSegment
import com.example.data.QueueActionResponse
import com.example.data.QueuedMessage
import com.example.data.SendMessageRequest
import com.example.data.SendMessageResponse
import com.example.data.ServerInfo
import com.example.data.SingleTabResponse
import com.example.data.TabMessagesResponse
import com.example.data.TabRecord
import com.example.data.TabsResponse
import com.example.data.ToolCallItem
import com.example.data.UpdateTabRequest
import com.example.data.UsageStats
import com.example.data.WsServerEnvelope
import com.example.data.decodeHerdrJson
import com.example.data.encodeHerdrJson
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process fake AgentsTerm VM daemon implementing the full HTTP + WebSocket contract
 * expected by the Android client so E2E tests exercise real OkHttp networking, JSON
 * serialization, ViewModel/Repository state management, and Jetpack Compose UI.
 */
class FakeAgentsTermDaemon(
    val token: String = "e2e-test-token",
    val hostname: String = "ci-test-vm",
    initialTabs: List<TabRecord> = emptyList(),
    initialMessages: Map<String, List<ChatMessage>> = emptyMap(),
) : AutoCloseable {

    private val lock = Any()
    private val tabCounter = AtomicInteger(initialTabs.size)
    private val msgCounter = AtomicInteger(100)
    private val executor = Executors.newCachedThreadPool()

    private val tabs = initialTabs.toMutableList()
    private val messagesByTab = initialMessages.mapValues { it.value.toMutableList() }.toMutableMap()
    private val activeSockets = CopyOnWriteArrayList<WebSocket>()

    val interruptedTabIds = CopyOnWriteArrayList<String>()
    val markedReadTabIds = CopyOnWriteArrayList<String>()
    val receivedOverrideModes = CopyOnWriteArrayList<String>()
    val uploadedDebugDumps = CopyOnWriteArrayList<DebugDumpUploadRequest>()
    val recordedRequests = CopyOnWriteArrayList<String>()
    val webSocketUpgradeCount = AtomicInteger(0)

    /** Raw text frames the app sent over the WebSocket (app_hello, app_state_response). */
    val receivedAppFrames = CopyOnWriteArrayList<String>()
    private val appStateRequestCounter = AtomicInteger(0)

    @Suppress("UNCHECKED_CAST")
    private fun parseFrame(raw: String): Map<String, Any?> = (HerdrMoshi.adapter(Any::class.java).fromJson(raw) as? Map<String, Any?>).orEmpty()

    /** `reporter.enabled` from the most recent app_hello frame, or null if none arrived yet. */
    @Suppress("UNCHECKED_CAST")
    fun latestAppHello(): Map<String, Any?>? = receivedAppFrames
        .map { parseFrame(it) }
        .lastOrNull { it["type"] == "app_hello" }
        ?.let { it["reporter"] as? Map<String, Any?> }

    /**
     * Sends an app_state_request like the real daemon's broker and waits for the app's response.
     * Returns the parsed app_state_response frame.
     */
    fun requestAppState(command: String, argsJson: String = "{}", timeoutMs: Long = 15_000L): Map<String, Any?> {
        val requestId = "e2e-req-${appStateRequestCounter.incrementAndGet()}"
        val frame = """{"type":"app_state_request","requestId":"$requestId","command":"$command","args":$argsJson}"""
        check(activeSockets.isNotEmpty()) { "No app WebSocket connected" }
        for (ws in activeSockets) ws.send(frame)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            receivedAppFrames
                .map { parseFrame(it) }
                .firstOrNull { it["type"] == "app_state_response" && it["requestId"] == requestId }
                ?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("App did not answer app_state_request '$command' within ${timeoutMs}ms")
    }

    fun dropAllWebSockets() {
        val snapshot = activeSockets.toList()
        activeSockets.clear()
        for (ws in snapshot) {
            runCatching { ws.close(1001, "Simulated network drop") }
        }
    }

    private val serverInfo = ServerInfo(
        version = "0.1.0-e2e",
        hostname = hostname,
        tailscaleIp = "127.0.0.1",
        defaultCwd = "/home/ci",
        defaultSystemPrompt = DEFAULT_AGENT_SYSTEM_PROMPT,
        defaultSystemPromptFullAccess = DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT,
        agents = listOf(
            AvailableAgentInfo(
                type = "agy",
                name = "Antigravity",
                description = "Antigravity CLI",
                binary = "agy",
                available = true,
                defaultModel = "gemini-4-neon-high",
                models = listOf(
                    AgentModelOption("gemini-4-neon-high", "Gemini 4 Neon (High)", isDefault = true),
                    AgentModelOption("gemini-4-neon-xhigh", "Gemini 4 Neon (XHigh)"),
                    AgentModelOption("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"),
                ),
            ),
            AvailableAgentInfo(
                type = "claude",
                name = "Claude Code",
                description = "Claude CLI",
                binary = "claude",
                available = true,
                defaultModel = null,
                models = listOf(
                    AgentModelOption("claude-opus-5-5", "Opus 5.5", "For complex work and everyday tasks"),
                    AgentModelOption("claude-sonnet-5-5", "Sonnet 5.5", "Most efficient for simpler tasks"),
                    AgentModelOption("claude-haiku-5-5", "Haiku 5.5", "Fastest for quick answers"),
                ),
            ),
            AvailableAgentInfo(
                type = "codex",
                name = "Codex",
                description = "Codex CLI",
                binary = "codex",
                available = true,
                defaultModel = "gpt-6-astra",
                models = listOf(
                    AgentModelOption("gpt-6.2-sol", "GPT-6.2-Sol"),
                    AgentModelOption("gpt-6-astra", "GPT-6-Astra", isDefault = true),
                ),
            ),
            AvailableAgentInfo("shell", "Shell", "Persistent bash", "bash", true),
        ),
    )

    fun tabModel(tabId: String): String? = synchronized(lock) {
        tabs.firstOrNull { it.id == tabId }?.model
    }

    fun tabSystemPrompt(tabId: String): String? = synchronized(lock) {
        tabs.firstOrNull { it.id == tabId }?.systemPrompt
    }

    private val fsListings = mutableMapOf(
        "/home/ci" to FsListResponse(
            path = "/home/ci",
            parent = "/home",
            directories = listOf(
                FsDirectoryEntry("projects", "/home/ci/projects"),
                FsDirectoryEntry("scripts", "/home/ci/scripts"),
            ),
        ),
        "/home/ci/projects" to FsListResponse(
            path = "/home/ci/projects",
            parent = "/home/ci",
            directories = listOf(
                FsDirectoryEntry("agents-term", "/home/ci/projects/agents-term"),
            ),
        ),
        "/home/ci/projects/agents-term" to FsListResponse(
            path = "/home/ci/projects/agents-term",
            parent = "/home/ci/projects",
            directories = emptyList(),
        ),
        "/home" to FsListResponse(
            path = "/home",
            parent = "/",
            directories = listOf(
                FsDirectoryEntry("ci", "/home/ci"),
            ),
        ),
    )

    fun deleteDirectory(path: String) {
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        synchronized(lock) {
            fsListings.keys.filter { it == normalized || it.startsWith("$normalized/") }
                .forEach { fsListings.remove(it) }
            val parent = normalized.substringBeforeLast('/').ifBlank { "/" }
            val parentListing = fsListings[parent]
            if (parentListing != null) {
                fsListings[parent] = parentListing.copy(
                    directories = parentListing.directories.filterNot {
                        it.path.trimEnd('/') == normalized
                    },
                )
            }
        }
    }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                val resp = handleRequest(request)
                recordedRequests.add("  -> ${resp.status}")
                resp
            } catch (t: Throwable) {
                recordedRequests.add("  -> EXCEPTION: $t")
                throw t
            }
        }
    }

    val host: String = "127.0.0.1"
    val port: Int
        get() = server.port

    fun start() {
        server.start(InetAddress.getByName(host), 0)
    }

    fun pairingUri(scheme: String = "agentsterm"): String = "$scheme://connect?host=$host&port=$port&token=$token"

    private fun handleRequest(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val method = request.method.orEmpty().uppercase()
        recordedRequests.add("$method ${request.path.orEmpty()} (url=${request.requestUrl})")

        if (!isAuthorized(request, path)) {
            return jsonResponse(401, """{"error":"Invalid bearer token"}""")
        }

        if (path == "/ws") {
            return handleWebSocketUpgrade()
        }

        if (method == "GET" && path == "/api/info") {
            return jsonResponse(200, encodeHerdrJson(serverInfo))
        }

        val agentModelsMatch = Regex("^/api/agents/([^/]+)/models$").matchEntire(path)
        if (method == "GET" && agentModelsMatch != null) {
            val agentType = agentModelsMatch.groupValues[1]
            val found = serverInfo.agents.firstOrNull { it.type == agentType }
            val resp = AgentModelsResponse(
                agent = agentType,
                defaultModel = found?.defaultModel,
                models = found?.models.orEmpty(),
            )
            return jsonResponse(200, encodeHerdrJson(resp))
        }

        if (method == "GET" && path == "/api/fs") {
            val requestedPath = request.requestUrl?.queryParameter("path") ?: serverInfo.defaultCwd
            val listing = synchronized(lock) {
                fsListings[requestedPath]
            } ?: FsListResponse(
                path = requestedPath,
                parent = requestedPath.trimEnd('/').substringBeforeLast('/').ifBlank { "/" },
                directories = emptyList(),
            )
            return jsonResponse(200, encodeHerdrJson(listing))
        }

        if (method == "POST" && path == "/api/debug/dumps") {
            val req = decodeHerdrJson<DebugDumpUploadRequest>(request.body.readUtf8())
            uploadedDebugDumps.add(req)
            val remoteDir = "/home/ci/.config/herdr-mobile/debug-dumps/20261124_${req.dumpId}"
            val resp = DebugDumpUploadResponse(
                ok = true,
                dumpId = req.dumpId,
                remoteDir = remoteDir,
                latestSymlink = "/home/ci/.config/herdr-mobile/debug-dumps/latest",
                files = listOf("summary.md", "meta.json", "state.json", "events.ndjson"),
            )
            return jsonResponse(200, encodeHerdrJson(resp))
        }

        if (method == "GET" && path == "/api/tabs") {
            val response = synchronized(lock) {
                TabsResponse(tabs = tabs.toList(), activeTabId = tabs.firstOrNull()?.id)
            }
            return jsonResponse(200, encodeHerdrJson(response))
        }

        if (method == "POST" && path == "/api/tabs") {
            val req = decodeHerdrJson<CreateTabRequest>(request.body.readUtf8())
            val id = "tab_${tabCounter.incrementAndGet()}"
            val cwd = req.cwd?.ifBlank { null } ?: serverInfo.defaultCwd
            val folder = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
            val defaultLabel = when (req.agent) {
                "agy" -> "Antigravity"
                "claude" -> "Claude"
                "codex" -> "Codex"
                "shell" -> "Shell"
                else -> req.agent
            }
            val now = System.currentTimeMillis()
            val tab = TabRecord(
                id = id,
                title = req.title?.ifBlank { null } ?: "$defaultLabel · $folder",
                agent = req.agent,
                cwd = cwd,
                permissionMode = req.permissionMode,
                model = req.model?.trim()?.ifEmpty { null },
                systemPrompt = if (req.agent == "shell") null else req.systemPrompt?.trim()?.ifEmpty { null },
                status = "idle",
                createdAt = now,
                updatedAt = now,
            )
            synchronized(lock) {
                tabs.add(tab)
                messagesByTab[id] = mutableListOf()
            }
            broadcast(WsServerEnvelope(type = "tab_created", tab = tab))
            return jsonResponse(200, encodeHerdrJson(SingleTabResponse(tab)))
        }

        val tabSubMatch = Regex("^/api/tabs/([^/]+)(?:/(messages|interrupt|read|queue)(?:/([^/]+))?)?$").matchEntire(path)
        if (tabSubMatch != null) {
            val tabId = tabSubMatch.groupValues[1]
            val subResource = tabSubMatch.groupValues[2]
            val itemId = tabSubMatch.groupValues[3]

            if (subResource.isEmpty()) {
                return when (method) {
                    "PATCH" -> handleUpdateTab(tabId, request.body.readUtf8())
                    "DELETE" -> handleDeleteTab(tabId)
                    else -> jsonResponse(405, """{"error":"Method not allowed"}""")
                }
            }

            return when (subResource) {
                "messages" -> when (method) {
                    "GET" -> handleGetMessages(tabId)
                    "POST" -> handleSendMessage(tabId, request.body.readUtf8())
                    else -> jsonResponse(405, """{"error":"Method not allowed"}""")
                }

                "queue" -> when (method) {
                    "DELETE" -> if (itemId.isNotEmpty()) {
                        handleRemoveQueuedMessage(tabId, itemId)
                    } else {
                        jsonResponse(404, """{"error":"Not found"}""")
                    }

                    else -> jsonResponse(405, """{"error":"Method not allowed"}""")
                }

                "interrupt" -> handleInterrupt(tabId, request.body.readUtf8())

                "read" -> handleMarkRead(tabId)

                else -> jsonResponse(404, """{"error":"Not found"}""")
            }
        }

        return jsonResponse(404, """{"error":"Unknown endpoint $method $path"}""")
    }

    private fun isAuthorized(request: RecordedRequest, path: String): Boolean {
        if (token.isBlank()) return true
        val authHeader = request.getHeader("Authorization")
        if (authHeader == "Bearer $token") return true
        if (path == "/ws" && request.requestUrl?.queryParameter("token") == token) return true
        return false
    }

    private fun handleWebSocketUpgrade(): MockResponse {
        webSocketUpgradeCount.incrementAndGet()
        return MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeSockets.add(webSocket)
                    val snapshot = synchronized(lock) {
                        WsServerEnvelope(
                            type = "snapshot",
                            server = serverInfo,
                            tabs = tabs.toList(),
                            activeTabId = tabs.firstOrNull()?.id,
                            ts = System.currentTimeMillis(),
                        )
                    }
                    webSocket.send(encodeHerdrJson(snapshot))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedAppFrames.add(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    activeSockets.remove(webSocket)
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    activeSockets.remove(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    activeSockets.remove(webSocket)
                }
            },
        )
    }

    private fun handleUpdateTab(tabId: String, body: String): MockResponse {
        val req = decodeHerdrJson<UpdateTabRequest>(body)
        val updated = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return jsonResponse(404, """{"error":"Tab not found"}""")
            val cur = tabs[idx]
            val nextModel = if (req.model != null) {
                req.model.trim().ifEmpty { null }
            } else {
                cur.model
            }
            val nextSystemPrompt = if (req.systemPrompt != null) {
                if (cur.agent == "shell") null else req.systemPrompt.trim().ifEmpty { null }
            } else {
                cur.systemPrompt
            }
            val next = cur.copy(
                title = req.title ?: cur.title,
                cwd = req.cwd ?: cur.cwd,
                permissionMode = req.permissionMode ?: cur.permissionMode,
                model = nextModel,
                systemPrompt = nextSystemPrompt,
                updatedAt = System.currentTimeMillis(),
            )
            tabs[idx] = next
            next
        }
        broadcast(WsServerEnvelope(type = "tab_updated", tab = updated))
        return jsonResponse(200, encodeHerdrJson(SingleTabResponse(updated)))
    }

    private fun handleDeleteTab(tabId: String): MockResponse {
        synchronized(lock) {
            tabs.removeAll { it.id == tabId }
            messagesByTab.remove(tabId)
        }
        broadcast(WsServerEnvelope(type = "tab_deleted", tabId = tabId))
        return jsonResponse(200, """{"ok":true}""")
    }

    private fun handleGetMessages(tabId: String): MockResponse {
        val resp = synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId }
                ?: return jsonResponse(404, """{"error":"Tab not found"}""")
            TabMessagesResponse(
                tab = tab,
                messages = messagesByTab[tabId].orEmpty().toList(),
            )
        }
        return jsonResponse(200, encodeHerdrJson(resp))
    }

    private fun handleSendMessage(tabId: String, body: String): MockResponse {
        val req = decodeHerdrJson<SendMessageRequest>(body)
        req.overridePermissionMode?.let { receivedOverrideModes.add(it) }

        val now = System.currentTimeMillis()
        val queuedResult = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return jsonResponse(404, """{"error":"Tab not found"}""")
            val cur = tabs[idx]
            if (cur.status == "working") {
                val item = QueuedMessage(
                    id = "q_${msgCounter.incrementAndGet()}",
                    text = req.text.trim(),
                    overridePermissionMode = req.overridePermissionMode,
                    createdAt = now,
                )
                val updated = cur.copy(
                    queuedMessages = cur.queuedMessages + item,
                    updatedAt = now,
                )
                tabs[idx] = updated
                Pair(updated, item)
            } else {
                null
            }
        }
        if (queuedResult != null) {
            val (updatedTab, item) = queuedResult
            broadcast(WsServerEnvelope(type = "tab_updated", tab = updatedTab))
            return jsonResponse(
                200,
                encodeHerdrJson(SendMessageResponse(queued = true, queuedMessage = item, tab = updatedTab)),
            )
        }

        val (workingTab, userMsg, assistantMsg, effectivePrompt) = startTurnInternal(
            tabId = tabId,
            promptText = req.text,
        ) ?: return jsonResponse(404, """{"error":"Tab not found"}""")

        executor.submit {
            emitTurnEvents(
                tab = workingTab,
                userMsg = userMsg,
                assistantMsg = assistantMsg,
                prompt = effectivePrompt,
                overridePermissionMode = req.overridePermissionMode,
            )
        }

        return jsonResponse(200, encodeHerdrJson(SendMessageResponse(userMessage = userMsg, assistantMessage = assistantMsg)))
    }

    private data class StartedTurn(
        val tab: TabRecord,
        val userMsg: ChatMessage,
        val assistantMsg: ChatMessage,
        val effectivePrompt: String,
    )

    private fun startTurnInternal(
        tabId: String,
        promptText: String,
    ): StartedTurn? {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return@synchronized null
            val cur = tabs[idx]
            val combinedText = if (cur.queuedMessages.isNotEmpty()) {
                (cur.queuedMessages.map { it.text } + promptText.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
            } else {
                promptText
            }
            val userMsg = ChatMessage(
                id = "msg_${msgCounter.incrementAndGet()}",
                tabId = tabId,
                role = "user",
                text = combinedText,
                status = "complete",
                createdAt = now,
                updatedAt = now,
            )
            val assistantMsg = ChatMessage(
                id = "msg_${msgCounter.incrementAndGet()}",
                tabId = tabId,
                role = "assistant",
                text = "",
                status = "streaming",
                createdAt = now + 1,
                updatedAt = now + 1,
            )
            val msgs = messagesByTab.getOrPut(tabId) { mutableListOf() }
            msgs.add(userMsg)
            msgs.add(assistantMsg)
            val updated = cur.copy(
                status = "working",
                statusDetail = "Processing prompt...",
                lastMessagePreview = combinedText,
                queuedMessages = emptyList(),
                updatedAt = now,
            )
            tabs[idx] = updated
            StartedTurn(updated, userMsg, assistantMsg, combinedText)
        }
    }

    private fun handleRemoveQueuedMessage(tabId: String, queuedId: String): MockResponse {
        val updated = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return jsonResponse(404, """{"error":"Tab not found"}""")
            val cur = tabs[idx]
            val next = cur.copy(
                queuedMessages = cur.queuedMessages.filterNot { it.id == queuedId },
                updatedAt = System.currentTimeMillis(),
            )
            tabs[idx] = next
            next
        }
        broadcast(WsServerEnvelope(type = "tab_updated", tab = updated))
        return jsonResponse(200, encodeHerdrJson(QueueActionResponse(ok = true, tab = updated)))
    }

    private fun emitTurnEvents(
        tab: TabRecord,
        userMsg: ChatMessage,
        assistantMsg: ChatMessage,
        prompt: String,
        overridePermissionMode: String?,
    ) {
        broadcast(WsServerEnvelope(type = "message_created", tabId = tab.id, message = userMsg))
        broadcast(WsServerEnvelope(type = "message_created", tabId = tab.id, message = assistantMsg))
        broadcast(WsServerEnvelope(type = "tab_updated", tab = tab))

        if (prompt.contains("hold_working")) {
            // Stay in working state until client calls POST /api/tabs/:id/interrupt
            return
        }

        if (prompt.contains("simulate_empty_response")) {
            // Reproduces the dump scenario where a daemon finishes a turn with empty text and no error
            val emptyComplete = assistantMsg.copy(
                text = "",
                status = "complete",
                usage = UsageStats(durationMs = 320L),
                updatedAt = System.currentTimeMillis(),
            )
            replaceMessage(tab.id, emptyComplete)
            val idleTab = updateTabState(tab.id, status = "idle", statusDetail = null)
            broadcast(WsServerEnvelope(type = "message_updated", tabId = tab.id, message = emptyComplete))
            if (idleTab != null) {
                broadcast(WsServerEnvelope(type = "tab_updated", tab = idleTab))
            }
            return
        }

        if (prompt.contains("multi_tool_turn")) {
            val step1 = "Checking repository state first."
            broadcast(
                WsServerEnvelope(
                    type = "message_delta",
                    tabId = tab.id,
                    messageId = assistantMsg.id,
                    textDelta = step1,
                ),
            )
            val tool1 = ToolCallItem(
                id = "tool_${assistantMsg.id}_1",
                name = "Bash",
                inputSummary = "Check git status",
                inputJson = """{"command":"cd ~/projects/agents-term && git status","description":"Check git status"}""",
                output = "On branch main",
                status = "done",
                durationMs = 110L,
            )
            val tool2 = ToolCallItem(
                id = "tool_${assistantMsg.id}_2",
                name = "Bash",
                inputSummary = "git diff --stat",
                inputJson = """{"command":"cd ~/projects/agents-term && git diff --stat"}""",
                output = "0 files changed",
                status = "done",
                durationMs = 140L,
            )
            val tool3 = ToolCallItem(
                id = "tool_${assistantMsg.id}_3",
                name = "Bash",
                inputSummary = "Run unit tests",
                inputJson = """{"command":"cd ~/projects/agents-term && pytest","description":"Run unit tests"}""",
                output = "28 passed",
                status = "done",
                durationMs = 350L,
            )
            for (tc in listOf(tool1, tool2, tool3)) {
                broadcast(
                    WsServerEnvelope(
                        type = "tool_update",
                        tabId = tab.id,
                        messageId = assistantMsg.id,
                        toolCall = tc.copy(status = "running", output = null, durationMs = null),
                    ),
                )
                broadcast(
                    WsServerEnvelope(
                        type = "tool_update",
                        tabId = tab.id,
                        messageId = assistantMsg.id,
                        toolCall = tc,
                    ),
                )
            }
            val step2 = "All repository checks passed."
            broadcast(
                WsServerEnvelope(
                    type = "message_delta",
                    tabId = tab.id,
                    messageId = assistantMsg.id,
                    textDelta = "\n\n$step2",
                ),
            )
            val completeMsg = assistantMsg.copy(
                text = "$step1\n\n$step2",
                toolCalls = listOf(tool1, tool2, tool3),
                segments = listOf(
                    MessageSegment("text", text = step1),
                    MessageSegment("tool", toolCallId = tool1.id),
                    MessageSegment("tool", toolCallId = tool2.id),
                    MessageSegment("tool", toolCallId = tool3.id),
                    MessageSegment("text", text = step2),
                ),
                status = "complete",
                usage = UsageStats(inputTokens = 210, outputTokens = 64, durationMs = 720L),
                updatedAt = System.currentTimeMillis(),
            )
            replaceMessage(tab.id, completeMsg)
            val idleTab = updateTabState(tab.id, status = "idle", statusDetail = null)
            broadcast(WsServerEnvelope(type = "message_updated", tabId = tab.id, message = completeMsg))
            if (idleTab != null) {
                broadcast(WsServerEnvelope(type = "tab_updated", tab = idleTab))
            }
            return
        }

        if (prompt.contains("needs_permission") && overridePermissionMode != "full-access") {
            val blockedMsg = assistantMsg.copy(
                text = "I need permission before running the command.",
                deniedActions = listOf(
                    DeniedAction(
                        action = "bash",
                        displayName = "Run Shell Command",
                        detail = "gradle test",
                    ),
                ),
                status = "complete",
                updatedAt = System.currentTimeMillis(),
            )
            val blockedTab = updateTabState(tab.id, status = "blocked", statusDetail = "Waiting for approval")
            replaceMessage(tab.id, blockedMsg)
            broadcast(WsServerEnvelope(type = "message_updated", tabId = tab.id, message = blockedMsg))
            if (blockedTab != null) {
                broadcast(WsServerEnvelope(type = "tab_updated", tab = blockedTab))
            }
            return
        }

        val thinkingText = "Inspecting repository context for: $prompt"
        broadcast(
            WsServerEnvelope(
                type = "message_delta",
                tabId = tab.id,
                messageId = assistantMsg.id,
                thinkingDelta = thinkingText,
            ),
        )

        val runningTool = ToolCallItem(
            id = "tool_${assistantMsg.id}",
            name = "Bash",
            inputSummary = "git status",
            inputJson = """{"command":"git status"}""",
            status = "running",
        )
        broadcast(
            WsServerEnvelope(
                type = "tool_update",
                tabId = tab.id,
                messageId = assistantMsg.id,
                toolCall = runningTool,
            ),
        )

        val doneTool = runningTool.copy(
            output = "On branch main\nnothing to commit",
            status = "done",
            durationMs = 120L,
        )
        broadcast(
            WsServerEnvelope(
                type = "tool_update",
                tabId = tab.id,
                messageId = assistantMsg.id,
                toolCall = doneTool,
            ),
        )

        val chunk1 = if (overridePermissionMode == "full-access") {
            "Full access granted. "
        } else {
            "Repository is clean on "
        }
        val chunk2 = if (overridePermissionMode == "full-access") {
            "Command completed successfully."
        } else {
            "main branch."
        }
        val fullText = chunk1 + chunk2

        broadcast(
            WsServerEnvelope(
                type = "message_delta",
                tabId = tab.id,
                messageId = assistantMsg.id,
                textDelta = chunk1,
            ),
        )
        broadcast(
            WsServerEnvelope(
                type = "message_delta",
                tabId = tab.id,
                messageId = assistantMsg.id,
                textDelta = chunk2,
            ),
        )

        val completeMsg = assistantMsg.copy(
            text = fullText,
            thinking = thinkingText,
            toolCalls = listOf(doneTool),
            status = "complete",
            usage = UsageStats(inputTokens = 120, outputTokens = 45, durationMs = 450L),
            updatedAt = System.currentTimeMillis(),
        )
        replaceMessage(tab.id, completeMsg)
        val idleTab = updateTabState(tab.id, status = "idle", statusDetail = null)
        broadcast(WsServerEnvelope(type = "message_updated", tabId = tab.id, message = completeMsg))
        if (idleTab != null) {
            broadcast(WsServerEnvelope(type = "tab_updated", tab = idleTab))
        }
    }

    private fun handleInterrupt(tabId: String, body: String): MockResponse {
        val req = if (body.isNotBlank()) {
            runCatching { decodeHerdrJson<InterruptTabRequest>(body) }.getOrDefault(InterruptTabRequest())
        } else {
            InterruptTabRequest()
        }
        interruptedTabIds.add(tabId)
        val interruptedMsg = synchronized(lock) {
            val list = messagesByTab[tabId] ?: return@synchronized null
            val idx = list.indexOfLast { it.role == "assistant" && it.status == "streaming" }
            if (idx < 0) return@synchronized null
            val updated = list[idx].copy(
                status = "interrupted",
                updatedAt = System.currentTimeMillis(),
            )
            list[idx] = updated
            updated
        }
        if (interruptedMsg != null) {
            broadcast(WsServerEnvelope(type = "message_updated", tabId = tabId, message = interruptedMsg))
        }

        val combinedToRun: Pair<String, String?>? = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return@synchronized null
            val cur = tabs[idx]
            val draftText = req.text?.trim()?.takeIf { it.isNotEmpty() }
            val allQueued = if (draftText != null) {
                cur.queuedMessages + QueuedMessage(
                    id = "q_${msgCounter.incrementAndGet()}",
                    text = draftText,
                    overridePermissionMode = req.overridePermissionMode,
                    createdAt = System.currentTimeMillis(),
                )
            } else {
                cur.queuedMessages
            }
            if (req.processQueue && allQueued.isNotEmpty()) {
                val combined = allQueued.joinToString("\n\n") { it.text }
                val override = allQueued.mapNotNull { it.overridePermissionMode }.lastOrNull()
                tabs[idx] = cur.copy(queuedMessages = emptyList(), updatedAt = System.currentTimeMillis())
                Pair(combined, override)
            } else {
                tabs[idx] = cur.copy(
                    queuedMessages = allQueued,
                    status = "idle",
                    statusDetail = null,
                    updatedAt = System.currentTimeMillis(),
                )
                null
            }
        }

        if (combinedToRun != null) {
            val (combinedPrompt, overrideMode) = combinedToRun
            val started = startTurnInternal(tabId, combinedPrompt)
            if (started != null) {
                executor.submit {
                    emitTurnEvents(
                        tab = started.tab,
                        userMsg = started.userMsg,
                        assistantMsg = started.assistantMsg,
                        prompt = started.effectivePrompt,
                        overridePermissionMode = overrideMode,
                    )
                }
                return jsonResponse(200, encodeHerdrJson(QueueActionResponse(ok = true, tab = started.tab)))
            }
        }

        val idleTab = synchronized(lock) { tabs.firstOrNull { it.id == tabId } }
        if (idleTab != null) {
            broadcast(WsServerEnvelope(type = "tab_updated", tab = idleTab))
        }
        return jsonResponse(200, encodeHerdrJson(QueueActionResponse(ok = true, tab = idleTab)))
    }

    private fun handleMarkRead(tabId: String): MockResponse {
        markedReadTabIds.add(tabId)
        synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx >= 0) {
                tabs[idx] = tabs[idx].copy(unread = false)
            }
        }
        return jsonResponse(200, """{"ok":true}""")
    }

    private fun updateTabState(tabId: String, status: String, statusDetail: String?): TabRecord? {
        return synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return null
            val next = tabs[idx].copy(
                status = status,
                statusDetail = statusDetail,
                updatedAt = System.currentTimeMillis(),
            )
            tabs[idx] = next
            next
        }
    }

    private fun replaceMessage(tabId: String, updated: ChatMessage) {
        synchronized(lock) {
            val list = messagesByTab.getOrPut(tabId) { mutableListOf() }
            val idx = list.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                list[idx] = updated
            } else {
                list.add(updated)
            }
        }
    }

    private fun broadcast(envelope: WsServerEnvelope) {
        val json = encodeHerdrJson(envelope)
        for (ws in activeSockets) {
            ws.send(json)
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .addHeader("Connection", "close")
        .setBody(body)

    override fun close() {
        for (ws in activeSockets) {
            runCatching { ws.close(1000, "test finished") }
        }
        activeSockets.clear()
        executor.shutdownNow()
        runCatching { server.shutdown() }
    }
}
