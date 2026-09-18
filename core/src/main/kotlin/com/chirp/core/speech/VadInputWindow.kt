package com.chirp.core.speech

/**
 * Builds the audio payload Silero v5 expects, one frame at a time.
 *
 * The model's STFT is a convolution that pads only on its **right**, so the 64
 * samples of left-hand overlap have to come from the caller: each run sees
 * `[64 samples of the previous frame] + [512 new ones]` = 576, and the tail of
 * that becomes the next run's context. Feeding the bare 512 is not an error —
 * the tensor's length is symbolic, so ONNX Runtime accepts it and returns a
 * number computed from three quarters of the intended spectrogram. That silent
 * near-miss is what made every utterance score as silence and no speech ever
 * reach the transcriber; see `docs/stt-vad-investigation-2026-09-17.md`.
 *
 * This is buffer arithmetic, not detection, so it lives in `:core` next to
 * [UtteranceAssembler] where it is unit-testable without ONNX or Android — the
 * same split that keeps the end-of-turn decision testable. The int16→float
 * conversion comes along because it is the other thing worth asserting on.
 *
 * Stateful across frames: one instance per utterance, [reset] between turns.
 */
class VadInputWindow {

    private val context = FloatArray(Vad.CONTEXT_SAMPLES)

    /**
     * Returns the [Vad.INPUT_SAMPLES]-wide payload for [frame] and retains its
     * tail as the next call's context.
     */
    fun next(frame: ShortArray): FloatArray {
        require(frame.size == Vad.FRAME_SAMPLES) {
            "Expected ${Vad.FRAME_SAMPLES} samples per frame, got ${frame.size}"
        }

        val input = FloatArray(Vad.INPUT_SAMPLES)
        context.copyInto(input)
        for (i in frame.indices) input[Vad.CONTEXT_SAMPLES + i] = frame[i] / 32768f
        input.copyInto(context, destinationOffset = 0, startIndex = input.size - Vad.CONTEXT_SAMPLES)
        return input
    }

    /** Drops the carried context, so the next frame starts an utterance cleanly. */
    fun reset() = context.fill(0f)
}
