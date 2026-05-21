package com.nazhi.app.core.chat

import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.repository.NazhiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeChatTaskState(
    val isRunning: Boolean = false,
    val runningLabel: String? = null,
    val progress: AiTaskProgress? = null,
    val message: String? = null,
    val sessionId: String? = null,
    val shouldClearQuestion: Boolean = false,
    val eventId: Long = 0
)

class KnowledgeChatCoordinator(
    private val repository: NazhiRepository,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(KnowledgeChatTaskState())
    val state: StateFlow<KnowledgeChatTaskState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun ask(question: String, sessionId: String?) {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            publishMessage("问题不能为空")
            return
        }
        launchChatTask(runningLabel = "正在后台生成回答") {
            val answer = repository.askKnowledgeQuestion(
                question = trimmedQuestion,
                topK = 5,
                sessionId = sessionId
            ) { progress ->
                publishProgress(progress)
            }
            ChatTaskResult(
                message = "回答已生成",
                sessionId = answer.sessionId,
                shouldClearQuestion = true
            )
        }
    }

    fun retry(messageId: String) {
        launchChatTask(runningLabel = "正在后台重试回答") {
            val answer = repository.retryChatMessage(messageId, topK = 5) { progress ->
                publishProgress(progress)
            }
            ChatTaskResult(
                message = "已重新尝试生成回答",
                sessionId = answer.sessionId,
                shouldClearQuestion = false
            )
        }
    }

    fun regenerate(messageId: String) {
        launchChatTask(runningLabel = "正在后台重新生成回答") {
            val answer = repository.regenerateChatAnswer(messageId, topK = 5) { progress ->
                publishProgress(progress)
            }
            ChatTaskResult(
                message = "已重新生成回答",
                sessionId = answer.sessionId,
                shouldClearQuestion = false
            )
        }
    }

    private fun launchChatTask(
        runningLabel: String,
        block: suspend () -> ChatTaskResult
    ) {
        if (activeJob?.isActive == true) {
            publishMessage("已有问答任务正在后台进行，请等待完成后再操作。")
            return
        }

        activeJob = scope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    runningLabel = runningLabel,
                    progress = null,
                    message = "$runningLabel，可切换页面，不会中断。",
                    shouldClearQuestion = false
                )
            }
            val result = runCatching { block() }.getOrElse { error ->
                ChatTaskResult(
                    message = "问答失败：${error.message ?: "请检查知识库和后端服务"}",
                    sessionId = null,
                    shouldClearQuestion = false
                )
            }
            _state.update {
                it.copy(
                    isRunning = false,
                    runningLabel = null,
                    progress = null,
                    message = result.message,
                    sessionId = result.sessionId,
                    shouldClearQuestion = result.shouldClearQuestion,
                    eventId = it.eventId + 1
                )
            }
        }
    }

    private fun publishProgress(progress: AiTaskProgress) {
        _state.update {
            it.copy(
                progress = progress,
                message = progress.message
            )
        }
    }

    private fun publishMessage(message: String) {
        _state.update {
            it.copy(
                message = message,
                shouldClearQuestion = false,
                eventId = it.eventId + 1
            )
        }
    }
}

private data class ChatTaskResult(
    val message: String,
    val sessionId: String?,
    val shouldClearQuestion: Boolean
)
