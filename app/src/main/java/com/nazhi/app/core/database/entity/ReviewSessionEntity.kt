package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.ReviewSession

@Entity(tableName = "review_sessions")
data class ReviewSessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    val totalCount: Int,
    val confirmedCount: Int,
    val deletedCount: Int,
    val completedAt: Long?
)

fun ReviewSessionEntity.toModel(): ReviewSession {
    return ReviewSession(
        id = id,
        date = date,
        totalCount = totalCount,
        confirmedCount = confirmedCount,
        deletedCount = deletedCount,
        completedAt = completedAt
    )
}

fun ReviewSession.toEntity(): ReviewSessionEntity {
    return ReviewSessionEntity(
        id = id,
        date = date,
        totalCount = totalCount,
        confirmedCount = confirmedCount,
        deletedCount = deletedCount,
        completedAt = completedAt
    )
}
