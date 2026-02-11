package com.chirp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY createdAt ASC")
    fun streamAll(): Flow<List<TranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranscriptEntity)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()

    @Query("DELETE FROM transcripts WHERE itemId = :itemId")
    suspend fun deleteById(itemId: String)
}
