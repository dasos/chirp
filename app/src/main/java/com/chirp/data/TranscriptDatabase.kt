package com.chirp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TranscriptEntity::class], version = 1, exportSchema = false)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var instance: TranscriptDatabase? = null

        fun getInstance(context: Context): TranscriptDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranscriptDatabase::class.java,
                    "chirp_transcripts.db",
                ).build().also { instance = it }
            }
        }
    }
}
