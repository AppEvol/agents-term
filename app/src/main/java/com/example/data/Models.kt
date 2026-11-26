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

@JsonClass(generateAdapter = true)
data class AgentUsageItem(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val remainingPercent: Double,
    val usedPercent: Double = (100.0 - remainingPercent).coerceIn(0.0, 100.0),
    val resetsAt: String? = null,
    val resetsAtMs: Long? = null,
    val modelIds: List<String> = emptyList(),
    val modelPrefixes: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AgentUsageResponse(
    val agent: String,
    val fetchedAt: Long = 0L,
    val plan: String? = null,
    val items: List<AgentUsageItem> = emptyList(),
    val error: String? = null,
)

data class AgentUsageUiState(
    val isLoading: Boolean = false,
    val response: AgentUsageResponse? = null,
    val error: String? = null,
)

fun formatUsagePercent(percent: Double): String {
    val clamped = percent.coerceIn(0.0, 100.0)
    if (clamped > 0.0 && clamped < 1.0) return "<1%"
    return "${kotlin.math.round(clamped).toInt()}%"
}

fun formatTimeUntilReset(
    resetsAtMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): String? {
    if (resetsAtMs == null || resetsAtMs <= 0L) return null
    val diffMs = resetsAtMs - nowMs
    if (diffMs <= 0L) return "Resets soon"
    val totalMinutes = diffMs / 60_000L
    if (totalMinutes < 1L) return "Resets in <1m"
    val days = totalMinutes / (60L * 24L)
    val hours = (totalMinutes % (60L * 24L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L && hours > 0L -> "Resets in ${days}d ${hours}h"
        days > 0L -> "Resets in ${days}d"
        hours > 0L && minutes > 0L -> "Resets in ${hours}h ${minutes}m"
        hours > 0L -> "Resets in ${hours}h"
        else -> "Resets in ${minutes}m"
    }
}

fun findUsageForModel(
    modelId: String,
    items: List<AgentUsageItem>,
): AgentUsageItem? {
    val trimmed = modelId.trim()
    if (trimmed.isEmpty() || items.isEmpty()) return null
    items.firstOrNull { item ->
        item.modelIds.any { it.equals(trimmed, ignoreCase = true) }
    }?.let { return it }
    return items.firstOrNull { item ->
        item.modelPrefixes.any { prefix ->
            prefix.isNotBlank() && trimmed.startsWith(prefix, ignoreCase = true)
        }
    }
}

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
    "~/.agents-term/live-debugging.md"

const val FULL_ACCESS_SYSTEM_PROMPT_ADDENDUM =
    "When the user asks you to accomplish a task in this tab, once the task is complete " +
        "clearly let them know it is finished and that they can close this tab."

const val DEFAULT_AGENT_SYSTEM_PROMPT =
    "You are responding inside AgentsTerm, a mobile app terminal/chat view on a phone or tablet. " +
        "Keep formatting mobile-friendly (concise paragraphs, short lists, avoid overly wide tables or ASCII art).\n\n" +
        "Live app debugging: when troubleshooting AgentsTerm itself, you can inspect the live phone app " +
        "(state, logs, logcat, crash traces, screenshots) via `agents_term_app_cli` or check uploaded dumps in " +
        "`~/.agents-term/debug-dumps/latest/`. For commands, prerequisites, and rules, read " +
        "`$DEFAULT_LIVE_DEBUGGING_DOC_PATH`.\n\n" +
        "VM state rule: treat `~/.agents-term/` as read-only — never create, edit, or delete files inside it " +
        "directly; only `agentsterm-daemon` manages files in that directory."

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

/** What a tab is doing from the user's point of view. Every tab is in exactly one of these. */
enum class TabActivity {
    /** The agent is running a turn. */
    WORKING,

    /** The agent's turn ended and it is waiting for the next message. */
    WAITING,

    /** The task is done and the tab can be closed. */
    FINISHED,
}

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
    val taskSummary: String? = null,
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val closedAt: Long? = null,
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
    val closedTabs: List<TabRecord> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ClosedTabsResponse(
    val closedTabs: List<TabRecord> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DeleteTabResponse(
    val ok: Boolean = true,
    val closedTab: TabRecord? = null,
    val closedTabs: List<TabRecord>? = null,
)

@JsonClass(generateAdapter = true)
data class ReviveTabResponse(
    val tab: TabRecord,
    val messages: List<ChatMessage> = emptyList(),
    val closedTabs: List<TabRecord>? = null,
)

fun formatRelativeClosedTime(
    closedAtMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (closedAtMs == null || closedAtMs <= 0L) return "recently"
    val elapsedMs = (nowMs - closedAtMs).coerceAtLeast(0L)
    return when {
        elapsedMs < 60_000L -> "just now"
        elapsedMs < 3_600_000L -> "${elapsedMs / 60_000L}m ago"
        elapsedMs < 86_400_000L -> "${elapsedMs / 3_600_000L}h ago"
        else -> "${elapsedMs / 86_400_000L}d ago"
    }
}

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

const val WORK_DONE_TOKEN = "<this-work-is-done-172728>"

private class WorkDoneTokenScanner(private val text: String) {
    private val out = StringBuilder(text.length)
    private val len = text.length
    private var pos = 0
    private var detected = false
    private var atLineStart = true
    private var fenceChar: Char? = null
    private var fenceLen = 0
    private var inFenceHeader = false
    private var inlineTicks = 0
    private var inlineBlankLinePending = false

    fun scan(): Pair<Boolean, String> {
        while (pos < len) {
            when {
                fenceChar != null -> stepFencedCode()
                inlineTicks > 0 -> stepInlineCode()
                else -> stepProse()
            }
        }
        val cleaned = if (detected) out.toString().trimEnd() else text
        return detected to cleaned
    }

    private fun stepFencedCode() {
        if (inFenceHeader) {
            emitUntilNewline()
            inFenceHeader = false
            return
        }
        if (atLineStart && tryCloseFence()) {
            return
        }
        atLineStart = false
        emitUntilNewline()
    }

    private fun emitUntilNewline() {
        val nl = text.indexOf('\n', pos)
        if (nl >= 0) {
            out.append(text, pos, nl + 1)
            pos = nl + 1
            atLineStart = true
        } else {
            out.append(text, pos, len)
            pos = len
        }
    }

    private fun tryCloseFence(): Boolean {
        val fc = fenceChar ?: return false
        var p = skipLeadingSpaces(pos)
        if (p >= len || text[p] != fc) return false
        val start = p
        while (p < len && text[p] == fc) p++
        if (p - start < fenceLen) return false
        while (p < len && (text[p] == ' ' || text[p] == '\t')) p++
        val end = consumeLineEndingOrEof(p) ?: return false
        out.append(text, pos, end)
        pos = end
        fenceChar = null
        fenceLen = 0
        atLineStart = true
        return true
    }

    private fun consumeLineEndingOrEof(p: Int): Int? {
        if (p == len) return p
        return when (text[p]) {
            '\n' -> p + 1
            '\r' -> if (p + 1 < len && text[p + 1] == '\n') p + 2 else p + 1
            else -> null
        }
    }

    private fun stepInlineCode() {
        if (text[pos] == '`') {
            val start = pos
            while (pos < len && text[pos] == '`') pos++
            out.append(text, start, pos)
            inlineBlankLinePending = false
            atLineStart = false
            if (pos - start == inlineTicks) inlineTicks = 0
            return
        }
        val ch = text[pos++]
        out.append(ch)
        if (ch == '\n') {
            if (inlineBlankLinePending) {
                inlineTicks = 0
                inlineBlankLinePending = false
            } else {
                inlineBlankLinePending = true
            }
            atLineStart = true
        } else if (ch != '\r' && ch != ' ' && ch != '\t') {
            inlineBlankLinePending = false
            atLineStart = false
        }
    }

    private fun stepProse() {
        if (atLineStart && tryHandleLineStart()) return
        if (text[pos] == '`') {
            val start = pos
            while (pos < len && text[pos] == '`') pos++
            out.append(text, start, pos)
            inlineTicks = pos - start
            inlineBlankLinePending = false
            atLineStart = false
            return
        }
        if (tryConsumeDoneToken()) return
        val ch = text[pos++]
        out.append(ch)
        atLineStart = ch == '\n'
    }

    private fun tryHandleLineStart(): Boolean {
        val afterSpaces = skipLeadingSpaces(pos)
        if (afterSpaces < len && (text[afterSpaces] == '`' || text[afterSpaces] == '~')) {
            openFenceOrInline(afterSpaces)
            return true
        }
        if (afterSpaces > pos) {
            out.append(text, pos, afterSpaces)
            pos = afterSpaces
            atLineStart = false
            return true
        }
        return false
    }

    private fun openFenceOrInline(fenceStart: Int) {
        val fc = text[fenceStart]
        var p = fenceStart
        while (p < len && text[p] == fc) p++
        val count = p - fenceStart
        out.append(text, pos, p)
        pos = p
        atLineStart = false
        if (count >= 3 && !isSingleLineBacktickSpan(fc, p)) {
            fenceChar = fc
            fenceLen = count
            inFenceHeader = true
        } else if (fc == '`') {
            inlineTicks = count
            inlineBlankLinePending = false
        }
    }

    private fun isSingleLineBacktickSpan(fc: Char, afterFence: Int): Boolean {
        if (fc != '`') return false
        val nl = text.indexOf('\n', afterFence)
        val tick = text.indexOf('`', afterFence)
        return tick >= 0 && (nl < 0 || tick < nl)
    }

    private fun tryConsumeDoneToken(): Boolean {
        if (text[pos] != '<' ||
            !text.regionMatches(pos, WORK_DONE_TOKEN, 0, WORK_DONE_TOKEN.length, ignoreCase = true)
        ) {
            return false
        }
        detected = true
        pos += WORK_DONE_TOKEN.length
        while (pos < len && (text[pos] == ' ' || text[pos] == '\t')) pos++
        if (pos < len && text[pos] == '\r') {
            pos += if (pos + 1 < len && text[pos + 1] == '\n') 2 else 1
            atLineStart = true
        } else if (pos < len && text[pos] == '\n') {
            pos++
            atLineStart = true
        }
        return true
    }

    private fun skipLeadingSpaces(start: Int): Int {
        var p = start
        var spaces = 0
        while (p < len && text[p] == ' ' && spaces < 3) {
            p++
            spaces++
        }
        return p
    }
}

private fun scanWorkDoneToken(text: String): Pair<Boolean, String> {
    if (text.isEmpty() || !text.contains(WORK_DONE_TOKEN, ignoreCase = true)) {
        return false to text
    }
    return WorkDoneTokenScanner(text).scan()
}

/**
 * Returns true when an assistant message includes [WORK_DONE_TOKEN] outside Markdown code blocks
 * and inline code spans.
 */
fun isTaskFinishedText(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return scanWorkDoneToken(text).first
}

/**
 * Strips [WORK_DONE_TOKEN] outside Markdown code blocks and inline code spans so raw completion
 * markers are never shown in chat bubbles or previews.
 */
fun stripWorkDoneToken(text: String): String = scanWorkDoneToken(text).second

fun isTabTaskFinished(
    tab: TabRecord,
    messages: List<ChatMessage> = emptyList(),
    manuallyReopened: Boolean = false,
): Boolean {
    if (tab.agent == "shell") return false
    if (tab.status == "done") return true
    if (manuallyReopened || tab.status != "idle") return false
    val latestAssistant = messages.lastOrNull { it.role == "assistant" && it.status == "complete" }
    if (latestAssistant != null) {
        return isTaskFinishedText(latestAssistant.text)
    }
    return isTaskFinishedText(tab.lastMessagePreview)
}

@JsonClass(generateAdapter = true)
data class UpdateTabRequest(
    val title: String? = null,
    val cwd: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null,
    val status: String? = null,
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
    val closedTabs: List<TabRecord>? = null,
    val activeTabId: String? = null,
    val tab: TabRecord? = null,
    val closedTab: TabRecord? = null,
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
