package com.example.data

private val WHITESPACE_RE = Regex("\\s+")
private val CODE_FENCE_RE = Regex("```[^\\n]*\\n?(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL))
private val MARKDOWN_LINK_RE = Regex("\\[([^\\]]+)]\\([^)]+\\)")
private val MARKDOWN_LINE_PREFIX_RE = Regex("^\\s*(?:#{1,6}\\s+|>\\s+|[-*+]\\s+)")
private val TASK_SUMMARY_WRAPPER_RE = Regex("</?tab_task_summary>", RegexOption.IGNORE_CASE)
private val MODEL_SLASH_RE = Regex("^/model(?:\\s|$)", RegexOption.IGNORE_CASE)
private val LEADING_CD_RE = Regex(
    """^\s*cd(?:\s+(?:-[LP]|--))?\s+(?:"[^"]*"|'[^']*'|(?:\\.|[^\s;&|\n])+)\s*(?:&&|;|\r?\n)\s*""",
)
private val SHELL_WRAPPER_RE = Regex(
    """^\s*(?:/(?:usr/)?bin/)?(?:ba|z)?sh\s+-[a-z]*c\s+(["'])(.*)\1\s*$""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

private val DESCRIPTION_KEYS = listOf("description", "toolSummary", "summary", "title")
private val COMMAND_KEYS = listOf("command", "CommandLine")
private val DETAIL_KEYS = listOf("file_path", "path", "Path", "pattern", "query", "url")

sealed class AssistantDisplayBlock {
    data class TextBlock(val text: String) : AssistantDisplayBlock()
    data class ToolRunBlock(val toolCalls: List<ToolCallItem>) : AssistantDisplayBlock()
}

private fun truncateSingleLine(raw: String, maxLen: Int = 120): String {
    val single = WHITESPACE_RE.replace(raw, " ").trim()
    return if (single.length > maxLen) single.take(maxLen - 1) + "…" else single
}

fun summarizeCommandForDisplay(command: String): String {
    var current = command.trim()
    if (current.isEmpty()) return ""
    val wrapped = SHELL_WRAPPER_RE.matchEntire(current)
    if (wrapped != null) {
        val quote = wrapped.groupValues[1]
        val inner = wrapped.groupValues[2].trim()
        if (inner.isNotEmpty() && !inner.contains(quote)) {
            current = inner
        }
    }
    while (true) {
        val match = LEADING_CD_RE.find(current) ?: break
        val rest = current.substring(match.range.last + 1).trim()
        if (rest.isEmpty()) break
        current = rest
    }
    return truncateSingleLine(current, 120)
}

@Suppress("UNCHECKED_CAST")
fun formatToolCallSummary(toolCall: ToolCallItem): String {
    val rawJson = toolCall.inputJson?.trim()
    if (!rawJson.isNullOrEmpty() && rawJson.startsWith("{")) {
        val parsed = runCatching {
            HerdrMoshi.adapter(Any::class.java).lenient().fromJson(rawJson) as? Map<String, Any?>
        }.getOrNull()
        if (parsed != null) {
            for (key in DESCRIPTION_KEYS) {
                val value = (parsed[key] as? String)?.trim()
                if (!value.isNullOrEmpty()) {
                    return truncateSingleLine(value, 120)
                }
            }
            for (key in COMMAND_KEYS) {
                val value = (parsed[key] as? String)?.trim()
                if (!value.isNullOrEmpty()) {
                    return summarizeCommandForDisplay(value)
                }
            }
            if (toolCall.inputSummary.isBlank()) {
                for (key in DETAIL_KEYS) {
                    val value = (parsed[key] as? String)?.trim()
                    if (!value.isNullOrEmpty()) {
                        return truncateSingleLine(value, 120)
                    }
                }
            }
        }
    }
    if (toolCall.inputSummary.isBlank()) return ""
    return summarizeCommandForDisplay(toolCall.inputSummary)
}

fun formatToolRunSummary(toolCalls: List<ToolCallItem>): String {
    val countLabel = if (toolCalls.size == 1) "1 tool call" else "${toolCalls.size} tool calls"
    val parts = mutableListOf(countLabel)
    val done = toolCalls.count { it.status == "done" }
    val running = toolCalls.count { it.status == "running" }
    val failed = toolCalls.count { it.status == "error" }
    val blocked = toolCalls.count { it.status == "denied" }
    if (done > 0) parts.add("$done done")
    if (running > 0) parts.add("$running running")
    if (failed > 0) parts.add("$failed failed")
    if (blocked > 0) parts.add("$blocked blocked")
    return parts.joinToString(" · ")
}

fun buildAssistantDisplayBlocks(message: ChatMessage): List<AssistantDisplayBlock> {
    val cleanMessageText = stripWorkDoneToken(message.text)
    if (message.segments.isEmpty()) {
        val blocks = mutableListOf<AssistantDisplayBlock>()
        if (message.toolCalls.isNotEmpty()) {
            blocks.add(AssistantDisplayBlock.ToolRunBlock(message.toolCalls))
        }
        if (cleanMessageText.isNotBlank()) {
            blocks.add(AssistantDisplayBlock.TextBlock(cleanMessageText))
        }
        return blocks
    }

    val toolById = message.toolCalls.associateBy { it.id }
    val seenToolIds = LinkedHashSet<String>()
    val blocks = mutableListOf<AssistantDisplayBlock>()
    val pendingTools = mutableListOf<ToolCallItem>()

    fun flushTools() {
        if (pendingTools.isNotEmpty()) {
            blocks.add(AssistantDisplayBlock.ToolRunBlock(pendingTools.toList()))
            pendingTools.clear()
        }
    }

    fun addText(raw: String) {
        val prev = blocks.lastOrNull() as? AssistantDisplayBlock.TextBlock
        if (prev != null) {
            val sep = when {
                prev.text.endsWith("\n\n") -> ""
                prev.text.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            blocks[blocks.lastIndex] = AssistantDisplayBlock.TextBlock(prev.text + sep + raw)
        } else {
            blocks.add(AssistantDisplayBlock.TextBlock(raw))
        }
    }

    for (seg in message.segments) {
        when (seg.type) {
            "tool" -> {
                val tc = seg.toolCallId?.let { toolById[it] }
                if (tc != null && seenToolIds.add(tc.id)) {
                    pendingTools.add(tc)
                }
            }

            "text" -> {
                val text = stripWorkDoneToken(seg.text.orEmpty())
                if (text.isNotBlank()) {
                    flushTools()
                    addText(text)
                }
            }
        }
    }

    for (tc in message.toolCalls) {
        if (seenToolIds.add(tc.id)) {
            pendingTools.add(tc)
        }
    }
    flushTools()

    if (blocks.none { it is AssistantDisplayBlock.TextBlock } && cleanMessageText.isNotBlank()) {
        addText(cleanMessageText)
    }
    return blocks
}

fun appendTextDeltaToSegments(segments: List<MessageSegment>, textDelta: String): List<MessageSegment> {
    if (textDelta.isEmpty()) return segments
    val last = segments.lastOrNull()
    if (last != null && last.type == "text") {
        return segments.dropLast(1) + last.copy(text = last.text.orEmpty() + textDelta)
    }
    val chunk = if (segments.isNotEmpty()) textDelta.trimStart('\r', '\n') else textDelta
    if (chunk.isEmpty()) return segments
    return segments + MessageSegment(type = "text", text = chunk)
}

fun appendToolToSegments(segments: List<MessageSegment>, toolCallId: String): List<MessageSegment> {
    if (segments.any { it.type == "tool" && it.toolCallId == toolCallId }) return segments
    return segments + MessageSegment(type = "tool", toolCallId = toolCallId)
}

fun reconcileUpdatedMessageSegments(existing: ChatMessage?, incoming: ChatMessage): ChatMessage {
    if (incoming.segments.isNotEmpty() || existing == null || existing.segments.isEmpty()) {
        return incoming
    }
    val existingToolIds = existing.segments
        .filter { it.type == "tool" }
        .mapNotNull { it.toolCallId }
        .toSet()
    val incomingToolIds = incoming.toolCalls.map { it.id }.toSet()
    if (existingToolIds != incomingToolIds) {
        return incoming
    }
    val existingText = existing.segments
        .filter { it.type == "text" }
        .joinToString("") { it.text.orEmpty() }
        .replace(WHITESPACE_RE, "")
    val incomingText = incoming.text.replace(WHITESPACE_RE, "")
    return if (existingText == incomingText) {
        incoming.copy(segments = existing.segments)
    } else {
        incoming
    }
}

fun summarizeTaskPromptForDisplay(raw: String, maxLen: Int = 160): String? {
    val cleaned = TASK_SUMMARY_WRAPPER_RE.replace(raw, "").trim()
    if (cleaned.isEmpty()) return null
    val withoutFences = CODE_FENCE_RE.replace(cleaned, " ").trim()
    val source = withoutFences.ifEmpty { CODE_FENCE_RE.replace(cleaned) { it.groupValues[1] } }
    val stripped = MARKDOWN_LINK_RE.replace(source, "$1").replace("`", "")
    val joined = stripped.lines()
        .map { MARKDOWN_LINE_PREFIX_RE.replace(it, "").trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    val single = WHITESPACE_RE.replace(joined, " ").trim()
    if (single.isEmpty()) return null
    if (single.length <= maxLen) return single
    var cut = single.take(maxLen - 1).trimEnd()
    val spaceIdx = cut.lastIndexOf(' ')
    if (spaceIdx >= ((maxLen - 1) * 0.65).toInt()) {
        cut = cut.substring(0, spaceIdx).trimEnd(' ', ',', ';', ':', '-')
    }
    return "$cut…"
}

fun resolveTabTaskSummary(tab: TabRecord, messages: List<ChatMessage>): String? {
    tab.taskSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    for (msg in messages) {
        if (msg.role != "user") continue
        val trimmed = msg.text.trim()
        if (trimmed.isEmpty() || MODEL_SLASH_RE.containsMatchIn(trimmed)) continue
        summarizeTaskPromptForDisplay(trimmed)?.let { return it }
    }
    return tab.lastMessagePreview?.let(::stripWorkDoneToken)?.trim()?.takeIf { it.isNotEmpty() }
}

fun resolveTabLatestDetail(tab: TabRecord, taskSummary: String?): String? {
    val normalizedSummary = taskSummary?.trim().orEmpty()
    return when (tab.status) {
        "working" -> tab.statusDetail?.trim()?.takeIf { it.isNotEmpty() }

        "blocked" -> tab.statusDetail?.trim()?.takeIf { it.isNotEmpty() } ?: "Waiting for approval"

        "error" -> tab.statusDetail?.trim()?.takeIf { it.isNotEmpty() } ?: "Last turn failed"

        else -> tab.lastMessagePreview?.let(::stripWorkDoneToken)?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(normalizedSummary, ignoreCase = true)
        }
    }
}
