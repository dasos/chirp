package com.chirp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun streamBySession(sessionId: String): Flow<List<TranscriptEntity>>

    @Query(
        "SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun getFirstBySession(sessionId: String, limit: Int): List<TranscriptEntity>

    @Query(
        "SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts ORDER BY updatedAt DESC LIMIT 1")
    fun streamLatest(): Flow<TranscriptEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranscriptEntity)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()

    @Query("DELETE FROM transcripts WHERE itemId = :itemId")
    suspend fun deleteById(itemId: String)

    @Query("DELETE FROM transcripts WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
