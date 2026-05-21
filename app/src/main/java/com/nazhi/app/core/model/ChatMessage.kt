package com.nazhi.app.core.model

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val parentMessageId: String? = null,
    val role: ChatRole,
    val content: String,
    val status: ChatMessageStatus,
    val errorMessage: String?,
    val attempt: Int = 1,
    val progressStage: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
