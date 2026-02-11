package com.chirp.data

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao) {
    fun streamAll(): Flow<List<TranscriptEntity>> = dao.streamAll()

    suspend fun upsert(entity: TranscriptEntity) = dao.upsert(entity)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteById(itemId: String) = dao.deleteById(itemId)
}
