package com.chirp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.data.ApiKeyStore
import com.chirp.data.AppContainer
import com.chirp.data.SessionEntity
import com.chirp.data.SessionRepository
import com.chirp.data.SettingsStore
import com.chirp.data.TranscriptRepository
import com.chirp.data.UserSettings
import com.chirp.realtime.SessionState
import com.chirp.realtime.VoiceSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(container: AppContainer) : ViewModel() {
    private val apiKeyStore: ApiKeyStore = container.apiKeyStore
    private val settingsStore: SettingsStore = container.settingsStore
    private val transcriptRepository: TranscriptRepository = container.transcriptRepository
    private val sessionRepository: SessionRepository = container.sessionRepository
    private val sessionController: VoiceSessionController = container.sessionController

    val sessionState: StateFlow<SessionState> = sessionController.state
    val settings: StateFlow<UserSettings> = settingsStore.settingsFlow()
    val apiKey: StateFlow<String?> = apiKeyStore.keyFlow()

    private val selectedSessionId = MutableStateFlow<String?>(null)

    val sessions: StateFlow<List<SessionEntity>> = sessionRepository.streamAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val transcripts = selectedSessionId.flatMapLatest { sessionId ->
        if (sessionId.isNullOrBlank()) flowOf(emptyList())
        else transcriptRepository.streamBySession(sessionId)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val selectedSession: StateFlow<SessionEntity?> = combine(sessions, selectedSessionId) { all, id ->
        all.firstOrNull { it.sessionId == id } ?: all.firstOrNull()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )

    init {
        viewModelScope.launch {
            sessions.collect { all ->
                val current = selectedSessionId.value
                if (current == null || all.none { it.sessionId == current }) {
                    selectedSessionId.value = all.firstOrNull()?.sessionId
                }
            }
        }
    }

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

    fun selectSession(sessionId: String) {
        selectedSessionId.value = sessionId
        sessionController.setActiveSession(sessionId)
    }

    fun deleteSession(sessionId: String) {
        sessionController.deleteSession(sessionId)
    }

    fun clearSelectedSession() {
        viewModelScope.launch {
            val sessionId = sessionController.startNewSession()
            selectedSessionId.value = sessionId
        }
    }
}
