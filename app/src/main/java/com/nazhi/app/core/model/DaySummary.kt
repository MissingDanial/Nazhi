package com.nazhi.app.core.model

data class DaySummary(
    val date: String,
    val totalCount: Int,
    val pendingCount: Int,
    val reviewedCount: Int
)
