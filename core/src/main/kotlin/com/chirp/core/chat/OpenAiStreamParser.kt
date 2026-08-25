package com.chirp.core.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Pure parser that maps one SSE line from a streaming `chat/completions` response
 * to a [ChatStreamEvent]. Kept free of any transport concerns so it is trivially
 * unit-testable.
 */
object OpenAiStreamParser {

    /**
     * Parses a single raw line of the SSE stream.
     *  - blank line or `:`-prefixed comment/keep-alive -> null
     *  - other non-data fields (`event:`, `id:`, …) -> null
     *  - `data: [DONE]` -> [ChatStreamEvent.Completed]
     *  - chunk with `usage` -> [ChatStreamEvent.Completed] with stats
     *  - chunk with an `error` field -> throws [ChatException]
     *  - chunk with non-empty delta content -> [ChatStreamEvent.Token]
     *  - any other valid chunk -> null
     */
    fun parse(line: String): ChatStreamEvent? {
        var trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null

        trimmed = trimmed.removePrefix("data:").trim()
        if (trimmed == "[DONE]") return ChatStreamEvent.Completed(null)
        if (trimmed.isEmpty()) return null

        val chunk = try {
            ChatJson.decodeFromString<ChatCompletionChunk>(trimmed)
        } catch (e: SerializationException) {
            throw ChatException("Malformed response from server", e)
        }

        chunk.errorMessage?.let { throw ChatException(it) }

        chunk.usage?.let {
            return ChatStreamEvent.Completed(ChatStats(it.promptTokens, it.completionTokens))
        }

        val content = chunk.choices.firstOrNull()?.delta?.content
        return if (!content.isNullOrEmpty()) ChatStreamEvent.Token(content) else null
    }
}
