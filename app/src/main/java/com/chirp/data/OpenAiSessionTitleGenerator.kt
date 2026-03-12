package com.chirp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiSessionTitleGenerator(
    private val httpClient: OkHttpClient,
    private val apiKeyStore: ApiKeyStore,
) {
    suspend fun generateTitle(sourceText: String): String? {
        val apiKey = apiKeyStore.getKey()?.trim().orEmpty()
        if (apiKey.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", TITLE_MODEL)
                put("store", false)
                put("max_output_tokens", 80)
                put("input", JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "Write a concise conversation title. Return 2 to 6 words only.",
                            )
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "Conversation:\n$sourceText")
                        },
                    )
                })
                put(
                    "text",
                    JSONObject().apply {
                        put(
                            "format",
                            JSONObject().apply {
                                put("type", "json_schema")
                                put("name", "session_title")
                                put("strict", true)
                                put(
                                    "schema",
                                    JSONObject().apply {
                                        put("type", "object")
                                        put("additionalProperties", false)
                                        put(
                                            "properties",
                                            JSONObject().apply {
                                                put(
                                                    "title",
                                                    JSONObject().apply {
                                                        put("type", "string")
                                                        put("description", "A short session title in 2 to 6 words.")
                                                    },
                                                )
                                            },
                                        )
                                        put("required", JSONArray().put("title"))
                                    },
                                )
                            },
                        )
                    },
                )
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
                parseTitle(raw)
            }
        }
    }

    private fun parseTitle(raw: String): String? {
        val json = JSONObject(raw)
        val output = json.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    return sanitizeTitle(part.optString("text"))
                }
            }
        }
        return null
    }

    private fun sanitizeTitle(raw: String): String? {
        if (raw.isBlank()) return null
        val structured = runCatching { JSONObject(raw).optString("title") }.getOrDefault(raw)
        val cleaned = structured
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .trim('"')
            .take(80)
        return cleaned.ifBlank { null }
    }

    private companion object {
        private const val TITLE_MODEL = "gpt-5-nano"
    }
}
