package com.chirp.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared JSON configuration for OpenAI-compatible request/response (de)serialization. */
val ChatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

/** Request body for `POST {base}/chat/completions`. */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val tools: List<WireTool>? = null,
)

@Serializable
data class WireMessage(
    val role: String,
    val content: String,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean,
)

/** Server-side tools; on OpenRouter, `openrouter:web_search` enables grounded replies. */
@Serializable
data class WireTool(
    val type: String,
)

/** One `data:` payload from the streaming response. */
@Serializable
data class ChatCompletionChunk(
    val choices: List<WireChoice> = emptyList(),
    val usage: WireUsage? = null,
    /** Decoded opaquely: providers use either `"message"` or `{ "message": ... }`. */
    val error: JsonElement? = null,
) {
    val errorMessage: String?
        get() = when (val e = error) {
            is JsonPrimitive -> e.content.takeIf { it.isNotBlank() }
            is JsonObject ->
                (e["message"] as? JsonPrimitive)?.content ?: e.toString()
            else -> null
        }
}

@Serializable
data class WireChoice(
    val delta: WireDelta = WireDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** Non-streaming response: `choices[0].message.content`. */
@Serializable
data class ChatCompletionResponse(
    val choices: List<WireResponseChoice> = emptyList(),
)

@Serializable
data class WireResponseChoice(
    val message: WireResponseMessage = WireResponseMessage(),
)

@Serializable
data class WireResponseMessage(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class WireDelta(
    val content: String? = null,
    val role: String? = null,
)

@Serializable
data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)

/** Response body for `GET {base}/models`. */
@Serializable
data class ModelListResponse(
    val data: List<WireModel> = emptyList(),
)

@Serializable
data class WireModel(
    val id: String,
    val name: String? = null,
)
