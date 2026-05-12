package com.nazhi.app.core.repository

import com.nazhi.app.core.database.dao.EmbeddingDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDraftDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDao
import com.nazhi.app.core.database.dao.NoteDao
import com.nazhi.app.core.database.dao.ReviewSessionDao
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.embedding.LocalEmbeddingEngine
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.EmbeddingRecord
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.ReviewSession
import com.nazhi.app.core.model.SemanticSearchResult
import com.nazhi.app.core.network.EmbeddingInput
import com.nazhi.app.core.network.NazhiBackendClient
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LocalNazhiRepository(
    private val noteDao: NoteDao,
    private val knowledgeEntryDao: KnowledgeEntryDao,
    private val knowledgeEntryDraftDao: KnowledgeEntryDraftDao,
    private val reviewSessionDao: ReviewSessionDao,
    private val embeddingDao: EmbeddingDao,
    private val backendClient: NazhiBackendClient
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

    override suspend fun getNotesByIds(ids: List<String>): List<Note> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val order = ids.withIndex().associate { it.value to it.index }
        return noteDao.getNotesByIds(ids)
            .map { it.toModel() }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
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

    override fun observeKnowledgeEntriesForDate(date: String): Flow<List<KnowledgeEntry>> {
        return knowledgeEntryDao.observeEntriesForCreatedDate(date)
            .map { entries -> entries.map { it.toModel() } }
    }

    override fun observeKnowledgeDraftsForDate(date: String): Flow<List<KnowledgeEntryDraft>> {
        return knowledgeEntryDraftDao.observeDraftsForDate(date)
            .map { drafts -> drafts.map { it.toModel() } }
    }

    override fun observeDayKnowledgeStatus(date: String): Flow<DayKnowledgeStatus> {
        return combine(
            noteDao.observeNotesForDate(date),
            knowledgeEntryDraftDao.observeDraftsForDate(date),
            knowledgeEntryDao.observeEntriesForCreatedDate(date),
            embeddingDao.observeEmbeddingRecords()
        ) { notes, drafts, entries, embeddings ->
            val entryIds = entries.map { it.id }.toSet()
            val indexedOwnerIds = embeddings
                .filter { record ->
                    record.ownerType == EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY &&
                        record.model == NazhiBackendClient.EMBEDDING_MODEL &&
                        record.ownerId in entryIds
                }
                .map { it.ownerId }
                .toSet()

            DayKnowledgeStatus(
                date = date,
                noteCount = notes.size,
                pendingNoteCount = notes.count { it.status == NoteStatus.INBOX },
                reviewedNoteCount = notes.count { it.status == NoteStatus.REVIEWED },
                draftCount = drafts.size,
                pendingDraftCount = drafts.count { it.status == KnowledgeDraftStatus.PENDING },
                knowledgeEntryCount = entries.size,
                indexedEntryCount = entries.count {
                    it.indexStatus == KnowledgeIndexStatus.INDEXED || it.id in indexedOwnerIds
                },
                failedIndexCount = entries.count { it.indexStatus == KnowledgeIndexStatus.FAILED }
            )
        }
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

    override suspend fun organizeNotesForDate(date: String): Int {
        val notes = noteDao.getNotesForDate(date).map { it.toModel() }
        if (notes.isEmpty()) {
            return 0
        }

        val response = backendClient.organizeNotes(
            requestId = "organize-$date-${UUID.randomUUID()}",
            date = date,
            notes = notes
        )
        val now = System.currentTimeMillis()
        val drafts = response.drafts
            .mapIndexedNotNull { index, draft ->
                val sourceIds = draft.sourceNoteIds
                    .ifEmpty { notes.getOrNull(index)?.let { listOf(it.id) } ?: emptyList() }
                    .filter { id -> notes.any { it.id == id } }
                if (sourceIds.isEmpty() || draft.content.isBlank()) {
                    null
                } else {
                    KnowledgeEntryDraft(
                        id = draft.id?.takeIf { it.isNotBlank() } ?: "draft-$date-${UUID.randomUUID()}",
                        date = date,
                        title = draft.title?.takeIf { it.isNotBlank() } ?: draft.content.firstLineOrTitle(),
                        summary = draft.summary.orEmpty().ifBlank { draft.content.firstLineOrTitle(80) },
                        content = draft.content.trim(),
                        intentType = draft.normalizedIntentType(),
                        tags = draft.tags.filter { it.isNotBlank() }.take(5),
                        sourceNoteIds = sourceIds,
                        evidenceQuotes = draft.evidenceQuotes.filter { it.isNotBlank() }.take(3),
                        insight = draft.insight?.takeIf { it.isNotBlank() },
                        confidence = draft.confidence.coerceIn(0f, 1f),
                        needsReview = draft.needsReview || draft.confidence < 0.7f,
                        status = KnowledgeDraftStatus.PENDING,
                        createdAt = now,
                        updatedAt = now
                    )
                }
            }

        knowledgeEntryDraftDao.deleteReplaceableDraftsForDate(date)
        knowledgeEntryDraftDao.upsertAll(drafts.map { it.toEntity() })
        return drafts.size
    }

    override suspend fun updateKnowledgeDraft(draft: KnowledgeEntryDraft) {
        knowledgeEntryDraftDao.upsert(
            draft.copy(updatedAt = System.currentTimeMillis()).toEntity()
        )
    }

    override suspend fun skipKnowledgeDraft(draftId: String) {
        knowledgeEntryDraftDao.updateStatus(
            id = draftId,
            status = KnowledgeDraftStatus.SKIPPED,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun submitKnowledgeDraft(draftId: String): KnowledgeEntry? {
        val draft = knowledgeEntryDraftDao.getDraft(draftId)?.toModel() ?: return null
        if (draft.status != KnowledgeDraftStatus.PENDING) {
            return null
        }

        val now = System.currentTimeMillis()
        val sourceNotes = draft.sourceNoteIds.mapNotNull { noteDao.getNote(it)?.toModel() }
        val firstNote = sourceNotes.firstOrNull()
        val entry = KnowledgeEntry(
            id = "knowledge-${UUID.randomUUID()}",
            noteId = firstNote?.id.orEmpty(),
            content = draft.content,
            intentType = draft.intentType,
            userTitle = draft.title,
            userRemark = draft.insight,
            createdAt = firstNote?.createdAt ?: now,
            createdDate = draft.date,
            confirmedAt = now,
            confirmedDate = draft.date,
            summary = draft.summary,
            tags = draft.tags,
            sourceNoteIds = draft.sourceNoteIds,
            indexStatus = KnowledgeIndexStatus.PENDING
        )

        knowledgeEntryDao.upsert(entry.toEntity())
        draft.sourceNoteIds.forEach { noteId ->
            noteDao.updateStatus(noteId, NoteStatus.REVIEWED, now)
        }
        knowledgeEntryDraftDao.updateStatus(draft.id, KnowledgeDraftStatus.CONFIRMED, now)
        indexKnowledgeEntry(entry.id)
        return knowledgeEntryDao.getEntry(entry.id)?.toModel()
    }

    override suspend fun submitAllKnowledgeDraftsForDate(date: String): Int {
        var submittedCount = 0
        knowledgeEntryDraftDao.getDraftsForDate(date)
            .filter { it.status == KnowledgeDraftStatus.PENDING }
            .forEach { draft ->
                if (submitKnowledgeDraft(draft.id) != null) {
                    submittedCount += 1
                }
            }
        return submittedCount
    }

    override suspend fun indexKnowledgeEntry(entryId: String): Boolean {
        val entry = knowledgeEntryDao.getEntry(entryId)?.toModel() ?: return false
        return runCatching {
            knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.INDEXING)
            upsertRemoteEmbeddingForEntry(entry)
            knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.INDEXED)
            true
        }.getOrElse {
            knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.FAILED)
            false
        }
    }

    override suspend fun indexPendingKnowledgeEntries(): Int {
        var indexedCount = 0
        val entries = knowledgeEntryDao.getEntriesByIndexStatus(KnowledgeIndexStatus.PENDING) +
            knowledgeEntryDao.getEntriesByIndexStatus(KnowledgeIndexStatus.FAILED)
        entries.distinctBy { it.id }.forEach { entry ->
            if (indexKnowledgeEntry(entry.id)) {
                indexedCount += 1
            }
        }
        return indexedCount
    }

    override fun observeEmbeddingCount(): Flow<Int> {
        return embeddingDao.observeEmbeddingCount()
    }

    override suspend fun saveEmbeddingRecord(record: EmbeddingRecord) {
        embeddingDao.upsert(record.toEntity())
    }

    override suspend fun generateMissingMockEmbeddings(): Int {
        var generatedCount = 0
        knowledgeEntryDao.getEntries()
            .map { it.toModel() }
            .forEach { entry ->
                val text = entry.toEmbeddingText()
                val hash = LocalEmbeddingEngine.textHash(text)
                val existing = embeddingDao.getRecord(
                    ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
                    ownerId = entry.id,
                    model = LocalEmbeddingEngine.MOCK_MODEL
                )
                if (existing == null || existing.textHash != hash) {
                    upsertMockEmbeddingForEntry(entry)
                    generatedCount += 1
                }
            }
        return generatedCount
    }

    override suspend fun searchSimilarKnowledgeEntries(
        query: String,
        topK: Int
    ): List<SemanticSearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return emptyList()
        }

        val remoteResults = runCatching {
            val response = backendClient.createEmbeddings(
                requestId = "query-${UUID.randomUUID()}",
                input = listOf(
                    EmbeddingInput(
                        id = "query",
                        text = trimmedQuery,
                        metadata = mapOf("type" to "query")
                    )
                )
            )
            val queryVector = LocalEmbeddingEngine.normalize(
                response.items.first().embedding.toFloatArray()
            )
            searchSimilarKnowledgeEntriesByVector(
                queryVector = queryVector,
                model = response.model,
                topK = topK
            )
        }.getOrDefault(emptyList())

        if (remoteResults.isNotEmpty()) {
            return remoteResults
        }

        val queryVector = LocalEmbeddingEngine.embedMock(trimmedQuery)
        return embeddingDao.getRecordsByOwnerType(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            model = LocalEmbeddingEngine.MOCK_MODEL
        )
            .mapNotNull { record ->
                val entry = knowledgeEntryDao.getEntry(record.ownerId)?.toModel()
                if (entry == null) {
                    null
                } else {
                    val vector = LocalEmbeddingEngine.fromBlob(record.vectorBlob)
                    SemanticSearchResult(
                        entry = entry,
                        score = LocalEmbeddingEngine.dot(queryVector, vector)
                    )
                }
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
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

    private suspend fun upsertMockEmbeddingForEntry(entry: KnowledgeEntry) {
        val text = entry.toEmbeddingText()
        val vector = LocalEmbeddingEngine.embedMock(text)
        val now = System.currentTimeMillis()
        val existing = embeddingDao.getRecord(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            ownerId = entry.id,
            model = LocalEmbeddingEngine.MOCK_MODEL
        )
        val record = EmbeddingRecord(
            id = existing?.id ?: "${EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY}_${entry.id}_${LocalEmbeddingEngine.MOCK_MODEL}_0",
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            ownerId = entry.id,
            chunkIndex = 0,
            textHash = LocalEmbeddingEngine.textHash(text),
            model = LocalEmbeddingEngine.MOCK_MODEL,
            dimensions = vector.size,
            precision = EmbeddingRecord.PRECISION_FLOAT32,
            vectorBlob = LocalEmbeddingEngine.toBlob(vector),
            vectorNorm = LocalEmbeddingEngine.norm(vector),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        embeddingDao.upsert(record.toEntity())
    }

    private suspend fun upsertRemoteEmbeddingForEntry(entry: KnowledgeEntry) {
        val text = entry.toEmbeddingText()
        val response = backendClient.createEmbeddings(
            requestId = "entry-${entry.id}-${UUID.randomUUID()}",
            input = listOf(
                EmbeddingInput(
                    id = entry.id,
                    text = text,
                    metadata = mapOf(
                        "ownerType" to EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
                        "ownerId" to entry.id
                    )
                )
            )
        )
        val item = response.items.firstOrNull() ?: error("Embedding response is empty.")
        val vector = LocalEmbeddingEngine.normalize(item.embedding.toFloatArray())
        val now = System.currentTimeMillis()
        val existing = embeddingDao.getRecord(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            ownerId = entry.id,
            model = response.model
        )
        val record = EmbeddingRecord(
            id = existing?.id ?: "${EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY}_${entry.id}_${response.model}_0",
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            ownerId = entry.id,
            chunkIndex = 0,
            textHash = LocalEmbeddingEngine.textHash(text),
            model = response.model,
            dimensions = response.dimensions,
            precision = EmbeddingRecord.PRECISION_FLOAT32,
            vectorBlob = LocalEmbeddingEngine.toBlob(vector),
            vectorNorm = LocalEmbeddingEngine.norm(vector),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        embeddingDao.upsert(record.toEntity())
    }

    private suspend fun searchSimilarKnowledgeEntriesByVector(
        queryVector: FloatArray,
        model: String,
        topK: Int
    ): List<SemanticSearchResult> {
        return embeddingDao.getRecordsByOwnerType(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            model = model
        )
            .mapNotNull { record ->
                val entry = knowledgeEntryDao.getEntry(record.ownerId)?.toModel()
                if (entry == null) {
                    null
                } else {
                    val vector = LocalEmbeddingEngine.fromBlob(record.vectorBlob)
                    SemanticSearchResult(
                        entry = entry,
                        score = LocalEmbeddingEngine.dot(queryVector, vector)
                    )
                }
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun KnowledgeEntry.toEmbeddingText(): String {
        return listOfNotNull(
            userTitle?.takeIf { it.isNotBlank() },
            summary.takeIf { it.isNotBlank() },
            tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Tags: "),
            userRemark?.takeIf { it.isNotBlank() },
            content
        ).joinToString(separator = "\n")
    }

    private fun String.firstLineOrTitle(maxLength: Int = 40): String {
        return lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(maxLength)
            ?: "未命名知识"
    }
}
