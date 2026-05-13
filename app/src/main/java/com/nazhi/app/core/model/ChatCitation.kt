package com.nazhi.app.core.model

data class ChatCitation(
    val id: String,
    val messageId: String,
    val knowledgeEntryId: String,
    val sourceNoteIds: List<String>,
    val quote: String,
    val reason: String,
    val score: Float,
    val createdAt: Long
)

