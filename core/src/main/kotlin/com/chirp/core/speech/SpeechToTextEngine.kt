package com.chirp.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over speech recognition. The shipped implementation owns the
 * microphone directly (`AudioRecord` + voice-activity detection) and hands the
 * captured utterance to a [Transcriber]; nothing about that leaks through this
 * interface, so an on-device or streaming engine can replace it without
 * changing any caller.
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
    /**
     * Silence after the last detected speech before the turn ends, in
     * milliseconds. Enforced exactly by the capture pipeline.
     */
    val silenceTimeoutMs: Long = 2_000L,
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

    /**
     * Speech has ended and the captured audio is being transcribed. Between
     * this and [FinalResult] the pipeline is waiting on the transcription
     * backend, which the UI surfaces so the pause is explained.
     */
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
