package com.assistant.gemini

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
            }
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
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                return@withContext when (code) {
                    400 -> "Request rejected. Check model/thinking compatibility and your prompt. $message"
                    401, 403 -> "API key rejected or permission denied. Check your Google AI Studio key. $message"
                    429 -> "Gemini rate limit or quota reached. Try again later or check your plan. $message"
                    else -> "Gemini API error ($code): $message"
                }
            }
            val root = JSONObject(response)
            val candidates = root.optJSONArray("candidates") ?: return@withContext "Gemini returned no answer. Try again."
            if (candidates.length() == 0) return@withContext "Gemini returned no answer. The request may have been blocked."
            val responseParts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return@withContext "Gemini returned an empty answer."
            val text = buildString {
                for (i in 0 until responseParts.length()) {
                    val part = responseParts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought", false)) append(part.optString("text"))
                }
            }.trim()
            text.ifBlank { "Gemini didn't return text for this request." }
        } catch (e: Exception) {
            "Connection failed: ${e.localizedMessage ?: "check internet and try again"}"
        } finally { connection?.disconnect() }
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

    private fun String.encodeURLPath(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
