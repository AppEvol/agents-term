package com.example

import android.graphics.Bitmap
import android.net.Uri
import com.example.data.AttachmentLimits
import com.example.data.ImageAttachmentException
import com.example.data.MessageAttachment
import com.example.data.PreparedImage
import com.example.ui.AttachmentController
import com.example.ui.PendingAttachmentState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentControllerTest {

    private val limits = AttachmentLimits(maxBytes = 1024, maxPerMessage = 3)
    private val errors = mutableListOf<String>()
    private val uploads = mutableListOf<String>()
    private val remembered = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    /** Uploads complete when the test says so, so the "uploading" state can be observed. */
    private val uploadGates = mutableMapOf<String, CompletableDeferred<Result<MessageAttachment>>>()

    private fun uri(name: String): Uri = Uri.parse("content://images/$name")

    private fun thumbnail(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun TestScope.controller() = AttachmentController(
        scope = backgroundScope,
        open = { uri, _ ->
            val name = uri.lastPathSegment.orEmpty()
            opened += name
            val source: () -> PreparedImage = {
                if (name.startsWith("broken")) throw ImageAttachmentException("That file is not an image the app can read")
                PreparedImage(name.toByteArray(), "image/png", thumbnail())
            }
            source
        },
        upload = { tabId, bytes, _ ->
            val name = String(bytes)
            uploads += "$tabId:$name"
            uploadGates.getOrPut(name) { CompletableDeferred() }.await()
        },
        onError = { errors += it },
        onUploaded = { attachment, _ -> remembered += attachment.id },
        workDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun finishUpload(name: String, result: Result<MessageAttachment> = Result.success(MessageAttachment(id = "att_$name"))) {
        uploadGates.getOrPut(name) { CompletableDeferred() }.complete(result)
    }

    @Test
    fun attachedImageUploadsImmediatelyAndBecomesReady() = runTest {
        val controller = controller()

        controller.attach("tab_1", listOf(uri("a")), limits)
        assertEquals("The image is opened before attach returns", listOf("a"), opened)
        var pending = controller.pendingByTab.value.getValue("tab_1").single()
        assertEquals(PendingAttachmentState.Uploading, pending.state)

        runCurrent()
        pending = controller.pendingByTab.value.getValue("tab_1").single()
        assertEquals("Still uploading until the VM answers", PendingAttachmentState.Uploading, pending.state)
        assertNotNull("The preview shows while the upload runs", pending.thumbnail)
        assertEquals(listOf("tab_1:a"), uploads)

        finishUpload("a")
        runCurrent()
        pending = controller.pendingByTab.value.getValue("tab_1").single()
        assertEquals(PendingAttachmentState.Ready, pending.state)
        assertEquals("att_a", pending.uploaded?.id)
        assertEquals(listOf("att_a"), remembered)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun failedUploadIsMarkedAndReportedWithoutAffectingOtherImages() = runTest {
        val controller = controller()
        controller.attach("tab_1", listOf(uri("a"), uri("b"), uri("broken")), limits)
        finishUpload("a")
        finishUpload("b", Result.failure(IOException("Images can be at most 8 MB")))
        runCurrent()

        val states = controller.pendingByTab.value.getValue("tab_1").map { it.state }
        assertEquals(
            listOf(PendingAttachmentState.Ready, PendingAttachmentState.Failed, PendingAttachmentState.Failed),
            states,
        )
        assertEquals(
            listOf(
                "That file is not an image the app can read",
                "Image upload failed: Images can be at most 8 MB",
            ),
            errors.sorted().reversed(),
        )
        assertEquals("An unreadable file is never uploaded", listOf("tab_1:a", "tab_1:b"), uploads)
    }

    @Test
    fun imagesBeyondThePerMessageLimitAreRefused() = runTest {
        val controller = controller()
        controller.attach("tab_1", listOf(uri("a"), uri("b")), limits)
        controller.attach("tab_1", listOf(uri("c"), uri("d"), uri("e")), limits)

        assertEquals(3, controller.pendingByTab.value.getValue("tab_1").size)
        assertEquals(listOf("A message can have at most 3 images"), errors)
        assertEquals(listOf("a", "b", "c"), opened)

        // Another tab has its own allowance.
        controller.attach("tab_2", listOf(uri("x")), limits)
        assertEquals(1, controller.pendingByTab.value.getValue("tab_2").size)
    }

    @Test
    fun removingAnImageCancelsItsUpload() = runTest {
        val controller = controller()
        controller.attach("tab_1", listOf(uri("a"), uri("b")), limits)
        runCurrent()
        val first = controller.pendingByTab.value.getValue("tab_1").first()

        controller.remove("tab_1", first.localId)
        finishUpload("a")
        finishUpload("b")
        runCurrent()

        val left = controller.pendingByTab.value.getValue("tab_1").single()
        assertEquals("att_b", left.uploaded?.id)
        assertEquals("A cancelled upload must not be remembered as sent", listOf("att_b"), remembered)

        controller.remove("tab_1", left.localId)
        assertNull(controller.pendingByTab.value["tab_1"])
    }

    @Test
    fun sendingTakesUploadedImagesDropsFailedOnesAndKeepsUnfinishedOnes() = runTest {
        val controller = controller()
        controller.attach("tab_1", listOf(uri("a"), uri("b"), uri("c")), limits)
        finishUpload("a")
        finishUpload("b", Result.failure(IOException("offline")))
        runCurrent()

        val taken = controller.takeReady("tab_1")

        assertEquals(listOf("att_a"), taken.map { it.id })
        val left = controller.pendingByTab.value.getValue("tab_1").single()
        assertEquals(PendingAttachmentState.Uploading, left.state)

        finishUpload("c")
        runCurrent()
        assertEquals(listOf("att_c"), controller.takeReady("tab_1").map { it.id })
        assertNull(controller.pendingByTab.value["tab_1"])
        assertTrue(controller.takeReady("tab_1").isEmpty())
    }
}
