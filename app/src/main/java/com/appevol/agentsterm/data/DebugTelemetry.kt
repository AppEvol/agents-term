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

// Text is truncated before it is sanitized, so a secret can be cut off before its closing
// delimiter. These patterns redact such an unterminated secret through the end of the text.
private val unterminatedPrivateKeyRegex = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*$""")
private val unterminatedJsonTokenRegex = Regex("""(?i)("token"\s*:\s*")[^"]*$""")

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
    result = unterminatedPrivateKeyRegex.replace(result, "[REDACTED_PRIVATE_KEY]")
    result = bearerTokenRegex.replace(result, "Bearer [REDACTED]")
    result = queryTokenRegex.replace(result, "$1[REDACTED]")
    result = labelTokenRegex.replace(result, "$1[REDACTED]")
    result = jsonTokenRegex.replace(result, "$1[REDACTED]$3")
    result = unterminatedJsonTokenRegex.replace(result, "$1[REDACTED]")
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
 *
 * The [maxEvents] slots are split between two classes so that a flood of network traffic
 * cannot evict the rare events that explain a bug. Network traffic (`ws` and `http`) gets
 * [trafficBudget] slots and everything else (errors, lifecycle, UI, SSH, PTY, app state)
 * gets the rest. A class may borrow the other class's unused slots; once the buffer is
 * full, the oldest event of whichever class is over its budget is evicted.
 */
class DebugRecorder(
    private val maxEvents: Int = 600,
    private val maxPayloadChars: Int = 2048,
    trafficBudget: Int = maxEvents * 2 / 3,
) {
    private val trafficBudget = trafficBudget.coerceIn(0, maxEvents)
    private val lock = Any()
    private val traffic = ArrayDeque<Sequenced>()
    private val essential = ArrayDeque<Sequenced>()
    private var nextSeq = 0L

    @Volatile
    private var onCountChanged: ((Int) -> Unit)? = null

    @Volatile
    private var pendingFlush: (() -> Unit)? = null

    fun setCountListener(listener: ((Int) -> Unit)?) {
        onCountChanged = listener
    }

    /**
     * Registers a source of buffered summaries (the live WebSocket's frame aggregator) that is
     * flushed by [toNdjson], so an uploaded dump includes the summary for the current window.
     * [snapshot] does not flush, so that polling (`logs -f`, the Recent Events viewer) does
     * not cut the summary windows short.
     */
    fun setPendingFlush(flush: (() -> Unit)?) {
        pendingFlush = flush
    }

    val count: Int
        get() = synchronized(lock) { traffic.size + essential.size }

    fun record(
        category: String,
        event: String,
        detail: String,
        payload: String? = null,
        ts: Long = System.currentTimeMillis(),
        payloadLimit: Int = maxPayloadChars,
    ) {
        // Truncate before sanitizing so large frames are never scanned in full (#121).
        val cleanDetail = sanitizeDebugText(detail.take(MAX_DETAIL_CHARS))
        val cleanPayload = payload?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.length > payloadLimit) {
                sanitizeDebugText(raw.take(payloadLimit)) + "…[truncated ${raw.length} chars]"
            } else {
                sanitizeDebugText(raw)
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
        val isTraffic = category in TRAFFIC_CATEGORIES
        val newSize = synchronized(lock) {
            val own = if (isTraffic) traffic else essential
            val other = if (isTraffic) essential else traffic
            val ownBudget = if (isTraffic) trafficBudget else maxEvents - trafficBudget
            if (traffic.size + essential.size >= maxEvents) {
                // When full, at least one class is at or over its budget: evict from the
                // incoming class if it is, otherwise from the other class.
                if (own.size >= ownBudget || other.isEmpty()) own.removeFirstOrNull() else other.removeFirst()
            }
            if (maxEvents > 0) own.addLast(Sequenced(nextSeq++, entry))
            traffic.size + essential.size
        }
        onCountChanged?.invoke(newSize)
    }

    fun snapshot(): List<DebugEvent> = synchronized(lock) {
        val merged = ArrayList<DebugEvent>(traffic.size + essential.size)
        var i = 0
        var j = 0
        while (i < traffic.size || j < essential.size) {
            val takeTraffic = j >= essential.size ||
                (i < traffic.size && traffic[i].seq < essential[j].seq)
            merged += if (takeTraffic) traffic[i++].event else essential[j++].event
        }
        merged
    }

    fun toNdjson(): String {
        pendingFlush?.invoke()
        val list = snapshot()
        if (list.isEmpty()) return ""
        return buildString {
            for (item in list) {
                appendLine(encodeAgentsTermJson(item))
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            traffic.clear()
            essential.clear()
        }
        onCountChanged?.invoke(0)
    }

    private class Sequenced(val seq: Long, val event: DebugEvent)

    private companion object {
        const val MAX_DETAIL_CHARS = 1000
        val TRAFFIC_CATEGORIES = setOf("ws", "http")
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
    return encodeAgentsTermJson(snapshot)
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
