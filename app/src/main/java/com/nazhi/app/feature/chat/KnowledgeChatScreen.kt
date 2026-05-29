package com.nazhi.app.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.ChatCitation
import com.nazhi.app.core.model.ChatMessage
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.ChatSession
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.ui.EditableKnowledgeEntryDialog
import com.nazhi.app.core.ui.EditableSourceNoteDialog
import com.nazhi.app.core.ui.KnowledgeEntryDetailDialog
import com.nazhi.app.core.util.extractFirstUrl
import com.nazhi.app.core.util.toNazhiTitle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun KnowledgeChatRoute(
    repository: NazhiRepository,
    knowledgeChatCoordinator: KnowledgeChatCoordinator
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
    val historicalChatSessions = remember(chatSessions) {
        chatSessions.filter { it.messageCount > 0 }
    }
    var activeChatSessionId by remember { mutableStateOf<String?>(null) }
    val activeChatSession = remember(historicalChatSessions, activeChatSessionId) {
        historicalChatSessions.firstOrNull { it.id == activeChatSessionId }
    }
    val visibleHistorySessions = remember(historicalChatSessions, activeChatSessionId) {
        historicalChatSessions.filterNot { it.id == activeChatSessionId }
    }
    LaunchedEffect(historicalChatSessions) {
        if (activeChatSessionId != null && historicalChatSessions.none { it.id == activeChatSessionId }) {
            activeChatSessionId = null
        }
    }
    val messages by remember(repository, activeChatSessionId) {
        activeChatSessionId?.let { repository.observeChatMessages(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val citations by remember(repository, activeChatSessionId) {
        activeChatSessionId?.let { repository.observeChatCitationsForSession(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val chatTaskState by remember(knowledgeChatCoordinator) {
        knowledgeChatCoordinator.state
    }.collectAsState(initial = com.nazhi.app.core.chat.KnowledgeChatTaskState())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var handledChatEventId by remember { mutableStateOf(chatTaskState.eventId) }
    var detailEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var detailSourceNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var detailCitation by remember { mutableStateOf<ChatCitation?>(null) }
    var editingEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var editingSourceNote by remember { mutableStateOf<Note?>(null) }
    var isUpdatingEntry by remember { mutableStateOf(false) }

    LaunchedEffect(chatTaskState.eventId) {
        val message = chatTaskState.message
        if (chatTaskState.eventId != handledChatEventId && !message.isNullOrBlank()) {
            handledChatEventId = chatTaskState.eventId
            chatTaskState.sessionId?.let { activeChatSessionId = it }
            if (chatTaskState.shouldClearQuestion) {
                question = ""
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    KnowledgeChatScreen(
        question = question,
        chatSessions = visibleHistorySessions,
        activeSession = activeChatSession,
        activeChatSessionId = activeChatSessionId,
        messages = messages,
        citations = citations,
        knowledgeEntries = knowledgeEntries,
        embeddingCount = embeddingCount,
        entryCount = knowledgeEntries.size,
        indexedEntryCount = knowledgeEntries.count { it.indexStatus == KnowledgeIndexStatus.INDEXED },
        isAsking = chatTaskState.isRunning,
        askProgress = chatTaskState.progress,
        askStatusMessage = chatTaskState.message.takeIf { chatTaskState.isRunning },
        snackbarHostState = snackbarHostState,
        onQuestionChange = { question = it },
        onSelectSession = { activeChatSessionId = it },
        onCreateSession = {
            coroutineScope.launch {
                if (activeChatSessionId == null && question.isBlank()) {
                    snackbarHostState.showSnackbar("当前无对话")
                } else {
                    activeChatSessionId = null
                    question = ""
                    snackbarHostState.showSnackbar("已新建对话")
                }
            }
        },
        onClearSessionMemory = { session ->
            coroutineScope.launch {
                repository.clearChatSessionMemory(session.id)
                snackbarHostState.showSnackbar("已清除记忆")
            }
        },
        onDeleteSession = { session ->
            coroutineScope.launch {
                repository.deleteChatSession(session.id)
                snackbarHostState.showSnackbar("已删除该问答会话")
            }
        },
        onClearSessions = {
            coroutineScope.launch {
                repository.clearChatSessions()
                activeChatSessionId = null
                snackbarHostState.showSnackbar("已清空本地问答记录")
            }
        },
        onCitationClick = { citation ->
            coroutineScope.launch {
                val entry = repository.getKnowledgeEntry(citation.knowledgeEntryId)
                if (entry == null) {
                    snackbarHostState.showSnackbar("引用对应的知识条目不存在，可能已被删除或数据已变化")
                } else {
                    detailEntry = entry
                    detailSourceNotes = repository.getNotesByIds(entry.sourceNoteIds)
                    detailCitation = citation
                }
            }
        },
        onAsk = {
            val trimmedQuestion = question.trim()
            if (trimmedQuestion.isNotBlank()) {
                knowledgeChatCoordinator.ask(
                    question = trimmedQuestion,
                    sessionId = activeChatSessionId
                )
            }
        },
        onRetry = { message ->
            knowledgeChatCoordinator.retry(message.id)
        },
        onRegenerate = { message ->
            knowledgeChatCoordinator.regenerate(message.id)
        },
        onCopyAnswer = { answer ->
            coroutineScope.launch {
                context.copyToClipboard(label = "纳知回答", text = answer)
                snackbarHostState.showSnackbar("已复制回答")
            }
        },
        onUseFollowUpSuggestion = { suggestion ->
            question = suggestion
        }
    )

    editingEntry?.let { entry ->
        EditableKnowledgeEntryDialog(
            entry = entry,
            isSaving = isUpdatingEntry,
            onDismiss = {
                if (!isUpdatingEntry) {
                    editingEntry = null
                }
            },
            onConfirm = { updatedEntry ->
                coroutineScope.launch {
                    isUpdatingEntry = true
                    val result = runCatching {
                        repository.updateKnowledgeEntry(updatedEntry, reindex = true)
                    }
                    isUpdatingEntry = false
                    result.fold(
                        onSuccess = { indexed ->
                            editingEntry = null
                            snackbarHostState.showSnackbar(
                                if (indexed) {
                                    "已保存并更新问答能力"
                                } else {
                                    "已保存，问答能力更新失败，可稍后重试"
                                }
                            )
                        },
                        onFailure = { error ->
                            snackbarHostState.showSnackbar("保存失败：${error.toUserFacingMessage()}")
                        }
                    )
                }
            }
        )
    }

    editingSourceNote?.let { note ->
        EditableSourceNoteDialog(
            note = note,
            onDismiss = { editingSourceNote = null },
            onConfirm = { content, remark ->
                coroutineScope.launch {
                    repository.updateNoteContent(
                        id = note.id,
                        content = content,
                        title = content.toNazhiTitle(),
                        sourceUrl = content.extractFirstUrl(),
                        userRemark = remark.takeIf { it.isNotBlank() },
                        updatedAt = System.currentTimeMillis()
                    )
                    editingSourceNote = null
                    snackbarHostState.showSnackbar("原始 Note 已更新")
                }
            }
        )
    }

    detailEntry?.let { entry ->
        KnowledgeEntryDetailDialog(
            entry = entry,
            sourceNotes = detailSourceNotes,
            dialogTitle = "引用来源",
            citationQuote = detailCitation?.quote,
            citationReason = detailCitation?.reason,
            onCopyEntry = {
                context.copyToClipboard(
                    label = "纳知知识条目",
                    text = entry.toReferenceText()
                )
            },
            onCopyNote = { note ->
                context.copyToClipboard(label = "纳知原始 Note", text = note.content)
            },
            onEditEntry = {
                detailEntry = null
                detailSourceNotes = emptyList()
                detailCitation = null
                editingEntry = entry
            },
            onEditNote = { note ->
                detailEntry = null
                detailSourceNotes = emptyList()
                detailCitation = null
                editingSourceNote = note
            },
            onCopyCitation = {
                detailCitation?.toReferenceText(entry)?.let { text ->
                    context.copyToClipboard(label = "纳知引用来源", text = text)
                }
            },
            onDismiss = {
                detailEntry = null
                detailSourceNotes = emptyList()
                detailCitation = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeChatScreen(
    question: String,
    chatSessions: List<ChatSession>,
    activeSession: ChatSession?,
    activeChatSessionId: String?,
    messages: List<ChatMessage>,
    citations: List<ChatCitation>,
    knowledgeEntries: List<KnowledgeEntry>,
    embeddingCount: Int,
    entryCount: Int,
    indexedEntryCount: Int,
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    askStatusMessage: String?,
    snackbarHostState: SnackbarHostState,
    onQuestionChange: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onClearSessionMemory: (ChatSession) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onClearSessions: () -> Unit,
    onCitationClick: (ChatCitation) -> Unit,
    onAsk: () -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onCopyAnswer: (String) -> Unit,
    onUseFollowUpSuggestion: (String) -> Unit
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
                    chatSessions = chatSessions,
                    activeSession = activeSession,
                    activeChatSessionId = activeChatSessionId,
                    messages = messages,
                    citations = citations,
                    knowledgeEntries = knowledgeEntries,
                    embeddingCount = embeddingCount,
                    entryCount = entryCount,
                    indexedEntryCount = indexedEntryCount,
                    isAsking = isAsking,
                    askProgress = askProgress,
                    askStatusMessage = askStatusMessage,
                    onQuestionChange = onQuestionChange,
                    onSelectSession = onSelectSession,
                    onCreateSession = onCreateSession,
                    onClearSessionMemory = onClearSessionMemory,
                    onDeleteSession = onDeleteSession,
                    onClearSessions = onClearSessions,
                    onCitationClick = onCitationClick,
                    onAsk = onAsk,
                    onRetry = onRetry,
                    onRegenerate = onRegenerate,
                    onCopyAnswer = onCopyAnswer,
                    onUseFollowUpSuggestion = onUseFollowUpSuggestion
                )
            }
        }
    }
}

@Composable
private fun KnowledgeChatCard(
    question: String,
    chatSessions: List<ChatSession>,
    activeSession: ChatSession?,
    activeChatSessionId: String?,
    messages: List<ChatMessage>,
    citations: List<ChatCitation>,
    knowledgeEntries: List<KnowledgeEntry>,
    embeddingCount: Int,
    entryCount: Int,
    indexedEntryCount: Int,
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    askStatusMessage: String?,
    onQuestionChange: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onClearSessionMemory: (ChatSession) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onClearSessions: () -> Unit,
    onCitationClick: (ChatCitation) -> Unit,
    onAsk: () -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onCopyAnswer: (String) -> Unit,
    onUseFollowUpSuggestion: (String) -> Unit
) {
    val canAsk = question.isNotBlank() && !isAsking && indexedEntryCount > 0
    val chatTurns = remember(messages, citations, knowledgeEntries) {
        buildChatTurns(
            messages = messages,
            citations = citations,
            knowledgeEntries = knowledgeEntries
        )
    }
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }

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
            CurrentConversationBlock(
                activeSession = activeSession,
                hasDraftQuestion = question.isNotBlank(),
                isBusy = isAsking,
                onCreateSession = onCreateSession,
                onClearSessionMemory = onClearSessionMemory
            )
            KnowledgeChatIndexStatusBlock(
                embeddingCount = embeddingCount
            )
            HorizontalDivider()
            ChatConversationWindowBlock(
                chatTurns = chatTurns,
                isAsking = isAsking,
                askProgress = askProgress,
                askStatusMessage = askStatusMessage,
                onCitationClick = onCitationClick,
                onRetry = onRetry,
                onRegenerate = onRegenerate,
                onCopyAnswer = onCopyAnswer,
                onUseFollowUpSuggestion = onUseFollowUpSuggestion
            )
            ChatQuestionInputBlock(
                question = question,
                canAsk = canAsk,
                isAsking = isAsking,
                entryCount = entryCount,
                indexedEntryCount = indexedEntryCount,
                onQuestionChange = onQuestionChange,
                onAsk = onAsk
            )
            HorizontalDivider()
            ChatHistoryBlock(
                sessions = chatSessions,
                activeChatSessionId = activeChatSessionId,
                expanded = historyExpanded,
                onToggleExpanded = { historyExpanded = !historyExpanded },
                onSelectSession = onSelectSession,
                onDeleteSession = { sessionToDelete = it },
                onClearSessions = { showClearConfirm = true },
                isBusy = isAsking
            )
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(text = "删除问答会话") },
            text = { Text(text = "只会删除这条问答会话和引用记录，不会删除知识条目或原始 Note。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete = null
                        onDeleteSession(session)
                    }
                ) {
                    Text(text = "删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(text = "取消")
                }
            }
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(text = "清空本地问答记录") },
            text = { Text(text = "会删除全部问答会话、回答和引用记录，不会删除知识库条目和原始 Note。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearSessions()
                    }
                ) {
                    Text(text = "清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

@Composable
private fun ChatConversationWindowBlock(
    chatTurns: List<ChatTurnUiState>,
    isAsking: Boolean,
    askProgress: AiTaskProgress?,
    askStatusMessage: String?,
    onCitationClick: (ChatCitation) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onCopyAnswer: (String) -> Unit,
    onUseFollowUpSuggestion: (String) -> Unit
) {
    if (
        chatTurns.isEmpty() &&
        askProgress == null &&
        !(isAsking && !askStatusMessage.isNullOrBlank())
    ) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (chatTurns.isNotEmpty()) {
            chatTurns.takeLast(8).forEach { turn ->
                ChatTurnConversationBlock(
                    turn = turn,
                    isBusy = isAsking,
                    onCitationClick = onCitationClick,
                    onRetry = onRetry,
                    onRegenerate = onRegenerate,
                    onCopyAnswer = onCopyAnswer,
                    onUseFollowUpSuggestion = onUseFollowUpSuggestion
                )
            }
        }
        askProgress?.let { progress ->
            RequestProgressBlock(progress = progress)
        }
        if (askProgress == null && isAsking && !askStatusMessage.isNullOrBlank()) {
            Text(
                text = askStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ChatTurnConversationBlock(
    turn: ChatTurnUiState,
    isBusy: Boolean,
    onCitationClick: (ChatCitation) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onCopyAnswer: (String) -> Unit,
    onUseFollowUpSuggestion: (String) -> Unit
) {
    val latestAnswer = turn.latestAnswer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.86f),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "我",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = turn.question.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = if (latestAnswer?.status == ChatMessageStatus.FAILED) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val answerText = latestAnswer?.fullAnswerText()
                    val answerStatus = latestAnswer?.status
                    val followUpSuggestions = remember(answerText, answerStatus, turn.latestCitations.size) {
                        answerText?.extractFollowUpSuggestions(
                            fallbackTopic = turn.question.content,
                            allowFallback = answerStatus == ChatMessageStatus.DONE &&
                                turn.latestCitations.isNotEmpty()
                        ).orEmpty()
                    }
                    Text(
                        text = "纳知",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (latestAnswer?.status == ChatMessageStatus.FAILED) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    RichAnswerBlock(
                        answer = answerText?.withoutFollowUpSuggestions() ?: "正在等待回答生成。",
                        color = if (latestAnswer?.status == ChatMessageStatus.FAILED) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    latestAnswer?.let { answer ->
                        Text(
                            text = turn.summaryMetaText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (answer.status == ChatMessageStatus.FAILED) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (answer.status == ChatMessageStatus.FAILED) {
                                TextButton(
                                    onClick = { onRetry(answer) },
                                    enabled = !isBusy
                                ) {
                                    Text(text = "重试")
                                }
                            } else {
                                TextButton(
                                    onClick = { onCopyAnswer(answer.fullAnswerText()) },
                                    enabled = !isBusy
                                ) {
                                    Text(text = "复制")
                                }
                                TextButton(
                                    onClick = { onRegenerate(answer) },
                                    enabled = !isBusy
                                ) {
                                    Text(text = "重新生成")
                                }
                            }
                        }
                        FollowUpSuggestionsBlock(
                            suggestions = followUpSuggestions,
                            onUseSuggestion = onUseFollowUpSuggestion
                        )
                    }
                    if (
                        latestAnswer?.status == ChatMessageStatus.DONE &&
                        turn.latestCitations.isEmpty()
                    ) {
                        Text(
                            text = "本次回答没有返回引用，建议重新生成，或检查知识库命中内容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (turn.latestCitations.isNotEmpty()) {
                        CitationCollectionBlock(
                            citations = turn.latestCitations,
                            onCitationClick = onCitationClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatQuestionInputBlock(
    question: String,
    canAsk: Boolean,
    isAsking: Boolean,
    entryCount: Int,
    indexedEntryCount: Int,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    entryCount == 0 -> "先完成知识沉淀"
                    indexedEntryCount == 0 -> "先完成沉淀"
                    else -> "发送"
                }
            )
        }
    }
}

private data class ChatTurnUiState(
    val question: ChatMessage,
    val answers: List<ChatMessage>,
    val latestAnswer: ChatMessage?,
    val latestCitations: List<ChatCitationUiState>
) {
    val answerCount: Int
        get() = answers.size
}

private data class ChatCitationUiState(
    val citation: ChatCitation,
    val entry: KnowledgeEntry?
)

private fun buildChatTurns(
    messages: List<ChatMessage>,
    citations: List<ChatCitation>,
    knowledgeEntries: List<KnowledgeEntry>
): List<ChatTurnUiState> {
    val entriesById = knowledgeEntries.associateBy { it.id }
    val citationsByMessage = citations.groupBy { it.messageId }
    val explicitAnswersByParent = messages
        .filter { it.role == ChatRole.ASSISTANT && !it.parentMessageId.isNullOrBlank() }
        .groupBy { it.parentMessageId.orEmpty() }
    val explicitAnswerIds = explicitAnswersByParent.values.flatten().map { it.id }.toSet()
    val legacyAnswersByParent = mutableMapOf<String, MutableList<ChatMessage>>()
    var currentQuestion: ChatMessage? = null

    messages.forEach { message ->
        when (message.role) {
            ChatRole.USER -> currentQuestion = message
            ChatRole.ASSISTANT -> {
                if (message.parentMessageId.isNullOrBlank() && message.id !in explicitAnswerIds) {
                    currentQuestion?.let { question ->
                        legacyAnswersByParent.getOrPut(question.id) { mutableListOf() }.add(message)
                    }
                }
            }
        }
    }

    return messages
        .filter { it.role == ChatRole.USER }
        .map { question ->
            val answers = (explicitAnswersByParent[question.id].orEmpty() +
                legacyAnswersByParent[question.id].orEmpty())
                .distinctBy { it.id }
                .sortedWith(compareBy<ChatMessage> { it.attempt }.thenBy { it.createdAt })
            val latestAnswer = answers.lastOrNull()
            ChatTurnUiState(
                question = question,
                answers = answers,
                latestAnswer = latestAnswer,
                latestCitations = latestAnswer
                    ?.let { citationsByMessage[it.id].orEmpty() }
                    .orEmpty()
                    .map { citation ->
                        ChatCitationUiState(
                            citation = citation,
                            entry = entriesById[citation.knowledgeEntryId]
                        )
                    }
            )
        }
}

@Composable
private fun CurrentConversationBlock(
    activeSession: ChatSession?,
    hasDraftQuestion: Boolean,
    isBusy: Boolean,
    onCreateSession: () -> Unit,
    onClearSessionMemory: (ChatSession) -> Unit
) {
    val sessionWithMemory = activeSession?.takeIf { it.memoryDigest.orEmpty().isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "当前对话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        activeSession?.let { session ->
            Text(
                text = "${session.title.ifBlank { "未命名对话" }} · ${session.messageCount} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCreateSession,
                enabled = !isBusy && (activeSession != null || hasDraftQuestion),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "新对话")
            }
            sessionWithMemory?.let { session ->
                OutlinedButton(
                    onClick = { onClearSessionMemory(session) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "清除记忆")
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryBlock(
    sessions: List<ChatSession>,
    activeChatSessionId: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onClearSessions: () -> Unit,
    isBusy: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "历史记录 ${sessions.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onToggleExpanded,
                enabled = sessions.isNotEmpty()
            ) {
                Text(text = if (expanded) "收起" else "查看")
            }
            TextButton(
                onClick = onClearSessions,
                enabled = sessions.isNotEmpty() && !isBusy
            ) {
                Text(text = "清空记录")
            }
        }
        if (expanded && sessions.isEmpty()) {
            Text(
                text = "还没有历史会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (expanded) {
            sessions.take(5).forEach { session ->
                val isActive = session.id == activeChatSessionId
                val sessionLabel = buildString {
                    append(session.title.ifBlank { "未命名对话" })
                    append(" · ")
                    append(session.messageCount)
                    append(" 条")
                    if (session.lastMessagePreview.isNotBlank()) {
                        append("\n")
                        append(session.lastMessagePreview)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isActive) {
                        Button(
                            onClick = { onSelectSession(session.id) },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = sessionLabel,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectSession(session.id) },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = sessionLabel,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(
                        onClick = { onDeleteSession(session) },
                        enabled = !isBusy
                    ) {
                        Text(text = "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeChatIndexStatusBlock(
    embeddingCount: Int
) {
    Text(
        text = "可问答知识 $embeddingCount 条",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChatTurnBlock(
    turn: ChatTurnUiState,
    expanded: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
    onCitationClick: (ChatCitation) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onCopyAnswer: (String) -> Unit,
    onUseFollowUpSuggestion: (String) -> Unit
) {
    val latestAnswer = turn.latestAnswer
    val statusColor = when (latestAnswer?.status) {
        ChatMessageStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "问：${turn.question.content.compactText(44)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "答：${latestAnswer.answerPreview()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = turn.summaryMetaText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!expanded && latestAnswer?.status == ChatMessageStatus.FAILED) {
            TextButton(
                onClick = { onRetry(latestAnswer) },
                enabled = !isBusy
            ) {
                Text(text = "重试")
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()
                Text(
                    text = "完整问题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = turn.question.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "回答",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (latestAnswer == null) {
                    Text(
                        text = "正在等待回答生成。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val answerText = latestAnswer.fullAnswerText()
                    val followUpSuggestions = remember(answerText, latestAnswer.status, turn.latestCitations.size) {
                        answerText.extractFollowUpSuggestions(
                            fallbackTopic = turn.question.content,
                            allowFallback = latestAnswer.status == ChatMessageStatus.DONE &&
                                turn.latestCitations.isNotEmpty()
                        )
                    }
                    RichAnswerBlock(
                        answer = answerText.withoutFollowUpSuggestions(),
                        color = if (latestAnswer.status == ChatMessageStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = "生成 ${turn.answerCount} 次 · 当前第 ${latestAnswer.attempt.coerceAtLeast(1)} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (latestAnswer.status == ChatMessageStatus.FAILED) {
                            TextButton(
                                onClick = { onRetry(latestAnswer) },
                                enabled = !isBusy
                            ) {
                                Text(text = "重试")
                            }
                        } else {
                            TextButton(
                                onClick = { onCopyAnswer(answerText) },
                                enabled = !isBusy
                            ) {
                                Text(text = "复制")
                            }
                            TextButton(
                                onClick = { onRegenerate(latestAnswer) },
                                enabled = !isBusy
                            ) {
                                Text(text = "重新生成")
                            }
                        }
                    }
                    FollowUpSuggestionsBlock(
                        suggestions = followUpSuggestions,
                        onUseSuggestion = onUseFollowUpSuggestion
                    )
                }
                if (
                    latestAnswer?.status == ChatMessageStatus.DONE &&
                    turn.latestCitations.isEmpty()
                ) {
                    Text(
                        text = "本次回答没有返回引用，建议重新生成，或检查知识库命中内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (turn.latestCitations.isNotEmpty()) {
                    CitationCollectionBlock(
                        citations = turn.latestCitations,
                        onCitationClick = onCitationClick
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationCollectionBlock(
    citations: List<ChatCitationUiState>,
    onCitationClick: (ChatCitation) -> Unit
) {
    if (citations.isEmpty()) {
        return
    }

    val citationKey = remember(citations) {
        citations.joinToString(separator = "|") { it.citation.id }
    }
    var expanded by remember(citationKey) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (expanded) {
                    "收起引用 ${citations.size} 条"
                } else {
                    "引用 ${citations.size} 条 · 点击查看"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            citations.forEachIndexed { index, citationState ->
                CitationEvidenceCard(
                    index = index,
                    citationState = citationState,
                    onClick = { onCitationClick(citationState.citation) }
                )
            }
        }
    }
}

@Composable
private fun CitationEvidenceCard(
    index: Int,
    citationState: ChatCitationUiState,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "引用 ${index + 1} · ${citationState.titleText()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (citationState.entry == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${citationState.metaText()} · 点击查看引用内容",
                style = MaterialTheme.typography.labelSmall,
                color = if (citationState.entry == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun ChatTurnUiState.summaryMetaText(): String {
    val status = when (latestAnswer?.status) {
        ChatMessageStatus.FAILED -> "处理失败"
        ChatMessageStatus.PENDING -> "生成中"
        ChatMessageStatus.DONE -> "已回答"
        null -> "生成中"
    }
    val citationText = "引用 ${latestCitations.size} 条"
    val answerText = if (answerCount > 0) "生成 $answerCount 次" else "等待回答"
    return "$status · $citationText · $answerText"
}

private fun ChatCitationUiState.titleText(): String {
    return entry?.displayTitle() ?: "知识条目已变化"
}

private fun ChatCitationUiState.metaText(): String {
    return entry?.let {
        "来源 ${it.sourceNoteIds.size} 条 · ${it.confirmedDate} · ${it.indexStatus.label()}"
    } ?: "来源可能已删除或尚未导入，点击后会重新检查"
}

@Composable
private fun RichAnswerBlock(
    answer: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val segments = remember(answer) { parseRichAnswerSegments(answer) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is RichAnswerSegment.Heading -> {
                    Text(
                        text = segment.text,
                        style = when (segment.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.labelLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                }
                is RichAnswerSegment.Paragraph -> {
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = color
                    )
                }
                is RichAnswerSegment.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        segment.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = "•", color = color)
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = color,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                is RichAnswerSegment.NumberedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        segment.items.forEachIndexed { index, item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = "${index + 1}.", color = color)
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = color,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowUpSuggestionsBlock(
    suggestions: List<String>,
    onUseSuggestion: (String) -> Unit
) {
    if (suggestions.isEmpty()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "可以继续追问",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        suggestions.forEach { suggestion ->
            OutlinedButton(
                onClick = { onUseSuggestion(suggestion) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = suggestion,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private sealed class RichAnswerSegment {
    data class Heading(val text: String, val level: Int) : RichAnswerSegment()
    data class Paragraph(val text: String) : RichAnswerSegment()
    data class BulletList(val items: List<String>) : RichAnswerSegment()
    data class NumberedList(val items: List<String>) : RichAnswerSegment()
}

private val numberedAnswerLineRegex = Regex("""^\d+[.)、]\s*(.+)$""")

private fun String.extractFollowUpSuggestions(
    fallbackTopic: String,
    allowFallback: Boolean
): List<String> {
    val suggestions = mutableListOf<String>()
    var collecting = false
    lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val heading = line.answerHeadingTextOrNull()
        if (heading != null) {
            collecting = heading.isFollowUpSuggestionHeading()
            return@forEach
        }
        if (!collecting || line.isBlank()) {
            return@forEach
        }
        line.answerListItemTextOrNull()?.let { item ->
            if (item.length in 4..80) {
                suggestions += item
            }
        }
    }
    val parsedSuggestions = suggestions.distinct().take(3)
    if (parsedSuggestions.isNotEmpty() || !allowFallback || isKnowledgeInsufficientAnswer()) {
        return parsedSuggestions
    }
    return buildFallbackFollowUpSuggestions(fallbackTopic)
}

private fun String.withoutFollowUpSuggestions(): String {
    val keptLines = mutableListOf<String>()
    var dropping = false
    lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val heading = line.answerHeadingTextOrNull()
        if (heading != null) {
            dropping = heading.isFollowUpSuggestionHeading()
            if (dropping) {
                return@forEach
            }
        }
        if (!dropping) {
            keptLines += rawLine
        }
    }
    return keptLines.joinToString(separator = "\n").trim().ifBlank { trim() }
}

private fun String.answerHeadingTextOrNull(): String? {
    val line = trim()
    val markdownHeading = when {
        line.startsWith("###") -> line.removePrefix("###")
        line.startsWith("##") -> line.removePrefix("##")
        line.startsWith("#") -> line.removePrefix("#")
        else -> null
    }?.cleanAnswerInline()?.trim()?.trimEnd(':', '：')

    if (!markdownHeading.isNullOrBlank()) {
        return markdownHeading
    }

    val plainHeading = line.cleanAnswerInline().trim().trimEnd(':', '：')
    return plainHeading.takeIf { it.isFollowUpSuggestionHeading() }
}

private fun String.isFollowUpSuggestionHeading(): Boolean {
    val normalized = cleanAnswerInline().replace(" ", "")
    return normalized.contains("继续追问") ||
        normalized.contains("追问建议") ||
        normalized.contains("下一步问题")
}

private fun String.answerListItemTextOrNull(): String? {
    val line = trim()
    val text = when {
        line.startsWith("- ") || line.startsWith("• ") -> line.drop(2)
        else -> numberedAnswerLineRegex.find(line)?.groupValues?.getOrNull(1)
    }
    return text?.cleanAnswerInline()?.trim()?.trimEnd('。')?.takeIf { it.isNotBlank() }
}

private fun String.isKnowledgeInsufficientAnswer(): Boolean {
    return contains("当前知识库中没有足够信息") ||
        contains("没有足够信息") ||
        contains("不足以回答") ||
        contains("无法回答")
}

private fun buildFallbackFollowUpSuggestions(topic: String): List<String> {
    val compactTopic = topic.compactText(28).ifBlank { "这个问题" }
    return listOf(
        "$compactTopic 可以怎么展开？",
        "$compactTopic 有哪些风险或限制？",
        "$compactTopic 下一步应该怎么做？"
    )
}

private fun parseRichAnswerSegments(answer: String): List<RichAnswerSegment> {
    val segments = mutableListOf<RichAnswerSegment>()
    val paragraphLines = mutableListOf<String>()
    val bulletItems = mutableListOf<String>()
    val numberedItems = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            val text = paragraphLines.joinToString(separator = " ").cleanAnswerInline()
            if (text.isNotBlank()) {
                segments += RichAnswerSegment.Paragraph(text)
            }
            paragraphLines.clear()
        }
    }

    fun flushBullets() {
        if (bulletItems.isNotEmpty()) {
            segments += RichAnswerSegment.BulletList(bulletItems.toList())
            bulletItems.clear()
        }
    }

    fun flushNumbered() {
        if (numberedItems.isNotEmpty()) {
            segments += RichAnswerSegment.NumberedList(numberedItems.toList())
            numberedItems.clear()
        }
    }

    fun flushAll() {
        flushParagraph()
        flushBullets()
        flushNumbered()
    }

    answer.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) {
            flushAll()
            return@forEach
        }

        val numberedMatch = numberedAnswerLineRegex.find(line)
        when {
            line.startsWith("### ") -> {
                flushAll()
                line.removePrefix("### ").cleanAnswerInline().takeIf { it.isNotBlank() }?.let {
                    segments += RichAnswerSegment.Heading(text = it, level = 3)
                }
            }
            line.startsWith("## ") -> {
                flushAll()
                line.removePrefix("## ").cleanAnswerInline().takeIf { it.isNotBlank() }?.let {
                    segments += RichAnswerSegment.Heading(text = it, level = 2)
                }
            }
            line.startsWith("# ") -> {
                flushAll()
                line.removePrefix("# ").cleanAnswerInline().takeIf { it.isNotBlank() }?.let {
                    segments += RichAnswerSegment.Heading(text = it, level = 1)
                }
            }
            line.startsWith("- ") || line.startsWith("• ") -> {
                flushParagraph()
                flushNumbered()
                line.drop(2).cleanAnswerInline().takeIf { it.isNotBlank() }?.let {
                    bulletItems += it
                }
            }
            numberedMatch != null -> {
                flushParagraph()
                flushBullets()
                numberedMatch.groupValues[1].cleanAnswerInline().takeIf { it.isNotBlank() }?.let {
                    numberedItems += it
                }
            }
            else -> {
                flushBullets()
                flushNumbered()
                paragraphLines += line
            }
        }
    }
    flushAll()

    if (segments.isEmpty()) {
        val fallback = answer.cleanAnswerInline()
        if (fallback.isNotBlank()) {
            segments += RichAnswerSegment.Paragraph(fallback)
        }
    }
    return segments
}

private fun String.cleanAnswerInline(): String {
    return trim()
        .replace("**", "")
        .replace("__", "")
}

private fun ChatMessage?.answerPreview(): String {
    if (this == null) {
        return "正在等待回答生成..."
    }
    return fullAnswerText().compactText(68)
}

private fun ChatMessage.fullAnswerText(): String {
    return if (status == ChatMessageStatus.FAILED) {
        errorMessage ?: content
    } else {
        content
    }
}

private fun String.compactText(maxLength: Int): String {
    val compact = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
    return if (compact.length <= maxLength) {
        compact
    } else {
        compact.take(maxLength) + "..."
    }
}

private fun KnowledgeEntry.toReferenceText(): String {
    val title = userTitle?.takeIf { it.isNotBlank() } ?: "未命名知识"
    return "“$title”\n$content\n—— 纳知 $confirmedDate"
}

private fun ChatCitation.toReferenceText(entry: KnowledgeEntry): String {
    val title = entry.displayTitle()
    val quoteText = quote.takeIf { it.isNotBlank() } ?: entry.summary.ifBlank { entry.content.compactText(96) }
    val reasonText = reason.takeIf { it.isNotBlank() } ?: "未返回引用理由"
    return "引用来源：$title\n引用短句：$quoteText\n引用理由：$reasonText\n—— 纳知 ${entry.confirmedDate}"
}

private fun KnowledgeEntry.displayTitle(): String {
    return userTitle?.takeIf { it.isNotBlank() }
        ?: summary.takeIf { it.isNotBlank() }?.compactText(28)
        ?: content.lineSequence().firstOrNull().orEmpty().ifBlank { "未命名知识" }.compactText(28)
}

private fun KnowledgeIndexStatus.label(): String {
    return when (this) {
        KnowledgeIndexStatus.PENDING -> "已沉淀"
        KnowledgeIndexStatus.INDEXING -> "已沉淀"
        KnowledgeIndexStatus.INDEXED -> "已沉淀"
        KnowledgeIndexStatus.FAILED -> "处理失败"
    }
}

private fun Throwable.toUserFacingMessage(): String {
    return when (this) {
        is NazhiBackendException -> when {
            statusCode == 401 || code == "UNAUTHORIZED" -> "鉴权失败，请检查设置页中的服务 Token。"
            else -> publicMessage
        }
        else -> message?.takeIf { it.isNotBlank() } ?: "请求失败，请稍后重试。"
    }
}

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
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
        com.nazhi.app.core.model.AiTaskStage.FAILED -> "处理失败"
        com.nazhi.app.core.model.AiTaskStage.UNKNOWN -> "处理中"
    }
}
