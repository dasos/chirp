package com.chirp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val itemId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)
