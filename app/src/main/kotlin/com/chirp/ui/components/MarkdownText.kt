package com.chirp.ui.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.isSpecified

/**
 * A tiny, dependency-free Markdown renderer for chat bubbles. It keeps the raw
 * assistant text (which [com.chirp.core.speech.SpeechFormatter] strips for TTS)
 * while giving the transcript a bit of structure: bold, italic, strikethrough,
 * `inline code`, clickable links, headers, bullets and block quotes.
 *
 * Rendering is deliberately *best-effort*: streaming partial responses render
 * as they arrive, so a `**bold**` split across stream chunks may briefly show
 * raw markers until it completes.
 */
@Suppress("DEPRECATION") // ClickableText is the simplest stable clickable-annotation API here
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    // ClickableText (Foundation) doesn't resolve LocalContentColor the way material3.Text
    // does, so an unspecified style color falls back to hardcoded black (unreadable on
    // dark bubbles). Resolve the ambient content color ourselves, honoring an explicit one.
    val contentColor = LocalContentColor.current
    val mergedStyle = remember(style, contentColor) {
        if (style.color.isSpecified) style else style.copy(color = contentColor)
    }
    val annotated = remember(text, mergedStyle, colors) { buildMarkdown(text, mergedStyle, colors) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = mergedStyle,
    ) { offset ->
        annotated.getStringAnnotations(LinkKey, offset, offset)
            .firstOrNull()
            ?.let { runCatching { uriHandler.openUri(it.item) } }
    }
}

private const val LinkKey = "markdown.link"

private fun buildMarkdown(raw: String, baseStyle: TextStyle, colors: ColorScheme): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var first = true
    for (line in raw.lines()) {
        if (!first) builder.append("\n")
        first = false
        appendLine(builder, line, baseStyle, colors)
    }
    return builder.toAnnotatedString()
}

private fun appendLine(builder: AnnotatedString.Builder, line: String, baseStyle: TextStyle, colors: ColorScheme) {
    val trimmed = line.trimStart()
    var content = trimmed
    var extra: SpanStyle? = null

    when {
        trimmed.startsWith("```") -> return   // fence markers are skipped
        HorizontalRule.matches(trimmed) -> return
        Header.containsMatchIn(trimmed) -> {
            content = Header.replace(trimmed, "")
            extra = SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.15f)
        }
        Quote.containsMatchIn(trimmed) -> {
            content = Quote.replace(trimmed, "")
            extra = SpanStyle(fontStyle = FontStyle.Italic)
        }
        Bullet.containsMatchIn(trimmed) -> {
            content = Bullet.replace(trimmed, "•  ")
        }
        // Ordered lists ("1. ", "2) ") are kept as-is — they already read well.
    }

    appendInline(builder, content, colors, extra)
}

private fun appendInline(
    builder: AnnotatedString.Builder,
    content: String,
    colors: ColorScheme,
    extra: SpanStyle?,
) {
    var cursor = 0
    for (match in InlineToken.findAll(content)) {
        if (match.range.first > cursor) {
            appendStyled(builder, content.substring(cursor, match.range.first), extra, null)
        }
        appendToken(builder, match.value, colors, extra)
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        appendStyled(builder, content.substring(cursor), extra, null)
    }
}

private fun appendToken(
    builder: AnnotatedString.Builder,
    token: String,
    colors: ColorScheme,
    extra: SpanStyle?,
) {
    when {
        token.startsWith("**") ->
            appendStyled(builder, token.removeSurrounding("**"), extra, SpanStyle(fontWeight = FontWeight.Bold))
        token.startsWith("__") ->
            appendStyled(builder, token.removeSurrounding("__"), extra, SpanStyle(fontWeight = FontWeight.Bold))
        token.startsWith("~~") ->
            appendStyled(builder, token.removeSurrounding("~~"), extra, SpanStyle(textDecoration = TextDecoration.LineThrough))
        token.startsWith("`") ->
            appendStyled(builder, token.removeSurrounding("`"), extra, SpanStyle(fontFamily = FontFamily.Monospace))
        token.startsWith("*") ->
            appendStyled(builder, token.removeSurrounding("*"), extra, SpanStyle(fontStyle = FontStyle.Italic))
        token.startsWith("_") ->
            appendStyled(builder, token.removeSurrounding("_"), extra, SpanStyle(fontStyle = FontStyle.Italic))
        token.startsWith("[") || token.startsWith("![") -> {
            val open = token.indexOf('[')
            val close = token.indexOf(']', open)
            val paren = token.indexOf('(', close)
            val label = token.substring(open + 1, close)
            val url = token.substring(paren + 1, token.length - 1)
            val start = builder.length
            appendStyled(builder, label, extra, SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
            if (url.isNotBlank()) builder.addStringAnnotation(LinkKey, url, start, builder.length)
        }
        else -> appendStyled(builder, token, extra, null)
    }
}

private fun appendStyled(builder: AnnotatedString.Builder, text: String, extra: SpanStyle?, span: SpanStyle?) {
    if (text.isEmpty()) return
    val merged = when {
        extra == null -> span
        span == null -> extra
        else -> extra.merge(span)
    }
    if (merged == null) {
        builder.append(text)
    } else {
        val start = builder.length
        builder.append(text)
        builder.addStyle(merged, start, builder.length)
    }
}

private val InlineToken = Regex(
    """(\*\*[^*\n]+\*\*|__[^_\n]+__|~~[^~\n]+~~|`[^`\n]+`|!?\[[^\n]+?\]\([^)\n]+?\)|\*[^*\n]+\*|_[^_\n]+_)""",
)

private val Header = Regex("""^\s*#{1,6}\s*""")
private val Quote = Regex("""^\s*>\s?""")
private val Bullet = Regex("""^\s*[-*+]\s+""")
private val HorizontalRule = Regex("""^\s*([-*_]\s*){3,}$""")

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    MaterialTheme {
        MarkdownText(
            text = "**Hello** *world*!\n\n" +
                "## Let me explain\n\n" +
                "- First item\n" +
                "- Second **important** item\n\n" +
                "> As I always say: keep it simple.\n\n" +
                "See the [Android docs](https://developer.android.com) for more.\n" +
                "Run `installDebug` to deploy.\n\n" +
                "We ~~struck out~~ fixed it.",
        )
    }
}