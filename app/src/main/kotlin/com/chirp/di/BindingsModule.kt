package com.chirp.di

import com.chirp.core.chat.ChatClient
import com.chirp.core.session.ConversationStore
import com.chirp.core.session.SettingsProvider
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.core.speech.Transcriber
import com.chirp.core.speech.Vad
import com.chirp.data.repository.ConversationRepository
import com.chirp.data.settings.SettingsRepository
import com.chirp.network.OpenRouterChatClient
import com.chirp.network.OpenRouterTranscriber
import com.chirp.speech.AndroidTextToSpeech
import com.chirp.speech.PipelineSpeechToText
import com.chirp.speech.mic.SileroVad
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
    abstract fun bindSpeechToText(impl: PipelineSpeechToText): SpeechToTextEngine

    @Binds
    @Singleton
    abstract fun bindTranscriber(impl: OpenRouterTranscriber): Transcriber

    @Binds
    @Singleton
    abstract fun bindVad(impl: SileroVad): Vad

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
