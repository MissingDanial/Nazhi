package com.nazhi.app.core.repository

import com.nazhi.app.core.database.dao.EmbeddingDao
import com.nazhi.app.core.database.dao.ChatCitationDao
import com.nazhi.app.core.database.dao.ChatMessageDao
import com.nazhi.app.core.database.dao.ChatSessionDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDraftDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDao
import com.nazhi.app.core.database.dao.NoteDao
import com.nazhi.app.core.database.dao.ReviewSessionDao
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.embedding.LocalEmbeddingEngine
import com.nazhi.app.core.export.ExportChatCitation
import com.nazhi.app.core.export.ExportChatMessage
import com.nazhi.app.core.export.ExportChatSession
import com.nazhi.app.core.export.ExportKnowledgeDraft
import com.nazhi.app.core.export.ExportKnowledgeEntry
import com.nazhi.app.core.export.ExportNote
import com.nazhi.app.core.export.ExportSafety
import com.nazhi.app.core.export.ImportEntityResult
import com.nazhi.app.core.export.LocalDataImportPreview
import com.nazhi.app.core.export.LocalDataImportResult
import com.nazhi.app.core.export.NazhiExportPayload
import com.nazhi.app.core.export.toExportChatCitation
import com.nazhi.app.core.export.toExportChatMessage
import com.nazhi.app.core.export.toExportChatSession
import com.nazhi.app.core.export.toExportKnowledgeDraft
import com.nazhi.app.core.export.toExportKnowledgeEntry
import com.nazhi.app.core.export.toExportNote
import com.nazhi.app.core.export.toImportedChatCitation
import com.nazhi.app.core.export.toImportedChatMessage
import com.nazhi.app.core.export.toImportedChatSession
import com.nazhi.app.core.export.toImportedKnowledgeDraft
import com.nazhi.app.core.export.toImportedKnowledgeEntry
import com.nazhi.app.core.export.toImportedNote
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.AiTaskStage
import com.nazhi.app.core.model.AiTaskStatus
import com.nazhi.app.core.model.ChatCitation
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.ChatSession
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
import com.nazhi.app.core.model.findDuplicateEntry
import com.nazhi.app.core.network.EmbeddingInput
import com.nazhi.app.core.network.BackendTaskResponse
import com.nazhi.app.core.network.KnowledgeChatContextInput
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.network.QuestionRewriteResponse
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalNazhiRepository(
    private val noteDao: NoteDao,
    private val knowledgeEntryDao: KnowledgeEntryDao,
    private val knowledgeEntryDraftDao: KnowledgeEntryDraftDao,
    private val reviewSessionDao: ReviewSessionDao,
    private val embeddingDao: EmbeddingDao,
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao,
    private val chatCitationDao: ChatCitationDao,
    private val backendClient: NazhiBackendClient
) : NazhiRepository {
    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private companion object {
        const val DEFAULT_CHAT_SESSION_TITLE = "新对话"
        const val MAX_CHAT_RETRIEVAL_QUERY_LENGTH = 240
        const val MAX_CHAT_MEMORY_PREFIX_LENGTH = 80
        const val MAX_PREVIOUS_QUESTION_LENGTH = 100
        const val MAX_PREVIOUS_CITATIONS = 3
        const val MAX_MEMORY_DIGEST_LENGTH = 200
        const val MIN_REWRITE_CONFIDENCE = 0.55f
    }

    private data class ChatRetrievalContext(
        val retrievalQuery: String,
        val resolvedQuestion: String,
        val sessionMemory: String,
        val previousCitationIds: List<String>,
        val previousCitationResults: List<SemanticSearchResult>
    )

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

    override suspend fun updateKnowledgeEntry(entry: KnowledgeEntry, reindex: Boolean): Boolean {
        knowledgeEntryDao.upsert(
            entry.copy(indexStatus = KnowledgeIndexStatus.PENDING).toEntity()
        )
        embeddingDao.deleteRecordsForOwner(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            ownerId = entry.id
        )
        return if (reindex) {
            indexKnowledgeEntry(entry.id)
        } else {
            true
        }
    }

    override suspend fun organizeNotesForDate(
        date: String,
        onProgress: (AiTaskProgress) -> Unit
    ): Int {
        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.RUNNING,
                stage = AiTaskStage.PREPARING_NOTES,
                progress = 10,
                message = "正在读取待整理笔记"
            )
        )
        val notes = noteDao.getNotesForDate(date)
            .map { it.toModel() }
            .filter { it.status == NoteStatus.INBOX }
        if (notes.isEmpty()) {
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.SUCCEEDED,
                    stage = AiTaskStage.DONE,
                    progress = 100,
                    message = "没有可整理的内容"
                )
            )
            return 0
        }

        val requestId = "organize-$date-${UUID.randomUUID()}"
        val response = runCatching {
            val createdTask = backendClient.createOrganizeNotesJob(
                requestId = requestId,
                date = date,
                notes = notes
            )
            onProgress(createdTask.toAiTaskProgress())
            waitForOrganizeTask(createdTask, onProgress)
        }.getOrElse { error ->
            if (
                error is NazhiBackendException &&
                (error.statusCode == 404 || error.code == "NOT_FOUND" || error.code == "DIRECT_API_MODE")
            ) {
                onProgress(
                    AiTaskProgress(
                        status = AiTaskStatus.RUNNING,
                        stage = AiTaskStage.CALLING_MODEL,
                        progress = 45,
                        message = if (error.code == "DIRECT_API_MODE") "正在直接调用用户 API" else "后端使用旧版同步整理接口"
                    )
                )
                backendClient.organizeNotes(
                    requestId = requestId,
                    date = date,
                    notes = notes
                )
            } else {
                onProgress(
                    AiTaskProgress(
                        status = AiTaskStatus.FAILED,
                        stage = AiTaskStage.FAILED,
                        progress = 100,
                        message = error.toUserFacingMessage()
                    )
                )
                throw error
            }
        }

        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.RUNNING,
                stage = AiTaskStage.SAVING_RESULT,
                progress = 95,
                message = "正在写入本地草稿"
            )
        )
        val count = replaceDraftsFromResponse(date, notes, response)
        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.SUCCEEDED,
                stage = AiTaskStage.DONE,
                progress = 100,
                message = if (count == 0) "没有生成有效草稿" else "已生成 $count 条 AI 草稿"
            )
        )
        return count
    }

    private suspend fun waitForOrganizeTask(
        createdTask: BackendTaskResponse,
        onProgress: (AiTaskProgress) -> Unit
    ): com.nazhi.app.core.network.OrganizeNotesResponse {
        var task = createdTask
        while (task.status == "RUNNING") {
            delay(1_200)
            task = backendClient.getTask(task.taskId)
            onProgress(task.toAiTaskProgress())
        }

        if (task.status == "SUCCEEDED") {
            return task.result ?: throw IOException("AI 整理任务完成但没有返回结果。")
        }

        throw NazhiBackendException(
            statusCode = 500,
            code = task.error?.code ?: "TASK_FAILED",
            publicMessage = task.error?.message ?: task.message.ifBlank { "AI 整理任务失败。" }
        )
    }

    private suspend fun replaceDraftsFromResponse(
        date: String,
        notes: List<Note>,
        response: com.nazhi.app.core.network.OrganizeNotesResponse
    ): Int {
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
        val duplicateEntry = draft.findDuplicateEntry(
            knowledgeEntryDao.getEntries().map { it.toModel() }
        )
        if (duplicateEntry != null) {
            draft.sourceNoteIds.forEach { noteId ->
                noteDao.updateStatus(noteId, NoteStatus.REVIEWED, now)
            }
            knowledgeEntryDraftDao.updateStatus(draft.id, KnowledgeDraftStatus.SKIPPED, now)
            throw DuplicateKnowledgeEntryException(duplicateEntry.userTitle)
        }
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
            .filter { !it.needsReview }
            .forEach { draft ->
                val entry = runCatching { submitKnowledgeDraft(draft.id) }
                    .getOrElse { error ->
                        if (error is DuplicateKnowledgeEntryException) {
                            null
                        } else {
                            throw error
                        }
                    }
                if (entry != null) {
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
                if (entry == null || entry.indexStatus != KnowledgeIndexStatus.INDEXED) {
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

    override fun observeChatSessions(): Flow<List<ChatSession>> {
        return chatSessionDao.observeSessions().map { sessions -> sessions.map { it.toModel() } }
    }

    override fun observeChatMessages(sessionId: String): Flow<List<ChatMessage>> {
        return chatMessageDao.observeMessagesForSession(sessionId).map { messages ->
            messages.map { it.toModel() }
        }
    }

    override fun observeChatCitationsForSession(sessionId: String): Flow<List<ChatCitation>> {
        return chatCitationDao.observeCitationsForSession(sessionId).map { citations ->
            citations.map { it.toModel() }
        }
    }

    override suspend fun buildLocalDataExportJson(): String {
        val payload = NazhiExportPayload(
            schemaVersion = 2,
            appName = "Nazhi",
            exportedAt = System.currentTimeMillis(),
            safety = ExportSafety(),
            notes = noteDao.getNotes().map { it.toModel().toExportNote() },
            knowledgeEntries = knowledgeEntryDao.getEntries().map { it.toModel().toExportKnowledgeEntry() },
            knowledgeDrafts = knowledgeEntryDraftDao.getDrafts().map { it.toModel().toExportKnowledgeDraft() },
            chatSessions = chatSessionDao.getSessions().map { it.toModel().toExportChatSession() },
            chatMessages = chatMessageDao.getMessages().map { it.toModel().toExportChatMessage() },
            chatCitations = chatCitationDao.getCitations().map { it.toModel().toExportChatCitation() }
        )
        return exportJson.encodeToString(payload)
    }

    override suspend fun previewLocalDataImportJson(json: String): LocalDataImportPreview {
        val payload = parseLocalDataImportPayload(json)
        return LocalDataImportPreview(
            schemaVersion = payload.schemaVersion,
            exportedAt = payload.exportedAt,
            noteCount = payload.notes.size,
            knowledgeEntryCount = payload.knowledgeEntries.size,
            knowledgeDraftCount = payload.knowledgeDrafts.size,
            chatSessionCount = payload.chatSessions.size,
            chatMessageCount = payload.chatMessages.size,
            chatCitationCount = payload.chatCitations.size,
            warnings = buildImportWarnings(payload)
        )
    }

    override suspend fun importLocalDataJson(json: String): LocalDataImportResult {
        val payload = parseLocalDataImportPayload(json)
        return LocalDataImportResult(
            notes = importNotes(payload.notes),
            knowledgeEntries = importKnowledgeEntries(payload.knowledgeEntries),
            knowledgeDrafts = importKnowledgeDrafts(payload.knowledgeDrafts),
            chatSessions = importChatSessions(payload.chatSessions),
            chatMessages = importChatMessages(payload.chatMessages),
            chatCitations = importChatCitations(payload.chatCitations)
        )
    }

    private fun parseLocalDataImportPayload(json: String): NazhiExportPayload {
        val payload = runCatching {
            exportJson.decodeFromString<NazhiExportPayload>(json)
        }.getOrElse {
            throw IllegalArgumentException("导入文件不是有效的纳知 JSON。")
        }
        require(payload.appName.equals("Nazhi", ignoreCase = true)) {
            "导入文件不是纳知导出文件。"
        }
        require(payload.schemaVersion in 1..2) {
            "暂不支持 schemaVersion=${payload.schemaVersion} 的导出文件。"
        }
        return payload
    }

    private fun buildImportWarnings(payload: NazhiExportPayload): List<String> {
        return buildList {
            if (
                !payload.safety.excludesApiKeys ||
                !payload.safety.excludesTokens ||
                !payload.safety.excludesBackendSettings
            ) {
                add("导入流程不会恢复 API Key、服务 Token 或后端配置。")
            }
            if (!payload.safety.excludesEmbeddingVectors || payload.knowledgeEntries.isNotEmpty()) {
                add("导入不会恢复本地向量，知识条目会重新标记为待索引。")
            }
            add("同 ID 数据会跳过，不覆盖当前手机已有内容。")
        }
    }

    private suspend fun importNotes(notes: List<ExportNote>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        notes.forEach { item ->
            val note = item.toImportedNote()
            when {
                note.id.isBlank() || note.content.isBlank() || note.createdDate.isBlank() -> failedCount += 1
                noteDao.getNote(note.id) != null -> skippedCount += 1
                else -> {
                    noteDao.upsert(note.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    private suspend fun importKnowledgeEntries(entries: List<ExportKnowledgeEntry>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        entries.forEach { item ->
            val entry = item.toImportedKnowledgeEntry()
            when {
                entry.id.isBlank() || entry.content.isBlank() || entry.noteId.isBlank() -> failedCount += 1
                knowledgeEntryDao.getEntry(entry.id) != null -> skippedCount += 1
                noteDao.getNote(entry.noteId) == null -> failedCount += 1
                else -> {
                    knowledgeEntryDao.upsert(entry.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    private suspend fun importKnowledgeDrafts(drafts: List<ExportKnowledgeDraft>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        drafts.forEach { item ->
            val draft = item.toImportedKnowledgeDraft()
            when {
                draft.id.isBlank() || draft.date.isBlank() || draft.content.isBlank() -> failedCount += 1
                knowledgeEntryDraftDao.getDraft(draft.id) != null -> skippedCount += 1
                else -> {
                    knowledgeEntryDraftDao.upsert(draft.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    private suspend fun importChatSessions(sessions: List<ExportChatSession>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        sessions.forEach { item ->
            val session = item.toImportedChatSession()
            when {
                session.id.isBlank() -> failedCount += 1
                chatSessionDao.getSession(session.id) != null -> skippedCount += 1
                else -> {
                    chatSessionDao.upsert(session.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    private suspend fun importChatMessages(messages: List<ExportChatMessage>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        messages.forEach { item ->
            val message = item.toImportedChatMessage()
            when {
                message.id.isBlank() || message.sessionId.isBlank() || message.content.isBlank() -> failedCount += 1
                chatMessageDao.getMessage(message.id) != null -> skippedCount += 1
                chatSessionDao.getSession(message.sessionId) == null -> failedCount += 1
                else -> {
                    chatMessageDao.upsert(message.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    private suspend fun importChatCitations(citations: List<ExportChatCitation>): ImportEntityResult {
        var insertedCount = 0
        var skippedCount = 0
        var failedCount = 0
        citations.forEach { item ->
            val citation = item.toImportedChatCitation()
            when {
                citation.id.isBlank() || citation.messageId.isBlank() || citation.knowledgeEntryId.isBlank() -> failedCount += 1
                chatCitationDao.getCitation(citation.id) != null -> skippedCount += 1
                chatMessageDao.getMessage(citation.messageId) == null -> failedCount += 1
                knowledgeEntryDao.getEntry(citation.knowledgeEntryId) == null -> failedCount += 1
                else -> {
                    chatCitationDao.upsert(citation.toEntity())
                    insertedCount += 1
                }
            }
        }
        return ImportEntityResult(insertedCount, skippedCount, failedCount)
    }

    override suspend fun askKnowledgeQuestion(
        question: String,
        topK: Int,
        sessionId: String?,
        onProgress: (AiTaskProgress) -> Unit
    ): ChatMessage {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            throw IllegalArgumentException("问题不能为空")
        }
        ensureKnowledgeChatReady()
        val now = System.currentTimeMillis()
        val session = getOrCreateChatSession(sessionId, trimmedQuestion)
        val userMessage = ChatMessage(
            id = "chat-user-${UUID.randomUUID()}",
            sessionId = session.id,
            role = ChatRole.USER,
            content = trimmedQuestion,
            status = ChatMessageStatus.DONE,
            errorMessage = null,
            progressStage = AiTaskStage.DONE.name,
            createdAt = now,
            updatedAt = now
        )
        chatMessageDao.upsert(userMessage.toEntity())
        updateChatSessionOverview(session.id, titleSeed = trimmedQuestion, updatedAt = now)
        return answerKnowledgeQuestion(
            questionMessage = userMessage,
            topK = topK,
            attempt = 1,
            onProgress = onProgress
        )
    }

    override suspend fun retryChatMessage(
        messageId: String,
        topK: Int,
        onProgress: (AiTaskProgress) -> Unit
    ): ChatMessage {
        ensureKnowledgeChatReady()
        val questionMessage = resolveQuestionMessageForRetry(messageId)
        val attempt = nextAssistantAttempt(questionMessage.id)
        return answerKnowledgeQuestion(
            questionMessage = questionMessage,
            topK = topK,
            attempt = attempt,
            onProgress = onProgress
        )
    }

    override suspend fun regenerateChatAnswer(
        messageId: String,
        topK: Int,
        onProgress: (AiTaskProgress) -> Unit
    ): ChatMessage {
        ensureKnowledgeChatReady()
        val questionMessage = resolveQuestionMessageForRetry(messageId)
        val attempt = nextAssistantAttempt(questionMessage.id)
        return answerKnowledgeQuestion(
            questionMessage = questionMessage,
            topK = topK,
            attempt = attempt,
            onProgress = onProgress
        )
    }

    override suspend fun createChatSession(title: String?): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-session-${UUID.randomUUID()}",
            title = title?.takeIf { it.isNotBlank() }?.firstLineOrTitle(24) ?: DEFAULT_CHAT_SESSION_TITLE,
            createdAt = now,
            updatedAt = now
        )
        chatSessionDao.upsert(session.toEntity())
        return session
    }

    override suspend fun deleteChatSession(sessionId: String) {
        chatSessionDao.deleteSession(sessionId)
    }

    override suspend fun clearChatSessionMemory(sessionId: String) {
        chatSessionDao.updateMemoryDigest(
            id = sessionId,
            memoryDigest = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun clearChatSessions() {
        chatSessionDao.deleteAll()
    }

    private suspend fun answerKnowledgeQuestion(
        questionMessage: ChatMessage,
        topK: Int,
        attempt: Int,
        onProgress: (AiTaskProgress) -> Unit
    ): ChatMessage {
        val trimmedQuestion = questionMessage.content.trim()
        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.RUNNING,
                stage = AiTaskStage.LOCAL_RETRIEVAL,
                progress = 20,
                message = "正在理解问题并生成检索向量"
            )
        )
        val retrievalContext = buildChatRetrievalContext(
            questionMessage = questionMessage,
            topK = topK
        )
        val results = runCatching {
            searchSimilarKnowledgeEntriesForQuestion(
                query = retrievalContext.retrievalQuery,
                topK = topK,
                onProgress = onProgress
            )
        }.getOrElse { error ->
            val message = error.toUserFacingMessage()
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.FAILED,
                    stage = AiTaskStage.FAILED,
                    progress = 100,
                    message = message
                )
            )
            saveAssistantMessage(
                sessionId = questionMessage.sessionId,
                parentMessageId = questionMessage.id,
                content = "知识库问答失败。",
                status = ChatMessageStatus.FAILED,
                errorMessage = message,
                attempt = attempt,
                progressStage = AiTaskStage.FAILED.name,
                errorCode = error.toErrorCode(),
                citations = emptyList(),
                matchedResults = emptyList()
            )
            throw IOException(message, error)
        }
        val mergedResults = mergeChatRetrievalResults(
            searchResults = results,
            previousResults = retrievalContext.previousCitationResults,
            topK = topK
        )
        if (mergedResults.isEmpty()) {
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.SUCCEEDED,
                    stage = AiTaskStage.DONE,
                    progress = 100,
                    message = "没有找到足够相关的本地知识"
                )
            )
            return saveAssistantMessage(
                sessionId = questionMessage.sessionId,
                parentMessageId = questionMessage.id,
                content = "当前知识库中没有足够信息回答这个问题。请先完成知识入库和向量索引，或换一个更具体的问题。",
                status = ChatMessageStatus.DONE,
                errorMessage = null,
                attempt = attempt,
                progressStage = AiTaskStage.DONE.name,
                errorCode = null,
                citations = emptyList(),
                matchedResults = emptyList()
            )
        }

        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.RUNNING,
                stage = AiTaskStage.CONTEXT_READY,
                progress = 45,
                message = "已命中 ${mergedResults.size} 条知识，准备提交给 AI"
            )
        )
        return runCatching {
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.RUNNING,
                    stage = AiTaskStage.CALLING_MODEL,
                    progress = 70,
                    message = "AI 正在基于本地知识生成回答"
                )
            )
            val response = backendClient.chatWithKnowledge(
                requestId = "knowledge-chat-${UUID.randomUUID()}",
                question = trimmedQuestion,
                contexts = mergedResults.map { result ->
                    val entry = result.entry
                    KnowledgeChatContextInput(
                        id = entry.id,
                        title = entry.userTitle.orEmpty(),
                        summary = entry.summary,
                        content = entry.content,
                        tags = entry.tags,
                        sourceNoteIds = entry.sourceNoteIds,
                        score = result.score
                    )
                },
                resolvedQuestion = retrievalContext.resolvedQuestion,
                sessionMemory = retrievalContext.sessionMemory,
                previousCitationIds = retrievalContext.previousCitationIds
            )
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.RUNNING,
                    stage = AiTaskStage.SAVING_RESULT,
                    progress = 90,
                    message = "正在保存回答和引用"
                )
            )
            saveAssistantMessage(
                sessionId = questionMessage.sessionId,
                parentMessageId = questionMessage.id,
                content = response.answer.ifBlank { "当前知识库中没有足够信息回答这个问题。" },
                status = ChatMessageStatus.DONE,
                errorMessage = null,
                attempt = attempt,
                progressStage = AiTaskStage.DONE.name,
                errorCode = null,
                citations = response.citations,
                matchedResults = mergedResults
            ).also {
                updateChatSessionMemoryIfUseful(
                    sessionId = questionMessage.sessionId,
                    answer = response.answer,
                    citations = response.citations,
                    updatedMemoryDigest = response.updatedMemoryDigest
                )
                onProgress(
                    AiTaskProgress(
                        status = AiTaskStatus.SUCCEEDED,
                        stage = AiTaskStage.DONE,
                        progress = 100,
                        message = "回答已生成，引用 ${response.citations.size} 条"
                    )
                )
            }
        }.getOrElse { error ->
            val message = error.toUserFacingMessage()
            onProgress(
                AiTaskProgress(
                    status = AiTaskStatus.FAILED,
                    stage = AiTaskStage.FAILED,
                    progress = 100,
                    message = message
                )
            )
            saveAssistantMessage(
                sessionId = questionMessage.sessionId,
                parentMessageId = questionMessage.id,
                content = "回答生成失败。",
                status = ChatMessageStatus.FAILED,
                errorMessage = message,
                attempt = attempt,
                progressStage = AiTaskStage.FAILED.name,
                errorCode = error.toErrorCode(),
                citations = emptyList(),
                matchedResults = emptyList()
            )
            throw IOException(message, error)
        }
    }

    private suspend fun searchSimilarKnowledgeEntriesForQuestion(
        query: String,
        topK: Int,
        onProgress: (AiTaskProgress) -> Unit
    ): List<SemanticSearchResult> {
        val response = backendClient.createEmbeddings(
            requestId = "query-${UUID.randomUUID()}",
            input = listOf(
                EmbeddingInput(
                    id = "query",
                    text = query,
                    metadata = mapOf("type" to "query")
                )
            )
        )
        val item = response.items.firstOrNull() ?: throw IOException("查询向量生成失败：后端返回为空。")
        onProgress(
            AiTaskProgress(
                status = AiTaskStatus.RUNNING,
                stage = AiTaskStage.LOCAL_RETRIEVAL,
                progress = 35,
                message = "正在本地检索相似知识"
            )
        )
        val queryVector = LocalEmbeddingEngine.normalize(item.embedding.toFloatArray())
        val remoteResults = searchSimilarKnowledgeEntriesByVector(
            queryVector = queryVector,
            model = response.model,
            topK = topK,
            requirePositiveScore = false
        )
        if (remoteResults.isNotEmpty()) {
            return remoteResults
        }
        return searchSimilarKnowledgeEntriesByVector(
            queryVector = LocalEmbeddingEngine.embedMock(query),
            model = LocalEmbeddingEngine.MOCK_MODEL,
            topK = topK,
            requirePositiveScore = false
        )
    }

    private suspend fun buildChatRetrievalContext(
        questionMessage: ChatMessage,
        topK: Int
    ): ChatRetrievalContext {
        val session = chatSessionDao.getSession(questionMessage.sessionId)?.toModel()
        val messages = chatMessageDao.getMessagesForSession(questionMessage.sessionId).map { it.toModel() }
        val previousQuestion = messages
            .filter { message ->
                message.role == ChatRole.USER &&
                    message.id != questionMessage.id &&
                    message.createdAt <= questionMessage.createdAt
            }
            .lastOrNull()
            ?.content
        val previousAssistant = messages
            .filter { message ->
                message.role == ChatRole.ASSISTANT &&
                    message.status == ChatMessageStatus.DONE &&
                    message.createdAt < questionMessage.createdAt
            }
            .lastOrNull()
        val sessionMemory = session?.memoryDigest.orEmpty().compactSingleLine()
        val localFollowUp = shouldUseSessionMemory(
            question = questionMessage.content,
            sessionMemory = sessionMemory,
            previousQuestion = previousQuestion
        )
        val previousCitationResults = previousAssistant
            ?.let { assistant ->
                loadPreviousCitationResultsForMessage(
                    messageId = assistant.id,
                    maxCount = minOf(MAX_PREVIOUS_CITATIONS, topK)
                )
            }
            .orEmpty()
        val rewrite = rewriteQuestionForRetrieval(
            currentQuestion = questionMessage.content,
            sessionMemory = sessionMemory,
            previousQuestion = previousQuestion.orEmpty(),
            previousAssistantAnswer = previousAssistant?.content.orEmpty(),
            previousCitationResults = previousCitationResults
        )
        val shouldTrustRewrite = rewrite != null && rewrite.confidence >= MIN_REWRITE_CONFIDENCE
        val isFollowUp = if (shouldTrustRewrite) {
            rewrite?.isFollowUp == true
        } else {
            localFollowUp
        }
        val shouldUsePreviousCitations = isFollowUp &&
            (if (shouldTrustRewrite) rewrite?.shouldUsePreviousCitations == true else true)
        val fallbackFollowUpQuery = buildFollowUpRetrievalQuery(
            sessionMemory = sessionMemory,
            previousQuestion = previousQuestion.orEmpty(),
            currentQuestion = questionMessage.content
        )
        val resolvedQuestion = if (isFollowUp) {
            rewrite?.standaloneQuestion
                ?.compactSingleLine()
                ?.takeIf { it.isNotBlank() && shouldTrustRewrite }
                ?: fallbackFollowUpQuery
        } else {
            ""
        }
        val retrievalQuery = if (isFollowUp) {
            rewrite?.retrievalQuery
                ?.compactSingleLine()
                ?.takeIf { it.isNotBlank() && shouldTrustRewrite }
                ?: resolvedQuestion
        } else {
            questionMessage.content.trim()
        }
        val selectedPreviousResults = if (shouldUsePreviousCitations) previousCitationResults else emptyList()
        return ChatRetrievalContext(
            retrievalQuery = retrievalQuery,
            resolvedQuestion = resolvedQuestion,
            sessionMemory = if (isFollowUp) sessionMemory else "",
            previousCitationIds = selectedPreviousResults.map { it.entry.id },
            previousCitationResults = selectedPreviousResults
        )
    }

    private suspend fun rewriteQuestionForRetrieval(
        currentQuestion: String,
        sessionMemory: String,
        previousQuestion: String,
        previousAssistantAnswer: String,
        previousCitationResults: List<SemanticSearchResult>
    ): QuestionRewriteResponse? {
        if (
            sessionMemory.isBlank() &&
            previousQuestion.isBlank() &&
            previousAssistantAnswer.isBlank() &&
            previousCitationResults.isEmpty()
        ) {
            return null
        }
        return runCatching {
            backendClient.rewriteQuestion(
                requestId = "rewrite-question-${UUID.randomUUID()}",
                currentQuestion = currentQuestion,
                sessionMemory = sessionMemory,
                lastUserQuestion = previousQuestion,
                lastAssistantAnswerPreview = previousAssistantAnswer.compactSingleLine().take(320),
                previousCitationTitles = previousCitationResults.map { result ->
                    result.entry.userTitle
                        ?.takeIf { it.isNotBlank() }
                        ?: result.entry.summary.takeIf { it.isNotBlank() }
                        ?: result.entry.content.compactSingleLine().take(80)
                }
            )
        }.getOrNull()
    }

    private suspend fun loadPreviousCitationResultsForMessage(
        messageId: String,
        maxCount: Int
    ): List<SemanticSearchResult> {
        if (maxCount <= 0) {
            return emptyList()
        }
        return chatCitationDao.getCitationsForMessage(messageId)
            .map { it.toModel() }
            .distinctBy { it.knowledgeEntryId }
            .take(maxCount)
            .mapNotNull { citation ->
                val entry = knowledgeEntryDao.getEntry(citation.knowledgeEntryId)?.toModel()
                    ?: return@mapNotNull null
                SemanticSearchResult(
                    entry = entry,
                    score = citation.score.takeIf { it > 0f } ?: 0.0001f
                )
            }
    }

    private fun shouldUseSessionMemory(
        question: String,
        sessionMemory: String,
        previousQuestion: String?
    ): Boolean {
        val normalized = question.compactSingleLine()
        if (normalized.length > 40) {
            return false
        }
        if (sessionMemory.isBlank() && previousQuestion.isNullOrBlank()) {
            return false
        }
        val hasFollowUpMarker = listOf(
            "这个",
            "刚才",
            "上面",
            "前面",
            "继续",
            "展开",
            "展开一下",
            "详细",
            "具体",
            "怎么做",
            "怎么",
            "如何",
            "例子",
            "应用",
            "建议",
            "优化",
            "方案",
            "步骤",
            "哪些",
            "为什么",
            "总结",
            "归纳",
            "分析",
            "这个观点",
            "这部分",
            "这一点",
            "它"
        ).any { normalized.contains(it) }
        val asksNewDefinition = listOf("是什么", "介绍一下", "解释一下").any { normalized.contains(it) }
        val hasExplicitReference = listOf("这个", "刚才", "上面", "前面", "这部分", "这一点", "它").any {
            normalized.contains(it)
        }
        if (!hasFollowUpMarker) {
            return !asksNewDefinition && sessionMemory.isNotBlank() && normalized.length <= 18
        }
        return !asksNewDefinition || hasExplicitReference
    }

    private fun buildFollowUpRetrievalQuery(
        sessionMemory: String,
        previousQuestion: String,
        currentQuestion: String
    ): String {
        return listOfNotNull(
            sessionMemory.takeIf { it.isNotBlank() }?.let {
                "会话：${it.compactSingleLine().take(MAX_CHAT_MEMORY_PREFIX_LENGTH)}"
            },
            previousQuestion.takeIf { it.isNotBlank() }?.let {
                "上问：${it.compactSingleLine().takeLast(MAX_PREVIOUS_QUESTION_LENGTH)}"
            },
            "追问：${currentQuestion.compactSingleLine()}"
        )
            .joinToString(separator = "\n")
            .take(MAX_CHAT_RETRIEVAL_QUERY_LENGTH)
    }

    private fun mergeChatRetrievalResults(
        searchResults: List<SemanticSearchResult>,
        previousResults: List<SemanticSearchResult>,
        topK: Int
    ): List<SemanticSearchResult> {
        val selected = previousResults
            .distinctBy { it.entry.id }
            .take(minOf(MAX_PREVIOUS_CITATIONS, topK))
            .toMutableList()
        val selectedIds = selected.map { it.entry.id }.toMutableSet()
        searchResults
            .filterNot { it.entry.id in selectedIds }
            .take(topK - selected.size)
            .forEach { result ->
                selected += result
                selectedIds += result.entry.id
            }
        return selected.take(topK)
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
        topK: Int,
        requirePositiveScore: Boolean = true
    ): List<SemanticSearchResult> {
        return embeddingDao.getRecordsByOwnerType(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            model = model
        )
            .mapNotNull { record ->
                val entry = knowledgeEntryDao.getEntry(record.ownerId)?.toModel()
                if (entry == null || entry.indexStatus != KnowledgeIndexStatus.INDEXED) {
                    null
                } else {
                    val vector = LocalEmbeddingEngine.fromBlob(record.vectorBlob)
                    SemanticSearchResult(
                        entry = entry,
                        score = LocalEmbeddingEngine.dot(queryVector, vector)
                    )
                }
            }
            .filter { !requirePositiveScore || it.score > 0f }
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

    private suspend fun ensureKnowledgeChatReady() {
        val entries = knowledgeEntryDao.getEntries()
        if (entries.isEmpty()) {
            throw IllegalStateException("当前还没有知识条目，请先完成知识入库后再提问。")
        }
        if (entries.none { it.indexStatus == KnowledgeIndexStatus.INDEXED }) {
            throw IllegalStateException("知识库尚未完成索引，请先在知识库页重建索引后再提问。")
        }
    }

    private suspend fun getOrCreateChatSession(sessionId: String?, question: String): ChatSession {
        val selected = sessionId?.let { chatSessionDao.getSession(it)?.toModel() }
        if (selected != null) {
            return selected
        }
        return createChatSession(question)
    }

    private suspend fun resolveQuestionMessageForRetry(messageId: String): ChatMessage {
        val target = chatMessageDao.getMessage(messageId)?.toModel()
            ?: throw IllegalArgumentException("找不到对应的问答记录。")
        if (target.role == ChatRole.USER) {
            return target
        }
        val parentId = target.parentMessageId
            ?: throw IllegalArgumentException("这条旧回答缺少问题关联，无法直接重试。")
        return chatMessageDao.getMessage(parentId)?.toModel()
            ?: throw IllegalArgumentException("找不到这条回答对应的问题。")
    }

    private suspend fun nextAssistantAttempt(parentMessageId: String): Int {
        val latestAttempt = chatMessageDao.getAssistantMessagesForParent(parentMessageId)
            .maxOfOrNull { it.attempt }
            ?: 0
        return latestAttempt + 1
    }

    private suspend fun updateChatSessionOverview(
        sessionId: String,
        titleSeed: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        val session = chatSessionDao.getSession(sessionId)?.toModel() ?: return
        val messages = chatMessageDao.getMessagesForSession(sessionId).map { it.toModel() }
        val firstQuestion = messages.firstOrNull { it.role == ChatRole.USER }?.content ?: titleSeed
        val resolvedTitle = when {
            session.title.isBlank() || session.title == DEFAULT_CHAT_SESSION_TITLE -> {
                firstQuestion?.firstLineOrTitle(24) ?: DEFAULT_CHAT_SESSION_TITLE
            }
            else -> session.title
        }
        val lastMessage = messages.lastOrNull()
        chatSessionDao.updateOverview(
            id = sessionId,
            title = resolvedTitle,
            messageCount = messages.size,
            lastMessagePreview = lastMessage?.content?.compactPreview(64).orEmpty(),
            updatedAt = lastMessage?.updatedAt ?: updatedAt
        )
    }

    private suspend fun saveAssistantMessage(
        sessionId: String,
        parentMessageId: String,
        content: String,
        status: ChatMessageStatus,
        errorMessage: String?,
        attempt: Int,
        progressStage: String?,
        errorCode: String?,
        citations: List<com.nazhi.app.core.network.KnowledgeChatCitation>,
        matchedResults: List<SemanticSearchResult>
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = "chat-assistant-${UUID.randomUUID()}",
            sessionId = sessionId,
            parentMessageId = parentMessageId,
            role = ChatRole.ASSISTANT,
            content = content,
            status = status,
            errorMessage = errorMessage,
            attempt = attempt,
            progressStage = progressStage,
            errorCode = errorCode,
            createdAt = now,
            updatedAt = now
        )
        chatMessageDao.upsert(message.toEntity())
        updateChatSessionOverview(sessionId, updatedAt = now)

        val resultById = matchedResults.associateBy { it.entry.id }
        val citationEntities = citations.mapNotNull { citation ->
            val result = resultById[citation.contextId] ?: return@mapNotNull null
            ChatCitation(
                id = "chat-citation-${UUID.randomUUID()}",
                messageId = message.id,
                knowledgeEntryId = result.entry.id,
                sourceNoteIds = result.entry.sourceNoteIds,
                quote = citation.quote.ifBlank { result.entry.summary.ifBlank { result.entry.content.take(80) } },
                reason = citation.reason,
                score = result.score,
                createdAt = now
            ).toEntity()
        }
        if (citationEntities.isNotEmpty()) {
            chatCitationDao.upsertAll(citationEntities)
        }
        return message
    }

    private suspend fun updateChatSessionMemoryIfUseful(
        sessionId: String,
        answer: String,
        citations: List<com.nazhi.app.core.network.KnowledgeChatCitation>,
        updatedMemoryDigest: String
    ) {
        val normalizedDigest = updatedMemoryDigest
            .compactSingleLine()
            .take(MAX_MEMORY_DIGEST_LENGTH)
        if (
            normalizedDigest.isBlank() ||
            citations.isEmpty() ||
            answer.isKnowledgeInsufficientAnswer() ||
            normalizedDigest.isKnowledgeInsufficientAnswer()
        ) {
            return
        }
        chatSessionDao.updateMemoryDigest(
            id = sessionId,
            memoryDigest = normalizedDigest,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun BackendTaskResponse.toAiTaskProgress(): AiTaskProgress {
        return AiTaskProgress(
            status = when (status) {
                "SUCCEEDED" -> AiTaskStatus.SUCCEEDED
                "FAILED" -> AiTaskStatus.FAILED
                else -> AiTaskStatus.RUNNING
            },
            stage = when (stage) {
                "ACCEPTED" -> AiTaskStage.ACCEPTED
                "PREPARING_NOTES" -> AiTaskStage.PREPARING_NOTES
                "LOCAL_RETRIEVAL" -> AiTaskStage.LOCAL_RETRIEVAL
                "CONTEXT_READY" -> AiTaskStage.CONTEXT_READY
                "CALLING_MODEL" -> AiTaskStage.CALLING_MODEL
                "PARSING_RESULT" -> AiTaskStage.PARSING_RESULT
                "SAVING_RESULT" -> AiTaskStage.SAVING_RESULT
                "FALLBACK_DRAFTS" -> AiTaskStage.FALLBACK_DRAFTS
                "DONE" -> AiTaskStage.DONE
                "FAILED" -> AiTaskStage.FAILED
                else -> AiTaskStage.UNKNOWN
            },
            progress = progress.coerceIn(0, 100),
            message = error?.message ?: message
        )
    }

    private fun Throwable.toUserFacingMessage(): String {
        return when (this) {
            is NazhiBackendException -> when {
                code == "DIRECT_API_KEY_MISSING" -> "请先在设置页填写 API Key。"
                code == "DIRECT_API_BASE_URL_MISSING" -> "请先在设置页填写 API Base URL。"
                code == "DIRECT_API_UNAUTHORIZED" -> publicMessage
                code == "DIRECT_API_ENDPOINT_NOT_FOUND" -> publicMessage
                code == "DIRECT_API_RATE_LIMITED" -> publicMessage
                code == "DIRECT_API_QUOTA_EXHAUSTED" -> publicMessage
                code == "DIRECT_API_TIMEOUT" -> publicMessage
                code == "DIRECT_API_BAD_REQUEST" -> publicMessage
                code == "DIRECT_API_PROVIDER_UNAVAILABLE" -> publicMessage
                code == "DIRECT_API_CHAT_RESPONSE_EMPTY" -> publicMessage
                code == "DIRECT_API_INVALID_JSON" -> "模型返回格式异常，请检查当前模型是否支持稳定输出 JSON。"
                code == "DIRECT_API_EMBEDDING_FAILED" -> "Embedding API 调用失败，请检查模型名、Key 和服务额度。"
                code == "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED" -> publicMessage
                statusCode == 401 || code == "UNAUTHORIZED" -> "后端鉴权失败，请检查设置页中的 NAZHI_DEV_TOKEN。"
                code == "MINIMAX_CHAT_FAILED" -> "模型回答生成失败，请稍后重试或检查后端日志。"
                code == "MINIMAX_NOT_CONFIGURED" -> "后端 Chat 模型未配置，请检查服务器 .env。"
                else -> publicMessage
            }
            else -> {
                val raw = message.orEmpty()
                when {
                    raw.contains("Failed to connect", ignoreCase = true) ||
                        raw.contains("Unable to resolve host", ignoreCase = true) ||
                        raw.contains("No address associated", ignoreCase = true) ||
                        raw.contains("Network is unreachable", ignoreCase = true) ||
                        raw.contains("ENETUNREACH", ignoreCase = true) ||
                        raw.contains("No route to host", ignoreCase = true) ||
                        raw.contains("Connection refused", ignoreCase = true) -> {
                        "无网络连接或无法连接后端，请检查手机网络和服务器地址。"
                    }
                    raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> {
                        "请求超时，请检查服务器网络或稍后重试。"
                    }
                    raw.isNotBlank() -> raw
                    else -> "请求失败，请检查后端服务。"
                }
            }
        }
    }

    private fun Throwable.toErrorCode(): String {
        return when (this) {
            is NazhiBackendException -> code ?: "HTTP_$statusCode"
            else -> this::class.java.simpleName.ifBlank { "UNKNOWN_ERROR" }
        }
    }

    private fun String.compactPreview(maxLength: Int): String {
        return compactSingleLine().take(maxLength)
    }

    private fun String.compactSingleLine(): String {
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
    }

    private fun String.isKnowledgeInsufficientAnswer(): Boolean {
        return contains("当前知识库中没有足够信息") ||
            contains("没有足够信息") ||
            contains("不足以回答") ||
            contains("无法回答")
    }

    private fun String.firstLineOrTitle(maxLength: Int = 40): String {
        return lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(maxLength)
            ?: "未命名知识"
    }
}
