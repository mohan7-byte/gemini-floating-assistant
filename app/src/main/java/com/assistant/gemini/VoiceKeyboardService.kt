package com.assistant.gemini

import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class VoiceKeyboardService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var recording = false
    private var statusView: TextView? = null
    private var dictateButton: Button? = null
    private var captureThread: Thread? = null

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_keyboard, null)
        statusView = view.findViewById(R.id.imeStatus)
        dictateButton = view.findViewById(R.id.btnDictate)
        dictateButton?.setOnClickListener {
            if (recording) {
                stopRequested = true
                showStatus("Finishing your sentence…")
            } else startDictation()
        }
        view.findViewById<View>(R.id.btnHideKeyboard).setOnClickListener { requestHideSelf(0) }
        return view
    }

    private fun startDictation() {
        val prefs = SecurePrefs.get(this)
        val key = prefs.getString("api_key", "").orEmpty()
        if (key.isBlank()) {
            showStatus("Open Gemini Float and save your Google AI Studio API key first.")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Enable microphone permission for Gemini Float in Android app settings.")
            return
        }
        if (recording) return
        val targetConnection = currentInputConnection
        if (targetConnection == null) {
            showStatus("Tap a text field first, then choose Gemini Float Voice Keyboard.")
            return
        }
        stopRequested = false
        recording = true
        dictateButton?.text = "Stop and insert"
        showStatus("Listening… speak naturally. A pause finishes the sentence.")
        captureThread = Thread({
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("This device doesn't support 16 kHz microphone capture")
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone couldn't be initialized")
                recorder.startRecording()
                val pcm = ByteArrayOutputStream()
                val samples = ShortArray(1600)
                var heardSpeech = false
                var silentChunks = 0
                var totalChunks = 0
                while (!stopRequested && totalChunks < MAX_CHUNKS) {
                    val count = recorder.read(samples, 0, samples.size)
                    if (count <= 0) {
                        if (stopRequested) break
                        continue
                    }
                    var squareSum = 0.0
                    for (i in 0 until count) {
                        val sample = samples[i].toInt()
                        squareSum += sample.toDouble() * sample.toDouble()
                        pcm.write(sample and 0xff)
                        pcm.write((sample shr 8) and 0xff)
                    }
                    val rms = sqrt(squareSum / count)
                    if (rms > SPEECH_RMS_THRESHOLD) {
                        heardSpeech = true
                        silentChunks = 0
                    } else if (heardSpeech) silentChunks++
                    totalChunks++
                    if (heardSpeech && silentChunks >= SILENCE_CHUNKS) break
                }
                runCatching { recorder.stop() }
                if (!heardSpeech || pcm.size() < SAMPLE_RATE / 2) {
                    throw IllegalStateException("No speech detected. Speak close to the microphone and try again.")
                }
                showStatus("Formatting speech with Gemini…")
                val formatted = runBlocking {
                    GeminiApiClient.transcribeAndFormatAudio(
                        pcm.toByteArray(),
                        key,
                        prefs.getString("voice_keyboard_model", "gemini-2.5-flash").orEmpty()
                    )
                }
                val failed = formatted.isBlank() ||
                    formatted.startsWith("API key") ||
                    formatted.startsWith("Gemini API error") ||
                    formatted.startsWith("Connection failed") ||
                    formatted.startsWith("Couldn't transcribe") ||
                    formatted.startsWith("Request rejected") ||
                    formatted.startsWith("Gemini rate limit")
                if (failed) {
                    showStatus(formatted.ifBlank { "Gemini returned no text. Try again." })
                } else {
                    mainHandler.post {
                        targetConnection.commitText(formatted.trim(), 1)
                        statusView?.text = "Text inserted · tap Dictate to continue"
                    }
                }
            } catch (e: Exception) {
                showStatus(e.message ?: "Voice typing failed. Try again.")
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recording = false
                mainHandler.post {
                    dictateButton?.text = "●  Dictate"
                    if (statusView?.text == "Listening… speak naturally. A pause finishes the sentence.") {
                        statusView?.text = "Tap Dictate to enter formatted text into the focused app."
                    }
                }
            }
        }, "GeminiVoiceKeyboard").apply { isDaemon = true; start() }
    }

    private fun showStatus(message: String) {
        mainHandler.post { statusView?.text = message }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRequested = true
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopRequested = true
        captureThread?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MAX_CHUNKS = 250
        private const val SILENCE_CHUNKS = 13
        private const val SPEECH_RMS_THRESHOLD = 260.0
    }
}
