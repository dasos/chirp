package com.chirp.realtime

import com.chirp.data.SessionEntity
import com.chirp.data.SessionRepository
import com.chirp.data.TranscriptEntity
import com.chirp.data.TranscriptRepository
import com.chirp.data.OpenAiSessionTitleGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class TranscriptStore(
    private val repository: TranscriptRepository,
    private val sessionRepository: SessionRepository,
    private val titleGenerator: OpenAiSessionTitleGenerator,
) {
    private val items = ConcurrentHashMap<String, TranscriptEntity>()
    private val currentSessionId = AtomicReference<String?>(null)
    private val titleCandidates = ConcurrentHashMap<String, String>()
    private val titleRequestsInFlight = ConcurrentHashMap.newKeySet<String>()

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
        maybeSetTitle(sessionId, finalText, now)
    }

    suspend fun delete(itemId: String) {
        items.remove(itemId)
        repository.deleteById(itemId)
    }

    suspend fun deleteAll() {
        items.clear()
        titleCandidates.clear()
        repository.deleteAll()
        sessionRepository.deleteAll()
    }

    suspend fun deleteSession(sessionId: String) {
        items.entries.removeIf { it.value.sessionId == sessionId }
        titleCandidates.remove(sessionId)
        repository.deleteBySession(sessionId)
        sessionRepository.deleteById(sessionId)
        if (currentSessionId.get() == sessionId) {
            currentSessionId.set(null)
        }
    }

    suspend fun addCompletedMessage(role: String, text: String): String {
        val sessionId = startSessionIfNeeded()
        val now = System.currentTimeMillis()
        val itemId = UUID.randomUUID().toString()
        val entity = TranscriptEntity(itemId, sessionId, role, text, now, now)
        items[itemId] = entity
        repository.upsert(entity)
        sessionRepository.touch(sessionId, now)
        maybeSetTitle(sessionId, text, now)
        return itemId
    }

    private suspend fun createSession(now: Long): String {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId.set(sessionId)
        sessionRepository.upsert(SessionEntity(sessionId, now, now))
        return sessionId
    }

    private suspend fun maybeSetTitle(sessionId: String, text: String, now: Long) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val session = sessionRepository.getById(sessionId) ?: return
        if (!session.title.isNullOrBlank()) return
        val titleSource = buildTitleSource(sessionId, trimmed)
        if (titleSource.length < MIN_TITLE_SOURCE_LENGTH) return
        if (!titleRequestsInFlight.add(sessionId)) return
        val title = try {
            titleGenerator.generateTitle(titleSource)
        } finally {
            titleRequestsInFlight.remove(sessionId)
        }
        if (title.isNullOrBlank()) return
        sessionRepository.upsert(session.copy(updatedAt = now, title = title))
    }

    private suspend fun buildTitleSource(sessionId: String, latestText: String): String {
        val existing = titleCandidates[sessionId].orEmpty()
        if (existing.split(" ").size >= 200) return existing
        val firstTranscripts = repository.getFirstBySession(sessionId, 40)
        val combined = buildString {
            if (firstTranscripts.isEmpty()) {
                append(latestText)
            } else {
                firstTranscripts.forEach { transcript ->
                    append(transcript.role)
                        .append(": ")
                        .append(transcript.text)
                        .append('\n')
                }
            }
        }
        val words = combined.replace(Regex("\\s+"), " ").trim().split(" ")
        val capped = words.take(300).joinToString(" ")
        titleCandidates[sessionId] = capped
        return capped
    }

    private companion object {
        private const val MIN_TITLE_SOURCE_LENGTH = 48
    }
}
