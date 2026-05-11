package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeEntry

@Entity(
    tableName = "knowledge_entries",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["intentType"]),
        Index(value = ["createdDate"]),
        Index(value = ["confirmedDate"])
    ]
)
data class KnowledgeEntryEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val content: String,
    val intentType: IntentType,
    val userTitle: String?,
    val userRemark: String?,
    val createdAt: Long,
    val createdDate: String,
    val confirmedAt: Long,
    val confirmedDate: String
)

fun KnowledgeEntryEntity.toModel(): KnowledgeEntry {
    return KnowledgeEntry(
        id = id,
        noteId = noteId,
        content = content,
        intentType = intentType,
        userTitle = userTitle,
        userRemark = userRemark,
        createdAt = createdAt,
        createdDate = createdDate,
        confirmedAt = confirmedAt,
        confirmedDate = confirmedDate
    )
}

fun KnowledgeEntry.toEntity(): KnowledgeEntryEntity {
    return KnowledgeEntryEntity(
        id = id,
        noteId = noteId,
        content = content,
        intentType = intentType,
        userTitle = userTitle,
        userRemark = userRemark,
        createdAt = createdAt,
        createdDate = createdDate,
        confirmedAt = confirmedAt,
        confirmedDate = confirmedDate
    )
}
