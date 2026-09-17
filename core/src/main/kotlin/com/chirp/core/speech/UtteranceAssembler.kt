package com.chirp.core.speech

/**
 * Owns the end-of-utterance decision for one listening turn, as pure logic.
 *
 * This replaces the old `SttTurnWindow`. That class existed because the system
 * `SpeechRecognizer` finalized sessions at its *own* internal silence threshold
 * and ignored the `EXTRA_SPEECH_INPUT_*` extras, so the app could only
 * approximate the configured silence window by restarting the recognizer and
 * stitching segments together. Now that Chirp owns the microphone, the window
 * is enforced exactly: the turn ends after [silenceTimeoutMs] of non-speech
 * since the last speech frame, and not a frame sooner or later.
 *
 * The caller feeds one voice-activity verdict per fixed-size audio frame and
 * acts on the returned [Decision]. Speech detection itself is deliberately not
 * here — this module is Android-free and unit-testable, so the VAD lives in
 * `:app`.
 *
 * Note that "speech" must come from a real voice-activity detector, never from
 * raw amplitude: amplitude cannot tell speech from wind or traffic, which is
 * the bug that made the previous implementation hold the window open forever
 * when walking outdoors.
 */
class UtteranceAssembler(
    private val startedAtMs: Long,
    private val silenceTimeoutMs: Long,
    private val frameDurationMs: Long,
    /** How long to wait for the user to start speaking before giving up. */
    private val leadInTimeoutMs: Long = silenceTimeoutMs,
    /** Total speech below this is treated as a cough/door, not an utterance. */
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    /** Hard ceiling on one utterance, to bound memory and the upload size. */
    private val maxUtteranceMs: Long = DEFAULT_MAX_UTTERANCE_MS,
) {

    /** What the caller should do after the frame it just reported. */
    enum class Decision {
        /** Keep capturing. */
        CONTINUE,

        /** The utterance is complete — transcribe what has been buffered. */
        FINISH,

        /** Nothing worth transcribing was said; end the turn as a no-match. */
        NO_SPEECH,
    }

    private var speechFrames = 0L
    private var lastSpeechAtMs: Long? = null

    /** Wall-clock of the first speech frame, or null if the user never started. */
    var speechStartedAtMs: Long? = null
        private set

    /** True once any speech at all has been detected. */
    val hasSpeech: Boolean get() = speechStartedAtMs != null

    /** Total detected speech so far, excluding the silence between words. */
    val speechMs: Long get() = speechFrames * frameDurationMs

    fun onFrame(isSpeech: Boolean, atMs: Long): Decision {
        if (isSpeech) {
            if (speechStartedAtMs == null) speechStartedAtMs = atMs
            lastSpeechAtMs = atMs
            speechFrames++
            return if (atMs - startedAtMs >= maxUtteranceMs) settle() else Decision.CONTINUE
        }

        val last = lastSpeechAtMs
            ?: return if (atMs - startedAtMs >= leadInTimeoutMs) {
                Decision.NO_SPEECH
            } else {
                Decision.CONTINUE
            }

        if (atMs - last >= silenceTimeoutMs) return settle()
        if (atMs - startedAtMs >= maxUtteranceMs) return settle()
        return Decision.CONTINUE
    }

    /** Decides the turn's outcome once capture has stopped for whatever reason. */
    fun settle(): Decision =
        if (speechMs >= minSpeechMs) Decision.FINISH else Decision.NO_SPEECH

    companion object {
        const val DEFAULT_MIN_SPEECH_MS = 250L

        /**
         * Long enough that it never truncates a real spoken turn, and kept under
         * the service's listening ceiling so capture always ends on its own
         * terms rather than being parked mid-sentence. 60s of 16kHz mono PCM16
         * is ~1.9MB of WAV — far inside the endpoint's 25MB upload cap.
         */
        const val DEFAULT_MAX_UTTERANCE_MS = 60_000L
    }
}
