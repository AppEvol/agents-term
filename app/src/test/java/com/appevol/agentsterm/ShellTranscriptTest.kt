package com.appevol.agentsterm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.UsageStats
import com.appevol.agentsterm.ui.ChatWorkspace
import com.appevol.agentsterm.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ShellTranscriptTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val idleShellTab = TabRecord(
        id = "tab_shell",
        title = "Shell · repo",
        agent = "shell",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    @Test
    fun quietSuccessCommandShowsExitZeroWithoutEmptyCodeBlockOrPlaceholder() {
        val messages = listOf(
            ChatMessage(id = "u1", tabId = "tab_shell", role = "user", text = "true"),
            ChatMessage(
                id = "a1",
                tabId = "tab_shell",
                role = "assistant",
                text = "",
                status = "complete",
                exitCode = 0,
                usage = UsageStats(durationMs = 18L),
            ),
            ChatMessage(id = "u2", tabId = "tab_shell", role = "user", text = "export FOO=bar"),
            ChatMessage(
                id = "a2",
                tabId = "tab_shell",
                role = "assistant",
                text = "",
                status = "complete",
                exitCode = 0,
                usage = null,
            ),
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = idleShellTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("shell_exit_code_a1")
            .assertIsDisplayed()
            .assertTextEquals("Exit 0")
        composeRule.onNodeWithText("18ms").assertIsDisplayed()
        composeRule.onNodeWithTag("shell_exit_code_a2")
            .assertIsDisplayed()
            .assertTextEquals("Exit 0")
        assertTrue(
            "Quiet Shell command must not render empty-agent placeholder",
            composeRule.onAllNodesWithText("No response text was returned by the agent.").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Quiet Shell command must not render an empty code block",
            composeRule.onAllNodesWithContentDescription("Copy code").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun quietFailureCommandShowsExitOneWithoutDuplicateErrorBanner() {
        val messages = listOf(
            ChatMessage(id = "u1", tabId = "tab_shell", role = "user", text = "false"),
            ChatMessage(
                id = "a_false",
                tabId = "tab_shell",
                role = "assistant",
                text = "",
                status = "error",
                error = "Exited with code 1",
                exitCode = 1,
            ),
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = idleShellTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("shell_exit_code_a_false")
            .assertIsDisplayed()
            .assertTextEquals("Exit 1")
        assertTrue(
            "Redundant 'Exited with code 1' banner must not duplicate Exit 1 badge",
            composeRule.onAllNodesWithText("Exited with code 1").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("No response text was returned by the agent.").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun realErrorWithoutExitCodeAndDetailedErrorStillRenderErrorBanner() {
        val messages = listOf(
            ChatMessage(id = "u1", tabId = "tab_shell", role = "user", text = "pwd"),
            ChatMessage(
                id = "a_cwd_err",
                tabId = "tab_shell",
                role = "assistant",
                text = "",
                status = "error",
                error = "Working directory does not exist: /bad",
                exitCode = null,
            ),
            ChatMessage(id = "u2", tabId = "tab_shell", role = "user", text = "oom-cmd"),
            ChatMessage(
                id = "a_detail_err",
                tabId = "tab_shell",
                role = "assistant",
                text = "",
                status = "error",
                error = "Process terminated by SIGKILL",
                exitCode = 137,
            ),
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = idleShellTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithText("Working directory does not exist: /bad").assertIsDisplayed()
        composeRule.onNodeWithTag("shell_exit_code_a_cwd_err").assertDoesNotExist()
        composeRule.onNodeWithTag("shell_exit_code_a_detail_err")
            .assertIsDisplayed()
            .assertTextEquals("Exit 137")
        composeRule.onNodeWithText("Process terminated by SIGKILL").assertIsDisplayed()
    }

    @Test
    fun runningShellComposerDefaultsToQueueAndSendsStdinDataAndEof() {
        val queuedMessages = mutableListOf<String>()
        val stdinCalls = mutableListOf<Pair<String?, Boolean>>()
        val runningTab = idleShellTab.copy(status = "working", statusDetail = "Running shell command…")

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = runningTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = { queuedMessages.add(it) },
                    onSendStdin = { data, eof -> stdinCalls.add(data to eof) },
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        // Queue mode is selected by default and EOF is hidden until Stdin is active
        composeRule.onNodeWithTag("shell_composer_mode_queue")
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithTag("shell_composer_mode_stdin")
            .assertIsDisplayed()
            .assertIsNotSelected()
        composeRule.onNodeWithTag("shell_stdin_eof_button").assertDoesNotExist()

        composeRule.onNodeWithTag("composer_input").performTextInput("echo queued")
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("echo queued"), queuedMessages)
        assertTrue(stdinCalls.isEmpty())

        // Switch to Stdin mode: placeholder updates, EOF button appears, Send sends "$rawText\n"
        composeRule.onNodeWithTag("shell_composer_mode_stdin").performClick()
        composeRule.onNodeWithTag("shell_composer_mode_stdin").assertIsSelected()
        composeRule.onNodeWithTag("shell_composer_mode_queue").assertIsNotSelected()
        composeRule.onNodeWithText("Send input to running command…").assertIsDisplayed()
        composeRule.onNodeWithTag("shell_stdin_eof_button").assertIsDisplayed()

        composeRule.onNodeWithTag("composer_input").performTextInput("hello")
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("hello\n" to false), stdinCalls)

        // Sending an empty line in Stdin mode sends bare newline
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("hello\n" to false, "\n" to false), stdinCalls)

        // Tapping EOF sends (null, true)
        composeRule.onNodeWithTag("shell_stdin_eof_button").performClick()
        assertEquals(
            listOf("hello\n" to false, "\n" to false, null to true),
            stdinCalls,
        )
    }

    @Test
    fun runningShellComposerResetsToQueueOnCompletionAndTabSwitch() {
        var activeTab by mutableStateOf(
            idleShellTab.copy(status = "working", statusDetail = "Running shell command…"),
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = activeTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onSendStdin = { _, _ -> },
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("shell_composer_mode_stdin").performClick()
        composeRule.onNodeWithTag("shell_composer_mode_stdin").assertIsSelected()

        // Completing the command hides controls and resets mode back to Queue on next command
        activeTab = activeTab.copy(status = "idle", statusDetail = null)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_composer_mode_queue").assertDoesNotExist()

        activeTab = activeTab.copy(status = "working", statusDetail = "Running next…")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_composer_mode_queue").assertIsSelected()
        composeRule.onNodeWithTag("shell_stdin_eof_button").assertDoesNotExist()

        // Switching to Stdin then changing tab resets mode back to Queue
        composeRule.onNodeWithTag("shell_composer_mode_stdin").performClick()
        composeRule.onNodeWithTag("shell_composer_mode_stdin").assertIsSelected()
        activeTab = activeTab.copy(id = "tab_shell_2")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_composer_mode_queue").assertIsSelected()
        composeRule.onNodeWithTag("shell_stdin_eof_button").assertDoesNotExist()
    }

    @Test
    fun nonShellWorkingTabDoesNotShowShellStdinControls() {
        val codingTab = TabRecord(
            id = "tab_claude",
            title = "Claude · repo",
            agent = "claude",
            cwd = "/home/ned/projects/agents-term",
            status = "working",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = codingTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onSendStdin = { _, _ -> },
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("shell_composer_mode_queue").assertDoesNotExist()
        composeRule.onNodeWithTag("shell_composer_mode_stdin").assertDoesNotExist()
        composeRule.onNodeWithTag("shell_stdin_eof_button").assertDoesNotExist()
    }
}
