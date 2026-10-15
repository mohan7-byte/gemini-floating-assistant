package com.assistant.gemini

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GeminiApiClient {
    data class Turn(val role: String, val text: String)

    suspend fun generateContent(
        prompt: String, apiKey: String, model: String, thinkingMode: String,
        history: List<Turn> = emptyList(), screenshot: Bitmap? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Add your Google AI Studio API key in settings first."
        if (prompt.isBlank() && screenshot == null) return@withContext "Type a question or attach a screenshot."
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.encodeURLPath()}:generateContent"
            connection = openConnection(endpoint, apiKey)
            val contents = JSONArray()
            history.takeLast(12).forEach { turn ->
                contents.put(JSONObject().put("role", if (turn.role == "model") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text))))
            }
            val parts = JSONArray()
            if (prompt.isNotBlank()) parts.put(JSONObject().put("text", prompt))
            if (screenshot != null) {
                val compressed = compressScreenshot(screenshot)
                parts.put(JSONObject().put("text", "The user attached a screenshot of their screen. Analyze only what is visible and answer their question."))
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(compressed, Base64.NO_WRAP))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", parts))
            val body = JSONObject().put("contents", contents)
            if (thinkingMode != "Default") {
                val budget = when (thinkingMode) { "Fast" -> 0; "Deep" -> -1; else -> 1024 }
                body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", budget)))
            }
            val response = postJson(connection, body)
            parseTextResponse(connection.responseCode, response)
        } catch (e: Exception) {
            "Connection failed: ${e.localizedMessage ?: "check internet and try again"}"
        } finally { connection?.disconnect() }
    }

    /** Sends a short WAV recording to a standard multimodal Gemini model for formatted dictation. */
    suspend fun transcribeAndFormatAudio(
        pcm16le: ByteArray,
        apiKey: String,
        model: String = "gemini-3.8-flash"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Add your Google AI Studio API key in settings first."
        if (pcm16le.size < 16000) return@withContext "Couldn't transcribe: no speech was captured."
        var connection: HttpURLConnection? = null
        try {
            val wav = pcmToWav(pcm16le, 16000)
            val prompt = """
                Transcribe the attached speech and return only the text the user intended to type.
                Clean up punctuation, capitalization, grammar, and obvious disfluencies. Preserve the original language, meaning, names, numbers, and intent. Do not answer the spoken request or add commentary. Use paragraphs or bullets only when the speaker clearly intended them.
            """.trimIndent()
            val audioPart = JSONObject()
                .put("inline_data", JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(audioPart)
            // Gemini 3.x models do not accept legacy sampling parameters such as temperature.
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.encodeURLPath()}:generateContent"
            connection = openConnection(endpoint, apiKey)
            val response = postJson(connection, body)
            parseTextResponse(connection.responseCode, response)
        } catch (e: Exception) {
            "Couldn't transcribe: ${e.localizedMessage ?: "check the API key and network"}"
        } finally { connection?.disconnect() }
    }

    private fun openConnection(endpoint: String, apiKey: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
        }

    private fun postJson(connection: HttpURLConnection, body: JSONObject): String {
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun parseTextResponse(status: Int, response: String): String {
        if (status !in 200..299) {
            val message = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
            return when (status) {
                400 -> "Request rejected. Check model/API compatibility. $message"
                401, 403 -> "API key rejected or permission denied. Check your Google AI Studio key. $message"
                429 -> "Gemini rate limit or quota reached. Try later or check your plan. $message"
                else -> "Gemini API error ($status): $message"
            }
        }
        val candidates = JSONObject(response).optJSONArray("candidates") ?: return "Gemini returned no answer."
        if (candidates.length() == 0) return "Gemini returned no answer. The request may have been blocked."
        val responseParts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return "Gemini returned an empty answer."
        return buildString {
            for (i in 0 until responseParts.length()) {
                val part = responseParts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }.trim().ifBlank { "Gemini didn't return text for this request." }
    }

    private fun compressScreenshot(bitmap: Bitmap): ByteArray {
        val maxDimension = 1280
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, output)
            if (scaled !== bitmap) scaled.recycle()
            output.toByteArray()
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).array()
        return header + pcm
    }

    private fun String.encodeURLPath(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
