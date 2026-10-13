package com.assistant.gemini

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user-started Gemini Live session. close() stops the socket and audio hardware. */
class GeminiLiveSession(
    context: Context,
    private val apiKey: String,
    modelId: String,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onTranscript(message: String, isAssistant: Boolean)
        fun onReady()
        fun onEnded(message: String)
    }

    private val model = modelId.trim().removePrefix("models/")
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var muted = false
    private val cleanupOnce = AtomicBoolean(false)
    @Volatile private var setupTimeout: Runnable? = null
    private val outputQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null

    @Suppress("UNUSED_PARAMETER")
    fun connect() {
        if (apiKey.isBlank()) {
            callbacks.onEnded("Add your Google AI Studio API key in settings.")
            return
        }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + Uri.encode(apiKey)
        callbacks.onStatus("Connecting to $model…")
        val timeout = Runnable {
            if (!closed && !ready) {
                fail("Gemini accepted the WebSocket transport but did not acknowledge the Live setup within 15 seconds. Check model access/API-key restrictions, and make sure the installed APK contains the latest fix.")
            }
        }
        setupTimeout = timeout
        mainHandler.postDelayed(timeout, 15_000L)
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) { webSocket.cancel(); return }
                callbacks.onStatus("Secure WebSocket opened · sending Live session configuration…")
                // Use the schema for the model itself: audio modality and voice belong
                // under generationConfig. This matches Google's Gemini 3.8 Live model
                // example and BidiGenerateContentSetup schema.
                val setup = JSONObject().put("setup", JSONObject()
                    .put("model", "models/$model")
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Puck")))))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "You are a helpful, concise personal voice assistant. Reply naturally in spoken conversation. Do not claim to see the user's screen unless they explicitly share a screenshot.")))))
                if (!webSocket.send(setup.toString())) {
                    fail("Gemini Live session configuration could not be sent. Check network access and retry.")
                } else {
                    callbacks.onStatus("Configuration sent · waiting for Gemini Live acknowledgement…")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed) return
                try {
                    val root = JSONObject(text)
                    if (root.has("error")) {
                        val detail = root.optJSONObject("error")?.optString("message").orEmpty()
                        if (!closed) callbacks.onEnded("Gemini Live error: $detail")
                        close()
                        return
                    }
                    if (root.has("setupComplete")) {
                        setupTimeout?.let(mainHandler::removeCallbacks)
                        setupTimeout = null
                        ready = true
                        try {
                            startPlayback()
                            startMicrophone()
                            callbacks.onReady()
                            callbacks.onStatus("Live · Listening with $model")
                        } catch (e: Exception) {
                            callbacks.onEnded("Could not start microphone/audio: ${e.message ?: "permission or audio-device error"}")
                            close()
                        }
                        return
                    }
                    val server = root.optJSONObject("serverContent")
                    if (server == null) {
                        // Don't silently ignore unexpected protocol frames during setup.
                        if (!ready) {
                            val fields = mutableListOf<String>()
                            val fieldIterator = root.keys()
                            while (fieldIterator.hasNext()) fields += fieldIterator.next()
                            callbacks.onStatus("Waiting for Live setup acknowledgement · server sent: ${fields.joinToString().ifBlank { "unrecognized message" }}")
                        }
                        return
                    }
                    val inputTranscript = server.optJSONObject("inputTranscription")?.optString("text").orEmpty()
                    if (inputTranscript.isNotBlank()) callbacks.onTranscript("You: $inputTranscript", false)
                    val outputTranscript = server.optJSONObject("outputTranscription")?.optString("text").orEmpty()
                    if (outputTranscript.isNotBlank()) callbacks.onTranscript("Gemini: $outputTranscript", true)

                    // Process every returned part. Audio stays audible when only the mic is muted.
                    val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if (parts != null) for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        if (inline != null) {
                            val mime = inline.optString("mimeType", inline.optString("mime_type"))
                            val data = inline.optString("data")
                            if (data.isNotBlank() && mime.startsWith("audio/pcm")) {
                                val bytes = Base64.decode(data, Base64.DEFAULT)
                                if (!closed) outputQueue.offer(bytes)
                            }
                        }
                    }
                    if (server.optBoolean("turnComplete", false)) callbacks.onStatus("Live · Ready for you")
                } catch (e: Exception) {
                    callbacks.onStatus("Couldn't read Live response: ${e.message ?: "invalid response"}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) {
                    setupTimeout?.let(mainHandler::removeCallbacks)
                    setupTimeout = null
                    val detail = buildString {
                        append(t.message ?: response?.message ?: "connection failed")
                        response?.let { append(" (HTTP ").append(it.code).append(")") }
                    }
                    closed = true
                    cleanupMedia()
                    callbacks.onEnded("Gemini Live connection failed: $detail")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setupTimeout?.let(mainHandler::removeCallbacks)
                setupTimeout = null
                if (!closed) {
                    closed = true
                    cleanupMedia()
                    callbacks.onEnded(if (reason.isBlank()) "Gemini Live disconnected (code $code)." else "Gemini Live disconnected (code $code): $reason")
                }
            }
        })
    }

    /** Mute only the mic; model audio remains audible. Release mic hardware while muted. */
    fun setMuted(value: Boolean) {
        muted = value
        if (closed || !ready) return
        if (value) {
            socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            stopMicrophone()
            callbacks.onStatus("Microphone muted · Live session remains connected")
        } else {
            try {
                if (recorder == null) startMicrophone()
                callbacks.onStatus("Microphone on · Live listening")
            } catch (e: Exception) {
                callbacks.onStatus("Couldn't resume microphone: ${e.message ?: "audio error"}")
            }
        }
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || closed || !ready) {
            if (!ready) callbacks.onStatus("Wait for Gemini Live to connect before sending text.")
            return
        }
        val event = JSONObject().put("realtimeInput", JSONObject().put("text", clean))
        if (socket?.send(event.toString()) != true) callbacks.onStatus("Couldn't send text. Check the Live connection.")
    }

    private fun fail(message: String) {
        if (closed) return
        setupTimeout?.let(mainHandler::removeCallbacks)
        setupTimeout = null
        closed = true
        ready = false
        socket?.cancel()
        socket = null
        cleanupMedia()
        callbacks.onEnded(message)
    }

    fun sendImage(bitmap: Bitmap) {
        if (closed || !ready) {
            callbacks.onStatus("Connect to Gemini Live before capturing a screenshot.")
            return
        }
        try {
            val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            val bytes = ByteArrayOutputStream().use { out -> sized.compress(Bitmap.CompressFormat.JPEG, 72, out); out.toByteArray() }
            if (sized !== bitmap) sized.recycle()
            val imageMessage = JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
                .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
            if (socket?.send(imageMessage.toString()) == true) {
                sendText("Please look at the screenshot I just shared and help me with what is visible on it.")
                callbacks.onStatus("Screenshot sent to Gemini Live")
            } else callbacks.onStatus("Could not send screenshot. Check the Live connection.")
        } catch (e: Exception) {
            callbacks.onStatus("Screenshot error: ${e.message ?: "could not encode image"}")
        }
    }

    fun close() {
        setupTimeout?.let(mainHandler::removeCallbacks)
        setupTimeout = null
        if (closed) { cleanupMedia(); return }
        closed = true
        ready = false
        socket?.close(1000, "User disconnected")
        socket?.cancel()
        socket = null
        cleanupMedia()
    }

    private fun startMicrophone() {
        if (closed || muted || recorder != null) return
        val min = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("microphone format is not supported")
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("microphone could not be initialized")
        }
        recorder = record
        record.startRecording()
        micThread = Thread({
            val buffer = ByteArray(3200)
            while (!closed && !Thread.currentThread().isInterrupted) {
                val count = try { record.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                if (count <= 0) {
                    if (closed) break
                    continue
                }
                if (muted || closed) continue
                val data = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                val event = JSONObject().put("realtimeInput", JSONObject().put("audio",
                    JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", data))).toString()
                if (socket?.send(event) != true) {
                    if (!closed) callbacks.onStatus("Audio stream paused · reconnect if this continues")
                    break
                }
            }
        }, "GeminiLive-Microphone").apply { isDaemon = true; start() }
    }

    private fun stopMicrophone() {
        val record = recorder
        val thread = micThread
        recorder = null
        micThread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        thread?.interrupt()
    }

    private fun startPlayback() {
        val min = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("speaker format is not supported")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 2, 9600))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("speaker could not be initialized")
        }
        player = track
        track.play()
        playbackThread = Thread({
            while (!closed && !Thread.currentThread().isInterrupted) {
                val chunk = try { outputQueue.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                if (closed) break
                try { track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) }
                catch (_: Exception) { if (!closed) callbacks.onStatus("Audio playback stopped") }
            }
        }, "GeminiLive-Playback").apply { isDaemon = true; start() }
    }

    private fun cleanupMedia() {
        if (!cleanupOnce.compareAndSet(false, true)) return
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause(); player?.flush(); player?.stop(); player?.release() }
        player = null
        micThread?.interrupt(); micThread = null
        playbackThread?.interrupt(); playbackThread = null
        outputQueue.clear()
    }

    companion object {
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS).build()
        }
    }
}
