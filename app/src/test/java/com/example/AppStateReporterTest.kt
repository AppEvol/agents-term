package com.example

import com.example.data.AppStateCommandException
import com.example.data.AppStateReporter
import com.example.data.AppStateSources
import com.example.data.DebugRecorder
import com.example.data.RawJson
import com.example.data.toJsonText
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateReporterTest {

    private class FakeSources : AppStateSources {
        var screenshotError: String? = null
        var crash: String? = null
        var lastLogcatLines: Int? = null

        override fun uiState(): Map<String, Any?> = mapOf("screen" to "workspace", "appInForeground" to true)
        override fun workspaceJson(): String = """{"connectionState":"CONNECTED","port":7501}"""
        override suspend fun logcat(lines: Int): String {
            lastLogcatLines = lines
            return "logcat line Bearer secret123"
        }
        override fun lastCrash(): String? = crash
        override suspend fun screenshotPngBase64(): String {
            screenshotError?.let { throw AppStateCommandException(it) }
            return "iVBORw0KGgo="
        }
        override suspend fun uploadDump(note: String?, includeScreenshot: Boolean): Map<String, Any?> = mapOf("dumpId" to "abc", "note" to note, "includedScreenshot" to includeScreenshot)
    }

    private var enabled = true
    private val recorder = DebugRecorder()
    private val sources = FakeSources()
    private val reporter = AppStateReporter(
        recorder = recorder,
        sources = sources,
        isEnabled = { enabled },
        appVersion = "1.0 (code=1)",
        device = "Pixel Test",
        clockMs = { 1_795_536_752_575L },
    )

    /** Parses JSON into plain Kotlin values, keeping integral numbers as Long. */
    private fun parse(json: String): Any? = readValue(JsonReader.of(Buffer().writeUtf8(json)))

    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonReader.Token.BEGIN_OBJECT -> {
            val m = linkedMapOf<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readValue(r)
            r.endObject()
            m
        }

        JsonReader.Token.BEGIN_ARRAY -> {
            val l = mutableListOf<Any?>()
            r.beginArray()
            while (r.hasNext()) l.add(readValue(r))
            r.endArray()
            l
        }

        JsonReader.Token.STRING -> r.nextString()

        JsonReader.Token.NUMBER -> r.nextString().let { it.toLongOrNull() ?: it.toDouble() }

        JsonReader.Token.BOOLEAN -> r.nextBoolean()

        JsonReader.Token.NULL -> r.nextNull<Any?>()

        else -> error("unexpected token ${r.peek()}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    private suspend fun call(command: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> = obj(parse(reporter.handleRequest("req-1", command, args)))

    @Test
    fun helloAnnouncesEnabledStateAndCommands() {
        val hello = obj(parse(reporter.helloJson()))
        assertEquals("app_hello", hello["type"])
        val info = obj(hello["reporter"])
        assertEquals(true, info["enabled"])
        assertEquals("1.0 (code=1)", info["appVersion"])
        assertEquals(AppStateReporter.COMMANDS, info["commands"])

        enabled = false
        assertEquals(false, obj(obj(parse(reporter.helloJson()))["reporter"])["enabled"])
    }

    @Test
    fun disabledReporterRefusesEveryCommand() = runTest {
        enabled = false
        val res = call("ping")
        assertEquals("app_state_response", res["type"])
        assertEquals("req-1", res["requestId"])
        assertEquals(false, res["ok"])
        assertTrue((res["error"] as String).contains("disabled"))
    }

    @Test
    fun stateEmbedsWorkspaceJsonAndKeepsTimestampsIntegral() = runTest {
        val res = call("state")
        assertEquals(true, res["ok"])
        val result = obj(res["result"])
        assertEquals(1_795_536_752_575L, result["capturedAt"])
        assertEquals("workspace", obj(result["ui"])["screen"])
        assertEquals(7501L, obj(result["workspace"])["port"])
    }

    @Test
    fun logsFiltersByCategorySinceAndTextAndHonorsLimit() = runTest {
        recorder.record("http", "http_request", "GET /api/tabs", ts = 1_000L)
        recorder.record("ws", "ws_open", "connected", ts = 2_000L)
        recorder.record("error", "ws_failure", "Connection reset", ts = 3_000L)
        recorder.record("http", "http_request", "POST /api/tabs/x/messages", ts = 4_000L)

        fun events(res: Map<String, Any?>) = (obj(res["result"])["events"] as List<*>).map { obj(it) }

        assertEquals(4, events(call("logs")).size)
        assertEquals(
            listOf(1_000L, 4_000L),
            events(call("logs", mapOf("categories" to listOf("http")))).map { it["ts"] },
        )
        // Args from Moshi arrive as Doubles.
        assertEquals(
            listOf(3_000L, 4_000L),
            events(call("logs", mapOf("sinceTs" to 3_000.0))).map { it["ts"] },
        )
        assertEquals(listOf("ws_failure"), events(call("logs", mapOf("contains" to "RESET"))).map { it["event"] })
        val limited = call("logs", mapOf("limit" to 2.0))
        assertEquals(listOf(3_000L, 4_000L), events(limited).map { it["ts"] })
        assertEquals(4L, obj(limited["result"])["matchedCount"])
    }

    @Test
    fun logsAndPingAreNotRecordedButOtherCommandsAre() = runTest {
        call("logs")
        call("ping")
        assertEquals(0, recorder.count)
        call("state")
        sources.screenshotError = "The app is in the background"
        call("screenshot")
        val recorded = recorder.snapshot()
        assertEquals(listOf("app_state_command", "app_state_command_failed"), recorded.map { it.event })
        assertTrue(recorded.all { it.category == "app_state" })
    }

    @Test
    fun screenshotErrorsAreReportedToTheAgent() = runTest {
        assertEquals("iVBORw0KGgo=", obj(call("screenshot")["result"])["pngBase64"])
        sources.screenshotError = "The app is in the background"
        val res = call("screenshot")
        assertEquals(false, res["ok"])
        assertEquals("The app is in the background", res["error"])
    }

    @Test
    fun logcatClampsLinesAndCrashIsSanitized() = runTest {
        call("logcat", mapOf("lines" to 5.0))
        assertEquals(50, sources.lastLogcatLines)
        sources.crash = "java.lang.IllegalStateException token: abc123"
        val crash = obj(call("crash")["result"])
        assertEquals(true, crash["hasCrash"])
        assertFalse((crash["text"] as String).contains("abc123"))
        sources.crash = null
        assertEquals(mapOf("hasCrash" to false, "text" to null), obj(call("crash")["result"]))
    }

    @Test
    fun dumpPassesNoteAndScreenshotFlag() = runTest {
        val res = obj(call("dump", mapOf("note" to "from test", "includeScreenshot" to false))["result"])
        assertEquals("from test", res["note"])
        assertEquals(false, res["includedScreenshot"])
    }

    @Test
    fun unknownCommandsAndUnexpectedExceptionsBecomeErrors() = runTest {
        val unknown = call("bogus")
        assertEquals(false, unknown["ok"])
        assertTrue((unknown["error"] as String).contains("Supported: ping"))

        val crashing = AppStateReporter(
            recorder = recorder,
            sources = object : AppStateSources by sources {
                override fun uiState(): Map<String, Any?> = throw IllegalStateException("boom")
            },
            isEnabled = { true },
            appVersion = "1",
            device = "d",
        )
        val res = obj(parse(crashing.handleRequest("r", "state", emptyMap())))
        assertEquals(false, res["ok"])
        assertEquals("IllegalStateException: boom", res["error"])
    }

    @Test
    fun toJsonTextEncodesValuesAndEscapesStrings() {
        assertEquals(
            """{"s":"a\"b\n","n":null,"i":3,"d":2.5,"whole":4,"nan":null,"l":[true,false],"raw":{"x":1}}""",
            toJsonText(
                linkedMapOf(
                    "s" to "a\"b\n",
                    "n" to null,
                    "i" to 3,
                    "d" to 2.5,
                    "whole" to 4.0,
                    "nan" to Double.NaN,
                    "l" to listOf(true, false),
                    "raw" to RawJson("""{"x":1}"""),
                ),
            ),
        )
    }
}
