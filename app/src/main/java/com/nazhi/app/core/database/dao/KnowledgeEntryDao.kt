package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.KnowledgeEntryEntity
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeIndexStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeEntryDao {
    @Query("SELECT * FROM knowledge_entries ORDER BY confirmedAt DESC")
    fun observeEntries(): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries ORDER BY confirmedAt DESC")
    suspend fun getEntries(): List<KnowledgeEntryEntity>

    @Query("SELECT * FROM knowledge_entries WHERE intentType = :intentType ORDER BY confirmedAt DESC")
    fun observeEntriesByIntent(intentType: IntentType): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries WHERE createdDate = :date ORDER BY confirmedAt DESC")
    fun observeEntriesForCreatedDate(date: String): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries WHERE createdDate = :date ORDER BY confirmedAt DESC")
    suspend fun getEntriesForCreatedDate(date: String): List<KnowledgeEntryEntity>

    @Query("SELECT * FROM knowledge_entries WHERE indexStatus = :status ORDER BY confirmedAt ASC")
    suspend fun getEntriesByIndexStatus(status: KnowledgeIndexStatus): List<KnowledgeEntryEntity>

    @Query(
        """
        SELECT * FROM knowledge_entries
        WHERE content LIKE '%' || :query || '%'
           OR IFNULL(userTitle, '') LIKE '%' || :query || '%'
           OR IFNULL(userRemark, '') LIKE '%' || :query || '%'
        ORDER BY confirmedAt DESC
        """
    )
    fun searchEntries(query: String): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries WHERE id = :id LIMIT 1")
    suspend fun getEntry(id: String): KnowledgeEntryEntity?

    @Query("UPDATE knowledge_entries SET indexStatus = :status WHERE id = :id")
    suspend fun updateIndexStatus(id: String, status: KnowledgeIndexStatus)

    @Upsert
    suspend fun upsert(entry: KnowledgeEntryEntity)

    @Delete
    suspend fun delete(entry: KnowledgeEntryEntity)
}
