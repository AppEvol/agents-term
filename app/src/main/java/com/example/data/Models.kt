package com.example.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URLDecoder

val HerdrMoshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

inline fun <reified T> decodeHerdrJson(raw: String): T {
    val adapter = HerdrMoshi.adapter(T::class.java)
    return adapter.lenient().fromJson(raw)
        ?: throw IllegalArgumentException("Unable to decode ${T::class.java.simpleName} from JSON")
}

inline fun <reified T> encodeHerdrJson(value: T): String {
    val adapter = HerdrMoshi.adapter(T::class.java)
    return adapter.toJson(value)
}

@JsonClass(generateAdapter = true)
data class ConnectionConfig(
    val host: String = "",
    val port: Int = 7501,
    val token: String = "",
) {
    val isValid: Boolean get() = host.isNotBlank() && port in 1..65535

    fun httpBaseUrl(): String {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "http://$cleanHost:$port"
    }

    fun wsUrl(): String {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val base = "ws://$cleanHost:$port/ws"
        return if (token.isNotBlank()) "$base?token=${token.trim()}" else base
    }

    companion object {
        /**
         * Parses `agentsterm://connect?...` or `herdr://connect?host=100.x.y.z&port=7501&token=...` pairing URIs.
         */
        fun parsePairingUri(raw: String): ConnectionConfig? {
            val trimmed = raw.trim()
            val validScheme = trimmed.startsWith("herdr://", ignoreCase = true) ||
                trimmed.startsWith("agentsterm://", ignoreCase = true)
            if (!validScheme) return null
            val queryIdx = trimmed.indexOf('?')
            if (queryIdx < 0) return null
            val query = trimmed.substring(queryIdx + 1)
            val params = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                    params[k] = v
                }
            }
            val host = params["host"]?.trim().orEmpty()
            if (host.isBlank()) return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7501
            val token = params["token"]?.trim().orEmpty()
            return ConnectionConfig(host = host, port = port, token = token)
        }
    }
}

@JsonClass(generateAdapter = true)
data class AvailableAgentInfo(
    val type: String,
    val name: String,
    val description: String = "",
    val binary: String = "",
    val available: Boolean = true,
    val binaryPath: String? = null,
)

@JsonClass(generateAdapter = true)
data class ServerInfo(
    val version: String = "",
    val hostname: String = "",
    val tailscaleIp: String? = null,
    val defaultCwd: String = "/home",
    val agents: List<AvailableAgentInfo> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AgentSessionRef(
    val kind: String,
    val value: String,
)

@JsonClass(generateAdapter = true)
data class TabRecord(
    val id: String,
    val title: String,
    val agent: String,
    val cwd: String,
    val permissionMode: String = "full-access",
    val model: String? = null,
    val status: String = "idle",
    val statusDetail: String? = null,
    val sessionRef: AgentSessionRef? = null,
    val unread: Boolean = false,
    val lastMessagePreview: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val folderName: String
        get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }

    val agentLabel: String
        get() = when (agent) {
            "agy" -> "Antigravity"
            "claude" -> "Claude"
            "codex" -> "Codex"
            "shell" -> "Shell"
            else -> agent
        }
}

@JsonClass(generateAdapter = true)
data class ToolCallItem(
    val id: String,
    val name: String,
    val inputSummary: String = "",
    val inputJson: String? = null,
    val output: String? = null,
    val status: String = "running",
    val durationMs: Long? = null,
)

@JsonClass(generateAdapter = true)
data class DeniedAction(
    val action: String,
    val displayName: String,
    val detail: String? = null,
)

@JsonClass(generateAdapter = true)
data class UsageStats(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val thinkingTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val id: String,
    val tabId: String,
    val role: String,
    val text: String = "",
    val thinking: String? = null,
    val toolCalls: List<ToolCallItem> = emptyList(),
    val deniedActions: List<DeniedAction> = emptyList(),
    val status: String = "complete",
    val error: String? = null,
    val usage: UsageStats? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class TabsResponse(
    val tabs: List<TabRecord> = emptyList(),
    val activeTabId: String? = null,
)

@JsonClass(generateAdapter = true)
data class SingleTabResponse(
    val tab: TabRecord,
)

@JsonClass(generateAdapter = true)
data class TabMessagesResponse(
    val tab: TabRecord,
    val messages: List<ChatMessage> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class FsDirectoryEntry(
    val name: String,
    val path: String,
)

@JsonClass(generateAdapter = true)
data class FsListResponse(
    val path: String,
    val parent: String? = null,
    val directories: List<FsDirectoryEntry> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateTabRequest(
    val agent: String,
    val title: String? = null,
    val cwd: String? = null,
    val permissionMode: String = "full-access",
    val model: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTabRequest(
    val title: String? = null,
    val cwd: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
)

@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    val text: String,
    val overridePermissionMode: String? = null,
)

@JsonClass(generateAdapter = true)
data class SendMessageResponse(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage,
)

@JsonClass(generateAdapter = true)
data class ErrorResponse(
    val error: String? = null,
)

@JsonClass(generateAdapter = true)
data class WsServerEnvelope(
    val type: String,
    val server: ServerInfo? = null,
    val tabs: List<TabRecord>? = null,
    val activeTabId: String? = null,
    val tab: TabRecord? = null,
    val tabId: String? = null,
    val message: ChatMessage? = null,
    val messageId: String? = null,
    val textDelta: String? = null,
    val thinkingDelta: String? = null,
    val toolCall: ToolCallItem? = null,
    val ts: Long? = null,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

@JsonClass(generateAdapter = true)
data class DebugEvent(
    val ts: Long,
    val isoTime: String,
    val category: String,
    val event: String,
    val detail: String,
    val payload: String? = null,
)

@JsonClass(generateAdapter = true)
data class SanitizedWorkspaceSnapshot(
    val host: String,
    val port: Int,
    val hasToken: Boolean,
    val connectionState: String,
    val connectionError: String? = null,
    val bannerMessage: String? = null,
    val serverHostname: String? = null,
    val serverVersion: String? = null,
    val selectedTabId: String? = null,
    val loadingTabIds: List<String> = emptyList(),
    val tabs: List<TabRecord> = emptyList(),
    val activeTabMessages: List<ChatMessage> = emptyList(),
    val messageCountsByTab: Map<String, Int> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class DebugDumpUploadRequest(
    val dumpId: String,
    val createdAt: Long,
    val note: String? = null,
    val appVersion: String,
    val deviceInfo: String,
    val connectionSummary: String,
    val workspaceStateJson: String,
    val eventsNdjson: String,
    val logcat: String? = null,
    val lastCrash: String? = null,
    val screenshotPngBase64: String? = null,
)

@JsonClass(generateAdapter = true)
data class DebugDumpUploadResponse(
    val ok: Boolean = true,
    val dumpId: String,
    val remoteDir: String,
    val latestSymlink: String? = null,
    val files: List<String> = emptyList(),
)
