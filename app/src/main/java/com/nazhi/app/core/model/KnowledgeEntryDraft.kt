package com.nazhi.app.core.model

data class KnowledgeEntryDraft(
    val id: String,
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

