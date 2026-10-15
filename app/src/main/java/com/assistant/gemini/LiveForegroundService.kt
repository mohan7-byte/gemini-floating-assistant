package com.assistant.gemini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Keeps the process scheduled during screen-off audio, while the AccessibilityService
 * owns the actual Gemini WebSocket and recording thread. Disconnect stops this service.
 */
class LiveForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.assistant.gemini.live.START"
        const val ACTION_STOP = "com.assistant.gemini.live.STOP"
        private const val CHANNEL = "gemini_live"
        private const val ID = 2202

        fun start(context: Context) {
            val intent = Intent(context, LiveForegroundService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveForegroundService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!FloatingAssistantService.requestDisconnectFromNotification()) {
                AssistantAccessibilityService.requestDisconnectFromNotification()
            }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val stopIntent = Intent(this, LiveForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 901, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Gemini Float · Live")
            .setContentText("Voice session active · Disconnect to stop streaming")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Disconnect", stopPending
            ).build())
            .build()
        startForeground(ID, notification)

        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeminiFloat:LiveAudio").apply {
                setReferenceCounted(false)
                runCatching { acquire(3 * 60 * 60 * 1000L) } // Safety timeout; user's Disconnect releases sooner.
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Gemini Live session", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
