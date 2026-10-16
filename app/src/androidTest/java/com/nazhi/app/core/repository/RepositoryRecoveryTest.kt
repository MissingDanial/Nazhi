package com.nazhi.app.core.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.ChatSession
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.network.BackendConfig
import com.nazhi.app.core.network.NazhiBackendClient
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production repository against real SQLite, including failures during transactions. */
@RunWith(AndroidJUnit4::class)
class RepositoryRecoveryTest {
    private lateinit var database: NazhiDatabase
    private lateinit var repository: LocalNazhiRepository
    private lateinit var backendClient: NazhiBackendClient
    private lateinit var backend: StubBackend
    private lateinit var context: Context
    private val audioFiles = mutableListOf<File>()
    private val past = System.currentTimeMillis() - 60_000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NazhiDatabase::class.java).build()
        backend = StubBackend()
        backendClient = NazhiBackendClient(configProvider = {
            BackendConfig(baseUrl = backend.baseUrl, devToken = "test-only-token")
        })
        repository = LocalNazhiRepository(
            database = database,
            persistedAiTaskDao = database.persistedAiTaskDao(),
            noteDao = database.noteDao(),
            knowledgeEntryDao = database.knowledgeEntryDao(),
            knowledgeEntryDraftDao = database.knowledgeEntryDraftDao(),
            reviewSessionDao = database.reviewSessionDao(),
            embeddingDao = database.embeddingDao(),
            chatSessionDao = database.chatSessionDao(),
            chatMessageDao = database.chatMessageDao(),
            chatCitationDao = database.chatCitationDao(),
            audioTranscriptionJobDao = database.audioTranscriptionJobDao(),
            backendClient = backendClient
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        audioFiles.forEach { it.delete() }
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun floatingAudioRepeatedResultCreatesOneNoteAndCommitsTaskWithJob() = runBlocking {
        val job = seedAudio("floating-audio")

        val first = repository.storeAudioTranscript(job.id, "第一份可靠的转写结果。")
        val duplicate = repository.storeAudioTranscript(job.id, "重复回调不能覆盖第一份结果。")

        assertEquals(AudioTranscriptionJobStatus.SAVED, first.status)
        assertEquals(first.noteId, duplicate.noteId)
        val notes = database.noteDao().getNotes().map { it.toModel() }
        assertEquals(1, notes.size)
        assertEquals("第一份可靠的转写结果。", notes.single().content)
        assertEquals(SourceType.AUDIO_TRANSCRIPTION, notes.single().sourceType)
        assertEquals(job.durationMs, notes.single().audioDurationMs)
        assertEquals(first.noteId, database.audioTranscriptionJobDao().getJob(job.id)?.noteId)
        assertEquals("APPLIED", repository.getPersistedAiTask(job.id)?.status)
        assertTrue(File(job.filePath).exists())
        assertEquals(0, backend.totalRequests.get())
    }

    @Test
    fun inAppAudioWaitsForConfirmationAndRepeatedSavePreservesUserEdits() = runBlocking {
        val job = seedAudio("in-app-audio", requiresConfirmation = true)
        val ready = repository.storeAudioTranscript(job.id, "模型原始转写。")

        assertEquals(AudioTranscriptionJobStatus.READY_TO_SAVE, ready.status)
        assertNull(ready.noteId)
        assertTrue(database.noteDao().getNotes().isEmpty())
        assertEquals("APPLIED", repository.getPersistedAiTask(job.id)?.status)
        repository.updateAudioTranscriptPreview(job.id, "用户校订后的转写。")
        val repeated = repository.storeAudioTranscript(job.id, "晚到的旧模型结果。")
        assertEquals("用户校订后的转写。", repeated.transcriptText)

        val saved = repository.confirmAudioTranscript(job.id, "用户校订后的转写。")
        val edited = saved.copy(content = "笔记保存后继续编辑的内容。", userRemark = "保留我的修改")
        repository.saveNote(edited)
        val secondSave = repository.confirmAudioTranscript(job.id, "重复点击带来的旧文本。")

        assertEquals(edited, secondSave)
        assertEquals(1, database.noteDao().getNotes().size)
        assertEquals(AudioTranscriptionJobStatus.SAVED, repository.getAudioTranscriptionJob(job.id)?.status)
        assertEquals(saved.id, repository.getAudioTranscriptionJob(job.id)?.noteId)
        assertEquals("APPLIED", repository.getPersistedAiTask(job.id)?.status)
        assertEquals(0, backend.totalRequests.get())
    }

    @Test
    fun repeatedDraftConfirmationCreatesOneDurableEntryEvenWhenIndexingFails() = runBlocking {
        val source = note("source-for-confirmation")
        val draft = draft("draft-for-confirmation", source.id)
        repository.saveNote(source)
        repository.updateKnowledgeDraft(draft)

        val first = requireNotNull(repository.submitKnowledgeDraft(draft.id))
        val repeated = requireNotNull(repository.submitKnowledgeDraft(draft.id))

        assertEquals(first.id, repeated.id)
        assertEquals(1, database.knowledgeEntryDao().getEntries().size)
        assertEquals(KnowledgeIndexStatus.FAILED, repeated.indexStatus)
        assertEquals(NoteStatus.REVIEWED, repository.getNote(source.id)?.status)
        assertEquals(KnowledgeDraftStatus.CONFIRMED, database.knowledgeEntryDraftDao().getDraft(draft.id)?.status)
        assertEquals(1, backend.embeddingRequests.get())
        val edited = repeated.copy(content = "用户修改过的知识内容。", userTitle = "用户修改过的标题")
        repository.updateKnowledgeEntry(edited, reindex = false)
        assertEquals(edited.copy(indexStatus = KnowledgeIndexStatus.PENDING), repository.submitKnowledgeDraft(draft.id))
        assertEquals(1, backend.embeddingRequests.get())
    }

    @Test
    fun failureUpdatingSourceRollsBackEntryAndDraftConfirmation() = runBlocking {
        val source = note("source-with-failing-status")
        val draft = draft("draft-with-failing-status", source.id)
        val existing = unrelatedEntry("entry-already-saved")
        repository.saveNote(source)
        repository.updateKnowledgeDraft(draft)
        saveUnrelatedEntry(existing)
        val originalDraft = database.knowledgeEntryDraftDao().getDraft(draft.id)
        execSql("""
            CREATE TRIGGER fail_source_review BEFORE UPDATE ON notes
            WHEN NEW.status = 'REVIEWED'
            BEGIN SELECT RAISE(ABORT, 'injected_note_abort'); END
        """.trimIndent())

        val failure = runCatching { repository.submitKnowledgeDraft(draft.id) }.exceptionOrNull()

        assertSqlAbort(failure, "injected_note_abort")
        assertEquals(source, repository.getNote(source.id))
        assertEquals(originalDraft, database.knowledgeEntryDraftDao().getDraft(draft.id))
        assertEquals(listOf(existing), database.knowledgeEntryDao().getEntries().map { it.toModel() })
        assertNull(repository.getKnowledgeEntry("knowledge-draft-${draft.id}"))
        assertEquals(0, backend.totalRequests.get())
    }

    @Test
    fun draftReplacementFailureKeepsOldDraftAndRecoveryAppliesAcceptedResultOnce() = runBlocking {
        val source = note("source-for-organize")
        val oldDraft = draft("old-draft", source.id)
        val confirmedEntry = unrelatedEntry("confirmed-entry-before-organize")
        repository.saveNote(source)
        repository.updateKnowledgeDraft(oldDraft)
        saveUnrelatedEntry(confirmedEntry)
        val originalDraft = database.knowledgeEntryDraftDao().getDraft(oldDraft.id)
        execSql("""
            CREATE TRIGGER fail_new_draft BEFORE INSERT ON knowledge_entry_drafts
            WHEN NEW.id != 'old-draft'
            BEGIN SELECT RAISE(ABORT, 'injected_draft_abort'); END
        """.trimIndent())

        val failure = runCatching { repository.organizeNotesForDate(DATE) {} }.exceptionOrNull()

        assertSqlAbort(failure, "injected_draft_abort")
        assertEquals(listOf(originalDraft), database.knowledgeEntryDraftDao().getDraftsForDate(DATE))
        assertEquals(source, repository.getNote(source.id))
        assertEquals(confirmedEntry, repository.getKnowledgeEntry(confirmedEntry.id))
        val acceptedTask = requireNotNull(database.persistedAiTaskDao().getLatestForDate(DATE, "ORGANIZE_NOTES"))
        assertEquals("WAITING", acceptedTask.status)
        assertEquals("test-organize-task", acceptedTask.backendTaskId)
        assertEquals(1, backend.organizePosts.get())
        execSql("DROP TRIGGER fail_new_draft")

        assertEquals(1, repository.recoverOrganizeTask(acceptedTask.toModel()) {})
        assertEquals(0, repository.recoverOrganizeTask(acceptedTask.toModel()) {})
        val recoveredDrafts = database.knowledgeEntryDraftDao().getDraftsForDate(DATE)
        assertEquals(1, recoveredDrafts.size)
        assertEquals("恢复后的整理草稿。", recoveredDrafts.single().content)
        assertEquals("APPLIED", repository.getPersistedAiTask(acceptedTask.id)?.status)
        assertEquals(1, backend.organizePosts.get())
        assertEquals(1, backend.organizeQueries.get())
        assertEquals(confirmedEntry, repository.getKnowledgeEntry(confirmedEntry.id))
    }

    @Test
    fun coldStartMarksInterruptedIndexChatAndLegacyAudioForExplicitRetryWithoutLosingContent() = runBlocking {
        val source = note("retained-source")
        val indexing = unrelatedEntry("interrupted-index").copy(indexStatus = KnowledgeIndexStatus.INDEXING)
        repository.saveNote(source)
        saveUnrelatedEntry(indexing)
        val legacyAudio = audioJob("legacy-upload", requiresConfirmation = false)
        // Seed pre-start records without triggering the repository's one-time cold-start normalization.
        database.audioTranscriptionJobDao().upsert(legacyAudio.copy(status = AudioTranscriptionJobStatus.UPLOADING).toEntity())
        database.chatSessionDao().upsert(ChatSession(
            id = "interrupted-chat", title = "保留问题", createdAt = past, updatedAt = past
        ).toEntity())
        val question = ChatMessage(
            id = "retained-question", sessionId = "interrupted-chat", role = ChatRole.USER,
            content = "原始问题仍然需要保存。", status = ChatMessageStatus.DONE, errorMessage = null,
            createdAt = past, updatedAt = past
        )
        val answer = ChatMessage(
            id = "pending-answer", sessionId = question.sessionId, parentMessageId = question.id,
            role = ChatRole.ASSISTANT, content = "", status = ChatMessageStatus.PENDING,
            errorMessage = null, createdAt = past, updatedAt = past
        )
        database.chatMessageDao().upsert(question.toEntity())
        database.chatMessageDao().upsert(answer.toEntity())

        repository.normalizeInterruptedOperations()
        repository.normalizeInterruptedOperations()

        assertEquals(source, repository.getNote(source.id))
        assertEquals(indexing.content, repository.getKnowledgeEntry(indexing.id)?.content)
        assertEquals(KnowledgeIndexStatus.FAILED, repository.getKnowledgeEntry(indexing.id)?.indexStatus)
        assertEquals(question, database.chatMessageDao().getMessage(question.id)?.toModel())
        val failedAnswer = requireNotNull(database.chatMessageDao().getMessage(answer.id))
        assertEquals(ChatMessageStatus.FAILED, failedAnswer.status)
        assertEquals("CHAT_INTERRUPTED", failedAnswer.errorCode)
        assertEquals(2, database.chatMessageDao().getMessages().size)
        assertEquals(AudioTranscriptionJobStatus.FAILED, repository.getAudioTranscriptionJob(legacyAudio.id)?.status)
        assertTrue(File(legacyAudio.filePath).exists())
        assertEquals(0, backend.totalRequests.get())
    }

    @Test
    fun legacySuccessfulAnswersWithoutParentIdsArePreservedAndOnlyActualOrphanGetsRetryMarker() = runBlocking {
        val sessionId = "legacy-chat-without-parent-ids"
        database.chatSessionDao().upsert(ChatSession(
            id = sessionId, title = "旧版本对话", createdAt = past, updatedAt = past + 4
        ).toEntity())
        fun message(id: String, role: ChatRole, content: String, offset: Long) = ChatMessage(
            id = id, sessionId = sessionId, role = role, content = content,
            status = ChatMessageStatus.DONE, errorMessage = null,
            createdAt = past + offset, updatedAt = past + offset
        )
        val legacyMessages = listOf(
            message("legacy-question-1", ChatRole.USER, "旧问题一", 0),
            message("legacy-answer-1", ChatRole.ASSISTANT, "旧版本成功回答一", 1),
            message("legacy-question-2", ChatRole.USER, "旧问题二", 2),
            message("legacy-answer-2", ChatRole.ASSISTANT, "旧版本成功回答二", 3),
            message("legacy-orphan-question", ChatRole.USER, "写入后没有收到回答的问题", 4)
        )
        legacyMessages.forEach { database.chatMessageDao().upsert(it.toEntity()) }

        repository.normalizeInterruptedOperations()
        repository.normalizeInterruptedOperations()

        legacyMessages.forEach { assertEquals(it, database.chatMessageDao().getMessage(it.id)?.toModel()) }
        val recovered = database.chatMessageDao().getMessagesForSession(sessionId).map { it.toModel() }
        assertEquals(6, recovered.size)
        val failureMarkers = recovered.filter { it.status == ChatMessageStatus.FAILED }
        assertEquals(1, failureMarkers.size)
        assertEquals("legacy-orphan-question", failureMarkers.single().parentMessageId)
        assertEquals("CHAT_INTERRUPTED", failureMarkers.single().errorCode)
        assertEquals(0, backend.totalRequests.get())
    }

    @Test
    fun failureSavingAudioJobRollsBackItsNoteAndAppliedTaskMarker() = runBlocking {
        val job = seedAudio("audio-save-abort")
        execSql("""
            CREATE TRIGGER fail_audio_save BEFORE UPDATE ON audio_transcription_jobs
            WHEN NEW.status = 'SAVED'
            BEGIN SELECT RAISE(ABORT, 'injected_audio_abort'); END
        """.trimIndent())

        val failure = runCatching { repository.storeAudioTranscript(job.id, "不能保存一半的转写结果。") }.exceptionOrNull()

        assertSqlAbort(failure, "injected_audio_abort")
        assertTrue(database.noteDao().getNotes().isEmpty())
        assertEquals(job, repository.getAudioTranscriptionJob(job.id))
        assertEquals("RUNNING", repository.getPersistedAiTask(job.id)?.status)
        assertTrue(File(job.filePath).exists())
        execSql("DROP TRIGGER fail_audio_save")
        repository.storeAudioTranscript(job.id, "重试后完整保存。")
        assertEquals(1, database.noteDao().getNotes().size)
        assertEquals(AudioTranscriptionJobStatus.SAVED, repository.getAudioTranscriptionJob(job.id)?.status)
        assertEquals("APPLIED", repository.getPersistedAiTask(job.id)?.status)
    }

    @Test
    fun editingDraftDuringRecoveryKeepsEditedDraftAndInterruptsAcceptedResult() = runBlocking {
        val fixture = seedInterruptedOrganize("draft-edit-race")
        val gate = backend.blockNextOrganizeQuery()
        val recovery = async(Dispatchers.IO) { runCatching { repository.recoverOrganizeTask(fixture.task) {} } }
        try {
            assertTrue("Recovery must reach the existing task GET.", gate.entered.await(5, TimeUnit.SECONDS))
            val edited = fixture.draft.copy(
                content = "用户在后台恢复期间修改过的草稿。", updatedAt = fixture.task.createdAt + 60_000
            )
            database.knowledgeEntryDraftDao().upsert(edited.toEntity())
            gate.release.countDown()

            assertNotNull(recovery.await().exceptionOrNull())
            assertEquals(edited, database.knowledgeEntryDraftDao().getDraft(edited.id)?.toModel())
            assertEquals(listOf(edited), database.knowledgeEntryDraftDao().getDraftsForDate(DATE).map { it.toModel() })
            assertEquals("INTERRUPTED", repository.getPersistedAiTask(fixture.task.id)?.status)
            assertEquals(1, backend.organizePosts.get())
            assertEquals(1, backend.organizeQueries.get())
        } finally {
            gate.release.countDown()
            recovery.cancel()
        }
    }

    @Test
    fun editingSourceDuringRecoveryKeepsCurrentNoteAndOldDraftAndInterruptsResult() = runBlocking {
        val fixture = seedInterruptedOrganize("source-edit-race")
        val gate = backend.blockNextOrganizeQuery()
        val recovery = async(Dispatchers.IO) { runCatching { repository.recoverOrganizeTask(fixture.task) {} } }
        try {
            assertTrue("Recovery must reach the existing task GET.", gate.entered.await(5, TimeUnit.SECONDS))
            repository.updateNoteContent(
                id = fixture.source.id, content = "用户在恢复结果返回前编辑的原笔记。", title = fixture.source.title,
                sourceUrl = null, userRemark = null, updatedAt = fixture.task.createdAt + 60_000
            )
            gate.release.countDown()

            assertNotNull(recovery.await().exceptionOrNull())
            assertEquals("用户在恢复结果返回前编辑的原笔记。", repository.getNote(fixture.source.id)?.content)
            assertEquals(fixture.draft, database.knowledgeEntryDraftDao().getDraft(fixture.draft.id)?.toModel())
            assertEquals("INTERRUPTED", repository.getPersistedAiTask(fixture.task.id)?.status)
            assertEquals(1, backend.organizePosts.get())
            assertEquals(1, backend.organizeQueries.get())
        } finally {
            gate.release.countDown()
            recovery.cancel()
        }
    }

    private data class OrganizeFixture(val source: Note, val draft: KnowledgeEntryDraft, val task: PersistedAiTask)

    private suspend fun seedInterruptedOrganize(prefix: String): OrganizeFixture {
        val source = note("source-$prefix")
        val originalDraft = draft("draft-$prefix", source.id)
        repository.saveNote(source)
        repository.updateKnowledgeDraft(originalDraft)
        execSql("""
            CREATE TRIGGER fixture_draft_abort BEFORE INSERT ON knowledge_entry_drafts
            WHEN NEW.id != '${originalDraft.id}'
            BEGIN SELECT RAISE(ABORT, 'injected_fixture_abort'); END
        """.trimIndent())
        assertSqlAbort(runCatching { repository.organizeNotesForDate(DATE) {} }.exceptionOrNull(), "injected_fixture_abort")
        execSql("DROP TRIGGER fixture_draft_abort")
        return OrganizeFixture(
            source = source,
            draft = requireNotNull(database.knowledgeEntryDraftDao().getDraft(originalDraft.id)).toModel(),
            task = requireNotNull(database.persistedAiTaskDao().getLatestForDate(DATE, "ORGANIZE_NOTES")).toModel()
        )
    }

    private suspend fun seedAudio(id: String, requiresConfirmation: Boolean = false): AudioTranscriptionJob {
        val job = audioJob(id, requiresConfirmation)
        repository.saveAudioTranscriptionJob(job)
        repository.savePersistedAiTask(PersistedAiTask(
            id = id, type = "AUDIO_TRANSCRIPTION", requestId = "request-$id",
            bindingKey = backendClient.currentTaskBinding(forAudio = true), date = DATE,
            backendTaskId = "remote-$id", status = "RUNNING", createdAt = past, updatedAt = past
        ))
        return job
    }

    private fun audioJob(id: String, requiresConfirmation: Boolean): AudioTranscriptionJob {
        val audio = File.createTempFile("recovery-audio-", ".wav", context.cacheDir)
        audio.writeBytes(byteArrayOf(1, 2, 3, 4))
        audioFiles += audio
        return AudioTranscriptionJob(
            id = id, sourceApp = "instrumentation", backendSource = "test",
            filePath = audio.absolutePath, durationMs = 1_000, byteSize = audio.length(),
            status = AudioTranscriptionJobStatus.TRANSCRIBING,
            createdAt = past, createdDate = DATE, updatedAt = past,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun note(id: String) = Note(
        id = id, content = "可靠地保存原笔记。", title = "原笔记", sourceType = SourceType.MANUAL,
        sourceApp = null, sourceUrl = null, createdAt = past, createdDate = DATE,
        updatedAt = past, status = NoteStatus.INBOX, userRemark = null
    )

    private fun draft(id: String, sourceId: String) = KnowledgeEntryDraft(
        id = id, date = DATE, title = "事务保证", summary = "保存知识需要事务",
        content = "事务提交应同时保存知识、来源状态和草稿状态。", intentType = IntentType.READ_LATER,
        tags = listOf("事务"), sourceNoteIds = listOf(sourceId), evidenceQuotes = listOf("可靠地保存原笔记。"),
        insight = null, confidence = 0.95f, needsReview = false, status = KnowledgeDraftStatus.PENDING,
        createdAt = past, updatedAt = past
    )

    private fun unrelatedEntry(id: String) = KnowledgeEntry(
        id = id, noteId = "unrelated-note", content = "另一条已经入库的数学证明：素数无穷多。",
        intentType = IntentType.QUOTABLE, userTitle = "数学证明", userRemark = null,
        createdAt = past, createdDate = DATE, confirmedAt = past, confirmedDate = DATE,
        indexStatus = KnowledgeIndexStatus.INDEXED
    )

    private suspend fun saveUnrelatedEntry(entry: KnowledgeEntry) {
        // knowledge_entries.noteId is a foreign key; reviewed sources do not join a new organize job.
        repository.saveNote(note(entry.noteId).copy(content = entry.content, status = NoteStatus.REVIEWED))
        repository.saveKnowledgeEntry(entry)
    }

    private fun execSql(sql: String) = database.openHelper.writableDatabase.execSQL(sql)

    private fun assertSqlAbort(error: Throwable?, marker: String) {
        assertNotNull("The injected SQLite failure must reach the caller.", error)
        assertTrue("Expected SQLite failure $marker, got $error", generateSequence(requireNotNull(error)) { it.cause }
            .any { it.message.orEmpty().contains(marker) })
    }

    private companion object {
        const val DATE = "2026-10-13"
    }

    /** Device-local HTTP boundary: embeddings fail deterministically; organize results can be queried. */
    private class StubBackend : Closeable {
        private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        private var organizeResult: String? = null
        private val nextQuery = AtomicReference<QueryGate?>()
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val totalRequests = AtomicInteger()
        val embeddingRequests = AtomicInteger()
        val organizePosts = AtomicInteger()
        val organizeQueries = AtomicInteger()

        class QueryGate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

        fun blockNextOrganizeQuery(): QueryGate = QueryGate().also { nextQuery.set(it) }

        private val worker = thread(start = true, isDaemon = true, name = "repository-test-backend") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().buffered()
                    val requestLine = readLine(input).split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val contentLength = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(contentLength)
                    var consumed = 0
                    while (consumed < body.size) {
                        val count = input.read(body, consumed, body.size - consumed)
                        if (count < 0) throw IOException("Test request ended before its body was complete.")
                        consumed += count
                    }
                    totalRequests.incrementAndGet()
                    val (status, response) = respond(requestLine[0], requestLine[1], body.toString(Charsets.UTF_8))
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 $status Test\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }

        private fun respond(method: String, path: String, body: String): Pair<Int, String> {
            if (method == "POST" && path == "/v1/embeddings") {
                embeddingRequests.incrementAndGet()
                return 500 to """{"error":{"code":"TEST_EMBEDDING_UNAVAILABLE","message":"Local test embedding service is unavailable."}}"""
            }
            if (method == "POST" && path == "/v1/organize-notes/jobs") {
                organizePosts.incrementAndGet()
                val request = JSONObject(body)
                val sourceId = request.getJSONArray("notes").getJSONObject(0).getString("id")
                val result = JSONObject()
                    .put("requestId", request.getString("requestId"))
                    .put("date", request.getString("date"))
                    .put("drafts", JSONArray().put(JSONObject()
                        .put("title", "恢复草稿").put("content", "恢复后的整理草稿。")
                        .put("sourceNoteIds", JSONArray().put(sourceId)).put("confidence", 0.95)))
                organizeResult = JSONObject()
                    .put("taskId", "test-organize-task").put("requestId", request.getString("requestId"))
                    .put("type", "ORGANIZE_NOTES").put("status", "SUCCEEDED").put("stage", "DONE")
                    .put("progress", 100).put("result", result).toString()
                return 202 to requireNotNull(organizeResult)
            }
            if (method == "GET" && path == "/v1/tasks/test-organize-task") {
                organizeQueries.incrementAndGet()
                nextQuery.getAndSet(null)?.let { gate ->
                    gate.entered.countDown()
                    check(gate.release.await(10, TimeUnit.SECONDS)) { "Test did not release the blocked task query." }
                }
                return 200 to requireNotNull(organizeResult)
            }
            return 404 to """{"error":{"code":"TASK_NOT_FOUND","message":"Task is absent from the local test backend."}}"""
        }

        override fun close() {
            server.close()
            worker.join(1_000)
            assertFalse("The device-local HTTP worker must exit.", worker.isAlive)
            failure.get()?.let { throw AssertionError("Device-local HTTP backend failed.", it) }
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) throw IOException("Unexpected end of test HTTP headers.")
                if (value == '\n'.code) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
        }
    }
}
