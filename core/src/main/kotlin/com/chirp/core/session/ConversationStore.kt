package com.chirp.core.session

import com.chirp.core.model.Conversation
import com.chirp.core.model.Message
import com.chirp.core.model.Role

/**
 * Persistence boundary used by the [SessionController]. Implemented in the app
 * module by a Room-backed repository. Kept as an interface so the controller
 * stays in pure-Kotlin :core and is unit-testable with a fake.
 */
interface ConversationStore {

    /** Creates a new conversation and returns its id. */
    suspend fun createConversation(model: String, systemPrompt: String?): Long

    suspend fun getConversation(id: Long): Conversation?

    /** Appends a message and returns its id. Implementations also derive the
     *  conversation title from the first user message and bump `updatedAt`. */
    suspend fun appendMessage(conversationId: Long, role: Role, text: String): Long

    /** Sets the conversation title (used for LLM-generated titles). */
    suspend fun updateTitle(conversationId: Long, title: String)

    /** Loads the full transcript ordered oldest-first. */
    suspend fun loadMessages(conversationId: Long): List<Message>
}
