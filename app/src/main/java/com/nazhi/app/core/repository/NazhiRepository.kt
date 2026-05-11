package com.nazhi.app.core.repository

import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.ReviewSession
import kotlinx.coroutines.flow.Flow

interface NazhiRepository {
    fun observeNotes(): Flow<List<Note>>

    fun observeNotesForDay(startOfDay: Long, startOfNextDay: Long): Flow<List<Note>>

    fun observeNotesForDate(date: String): Flow<List<Note>>

    fun observePendingNotesBeforeDate(date: String): Flow<List<Note>>

    fun observePendingCountBeforeDate(date: String): Flow<Int>

    fun observeDaySummaries(startDate: String, endDate: String): Flow<List<DaySummary>>

    suspend fun getNote(id: String): Note?

    suspend fun saveNote(note: Note)

    suspend fun updateNoteContent(
        id: String,
        content: String,
        title: String?,
        sourceUrl: String?,
        userRemark: String?,
        updatedAt: Long
    )

    suspend fun updateNoteStatus(id: String, status: NoteStatus, updatedAt: Long)

    suspend fun softDeleteNote(id: String, updatedAt: Long)

    fun observeKnowledgeEntries(): Flow<List<KnowledgeEntry>>

    fun observeKnowledgeEntriesByIntent(intentType: IntentType): Flow<List<KnowledgeEntry>>

    fun searchKnowledgeEntries(query: String): Flow<List<KnowledgeEntry>>

    suspend fun getKnowledgeEntry(id: String): KnowledgeEntry?

    suspend fun saveKnowledgeEntry(entry: KnowledgeEntry)

    fun observeReviewSessions(): Flow<List<ReviewSession>>

    suspend fun getReviewSession(id: String): ReviewSession?

    suspend fun saveReviewSession(session: ReviewSession)
}
