package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.KnowledgeEntryDraftEntity
import com.nazhi.app.core.model.KnowledgeDraftStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeEntryDraftDao {
    @Query("SELECT * FROM knowledge_entry_drafts WHERE date = :date ORDER BY updatedAt DESC")
    fun observeDraftsForDate(date: String): Flow<List<KnowledgeEntryDraftEntity>>

    @Query("SELECT * FROM knowledge_entry_drafts WHERE date = :date ORDER BY updatedAt DESC")
    suspend fun getDraftsForDate(date: String): List<KnowledgeEntryDraftEntity>

    @Query("SELECT * FROM knowledge_entry_drafts WHERE id = :id LIMIT 1")
    suspend fun getDraft(id: String): KnowledgeEntryDraftEntity?

    @Upsert
    suspend fun upsert(draft: KnowledgeEntryDraftEntity)

    @Upsert
    suspend fun upsertAll(drafts: List<KnowledgeEntryDraftEntity>)

    @Query("UPDATE knowledge_entry_drafts SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: KnowledgeDraftStatus, updatedAt: Long)

    @Query("DELETE FROM knowledge_entry_drafts WHERE date = :date AND status != :keepStatus")
    suspend fun deleteReplaceableDraftsForDate(date: String, keepStatus: KnowledgeDraftStatus = KnowledgeDraftStatus.CONFIRMED)
}

