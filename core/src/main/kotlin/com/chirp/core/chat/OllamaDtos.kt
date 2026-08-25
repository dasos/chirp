package com.chirp.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for `POST /api/chat`. */
@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessageDto>,
    val stream: Boolean = true,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
data class OllamaMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("num_ctx") val numCtx: Int? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
)

/** A single NDJSON line from the streaming `/api/chat` response. */
@Serializable
data class OllamaChatResponseChunk(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessageDto? = null,
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
)

/** Response body for `GET /api/tags`. */
@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelDto> = emptyList(),
)

@Serializable
data class OllamaModelDto(
    val name: String,
    val model: String? = null,
    val size: Long? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val details: OllamaModelDetailsDto? = null,
)

@Serializable
data class OllamaModelDetailsDto(
    val family: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

/** Response body for `GET /api/version`. */
@Serializable
data class OllamaVersionResponse(
    val version: String? = null,
)
