package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntryDraft

@Entity(
    tableName = "knowledge_entry_drafts",
    indices = [
        Index(value = ["date"]),
        Index(value = ["status"])
    ]
)
data class KnowledgeEntryDraftEntity(
    @PrimaryKey val id: String,
    val date: String,
    val title: String,
    val summary: String,
    val content: String,
    val intentType: IntentType,
    val tags: List<String>,
    val sourceNoteIds: List<String>,
    val evidenceQuotes: List<String>,
    val insight: String?,
    val confidence: Float,
    val needsReview: Boolean,
    val status: KnowledgeDraftStatus,
    val createdAt: Long,
    val updatedAt: Long
)

fun KnowledgeEntryDraftEntity.toModel(): KnowledgeEntryDraft {
    return KnowledgeEntryDraft(
        id = id,
        date = date,
        title = title,
        summary = summary,
        content = content,
        intentType = intentType,
        tags = tags,
        sourceNoteIds = sourceNoteIds,
        evidenceQuotes = evidenceQuotes,
        insight = insight,
        confidence = confidence,
        needsReview = needsReview,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun KnowledgeEntryDraft.toEntity(): KnowledgeEntryDraftEntity {
    return KnowledgeEntryDraftEntity(
        id = id,
        date = date,
        title = title,
        summary = summary,
        content = content,
        intentType = intentType,
        tags = tags,
        sourceNoteIds = sourceNoteIds,
        evidenceQuotes = evidenceQuotes,
        insight = insight,
        confidence = confidence,
        needsReview = needsReview,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

