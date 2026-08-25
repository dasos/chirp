package com.chirp.core.chat

import com.chirp.core.model.Message

/** Everything the [ChatClient] needs to make one streaming chat request. */
data class ChatRequestSpec(
    val model: String,
    /** Full conversation history to send, including a leading system message if any. */
    val messages: List<Message>,
    val temperature: Double? = null,
    val numCtx: Int? = null,
)

/** Events produced while streaming an assistant response. */
sealed interface ChatStreamEvent {
    /** An incremental chunk of assistant text. */
    data class Token(val content: String) : ChatStreamEvent

    /** Terminal success (`done: true`); carries optional generation stats. */
    data class Completed(val stats: ChatStats?) : ChatStreamEvent
}

data class ChatStats(
    val totalDurationNanos: Long?,
    val evalCount: Int?,
    val promptEvalCount: Int?,
)

/** A model advertised by `GET /api/tags`. */
data class OllamaModel(
    val name: String,
    val parameterSize: String? = null,
    val family: String? = null,
    val sizeBytes: Long? = null,
)

/** Result of a "Test connection" attempt from Settings. */
sealed interface ConnectionResult {
    data class Success(val version: String?, val modelCount: Int) : ConnectionResult
    data class Failure(val reason: String) : ConnectionResult
}

/** Thrown for Ollama-level and transport-level failures during chat. */
class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)
