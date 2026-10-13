package com.assistant.gemini

import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Live dictation IME. Uses Gemini Live's inputAudioTranscription stream and commits
 * each new transcription fragment straight into the currently focused text field.
 */
class VoiceKeyboardService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    @Volatile private var intentionalStop = false
    private var statusView: TextView? = null
    private var dictateButton: Button? = null
    @Volatile private var liveSession: GeminiLiveSession? = null
    @Volatile private var lastTranscriptChunk = ""

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_keyboard, null)
        statusView = view.findViewById(R.id.imeStatus)
        dictateButton = view.findViewById(R.id.btnDictate)
        dictateButton?.setOnClickListener {
            if (recording) stopDictation() else startDictation()
        }
        view.findViewById<View>(R.id.btnHideKeyboard).setOnClickListener {
            stopDictation()
            requestHideSelf(0)
        }
        return view
    }

    private fun startDictation() {
        if (recording) return
        val key = SecurePrefs.get(this).getString("api_key", "").orEmpty()
        if (key.isBlank()) {
            showStatus("Open Gemini Float and save your Google AI Studio API key first.")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Enable microphone permission for Gemini Float in Android app settings.")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            showStatus("Tap a text field first, then choose Gemini Float Voice Keyboard.")
            return
        }

        intentionalStop = false
        recording = true
        lastTranscriptChunk = ""
        dictateButton?.text = "Stop dictation"
        showStatus("Connecting to Gemini Live…")

        // Use the same Live model as the floating assistant, but in transcription-only
        // mode: no spoken answers, no batch upload, and no waiting for a long pause.
        val session = GeminiLiveSession(
            this,
            key,
            "gemini-3.8-live",
            object : GeminiLiveSession.Callbacks {
                override fun onStatus(message: String) {
                    if (recording) showStatus(message)
                }

                override fun onReady() {
                    showStatus("Listening · words appear as you speak")
                }

                override fun onTranscript(message: String, isAssistant: Boolean) {
                    if (isAssistant || !recording || message.isBlank()) return
                    val delta = synchronized(this@VoiceKeyboardService) {
                        val previous = lastTranscriptChunk
                        lastTranscriptChunk = message
                        transcriptionDelta(previous, message)
                    }
                    if (delta.isNotBlank()) {
                        mainHandler.post {
                            if (recording) {
                                val needsSpace = shouldInsertSpace(connection, delta)
                                connection.commitText((if (needsSpace) " " else "") + delta, 1)
                            }
                        }
                    }
                }

                override fun onEnded(message: String) {
                    mainHandler.post {
                        val wasUserStop = intentionalStop
                        recording = false
                        liveSession = null
                        dictateButton?.text = "●  Start live dictation"
                        showStatus(if (wasUserStop) "Dictation stopped · text kept in place" else message)
                    }
                }
            },
            dictationOnly = true
        )
        liveSession = session
        session.connect()
    }

    private fun stopDictation() {
        if (!recording && liveSession == null) return
        intentionalStop = true
        recording = false
        val session = liveSession
        liveSession = null
        session?.close()
        dictateButton?.text = "●  Start live dictation"
        showStatus("Dictation stopped · text kept in place")
        lastTranscriptChunk = ""
    }

    private fun shouldInsertSpace(connection: android.view.inputmethod.InputConnection, delta: String): Boolean {
        val first = delta.firstOrNull() ?: return false
        if (first.isWhitespace() || first in ".,!?;:%)]}") return false
        val before = connection.getTextBeforeCursor(1, 0)?.lastOrNull() ?: return false
        return !before.isWhitespace() && before !in "([{\n\r"
    }

    /**
     * Live transcription can arrive either as independent fragments or as a growing
     * partial. Convert a growing partial to just its suffix and trim a small overlap
     * between adjacent fragments so words are not duplicated in the target field.
     */
    private fun transcriptionDelta(previous: String, current: String): String {
        val next = current.trim()
        if (next.isBlank() || next == previous.trim()) return ""
        if (previous.isBlank()) return next
        val old = previous.trim()
        if (next.startsWith(old)) return next.substring(old.length).trimStart()
        val maxOverlap = minOf(old.length, next.length, 100)
        for (overlap in maxOverlap downTo 1) {
            if (old.takeLast(overlap).equals(next.take(overlap), ignoreCase = true)) {
                return next.substring(overlap).trimStart()
            }
        }
        return next
    }

    private fun showStatus(message: String) {
        mainHandler.post { statusView?.text = message }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDictation()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDictation()
        super.onDestroy()
    }
}