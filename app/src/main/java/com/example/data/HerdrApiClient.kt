package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class HerdrApiClient(
    private val debugRecorder: DebugRecorder? = null,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.withAuth(config: ConnectionConfig): Request.Builder {
        if (config.token.isNotBlank()) {
            header("Authorization", "Bearer ${config.token.trim()}")
        }
        return this
    }

    private suspend fun executeRawJson(
        request: Request,
        logPayload: Boolean = true,
    ): String =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val endpoint = "${request.method} ${request.url.encodedPath}"
            try {
                client.newCall(request).execute().use { resp ->
                    val durationMs = System.currentTimeMillis() - startMs
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val parsedErr = runCatching {
                            decodeHerdrJson<ErrorResponse>(bodyStr).error
                        }.getOrNull()
                        val errMsg = parsedErr ?: "HTTP ${resp.code}: ${resp.message}"
                        debugRecorder?.record(
                            category = "http",
                            event = "http_error",
                            detail = "$endpoint -> ${resp.code} (${durationMs}ms): $errMsg",
                            payload = if (logPayload) bodyStr else null,
                        )
                        throw IOException(errMsg)
                    }
                    debugRecorder?.record(
                        category = "http",
                        event = "http_ok",
                        detail = "$endpoint -> ${resp.code} (${durationMs}ms, ${bodyStr.length}B)",
                    )
                    bodyStr
                }
            } catch (err: Throwable) {
                val durationMs = System.currentTimeMillis() - startMs
                if (err !is IOException || err.message?.startsWith("HTTP ") != true) {
                    debugRecorder?.record(
                        category = "http",
                        event = "http_exception",
                        detail = "$endpoint failed (${durationMs}ms): ${err.message ?: err::class.java.simpleName}",
                    )
                }
                throw err
            }
        }

    private suspend inline fun <reified T> executeJson(
        request: Request,
        logPayload: Boolean = true,
    ): T {
        val bodyStr = executeRawJson(request, logPayload)
        return decodeHerdrJson(bodyStr)
    }

    suspend fun getInfo(config: ConnectionConfig): ServerInfo {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/info")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    suspend fun listTabs(config: ConnectionConfig): TabsResponse {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    suspend fun createTab(config: ConnectionConfig, body: CreateTabRequest): TabRecord {
        val json = encodeHerdrJson(body)
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson<SingleTabResponse>(req).tab
    }

    suspend fun updateTab(config: ConnectionConfig, tabId: String, body: UpdateTabRequest): TabRecord {
        val json = encodeHerdrJson(body)
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId")
            .withAuth(config)
            .patch(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson<SingleTabResponse>(req).tab
    }

    suspend fun deleteTab(config: ConnectionConfig, tabId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId")
            .withAuth(config)
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Failed to delete tab (HTTP ${resp.code})")
            }
        }
    }

    open suspend fun getTabMessages(config: ConnectionConfig, tabId: String): TabMessagesResponse {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/messages")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    suspend fun sendMessage(
        config: ConnectionConfig,
        tabId: String,
        text: String,
        overridePermissionMode: String? = null,
    ): SendMessageResponse {
        val json = encodeHerdrJson(
            SendMessageRequest(text = text, overridePermissionMode = overridePermissionMode)
        )
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/messages")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson(req)
    }

    suspend fun interruptTab(config: ConnectionConfig, tabId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/interrupt")
            .withAuth(config)
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Failed to interrupt tab (HTTP ${resp.code})")
            }
        }
    }

    suspend fun markRead(config: ConnectionConfig, tabId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/read")
            .withAuth(config)
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        client.newCall(req).execute().use { }
    }

    suspend fun listDirectories(config: ConnectionConfig, path: String?): FsListResponse {
        val base = "${config.httpBaseUrl()}/api/fs".toHttpUrlOrNull()
            ?: throw IOException("Invalid host URL")
        val url = base.newBuilder().apply {
            if (!path.isNullOrBlank()) addQueryParameter("path", path)
        }.build()
        val req = Request.Builder()
            .url(url)
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    suspend fun uploadDebugDump(
        config: ConnectionConfig,
        body: DebugDumpUploadRequest,
    ): DebugDumpUploadResponse {
        val json = encodeHerdrJson(body)
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/debug/dumps")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson(req, logPayload = false)
    }

    open fun openWebSocket(
        config: ConnectionConfig,
        onOpen: () -> Unit,
        onEvent: (WsServerEnvelope) -> Unit,
        onClosed: (String?) -> Unit,
    ): WebSocket {
        val req = Request.Builder()
            .url(config.wsUrl())
            .withAuth(config)
            .build()

        debugRecorder?.record(
            category = "ws",
            event = "ws_connecting",
            detail = "Opening WebSocket to ${config.host}:${config.port}/ws",
        )

        val closedNotified = AtomicBoolean(false)
        fun notifyClosedOnce(reason: String?) {
            if (closedNotified.compareAndSet(false, true)) {
                onClosed(reason)
            }
        }

        return client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    debugRecorder?.record(
                        category = "ws",
                        event = "ws_open",
                        detail = "WebSocket connected (HTTP ${response.code})",
                    )
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { decodeHerdrJson<WsServerEnvelope>(text) }
                        .onSuccess { env ->
                            val summary = buildString {
                                append("type=${env.type}")
                                if (!env.tabId.isNullOrBlank()) append(" tab=${env.tabId}")
                                if (!env.messageId.isNullOrBlank()) append(" msg=${env.messageId}")
                                if (env.tab != null) append(" tabStatus=${env.tab.status}")
                                if (env.message != null) append(" msgStatus=${env.message.status}")
                                if (env.textDelta != null) append(" textDeltaLen=${env.textDelta.length}")
                                if (env.thinkingDelta != null) append(" thinkingDeltaLen=${env.thinkingDelta.length}")
                                if (env.toolCall != null) append(" tool=${env.toolCall.name}:${env.toolCall.status}")
                            }
                            debugRecorder?.record(
                                category = "ws",
                                event = "ws_event",
                                detail = summary,
                                payload = if (env.type == "message_delta") null else text,
                            )
                            onEvent(env)
                        }
                        .onFailure { err ->
                            debugRecorder?.record(
                                category = "error",
                                event = "ws_decode_failed",
                                detail = "Failed to decode WS frame: ${err.message ?: err::class.java.simpleName}",
                                payload = text,
                            )
                        }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    debugRecorder?.record(
                        category = "ws",
                        event = "ws_closing",
                        detail = "WebSocket closing (code=$code, reason=${reason.ifBlank { "none" }})",
                    )
                    webSocket.close(1000, null)
                    notifyClosedOnce(reason.ifBlank { null })
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    debugRecorder?.record(
                        category = "ws",
                        event = "ws_closed",
                        detail = "WebSocket closed (code=$code, reason=${reason.ifBlank { "none" }})",
                    )
                    notifyClosedOnce(reason.ifBlank { null })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val codeMsg = response?.let { "HTTP ${it.code}: ${it.message}" }
                    val reason = codeMsg ?: t.message ?: "Connection lost"
                    debugRecorder?.record(
                        category = "error",
                        event = "ws_failure",
                        detail = "WebSocket failure: $reason",
                    )
                    notifyClosedOnce(reason)
                }
            }
        )
    }
}
