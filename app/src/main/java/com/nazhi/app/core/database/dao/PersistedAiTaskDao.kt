package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.PersistedAiTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersistedAiTaskDao {
    @Query("SELECT * FROM local_ai_tasks ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<PersistedAiTaskEntity>>

    @Query("SELECT * FROM local_ai_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PersistedAiTaskEntity?

    @Upsert
    suspend fun upsert(task: PersistedAiTaskEntity)

    @Query("SELECT * FROM local_ai_tasks WHERE status IN ('CREATED', 'RUNNING', 'WAITING') ORDER BY createdAt")
    suspend fun getRecoverable(): List<PersistedAiTaskEntity>

    @Query("SELECT * FROM local_ai_tasks WHERE date = :date ORDER BY createdAt DESC")
    fun observeForDate(date: String): Flow<List<PersistedAiTaskEntity>>

    @Query("SELECT * FROM local_ai_tasks WHERE date = :date AND type = :type ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestForDate(date: String, type: String): PersistedAiTaskEntity?
}
