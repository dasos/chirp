package com.chirp.core.chat

import com.chirp.core.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Talks to an OpenAI-compatible chat-completions backend over SSE. The Android
 * implementation targets OpenRouter by default; any endpoint speaking the same
 * wire format (e.g. a self-hosted LiteLLM gateway) works via the base-URL setting,
 * without affecting the [com.chirp.core.session.SessionController].
 */
interface ChatClient {

    /**
     * Streams a chat completion. The cold flow:
     *  - emits [ChatStreamEvent.Token] for each content chunk,
     *  - emits [ChatStreamEvent.Completed] when the stream finishes,
     *  - throws [ChatException] on server/transport errors (so callers can
     *    retry with backoff).
     * Cancelling the collection cancels the underlying request.
     */
    fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent>

    /** Fetches available models from `GET {base}/models`. */
    suspend fun listModels(): List<ChatModel>

    /**
     * Asks the model to summarize the given opening messages into a short
     * conversation title. Returns the raw title, or `null` on any failure so
     * callers can keep their fallback. Implementations must not throw.
     */
    suspend fun generateTitle(messages: List<Message>, model: String): String?

    /** Lightweight reachability/auth check used by Settings → "Test connection". */
    suspend fun testConnection(): ConnectionResult
}
