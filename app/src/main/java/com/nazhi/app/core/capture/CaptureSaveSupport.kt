package com.nazhi.app.core.capture

import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.model.isMeaningfulKnowledgeDuplicateKey
import com.nazhi.app.core.model.toKnowledgeDuplicateKey
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.toLocalDateId
import java.util.UUID
import kotlinx.coroutines.flow.first

sealed interface CaptureSaveResult {
    data class Saved(val noteId: String) : CaptureSaveResult
    data object Empty : CaptureSaveResult
    data object DuplicateToday : CaptureSaveResult
    data object VerificationCodeLike : CaptureSaveResult
}

suspend fun saveCapturedText(
    repository: NazhiRepository,
    rawText: String?,
    sourceType: SourceType,
    sourceApp: String?,
    title: String? = null,
    audioDurationMs: Long? = null
): CaptureSaveResult {
    val content = rawText?.trim().orEmpty()
    if (content.isEmpty()) {
        return CaptureSaveResult.Empty
    }
    if (content.isVerificationCodeLike()) {
        return CaptureSaveResult.VerificationCodeLike
    }

    val now = System.currentTimeMillis()
    val dateId = now.toLocalDateId()
    val duplicateKey = content.toKnowledgeDuplicateKey()
    val hasDuplicateToday = duplicateKey.isMeaningfulKnowledgeDuplicateKey() &&
        repository.observeNotesForDate(dateId)
            .first()
            .any { note -> note.content.toKnowledgeDuplicateKey() == duplicateKey }
    if (hasDuplicateToday) {
        return CaptureSaveResult.DuplicateToday
    }

    val noteId = UUID.randomUUID().toString()
    repository.saveNote(
        Note(
            id = noteId,
            content = content,
            title = title?.takeIf { it.isNotBlank() } ?: content.toTitle(),
            sourceType = sourceType,
            sourceApp = sourceApp,
            sourceUrl = content.extractFirstUrl(),
            audioDurationMs = audioDurationMs,
            createdAt = now,
            createdDate = dateId,
            updatedAt = now,
            status = NoteStatus.INBOX,
            userRemark = null
        )
    )
    return CaptureSaveResult.Saved(noteId)
}

fun CaptureSaveResult.toToastMessage(emptyMessage: String = "没有读取到可收纳文本"): String {
    return when (this) {
        is CaptureSaveResult.Saved -> "已收纳到今日"
        CaptureSaveResult.Empty -> emptyMessage
        CaptureSaveResult.DuplicateToday -> "今日已存在相同内容，未重复收纳"
        CaptureSaveResult.VerificationCodeLike -> "疑似验证码，未收纳"
    }
}

private fun String.toTitle(): String {
    val compact = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: "未命名记录"
    return compact.take(32)
}

private fun String.extractFirstUrl(): String? {
    val pattern = Regex("""https?://\S+""")
    return pattern.find(this)?.value
}

private fun String.isVerificationCodeLike(): Boolean {
    val compact = trim()
    return compact.length in 4..8 && compact.matches(Regex("[A-Za-z0-9]+"))
}
