package com.nazhi.app.core.model

data class Note(
    val id: String,
    val content: String,
    val title: String?,
    val sourceType: SourceType,
    val sourceApp: String?,
    val sourceUrl: String?,
    val audioDurationMs: Long? = null,
    val createdAt: Long,
    val createdDate: String,
    val updatedAt: Long,
    val status: NoteStatus,
    val userRemark: String?
)
