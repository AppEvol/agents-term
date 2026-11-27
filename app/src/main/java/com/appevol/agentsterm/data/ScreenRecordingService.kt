package com.appevol.agentsterm.data

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.activity.result.ActivityResult
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.appevol.agentsterm.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Implemented by the Activity: opens Android's "start recording or casting?" dialog. */
interface ScreenCaptureConsentHost {
    /** Opens the dialog; the answer goes to [ScreenCaptureConsent.deliver]. Main thread only. */
    fun launchScreenCaptureConsent()
}

/**
 * Hands the user's answer to the consent dialog back to the coroutine that asked. Kept outside
 * the Activity so the answer still arrives if the Activity is recreated while the dialog is open.
 */
object ScreenCaptureConsent {
    private val pending = AtomicReference<CompletableDeferred<ActivityResult>?>(null)

    suspend fun request(host: ScreenCaptureConsentHost): ActivityResult {
        val answer = CompletableDeferred<ActivityResult>()
        pending.getAndSet(answer)?.cancel()
        try {
            withContext(Dispatchers.Main) { host.launchScreenCaptureConsent() }
            return answer.await()
        } finally {
            pending.compareAndSet(answer, null)
        }
    }

    fun deliver(result: ActivityResult) {
        pending.getAndSet(null)?.complete(result)
    }
}

/** Where a recording stands; shared by [AndroidScreenRecorder] and [ScreenRecordingService]. */
sealed interface ScreenRecordingState {
    data object Idle : ScreenRecordingState
    data object Starting : ScreenRecordingState
    data class Active(val recording: ScreenRecording) : ScreenRecordingState
    data class Failed(val message: String) : ScreenRecordingState
}

/** [ScreenRecorder] backed by MediaProjection: the user approves, then [ScreenRecordingService] records. */
class AndroidScreenRecorder(
    private val context: Context,
    private val activity: () -> Activity?,
) : ScreenRecorder {
    private val state = ScreenRecordingService.state
    private val starting = AtomicBoolean(false)

    override suspend fun start(maxDurationMs: Long): ScreenRecording {
        val before = state.value
        val running = before is ScreenRecordingState.Active && !before.recording.isFinished
        if (running || !starting.compareAndSet(false, true)) {
            throw AppStateCommandException("A screen recording is already running or being approved; stop it first.")
        }
        try {
            val host = activity() as? ScreenCaptureConsentHost
                ?: throw AppStateCommandException(
                    "The app is in the background; the user has to approve a recording on screen.",
                )
            val answer = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { ScreenCaptureConsent.request(host) }
                ?: throw AppStateCommandException("The user did not answer the screen recording prompt.")
            val grant = answer.data
            if (answer.resultCode != Activity.RESULT_OK || grant == null) {
                throw AppStateCommandException("The user declined the screen recording.")
            }

            // From here on the previous recording is gone.
            state.value = ScreenRecordingState.Starting
            val dir = File(context.cacheDir, RECORDINGS_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val id = UUID.randomUUID().toString().substring(0, 8)
            val intent = Intent(context, ScreenRecordingService::class.java)
                .setAction(ScreenRecordingService.ACTION_START)
                .putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, answer.resultCode)
                .putExtra(ScreenRecordingService.EXTRA_GRANT, grant)
                .putExtra(ScreenRecordingService.EXTRA_ID, id)
                .putExtra(ScreenRecordingService.EXTRA_FILE, File(dir, "$id.mp4").absolutePath)
                .putExtra(ScreenRecordingService.EXTRA_MAX_DURATION_MS, maxDurationMs)
            ContextCompat.startForegroundService(context, intent)

            val started = withTimeoutOrNull(START_TIMEOUT_MS) { state.first { it !is ScreenRecordingState.Starting } }
            return when (started) {
                is ScreenRecordingState.Active -> started.recording
                is ScreenRecordingState.Failed -> throw AppStateCommandException(started.message)
                else -> throw AppStateCommandException("The screen recording did not start.")
            }
        } finally {
            state.compareAndSet(ScreenRecordingState.Starting, ScreenRecordingState.Idle)
            starting.set(false)
        }
    }

    override suspend fun stop(): ScreenRecording {
        val running = (state.value as? ScreenRecordingState.Active)?.recording
            ?: throw AppStateCommandException("There is no screen recording to stop.")
        if (running.isFinished) return running
        ScreenRecordingService.stopRunning("stopped")
        val ended = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            state.first { it !is ScreenRecordingState.Active || it.recording.isFinished }
        }
        return when (ended) {
            is ScreenRecordingState.Active -> ended.recording
            is ScreenRecordingState.Failed -> throw AppStateCommandException(ended.message)
            else -> throw AppStateCommandException("The screen recording did not stop.")
        }
    }

    override fun current(): ScreenRecording? = (state.value as? ScreenRecordingState.Active)?.recording

    private companion object {
        const val RECORDINGS_DIR = "screen-recordings"

        // Shorter than the CLI's wait for `record start`, so the agent gets this error, not a timeout.
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val START_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 10_000L
    }
}

/**
 * Foreground service that records the screen to an MP4 file for the length of one recording.
 * Android only allows screen capture from a foreground service of type `mediaProjection`, with
 * a notification the user can stop it from.
 */
class ScreenRecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var recording: ScreenRecording? = null
    private var startedAtElapsedMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        // The user ended the capture from the system UI (status bar chip, lock screen…).
        override fun onStop() = finish("user_stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)

            ACTION_STOP -> finish("user_stopped")

            // Restarted by the system without its intent: nothing to record.
            else -> if (recording == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        // Must come first: Android requires it within seconds of startForegroundService().
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        if (recording != null) return
        instance = this
        val file = File(intent.getStringExtra(EXTRA_FILE).orEmpty())
        // MediaRecorder and MediaProjection report most failures as bare RuntimeExceptions.
        @Suppress("TooGenericExceptionCaught")
        try {
            val grant = IntentCompat.getParcelableExtra(intent, EXTRA_GRANT, Intent::class.java)
                ?: error("missing screen capture grant")
            val maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 0L)
            val (width, height, densityDpi) = captureSize()

            val mediaRecorder = newMediaRecorder().also { recorder = it }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(FRAME_RATE)
            mediaRecorder.setVideoEncodingBitRate(BIT_RATE)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()

            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), grant)
                ?: error("Android returned no screen capture session")
            projection = mediaProjection
            // Required before createVirtualDisplay() since Android 14.
            mediaProjection.registerCallback(projectionCallback, handler)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "agentsterm-screen-recording",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null,
            )
            mediaRecorder.start()

            startedAtElapsedMs = SystemClock.elapsedRealtime()
            val started = ScreenRecording(
                id = intent.getStringExtra(EXTRA_ID).orEmpty(),
                startedAtMs = System.currentTimeMillis(),
                width = width,
                height = height,
                maxDurationMs = maxDurationMs,
                file = file,
            )
            recording = started
            state.value = ScreenRecordingState.Active(started)
            handler.postDelayed({ finish("max_duration") }, maxDurationMs)
        } catch (e: Exception) {
            fail("Could not start the screen recording: ${e.message ?: e::class.java.simpleName}", file)
        }
    }

    /** Ends the recording and publishes the finished file. Safe to call more than once. */
    private fun finish(reason: String) {
        val active = recording ?: return
        recording = null
        val durationMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
        val stopped = runCatching { recorder?.stop() }.isSuccess
        release()
        if (stopped && active.file.length() > 0) {
            state.value = ScreenRecordingState.Active(active.copy(durationMs = durationMs, stopReason = reason))
        } else {
            // MediaRecorder.stop() throws when no frame was ever encoded.
            active.file.delete()
            state.value = ScreenRecordingState.Failed("The screen recording captured nothing.")
        }
        shutDown()
    }

    private fun fail(message: String, file: File) {
        recording = null
        release()
        file.delete()
        state.value = ScreenRecordingState.Failed(message)
        shutDown()
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { recorder?.release() }
        recorder = null
        projection?.let { p ->
            runCatching { p.unregisterCallback(projectionCallback) }
            runCatching { p.stop() }
        }
        projection = null
    }

    private fun shutDown() {
        if (instance === this) instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish("service_destroyed")
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun newMediaRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(this)
    } else {
        @Suppress("DEPRECATION") // The Context constructor only exists since Android 12.
        MediaRecorder()
    }

    /** Video width, height and density: the screen at the scale screenshots use, with even sides. */
    private fun captureSize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        // Not deprecated-free, but the only way to get the full screen size from a Service on all versions.
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        var scale = screenshotScale(metrics.widthPixels)
        while (true) {
            val width = (metrics.widthPixels * scale).toInt() / 2 * 2
            val height = (metrics.heightPixels * scale).toInt() / 2 * 2
            // Tall screens can exceed what an older phone's encoder accepts: shrink until it fits.
            if (encoderSupports(width, height) || width <= MIN_WIDTH) {
                return Triple(width, height, (metrics.densityDpi * scale).toInt().coerceAtLeast(1))
            }
            scale *= SHRINK_STEP
        }
    }

    private fun encoderSupports(width: Int, height: Int): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        codec.isEncoder &&
            codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
            runCatching {
                codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.isSizeSupported(width, height) == true
            }.getOrDefault(false)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_screen_recording)
            .setContentTitle(getString(R.string.screen_recording_notification_title))
            .setContentText(getString(R.string.screen_recording_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.screen_recording_notification_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.appevol.agentsterm.action.SCREEN_RECORDING_START"
        const val ACTION_STOP = "com.appevol.agentsterm.action.SCREEN_RECORDING_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_GRANT = "grant"
        const val EXTRA_ID = "id"
        const val EXTRA_FILE = "file"
        const val EXTRA_MAX_DURATION_MS = "maxDurationMs"

        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 7501
        private const val FRAME_RATE = 30
        private const val MIN_WIDTH = 360
        private const val SHRINK_STEP = 0.75f

        // Enough for sharp UI text at up to 1080 pixels wide; about 20 MB per minute at most.
        private const val BIT_RATE = 3_000_000

        val state = MutableStateFlow<ScreenRecordingState>(ScreenRecordingState.Idle)

        @Volatile
        private var instance: ScreenRecordingService? = null

        /** Ends the running recording, if any, keeping what was recorded. Callable from any thread. */
        fun stopRunning(reason: String) {
            val service = instance ?: return
            service.handler.post { service.finish(reason) }
        }
    }
}
