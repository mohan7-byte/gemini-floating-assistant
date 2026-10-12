package com.assistant.gemini

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.Locale

class AssistantAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var panelView: View? = null
    private var panelExpanded = false
    private var muted = false
    private var lastVolumeDown = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var liveSession: GeminiLiveSession? = null
    private var attachedScreenshot: Bitmap? = null
    private val transcript = StringBuilder()

    companion object {
        const val ACTION_SHOW = "com.assistant.gemini.SHOW"
        private const val DOUBLE_PRESS_MS = 480L
        @Volatile private var activeInstance: AssistantAccessibilityService? = null

        /** The launcher can open the active accessibility service without leaving an always-on orb. */
        fun requestShow(): Boolean {
            val service = activeInstance ?: return false
            service.mainHandler.post { service.showAssistant() }
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ScreenshotBridge.receiver = { bitmap -> onScreenshotReady(bitmap) }
        // Intentionally do not create any always-visible floating icon.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) showAssistant()
        return START_NOT_STICKY
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        val now = System.currentTimeMillis()
        if (now - lastVolumeDown in 1..DOUBLE_PRESS_MS) {
            lastVolumeDown = 0L
            if (liveSession == null) showAssistant()
            return true
        }
        lastVolumeDown = now
        return false
    }

    private fun showAssistant() {
        if (liveSession != null || panelView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps in Gemini Float settings first.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow microphone permission from Gemini Float settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = SecurePrefs.get(this)
        val apiKey = prefs.getString("api_key", "").orEmpty()
        val savedModel = prefs.getString("model", "").orEmpty().trim().removePrefix("models/")
        val legacyModels = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3-pro-preview")
        val model = if (savedModel.isBlank() || savedModel in legacyModels) "gemini-3.8-live" else savedModel
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Paste your Google AI Studio API key in Gemini Float settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_assistant, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelView = view
        panelExpanded = false
        muted = false
        transcript.setLength(0)
        view.findViewById<TextView>(R.id.tvModelBadge).text = model
        view.findViewById<LinearLayout>(R.id.expandedContent).visibility = View.GONE

        view.findViewById<View>(R.id.btnExpand).setOnClickListener {
            panelExpanded = !panelExpanded
            view.findViewById<LinearLayout>(R.id.expandedContent).visibility =
                if (panelExpanded) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.btnExpand).text = if (panelExpanded) "↓" else "↑"
            if (panelExpanded) {
                view.findViewById<ScrollView>(R.id.responseScroll).post {
                    view.findViewById<ScrollView>(R.id.responseScroll).fullScroll(View.FOCUS_DOWN)
                }
            }
            runCatching { windowManager?.updateViewLayout(view, params) }
        }

        view.findViewById<View>(R.id.btnMute).setOnClickListener {
            muted = !muted
            view.findViewById<TextView>(R.id.btnMute).text = if (muted) "Unmute" else "Mute"
            liveSession?.setMuted(muted)
        }

        view.findViewById<View>(R.id.btnScreenshot).setOnClickListener {
            if (liveSession == null) {
                status("No active Live session.")
            } else {
                status("Approve Android's one-time screen capture prompt.")
                startActivity(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        view.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            endAssistant("Gemini Live disconnected.")
        }

        val input = view.findViewById<EditText>(R.id.etPrompt)
        view.findViewById<View>(R.id.btnSend).setOnClickListener { sendText(input) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendText(input); true
            } else false
        }

        runCatching { windowManager?.addView(view, params) }.onFailure {
            panelView = null
            Toast.makeText(this, "Couldn't open the control bar. Check overlay permission.", Toast.LENGTH_LONG).show()
            return
        }

        val session = GeminiLiveSession(this, apiKey, model, object : GeminiLiveSession.Callbacks {
            override fun onStatus(message: String) { status(message) }
            override fun onTranscript(message: String, isAssistant: Boolean) { appendTranscript(message, isAssistant) }
            override fun onReady() { status("Live · Listening with $model") }
            override fun onEnded(message: String) {
                mainHandler.post {
                    if (liveSession != null) endAssistant(message)
                    else status(message)
                }
            }
        })
        liveSession = session
        session.connect()
    }

    private fun sendText(input: EditText) {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        appendTranscript("You: $message", false)
        liveSession?.sendText(message)
        input.setText("")
    }

    private fun status(message: String) {
        mainHandler.post {
            panelView?.findViewById<TextView>(R.id.tvStatus)?.text = message
        }
    }

    private fun appendTranscript(message: String, isAssistant: Boolean) {
        mainHandler.post {
            if (panelView == null || message.isBlank()) return@post
            if (isAssistant && transcript.isNotEmpty() &&
                transcript.toString().substringAfterLast("\n").startsWith("Gemini: ")) {
                transcript.append(message.removePrefix("Gemini: "))
            } else {
                if (transcript.isNotEmpty()) transcript.append("\n")
                transcript.append(message)
            }
            if (transcript.length > 6000) transcript.delete(0, transcript.length - 5000)
            panelView?.findViewById<TextView>(R.id.tvResponse)?.text = transcript.toString()
            panelView?.findViewById<ScrollView>(R.id.responseScroll)?.post {
                panelView?.findViewById<ScrollView>(R.id.responseScroll)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun onScreenshotReady(bitmap: Bitmap) {
        mainHandler.post {
            val session = liveSession
            if (session == null) {
                bitmap.recycle()
                status("Live session is disconnected; screenshot was not sent.")
                return@post
            }
            session.sendImage(bitmap)
            bitmap.recycle()
        }
    }

    /** Disconnect always closes the socket, microphone and speaker, then removes the control bar. */
    private fun endAssistant(message: String? = null) {
        val session = liveSession
        liveSession = null
        session?.close()
        attachedScreenshot?.recycle()
        attachedScreenshot = null
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        panelExpanded = false
        muted = false
        transcript.setLength(0)
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {
        endAssistant()
    }

    override fun onDestroy() {
        endAssistant()
        if (activeInstance === this) activeInstance = null
        ScreenshotBridge.receiver = null
        super.onDestroy()
    }
}
