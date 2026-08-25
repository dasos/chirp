package com.chirp.core.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Pure parser that maps one NDJSON line from `/api/chat` to a [ChatStreamEvent].
 * Kept free of any transport concerns so it is trivially unit-testable.
 */
object OllamaStreamParser {

    /**
     * Parses a single response line.
     *  - blank line -> null (ignore)
     *  - `{"error": "..."}` -> throws [OllamaException]
     *  - `{"done": true, ...}` -> [ChatStreamEvent.Completed]
     *  - chunk with non-empty content -> [ChatStreamEvent.Token]
     *  - any other valid chunk -> null
     */
    fun parse(line: String): ChatStreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val chunk = try {
            OllamaJson.decodeFromString<OllamaChatResponseChunk>(trimmed)
        } catch (e: SerializationException) {
            throw OllamaException("Malformed response from server", e)
        }

        chunk.error?.let { throw OllamaException(it) }

        if (chunk.done) {
            return ChatStreamEvent.Completed(
                ChatStats(
                    totalDurationNanos = chunk.totalDuration,
                    evalCount = chunk.evalCount,
                    promptEvalCount = chunk.promptEvalCount,
                )
            )
        }

        val content = chunk.message?.content
        return if (!content.isNullOrEmpty()) ChatStreamEvent.Token(content) else null
    }
}
