package com.chirp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun streamAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun touch(sessionId: String, updatedAt: Long)
}
