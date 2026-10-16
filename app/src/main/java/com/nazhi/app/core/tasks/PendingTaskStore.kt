package com.nazhi.app.core.tasks

import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.database.entity.toModel
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.toLocalDateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A view of existing operations. Observing it never calls a model. */
class PendingTaskStore internal constructor(
    organizeTasks: Flow<List<PersistedAiTask>>,
    entries: Flow<List<KnowledgeEntry>>,
    messages: Flow<List<ChatMessage>>,
    private val scope: CoroutineScope,
    private val operation: suspend (PendingTask) -> String
) {
    constructor(
        database: NazhiDatabase,
        repository: NazhiRepository,
        backendClient: NazhiBackendClient,
        scope: CoroutineScope
    ) : this(
        database.persistedAiTaskDao().observeAll().map { list -> list.map { it.toModel() } },
        repository.observeKnowledgeEntries(),
        database.chatMessageDao().observeAll().map { list -> list.map { it.toModel() } },
        scope,
        runTask@{ task ->
            when (task.kind) {
                PendingTaskKind.ORGANIZE -> {
                    val local = repository.getPersistedAiTask(task.id)
                        ?: return@runTask "任务已处理，请刷新待处理列表。"
                    if (local.bindingKey != backendClient.currentTaskBinding()) {
                        return@runTask "请切回提交任务时的账号和服务配置后再处理。"
                    }
                    val count = if (task.action == PendingTaskAction.QUERY_EXISTING) {
                        repository.recoverOrganizeTask(local)
                    } else {
                        val editedDrafts = repository.observeKnowledgeDraftsForDate(local.date).first()
                            .any { it.status == KnowledgeDraftStatus.PENDING && it.updatedAt > it.createdAt }
                        if (editedDrafts) {
                            return@runTask "这一天还有编辑过的待确认草稿，请先确认或跳过这些草稿，再重新整理。"
                        }
                        repository.organizeNotesForDate(local.date)
                    }
                    if (count > 0) "已生成 $count 条草稿" else "任务已处理，请查看对应日期"
                }
                PendingTaskKind.INDEX -> if (repository.indexKnowledgeEntry(task.id)) {
                    "知识处理完成，可以用于问答"
                } else {
                    "知识已保留，处理尚未完成；请查看原因后重试"
                }
                PendingTaskKind.CHAT -> {
                    repository.retryChatMessage(task.id)
                    "回答已生成，可查看原会话"
                }
            }
        }
    )

    val tasks: Flow<List<PendingTask>> = combine(organizeTasks, entries, messages, ::buildPendingTasks)
    private val jobs = mutableMapOf<String, Deferred<String>>()
    private val _runningKeys = MutableStateFlow<Set<String>>(emptySet())
    val runningKeys: StateFlow<Set<String>> = _runningKeys.asStateFlow()

    /** Clicking twice joins one operation; leaving the page does not cancel accepted work. */
    suspend fun perform(task: PendingTask): String {
        val work = synchronized(jobs) {
            jobs[task.key]?.takeIf { !it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                val current = tasks.first().firstOrNull { it.key == task.key }
                    ?: return@async "任务已处理，请查看原始内容"
                if (current.action != task.action || current.action == PendingTaskAction.NONE) {
                    return@async "任务状态已变化，请查看更新后的处理方式"
                }
                operation(current)
            }.also { deferred ->
                jobs[task.key] = deferred
                _runningKeys.update { it + task.key }
                deferred.invokeOnCompletion {
                    synchronized(jobs) {
                        if (jobs[task.key] === deferred) {
                            jobs.remove(task.key)
                            _runningKeys.update { it - task.key }
                        }
                    }
                }
            }
        }
        work.start()
        return work.await()
    }
}

private fun buildPendingTasks(
    organizeTasks: List<PersistedAiTask>,
    entries: List<KnowledgeEntry>,
    messages: List<ChatMessage>
): List<PendingTask> {
    val tasks = mutableListOf<PendingTask>()
    organizeTasks.filter { it.type == "ORGANIZE_NOTES" }
        .groupBy { it.date }.values.mapNotNull { list ->
            list.maxWithOrNull(compareBy<PersistedAiTask> { it.createdAt }.thenBy { it.id })
        }.filter { it.status != "APPLIED" }.forEach { task ->
            val waiting = task.status in setOf("CREATED", "RUNNING", "WAITING")
            tasks += PendingTask(
                task.id, PendingTaskKind.ORGANIZE, "整理这一天的笔记", task.date,
                if (waiting) "待查询" else "整理中断",
                task.errorMessage ?: if (waiting) "已提交的任务，可继续查询原结果" else "原始笔记已保留",
                if (waiting) PendingTaskAction.QUERY_EXISTING else PendingTaskAction.RETRY_MODEL
            )
        }
    entries.filter { it.indexStatus != KnowledgeIndexStatus.INDEXED }.forEach { entry ->
        val running = entry.indexStatus == KnowledgeIndexStatus.INDEXING
        tasks += PendingTask(
            entry.id, PendingTaskKind.INDEX, entry.userTitle?.takeIf { it.isNotBlank() }
                ?: entry.content.lineSequence().firstOrNull().orEmpty().take(48).ifBlank { "处理知识条目" },
            entry.createdDate, when (entry.indexStatus) {
                KnowledgeIndexStatus.INDEXING -> "处理中"
                KnowledgeIndexStatus.FAILED -> "处理失败"
                else -> "待处理"
            },
            when (entry.indexStatus) {
                KnowledgeIndexStatus.INDEXING -> "正在处理，完成后可用于问答"
                KnowledgeIndexStatus.FAILED -> "上次知识处理失败，内容已保存；可检查服务连接后重试"
                else -> "知识已保存，需要完成处理后才能用于问答"
            },
            if (running) PendingTaskAction.NONE else PendingTaskAction.RETRY_MODEL, entry.id
        )
    }
    val questions = messages.filter { it.role == ChatRole.USER }.associateBy { it.id }
    val questionsBySession = questions.values.groupBy { it.sessionId }
        .mapValues { (_, list) -> list.sortedBy { it.createdAt } }
    fun questionFor(message: ChatMessage): ChatMessage? {
        message.parentMessageId?.let { questions[it] }?.let { return it }
        val list = questionsBySession[message.sessionId].orEmpty()
        var low = 0
        var high = list.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (list[middle].createdAt <= message.createdAt) low = middle + 1 else high = middle
        }
        return list.getOrNull(low - 1)
    }
    val assistants = messages.filter { it.role == ChatRole.ASSISTANT }
        .groupBy { it.parentMessageId ?: questionFor(it)?.id ?: "legacy:${it.id}" }
        .values.mapNotNull { list ->
            list.maxWithOrNull(compareBy<ChatMessage> { it.attempt }.thenBy { it.createdAt }.thenBy { it.id })
        }
    assistants.filter { it.status != ChatMessageStatus.DONE }.forEach { message ->
        val question = questionFor(message)
        val running = message.status == ChatMessageStatus.PENDING
        tasks += PendingTask(
            message.id, PendingTaskKind.CHAT, question?.content?.take(80) ?: "知识库问答",
            message.createdAt.toLocalDateId(), if (running) "回答中" else "问答中断",
            message.errorMessage ?: if (running) "正在生成回答" else "问题已保留，可重新尝试",
            if (running) PendingTaskAction.NONE else PendingTaskAction.RETRY_MODEL, message.sessionId
        )
    }
    return tasks.sortedWith(compareByDescending<PendingTask> { it.date }.thenBy { it.kind.ordinal }.thenBy { it.id })
}
