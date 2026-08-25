package com.chirp.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiStreamParserTest {

    @Test
    fun `parses a content token chunk`() {
        val line =
            """data: {"id":"x","choices":[{"delta":{"content":"Hello"}}]}"""
        val event = OpenAiStreamParser.parse(line)
        assertEquals(ChatStreamEvent.Token("Hello"), event)
    }

    @Test
    fun `parses the usage chunk as completed`() {
        val line =
            """data: {"id":"x","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34}}"""
        val event = OpenAiStreamParser.parse(line)
        assertTrue(event is ChatStreamEvent.Completed)
        val stats = (event as ChatStreamEvent.Completed).stats
        assertEquals(12, stats?.promptTokens)
        assertEquals(34, stats?.completionTokens)
    }

    @Test
    fun `parses data done as completed without stats`() {
        val event = OpenAiStreamParser.parse("data: [DONE]")
        assertEquals(ChatStreamEvent.Completed(null), event)
    }

    @Test
    fun `returns null for blank lines sse comments and non-data fields`() {
        assertNull(OpenAiStreamParser.parse(""))
        assertNull(OpenAiStreamParser.parse("   "))
        assertNull(OpenAiStreamParser.parse(": OPENROUTER PROCESSING"))
        assertNull(OpenAiStreamParser.parse("event: ping"))
    }

    @Test
    fun `returns null for role-only and finish-only chunks`() {
        assertNull(
            OpenAiStreamParser.parse(
                """data: {"choices":[{"delta":{"role":"assistant"}}]}"""
            )
        )
        assertNull(
            OpenAiStreamParser.parse(
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
            )
        )
    }

    @Test
    fun `returns null for empty delta content`() {
        val line = """data: {"choices":[{"delta":{"content":""}}]}"""
        assertNull(OpenAiStreamParser.parse(line))
    }

    @Test
    fun `throws on a string error payload`() {
        val line = """data: {"error":"rate limited"}"""
        try {
            OpenAiStreamParser.parse(line)
            fail("expected ChatException")
        } catch (e: ChatException) {
            assertEquals("rate limited", e.message)
        }
    }

    @Test
    fun `throws on an object error payload`() {
        val line = """data: {"error":{"message":"model not found","code":404}}"""
        try {
            OpenAiStreamParser.parse(line)
            fail("expected ChatException")
        } catch (e: ChatException) {
            assertEquals("model not found", e.message)
        }
    }

    @Test
    fun `throws on malformed json`() {
        try {
            OpenAiStreamParser.parse("""data: {not json""")
            fail("expected ChatException")
        } catch (e: ChatException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    @Test
    fun `ignores unknown fields`() {
        val line =
            """data: {"id":"x","model":"m","surprise":42,"choices":[{"delta":{"content":"Hi"},"index":0}]}"""
        assertEquals(ChatStreamEvent.Token("Hi"), OpenAiStreamParser.parse(line))
    }
}
