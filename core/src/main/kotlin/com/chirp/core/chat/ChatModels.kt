package com.chirp.core.chat

import com.chirp.core.model.Message

/** Everything the [ChatClient] needs to make one streaming chat request. */
data class ChatRequestSpec(
    val model: String,
    /** Full conversation history to send, including a leading system message if any. */
    val messages: List<Message>,
    val temperature: Double? = null,
    /**
     * Ask the server-side web-search tool to ground the reply. On OpenRouter this
     * maps to the `openrouter:web_search` server tool; generic OpenAI-compatible
     * gateways may ignore or reject it, in which case disable the setting.
     */
    val webSearch: Boolean = false,
)

/** Events produced while streaming an assistant response. */
sealed interface ChatStreamEvent {
    /** An incremental chunk of assistant text. */
    data class Token(val content: String) : ChatStreamEvent

    /** Terminal success; carries optional usage stats. */
    data class Completed(val stats: ChatStats?) : ChatStreamEvent
}

/** Optional token-usage stats reported when the stream completes. */
data class ChatStats(
    val promptTokens: Int?,
    val completionTokens: Int?,
)

/** A model advertised by the server's model-list endpoint. */
data class ChatModel(
    /** Wire identifier sent as `model` in chat requests, e.g. `anthropic/claude-sonnet-4`. */
    val id: String,
    /** Human-readable name, when the server provides one. */
    val label: String? = null,
)

/** Result of a "Test connection" attempt from Settings. */
sealed interface ConnectionResult {
    data class Success(val version: String?, val modelCount: Int) : ConnectionResult
    data class Failure(val reason: String) : ConnectionResult
}

/** Thrown for server/transport-level failures during chat (so callers can retry with backoff). */
class ChatException(message: String, cause: Throwable? = null) : Exception(message, cause)
