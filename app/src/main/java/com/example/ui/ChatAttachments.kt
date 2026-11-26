package com.example.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.AttachmentImageStore
import com.example.data.AttachmentLimits
import com.example.data.MessageAttachment
import com.example.ui.theme.AppTheme
import com.example.ui.theme.themedCorner
import java.io.File

/** Loads the images of sent messages; absent in previews and tests that do not show images. */
val LocalAttachmentImages = staticCompositionLocalOf<AttachmentImageStore?> { null }

/** What the composer needs to attach images. Absent when the tab cannot take images. */
class ComposerAttachments(
    val limits: AttachmentLimits,
    val pending: List<PendingAttachment>,
    val onAttach: (List<Uri>) -> Unit,
    val onRemove: (String) -> Unit,
) {
    val isUploading: Boolean get() = pending.any { it.state == PendingAttachmentState.Uploading }
    val hasReady: Boolean get() = pending.any { it.state == PendingAttachmentState.Ready }
}

private const val CAMERA_CAPTURE_FOLDER = "camera_captures"
private const val CAMERA_CAPTURE_MAX_AGE_MS = 24L * 60 * 60 * 1000
private const val FILE_PROVIDER_SUFFIX = ".debugfileprovider"
private const val MAX_ZOOM = 5f

/** A new file for the camera app to write a photo into, shared through the app's file provider. */
internal fun createCameraCaptureUri(context: Context, nowMs: Long = System.currentTimeMillis()): Uri {
    val dir = File(context.cacheDir, CAMERA_CAPTURE_FOLDER).apply { mkdirs() }
    // Photos are uploaded right after they are taken, so older ones are leftovers.
    dir.listFiles()?.forEach { old ->
        if (nowMs - old.lastModified() > CAMERA_CAPTURE_MAX_AGE_MS) old.delete()
    }
    val file = File(dir, "capture_$nowMs.jpg")
    return FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
}

/**
 * Makes a text field accept images that are pasted from the clipboard or inserted by the
 * keyboard (GIF and sticker pickers, screenshot suggestions). Text still pastes as usual.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.receiveImages(onImages: (List<Uri>) -> Unit): Modifier = contentReceiver { content ->
    if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
    val uris = mutableListOf<Uri>()
    val remaining = content.consume { item ->
        val uri = item.uri
        if (uri != null) uris += uri
        uri != null
    }
    if (uris.isNotEmpty()) onImages(uris)
    remaining
}

/** The composer's attach button, offering the camera and the system photo picker. */
@Composable
internal fun AttachImageButton(
    attachments: ComposerAttachments,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onAttach by rememberUpdatedState(attachments.onAttach)
    var menuOpen by remember { mutableStateOf(false) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        // The picker needs no storage permission and includes the Screenshots album.
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = attachments.limits.maxPerMessage.coerceAtLeast(2)),
    ) { uris -> if (uris.isNotEmpty()) onAttach(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri?.let(Uri::parse)
        captureUri = null
        if (saved && uri != null) onAttach(listOf(uri))
    }

    fun launchOrExplain(missingMessage: Int, launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { menuOpen = true }
                .testTag("attach_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.AddPhotoAlternate,
                contentDescription = stringResource(R.string.action_attach_image),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_take_photo)) },
                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    launchOrExplain(R.string.attachment_no_camera) {
                        val uri = createCameraCaptureUri(context)
                        captureUri = uri.toString()
                        camera.launch(uri)
                    }
                },
                modifier = Modifier.testTag("attach_camera"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_choose_photos)) },
                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    launchOrExplain(R.string.attachment_no_picker) {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
                modifier = Modifier.testTag("attach_photos"),
            )
        }
    }
}

/** Thumbnails of the images attached to the message being written, each removable. */
@Composable
internal fun PendingAttachmentsRow(
    pending: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("pending_attachments_row"),
    ) {
        pending.forEach { item ->
            PendingAttachmentThumbnail(item = item, onRemove = { onRemove(item.localId) })
        }
    }
}

@Composable
private fun PendingAttachmentThumbnail(item: PendingAttachment, onRemove: () -> Unit) {
    val failed = item.state == PendingAttachmentState.Failed
    val description = item.error ?: stringResource(
        when (item.state) {
            PendingAttachmentState.Uploading -> R.string.attachment_uploading
            PendingAttachmentState.Ready -> R.string.attachment_ready
            PendingAttachmentState.Failed -> R.string.attachment_failed
        },
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .testTag("pending_attachment_${item.localId}")
            .semantics { contentDescription = description },
    ) {
        Surface(
            shape = themedCorner(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(
                1.dp,
                if (failed) AppTheme.colors.danger else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .padding(top = 6.dp, end = 6.dp)
                .fillMaxSize(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                item.thumbnail?.let { ThumbnailImage(it) }
                when (item.state) {
                    PendingAttachmentState.Uploading -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                        )
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .testTag("pending_attachment_progress"),
                        )
                    }

                    PendingAttachmentState.Failed -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = AppTheme.colors.danger,
                        modifier = Modifier.size(24.dp),
                    )

                    PendingAttachmentState.Ready -> Unit
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .clickable(onClick = onRemove)
                .testTag("remove_attachment_${item.localId}"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_remove_attachment),
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ThumbnailImage(bitmap: Bitmap) {
    Image(
        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** The images of a sent message. Every thumbnail has a fixed size, so loading never moves the chat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageAttachmentsRow(
    tabId: String,
    attachments: List<MessageAttachment>,
    modifier: Modifier = Modifier,
) {
    var viewing by remember { mutableStateOf<MessageAttachment?>(null) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.testTag("message_attachments"),
    ) {
        attachments.forEachIndexed { index, attachment ->
            MessageAttachmentThumbnail(
                tabId = tabId,
                attachment = attachment,
                description = stringResource(R.string.attachment_sent_image, index + 1, attachments.size),
                onOpen = { viewing = attachment },
            )
        }
    }
    viewing?.let { attachment ->
        AttachmentViewerDialog(tabId = tabId, attachment = attachment, onDismiss = { viewing = null })
    }
}

@Composable
private fun MessageAttachmentThumbnail(
    tabId: String,
    attachment: MessageAttachment,
    description: String,
    onOpen: () -> Unit,
) {
    val images = LocalAttachmentImages.current
    val thumbnail by produceState(images?.cachedThumbnail(attachment.id), images, tabId, attachment.id) {
        if (value == null && images != null) value = images.thumbnail(tabId, attachment.id)
    }
    Surface(
        shape = themedCorner(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(96.dp)
            .clip(themedCorner(12.dp))
            .clickable(onClick = onOpen)
            .testTag("message_attachment_${attachment.id}")
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = thumbnail
            if (loaded != null) {
                ThumbnailImage(loaded)
            } else {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private sealed interface ViewerImage {
    data object Loading : ViewerImage
    data object Unavailable : ViewerImage
    class Loaded(val bitmap: Bitmap) : ViewerImage
}

/** Shows one sent image full screen; pinch to zoom, drag to pan, tap the close button to leave. */
@Composable
private fun AttachmentViewerDialog(
    tabId: String,
    attachment: MessageAttachment,
    onDismiss: () -> Unit,
) {
    val images = LocalAttachmentImages.current
    val image by produceState<ViewerImage>(ViewerImage.Loading, images, tabId, attachment.id) {
        val bitmap = images?.fullImage(tabId, attachment.id)
        value = if (bitmap != null) ViewerImage.Loaded(bitmap) else ViewerImage.Unavailable
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("attachment_viewer"),
        ) {
            when (val current = image) {
                ViewerImage.Loading -> CircularProgressIndicator(color = Color.White)

                ViewerImage.Unavailable -> Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = stringResource(R.string.attachment_image_unavailable),
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )

                is ViewerImage.Loaded -> ZoomableImage(current.bitmap)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onDismiss)
                    .testTag("attachment_viewer_close"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close_image),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
        contentDescription = stringResource(R.string.attachment_ready),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    // Panning only makes sense once zoomed in; at rest the image stays centred.
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .testTag("attachment_viewer_image"),
    )
}
