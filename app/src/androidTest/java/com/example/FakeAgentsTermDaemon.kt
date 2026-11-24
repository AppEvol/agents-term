package com.example

import com.example.data.AvailableAgentInfo
import com.example.data.ChatMessage
import com.example.data.CreateTabRequest
import com.example.data.DeniedAction
import com.example.data.FsDirectoryEntry
import com.example.data.FsListResponse
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

    private val serverInfo = ServerInfo(
        version = "0.1.0-e2e",
        hostname = hostname,
        tailscaleIp = "127.0.0.1",
        defaultCwd = "/home/ci",
        agents = listOf(
            AvailableAgentInfo("agy", "Antigravity", "Antigravity CLI", "agy", true),
            AvailableAgentInfo("claude", "Claude Code", "Claude CLI", "claude", true),
            AvailableAgentInfo("codex", "Codex", "Codex CLI", "codex", true),
            AvailableAgentInfo("shell", "Shell", "Persistent bash", "bash", true),
        ),
    )

    private val fsListings = mapOf(
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

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleRequest(request)
            }
        }
    }

    val host: String = "127.0.0.1"
    val port: Int
        get() = server.port

    fun start() {
        server.start(InetAddress.getByName(host), 0)
    }

    fun pairingUri(scheme: String = "agentsterm"): String {
        return "$scheme://connect?host=$host&port=$port&token=$token"
    }

    private fun handleRequest(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val method = request.method.orEmpty().uppercase()

        if (!isAuthorized(request, path)) {
            return jsonResponse(401, """{"error":"Invalid bearer token"}""")
        }

        if (path == "/ws") {
            return handleWebSocketUpgrade()
        }

        if (method == "GET" && path == "/api/info") {
            return jsonResponse(200, encodeHerdrJson(serverInfo))
        }

        if (method == "GET" && path == "/api/fs") {
            val requestedPath = request.requestUrl?.queryParameter("path") ?: serverInfo.defaultCwd
            val listing = fsListings[requestedPath] ?: FsListResponse(
                path = requestedPath,
                parent = "/home/ci",
                directories = emptyList(),
            )
            return jsonResponse(200, encodeHerdrJson(listing))
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

        val tabSubMatch = Regex("^/api/tabs/([^/]+)(?:/(messages|interrupt|read))?$").matchEntire(path)
        if (tabSubMatch != null) {
            val tabId = tabSubMatch.groupValues[1]
            val subResource = tabSubMatch.groupValues[2]

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
                "interrupt" -> handleInterrupt(tabId)
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
            }
        )
    }

    private fun handleUpdateTab(tabId: String, body: String): MockResponse {
        val req = decodeHerdrJson<UpdateTabRequest>(body)
        val updated = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return jsonResponse(404, """{"error":"Tab not found"}""")
            val cur = tabs[idx]
            val next = cur.copy(
                title = req.title ?: cur.title,
                cwd = req.cwd ?: cur.cwd,
                permissionMode = req.permissionMode ?: cur.permissionMode,
                model = req.model ?: cur.model,
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
        val userMsg = ChatMessage(
            id = "msg_${msgCounter.incrementAndGet()}",
            tabId = tabId,
            role = "user",
            text = req.text,
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

        val workingTab = synchronized(lock) {
            val idx = tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return jsonResponse(404, """{"error":"Tab not found"}""")
            val msgs = messagesByTab.getOrPut(tabId) { mutableListOf() }
            msgs.add(userMsg)
            msgs.add(assistantMsg)
            val updated = tabs[idx].copy(
                status = "working",
                statusDetail = "Processing prompt...",
                lastMessagePreview = req.text,
                updatedAt = now,
            )
            tabs[idx] = updated
            updated
        }

        executor.submit {
            emitTurnEvents(
                tab = workingTab,
                userMsg = userMsg,
                assistantMsg = assistantMsg,
                prompt = req.text,
                overridePermissionMode = req.overridePermissionMode,
            )
        }

        return jsonResponse(200, encodeHerdrJson(SendMessageResponse(userMsg, assistantMsg)))
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

        if (prompt.contains("needs_permission") && overridePermissionMode != "full-access") {
            val blockedMsg = assistantMsg.copy(
                text = "I need permission before running the command.",
                deniedActions = listOf(
                    DeniedAction(
                        action = "bash",
                        displayName = "Run Shell Command",
                        detail = "gradle test",
                    )
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
            )
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
            )
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
            )
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
            )
        )
        broadcast(
            WsServerEnvelope(
                type = "message_delta",
                tabId = tab.id,
                messageId = assistantMsg.id,
                textDelta = chunk2,
            )
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

    private fun handleInterrupt(tabId: String): MockResponse {
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
        val idleTab = updateTabState(tabId, status = "idle", statusDetail = null)
        if (interruptedMsg != null) {
            broadcast(WsServerEnvelope(type = "message_updated", tabId = tabId, message = interruptedMsg))
        }
        if (idleTab != null) {
            broadcast(WsServerEnvelope(type = "tab_updated", tab = idleTab))
        }
        return jsonResponse(200, """{"ok":true}""")
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

    private fun jsonResponse(code: Int, body: String): MockResponse {
        return MockResponse()
            .setResponseCode(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
    }

    override fun close() {
        for (ws in activeSockets) {
            runCatching { ws.close(1000, "test finished") }
        }
        activeSockets.clear()
        executor.shutdownNow()
        runCatching { server.shutdown() }
    }
}
