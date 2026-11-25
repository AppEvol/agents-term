package com.example

import android.widget.Magnifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.example.data.ChatMessage
import com.example.data.TabRecord
import com.example.ui.ChatWorkspace
import com.example.ui.markdown.LocalMarkdownParseDispatcher
import com.example.ui.markdown.LocalMarkdownSyncParseThreshold
import com.example.ui.theme.HerdrTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp", shadows = [ChatMarkdownVirtualizationUiTest.NoMagnifier::class])
class ChatMarkdownVirtualizationUiTest {

    @Implements(Magnifier::class)
    class NoMagnifier {
        // Signature must match android.widget.Magnifier.show for Robolectric shadow binding.
        @Suppress("UnusedParameter")
        @Implementation
        fun show(sourceX: Float, sourceY: Float, magnifierX: Float, magnifierY: Float) = Unit

        @Implementation
        fun dismiss() = Unit

        @Implementation
        fun update() = Unit
    }

    private class FakeClipboard(private val real: Clipboard) : Clipboard {
        var copied: String? = null
        override val nativeClipboard get() = real.nativeClipboard

        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            copied = clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
    }

    private class FakeToolbar : TextToolbar {
        var onCopy: (() -> Unit)? = null
        var onSelectAll: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            onCopy = onCopyRequested
            onSelectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val testTab = TabRecord(
        id = "tab_virt",
        title = "Antigravity · agents-term",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    @Test
    fun singleLongAssistantReplyVirtualizesOffscreenMarkdownBlocks() {
        val longReply = (1..40).joinToString("\n\n") { idx ->
            val tag = "BLOCK_${idx.toString().padStart(2, '0')}"
            "$tag: Paragraph spanning multiple lines on mobile."
        }
        val messages = listOf(
            ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "Explain every step"),
            ChatMessage(id = "a1", tabId = testTab.id, role = "assistant", text = longReply, status = "complete"),
        )

        composeRule.setContent {
            HerdrTheme {
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

        // Viewport is pinned to the bottom: last block is visible, first block is virtualized offscreen.
        composeRule.onNodeWithText("BLOCK_40", substring = true).assertIsDisplayed()
        assertTrue(
            "Expected top block of a 40-block single message to stay uncomposed while scrolled to bottom",
            composeRule.onAllNodesWithText("BLOCK_01", substring = true).fetchSemanticsNodes().isEmpty(),
        )

        // Scroll all the way up to the top of the same single message.
        repeat(16) {
            composeRule.onNodeWithTag("chat_message_list").performTouchInput {
                swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 120)
            }
            composeRule.waitForIdle()
        }

        // Top block is now displayed and far-bottom block has been recycled offscreen.
        composeRule.onNodeWithText("BLOCK_01", substring = true).assertIsDisplayed()
        assertTrue(
            "Expected bottom block to be recycled offscreen after scrolling to the top",
            composeRule.onAllNodesWithText("BLOCK_40", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun largeMessageParsesOffUiThreadAndAutoScrollsToBottomWhenCommitted() {
        val bgDispatcher = StandardTestDispatcher()
        val largeReply = (1..30).joinToString("\n\n") { idx ->
            "ASYNC_PARA_$idx: " + "lorem ipsum dolor sit amet ".repeat(8)
        } + "\n\nASYNC_FINAL_SENTINEL"
        val messages = listOf(
            ChatMessage(id = "u1", tabId = testTab.id, role = "user", text = "Generate large doc"),
            ChatMessage(id = "a1", tabId = testTab.id, role = "assistant", text = largeReply, status = "complete"),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalMarkdownParseDispatcher provides bgDispatcher,
                LocalMarkdownSyncParseThreshold provides 256,
            ) {
                HerdrTheme {
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
        }
        composeRule.waitForIdle()

        // Before background dispatcher runs, the >256-char reply has not blocked the UI thread.
        assertTrue(
            composeRule.onAllNodesWithText("ASYNC_FINAL_SENTINEL", substring = true).fetchSemanticsNodes().isEmpty(),
        )

        // Run the background parse dispatcher and let Compose commit + auto-scroll.
        bgDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ASYNC_FINAL_SENTINEL", substring = true).assertIsDisplayed()
    }

    @Test
    fun virtualizedMarkdownBlockPreservesSelectionAndCleanCopy() {
        lateinit var clipboard: FakeClipboard
        val toolbar = FakeToolbar()
        val messages = listOf(
            ChatMessage(
                id = "a1",
                tabId = testTab.id,
                role = "assistant",
                text = "Run `gradle :app:testDebugUnitTest` now",
                status = "complete",
            ),
        )

        composeRule.setContent {
            val real = LocalClipboard.current
            clipboard = remember { FakeClipboard(real) }
            CompositionLocalProvider(LocalClipboard provides clipboard, LocalTextToolbar provides toolbar) {
                HerdrTheme {
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
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Run", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertNotNull("long press selects text inside virtualized block", toolbar.onSelectAll)
        composeRule.runOnUiThread { toolbar.onSelectAll?.invoke() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { checkNotNull(toolbar.onCopy).invoke() }
        composeRule.waitForIdle()
        assertEquals("Run gradle :app:testDebugUnitTest now", clipboard.copied)
    }
}
