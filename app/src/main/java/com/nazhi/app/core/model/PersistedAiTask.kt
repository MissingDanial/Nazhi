package com.nazhi.app.core.model

/** Identity is saved before submission so a lost response can be recovered by requestId. */
data class PersistedAiTask(
    val id: String,
    val type: String,
    val requestId: String,
    val bindingKey: String,
    val date: String,
    val sourceNoteIds: List<String> = emptyList(),
    val inputFingerprint: String = "",
    val backendTaskId: String? = null,
    val status: String = "CREATED",
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
