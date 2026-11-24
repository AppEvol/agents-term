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
            val validPrefix = trimmed.startsWith("herdr://connect?", ignoreCase = true) ||
                trimmed.startsWith("agentsterm://connect?", ignoreCase = true)
            if (!validPrefix) return null
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
data class AgentModelOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class AvailableAgentInfo(
    val type: String,
    val name: String,
    val description: String = "",
    val binary: String = "",
    val available: Boolean = true,
    val binaryPath: String? = null,
    val defaultModel: String? = null,
    val models: List<AgentModelOption> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AgentModelsResponse(
    val agent: String,
    val defaultModel: String? = null,
    val models: List<AgentModelOption> = emptyList(),
)

sealed class ModelSlashCommand {
    data object OpenPicker : ModelSlashCommand()
    data object ResetToDefault : ModelSlashCommand()
    data class SetModel(val modelQuery: String) : ModelSlashCommand()
}

fun parseModelSlashCommand(rawText: String): ModelSlashCommand? {
    val trimmed = rawText.trim()
    if (!trimmed.startsWith("/model", ignoreCase = true)) return null
    val rest = trimmed.substring(6)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    val arg = rest.trim()
    if (arg.isEmpty()) return ModelSlashCommand.OpenPicker
    if (arg.equals("default", ignoreCase = true) ||
        arg.equals("reset", ignoreCase = true) ||
        arg.equals("auto", ignoreCase = true)
    ) {
        return ModelSlashCommand.ResetToDefault
    }
    return ModelSlashCommand.SetModel(arg)
}

fun resolveModelIdFromQuery(query: String, availableModels: List<AgentModelOption>): String {
    val trimmed = query.trim()
    availableModels.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.let { return it.id }
    availableModels.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.id }
    return trimmed
}

fun fallbackModelsForAgent(agentType: String): List<AgentModelOption> = when (agentType) {
    "agy" -> listOf(
        AgentModelOption("gemini-4-neon-high", "Gemini 4 Neon (High)"),
        AgentModelOption("gemini-4-neon-medium", "Gemini 4 Neon (Medium)"),
        AgentModelOption("gemini-4-carbon-high", "Gemini 4 Carbon (High)"),
        AgentModelOption("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"),
        AgentModelOption("claude-opus-5-5-high", "Claude Opus 5.5 (High)"),
        AgentModelOption("claude-sonnet-5-5-medium", "Claude Sonnet 5.5 (Medium)"),
    )

    "claude" -> listOf(
        AgentModelOption("opus", "Opus", "Most capable Claude model alias"),
        AgentModelOption("sonnet", "Sonnet", "Balanced Claude model alias"),
        AgentModelOption("haiku", "Haiku", "Fastest Claude model alias"),
    )

    "codex" -> listOf(
        AgentModelOption("gpt-6.2-sol", "GPT-6.2-Sol"),
        AgentModelOption("gpt-6.2-astra", "GPT-6.2-Astra"),
        AgentModelOption("gpt-6.1-terra", "GPT-6.1 Terra"),
        AgentModelOption("gpt-6-astra", "GPT-6-Astra"),
    )

    else -> emptyList()
}

fun resolveAgentModelCatalog(
    agentType: String,
    agents: List<AvailableAgentInfo>,
): Pair<List<AgentModelOption>, String?> {
    if (agentType == "shell") return emptyList<AgentModelOption>() to null
    val info = agents.firstOrNull { it.type == agentType }
    val models = info?.models?.takeIf { it.isNotEmpty() } ?: fallbackModelsForAgent(agentType)
    return models to info?.defaultModel
}

fun formatModelChipLabel(
    modelId: String?,
    availableModels: List<AgentModelOption>,
    defaultModel: String? = null,
): String {
    val trimmed = modelId?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        val defaultName = defaultModel?.let { defId ->
            availableModels.firstOrNull { it.id == defId }?.name ?: defId
        }
        return if (defaultName != null) "Default · $defaultName" else "Default model"
    }
    return availableModels.firstOrNull { it.id == trimmed }?.name ?: trimmed
}

const val DEFAULT_LIVE_DEBUGGING_DOC_PATH =
    "~/.local/share/agentsterm/agents-term/docs/live-debugging.md"

const val FULL_ACCESS_SYSTEM_PROMPT_ADDENDUM =
    "When the user asks you to accomplish a task in this tab, once the task is complete " +
        "clearly let them know it is finished and that they can close this tab."

const val DEFAULT_AGENT_SYSTEM_PROMPT =
    "You are responding inside AgentsTerm, a mobile app terminal/chat view on a phone or tablet. " +
        "Keep formatting mobile-friendly (concise paragraphs, short lists, avoid overly wide tables or ASCII art).\n\n" +
        "Live app debugging: when troubleshooting AgentsTerm itself, you can inspect the live phone app " +
        "(state, logs, logcat, crash traces, screenshots) via `app_state_cli` or check uploaded dumps in " +
        "`~/.config/herdr-mobile/debug-dumps/latest/`. For commands, prerequisites, and rules, read " +
        "`$DEFAULT_LIVE_DEBUGGING_DOC_PATH`."

const val DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT =
    "$DEFAULT_AGENT_SYSTEM_PROMPT\n\n$FULL_ACCESS_SYSTEM_PROMPT_ADDENDUM"

fun resolveDefaultSystemPrompt(
    serverInfo: ServerInfo?,
    permissionMode: String = "default",
): String {
    if (permissionMode == "full-access") {
        serverInfo?.defaultSystemPromptFullAccess?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val base = serverInfo?.defaultSystemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: return DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT
        return if (base.contains(FULL_ACCESS_SYSTEM_PROMPT_ADDENDUM)) {
            base
        } else {
            "$base\n\n$FULL_ACCESS_SYSTEM_PROMPT_ADDENDUM"
        }
    }
    return serverInfo?.defaultSystemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_AGENT_SYSTEM_PROMPT
}

@JsonClass(generateAdapter = true)
data class ServerInfo(
    val version: String = "",
    val hostname: String = "",
    val tailscaleIp: String? = null,
    val defaultCwd: String = "/home",
    val defaultSystemPrompt: String? = null,
    val defaultSystemPromptFullAccess: String? = null,
    val agents: List<AvailableAgentInfo> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AgentSessionRef(
    val kind: String,
    val value: String,
)

@JsonClass(generateAdapter = true)
data class QueuedMessage(
    val id: String,
    val text: String,
    val overridePermissionMode: String? = null,
    val createdAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class TabRecord(
    val id: String,
    val title: String,
    val agent: String,
    val cwd: String,
    val permissionMode: String = "full-access",
    val model: String? = null,
    val systemPrompt: String? = null,
    val status: String = "idle",
    val statusDetail: String? = null,
    val sessionRef: AgentSessionRef? = null,
    val unread: Boolean = false,
    val lastMessagePreview: String? = null,
    val queuedMessages: List<QueuedMessage> = emptyList(),
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
data class MessageSegment(
    val type: String,
    val text: String? = null,
    val toolCallId: String? = null,
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val id: String,
    val tabId: String,
    val role: String,
    val text: String = "",
    val thinking: String? = null,
    val toolCalls: List<ToolCallItem> = emptyList(),
    val segments: List<MessageSegment> = emptyList(),
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
    val exists: Boolean? = null,
)

fun normalizeFsPath(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed == "/") return "/"
    val stripped = trimmed.trimEnd('/')
    return if (stripped.isEmpty() && trimmed.startsWith("/")) "/" else stripped
}

fun resolvePreferredNewTabCwd(
    lastCwd: String?,
    activeTabCwd: String?,
    homeCwd: String?,
): String = lastCwd?.trim()?.takeIf { it.isNotEmpty() }
    ?: activeTabCwd?.trim()?.takeIf { it.isNotEmpty() }
    ?: homeCwd?.trim()?.takeIf { it.isNotEmpty() }
    ?: "/home"

@JsonClass(generateAdapter = true)
data class CreateTabRequest(
    val agent: String,
    val title: String? = null,
    val cwd: String? = null,
    val permissionMode: String = "full-access",
    val model: String? = null,
    val systemPrompt: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTabRequest(
    val title: String? = null,
    val cwd: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null,
)

@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    val text: String,
    val overridePermissionMode: String? = null,
)

@JsonClass(generateAdapter = true)
data class SendMessageResponse(
    val userMessage: ChatMessage? = null,
    val assistantMessage: ChatMessage? = null,
    val queued: Boolean = false,
    val queuedMessage: QueuedMessage? = null,
    val tab: TabRecord? = null,
)

@JsonClass(generateAdapter = true)
data class InterruptTabRequest(
    val processQueue: Boolean = false,
    val text: String? = null,
    val overridePermissionMode: String? = null,
)

@JsonClass(generateAdapter = true)
data class QueueActionResponse(
    val ok: Boolean = true,
    val tab: TabRecord? = null,
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
    // App state reporter requests (type = "app_state_request"); see AppStateReporter.kt.
    val requestId: String? = null,
    val command: String? = null,
    val args: Map<String, Any?>? = null,
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
