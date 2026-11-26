package com.example.ui

import android.graphics.Bitmap
import android.net.Uri
import com.example.data.AttachmentLimits
import com.example.data.ImageAttachmentException
import com.example.data.MessageAttachment
import com.example.data.PreparedImage
import com.example.data.formatAttachmentCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class PendingAttachmentState { Uploading, Ready, Failed }

/** An image the user attached to the message they are composing, not sent yet. */
data class PendingAttachment(
    val localId: String,
    val state: PendingAttachmentState,
    val thumbnail: Bitmap? = null,
    val uploaded: MessageAttachment? = null,
    val error: String? = null,
)

/**
 * Holds the images attached to each tab's unsent message. An image is prepared and uploaded to
 * the VM as soon as it is attached, so sending the message only has to refer to it by id.
 */
class AttachmentController(
    private val scope: CoroutineScope,
    /**
     * Opens an image right away, on the calling thread, and returns the slower work of reading
     * and preparing it. Opening early matters because the permission to read a pasted or
     * keyboard-inserted image can end as soon as the paste has been handled.
     */
    private val open: (Uri, AttachmentLimits) -> (() -> PreparedImage),
    private val upload: suspend (tabId: String, bytes: ByteArray, mimeType: String) -> Result<MessageAttachment>,
    private val onError: (String) -> Unit,
    private val onUploaded: (MessageAttachment, Bitmap) -> Unit = { _, _ -> },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _pendingByTab = MutableStateFlow<Map<String, List<PendingAttachment>>>(emptyMap())
    val pendingByTab: StateFlow<Map<String, List<PendingAttachment>>> = _pendingByTab.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val nextLocalId = AtomicLong(0L)

    fun attach(tabId: String, uris: List<Uri>, limits: AttachmentLimits) {
        if (uris.isEmpty()) return
        val room = limits.maxPerMessage - _pendingByTab.value[tabId].orEmpty().size
        if (uris.size > room) {
            onError("A message can have at most ${formatAttachmentCount(limits.maxPerMessage)}")
        }
        uris.take(room.coerceAtLeast(0)).forEach { uri -> startUpload(tabId, uri, limits) }
    }

    private fun startUpload(tabId: String, uri: Uri, limits: AttachmentLimits) {
        val localId = "local_${nextLocalId.incrementAndGet()}"
        _pendingByTab.update { all ->
            all + (tabId to all[tabId].orEmpty() + PendingAttachment(localId, PendingAttachmentState.Uploading))
        }
        val source = open(uri, limits)
        jobs[localId] = scope.launch {
            try {
                val prepared = withContext(workDispatcher) { source() }
                replace(tabId, localId) { it.copy(thumbnail = prepared.thumbnail) }
                val uploaded = upload(tabId, prepared.bytes, prepared.mimeType).getOrThrow()
                onUploaded(uploaded, prepared.thumbnail)
                replace(tabId, localId) { it.copy(state = PendingAttachmentState.Ready, uploaded = uploaded) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                val reason = if (err is ImageAttachmentException) {
                    err.message ?: "That image could not be attached"
                } else {
                    "Image upload failed: ${err.message ?: err::class.java.simpleName}"
                }
                replace(tabId, localId) { it.copy(state = PendingAttachmentState.Failed, error = reason) }
                onError(reason)
            } finally {
                jobs.remove(localId)
            }
        }
    }

    private fun replace(tabId: String, localId: String, change: (PendingAttachment) -> PendingAttachment) {
        _pendingByTab.update { all ->
            val list = all[tabId] ?: return@update all
            all + (tabId to list.map { if (it.localId == localId) change(it) else it })
        }
    }

    fun remove(tabId: String, localId: String) {
        jobs.remove(localId)?.cancel()
        _pendingByTab.update { all ->
            val remaining = all[tabId].orEmpty().filterNot { it.localId == localId }
            if (remaining.isEmpty()) all - tabId else all + (tabId to remaining)
        }
    }

    /**
     * Hands over the uploaded images of [tabId] for sending and clears them from the composer.
     * Images that failed are dropped; ones still uploading stay for the next message.
     */
    fun takeReady(tabId: String): List<MessageAttachment> {
        var taken = emptyList<MessageAttachment>()
        _pendingByTab.update { all ->
            val list = all[tabId] ?: return@update all
            taken = list.mapNotNull { it.uploaded.takeIf { _ -> it.state == PendingAttachmentState.Ready } }
            val remaining = list.filter { it.state == PendingAttachmentState.Uploading }
            if (remaining.isEmpty()) all - tabId else all + (tabId to remaining)
        }
        return taken
    }
}
