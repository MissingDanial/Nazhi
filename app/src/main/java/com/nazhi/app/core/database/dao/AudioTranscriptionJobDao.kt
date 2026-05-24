package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.AudioTranscriptionJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioTranscriptionJobDao {
    @Query("SELECT * FROM audio_transcription_jobs WHERE createdDate = :date ORDER BY createdAt DESC")
    fun observeJobsForDate(date: String): Flow<List<AudioTranscriptionJobEntity>>

    @Query(
        """
        SELECT * FROM audio_transcription_jobs
        WHERE status IN ('PENDING', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRetryableJobs(): List<AudioTranscriptionJobEntity>

    @Query("SELECT * FROM audio_transcription_jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): AudioTranscriptionJobEntity?

    @Query("SELECT * FROM audio_transcription_jobs WHERE noteId = :noteId")
    suspend fun getJobsForNote(noteId: String): List<AudioTranscriptionJobEntity>

    @Upsert
    suspend fun upsert(job: AudioTranscriptionJobEntity)
}
