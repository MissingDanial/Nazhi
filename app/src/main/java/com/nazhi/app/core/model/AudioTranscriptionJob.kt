package com.nazhi.app.core.model

data class AudioTranscriptionJob(
    val id: String,
    val sourceApp: String,
    val backendSource: String,
    val filePath: String,
    val durationMs: Long,
    val byteSize: Long,
    val status: AudioTranscriptionJobStatus,
    val noteId: String? = null,
    val transcriptText: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val lastTriedAt: Long? = null,
    val createdAt: Long,
    val createdDate: String,
    val updatedAt: Long,
    val audioDeletedAt: Long? = null
) {
    val canRetry: Boolean
        get() = status == AudioTranscriptionJobStatus.PENDING ||
            status == AudioTranscriptionJobStatus.FAILED

    val hasRetainedAudio: Boolean
        get() = audioDeletedAt == null && filePath.isNotBlank()
}

enum class AudioTranscriptionJobStatus {
    PENDING,
    UPLOADING,
    TRANSCRIBING,
    FAILED,
    SAVED,
    AUDIO_CLEANED
}
