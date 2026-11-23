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

class HerdrApiClient {
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

    private suspend inline fun <reified T> executeJson(request: Request): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val parsedErr = runCatching {
                        decodeHerdrJson<ErrorResponse>(bodyStr).error
                    }.getOrNull()
                    throw IOException(parsedErr ?: "HTTP ${resp.code}: ${resp.message}")
                }
                decodeHerdrJson<T>(bodyStr)
            }
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

    suspend fun getTabMessages(config: ConnectionConfig, tabId: String): TabMessagesResponse {
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

    fun openWebSocket(
        config: ConnectionConfig,
        onOpen: () -> Unit,
        onEvent: (WsServerEnvelope) -> Unit,
        onClosed: (String?) -> Unit,
    ): WebSocket {
        val req = Request.Builder()
            .url(config.wsUrl())
            .withAuth(config)
            .build()

        return client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { decodeHerdrJson<WsServerEnvelope>(text) }
                        .onSuccess { onEvent(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    onClosed(reason.ifBlank { null })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val codeMsg = response?.let { "HTTP ${it.code}: ${it.message}" }
                    onClosed(codeMsg ?: t.message ?: "Connection lost")
                }
            }
        )
    }
}
