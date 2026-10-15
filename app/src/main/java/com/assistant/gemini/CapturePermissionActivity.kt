package com.assistant.gemini

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Starts a new consented capture session only when the active Live session has none. */
class CapturePermissionActivity : Activity() {
    private val request = 9124
    private val handler = Handler(Looper.getMainLooper())
    private var launchHandled = false
    private var pollCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenCaptureService.isReady()) {
            launchHandled = true
            if (!ScreenCaptureService.requestFrame(this)) {
                ScreenshotBridge.statusReceiver?.invoke("A screen capture is already in progress. Wait a moment and try again.")
                ScreenshotBridge.captureFinishedReceiver?.invoke()
            }
            finish()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), request)
    }

    @Deprecated("Uses result callback for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != request) return
        launchHandled = true
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                finish()
                // Wait for the foreground service to initialise its projection and VirtualDisplay.
                // Do not show the permission picker again if startup is merely a little slow.
                pollUntilReady()
            } catch (e: Exception) {
                ScreenshotBridge.statusReceiver?.invoke("Couldn't start screen capture: ${e.message ?: "service start failed"}.")
                ScreenshotBridge.captureFinishedReceiver?.invoke()
                finish()
            }
        } else {
            ScreenshotBridge.statusReceiver?.invoke("Screen capture permission was cancelled.")
            ScreenshotBridge.captureFinishedReceiver?.invoke()
            Toast.makeText(this, "Screen capture cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun pollUntilReady() {
        if (ScreenCaptureService.isReady()) {
            if (!ScreenCaptureService.requestFrame(applicationContext)) {
                ScreenshotBridge.statusReceiver?.invoke("Screen capture session is busy. Try Screenshot again.")
                ScreenshotBridge.captureFinishedReceiver?.invoke()
            }
            return
        }
        if (pollCount++ >= 30) {
            ScreenshotBridge.statusReceiver?.invoke("Screen capture didn't become ready. Tap Screenshot again to retry.")
            ScreenshotBridge.captureFinishedReceiver?.invoke()
            return
        }
        handler.postDelayed({ pollUntilReady() }, 180L)
    }

    override fun onDestroy() {
        // The readiness poll deliberately survives finish(), because this Activity must
        // return to the underlying app before the first screenshot can be captured.
        if (!launchHandled) ScreenshotBridge.captureFinishedReceiver?.invoke()
        super.onDestroy()
    }
}
