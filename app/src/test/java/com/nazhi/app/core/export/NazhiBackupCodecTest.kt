package com.nazhi.app.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NazhiBackupCodecTest {
    @Test fun digestIgnoresObjectKeyOrderAndWhitespaceButRejectsChangedContent() {
        val encoded = NazhiBackupCodec.encode(fixture())
        val root = Json.parseToJsonElement(encoded) as JsonObject
        val reordered = JsonObject(root.entries.reversed().associate { it.key to it.value }).toString()
        assertTrue(NazhiBackupCodec.inspect(reordered).integrityVerified)
        val damaged = encoded.replace("可恢复的文字内容", "被修改的文字内容")
        assertTrue(NazhiBackupCodec.inspect(damaged).errors.any { it.contains("完整性") })
    }

    @Test fun thousandsOfNotesRoundTripPreservesEscapedTextAndChecksum() {
        val notes = (1..5_000).map { index -> note().copy(id = "note-$index", content = "第 $index 条：\"引号\"\n换行与中文，表情 📚") }
        val encoded = NazhiBackupCodec.encode(fixture().copy(notes = notes))
        val inspected = NazhiBackupCodec.inspect(encoded)
        assertTrue(inspected.errors.toString(), inspected.errors.isEmpty())
        assertTrue(inspected.integrityVerified)
        assertEquals(notes, inspected.payload!!.notes)
        assertEquals(5_000, NazhiBackupCodec.plan(inspected.payload, fixture().copy(notes = emptyList())).newCount)
    }

    @Test fun invalidEnumDuplicateIdsAndBrokenRelationsAreReportedTogether() {
        val data = fixture().copy(notes = listOf(note().copy(sourceType = "UNKNOWN", createdDate = "2026-02-30", createdAt = -1), note()),
            knowledgeEntries = listOf(entry().copy(noteId = "missing")))
        val inspected = NazhiBackupCodec.inspect(NazhiBackupCodec.encode(data))
        assertTrue(inspected.errors.any { it.contains("来源类型") })
        assertTrue(inspected.errors.any { it.contains("重复") })
        assertTrue(inspected.errors.any { it.contains("missing") })
        assertTrue(inspected.errors.any { it.contains("日期") })
        assertTrue(inspected.errors.any { it.contains("时间") })
    }

    @Test fun legacyFilesAreAcceptedWithoutClaimingIntegrity() {
        val raw = NazhiBackupCodec.json.encodeToString(NazhiExportPayload.serializer(), fixture().copy(schemaVersion = 1))
        val inspected = NazhiBackupCodec.inspect(raw)
        assertTrue(inspected.errors.toString(), inspected.errors.isEmpty())
        assertFalse(inspected.integrityVerified)
        assertTrue(inspected.warnings.any { it.contains("旧") })
    }

    @Test fun finiteHistoricalScoresArePreservedWithoutRejudgingOldAnswers() {
        val data = fixture().copy(knowledgeEntries = listOf(entry()),
            chatSessions = listOf(ExportChatSession("session", "会话", createdAt = 1000, updatedAt = 1000)),
            chatMessages = listOf(ExportChatMessage("answer", "session", role = "ASSISTANT", content = "回答", status = "DONE", errorMessage = null, createdAt = 1000, updatedAt = 1000)),
            chatCitations = listOf(ExportChatCitation("citation", "answer", "entry", listOf("note"), "知识编辑前的历史摘录", "理由", 1.0000001f, 1000)))
        val inspected = NazhiBackupCodec.inspect(NazhiBackupCodec.encode(data))
        assertTrue(inspected.errors.toString(), inspected.errors.isEmpty())
        assertEquals(1.0000001f, inspected.payload!!.chatCitations.single().score)
    }

    @Test fun unknownFieldsDeepJsonAndNonFiniteNumbersAreRejected() {
        val root = Json.parseToJsonElement(NazhiBackupCodec.encode(fixture())) as JsonObject
        assertTrue(NazhiBackupCodec.inspect(JsonObject(root + ("token" to JsonPrimitive("secret"))).toString()).errors.isNotEmpty())
        assertTrue(NazhiBackupCodec.inspect("{\"schemaVersion\":3,\"schemaVersion\":3}").errors.any { it.contains("重复字段") })
        assertTrue(NazhiBackupCodec.inspect("[".repeat(65) + "0" + "]".repeat(65)).errors.isNotEmpty())
        val data = fixture().copy(knowledgeDrafts = listOf(ExportKnowledgeDraft(
            "draft", "2026-10-13", "标题", "摘要", "正文", "INSPIRATION", emptyList(), listOf("note"),
            emptyList(), null, Float.NaN, true, "PENDING", 1000, 1000)))
        assertThrows(IllegalArgumentException::class.java) { NazhiBackupCodec.encode(data) }
    }

    @Test fun conflictSkipsDependentKnowledgeWithoutOverwritingSource() {
        val backup = fixture().copy(knowledgeEntries = listOf(entry()))
        val local = fixture().copy(notes = listOf(note().copy(content = "本机编辑")))
        val plan = NazhiBackupCodec.plan(backup, local)
        assertEquals(2, plan.conflictCount)
        assertTrue(plan.insertions.notes.isEmpty())
        assertTrue(plan.insertions.knowledgeEntries.isEmpty())
    }

    @Test fun indexStateIsExcludedFromContentComparisonAndPendingChatBecomesRetryable() {
        val data = fixture().copy(knowledgeEntries = listOf(entry().copy(indexStatus = "INDEXED")))
        val restored = data.copy(knowledgeEntries = listOf(entry().copy(indexStatus = "PENDING")))
        assertEquals(2, NazhiBackupCodec.plan(data, restored).unchangedCount)
        val chat = data.copy(chatSessions = listOf(ExportChatSession("session", "会话", createdAt = 1000, updatedAt = 1000)),
            chatMessages = listOf(ExportChatMessage("question", "session", role = "USER", content = "问题", status = "DONE",
                errorMessage = null, createdAt = 1000, updatedAt = 1000)))
        val normalized = NazhiBackupCodec.forRestore(chat)
        assertEquals("FAILED", normalized.chatMessages.last().status)
        assertEquals("question", normalized.chatMessages.last().parentMessageId)
        assertEquals(0, NazhiBackupCodec.plan(chat, normalized).newCount)
    }

    private fun fixture() = NazhiExportPayload(3, "Nazhi", 1000, ExportSafety(), listOf(note()),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun note() = ExportNote("note", "可恢复的文字内容", null, "MANUAL", null, null,
        createdAt = 1000, createdDate = "2026-10-13", updatedAt = 1000, status = "INBOX", userRemark = null)
    private fun entry() = ExportKnowledgeEntry("entry", "note", "知识正文", "INSPIRATION", null, null,
        1000, "2026-10-13", 1000, "2026-10-13", "摘要", emptyList(), listOf("note"), "PENDING")
}
