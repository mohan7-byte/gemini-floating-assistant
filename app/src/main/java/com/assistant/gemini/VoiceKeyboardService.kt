package com.assistant.gemini

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Premium dictation IME with a local offline English/Hindi keyboard fallback. */
class VoiceKeyboardService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    @Volatile private var intentionalStop = false
    private var statusView: TextView? = null
    private var dictateButton: TextView? = null
    private var keyboardContainer: LinearLayout? = null
    private var quickKeysContainer: LinearLayout? = null
    private var voicePanel: View? = null
    private var offlinePanel: View? = null
    private var languageButton: TextView? = null
    private var isOfflineKeyboard = false
    private var offlineLanguage = "EN"
    @Volatile private var liveSession: GeminiLiveSession? = null
    @Volatile private var lastTranscriptChunk = ""
    @Volatile private var composingPrefix = ""

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_voice_keyboard, null)
        statusView = root.findViewById(R.id.imeStatus)
        dictateButton = root.findViewById(R.id.btnDictate)
        voicePanel = root.findViewById(R.id.voicePanel)
        offlinePanel = root.findViewById(R.id.offlinePanel)
        keyboardContainer = root.findViewById(R.id.keyboardContainer)
        quickKeysContainer = root.findViewById(R.id.quickKeysContainer)
        languageButton = root.findViewById(R.id.btnLanguage)
        dictateButton?.setOnClickListener { if (recording) stopDictation() else startDictation() }
        root.findViewById<View>(R.id.btnHideKeyboard).setOnClickListener {
            stopDictation()
            requestHideSelf(0)
        }
        root.findViewById<View>(R.id.btnOfflineKeyboard).setOnClickListener { showOfflineKeyboard(!isOfflineKeyboard) }
        root.findViewById<View>(R.id.btnBackToVoice).setOnClickListener { showOfflineKeyboard(false) }
        languageButton?.setOnClickListener {
            offlineLanguage = if (offlineLanguage == "EN") "HI" else "EN"
            languageButton?.text = offlineLanguage
            renderOfflineKeys()
        }
        root.findViewById<View>(R.id.btnCustomizeKeys).setOnClickListener { customizeKeys() }
        renderQuickKeys()
        renderOfflineKeys()
        showOfflineKeyboard(false)
        return root
    }

    private fun startDictation() {
        if (recording) return
        val key = SecurePrefs.get(this).getString("api_key", "").orEmpty()
        if (key.isBlank()) { showStatus("Save your Google AI Studio API key in app settings first."); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Enable microphone permission for Gemini Float in Android settings."); return
        }
        if (currentInputConnection == null) { showStatus("Tap a text field first, then select Gemini Float."); return }
        intentionalStop = false
        recording = true
        lastTranscriptChunk = ""
        dictateButton?.text = "■  Stop dictation"
        showStatus("Connecting to live transcription…")
        val session = GeminiLiveSession(this, key, "gemini-3.5-transcribe-live",
            object : GeminiLiveSession.Callbacks {
                override fun onStatus(message: String) { if (recording) showStatus(message) }
                override fun onReady() { showStatus("Listening · words appear as you speak") }
                override fun onTranscript(message: String, isAssistant: Boolean, isInterim: Boolean) {
                    if (isAssistant || !recording || message.isBlank()) return
                    mainHandler.post {
                        if (!recording) return@post
                        val ic = currentInputConnection ?: return@post
                        val normalized = message.trim()
                        if (normalized.isBlank()) return@post
                        if (isInterim) {
                            if (lastTranscriptChunk.isBlank()) composingPrefix = spacingPrefix(ic)
                            lastTranscriptChunk = normalized
                            ic.setComposingText(composingPrefix + normalized, 1)
                            showStatus("Listening · $normalized")
                        } else {
                            val finalText = if (lastTranscriptChunk.isNotBlank()) composingPrefix + normalized else spacingPrefix(ic) + normalized
                            ic.commitText(finalText, 1)
                            lastTranscriptChunk = ""
                            composingPrefix = ""
                            showStatus("Inserted · $normalized")
                        }
                    }
                }
                override fun onEnded(message: String) {
                    mainHandler.post {
                        val userStopped = intentionalStop
                        currentInputConnection?.finishComposingText()
                        recording = false
                        liveSession = null
                        lastTranscriptChunk = ""
                        composingPrefix = ""
                        dictateButton?.text = "●  Start live dictation"
                        showStatus(if (userStopped) "Dictation stopped · text kept" else message)
                    }
                }
            }, dictationOnly = true)
        liveSession = session
        session.connect()
    }

    private fun spacingPrefix(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        return if (before != null && !before.isWhitespace() && before !in "([{\n\r") " " else ""
    }

    private fun stopDictation() {
        if (!recording && liveSession == null) return
        intentionalStop = true
        recording = false
        currentInputConnection?.finishComposingText()
        val session = liveSession
        liveSession = null
        session?.close()
        dictateButton?.text = "●  Start live dictation"
        showStatus("Dictation stopped · text kept")
        lastTranscriptChunk = ""
        composingPrefix = ""
    }

    private fun showOfflineKeyboard(show: Boolean) {
        isOfflineKeyboard = show
        voicePanel?.visibility = if (show) View.GONE else View.VISIBLE
        offlinePanel?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            stopDictation()
            showStatus("Offline keyboard · no internet required")
        }
    }

    private fun renderQuickKeys() {
        val container = quickKeysContainer ?: return
        container.removeAllViews()
        val custom = SecurePrefs.get(this).getString("voice_custom_keys", "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        val keys = listOf("⌫", "Space", "↵", ".", ",") + custom
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = matchWrap() }
        keys.forEach { key ->
            val label = when (key) { "Space" -> "Space"; "↵" -> "Enter"; else -> key }
            row.addView(keyView(label, if (key == "Space") 1.7f else 1f) {
                when (key) {
                    "⌫", "Backspace" -> deleteOne()
                    "Space" -> commit(" ")
                    "↵", "Enter" -> enterKey()
                    else -> commit(key)
                }
            })
        }
        container.addView(row)
    }

    private fun renderOfflineKeys() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        val rows = if (offlineLanguage == "EN") listOf(
            listOf("q","w","e","r","t","y","u","i","o","p"),
            listOf("a","s","d","f","g","h","j","k","l"),
            listOf("⇧","z","x","c","v","b","n","m","⌫"),
            listOf("?123",",","🌐","Space",".","↵")
        ) else listOf(
            listOf("ौ","ै","ा","ी","ू","ब","ह","ग","द","ज"),
            listOf("ो","े","्","ि","ु","प","र","क","त"),
            listOf("ं","म","न","व","ल","स","य","⌫"),
            listOf("ि","ा","🌐","Space","।","↵")
        )
        rows.forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(5) }
            }
            rowKeys.forEach { key ->
                val weight = if (key in listOf("Space", "?123", "🌐", "↵", "⇧", "⌫")) 1.35f else 1f
                row.addView(keyView(if (key == "Space") "English" else key, weight) {
                    when (key) {
                        "⌫" -> deleteOne()
                        "Space" -> commit(" ")
                        "↵" -> enterKey()
                        "🌐" -> { offlineLanguage = if (offlineLanguage == "EN") "HI" else "EN"; languageButton?.text = offlineLanguage; renderOfflineKeys() }
                        "⇧" -> { }
                        "?123" -> renderNumberKeys()
                        else -> commit(key)
                    }
                })
            }
            container.addView(row)
        }
    }

    private fun renderNumberKeys() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        listOf("1234567890".map { it.toString() }, listOf("@","#","$","%","&","-","+","(",")","/"), listOf("=*",",","🌐","Space",".","↵")).forEach { keys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) } }
            keys.forEach { key -> row.addView(keyView(if (key == "Space") "English" else key, if (key == "Space") 1.8f else 1f) {
                when (key) { "🌐" -> renderOfflineKeys(); "Space" -> commit(" "); "↵" -> enterKey(); "⌫" -> deleteOne(); else -> commit(key) }
            }) }
            container.addView(row)
        }
    }

    private fun customizeKeys() {
        val input = EditText(this).apply {
            setText(SecurePrefs.get(this@VoiceKeyboardService).getString("voice_custom_keys", "").orEmpty())
            hint = "e.g. @, #, ₹, 👍"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9CA3AF.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Customize quick keys")
            .setMessage("Add up to 6 shortcuts separated by commas. They appear beside Backspace, Space, Enter, and punctuation.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val keys = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
                SecurePrefs.get(this).edit().putString("voice_custom_keys", keys.joinToString(",")).apply()
                renderQuickKeys()
            }.show()
    }

    private fun keyView(label: String, weight: Float = 1f, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (label.length > 5) 11f else 15f
            setTextColor(if (label in listOf("Space", "English", "Enter", "↵")) 0xFFA7F3D0.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (label in listOf("Space", "English", "Enter", "↵")) 0xFF263A36.toInt() else 0xFF252831.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0xFF3A414A.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), weight).apply { marginStart = dp(2); marginEnd = dp(2) }
        }

    private fun commit(text: String) { currentInputConnection?.commitText(text, 1) }
    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (!before.isNullOrEmpty() && before.length >= 2 && Character.isLowSurrogate(before.last()) && Character.isHighSurrogate(before[before.length - 2])) ic.deleteSurroundingText(2, 0)
            else ic.deleteSurroundingText(1, 0)
        }
    }
    private fun enterKey() {
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showStatus(message: String) { mainHandler.post { statusView?.text = message } }

    override fun onFinishInputView(finishingInput: Boolean) { stopDictation(); super.onFinishInputView(finishingInput) }
    override fun onDestroy() { stopDictation(); super.onDestroy() }
}