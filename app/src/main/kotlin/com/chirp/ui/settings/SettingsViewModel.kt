package com.chirp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ConnectionResult
import com.chirp.core.chat.OllamaModel
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.core.speech.TtsVoice
import com.chirp.data.settings.AppSettings
import com.chirp.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState
    data object Testing : ConnectionUiState
    data class Success(val message: String) : ConnectionUiState
    data class Failure(val message: String) : ConnectionUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatClient: ChatClient,
    private val textToSpeech: TextToSpeechEngine,
) : ViewModel() {

    // Nullable initial so the screen can seed text fields exactly once on first load.
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _models = MutableStateFlow<List<OllamaModel>>(emptyList())
    val models: StateFlow<List<OllamaModel>> = _models.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    val connection: StateFlow<ConnectionUiState> = _connection.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun loadModels() {
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                _models.value = chatClient.listModels()
                _connection.value = ConnectionUiState.Idle
            } catch (e: Exception) {
                _connection.value = ConnectionUiState.Failure(e.message ?: "Could not load models")
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    fun loadVoices() {
        viewModelScope.launch {
            _voices.value = runCatching { textToSpeech.availableVoices() }.getOrDefault(emptyList())
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _connection.value = ConnectionUiState.Testing
            _connection.value = when (val result = chatClient.testConnection()) {
                is ConnectionResult.Success -> ConnectionUiState.Success(
                    buildString {
                        append("Connected")
                        result.version?.let { append(" · Ollama $it") }
                        append(" · ${result.modelCount} models")
                    },
                )
                is ConnectionResult.Failure -> ConnectionUiState.Failure(result.reason)
            }
        }
    }
}
