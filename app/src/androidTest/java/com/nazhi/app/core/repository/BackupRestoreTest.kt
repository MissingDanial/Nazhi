package com.nazhi.app.core.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.database.entity.toEntity
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.export.*
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.network.NazhiBackendClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite proves restoration is atomic and preserves local edits even after preview. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private lateinit var db: NazhiDatabase
    private lateinit var repository: LocalNazhiRepository
    private val databases = mutableListOf<NazhiDatabase>()

    @Before fun setup() { db = newDb(); repository = repository(db) }
    @After fun cleanup() { databases.forEach { it.close() } }

    @Test fun exportIntoEmptyDatabasePreservesAllBusinessDataAndRepeatImportIsIdempotent() = runBlocking {
        seed(db, fixture())
        val raw = repository.buildLocalDataExportJson()
        val targetDb = newDb()
        val target = repository(targetDb)
        val preview = target.previewLocalDataImportJson(raw)
        assertTrue(preview.errors.toString(), preview.canImport)
        assertTrue(preview.integrityVerified)
        assertEquals(6, preview.newCount)
        val result = target.importLocalDataJson(raw)
        assertEquals(6, result.insertedCount)
        assertEquals(6, result.verifiedCount)
        assertEquals(db.noteDao().getNote("note"), targetDb.noteDao().getNote("note"))
        assertEquals(db.knowledgeEntryDraftDao().getDraft("draft"), targetDb.knowledgeEntryDraftDao().getDraft("draft"))
        assertEquals(db.chatCitationDao().getCitation("citation"), targetDb.chatCitationDao().getCitation("citation"))
        assertEquals(KnowledgeIndexStatus.PENDING, targetDb.knowledgeEntryDao().getEntry("entry")?.indexStatus)
        assertEquals(0, target.importLocalDataJson(raw).insertedCount)
        assertEquals(6, target.previewLocalDataImportJson(raw).unchangedCount)
    }

    @Test fun damagedBackupAndBrokenRelationshipsWriteNothing() = runBlocking {
        val raw = NazhiBackupCodec.encode(fixture())
        val damaged = raw.replace("原始收纳文字", "篡改收纳文字")
        assertFalse(repository.previewLocalDataImportJson(damaged).canImport)
        assertTrue(runCatching { repository.importLocalDataJson(damaged) }.exceptionOrNull() is BackupValidationException)
        val broken = fixture().copy(chatMessages = fixture().chatMessages.map { it.copy(parentMessageId = "missing") })
        assertFalse(repository.previewLocalDataImportJson(NazhiBackupCodec.encode(broken)).canImport)
        assertTrue(runCatching { repository.importLocalDataJson(NazhiBackupCodec.encode(broken)) }.isFailure)
        assertTrue(db.noteDao().getNotes().isEmpty())
        assertTrue(db.chatSessionDao().getSessions().isEmpty())
    }

    @Test fun databaseFailureRollsBackEarlierRecordsAndExistingDataSurvives() = runBlocking {
        val kept = fixture().notes.single().copy(id = "kept", content = "本机保留文字")
        db.noteDao().upsert(kept.toImportedNote().toEntity())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON chat_citations BEGIN SELECT RAISE(ABORT, 'injected_restore_failure'); END")
        assertTrue(runCatching { repository.importLocalDataJson(NazhiBackupCodec.encode(fixture())) }.isFailure)
        assertNull(db.noteDao().getNote("note"))
        assertNull(db.knowledgeEntryDao().getEntry("entry"))
        assertTrue(db.chatMessageDao().getMessages().isEmpty())
        assertEquals(kept.toImportedNote(), db.noteDao().getNote("kept")?.toModel())
    }

    @Test fun readbackMismatchRollsBackSilentDatabaseMutation() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER mutate_restore AFTER INSERT ON notes BEGIN UPDATE notes SET content = 'injected mutation' WHERE id = NEW.id; END")
        assertTrue(runCatching { repository.importLocalDataJson(NazhiBackupCodec.encode(fixture())) }.exceptionOrNull() is IllegalStateException)
        assertTrue(db.noteDao().getNotes().isEmpty())
        assertTrue(db.knowledgeEntryDao().getEntries().isEmpty())
    }

    @Test fun editingAfterPreviewIsRecheckedAndConflictDependenciesAreSkipped() = runBlocking {
        val raw = NazhiBackupCodec.encode(fixture())
        assertEquals(6, repository.previewLocalDataImportJson(raw).newCount)
        val edited = fixture().notes.single().copy(content = "预览后本机编辑", userRemark = "不要覆盖")
        db.noteDao().upsert(edited.toImportedNote().toEntity())
        val result = repository.importLocalDataJson(raw)
        assertEquals(4, result.conflictCount) // source + entry + draft + citation
        assertEquals(2, result.insertedCount) // independent session and answer
        assertEquals(edited.toImportedNote(), db.noteDao().getNote("note")?.toModel())
        assertNull(db.knowledgeEntryDao().getEntry("entry"))
        assertNull(db.chatCitationDao().getCitation("citation"))
        assertNotNull(db.chatMessageDao().getMessage("answer"))
    }

    @Test fun pendingAnswerAndOrphanQuestionImmediatelyBecomeRetryableWithoutModelCalls() = runBlocking {
        val payload = fixture().copy(chatCitations = emptyList(), chatMessages = listOf(
            ExportChatMessage("question", "session", role = "USER", content = "保留问题", status = "DONE", errorMessage = null, createdAt = 1000, updatedAt = 1000),
            ExportChatMessage("pending", "session", "question", "ASSISTANT", "处理中", "PENDING", null, createdAt = 1001, updatedAt = 1001),
            ExportChatMessage("orphan", "session", role = "USER", content = "中断问题", status = "DONE", errorMessage = null, createdAt = 1002, updatedAt = 1002)))
        val raw = NazhiBackupCodec.encode(payload)
        repository.importLocalDataJson(raw)
        assertEquals(ChatMessageStatus.FAILED, db.chatMessageDao().getMessage("pending")?.status)
        val marker = db.chatMessageDao().getAssistantMessagesForParent("orphan").single()
        assertEquals(ChatMessageStatus.FAILED, marker.status)
        assertEquals("CHAT_INTERRUPTED", marker.errorCode)
        assertEquals(0, repository.importLocalDataJson(raw).insertedCount)
        assertEquals(4, db.chatMessageDao().getMessages().size)
    }

    @Test fun emptyFailedAssistantAndNormalizedEmptyPendingAssistantRemainBackupableAndIdempotent() = runBlocking {
        for (status in listOf("FAILED", "PENDING")) {
            val sourceDb = newDb()
            val payload = fixture().copy(chatCitations = emptyList(), chatMessages = listOf(
                ExportChatMessage("question", "session", role = "USER", content = "被完整保留的原问题", status = "DONE",
                    errorMessage = null, createdAt = 1000, updatedAt = 1000),
                ExportChatMessage("empty-answer", "session", "question", "ASSISTANT", "", status,
                    "问题已保留，请点击重试。", createdAt = 1001, updatedAt = 1001)))
            seed(sourceDb, payload)
            val source = repository(sourceDb)
            // Exercise the production cold-start normalizer, which preserves empty assistant content.
            if (status == "PENDING") source.normalizeInterruptedOperations()
            val expected = requireNotNull(sourceDb.chatMessageDao().getMessage("empty-answer"))
            assertEquals(ChatMessageStatus.FAILED, expected.status)
            assertEquals("", expected.content)
            val raw = source.buildLocalDataExportJson()
            val localPreview = source.previewLocalDataImportJson(raw)
            assertTrue(localPreview.errors.toString(), localPreview.canImport)
            assertEquals(0, localPreview.conflictCount)
            val targetDb = newDb()
            val target = repository(targetDb)
            assertTrue(target.importLocalDataJson(raw).insertedCount > 0)
            assertEquals(expected, targetDb.chatMessageDao().getMessage("empty-answer"))
            assertEquals("被完整保留的原问题", targetDb.chatMessageDao().getMessage("question")?.content)
            assertEquals(0, target.importLocalDataJson(raw).insertedCount)
            assertEquals(0, target.previewLocalDataImportJson(raw).conflictCount)
            assertEquals(2, targetDb.chatMessageDao().getMessages().size)
        }
    }

    @Test fun legacySchemaOneAndTwoRestoreWithoutIntegrityClaim() = runBlocking {
        for (version in 1..2) {
            val target = repository(newDb())
            val raw = NazhiBackupCodec.json.encodeToString(NazhiExportPayload.serializer(), fixture().copy(schemaVersion = version))
            assertFalse(target.previewLocalDataImportJson(raw).integrityVerified)
            assertEquals(6, target.importLocalDataJson(raw).insertedCount)
        }
    }

    @Test fun exportIncludesDeletedSourceNeededForKnowledgeAndCitation() = runBlocking {
        seed(db, fixture().copy(notes = fixture().notes.map { it.copy(status = "DELETED") }))
        assertTrue(db.noteDao().getNotes().isEmpty())
        val raw = repository.buildLocalDataExportJson()
        val targetDb = newDb()
        assertEquals(6, repository(targetDb).importLocalDataJson(raw).insertedCount)
        assertNotNull(targetDb.noteDao().getNote("note"))
        assertNotNull(targetDb.knowledgeEntryDao().getEntry("entry"))
    }

    @Test fun editingKnowledgePreservesHistoricalCitationQuoteAndOriginalSourceOnRestore() = runBlocking {
        seed(db, fixture())
        val secondSource = fixture().notes.single().copy(id = "new-note", content = "新的来源正文")
        db.noteDao().upsert(secondSource.toImportedNote().toEntity())
        val entry = requireNotNull(db.knowledgeEntryDao().getEntry("entry"))
        db.knowledgeEntryDao().upsert(entry.copy(content = "后来修改的知识正文", summary = "后来修改的摘要", sourceNoteIds = listOf("new-note")))
        val raw = repository.buildLocalDataExportJson()
        val targetDb = newDb()
        repository(targetDb).importLocalDataJson(raw)
        val citation = requireNotNull(targetDb.chatCitationDao().getCitation("citation"))
        assertEquals("可靠知识正文", citation.quote)
        assertEquals(listOf("note"), citation.sourceNoteIds)
        assertEquals("后来修改的知识正文", targetDb.knowledgeEntryDao().getEntry("entry")?.content)
        assertEquals(listOf("new-note"), targetDb.knowledgeEntryDao().getEntry("entry")?.sourceNoteIds)
        assertNotNull(targetDb.noteDao().getNote("note"))
        assertNotNull(targetDb.noteDao().getNote("new-note"))
    }

    private fun newDb(): NazhiDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), NazhiDatabase::class.java).build().also { databases += it }
    private fun repository(db: NazhiDatabase) = LocalNazhiRepository(db, db.persistedAiTaskDao(), db.noteDao(),
        db.knowledgeEntryDao(), db.knowledgeEntryDraftDao(), db.reviewSessionDao(), db.embeddingDao(),
        db.chatSessionDao(), db.chatMessageDao(), db.chatCitationDao(), db.audioTranscriptionJobDao(),
        NazhiBackendClient(configProvider = { error("备份恢复不得调用模型或读取凭证") }))
    private suspend fun seed(db: NazhiDatabase, p: NazhiExportPayload) {
        p.notes.forEach { db.noteDao().upsert(it.toImportedNote().toEntity()) }
        p.knowledgeEntries.forEach { db.knowledgeEntryDao().upsert(it.toImportedKnowledgeEntry().copy(indexStatus = KnowledgeIndexStatus.INDEXED).toEntity()) }
        p.knowledgeDrafts.forEach { db.knowledgeEntryDraftDao().upsert(it.toImportedKnowledgeDraft().toEntity()) }
        p.chatSessions.forEach { db.chatSessionDao().upsert(it.toImportedChatSession().toEntity()) }
        p.chatMessages.forEach { db.chatMessageDao().upsert(it.toImportedChatMessage().toEntity()) }
        p.chatCitations.forEach { db.chatCitationDao().upsert(it.toImportedChatCitation().toEntity()) }
    }
    private fun fixture() = NazhiExportPayload(3, "Nazhi", 2000, ExportSafety(),
        listOf(ExportNote("note", "原始收纳文字", "来源标题", "MANUAL", "文字收纳", null, createdAt = 1000,
            createdDate = "2026-10-13", updatedAt = 1000, status = "REVIEWED", userRemark = "备注")),
        listOf(ExportKnowledgeEntry("entry", "note", "可靠知识正文", "INSPIRATION", "知识标题", "知识备注", 1000,
            "2026-10-13", 1000, "2026-10-13", "摘要", listOf("标签"), listOf("note"), "INDEXED")),
        listOf(ExportKnowledgeDraft("draft", "2026-10-13", "草稿标题", "草稿摘要", "草稿正文", "INSPIRATION", listOf("标签"),
            listOf("note"), listOf("原始收纳文字"), "洞察", 0.8f, true, "PENDING", 1000, 1000)),
        listOf(ExportChatSession("session", "问答会话", "会话记忆", 1, "可靠知识正文", 1000, 1000)),
        listOf(ExportChatMessage("answer", "session", role = "ASSISTANT", content = "可靠知识正文", status = "DONE",
            errorMessage = null, createdAt = 1000, updatedAt = 1000)),
        listOf(ExportChatCitation("citation", "answer", "entry", listOf("note"), "可靠知识正文", "引用理由", 0.5f, 1000)))
}
