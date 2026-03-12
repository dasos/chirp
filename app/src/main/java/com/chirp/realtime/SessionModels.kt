package com.chirp.realtime

import com.chirp.data.TranscriptEntity

data class SessionConfig(
    val lowBandwidth: Boolean,
    val speakerphone: Boolean,
    val transcribe: Boolean,
    val maxOutputTokens: Int,
    val model: String = "gpt-realtime-mini",
    val voice: String = "verse",
    val history: List<TranscriptEntity> = emptyList(),
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
