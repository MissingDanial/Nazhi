package com.nazhi.app.core.repository

import com.nazhi.app.core.model.EmbeddingRecord
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.ChatCitation
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatSession
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.ReviewSession
import com.nazhi.app.core.model.SemanticSearchResult
import kotlinx.coroutines.flow.Flow

interface NazhiRepository {
    fun observeNotes(): Flow<List<Note>>

    fun observeNotesForDay(startOfDay: Long, startOfNextDay: Long): Flow<List<Note>>

    fun observeNotesForDate(date: String): Flow<List<Note>>

    fun observePendingNotesBeforeDate(date: String): Flow<List<Note>>

    fun observePendingCountBeforeDate(date: String): Flow<Int>

    fun observeDaySummaries(startDate: String, endDate: String): Flow<List<DaySummary>>

    suspend fun getNote(id: String): Note?

    suspend fun getNotesByIds(ids: List<String>): List<Note>

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

    fun observeKnowledgeEntriesForDate(date: String): Flow<List<KnowledgeEntry>>

    fun observeKnowledgeDraftsForDate(date: String): Flow<List<KnowledgeEntryDraft>>

    fun observeDayKnowledgeStatus(date: String): Flow<DayKnowledgeStatus>

    fun observeKnowledgeEntriesByIntent(intentType: IntentType): Flow<List<KnowledgeEntry>>

    fun searchKnowledgeEntries(query: String): Flow<List<KnowledgeEntry>>

    suspend fun getKnowledgeEntry(id: String): KnowledgeEntry?

    suspend fun saveKnowledgeEntry(entry: KnowledgeEntry)

    suspend fun organizeNotesForDate(
        date: String,
        onProgress: (AiTaskProgress) -> Unit = {}
    ): Int

    suspend fun updateKnowledgeDraft(draft: KnowledgeEntryDraft)

    suspend fun skipKnowledgeDraft(draftId: String)

    suspend fun submitKnowledgeDraft(draftId: String): KnowledgeEntry?

    suspend fun submitAllKnowledgeDraftsForDate(date: String): Int

    suspend fun indexKnowledgeEntry(entryId: String): Boolean

    suspend fun indexPendingKnowledgeEntries(): Int

    fun observeEmbeddingCount(): Flow<Int>

    suspend fun saveEmbeddingRecord(record: EmbeddingRecord)

    suspend fun generateMissingMockEmbeddings(): Int

    suspend fun searchSimilarKnowledgeEntries(query: String, topK: Int = 5): List<SemanticSearchResult>

    fun observeChatSessions(): Flow<List<ChatSession>>

    fun observeChatMessages(sessionId: String): Flow<List<ChatMessage>>

    fun observeChatCitationsForSession(sessionId: String): Flow<List<ChatCitation>>

    suspend fun askKnowledgeQuestion(
        question: String,
        topK: Int = 5,
        onProgress: (AiTaskProgress) -> Unit = {}
    ): ChatMessage

    fun observeReviewSessions(): Flow<List<ReviewSession>>

    suspend fun getReviewSession(id: String): ReviewSession?

    suspend fun saveReviewSession(session: ReviewSession)
}
