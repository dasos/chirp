package com.chirp.di

import com.chirp.core.chat.ChatClient
import com.chirp.core.session.ConversationStore
import com.chirp.core.session.SettingsProvider
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.data.repository.ConversationRepository
import com.chirp.data.settings.SettingsRepository
import com.chirp.network.OpenRouterChatClient
import com.chirp.speech.AndroidSpeechToText
import com.chirp.speech.AndroidTextToSpeech
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the :core interfaces to their Android implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindChatClient(impl: OpenRouterChatClient): ChatClient

    @Binds
    @Singleton
    abstract fun bindSpeechToText(impl: AndroidSpeechToText): SpeechToTextEngine

    @Binds
    @Singleton
    abstract fun bindTextToSpeech(impl: AndroidTextToSpeech): TextToSpeechEngine

    @Binds
    @Singleton
    abstract fun bindConversationStore(impl: ConversationRepository): ConversationStore

    @Binds
    @Singleton
    abstract fun bindSettingsProvider(impl: SettingsRepository): SettingsProvider
}
