package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.ChatCitation

@Entity(
    tableName = "chat_citations",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["knowledgeEntryId"])
    ]
)
data class ChatCitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val knowledgeEntryId: String,
    val sourceNoteIds: List<String>,
    val quote: String,
    val reason: String,
    val score: Float,
    val createdAt: Long
)

fun ChatCitationEntity.toModel(): ChatCitation {
    return ChatCitation(
        id = id,
        messageId = messageId,
        knowledgeEntryId = knowledgeEntryId,
        sourceNoteIds = sourceNoteIds,
        quote = quote,
        reason = reason,
        score = score,
        createdAt = createdAt
    )
}

fun ChatCitation.toEntity(): ChatCitationEntity {
    return ChatCitationEntity(
        id = id,
        messageId = messageId,
        knowledgeEntryId = knowledgeEntryId,
        sourceNoteIds = sourceNoteIds,
        quote = quote,
        reason = reason,
        score = score,
        createdAt = createdAt
    )
}

