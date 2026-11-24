package com.example.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.view.PixelCopy
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Persists uncaught Java/Kotlin exceptions into internal app storage so the next launch
 * can attach the crash stack trace to a VM debug dump.
 */
object CrashLogStore {
    private val installed = AtomicBoolean(false)

    private fun crashFile(context: Context): File {
        val dir = File(context.filesDir, "debug")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "last_crash.txt")
    }

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val content = buildString {
                    appendLine("Timestamp: ${formatIsoUtc(System.currentTimeMillis())}")
                    appendLine("Thread: ${thread.name} (id=${thread.id})")
                    appendLine()
                    append(sanitizeDebugText(sw.toString()))
                }
                crashFile(appContext).writeText(content, Charsets.UTF_8)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrash(context: Context): String? {
        val f = crashFile(context)
        if (!f.exists()) return null
        return runCatching { f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun hasLastCrash(context: Context): Boolean {
        val f = crashFile(context)
        return f.exists() && f.length() > 0L
    }

    fun clearLastCrash(context: Context) {
        runCatching {
            val f = crashFile(context)
            if (f.exists()) f.delete()
        }
    }
}

fun buildDeviceInfoSummary(): String {
    return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
}

suspend fun collectAppLogcat(maxLines: Int = 400): String? = withContext(Dispatchers.IO) {
    withTimeoutOrNull(3_000L) {
        runCatching {
            val pid = Process.myPid().toString()
            val proc = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "--pid=$pid",
                "-t",
                maxLines.coerceIn(50, 2000).toString(),
            )
                .redirectErrorStream(true)
                .start()
            try {
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                proc.waitFor()
                sanitizeDebugText(output).takeLast(120_000).takeIf { it.isNotBlank() }
            } finally {
                proc.destroy()
            }
        }.getOrNull()
    }
}

/**
 * Captures the current Activity window as a downscaled Base64 PNG for visual glitch inspection.
 * Returns null safely if no Activity is attached or capture fails.
 */
suspend fun captureWindowScreenshotBase64(activity: Activity?): String? {
    if (activity == null || activity.isFinishing || activity.isDestroyed) return null
    val window = activity.window ?: return null
    val decorView = window.decorView
    val width = decorView.width
    val height = decorView.height
    if (width <= 0 || height <= 0) return null

    return runCatching {
        val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine { cont ->
                    try {
                        PixelCopy.request(
                            window,
                            rawBitmap,
                            { result ->
                                if (cont.isActive) {
                                    cont.resume(result == PixelCopy.SUCCESS)
                                }
                            },
                            Handler(Looper.getMainLooper()),
                        )
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } ?: false
        } else {
            false
        }

        if (!copied) {
            withContext(Dispatchers.Main) {
                val canvas = Canvas(rawBitmap)
                decorView.draw(canvas)
            }
        }

        withContext(Dispatchers.Default) {
            val maxWidth = 1080
            val finalBitmap = if (rawBitmap.width > maxWidth) {
                val scaledHeight = (rawBitmap.height.toFloat() * maxWidth / rawBitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawBitmap, maxWidth, scaledHeight, true).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            try {
                val out = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                val bytes = out.toByteArray()
                // Guard against unexpectedly huge bitmaps (> 4 MB raw PNG)
                if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) {
                    null
                } else {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } finally {
                finalBitmap.recycle()
            }
        }
    }.getOrNull()
}

/**
 * Exports a debug dump into a local ZIP archive inside app-private filesDir/debug_dumps/
 * so the user can share or save it to Google Drive / Files even when the VM is unreachable.
 */
suspend fun exportDebugBundleZip(
    context: Context,
    request: DebugDumpUploadRequest,
): File = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "debug_dumps")
    if (!dir.exists()) dir.mkdirs()

    // Keep only the most recent 10 exported zip archives to avoid filling device storage
    dir.listFiles()
        ?.sortedByDescending { it.lastModified() }
        ?.drop(9)
        ?.forEach { runCatching { it.delete() } }

    val safeId = request.dumpId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "dump" }
    val zipFile = File(dir, "agentsterm-debug-$safeId.zip")

    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        fun addTextEntry(name: String, content: String) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        addTextEntry("summary.md", buildDebugSummaryMarkdown(request))
        addTextEntry("state.json", request.workspaceStateJson)
        addTextEntry("events.ndjson", request.eventsNdjson)
        if (!request.logcat.isNullOrBlank()) {
            addTextEntry("logcat.txt", request.logcat)
        }
        if (!request.lastCrash.isNullOrBlank()) {
            addTextEntry("crash.txt", request.lastCrash)
        }
        if (!request.screenshotPngBase64.isNullOrBlank()) {
            runCatching {
                val pngBytes = Base64.decode(request.screenshotPngBase64, Base64.DEFAULT)
                if (pngBytes.isNotEmpty()) {
                    zos.putNextEntry(ZipEntry("screenshot.png"))
                    zos.write(pngBytes)
                    zos.closeEntry()
                }
            }
        }
    }

    zipFile
}

fun shareDebugBundleFile(context: Context, zipFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.debugfileprovider",
        zipFile,
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AgentsTerm Debug Dump (${zipFile.name})")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Share or Save Debug Dump").apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(chooser)
}
