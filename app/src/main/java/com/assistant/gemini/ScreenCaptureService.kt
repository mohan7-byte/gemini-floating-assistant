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

class ScreenCaptureService : Service() {
    companion object { const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_RESULT_DATA = "result_data"; private const val CHANNEL = "screen_capture"; private const val ID = 2201 }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var stopped = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("Gemini Float").setContentText("Capturing the screen you approved…").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, notification)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) { stopCapture(); return START_NOT_STICKY }
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        if (projection == null) { stopCapture(); return START_NOT_STICKY }
        thread = HandlerThread("GeminiOneShotCapture").also { it.start() }; handler = Handler(thread!!.looper)
        captureOneFrame(); return START_NOT_STICKY
    }
    private fun captureOneFrame() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels.coerceAtLeast(1); val h = metrics.heightPixels.coerceAtLeast(1)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ ir ->
            var image: Image? = null
            try {
                image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = image.planes[0]; val buffer = plane.buffer
                val px = plane.pixelStride; val rowPadding = plane.rowStride - px * w
                val padded = Bitmap.createBitmap(w + rowPadding / px, h, Bitmap.Config.ARGB_8888); padded.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, w, h); if (cropped !== padded) padded.recycle()
                val scale = minOf(1f, 1280f / maxOf(cropped.width, cropped.height))
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt().coerceAtLeast(1), (cropped.height * scale).toInt().coerceAtLeast(1), true) else cropped
                if (resized !== cropped) cropped.recycle()
                ScreenshotBridge.receiver?.invoke(resized) ?: resized.recycle()
            } catch (_: Exception) { }
            finally { image?.close(); stopCapture() }
        }, handler)
        display = projection?.createVirtualDisplay("GeminiFloatOneShot", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        handler?.postDelayed({ if (projection != null) stopCapture() }, 5000)
    }
    private fun stopCapture() {
        if (stopped) return
        stopped = true
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null); reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }
    override fun onDestroy() { stopCapture(); thread?.quitSafely(); thread = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
object ScreenshotBridge { @Volatile var receiver: ((Bitmap) -> Unit)? = null }
