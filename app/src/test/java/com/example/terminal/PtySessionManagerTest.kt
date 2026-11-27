package com.example.terminal

import com.example.data.ConnectionConfig
import com.example.data.DebugRecorder
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end JVM tests for [PtyTabSession] over a real [MockWebServer] WebSocket:
 * sequenced replay, outbound reply suppression during replay, live DSR replies,
 * reconnect resume, post-replay resize, and privacy (raw input never enters DebugRecorder).
 */
class PtySessionManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun packOutput(startSeq: Long, text: String): ByteString {
        val payload = text.encodeToByteArray()
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(startSeq)
        buf.put(payload)
        return buf.array().toByteString()
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    @Test
    fun replaySuppressesRepliesLiveRepliesForwardAndRawInputNeverEntersDebugLogs() {
        val receivedBinaries = CopyOnWriteArrayList<String>()
        val receivedTexts = CopyOnWriteArrayList<String>()
        val serverOpened = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket = webSocket
                        // Daemon reports 50x12 while Android view later wants 80x24.
                        val replayText = "REPLAY:\u001b[6nOK\r\n"
                        val replayLen = replayText.encodeToByteArray().size.toLong()
                        webSocket.send(
                            """{"type":"ready","sessionId":"sess_1","cols":50,"rows":12,""" +
                                """"baseSeq":0,"nextSeq":$replayLen,"replayFrom":0,"truncated":false,"running":true}""",
                        )
                        // Replayed output includes a DSR query (\x1b[6n) which MUST NOT trigger a reply!
                        webSocket.send(packOutput(0L, replayText))
                        webSocket.send("""{"type":"replay_end","seq":$replayLen}""")
                        serverOpened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receivedBinaries.add(bytes.utf8())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedTexts.add(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )

        val recorder = DebugRecorder()
        val config = ConnectionConfig(host = server.hostName, port = server.port, token = "tok_test")
        val session = PtyTabSession(
            tabId = "tab_pty_1",
            initialConfig = config,
            initialPalette = TerminalThemePalette.Dark,
            socketClient = OkHttpPtySocketClient(),
            debugRecorder = recorder,
            frameFlushIntervalMs = 5L,
        )

        try {
            session.resize(cols = 80, rows = 24)
            session.connect()
            assertTrue("WebSocket did not open", serverOpened.await(5, TimeUnit.SECONDS))

            waitUntil {
                session.uiState.value.connectionState == PtyConnectionState.CONNECTED &&
                    !session.uiState.value.isReplaying &&
                    session.uiState.value.frame?.toPlainText()?.contains("REPLAY:OK") == true
            }

            // Replay DSR must NOT have produced any binary reply, while post-replay resize WAS sent.
            assertTrue("Expected no WRITE_PTY reply during replay, got $receivedBinaries", receivedBinaries.isEmpty())
            waitUntil { receivedTexts.any { it.contains("\"type\":\"resize\"") && it.contains("\"cols\":80") } }

            // Now send a live DSR query after replay_end -> MUST reply with CSI row;col R.
            serverSocket!!.send(packOutput(15L, "\u001b[6n"))
            waitUntil { receivedBinaries.any { it.startsWith("\u001b[") && it.endsWith("R") } }

            // Send a disposable secret as raw user input; verify server receives it and DebugRecorder NEVER records it.
            val secret = "s3cr3t-p4ss-141"
            session.sendText("$secret\n")
            waitUntil { receivedBinaries.any { it.contains(secret) } }

            val allDebugText = recorder.toNdjson()
            assertFalse("Raw PTY input must never appear in debug logs", allDebugText.contains(secret))
            assertFalse("Bearer token must never appear in debug logs", allDebugText.contains("tok_test"))
        } finally {
            session.close()
            serverSocket?.close(1000, null)
        }
    }

    @Test
    fun reconnectPassesLastAppliedSeqAndSessionId() {
        val firstOpened = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        var firstSocket: WebSocket? = null
        var secondSocket: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstSocket = webSocket
                        webSocket.send(
                            """{"type":"ready","sessionId":"sess_abc","cols":80,"rows":24,""" +
                                """"baseSeq":0,"nextSeq":5,"replayFrom":0,"truncated":false,"running":true}""",
                        )
                        webSocket.send(packOutput(0L, "HELLO"))
                        webSocket.send("""{"type":"replay_end","seq":5}""")
                        firstOpened.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondSocket = webSocket
                        webSocket.send(
                            """{"type":"ready","sessionId":"sess_abc","cols":80,"rows":24,""" +
                                """"baseSeq":0,"nextSeq":11,"replayFrom":5,"truncated":false,"running":true}""",
                        )
                        webSocket.send(packOutput(5L, " WORLD"))
                        webSocket.send("""{"type":"replay_end","seq":11}""")
                        secondOpened.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )

        val config = ConnectionConfig(host = server.hostName, port = server.port, token = "tok_test")
        val session = PtyTabSession(
            tabId = "tab_pty_2",
            initialConfig = config,
            initialPalette = TerminalThemePalette.Dark,
            socketClient = OkHttpPtySocketClient(),
            frameFlushIntervalMs = 5L,
            reconnectBaseDelayMs = 50L,
            reconnectMaxDelayMs = 100L,
        )

        try {
            session.connect()
            assertTrue(firstOpened.await(5, TimeUnit.SECONDS))
            waitUntil { session.uiState.value.frame?.toPlainText()?.contains("HELLO") == true }
            val req1 = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(req1.path!!.contains("afterSeq=0"))
            assertEquals("Bearer tok_test", req1.getHeader("Authorization"))

            // Drop the first socket to force automatic reconnect.
            firstSocket!!.close(1011, "network blip")
            assertTrue("Expected automatic reconnect", secondOpened.await(5, TimeUnit.SECONDS))
            val req2 = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue("Expected afterSeq=5 on reconnect, got ${req2.path}", req2.path!!.contains("afterSeq=5"))
            assertTrue("Expected sessionId=sess_abc on reconnect, got ${req2.path}", req2.path!!.contains("sessionId=sess_abc"))

            waitUntil { session.uiState.value.frame?.toPlainText()?.contains("HELLO WORLD") == true }
            assertEquals(11L, session.uiState.value.appliedSeq)
        } finally {
            session.close()
            secondSocket?.close(1000, null)
        }
    }
}
