package com.chirp.data

import kotlinx.coroutines.flow.Flow

class SessionRepository(private val dao: SessionDao) {
    fun streamAll(): Flow<List<SessionEntity>> = dao.streamAll()

    suspend fun upsert(entity: SessionEntity) = dao.upsert(entity)

    suspend fun deleteById(sessionId: String) = dao.deleteById(sessionId)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun touch(sessionId: String, updatedAt: Long) = dao.touch(sessionId, updatedAt)
}
