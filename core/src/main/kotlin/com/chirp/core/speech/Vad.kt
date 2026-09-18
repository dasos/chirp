package com.chirp.core.speech

/**
 * Per-frame voice activity detection: the "is the user talking right now?"
 * verdict that [UtteranceAssembler] turns into an end-of-turn decision.
 *
 * Detection itself needs a neural model and so lives in `:app`; this interface
 * is the seam. Swapping implementations is a one-line change to the Hilt
 * binding — there is deliberately no runtime flag, because a toggle with one
 * implementation behind it is dead config.
 *
 * The frame geometry lives here rather than on an implementation because
 * `MicCapture` sizes its reads from it and `UtteranceAssembler` derives its
 * whole time base from it. It is Silero v5's geometry, and an alternative
 * detector would have to accept the same frames.
 *
 * Implementations are stateful across frames — one instance handles one
 * utterance; call [reset] between turns — and are not thread-safe.
 */
interface Vad {

    /** Clears all per-utterance state. Call at the start of every turn. */
    fun reset()

    /** True when [frame] contains speech. [frame] must be [FRAME_SAMPLES] long. */
    fun isSpeech(frame: ShortArray): Boolean

    /**
     * The raw score behind the last [isSpeech] verdict, for diagnostics only.
     * It must never feed the silence decision — that is [isSpeech]'s job, and
     * it applies hysteresis this value does not.
     */
    val lastProbability: Float

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        /** Audio consumed per call: Silero v5 is fixed at 512 samples (32ms) at 16kHz. */
        const val FRAME_SAMPLES = 512

        /**
         * Samples of the *previous* frame the model also needs to see. Silero's
         * STFT pads only on the right, so the left-hand overlap is the caller's
         * to supply; see [VadInputWindow].
         */
        const val CONTEXT_SAMPLES = 64

        /** Width of the tensor handed to the model — context plus new audio. */
        const val INPUT_SAMPLES = FRAME_SAMPLES + CONTEXT_SAMPLES

        const val FRAME_DURATION_MS = FRAME_SAMPLES * 1000L / SAMPLE_RATE_HZ
    }
}
