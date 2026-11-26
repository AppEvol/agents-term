package com.example

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatMessage
import com.example.data.DeniedAction
import com.example.data.MessageSegment
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.ui.ChatWorkspace
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ChatToolCallsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testTab = TabRecord(
        id = "tab_1",
        title = "Claude · agents-term",
        agent = "claude",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    @Test
    fun runningToolRunStaysCollapsedToSummaryHeaderUntilExpanded() {
        val tools = listOf(
            ToolCallItem("t1", "Bash", "cd ~/projects/app && git status", """{"command":"cd ~/projects/app && git status","description":"Check git status"}""", "clean", "done", 120L),
            ToolCallItem("t2", "Bash", "cd ~/projects/app && git diff", """{"command":"cd ~/projects/app && git diff","description":"Inspect diff"}""", "0 changes", "done", 90L),
            ToolCallItem("t3", "Bash", "cd ~/projects/app && git log -n 5", """{"command":"cd ~/projects/app && git log -n 5","description":"Read recent commits"}""", "abc123", "done", 80L),
            ToolCallItem("t4", "Bash", "cd ~/projects/app && ls -la", """{"command":"cd ~/projects/app && ls -la","description":"List workspace files"}""", "README.md", "done", 60L),
            ToolCallItem("t5", "Bash", "cd ~/projects/app && pytest", """{"command":"cd ~/projects/app && pytest","description":"Run daemon tests"}""", "28 passed", "done", 450L),
            ToolCallItem("t6", "Bash", "cd ~/projects/app && gradle test", """{"command":"cd ~/projects/app && gradle test","description":"Run Android unit tests"}""", null, "running", null),
        )
        val message = ChatMessage(
            id = "msg_running",
            tabId = "tab_1",
            role = "assistant",
            text = "Checking the repository.",
            toolCalls = tools,
            segments = listOf(MessageSegment("text", text = "Checking the repository.")) +
                tools.map { MessageSegment("tool", toolCallId = it.id) },
            status = "streaming",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab.copy(status = "working", statusDetail = "Running Bash: Run Android unit tests"),
                    messages = listOf(message),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        // Summary header collapses all 6 calls into one stable row (active command stays in composer status)
        composeRule.onNodeWithText("6 tool calls · 5 done · 1 running").assertIsDisplayed()
        // Only the group header keeps a sandclock indicator
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
        // Individual tool call rows remain hidden until the header is tapped
        assertTrue(composeRule.onAllNodesWithText("Check git status").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Run daemon tests").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("tool_call_card_t6").fetchSemanticsNodes().isEmpty())

        // Tapping the group header reveals all 6 calls with their meaningful descriptions
        composeRule.onNodeWithTag("tool_run_header").performClick()
        composeRule.onNodeWithText("Check git status").assertIsDisplayed()
        composeRule.onNodeWithText("Run daemon tests").assertIsDisplayed()
        composeRule.onNodeWithText("Run Android unit tests").assertIsDisplayed()

        // Tapping an individual call expands its output
        composeRule.onNodeWithTag("tool_call_card_t5").performClick()
        composeRule.onNodeWithText("28 passed").assertIsDisplayed()
    }

    @Test
    fun completedInterleavedTurnRendersStepsAndCollapsedToolRunsInOrder() {
        val t1 = ToolCallItem("t1", "Bash", "cd ~/projects/app && git status", status = "done", durationMs = 110L)
        val t2 = ToolCallItem("t2", "Bash", "cd ~/projects/app && git diff", status = "done", durationMs = 140L)
        val t3 = ToolCallItem("t3", "Bash", "cd ~/projects/app && pytest", status = "done", durationMs = 300L)
        val t4 = ToolCallItem("t4", "Bash", "cd ~/projects/app && gradle test", status = "done", durationMs = 900L)
        val message = ChatMessage(
            id = "msg_interleaved",
            tabId = "tab_1",
            role = "assistant",
            text = "First I will inspect git.\n\nNow I will run the suites.\n\nEverything passed.",
            toolCalls = listOf(t1, t2, t3, t4),
            segments = listOf(
                MessageSegment("text", text = "First I will inspect git."),
                MessageSegment("tool", toolCallId = "t1"),
                MessageSegment("tool", toolCallId = "t2"),
                MessageSegment("text", text = "Now I will run the suites."),
                MessageSegment("tool", toolCallId = "t3"),
                MessageSegment("tool", toolCallId = "t4"),
                MessageSegment("text", text = "Everything passed."),
            ),
            status = "complete",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab,
                    messages = listOf(message),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        val step1Top = composeRule.onNodeWithText("First I will inspect git.").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val step2Top = composeRule.onNodeWithText("Now I will run the suites.").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val finalTop = composeRule.onNodeWithText("Everything passed.").assertIsDisplayed().getUnclippedBoundsInRoot().top

        // Finished calls are collapsed into summary rows (none of the individual commands shown yet)
        val groupNodes = composeRule.onAllNodesWithText("2 tool calls · 2 done")
        assertTrue(groupNodes.fetchSemanticsNodes().size == 2)
        val group1Top = groupNodes[0].getUnclippedBoundsInRoot().top
        val group2Top = groupNodes[1].getUnclippedBoundsInRoot().top
        assertTrue(composeRule.onAllNodesWithText("git status").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("pytest").fetchSemanticsNodes().isEmpty())

        // Verify strict chronological vertical layout: step 1 -> tools 1 -> step 2 -> tools 2 -> final answer
        assertTrue("Expected step1 < group1 < step2 < group2 < final", step1Top < group1Top && group1Top < step2Top && step2Top < group2Top && group2Top < finalTop)
    }

    @Test
    fun liveRunKeepsCardHeightConstantAcrossRunningGapsTextAndCompletion() {
        fun call(id: String, description: String, status: String, durationMs: Long? = null) = ToolCallItem(
            id = id,
            name = "Bash",
            inputSummary = "git $id",
            inputJson = """{"command":"git $id","description":"$description"}""",
            status = status,
            durationMs = durationMs,
        )
        val t1Done = call("t1", "Check git status", "done", 100L)
        val t2Running = call("t2", "Inspect diff", "running")
        val t2Done = call("t2", "Inspect diff", "done", 100L)

        fun message(
            calls: List<ToolCallItem>,
            status: String,
            trailingText: String? = null,
        ): ChatMessage {
            val segments = buildList {
                calls.forEach { add(MessageSegment("tool", toolCallId = it.id)) }
                if (trailingText != null) add(MessageSegment("text", text = trailingText))
            }
            return ChatMessage(
                id = "msg_between_calls",
                tabId = "tab_1",
                role = "assistant",
                text = trailingText.orEmpty(),
                toolCalls = calls,
                segments = segments,
                status = status,
            )
        }

        var current by mutableStateOf(message(listOf(t1Done, t2Running), "streaming"))

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab.copy(status = "working"),
                    messages = listOf(current),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        val runningHeight = composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height
        composeRule.onNodeWithText("2 tool calls · 1 done · 1 running").assertIsDisplayed()
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
        assertTrue(composeRule.onAllNodesWithText("Inspect diff").fetchSemanticsNodes().isEmpty())

        // Inter-call gap while still streaming: header stays active without flashing duration or changing height.
        current = message(listOf(t1Done, t2Done), "streaming")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 tool calls · 2 done").assertIsDisplayed()
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
        assertTrue(composeRule.onAllNodesWithText("200ms").fetchSemanticsNodes().isEmpty())
        assertEquals(
            runningHeight,
            composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height,
        )

        // Assistant starts streaming text after the tool run: icon flips from sandclock to checkmark
        // and duration appears, while card height stays strictly identical.
        current = message(listOf(t1Done, t2Done), "streaming", trailingText = "Looks clean.")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 tool calls · 2 done").assertIsDisplayed()
        composeRule.onNodeWithText("200ms").assertIsDisplayed()
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
        assertEquals(
            runningHeight,
            composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height,
        )

        // Turn completes: height stays identical.
        current = message(listOf(t1Done, t2Done), "complete", trailingText = "Looks clean.")
        composeRule.waitForIdle()
        assertEquals(
            runningHeight,
            composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height,
        )
    }

    @Test
    fun parallelRunningCallsKeepCollapsedCardHeightConstantAndRevealAllWhenExpanded() {
        fun tool(id: String, desc: String, status: String, durationMs: Long? = null) = ToolCallItem(
            id = id,
            name = "read_file",
            inputSummary = desc,
            inputJson = """{"description":"$desc"}""",
            status = status,
            durationMs = durationMs,
        )

        fun msg(calls: List<ToolCallItem>, status: String = "streaming") = ChatMessage(
            id = "msg_parallel",
            tabId = "tab_1",
            role = "assistant",
            toolCalls = calls,
            segments = calls.map { MessageSegment("tool", toolCallId = it.id) },
            status = status,
        )

        val t1Done = tool("t1", "Read ChatScreen.kt", "done", 90L)
        val t2Run = tool("t2", "Read WorkingIndicators.kt", "running")
        val t3Run = tool("t3", "Read Controls.kt", "running")
        val t4Run = tool("t4", "Read Models.kt", "running")

        var current by mutableStateOf(msg(listOf(t1Done, t2Run)))

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab.copy(status = "working"),
                    messages = listOf(current),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        val initialHeight = composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height
        composeRule.onNodeWithText("2 tool calls · 1 done · 1 running").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Read WorkingIndicators.kt").fetchSemanticsNodes().isEmpty())

        // Starting two more calls in parallel keeps the collapsed card at the exact same height.
        current = msg(listOf(t1Done, t2Run, t3Run, t4Run))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4 tool calls · 1 done · 3 running").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Read Models.kt").fetchSemanticsNodes().isEmpty())
        assertEquals(
            initialHeight,
            composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height,
        )

        // Completing all calls keeps the collapsed card height identical.
        current = msg(
            listOf(
                t1Done,
                t2Run.copy(status = "done", durationMs = 50L),
                t3Run.copy(status = "done", durationMs = 60L),
                ToolCallItem(id = "t4", name = "update_steps", inputSummary = "", status = "done", durationMs = 40L),
            ),
            status = "complete",
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4 tool calls · 4 done").assertIsDisplayed()
        assertEquals(
            initialHeight,
            composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height,
        )

        // Expanding reveals every call in the run.
        composeRule.onNodeWithTag("tool_run_header").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Read ChatScreen.kt").assertIsDisplayed()
        composeRule.onNodeWithText("Read WorkingIndicators.kt").assertIsDisplayed()
        composeRule.onNodeWithText("Read Controls.kt").assertIsDisplayed()
        composeRule.onNodeWithText("update_steps").assertIsDisplayed()
    }

    @Test
    fun toolCallGroupUpdatesAndExpandsCleanlyWhenAnimationsAreDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            val t1 = ToolCallItem("t1", "Bash", "git status", output = "clean", status = "done", durationMs = 80L)
            val t2 = ToolCallItem("t2", "Bash", "git diff", output = "no diff", status = "running")
            val t3 = ToolCallItem("t3", "Bash", "gradle test", output = "all passed", status = "running")

            fun msg(calls: List<ToolCallItem>, status: String = "streaming") = ChatMessage(
                id = "msg_no_anim",
                tabId = "tab_1",
                role = "assistant",
                toolCalls = calls,
                segments = calls.map { MessageSegment("tool", toolCallId = it.id) },
                status = status,
            )

            var current by mutableStateOf(msg(listOf(t1)))

            composeRule.setContent {
                HerdrTheme {
                    ChatWorkspace(
                        tab = testTab.copy(status = "working"),
                        messages = listOf(current),
                        isLoading = false,
                        isConnected = true,
                        onSendMessage = {},
                        onInterrupt = {},
                        onRetryFullAccess = {},
                    )
                }
            }

            composeRule.onNodeWithText("git status").assertIsDisplayed()

            current = msg(listOf(t1, t2, t3))
            composeRule.waitForIdle()
            val runningHeight = composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height
            composeRule.onNodeWithText("3 tool calls · 1 done · 2 running").assertIsDisplayed()
            assertTrue(composeRule.onAllNodesWithText("gradle test").fetchSemanticsNodes().isEmpty())

            current = msg(
                listOf(t1, t2.copy(status = "done", durationMs = 50L), t3.copy(status = "done", durationMs = 70L)),
                status = "complete",
            )
            composeRule.waitForIdle()
            assertEquals(runningHeight, composeRule.onNodeWithTag("tool_run_group").getUnclippedBoundsInRoot().height)

            composeRule.onNodeWithTag("tool_run_header").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("git status").assertIsDisplayed()
            composeRule.onNodeWithText("git diff").assertIsDisplayed()
            composeRule.onNodeWithText("gradle test").assertIsDisplayed()

            composeRule.onNodeWithTag("tool_call_card_t1").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("clean").assertIsDisplayed()
        } finally {
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousScale,
            )
        }
    }

    @Test
    fun singleToolCallRendersDirectlyWithoutGroupWrapperAndExpandsOutputOnTap() {
        val onlyTool = ToolCallItem(
            id = "t_only",
            name = "Bash",
            inputSummary = "cd ~/projects/app && git status",
            output = "On branch main\nnothing to commit",
            status = "done",
            durationMs = 120L,
        )
        val message = ChatMessage(
            id = "msg_single",
            tabId = "tab_1",
            role = "assistant",
            text = "Repository is clean.",
            toolCalls = listOf(onlyTool),
            segments = listOf(
                MessageSegment("tool", toolCallId = "t_only"),
                MessageSegment("text", text = "Repository is clean."),
            ),
            status = "complete",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab,
                    messages = listOf(message),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        // Single tool call shows directly with stripped cd prefix and expands on tap
        composeRule.onNodeWithText("Bash").assertIsDisplayed()
        composeRule.onNodeWithText("git status").assertIsDisplayed()
        composeRule.onNodeWithText("Bash").performClick()
        composeRule.onNodeWithText("On branch main\nnothing to commit").assertIsDisplayed()
    }

    @Test
    fun fullAccessDeniedActionsShowAgentSettingsNoticeWithoutRetryButton() {
        val message = ChatMessage(
            id = "msg_full_access_denied",
            tabId = "tab_1",
            role = "assistant",
            text = "Toolchain install didn't happen because curl was denied.",
            deniedActions = listOf(
                DeniedAction("Bash", "Bash", "Check sudo and install Rust toolchain via rustup"),
                DeniedAction("Bash", "Bash", "Download the official rustup installer binary"),
            ),
            // Null permissionMode verifies fallback to tab.permissionMode for older daemon messages.
            permissionMode = null,
            status = "complete",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab.copy(agent = "claude", permissionMode = "full-access", status = "idle"),
                    messages = listOf(message),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithText("Denied by Agent Settings").assertIsDisplayed()
        composeRule.onNodeWithText("~/.claude/settings.json", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bash (Check sudo and install Rust toolchain via rustup)", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Bash (Download the official rustup installer binary)", substring = true)
            .assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Permission Required").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("allow_full_access_button").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun restrictedModeDeniedActionsShowRetryOnlyOnLatestNonWorkingMessage() {
        var retried = 0
        val firstUser = ChatMessage(
            id = "msg_u1",
            tabId = "tab_1",
            role = "user",
            text = "Run tests",
            status = "complete",
        )
        val blockedAssistant = ChatMessage(
            id = "msg_a1",
            tabId = "tab_1",
            role = "assistant",
            text = "Need permission.",
            deniedActions = listOf(DeniedAction("Bash", "Bash", "gradle test")),
            permissionMode = "default",
            status = "complete",
        )
        var currentMessages by mutableStateOf(listOf(firstUser, blockedAssistant))

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = testTab.copy(permissionMode = "default", status = "blocked"),
                    messages = currentMessages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = { retried++ },
                )
            }
        }

        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()
        composeRule.onNodeWithText("Bash (gradle test)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("allow_full_access_button").assertIsDisplayed().performClick()
        assertEquals(1, retried)

        // Once a follow-up turn arrives (e.g. a full-access override that still hits a deny rule),
        // the older message drops its stale retry button and the full-access message shows the policy notice.
        currentMessages = listOf(
            firstUser,
            blockedAssistant,
            ChatMessage(id = "msg_u2", tabId = "tab_1", role = "user", text = "Run tests", status = "complete"),
            ChatMessage(
                id = "msg_a2",
                tabId = "tab_1",
                role = "assistant",
                text = "Still denied by deny rule.",
                deniedActions = listOf(DeniedAction("Bash", "Bash", "curl https://example.com")),
                permissionMode = "full-access",
                status = "complete",
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Denied by Agent Settings").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("allow_full_access_button").fetchSemanticsNodes().isEmpty())
    }
}
