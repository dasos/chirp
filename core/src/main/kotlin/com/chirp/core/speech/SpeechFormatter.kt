package com.chirp.core.speech

/**
 * Converts raw assistant output into text that reads naturally when spoken
 * aloud, stripping the Markdown the LLM may decorate its replies with (bold,
 * italics, headers, bullets, code spans, links, HTML, tables, …).
 *
 * This is the on-device enforcement of the "plain spoken text" request the app
 * also sends to the backend in the system prompt — models often ignore that
 * instruction, so we sanitize at the TTS boundary regardless.
 *
 * [SentenceBuffer] splits a numbered list like "1. First\n2. Second" into the
 * utterances "1.", "First", "2.", "Second". A bare list number ("1.") is
 * therefore remembered here and fused onto the next utterance, so the list
 * reads "one, First", "two, Second" instead of a staccato "one" pause "First".
 *
 * This class is pure Kotlin/JVM (no Android, no coroutines) and covered by unit
 * tests, mirroring [SentenceBuffer].
 */
class SpeechFormatter {

    /** A bare numbered-list marker ("1.") awaiting the content of its item. */
    private var pendingListNumber: Int = -1

    /**
     * Returns the text to speak for the next streamed [raw] utterance, or an
     * empty string when there is nothing worth speaking (e.g. a lone list
     * marker that will be fused onto the next sentence).
     */
    fun toSpokenText(raw: String): String {
        val trimmed = raw.trim()

        // "1." delivered on its own (SentenceBuffer split it off) — remember it
        // and stay silent until the item's content streams in.
        val marker = ListNumberOnly.matchEntire(trimmed)
        if (marker != null) {
            pendingListNumber = marker.groupValues[1].toIntOrNull() ?: -1
            return ""
        }

        val cleaned = stripMarkdown(raw).trim()
        val number = pendingListNumber
        pendingListNumber = -1
        val prefixed = if (number > 0) "$number $cleaned" else cleaned
        return Spaces.replace(prefixed, " ").trim()
    }

    /**
     * Removes common Markdown/HTML decoration, leaving plain prose. Kept
     * package-visible for tests.
     */
    fun stripMarkdown(text: String): String {
        var out = text
        out = InlineLink.replace(out, "$1")     // [label](url) / ![alt](url) -> label/alt
        out = ReferenceLink.replace(out, "$1")  // [label] -> label
        out = HtmlTag.replace(out, "")          // <b>, <em>, …
        out = Header.replace(out, "")           // "### Heading" -> "Heading"
        out = Quote.replace(out, "")            // "> quote" -> "quote"
        out = Bullet.replace(out, "")           // "- item", "+ item" -> "item"
        out = Ordered.replace(out, "")          // "2. item" -> "item"
        out = CodeFence.replace(out, "")        // ``` / ```kotlin fence lines
        out = HorizontalRule.replace(out, "")   // "---", "***" lines
        out = Escape.replace(out, "$1")         // \* -> *  (eaten next anyway)
        out = EmphasisDelimiters.remove(out)    // **, *, __, _, ~~, ` all dropped
        out = TablePipe.replace(out, " ")       // "a | b" -> "a b"
        return out
    }

    private companion object {
        val ListNumberOnly = Regex("""\d{1,3}\s*\.""")

        val InlineLink = Regex("""!?\[([^\]]*)\]\([^)]*\)""")
        val ReferenceLink = Regex("""\[([^\]]*)\]""")
        val HtmlTag = Regex("""<[^>]+>""")
        val Header = Regex("""(?m)^\s*#{1,6}\s*""")
        val Quote = Regex("""(?m)^\s*>\s?""")
        val Bullet = Regex("""(?m)^\s*[-+]\s+""")
        val Ordered = Regex("""(?m)^\s*(\(\d{1,3}|\d{1,3})[).]\s+""")
        val CodeFence = Regex("""(?m)^\s*`{3,}[^\n]*$""")
        val HorizontalRule = Regex("""(?m)^\s*([-*_]\s*){3,}$""")
        val Escape = Regex("""\\(.)""")
        val EmphasisDelimiters = Regex("""[*_~`]""")
        val TablePipe = Regex("""\s+\|\s+""")
        val Spaces = Regex("""[ \t]+""")
    }
}