package com.chirp.core.session

import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatRequestSpec
import com.chirp.core.chat.ChatStreamEvent
import com.chirp.core.chat.ConnectionResult
import com.chirp.core.chat.OllamaException
import com.chirp.core.chat.OllamaModel
import com.chirp.core.model.Conversation
import com.chirp.core.model.Message
import com.chirp.core.model.Role
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttEvent
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.core.speech.TtsVoice
import com.chirp.core.util.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Test doubles for [SessionController] unit tests. */

class FakeChatClient(
    var tokens: List<String> = emptyList(),
    var failAlways: Boolean = false,
    var models: List<OllamaModel> = emptyList(),
) : ChatClient {
    override fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent> = flow {
        if (failAlways) throw OllamaException("boom")
        tokens.forEach { emit(ChatStreamEvent.Token(it)) }
        emit(ChatStreamEvent.Completed(null))
    }

    override suspend fun listModels(): List<OllamaModel> = models
    override suspend fun testConnection(): ConnectionResult = ConnectionResult.Success(null, models.size)
}

class FakeSpeechToText(var script: List<SttEvent> = emptyList()) : SpeechToTextEngine {
    override suspend fun isAvailable(): Boolean = true
    override fun listen(config: SttConfig): Flow<SttEvent> = flow {
        script.forEach { emit(it) }
    }
}

class FakeTextToSpeech : TextToSpeechEngine {
    val spoken = mutableListOf<String>()
    var initResult = true
    override suspend fun init(): Boolean = initResult
    override suspend fun speak(text: String, utteranceId: String) {
        spoken += text
    }
    override fun stop() = Unit
    override fun setSpeed(rate: Float) = Unit
    override fun setVoice(voiceId: String?) = Unit
    override suspend fun availableVoices(): List<TtsVoice> = emptyList()
    override fun shutdown() = Unit
}

class FakeConversationStore : ConversationStore {
    val conversations = mutableListOf<Conversation>()
    val messages = mutableListOf<Message>()
    private var convSeq = 0L
    private var msgSeq = 0L

    override suspend fun createConversation(model: String, systemPrompt: String?): Long {
        val id = ++convSeq
        conversations += Conversation(
            id = id,
            title = Conversation.DEFAULT_TITLE,
            model = model,
            systemPrompt = systemPrompt,
        )
        return id
    }

    override suspend fun getConversation(id: Long): Conversation? =
        conversations.firstOrNull { it.id == id }

    override suspend fun appendMessage(conversationId: Long, role: Role, text: String): Long {
        val id = ++msgSeq
        messages += Message(id = id, conversationId = conversationId, role = role, text = text, timestamp = id)
        return id
    }

    override suspend fun loadMessages(conversationId: Long): List<Message> =
        messages.filter { it.conversationId == conversationId }
}

class FakeSettingsProvider(var settings: SessionSettings) : SettingsProvider {
    override suspend fun current(): SessionSettings = settings
}

class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}
