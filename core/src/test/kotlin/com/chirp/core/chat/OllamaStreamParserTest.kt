package com.chirp.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OllamaStreamParserTest {

    @Test
    fun `parses a content token chunk`() {
        val line =
            """{"model":"llama3","created_at":"2024-01-01T00:00:00Z","message":{"role":"assistant","content":"Hello"},"done":false}"""
        val event = OllamaStreamParser.parse(line)
        assertEquals(ChatStreamEvent.Token("Hello"), event)
    }

    @Test
    fun `parses the final done chunk with stats`() {
        val line =
            """{"model":"llama3","message":{"role":"assistant","content":""},"done":true,"total_duration":123,"eval_count":7,"prompt_eval_count":3}"""
        val event = OllamaStreamParser.parse(line)
        assertTrue(event is ChatStreamEvent.Completed)
        val stats = (event as ChatStreamEvent.Completed).stats
        assertEquals(123L, stats?.totalDurationNanos)
        assertEquals(7, stats?.evalCount)
        assertEquals(3, stats?.promptEvalCount)
    }

    @Test
    fun `returns null for blank lines`() {
        assertNull(OllamaStreamParser.parse(""))
        assertNull(OllamaStreamParser.parse("   "))
    }

    @Test
    fun `returns null for an empty content chunk`() {
        val line = """{"model":"llama3","message":{"role":"assistant","content":""},"done":false}"""
        assertNull(OllamaStreamParser.parse(line))
    }

    @Test
    fun `throws on an error chunk`() {
        val line = """{"error":"model 'nope' not found"}"""
        try {
            OllamaStreamParser.parse(line)
            fail("expected OllamaException")
        } catch (e: OllamaException) {
            assertEquals("model 'nope' not found", e.message)
        }
    }

    @Test
    fun `throws on malformed json`() {
        try {
            OllamaStreamParser.parse("{not json")
            fail("expected OllamaException")
        } catch (e: OllamaException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    @Test
    fun `ignores unknown fields`() {
        val line =
            """{"message":{"role":"assistant","content":"Hi","extra":true},"done":false,"surprise":42}"""
        assertEquals(ChatStreamEvent.Token("Hi"), OllamaStreamParser.parse(line))
    }
}
