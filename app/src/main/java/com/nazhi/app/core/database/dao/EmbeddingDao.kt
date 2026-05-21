package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.EmbeddingRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM embedding_records ORDER BY updatedAt DESC")
    fun observeEmbeddingRecords(): Flow<List<EmbeddingRecordEntity>>

    @Query("SELECT COUNT(*) FROM embedding_records")
    fun observeEmbeddingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM embedding_records
        WHERE ownerType = :ownerType
          AND model = :model
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getRecordsByOwnerType(ownerType: String, model: String): List<EmbeddingRecordEntity>

    @Query(
        """
        SELECT * FROM embedding_records
        WHERE ownerType = :ownerType
          AND ownerId = :ownerId
          AND model = :model
          AND chunkIndex = :chunkIndex
        LIMIT 1
        """
    )
    suspend fun getRecord(
        ownerType: String,
        ownerId: String,
        model: String,
        chunkIndex: Int = 0
    ): EmbeddingRecordEntity?

    @Query("DELETE FROM embedding_records WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteRecordsForOwner(ownerType: String, ownerId: String)

    @Upsert
    suspend fun upsert(record: EmbeddingRecordEntity)
}

