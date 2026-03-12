package com.chirp.data

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao) {
    fun streamBySession(sessionId: String): Flow<List<TranscriptEntity>> = dao.streamBySession(sessionId)
    fun streamLatest(): Flow<TranscriptEntity?> = dao.streamLatest()

    suspend fun getFirstBySession(sessionId: String, limit: Int): List<TranscriptEntity> =
        dao.getFirstBySession(sessionId, limit)

    suspend fun getRecentBySession(sessionId: String, limit: Int): List<TranscriptEntity> =
        dao.getRecentBySession(sessionId, limit)

    suspend fun upsert(entity: TranscriptEntity) = dao.upsert(entity)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteById(itemId: String) = dao.deleteById(itemId)

    suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)
}
