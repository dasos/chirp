package com.chirp.realtime

import com.chirp.data.SessionEntity
import com.chirp.data.SessionRepository
import com.chirp.data.TranscriptEntity
import com.chirp.data.TranscriptRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class TranscriptStore(
    private val repository: TranscriptRepository,
    private val sessionRepository: SessionRepository,
) {
    private val items = ConcurrentHashMap<String, TranscriptEntity>()
    private val currentSessionId = AtomicReference<String?>(null)

    suspend fun startSessionIfNeeded(): String {
        val now = System.currentTimeMillis()
        val existing = currentSessionId.get()
        if (!existing.isNullOrBlank()) {
            sessionRepository.touch(existing, now)
            return existing
        }
        return createSession(now)
    }

    suspend fun startNewSession(): String {
        return createSession(System.currentTimeMillis())
    }

    fun activeSessionId(): String? = currentSessionId.get()

    fun setActiveSession(sessionId: String?) {
        currentSessionId.set(sessionId)
    }

    suspend fun ensure(itemId: String, role: String, initial: String) {
        val sessionId = currentSessionId.get() ?: return
        val now = System.currentTimeMillis()
        val existing = items[itemId]
        val entity = if (existing == null) {
            TranscriptEntity(itemId, sessionId, role, initial, now, now)
        } else {
            existing.copy(text = if (initial.isNotBlank()) initial else existing.text, updatedAt = now)
        }
        items[itemId] = entity
        repository.upsert(entity)
        sessionRepository.touch(sessionId, now)
    }

    suspend fun append(itemId: String, delta: String) {
        val existing = items[itemId] ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(text = existing.text + delta, updatedAt = now)
        items[itemId] = updated
        repository.upsert(updated)
        sessionRepository.touch(existing.sessionId, now)
    }

    suspend fun finalize(itemId: String, text: String) {
        val existing = items[itemId]
        val sessionId = existing?.sessionId ?: currentSessionId.get() ?: return
        val now = System.currentTimeMillis()
        val finalText = if (text.isNotBlank()) text else existing?.text.orEmpty()
        val role = existing?.role ?: "assistant"
        val createdAt = existing?.createdAt ?: now
        val updated = TranscriptEntity(itemId, sessionId, role, finalText, createdAt, now)
        items[itemId] = updated
        repository.upsert(updated)
        sessionRepository.touch(sessionId, now)
    }

    suspend fun delete(itemId: String) {
        items.remove(itemId)
        repository.deleteById(itemId)
    }

    suspend fun deleteAll() {
        items.clear()
        repository.deleteAll()
        sessionRepository.deleteAll()
    }

    suspend fun deleteSession(sessionId: String) {
        items.entries.removeIf { it.value.sessionId == sessionId }
        repository.deleteBySession(sessionId)
        sessionRepository.deleteById(sessionId)
        if (currentSessionId.get() == sessionId) {
            currentSessionId.set(null)
        }
    }

    private suspend fun createSession(now: Long): String {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId.set(sessionId)
        sessionRepository.upsert(SessionEntity(sessionId, now, now))
        return sessionId
    }
}
