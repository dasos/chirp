package com.chirp.core.speech

/** A mono PCM 16-bit clip captured for one utterance. */
data class PcmClip(
    val samples: ShortArray,
    val sampleRateHz: Int,
) {
    val durationMs: Long get() = samples.size * 1000L / sampleRateHz

    // ShortArray uses identity equals/hashCode, so data-class semantics need help.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmClip) return false
        return sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRateHz
}

/**
 * Turns a captured utterance into text. Separated from [SpeechToTextEngine] so
 * the microphone/VAD half of the pipeline is independent of the ASR backend:
 * the default implementation posts the clip to an OpenAI-compatible
 * `/audio/transcriptions` endpoint, but an on-device model could be dropped in
 * here without touching capture, and a streaming engine could later supply
 * live partial results alongside it.
 */
interface Transcriber {

    /**
     * Transcribes [clip], returning the recognized text (blank if nothing was
     * recognized). Throws [TranscriptionException] on failure.
     */
    suspend fun transcribe(clip: PcmClip, languageTag: String? = null): String
}

/** Failure while transcribing; [error] maps onto the existing STT error surface. */
class TranscriptionException(
    val error: SttError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
