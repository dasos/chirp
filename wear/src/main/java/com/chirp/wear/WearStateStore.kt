package com.chirp.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WearStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("chirp_wear", Context.MODE_PRIVATE)
    private val stateFlow = MutableStateFlow(load())

    fun state(): StateFlow<WearState> = stateFlow

    fun updateStatus(status: String, isLive: Boolean) {
        val next = stateFlow.value.copy(status = status, isLive = isLive)
        persist(next)
        stateFlow.value = next
    }

    fun updateTranscript(text: String) {
        val next = stateFlow.value.copy(lastTranscript = text)
        persist(next)
        stateFlow.value = next
    }

    private fun load(): WearState {
        return WearState(
            status = prefs.getString(KEY_STATUS, "Idle") ?: "Idle",
            isLive = prefs.getBoolean(KEY_LIVE, false),
            lastTranscript = prefs.getString(KEY_TRANSCRIPT, "") ?: "",
        )
    }

    private fun persist(state: WearState) {
        prefs.edit()
            .putString(KEY_STATUS, state.status)
            .putBoolean(KEY_LIVE, state.isLive)
            .putString(KEY_TRANSCRIPT, state.lastTranscript)
            .apply()
    }

    companion object {
        private const val KEY_STATUS = "status"
        private const val KEY_LIVE = "live"
        private const val KEY_TRANSCRIPT = "transcript"
    }
}

data class WearState(
    val status: String,
    val isLive: Boolean,
    val lastTranscript: String,
)
