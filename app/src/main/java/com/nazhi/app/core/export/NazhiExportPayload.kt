package com.nazhi.app.core.export

import com.nazhi.app.core.model.ChatCitation
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
import com.nazhi.app.core.model.SourceType
import kotlinx.serialization.Serializable

@Serializable
data class NazhiExportPayload(
    val schemaVersion: Int,
    val appName: String,
    val exportedAt: Long,
    val safety: ExportSafety,
    val notes: List<ExportNote>,
    val knowledgeEntries: List<ExportKnowledgeEntry>,
    val knowledgeDrafts: List<ExportKnowledgeDraft>,
    val chatSessions: List<ExportChatSession>,
    val chatMessages: List<ExportChatMessage>,
    val chatCitations: List<ExportChatCitation>,
    val integrity: ExportIntegrity? = null
)

@Serializable
data class ExportIntegrity(val algorithm: String = "SHA-256", val digest: String)

@Serializable
data class ExportSafety(
    val excludesApiKeys: Boolean = true,
    val excludesTokens: Boolean = true,
    val excludesBackendSettings: Boolean = true,
    val excludesEmbeddingVectors: Boolean = true,
    val excludesAudio: Boolean = true,
    val excludesPendingAiTasks: Boolean = true,
    val excludesReviewSessions: Boolean = true
)

data class LocalDataImportPreview(
    val schemaVersion: Int,
    val exportedAt: Long,
    val noteCount: Int,
    val knowledgeEntryCount: Int,
    val knowledgeDraftCount: Int,
    val chatSessionCount: Int,
    val chatMessageCount: Int,
    val chatCitationCount: Int,
    val warnings: List<String>,
    val newCount: Int = 0,
    val unchangedCount: Int = 0,
    val conflictCount: Int = 0,
    val conflicts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val integrityVerified: Boolean = false
) {
    val canImport: Boolean get() = errors.isEmpty()
    val totalCount: Int
        get() = noteCount +
            knowledgeEntryCount +
            knowledgeDraftCount +
            chatSessionCount +
            chatMessageCount +
            chatCitationCount
}

data class LocalDataImportResult(
    val notes: ImportEntityResult,
    val knowledgeEntries: ImportEntityResult,
    val knowledgeDrafts: ImportEntityResult,
    val chatSessions: ImportEntityResult,
    val chatMessages: ImportEntityResult,
    val chatCitations: ImportEntityResult,
    val verifiedCount: Int = 0,
    val conflictCount: Int = 0,
    val verificationMessage: String = ""
) {
    val insertedCount: Int
        get() = notes.insertedCount +
            knowledgeEntries.insertedCount +
            knowledgeDrafts.insertedCount +
            chatSessions.insertedCount +
            chatMessages.insertedCount +
            chatCitations.insertedCount

    val skippedCount: Int
        get() = notes.skippedCount +
            knowledgeEntries.skippedCount +
            knowledgeDrafts.skippedCount +
            chatSessions.skippedCount +
            chatMessages.skippedCount +
            chatCitations.skippedCount

    val failedCount: Int
        get() = notes.failedCount +
            knowledgeEntries.failedCount +
            knowledgeDrafts.failedCount +
            chatSessions.failedCount +
            chatMessages.failedCount +
            chatCitations.failedCount
}

data class ImportEntityResult(
    val insertedCount: Int,
    val skippedCount: Int,
    val failedCount: Int
)

@Serializable
data class ExportNote(
    val id: String,
    val content: String,
    val title: String?,
    val sourceType: String,
    val sourceApp: String?,
    val sourceUrl: String?,
    val audioDurationMs: Long? = null,
    val createdAt: Long,
    val createdDate: String,
    val updatedAt: Long,
    val status: String,
    val userRemark: String?
)

@Serializable
data class ExportKnowledgeEntry(
    val id: String,
    val noteId: String,
    val content: String,
    val intentType: String,
    val userTitle: String?,
    val userRemark: String?,
    val createdAt: Long,
    val createdDate: String,
    val confirmedAt: Long,
    val confirmedDate: String,
    val summary: String,
    val tags: List<String>,
    val sourceNoteIds: List<String>,
    val indexStatus: String
)

@Serializable
data class ExportKnowledgeDraft(
    val id: String,
    val date: String,
    val title: String,
    val summary: String,
    val content: String,
    val intentType: String,
    val tags: List<String>,
    val sourceNoteIds: List<String>,
    val evidenceQuotes: List<String>,
    val insight: String?,
    val confidence: Float,
    val needsReview: Boolean,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ExportChatSession(
    val id: String,
    val title: String,
    val memoryDigest: String? = null,
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ExportChatMessage(
    val id: String,
    val sessionId: String,
    val parentMessageId: String? = null,
    val role: String,
    val content: String,
    val status: String,
    val errorMessage: String?,
    val attempt: Int = 1,
    val progressStage: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ExportChatCitation(
    val id: String,
    val messageId: String,
    val knowledgeEntryId: String,
    val sourceNoteIds: List<String>,
    val quote: String,
    val reason: String,
    val score: Float,
    val createdAt: Long
)

fun Note.toExportNote(): ExportNote {
    return ExportNote(
        id = id,
        content = content,
        title = title,
        sourceType = sourceType.name,
        sourceApp = sourceApp,
        sourceUrl = sourceUrl,
        audioDurationMs = audioDurationMs,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        status = status.name,
        userRemark = userRemark
    )
}

fun KnowledgeEntry.toExportKnowledgeEntry(): ExportKnowledgeEntry {
    return ExportKnowledgeEntry(
        id = id,
        noteId = noteId,
        content = content,
        intentType = intentType.name,
        userTitle = userTitle,
        userRemark = userRemark,
        createdAt = createdAt,
        createdDate = createdDate,
        confirmedAt = confirmedAt,
        confirmedDate = confirmedDate,
        summary = summary,
        tags = tags,
        sourceNoteIds = sourceNoteIds,
        indexStatus = indexStatus.name
    )
}

fun KnowledgeEntryDraft.toExportKnowledgeDraft(): ExportKnowledgeDraft {
    return ExportKnowledgeDraft(
        id = id,
        date = date,
        title = title,
        summary = summary,
        content = content,
        intentType = intentType.name,
        tags = tags,
        sourceNoteIds = sourceNoteIds,
        evidenceQuotes = evidenceQuotes,
        insight = insight,
        confidence = confidence,
        needsReview = needsReview,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ChatSession.toExportChatSession(): ExportChatSession {
    return ExportChatSession(
        id = id,
        title = title,
        memoryDigest = memoryDigest,
        messageCount = messageCount,
        lastMessagePreview = lastMessagePreview,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ChatMessage.toExportChatMessage(): ExportChatMessage {
    return ExportChatMessage(
        id = id,
        sessionId = sessionId,
        parentMessageId = parentMessageId,
        role = role.name,
        content = content,
        status = status.name,
        errorMessage = errorMessage,
        attempt = attempt,
        progressStage = progressStage,
        errorCode = errorCode,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ChatCitation.toExportChatCitation(): ExportChatCitation {
    return ExportChatCitation(
        id = id,
        messageId = messageId,
        knowledgeEntryId = knowledgeEntryId,
        sourceNoteIds = sourceNoteIds,
        quote = quote,
        reason = reason,
        score = score,
        createdAt = createdAt
    )
}

fun ExportNote.toImportedNote(): Note {
    return Note(
        id = id,
        content = content,
        title = title,
        sourceType = enumValueOf<SourceType>(sourceType),
        sourceApp = sourceApp,
        sourceUrl = sourceUrl,
        audioDurationMs = audioDurationMs,
        createdAt = createdAt,
        createdDate = createdDate,
        updatedAt = updatedAt,
        status = enumValueOf<NoteStatus>(status),
        userRemark = userRemark
    )
}

fun ExportKnowledgeEntry.toImportedKnowledgeEntry(): KnowledgeEntry {
    val resolvedNoteId = noteId
    val resolvedSourceNoteIds = sourceNoteIds.ifEmpty { listOf(resolvedNoteId) }
    return KnowledgeEntry(
        id = id,
        noteId = resolvedNoteId,
        content = content,
        intentType = enumValueOf<IntentType>(intentType),
        userTitle = userTitle,
        userRemark = userRemark,
        createdAt = createdAt,
        createdDate = createdDate,
        confirmedAt = confirmedAt,
        confirmedDate = confirmedDate,
        summary = summary,
        tags = tags,
        sourceNoteIds = resolvedSourceNoteIds,
        indexStatus = KnowledgeIndexStatus.PENDING
    )
}

fun ExportKnowledgeDraft.toImportedKnowledgeDraft(): KnowledgeEntryDraft {
    return KnowledgeEntryDraft(
        id = id,
        date = date,
        title = title,
        summary = summary,
        content = content,
        intentType = enumValueOf<IntentType>(intentType),
        tags = tags,
        sourceNoteIds = sourceNoteIds,
        evidenceQuotes = evidenceQuotes,
        insight = insight,
        confidence = confidence,
        needsReview = needsReview,
        status = enumValueOf<KnowledgeDraftStatus>(status),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ExportChatSession.toImportedChatSession(): ChatSession {
    return ChatSession(
        id = id,
        title = title,
        memoryDigest = memoryDigest,
        messageCount = messageCount,
        lastMessagePreview = lastMessagePreview,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ExportChatMessage.toImportedChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        parentMessageId = parentMessageId,
        role = enumValueOf<ChatRole>(role),
        content = content,
        status = enumValueOf<ChatMessageStatus>(status),
        errorMessage = errorMessage,
        attempt = attempt,
        progressStage = progressStage,
        errorCode = errorCode,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ExportChatCitation.toImportedChatCitation(): ChatCitation {
    return ChatCitation(
        id = id,
        messageId = messageId,
        knowledgeEntryId = knowledgeEntryId,
        sourceNoteIds = sourceNoteIds,
        quote = quote,
        reason = reason,
        score = score,
        createdAt = createdAt
    )
}
