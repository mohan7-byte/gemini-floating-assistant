package com.assistant.gemini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * One user-approved MediaProjection per Live session. Android 10 uses a temporary,
 * low-resolution VirtualDisplay only when a screenshot is requested. Android 14+
 * reuses one VirtualDisplay for the consent session, as required for target SDK 35.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.assistant.gemini.capture.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION_ID = 2201
        @Volatile private var active: ScreenCaptureService? = null

        fun isReady(): Boolean = active?.let { it.projection != null && !it.stopping } == true

        fun requestFrame(context: Context): Boolean {
            val service = active ?: return false
            if (service.projection == null || service.stopping || service.capturePending || service.captureScheduled) return false
            service.captureScheduled = true
            service.mainHandler.postDelayed({ service.armFrameCapture() }, 220L)
            return true
        }

        fun stopSession(context: Context) {
            runCatching { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val persistentDisplay = Build.VERSION.SDK_INT >= 34
    @Volatile private var capturePending = false
    @Volatile private var captureScheduled = false
    @Volatile private var stopping = false
    private var captureTimeout: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopCaptureSession()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Gemini Float")
            .setContentText("Screen sharing is ready for screenshots during this Live session")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            failCapture("Android did not return screen-capture permission data.")
            stopCaptureSession()
            return START_NOT_STICKY
        }

        try {
            projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(code, data)
            val currentProjection = projection
            if (currentProjection == null) {
                failCapture("Android could not start the approved screen-capture session.")
                stopCaptureSession()
                return START_NOT_STICKY
            }
            thread = HandlerThread("GeminiScreenCapture").also { it.start() }
            captureHandler = Handler(thread!!.looper)
            currentProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopCaptureSession() }
                }
            }, captureHandler)

            // Android 14+ targeting SDK 34+ may create only one VirtualDisplay per grant.
            // On Android 10, defer display allocation until each explicit screenshot tap.
            if (persistentDisplay) createDisplay()
            active = this
        } catch (e: Exception) {
            failCapture("Couldn't start screen capture: ${e.message ?: "Android rejected the session"}.")
            stopCaptureSession()
        }
        return START_NOT_STICKY
    }

    private fun armFrameCapture() {
        captureScheduled = false
        if (stopping || projection == null || capturePending) {
            mainHandler.post { ScreenshotBridge.captureFinishedReceiver?.invoke() }
            return
        }
        capturePending = true
        captureTimeout?.let(mainHandler::removeCallbacks)
        captureTimeout = Runnable {
            if (capturePending) {
                capturePending = false
                if (!persistentDisplay) releaseFrameDisplay()
                failCapture("No usable screen frame arrived. Return to the app you want to capture and try again.")
                ScreenshotBridge.captureFinishedReceiver?.invoke()
            }
        }.also { mainHandler.postDelayed(it, 5000L) }

        if (!persistentDisplay) {
            try {
                createDisplay()
            } catch (e: Exception) {
                capturePending = false
                captureTimeout?.let(mainHandler::removeCallbacks)
                captureTimeout = null
                releaseFrameDisplay()
                failCapture("Screen capture failed: ${e.message ?: "could not create capture display"}.")
                mainHandler.post { ScreenshotBridge.captureFinishedReceiver?.invoke() }
            }
        }
    }

    private fun createDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
        val scale = minOf(1f, 960f / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ imageReader ->
            var image: Image? = null
            var bitmap: Bitmap? = null
            var failure: String? = null
            try {
                image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                // Drain frames while idle; only allocate a Bitmap after a user tap.
                if (!capturePending || stopping) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                bitmap = if (padded.width == width) padded
                    else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
                capturePending = false
            } catch (e: Exception) {
                capturePending = false
                failure = e.message ?: "capture failed"
            } finally {
                image?.close()
            }

            val resultBitmap = bitmap
            if (resultBitmap != null) {
                captureTimeout?.let(mainHandler::removeCallbacks)
                captureTimeout = null
                if (!persistentDisplay) releaseFrameDisplay()
                ScreenshotBridge.receiver?.invoke(resultBitmap) ?: resultBitmap.recycle()
                mainHandler.post { ScreenshotBridge.captureFinishedReceiver?.invoke() }
            } else if (failure != null) {
                captureTimeout?.let(mainHandler::removeCallbacks)
                captureTimeout = null
                if (!persistentDisplay) releaseFrameDisplay()
                failCapture("Couldn't read the screen frame: $failure.")
                mainHandler.post { ScreenshotBridge.captureFinishedReceiver?.invoke() }
            }
        }, captureHandler)

        display = projection?.createVirtualDisplay(
            "GeminiFloatScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, captureHandler
        )
        if (display == null) throw IllegalStateException("Virtual display could not be created")
    }

    private fun releaseFrameDisplay() {
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
    }

    private fun failCapture(message: String) {
        mainHandler.post { ScreenshotBridge.statusReceiver?.invoke(message) }
    }

    private fun stopCaptureSession() {
        if (stopping) return
        stopping = true
        val hadPendingCapture = capturePending || captureScheduled
        capturePending = false
        captureScheduled = false
        captureTimeout?.let(mainHandler::removeCallbacks)
        captureTimeout = null
        if (active === this) active = null
        releaseFrameDisplay()
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
        captureHandler = null
        if (hadPendingCapture) mainHandler.post { ScreenshotBridge.captureFinishedReceiver?.invoke() }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopCaptureSession()
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object ScreenshotBridge {
    @Volatile var receiver: ((Bitmap) -> Unit)? = null
    @Volatile var captureFinishedReceiver: (() -> Unit)? = null
    @Volatile var fileTextReceiver: ((String, String) -> Unit)? = null
    @Volatile var statusReceiver: ((String) -> Unit)? = null
}
