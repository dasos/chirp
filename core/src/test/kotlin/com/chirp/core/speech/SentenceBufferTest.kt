package com.chirp.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBufferTest {

    @Test
    fun `emits a sentence only once a terminator is followed by whitespace`() {
        val buffer = SentenceBuffer()
        // No trailing space yet -> not confirmed.
        assertTrue(buffer.append("Hello world.").isEmpty())
        // The space confirms the boundary.
        assertEquals(listOf("Hello world."), buffer.append(" "))
    }

    @Test
    fun `splits multiple sentences across chunked tokens`() {
        val buffer = SentenceBuffer()
        val emitted = mutableListOf<String>()
        for (chunk in listOf("Hello", " world", ". ", "How ", "are ", "you", "? ", "Bye")) {
            emitted += buffer.append(chunk)
        }
        assertEquals(listOf("Hello world.", "How are you?"), emitted)
        // "Bye" has no terminator yet and is flushed at the end.
        assertEquals("Bye", buffer.flush())
    }

    @Test
    fun `does not split on a decimal number`() {
        val buffer = SentenceBuffer()
        val emitted = buffer.append("Pi is about 3.14 today. ")
        assertEquals(listOf("Pi is about 3.14 today."), emitted)
    }

    @Test
    fun `does not split on common abbreviations or initials`() {
        val buffer = SentenceBuffer()
        assertTrue(buffer.append("Dr. Smith met Mr. ").isEmpty())
        val emitted = buffer.append("J. R. R. Tolkien wrote books. ")
        assertEquals(listOf("Dr. Smith met Mr. J. R. R. Tolkien wrote books."), emitted)
    }

    @Test
    fun `does not split inside a dotted acronym`() {
        val buffer = SentenceBuffer()
        val emitted = buffer.append("We use e.g. this pattern often. ")
        assertEquals(listOf("We use e.g. this pattern often."), emitted)
    }

    @Test
    fun `treats a newline as a hard boundary`() {
        val buffer = SentenceBuffer()
        val emitted = buffer.append("First line\nSecond line still going")
        assertEquals(listOf("First line"), emitted)
        assertEquals("Second line still going", buffer.flush())
    }

    @Test
    fun `keeps a trailing closing quote attached to the sentence`() {
        val buffer = SentenceBuffer()
        val emitted = buffer.append("He said \"Stop!\" Then left. ")
        assertEquals(listOf("He said \"Stop!\"", "Then left."), emitted)
    }

    @Test
    fun `flushes at a word boundary when no terminator appears within the limit`() {
        val buffer = SentenceBuffer(maxBufferedChars = 20)
        val emitted = buffer.append("word ".repeat(10)) // 50 chars, no terminator
        assertTrue("should force-emit at least one chunk", emitted.isNotEmpty())
        // Forced chunks should break on whitespace, not mid-word.
        assertTrue(emitted.all { !it.endsWith("wor") })
    }

    @Test
    fun `flush returns null when empty`() {
        val buffer = SentenceBuffer()
        buffer.append("Done. ")
        assertNull(buffer.flush())
    }
}
