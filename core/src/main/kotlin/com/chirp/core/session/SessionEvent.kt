package com.chirp.core.session

/**
 * One-off effects emitted by the [SessionController] that are not part of the
 * retained [SessionState] — e.g. haptics on listen start/stop. Delivered via a
 * `SharedFlow`; the UI handles haptics, the service can react too.
 */
sealed interface SessionEvent {
    data object ListeningStarted : SessionEvent
    data object ListeningStopped : SessionEvent
    data class UserUtterance(val text: String) : SessionEvent
    data class AssistantSentence(val text: String) : SessionEvent
    data class RecoverableError(val message: String) : SessionEvent
}
