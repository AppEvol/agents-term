package com.appevol.agentsterm

import com.appevol.agentsterm.data.AppStateCommandException
import com.appevol.agentsterm.data.AppStateReporter
import com.appevol.agentsterm.data.AppStateSources
import com.appevol.agentsterm.data.DebugRecorder
import com.appevol.agentsterm.data.ScreenRecorder
import com.appevol.agentsterm.data.ScreenRecording
import com.appevol.agentsterm.data.ScreenRecordingCommands
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ScreenRecordingCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Records nothing: `stop` "finishes" the recording by writing [video] to its file. */
    private inner class FakeRecorder : ScreenRecorder {
        var recording: ScreenRecording? = null
        var startError: String? = null
        var video = ByteArray(0)
        var lastMaxDurationMs: Long? = null

        override suspend fun start(maxDurationMs: Long): ScreenRecording {
            startError?.let { throw AppStateCommandException(it) }
            lastMaxDurationMs = maxDurationMs
            return ScreenRecording(
                id = "rec1",
                startedAtMs = 1_000,
                width = 1080,
                height = 2400,
                maxDurationMs = maxDurationMs,
                file = tmp.newFile(),
            ).also { recording = it }
        }

        override suspend fun stop(): ScreenRecording {
            val running = recording ?: throw AppStateCommandException("There is no screen recording to stop.")
            running.file.writeBytes(video)
            return running.copy(durationMs = 4_200, stopReason = "stopped").also { recording = it }
        }

        override fun current(): ScreenRecording? = recording
    }

    private var inspection = true
    private var recordingAllowed = true
    private val fake = FakeRecorder()
    private val debugLog = DebugRecorder()
    private val reporter = AppStateReporter(
        recorder = debugLog,
        sources = object : AppStateSources {
            override fun uiState(): Map<String, Any?> = emptyMap()
            override fun workspaceJson(): String = "{}"
            override suspend fun logcat(lines: Int): String = ""
            override fun lastCrash(): String? = null
            override suspend fun screenshotPngBase64(): String = ""
            override suspend fun uploadDump(note: String?, includeScreenshot: Boolean): Map<String, Any?> = emptyMap()
        },
        isEnabled = { inspection },
        appVersion = "1.0",
        device = "Pixel Test",
        recording = ScreenRecordingCommands(
            recorder = fake,
            encodeBase64 = { Base64.getEncoder().encodeToString(it) },
            clockMs = { 3_500 },
        ),
        isRecordingEnabled = { recordingAllowed },
    )

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> {
        val adapter = Moshi.Builder().build().adapter(Map::class.java)
        return adapter.fromJson(json) as Map<String, Any?>
    }

    private suspend fun call(command: String, args: Map<String, Any?> = emptyMap()) = parse(reporter.handleRequest("r1", command, args))

    @Suppress("UNCHECKED_CAST")
    private suspend fun result(command: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val res = call(command, args)
        assertEquals("$command failed: ${res["error"]}", true, res["ok"])
        return res["result"] as Map<String, Any?>
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun helloAnnouncesRecordingCommandsAndWhetherRecordingIsAllowed() {
        fun hello() = parse(reporter.helloJson())["reporter"] as Map<String, Any?>
        assertTrue((hello()["commands"] as List<String>).containsAll(ScreenRecordingCommands.COMMANDS))
        assertEquals(true, hello()["recordingEnabled"])
        recordingAllowed = false
        assertEquals(false, hello()["recordingEnabled"])
        recordingAllowed = true
        inspection = false
        assertEquals("recording needs inspection", false, hello()["recordingEnabled"])
    }

    @Test
    fun recordingNeedsItsOwnOptIn() = runTest {
        recordingAllowed = false
        for (command in ScreenRecordingCommands.COMMANDS) {
            val res = call(command)
            assertEquals(command, false, res["ok"])
            assertEquals(command, "recording_disabled", res["code"])
        }
        assertEquals(null, fake.recording)
        // Other commands are unaffected, and their errors carry no code.
        assertEquals(true, call("ping")["ok"])
        assertFalse(call("nope").containsKey("code"))
    }

    @Test
    fun startStopAndStatusDescribeTheRecording() = runTest {
        assertEquals("idle", result("record_status")["state"])

        val started = result("record_start", mapOf("maxDurationSec" to 20.0))
        assertEquals(20_000L, fake.lastMaxDurationMs)
        assertEquals("recording", started["state"])
        assertEquals("rec1", started["recordingId"])
        assertEquals(1080.0, started["width"])
        assertEquals(2400.0, started["height"])
        // Still running: the duration is the time since the start, and there is no size yet.
        assertEquals(2_500.0, started["durationMs"])
        assertEquals(null, started["bytes"])
        assertEquals("recording", result("record_status")["state"])

        fake.video = ByteArray(10)
        val stopped = result("record_stop")
        assertEquals("finished", stopped["state"])
        assertEquals("stopped", stopped["stopReason"])
        assertEquals(4_200.0, stopped["durationMs"])
        assertEquals(10.0, stopped["bytes"])
        assertEquals("finished", result("record_status")["state"])
    }

    @Test
    fun startClampsTheDurationAndReportsWhyItCouldNotStart() = runTest {
        result("record_start")
        assertEquals(ScreenRecordingCommands.DEFAULT_MAX_DURATION_SEC * 1000, fake.lastMaxDurationMs)
        result("record_start", mapOf("maxDurationSec" to 100_000.0))
        assertEquals(ScreenRecordingCommands.MAX_DURATION_SEC * 1000, fake.lastMaxDurationMs)
        result("record_start", mapOf("maxDurationSec" to 0.0))
        assertEquals(1_000L, fake.lastMaxDurationMs)

        fake.startError = "The user declined the screen recording."
        val res = call("record_start")
        assertEquals(false, res["ok"])
        assertEquals("The user declined the screen recording.", res["error"])
    }

    @Test
    fun readReturnsTheVideoInChunks() = runTest {
        result("record_start")
        assertEquals("nothing to read while recording", false, call("record_read")["ok"])

        fake.video = ByteArray(2_500) { (it % 251).toByte() }
        result("record_stop")

        val received = mutableListOf<Byte>()
        var chunks = 0
        while (true) {
            val chunk = result("record_read", mapOf("recordingId" to "rec1", "offset" to received.size.toDouble(), "length" to 1_000.0))
            assertEquals(received.size.toDouble(), chunk["offset"])
            assertEquals(2_500.0, chunk["totalBytes"])
            received += Base64.getDecoder().decode(chunk["dataBase64"] as String).toList()
            chunks++
            if (chunk["eof"] == true) break
        }
        assertEquals(3, chunks)
        assertArrayEquals(fake.video, received.toByteArray())

        // Reading at or past the end is an empty last chunk, not an error.
        val past = result("record_read", mapOf("offset" to 9_999.0))
        assertEquals(0.0, past["length"])
        assertEquals(true, past["eof"])

        val other = call("record_read", mapOf("recordingId" to "older"))
        assertEquals(false, other["ok"])
        assertTrue((other["error"] as String).contains("replaced"))
    }

    @Test
    fun readLimitsTheChunkSize() = runTest {
        result("record_start")
        fake.video = ByteArray(ScreenRecordingCommands.MAX_CHUNK_BYTES + 5)
        result("record_stop")
        val chunk = result("record_read", mapOf("length" to 50_000_000.0))
        assertEquals(ScreenRecordingCommands.MAX_CHUNK_BYTES.toDouble(), chunk["length"])
        assertEquals(false, chunk["eof"])
    }

    @Test
    fun chunkReadsAreNotWrittenToTheDebugLog() = runTest {
        result("record_start")
        result("record_stop")
        result("record_read")
        assertEquals(
            listOf("VM agent ran 'record_start'", "VM agent ran 'record_stop'"),
            debugLog.snapshot().map { it.detail.substringBefore(" (") },
        )
    }
}
