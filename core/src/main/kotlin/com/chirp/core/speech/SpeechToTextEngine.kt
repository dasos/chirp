package com.chirp.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over speech recognition. The Android implementation wraps
 * `android.speech.SpeechRecognizer`; a future implementation could stream audio
 * to a server-side Whisper endpoint without changing any caller.
 */
interface SpeechToTextEngine {

    /** Whether on-device recognition is currently usable. */
    suspend fun isAvailable(): Boolean

    /**
     * Starts a single listening session and emits [SttEvent]s. The returned flow
     * is cold: collecting it starts recognition; cancelling the collection stops
     * it. The flow completes after a [SttEvent.FinalResult] or a terminal
     * [SttEvent.Error].
     */
    fun listen(config: SttConfig = SttConfig()): Flow<SttEvent>
}

/** Tunables for a listening session. */
data class SttConfig(
    val languageTag: String? = null,
    /** Preferred max silence before the recognizer finalizes, in milliseconds. */
    val silenceTimeoutMs: Long = 2_000L,
    val preferOffline: Boolean = false,
)

/** Events emitted during a recognition session. */
sealed interface SttEvent {
    data object ReadyForSpeech : SttEvent
    data object BeginningOfSpeech : SttEvent

    /** Microphone amplitude (roughly dB), useful for a live waveform/pulse. */
    data class RmsChanged(val rms: Float) : SttEvent

    /** Best partial hypothesis so far. */
    data class PartialResult(val text: String) : SttEvent

    /** Final recognized text; the flow completes after this. */
    data class FinalResult(val text: String) : SttEvent

    data object EndOfSpeech : SttEvent

    /** Terminal error; the flow completes after this. */
    data class Error(val type: SttError) : SttEvent
}

enum class SttError {
    NO_MATCH,
    SPEECH_TIMEOUT,
    NETWORK,
    NETWORK_TIMEOUT,
    AUDIO,
    PERMISSION,
    BUSY,
    CLIENT,
    SERVER,
    NOT_AVAILABLE,
    UNKNOWN,
}
