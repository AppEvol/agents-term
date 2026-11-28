package com.appevol.agentsterm

import com.appevol.agentsterm.data.DebugRecorder
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.ToolCallItem
import com.appevol.agentsterm.data.WsFrameLogAggregator
import com.appevol.agentsterm.data.WsServerEnvelope
import com.appevol.agentsterm.data.sanitizeDebugText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the debug ring buffer useful while agents stream tab and tool updates (#188, #121). */
class DebugLogFloodTest {

    private var nowMs = 1_000L

    private fun aggregator(recorder: DebugRecorder) = WsFrameLogAggregator(
        debugRecorder = recorder,
        flushIntervalMs = 10_000L,
        clockMs = { nowMs },
    )

    private fun tab(id: String, status: String, detail: String = "") = WsServerEnvelope(
        type = "tab_updated",
        tab = TabRecord(id = id, title = id, agent = "claude", cwd = "/w", status = status, statusDetail = detail),
    )

    private fun tool(tabId: String, status: String) = WsServerEnvelope(
        type = "tool_update",
        tabId = tabId,
        messageId = "m1",
        toolCall = ToolCallItem(id = "c1", name = "edit_file", status = status),
    )

    @Test
    fun routineFramesAreCountedAndMeaningfulFramesAreRecordedIndividually() {
        val recorder = DebugRecorder()
        val agg = aggregator(recorder)

        agg.onFrame(tab("t1", "working"), "{}") // first status seen for t1
        repeat(20) { agg.onFrame(tab("t1", "working", "step $it"), "{}") }
        repeat(10) { agg.onFrame(tool("t1", "running"), "{}") }
        repeat(10) { agg.onFrame(tool("t1", "done"), "{}") }
        agg.onFrame(tool("t1", "error"), "{}")
        agg.onFrame(WsServerEnvelope(type = "model_throughput"), "{}")
        agg.onFrame(WsServerEnvelope(type = "app_state_request", requestId = "r1", command = "state"), "{}")
        agg.onFrame(WsServerEnvelope(type = "message_created", tabId = "t1", messageId = "m2"), "{}")
        agg.onFrame(tab("t1", "done"), "{}") // status change

        val individual = recorder.snapshot().map { it.detail }
        assertEquals(
            listOf(
                "type=tab_updated tabStatus=working",
                "type=tool_update tab=t1 msg=m1 tool=edit_file:error",
                "type=message_created tab=t1 msg=m2",
                "type=tab_updated tabStatus=done",
            ),
            individual,
        )

        agg.flush()
        val batch = recorder.snapshot().last()
        assertEquals("ws_frame_batch", batch.event)
        assertEquals(null, batch.payload)
        assertTrue(batch.detail, batch.detail.startsWith("frames=42 "))
        assertTrue(batch.detail, batch.detail.contains("tabUpdates=20"))
        assertTrue(batch.detail, batch.detail.contains("toolUpdates=20"))
        assertTrue(batch.detail, batch.detail.contains("throughput=1"))
        assertTrue(batch.detail, batch.detail.contains("appStateRequests=1"))
        assertTrue(batch.detail, batch.detail.contains("tabs=1"))
        assertFalse(batch.detail, batch.detail.contains("deltas="))
    }

    @Test
    fun summariesAreWrittenPerWindowAndFlushedForDumps() {
        val recorder = DebugRecorder()
        val agg = aggregator(recorder)
        recorder.setPendingFlush(agg::flush)
        agg.onFrame(tab("t1", "working"), "{}")

        repeat(25) {
            nowMs += 1_000L
            agg.onFrame(tab("t1", "working", "step $it"), "{}")
        }
        // 25 s of progress frames: two full 10 s windows, the rest still pending.
        assertEquals(listOf("ws_event", "ws_frame_batch", "ws_frame_batch"), recorder.snapshot().map { it.event })

        // A dump includes the pending window; plain snapshots (logs -f polling) do not cut it short.
        val dumped = recorder.toNdjson().trim().lines()
        assertEquals(4, dumped.size)
        assertTrue(dumped.last(), dumped.last().contains("tabUpdates=3"))
    }

    @Test
    fun individualFramePayloadsAreCappedAndSanitized() {
        val recorder = DebugRecorder()
        val agg = aggregator(recorder)
        val raw = """{"type":"tab_created","token":"abc_secret_1","pad":"${"x".repeat(5_000)}"}"""
        agg.onFrame(WsServerEnvelope(type = "tab_created"), raw)

        val payload = recorder.snapshot().single().payload!!
        assertTrue(payload, payload.endsWith("…[truncated ${raw.length} chars]"))
        assertTrue(payload.length < 600)
        assertFalse(payload.contains("abc_secret_1"))
    }

    @Test
    fun secretsCutOffByTruncationAreStillRedacted() {
        val recorder = DebugRecorder(maxPayloadChars = 40)
        recorder.record("http", "a", "d", payload = """{"ok":true,"token":"abcdefghijklmnopqrstuvwxyz0123456789"}""")
        recorder.record(
            "http",
            "b",
            "d",
            payloadLimit = 60,
            payload = "key: -----BEGIN OPENSSH PRIVATE KEY-----\nAAAAB3NzaC1yc2EAAAADAQABAAABAQC\n-----END OPENSSH PRIVATE KEY-----",
        )
        val (json, key) = recorder.snapshot().map { it.payload!! }
        assertTrue(json, json.startsWith("""{"ok":true,"token":"[REDACTED]…[truncated"""))
        assertTrue(key, key.startsWith("key: [REDACTED_PRIVATE_KEY]…[truncated"))
        assertFalse(key.contains("AAAAB3"))

        // Complete secrets keep their existing redaction.
        assertEquals("""{"token":"[REDACTED]","a":1}""", sanitizeDebugText("""{"token":"s3cret","a":1}"""))
    }

    @Test
    fun networkTrafficCannotEvictErrorsOrLifecycleEvents() {
        val recorder = DebugRecorder(maxEvents = 9, trafficBudget = 6)
        recorder.record("lifecycle", "app_start", "start")
        recorder.record("error", "boom", "failed")
        repeat(100) { recorder.record("ws", "ws_event", "frame $it") }
        recorder.record("http", "http_ok", "last")

        val events = recorder.snapshot()
        assertEquals(9, events.size)
        assertEquals(listOf("app_start", "boom"), events.take(2).map { it.event })
        assertEquals((96..99).map { "frame $it" } + "last", events.drop(2).takeLast(5).map { it.detail })
        assertEquals(7, events.count { it.category == "ws" || it.category == "http" })
    }

    @Test
    fun eachClassBorrowsUnusedSlotsAndSnapshotKeepsRecordingOrder() {
        val recorder = DebugRecorder(maxEvents = 6, trafficBudget = 4)
        repeat(6) { recorder.record("ws", "w$it", "") }
        assertEquals((0..5).map { "w$it" }, recorder.snapshot().map { it.event })

        // Non-traffic events reclaim their 2 reserved slots from traffic, then evict their own oldest.
        repeat(3) { recorder.record("ui", "u$it", "") }
        assertEquals(listOf("w2", "w3", "w4", "w5", "u1", "u2"), recorder.snapshot().map { it.event })

        recorder.record("ws", "w6", "")
        assertEquals(listOf("w3", "w4", "w5", "u1", "u2", "w6"), recorder.snapshot().map { it.event })
        assertEquals(6, recorder.count)
    }

    @Test
    fun busyAgentStreamKeepsMostOfTheBufferFree() {
        // Roughly the 10-minute mix from debug dump 60b4d5d9: 3 busy tabs.
        val recorder = DebugRecorder()
        val agg = aggregator(recorder)
        repeat(600) { second ->
            nowMs += 1_000L
            val tabId = "t${second % 3}"
            agg.onFrame(tab(tabId, "working", "step $second"), "{}")
            agg.onFrame(tool(tabId, if (second % 2 == 0) "running" else "done"), "{}")
            if (second % 60 == 0) agg.onFrame(WsServerEnvelope(type = "message_created", tabId = tabId), "{}")
        }
        // 1,210 frames become 3 first-status events, 10 messages, and one summary per 10 s.
        assertTrue("count=${recorder.count}", recorder.count <= 80)
    }
}
