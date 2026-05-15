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
import com.nazhi.app.core.repository.NazhiRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun KnowledgeChatRoute(repository: NazhiRepository) {
    val embeddingCount by remember(repository) {
        repository.observeEmbeddingCount()
    }.collectAsState(initial = 0)
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
        isAsking = isAsking,
        askProgress = askProgress,
        snackbarHostState = snackbarHostState,
        onQuestionChange = { question = it },
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
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    snackbarHostState: SnackbarHostState,
    onQuestionChange: (String) -> Unit,
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
                    isAsking = isAsking,
                    askProgress = askProgress,
                    onQuestionChange = onQuestionChange,
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
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit
) {
    val citationsByMessage = citations.groupBy { it.messageId }

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
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(text = "向知识库提问") }
            )
            Button(
                onClick = onAsk,
                enabled = question.isNotBlank() && !isAsking && embeddingCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isAsking -> "回答中"
                        embeddingCount == 0 -> "先完成知识入库"
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
                        citations = citationsByMessage[message.id].orEmpty()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBlock(
    message: ChatMessage,
    citations: List<ChatCitation>
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
            citations.forEachIndexed { index, citation ->
                Text(
                    text = "引用 ${index + 1}：${citation.quote.ifBlank { citation.knowledgeEntryId }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
