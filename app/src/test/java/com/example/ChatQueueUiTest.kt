package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.example.data.ChatMessage
import com.example.data.QueuedMessage
import com.example.data.TabRecord
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
class ChatQueueUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val workingTab = TabRecord(
        id = "tab_1",
        title = "Antigravity · app",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        status = "working",
        statusDetail = "Antigravity is thinking…",
    )

    @Test
    fun tappingSendWhileWorkingQueuesDraftAndEmptyTapShowsHintWithoutInterrupting() {
        val sent = mutableListOf<String>()
        val forced = mutableListOf<String?>()

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = workingTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = { sent.add(it) },
                    onInterrupt = { forced.add("<legacy>") },
                    onForceInterrupt = { forced.add(it) },
                    onRetryFullAccess = {},
                )
            }
        }

        // Single-tap stop button no longer exists; empty tap shows hold hint instead of interrupting
        assertTrue(composeRule.onAllNodesWithTag("stop_agent_button").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.onNodeWithTag("hold_to_interrupt_hint").assertIsDisplayed()
        assertTrue("Single tap while working must never interrupt", forced.isEmpty())
        assertTrue(sent.isEmpty())

        // Typing a message and tapping send queues it by default without interrupting
        composeRule.onNodeWithTag("composer_input").performTextInput("Follow-up prompt")
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("Follow-up prompt"), sent)
        assertTrue("Queueing a message must not interrupt", forced.isEmpty())
    }

    @Test
    fun queuedMessagesTrayDisplaysItemsAndAllowsRemoval() {
        val removed = mutableListOf<String>()
        val queued = listOf(
            QueuedMessage(id = "q_1", text = "First queued task"),
            QueuedMessage(id = "q_2", text = "Second queued task"),
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = workingTab.copy(queuedMessages = queued),
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onForceInterrupt = {},
                    onRemoveQueuedMessage = { removed.add(it) },
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("queued_messages_tray").assertIsDisplayed()
        composeRule.onNodeWithText("Queued (2)").assertIsDisplayed()
        composeRule.onNodeWithText("Hold send to interrupt & run").assertIsDisplayed()
        composeRule.onNodeWithText("First queued task").assertIsDisplayed()
        composeRule.onNodeWithText("Second queued task").assertIsDisplayed()

        composeRule.onNodeWithTag("remove_queued_q_1").performClick()
        assertEquals(listOf("q_1"), removed)
    }

    @Test
    fun pressingAndHoldingSendForcesInterruptAndIncludesDraft() {
        val sent = mutableListOf<String>()
        val forced = mutableListOf<String?>()

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = workingTab.copy(
                        queuedMessages = listOf(QueuedMessage(id = "q_1", text = "Queued first")),
                    ),
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = { sent.add(it) },
                    onInterrupt = {},
                    onForceInterrupt = { forced.add(it) },
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer_input").performTextInput("Urgent change")
        composeRule.onNodeWithTag("send_button").performTouchInput { longClick() }

        assertEquals(listOf<String?>("Urgent change"), forced)
        assertTrue("Long press must force interrupt instead of normal send", sent.isEmpty())
    }

    @Test
    fun pressingAndHoldingEmptySendStopsAgentAndDraggingOffCancelsHold() {
        val forced = mutableListOf<String?>()

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = workingTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onForceInterrupt = { forced.add(it) },
                    onRetryFullAccess = {},
                )
            }
        }

        // Dragging finger off the button during hold cancels the interrupt
        composeRule.onNodeWithTag("send_button").performTouchInput {
            down(center)
            advanceEventTime(150L)
            moveBy(Offset(-250f, 0f))
            advanceEventTime(600L)
            up()
        }
        assertTrue("Dragging off send button must cancel hold-to-interrupt", forced.isEmpty())

        // Holding without dragging off triggers interrupt with null draft
        composeRule.onNodeWithTag("send_button").performTouchInput { longClick() }
        assertEquals(listOf<String?>(null), forced)
    }

    @Test
    fun workingAnimationsAppearOnlyWhileActiveAndStreaming() {
        val streamingMsg = ChatMessage(
            id = "msg_stream",
            model = "model-a",
            tabId = "tab_1",
            role = "assistant",
            text = "",
            status = "streaming",
        )

        composeRule.setContent {
            HerdrTheme {
                ChatWorkspace(
                    tab = workingTab,
                    messages = listOf(streamingMsg),
                    throughput = mapOf("model-a" to "50 tokens/s"),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onForceInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("working_progress_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("composer_status_detail")
            .assertIsDisplayed()
            .assertTextEquals("Antigravity is thinking…")
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("streaming_working_badge").assertIsDisplayed()
        composeRule.onNodeWithText("50 tokens/s").assertIsDisplayed()
        composeRule.onNodeWithTag("streaming_activity_indicator").assertIsDisplayed()
    }
}
