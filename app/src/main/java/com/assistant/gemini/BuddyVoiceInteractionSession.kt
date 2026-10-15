package com.assistant.gemini

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Routes the Android default-assistant gesture into Buddy's existing floating orb. */
class BuddyVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        main.post {
            val prefs = SecurePrefs.get(context)
            val apiKey = prefs.getString("api_key", "").orEmpty()
            val needsOverlay = !Settings.canDrawOverlays(context)
            val needsMicrophone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            val message = when {
                apiKey.isBlank() -> "Add your API key in Buddy settings before starting Live."
                needsOverlay -> "Allow Buddy to display over other apps, then try the assistant shortcut again."
                needsMicrophone -> "Allow microphone access in Buddy settings, then try again."
                else -> null
            }
            if (message != null) {
                setContentView(buildFallback(message))
                return@post
            }
            if (FloatingAssistantService.show(context)) {
                // The same service creates the live orb used by the app's own launch button.
                hide()
            } else {
                setContentView(buildFallback("Buddy couldn't start the floating control. Open Buddy settings and try again."))
            }
        }
    }

    private fun buildFallback(message: String): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(0xF012151B.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF343B46.toInt())
            }
            elevation = dp(16).toFloat()
        }
        val title = TextView(context).apply {
            text = "Buddy"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val detail = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(0xFFB9C2CD.toInt())
            setPadding(0, dp(8), 0, dp(14))
        }
        val open = TextView(context).apply {
            text = "Open Buddy settings"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF17211F.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF9EEBCB.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                val intent = Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
        root.addView(title)
        root.addView(detail)
        root.addView(open, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onHide() {
        super.onHide()
    }
}
