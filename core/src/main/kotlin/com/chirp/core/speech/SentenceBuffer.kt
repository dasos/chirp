package com.chirp.core.speech

import kotlin.math.min

/**
 * Accumulates streamed assistant tokens and yields complete sentences as soon as
 * they are confidently finished, so TTS can start speaking the first sentence
 * before the full response has arrived.
 *
 * A sentence boundary is only reported when a terminator (`.`/`!`/`?`) is
 * *followed by whitespace* — this naturally avoids splitting decimals like
 * "3.14" (the dot is followed by a digit, not whitespace) and mid-token dots.
 * Newlines are treated as hard boundaries. Abbreviations ("Dr."), single-letter
 * initials ("J. R. R.") and dotted acronyms ("e.g.", "U.S.") are guarded against.
 *
 * A length safety-valve flushes at a word boundary if the buffer grows past
 * [maxBufferedChars] without a terminator, so an unpunctuated stream still speaks.
 *
 * This class is intentionally pure (no Android, no coroutines) and is covered by
 * unit tests.
 */
class SentenceBuffer(
    private val maxBufferedChars: Int = 240,
) {
    private val buffer = StringBuilder()

    /** Appends streamed [text] and returns any sentences now ready to speak. */
    fun append(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer.append(text)
        return drain()
    }

    /** Returns and clears whatever remains (the trailing partial sentence). */
    fun flush(): String? {
        val remaining = buffer.toString().trim()
        buffer.setLength(0)
        return remaining.ifEmpty { null }
    }

    fun isEmpty(): Boolean = buffer.isBlank()

    private fun drain(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val end = findSentenceEnd()
            if (end != null) {
                emitInto(out, end)
                continue
            }
            if (buffer.length > maxBufferedChars) {
                emitInto(out, forcedCutIndex())
                continue
            }
            break
        }
        return out
    }

    private fun emitInto(out: MutableList<String>, endExclusive: Int) {
        val sentence = buffer.substring(0, endExclusive).trim()
        buffer.delete(0, endExclusive)
        if (sentence.isNotEmpty()) out += sentence
    }

    /** Index (exclusive) just after the first confirmed sentence, or null. */
    private fun findSentenceEnd(): Int? {
        var i = 0
        val n = buffer.length
        while (i < n) {
            val c = buffer[i]
            if (c == '\n') return i + 1 // hard boundary; consume the newline

            if (isTerminator(c)) {
                var j = i + 1
                while (j < n && isTerminator(buffer[j])) j++
                while (j < n && isCloser(buffer[j])) j++

                if (j >= n) return null // terminator at very end — not confirmed yet

                if (buffer[j].isWhitespace()) {
                    if (c == '.' && isAbbreviationOrInitialBefore(i)) {
                        i = j // false stop (abbreviation/initial/acronym) — keep scanning
                        continue
                    }
                    return j
                }
                i = j // e.g. "3.14" or "a.b" — terminator not followed by space
                continue
            }
            i++
        }
        return null
    }

    private fun forcedCutIndex(): Int {
        val limit = min(maxBufferedChars, buffer.length)
        for (k in limit - 1 downTo 1) {
            if (buffer[k].isWhitespace()) return k
        }
        return limit
    }

    private fun isAbbreviationOrInitialBefore(dotIndex: Int): Boolean {
        // Dotted acronym such as "e.g." / "U.S." — the char two back is also a dot.
        if (dotIndex >= 2 && buffer[dotIndex - 2] == '.') return true

        var start = dotIndex
        while (start > 0 && buffer[start - 1].isLetter()) start--
        val word = buffer.substring(start, dotIndex)
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isUpperCase()) return true // initial "J."
        return ABBREVIATIONS.contains(word.lowercase())
    }

    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？'

    private fun isCloser(c: Char): Boolean =
        c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’' || c == '»'

    companion object {
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
            "fig", "no", "approx", "dept", "gen", "gov", "inc", "ltd", "co",
        )
    }
}
