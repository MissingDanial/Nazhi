package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.PersistedAiTask

@Entity(
    tableName = "local_ai_tasks",
    indices = [
        Index(value = ["requestId", "bindingKey"], unique = true),
        Index(value = ["bindingKey", "status"]),
        Index(value = ["date", "type"])
    ]
)
data class PersistedAiTaskEntity(
    @PrimaryKey val id: String,
    val type: String,
    val requestId: String,
    val bindingKey: String,
    val date: String,
    val sourceNoteIds: List<String>,
    val inputFingerprint: String,
    val backendTaskId: String?,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
)

fun PersistedAiTaskEntity.toModel() = PersistedAiTask(
    id, type, requestId, bindingKey, date, sourceNoteIds, inputFingerprint,
    backendTaskId, status, errorMessage, createdAt, updatedAt
)

fun PersistedAiTask.toEntity() = PersistedAiTaskEntity(
    id, type, requestId, bindingKey, date, sourceNoteIds, inputFingerprint,
    backendTaskId, status, errorMessage, createdAt, updatedAt
)
