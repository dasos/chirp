package com.chirp.core.session

/**
 * Immutable snapshot of the conversation session. Exposed as a `StateFlow` by
 * the [SessionController] and consumed by the UI, the notification, and the
 * future Wear companion (serialized via [com.chirp.core.wear.WearContract]).
 */
data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val active: Boolean = false,
    val conversationId: Long? = null,
    val model: String = "",
    /** Live partial transcript while [SessionPhase.LISTENING]. */
    val partialTranscript: String = "",
    /**
     * True while captured speech is being transcribed — after the user stops
     * talking but before the text is available. The capture pipeline uploads
     * the utterance at this point, so the UI explains the pause instead of
     * looking stalled.
     */
    val transcribing: Boolean = false,
    /** Assistant text accumulated so far while THINKING/SPEAKING. */
    val partialResponse: String = "",
    /** Microphone amplitude for the listening waveform/pulse. */
    val rms: Float = 0f,
    /** Wall-clock millis when THINKING began, for an elapsed-time display. */
    val thinkingStartedAtMillis: Long? = null,
    /** Whether the loop will auto-listen again after speaking. */
    val autoListen: Boolean = true,
    val errorMessage: String? = null,
) {
    val isListening: Boolean get() = phase == SessionPhase.LISTENING
    val isPaused: Boolean get() = phase == SessionPhase.PAUSED
    val isSpeaking: Boolean get() = phase == SessionPhase.SPEAKING
    val isThinking: Boolean get() = phase == SessionPhase.THINKING
}
