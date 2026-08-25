package com.chirp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val model: String,
    val systemPrompt: String?,
    @ColumnInfo(name = "lastMessagePreview") val lastMessagePreview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    /** Stored as the chat wire name: "system" | "user" | "assistant". */
    val role: String,
    val text: String,
    val timestamp: Long,
)
