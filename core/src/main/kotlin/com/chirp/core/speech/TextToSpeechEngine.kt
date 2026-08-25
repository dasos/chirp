package com.chirp.core.speech

/**
 * Abstraction over speech synthesis. The Android implementation wraps
 * `android.speech.tts.TextToSpeech`; a future implementation could call a
 * server-side Piper endpoint. [speak] suspends until the utterance finishes so
 * the [com.chirp.core.session.SessionController] can speak sentence-by-sentence.
 */
interface TextToSpeechEngine {

    /** Initializes the engine. Returns true on success. Safe to call repeatedly. */
    suspend fun init(): Boolean

    /**
     * Speaks [text] and suspends until playback finishes or is stopped/cancelled.
     * Throws [TtsException] on synthesis error.
     */
    suspend fun speak(text: String, utteranceId: String)

    /** Immediately stops any in-progress and queued speech. */
    fun stop()

    /** Sets the speaking rate where 1.0 is normal. */
    fun setSpeed(rate: Float)

    /** Selects a voice by its engine-specific id, or system default when null. */
    fun setVoice(voiceId: String?)

    /** Lists installed voices for the current language. */
    suspend fun availableVoices(): List<TtsVoice>

    /** Releases engine resources. */
    fun shutdown()
}

data class TtsVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val needsNetwork: Boolean,
)

class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
