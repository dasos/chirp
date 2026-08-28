package com.chirp.ui.conversation

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chirp.core.model.Message
import com.chirp.core.session.SessionController
import com.chirp.core.session.SessionEvent
import com.chirp.core.session.SessionState
import com.chirp.data.repository.ConversationRepository
import com.chirp.service.ConversationService
import com.chirp.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val controller: SessionController,
    repository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Conversation id from navigation; null for a brand-new conversation. */
    private val argId: Long? = savedStateHandle.get<Long>(Routes.ARG_CONVERSATION_ID)?.takeIf { it >= 0 }
    val shouldStartListening =
        argId == null && savedStateHandle.get<Boolean>(Routes.ARG_START_LISTENING) == true

    val state: StateFlow<SessionState> = controller.state
    val events: Flow<SessionEvent> = controller.events

    // An existing conversation opened from Home always uses its route id. A screen
    // opened as "new" adopts the id created for its active session and retains it
    // after End, so typing or tapping the mic continues that conversation instead
    // of creating another one. Do not initialize from the singleton controller's
    // inactive state: it may still contain an id from a previously viewed screen.
    private val effectiveId = MutableStateFlow(argId)

    init {
        if (argId == null) {
            viewModelScope.launch {
                controller.state.collect { state ->
                    if (state.active && effectiveId.value == null) {
                        effectiveId.value = state.conversationId
                        savedStateHandle[Routes.ARG_CONVERSATION_ID] = state.conversationId
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = effectiveId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversationTitle: StateFlow<String> =
        combine(effectiveId, repository.observeConversations()) { id, list ->
            list.firstOrNull { it.id == id }?.title ?: "New conversation"
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "New conversation")

    /** Mic button: start the session (foreground) if idle, otherwise toggle listening. */
    fun onMicTap() {
        if (!state.value.active) {
            ContextCompat.startForegroundService(
                appContext,
                ConversationService.startIntent(appContext, effectiveId.value),
            )
        } else {
            sendAction(ConversationService.ACTION_PRIMARY)
        }
    }

    fun endSession() = sendAction(ConversationService.ACTION_STOP)

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!state.value.active) {
            ContextCompat.startForegroundService(
                appContext,
                ConversationService.startIntent(appContext, effectiveId.value, trimmed),
            )
        } else {
            appContext.startService(ConversationService.submitTextIntent(appContext, trimmed))
        }
    }

    private fun sendAction(action: String) {
        // Only used while a session is active, so the service is already foregrounded.
        appContext.startService(ConversationService.intent(appContext, action))
    }
}
