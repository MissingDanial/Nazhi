package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.ChatSession

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

fun ChatSessionEntity.toModel(): ChatSession {
    return ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ChatSession.toEntity(): ChatSessionEntity {
    return ChatSessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

