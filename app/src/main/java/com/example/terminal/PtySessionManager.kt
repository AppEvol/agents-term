package com.example.terminal

import androidx.compose.runtime.Immutable
import com.example.data.ConnectionConfig
import com.example.data.ConnectionState
import com.example.data.DebugRecorder
import com.example.data.WorkspaceState
import com.example.data.decodeHerdrJson
import com.example.data.encodeHerdrJson
import com.example.data.isAuthStatusCode
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class PtyConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    EXITED,
    ERROR,
}

sealed interface PtyPasteOutcome {
    data object Sent : PtyPasteOutcome
    data class NeedsConfirmation(val text: String) : PtyPasteOutcome
    data object Empty : PtyPasteOutcome
}

@Immutable
data class PtyTabUiState(
    val connectionState: PtyConnectionState = PtyConnectionState.IDLE,
    val frame: TerminalFrame? = null,
    val title: String? = null,
    val exitCode: Int? = null,
    val exitSignal: Int? = null,
    val errorMessage: String? = null,
    val isReplaying: Boolean = false,
    val appliedSeq: Long = 0L,
    val sessionId: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class PtyServerControlMessage(
    val type: String,
    val sessionId: String? = null,
    val cols: Int? = null,
    val rows: Int? = null,
    val baseSeq: Long? = null,
    val nextSeq: Long? = null,
    val replayFrom: Long? = null,
    val truncated: Boolean = false,
    val running: Boolean = true,
    val exitCode: Int? = null,
    val signal: Int? = null,
    val seq: Long? = null,
    val error: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class PtyResizePayload(
    val type: String = "resize",
    val cols: Int,
    val rows: Int,
)

@JsonClass(generateAdapter = true)
internal data class PtyRestartPayload(
    val type: String = "restart",
    val cols: Int,
    val rows: Int,
)

data class PtyConnectRequest(
    val config: ConnectionConfig,
    val tabId: String,
    val afterSeq: Long,
    val sessionId: String?,
    val cols: Int,
    val rows: Int,
)

/** Abstraction over OkHttp WebSocket for deterministic unit testing. */
interface PtySocketClient {
    fun openPtySocket(request: PtyConnectRequest, listener: WebSocketListener): WebSocket
}

class OkHttpPtySocketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : PtySocketClient {
    override fun openPtySocket(request: PtyConnectRequest, listener: WebSocketListener): WebSocket {
        val url = request.config.ptyWsUrl(
            tabId = request.tabId,
            afterSeq = request.afterSeq,
            sessionId = request.sessionId,
            cols = request.cols,
            rows = request.rows,
        )
        val reqBuilder = Request.Builder().url(url)
        if (request.config.token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${request.config.token.trim()}")
        }
        return client.newWebSocket(reqBuilder.build(), listener)
    }
}

/**
 * Owns one interactive PTY tab's `libghostty-vt` terminal model and WebSocket stream.
 *
 * Raw user input and PTY output bytes are intentionally never written to `DebugRecorder`,
 * Android logcat, or chat history.
 */
class PtyTabSession(
    val tabId: String,
    initialConfig: ConnectionConfig,
    initialPalette: TerminalThemePalette,
    private val socketClient: PtySocketClient,
    private val debugRecorder: DebugRecorder? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val frameFlushIntervalMs: Long = DEFAULT_FRAME_FLUSH_MS,
    private val reconnectBaseDelayMs: Long = DEFAULT_RECONNECT_BASE_MS,
    private val reconnectMaxDelayMs: Long = DEFAULT_RECONNECT_MAX_MS,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateLock = Any()

    private var config: ConnectionConfig = initialConfig
    private var palette: TerminalThemePalette = initialPalette
    private var engine: GhosttyTerminalEngine? = null

    private var desiredCols = DEFAULT_COLS
    private var desiredRows = DEFAULT_ROWS
    private var desiredCellWidthPx = DEFAULT_CELL_WIDTH_PX
    private var desiredCellHeightPx = DEFAULT_CELL_HEIGHT_PX
    private var daemonCols = DEFAULT_COLS
    private var daemonRows = DEFAULT_ROWS
    private var engineCols = DEFAULT_COLS
    private var engineRows = DEFAULT_ROWS

    private var webSocket: WebSocket? = null
    private var socketGeneration = 0L
    private var reconnectJob: Job? = null
    private var flushJob: Job? = null
    private var reconnectAttempt = 0
    private var shouldStayConnected = false
    private var isInForeground = true
    private var closed = false
    private var pendingRestart = false
    private var serverErrorReceived = false

    private var currentSessionId: String? = null
    private var appliedSeq = 0L
    private var isReplaying = false

    private val _uiState = MutableStateFlow(PtyTabUiState())
    val uiState: StateFlow<PtyTabUiState> = _uiState.asStateFlow()

    private fun ensureEngineLocked(): GhosttyTerminalEngine {
        engine?.let { return it }
        val created = GhosttyTerminalEngine(
            initialCols = desiredCols,
            initialRows = desiredRows,
        )
        created.setThemeColors(palette)
        engine = created
        engineCols = desiredCols
        engineRows = desiredRows
        return created
    }

    fun updateConfig(newConfig: ConnectionConfig) {
        val shouldReconnect = synchronized(stateLock) {
            if (closed || config == newConfig) {
                false
            } else {
                config = newConfig
                shouldStayConnected && newConfig.isValid
            }
        }
        if (shouldReconnect) {
            connect()
        }
    }

    fun updatePalette(newPalette: TerminalThemePalette) {
        val frame = synchronized(stateLock) {
            if (closed || palette == newPalette) return
            palette = newPalette
            val eng = engine ?: return
            eng.setThemeColors(newPalette)
            eng.captureFrame(forceFull = true)
        }
        if (frame != null) {
            publishFrame(frame)
        }
    }

    fun ensureConnected() {
        val shouldOpen = synchronized(stateLock) {
            if (closed || !config.isValid) return
            shouldStayConnected = true
            webSocket == null && _uiState.value.connectionState != PtyConnectionState.CONNECTING
        }
        if (shouldOpen) {
            connect()
        }
    }

    fun connect() {
        val params = prepareConnectParams() ?: return
        params.previousSocket?.cancel()

        debugRecorder?.record(
            category = "pty",
            event = "pty_connecting",
            detail = "tab=$tabId afterSeq=${params.afterSeq} cols=${params.cols} rows=${params.rows} " +
                "hasSession=${params.sessionId != null}",
        )

        val socketClosed = AtomicBoolean(false)
        val listener = buildSocketListener(params.generation, socketClosed)
        val opened = socketClient.openPtySocket(
            request = PtyConnectRequest(
                config = params.config,
                tabId = tabId,
                afterSeq = params.afterSeq,
                sessionId = params.sessionId,
                cols = params.cols,
                rows = params.rows,
            ),
            listener = listener,
        )

        val superseded = synchronized(stateLock) {
            if (closed || params.generation != socketGeneration || !shouldStayConnected) {
                true
            } else {
                if (!socketClosed.get()) {
                    webSocket = opened
                }
                false
            }
        }
        if (superseded) {
            opened.cancel()
        }
    }

    private fun prepareConnectParams(): PtyConnectParams? = synchronized(stateLock) {
        if (closed || !config.isValid) return null
        shouldStayConnected = true
        isReplaying = false
        serverErrorReceived = false
        ensureEngineLocked()
        reconnectJob?.cancel()
        reconnectJob = null
        val previous = webSocket
        webSocket = null
        val nextGen = ++socketGeneration
        _uiState.update { cur ->
            cur.copy(
                connectionState = if (cur.sessionId != null) {
                    PtyConnectionState.RECONNECTING
                } else {
                    PtyConnectionState.CONNECTING
                },
                isReplaying = false,
                errorMessage = null,
            )
        }
        PtyConnectParams(
            config = config,
            generation = nextGen,
            afterSeq = appliedSeq,
            sessionId = currentSessionId,
            cols = desiredCols,
            rows = desiredRows,
            previousSocket = previous,
        )
    }

    private fun buildSocketListener(gen: Long, socketClosed: AtomicBoolean): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val restartPayload = synchronized(stateLock) {
                if (closed || gen != socketGeneration || !shouldStayConnected) return
                this@PtyTabSession.webSocket = webSocket
                reconnectAttempt = 0
                if (pendingRestart) {
                    pendingRestart = false
                    PtyRestartPayload(cols = desiredCols, rows = desiredRows)
                } else {
                    null
                }
            }
            if (restartPayload != null) {
                webSocket.send(encodeHerdrJson(restartPayload))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextFrame(gen, webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleBinaryFrame(gen, webSocket, bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (socketClosed.compareAndSet(false, true)) {
                handleSocketClosed(gen, reason.ifBlank { null }, null)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socketClosed.compareAndSet(false, true)) {
                handleSocketClosed(gen, reason.ifBlank { null }, null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socketClosed.compareAndSet(false, true)) {
                val reason = response?.let { "HTTP ${it.code}: ${it.message}" }
                    ?: t.message
                    ?: "Terminal connection lost"
                handleSocketClosed(gen, reason, response?.code)
            }
        }
    }

    private fun handleTextFrame(generation: Long, socket: WebSocket, raw: String) {
        val msg = runCatching { decodeHerdrJson<PtyServerControlMessage>(raw) }.getOrNull() ?: return
        val resizeAfterReplay = synchronized(stateLock) {
            if (closed || generation != socketGeneration) return
            if (webSocket == null) webSocket = socket
            val eng = ensureEngineLocked()
            when (msg.type) {
                "ready" -> {
                    handleReadyLocked(eng, msg)
                    null
                }

                "replay_end" -> handleReplayEndLocked(eng, msg)

                "exit" -> {
                    handleExitLocked(eng, msg)
                    null
                }

                "error" -> {
                    handleErrorLocked(msg)
                    null
                }

                else -> null
            }
        }
        resizeAfterReplay?.let { (c, r) ->
            socket.send(encodeHerdrJson(PtyResizePayload(cols = c, rows = r)))
        }
    }

    private fun handleReadyLocked(eng: GhosttyTerminalEngine, msg: PtyServerControlMessage) {
        val serverCols = (msg.cols ?: desiredCols).coerceIn(MIN_COLS, MAX_COLS)
        val serverRows = (msg.rows ?: desiredRows).coerceIn(MIN_ROWS, MAX_ROWS)
        val replayFrom = msg.replayFrom ?: 0L
        val sessionChanged = msg.sessionId != null && msg.sessionId != currentSessionId
        val isColdReset = msg.truncated || currentSessionId == null || sessionChanged
        currentSessionId = msg.sessionId
        daemonCols = serverCols
        daemonRows = serverRows
        isReplaying = true
        if (isColdReset) {
            eng.reset()
            eng.setThemeColors(palette)
            eng.resize(serverCols, serverRows, desiredCellWidthPx, desiredCellHeightPx)
            engineCols = serverCols
            engineRows = serverRows
            appliedSeq = replayFrom
        } else if (engineCols != serverCols || engineRows != serverRows) {
            eng.resize(serverCols, serverRows, desiredCellWidthPx, desiredCellHeightPx)
            engineCols = serverCols
            engineRows = serverRows
        }
        debugRecorder?.record(
            category = "pty",
            event = "pty_ready",
            detail = "tab=$tabId cols=$serverCols rows=$serverRows replayFrom=$replayFrom " +
                "truncated=${msg.truncated} running=${msg.running}",
        )
        val nextConnState = if (msg.running) PtyConnectionState.CONNECTED else PtyConnectionState.EXITED
        _uiState.update {
            it.copy(
                connectionState = nextConnState,
                isReplaying = true,
                exitCode = if (msg.running) null else msg.exitCode,
                exitSignal = if (msg.running) null else msg.signal,
                errorMessage = null,
                appliedSeq = appliedSeq,
                sessionId = currentSessionId,
            )
        }
    }

    private fun handleReplayEndLocked(
        eng: GhosttyTerminalEngine,
        msg: PtyServerControlMessage,
    ): Pair<Int, Int>? {
        isReplaying = false
        msg.seq?.let { if (it > appliedSeq) appliedSeq = it }
        val resizeTarget = if (desiredCols != daemonCols || desiredRows != daemonRows) {
            eng.resize(desiredCols, desiredRows, desiredCellWidthPx, desiredCellHeightPx)
            engineCols = desiredCols
            engineRows = desiredRows
            daemonCols = desiredCols
            daemonRows = desiredRows
            desiredCols to desiredRows
        } else {
            null
        }
        val fullFrame = eng.captureFrame(forceFull = true)
        _uiState.update { cur ->
            cur.withFrameIfNewer(fullFrame).copy(
                isReplaying = false,
                appliedSeq = appliedSeq,
            )
        }
        return resizeTarget
    }

    private fun handleExitLocked(eng: GhosttyTerminalEngine, msg: PtyServerControlMessage) {
        val frame = eng.captureFrame(forceFull = false)
        debugRecorder?.record(
            category = "pty",
            event = "pty_exit",
            detail = "tab=$tabId exitCode=${msg.exitCode} signal=${msg.signal}",
        )
        _uiState.update { cur ->
            cur.withFrameIfNewer(frame).copy(
                connectionState = PtyConnectionState.EXITED,
                exitCode = msg.exitCode,
                exitSignal = msg.signal,
            )
        }
    }

    private fun handleErrorLocked(msg: PtyServerControlMessage) {
        serverErrorReceived = true
        val errText = msg.error ?: "PTY error"
        debugRecorder?.record(
            category = "error",
            event = "pty_error",
            detail = "tab=$tabId $errText",
        )
        _uiState.update {
            it.copy(
                connectionState = PtyConnectionState.ERROR,
                errorMessage = errText,
            )
        }
    }

    private fun handleBinaryFrame(generation: Long, socket: WebSocket, bytes: ByteString) {
        if (bytes.size <= SEQ_HEADER_BYTES) return
        val raw = bytes.toByteArray()
        val startSeq = ByteBuffer.wrap(raw, 0, SEQ_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long
        val payloadLen = raw.size - SEQ_HEADER_BYTES
        val endSeq = startSeq + payloadLen

        var repliesToSend: ByteArray? = null
        var scheduleFlush = false
        synchronized(stateLock) {
            if (closed || generation != socketGeneration) return
            if (webSocket == null) webSocket = socket
            if (endSeq <= appliedSeq) return
            val skip = if (startSeq < appliedSeq) (appliedSeq - startSeq).toInt() else 0
            val offset = SEQ_HEADER_BYTES + skip
            val count = payloadLen - skip
            if (count <= 0) return

            val eng = ensureEngineLocked()
            val replies = eng.writeVt(
                data = raw,
                offset = offset,
                length = count,
                suppressReplies = isReplaying,
            )
            appliedSeq = endSeq
            if (!isReplaying && replies != null && replies.isNotEmpty()) {
                repliesToSend = replies
            }
            if (!isReplaying) {
                scheduleFlush = true
            }
        }

        repliesToSend?.let { socket.send(it.toByteString()) }
        if (scheduleFlush) {
            scheduleFrameFlush()
        }
    }

    private fun scheduleFrameFlush() {
        synchronized(stateLock) {
            if (closed || flushJob?.isActive == true) return
            flushJob = scope.launch {
                if (frameFlushIntervalMs > 0L) {
                    delay(frameFlushIntervalMs)
                }
                flushFrameNow()
            }
        }
    }

    private fun publishFrame(frame: TerminalFrame, seqSnapshot: Long? = null) {
        _uiState.update { cur -> cur.withFrameIfNewer(frame, seqSnapshot) }
    }

    private fun flushFrameNow() {
        val snapshot = synchronized(stateLock) {
            if (closed) return
            flushJob = null
            val frame = engine?.captureFrame(forceFull = false) ?: return
            frame to appliedSeq
        }
        publishFrame(snapshot.first, seqSnapshot = snapshot.second)
    }

    private fun handleSocketClosed(generation: Long, reason: String?, httpStatusCode: Int?) {
        val shouldRetry = synchronized(stateLock) {
            if (closed || generation != socketGeneration) return
            webSocket = null
            isReplaying = false
            val curState = _uiState.value
            val outcome = resolveSocketCloseOutcome(
                PtySocketCloseInput(
                    currentState = curState,
                    serverErrorReceived = serverErrorReceived,
                    shouldStayConnected = shouldStayConnected,
                    isInForeground = isInForeground,
                    reason = reason,
                    httpStatusCode = httpStatusCode,
                ),
            )
            _uiState.update { cur ->
                cur.copy(
                    connectionState = outcome.nextState,
                    isReplaying = false,
                    errorMessage = outcome.errorMessage,
                )
            }
            debugRecorder?.record(
                category = "pty",
                event = "pty_closed",
                detail = "tab=$tabId retry=${outcome.shouldRetry} status=${httpStatusCode ?: 0}",
            )
            outcome.shouldRetry
        }

        if (shouldRetry) {
            scheduleReconnect(generation)
        }
    }

    private fun canReconnectLocked(expectedGeneration: Long): Boolean {
        val active = !closed && expectedGeneration == socketGeneration
        return active && shouldStayConnected && isInForeground
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        synchronized(stateLock) {
            if (!canReconnectLocked(expectedGeneration)) return
            reconnectJob?.cancel()
            val delayMs = (reconnectBaseDelayMs * (1 shl reconnectAttempt.coerceAtMost(4)))
                .coerceAtMost(reconnectMaxDelayMs)
            reconnectAttempt++
            reconnectJob = scope.launch {
                delay(delayMs)
                val stillValid = synchronized(stateLock) { canReconnectLocked(expectedGeneration) }
                if (stillValid) {
                    connect()
                }
            }
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        val shouldReconnect = synchronized(stateLock) {
            if (closed) return
            isInForeground = inForeground
            if (!inForeground) {
                reconnectJob?.cancel()
                reconnectJob = null
                false
            } else {
                val activeConn = _uiState.value.connectionState
                val needsSocket = shouldStayConnected && webSocket == null
                needsSocket &&
                    activeConn != PtyConnectionState.EXITED &&
                    activeConn != PtyConnectionState.CONNECTING &&
                    activeConn != PtyConnectionState.ERROR
            }
        }
        if (shouldReconnect) {
            connect()
        }
    }

    fun resize(cols: Int, rows: Int, cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX, cellHeightPx: Int = DEFAULT_CELL_HEIGHT_PX) {
        val clampedCols = cols.coerceIn(MIN_COLS, MAX_COLS)
        val clampedRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        val (sendToDaemon, frame) = synchronized(stateLock) {
            if (closed) return
            desiredCellWidthPx = cellWidthPx.coerceAtLeast(1)
            desiredCellHeightPx = cellHeightPx.coerceAtLeast(1)
            if (desiredCols == clampedCols && desiredRows == clampedRows) {
                return
            }
            desiredCols = clampedCols
            desiredRows = clampedRows
            val eng = engine
            val updatedFrame = if (eng != null && !isReplaying) {
                eng.resize(clampedCols, clampedRows, desiredCellWidthPx, desiredCellHeightPx)
                engineCols = clampedCols
                engineRows = clampedRows
                daemonCols = clampedCols
                daemonRows = clampedRows
                eng.captureFrame(forceFull = true)
            } else {
                null
            }
            val shouldSend = webSocket != null && !isReplaying
            shouldSend to updatedFrame
        }
        if (frame != null) {
            publishFrame(frame)
        }
        if (sendToDaemon) {
            sendResizeControl(clampedCols, clampedRows)
        }
    }

    private fun sendResizeControl(cols: Int, rows: Int) {
        val ws = synchronized(stateLock) { webSocket } ?: return
        ws.send(encodeHerdrJson(PtyResizePayload(cols = cols, rows = rows)))
    }

    fun restart() {
        val (ws, cols, rows) = synchronized(stateLock) {
            if (closed) return
            val currentWs = webSocket
            if (currentWs != null) {
                pendingRestart = false
                Triple(currentWs, desiredCols, desiredRows)
            } else {
                pendingRestart = true
                currentSessionId = null
                appliedSeq = 0L
                Triple(null, desiredCols, desiredRows)
            }
        }
        if (ws != null) {
            ws.send(encodeHerdrJson(PtyRestartPayload(cols = cols, rows = rows)))
        } else {
            connect()
        }
    }

    /** Sends raw bytes to the PTY; never logged or persisted. */
    fun sendRawBytes(bytes: ByteArray, snapToBottom: Boolean = true) {
        if (bytes.isEmpty()) return
        val (ws, frame) = synchronized(stateLock) {
            if (closed) return
            val updatedFrame = if (snapToBottom && engine != null) {
                engine?.scrollViewportBottom()
                engine?.captureFrame(forceFull = false)
            } else {
                null
            }
            webSocket to updatedFrame
        }
        if (frame != null) {
            publishFrame(frame)
        }
        ws?.send(bytes.toByteString())
    }

    /** Sends committed UTF-8 text from the IME (applying latched Ctrl/Alt if provided). */
    fun sendText(text: String, latchedMods: Int = GhosttyMods.NONE) {
        if (text.isEmpty()) return
        if (latchedMods != GhosttyMods.NONE && text.length == 1) {
            val ch = text[0]
            val key = GhosttyKey.fromAsciiChar(ch)
            if (key != GhosttyKey.UNIDENTIFIED) {
                val encoded = synchronized(stateLock) {
                    if (closed) return
                    ensureEngineLocked().encodeKey(
                        key = key,
                        mods = latchedMods,
                        action = GhosttyKeyAction.PRESS,
                        utf8 = text,
                        unshiftedCodepoint = ch.lowercaseChar().code,
                    )
                }
                if (encoded != null && encoded.isNotEmpty()) {
                    sendRawBytes(encoded)
                    return
                }
            }
        }
        sendRawBytes(text.encodeToByteArray())
    }

    /** Encodes a physical key using Ghostty's mode-synchronized key encoder and writes to PTY. */
    fun sendKey(
        key: Int,
        mods: Int = GhosttyMods.NONE,
        utf8: String? = null,
        unshiftedCodepoint: Int = 0,
    ) {
        val encoded = synchronized(stateLock) {
            if (closed) return
            ensureEngineLocked().encodeKey(
                key = key,
                mods = mods,
                action = GhosttyKeyAction.PRESS,
                utf8 = utf8,
                unshiftedCodepoint = unshiftedCodepoint,
            )
        }
        if (encoded != null && encoded.isNotEmpty()) {
            sendRawBytes(encoded)
        }
    }

    /** Pastes clipboard text via Ghostty's paste API (bracketed paste + unsafe newline safety). */
    fun pasteText(text: String, allowUnsafe: Boolean = false): PtyPasteOutcome {
        if (text.isEmpty()) return PtyPasteOutcome.Empty
        val encoded = synchronized(stateLock) {
            if (closed) return PtyPasteOutcome.Empty
            ensureEngineLocked().encodePaste(text, allowUnsafe = allowUnsafe)
        }
        if (encoded == null) {
            return if (!allowUnsafe) PtyPasteOutcome.NeedsConfirmation(text) else PtyPasteOutcome.Empty
        }
        sendRawBytes(encoded)
        return PtyPasteOutcome.Sent
    }

    fun scrollLines(deltaLines: Int) {
        if (deltaLines == 0) return
        val frame = synchronized(stateLock) {
            if (closed) return
            val eng = engine ?: return
            eng.scrollViewportDelta(deltaLines)
            eng.captureFrame(forceFull = false)
        }
        if (frame != null) {
            publishFrame(frame)
        }
    }

    fun scrollToBottom() {
        val frame = synchronized(stateLock) {
            if (closed) return
            val eng = engine ?: return
            eng.scrollViewportBottom()
            eng.captureFrame(forceFull = false)
        }
        if (frame != null) {
            publishFrame(frame)
        }
    }

    override fun close() {
        val socketToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            shouldStayConnected = false
            pendingRestart = false
            isReplaying = false
            socketGeneration++
            reconnectJob?.cancel()
            reconnectJob = null
            flushJob?.cancel()
            flushJob = null
            val ws = webSocket
            webSocket = null
            engine?.close()
            engine = null
            ws
        }
        scope.cancel()
        socketToClose?.close(1000, "Tab closed")
    }

    companion object {
        const val SEQ_HEADER_BYTES = 8
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val MIN_COLS = 10
        const val MAX_COLS = 512
        const val MIN_ROWS = 4
        const val MAX_ROWS = 256
        private const val DEFAULT_CELL_WIDTH_PX = 10
        private const val DEFAULT_CELL_HEIGHT_PX = 20
        private const val DEFAULT_FRAME_FLUSH_MS = 16L
        private const val DEFAULT_RECONNECT_BASE_MS = 1_500L
        private const val DEFAULT_RECONNECT_MAX_MS = 15_000L
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404

private data class PtyConnectParams(
    val config: ConnectionConfig,
    val generation: Long,
    val afterSeq: Long,
    val sessionId: String?,
    val cols: Int,
    val rows: Int,
    val previousSocket: WebSocket?,
)

private data class PtySocketCloseInput(
    val currentState: PtyTabUiState,
    val serverErrorReceived: Boolean,
    val shouldStayConnected: Boolean,
    val isInForeground: Boolean,
    val reason: String?,
    val httpStatusCode: Int?,
)

private data class PtySocketCloseOutcome(
    val nextState: PtyConnectionState,
    val errorMessage: String?,
    val shouldRetry: Boolean,
)

private fun PtyTabUiState.withFrameIfNewer(
    nextFrame: TerminalFrame?,
    seqSnapshot: Long? = null,
): PtyTabUiState {
    if (nextFrame == null) return this
    if (frame != null && nextFrame.revision < frame.revision) return this
    return copy(
        frame = nextFrame,
        title = nextFrame.title ?: title,
        appliedSeq = seqSnapshot ?: appliedSeq,
    )
}

private fun resolveSocketCloseOutcome(input: PtySocketCloseInput): PtySocketCloseOutcome {
    val isAuth = isAuthStatusCode(input.httpStatusCode)
    val isNonRetryableHttp = isAuth ||
        input.httpStatusCode == HTTP_BAD_REQUEST ||
        input.httpStatusCode == HTTP_NOT_FOUND
    val exited = input.currentState.connectionState == PtyConnectionState.EXITED
    val alreadyError = input.serverErrorReceived ||
        input.currentState.connectionState == PtyConnectionState.ERROR
    val retry = input.shouldStayConnected &&
        input.isInForeground &&
        !isNonRetryableHttp &&
        !exited &&
        !alreadyError
    val nextState = when {
        exited -> PtyConnectionState.EXITED
        alreadyError -> PtyConnectionState.ERROR
        !input.shouldStayConnected -> PtyConnectionState.IDLE
        isNonRetryableHttp -> PtyConnectionState.ERROR
        retry -> PtyConnectionState.RECONNECTING
        else -> PtyConnectionState.IDLE
    }
    val errorMessage = resolveSocketCloseErrorMessage(
        nextState = nextState,
        alreadyError = alreadyError,
        existingMessage = input.currentState.errorMessage,
        isAuth = isAuth,
        reason = input.reason,
    )
    return PtySocketCloseOutcome(
        nextState = nextState,
        errorMessage = errorMessage,
        shouldRetry = retry,
    )
}

private fun resolveSocketCloseErrorMessage(
    nextState: PtyConnectionState,
    alreadyError: Boolean,
    existingMessage: String?,
    isAuth: Boolean,
    reason: String?,
): String? = when (nextState) {
    PtyConnectionState.ERROR -> {
        if (alreadyError && !existingMessage.isNullOrBlank()) {
            existingMessage
        } else {
            reason ?: if (isAuth) "Authentication failed" else "PTY connection failed"
        }
    }

    PtyConnectionState.RECONNECTING -> reason

    else -> null
}

/** Manages live [PtyTabSession] instances across tab switches and configuration changes. */
class PtySessionManager(
    private val socketClient: PtySocketClient = OkHttpPtySocketClient(),
    private val debugRecorder: DebugRecorder? = null,
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, PtyTabSession>()
    private var inForeground = true
    private var lastEndpoint: Pair<String, Int>? = null

    private fun endpointOf(config: ConnectionConfig): Pair<String, Int>? = config.host.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { it to config.port }

    fun observeWorkspace(scope: CoroutineScope, workspaceState: StateFlow<WorkspaceState>) {
        scope.launch {
            workspaceState
                .map { ws ->
                    Triple(
                        ws.config,
                        ws.connectionState == ConnectionState.CONNECTED,
                        ws.tabs.filter { it.isPtyShell }.map { it.id }.toSet(),
                    )
                }
                .distinctUntilChanged()
                .collect { (cfg, connected, openPtyIds) ->
                    updateConfig(cfg)
                    if (connected) {
                        pruneClosedTabs(openPtyIds)
                    }
                }
        }
    }

    fun getOrCreate(
        tabId: String,
        config: ConnectionConfig,
        palette: TerminalThemePalette,
    ): PtyTabSession {
        val nextEndpoint = endpointOf(config)
        var toClose: List<PtyTabSession> = emptyList()
        val session = synchronized(lock) {
            val prev = lastEndpoint
            if (prev != null && nextEndpoint != null && prev != nextEndpoint) {
                lastEndpoint = nextEndpoint
                toClose = sessions.values.toList()
                sessions.clear()
            } else if (nextEndpoint != null) {
                lastEndpoint = nextEndpoint
            }
            val existing = sessions[tabId]
            if (existing != null) {
                existing.updateConfig(config)
                existing.updatePalette(palette)
                existing
            } else {
                val created = PtyTabSession(
                    tabId = tabId,
                    initialConfig = config,
                    initialPalette = palette,
                    socketClient = socketClient,
                    debugRecorder = debugRecorder,
                )
                created.onForegroundChanged(inForeground)
                sessions[tabId] = created
                created
            }
        }
        for (stale in toClose) {
            stale.close()
        }
        return session
    }

    fun updateConfig(config: ConnectionConfig) {
        val nextEndpoint = endpointOf(config)
        val toClose: List<PtyTabSession>
        val toUpdate: List<PtyTabSession>
        synchronized(lock) {
            val prev = lastEndpoint
            if (prev != null && nextEndpoint != null && prev != nextEndpoint) {
                lastEndpoint = nextEndpoint
                toClose = sessions.values.toList()
                sessions.clear()
                toUpdate = emptyList()
            } else {
                if (nextEndpoint != null) {
                    lastEndpoint = nextEndpoint
                }
                toClose = emptyList()
                toUpdate = sessions.values.toList()
            }
        }
        for (session in toClose) {
            session.close()
        }
        for (session in toUpdate) {
            session.updateConfig(config)
        }
    }

    fun updatePalette(palette: TerminalThemePalette) {
        val list = synchronized(lock) { sessions.values.toList() }
        for (session in list) {
            session.updatePalette(palette)
        }
    }

    fun onForegroundChanged(isForeground: Boolean) {
        val list = synchronized(lock) {
            inForeground = isForeground
            sessions.values.toList()
        }
        for (session in list) {
            session.onForegroundChanged(isForeground)
        }
    }

    fun remove(tabId: String) {
        val removed = synchronized(lock) { sessions.remove(tabId) }
        removed?.close()
    }

    fun pruneClosedTabs(openPtyTabIds: Set<String>) {
        val stale = synchronized(lock) {
            val ids = sessions.keys.filter { it !in openPtyTabIds }
            ids.mapNotNull { sessions.remove(it) }
        }
        for (session in stale) {
            session.close()
        }
    }

    fun closeAll() {
        val all = synchronized(lock) {
            lastEndpoint = null
            val copy = sessions.values.toList()
            sessions.clear()
            copy
        }
        for (session in all) {
            session.close()
        }
    }
}
