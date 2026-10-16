package com.nazhi.app.core.tasks

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nazhi.app.NazhiApp
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
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
import com.nazhi.app.core.repository.LocalNazhiRepository
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Production store and repository against real SQLite; all providers are local test fixtures. */
@RunWith(AndroidJUnit4::class)
class PendingTasksIntegrationTest {
    private lateinit var database: NazhiDatabase
    private lateinit var repository: LocalNazhiRepository
    private lateinit var backendClient: NazhiBackendClient
    private lateinit var backend: CountingBackend
    private lateinit var applicationScope: CoroutineScope
    private lateinit var store: PendingTaskStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NazhiDatabase::class.java).build()
        backend = CountingBackend()
        backendClient = NazhiBackendClient(
            configProvider = { BackendConfig(backend.baseUrl, "pending-tasks-test-only") },
            accountIdProvider = { "current-test-user" }
        )
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
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = PendingTaskStore(database, repository, backendClient, applicationScope)
    }

    @After
    fun tearDown() {
        if (::applicationScope.isInitialized) applicationScope.cancel()
        if (::database.isInitialized) database.close()
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun observingAcrossDatesIncludesOnlyOutstandingLatestAttemptsAndNeverCallsProvider() = runBlocking {
        val binding = backendClient.currentTaskBinding()
        repository.savePersistedAiTask(task("waiting", "2026-10-11", "WAITING", binding))
        repository.savePersistedAiTask(task("failed", "2026-10-12", "FAILED", binding))
        val older = task("obsolete-failure", "2026-10-10", "FAILED", binding)
        repository.savePersistedAiTask(older)
        repository.savePersistedAiTask(older.copy(
            id = "completed-organize", requestId = "completed-organize-request", status = "APPLIED",
            createdAt = older.createdAt + 1, updatedAt = older.updatedAt + 1
        ))
        repository.saveNote(note("failed-index-source", "2026-10-13"))
        repository.saveKnowledgeEntry(entry("failed-index", "failed-index-source", "2026-10-13", KnowledgeIndexStatus.FAILED))
        repository.saveNote(note("indexed-source", "2026-10-10"))
        repository.saveKnowledgeEntry(entry("already-indexed", "indexed-source", "2026-10-10", KnowledgeIndexStatus.INDEXED))
        // Ordinary inbox content and an unconfirmed draft are not submitted tasks.
        val untouched = note("ordinary-inbox", "2026-10-08")
        repository.saveNote(untouched)
        repository.updateKnowledgeDraft(draft("ordinary-draft", untouched.id, untouched.createdDate))

        val session = ChatSession("history", "原问题", messageCount = 6,
            createdAt = timestamp("2026-10-09"), updatedAt = timestamp("2026-10-09") + 5)
        database.chatSessionDao().upsert(session.toEntity())
        val question = message("question", session.id, ChatRole.USER, "原问题仍然需要回答。", "2026-10-09")
        val failed1 = message("old-failed-answer", session.id, ChatRole.ASSISTANT, "", "2026-10-09", question.id, ChatMessageStatus.FAILED)
        val failed2 = failed1.copy(id = "latest-failed-answer", attempt = 2, createdAt = failed1.createdAt + 1)
        val answeredQuestion = message("answered-question", session.id, ChatRole.USER, "已经回答的问题", "2026-10-09")
        val obsoleteAnswer = failed1.copy(id = "obsolete-answer", parentMessageId = answeredQuestion.id)
        val completedAnswer = obsoleteAnswer.copy(id = "completed-answer", attempt = 2,
            content = "已经完成的回答。", status = ChatMessageStatus.DONE, errorMessage = null,
            createdAt = obsoleteAnswer.createdAt + 2)
        listOf(question, failed1, failed2, answeredQuestion, obsoleteAnswer, completedAnswer)
            .forEach { database.chatMessageDao().upsert(it.toEntity()) }

        val pending = withTimeout(10_000) { store.tasks.first() }

        assertEquals(setOf("waiting", "failed", "failed-index", "latest-failed-answer"), pending.map { it.id }.toSet())
        assertEquals(setOf("2026-10-09", "2026-10-11", "2026-10-12", "2026-10-13"), pending.map { it.date }.toSet())
        assertEquals(PendingTaskAction.QUERY_EXISTING, pending.single { it.id == "waiting" }.action)
        assertEquals(PendingTaskAction.RETRY_MODEL, pending.single { it.id == "failed" }.action)
        assertEquals("原问题仍然需要回答。", pending.single { it.kind == PendingTaskKind.CHAT }.title)
        assertEquals(session.id, pending.single { it.kind == PendingTaskKind.CHAT }.targetId)
        assertNoRequests()
    }

    @Test
    fun staleQuerySelectionDoesNotSubmitWhenTaskHasFailedSinceItWasDisplayed() = runBlocking {
        val waiting = task("stale-failed", "2026-10-11", "WAITING", backendClient.currentTaskBinding())
        repository.savePersistedAiTask(waiting)
        val selected = withTimeout(10_000) { store.tasks.first() }.single()
        val failed = waiting.copy(status = "FAILED", updatedAt = waiting.updatedAt + 1)
        repository.savePersistedAiTask(failed)

        val message = withTimeout(10_000) { store.perform(selected) }

        assertTrue(message.contains("状态已变化"))
        assertEquals(failed, repository.getPersistedAiTask(waiting.id))
        assertEquals(PendingTaskAction.RETRY_MODEL, store.tasks.first().single().action)
        assertNoRequests()
    }

    @Test
    fun staleSelectionDisappearsAfterApplicationAndNeverSubmitsAgain() = runBlocking {
        val waiting = task("stale-applied", "2026-10-12", "WAITING", backendClient.currentTaskBinding())
        repository.savePersistedAiTask(waiting)
        val selected = withTimeout(10_000) { store.tasks.first() }.single()
        val applied = waiting.copy(status = "APPLIED", updatedAt = waiting.updatedAt + 1)
        repository.savePersistedAiTask(applied)

        val message = withTimeout(10_000) { store.perform(selected) }

        assertTrue(message.contains("任务已处理"))
        assertTrue(store.tasks.first().isEmpty())
        assertEquals(applied, repository.getPersistedAiTask(waiting.id))
        assertNoRequests()
    }

    @Test
    fun mismatchedOriginalBindingRefusesBothQueryAndRetryWithoutChangingService() = runBlocking {
        val oldBinding = "nazhi:original-account-and-backend"
        val query = task("different-account-query", "2026-10-11", "WAITING", oldBinding)
        val retry = task("different-account-retry", "2026-10-12", "FAILED", oldBinding)
        repository.savePersistedAiTask(query)
        repository.savePersistedAiTask(retry)
        val originalCurrentBinding = backendClient.currentTaskBinding()
        val selections = withTimeout(10_000) { store.tasks.first() }

        selections.forEach { selected ->
            val result = withTimeout(10_000) { store.perform(selected) }
            assertTrue(result.contains("切回提交任务时的账号"))
        }

        assertEquals(originalCurrentBinding, backendClient.currentTaskBinding())
        assertEquals(query, repository.getPersistedAiTask(query.id))
        assertEquals(retry, repository.getPersistedAiTask(retry.id))
        assertNoRequests()
    }

    @Test
    fun editedPendingDraftBlocksNewOrganizeAndPreservesItsSourceAndIdentity() = runBlocking {
        val source = note("edited-draft-source", "2026-10-11")
        repository.saveNote(source)
        val edited = draft("edited-draft", source.id, source.createdDate).let {
            it.copy(content = "用户校订过的草稿全文。", updatedAt = it.createdAt + 1000)
        }
        repository.updateKnowledgeDraft(edited)
        val savedDraft = database.knowledgeEntryDraftDao().getDraft(edited.id)?.toModel()
        val failed = task("organize-with-edited-draft", source.createdDate, "FAILED", backendClient.currentTaskBinding())
            .copy(sourceNoteIds = listOf(source.id))
        repository.savePersistedAiTask(failed)
        val selected = withTimeout(10_000) { store.tasks.first() }.single()

        val result = withTimeout(10_000) { store.perform(selected) }

        assertTrue(result.contains("编辑过的待确认草稿"))
        assertEquals(source, repository.getNote(source.id))
        assertEquals(savedDraft, database.knowledgeEntryDraftDao().getDraft(edited.id)?.toModel())
        assertEquals(failed, repository.getPersistedAiTask(failed.id))
        assertEquals(1, database.persistedAiTaskDao().observeAll().first().size)
        assertNoRequests()
    }

    /** Seeds the actual app only on a disposable emulator, explicitly requested via instrumentation. */
    @Test
    fun manualUiSeedForPendingTasks() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskUiPhase") == "seed")
        assumeTrue("The manual fixture must not touch a physical phone.",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true))
        val app = ApplicationProvider.getApplicationContext<NazhiApp>()
        val container = app.appContainer
        val mainRepository = container.repository
        // Keep the manual scenario isolated from any real backend or model configuration.
        container.backendSettingsStore.save(BackendConfig("http://127.0.0.1:1", "task-ui-only"))
        delay(2000)
        val binding = container.backendClient.currentTaskBinding()
        val original = note("task-ui-organize-source", "2026-10-11").copy(
            title = "中断后保留的原始笔记", content = "这是十月十一日收纳的原始文字，整理失败后仍可完整查看。"
        )
        mainRepository.saveNote(original)
        mainRepository.updateKnowledgeDraft(draft("task-ui-edited-draft", original.id, original.createdDate).let {
            it.copy(title = "用户编辑过的待确认草稿", content = "这份草稿已经手动校订，请保留我的编辑。", updatedAt = it.createdAt + 1000)
        })
        mainRepository.savePersistedAiTask(task("task-ui-failed-organize", original.createdDate, "FAILED", binding)
            .copy(sourceNoteIds = listOf(original.id), errorMessage = "原笔记已保留，可先查看这一天的草稿。"))
        val waitingSource = note("task-ui-waiting-source", "2026-10-12").copy(
            title = "等待原任务的笔记", content = "这是十月十二日的原始文字，等待切回提交任务时的配置。"
        )
        mainRepository.saveNote(waitingSource)
        mainRepository.savePersistedAiTask(task("task-ui-waiting-organize", waitingSource.createdDate, "WAITING", "nazhi:task-ui-original-profile")
            .copy(sourceNoteIds = listOf(waitingSource.id), errorMessage = "请切回提交任务时的账号和服务配置后继续查询。"))
        val indexSource = note("task-ui-index-source", "2026-10-13").copy(content = "知识来源原文：先保存正文，再处理索引。")
        mainRepository.saveNote(indexSource)
        mainRepository.saveKnowledgeEntry(entry("task-ui-failed-index", indexSource.id, indexSource.createdDate, KnowledgeIndexStatus.FAILED)
            .copy(userTitle = "正文已经可靠保存", content = "索引中断不会丢失这条知识正文。查看详情还可以找到原始笔记。"))
        // A second connection to the same Room database lets the fixture seed chat without invoking AI.
        val mainDatabase = NazhiDatabase.create(app)
        try {
            val session = ChatSession("task-ui-chat", "保留下来的原问题", messageCount = 2,
                lastMessagePreview = "任务中断后，原问题会保留吗？", createdAt = timestamp("2026-10-10"), updatedAt = timestamp("2026-10-10") + 1)
            mainDatabase.chatSessionDao().upsert(session.toEntity())
            val question = message("task-ui-question", session.id, ChatRole.USER, "任务中断后，原问题会保留吗？", "2026-10-10")
            val answer = message("task-ui-answer", session.id, ChatRole.ASSISTANT, "", "2026-10-10", question.id, ChatMessageStatus.FAILED)
                .copy(errorMessage = "回答已中断，原问题仍然保留。")
            mainDatabase.chatMessageDao().upsert(question.toEntity())
            mainDatabase.chatMessageDao().upsert(answer.toEntity())
        } finally {
            mainDatabase.close()
        }
        val actual = withTimeout(10_000) { container.pendingTaskStore.tasks.first { list ->
            list.count { it.id.startsWith("task-ui-") } == 4
        } }
        assertEquals(4, actual.count { it.id.startsWith("task-ui-") })
        assertFalse(actual.single { it.id == "task-ui-waiting-organize" }.action == PendingTaskAction.RETRY_MODEL)
    }

    private fun assertNoRequests() {
        assertEquals("Viewing or declining an action must make no HTTP calls.", 0, backend.totalRequests.get())
        assertEquals("No new model request may be submitted.", 0, backend.postRequests.get())
    }

    private fun task(id: String, date: String, status: String, binding: String) = PersistedAiTask(
        id = id, type = "ORGANIZE_NOTES", requestId = "$id-request", bindingKey = binding,
        date = date, status = status, createdAt = timestamp(date), updatedAt = timestamp(date)
    )

    private fun note(id: String, date: String) = Note(
        id = id, content = "原始文字 $id。", title = "原始笔记", sourceType = SourceType.MANUAL,
        sourceApp = "测试", sourceUrl = null, createdAt = timestamp(date), createdDate = date,
        updatedAt = timestamp(date), status = NoteStatus.INBOX, userRemark = null
    )

    private fun entry(id: String, sourceId: String, date: String, status: KnowledgeIndexStatus) = KnowledgeEntry(
        id = id, noteId = sourceId, content = "完整知识正文 $id。", intentType = IntentType.QUOTABLE,
        userTitle = "知识条目", userRemark = null, createdAt = timestamp(date), createdDate = date,
        confirmedAt = timestamp(date), confirmedDate = date, sourceNoteIds = listOf(sourceId), indexStatus = status
    )

    private fun draft(id: String, sourceId: String, date: String) = KnowledgeEntryDraft(
        id = id, date = date, title = "待确认草稿", summary = "保留草稿", content = "草稿全文。",
        intentType = IntentType.QUOTABLE, tags = emptyList(), sourceNoteIds = listOf(sourceId),
        evidenceQuotes = emptyList(), insight = null, confidence = 1f, needsReview = false,
        status = KnowledgeDraftStatus.PENDING, createdAt = timestamp(date), updatedAt = timestamp(date)
    )

    private fun message(
        id: String,
        sessionId: String,
        role: ChatRole,
        content: String,
        date: String,
        parentId: String? = null,
        status: ChatMessageStatus = ChatMessageStatus.DONE
    ) = ChatMessage(
        id = id, sessionId = sessionId, parentMessageId = parentId, role = role,
        content = content, status = status, errorMessage = if (status == ChatMessageStatus.FAILED) "任务已中断，文字已保留。" else null,
        createdAt = timestamp(date) + if (role == ChatRole.ASSISTANT) 1 else 0,
        updatedAt = timestamp(date) + if (role == ChatRole.ASSISTANT) 1 else 0
    )

    private fun timestamp(date: String): Long = LocalDate.parse(date).atTime(12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Fails every accidental provider call and records it; fixture actions should never reach it. */
private class CountingBackend : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val totalRequests = AtomicInteger()
    val postRequests = AtomicInteger()
    private val worker = thread(isDaemon = true, name = "pending-task-http-probe") {
        while (!server.isClosed) {
            runCatching {
                server.accept().use { socket ->
                    totalRequests.incrementAndGet()
                    socket.soTimeout = 1000
                    val firstLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                    if (firstLine.startsWith("POST ")) postRequests.incrementAndGet()
                    val body = "{\"error\":{\"code\":\"TEST_PROVIDER_DISABLED\",\"message\":\"Test provider must not be called\"}}"
                        .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            }
        }
    }

    override fun close() {
        server.close()
        worker.join(1500)
    }
}
