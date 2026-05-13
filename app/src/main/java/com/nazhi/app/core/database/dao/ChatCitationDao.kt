package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.ChatCitationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatCitationDao {
    @Query(
        """
        SELECT chat_citations.* FROM chat_citations
        INNER JOIN chat_messages ON chat_messages.id = chat_citations.messageId
        WHERE chat_messages.sessionId = :sessionId
        ORDER BY chat_citations.createdAt ASC
        """
    )
    fun observeCitationsForSession(sessionId: String): Flow<List<ChatCitationEntity>>

    @Upsert
    suspend fun upsertAll(citations: List<ChatCitationEntity>)
}

