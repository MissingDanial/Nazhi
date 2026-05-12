package com.nazhi.app.core.model

data class DayKnowledgeStatus(
    val date: String,
    val noteCount: Int,
    val pendingNoteCount: Int,
    val reviewedNoteCount: Int,
    val draftCount: Int,
    val pendingDraftCount: Int,
    val knowledgeEntryCount: Int,
    val indexedEntryCount: Int,
    val failedIndexCount: Int
) {
    val isComplete: Boolean
        get() = knowledgeEntryCount > 0 && knowledgeEntryCount == indexedEntryCount
}

