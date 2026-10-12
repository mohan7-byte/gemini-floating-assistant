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
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AssistantAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isExpanded = false
    private var muted = false
    private var lastVolumeDown = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val history = mutableListOf<GeminiApiClient.Turn>()
    private var attachedScreenshot: Bitmap? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var lastRequestJob: Job? = null

    companion object {
        const val ACTION_SHOW = "com.assistant.gemini.SHOW"
        private const val DOUBLE_PRESS_MS = 480L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ScreenshotBridge.receiver = { bitmap -> onScreenshotReady(bitmap) }
        if (Settings.canDrawOverlays(this)) showOrb()
        tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        val now = System.currentTimeMillis()
        if (now - lastVolumeDown in 1..DOUBLE_PRESS_MS) {
            lastVolumeDown = 0L
            if (isExpanded) dismissAssistant() else showAssistant()
            return true
        }
        lastVolumeDown = now
        return false
    }

    private fun showOrb() {
        if (floatingView != null || !Settings.canDrawOverlays(this)) return
        val orb = TextView(this).apply {
            text = "✦"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.accent))
            background = getDrawable(R.drawable.floating_orb)
            alpha = 0.84f
            elevation = 12f
            contentDescription = "Open Gemini Float assistant"
        }
        val params = WindowManager.LayoutParams(dp(52), dp(52), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(10); y = 0 }
        var startX = 0; var startY = 0; var initialX = 0; var initialY = 0
        orb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initialX = params.x; initialY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = startX - event.rawX.toInt(); val dy = event.rawY.toInt() - startY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) {
                        params.x = (initialX + dx).coerceAtLeast(0); params.y = initialY + dy
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(startX - event.rawX.toInt()); val dy = kotlin.math.abs(startY - event.rawY.toInt())
                    if (dx + dy < dp(8)) showAssistant()
                    true
                }
                else -> true
            }
        }
        floatingView = orb
        runCatching { windowManager?.addView(orb, params) }.onFailure { floatingView = null }
    }

    private fun showAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Display over other apps' in Gemini Float settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (isExpanded) return
        removeFloatingView()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_assistant, null)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM; y = dp(12); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        floatingView = view
        isExpanded = true
        val answer = view.findViewById<TextView>(R.id.tvResponse)
        val prompt = view.findViewById<EditText>(R.id.etPrompt)
        val status = view.findViewById<TextView>(R.id.tvStatus)
        val badge = view.findViewById<TextView>(R.id.tvModelBadge)
        val prefs = SecurePrefs.get(this)
        badge.text = (prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash").uppercase(Locale.ROOT)
        answer.movementMethod = android.text.method.ScrollingMovementMethod()

        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismissAssistant() }
        view.findViewById<View>(R.id.btnMute).setOnClickListener {
            muted = !muted
            view.findViewById<TextView>(R.id.btnMute).text = if (muted) "Unmute" else "Mute"
            if (muted) tts?.stop()
            status.text = if (muted) "Voice responses muted" else "Voice responses enabled"
        }
        view.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            disconnect()
            answer.text = "Disconnected. Reopen the assistant whenever you are ready."
        }
        view.findViewById<View>(R.id.btnSend).setOnClickListener { sendPrompt(prompt.text.toString(), answer, status, prompt) }
        prompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendPrompt(prompt.text.toString(), answer, status, prompt); true
            } else false
        }
        view.findViewById<View>(R.id.btnScreenshot).setOnClickListener {
            startActivity(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            status.text = "Approve Android's screen-capture prompt; then return here."
        }
        view.findViewById<View>(R.id.btnVoice).setOnClickListener { listen(prompt, status) }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { dismissAssistant(); true } else false
        }
        runCatching { windowManager?.addView(view, params) }.onFailure {
            floatingView = null; isExpanded = false
            Toast.makeText(this, "Couldn't open floating window. Check overlay permission.", Toast.LENGTH_LONG).show()
            showOrb()
        }
        attachedScreenshot?.let {
            answer.text = "Screenshot attached. Ask a question about the visible screen below."
            status.text = "Screenshot ready · Included with next question"
        }
    }

    private fun sendPrompt(rawPrompt: String, answer: TextView, status: TextView, input: EditText) {
        val question = rawPrompt.trim()
        val screenshot = attachedScreenshot
        if (question.isBlank() && screenshot == null) {
            Toast.makeText(this, "Type a question or attach a screenshot", Toast.LENGTH_SHORT).show(); return
        }
        val prefs = SecurePrefs.get(this)
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val mode = prefs.getString("thinking", "Default") ?: "Default"
        input.setText("")
        answer.text = if (question.isBlank()) "Looking at your screenshot…" else "You  ·  $question\n\nThinking…"
        status.text = "Connecting to Gemini…"
        lastRequestJob?.cancel()
        lastRequestJob = scope.launch {
            val reply = GeminiApiClient.generateContent(question, key, model, mode, history.toList(), screenshot)
            if (question.isNotBlank()) history += GeminiApiClient.Turn("user", question)
            history += GeminiApiClient.Turn("model", reply)
            while (history.size > 16) history.removeAt(0)
            answer.text = reply
            status.text = if (reply.startsWith("Connection failed:") || reply.startsWith("API ") || reply.startsWith("Gemini API error")) "Check connection or API settings" else "Done · History stays in memory until disconnect"
            if (screenshot != null) { attachedScreenshot = null; screenshot.recycle() }
            if (!muted && !reply.startsWith("API key") && reply.length < 1800)
                tts?.speak(reply, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "gemini-reply")
            floatingView?.findViewById<ScrollView>(R.id.responseScroll)?.post {
                floatingView?.findViewById<ScrollView>(R.id.responseScroll)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun listen(input: EditText, status: TextView) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition is not available on this device", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow microphone permission from Android app settings to use voice input."
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Processing voice…" }
                override fun onError(error: Int) { status.text = "Voice input stopped (code $error)" }
                override fun onResults(results: android.os.Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) { input.setText(spoken); input.setSelection(input.text.length); status.text = "Voice captured · Tap send" }
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            recognizer.startListening(intent)
        }
    }

    private fun disconnect() {
        lastRequestJob?.cancel(); lastRequestJob = null
        history.clear()
        tts?.stop()
        attachedScreenshot?.recycle(); attachedScreenshot = null
        if (isExpanded) {
            floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = "Disconnected · Conversation cleared"
            floatingView?.findViewById<TextView>(R.id.tvResponse)?.text = "Disconnected. Your in-memory conversation history was cleared."
        }
    }

    private fun dismissAssistant() {
        lastRequestJob?.cancel()
        tts?.stop()
        removeFloatingView()
        isExpanded = false
        if (Settings.canDrawOverlays(this)) showOrb()
    }

    private fun removeFloatingView() {
        floatingView?.let { view -> runCatching { windowManager?.removeView(view) } }
        floatingView = null
    }

    fun onScreenshotReady(bitmap: Bitmap) {
        attachedScreenshot?.recycle()
        attachedScreenshot = bitmap
        mainHandler.post {
            floatingView?.findViewById<TextView>(R.id.tvResponse)?.text = "Screenshot attached. Ask a question about the screen, then tap send."
            floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = "Screenshot ready · Not sent until you send a prompt"
            if (!isExpanded) showAssistant()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { dismissAssistant() }

    override fun onDestroy() {
        removeFloatingView(); scope.cancel()
        runCatching { speechRecognizer?.destroy() }
        tts?.stop(); tts?.shutdown()
        attachedScreenshot?.recycle()
        ScreenshotBridge.receiver = null
        super.onDestroy()
    }
}
