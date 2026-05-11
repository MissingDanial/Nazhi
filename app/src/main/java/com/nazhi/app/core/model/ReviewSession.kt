package com.nazhi.app.core.model

data class ReviewSession(
    val id: String,
    val date: String,
    val totalCount: Int,
    val confirmedCount: Int,
    val deletedCount: Int,
    val completedAt: Long?
)

