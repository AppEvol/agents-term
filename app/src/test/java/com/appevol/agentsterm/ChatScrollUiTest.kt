package com.appevol.agentsterm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.ui.ChatWorkspace
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ChatScrollUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testTab = TabRecord(
        id = "tab_scroll",
        title = "Antigravity · agents-term",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    private fun longParagraphs(prefix: String, count: Int): String = (1..count).joinToString("\n\n") { idx ->
        "$prefix paragraph $idx: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt."
    }

    private fun scrollUp(times: Int, startFraction: Float = 0.25f, endFraction: Float = 0.85f) {
        repeat(times) {
            composeRule.onNodeWithTag("chat_message_list").performTouchInput {
                swipeDown(startY = height * startFraction, endY = height * endFraction, durationMillis = 120)
            }
            composeRule.waitForIdle()
        }
    }

    private fun streamingTurn(user: ChatMessage, text: String): List<ChatMessage> = listOf(
        user,
        ChatMessage(id = "a1", tabId = testTab.id, role = "assistant", text = text, status = "streaming"),
    )

    private fun multiTurnConversation(): List<ChatMessage> = listOf(
        ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "FIRST_USER_TURN_MARKER"),
        ChatMessage(
            id = "a1",
            tabId = testTab.id,
            role = "assistant",
            text = longParagraphs("Turn 1", 12),
            status = "complete",
        ),
        ChatMessage(id = "u2", tabId = testTab.id, role = "user", text = "Second question"),
        ChatMessage(
            id = "a2",
            tabId = testTab.id,
            role = "assistant",
            text = longParagraphs("Turn 2", 12),
            status = "complete",
        ),
        ChatMessage(id = "u3", tabId = testTab.id, role = "user", text = "Third question"),
        ChatMessage(
            id = "a3",
            tabId = testTab.id,
            role = "assistant",
            text = "${longParagraphs("Turn 3", 12)}\n\nFINAL_BOTTOM_MARKER",
            status = "complete",
        ),
    )

    @Test
    fun scrollToBottomFabAppearsWhenScrollingUpAfterStartingEmpty() {
        var messages by mutableStateOf<List<ChatMessage>>(emptyList())

        composeRule.setContent {
            AgentsTermTheme {
                ChatWorkspace(
                    tab = testTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        // Start from an empty tab (repro for stale remember capture of initial empty messages).
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        messages = multiTurnConversation()
        composeRule.waitForIdle()

        // Should land at the bottom of the last message with FAB hidden.
        composeRule.onNodeWithText("FINAL_BOTTOM_MARKER", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        // Scroll up multiple screens; FAB must appear.
        scrollUp(times = 3)

        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Tapping FAB returns to the bottom of the last message and hides the FAB.
        composeRule.onNodeWithTag("scroll_to_bottom_fab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("FINAL_BOTTOM_MARKER", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()
    }

    @Test
    fun tallStreamingMessageStaysPinnedToBottomAndStopsYankingWhenUserScrollsUp() {
        var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        val baseUser = ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "FIRST_PROMPT_AT_TOP")

        composeRule.setContent {
            AgentsTermTheme {
                ChatWorkspace(
                    tab = testTab.copy(status = "working"),
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        val chunk1 = "TOP_OF_STREAM_MARKER\n\n${longParagraphs("Stream A", 18)}\n\nLIVE_TAIL_CHUNK_1"
        messages = streamingTurn(baseUser, chunk1)
        composeRule.waitForIdle()
        // Tall streaming reply must stay pinned to its bottom, not yanked to its top.
        composeRule.onNodeWithText("LIVE_TAIL_CHUNK_1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        val chunk2 = "$chunk1\n\n${longParagraphs("Stream B", 6)}\n\nLIVE_TAIL_CHUNK_2"
        messages = streamingTurn(baseUser, chunk2)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LIVE_TAIL_CHUNK_2", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        // User scrolls up to read the top of the conversation while streaming continues.
        scrollUp(times = 8, startFraction = 0.2f, endFraction = 0.9f)
        composeRule.onNodeWithText("FIRST_PROMPT_AT_TOP").assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Further streaming deltas (>40 chars) must NOT yank the viewport back down.
        val chunk3 = "$chunk2\n\n${longParagraphs("Stream C", 4)}\n\nLIVE_TAIL_CHUNK_3"
        messages = streamingTurn(baseUser, chunk3)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FIRST_PROMPT_AT_TOP").assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Tapping FAB scrolls to the bottom of the streaming reply and resumes auto-scroll.
        composeRule.onNodeWithTag("scroll_to_bottom_fab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LIVE_TAIL_CHUNK_3", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        val chunk4 = "$chunk3\n\n${longParagraphs("Stream D", 3)}\n\nLIVE_TAIL_CHUNK_4"
        messages = streamingTurn(baseUser, chunk4)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LIVE_TAIL_CHUNK_4", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()
    }

    @Test
    fun scrollingUpWithinSingleTallLastMessageShowsFabAndFabJumpsToBottom() {
        val messages = listOf(
            ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "Explain everything"),
            ChatMessage(
                id = "a1",
                tabId = testTab.id,
                role = "assistant",
                text = "START_OF_TALL_MESSAGE\n\n${longParagraphs("Detail", 22)}\n\nEND_OF_TALL_MESSAGE",
                status = "complete",
            ),
        )

        composeRule.setContent {
            AgentsTermTheme {
                ChatWorkspace(
                    tab = testTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("END_OF_TALL_MESSAGE", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        // Scroll up modestly while still inside the same last message item.
        composeRule.onNodeWithTag("chat_message_list").performTouchInput {
            swipeDown(startY = height * 0.35f, endY = height * 0.75f, durationMillis = 150)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Tapping FAB returns to the bottom of that tall message (not its top).
        composeRule.onNodeWithTag("scroll_to_bottom_fab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("END_OF_TALL_MESSAGE", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()
    }

    @Test
    fun manuallyScrollingBackToBottomHidesFabAndResumesAutoScroll() {
        val baseUser = ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "Keep going")
        var streamText = longParagraphs("Initial", 20) + "\n\nSTREAM_BEFORE_SCROLL"
        var messages by mutableStateOf(
            listOf(
                baseUser,
                ChatMessage(id = "a1", tabId = testTab.id, role = "assistant", text = streamText, status = "streaming"),
            ),
        )

        composeRule.setContent {
            AgentsTermTheme {
                ChatWorkspace(
                    tab = testTab.copy(status = "working"),
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        // Scroll up to pause auto-scroll.
        repeat(2) {
            composeRule.onNodeWithTag("chat_message_list").performTouchInput {
                swipeDown(startY = height * 0.25f, endY = height * 0.85f, durationMillis = 150)
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Manually scroll all the way back down to the bottom.
        repeat(6) {
            composeRule.onNodeWithTag("chat_message_list").performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 120)
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()

        // New streaming content should auto-scroll again.
        streamText = "$streamText\n\n${longParagraphs("More", 4)}\n\nRESUMED_STREAM_END"
        messages = listOf(
            baseUser,
            ChatMessage(id = "a1", tabId = testTab.id, role = "assistant", text = streamText, status = "streaming"),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("RESUMED_STREAM_END", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()
    }

    @Test
    fun sendingMessageWhileScrolledUpRepinsToBottom() {
        var messages by mutableStateOf(multiTurnConversation())

        composeRule.setContent {
            AgentsTermTheme {
                ChatWorkspace(
                    tab = testTab,
                    messages = messages,
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = { text ->
                        messages = messages + ChatMessage(
                            id = "u_new",
                            tabId = testTab.id,
                            role = "user",
                            text = text,
                        )
                    },
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.waitForIdle()

        // Scroll up away from the bottom.
        repeat(4) {
            composeRule.onNodeWithTag("chat_message_list").performTouchInput {
                swipeDown(startY = height * 0.2f, endY = height * 0.85f, durationMillis = 150)
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertIsDisplayed()

        // Send a new message; viewport must scroll to show it and hide the FAB.
        composeRule.onNodeWithTag("composer_input").performTextInput("NEW_SENT_PROMPT_MARKER")
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("NEW_SENT_PROMPT_MARKER").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("NEW_SENT_PROMPT_MARKER").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("scroll_to_bottom_fab").assertDoesNotExist()
    }
}
