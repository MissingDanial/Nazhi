package com.nazhi.app.core.knowledge

import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.DuplicateKnowledgeEntryException
import com.nazhi.app.core.repository.NazhiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeIngestionState(
    val isRunning: Boolean = false,
    val taskKind: KnowledgeTaskKind? = null,
    val completedTaskKind: KnowledgeTaskKind? = null,
    val runningLabel: String? = null,
    val progress: AiTaskProgress? = null,
    val message: String? = null,
    val eventId: Long = 0
)

enum class KnowledgeTaskKind {
    ORGANIZE,
    SUBMIT_DRAFT,
    SUBMIT_ALL,
    INDEX_PENDING
}

class KnowledgeIngestionCoordinator(
    private val repository: NazhiRepository,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(KnowledgeIngestionState())
    val state: StateFlow<KnowledgeIngestionState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun organizeToday(date: String) {
        launchTask(
            taskKind = KnowledgeTaskKind.ORGANIZE,
            runningLabel = "正在后台 AI 整理"
        ) {
            val count = repository.organizeNotesForDate(date) { progress ->
                _state.update { state ->
                    state.copy(
                        progress = progress,
                        message = progress.message
                    )
                }
            }
            if (count == 0) "今日没有可整理的笔记" else "已生成 $count 条 AI 草稿"
        }
    }

    fun submitDraft(draftId: String) {
        launchTask(
            taskKind = KnowledgeTaskKind.SUBMIT_DRAFT,
            runningLabel = "正在提交知识并生成向量"
        ) {
            val entry = repository.submitKnowledgeDraft(draftId)
            when {
                entry == null -> "草稿已处理或不存在"
                entry.indexStatus == KnowledgeIndexStatus.INDEXED -> "已提交知识库并完成向量入库"
                entry.indexStatus == KnowledgeIndexStatus.FAILED -> "已提交知识库，向量入库失败，可稍后重试"
                else -> "已提交知识库并开始向量入库"
            }
        }
    }

    fun submitAll(date: String, hasDuplicateDrafts: Boolean, hasReviewRequiredDrafts: Boolean) {
        launchTask(
            taskKind = KnowledgeTaskKind.SUBMIT_ALL,
            runningLabel = "正在批量提交知识并生成向量"
        ) {
            val count = repository.submitAllKnowledgeDraftsForDate(date)
            when {
                count > 0 -> "已提交 $count 条草稿并尝试向量入库"
                hasDuplicateDrafts -> "存在重复草稿，请逐条查看后跳过或编辑"
                hasReviewRequiredDrafts -> "存在需确认草稿，请逐条确认后提交"
                else -> "没有待提交草稿"
            }
        }
    }

    fun indexPending() {
        launchTask(
            taskKind = KnowledgeTaskKind.INDEX_PENDING,
            runningLabel = "正在后台向量入库"
        ) {
            val count = repository.indexPendingKnowledgeEntries()
            if (count == 0) "没有可重试的向量任务" else "已完成 $count 条向量入库"
        }
    }

    private fun launchTask(
        taskKind: KnowledgeTaskKind,
        runningLabel: String,
        block: suspend () -> String
    ) {
        if (activeJob?.isActive == true) {
            _state.update { state ->
                state.copy(
                    message = "已有知识任务正在后台进行，请等待完成后再操作。",
                    eventId = state.eventId + 1
                )
            }
            return
        }

        activeJob = scope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    taskKind = taskKind,
                    completedTaskKind = null,
                    runningLabel = runningLabel,
                    progress = null,
                    message = "$runningLabel，可切换页面，不会中断。"
                )
            }
            val message = runCatching { block() }.getOrElse { error ->
                if (error is DuplicateKnowledgeEntryException) {
                    error.toUserFacingMessage()
                } else {
                    "知识入库失败：${error.toUserFacingMessage()}"
                }
            }
            _state.update {
                it.copy(
                    isRunning = false,
                    taskKind = null,
                    completedTaskKind = taskKind,
                    runningLabel = null,
                    progress = null,
                    message = message,
                    eventId = it.eventId + 1
                )
            }
        }
    }
}

private fun Throwable.toUserFacingMessage(): String {
    return when (this) {
        is DuplicateKnowledgeEntryException -> message ?: "已跳过重复草稿"
        is NazhiBackendException -> when {
            statusCode == 401 || code == "UNAUTHORIZED" -> "鉴权失败，请检查设置页中的 NAZHI_DEV_TOKEN。"
            code == "MINIMAX_EMBEDDING_FAILED" -> "Embedding 模型调用失败，请稍后重试或检查服务器日志。"
            else -> publicMessage
        }
        else -> {
            val raw = message.orEmpty()
            when {
                raw.contains("Failed to connect", ignoreCase = true) -> "无法连接后端，请检查服务器地址、端口和防火墙。"
                raw.contains("Unable to resolve host", ignoreCase = true) -> "无法解析服务器地址，请检查网络连接。"
                raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> {
                    "请求超时，请检查服务器网络或稍后重试。"
                }
                raw.contains("Cleartext", ignoreCase = true) -> "HTTP 请求被系统拦截，请检查网络安全配置。"
                raw.isNotBlank() -> raw
                else -> "请求失败，请检查后端服务。"
            }
        }
    }
}
