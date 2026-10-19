package com.nazhi.app.core.repository

import androidx.room.withTransaction
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.database.dao.PersistedAiTaskDao
import com.nazhi.app.core.database.dao.AudioTranscriptionJobDao
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
import com.nazhi.app.core.export.ExportSafety
import com.nazhi.app.core.export.ImportEntityResult
import com.nazhi.app.core.export.LocalDataImportPreview
import com.nazhi.app.core.export.LocalDataImportResult
import com.nazhi.app.core.export.NazhiExportPayload
import com.nazhi.app.core.export.NazhiBackupCodec
import com.nazhi.app.core.export.BackupMergePlan
import com.nazhi.app.core.export.BackupValidationException
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
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.CalendarDraftFarmSummary
import com.nazhi.app.core.model.CalendarFarmMarker
import com.nazhi.app.core.model.CalendarKnowledgeFarmSummary
import com.nazhi.app.core.model.CalendarNoteFarmSummary
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
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.model.ReviewSession
import com.nazhi.app.core.model.SemanticSearchResult
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.model.findDuplicateEntry
import com.nazhi.app.core.network.EmbeddingInput
import com.nazhi.app.core.network.BackendTaskResponse
import com.nazhi.app.core.network.KnowledgeChatContextInput
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.network.QuestionRewriteResponse
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LocalNazhiRepository(
    private val database: NazhiDatabase,
    private val persistedAiTaskDao: PersistedAiTaskDao,
    private val noteDao: NoteDao,
    private val knowledgeEntryDao: KnowledgeEntryDao,
    private val knowledgeEntryDraftDao: KnowledgeEntryDraftDao,
    private val reviewSessionDao: ReviewSessionDao,
    private val embeddingDao: EmbeddingDao,
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao,
    private val chatCitationDao: ChatCitationDao,
    private val audioTranscriptionJobDao: AudioTranscriptionJobDao,
    private val backendClient: NazhiBackendClient
) : NazhiRepository {
    private val organizeLocks = ConcurrentHashMap<String, Mutex>()
    private val indexLocks = ConcurrentHashMap<String, Mutex>()
    private val coldStartAt = System.currentTimeMillis()
    private val normalizationLock = Mutex()
    private var normalized = false

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

    override fun observeCalendarFarmMarkers(startDate: String, endDate: String): Flow<List<CalendarFarmMarker>> {
        return combine(
            noteDao.observeCalendarFarmNoteSummaries(startDate, endDate),
            knowledgeEntryDraftDao.observeCalendarFarmDraftSummaries(startDate, endDate),
            knowledgeEntryDao.observeCalendarFarmKnowledgeSummaries(startDate, endDate)
        ) { noteSummaries, draftSummaries, knowledgeSummaries ->
            buildCalendarFarmMarkers(
                noteSummaries = noteSummaries,
                draftSummaries = draftSummaries,
                knowledgeSummaries = knowledgeSummaries
            )
        }
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
        if (status == NoteStatus.REVIEWED) {
            cleanupAudioForNote(id, updatedAt)
        }
    }

    override suspend fun softDeleteNote(id: String, updatedAt: Long) {
        noteDao.updateStatus(id, NoteStatus.DELETED, updatedAt)
        cleanupAudioForNote(id, updatedAt)
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
        database.withTransaction {
            knowledgeEntryDao.upsert(entry.copy(indexStatus = KnowledgeIndexStatus.PENDING).toEntity())
            embeddingDao.deleteRecordsForOwner(
                ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
                ownerId = entry.id
            )
        }
        return if (reindex) {
            indexKnowledgeEntry(entry.id)
        } else {
            true
        }
    }

    override fun observePersistedAiTasksForDate(date: String): Flow<List<PersistedAiTask>> =
        persistedAiTaskDao.observeForDate(date).map { tasks -> tasks.map { it.toModel() } }

    override suspend fun getPersistedAiTask(id: String): PersistedAiTask? =
        persistedAiTaskDao.getById(id)?.toModel()

    override suspend fun savePersistedAiTask(task: PersistedAiTask) {
        persistedAiTaskDao.upsert(task.toEntity())
    }

    override suspend fun getRecoverableAiTasks(): List<PersistedAiTask> =
        persistedAiTaskDao.getRecoverable().map { it.toModel() }

    override suspend fun organizeNotesForDate(
        date: String,
        onProgress: (AiTaskProgress) -> Unit
    ): Int = organizeLocks.getOrPut(date) { Mutex() }.withLock {
        normalizeInterruptedOperations()
        val previous = persistedAiTaskDao.getLatestForDate(date, "ORGANIZE_NOTES")?.toModel()
        // A second tap or restart must first query the accepted request, never upload it again.
        if (previous != null && previous.status in setOf("CREATED", "RUNNING", "WAITING")) {
            return@withLock recoverOrganizeTaskLocked(previous, onProgress)
        }
        onProgress(AiTaskProgress(
            status = AiTaskStatus.RUNNING, stage = AiTaskStage.PREPARING_NOTES,
            progress = 10, message = "正在读取待整理笔记"
        ))
        val notes = noteDao.getNotesForDate(date).map { it.toModel() }
            .filter { it.status == NoteStatus.INBOX }
        if (notes.isEmpty()) {
            onProgress(AiTaskProgress(
                status = AiTaskStatus.SUCCEEDED, stage = AiTaskStage.DONE,
                progress = 100, message = "没有可整理的内容"
            ))
            return@withLock 0
        }
        val now = maxOf(System.currentTimeMillis(), (previous?.createdAt ?: 0L) + 1L)
        val localTask = PersistedAiTask(
            id = "organize-local-${UUID.randomUUID()}", type = "ORGANIZE_NOTES",
            requestId = "organize-$date-${UUID.randomUUID()}",
            bindingKey = backendClient.currentTaskBinding(), date = date,
            sourceNoteIds = notes.map { it.id }, inputFingerprint = organizeNotesFingerprint(notes),
            status = "CREATED", createdAt = now, updatedAt = now
        )
        savePersistedAiTask(localTask)
        try {
            val response = try {
                val remote = backendClient.createOrganizeNotesJob(
                    requestId = localTask.requestId, date = date, notes = notes,
                    expectedBinding = localTask.bindingKey
                )
                validateOrganizeIdentity(localTask, remote)
                savePersistedAiTask(localTask.copy(
                    backendTaskId = remote.taskId, status = "RUNNING", updatedAt = System.currentTimeMillis()
                ))
                onProgress(remote.toAiTaskProgress())
                waitForOrganizeTask(localTask.id, remote, onProgress)
            } catch (error: NazhiBackendException) {
                if (error.code != "DIRECT_API_MODE") throw error
                // Direct providers have no resumable identity. Only an explicit user action calls them.
                savePersistedAiTask(localTask.copy(status = "RUNNING", updatedAt = System.currentTimeMillis()))
                onProgress(AiTaskProgress(
                    status = AiTaskStatus.RUNNING, stage = AiTaskStage.CALLING_MODEL,
                    progress = 45, message = "正在直接调用用户 API"
                ))
                backendClient.organizeNotes(
                    requestId = localTask.requestId, date = date, notes = notes,
                    expectedBinding = localTask.bindingKey
                )
            }
            applyOrganizeResult(localTask, notes, response, onProgress)
        } catch (error: Throwable) {
            handleOrganizeError(localTask.id, error, onProgress)
            throw error
        }
    }

    override suspend fun recoverOrganizeTask(
        task: PersistedAiTask,
        onProgress: (AiTaskProgress) -> Unit
    ): Int = organizeLocks.getOrPut(task.date) { Mutex() }.withLock {
        normalizeInterruptedOperations()
        recoverOrganizeTaskLocked(task, onProgress)
    }

    private suspend fun recoverOrganizeTaskLocked(
        suppliedTask: PersistedAiTask,
        onProgress: (AiTaskProgress) -> Unit
    ): Int {
        val task = getPersistedAiTask(suppliedTask.id) ?: return 0
        if (task.status == "APPLIED") return 0
        if (task.status !in setOf("CREATED", "RUNNING", "WAITING")) return 0
        try {
            if (task.bindingKey.startsWith("direct:")) {
                interruptOrganizeTask(task, "直连模型任务已中断，笔记已保留，请点击重新整理。")
            }
            if (task.bindingKey != backendClient.currentTaskBinding()) {
                savePersistedAiTask(task.copy(
                    status = "WAITING", errorMessage = "请切回提交任务时的账号和后端后继续查询。",
                    updatedAt = System.currentTimeMillis()
                ))
                throw IOException("请切回提交任务时的账号和后端后继续查询。")
            }
            val notes = noteDao.getNotesByIds(task.sourceNoteIds).map { it.toModel() }
            if (notes.size != task.sourceNoteIds.size || organizeNotesFingerprint(notes) != task.inputFingerprint) {
                interruptOrganizeTask(task, "整理期间原笔记已修改，已保留现有草稿，请点击重新整理。")
            }
            val remote = if (task.backendTaskId == null) {
                backendClient.getOrganizeTaskByRequestId(task.requestId, expectedBinding = task.bindingKey)
            } else {
                backendClient.getTask(task.backendTaskId, expectedBinding = task.bindingKey)
            }
            validateOrganizeIdentity(task, remote)
            savePersistedAiTask(task.copy(
                backendTaskId = remote.taskId, status = "RUNNING", errorMessage = null,
                updatedAt = System.currentTimeMillis()
            ))
            onProgress(remote.toAiTaskProgress())
            val response = waitForOrganizeTask(task.id, remote, onProgress)
            return applyOrganizeResult(task, notes, response, onProgress)
        } catch (error: Throwable) {
            handleOrganizeError(task.id, error, onProgress, isLookup = true)
            throw error
        }
    }

    private suspend fun interruptOrganizeTask(task: PersistedAiTask, message: String): Nothing {
        savePersistedAiTask(task.copy(
            status = "INTERRUPTED", errorMessage = message, updatedAt = System.currentTimeMillis()
        ))
        throw IOException(message)
    }

    private suspend fun waitForOrganizeTask(
        localTaskId: String,
        createdTask: BackendTaskResponse,
        onProgress: (AiTaskProgress) -> Unit
    ): com.nazhi.app.core.network.OrganizeNotesResponse = withTimeout(300_000) {
        var task = createdTask
        while (task.status == "RUNNING") {
            delay(1_200)
            val local = getPersistedAiTask(localTaskId)
                ?: throw IOException("本地整理任务不存在。")
            task = backendClient.getTask(task.taskId, expectedBinding = local.bindingKey)
            validateOrganizeIdentity(local, task)
            onProgress(task.toAiTaskProgress())
        }
        if (task.status == "SUCCEEDED") {
            return@withTimeout task.result ?: throw OrganizeResultInvalidException("AI 整理任务完成但没有返回结果，请点击重新整理。")
        }
        getPersistedAiTask(localTaskId)?.let { local ->
            savePersistedAiTask(local.copy(
                status = "FAILED", errorMessage = task.error?.message ?: task.message,
                updatedAt = System.currentTimeMillis()
            ))
        }
        throw NazhiBackendException(
            statusCode = 500, code = task.error?.code ?: "TASK_FAILED",
            publicMessage = task.error?.message ?: task.message.ifBlank { "AI 整理任务失败。" }
        )
    }

    private suspend fun handleOrganizeError(
        id: String,
        error: Throwable,
        onProgress: (AiTaskProgress) -> Unit,
        isLookup: Boolean = false
    ) {
        withContext(NonCancellable) {
            val existing = getPersistedAiTask(id) ?: return@withContext
            if (existing.status in setOf("APPLIED", "FAILED", "INTERRUPTED")) return@withContext
            val lost = error is NazhiBackendException && error.statusCode == 404 &&
                (isLookup || existing.backendTaskId != null)
            val direct = existing.bindingKey.startsWith("direct:")
            val inputsChanged = error is OrganizeInputsChangedException || error is OrganizeDraftsChangedException
            val invalidResult = error is OrganizeResultInvalidException
            val submissionRejected = error is NazhiBackendException && existing.backendTaskId == null &&
                error.statusCode in 400..499 && error.code != "TASK_BINDING_CHANGED" && error.statusCode != 401
            val message = when {
                inputsChanged -> error.message.orEmpty()
                lost -> "服务器任务已失效，原笔记已保留，请点击重新整理。"
                direct -> "直连模型任务已中断，原笔记已保留，请点击重新整理。"
                error is CancellationException -> "整理已暂停，重开应用后会继续查询已有任务。"
                else -> error.toUserFacingMessage()
            }
            savePersistedAiTask(existing.copy(
                status = when {
                    lost || direct || inputsChanged -> "INTERRUPTED"
                    submissionRejected -> "FAILED"
                    invalidResult -> "FAILED"
                    else -> "WAITING"
                },
                errorMessage = message, updatedAt = System.currentTimeMillis()
            ))
            if (error !is CancellationException) onProgress(AiTaskProgress(
                status = AiTaskStatus.FAILED, stage = AiTaskStage.FAILED,
                progress = 100, message = message
            ))
        }
    }

    private suspend fun applyOrganizeResult(
        task: PersistedAiTask,
        notes: List<Note>,
        response: com.nazhi.app.core.network.OrganizeNotesResponse,
        onProgress: (AiTaskProgress) -> Unit
    ): Int {
        if (response.requestId != task.requestId || response.date != task.date) {
            throw OrganizeResultInvalidException("整理结果与本地请求不匹配，已保留原笔记和草稿，请重新整理。")
        }
        onProgress(AiTaskProgress(
            status = AiTaskStatus.RUNNING, stage = AiTaskStage.SAVING_RESULT,
            progress = 95, message = "正在写入本地草稿"
        ))
        val count = database.withTransaction {
            val current = getPersistedAiTask(task.id) ?: throw IOException("本地整理任务不存在。")
            if (current.status == "APPLIED") return@withTransaction 0
            val currentNotes = noteDao.getNotesByIds(task.sourceNoteIds).map { it.toModel() }
            if (currentNotes.size != task.sourceNoteIds.size || organizeNotesFingerprint(currentNotes) != task.inputFingerprint) {
                // Throwing rolls back the transaction; persist the interruption outside it.
                throw OrganizeInputsChangedException()
            }
            val editedDrafts = knowledgeEntryDraftDao.getDraftsForDate(task.date).any { draft ->
                draft.status != KnowledgeDraftStatus.CONFIRMED && draft.updatedAt > task.createdAt &&
                    !draft.id.startsWith("draft-${task.id}-")
            }
            if (editedDrafts) throw OrganizeDraftsChangedException()
            val saved = replaceDraftsFromResponse(task.date, notes, response, task.id)
            savePersistedAiTask(current.copy(
                status = "APPLIED", errorMessage = null, updatedAt = System.currentTimeMillis()
            ))
            saved
        }
        onProgress(AiTaskProgress(
            status = AiTaskStatus.SUCCEEDED, stage = AiTaskStage.DONE,
            progress = 100, message = "已生成 $count 条 AI 草稿"
        ))
        return count
    }

    private class OrganizeInputsChangedException : IOException("整理期间原笔记已修改，已保留现有草稿，请点击重新整理。")

    private class OrganizeDraftsChangedException : IOException("整理期间草稿已修改，已保留你的修改，请确认现有草稿后再重新整理。")

    private class OrganizeResultInvalidException(message: String) : IOException(message)

    private fun validateOrganizeIdentity(local: PersistedAiTask, remote: BackendTaskResponse) {
        if (remote.requestId != local.requestId || remote.type != "ORGANIZE_NOTES" ||
            (local.backendTaskId != null && remote.taskId != local.backendTaskId)) {
            throw OrganizeResultInvalidException("后端任务与本地请求不匹配，已保留原笔记和草稿。")
        }
    }

    private fun organizeNotesFingerprint(notes: List<Note>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        notes.sortedBy { it.id }.forEach { note ->
            listOf(note.id, note.content, note.title.orEmpty(), note.status.name,
                note.updatedAt.toString(), note.createdAt.toString(), note.sourceType.name).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun replaceDraftsFromResponse(
        date: String,
        notes: List<Note>,
        response: com.nazhi.app.core.network.OrganizeNotesResponse,
        localTaskId: String
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
                        id = "draft-$localTaskId-$index",
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

        if (drafts.isEmpty()) {
            throw OrganizeResultInvalidException("模型没有返回有效整理结果，请点击重新整理或检查后端模型输出。")
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
        normalizeInterruptedOperations()
        val now = System.currentTimeMillis()
        val result = database.withTransaction {
            val draft = knowledgeEntryDraftDao.getDraft(draftId)?.toModel()
                ?: return@withTransaction DraftSubmission(null, emptyList())
            val stableId = "knowledge-draft-$draftId"
            val alreadySaved = knowledgeEntryDao.getEntry(stableId)?.toModel()
            if (alreadySaved != null) return@withTransaction DraftSubmission(alreadySaved, emptyList())
            if (draft.status != KnowledgeDraftStatus.PENDING) {
                // Older confirmed drafts predate stable IDs; return their existing entry when possible.
                val legacyEntry = if (draft.status == KnowledgeDraftStatus.CONFIRMED) {
                    knowledgeEntryDao.getEntries().map { it.toModel() }.firstOrNull {
                        it.content == draft.content && it.sourceNoteIds.toSet() == draft.sourceNoteIds.toSet()
                    }
                } else null
                return@withTransaction DraftSubmission(legacyEntry, emptyList())
            }
            val sourceNotes = draft.sourceNoteIds.mapNotNull { noteDao.getNote(it)?.toModel() }
            require(sourceNotes.isNotEmpty() && sourceNotes.size == draft.sourceNoteIds.distinct().size &&
                sourceNotes.none { it.status == NoteStatus.DELETED }) {
                "草稿来源笔记已删除，请保留草稿并重新选择来源后再确认。"
            }
            val duplicate = draft.findDuplicateEntry(knowledgeEntryDao.getEntries().map { it.toModel() })
            if (duplicate != null) {
                draft.sourceNoteIds.forEach { noteDao.updateStatus(it, NoteStatus.REVIEWED, now) }
                knowledgeEntryDraftDao.updateStatus(draft.id, KnowledgeDraftStatus.SKIPPED, now)
                return@withTransaction DraftSubmission(null, draft.sourceNoteIds, duplicate.userTitle.orEmpty())
            }
            val firstNote = sourceNotes.first()
            val entry = KnowledgeEntry(
                id = stableId, noteId = firstNote.id, content = draft.content,
                intentType = draft.intentType, userTitle = draft.title, userRemark = draft.insight,
                createdAt = firstNote.createdAt, createdDate = draft.date,
                confirmedAt = now, confirmedDate = draft.date, summary = draft.summary,
                tags = draft.tags, sourceNoteIds = draft.sourceNoteIds,
                indexStatus = KnowledgeIndexStatus.PENDING
            )
            knowledgeEntryDao.upsert(entry.toEntity())
            draft.sourceNoteIds.forEach { noteDao.updateStatus(it, NoteStatus.REVIEWED, now) }
            knowledgeEntryDraftDao.updateStatus(draft.id, KnowledgeDraftStatus.CONFIRMED, now)
            DraftSubmission(entry, draft.sourceNoteIds, newlySaved = true)
        }
        // Files and network work follow the committed transaction, so they cannot leave half a confirmation.
        result.sourceNoteIds.forEach { cleanupAudioForNote(it, now) }
        if (result.duplicateTitle != null) throw DuplicateKnowledgeEntryException(result.duplicateTitle)
        val entry = result.entry ?: return null
        if (result.newlySaved) indexKnowledgeEntry(entry.id)
        return knowledgeEntryDao.getEntry(entry.id)?.toModel()
    }

    private data class DraftSubmission(
        val entry: KnowledgeEntry?,
        val sourceNoteIds: List<String>,
        val duplicateTitle: String? = null,
        val newlySaved: Boolean = false
    )

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

    override suspend fun indexKnowledgeEntry(entryId: String): Boolean = indexLocks.getOrPut(entryId) { Mutex() }.withLock {
        normalizeInterruptedOperations()
        val entry = knowledgeEntryDao.getEntry(entryId)?.toModel() ?: return@withLock false
        runCatching {
            knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.INDEXING)
            upsertRemoteEmbeddingForEntry(entry)
        }.getOrElse { error ->
            withContext(NonCancellable) {
                database.withTransaction {
                    val current = knowledgeEntryDao.getEntry(entry.id)?.toModel()
                    if (current?.toEmbeddingText() == entry.toEmbeddingText()) {
                        knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.FAILED)
                    }
                }
            }
            if (error is CancellationException) throw error
            false
        }
    }

    override suspend fun indexPendingKnowledgeEntries(): Int {
        normalizeInterruptedOperations()
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
                val vector = LocalEmbeddingEngine.decodeCompatibleVector(
                    blob = record.vectorBlob,
                    dimensions = record.dimensions,
                    queryDimensions = queryVector.size
                ) ?: return@mapNotNull null
                val entry = knowledgeEntryDao.getEntry(record.ownerId)?.toModel()
                if (entry == null || entry.indexStatus != KnowledgeIndexStatus.INDEXED) {
                    null
                } else {
                    SemanticSearchResult(
                        entry = entry,
                        score = LocalEmbeddingEngine.dot(queryVector, vector)
                    )
                }
            }
            .filter { it.score.isFinite() && it.score > 0f }
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

    override suspend fun buildLocalDataExportJson(): String = withContext(Dispatchers.Default) {
        val payload = database.withTransaction { readBackupSnapshot() }
        val encoded = NazhiBackupCodec.encode(payload)
        val inspection = NazhiBackupCodec.inspect(encoded)
        if (inspection.errors.isNotEmpty()) throw BackupValidationException(inspection.errors)
        encoded
    }

    /** Includes soft-deleted sources still needed to restore knowledge, drafts or citations. */
    private suspend fun readBackupSnapshot(additionalNoteIds: List<String> = emptyList()): NazhiExportPayload {
        val entries = knowledgeEntryDao.getEntries().map { it.toModel().toExportKnowledgeEntry() }
        val drafts = knowledgeEntryDraftDao.getDrafts().map { it.toModel().toExportKnowledgeDraft() }
        val citations = chatCitationDao.getCitations().map { it.toModel().toExportChatCitation() }
        val needed = (entries.flatMap { listOf(it.noteId) + it.sourceNoteIds } +
            drafts.flatMap { it.sourceNoteIds } + citations.flatMap { it.sourceNoteIds } + additionalNoteIds).distinct()
        val notes = noteDao.getNotes().map { it.toModel().toExportNote() }.associateBy { it.id }.toMutableMap()
        needed.filterNot { it in notes }.chunked(900).forEach { ids ->
            noteDao.getNotesByIds(ids).forEach { notes[it.id] = it.toModel().toExportNote() }
        }
        return NazhiExportPayload(
            schemaVersion = 3, appName = "Nazhi", exportedAt = System.currentTimeMillis(), safety = ExportSafety(),
            notes = notes.values.sortedBy { it.id }, knowledgeEntries = entries,
            knowledgeDrafts = drafts, chatSessions = chatSessionDao.getSessions().map { it.toModel().toExportChatSession() },
            chatMessages = chatMessageDao.getMessages().map { it.toModel().toExportChatMessage() }, chatCitations = citations
        )
    }

    override suspend fun previewLocalDataImportJson(json: String): LocalDataImportPreview = withContext(Dispatchers.Default) {
        val inspection = NazhiBackupCodec.inspect(json)
        val payload = inspection.payload
        if (payload == null || inspection.errors.isNotEmpty()) return@withContext importPreview(
            payload, inspection.warnings, inspection.errors, inspection.integrityVerified
        )
        try {
            database.withTransaction {
                val plan = NazhiBackupCodec.plan(payload, readBackupSnapshot(payload.notes.map { it.id }))
                importPreview(plan.normalized, inspection.warnings, emptyList(), inspection.integrityVerified, plan)
            }
        } catch (error: IllegalArgumentException) {
            importPreview(payload, inspection.warnings, listOf(error.message ?: "恢复数据校验失败。"), inspection.integrityVerified)
        }
    }

    private fun importPreview(
        payload: NazhiExportPayload?, warnings: List<String>, errors: List<String>, integrityVerified: Boolean,
        plan: BackupMergePlan? = null
    ) = LocalDataImportPreview(
        schemaVersion = payload?.schemaVersion ?: 0, exportedAt = payload?.exportedAt ?: 0,
        noteCount = payload?.notes?.size ?: 0, knowledgeEntryCount = payload?.knowledgeEntries?.size ?: 0,
        knowledgeDraftCount = payload?.knowledgeDrafts?.size ?: 0, chatSessionCount = payload?.chatSessions?.size ?: 0,
        chatMessageCount = payload?.chatMessages?.size ?: 0, chatCitationCount = payload?.chatCitations?.size ?: 0,
        warnings = warnings, newCount = plan?.newCount ?: 0, unchangedCount = plan?.unchangedCount ?: 0,
        conflictCount = plan?.conflictCount ?: 0, conflicts = plan?.conflicts.orEmpty(), errors = errors,
        integrityVerified = integrityVerified
    )

    override suspend fun importLocalDataJson(json: String): LocalDataImportResult = withContext(Dispatchers.Default) {
        val inspection = NazhiBackupCodec.inspect(json)
        if (inspection.errors.isNotEmpty()) throw BackupValidationException(inspection.errors)
        val payload = inspection.payload ?: throw BackupValidationException(listOf("备份文件无效。"))
        database.withTransaction {
            // The user may edit data after preview. Recompute against the transaction's current snapshot.
            val plan = NazhiBackupCodec.plan(payload, readBackupSnapshot(payload.notes.map { it.id }))
            val insert = plan.insertions
            insert.notes.forEach { noteDao.upsert(it.toImportedNote().toEntity()) }
            insert.knowledgeEntries.forEach { knowledgeEntryDao.upsert(it.toImportedKnowledgeEntry().toEntity()) }
            insert.knowledgeDrafts.forEach { knowledgeEntryDraftDao.upsert(it.toImportedKnowledgeDraft().toEntity()) }
            insert.chatSessions.forEach { chatSessionDao.upsert(it.toImportedChatSession().toEntity()) }
            // Parent IDs are logical relations; user messages are written first for clarity.
            insert.chatMessages.sortedBy { if (it.role == "USER") 0 else 1 }.forEach { chatMessageDao.upsert(it.toImportedChatMessage().toEntity()) }
            insert.chatCitations.forEach { chatCitationDao.upsert(it.toImportedChatCitation().toEntity()) }
            verifyImportedRecords(insert)
            insert.chatMessages.map { it.sessionId }.distinct().forEach { id ->
                val session = requireNotNull(chatSessionDao.getSession(id))
                val messages = chatMessageDao.getMessagesForSession(id)
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
                val refreshed = session.copy(messageCount = messages.size, lastMessagePreview = messages.lastOrNull()?.content?.take(80).orEmpty())
                chatSessionDao.upsert(refreshed)
                check(chatSessionDao.getSession(id) == refreshed) { "恢复后会话校验失败，已回滚本次导入。" }
            }
            fun result(total: Int, count: Int) = ImportEntityResult(count, total - count, 0)
            LocalDataImportResult(
                notes = result(plan.normalized.notes.size, insert.notes.size),
                knowledgeEntries = result(plan.normalized.knowledgeEntries.size, insert.knowledgeEntries.size),
                knowledgeDrafts = result(plan.normalized.knowledgeDrafts.size, insert.knowledgeDrafts.size),
                chatSessions = result(plan.normalized.chatSessions.size, insert.chatSessions.size),
                chatMessages = result(plan.normalized.chatMessages.size, insert.chatMessages.size),
                chatCitations = result(plan.normalized.chatCitations.size, insert.chatCitations.size),
                verifiedCount = plan.newCount, conflictCount = plan.conflictCount,
                verificationMessage = "已回读校验 ${plan.newCount} 条新增记录及其关联；${plan.conflictCount} 条冲突记录已跳过，保留本机内容。"
            )
        }
    }

    /** Any failed insert, trigger mutation or missing relation throws inside the transaction and rolls back everything. */
    private suspend fun verifyImportedRecords(p: NazhiExportPayload) {
        fun verify(ok: Boolean, label: String) { check(ok) { "恢复后校验失败：$label，已回滚本次导入。" } }
        p.notes.forEach { verify(noteDao.getNote(it.id)?.toModel() == it.toImportedNote(), "笔记 ${it.id}") }
        p.knowledgeEntries.forEach { item ->
            val expected = item.toImportedKnowledgeEntry()
            verify(knowledgeEntryDao.getEntry(item.id)?.toModel() == expected, "知识 ${item.id}")
            expected.sourceNoteIds.forEach { verify(noteDao.getNote(it) != null, "知识来源 $it") }
        }
        p.knowledgeDrafts.forEach { item ->
            verify(knowledgeEntryDraftDao.getDraft(item.id)?.toModel() == item.toImportedKnowledgeDraft(), "草稿 ${item.id}")
            item.sourceNoteIds.forEach { verify(noteDao.getNote(it) != null, "草稿来源 $it") }
        }
        p.chatSessions.forEach { verify(chatSessionDao.getSession(it.id)?.toModel() == it.toImportedChatSession(), "会话 ${it.id}") }
        p.chatMessages.forEach { item ->
            verify(chatMessageDao.getMessage(item.id)?.toModel() == item.toImportedChatMessage(), "消息 ${item.id}")
            verify(chatSessionDao.getSession(item.sessionId) != null, "消息会话 ${item.sessionId}")
            item.parentMessageId?.let { id ->
                val parent = chatMessageDao.getMessage(id)
                verify(parent?.role == ChatRole.USER && parent.sessionId == item.sessionId, "回答父问题 $id")
            }
        }
        p.chatCitations.forEach { item ->
            verify(chatCitationDao.getCitation(item.id)?.toModel() == item.toImportedChatCitation(), "引用 ${item.id}")
            verify(chatMessageDao.getMessage(item.messageId)?.role == ChatRole.ASSISTANT &&
                knowledgeEntryDao.getEntry(item.knowledgeEntryId) != null, "引用关联 ${item.id}")
            item.sourceNoteIds.forEach { verify(noteDao.getNote(it) != null, "引用来源 $it") }
        }
    }
    override suspend fun askKnowledgeQuestion(
        question: String,
        topK: Int,
        sessionId: String?,
        onProgress: (AiTaskProgress) -> Unit
    ): ChatMessage {
        normalizeInterruptedOperations()
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
        normalizeInterruptedOperations()
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
        normalizeInterruptedOperations()
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
        saveAssistantMessage(
            sessionId = questionMessage.sessionId, parentMessageId = questionMessage.id,
            content = "正在处理问题…", status = ChatMessageStatus.PENDING,
            errorMessage = null, attempt = attempt, progressStage = AiTaskStage.LOCAL_RETRIEVAL.name,
            errorCode = null, citations = emptyList(), matchedResults = emptyList()
        )
        try {
            return performKnowledgeAnswer(questionMessage, topK, attempt, onProgress)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                saveAssistantMessage(
                    sessionId = questionMessage.sessionId, parentMessageId = questionMessage.id,
                    content = "问答处理已中断。", status = ChatMessageStatus.FAILED,
                    errorMessage = if (error is CancellationException) "问题已保留，请点击重试。" else error.toUserFacingMessage(),
                    attempt = attempt, progressStage = AiTaskStage.FAILED.name,
                    errorCode = if (error is CancellationException) "CHAT_INTERRUPTED" else error.toErrorCode(),
                    citations = emptyList(), matchedResults = emptyList()
                )
            }
            throw error
        }
    }

    private suspend fun performKnowledgeAnswer(
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
                message = "正在理解问题并检索知识"
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
            if (error is CancellationException) throw error
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
                content = "当前知识库中没有足够信息回答这个问题。请先完成知识沉淀，或换一个更具体的问题。",
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
            if (error is CancellationException) throw error
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
                content = "回答处理失败。",
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
        val item = response.items.firstOrNull() ?: throw IOException("查询处理失败：后端返回为空。")
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

    override fun observeAudioTranscriptionJobsForDate(date: String): Flow<List<AudioTranscriptionJob>> {
        return audioTranscriptionJobDao.observeJobsForDate(date)
            .map { jobs -> jobs.map { it.toModel() } }
    }

    override suspend fun getAudioTranscriptionJob(id: String): AudioTranscriptionJob? {
        return audioTranscriptionJobDao.getJob(id)?.toModel()
    }

    override suspend fun getRetryableAudioTranscriptionJobs(): List<AudioTranscriptionJob> {
        return audioTranscriptionJobDao.getRetryableJobs().map { it.toModel() }
    }

    override suspend fun saveAudioTranscriptionJob(job: AudioTranscriptionJob) {
        normalizeInterruptedOperations()
        audioTranscriptionJobDao.upsert(job.toEntity())
    }

    override suspend fun storeAudioTranscript(jobId: String, text: String): AudioTranscriptionJob =
        database.withTransaction {
            val job = getAudioTranscriptionJob(jobId) ?: throw IOException("找不到本地录音任务。")
            // Repeated recovery must preserve the saved note or the user's edited preview.
            if (job.noteId != null || job.status == AudioTranscriptionJobStatus.READY_TO_SAVE) {
                markAudioTaskApplied(jobId)
                return@withTransaction job
            }
            val transcript = text.trim()
            require(transcript.isNotEmpty()) { "录音没有识别出有效文字，音频已保留。" }
            val now = System.currentTimeMillis()
            if (job.requiresConfirmation) {
                val ready = job.copy(
                    status = AudioTranscriptionJobStatus.READY_TO_SAVE, transcriptText = transcript,
                    errorMessage = null, updatedAt = now
                )
                audioTranscriptionJobDao.upsert(ready.toEntity())
                markAudioTaskApplied(jobId)
                ready
            } else {
                commitAudioNote(job, transcript, now)
                getAudioTranscriptionJob(jobId) ?: throw IOException("录音任务保存失败。")
            }
        }

    override suspend fun updateAudioTranscriptPreview(jobId: String, text: String) {
        database.withTransaction {
            val job = getAudioTranscriptionJob(jobId) ?: return@withTransaction
            if (job.status != AudioTranscriptionJobStatus.READY_TO_SAVE) return@withTransaction
            audioTranscriptionJobDao.upsert(job.copy(
                transcriptText = text, updatedAt = System.currentTimeMillis()
            ).toEntity())
        }
    }

    override suspend fun confirmAudioTranscript(jobId: String, text: String): Note = database.withTransaction {
        val job = getAudioTranscriptionJob(jobId) ?: throw IOException("找不到本地录音任务。")
        val existingNote = job.noteId?.let { noteDao.getNote(it)?.toModel() }
            ?: noteDao.getNote("audio-note-$jobId")?.toModel()
        if (existingNote != null) return@withTransaction existingNote
        require(job.status == AudioTranscriptionJobStatus.READY_TO_SAVE) { "转写尚未完成，请等待结果后再保存。" }
        val transcript = text.trim()
        require(transcript.isNotEmpty()) { "内容不能为空。" }
        commitAudioNote(job, transcript, System.currentTimeMillis())
    }

    private suspend fun commitAudioNote(job: AudioTranscriptionJob, transcript: String, now: Long): Note {
        val id = job.noteId ?: "audio-note-${job.id}"
        val note = noteDao.getNote(id)?.toModel() ?: Note(
            id = id, content = transcript, title = transcript.firstLineOrTitle(80),
            sourceType = SourceType.AUDIO_TRANSCRIPTION, sourceApp = job.sourceApp,
            sourceUrl = null, audioDurationMs = job.durationMs,
            createdAt = job.createdAt, createdDate = job.createdDate, updatedAt = now,
            status = NoteStatus.INBOX, userRemark = null
        ).also { noteDao.upsert(it.toEntity()) }
        audioTranscriptionJobDao.upsert(job.copy(
            status = AudioTranscriptionJobStatus.SAVED, noteId = note.id,
            transcriptText = note.content, errorMessage = null, updatedAt = now
        ).toEntity())
        markAudioTaskApplied(job.id)
        return note
    }

    private suspend fun markAudioTaskApplied(jobId: String) {
        val task = getPersistedAiTask(jobId) ?: return
        require(task.type == "AUDIO_TRANSCRIPTION") { "录音任务身份不匹配。" }
        savePersistedAiTask(task.copy(
            status = "APPLIED", errorMessage = null, updatedAt = System.currentTimeMillis()
        ))
    }

    override suspend fun normalizeInterruptedOperations() = normalizationLock.withLock {
        if (normalized) return@withLock
        database.withTransaction {
            knowledgeEntryDao.getEntriesByIndexStatus(KnowledgeIndexStatus.INDEXING)
                .filter { it.confirmedAt <= coldStartAt }.forEach {
                    knowledgeEntryDao.updateIndexStatus(it.id, KnowledgeIndexStatus.FAILED)
                }
            val messages = chatMessageDao.getMessages().map { it.toModel() }
            messages.filter { it.status == ChatMessageStatus.PENDING && it.updatedAt <= coldStartAt }.forEach {
                chatMessageDao.upsert(it.copy(
                    status = ChatMessageStatus.FAILED,
                    errorMessage = "上次问答已中断，问题已保留，请点击重试。",
                    errorCode = "CHAT_INTERRUPTED", progressStage = AiTaskStage.FAILED.name,
                    updatedAt = System.currentTimeMillis()
                ).toEntity())
            }
            // Older versions could die after writing the question but before writing any assistant state.
            val messagesBySession = chatMessageDao.getMessages().map { it.toModel() }.groupBy { it.sessionId }
            messages.filter { it.role == ChatRole.USER && it.updatedAt <= coldStartAt }.forEach { question ->
                val sessionMessages = messagesBySession[question.sessionId].orEmpty()
                val nextQuestionAt = sessionMessages.asSequence()
                    .filter { it.role == ChatRole.USER && it.createdAt > question.createdAt }
                    .minOfOrNull { it.createdAt }
                val hasAnswerState = sessionMessages.any { answer ->
                    answer.role == ChatRole.ASSISTANT && (
                        answer.parentMessageId == question.id ||
                            (answer.parentMessageId == null &&
                                answer.status in setOf(ChatMessageStatus.DONE, ChatMessageStatus.FAILED) &&
                                answer.createdAt >= question.createdAt &&
                                (nextQuestionAt == null || answer.createdAt < nextQuestionAt))
                        )
                }
                if (!hasAnswerState) {
                    saveAssistantMessage(
                        sessionId = question.sessionId, parentMessageId = question.id,
                        content = "上次问答已中断。", status = ChatMessageStatus.FAILED,
                        errorMessage = "问题已保留，请点击重试。", attempt = 1,
                        progressStage = AiTaskStage.FAILED.name, errorCode = "CHAT_INTERRUPTED",
                        citations = emptyList(), matchedResults = emptyList()
                    )
                }
            }
            audioTranscriptionJobDao.getJobs()
                .filter { it.updatedAt <= coldStartAt && it.status in setOf(
                    AudioTranscriptionJobStatus.UPLOADING, AudioTranscriptionJobStatus.TRANSCRIBING
                ) }.forEach { job ->
                    if (persistedAiTaskDao.getById(job.id) == null) {
                        audioTranscriptionJobDao.upsert(job.copy(
                            status = AudioTranscriptionJobStatus.FAILED,
                            errorMessage = "上次转写已中断，音频已保留，请点击重试。",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
        }
        // A crash after confirmation but before deleting the file leaves a retryable cleanup record.
        audioTranscriptionJobDao.getJobs().mapNotNull { it.noteId }.distinct().forEach { noteId ->
            val note = noteDao.getNote(noteId)
            if (note?.status == NoteStatus.REVIEWED || note?.status == NoteStatus.DELETED) {
                cleanupAudioForNote(noteId, System.currentTimeMillis())
            }
        }
        normalized = true
    }

    override suspend fun updateAudioTranscriptionJobStatus(
        id: String,
        status: AudioTranscriptionJobStatus,
        errorMessage: String?,
        retryIncrement: Int,
        lastTriedAt: Long?,
        updatedAt: Long
    ) = database.withTransaction {
        val existing = audioTranscriptionJobDao.getJob(id)?.toModel() ?: return@withTransaction
        if (existing.status in setOf(
            AudioTranscriptionJobStatus.SAVED, AudioTranscriptionJobStatus.AUDIO_CLEANED,
            AudioTranscriptionJobStatus.READY_TO_SAVE
        )) return@withTransaction
        audioTranscriptionJobDao.upsert(
            existing.copy(
                status = status,
                errorMessage = errorMessage,
                retryCount = existing.retryCount + retryIncrement,
                lastTriedAt = lastTriedAt ?: existing.lastTriedAt,
                updatedAt = updatedAt
            ).toEntity()
        )
    }

    override suspend fun markAudioTranscriptionJobSaved(
        id: String,
        noteId: String,
        transcriptText: String,
        updatedAt: Long
    ) = database.withTransaction {
        val existing = audioTranscriptionJobDao.getJob(id)?.toModel() ?: return@withTransaction
        if (existing.noteId != null) return@withTransaction
        require(noteDao.getNote(noteId) != null) { "录音笔记尚未保存。" }
        audioTranscriptionJobDao.upsert(
            existing.copy(
                status = AudioTranscriptionJobStatus.SAVED,
                noteId = noteId,
                transcriptText = transcriptText,
                errorMessage = null,
                updatedAt = updatedAt
            ).toEntity()
        )
        markAudioTaskApplied(id)
    }

    private suspend fun cleanupAudioForNote(noteId: String, now: Long) {
        audioTranscriptionJobDao.getJobsForNote(noteId)
            .filter { job -> job.audioDeletedAt == null && job.filePath.isNotBlank() }
            .forEach { entity ->
                val file = File(entity.filePath)
                val removed = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
                database.withTransaction {
                    val latest = audioTranscriptionJobDao.getJob(entity.id) ?: return@withTransaction
                    if (latest.audioDeletedAt != null || latest.filePath != entity.filePath) return@withTransaction
                    audioTranscriptionJobDao.upsert(if (removed) latest.copy(
                        status = AudioTranscriptionJobStatus.AUDIO_CLEANED, audioDeletedAt = now,
                        errorMessage = null, updatedAt = now
                    ) else latest.copy(
                        errorMessage = "笔记已保存，音频文件清理失败，下次启动会再次尝试。", updatedAt = now
                    ))
                }
            }
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

    private suspend fun upsertRemoteEmbeddingForEntry(entry: KnowledgeEntry): Boolean {
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
        return database.withTransaction {
            val current = knowledgeEntryDao.getEntry(entry.id)?.toModel() ?: return@withTransaction false
            if (current.toEmbeddingText() != text) return@withTransaction false
            embeddingDao.upsert(record.toEntity())
            knowledgeEntryDao.updateIndexStatus(entry.id, KnowledgeIndexStatus.INDEXED)
            true
        }
    }

    private suspend fun searchSimilarKnowledgeEntriesByVector(
        queryVector: FloatArray,
        model: String,
        topK: Int,
        requirePositiveScore: Boolean = true
    ): List<SemanticSearchResult> {
        if (queryVector.isEmpty() || queryVector.any { !it.isFinite() } || queryVector.all { it == 0f }) {
            return emptyList()
        }
        return embeddingDao.getRecordsByOwnerType(
            ownerType = EmbeddingRecord.OWNER_KNOWLEDGE_ENTRY,
            model = model
        )
            .mapNotNull { record ->
                val vector = LocalEmbeddingEngine.decodeCompatibleVector(
                    blob = record.vectorBlob,
                    dimensions = record.dimensions,
                    queryDimensions = queryVector.size
                ) ?: return@mapNotNull null
                val entry = knowledgeEntryDao.getEntry(record.ownerId)?.toModel()
                if (entry == null || entry.indexStatus != KnowledgeIndexStatus.INDEXED) {
                    null
                } else {
                    SemanticSearchResult(
                        entry = entry,
                        score = LocalEmbeddingEngine.dot(queryVector, vector)
                    )
                }
            }
            .filter { it.score.isFinite() && (!requirePositiveScore || it.score > 0f) }
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
            throw IllegalStateException("当前还没有知识条目，请先完成知识沉淀后再提问。")
        }
        if (entries.none { it.indexStatus == KnowledgeIndexStatus.INDEXED }) {
            throw IllegalStateException("知识库尚未完成沉淀，请先在知识库页完成沉淀后再提问。")
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
    ): ChatMessage = database.withTransaction {
        val stableId = "chat-assistant-$parentMessageId-$attempt"
        val existing = chatMessageDao.getMessage(stableId)?.toModel()
        if (existing?.status == ChatMessageStatus.DONE && status != ChatMessageStatus.DONE) {
            return@withTransaction existing
        }
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = stableId,
            sessionId = sessionId,
            parentMessageId = parentMessageId,
            role = ChatRole.ASSISTANT,
            content = content,
            status = status,
            errorMessage = errorMessage,
            attempt = attempt,
            progressStage = progressStage,
            errorCode = errorCode,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        chatMessageDao.upsert(message.toEntity())
        updateChatSessionOverview(sessionId, updatedAt = now)

        val resultById = matchedResults.associateBy { it.entry.id }
        val citationEntities = citations.mapIndexedNotNull { index, citation ->
            val result = resultById[citation.contextId] ?: return@mapIndexedNotNull null
            ChatCitation(
                id = "chat-citation-$stableId-$index",
                messageId = message.id,
                knowledgeEntryId = result.entry.id,
                sourceNoteIds = result.entry.sourceNoteIds,
                quote = citation.quote,
                reason = citation.reason,
                score = result.score,
                createdAt = now
            ).toEntity()
        }
        chatCitationDao.deleteForMessage(message.id)
        if (citationEntities.isNotEmpty()) {
            chatCitationDao.upsertAll(citationEntities)
        }
        message
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
                code == "DIRECT_API_ORGANIZE_EMPTY" -> publicMessage
                code == "DIRECT_API_INVALID_JSON" -> "模型返回格式异常，请检查当前模型是否支持稳定输出 JSON。"
                code == "DIRECT_API_EMBEDDING_FAILED" -> "Embedding API 调用失败，请检查模型名、Key 和服务额度。"
                code == "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED" -> publicMessage
                statusCode == 401 || code == "UNAUTHORIZED" -> "后端鉴权失败，请检查设置页中的 NAZHI_DEV_TOKEN。"
                code == "MINIMAX_ORGANIZE_EMPTY" -> publicMessage
                code == "MINIMAX_CHAT_FAILED" -> "模型回答处理失败，请稍后重试或检查后端日志。"
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

private fun buildCalendarFarmMarkers(
    noteSummaries: List<CalendarNoteFarmSummary>,
    draftSummaries: List<CalendarDraftFarmSummary>,
    knowledgeSummaries: List<CalendarKnowledgeFarmSummary>
): List<CalendarFarmMarker> {
    val notesByDate = noteSummaries.associateBy { it.date }
    val draftsByDate = draftSummaries.associateBy { it.date }
    val knowledgeByDate = knowledgeSummaries.associateBy { it.date }
    val dates = notesByDate.keys + draftsByDate.keys + knowledgeByDate.keys

    return dates
        .map { date ->
            val saplingCount = notesByDate[date].orZero { it.saplingUnits }.coerceAtMost(36)
            val plantCount = draftsByDate[date].orZero { it.plantUnits }.coerceAtMost(36)
            val matureCount = knowledgeByDate[date].orZero { it.matureUnits }.coerceAtMost(36)
            val issueCount = knowledgeByDate[date].orZero { it.failedIndexCount }
            CalendarFarmMarker(
                date = date,
                saplingCount = saplingCount,
                plantCount = plantCount,
                matureCount = matureCount,
                issueCount = issueCount,
                maturityScore = (saplingCount * 8 + plantCount * 18 + matureCount * 28).coerceIn(0, 100)
            )
        }
        .filter { it.hasFarmData }
        .sortedByDescending { it.date }
}

private inline fun <T> T?.orZero(selector: (T) -> Int): Int = this?.let(selector) ?: 0
