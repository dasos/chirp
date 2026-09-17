package com.chirp.core.speech

import com.chirp.core.speech.UtteranceAssembler.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAssemblerTest {

    private val frameMs = 32L

    private fun assembler(
        silenceTimeoutMs: Long = 2_000L,
        leadInTimeoutMs: Long = 2_000L,
        minSpeechMs: Long = 250L,
        maxUtteranceMs: Long = 60_000L,
    ) = UtteranceAssembler(
        startedAtMs = 0L,
        silenceTimeoutMs = silenceTimeoutMs,
        frameDurationMs = frameMs,
        leadInTimeoutMs = leadInTimeoutMs,
        minSpeechMs = minSpeechMs,
        maxUtteranceMs = maxUtteranceMs,
    )

    /** Feeds [count] frames of the given verdict starting at [fromMs]; returns the last decision. */
    private fun UtteranceAssembler.feed(isSpeech: Boolean, count: Int, fromMs: Long): Decision {
        var last = Decision.CONTINUE
        repeat(count) { i -> last = onFrame(isSpeech, fromMs + i * frameMs) }
        return last
    }

    @Test
    fun `gives up when the user never starts speaking`() {
        val a = assembler(leadInTimeoutMs = 2_000L)
        assertEquals(Decision.CONTINUE, a.onFrame(isSpeech = false, atMs = 1_968L))
        assertEquals(Decision.NO_SPEECH, a.onFrame(isSpeech = false, atMs = 2_000L))
        assertFalse(a.hasSpeech)
    }

    @Test
    fun `ends exactly one silence window after the last speech frame`() {
        val a = assembler(silenceTimeoutMs = 2_000L)
        a.feed(isSpeech = true, count = 20, fromMs = 0L) // 640ms of speech, last frame at 608ms
        assertEquals(Decision.CONTINUE, a.onFrame(isSpeech = false, atMs = 2_607L))
        assertEquals(Decision.FINISH, a.onFrame(isSpeech = false, atMs = 2_608L))
    }

    @Test
    fun `silence between words does not end the turn`() {
        val a = assembler(silenceTimeoutMs = 2_000L)
        a.feed(isSpeech = true, count = 10, fromMs = 0L)
        // A 1.5s pause mid-sentence — shorter than the window, so keep going.
        assertEquals(Decision.CONTINUE, a.onFrame(isSpeech = false, atMs = 1_800L))
        // Speaking again resets the window from the new last-speech frame.
        a.onFrame(isSpeech = true, atMs = 1_900L)
        assertEquals(Decision.CONTINUE, a.onFrame(isSpeech = false, atMs = 3_800L))
        assertEquals(Decision.FINISH, a.onFrame(isSpeech = false, atMs = 3_900L))
    }

    @Test
    fun `a brief noise burst is not an utterance`() {
        val a = assembler(silenceTimeoutMs = 2_000L, minSpeechMs = 250L)
        a.feed(isSpeech = true, count = 3, fromMs = 0L) // 96ms — a cough, not speech
        assertEquals(Decision.NO_SPEECH, a.onFrame(isSpeech = false, atMs = 2_100L))
    }

    @Test
    fun `speech just over the minimum does count`() {
        val a = assembler(silenceTimeoutMs = 2_000L, minSpeechMs = 250L)
        a.feed(isSpeech = true, count = 8, fromMs = 0L) // 256ms
        assertEquals(Decision.FINISH, a.onFrame(isSpeech = false, atMs = 2_300L))
    }

    @Test
    fun `caps a runaway utterance and keeps what was said`() {
        val a = assembler(maxUtteranceMs = 1_000L)
        a.feed(isSpeech = true, count = 20, fromMs = 0L)
        assertEquals(Decision.FINISH, a.onFrame(isSpeech = true, atMs = 1_000L))
    }

    @Test
    fun `cap with no real speech is a no-match, not an empty upload`() {
        val a = assembler(minSpeechMs = 250L, maxUtteranceMs = 1_000L)
        a.feed(isSpeech = true, count = 2, fromMs = 0L) // 64ms only
        assertEquals(Decision.NO_SPEECH, a.onFrame(isSpeech = false, atMs = 1_000L))
    }

    @Test
    fun `tracks when speech began so the caller can apply pre-roll`() {
        val a = assembler()
        assertNull(a.speechStartedAtMs)
        a.onFrame(isSpeech = false, atMs = 100L)
        a.onFrame(isSpeech = true, atMs = 200L)
        a.onFrame(isSpeech = true, atMs = 232L)
        assertEquals(200L, a.speechStartedAtMs)
        assertTrue(a.hasSpeech)
        assertEquals(64L, a.speechMs)
    }

    @Test
    fun `settle decides the outcome when capture stops early`() {
        val spoke = assembler()
        spoke.feed(isSpeech = true, count = 10, fromMs = 0L)
        assertEquals(Decision.FINISH, spoke.settle())

        val silent = assembler()
        assertEquals(Decision.NO_SPEECH, silent.settle())
    }
}
