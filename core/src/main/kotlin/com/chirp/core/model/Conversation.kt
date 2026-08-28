package com.chirp.core.model

/** Conversation metadata (the transcript itself is a list of [Message]). */
data class Conversation(
    val id: Long = 0L,
    val title: String,
    val model: String,
    val systemPrompt: String? = null,
    val lastMessagePreview: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_TITLE = "New conversation"

        /** Derive a conversation title from the first user message. */
        fun titleFrom(firstUserMessage: String, maxLength: Int = 48): String {
            val cleaned = firstUserMessage.trim().replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) return DEFAULT_TITLE
            return if (cleaned.length <= maxLength) {
                cleaned
            } else {
                cleaned.take(maxLength).trimEnd() + "…"
            }
        }

        /** Clean up an LLM-generated title: strip quotes/markdown/punctuation and clamp length. */
        fun sanitizeTitle(raw: String, maxLength: Int = 50): String {
            var cleaned = raw.trim()
            if (cleaned.isEmpty()) return DEFAULT_TITLE
            // Strip surrounding quotes (straight or curly) and markdown emphasis.
            cleaned = cleaned
                .trim('"', '\u201C', '\u201D', '\'', '\u2018', '\u2019', '*', '_', '`')
                .trim()
            // Drop trailing punctuation.
            cleaned = cleaned.trimEnd('.', '!', '?', ':', ';', ',', '-', '—')
            cleaned = cleaned.trim().replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) return DEFAULT_TITLE
            return if (cleaned.length <= maxLength) {
                cleaned
            } else {
                cleaned.take(maxLength).trimEnd() + "…"
            }
        }
    }
}
