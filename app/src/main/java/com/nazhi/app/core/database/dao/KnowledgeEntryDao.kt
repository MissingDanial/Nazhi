package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.KnowledgeEntryEntity
import com.nazhi.app.core.model.IntentType
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeEntryDao {
    @Query("SELECT * FROM knowledge_entries ORDER BY confirmedAt DESC")
    fun observeEntries(): Flow<List<KnowledgeEntryEntity>>

    @Query("SELECT * FROM knowledge_entries WHERE intentType = :intentType ORDER BY confirmedAt DESC")
    fun observeEntriesByIntent(intentType: IntentType): Flow<List<KnowledgeEntryEntity>>

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

    @Upsert
    suspend fun upsert(entry: KnowledgeEntryEntity)

    @Delete
    suspend fun delete(entry: KnowledgeEntryEntity)
}
