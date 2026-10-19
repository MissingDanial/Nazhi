package com.nazhi.app.core.export

import com.nazhi.app.core.model.*
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** A checksum detects corruption, not authorship. No data is written until all checks pass. */
object NazhiBackupCodec {
    const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS = 100_000
    private const val MAX_TIMESTAMP = 253402300799999L
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(payload: NazhiExportPayload): String {
        val data = payload.copy(schemaVersion = 3, appName = "Nazhi", integrity = null)
        return json.encodeToString(data.copy(integrity = ExportIntegrity(digest = digest(data))))
    }

    fun inspect(raw: String): BackupInspection {
        val preflight = preflight(raw)
        if (preflight != null) return BackupInspection(null, listOf(preflight))
        val payload = try { json.decodeFromString<NazhiExportPayload>(raw) }
        catch (_: Exception) { return BackupInspection(null, listOf("文件不是有效的纳知备份 JSON，字段缺失、类型错误或含有未知字段。")) }
        val errors = validate(payload).toMutableList()
        var verified = false
        if (payload.schemaVersion == 3 || payload.integrity != null) {
            val integrity = payload.integrity
            if (integrity == null || integrity.algorithm != "SHA-256" ||
                !integrity.digest.matches(Regex("[a-fA-F0-9]{64}"))) {
                errors += "备份缺少有效的完整性校验信息。"
            } else if (!integrity.digest.equals(digest(payload), ignoreCase = true)) {
                errors += "备份完整性校验失败，内容可能已损坏或修改。"
            } else verified = true
        }
        return BackupInspection(payload, errors.take(200), buildList {
            if (payload.schemaVersion < 3) add("旧备份未带完整性校验，已检查数据结构与关联。")
            if (payload.knowledgeEntries.isNotEmpty()) add("知识正文会恢复，问答索引需由你手动重建，不会自动调用模型。")
            if (payload.chatMessages.any { it.status == "PENDING" }) add("未完成的问答会恢复为可手动重试状态。")
            add("不包含录音文件、待确认转写、未完成 AI 任务、复习记录、向量、账号凭证和后端设置。")
            add("相同内容会跳过；同 ID 内容冲突及其新增关联数据会跳过，保留本机编辑。")
        }, verified)
    }

    private fun digest(payload: NazhiExportPayload): String {
        val root = json.encodeToJsonElement(payload.copy(integrity = null)).jsonObject
        val canonical = canonicalize(JsonObject(root - "integrity")).toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    private fun preflight(raw: String): String? {
        if (raw.length > MAX_BACKUP_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES)
            return "备份文件超过 32 MiB 上限。"
        // Bound parser recursion and reject duplicate object keys before deserialization can hide them.
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '{' -> stack.add(mutableSetOf<String>())
                '[' -> stack.add(null)
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                '"' -> {
                    val start = i++
                    while (i < raw.length && raw[i] != '"') {
                        if (raw[i] == '\\') i++
                        i++
                    }
                    if (i >= raw.length) return "备份 JSON 字符串不完整。"
                    var next = i + 1
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':' && stack.lastOrNull() != null) {
                        val key = try { Json.parseToJsonElement(raw.substring(start, i + 1)).jsonPrimitive.content }
                        catch (_: Exception) { return "备份 JSON 字符串无效。" }
                        if (!stack.last()!!.add(key)) return "备份 JSON 包含重复字段：$key。"
                    }
                }
            }
            if (stack.size > 64) return "备份 JSON 嵌套过深。"
            i++
        }
        return null
    }

    fun validate(p: NazhiExportPayload): List<String> = buildList {
        fun bad(condition: Boolean, message: String) { if (condition && size < 200) add(message) }
        fun id(value: String, label: String) = bad(value.isBlank() || value.length > 256 || value.any { it.isISOControl() }, "$label 的 ID 无效。")
        fun text(value: String, label: String) = bad(value.isBlank(), "$label 不能为空。")
        fun time(value: Long, label: String) = bad(value !in 0..MAX_TIMESTAMP, "$label 的时间无效。")
        fun times(created: Long, updated: Long, label: String) {
            time(created, label); time(updated, label); bad(updated < created, "$label 的更新时间早于创建时间。")
        }
        fun date(value: String, label: String) = bad(!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || runCatching { LocalDate.parse(value) }.isFailure, "$label 的日期无效：$value。")
        fun unique(ids: List<String>, label: String) {
            ids.forEach { id(it, label) }; bad(ids.distinct().size != ids.size, "$label 包含重复 ID。")
        }
        fun references(ids: List<String>, available: Set<String>, label: String) {
            unique(ids, label); ids.filterNot { it in available }.forEach { bad(true, "$label 缺少关联记录：$it。") }
        }
        bad(p.appName != "Nazhi", "这不是纳知备份文件。")
        bad(p.schemaVersion !in 1..3, "不支持此备份版本：${p.schemaVersion}。")
        time(p.exportedAt, "导出")
        val total = p.notes.size.toLong() + p.knowledgeEntries.size + p.knowledgeDrafts.size + p.chatSessions.size + p.chatMessages.size + p.chatCitations.size
        bad(total > MAX_RECORDS, "备份记录超过 $MAX_RECORDS 条上限。")
        unique(p.notes.map { it.id }, "笔记"); unique(p.knowledgeEntries.map { it.id }, "知识")
        unique(p.knowledgeDrafts.map { it.id }, "草稿"); unique(p.chatSessions.map { it.id }, "会话")
        unique(p.chatMessages.map { it.id }, "消息"); unique(p.chatCitations.map { it.id }, "引用")
        val notes = p.notes.map { it.id }.toSet()
        val entries = p.knowledgeEntries.associateBy { it.id }
        val sessions = p.chatSessions.map { it.id }.toSet()
        val messages = p.chatMessages.associateBy { it.id }
        p.notes.forEach {
            text(it.content, "笔记 ${it.id} 的正文"); times(it.createdAt, it.updatedAt, "笔记 ${it.id}")
            date(it.createdDate, "笔记 ${it.id}")
            bad(it.sourceType !in SourceType.entries.map { e -> e.name }, "笔记 ${it.id} 的来源类型无效。")
            bad(it.status !in NoteStatus.entries.map { e -> e.name }, "笔记 ${it.id} 的处理状态无效。")
            bad(it.audioDurationMs != null && it.audioDurationMs !in 0..MAX_TIMESTAMP, "笔记 ${it.id} 的音频时长无效。")
        }
        p.knowledgeEntries.forEach {
            text(it.content, "知识 ${it.id} 的正文"); times(it.createdAt, it.confirmedAt, "知识 ${it.id}")
            date(it.createdDate, "知识 ${it.id}"); date(it.confirmedDate, "知识 ${it.id}")
            bad(it.intentType !in IntentType.entries.map { e -> e.name }, "知识 ${it.id} 的知识分类无效。")
            bad(it.indexStatus !in KnowledgeIndexStatus.entries.map { e -> e.name }, "知识 ${it.id} 的索引状态无效。")
            references(listOf(it.noteId), notes, "知识 ${it.id}"); references(it.sourceNoteIds, notes, "知识 ${it.id} 的来源")
        }
        p.knowledgeDrafts.forEach {
            text(it.content, "草稿 ${it.id} 的正文"); times(it.createdAt, it.updatedAt, "草稿 ${it.id}"); date(it.date, "草稿 ${it.id}")
            bad(it.intentType !in IntentType.entries.map { e -> e.name }, "草稿 ${it.id} 的知识分类无效。")
            bad(it.status !in KnowledgeDraftStatus.entries.map { e -> e.name }, "草稿 ${it.id} 的处理状态无效。")
            bad(!it.confidence.isFinite(), "草稿 ${it.id} 的置信度无效。")
            bad(it.sourceNoteIds.isEmpty(), "草稿 ${it.id} 缺少来源笔记。")
            references(it.sourceNoteIds, notes, "草稿 ${it.id} 的来源")
        }
        p.chatSessions.forEach {
            times(it.createdAt, it.updatedAt, "会话 ${it.id}"); bad(it.messageCount < 0, "会话 ${it.id} 的消息数无效。")
        }
        p.chatMessages.forEach {
            if (it.role == "USER" || (it.role == "ASSISTANT" && it.status == "DONE")) {
                text(it.content, "消息 ${it.id} 的正文")
            }
            times(it.createdAt, it.updatedAt, "消息 ${it.id}")
            bad(it.role !in ChatRole.entries.map { e -> e.name }, "消息 ${it.id} 的消息角色无效。")
            bad(it.status !in ChatMessageStatus.entries.map { e -> e.name }, "消息 ${it.id} 的处理状态无效。")
            bad(it.attempt < 1, "消息 ${it.id} 的尝试次数无效。")
            references(listOf(it.sessionId), sessions, "消息 ${it.id} 的会话")
            if (it.parentMessageId != null) {
                val parent = messages[it.parentMessageId]
                bad(parent == null || parent.role != "USER" || it.role != "ASSISTANT" || parent.sessionId != it.sessionId || parent.createdAt > it.createdAt,
                    "消息 ${it.id} 的父问题关联无效。")
            }
        }
        p.chatCitations.forEach {
            time(it.createdAt, "引用 ${it.id}")
            bad(!it.score.isFinite(), "引用 ${it.id} 的分数无效。")
            val message = messages[it.messageId]
            bad(message == null || message.role != "ASSISTANT", "引用 ${it.id} 缺少回答消息：${it.messageId}。")
            val entry = entries[it.knowledgeEntryId]
            bad(entry == null, "引用 ${it.id} 缺少知识：${it.knowledgeEntryId}。")
            references(it.sourceNoteIds, notes, "引用 ${it.id} 的来源")
            // Citations are historical snapshots. The referenced entry may have been edited since
            // answering; retain its original quote and source IDs without reevaluating its meaning.
        }
    }

    /** Prepare durable user-visible states; no restored record triggers a model call. */
    fun forRestore(p: NazhiExportPayload): NazhiExportPayload {
        val messages = p.chatMessages.map { if (it.status == "PENDING") it.copy(status = "FAILED",
            content = if (it.role == "ASSISTANT") "备份中的问答尚未完成。" else it.content,
            errorMessage = "问题已保留，请点击重试。",
            errorCode = "CHAT_INTERRUPTED", progressStage = "FAILED") else it }.toMutableList()
        val occupiedIds = messages.mapTo(mutableSetOf()) { it.id }
        val bySession = p.chatMessages.groupBy { it.sessionId }
        val questionTimes = bySession.mapValues { (_, items) -> items.filter { it.role == "USER" }.map { it.createdAt }.distinct().sorted() }
        val answeredQuestions = p.chatMessages.filter { it.role == "ASSISTANT" }.mapNotNull { it.parentMessageId }.toSet()
        val legacyAnswerTimes = bySession.mapValues { (_, items) -> items.filter { it.role == "ASSISTANT" && it.parentMessageId == null }.map { it.createdAt }.sorted() }
        p.chatMessages.filter { it.role == "USER" }.forEach { question ->
            val times = questionTimes[question.sessionId].orEmpty()
            val nextAt = times.getOrNull(times.binarySearch(question.createdAt) + 1)
            val legacyTimes = legacyAnswerTimes[question.sessionId].orEmpty()
            val found = legacyTimes.binarySearch(question.createdAt)
            val legacyAt = legacyTimes.getOrNull(if (found >= 0) found else -found - 1)
            val answered = question.id in answeredQuestions || (legacyAt != null && (nextAt == null || legacyAt < nextAt))
            if (!answered) {
                val markerId = "restore-interrupted-" + MessageDigest.getInstance("SHA-256").digest(question.id.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
                require(occupiedIds.add(markerId)) { "恢复问答状态的 ID 与备份记录冲突。" }
                messages += ExportChatMessage(markerId, question.sessionId, question.id, "ASSISTANT", "备份中的问答尚未完成。",
                    "FAILED", "问题已保留，请点击重试。", 1, "FAILED", "CHAT_INTERRUPTED", question.createdAt, question.updatedAt)
            }
        }
        val restoredBySession = messages.groupBy { it.sessionId }
        return p.copy(knowledgeEntries = p.knowledgeEntries.map { it.toImportedKnowledgeEntry().toExportKnowledgeEntry() },
            chatMessages = messages, chatSessions = p.chatSessions.map { session ->
                val items = restoredBySession[session.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id }))
                session.copy(messageCount = items.size, lastMessagePreview = items.lastOrNull()?.content?.take(80).orEmpty())
            })
    }

    fun plan(payload: NazhiExportPayload, local: NazhiExportPayload): BackupMergePlan {
        val incoming = forRestore(payload)
        val existing = records(local).associateBy { it.key }
        val candidates = records(incoming)
        val unchanged = mutableSetOf<String>()
        val conflicts = linkedMapOf<String, String>()
        candidates.forEach { item ->
            val current = existing[item.key]
            if (current != null) {
                if (current.value == item.value) unchanged += item.key
                else conflicts[item.key] = "${item.label} ${item.id}：同 ID 内容不同，保留本机内容。"
            }
        }
        // A new record must not quietly attach to a source/session/parent changed on this device.
        var changed: Boolean
        do {
            changed = false
            candidates.filter { it.key !in existing && it.key !in conflicts }.forEach { item ->
                if (item.dependencies.any { it in conflicts }) {
                    conflicts[item.key] = "${item.label} ${item.id}：关联记录存在冲突，已跳过。"
                    changed = true
                }
            }
        } while (changed)
        val newKeys = candidates.filter { it.key !in existing && it.key !in conflicts }.map { it.key }.toSet()
        fun include(type: String, id: String) = "$type:$id" in newKeys
        val insertions = incoming.copy(notes = incoming.notes.filter { include("note", it.id) },
            knowledgeEntries = incoming.knowledgeEntries.filter { include("entry", it.id) },
            knowledgeDrafts = incoming.knowledgeDrafts.filter { include("draft", it.id) },
            chatSessions = incoming.chatSessions.filter { include("session", it.id) },
            chatMessages = incoming.chatMessages.filter { include("message", it.id) },
            chatCitations = incoming.chatCitations.filter { include("citation", it.id) })
        return BackupMergePlan(incoming, insertions, newKeys.size, unchanged.size, conflicts.size, conflicts.values.toList())
    }

    private data class Record(val key: String, val id: String, val label: String, val value: JsonElement, val dependencies: List<String>)
    private fun records(p: NazhiExportPayload): List<Record> = buildList {
        fun record(type: String, id: String, label: String, value: JsonElement, deps: List<String> = emptyList(), ignore: Set<String> = emptySet()) {
            add(Record("$type:$id", id, label, JsonObject(value.jsonObject - ignore), deps))
        }
        fun label(type: String, title: String) = "$type「${title.replace(Regex("\\s+"), " ").take(40)}」"
        p.notes.forEach { record("note", it.id, label("笔记", it.title?.takeIf(String::isNotBlank) ?: it.content), json.encodeToJsonElement(it)) }
        p.knowledgeEntries.forEach { record("entry", it.id, label("知识", it.userTitle?.takeIf(String::isNotBlank) ?: it.content), json.encodeToJsonElement(it.copy(sourceNoteIds = it.sourceNoteIds.ifEmpty { listOf(it.noteId) })), (listOf(it.noteId) + it.sourceNoteIds).map { id -> "note:$id" }, setOf("indexStatus")) }
        p.knowledgeDrafts.forEach { record("draft", it.id, label("草稿", it.title), json.encodeToJsonElement(it), it.sourceNoteIds.map { id -> "note:$id" }) }
        p.chatSessions.forEach { record("session", it.id, label("会话", it.title), json.encodeToJsonElement(it), ignore = setOf("messageCount", "lastMessagePreview")) }
        p.chatMessages.forEach { record("message", it.id, label("消息", it.content), json.encodeToJsonElement(it), listOf("session:${it.sessionId}") + listOfNotNull(it.parentMessageId?.let { id -> "message:$id" })) }
        p.chatCitations.forEach { record("citation", it.id, label("引用", it.quote), json.encodeToJsonElement(it), listOf("message:${it.messageId}", "entry:${it.knowledgeEntryId}") + it.sourceNoteIds.map { id -> "note:$id" }) }
    }
}

data class BackupInspection(val payload: NazhiExportPayload?, val errors: List<String>, val warnings: List<String> = emptyList(), val integrityVerified: Boolean = false)
data class BackupMergePlan(val normalized: NazhiExportPayload, val insertions: NazhiExportPayload, val newCount: Int,
    val unchangedCount: Int, val conflictCount: Int, val conflicts: List<String>)
class BackupValidationException(val errors: List<String>) : IllegalArgumentException(errors.joinToString("\n"))
