package com.appevol.agentsterm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.appevol.agentsterm.data.AttachmentImageStore
import com.appevol.agentsterm.data.ImageAttachmentException
import com.appevol.agentsterm.data.ImageAttachmentPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageAttachmentPreparerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val maxBytes = 8L * 1024 * 1024

    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat, noisy: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (noisy) {
            // Noise does not compress, which stands in for the detail of a real photo.
            val random = Random(7)
            val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            bitmap.eraseColor(Color.rgb(20, 90, 200))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        return out.toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun targetSizeCapsTheLongEdgeAndKeepsTheShape() {
        assertEquals(800 to 600, ImageAttachmentPreparer.targetSize(800, 600, 2048))
        assertEquals(2048 to 1536, ImageAttachmentPreparer.targetSize(4096, 3072, 2048))
        assertEquals(1024 to 2048, ImageAttachmentPreparer.targetSize(3000, 6000, 2048))
        assertEquals(2048 to 1, ImageAttachmentPreparer.targetSize(20000, 2, 2048))
    }

    @Test
    fun sampleSizeNeverDecodesBelowTheTargetEdge() {
        assertEquals(1, ImageAttachmentPreparer.sampleSizeFor(2048, 1000, 2048))
        assertEquals(1, ImageAttachmentPreparer.sampleSizeFor(4000, 3000, 2048))
        assertEquals(2, ImageAttachmentPreparer.sampleSizeFor(4096, 3072, 2048))
        assertEquals(4, ImageAttachmentPreparer.sampleSizeFor(12000, 9000, 2048))
    }

    @Test
    fun smallScreenshotIsUploadedUntouched() {
        val source = encoded(1080, 1920, Bitmap.CompressFormat.PNG)

        val prepared = ImageAttachmentPreparer.prepare(source, maxBytes)

        assertArrayEquals("A small image must not be re-encoded", source, prepared.bytes)
        assertEquals("image/png", prepared.mimeType)
        assertEquals(256, maxOf(prepared.thumbnail.width, prepared.thumbnail.height))
    }

    @Test
    fun largePhotoIsScaledDownAndStaysAJpeg() {
        val source = encoded(4000, 3000, Bitmap.CompressFormat.JPEG, noisy = true)

        val prepared = ImageAttachmentPreparer.prepare(source, maxBytes)

        assertEquals("image/jpeg", prepared.mimeType)
        assertEquals(2048 to 1536, sizeOf(prepared.bytes))
        assertTrue("Scaling must shrink the upload", prepared.bytes.size < source.size)
        assertTrue(prepared.bytes.size <= maxBytes)
    }

    @Test
    fun tallScreenshotIsScaledDownButKeptLossless() {
        val source = encoded(1440, 6000, Bitmap.CompressFormat.PNG)

        val prepared = ImageAttachmentPreparer.prepare(source, maxBytes)

        assertEquals("Screenshots stay PNG so their text is sharp", "image/png", prepared.mimeType)
        assertEquals(492 to 2048, sizeOf(prepared.bytes))
    }

    @Test
    fun imageThatCannotFitTheLimitIsRefusedWithAReason() {
        val source = encoded(3000, 3000, Bitmap.CompressFormat.JPEG, noisy = true)

        val err = assertThrows(ImageAttachmentException::class.java) {
            ImageAttachmentPreparer.prepare(source, maxBytes = 10_000)
        }
        assertEquals("That image is too large to attach", err.message)
    }

    @Test
    fun fileThatIsNotAnImageIsRefusedWithAReason() {
        val err = assertThrows(ImageAttachmentException::class.java) {
            ImageAttachmentPreparer.prepare("#!/bin/sh\necho not an image".toByteArray(), maxBytes)
        }
        assertEquals("That file is not an image the app can read", err.message)
    }

    @Test
    fun imageStoreDownloadsOnceThenServesFromTheCacheFolder() = runBlocking {
        val png = encoded(640, 480, Bitmap.CompressFormat.PNG)
        val downloads = mutableListOf<String>()
        fun newStore() = AttachmentImageStore(
            cacheDir = tempFolder.root,
            ioDispatcher = Dispatchers.Unconfined,
            download = { tabId, id ->
                downloads += "$tabId/$id"
                Result.success(png)
            },
        )

        val store = newStore()
        assertNull(store.cachedThumbnail("att_01"))
        val thumbnail = store.thumbnail("tab_1", "att_01")
        assertNotNull(thumbnail)
        assertEquals(thumbnail, store.cachedThumbnail("att_01"))
        assertEquals(640, store.fullImage("tab_1", "att_01")?.width)
        assertEquals("The image is fetched from the VM once", listOf("tab_1/att_01"), downloads)

        // A new app session finds the image in the cache folder instead of downloading again.
        assertNotNull(newStore().thumbnail("tab_1", "att_01"))
        assertEquals(1, downloads.size)
    }

    @Test
    fun imageStoreReportsAMissingImageAsAbsentAndNeverWritesOutsideItsFolder() = runBlocking {
        val store = AttachmentImageStore(
            cacheDir = tempFolder.root,
            ioDispatcher = Dispatchers.Unconfined,
            download = { _, _ -> Result.failure(IOException("HTTP 404: Not Found")) },
        )
        assertNull(store.thumbnail("tab_1", "att_gone"))
        assertNull(store.fullImage("tab_1", "../../escape"))
        assertTrue(tempFolder.root.walkTopDown().none { it.isFile })
    }
}
