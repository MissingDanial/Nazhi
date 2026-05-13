package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: ChatRole,
    val content: String,
    val status: ChatMessageStatus,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun ChatMessageEntity.toModel(): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        status = status,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ChatMessage.toEntity(): ChatMessageEntity {
    return ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        status = status,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

