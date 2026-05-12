package com.nazhi.app.core.model

data class KnowledgeEntry(
    val id: String,
    val noteId: String,
    val content: String,
    val intentType: IntentType,
    val userTitle: String?,
    val userRemark: String?,
    val createdAt: Long,
    val createdDate: String,
    val confirmedAt: Long,
    val confirmedDate: String,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val sourceNoteIds: List<String> = if (noteId.isBlank()) emptyList() else listOf(noteId),
    val indexStatus: KnowledgeIndexStatus = KnowledgeIndexStatus.PENDING
)
