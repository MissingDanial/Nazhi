package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSession(): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitleAndTime(id: String, title: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTime(id: String, updatedAt: Long)

    @Query(
        """
        UPDATE chat_sessions
        SET title = :title,
            messageCount = :messageCount,
            lastMessagePreview = :lastMessagePreview,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateOverview(
        id: String,
        title: String,
        messageCount: Int,
        lastMessagePreview: String,
        updatedAt: Long
    )

    @Query("UPDATE chat_sessions SET memoryDigest = :memoryDigest, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMemoryDigest(id: String, memoryDigest: String?, updatedAt: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(session: ChatSessionEntity)
}
