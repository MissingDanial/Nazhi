package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.ReviewSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewSessionDao {
    @Query("SELECT * FROM review_sessions ORDER BY date DESC")
    fun observeReviewSessions(): Flow<List<ReviewSessionEntity>>

    @Query("SELECT * FROM review_sessions WHERE id = :id LIMIT 1")
    suspend fun getReviewSession(id: String): ReviewSessionEntity?

    @Upsert
    suspend fun upsert(session: ReviewSessionEntity)
}
