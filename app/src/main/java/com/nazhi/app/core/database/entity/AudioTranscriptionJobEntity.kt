package com.nazhi.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus

@Entity(
    tableName = "audio_transcription_jobs",
    indices = [
        Index(value = ["createdDate"]),
        Index(value = ["status"]),
        Index(value = ["noteId"])
    ]
)
data class AudioTranscriptionJobEntity(
    @PrimaryKey val id: String,
    val sourceApp: String,
    val backendSource: String,
    val filePath: String,
    val durationMs: Long,
    val byteSize: Long,
    val status: AudioTranscriptionJobStatus,
    val noteId: String?,
    val transcriptText: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val lastTriedAt: Long?,
    val createdAt: Long,
    val createdDate: String,
    val updatedAt: Long,
    val audioDeletedAt: Long?
)

fun AudioTranscriptionJobEntity.toModel(): AudioTranscriptionJob {
    return AudioTranscriptionJob(
        id = id,
        sourceApp = sourceApp,
        backendSource = backendSource,
        filePath = filePath,
        durationMs = durationMs,
        byteSize = byteSize,
        status = status,
        noteId = noteId,
        transcriptText = transcriptText,
        errorMessage = errorMessage,
        retryCount = retryCount,
        lastTriedAt = lastTriedAt,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        audioDeletedAt = audioDeletedAt
    )
}

fun AudioTranscriptionJob.toEntity(): AudioTranscriptionJobEntity {
    return AudioTranscriptionJobEntity(
        id = id,
        sourceApp = sourceApp,
        backendSource = backendSource,
        filePath = filePath,
        durationMs = durationMs,
        byteSize = byteSize,
        status = status,
        noteId = noteId,
        transcriptText = transcriptText,
        errorMessage = errorMessage,
        retryCount = retryCount,
        lastTriedAt = lastTriedAt,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        audioDeletedAt = audioDeletedAt
    )
}
