package com.chirp.realtime

import com.chirp.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceSessionController(
    private val scope: CoroutineScope,
    private val realtimeClient: OpenAiRealtimeClient,
    private val settingsStore: SettingsStore,
    private val transcriptStore: TranscriptStore,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    fun start() {
        val settings = settingsStore.settingsFlow().value
        val config = SessionConfig(
            lowBandwidth = settings.lowBandwidth,
            transcribe = settings.transcribe,
            maxOutputTokens = settings.maxOutputTokens,
        )
        scope.launch {
            _state.value = SessionState(SessionStatus.CONNECTING, "Requesting session…", false)
            realtimeClient.start(config) { state ->
                _state.value = state
            }
        }
    }

    fun stop() {
        scope.launch {
            realtimeClient.stop()
            _state.value = SessionState(SessionStatus.IDLE, "Disconnected", false)
        }
    }

    fun toggle() {
        when (_state.value.status) {
            SessionStatus.CONNECTED, SessionStatus.CONNECTING -> stop()
            SessionStatus.IDLE, SessionStatus.ERROR -> start()
        }
    }

    fun clearTranscripts() {
        scope.launch { transcriptStore.deleteAll() }
    }

    fun deleteTranscript(itemId: String) {
        scope.launch { transcriptStore.delete(itemId) }
    }
}
