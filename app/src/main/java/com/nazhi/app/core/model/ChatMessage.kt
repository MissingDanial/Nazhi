package com.nazhi.app.core.model

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatRole,
    val content: String,
    val status: ChatMessageStatus,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
)

