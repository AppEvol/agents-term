package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.ChatMessage
import com.example.data.MessageSegment
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.ui.ChatWorkspace
import com.example.ui.theme.HerdrTheme
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
    fun runningToolRunCollapsesFinishedCallsAndShowsOnlyActiveCallUntilExpanded() {
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

        // Summary header collapses the 6 calls into one row
        composeRule.onNodeWithText("6 tool calls · 5 done · 1 running").assertIsDisplayed()
        // Only the active running call is shown while collapsed
        composeRule.onNodeWithText("Run Android unit tests").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Check git status").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Run daemon tests").fetchSemanticsNodes().isEmpty())

        // Tapping the group header reveals all 6 calls with their meaningful descriptions
        composeRule.onNodeWithTag("tool_run_header").performClick()
        composeRule.onNodeWithText("Check git status").assertIsDisplayed()
        composeRule.onNodeWithText("Run daemon tests").assertIsDisplayed()

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
}
