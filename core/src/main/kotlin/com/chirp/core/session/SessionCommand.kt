package com.chirp.core.session

/**
 * Commands that drive the session. Every entry point — the UI, the persistent
 * notification, headset media buttons, and (Phase 2) the Wear companion via the
 * Data Layer — ultimately maps to one of these. They are funneled through
 * [com.chirp.core.session.SessionController] (on the phone, via the foreground
 * service). Kept as a serializable-friendly sealed type so the same vocabulary
 * crosses the Data Layer boundary; see [com.chirp.core.wear.WearContract].
 */
sealed interface SessionCommand {
    /** Begin (or resume into) a session for the given conversation (null = new). */
    data class Start(val conversationId: Long?) : SessionCommand

    /** Start a single listening turn now. */
    data object StartListening : SessionCommand

    /** Toggle: start listening if idle, pause if active. */
    data object ToggleListen : SessionCommand

    data object Pause : SessionCommand
    data object Resume : SessionCommand
    data object Stop : SessionCommand

    /** Stop current speech and continue the loop. */
    data object StopSpeaking : SessionCommand

    /** Inject text as if it had been spoken (type-instead-of-speak fallback). */
    data class SubmitText(val text: String) : SessionCommand
}
