package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.EmbeddingRecord

@Entity(
    tableName = "embedding_records",
    indices = [
        Index(value = ["ownerType", "ownerId", "model", "chunkIndex"], unique = true),
        Index(value = ["ownerType"]),
        Index(value = ["ownerId"]),
        Index(value = ["textHash"]),
        Index(value = ["model"])
    ]
)
data class EmbeddingRecordEntity(
    @PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val chunkIndex: Int,
    val textHash: String,
    val model: String,
    val dimensions: Int,
    val precision: String,
    val vectorBlob: ByteArray,
    val vectorNorm: Float?,
    val createdAt: Long,
    val updatedAt: Long
)

fun EmbeddingRecordEntity.toModel(): EmbeddingRecord {
    return EmbeddingRecord(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        chunkIndex = chunkIndex,
        textHash = textHash,
        model = model,
        dimensions = dimensions,
        precision = precision,
        vectorBlob = vectorBlob,
        vectorNorm = vectorNorm,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun EmbeddingRecord.toEntity(): EmbeddingRecordEntity {
    return EmbeddingRecordEntity(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        chunkIndex = chunkIndex,
        textHash = textHash,
        model = model,
        dimensions = dimensions,
        precision = precision,
        vectorBlob = vectorBlob,
        vectorNorm = vectorNorm,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

