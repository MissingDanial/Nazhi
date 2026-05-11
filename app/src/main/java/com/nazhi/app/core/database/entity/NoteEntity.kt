package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["createdDate"]),
        Index(value = ["status"])
    ]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val content: String,
    val title: String?,
    val sourceType: SourceType,
    val sourceApp: String?,
    val sourceUrl: String?,
    val createdAt: Long,
    val createdDate: String,
    val updatedAt: Long,
    val status: NoteStatus,
    val userRemark: String?
)

fun NoteEntity.toModel(): Note {
    return Note(
        id = id,
        content = content,
        title = title,
        sourceType = sourceType,
        sourceApp = sourceApp,
        sourceUrl = sourceUrl,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        status = status,
        userRemark = userRemark
    )
}

fun Note.toEntity(): NoteEntity {
    return NoteEntity(
        id = id,
        content = content,
        title = title,
        sourceType = sourceType,
        sourceApp = sourceApp,
        sourceUrl = sourceUrl,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        status = status,
        userRemark = userRemark
    )
}
