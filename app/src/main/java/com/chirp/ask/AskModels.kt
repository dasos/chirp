package com.chirp.ask

enum class AskStatus {
    IDLE,
    RECORDING,
    PROCESSING,
    ERROR,
}

data class AskState(
    val status: AskStatus = AskStatus.IDLE,
    val message: String = "Hold Ask to record",
    val error: String? = null,
)
