package com.nazhi.app.feature.tasks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.tasks.PendingTask
import com.nazhi.app.core.tasks.PendingTaskAction
import com.nazhi.app.core.tasks.PendingTaskKind
import com.nazhi.app.core.tasks.PendingTaskStore
import com.nazhi.app.core.ui.KnowledgeEntryDetailDialog
import com.nazhi.app.core.ui.NazhiTokens
import com.nazhi.app.core.util.displayDateLabel
import com.nazhi.app.feature.chat.KnowledgeChatRoute
import com.nazhi.app.feature.inbox.DateNotesRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A cross-date list of submitted work; opening the list never submits model requests. */
@Composable
fun PendingTasksRoute(
    repository: NazhiRepository,
    pendingTaskStore: PendingTaskStore,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator,
    knowledgeChatCoordinator: KnowledgeChatCoordinator,
    onNavigateBack: () -> Unit
) {
    val tasks by remember(pendingTaskStore) {
        pendingTaskStore.tasks
    }.collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val busyIds by pendingTaskStore.runningKeys.collectAsState()
    var retryConfirmation by remember { mutableStateOf<PendingTask?>(null) }
    var sourceDate by remember { mutableStateOf<String?>(null) }
    var chatSessionId by remember { mutableStateOf<String?>(null) }
    var detailEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var sourceNotes by remember { mutableStateOf(emptyList<Note>()) }

    fun perform(task: PendingTask) {
        val key = task.key
        if (key in busyIds) return
        coroutineScope.launch {
            try {
                val message = pendingTaskStore.perform(task)
                snackbarHostState.showSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("暂时未能处理，请检查网络后再试。原内容已保留。")
            }
        }
    }

    val date = sourceDate
    val sessionId = chatSessionId
    when {
        date != null -> {
            BackHandler { sourceDate = null }
            DateNotesRoute(
                repository = repository,
                knowledgeIngestionCoordinator = knowledgeIngestionCoordinator,
                dateId = date,
                screenTitle = "原始笔记",
                screenSubtitle = "整理来源",
                showQuickInput = false,
                onNavigateBack = { sourceDate = null }
            )
        }
        sessionId != null -> {
            BackHandler { chatSessionId = null }
            KnowledgeChatRoute(
                repository = repository,
                knowledgeChatCoordinator = knowledgeChatCoordinator,
                initialSessionId = sessionId,
                onNavigateBack = { chatSessionId = null }
            )
        }
        else -> {
            BackHandler(onBack = onNavigateBack)
            PendingTasksScreen(
                tasks = tasks,
                busyIds = busyIds,
                snackbarHostState = snackbarHostState,
                onNavigateBack = onNavigateBack,
                onPerform = { task ->
                    if (task.action == PendingTaskAction.RETRY_MODEL) {
                        retryConfirmation = task
                    } else if (task.action == PendingTaskAction.QUERY_EXISTING) {
                        perform(task)
                    }
                },
                onViewSource = { task ->
                    when (task.kind) {
                        PendingTaskKind.ORGANIZE -> sourceDate = task.date
                        PendingTaskKind.CHAT -> {
                            task.targetId?.let { chatSessionId = it }
                                ?: coroutineScope.launch {
                                    snackbarHostState.showSnackbar("原对话已移除。")
                                }
                        }
                        PendingTaskKind.INDEX -> coroutineScope.launch {
                            val entry = repository.getKnowledgeEntry(task.targetId ?: task.id)
                            if (entry == null) {
                                snackbarHostState.showSnackbar("原知识已移除。")
                            } else {
                                sourceNotes = repository.getNotesByIds(entry.sourceNoteIds)
                                detailEntry = entry
                            }
                        }
                    }
                }
            )
        }
    }

    retryConfirmation?.let { task ->
        AlertDialog(
            onDismissRequest = { retryConfirmation = null },
            title = { Text(if (task.kind == PendingTaskKind.CHAT) "重试回答" else "重新处理") },
            text = {
                Text(
                    when (task.kind) {
                        PendingTaskKind.ORGANIZE -> "将使用这一天仍待整理的笔记再次调用模型，可能产生服务费用。原始笔记会保留。"
                        PendingTaskKind.INDEX -> "将为这条知识再次调用模型建立索引，可能产生服务费用。知识正文会保留。"
                        PendingTaskKind.CHAT -> "将使用原问题再次调用模型生成回答，可能产生服务费用。原问题会保留。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    retryConfirmation = null
                    perform(task)
                }) {
                    Text("确认重试")
                }
            },
            dismissButton = {
                TextButton(onClick = { retryConfirmation = null }) { Text("取消") }
            }
        )
    }

    detailEntry?.let { entry ->
        KnowledgeEntryDetailDialog(
            entry = entry,
            sourceNotes = sourceNotes,
            onCopyEntry = { context.copyPendingTaskText(entry.content) },
            onCopyNote = { context.copyPendingTaskText(it.content) },
            onDismiss = { detailEntry = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingTasksScreen(
    tasks: List<PendingTask>,
    busyIds: Set<String>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onPerform: (PendingTask) -> Unit,
    onViewSource: (PendingTask) -> Unit
) {
    Scaffold(
        containerColor = NazhiTokens.colors.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("返回") }
                },
                title = {
                    Column {
                        Text("待处理")
                        Text("跨日期查看整理、索引和问答", style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "继续查询会查找已提交任务的结果；重新处理会再次调用模型。这里列出已提交但尚未完成的文字任务，未整理笔记和待确认草稿仍在对应日期中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (tasks.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("目前没有待处理任务", style = MaterialTheme.typography.titleMedium)
                            Text("已完成的任务会自动从这里移除。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(tasks, key = { it.key }) { task ->
                PendingTaskCard(
                    task = task,
                    busy = task.key in busyIds,
                    onPerform = { onPerform(task) },
                    onViewSource = { onViewSource(task) }
                )
            }
        }
    }
}

@Composable
private fun PendingTaskCard(
    task: PendingTask,
    busy: Boolean,
    onPerform: () -> Unit,
    onViewSource: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when (task.kind) {
                        PendingTaskKind.ORGANIZE -> "笔记整理"
                        PendingTaskKind.INDEX -> "知识索引"
                        PendingTaskKind.CHAT -> "知识问答"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = NazhiTokens.colors.grassDark
                )
                Text(task.statusLabel, style = MaterialTheme.typography.labelMedium)
            }
            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                displayDateLabel(task.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            task.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在处理，可稍后查看结果", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onViewSource, modifier = Modifier.weight(1f)) {
                    Text(
                        when (task.kind) {
                            PendingTaskKind.ORGANIZE -> "查看原笔记"
                            PendingTaskKind.INDEX -> "查看知识"
                            PendingTaskKind.CHAT -> "查看原问题"
                        }
                    )
                }
                if (task.action != PendingTaskAction.NONE) {
                    Button(onClick = onPerform, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                task.action == PendingTaskAction.QUERY_EXISTING -> "继续查询"
                                task.kind == PendingTaskKind.CHAT -> "重试回答"
                                else -> "重新处理"
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun Context.copyPendingTaskText(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        ?.setPrimaryClip(ClipData.newPlainText("纳知", text))
}
