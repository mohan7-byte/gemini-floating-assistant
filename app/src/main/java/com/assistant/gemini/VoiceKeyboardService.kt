package com.assistant.gemini

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Minimal live-dictation IME with a usable offline English/Hindi keyboard. */
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
    private var shifted = false
    private var numberPage = false
    @Volatile private var liveSession: GeminiLiveSession? = null
    @Volatile private var lastTranscriptChunk = ""
    @Volatile private var composingPrefix = ""
    private val deleteRepeater = object : Runnable {
        override fun run() {
            if (!deleteHeld) return
            deleteWord()
            mainHandler.postDelayed(this, 85L)
        }
    }
    private var deleteHeld = false
    private var deleteDownAt = 0L
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureHandled = false

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
        root.findViewById<View>(R.id.btnHideKeyboard).setOnClickListener { stopDictation(); requestHideSelf(0) }
        root.findViewById<View>(R.id.btnOfflineKeyboard).setOnClickListener { showOfflineKeyboard(!isOfflineKeyboard) }
        root.findViewById<View>(R.id.btnBackToVoice).setOnClickListener { showOfflineKeyboard(false) }
        languageButton?.setOnClickListener {
            offlineLanguage = if (offlineLanguage == "EN") "HI" else "EN"
            numberPage = false
            languageButton?.text = offlineLanguage
            renderOfflineKeys()
        }
        root.findViewById<View>(R.id.btnCustomizeKeys).setOnClickListener { customizeKeys() }
        // Swipe down on the header/status area hides the IME; it never steals ordinary key taps.
        listOf(root.findViewById<View>(R.id.imeHeader), root.findViewById<View>(R.id.imeStatus))
            .forEach { installHideGesture(it) }
        renderQuickKeys()
        renderOfflineKeys()
        showOfflineKeyboard(false)
        return root
    }

    private fun startDictation() {
        if (recording) return
        val key = SecurePrefs.get(this).getString("api_key", "").orEmpty()
        if (key.isBlank()) { showStatus("Add your API key in app settings first."); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Enable microphone permission in Android settings."); return
        }
        if (currentInputConnection == null) { showStatus("Tap a text field first."); return }
        intentionalStop = false
        recording = true
        lastTranscriptChunk = ""
        dictateButton?.text = "■  Stop dictation"
        showStatus("Connecting to live transcription…")
        val session = GeminiLiveSession(this, key, "gemini-3.5-transcribe-live",
            object : GeminiLiveSession.Callbacks {
                override fun onStatus(message: String) { if (recording) showStatus(message) }
                override fun onReady() { showStatus("Listening · live text") }
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
                            ic.commitText((if (lastTranscriptChunk.isNotBlank()) composingPrefix else spacingPrefix(ic)) + normalized, 1)
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
                        showStatus(if (userStopped) "Dictation stopped" else message)
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
            val view = keyView(label, if (key == "Space") 1.6f else 1f, primary = key == "Space" || key == "↵") {
                when (key) {
                    "⌫", "Backspace" -> deleteOne()
                    "Space" -> commit(" ")
                    "↵", "Enter" -> enterKey()
                    else -> commit(key)
                }
            }
            if (key == "⌫" || key == "Backspace") installDeleteGesture(view)
            row.addView(view)
        }
        container.addView(row)
    }

    private fun renderOfflineKeys() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        val rows: List<List<String>> = when {
            numberPage -> listOf(
                "1234567890".map { it.toString() },
                listOf("@","#","₹","%","&","-","+","(",")","/"),
                listOf("ABC",",","?","!","Space",".","⌫","↵")
            )
            offlineLanguage == "EN" -> listOf(
                listOf("q","w","e","r","t","y","u","i","o","p"),
                listOf("a","s","d","f","g","h","j","k","l"),
                listOf("⇧","z","x","c","v","b","n","m","⌫"),
                listOf("?123",",","🌐","Space",".","↵")
            )
            else -> listOf(
                listOf("अ","आ","इ","ई","उ","ऊ","ऋ","ए","ऐ","ओ"),
                listOf("औ","अं","अः","क","ख","ग","घ","ङ","च","छ"),
                listOf("ज","झ","ञ","ट","ठ","ड","ढ","ण","त","थ"),
                listOf("द","ध","न","प","फ","ब","भ","म","य","र"),
                listOf("ल","व","श","ष","स","ह","क्ष","त्र","ज्ञ","⌫"),
                listOf("ा","ि","ी","ु","ू","ृ","े","ै","ो","ौ"),
                listOf("ं","ः","्","ँ","।","?123","🌐","Space","↵")
            )
        }
        rows.forEachIndexed { rowIndex, rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (offlineLanguage == "HI" && !numberPage) 41 else 44)).apply {
                    bottomMargin = dp(4)
                }
            }
            rowKeys.forEach { key ->
                val special = key in listOf("Space","?123","🌐","↵","⇧","⌫","ABC")
                val weight = when (key) { "Space" -> 2.0f; "?123","ABC","🌐","↵","⇧","⌫" -> 1.35f; else -> 1f }
                val label = when {
                    key == "Space" -> "space"
                    key == "🌐" -> "EN/हि"
                    key == "⇧" && shifted -> "⬆"
                    key == "⇧" -> "⇧"
                    key.length == 1 && offlineLanguage == "EN" && shifted && key[0].isLetter() -> key.uppercase()
                    else -> key
                }
                val view = keyView(label, weight, primary = key == "Space" || key == "↵") {
                    when (key) {
                        "⌫" -> deleteOne()
                        "Space" -> commit(" ")
                        "↵" -> enterKey()
                        "🌐" -> { offlineLanguage = if (offlineLanguage == "EN") "HI" else "EN"; numberPage = false; languageButton?.text = offlineLanguage; renderOfflineKeys() }
                        "⇧" -> { shifted = !shifted; renderOfflineKeys() }
                        "?123" -> { numberPage = true; renderOfflineKeys() }
                        "ABC" -> { numberPage = false; renderOfflineKeys() }
                        else -> {
                            val output = if (offlineLanguage == "EN" && shifted && key.length == 1 && key[0].isLetter()) key.uppercase() else key
                            commit(output)
                            if (offlineLanguage == "EN" && shifted) { shifted = false; renderOfflineKeys() }
                        }
                    }
                }
                if (key == "⌫") installDeleteGesture(view)
                else installHideGesture(view)
                row.addView(view)
            }
            container.addView(row)
        }
    }

    private fun installHideGesture(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.rawX; gestureStartY = event.rawY; gestureHandled = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureHandled && event.rawY - gestureStartY > dp(70) &&
                        abs(event.rawY - gestureStartY) > abs(event.rawX - gestureStartX)) {
                        gestureHandled = true; stopDictation(); requestHideSelf(0); true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { gestureHandled = false; false }
                else -> false
            }
        }
    }

    private fun installDeleteGesture(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteHeld = false
                    gestureStartX = event.rawX; gestureStartY = event.rawY
                    deleteDownAt = android.os.SystemClock.uptimeMillis()
                    mainHandler.postDelayed({
                        if (deleteDownAt != 0L) { deleteHeld = true; deleteWord(); mainHandler.postDelayed(deleteRepeater, 140L) }
                    }, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (deleteDownAt != 0L && (abs(event.rawX - gestureStartX) > dp(36) || abs(event.rawY - gestureStartY) > dp(36))) {
                        if (!deleteHeld) { mainHandler.removeCallbacks(deleteRepeater); deleteHeld = true }
                        deleteWord()
                        true
                    } else true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasLong = deleteHeld
                    deleteDownAt = 0L; deleteHeld = false; mainHandler.removeCallbacks(deleteRepeater)
                    if (!wasLong && event.actionMasked == MotionEvent.ACTION_UP) deleteOne()
                    true
                }
                else -> true
            }
        }
    }

    private fun customizeKeys() {
        val input = EditText(this).apply {
            setText(SecurePrefs.get(this@VoiceKeyboardService).getString("voice_custom_keys", "").orEmpty())
            hint = "e.g. @, #, ₹, 👍"
            setTextColor(Color.WHITE); setHintTextColor(0xFF9CA3AF.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Quick keys")
            .setMessage("Up to 6 shortcuts, separated by commas.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val keys = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
                SecurePrefs.get(this).edit().putString("voice_custom_keys", keys.joinToString(",")).apply()
                renderQuickKeys()
            }.show()
    }

    private fun keyView(label: String, weight: Float = 1f, primary: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = when { label.length > 5 -> 11f; label.length > 2 -> 13f; else -> 17f }
            setTextColor(if (primary) 0xFFC0F5E5.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) 0xFF21483F.toInt() else 0xFF252831.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), if (primary) 0xFF32665A.toInt() else 0xFF3A414A.toInt())
            }
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), weight).apply { marginStart = dp(2); marginEnd = dp(2) }
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
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        if (before.isEmpty()) { deleteOne(); return }
        val trimmed = before.dropLastWhile { it.isWhitespace() }
        val word = trimmed.takeLastWhile { !it.isWhitespace() }
        val count = (word.length + (before.length - trimmed.length)).coerceAtLeast(1)
        ic.deleteSurroundingText(count, 0)
    }
    private fun enterKey() {
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
    }
    private fun installDummy() = Unit
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showStatus(message: String) { mainHandler.post { statusView?.text = message } }

    override fun onFinishInputView(finishingInput: Boolean) { stopDictation(); super.onFinishInputView(finishingInput) }
    override fun onDestroy() { deleteDownAt = 0L; deleteHeld = false; mainHandler.removeCallbacks(deleteRepeater); stopDictation(); super.onDestroy() }
}