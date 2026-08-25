package com.chirp.core.session

/**
 * High-level state of the hands-free loop. Rendered by the UI, the persistent
 * notification, and (Phase 2) the Wear companion.
 */
enum class SessionPhase {
    /** Session not running. */
    IDLE,

    /** Microphone open, capturing the user's speech. */
    LISTENING,

    /** Request sent, waiting for / receiving the model's response. */
    THINKING,

    /** Speaking the response aloud. */
    SPEAKING,

    /** Loop suspended by the user (or transient audio-focus loss). */
    PAUSED,

    /** A recoverable error occurred; message is spoken and shown. */
    ERROR,
}
