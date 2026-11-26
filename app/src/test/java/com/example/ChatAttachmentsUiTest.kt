package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.AttachmentImageStore
import com.example.data.AttachmentLimits
import com.example.data.ChatMessage
import com.example.data.MessageAttachment
import com.example.data.QueuedMessage
import com.example.data.TabRecord
import com.example.ui.ChatWorkspace
import com.example.ui.ComposerAttachments
import com.example.ui.LocalAttachmentImages
import com.example.ui.PendingAttachment
import com.example.ui.PendingAttachmentState
import com.example.ui.theme.HerdrTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ChatAttachmentsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val idleTab = TabRecord(id = "tab_1", title = "Claude · app", agent = "claude", cwd = "/home/ned/app")
    private val limits = AttachmentLimits()

    private val sent = mutableListOf<String>()
    private val forced = mutableListOf<String?>()
    private val attached = mutableListOf<Uri>()
    private val removed = mutableListOf<String>()

    private fun thumbnail(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private fun pending(
        localId: String,
        state: PendingAttachmentState,
        error: String? = null,
    ) = PendingAttachment(
        localId = localId,
        state = state,
        thumbnail = thumbnail(),
        uploaded = MessageAttachment(id = "att_$localId").takeIf { state == PendingAttachmentState.Ready },
        error = error,
    )

    private fun composerAttachments(pending: List<PendingAttachment> = emptyList()) = ComposerAttachments(
        limits = limits,
        pending = pending,
        onAttach = { attached += it },
        onRemove = { removed += it },
    )

    private fun setChat(
        tab: TabRecord = idleTab,
        messages: List<ChatMessage> = emptyList(),
        images: AttachmentImageStore? = null,
        attachments: () -> ComposerAttachments?,
    ) {
        composeRule.setContent {
            HerdrTheme {
                CompositionLocalProvider(LocalAttachmentImages provides images) {
                    ChatWorkspace(
                        tab = tab,
                        messages = messages,
                        isLoading = false,
                        isConnected = true,
                        onSendMessage = { sent += it },
                        onInterrupt = {},
                        onForceInterrupt = { forced += it },
                        onRetryFullAccess = {},
                        attachments = attachments(),
                    )
                }
            }
        }
    }

    @Test
    fun attachButtonIsHiddenWhenTheTabCannotTakeImages() {
        setChat { null }

        composeRule.onAllNodesWithTag("attach_button").assertCountEquals(0)
        composeRule.onAllNodesWithTag("pending_attachments_row").assertCountEquals(0)
        // The composer still works as a plain text box.
        composeRule.onNodeWithTag("composer_input").performTextInput("hello")
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("hello"), sent)
    }

    @Test
    fun attachButtonOffersTheCameraAndThePhotoPicker() {
        setChat { composerAttachments() }

        composeRule.onNodeWithTag("attach_button").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("attach_camera").assertIsDisplayed()
        composeRule.onNodeWithText("Take photo").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_photos").assertIsDisplayed()
        composeRule.onNodeWithText("Choose photos").assertIsDisplayed()
    }

    @Test
    fun pendingImagesShowAsRemovableThumbnailsWithTheirUploadState() {
        setChat {
            composerAttachments(
                listOf(
                    pending("a", PendingAttachmentState.Ready),
                    pending("b", PendingAttachmentState.Uploading),
                    pending("c", PendingAttachmentState.Failed, error = "Images can be at most 8 MB"),
                ),
            )
        }

        composeRule.onNodeWithTag("pending_attachments_row").assertIsDisplayed()
        composeRule.onNodeWithTag("pending_attachment_a").assertContentDescriptionEquals("Attached image")
        composeRule.onNodeWithTag("pending_attachment_b").assertContentDescriptionEquals("Uploading image")
        composeRule.onNodeWithTag("pending_attachment_c").assertContentDescriptionEquals("Images can be at most 8 MB")
        composeRule.onAllNodesWithTag("pending_attachment_progress", useUnmergedTree = true).assertCountEquals(1)

        composeRule.onNodeWithTag("remove_attachment_c").performClick()
        assertEquals(listOf("c"), removed)
    }

    @Test
    fun aMessageCanBeSentWithImagesOnly() {
        setChat { composerAttachments(listOf(pending("a", PendingAttachmentState.Ready))) }

        composeRule.onNodeWithTag("send_button").performClick()

        assertEquals("Images alone are enough to send", listOf(""), sent)
    }

    @Test
    fun sendingWaitsForImagesThatAreStillUploadingAndKeepsTheTypedText() {
        var pending by mutableStateOf(listOf(pending("a", PendingAttachmentState.Uploading)))
        setChat { composerAttachments(pending) }
        composeRule.onNodeWithTag("composer_input").performTextInput("What is this error?")

        composeRule.onNodeWithTag("send_button").performClick()
        assertTrue("Nothing is sent while an image is uploading", sent.isEmpty())
        composeRule.onNodeWithTag("composer_input").assertTextEquals("What is this error?")

        // A failed upload leaves the text in place; the message can then go without the image.
        pending = listOf(pending("a", PendingAttachmentState.Failed, error = "Image upload failed: offline"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_input").assertTextEquals("What is this error?")
        composeRule.onNodeWithTag("send_button").performClick()
        assertEquals(listOf("What is this error?"), sent)
    }

    @Test
    fun holdingSendWhileTheAgentWorksSendsAnImageOnlyMessageNow() {
        setChat(tab = idleTab.copy(status = "working")) {
            composerAttachments(listOf(pending("a", PendingAttachmentState.Ready)))
        }

        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnLongClick)

        assertEquals("The images travel with the interrupt; there is no text", listOf<String?>(null), forced)
    }

    @Test
    fun pastingACopiedImageIntoTheComposerAttachesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageUri = Uri.parse("content://com.example.screenshots/shot_1.png")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData("screenshot", arrayOf("image/png"), ClipData.Item(imageUri)))
        setChat { composerAttachments() }
        composeRule.onNodeWithTag("composer_input").performTextInput("see: ")

        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        composeRule.waitForIdle()

        assertEquals(listOf(imageUri), attached)
        composeRule.onNodeWithTag("composer_input").assertTextEquals("see: ")
    }

    @Test
    fun pastingTextStillPastesText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "pasted words"))
        setChat { composerAttachments() }

        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        composeRule.waitForIdle()

        assertTrue(attached.isEmpty())
        composeRule.onNodeWithTag("composer_input").assertTextEquals("pasted words")
    }

    @Test
    fun sentImagesShowInTheTranscriptAndOpenFullSize() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.BLUE) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val downloads = mutableListOf<String>()
        val images = AttachmentImageStore(
            cacheDir = null,
            ioDispatcher = Dispatchers.Unconfined,
            download = { tabId, id ->
                downloads += "$tabId/$id"
                Result.success(png)
            },
        )
        val messages = listOf(
            ChatMessage(
                id = "m1",
                tabId = "tab_1",
                role = "user",
                text = "What is wrong in these?",
                attachments = listOf(MessageAttachment(id = "att_1"), MessageAttachment(id = "att_2")),
            ),
            ChatMessage(id = "m2", tabId = "tab_1", role = "assistant", text = "The button overlaps the title."),
            ChatMessage(
                id = "m3",
                tabId = "tab_1",
                role = "user",
                text = "",
                attachments = listOf(MessageAttachment(id = "att_3")),
            ),
        )
        setChat(messages = messages, images = images) { composerAttachments() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What is wrong in these?").assertIsDisplayed()
        composeRule.onNodeWithTag("message_attachment_att_1").assertContentDescriptionEquals("Sent image 1 of 2")
        composeRule.onNodeWithTag("message_attachment_att_2").assertContentDescriptionEquals("Sent image 2 of 2")
        composeRule.onNodeWithTag("message_attachment_att_3").assertContentDescriptionEquals("Sent image 1 of 1")
        assertEquals(setOf("tab_1/att_1", "tab_1/att_2", "tab_1/att_3"), downloads.toSet())

        composeRule.onAllNodesWithTag("attachment_viewer").assertCountEquals(0)
        composeRule.onNodeWithTag("message_attachment_att_2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("attachment_viewer_image").assertIsDisplayed()

        composeRule.onNodeWithTag("attachment_viewer_close").performClick()
        composeRule.onAllNodesWithTag("attachment_viewer").assertCountEquals(0)
    }

    @Test
    fun queuedMessagesShowHowManyImagesTheyCarry() {
        val queued = listOf(
            QueuedMessage(id = "q_1", text = "Check this layout", attachments = listOf(MessageAttachment(id = "att_1"))),
            QueuedMessage(
                id = "q_2",
                text = "",
                attachments = listOf(MessageAttachment(id = "att_2"), MessageAttachment(id = "att_3")),
            ),
            QueuedMessage(id = "q_3", text = "Plain follow-up"),
        )
        setChat(tab = idleTab.copy(status = "working", queuedMessages = queued)) { composerAttachments() }

        composeRule.onNodeWithText("Check this layout (1 image)").assertIsDisplayed()
        composeRule.onNodeWithText("2 images").assertIsDisplayed()
        composeRule.onNodeWithText("Plain follow-up").assertIsDisplayed()
        composeRule.onAllNodesWithText("Plain follow-up (0 images)").assertCountEquals(0)
    }
}
