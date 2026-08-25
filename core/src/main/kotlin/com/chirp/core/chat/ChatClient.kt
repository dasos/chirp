package com.chirp.core.chat

import kotlinx.coroutines.flow.Flow

/**
 * Talks to an Ollama-compatible server. The Android implementation uses OkHttp
 * to stream NDJSON; a future implementation could target a different backend
 * without affecting the [com.chirp.core.session.SessionController].
 */
interface ChatClient {

    /**
     * Streams a chat completion. The cold flow:
     *  - emits [ChatStreamEvent.Token] for each chunk,
     *  - emits [ChatStreamEvent.Completed] when the server reports `done`,
     *  - throws [OllamaException] on server/transport errors (so callers can
     *    retry with backoff).
     * Cancelling the collection cancels the underlying request.
     */
    fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent>

    /** Fetches available models from `GET /api/tags`. */
    suspend fun listModels(): List<OllamaModel>

    /** Lightweight reachability/auth check used by Settings → "Test connection". */
    suspend fun testConnection(): ConnectionResult
}
