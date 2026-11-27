package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.CreateTabRequest
import com.example.data.HerdrApiClient
import com.example.data.TabRecord
import com.example.data.appWindowRoots
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class AgentsTermE2ETest {

    /**
     * On failure, appends which window holds input focus. A system dialog on a CI emulator (for
     * example "System UI isn't responding") can steal window focus from the app, and the stack
     * trace alone does not show what is on top.
     */
    @get:Rule(order = 0)
    val windowFocusDiagnostics = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    val focus = queryWindowFocusDiagnostics()
                    throw AssertionError("${t.message}\n[window focus at failure] $focus", t)
                }
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    private fun queryWindowFocusDiagnostics(): String = runCatching {
        val lines = sequenceOf("dumpsys window displays", "dumpsys window")
            .map { shell(it) }
            .map { output ->
                output.lineSequence()
                    .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mObscuringWindow" in it }
                    .map { it.trim() }
                    .distinct()
                    .toList()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (lines.isEmpty()) "(none found)" else lines.joinToString(" | ")
    }.getOrElse { "unavailable: $it" }

    private fun closeSoftKeyboard() {
        scenario?.onActivity { activity ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            for (root in appWindowRoots(activity).asReversed()) {
                WindowCompat.getInsetsController(activity.window, root)
                    .hide(WindowInsetsCompat.Type.ime())
                val token = root.findFocus()?.windowToken ?: root.windowToken
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        scenario?.onActivity { activity ->
            val decorView = activity.window.decorView
            val top = appWindowRoots(activity).last { root ->
                root === decorView ||
                    ((root.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
            }
            val dispatcher = top.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                ?: activity.onBackPressedDispatcher
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private var scenario: ActivityScenario<MainActivity>? = null
    private var daemon: FakeAgentsTermDaemon? = null
    private var sshServer: FakeSshVmServer? = null

    @Before
    fun dismissSystemDialogs() {
        // A leftover system dialog would keep window focus away from the app for the whole run.
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    @Before
    fun clearAppPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("herdr_mobile_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("herdr_mobile_local_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        sshServer?.close()
        sshServer = null
        daemon?.close()
        daemon = null
    }

    private fun startDaemon(
        initialTabs: List<TabRecord> = emptyList(),
        initialMessages: Map<String, List<ChatMessage>> = emptyMap(),
    ): FakeAgentsTermDaemon = FakeAgentsTermDaemon(
        initialTabs = initialTabs,
        initialMessages = initialMessages,
    ).also {
        it.start()
        daemon = it
    }

    private fun launchMainActivity(intent: Intent? = null) {
        scenario = if (intent != null) {
            ActivityScenario.launch(intent)
        } else {
            ActivityScenario.launch(MainActivity::class.java)
        }
    }

    private fun formatTimeoutDiagnostics(header: String): String {
        val reqs = daemon?.recordedRequests?.joinToString("\n  ") ?: "(no daemon)"
        val dbg = com.example.ui.MainViewModel.latestDebugRecorder
            ?.snapshot()
            ?.takeLast(30)
            ?.joinToString("\n  ") { "[${it.category}] ${it.event}: ${it.detail}" }
            ?: "(no debug events)"
        val roots = runCatching { composeRule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
            .getOrElse { it.toString() }
        return "$header\n" +
            "Daemon requests:\n  $reqs\n" +
            "App debug events:\n  $dbg\n" +
            "Compose roots:\n$roots"
    }

    private fun waitForCondition(
        description: String,
        timeoutMs: Long = 10_000L,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                formatTimeoutDiagnostics("Timed out waiting for $description."),
                e,
            )
        } catch (e: Throwable) {
            throw AssertionError(
                formatTimeoutDiagnostics(
                    "Error while waiting for $description (${e::class.simpleName}: ${e.message}).",
                ),
                e,
            )
        }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000L) {
        waitForCondition("text='$text' (substring=$substring)", timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTextGone(text: String, substring: Boolean = false, timeoutMs: Long = 10_000L) {
        waitForCondition("text='$text' (substring=$substring) to disappear", timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L, useUnmergedTree: Boolean = false) {
        waitForCondition("tag='$tag'", timeoutMs) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTagGone(tag: String, timeoutMs: Long = 10_000L, useUnmergedTree: Boolean = false) {
        waitForCondition("tag='$tag' to disappear", timeoutMs) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Types [text] into the composer and triggers send via its semantics click action rather than a
     * fixed-coordinate touch, which can miss or cancel when the out-of-process IME opens and shifts
     * the composer upward via `imePadding()`.
     */
    private fun sendComposerMessage(text: String) {
        composeRule.onNodeWithTag("composer_input").performTextInput(text)
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnClick)
        waitForCondition("composer_input to clear after sending '$text'", timeoutMs = 5_000L) {
            val nodes = composeRule.onAllNodesWithTag("composer_input").fetchSemanticsNodes()
            nodes.isEmpty() || nodes.all { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty()
            }
        }
    }

    /** Submits the open NewTabSheet and waits for the sheet to close before matching [expectedTitle]. */
    private fun submitNewTab(expectedTitle: String) {
        closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().performClick()
        waitForTagGone("create_tab_button")
        waitForText(expectedTitle)
    }

    private fun connectDirectlyThroughUi(targetDaemon: FakeAgentsTermDaemon, token: String = targetDaemon.token) {
        composeRule.onNodeWithTag("mode_direct_uri").performClick()
        composeRule.onNodeWithTag("host_input").performScrollTo().performTextReplacement(targetDaemon.host)
        composeRule.onNodeWithTag("port_input").performScrollTo().performTextReplacement(targetDaemon.port.toString())
        composeRule.onNodeWithTag("token_input").performScrollTo().performTextReplacement(token)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("connect_button").performScrollTo().performClick()
    }

    @Test
    fun connectScreenSupportsModeSwitchingAndSshAuthControls() {
        launchMainActivity()

        // Default unconfigured screen opens in SSH + agy Setup mode
        waitForTag("ssh_host_input")
        composeRule.onNodeWithText("Connect to your VM").assertIsDisplayed()
        composeRule.onNodeWithTag("ssh_username_input").performScrollTo().assertIsDisplayed()

        // Switching auth method reveals matching credential inputs
        composeRule.onNodeWithTag("auth_password").performScrollTo().performClick()
        composeRule.onNodeWithTag("ssh_password_input").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("auth_private_key").performScrollTo().performClick()
        composeRule.onNodeWithTag("ssh_private_key_input").performScrollTo().assertIsDisplayed()

        // Switch to Direct / URI mode
        composeRule.onNodeWithTag("mode_direct_uri").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("paste_uri_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun invalidTokenShowsErrorThenValidConnectionOpensWorkspace() {
        val activeDaemon = startDaemon()
        launchMainActivity()

        // First attempt with wrong bearer token should display daemon error and stay on ConnectScreen
        connectDirectlyThroughUi(activeDaemon, token = "wrong-token")
        waitForText("Invalid bearer token")
        composeRule.onNodeWithText("Invalid bearer token").assertIsDisplayed()

        // Correcting the token succeeds and opens the connected empty workspace
        composeRule.onNodeWithTag("token_input").performScrollTo().performTextReplacement(activeDaemon.token)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("connect_button").performScrollTo().performClick()

        waitForText("Connected to ci-test-vm")
        composeRule.onNodeWithTag("empty_new_tab_button").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun createTabBrowseDirectoryAndStreamAgentChatEndToEnd() {
        val activeDaemon = startDaemon()
        launchMainActivity()
        connectDirectlyThroughUi(activeDaemon)

        waitForText("Connected to ci-test-vm")

        // Open New Tab sheet from empty workspace
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")

        // Wait for VM directory listing (/home/ci -> projects) and browse into projects
        waitForText("projects")
        composeRule.onNodeWithText("projects").performScrollTo().performClick()
        waitForText("agents-term")

        // Pick Claude agent, enter a custom tab title, and create the tab
        composeRule.onNodeWithTag("agent_option_claude").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("E2E Claude Tab")
        submitNewTab("E2E Claude Tab")
        waitForText("Claude · projects")
        waitForTag("composer_input")

        // This daemon predates image attachments (its /api/info does not offer them), so the
        // composer must not offer an attach button that could never work.
        waitForTag("composer_input")
        assertTrue(composeRule.onAllNodesWithTag("attach_button").fetchSemanticsNodes().isEmpty())

        // Send a prompt and verify streamed reasoning, tool call, markdown text, and usage stats
        sendComposerMessage("Summarize the repo")

        waitForText("Summarize the repo")
        waitForText("Repository is clean on main branch.")

        // Verify token usage footer rendered before expanding accordions
        composeRule.onNodeWithText("120 in · 45 out", substring = true).assertIsDisplayed()

        // Expand Reasoning accordion and check reasoning content
        composeRule.onNodeWithText("Reasoning").performClick()
        waitForText("Inspecting repository context for: Summarize the repo")
        composeRule.onNodeWithText("Reasoning").performClick()

        // Expand Tool Call card and check command output
        composeRule.onNodeWithText("Bash").performClick()
        waitForText("On branch main", substring = true)
    }

    @Test
    fun interruptWorkingAgentAndRetryBlockedPermissionWithFullAccess() {
        val seedTab = TabRecord(
            id = "tab_seed",
            title = "Antigravity · app",
            agent = "agy",
            cwd = "/home/ci/projects",
            permissionMode = "default",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(seedTab))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)

        // Deep link auto-connects directly into the workspace with the seed tab active
        waitForText("Antigravity · app")
        waitForTag("composer_input")

        // 1) Trigger a long-running working turn; tapping send while empty shows hint and does NOT interrupt
        sendComposerMessage("hold_working long job")

        waitForText("Processing prompt...")
        waitForTag("streaming_working_badge")
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("hold_to_interrupt_hint")
        assertTrue("Accidental tap must not interrupt active turn", activeDaemon.interruptedTabIds.isEmpty())

        // 2) Typing while working queues messages by default; queued items can be inspected and removed
        sendComposerMessage("First queued follow-up")
        waitForTag("queued_messages_tray")
        waitForText("Queued (1)")
        waitForText("First queued follow-up")
        assertTrue("Queueing must not interrupt active turn", activeDaemon.interruptedTabIds.isEmpty())

        sendComposerMessage("Drop this queued item")
        waitForText("Queued (2)")
        waitForText("Drop this queued item")

        // Remove the second queued item via its close button
        composeRule.onAllNodesWithTag("remove_queued_q_104").let { nodes ->
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                nodes[0].performClick()
            } else {
                // Fallback by content description if counter shifts
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasContentDescription("Remove queued message"),
                )[1].performClick()
            }
        }
        waitForText("Queued (1)")

        // 3) Press & hold send button with a draft to force interrupt AND process all queued messages + draft
        composeRule.onNodeWithTag("composer_input").performTextInput("Final urgent follow-up")
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnLongClick)

        waitForText("Repository is clean on main branch.")
        waitForText("First queued follow-up\n\nFinal urgent follow-up")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("chat_message_list").performScrollToIndex(0)
        waitForText("Interrupted by user")
        assertTrue(activeDaemon.interruptedTabIds.contains("tab_seed"))
        assertTrue(composeRule.onAllNodesWithTag("queued_messages_tray").fetchSemanticsNodes().isEmpty())

        // 4) Trigger another working turn and press & hold empty send button to stop cleanly
        sendComposerMessage("hold_working stop only")
        waitForText("Processing prompt...")
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Processing prompt...").fetchSemanticsNodes().isEmpty()
        }

        // 5) Trigger a permission-blocked turn and retry with Full Access
        sendComposerMessage("needs_permission run tests")

        waitForTag("allow_full_access_button")
        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()
        composeRule.onNodeWithTag("allow_full_access_button").performSemanticsAction(SemanticsActions.OnClick)

        waitForText("Full access granted. Command completed successfully.")
        assertTrue(activeDaemon.receivedOverrideModes.contains("full-access"))
    }

    @Test
    fun newTabControlsTrackTheLastSlotAcrossClients() {
        val tabs = (1..9).map { TabRecord(id = "tab_$it", title = "Tab $it", agent = "shell", cwd = "/home/ci") }
        val activeDaemon = startDaemon(initialTabs = tabs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = activeDaemon.pairingUri()
        launchMainActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, MainActivity::class.java))
        waitForText("Tab 1")
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForTag("create_tab_button")

        val otherClient = HerdrApiClient()
        val config = requireNotNull(ConnectionConfig.parsePairingUri(uri))
        val tenth = runBlocking { otherClient.createTab(config, CreateTabRequest(agent = "claude")) }
        val limitLabel = "10-tab limit reached"
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodes(hasTestTag("create_tab_button") and hasText(limitLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().assertIsNotEnabled()
        pressBack()
        composeRule.onNodeWithTag("top_new_tab_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        waitForText("PERSISTENT TABS (10)")
        composeRule.onNodeWithTag("drawer_new_tab_button").assertIsNotEnabled().assertTextContains(limitLabel)

        runBlocking { otherClient.deleteTab(config, tenth.id) }
        waitForText("PERSISTENT TABS (9)")
        composeRule.onNodeWithTag("drawer_new_tab_button").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().assertIsEnabled().performClick()
        waitForTagGone("create_tab_button")
        waitForTag("quick_tab_tab_11")
        composeRule.onNodeWithTag("top_new_tab_button").assertIsNotEnabled()
    }

    @Test
    fun manageMultipleTabsDrawerRenameCloseAndSettingsRoundTrip() {
        val tab1 = TabRecord(
            id = "tab_1",
            title = "Primary Agent",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
            taskSummary = "Inspect CI build logs",
        )
        val msg1 = ChatMessage(
            id = "m_1",
            tabId = "tab_1",
            role = "assistant",
            text = "Hello from Primary Agent",
            status = "complete",
        )
        val activeDaemon = startDaemon(
            initialTabs = listOf(tab1),
            initialMessages = mapOf("tab_1" to listOf(msg1)),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("herdr"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)

        waitForText("Hello from Primary Agent")

        // Create a second tab (Shell) via the top bar + button
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_shell").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Build Shell")
        submitNewTab("Build Shell")

        // Quick tab strip appears with 2 tabs; switch back to tab_1 and verify its summary & transcript load
        waitForTag("quick_tab_tab_1")
        composeRule.onNodeWithTag("quick_tab_tab_1").performClick()
        waitForTag("tab_task_summary_card")
        waitForText("Inspect CI build logs")
        composeRule.onNodeWithTag("dismiss_task_summary_button").performClick()
        waitForText("Hello from Primary Agent")
        assertTrue(activeDaemon.markedReadTabIds.contains("tab_1"))

        // Open navigation drawer and switch back to the second tab (tab_2)
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        waitForText("PERSISTENT TABS (2)")
        composeRule.onNodeWithTag("drawer_tab_tab_2").performClick()
        waitForText("Build Shell")

        // Rename active tab via overflow menu
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithText("Rename tab").performClick()
        waitForText("Rename Tab")
        composeRule.onNode(hasSetTextAction() and hasText("Build Shell")).performTextReplacement("Renamed Shell")
        composeRule.onNodeWithText("Save").performClick()
        waitForTextGone("Rename Tab")
        waitForText("Renamed Shell")

        // Open VM Connection Settings and navigate back to workspace
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithText("VM Connection Settings").performClick()
        waitForTag("connect_back_button")
        composeRule.onNodeWithTag("connect_back_button").performClick()
        waitForText("Renamed Shell")

        // Close the active tab via overflow menu and confirm fallback to Primary Agent
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithText("Close tab").performClick()
        waitForText("Hello from Primary Agent")
    }

    @Test
    fun settingsDebugSharingUploadsDumpToVmAndEnablesQuickBugButton() {
        val tab1 = TabRecord(
            id = "tab_1",
            title = "Antigravity · app",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(tab1))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Antigravity · app")

        // Open Settings & Debug from overflow menu
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_settings").performClick()
        waitForTag("debug_sharing_switch")
        composeRule.onNodeWithTag("settings_copy_setup_link_button").performScrollTo().assertIsDisplayed()

        // Enable Debug Sharing to VM and upload a dump with a note
        composeRule.onNodeWithTag("debug_sharing_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("debug_note_input").performScrollTo().performTextInput("agy glitched on comment")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("upload_debug_dump_button").performScrollTo().performClick()

        // Wait for VM upload confirmation card showing remote dump directory
        waitForTag("last_uploaded_dump_card")
        composeRule.onNodeWithText("20261124_", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(activeDaemon.uploadedDebugDumps.any { it.note == "agy glitched on comment" })

        // Return to workspace and verify the quick TopAppBar Bug Report icon is visible and works
        composeRule.onNodeWithTag("settings_back_button").performClick()
        waitForTag("top_debug_dump_button")
        composeRule.onNodeWithTag("top_debug_dump_button").performClick()
        waitForTag("quick_debug_note_input")
        composeRule.onNodeWithTag("quick_debug_note_input").performTextInput("quick dump from chat")
        composeRule.onNodeWithTag("quick_debug_upload_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.uploadedDebugDumps.any { it.note == "quick dump from chat" }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun appStateReporterAnswersVmRequestsOnlyWhileUserAllowsInspection() {
        val tab1 = TabRecord(
            id = "tab_1",
            title = "Antigravity · app",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(tab1))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Antigravity · app")

        // The app announces itself after the snapshot. Inspection is off by default and refused.
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello() != null }
        assertEquals(false, activeDaemon.latestAppHello()!!["enabled"])
        val refused = activeDaemon.requestAppState("state")
        assertEquals(false, refused["ok"])
        assertTrue((refused["error"] as String).contains("disabled"))

        // The user opts in from Settings: debug sharing, then "Allow VM agents to inspect the app".
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_settings").performClick()
        waitForTag("debug_sharing_switch")
        composeRule.onNodeWithTag("debug_sharing_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("debug_remote_inspection_switch").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello()?.get("enabled") == true }
        val commands = activeDaemon.latestAppHello()!!["commands"] as List<String>
        assertTrue(commands.containsAll(listOf("ping", "state", "logs", "logcat", "crash", "screenshot", "dump")))

        // state: live UI + workspace, with the token reported only as a boolean.
        val state = activeDaemon.requestAppState("state")
        assertEquals(true, state["ok"])
        val result = state["result"] as Map<String, Any?>
        val ui = result["ui"] as Map<String, Any?>
        assertEquals("settings", ui["screen"])
        assertEquals(true, ui["appInForeground"])
        assertEquals(true, (ui["debugSettings"] as Map<String, Any?>)["remoteInspectionEnabled"])
        val workspace = result["workspace"] as Map<String, Any?>
        assertEquals("CONNECTED", workspace["connectionState"])
        assertEquals(true, workspace["hasToken"])
        assertEquals("tab_1", ((workspace["tabs"] as List<Map<String, Any?>>).single())["id"])

        // logs: the in-app event buffer, filterable by category.
        val logs = activeDaemon.requestAppState("logs", """{"categories":["ui"],"limit":50}""")
        val events = (logs["result"] as Map<String, Any?>)["events"] as List<Map<String, Any?>>
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it["category"] == "ui" })
        assertTrue(events.any { it["event"] == "debug_settings_updated" })

        // screenshot: a real PNG of the app window.
        val shot = activeDaemon.requestAppState("screenshot")
        assertEquals("screenshot failed: ${shot["error"]}", true, shot["ok"])
        val png = android.util.Base64.decode(
            (shot["result"] as Map<String, Any?>)["pngBase64"] as String,
            android.util.Base64.DEFAULT,
        )
        assertTrue("screenshot too small: ${png.size} bytes", png.size > 2_000)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xFF })
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertTrue(bitmap.width > 100 && bitmap.height > 100)

        assertEquals(false, (activeDaemon.requestAppState("crash")["result"] as Map<String, Any?>)["hasCrash"])
        assertEquals(false, activeDaemon.requestAppState("bogus")["ok"])

        // Nothing the app sent may contain the bearer token.
        assertTrue(activeDaemon.receivedAppFrames.none { it.contains(activeDaemon.token) })

        // Turning the switch off takes effect immediately.
        composeRule.onNodeWithTag("debug_remote_inspection_switch").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello()?.get("enabled") == false }
        assertEquals(false, activeDaemon.requestAppState("state")["ok"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun vmAgentDrivesTheAppOnceTheUserAllowsControl() {
        val tab1 = TabRecord(
            id = "tab_1",
            title = "Antigravity · app",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(tab1))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Antigravity · app")

        // What the VM agent does, through the same frames the daemon relays for agents_term_app_cli.
        // Under the Compose test rule the UI only advances while the test thread is inside the rule,
        // so each step ends with waitForIdle and waits go through waitUntil.
        fun agent(command: String, argsJson: String = "{}"): Map<String, Any?> {
            val response = activeDaemon.requestAppState(command, argsJson)
            composeRule.waitForIdle()
            assertEquals("'$command' $argsJson failed: ${response["error"]}", true, response["ok"])
            return response["result"] as Map<String, Any?>
        }
        fun nodes(argsJson: String = "{}") = agent("ui", argsJson)["nodes"] as List<Map<String, Any?>>
        fun screen() = (agent("state")["ui"] as Map<String, Any?>)["screen"]
        fun agentWaitsFor(what: String, condition: () -> Boolean) {
            try {
                composeRule.waitUntil(timeoutMillis = 10_000L) { condition() }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Timed out waiting for $what. On screen: ${nodes().map { it["tag"] ?: it["text"] }}")
            }
        }

        // Like `agents_term_app_cli wait --tag … && agents_term_app_cli tap --tag …`: screens change asynchronously.
        fun agentTaps(tag: String): Map<String, Any?> {
            agentWaitsFor("'$tag' to tap") { nodes("""{"tag":"$tag"}""").isNotEmpty() }
            return agent("tap", """{"tag":"$tag"}""")
        }

        // The user allows inspection only: the agent can read the screen but not touch it.
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_settings").performClick()
        waitForTag("debug_sharing_switch")
        composeRule.onNodeWithTag("debug_sharing_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("debug_remote_inspection_switch").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello()?.get("enabled") == true }
        assertEquals(false, activeDaemon.latestAppHello()!!["controlEnabled"])
        composeRule.onNodeWithTag("debug_remote_control_switch").performScrollTo()
        assertEquals(
            "on screen: ${nodes()}",
            "off",
            nodes("""{"tag":"debug_remote_control_switch"}""").singleOrNull()?.get("toggle"),
        )
        val refused = activeDaemon.requestAppState("key", """{"key":"back"}""")
        assertEquals(false, refused["ok"])
        assertTrue((refused["error"] as String).contains("Remote control is disabled"))
        assertEquals("settings", screen())

        // The user allows control too.
        composeRule.onNodeWithTag("debug_remote_control_switch").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello()?.get("controlEnabled") == true }
        // The hello can reach the daemon before the switch is redrawn.
        agentWaitsFor("the control switch to show on") {
            nodes("""{"tag":"debug_remote_control_switch"}""").singleOrNull()?.get("toggle") == "on"
        }
        assertTrue(
            (activeDaemon.latestAppHello()!!["commands"] as List<String>)
                .containsAll(listOf("ui", "tap", "long_press", "swipe", "scroll", "type", "key")),
        )

        // Scroll the settings list back up, by dragging it.
        val scrolled = agent("scroll", """{"direction":"up"}""")
        assertEquals("activity", (scrolled["window"] as Map<String, Any?>)["type"])
        agentWaitsFor("the top of Settings") { nodes("""{"tag":"settings_back_button"}""").isNotEmpty() }

        // Back leaves Settings.
        assertEquals("handled", agent("key", """{"key":"back"}""")["outcome"])
        agentWaitsFor("the workspace") { screen() == "workspace" }

        // Open the overflow menu. It is a separate window, and shows up in the UI tree and screenshot.
        agentTaps("overflow_menu_button")
        agentWaitsFor("the overflow menu") { nodes("""{"tag":"menu_app_settings"}""").isNotEmpty() }
        val menuItem = nodes("""{"tag":"menu_app_settings"}""").single()
        assertTrue("the menu item should be in a window above the Activity", (menuItem["window"] as Number).toInt() >= 1)
        val shot = agent("screenshot")
        val png = android.util.Base64.decode(shot["pngBase64"] as String, android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        val ui = agent("ui")
        assertEquals("ui and screenshot share one coordinate space", bitmap.width, (ui["width"] as Number).toInt())
        assertEquals(bitmap.height, (ui["height"] as Number).toInt())

        // Back closes the menu and nothing else.
        agent("key", """{"key":"back"}""")
        agentWaitsFor("the menu to close") { nodes("""{"tag":"menu_app_settings"}""").isEmpty() }
        assertEquals("workspace", screen())

        // Tap a menu item by the position read from the UI tree, like an agent working from a screenshot.
        agentTaps("overflow_menu_button")
        agentWaitsFor("the overflow menu") { nodes("""{"tag":"menu_app_settings"}""").isNotEmpty() }
        val center = nodes("""{"tag":"menu_app_settings"}""").single()["center"] as List<Number>
        val tapped = agent("tap", """{"x":${center[0].toInt()},"y":${center[1].toInt()}}""")
        assertEquals("popup", (tapped["window"] as Map<String, Any?>)["type"])
        agentWaitsFor("Settings") { screen() == "settings" }
        agentTaps("settings_back_button")
        agentWaitsFor("the workspace") { screen() == "workspace" }

        // Write a prompt and send it.
        agentWaitsFor("the composer") { nodes("""{"tag":"composer_input"}""").isNotEmpty() }
        val typed = agent("type", """{"tag":"composer_input","text":"Summarize the repo"}""")
        assertEquals(18, (typed["chars"] as Number).toInt())
        agentWaitsFor("the prompt in the composer") {
            nodes("""{"tag":"composer_input"}""").singleOrNull()?.get("value") == "Summarize the repo"
        }
        agentTaps("send_button")
        agentWaitsFor("the agent's reply") { nodes("""{"text":"Repository is clean on main branch."}""").isNotEmpty() }
        waitForText("Repository is clean on main branch.")

        // Every control action is in the app's debug log, and no frame leaked the token.
        val logged = (agent("logs", """{"categories":["app_state"],"limit":200}""")["events"] as List<Map<String, Any?>>)
            .map { it["detail"] as String }
        assertTrue(logged.any { it.contains("'tap'") })
        assertTrue(logged.any { it.contains("'type'") })
        assertTrue(activeDaemon.receivedAppFrames.none { it.contains(activeDaemon.token) })

        // Turning control off takes effect immediately; inspection keeps working.
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_settings").performClick()
        waitForTag("debug_sharing_switch")
        composeRule.onNodeWithTag("debug_remote_control_switch").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.latestAppHello()?.get("controlEnabled") == false }
        assertEquals(false, activeDaemon.requestAppState("tap", """{"tag":"settings_back_button"}""")["ok"])
        assertEquals("settings", screen())
    }

    @Test
    fun sshBootstrapWithFakeVmStreamsAgyAndConnectsToAgentWorkspace() {
        val activeDaemon = startDaemon()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fakeSsh = FakeSshVmServer(
            context = context,
            expectedUsername = "ned",
            allowNoneAuth = true,
            pairingUri = activeDaemon.pairingUri("herdr"),
            daemonPort = activeDaemon.port,
        ).also {
            it.start()
            sshServer = it
        }

        launchMainActivity()

        // Default unconfigured screen opens in SSH + agy Setup mode (Tailscale SSH / none auth)
        waitForTag("ssh_host_input")
        composeRule.onNodeWithTag("ssh_host_input").performScrollTo().performTextReplacement(fakeSsh.host)
        composeRule.onNodeWithTag("ssh_username_input").performScrollTo().performTextReplacement("ned")
        composeRule.onNodeWithTag("ssh_port_input").performScrollTo().performTextReplacement(fakeSsh.port.toString())
        composeRule.onNodeWithTag("ssh_daemon_port_input").performScrollTo().performTextReplacement(activeDaemon.port.toString())
        closeSoftKeyboard()

        // Start SSH bootstrap against the in-process fake VM SSH server
        composeRule.onNodeWithTag("ssh_bootstrap_button").performScrollTo().performClick()

        // App connects over real JSch, parses streamed agy JSON, extracts pairing URI,
        // verifies /api/info on FakeAgentsTermDaemon, and opens the workspace automatically.
        waitForText("Connected to ci-test-vm", timeoutMs = 20_000L)
        assertTrue(fakeSsh.receivedCommands.isNotEmpty())
        assertTrue(fakeSsh.receivedCommands.first().contains("base64 -d | sh -s"))

        // Create an Antigravity (agy) agent tab and verify full agent streaming workflow
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_agy").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("SSH Agy Tab")
        submitNewTab("SSH Agy Tab")
        waitForTag("composer_input")
        sendComposerMessage("Diagnose phone glitch")

        waitForText("Diagnose phone glitch")
        waitForText("Repository is clean on main branch.")
    }

    @Test
    fun emptyAgentCompletionShowsFallbackNoticeAndAllowsSubsequentTurn() {
        val seedTab = TabRecord(
            id = "tab_agy_empty",
            title = "agents-term",
            agent = "agy",
            cwd = "/home/ci/projects/agents-term",
            permissionMode = "full-access",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(seedTab))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForTag("composer_input")

        // 1) Reproduce empty completion from debug dump 3445b6c8: should show clear fallback text
        sendComposerMessage("simulate_empty_response")

        waitForText("No response text was returned by the agent.")

        // 2) Subsequent normal prompt on the same agy tab still streams and completes cleanly
        sendComposerMessage("Can you say something?")

        waitForText("Can you say something?")
        waitForText("Repository is clean on main branch.")
    }

    @Test
    fun sshSetupDeepLinkAndSmartPasteAutofillConnectScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sshDeepLink = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("agentsterm://ssh?host=100.71.219.26&user=ned&sshPort=2222&daemonPort=7502&auth=password"),
        ).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(sshDeepLink)

        // Deep link opens ConnectScreen in SSH mode with every field autofilled
        waitForTag("ssh_host_input")
        composeRule.onNodeWithTag("ssh_autofill_clipboard_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ssh_host_input").performScrollTo().assertTextContains("100.71.219.26")
        composeRule.onNodeWithTag("ssh_username_input").performScrollTo().assertTextContains("ned")
        composeRule.onNodeWithTag("ssh_port_input").performScrollTo().assertTextContains("2222")
        composeRule.onNodeWithTag("ssh_daemon_port_input").performScrollTo().assertTextContains("7502")
        composeRule.onNodeWithTag("ssh_password_input").performScrollTo().assertIsDisplayed()

        // Pasting an ssh CLI target directly into the host field splits and autofills host, user, and port
        composeRule.onNodeWithTag("ssh_host_input")
            .performScrollTo()
            .performTextReplacement("ssh -p 2200 alice@dev-vm.ts.net")

        composeRule.onNodeWithTag("ssh_host_input").performScrollTo().assertTextContains("dev-vm.ts.net")
        composeRule.onNodeWithTag("ssh_username_input").performScrollTo().assertTextContains("alice")
        composeRule.onNodeWithTag("ssh_port_input").performScrollTo().assertTextContains("2200")
    }

    @Test
    fun reconnectOnForegroundAfterSocketDropStabilizesWithoutReconnectStorm() {
        val seedTab = TabRecord(
            id = "tab_reconnect",
            title = "Antigravity · stable",
            agent = "agy",
            cwd = "/home/ci/projects/agents-term",
            permissionMode = "full-access",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(seedTab))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Antigravity · stable")
        waitForTag("composer_input")

        val initialUpgrades = activeDaemon.webSocketUpgradeCount.get()
        assertTrue("Expected at least 1 initial WS connection", initialUpgrades >= 1)

        // Drop the active socket, wait for the disconnect banner (which schedules the 1.5s
        // backoff reconnect), then resume to foreground to trigger an overlapping reconnect.
        activeDaemon.dropAllWebSockets()
        waitForTag("banner_reconnect_button")
        scenario?.moveToState(Lifecycle.State.STARTED)
        scenario?.moveToState(Lifecycle.State.RESUMED)

        // Wait for reconnection to succeed and the whole banner to clear. The retry button alone
        // is not enough: it is hidden while still connecting, when the composer cannot send yet.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.webSocketUpgradeCount.get() > initialUpgrades &&
                composeRule.onAllNodesWithTag("connection_banner")
                    .fetchSemanticsNodes()
                    .isEmpty()
        }

        // Send a prompt to confirm the recovered socket is healthy
        sendComposerMessage("Verify reconnect stability")
        waitForText("Repository is clean on main branch.")

        // Wait longer than 2x the 1.5s reconnect interval and assert no further reconnects occur
        val stableUpgrades = activeDaemon.webSocketUpgradeCount.get()
        Thread.sleep(3_500L)
        assertEquals(
            "WebSocket entered a reconnect loop after foreground recovery",
            stableUpgrades,
            activeDaemon.webSocketUpgradeCount.get(),
        )
    }

    @Test
    fun modelPickerAndSlashModelCommandSwitchAgentModelEndToEnd() {
        val activeDaemon = startDaemon()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Connected to ci-test-vm")

        // 1) Create a Claude tab with a specific initial model picked in NewTabSheet
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_claude").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_model_claude-sonnet-5-5").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Claude Model Tab")
        submitNewTab("Claude Model Tab")
        waitForTag("model_picker_chip")
        composeRule.onNodeWithTag("model_picker_chip").assertTextContains("Sonnet 5.5")
        assertEquals("claude-sonnet-5-5", activeDaemon.tabModel("tab_1"))

        // 2) Tap the per-tab model picker chip and switch to Opus 5.5
        composeRule.onNodeWithTag("model_picker_chip").performClick()
        waitForTag("model_option_claude-opus-5-5")
        composeRule.onNodeWithTag("model_option_claude-opus-5-5").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.tabModel("tab_1") == "claude-opus-5-5" &&
                composeRule.onAllNodes(hasTestTag("model_picker_chip") and hasText("Opus 5.5", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        composeRule.onNodeWithTag("model_picker_chip").assertTextContains("Opus 5.5")

        // 3) Use /model default slash command in composer to reset to CLI default
        sendComposerMessage("/model default")
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.tabModel("tab_1") == null &&
                composeRule.onAllNodes(hasTestTag("model_picker_chip") and hasText("Default model", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        composeRule.onNodeWithTag("model_picker_chip").assertTextContains("Default model")

        // 4) Use /model (bare command) to open the ModelPickerSheet and apply a custom model ID
        sendComposerMessage("/model")
        waitForTag("custom_model_input")
        composeRule.onNodeWithTag("custom_model_input").performScrollTo().performTextInput("claude-custom-exp")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("custom_model_apply_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.tabModel("tab_1") == "claude-custom-exp" &&
                composeRule.onAllNodes(hasTestTag("model_picker_chip") and hasText("claude-custom-exp", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        composeRule.onNodeWithTag("model_picker_chip").assertTextContains("claude-custom-exp")

        // 5) Shell tabs should NOT display the model picker chip
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_shell").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Plain Shell")
        submitNewTab("Plain Shell")
        composeRule.waitForIdle()
        assertTrue(
            "Shell tab should not show model picker chip",
            composeRule.onAllNodesWithTag("model_picker_chip").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun newTabDefaultsToLastFolderLocationAndFallsBackToHomeWhenDeleted() {
        val activeDaemon = startDaemon()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Connected to ci-test-vm")

        // 1) Browse to /home/ci/projects/agents-term and create a tab there
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        waitForText("projects")
        composeRule.onNodeWithText("projects").performScrollTo().performClick()
        waitForText("agents-term")
        composeRule.onNodeWithText("agents-term").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("new_tab_cwd_input") and hasText("/home/ci/projects/agents-term"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Worktree Tab")
        submitNewTab("Worktree Tab")

        // 2) Close the tab (returning to empty workspace) and open New Tab: must remember last folder
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithText("Close tab").performClick()
        waitForText("Connected to ci-test-vm")

        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("new_tab_cwd_input") and hasText("/home/ci/projects/agents-term"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("new_tab_cwd_input").assertTextContains("/home/ci/projects/agents-term")

        // Dismiss sheet before simulating folder deletion
        pressBack()
        waitForTag("empty_new_tab_button")

        // 3) Delete /home/ci/projects/agents-term on VM and open New Tab: must fall back to /home/ci
        activeDaemon.deleteDirectory("/home/ci/projects/agents-term")
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("new_tab_cwd_input") and hasText("/home/ci"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("new_tab_cwd_input").assertTextContains("/home/ci")
        waitForText("projects")
        waitForText("scripts")
    }

    @Test
    fun multiToolTurnGroupsCallsAndExpandsDetailsOnTap() {
        val seedTab = TabRecord(
            id = "tab_tools",
            title = "Claude · agents-term",
            agent = "claude",
            cwd = "/home/ci/projects/agents-term",
            permissionMode = "full-access",
            status = "idle",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(seedTab))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForTag("composer_input")

        sendComposerMessage("multi_tool_turn check repo")

        waitForText("Checking repository state first.")
        waitForText("3 tool calls · 3 done")
        waitForText("All repository checks passed.")

        // Individual completed calls stay collapsed until the summary row is tapped
        assertTrue(composeRule.onAllNodesWithText("Check git status").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("tool_run_header").performClick()
        waitForText("Check git status")
        waitForText("git diff --stat")
        waitForText("Run unit tests")

        composeRule.onNodeWithText("Run unit tests").performClick()
        waitForText("28 passed")
    }

    @Test
    fun systemPromptDefaultsOnNewTabCanBeEditedOrLeftEmptyEndToEnd() {
        val activeDaemon = startDaemon()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)
        waitForText("Connected to ci-test-vm")

        // 1) Default new coding-agent tab includes the default mobile + live-debugging system prompt
        composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_claude").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Default Prompt Tab")
        submitNewTab("Default Prompt Tab")
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.hasTab("tab_1") }
        val defaultSaved = activeDaemon.tabSystemPrompt("tab_1")
        assertTrue(
            "Expected default full-access system prompt to mention live-debugging.md and closing the tab, got: $defaultSaved",
            defaultSaved != null &&
                defaultSaved.contains(".agents-term/live-debugging.md") &&
                defaultSaved.contains("close this tab", ignoreCase = true),
        )

        // 2) Edit the tab's system prompt via overflow menu -> custom, then clear to empty
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        waitForTag("menu_system_prompt")
        composeRule.onNodeWithTag("menu_system_prompt").assertTextContains("Default", substring = true)
        composeRule.onNodeWithTag("menu_system_prompt").performClick()
        waitForTag("edit_tab_system_prompt_input")
        composeRule.onNodeWithTag("edit_tab_system_prompt_input")
            .performTextReplacement("Respond in short bullet points only.")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("edit_tab_system_prompt_save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.tabSystemPrompt("tab_1") == "Respond in short bullet points only."
        }

        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        waitForTag("menu_system_prompt")
        composeRule.onNodeWithTag("menu_system_prompt").assertTextContains("Custom", substring = true)
        composeRule.onNodeWithTag("menu_system_prompt").performClick()
        waitForTag("edit_tab_system_prompt_switch")
        composeRule.onNodeWithTag("edit_tab_system_prompt_switch").performClick()
        composeRule.onNodeWithTag("edit_tab_system_prompt_save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activeDaemon.tabSystemPrompt("tab_1") == null
        }

        // 3) Create a Plan Only tab and verify default prompt omits the full-access close-tab line
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_claude").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_permission_plan").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Plan Prompt Tab")
        submitNewTab("Plan Prompt Tab")
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.hasTab("tab_2") }
        val planSaved = activeDaemon.tabSystemPrompt("tab_2")
        assertTrue(
            "Expected plan-mode default prompt to keep live-debugging.md without close-tab notice, got: $planSaved",
            planSaved != null &&
                planSaved.contains(".agents-term/live-debugging.md") &&
                !planSaved.contains("close this tab", ignoreCase = true),
        )

        // 4) Create a coding-agent tab with system prompt toggled OFF (empty)
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_agy").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_system_prompt_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextInput("Empty Prompt Tab")
        submitNewTab("Empty Prompt Tab")
        composeRule.waitUntil(timeoutMillis = 10_000L) { activeDaemon.hasTab("tab_3") }
        assertNull(activeDaemon.tabSystemPrompt("tab_3"))

        // 5) Shell tab does not show system prompt controls
        composeRule.onNodeWithTag("top_new_tab_button").performClick()
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_shell").performScrollTo().performClick()
        assertTrue(
            "Shell tab should not show system prompt toggle",
            composeRule.onAllNodesWithTag("new_tab_system_prompt_switch").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun finishedTaskTabShowsDoneIndicatorsInPersistentTabsMenuAndCanBeClosed() {
        val activeTab = TabRecord(
            id = "tab_active",
            title = "Active Agent",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
        )
        val finishedTab = TabRecord(
            id = "tab_done",
            title = "Finished Agent",
            agent = "claude",
            cwd = "/home/ci/projects",
            status = "done",
            statusDetail = "Task finished",
            lastMessagePreview = "This task is finished — you can close this tab.",
        )
        val activeDaemon = startDaemon(initialTabs = listOf(activeTab, finishedTab))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(activeDaemon.pairingUri("agentsterm"))).apply {
            setClass(context, MainActivity::class.java)
        }
        launchMainActivity(deepLink)

        // Quick tab strip shows checkmark icon on the finished tab
        waitForTag("quick_tab_done_icon_tab_done", useUnmergedTree = true)
        composeRule.onNodeWithTag("quick_tab_done_icon_tab_done", useUnmergedTree = true).assertIsDisplayed()

        // Selecting the finished tab shows Done indicator in top bar
        composeRule.onNodeWithTag("quick_tab_tab_done").performClick()
        waitForTag("topbar_done_badge")
        composeRule.onNodeWithTag("topbar_done_badge").assertIsDisplayed()

        // Open Persistent Tabs drawer, verify finished-task UI, and close finished tabs
        verifyAndCloseFinishedTabInDrawer(finishedTabId = "tab_done", activeTabId = "tab_active")

        // Select remaining active tab, trigger a turn that finishes its task, verify Done appears, then mark active
        composeRule.onNodeWithTag("drawer_tab_tab_active").performClick()
        composeRule.waitForIdle()
        waitForTag("composer_input")
        sendComposerMessage("finish_task ship update")

        waitForTag("topbar_done_badge")
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        waitForTag("menu_toggle_task_done")
        composeRule.onNodeWithTag("menu_toggle_task_done").assertTextContains("Mark as active")
        composeRule.onNodeWithTag("menu_toggle_task_done").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("topbar_done_badge").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun verifyAndCloseFinishedTabInDrawer(finishedTabId: String, activeTabId: String) {
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        waitForTag("drawer_finished_count_badge")
        composeRule.onNodeWithTag("drawer_finished_count_badge").assertTextContains("1 finished")
        composeRule.onNodeWithTag("drawer_tab_done_icon_$finishedTabId", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_tab_done_badge_$finishedTabId", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_tab_done_hint_$finishedTabId", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Task finished", substring = true)

        composeRule.onNodeWithTag("drawer_close_finished_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("drawer_tab_$finishedTabId").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("drawer_tab_$activeTabId").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Finished count badge should disappear after closing finished tabs",
            composeRule.onAllNodesWithTag("drawer_finished_count_badge").fetchSemanticsNodes().isEmpty(),
        )
    }
}
