package com.example

import com.example.data.AppStateCommandException
import com.example.data.AppStateReporter
import com.example.data.AppUiController
import com.example.data.AppUiDriver
import com.example.data.BackOutcome
import com.example.data.DebugRecorder
import com.example.data.UiNode
import com.example.data.UiSnapshot
import com.example.data.UiWindow
import com.example.data.screenshotScale
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiControllerTest {

    private class FakeDriver : AppUiDriver {
        val activityWindow = UiWindow(0, "activity", 0, 0, 1080, 2400, modal = false)
        val menuWindow = UiWindow(1, "popup", 600, 100, 1080, 500, modal = true)
        var windows = listOf(activityWindow)
        var nodes = listOf(
            UiNode(id = 1, window = 0, left = 0, top = 200, right = 1080, bottom = 2000, scrollable = true, tag = "messages"),
            UiNode(id = 2, window = 0, left = 40, top = 300, right = 500, bottom = 380, text = "Hello from the agent"),
            UiNode(
                id = 3, window = 0, left = 0, top = 2100, right = 880, bottom = 2300, text = "Message",
                value = "draft", tag = "composer_input", editable = true,
            ),
            UiNode(
                id = 4, window = 0, left = 900, top = 2100, right = 1060, bottom = 2300, description = "Send",
                tag = "send_button", role = "Button", clickable = true,
            ),
            UiNode(id = 5, window = 0, left = 0, top = 0, right = 100, bottom = 100, text = "Stop", clickable = true, enabled = false),
        )
        val calls = mutableListOf<String>()
        var snapshotError: String? = null

        override suspend fun snapshot(): UiSnapshot {
            snapshotError?.let { throw AppStateCommandException(it) }
            return UiSnapshot(1080, 2400, windows, nodes)
        }

        override suspend fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, holdMs: Long): UiWindow {
            calls += "stroke($x1,$y1 -> $x2,$y2 in ${durationMs}ms hold ${holdMs}ms)"
            return windows.last()
        }

        override suspend fun setText(nodeId: Int, text: String, replace: Boolean) {
            calls += "setText($nodeId, $text, replace=$replace)"
        }

        override suspend fun imeAction(nodeId: Int) {
            calls += "imeAction($nodeId)"
        }

        override suspend fun pressBack(): BackOutcome {
            calls += "back"
            return BackOutcome.HANDLED
        }
    }

    private val driver = FakeDriver()
    private val controlled = mutableListOf<String>()
    private val controller = AppUiController(driver, onControl = { controlled += it })

    private suspend fun failure(command: String, args: Map<String, Any?>): String = try {
        controller.handle(command, args)
        throw AssertionError("'$command' should have failed")
    } catch (e: AppStateCommandException) {
        e.message.orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun node(result: Map<String, Any?>): Map<String, Any?> = result["node"] as Map<String, Any?>

    @Test
    fun uiListsWindowsAndNodesCompactlyAndFilters() = runTest {
        val all = controller.handle("ui", emptyMap())
        assertEquals(1080, all["width"])
        assertEquals(2400, all["height"])
        assertEquals(5, all["nodeCount"])
        @Suppress("UNCHECKED_CAST")
        val send = (all["nodes"] as List<Map<String, Any?>>).single { it["tag"] == "send_button" }
        assertEquals(
            mapOf(
                "id" to 4, "window" to 0, "bounds" to listOf(900, 2100, 1060, 2300), "center" to listOf(980, 2200),
                "description" to "Send", "tag" to "send_button", "role" to "Button", "clickable" to true,
            ),
            send,
        )

        assertEquals(1, controller.handle("ui", mapOf("text" to "hello FROM"))["nodeCount"])
        assertEquals(0, controller.handle("ui", mapOf("text" to "hello FROM", "exact" to true))["nodeCount"])
        assertEquals(1, controller.handle("ui", mapOf("text" to "draft"))["nodeCount"])
        assertEquals(4, controller.handle("ui", mapOf("interactiveOnly" to true))["nodeCount"])
        assertTrue("reading the UI is not a control action", controlled.isEmpty())
    }

    @Test
    fun tapResolvesSelectorsToTheNodeCenterOrUsesCoordinates() = runTest {
        val byTag = controller.handle("tap", mapOf("tag" to "send_button"))
        assertEquals(980, byTag["x"])
        assertEquals(2200, byTag["y"])
        assertEquals("send_button", node(byTag)["tag"])
        controller.handle("tap", mapOf("text" to "send"))
        controller.handle("tap", mapOf("x" to 10.4, "y" to 20))
        controller.handle("long_press", mapOf("tag" to "send_button"))
        assertEquals(
            listOf(
                "stroke(980,2200 -> 980,2200 in 60ms hold 0ms)",
                "stroke(980,2200 -> 980,2200 in 60ms hold 0ms)",
                "stroke(10,20 -> 10,20 in 60ms hold 0ms)",
                "stroke(980,2200 -> 980,2200 in 800ms hold 0ms)",
            ),
            driver.calls,
        )
        assertEquals(listOf("tap", "tap", "tap", "long_press"), controlled)
    }

    @Test
    fun tapRejectsMissingDisabledAmbiguousAndOffScreenTargets() = runTest {
        assertTrue(failure("tap", emptyMap()).contains("needs a target"))
        assertTrue(failure("tap", mapOf("tag" to "nope")).contains("No on-screen element matches tag 'nope'"))
        assertTrue(failure("tap", mapOf("text" to "Stop")).contains("disabled"))
        assertTrue(failure("tap", mapOf("x" to 1080, "y" to 5)).contains("outside the app window"))
        assertTrue(failure("tap", mapOf("text" to "e", "index" to 9)).contains("index must be below"))
        assertTrue(failure("tap", mapOf("text" to "e", "index" to -1)).contains("0 or greater"))
        assertTrue(driver.calls.isEmpty())

        // index picks among several matches.
        assertEquals(3, node(controller.handle("tap", mapOf("text" to "e", "index" to 1)))["id"])
    }

    @Test
    fun swipeAndScrollBuildStrokes() = runTest {
        controller.handle("swipe", mapOf("x1" to 500, "y1" to 1500, "x2" to 500, "y2" to 500, "durationMs" to 250))
        // Default scroll: the biggest scrollable of the top window, 60% of its height, finger moving up.
        val down = controller.handle("scroll", emptyMap())
        assertEquals("messages", node(down)["tag"])
        controller.handle("scroll", mapOf("direction" to "up", "amount" to 0.5))
        controller.handle("scroll", mapOf("direction" to "right", "tag" to "composer_input"))
        assertEquals(
            listOf(
                "stroke(500,1500 -> 500,500 in 250ms hold 0ms)",
                "stroke(540,1640 -> 540,560 in 400ms hold 150ms)",
                "stroke(540,650 -> 540,1550 in 400ms hold 150ms)",
                "stroke(704,2200 -> 176,2200 in 400ms hold 150ms)",
            ),
            driver.calls,
        )
        assertTrue(failure("scroll", mapOf("direction" to "sideways")).contains("direction must be one of"))
        assertTrue(failure("swipe", mapOf("x1" to 1)).contains("x1, y1, x2 and y2"))

        // Nothing scrollable: drag the middle of the window.
        driver.nodes = emptyList()
        driver.calls.clear()
        assertNull(controller.handle("scroll", emptyMap())["node"])
        assertEquals(listOf("stroke(540,1920 -> 540,480 in 400ms hold 150ms)"), driver.calls)
    }

    @Test
    fun scrollPrefersTheTopWindow() = runTest {
        driver.windows = listOf(driver.activityWindow, driver.menuWindow)
        driver.nodes = driver.nodes + UiNode(id = 9, window = 1, left = 600, top = 100, right = 1080, bottom = 500, scrollable = true)
        assertEquals(9, node(controller.handle("scroll", emptyMap()))["id"])
    }

    @Test
    fun typeTargetsANamedFocusedOrOnlyTextField() = runTest {
        controller.handle("type", mapOf("text" to "hi", "tag" to "composer_input", "replace" to true, "submit" to true))
        // No selector and nothing focused: the only text field on screen.
        val implicit = controller.handle("type", mapOf("text" to " there"))
        assertEquals(6, implicit["chars"])
        assertEquals(false, implicit["submitted"])
        controller.handle("type", mapOf("text" to "x", "field" to "Message"))
        assertEquals(
            listOf("setText(3, hi, replace=true)", "imeAction(3)", "setText(3,  there, replace=false)", "setText(3, x, replace=false)"),
            driver.calls,
        )

        assertTrue(failure("type", emptyMap()).contains("needs 'text'"))
        assertTrue(failure("type", mapOf("text" to "x", "tag" to "send_button")).contains("not a text field"))

        // Two fields and neither focused: the agent has to say which one.
        val second = UiNode(id = 7, window = 0, left = 0, top = 1900, right = 800, bottom = 2000, editable = true, tag = "other")
        driver.nodes = driver.nodes + second
        assertTrue(failure("type", mapOf("text" to "x")).contains("No text field has focus"))
        driver.nodes = driver.nodes - second + second.copy(focused = true)
        assertEquals("other", node(controller.handle("type", mapOf("text" to "x")))["tag"])
    }

    @Test
    fun keySupportsBackAndEnter() = runTest {
        assertEquals("handled", controller.handle("key", mapOf("key" to "BACK"))["outcome"])
        assertTrue(failure("key", mapOf("key" to "enter")).contains("No text field has focus"))
        driver.nodes = driver.nodes.map { if (it.id == 3) it.copy(focused = true) else it }
        controller.handle("key", mapOf("key" to "enter"))
        assertEquals(listOf("back", "imeAction(3)"), driver.calls)
        assertTrue(failure("key", mapOf("key" to "home")).contains("Supported: back, enter"))
        assertTrue(failure("key", emptyMap()).contains("Unknown key"))
    }

    @Test
    fun driverErrorsReachTheAgent() = runTest {
        driver.snapshotError = "The app is in the background"
        assertEquals("The app is in the background", failure("tap", mapOf("x" to 1, "y" to 1)))
    }

    @Test
    fun screenshotScaleOnlyShrinksWideWindows() {
        assertEquals(1f, screenshotScale(1080))
        assertEquals(1f, screenshotScale(720))
        assertEquals(0.75f, screenshotScale(1440))
    }

    // --- through AppStateReporter: gating and the hello frame ---

    private var inspection = true
    private var control = false
    private val recorder = DebugRecorder()
    private val reporter = AppStateReporter(
        recorder = recorder,
        sources = object : com.example.data.AppStateSources {
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
        ui = controller,
        isControlEnabled = { control },
    )

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> =
        Moshi.Builder().build().adapter(Map::class.java).fromJson(json) as Map<String, Any?>

    @Test
    @Suppress("UNCHECKED_CAST")
    fun helloAnnouncesUiCommandsAndWhetherControlIsAllowed() {
        fun hello() = parse(reporter.helloJson())["reporter"] as Map<String, Any?>
        val commands = hello()["commands"] as List<String>
        assertTrue(commands.containsAll(AppStateReporter.COMMANDS + listOf("ui", "tap", "long_press", "swipe", "scroll", "type", "key")))
        assertEquals(AppUiController.CONTROL_COMMANDS, hello()["controlCommands"])
        assertEquals(false, hello()["controlEnabled"])
        control = true
        assertEquals(true, hello()["controlEnabled"])
        inspection = false
        assertEquals("control needs inspection", false, hello()["controlEnabled"])
    }

    @Test
    fun controlCommandsNeedTheirOwnOptInButReadingTheUiDoesNot() = runTest {
        val ui = parse(reporter.handleRequest("r1", "ui", emptyMap()))
        assertEquals(true, ui["ok"])

        val refused = parse(reporter.handleRequest("r2", "tap", mapOf("tag" to "send_button")))
        assertEquals(false, refused["ok"])
        assertTrue((refused["error"] as String).contains("Remote control is disabled"))
        assertTrue(driver.calls.isEmpty())
        assertTrue(controlled.isEmpty())

        control = true
        val tapped = parse(reporter.handleRequest("r3", "tap", mapOf("tag" to "send_button")))
        assertEquals(true, tapped["ok"])
        assertEquals(1, driver.calls.size)

        // Control actions are in the debug log; polling `ui` is not.
        val events = recorder.snapshot().map { it.detail }
        assertEquals(2, events.size)
        assertTrue(events.all { it.contains("'tap'") })
        assertFalse(events.any { it.contains("'ui'") })
    }

    @Test
    fun withoutAUiControllerTheUiCommandsAreUnknown() = runTest {
        val plain = AppStateReporter(
            recorder = DebugRecorder(),
            sources = object : com.example.data.AppStateSources {
                override fun uiState(): Map<String, Any?> = emptyMap()
                override fun workspaceJson(): String = "{}"
                override suspend fun logcat(lines: Int): String = ""
                override fun lastCrash(): String? = null
                override suspend fun screenshotPngBase64(): String = ""
                override suspend fun uploadDump(note: String?, includeScreenshot: Boolean): Map<String, Any?> = emptyMap()
            },
            isEnabled = { true },
            appVersion = "1.0",
            device = "Pixel Test",
        )
        val res = parse(plain.handleRequest("r1", "tap", emptyMap()))
        assertEquals(false, res["ok"])
        assertTrue((res["error"] as String).contains("Unknown command 'tap'"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(AppStateReporter.COMMANDS, (parse(plain.helloJson())["reporter"] as Map<String, Any?>)["commands"])
    }
}
