package com.example.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** An image made ready for upload, with a small preview for the composer. */
class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val thumbnail: Bitmap,
)

/** A problem with an image the user tried to attach; [message] is shown to the user. */
class ImageAttachmentException(message: String) : IOException(message)

/**
 * Turns an image picked on the phone into bytes worth uploading: photos are scaled down and
 * re-encoded so they travel quickly over mobile data, while small images go through untouched.
 */
object ImageAttachmentPreparer {
    const val MAX_EDGE_PX = 2048
    const val THUMBNAIL_EDGE_PX = 256

    /** Images at most this big, and no larger than [MAX_EDGE_PX], are uploaded as they are. */
    const val PASSTHROUGH_MAX_BYTES = 1536 * 1024

    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private const val LOSSLESS_MAX_BYTES = 3 * 1024 * 1024
    private const val JPEG_START_QUALITY = 85
    private const val JPEG_MIN_QUALITY = 40
    private const val JPEG_QUALITY_STEP = 15
    private const val MIME_JPEG = "image/jpeg"
    private const val MIME_PNG = "image/png"
    private val passthroughMimeTypes = setOf(MIME_PNG, MIME_JPEG, "image/gif", "image/webp")

    /** The size an image of [width] x [height] is scaled to so its long edge fits [maxEdge]. */
    fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return width to height
        val scale = maxEdge.toFloat() / longEdge
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** The largest power-of-two subsampling that still leaves the long edge at least [maxEdge]. */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * Opens [uri] immediately and returns the work of reading and preparing it, to run off the
     * main thread. A pasted image may only be readable while the paste is being handled, but a
     * stream opened in time stays readable.
     */
    fun open(resolver: ContentResolver, uri: Uri, maxBytes: Long): () -> PreparedImage {
        val opened = runCatching { resolver.openInputStream(uri) }
        return {
            val input = opened.getOrElse {
                throw ImageAttachmentException("The app is not allowed to read that image")
            } ?: throw ImageAttachmentException("That image could not be opened")
            prepare(input.use { readCapped(it) }, maxBytes)
        }
    }

    fun prepare(source: ByteArray, maxBytes: Long): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageAttachmentException("That file is not an image the app can read")
        }
        val sourceMime = bounds.outMimeType.orEmpty().lowercase()
        val rotation = exifRotationDegrees(source)
        val fitsAsIs = max(bounds.outWidth, bounds.outHeight) <= MAX_EDGE_PX &&
            rotation == 0 &&
            sourceMime in passthroughMimeTypes &&
            source.size <= minOf(PASSTHROUGH_MAX_BYTES.toLong(), maxBytes)

        val decoded = decodeSampled(source, MAX_EDGE_PX)
            ?: throw ImageAttachmentException("That file is not an image the app can read")
        val upright = rotate(scaleToFit(decoded, MAX_EDGE_PX), rotation)
        val thumbnail = scaleToFit(upright, THUMBNAIL_EDGE_PX)
        if (fitsAsIs) {
            return PreparedImage(source, sourceMime, thumbnail)
        }
        val (bytes, mime) = encode(upright, preferLossless = sourceMime == MIME_PNG, maxBytes = maxBytes)
        return PreparedImage(bytes, mime, thumbnail)
    }

    /** Decodes [bytes] into a bitmap whose long edge is at most about [maxEdge]. */
    fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = targetSize(bitmap.width, bitmap.height, maxEdge)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return bitmap.scale(w, h)
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_SOURCE_BYTES) {
                throw ImageAttachmentException("That image is too large to attach")
            }
        }
        return out.toByteArray()
    }

    private fun exifRotationDegrees(source: ByteArray): Int = runCatching {
        val orientation = ExifInterface(ByteArrayInputStream(source))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun encode(bitmap: Bitmap, preferLossless: Boolean, maxBytes: Long): Pair<ByteArray, String> {
        if (preferLossless) {
            // Screenshots stay PNG so their text remains sharp, unless that is too heavy.
            val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100)
            if (png.size <= minOf(LOSSLESS_MAX_BYTES.toLong(), maxBytes)) return png to MIME_PNG
        }
        val opaque = flattenOnWhite(bitmap)
        var quality = JPEG_START_QUALITY
        while (true) {
            val jpeg = compress(opaque, Bitmap.CompressFormat.JPEG, quality)
            if (jpeg.size <= maxBytes) return jpeg to MIME_JPEG
            if (quality <= JPEG_MIN_QUALITY) {
                throw ImageAttachmentException("That image is too large to attach")
            }
            quality = max(JPEG_MIN_QUALITY, quality - JPEG_QUALITY_STEP)
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }

    /** JPEG has no transparency; without this, transparent areas would turn black. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = createBitmap(bitmap.width, bitmap.height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }
}

/**
 * Loads the images of sent messages for the transcript. Attachments never change once stored
 * on the VM, so each is downloaded once and then kept in the app's cache folder.
 */
class AttachmentImageStore(
    private val cacheDir: File?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val download: suspend (tabId: String, attachmentId: String) -> Result<ByteArray>,
) {
    private val thumbnails = LruCache<String, Bitmap>(THUMBNAIL_CACHE_ENTRIES)

    fun cachedThumbnail(attachmentId: String): Bitmap? = thumbnails.get(attachmentId)

    /** Remembers the preview of an image the user just uploaded, so sending it shows no reload. */
    fun rememberThumbnail(attachmentId: String, thumbnail: Bitmap) {
        thumbnails.put(attachmentId, thumbnail)
    }

    suspend fun thumbnail(tabId: String, attachmentId: String): Bitmap? {
        cachedThumbnail(attachmentId)?.let { return it }
        val decoded = load(tabId, attachmentId, ImageAttachmentPreparer.THUMBNAIL_EDGE_PX * 2) ?: return null
        thumbnails.put(attachmentId, decoded)
        return decoded
    }

    suspend fun fullImage(tabId: String, attachmentId: String): Bitmap? = load(tabId, attachmentId, ImageAttachmentPreparer.MAX_EDGE_PX)

    private suspend fun load(tabId: String, attachmentId: String, maxEdge: Int): Bitmap? {
        val bytes = bytesFor(tabId, attachmentId) ?: return null
        return withContext(ioDispatcher) { ImageAttachmentPreparer.decodeSampled(bytes, maxEdge) }
    }

    private suspend fun bytesFor(tabId: String, attachmentId: String): ByteArray? {
        val cached = cacheFile(attachmentId)
        if (cached != null) {
            val onDisk = withContext(ioDispatcher) {
                runCatching { cached.takeIf { it.isFile }?.readBytes() }.getOrNull()
            }
            if (onDisk != null && onDisk.isNotEmpty()) return onDisk
        }
        val downloaded = download(tabId, attachmentId).getOrNull() ?: return null
        if (cached != null) {
            withContext(ioDispatcher) {
                runCatching {
                    cached.parentFile?.mkdirs()
                    cached.writeBytes(downloaded)
                }
            }
        }
        return downloaded
    }

    private fun cacheFile(attachmentId: String): File? {
        val dir = cacheDir ?: return null
        if (!SAFE_ID.matches(attachmentId)) return null
        return File(File(dir, CACHE_FOLDER), attachmentId)
    }

    companion object {
        const val CACHE_FOLDER = "attachment_images"
        private const val THUMBNAIL_CACHE_ENTRIES = 64
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    }
}
