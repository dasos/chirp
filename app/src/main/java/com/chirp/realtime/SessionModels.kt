package com.chirp.realtime

data class SessionConfig(
    val lowBandwidth: Boolean,
    val transcribe: Boolean,
    val maxOutputTokens: Int,
    val model: String = "gpt-realtime",
    val voice: String = "verse",
)

enum class SessionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class SessionState(
    val status: SessionStatus = SessionStatus.IDLE,
    val message: String = "Idle",
    val isLive: Boolean = false,
    val error: String? = null,
)
