package com.chirp.data.repository

import com.chirp.core.model.Conversation
import com.chirp.core.model.Message
import com.chirp.core.model.Role
import com.chirp.core.session.ConversationStore
import com.chirp.data.local.ConversationDao
import com.chirp.data.local.ConversationEntity
import com.chirp.data.local.MessageDao
import com.chirp.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of the :core [ConversationStore], plus the
 * observable streams the UI needs. Derives the conversation title from the first
 * user message and keeps a short preview + updatedAt for the home list.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationStore {

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(conversationId: Long): Flow<List<Message>> =
        messageDao.observeByConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createConversation(model: String, systemPrompt: String?): Long {
        val now = System.currentTimeMillis()
        return conversationDao.insert(
            ConversationEntity(
                title = Conversation.DEFAULT_TITLE,
                model = model,
                systemPrompt = systemPrompt,
                lastMessagePreview = "",
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun getConversation(id: Long): Conversation? =
        conversationDao.getById(id)?.toDomain()

    override suspend fun appendMessage(conversationId: Long, role: Role, text: String): Long {
        val now = System.currentTimeMillis()
        val id = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = role.wireName,
                text = text,
                timestamp = now,
            )
        )
        // First user message becomes the conversation title.
        if (role == Role.USER && messageDao.userMessageCount(conversationId) == 1) {
            conversationDao.updateTitle(conversationId, Conversation.titleFrom(text))
        }
        conversationDao.updatePreview(conversationId, previewOf(text), now)
        return id
    }

    override suspend fun loadMessages(conversationId: Long): List<Message> =
        messageDao.getByConversation(conversationId).map { it.toDomain() }

    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)

    private fun previewOf(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(120)
}

private fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    model = model,
    systemPrompt = systemPrompt,
    lastMessagePreview = lastMessagePreview,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = Role.fromWire(role),
    text = text,
    timestamp = timestamp,
)
