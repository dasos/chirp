package com.chirp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.ask.AskSessionController
import com.chirp.ask.AskState
import com.chirp.ask.AskStatus
import com.chirp.data.ApiKeyStore
import com.chirp.data.AppContainer
import com.chirp.data.SessionEntity
import com.chirp.data.SessionRepository
import com.chirp.data.SettingsStore
import com.chirp.data.TranscriptRepository
import com.chirp.data.UserSettings
import com.chirp.realtime.SessionState
import com.chirp.realtime.SessionStatus
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
    private val askController: AskSessionController = container.askController

    val sessionState: StateFlow<SessionState> = sessionController.state
    val askState: StateFlow<AskState> = askController.state
    val settings: StateFlow<UserSettings> = settingsStore.settingsFlow()
    val apiKey: StateFlow<String?> = apiKeyStore.keyFlow()

    private val selectedSessionId = MutableStateFlow<String?>(null)
    private val selectedExistingSessionId = MutableStateFlow<String?>(null)
    private val defaultSessionId = MutableStateFlow<String?>(null)

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
        if (id.isNullOrBlank()) null else all.firstOrNull { it.sessionId == id }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )

    val isViewingExistingSession: StateFlow<Boolean> = selectedExistingSessionId
        .combine(selectedSessionId) { existingId, currentId ->
            !existingId.isNullOrBlank() && existingId == currentId
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    init {
        viewModelScope.launch {
            combine(sessions, sessionState, selectedSessionId) { all, state, selected ->
                Triple(all, state, selected)
            }.collect { (all, state, selected) ->
                if (selected != null && all.none { it.sessionId == selected }) {
                    if (defaultSessionId.value == selected) {
                        defaultSessionId.value = null
                    }
                    selectedSessionId.value = null
                    selectedExistingSessionId.value = null
                    sessionController.setActiveSession(null)
                    return@collect
                }
                if (selected.isNullOrBlank() && state.status != SessionStatus.IDLE) {
                    val active = sessionController.activeSessionId() ?: return@collect
                    if (selectedExistingSessionId.value == null) {
                        defaultSessionId.value = active
                    }
                    selectedSessionId.value = active
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
        selectedExistingSessionId.value = sessionId
        sessionController.setActiveSession(sessionId)
    }

    fun deleteSession(sessionId: String) {
        sessionController.deleteSession(sessionId)
    }

    fun startNewSession() {
        viewModelScope.launch {
            val sessionId = sessionController.startNewSession()
            defaultSessionId.value = sessionId
            selectedExistingSessionId.value = null
            selectedSessionId.value = sessionId
        }
    }

    fun returnToDefaultSession() {
        val sessionId = defaultSessionId.value
        if (!sessionId.isNullOrBlank()) {
            selectedExistingSessionId.value = null
            selectedSessionId.value = sessionId
            sessionController.setActiveSession(sessionId)
            return
        }
        startNewSession()
    }

    fun beginAsk() {
        if (askState.value.status == AskStatus.PROCESSING) return
        viewModelScope.launch {
            val activeSessionId = selectedSessionId.value ?: sessionController.startNewSession().also { sessionId ->
                defaultSessionId.value = sessionId
                selectedExistingSessionId.value = null
                selectedSessionId.value = sessionId
            }
            sessionController.setActiveSession(activeSessionId)
            askController.startRecording()
        }
    }

    fun finishAsk() {
        askController.finishRecording()
    }

    fun cancelAsk() {
        askController.cancelRecording()
    }
}
