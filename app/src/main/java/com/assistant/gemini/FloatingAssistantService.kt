package com.assistant.gemini

import android.Manifest
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Floating overlay that runs independently of AccessibilityService. */
class FloatingAssistantService : Service() {
    companion object {
        const val ACTION_SHOW = "com.assistant.gemini.FLOATING_SHOW"
        fun show(context: Context) { context.startService(Intent(context, FloatingAssistantService::class.java).setAction(ACTION_SHOW)) }
    }
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var session: GeminiLiveSession? = null
    private var expanded = false
    private var minimized = false
    private var muted = false
    private val transcript = StringBuilder()

    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (panel == null) showPanel(); return START_NOT_STICKY }

    private fun showPanel() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Buddy to display over other apps first.", Toast.LENGTH_LONG).show(); stopSelf(); return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow microphone permission in Buddy settings first.", Toast.LENGTH_LONG).show(); stopSelf(); return
        }
        val prefs = SecurePrefs.get(this)
        val key = prefs.getString("api_key", "").orEmpty()
        val saved = prefs.getString("model", "gemini-3.8-live").orEmpty().trim().removePrefix("models/")
        val legacy = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3-pro-preview")
        val model = if (saved.isBlank() || saved in legacy) "gemini-3.8-live" else saved
        if (key.isBlank()) { Toast.makeText(this, "Add your API key in Buddy settings first.", Toast.LENGTH_LONG).show(); stopSelf(); return }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_assistant, null)
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM; y = dp(8); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        panel = v; params = p
        v.findViewById<TextView>(R.id.tvModelBadge).text = "Connecting securely…"
        v.findViewById<LinearLayout>(R.id.expandedContent).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.cameraPreviewPanel).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.secondaryActionPage).visibility = View.GONE
        v.findViewById<View>(R.id.minimizedBubble).visibility = View.GONE
        v.findViewById<View>(R.id.btnMinimize).setOnClickListener { minimize() }
        v.findViewById<View>(R.id.minimizedBubble).setOnClickListener { restore() }
        val toggle = {
            expanded = !expanded
            v.findViewById<LinearLayout>(R.id.expandedContent).visibility = if (expanded) View.VISIBLE else View.GONE
            v.findViewById<ImageView>(R.id.btnExpand).setImageResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
            updateLayout()
        }
        v.findViewById<View>(R.id.headerInfo).setOnClickListener { toggle() }
        v.findViewById<View>(R.id.btnExpand).setOnClickListener { toggle() }
        v.findViewById<View>(R.id.btnMute).setOnClickListener {
            muted = !muted; v.findViewById<ImageView>(R.id.btnMute).setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            session?.setMuted(muted); status(if (muted) "Microphone muted" else "Buddy listening")
        }
        v.findViewById<View>(R.id.btnDisconnect).setOnClickListener { removePanel() }
        v.findViewById<View>(R.id.btnVoiceKeyboard).setOnClickListener {
            session?.close(); session = null; LiveForegroundService.stop(this); removePanel()
            main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }, 250L)
        }
        v.findViewById<View>(R.id.btnScreenshot).setOnClickListener { status("Screenshot sharing is available with Buddy Shortcut enabled in Accessibility settings.") }
        v.findViewById<View>(R.id.btnCamera).setOnClickListener { status("Camera controls currently require Buddy Shortcut in Accessibility settings.") }
        v.findViewById<View>(R.id.btnFiles).setOnClickListener { status("File sharing currently requires Buddy Shortcut in Accessibility settings.") }
        v.findViewById<View>(R.id.btnBackFromExtras).setOnClickListener {
            v.findViewById<LinearLayout>(R.id.secondaryActionPage).visibility = View.GONE
            v.findViewById<LinearLayout>(R.id.primaryActionPage).visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.actionsPager).setOnClickListener {
            val extras = v.findViewById<LinearLayout>(R.id.secondaryActionPage); val primary = v.findViewById<LinearLayout>(R.id.primaryActionPage)
            val show = extras.visibility != View.VISIBLE
            extras.visibility = if (show) View.VISIBLE else View.GONE; primary.visibility = if (show) View.GONE else View.VISIBLE
        }
        val input = v.findViewById<EditText>(R.id.etPrompt)
        input.setOnFocusChangeListener { _, focused ->
            val current = params ?: return@setOnFocusChangeListener
            current.flags = if (focused) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            runCatching { wm?.updateViewLayout(panel, current) }
        }
        v.findViewById<View>(R.id.btnSend).setOnClickListener { send(input) }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE) { send(input); true } else false }
        v.findViewById<View>(R.id.btnCopyResponse).setOnClickListener {
            val text = v.findViewById<TextView>(R.id.tvResponse).text.toString().trim()
            if (text.isNotBlank() && text != "Live transcript appears here.") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Buddy response", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        try { wm?.addView(v, p) } catch (_: Exception) {
            panel = null; params = null; Toast.makeText(this, "Couldn't show Buddy bar. Check overlay permission.", Toast.LENGTH_LONG).show(); stopSelf(); return
        }
        LiveForegroundService.start(this)
        session = GeminiLiveSession(this, key, model, object : GeminiLiveSession.Callbacks {
            override fun onStatus(message: String) { main.post { v.findViewById<TextView>(R.id.tvModelBadge).text = clean(message); v.findViewById<TextView>(R.id.tvStatus).text = clean(message) } }
            override fun onTranscript(message: String, isAssistant: Boolean, isInterim: Boolean) { if (!isInterim) append(message) }
            override fun onReady() { main.post { v.findViewById<TextView>(R.id.tvModelBadge).text = "Buddy · Listening" } }
            override fun onEnded(message: String) { main.post { v.findViewById<TextView>(R.id.tvModelBadge).text = "Disconnected"; status(message) } }
        }).also { it.connect() }
    }
    private fun send(input: EditText) {
        val text = input.text.toString().trim(); if (text.isBlank()) return
        append("You: " + text); session?.sendText(text); input.setText("")
    }
    private fun append(message: String) {
        main.post {
            val v = panel ?: return@post
            if (message.isBlank()) return@post
            if (transcript.isNotEmpty()) transcript.append("\n")
            transcript.append(message); if (transcript.length > 6000) transcript.delete(0, transcript.length - 5000)
            v.findViewById<TextView>(R.id.tvResponse).text = transcript.toString()
            if (!expanded) { expanded = true; v.findViewById<LinearLayout>(R.id.expandedContent).visibility = View.VISIBLE; updateLayout() }
            v.findViewById<ScrollView>(R.id.responseScroll).post { v.findViewById<ScrollView>(R.id.responseScroll).fullScroll(View.FOCUS_DOWN) }
        }
    }
    private fun status(message: String) { panel?.findViewById<TextView>(R.id.tvStatus)?.text = clean(message) }
    private fun clean(message: String) = message.replace(Regex("(?i)gemini(?:[ -][a-z0-9.-]+)?"), "Buddy")
    private fun minimize() {
        val v = panel ?: return; minimized = true
        v.findViewById<View>(R.id.topBar).visibility = View.GONE; v.findViewById<View>(R.id.headerDivider).visibility = View.GONE
        v.findViewById<View>(R.id.expandedContent).visibility = View.GONE; v.findViewById<View>(R.id.minimizedBubble).visibility = View.VISIBLE; updateLayout()
    }
    private fun restore() {
        val v = panel ?: return; minimized = false
        v.findViewById<View>(R.id.topBar).visibility = View.VISIBLE; v.findViewById<View>(R.id.headerDivider).visibility = View.VISIBLE
        v.findViewById<View>(R.id.minimizedBubble).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.expandedContent).visibility = if (expanded) View.VISIBLE else View.GONE; updateLayout()
    }
    private fun updateLayout() {
        val v = panel ?: return; val p = params ?: return
        v.findViewById<View>(R.id.expandedContent).visibility = if (!minimized && expanded) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(v, p) }
    }
    private fun removePanel() {
        session?.close(); session = null; panel?.let { runCatching { wm?.removeView(it) } }; panel = null; params = null
        LiveForegroundService.stop(this); stopSelf()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onDestroy() { session?.close(); session = null; panel?.let { runCatching { wm?.removeView(it) } }; panel = null; params = null; LiveForegroundService.stop(this); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}