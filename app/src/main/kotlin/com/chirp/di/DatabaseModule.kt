package com.chirp.di

import android.content.Context
import androidx.room.Room
import com.chirp.data.local.ChirpDatabase
import com.chirp.data.local.ConversationDao
import com.chirp.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChirpDatabase =
        Room.databaseBuilder(context, ChirpDatabase::class.java, ChirpDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(database: ChirpDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: ChirpDatabase): MessageDao = database.messageDao()
}
