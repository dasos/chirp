package com.chirp.realtime

import com.chirp.data.TranscriptEntity
import com.chirp.data.TranscriptRepository
import java.util.concurrent.ConcurrentHashMap

class TranscriptStore(private val repository: TranscriptRepository) {
    private val items = ConcurrentHashMap<String, TranscriptEntity>()

    suspend fun ensure(itemId: String, role: String, initial: String) {
        val now = System.currentTimeMillis()
        val existing = items[itemId]
        val entity = if (existing == null) {
            TranscriptEntity(itemId, role, initial, now, now)
        } else {
            existing.copy(text = if (initial.isNotBlank()) initial else existing.text, updatedAt = now)
        }
        items[itemId] = entity
        repository.upsert(entity)
    }

    suspend fun append(itemId: String, delta: String) {
        val existing = items[itemId] ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(text = existing.text + delta, updatedAt = now)
        items[itemId] = updated
        repository.upsert(updated)
    }

    suspend fun finalize(itemId: String, text: String) {
        val existing = items[itemId]
        val now = System.currentTimeMillis()
        val finalText = if (text.isNotBlank()) text else existing?.text.orEmpty()
        val role = existing?.role ?: "assistant"
        val createdAt = existing?.createdAt ?: now
        val updated = TranscriptEntity(itemId, role, finalText, createdAt, now)
        items[itemId] = updated
        repository.upsert(updated)
    }

    suspend fun delete(itemId: String) {
        items.remove(itemId)
        repository.deleteById(itemId)
    }

    suspend fun deleteAll() {
        items.clear()
        repository.deleteAll()
    }
}
