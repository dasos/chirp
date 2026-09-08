package com.chirp.core.speech

/**
 * Owns the app-side silence window for a single listening turn that may span
 * several recognizer sessions. The system `SpeechRecognizer` ends sessions at
 * its own internal silence threshold (the `EXTRA_SPEECH_INPUT_*` extras are
 * advisory and often ignored), so the turn-level "am I done listening?" decision
 * lives here: a turn ends only after [silenceTimeoutMs] of silence since the
 * last detected voice (or since [startedAt] if nothing was ever heard). Finalized
 * segments from successive sessions are stitched into one [accumulated] transcript.
 *
 * This is the "Option A" silence-window enforcer (consumed by
 * `AndroidSpeechToText` in :app). If a true cross-device silence floor is ever
 * required, replace the recognizer-session approach with an app-owned mic +
 * VAD pipeline ("Option B"); see the class KDoc of `AndroidSpeechToText`.
 */
class SttTurnWindow(
    private val startedAt: Long,
    private val silenceTimeoutMs: Long,
) {
    /** Finalized segments stitched so far, in chronological order. */
    var accumulated: String = ""
        private set

    private var lastVoiceAt: Long? = null

    /** True once any voice was detected (recognized text — see [onVoice]). */
    val hasVoice: Boolean get() = lastVoiceAt != null

    /**
     * Records that voice was heard at [at]; resets the silence window. Callers
     * must only invoke this for recognized text (a non-blank partial/final
     * result), never for raw mic amplitude — amplitude alone can't
     * distinguish speech from ambient noise, so treating it as voice would
     * let background noise keep the window open indefinitely.
     */
    fun onVoice(at: Long) {
        lastVoiceAt = at
    }

    /** True when silence since the last voice (or since the start) reached the window. */
    fun expired(at: Long): Boolean =
        (at - (lastVoiceAt ?: startedAt)) >= silenceTimeoutMs

    /**
     * Folds a finalized segment into [accumulated] and counts it as voice.
     * Blank segments are ignored (and do NOT reset the window). Returns the
     * stitched transcript.
     */
    fun addSegment(text: String, at: Long): String {
        val t = text.trim()
        if (t.isNotEmpty()) {
            accumulated = if (accumulated.isEmpty()) t else "$accumulated $t"
            onVoice(at)
        }
        return accumulated
    }
}