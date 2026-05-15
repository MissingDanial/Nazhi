package com.nazhi.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.model.isMeaningfulKnowledgeDuplicateKey
import com.nazhi.app.core.model.toKnowledgeDuplicateKey
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.toLocalDateId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProcessTextCaptureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.extractProcessText()
        if (selectedText.isNullOrBlank()) {
            toastAndFinish("没有读取到选中文字")
            return
        }
        if (selectedText.isVerificationCodeLike()) {
            toastAndFinish("疑似验证码，未收纳")
            return
        }

        val repository = (application as NazhiApp).appContainer.repository
        scope.launch {
            val message = runCatching {
                saveSelectedText(repository, selectedText)
            }.getOrElse {
                "收纳失败，请稍后重试"
            }
            Toast.makeText(this@ProcessTextCaptureActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun saveSelectedText(
        repository: NazhiRepository,
        selectedText: String
    ): String {
        val content = selectedText.trim()
        val now = System.currentTimeMillis()
        val dateId = now.toLocalDateId()
        val duplicateKey = content.toKnowledgeDuplicateKey()
        val hasDuplicateToday = duplicateKey.isMeaningfulKnowledgeDuplicateKey() &&
            repository.observeNotesForDate(dateId)
                .first()
                .any { note -> note.content.toKnowledgeDuplicateKey() == duplicateKey }
        if (hasDuplicateToday) {
            return "今日已存在相同内容，未重复收纳"
        }

        repository.saveNote(
            Note(
                id = UUID.randomUUID().toString(),
                content = content,
                title = content.toTitle(),
                sourceType = SourceType.TEXT_SELECTION,
                sourceApp = intent.extractSourceApp(),
                sourceUrl = content.extractFirstUrl(),
                createdAt = now,
                createdDate = dateId,
                updatedAt = now,
                status = NoteStatus.INBOX,
                userRemark = null
            )
        )
        return "已收纳到今日"
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

private fun Intent.extractProcessText(): String? {
    if (action != Intent.ACTION_PROCESS_TEXT) {
        return null
    }
    return getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun Intent.extractSourceApp(): String? {
    return getStringExtra(Intent.EXTRA_REFERRER_NAME)
        ?: getStringExtra(Intent.EXTRA_TITLE)
        ?: `package`
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
