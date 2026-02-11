package com.chirp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.data.ApiKeyStore
import com.chirp.data.AppContainer
import com.chirp.data.SettingsStore
import com.chirp.data.TranscriptRepository
import com.chirp.data.UserSettings
import com.chirp.realtime.SessionState
import com.chirp.realtime.VoiceSessionController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(container: AppContainer) : ViewModel() {
    private val apiKeyStore: ApiKeyStore = container.apiKeyStore
    private val settingsStore: SettingsStore = container.settingsStore
    private val transcriptRepository: TranscriptRepository = container.transcriptRepository
    private val sessionController: VoiceSessionController = container.sessionController

    val sessionState: StateFlow<SessionState> = sessionController.state
    val settings: StateFlow<UserSettings> = settingsStore.settingsFlow()
    val apiKey: StateFlow<String?> = apiKeyStore.keyFlow()

    val transcripts = transcriptRepository.streamAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    fun updateSettings(block: (UserSettings) -> UserSettings) {
        settingsStore.update(block)
    }

    fun saveApiKey(value: String) {
        apiKeyStore.setKey(value)
    }

    fun clearApiKey() {
        apiKeyStore.clearKey()
    }

    fun clearTranscripts() {
        sessionController.clearTranscripts()
    }

    fun deleteTranscript(itemId: String) {
        sessionController.deleteTranscript(itemId)
    }
}
