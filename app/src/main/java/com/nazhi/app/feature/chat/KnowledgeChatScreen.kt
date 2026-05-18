package com.nazhi.app.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.ChatCitation
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.repository.NazhiRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun KnowledgeChatRoute(
    repository: NazhiRepository,
    onOpenKnowledgeEntry: (String) -> Unit = {}
) {
    val embeddingCount by remember(repository) {
        repository.observeEmbeddingCount()
    }.collectAsState(initial = 0)
    val knowledgeEntries by remember(repository) {
        repository.observeKnowledgeEntries()
    }.collectAsState(initial = emptyList())
    val chatSessions by remember(repository) {
        repository.observeChatSessions()
    }.collectAsState(initial = emptyList())
    val activeChatSessionId = chatSessions.firstOrNull()?.id
    val messages by remember(repository, activeChatSessionId) {
        activeChatSessionId?.let { repository.observeChatMessages(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val citations by remember(repository, activeChatSessionId) {
        activeChatSessionId?.let { repository.observeChatCitationsForSession(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var isAsking by remember { mutableStateOf(false) }
    var askProgress by remember { mutableStateOf<AiTaskProgress?>(null) }

    KnowledgeChatScreen(
        question = question,
        messages = messages,
        citations = citations,
        embeddingCount = embeddingCount,
        entryCount = knowledgeEntries.size,
        indexedEntryCount = knowledgeEntries.count { it.indexStatus == KnowledgeIndexStatus.INDEXED },
        pendingIndexCount = knowledgeEntries.count {
            it.indexStatus == KnowledgeIndexStatus.PENDING || it.indexStatus == KnowledgeIndexStatus.INDEXING
        },
        failedIndexCount = knowledgeEntries.count { it.indexStatus == KnowledgeIndexStatus.FAILED },
        isAsking = isAsking,
        askProgress = askProgress,
        snackbarHostState = snackbarHostState,
        onQuestionChange = { question = it },
        onCitationClick = { citation ->
            coroutineScope.launch {
                val entry = repository.getKnowledgeEntry(citation.knowledgeEntryId)
                if (entry == null) {
                    snackbarHostState.showSnackbar("引用对应的知识条目不存在，可能已被删除或数据已变化")
                } else {
                    onOpenKnowledgeEntry(entry.id)
                }
            }
        },
        onAsk = {
            val trimmedQuestion = question.trim()
            if (trimmedQuestion.isNotBlank()) {
                coroutineScope.launch {
                    isAsking = true
                    val message = runCatching {
                        repository.askKnowledgeQuestion(trimmedQuestion, topK = 5) { progress ->
                            askProgress = progress
                        }
                        question = ""
                        "回答已生成"
                    }.getOrElse { error ->
                        "问答失败：${error.message ?: "请检查知识库和后端服务"}"
                    }
                    isAsking = false
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeChatScreen(
    question: String,
    messages: List<ChatMessage>,
    citations: List<ChatCitation>,
    embeddingCount: Int,
    entryCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    snackbarHostState: SnackbarHostState,
    onQuestionChange: (String) -> Unit,
    onCitationClick: (ChatCitation) -> Unit,
    onAsk: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "问答")
                        Text(
                            text = "基于本地知识库检索后回答",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                KnowledgeChatCard(
                    question = question,
                    messages = messages,
                    citations = citations,
                    embeddingCount = embeddingCount,
                    entryCount = entryCount,
                    indexedEntryCount = indexedEntryCount,
                    pendingIndexCount = pendingIndexCount,
                    failedIndexCount = failedIndexCount,
                    isAsking = isAsking,
                    askProgress = askProgress,
                    onQuestionChange = onQuestionChange,
                    onCitationClick = onCitationClick,
                    onAsk = onAsk
                )
            }
        }
    }
}

@Composable
private fun KnowledgeChatCard(
    question: String,
    messages: List<ChatMessage>,
    citations: List<ChatCitation>,
    embeddingCount: Int,
    entryCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    onQuestionChange: (String) -> Unit,
    onCitationClick: (ChatCitation) -> Unit,
    onAsk: () -> Unit
) {
    val citationsByMessage = citations.groupBy { it.messageId }
    val canAsk = question.isNotBlank() && !isAsking && indexedEntryCount > 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "知识库问答",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "先在本地向量库检索相关知识，再把命中的少量上下文交给 AI 回答。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            KnowledgeChatIndexStatusBlock(
                entryCount = entryCount,
                indexedEntryCount = indexedEntryCount,
                pendingIndexCount = pendingIndexCount,
                failedIndexCount = failedIndexCount,
                embeddingCount = embeddingCount
            )
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(text = "向知识库提问") }
            )
            Button(
                onClick = onAsk,
                enabled = canAsk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isAsking -> "回答中"
                        entryCount == 0 -> "先完成知识入库"
                        indexedEntryCount == 0 -> "先重建索引"
                        else -> "提问"
                    }
                )
            }
            askProgress?.let { progress ->
                RequestProgressBlock(progress = progress)
            }
            if (messages.isEmpty()) {
                Text(
                    text = "暂无问答记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider()
                messages.takeLast(8).forEach { message ->
                    ChatMessageBlock(
                        message = message,
                        citations = citationsByMessage[message.id].orEmpty(),
                        onCitationClick = onCitationClick
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeChatIndexStatusBlock(
    entryCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    embeddingCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "知识条目 $entryCount 条 · 可问答 $indexedEntryCount 条 · 本地向量 $embeddingCount 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val statusText = when {
            entryCount == 0 -> "当前还没有知识条目。请先保存文本，并完成 AI 整理或手动入库。"
            indexedEntryCount == 0 && pendingIndexCount > 0 -> {
                "知识库尚未完成索引。请先在知识库页重建索引后再提问。"
            }
            indexedEntryCount == 0 && failedIndexCount > 0 -> {
                "知识索引失败。请检查网络或 API 配置后，在知识库页重试向量入库。"
            }
            pendingIndexCount > 0 || failedIndexCount > 0 -> {
                "部分知识尚未索引，本次问答只会使用已完成索引的知识。"
            }
            else -> null
        }
        statusText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (indexedEntryCount == 0 && entryCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun ChatMessageBlock(
    message: ChatMessage,
    citations: List<ChatCitation>,
    onCitationClick: (ChatCitation) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (message.role == ChatRole.USER) "我" else "纳知",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (message.status == ChatMessageStatus.FAILED) {
                message.errorMessage ?: message.content
            } else {
                message.content
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.status == ChatMessageStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        if (citations.isNotEmpty()) {
            Text(
                text = "引用 ${citations.size} 条 · 可点击追溯来源",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            citations.forEachIndexed { index, citation ->
                OutlinedButton(
                    onClick = { onCitationClick(citation) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "引用 ${index + 1}：${citation.quote.ifBlank { citation.knowledgeEntryId }}\n查看知识条目 / 原始 Note",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestProgressBlock(progress: AiTaskProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${progress.stage.label()} · ${progress.progress}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = progress.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun com.nazhi.app.core.model.AiTaskStage.label(): String {
    return when (this) {
        com.nazhi.app.core.model.AiTaskStage.ACCEPTED -> "已提交"
        com.nazhi.app.core.model.AiTaskStage.PREPARING_NOTES -> "准备笔记"
        com.nazhi.app.core.model.AiTaskStage.LOCAL_RETRIEVAL -> "本地检索"
        com.nazhi.app.core.model.AiTaskStage.CONTEXT_READY -> "上下文就绪"
        com.nazhi.app.core.model.AiTaskStage.CALLING_MODEL -> "AI 生成"
        com.nazhi.app.core.model.AiTaskStage.PARSING_RESULT -> "校验结果"
        com.nazhi.app.core.model.AiTaskStage.SAVING_RESULT -> "写入本地"
        com.nazhi.app.core.model.AiTaskStage.FALLBACK_DRAFTS -> "兜底草稿"
        com.nazhi.app.core.model.AiTaskStage.DONE -> "完成"
        com.nazhi.app.core.model.AiTaskStage.FAILED -> "失败"
        com.nazhi.app.core.model.AiTaskStage.UNKNOWN -> "处理中"
    }
}
