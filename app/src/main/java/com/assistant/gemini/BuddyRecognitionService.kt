package com.assistant.gemini

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Speech-recognition companion required by Android's VoiceInteractionService metadata.
 * It uses Buddy Live's input transcription, without generating a second spoken reply.
 */
class BuddyRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: Callback? = null
    @Volatile private var liveSession: GeminiLiveSession? = null
    @Volatile private var speechStarted = false
    @Volatile private var finalDelivered = false
    @Volatile private var stopRequested = false

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        liveSession?.close()
        this.listener = listener
        speechStarted = false
        finalDelivered = false
        stopRequested = false

        val prefs = SecurePrefs.get(this)
        val apiKey = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("model", "gemini-3.8-live").orEmpty().ifBlank { "gemini-3.8-live" }
        if (apiKey.isBlank()) {
            this.listener = null
            main.post { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
            return
        }

        liveSession = GeminiLiveSession(
            this,
            apiKey,
            model,
            object : GeminiLiveSession.Callbacks {
                override fun onStatus(message: String) = Unit

                override fun onTranscript(message: String, isAssistant: Boolean, isInterim: Boolean) {
                    if (isAssistant || message.isBlank()) return
                    val target = this@BuddyRecognitionService.listener ?: return
                    if (isInterim) {
                        main.post {
                            if (this@BuddyRecognitionService.listener !== target || finalDelivered) return@post
                            runCatching {
                                if (!speechStarted) {
                                    speechStarted = true
                                    target.beginningOfSpeech()
                                }
                                target.partialResults(resultBundle(message))
                            }
                        }
                    } else {
                        main.post {
                            if (this@BuddyRecognitionService.listener !== target || finalDelivered) return@post
                            finalDelivered = true
                            runCatching {
                                if (!speechStarted) target.beginningOfSpeech()
                                target.endOfSpeech()
                                target.results(resultBundle(message))
                            }
                            this@BuddyRecognitionService.listener = null
                            liveSession?.close()
                            liveSession = null
                        }
                    }
                }

                override fun onReady() {
                    val target = this@BuddyRecognitionService.listener ?: return
                    main.post {
                        if (this@BuddyRecognitionService.listener === target) {
                            runCatching { target.readyForSpeech(Bundle()) }
                            if (stopRequested) liveSession?.finishInput()
                        }
                    }
                }

                override fun onEnded(message: String) {
                    val target = this@BuddyRecognitionService.listener ?: return
                    main.post {
                        if (this@BuddyRecognitionService.listener !== target || finalDelivered) return@post
                        this@BuddyRecognitionService.listener = null
                        runCatching { target.error(SpeechRecognizer.ERROR_NETWORK) }
                    }
                }
            },
            dictationOnly = true
        ).also { it.connect() }
    }

    override fun onStopListening(listener: Callback) {
        stopRequested = true
        liveSession?.finishInput()
    }

    override fun onCancel(listener: Callback) {
        this.listener = null
        liveSession?.close()
        liveSession = null
        stopRequested = false
    }

    override fun onDestroy() {
        listener = null
        liveSession?.close()
        liveSession = null
        super.onDestroy()
    }

    private fun resultBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
}
