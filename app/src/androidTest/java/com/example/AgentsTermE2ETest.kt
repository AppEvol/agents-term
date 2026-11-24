package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.ChatMessage
import com.example.data.TabRecord
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentsTermE2ETest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var daemon: FakeAgentsTermDaemon? = null

    @Before
    fun clearAppPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("herdr_mobile_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        daemon?.close()
        daemon = null
    }

    private fun startDaemon(
        initialTabs: List<TabRecord> = emptyList(),
        initialMessages: Map<String, List<ChatMessage>> = emptyMap(),
    ): FakeAgentsTermDaemon {
        return FakeAgentsTermDaemon(
            initialTabs = initialTabs,
            initialMessages = initialMessages,
        ).also {
            it.start()
            daemon = it
        }
    }

    private fun launchMainActivity(intent: Intent? = null) {
        scenario = if (intent != null) {
            ActivityScenario.launch(intent)
        } else {
            ActivityScenario.launch(MainActivity::class.java)
        }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000L) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                composeRule.onAllNodesWithText(text, substring = substring)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: Throwable) {
            val reqs = daemon?.recordedRequests?.joinToString("\n  ") ?: "(no daemon)"
            val roots = runCatching { composeRule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
                .getOrElse { it.toString() }
            throw AssertionError(
                "Timed out waiting for text='$text' (substring=$substring).\n" +
                    "Daemon requests:\n  $reqs\n" +
                    "Compose roots:\n$roots",
                e,
            )
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                composeRule.onAllNodesWithTag(tag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: Throwable) {
            val reqs = daemon?.recordedRequests?.joinToString("\n  ") ?: "(no daemon)"
            val roots = runCatching { composeRule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
                .getOrElse { it.toString() }
            throw AssertionError(
                "Timed out waiting for tag='$tag'.\n" +
                    "Daemon requests:\n  $reqs\n" +
                    "Compose roots:\n$roots",
                e,
            )
        }
    }

    private fun connectDirectlyThroughUi(targetDaemon: FakeAgentsTermDaemon, token: String = targetDaemon.token) {
        composeRule.onNodeWithTag("mode_direct_uri").performClick()
        composeRule.onNodeWithTag("host_input").performScrollTo().performTextReplacement(targetDaemon.host)
        composeRule.onNodeWithTag("port_input").performScrollTo().performTextReplacement(targetDaemon.port.toString())
        composeRule.onNodeWithTag("token_input").performScrollTo().performTextReplacement(token)
        Espresso.closeSoftKeyboard()
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

        // Expand custom agy bootstrap prompt editor
        composeRule.onNodeWithTag("toggle_prompt_editor").performScrollTo().performClick()
        composeRule.onNodeWithTag("agy_prompt_input").performScrollTo().assertIsDisplayed()

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
        Espresso.closeSoftKeyboard()
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
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().performClick()

        // Workspace transitions to the newly created tab (subtitle appears in both top bar and tab drawer)
        waitForText("E2E Claude Tab")
        waitForText("Claude · projects")

        // Send a prompt and verify streamed reasoning, tool call, markdown text, and usage stats
        composeRule.onNodeWithTag("composer_input").performTextInput("Summarize the repo")
        composeRule.onNodeWithTag("send_button").performClick()

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

        // 1) Trigger a long-running working turn and interrupt it via Stop button
        composeRule.onNodeWithTag("composer_input").performTextInput("hold_working long job")
        composeRule.onNodeWithTag("send_button").performClick()

        waitForTag("stop_agent_button")
        composeRule.onNodeWithTag("stop_agent_button").performClick()

        waitForText("Interrupted by user")
        waitForTag("send_button")
        assertTrue(activeDaemon.interruptedTabIds.contains("tab_seed"))

        // 2) Trigger a permission-blocked turn and retry with Full Access
        composeRule.onNodeWithTag("composer_input").performTextInput("needs_permission run tests")
        composeRule.onNodeWithTag("send_button").performClick()

        waitForTag("allow_full_access_button")
        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()
        composeRule.onNodeWithTag("allow_full_access_button").performClick()

        waitForText("Full access granted. Command completed successfully.")
        assertTrue(activeDaemon.receivedOverrideModes.contains("full-access"))
    }

    @Test
    fun manageMultipleTabsDrawerRenameCloseAndSettingsRoundTrip() {
        val tab1 = TabRecord(
            id = "tab_1",
            title = "Primary Agent",
            agent = "agy",
            cwd = "/home/ci/projects",
            status = "idle",
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
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().performClick()

        waitForText("Build Shell")

        // Quick tab strip appears with 2 tabs; switch back to tab_1 and verify its transcript loads
        waitForTag("quick_tab_tab_1")
        composeRule.onNodeWithTag("quick_tab_tab_1").performClick()
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
}
