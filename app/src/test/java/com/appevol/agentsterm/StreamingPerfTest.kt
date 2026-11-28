package com.appevol.agentsterm

import com.appevol.agentsterm.data.AgentsTermApiClient
import com.appevol.agentsterm.data.AgentsTermRepository
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionSettings
import com.appevol.agentsterm.data.DebugRecorder
import com.appevol.agentsterm.data.MessageSegment
import com.appevol.agentsterm.data.TabMessagesResponse
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.ToolCallItem
import com.appevol.agentsterm.data.WsFrameLogAggregator
import com.appevol.agentsterm.data.WsServerEnvelope
import com.appevol.agentsterm.data.formatIsoUtc
import com.appevol.agentsterm.data.sanitizeDebugText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPerfTest {

    private class InMemorySettings(
        private var connection: ConnectionConfig = ConnectionConfig(),
    ) : ConnectionSettings {
        private var lastTabId: String? = null
        override fun loadConnection(): ConnectionConfig = connection
        override fun saveConnection(config: ConnectionConfig) {
            connection = config
        }
        override fun loadLastTabId(): String? = lastTabId
        override fun saveLastTabId(tabId: String?) {
            lastTabId = tabId
        }
        override fun loadLastCwd(host: String): String? = null
        override fun saveLastCwd(host: String, cwd: String?) = Unit
    }

    private class FakeWebSocket(
        private val onOpenCb: () -> Unit,
        private val onEventCb: (WsServerEnvelope) -> Unit,
    ) : WebSocket {
        fun emitOpen() = onOpenCb()
        fun emitEvent(env: WsServerEnvelope) = onEventCb(env)
        override fun request(): Request = Request.Builder().url("http://127.0.0.1:7501/ws").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private class FakeApi(
        private val tabMessages: Map<String, List<ChatMessage>> = emptyMap(),
    ) : AgentsTermApiClient() {
        val sockets = mutableListOf<FakeWebSocket>()
        var getMessagesEnabled = false

        override suspend fun markRead(config: ConnectionConfig, tabId: String) = Unit

        override suspend fun getTabMessages(config: ConnectionConfig, tabId: String): TabMessagesResponse {
            if (!getMessagesEnabled) {
                error("refresh disabled in test")
            }
            return TabMessagesResponse(
                tab = TabRecord(id = tabId, title = tabId, agent = "agy", cwd = "/tmp"),
                messages = tabMessages[tabId].orEmpty(),
            )
        }

        override fun openWebSocket(
            config: ConnectionConfig,
            onOpen: () -> Unit,
            onEvent: (WsServerEnvelope) -> Unit,
            onClosed: (String?, Int?) -> Unit,
        ): WebSocket = FakeWebSocket(onOpen, onEvent).also { sockets.add(it) }
    }

    private val testConfig = ConnectionConfig(host = "127.0.0.1", port = 7501, token = "tok")

    @Test
    fun activeTabDeltasCoalesceWithLeadingAndTrailingFlush() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nowMs = 1_000L
        val api = FakeApi()
        val repo = AgentsTermRepository(
            api = api,
            settings = InMemorySettings(testConfig),
            deltaFlushIntervalMs = 60L,
            clockMs = { nowMs },
            ioDispatcher = dispatcher,
        )

        val tab = TabRecord(id = "t1", title = "Tab 1", agent = "agy", cwd = "/tmp", status = "working")
        repo.connect(testConfig)
        val socket = api.sockets.single()
        socket.emitOpen()
        socket.emitEvent(WsServerEnvelope(type = "snapshot", tabs = listOf(tab), activeTabId = "t1"))
        socket.emitEvent(
            WsServerEnvelope(
                type = "message_created",
                tabId = "t1",
                message = ChatMessage(id = "m1", tabId = "t1", role = "assistant", text = "", status = "streaming"),
            ),
        )
        runCurrent()

        val emissions = mutableListOf<String>()
        val collectJob = launch(dispatcher) {
            repo.state.collect { ws ->
                ws.activeMessages.singleOrNull()?.text?.let { emissions.add(it) }
            }
        }
        runCurrent()
        emissions.clear()

        // First delta flushes immediately (leading edge).
        socket.emitEvent(WsServerEnvelope(type = "message_delta", tabId = "t1", messageId = "m1", textDelta = "chunk0 "))
        runCurrent()
        assertEquals("chunk0 ", repo.state.value.activeMessages.single().text)

        // Rapid burst within the 60ms window is coalesced into StringBuilder without emitting state.
        for (i in 1..9) {
            nowMs += 5L
            socket.emitEvent(
                WsServerEnvelope(type = "message_delta", tabId = "t1", messageId = "m1", textDelta = "chunk$i "),
            )
        }
        runCurrent()
        assertEquals("chunk0 ", repo.state.value.activeMessages.single().text)

        // Once the 60ms throttle window elapses, all 9 buffered deltas flush in one state emission.
        nowMs += 60L
        advanceTimeBy(60L)
        runCurrent()
        val expected = (0..9).joinToString("") { "chunk$it " }
        assertEquals(expected, repo.state.value.activeMessages.single().text)
        assertEquals(listOf("chunk0 ", expected), emissions)

        collectJob.cancel()
    }

    @Test
    fun structuralEventFlushesBufferedDeltasBeforeApplyingToolUpdate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nowMs = 1_000L
        val api = FakeApi()
        val repo = AgentsTermRepository(
            api = api,
            settings = InMemorySettings(testConfig),
            deltaFlushIntervalMs = 60L,
            clockMs = { nowMs },
            ioDispatcher = dispatcher,
        )

        val tab = TabRecord(id = "t1", title = "Tab 1", agent = "agy", cwd = "/tmp", status = "working")
        repo.connect(testConfig)
        val socket = api.sockets.single()
        socket.emitOpen()
        socket.emitEvent(WsServerEnvelope(type = "snapshot", tabs = listOf(tab), activeTabId = "t1"))
        socket.emitEvent(
            WsServerEnvelope(
                type = "message_created",
                tabId = "t1",
                message = ChatMessage(id = "m1", tabId = "t1", role = "assistant", text = "", status = "streaming"),
            ),
        )
        // Leading delta flushes immediately, second delta is buffered inside the 60ms window.
        socket.emitEvent(WsServerEnvelope(type = "message_delta", tabId = "t1", messageId = "m1", textDelta = "Before "))
        nowMs += 10L
        socket.emitEvent(WsServerEnvelope(type = "message_delta", tabId = "t1", messageId = "m1", textDelta = "tool."))
        // Tool call arrives before the 60ms timer fires: buffered text must be flushed first.
        socket.emitEvent(
            WsServerEnvelope(
                type = "tool_update",
                tabId = "t1",
                messageId = "m1",
                toolCall = ToolCallItem(id = "tc1", name = "Bash", inputSummary = "ls", status = "running"),
            ),
        )
        nowMs += 10L
        socket.emitEvent(WsServerEnvelope(type = "message_delta", tabId = "t1", messageId = "m1", textDelta = "\n\nAfter tool."))
        nowMs += 60L
        advanceTimeBy(60L)
        runCurrent()

        val msg = repo.state.value.activeMessages.single()
        assertEquals("Before tool.\n\nAfter tool.", msg.text)
        assertEquals(
            listOf(
                MessageSegment(type = "text", text = "Before tool."),
                MessageSegment(type = "tool", toolCallId = "tc1"),
                MessageSegment(type = "text", text = "After tool."),
            ),
            msg.segments,
        )
    }

    @Test
    fun backgroundTabDeltasDoNotEmitWorkspaceStateUntilSelected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeApi()
        val repo = AgentsTermRepository(
            api = api,
            settings = InMemorySettings(testConfig),
            deltaFlushIntervalMs = 60L,
            ioDispatcher = dispatcher,
        )

        val t1 = TabRecord(id = "t1", title = "Active", agent = "agy", cwd = "/tmp", status = "idle")
        val t2 = TabRecord(id = "t2", title = "Background", agent = "agy", cwd = "/tmp", status = "working")
        repo.connect(testConfig)
        val socket = api.sockets.single()
        socket.emitOpen()
        socket.emitEvent(WsServerEnvelope(type = "snapshot", tabs = listOf(t1, t2), activeTabId = "t1"))
        socket.emitEvent(
            WsServerEnvelope(
                type = "message_created",
                tabId = "t2",
                message = ChatMessage(id = "m2", tabId = "t2", role = "assistant", text = "", status = "streaming"),
            ),
        )
        runCurrent()

        var stateEmissions = 0
        val collectJob = launch(dispatcher) {
            repo.state.collect { stateEmissions++ }
        }
        runCurrent()
        stateEmissions = 0

        repeat(25) { i ->
            socket.emitEvent(
                WsServerEnvelope(type = "message_delta", tabId = "t2", messageId = "m2", textDelta = "part$i "),
            )
        }
        advanceTimeBy(500L)
        runCurrent()

        // Background tab streaming emitted zero WorkspaceState updates.
        assertEquals(0, stateEmissions)
        // Debug snapshot still sees all buffered text.
        val expectedText = (0 until 25).joinToString("") { "part$it " }
        assertEquals(expectedText, repo.snapshotStateWithPendingDeltas().messagesByTab["t2"]?.single()?.text)

        // Switching to t2 immediately flushes its buffered deltas into activeMessages.
        repo.selectTab("t2")
        assertEquals(expectedText, repo.state.value.activeMessages.single().text)

        collectJob.cancel()
    }

    @Test
    fun deltaLogAggregatorBatchesFramesAndSanitizerFastPathAvoidsAllocations() {
        var nowMs = 10_000L
        val recorder = DebugRecorder(maxEvents = 600)
        val aggregator = WsFrameLogAggregator(
            debugRecorder = recorder,
            flushIntervalMs = 5_000L,
            clockMs = { nowMs },
        )

        repeat(500) { i ->
            nowMs += 5L // 2.5s total, still within the 5s batch window
            aggregator.onFrame(
                WsServerEnvelope(
                    type = "message_delta",
                    tabId = "t${i % 3}",
                    messageId = "m1",
                    textDelta = "abcd",
                    thinkingDelta = if (i % 2 == 0) "xy" else null,
                ),
                rawText = "{}",
            )
        }
        assertEquals(0, recorder.count)

        nowMs += 3_000L // crosses 5s window on next delta
        aggregator.onFrame(
            WsServerEnvelope(type = "message_delta", tabId = "t0", messageId = "m1", textDelta = "z"),
            rawText = "{}",
        )
        assertEquals(1, recorder.count)
        val batch = recorder.snapshot().single()
        assertEquals("ws_frame_batch", batch.event)
        assertTrue(batch.detail.contains("frames=501"))
        assertTrue(batch.detail.contains("deltas=501"))
        assertTrue(batch.detail.contains("textChars=2001"))
        assertTrue(batch.detail.contains("thinkingChars=500"))
        assertTrue(batch.detail.contains("tabs=3"))

        // Clean strings take the zero-allocation fast path.
        val clean = "type=message_updated tab=t1 msg=m1 msgStatus=complete"
        assertSame(clean, sanitizeDebugText(clean))
        assertEquals("1970-01-01T00:00:00.000Z", formatIsoUtc(0L))
    }
}
