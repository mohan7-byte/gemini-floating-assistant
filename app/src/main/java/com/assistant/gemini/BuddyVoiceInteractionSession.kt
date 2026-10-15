package com.assistant.gemini

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class BuddyVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var liveSession: GeminiLiveSession? = null
    private var transcriptView: TextView? = null
    private var statusView: TextView? = null
    private var promptView: EditText? = null
    private val transcript = StringBuilder()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setContentView(buildContent())
        connectLive()
    }

    override fun onHide() {
        liveSession?.close()
        liveSession = null
        super.onHide()
    }

    override fun onDestroy() {
        liveSession?.close()
        liveSession = null
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(0xF012151B.toInt(), dp(24).toFloat())
            elevation = dp(20).toFloat()
        }
        val title = TextView(context).apply {
            text = "Buddy"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        statusView = TextView(context).apply {
            text = "Connecting · Live voice"
            textSize = 12f
            setTextColor(0xFF9EEBCB.toInt())
            setPadding(0, dp(3), 0, dp(12))
        }
        root.addView(title)
        root.addView(statusView)
        val scroll = ScrollView(context)
        transcriptView = TextView(context).apply {
            text = "Hi, I'm Buddy. Ask me anything or speak naturally."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        scroll.addView(transcriptView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 1f))
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        promptView = EditText(context).apply {
            hint = "Message Buddy…"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF87909D.toInt())
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(0xFF222832.toInt(), dp(14).toFloat())
        }
        inputRow.addView(promptView, LinearLayout.LayoutParams(0, dp(48), 1f))
        val send = TextView(context).apply {
            text = "Send"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111820.toInt())
            gravity = Gravity.CENTER
            background = rounded(0xFF9EEBCB.toInt(), dp(14).toFloat())
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                val message = promptView?.text?.toString()?.trim().orEmpty()
                if (message.isNotEmpty()) {
                    addTranscript("You: $message")
                    liveSession?.sendText(message)
                    promptView?.setText("")
                }
            }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
        root.addView(inputRow)
        val settings = TextView(context).apply {
            text = "Buddy settings"
            textSize = 12f
            setTextColor(0xFF9AA4B2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                val intent = android.content.Intent(context, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
        root.addView(settings)
        return root
    }

    private fun connectLive() {
        val prefs = SecurePrefs.get(context)
        val apiKey = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("model", "gemini-3.8-live").orEmpty().ifBlank { "gemini-3.8-live" }
        if (apiKey.isBlank()) {
            statusView?.text = "Setup needed · Add API key in Buddy settings"
            return
        }
        liveSession = GeminiLiveSession(context, apiKey, model, object : GeminiLiveSession.Callbacks {
            override fun onStatus(message: String) {
                main.post { statusView?.text = message.replace(Regex("(?i)gemini(?:[ -][a-z0-9.-]+)?"), "Buddy") }
            }
            override fun onTranscript(message: String, isAssistant: Boolean, isInterim: Boolean) {
                if (!isInterim) addTranscript(message)
            }
            override fun onReady() {
                main.post { statusView?.text = "Connected · Listening" }
            }
            override fun onEnded(message: String) {
                main.post { statusView?.text = "Disconnected · $message" }
            }
        }).also { it.connect() }
    }

    private fun addTranscript(message: String) {
        main.post {
            if (message.isBlank()) return@post
            if (transcript.isNotEmpty()) transcript.append("\n")
            transcript.append(message)
            if (transcript.length > 5000) transcript.delete(0, transcript.length - 4000)
            transcriptView?.text = transcript.toString()
        }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(dp(1), 0xFF343B46.toInt())
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
