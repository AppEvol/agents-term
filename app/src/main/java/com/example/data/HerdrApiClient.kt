package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val HTTP_UNAUTHORIZED = 401
const val HTTP_FORBIDDEN = 403
const val HTTP_NOT_FOUND = 404

fun isAuthStatusCode(statusCode: Int?): Boolean = statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN

class HttpStatusException(
    val statusCode: Int,
    message: String,
    val isUnknownEndpoint: Boolean = isUnknownEndpointResponse(statusCode, message),
) : IOException(message)

val Throwable.isAuthError: Boolean
    get() = this is HttpStatusException && isAuthStatusCode(statusCode)

val Throwable.isUnknownEndpointError: Boolean
    get() = this is HttpStatusException && isUnknownEndpoint

internal fun recordWebSocketFailure(
    debugRecorder: DebugRecorder?,
    reason: String,
    httpStatusCode: Int?,
    isInForeground: Boolean,
) {
    if (debugRecorder == null) return
    if (!isInForeground && !isAuthStatusCode(httpStatusCode)) {
        debugRecorder.record(
            category = "ws",
            event = "ws_background_closed",
            detail = "WebSocket closed while backgrounded: $reason",
        )
    } else {
        debugRecorder.record(
            category = "error",
            event = "ws_failure",
            detail = "WebSocket failure: $reason",
        )
    }
}

open class HerdrApiClient(
    private val debugRecorder: DebugRecorder? = null,
) {
    @Volatile
    internal var isAppInForeground: () -> Boolean = { true }
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
    ): String = withContext(Dispatchers.IO) {
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
                    if (isUnknownEndpointResponse(resp.code, errMsg)) {
                        throw HttpStatusException(
                            statusCode = resp.code,
                            message = OUTDATED_DAEMON_ENDPOINT_ERROR,
                            isUnknownEndpoint = true,
                        )
                    }
                    throw HttpStatusException(resp.code, errMsg)
                }
                debugRecorder?.record(
                    category = "http",
                    event = "http_ok",
                    detail = "$endpoint -> ${resp.code} (${durationMs}ms, ${bodyStr.length}B)",
                )
                bodyStr
            }
        } catch (err: HttpStatusException) {
            throw err
        } catch (err: Throwable) {
            val durationMs = System.currentTimeMillis() - startMs
            debugRecorder?.record(
                category = "http",
                event = "http_exception",
                detail = "$endpoint failed (${durationMs}ms): ${err.message ?: err::class.java.simpleName}",
            )
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

    open suspend fun getDaemonUpdateStatus(config: ConnectionConfig): DaemonUpdateStatus {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/daemon/update")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    open suspend fun startDaemonUpdate(
        config: ConnectionConfig,
        body: DaemonUpdateRequest,
    ): DaemonUpdateStatus {
        val json = encodeHerdrJson(body)
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/daemon/update")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson(req)
    }

    open suspend fun cancelDaemonUpdate(config: ConnectionConfig): DaemonUpdateStatus {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/daemon/update")
            .withAuth(config)
            .delete()
            .build()
        return executeJson(req)
    }

    open suspend fun rollbackDaemon(
        config: ConnectionConfig,
        body: DaemonRollbackRequest = DaemonRollbackRequest(),
    ): DaemonUpdateStatus {
        val json = encodeHerdrJson(body)
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/daemon/rollback")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson(req)
    }

    open suspend fun getAgentModels(
        config: ConnectionConfig,
        agent: String,
        refresh: Boolean = false,
    ): AgentModelsResponse {
        val base = "${config.httpBaseUrl()}/api/agents/$agent/models".toHttpUrlOrNull()
            ?: throw IOException("Invalid host URL")
        val url = base.newBuilder().apply {
            if (refresh) addQueryParameter("refresh", "1")
        }.build()
        val req = Request.Builder()
            .url(url)
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    open suspend fun getAgentUsage(
        config: ConnectionConfig,
        agent: String,
    ): AgentUsageResponse {
        val url = "${config.httpBaseUrl()}/api/agents/$agent/usage".toHttpUrlOrNull()
            ?: throw IOException("Invalid host URL")
        val req = Request.Builder()
            .url(url)
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

    open suspend fun createTab(config: ConnectionConfig, body: CreateTabRequest): TabRecord {
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

    open suspend fun deleteTab(config: ConnectionConfig, tabId: String): DeleteTabResponse? {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId")
            .withAuth(config)
            .delete()
            .build()
        val bodyStr = executeRawJson(req)
        return bodyStr.takeIf { it.isNotBlank() }?.let {
            runCatching { decodeHerdrJson<DeleteTabResponse>(it) }.getOrNull()
        }
    }

    open suspend fun listClosedTabs(config: ConnectionConfig): ClosedTabsResponse {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/closed")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    open suspend fun reviveClosedTab(config: ConnectionConfig, tabId: String): ReviveTabResponse {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/closed/$tabId/revive")
            .withAuth(config)
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        return executeJson(req)
    }

    open suspend fun getTabMessages(config: ConnectionConfig, tabId: String): TabMessagesResponse {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/messages")
            .withAuth(config)
            .get()
            .build()
        return executeJson(req)
    }

    open suspend fun sendMessage(
        config: ConnectionConfig,
        tabId: String,
        text: String,
        overridePermissionMode: String? = null,
        attachmentIds: List<String> = emptyList(),
    ): SendMessageResponse {
        val json = encodeHerdrJson(
            SendMessageRequest(
                text = text,
                overridePermissionMode = overridePermissionMode,
                attachments = attachmentIds.takeIf { it.isNotEmpty() },
            ),
        )
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/messages")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson(req)
    }

    open suspend fun sendStdin(
        config: ConnectionConfig,
        tabId: String,
        data: String? = null,
        eof: Boolean = false,
    ) {
        val json = encodeHerdrJson(SendStdinRequest(data = data, eof = eof))
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/stdin")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        executeRawJson(req)
    }

    /** Stores an image on the VM for [tabId]; a message then refers to it by the returned id. */
    open suspend fun uploadAttachment(
        config: ConnectionConfig,
        tabId: String,
        bytes: ByteArray,
        mimeType: String,
    ): MessageAttachment {
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/attachments")
            .withAuth(config)
            .post(bytes.toRequestBody(mediaType))
            .build()
        return executeJson<UploadAttachmentResponse>(req, logPayload = false).attachment
    }

    open suspend fun downloadAttachment(
        config: ConnectionConfig,
        tabId: String,
        attachmentId: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/attachments/$attachmentId")
            .withAuth(config)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}: ${resp.message}")
            resp.body?.bytes() ?: throw IOException("Empty attachment response")
        }
    }

    open suspend fun interruptTab(
        config: ConnectionConfig,
        tabId: String,
        processQueue: Boolean = false,
        text: String? = null,
        overridePermissionMode: String? = null,
        attachmentIds: List<String> = emptyList(),
    ): TabRecord? {
        val json = encodeHerdrJson(
            InterruptTabRequest(
                processQueue = processQueue,
                text = text?.trim()?.takeIf { it.isNotEmpty() },
                overridePermissionMode = overridePermissionMode,
                attachments = attachmentIds.takeIf { it.isNotEmpty() },
            ),
        )
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/interrupt")
            .withAuth(config)
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return executeJson<QueueActionResponse>(req).tab
    }

    open suspend fun removeQueuedMessage(
        config: ConnectionConfig,
        tabId: String,
        queuedId: String,
    ): TabRecord? {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/queue/$queuedId")
            .withAuth(config)
            .delete()
            .build()
        return executeJson<QueueActionResponse>(req).tab
    }

    open suspend fun markRead(config: ConnectionConfig, tabId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.httpBaseUrl()}/api/tabs/$tabId/read")
            .withAuth(config)
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        client.newCall(req).execute().use { }
    }

    open suspend fun listDirectories(config: ConnectionConfig, path: String?): FsListResponse {
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
        onClosed: (reason: String?, httpStatusCode: Int?) -> Unit,
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
        val deltaLogAggregator = WsDeltaLogAggregator(debugRecorder)
        fun notifyClosedOnce(reason: String?, httpStatusCode: Int? = null) {
            if (closedNotified.compareAndSet(false, true)) {
                onClosed(reason, httpStatusCode)
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
                            if (env.type == "message_delta") {
                                deltaLogAggregator.recordDelta(env)
                            } else {
                                if (env.type == "message_updated") {
                                    deltaLogAggregator.flush()
                                }
                                if (debugRecorder != null) {
                                    val summary = buildString {
                                        append("type=${env.type}")
                                        if (!env.tabId.isNullOrBlank()) append(" tab=${env.tabId}")
                                        if (!env.messageId.isNullOrBlank()) append(" msg=${env.messageId}")
                                        if (env.tab != null) append(" tabStatus=${env.tab.status}")
                                        if (env.message != null) append(" msgStatus=${env.message.status}")
                                        if (env.toolCall != null) append(" tool=${env.toolCall.name}:${env.toolCall.status}")
                                    }
                                    debugRecorder.record(
                                        category = "ws",
                                        event = "ws_event",
                                        detail = summary,
                                        payload = text,
                                    )
                                }
                            }
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
                    deltaLogAggregator.flush()
                    debugRecorder?.record(
                        category = "ws",
                        event = "ws_closing",
                        detail = "WebSocket closing (code=$code, reason=${reason.ifBlank { "none" }})",
                    )
                    webSocket.close(1000, null)
                    notifyClosedOnce(reason.ifBlank { null })
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    deltaLogAggregator.flush()
                    debugRecorder?.record(
                        category = "ws",
                        event = "ws_closed",
                        detail = "WebSocket closed (code=$code, reason=${reason.ifBlank { "none" }})",
                    )
                    notifyClosedOnce(reason.ifBlank { null })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    deltaLogAggregator.flush()
                    val codeMsg = response?.let { "HTTP ${it.code}: ${it.message}" }
                    val reason = codeMsg ?: t.message ?: "Connection lost"
                    recordWebSocketFailure(
                        debugRecorder = debugRecorder,
                        reason = reason,
                        httpStatusCode = response?.code,
                        isInForeground = isAppInForeground(),
                    )
                    notifyClosedOnce(reason, response?.code)
                }
            },
        )
    }
}

/**
 * Aggregates high-frequency `message_delta` WebSocket frames into periodic summary events so
 * streaming does not allocate per-delta telemetry objects or wipe the 600-event ring buffer.
 */
internal class WsDeltaLogAggregator(
    private val debugRecorder: DebugRecorder?,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var frames = 0
    private var textChars = 0
    private var thinkingChars = 0
    private val tabIds = LinkedHashSet<String>()
    private var windowStartMs = 0L

    fun recordDelta(env: WsServerEnvelope) {
        if (debugRecorder == null) return
        val summary: String?
        val ts: Long
        synchronized(lock) {
            val now = clockMs()
            if (frames == 0) {
                windowStartMs = now
            }
            frames++
            textChars += env.textDelta?.length ?: 0
            thinkingChars += env.thinkingDelta?.length ?: 0
            env.tabId?.takeIf { it.isNotBlank() }?.let { tabIds.add(it) }
            if (now - windowStartMs >= flushIntervalMs) {
                summary = drainSummaryLocked(now)
                ts = now
            } else {
                summary = null
                ts = 0L
            }
        }
        if (summary != null) {
            debugRecorder.record(category = "ws", event = "ws_delta_batch", detail = summary, ts = ts)
        }
    }

    fun flush() {
        if (debugRecorder == null) return
        val summary: String?
        val ts: Long
        synchronized(lock) {
            if (frames == 0) return
            val now = clockMs()
            summary = drainSummaryLocked(now)
            ts = now
        }
        if (summary != null) {
            debugRecorder.record(category = "ws", event = "ws_delta_batch", detail = summary, ts = ts)
        }
    }

    private fun drainSummaryLocked(nowMs: Long): String {
        val elapsedMs = (nowMs - windowStartMs).coerceAtLeast(0L)
        val detail = buildString {
            append("frames=").append(frames)
            append(" textChars=").append(textChars)
            if (thinkingChars > 0) append(" thinkingChars=").append(thinkingChars)
            append(" tabs=").append(tabIds.size)
            append(" windowMs=").append(elapsedMs)
        }
        frames = 0
        textChars = 0
        thinkingChars = 0
        tabIds.clear()
        windowStartMs = nowMs
        return detail
    }

    companion object {
        private const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L
    }
}
