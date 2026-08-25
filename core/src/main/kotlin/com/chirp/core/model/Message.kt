package com.chirp.core.model

/**
 * A single turn in a conversation. This is the domain model used by the
 * [com.chirp.core.session.SessionController]; the persistence layer (Room) and
 * the network layer (Ollama DTOs) map to/from this type.
 */
data class Message(
    val id: Long = 0L,
    val conversationId: Long = 0L,
    val role: Role,
    val text: String,
    val timestamp: Long = 0L,
)
