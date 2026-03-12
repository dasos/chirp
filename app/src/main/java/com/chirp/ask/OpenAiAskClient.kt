package com.chirp.ask

import android.content.Context
import com.chirp.data.ApiKeyStore
import com.chirp.data.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OpenAiAskClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val apiKeyStore: ApiKeyStore,
) {
    suspend fun transcribe(audioFile: File): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getKey()?.trim().orEmpty()
        if (apiKey.isBlank()) return@withContext null

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", TRANSCRIBE_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use null
            JSONObject(raw).optString("text").trim().ifBlank { null }
        }
    }

    suspend fun generateReply(
        history: List<TranscriptEntity>,
        userText: String,
        maxOutputTokens: Int,
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getKey()?.trim().orEmpty()
        if (apiKey.isBlank()) return@withContext null

        val historyText = buildHistory(history)
        val prompt = buildString {
            if (historyText.isNotBlank()) {
                append("Conversation so far:\n")
                append(historyText)
                append("\n\n")
            }
            append("Latest user message:\n")
            append(userText)
        }

        val payload = JSONObject().apply {
            put("model", TEXT_MODEL)
            put("store", false)
            put("max_output_tokens", maxOutputTokens.coerceIn(80, 500))
            put("instructions", "You are a friendly voice assistant. Keep replies concise.")
            put("input", prompt)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use null
            parseOutputText(raw)
        }
    }

    suspend fun synthesize(text: String): File? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getKey()?.trim().orEmpty()
        if (apiKey.isBlank()) return@withContext null

        val payload = JSONObject().apply {
            put("model", TTS_MODEL)
            put("voice", VOICE)
            put("input", text)
            put("instructions", "Speak naturally and clearly.")
            put("response_format", "mp3")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val outputFile = File.createTempFile("chirp-ask-reply-", ".mp3", context.cacheDir)
            response.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@use null
            outputFile
        }
    }

    private fun buildHistory(history: List<TranscriptEntity>): String {
        return history
            .sortedBy { it.createdAt }
            .joinToString("\n") { "${it.role}: ${it.text}" }
            .take(MAX_HISTORY_CHARS)
    }

    private fun parseOutputText(raw: String): String? {
        val json = JSONObject(raw)
        val output = json.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    return part.optString("text").trim().ifBlank { null }
                }
            }
        }
        return null
    }

    private companion object {
        private const val TRANSCRIBE_MODEL = "gpt-4o-mini-transcribe"
        private const val TEXT_MODEL = "gpt-5-mini"
        private const val TTS_MODEL = "gpt-4o-mini-tts"
        private const val VOICE = "verse"
        private const val MAX_HISTORY_CHARS = 6_000
    }
}
