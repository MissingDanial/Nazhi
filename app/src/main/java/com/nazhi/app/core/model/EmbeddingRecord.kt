package com.nazhi.app.core.model

data class EmbeddingRecord(
    val id: String,
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
) {
    companion object {
        const val OWNER_KNOWLEDGE_ENTRY = "knowledge_entry"
        const val PRECISION_FLOAT32 = "float32"
    }
}

