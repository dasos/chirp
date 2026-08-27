package com.chirp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.core.model.Conversation
import com.chirp.data.repository.ConversationRepository
import com.chirp.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        repository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val startListeningOnNewConversation: StateFlow<Boolean> =
        settingsRepository.settings
            .map { it.startListeningOnNewConversation }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteConversation(id) }
    }
}
