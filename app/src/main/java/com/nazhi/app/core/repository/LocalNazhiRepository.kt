package com.nazhi.app.core.repository

import com.nazhi.app.core.database.dao.KnowledgeEntryDao
import com.nazhi.app.core.database.dao.NoteDao
import com.nazhi.app.core.database.dao.ReviewSessionDao
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.ReviewSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalNazhiRepository(
    private val noteDao: NoteDao,
    private val knowledgeEntryDao: KnowledgeEntryDao,
    private val reviewSessionDao: ReviewSessionDao
) : NazhiRepository {
    override fun observeNotes(): Flow<List<Note>> {
        return noteDao.observeNotes().map { notes -> notes.map { it.toModel() } }
    }

    override fun observeNotesForDay(startOfDay: Long, startOfNextDay: Long): Flow<List<Note>> {
        return noteDao.observeNotesForDay(startOfDay, startOfNextDay)
            .map { notes -> notes.map { it.toModel() } }
    }

    override fun observeNotesForDate(date: String): Flow<List<Note>> {
        return noteDao.observeNotesForDate(date)
            .map { notes -> notes.map { it.toModel() } }
    }

    override fun observePendingNotesBeforeDate(date: String): Flow<List<Note>> {
        return noteDao.observePendingNotesBeforeDate(date)
            .map { notes -> notes.map { it.toModel() } }
    }

    override fun observePendingCountBeforeDate(date: String): Flow<Int> {
        return noteDao.observePendingCountBeforeDate(date)
    }

    override fun observeDaySummaries(startDate: String, endDate: String): Flow<List<DaySummary>> {
        return noteDao.observeDaySummaries(startDate, endDate)
    }

    override suspend fun getNote(id: String): Note? {
        return noteDao.getNote(id)?.toModel()
    }

    override suspend fun saveNote(note: Note) {
        noteDao.upsert(note.toEntity())
    }

    override suspend fun updateNoteContent(
        id: String,
        content: String,
        title: String?,
        sourceUrl: String?,
        userRemark: String?,
        updatedAt: Long
    ) {
        noteDao.updateContent(
            id = id,
            content = content,
            title = title,
            sourceUrl = sourceUrl,
            userRemark = userRemark,
            updatedAt = updatedAt
        )
    }

    override suspend fun updateNoteStatus(id: String, status: NoteStatus, updatedAt: Long) {
        noteDao.updateStatus(id, status, updatedAt)
    }

    override suspend fun softDeleteNote(id: String, updatedAt: Long) {
        noteDao.updateStatus(id, NoteStatus.DELETED, updatedAt)
    }

    override fun observeKnowledgeEntries(): Flow<List<KnowledgeEntry>> {
        return knowledgeEntryDao.observeEntries().map { entries -> entries.map { it.toModel() } }
    }

    override fun observeKnowledgeEntriesByIntent(intentType: IntentType): Flow<List<KnowledgeEntry>> {
        return knowledgeEntryDao.observeEntriesByIntent(intentType)
            .map { entries -> entries.map { it.toModel() } }
    }

    override fun searchKnowledgeEntries(query: String): Flow<List<KnowledgeEntry>> {
        return knowledgeEntryDao.searchEntries(query).map { entries -> entries.map { it.toModel() } }
    }

    override suspend fun getKnowledgeEntry(id: String): KnowledgeEntry? {
        return knowledgeEntryDao.getEntry(id)?.toModel()
    }

    override suspend fun saveKnowledgeEntry(entry: KnowledgeEntry) {
        knowledgeEntryDao.upsert(entry.toEntity())
    }

    override fun observeReviewSessions(): Flow<List<ReviewSession>> {
        return reviewSessionDao.observeReviewSessions().map { sessions -> sessions.map { it.toModel() } }
    }

    override suspend fun getReviewSession(id: String): ReviewSession? {
        return reviewSessionDao.getReviewSession(id)?.toModel()
    }

    override suspend fun saveReviewSession(session: ReviewSession) {
        reviewSessionDao.upsert(session.toEntity())
    }
}
