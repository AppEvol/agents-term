package com.appevol.agentsterm.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class DebugSharingSettings(
    val enabled: Boolean = false,
    val includeScreenshot: Boolean = true,
    val includeLogcat: Boolean = true,
    val autoUploadOnError: Boolean = false,
    /** Lets agents on the connected VM query live app state (logs, state, screenshots) via the daemon. */
    val remoteInspectionEnabled: Boolean = false,
    /** Also lets those agents tap, swipe and type in the app (agents_term_app_cli tap/swipe/type/key). */
    val remoteControlEnabled: Boolean = false,
    /** Also lets those agents record a video of the screen (agents_term_app_cli record). */
    val remoteRecordingEnabled: Boolean = false,
) {
    val remoteInspectionActive: Boolean get() = enabled && remoteInspectionEnabled
    val remoteControlActive: Boolean get() = remoteInspectionActive && remoteControlEnabled
    val remoteRecordingActive: Boolean get() = remoteInspectionActive && remoteRecordingEnabled
}

data class DebugDumpUploadState(
    val isUploading: Boolean = false,
    val lastDumpId: String? = null,
    val lastRemoteDir: String? = null,
    val lastLatestSymlink: String? = null,
    val lastUploadedAt: Long? = null,
    val lastError: String? = null,
)

private val bearerTokenRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+""")
private val queryTokenRegex = Regex("""(?i)((?:[?&]|\b)token=)[^&\s"'`<>)\]]+""")
private val labelTokenRegex = Regex("""(?i)(\btoken\s*:\s*)\S+""")
private val jsonTokenRegex = Regex("""(?i)("token"\s*:\s*")([^"]*)(")""")
private val privateKeyBlockRegex = Regex(
    """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----""",
)

private val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")
private val isoUtcFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = UTC_TIME_ZONE
    }
}

/**
 * Strips bearer tokens, query tokens, label tokens, and PEM private keys before storing or uploading telemetry.
 */
fun sanitizeDebugText(raw: String): String {
    if (!raw.contains("token", ignoreCase = true) &&
        !raw.contains("bearer", ignoreCase = true) &&
        !raw.contains("PRIVATE KEY")
    ) {
        return raw
    }
    var result = privateKeyBlockRegex.replace(raw, "[REDACTED_PRIVATE_KEY]")
    result = bearerTokenRegex.replace(result, "Bearer [REDACTED]")
    result = queryTokenRegex.replace(result, "$1[REDACTED]")
    result = labelTokenRegex.replace(result, "$1[REDACTED]")
    result = jsonTokenRegex.replace(result, "$1[REDACTED]$3")
    return result
}

fun formatIsoUtc(epochMs: Long): String {
    val fmt = requireNotNull(isoUtcFormat.get())
    return fmt.format(Date(epochMs))
}

fun generateDebugDumpId(): String = UUID.randomUUID().toString().substring(0, 8)

/**
 * Thread-safe bounded ring buffer capturing sanitized HTTP, WebSocket, SSH, and UI events
 * for rapid debugging of mobile issues directly on the connected VM.
 */
class DebugRecorder(
    private val maxEvents: Int = 600,
    private val maxPayloadChars: Int = 2048,
) {
    private val lock = Any()
    private val events = ArrayDeque<DebugEvent>(maxEvents.coerceAtMost(600))

    @Volatile
    private var onCountChanged: ((Int) -> Unit)? = null

    fun setCountListener(listener: ((Int) -> Unit)?) {
        onCountChanged = listener
    }

    val count: Int
        get() = synchronized(lock) { events.size }

    fun record(
        category: String,
        event: String,
        detail: String,
        payload: String? = null,
        ts: Long = System.currentTimeMillis(),
    ) {
        val cleanDetail = sanitizeDebugText(detail).take(1000)
        val cleanPayload = payload?.takeIf { it.isNotBlank() }?.let { raw ->
            val sanitized = sanitizeDebugText(raw)
            if (sanitized.length > maxPayloadChars) {
                sanitized.take(maxPayloadChars) + "…[truncated ${sanitized.length} chars]"
            } else {
                sanitized
            }
        }
        val entry = DebugEvent(
            ts = ts,
            isoTime = formatIsoUtc(ts),
            category = category,
            event = event,
            detail = cleanDetail,
            payload = cleanPayload,
        )
        val newSize = synchronized(lock) {
            if (events.size >= maxEvents) {
                events.removeFirst()
            }
            events.addLast(entry)
            events.size
        }
        onCountChanged?.invoke(newSize)
    }

    fun snapshot(): List<DebugEvent> = synchronized(lock) { events.toList() }

    fun toNdjson(): String {
        val list = snapshot()
        if (list.isEmpty()) return ""
        return buildString {
            for (item in list) {
                appendLine(encodeHerdrJson(item))
            }
        }
    }

    fun clear() {
        synchronized(lock) { events.clear() }
        onCountChanged?.invoke(0)
    }
}

fun buildSanitizedWorkspaceJson(state: WorkspaceState): String {
    val activeMsgs = state.activeMessages.takeLast(50).map { msg ->
        msg.copy(
            text = sanitizeDebugText(msg.text),
            thinking = msg.thinking?.let { sanitizeDebugText(it) },
            error = msg.error?.let { sanitizeDebugText(it) },
        )
    }
    val snapshot = SanitizedWorkspaceSnapshot(
        host = state.config.host,
        port = state.config.port,
        hasToken = state.config.token.isNotBlank(),
        connectionState = state.connectionState.name,
        connectionError = state.connectionError?.let { sanitizeDebugText(it) },
        bannerMessage = state.bannerMessage?.let { sanitizeDebugText(it) },
        serverHostname = state.serverInfo?.hostname,
        serverVersion = state.serverInfo?.version,
        selectedTabId = state.selectedTabId,
        loadingTabIds = state.loadingTabIds.sorted(),
        tabs = state.tabs,
        activeTabMessages = activeMsgs,
        messageCountsByTab = state.messagesByTab.mapValues { it.value.size },
    )
    return encodeHerdrJson(snapshot)
}

fun buildDebugSummaryMarkdown(request: DebugDumpUploadRequest): String {
    val eventCount = request.eventsNdjson
        .lineSequence()
        .count { it.isNotBlank() }
    return buildString {
        appendLine("# AgentsTerm Android Debug Dump (${request.dumpId})")
        appendLine()
        appendLine("- **Captured (UTC):** ${formatIsoUtc(request.createdAt)}")
        appendLine("- **App Version:** ${request.appVersion}")
        appendLine("- **Device:** ${request.deviceInfo}")
        appendLine("- **Connection:** ${request.connectionSummary.ifBlank { "n/a" }}")
        appendLine("- **Buffered Events:** $eventCount")
        appendLine("- **Crash Trace Included:** ${if (!request.lastCrash.isNullOrBlank()) "yes (`crash.txt`)" else "no"}")
        appendLine("- **Screenshot Included:** ${if (!request.screenshotPngBase64.isNullOrBlank()) "yes (`screenshot.png`)" else "no"}")
        appendLine("- **Logcat Included:** ${if (!request.logcat.isNullOrBlank()) "yes (`logcat.txt`)" else "no"}")
        if (!request.note.isNullOrBlank()) {
            appendLine()
            appendLine("## User Note")
            appendLine()
            appendLine(request.note.trim())
        }
    }
}
