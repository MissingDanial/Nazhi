package com.nazhi.app.core.model

data class ChatSession(
    val id: String,
    val title: String,
    val memoryDigest: String? = null,
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
