package com.chirp.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the 576-sample contract. Feeding Silero the bare 512-sample frame is
 * accepted silently by ONNX Runtime and scores real speech as silence, so
 * nothing downstream can catch a regression here — only these asserts can.
 *
 * This covers the framing, not the model's numbers: it cannot catch a wrong
 * sample rate or a bad asset. That check is the offline script recorded in
 * `docs/stt-vad-investigation-2026-09-17.md`.
 */
class VadInputWindowTest {

    private fun frame(value: Short) = ShortArray(Vad.FRAME_SAMPLES) { value }

    @Test
    fun `the payload is context plus one frame`() {
        assertEquals(576, VadInputWindow().next(frame(1000)).size)
    }

    @Test
    fun `the first frame of an utterance has no context to carry`() {
        val input = VadInputWindow().next(frame(1000))
        repeat(Vad.CONTEXT_SAMPLES) { assertEquals(0f, input[it], 0f) }
        assertEquals(1000 / 32768f, input[Vad.CONTEXT_SAMPLES], 0f)
    }

    @Test
    fun `each frame carries the tail of the one before it`() {
        val window = VadInputWindow()
        window.next(frame(1000))
        val second = window.next(frame(2000))

        // The leading 64 are the previous frame, the rest is the new one.
        repeat(Vad.CONTEXT_SAMPLES) { assertEquals(1000 / 32768f, second[it], 0f) }
        assertEquals(2000 / 32768f, second[Vad.CONTEXT_SAMPLES], 0f)
        assertEquals(2000 / 32768f, second[second.size - 1], 0f)
    }

    @Test
    fun `reset drops the context so a turn does not inherit the last one`() {
        val window = VadInputWindow()
        window.next(frame(1000))
        window.reset()

        val afterReset = window.next(frame(2000))
        repeat(Vad.CONTEXT_SAMPLES) { assertEquals(0f, afterReset[it], 0f) }
    }

    @Test
    fun `samples are scaled to the range the model expects`() {
        val input = VadInputWindow().next(ShortArray(Vad.FRAME_SAMPLES).also {
            it[0] = Short.MIN_VALUE
            it[1] = Short.MAX_VALUE
        })
        assertEquals(-1f, input[Vad.CONTEXT_SAMPLES], 0f)
        assertEquals(0.99997f, input[Vad.CONTEXT_SAMPLES + 1], 1e-5f)
    }
}
