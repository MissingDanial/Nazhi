package com.nazhi.app.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.ReviewSession
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.model.isMeaningfulKnowledgeDuplicateKey
import com.nazhi.app.core.model.toKnowledgeDuplicateKey
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionState
import com.nazhi.app.core.knowledge.KnowledgeTaskKind
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.toLocalDateId
import com.nazhi.app.core.util.todayDateId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun InboxRoute(
    repository: NazhiRepository,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator,
    initialShareText: String? = null,
    initialShareSource: String? = null,
    onShareConsumed: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenKnowledge: () -> Unit = {}
) {
    val dateId = remember { todayDateId() }
    val historyPendingCount by remember(repository, dateId) {
        repository.observePendingCountBeforeDate(dateId)
    }.collectAsState(initial = 0)

    DateNotesRoute(
        repository = repository,
        knowledgeIngestionCoordinator = knowledgeIngestionCoordinator,
        dateId = dateId,
        screenTitle = "纳知",
        screenSubtitle = "今日",
        summaryLabel = "今日保存",
        reviewTitle = "今日回顾",
        showQuickInput = true,
        initialShareText = initialShareText,
        initialShareSource = initialShareSource,
        onShareConsumed = onShareConsumed,
        historyPendingCount = historyPendingCount,
        onOpenHistoricalPending = onOpenCalendar,
        onOpenKnowledge = onOpenKnowledge
    )
}

@Composable
fun DateNotesRoute(
    repository: NazhiRepository,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator? = null,
    dateId: String,
    screenTitle: String,
    screenSubtitle: String,
    summaryLabel: String,
    reviewTitle: String,
    showQuickInput: Boolean,
    initialShareText: String? = null,
    initialShareSource: String? = null,
    onShareConsumed: () -> Unit = {},
    historyPendingCount: Int = 0,
    onOpenHistoricalPending: () -> Unit = {},
    onOpenKnowledge: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null
) {
    val notes by remember(repository, dateId) {
        repository.observeNotesForDate(dateId)
    }.collectAsState(initial = emptyList())
    val dayKnowledgeStatus by remember(repository, dateId) {
        repository.observeDayKnowledgeStatus(dateId)
    }.collectAsState(initial = DayKnowledgeStatus(dateId, 0, 0, 0, 0, 0, 0, 0, 0))
    val knowledgeIngestionState by remember(knowledgeIngestionCoordinator) {
        knowledgeIngestionCoordinator?.state ?: flowOf(KnowledgeIngestionState())
    }.collectAsState(initial = KnowledgeIngestionState())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pendingReviewNotes = notes.filter { it.status == NoteStatus.INBOX }
    val reviewedCount = notes.count { it.status == NoteStatus.REVIEWED }
    var isReviewMode by remember { mutableStateOf(false) }
    var reviewIndex by remember { mutableStateOf(0) }
    var input by remember(initialShareText) { mutableStateOf(initialShareText.orEmpty()) }
    var inputSourceType by remember(initialShareText) {
        mutableStateOf(if (initialShareText.isNullOrBlank()) SourceType.MANUAL else SourceType.SHARE)
    }
    var inputSourceApp by remember(initialShareText, initialShareSource) {
        mutableStateOf(initialShareSource)
    }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var deletingNote by remember { mutableStateOf<Note?>(null) }
    var deleteUpdatesReviewSession by remember { mutableStateOf(false) }
    var handledKnowledgeTaskEventId by remember { mutableStateOf(knowledgeIngestionState.eventId) }
    val isAiOrganizing = knowledgeIngestionState.isRunning &&
        knowledgeIngestionState.taskKind == KnowledgeTaskKind.ORGANIZE
    val isKnowledgeTaskRunning = knowledgeIngestionState.isRunning
    val aiOrganizeProgress = if (isAiOrganizing) knowledgeIngestionState.progress else null
    val aiOrganizeMessage = knowledgeIngestionState.message.takeIf {
        knowledgeIngestionState.isRunning &&
            knowledgeIngestionState.taskKind == KnowledgeTaskKind.ORGANIZE &&
            knowledgeIngestionState.progress == null
    }
    val currentReviewNote = pendingReviewNotes.getOrNull(
        reviewIndex.coerceAtMost((pendingReviewNotes.size - 1).coerceAtLeast(0))
    )

    LaunchedEffect(pendingReviewNotes.size) {
        if (pendingReviewNotes.isEmpty()) {
            isReviewMode = false
            reviewIndex = 0
        } else if (reviewIndex > pendingReviewNotes.lastIndex) {
            reviewIndex = pendingReviewNotes.lastIndex
        }
    }

    LaunchedEffect(knowledgeIngestionState.eventId) {
        val message = knowledgeIngestionState.message
        if (knowledgeIngestionState.eventId != handledKnowledgeTaskEventId && !message.isNullOrBlank()) {
            handledKnowledgeTaskEventId = knowledgeIngestionState.eventId
            snackbarHostState.showSnackbar(message)
            if (
                knowledgeIngestionState.completedTaskKind == KnowledgeTaskKind.ORGANIZE &&
                message.startsWith("已生成")
            ) {
                onOpenKnowledge()
            }
        }
    }

    InboxScreen(
        screenTitle = screenTitle,
        screenSubtitle = screenSubtitle,
        summaryLabel = summaryLabel,
        reviewTitle = reviewTitle,
        notes = notes,
        input = input,
        inputSourceType = inputSourceType,
        showQuickInput = showQuickInput,
        historyPendingCount = historyPendingCount,
        pendingReviewCount = pendingReviewNotes.size,
        pendingDraftCount = dayKnowledgeStatus.pendingDraftCount,
        reviewedCount = reviewedCount,
        isAiOrganizing = isAiOrganizing,
        isKnowledgeTaskRunning = isKnowledgeTaskRunning,
        aiOrganizeProgress = aiOrganizeProgress,
        aiOrganizeMessage = aiOrganizeMessage,
        isReviewMode = isReviewMode,
        currentReviewNote = currentReviewNote,
        reviewIndex = reviewIndex,
        snackbarHostState = snackbarHostState,
        onInputChange = {
            input = it
            if (inputSourceType == SourceType.SHARE && it != initialShareText) {
                inputSourceType = SourceType.MANUAL
                inputSourceApp = null
            }
        },
        onPasteClipboard = {
            val clipboardText = context.readClipboardText()
            if (clipboardText.isNullOrBlank()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("剪贴板没有可保存的文本")
                }
            } else {
                input = clipboardText
                inputSourceType = SourceType.CLIPBOARD
                inputSourceApp = "系统剪贴板"
            }
        },
        onSave = {
            val content = input.trim()
            if (content.isEmpty()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("请输入要保存的内容")
                }
                return@InboxScreen
            }

            val now = System.currentTimeMillis()
            val duplicateKey = content.toKnowledgeDuplicateKey()
            val hasDuplicateToday = duplicateKey.isMeaningfulKnowledgeDuplicateKey() &&
                notes.any { note -> note.content.toKnowledgeDuplicateKey() == duplicateKey }
            if (hasDuplicateToday) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("今日已存在相同内容，未重复保存")
                }
                return@InboxScreen
            }

            val note = Note(
                id = UUID.randomUUID().toString(),
                content = content,
                title = content.toTitle(),
                sourceType = inputSourceType,
                sourceApp = inputSourceApp,
                    sourceUrl = content.extractFirstUrl(),
                    createdAt = now,
                    createdDate = now.toLocalDateId(),
                    updatedAt = now,
                    status = NoteStatus.INBOX,
                    userRemark = null
            )

            coroutineScope.launch {
                repository.saveNote(note)
                input = ""
                inputSourceType = SourceType.MANUAL
                inputSourceApp = null
                onShareConsumed()
                snackbarHostState.showSnackbar("已保存到今日收件箱")
            }
        },
        onEdit = { note ->
            editingNote = note
        },
        onCopy = { note ->
            context.copyToClipboard(label = "纳知记录", text = note.content)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("已复制原文")
            }
        },
        onDelete = { note ->
            deletingNote = note
            deleteUpdatesReviewSession = false
        },
        onAiOrganizeToday = {
            knowledgeIngestionCoordinator?.organizeToday(dateId)
        },
        onStartReview = {
            if (pendingReviewNotes.isEmpty()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("没有待回顾内容")
                }
            } else {
                reviewIndex = 0
                isReviewMode = true
            }
        },
        onStopReview = {
            isReviewMode = false
        },
        onSkipReview = {
            if (pendingReviewNotes.isNotEmpty()) {
                reviewIndex = (reviewIndex + 1) % pendingReviewNotes.size
            }
        },
        onConfirmIntent = { intentType ->
            val note = currentReviewNote
            if (note != null) {
                val now = System.currentTimeMillis()
                val confirmedDate = now.toLocalDateId()
                val reviewDateId = note.createdDate
                val entry = KnowledgeEntry(
                    id = UUID.randomUUID().toString(),
                    noteId = note.id,
                    content = note.content,
                    intentType = intentType,
                    userTitle = note.title,
                    userRemark = note.userRemark,
                    createdAt = note.createdAt,
                    createdDate = note.createdDate,
                    confirmedAt = now,
                    confirmedDate = confirmedDate
                )

                coroutineScope.launch {
                    repository.saveKnowledgeEntry(entry)
                    repository.updateNoteStatus(note.id, NoteStatus.REVIEWED, now)
                    val previousSession = repository.getReviewSession(reviewDateId)
                    val remainingCount = (pendingReviewNotes.size - 1).coerceAtLeast(0)
                    repository.saveReviewSession(
                        previousSession.toConfirmedReviewSession(
                            dateId = reviewDateId,
                            currentNotes = notes,
                            remainingCount = remainingCount,
                            now = now
                        )
                    )

                    if (remainingCount == 0) {
                        isReviewMode = false
                        reviewIndex = 0
                        snackbarHostState.showSnackbar("今日回顾完成")
                    } else {
                        snackbarHostState.showSnackbar("已沉淀为${intentType.label()}")
                    }
                }
            }
        },
        onDeleteFromReview = { note ->
            deletingNote = note
            deleteUpdatesReviewSession = true
        },
        onOpenHistoricalPending = onOpenHistoricalPending,
        onNavigateBack = onNavigateBack
    )

    editingNote?.let { note ->
        EditNoteDialog(
            note = note,
            onDismiss = { editingNote = null },
            onConfirm = { content, remark ->
                val now = System.currentTimeMillis()
                coroutineScope.launch {
                    repository.updateNoteContent(
                        id = note.id,
                        content = content,
                        title = content.toTitle(),
                        sourceUrl = content.extractFirstUrl(),
                        userRemark = remark.takeIf { it.isNotBlank() },
                        updatedAt = now
                    )
                    editingNote = null
                    snackbarHostState.showSnackbar("已更新记录")
                }
            }
        )
    }

    deletingNote?.let { note ->
        DeleteNoteDialog(
            note = note,
            onDismiss = {
                deletingNote = null
                deleteUpdatesReviewSession = false
            },
            onConfirm = {
                val shouldUpdateReviewSession = deleteUpdatesReviewSession && note.status == NoteStatus.INBOX
                deletingNote = null
                deleteUpdatesReviewSession = false
                coroutineScope.launch {
                    val now = System.currentTimeMillis()
                    repository.softDeleteNote(
                        id = note.id,
                        updatedAt = now
                    )
                    if (shouldUpdateReviewSession) {
                        val reviewDateId = note.createdDate
                        val previousSession = repository.getReviewSession(reviewDateId)
                        val remainingCount = (pendingReviewNotes.size - 1).coerceAtLeast(0)
                        repository.saveReviewSession(
                            previousSession.toDeletedReviewSession(
                                dateId = reviewDateId,
                                currentNotes = notes,
                                remainingCount = remainingCount,
                                now = now
                            )
                        )
                        if (remainingCount == 0) {
                            isReviewMode = false
                            reviewIndex = 0
                            snackbarHostState.showSnackbar("今日回顾完成")
                        } else {
                            snackbarHostState.showSnackbar("已删除记录")
                        }
                    } else {
                        snackbarHostState.showSnackbar("已删除记录")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    screenTitle: String,
    screenSubtitle: String,
    summaryLabel: String,
    reviewTitle: String,
    notes: List<Note>,
    input: String,
    inputSourceType: SourceType,
    showQuickInput: Boolean,
    historyPendingCount: Int,
    pendingReviewCount: Int,
    pendingDraftCount: Int,
    reviewedCount: Int,
    isAiOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    aiOrganizeProgress: AiTaskProgress?,
    aiOrganizeMessage: String?,
    isReviewMode: Boolean,
    currentReviewNote: Note?,
    reviewIndex: Int,
    snackbarHostState: SnackbarHostState,
    onInputChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit,
    onEdit: (Note) -> Unit,
    onCopy: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onAiOrganizeToday: () -> Unit,
    onStartReview: () -> Unit,
    onStopReview: () -> Unit,
    onSkipReview: () -> Unit,
    onConfirmIntent: (IntentType) -> Unit,
    onDeleteFromReview: (Note) -> Unit,
    onOpenHistoricalPending: () -> Unit,
    onNavigateBack: (() -> Unit)?
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onNavigateBack != null) {
                        TextButton(onClick = onNavigateBack) {
                            Text(text = "返回")
                        }
                    }
                },
                title = {
                    Column {
                        Text(text = screenTitle)
                        Text(
                            text = screenSubtitle,
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
            if (showQuickInput) {
                item {
                    QuickInputCard(
                        input = input,
                        sourceType = inputSourceType,
                        onInputChange = onInputChange,
                        onPasteClipboard = onPasteClipboard,
                        onSave = onSave
                    )
                }
            }

            if (showQuickInput) {
                item {
                    AiOrganizeTodayCard(
                        totalCount = notes.size,
                        pendingCount = pendingReviewCount,
                        pendingDraftCount = pendingDraftCount,
                        reviewedCount = reviewedCount,
                        isOrganizing = isAiOrganizing,
                        isKnowledgeTaskRunning = isKnowledgeTaskRunning,
                        progress = aiOrganizeProgress,
                        statusMessage = aiOrganizeMessage,
                        onOrganize = onAiOrganizeToday
                    )
                }
            }

            item {
                InboxSummary(label = summaryLabel, notes = notes)
            }

            if (showQuickInput && historyPendingCount > 0) {
                item {
                    HistoricalPendingCard(
                        pendingCount = historyPendingCount,
                        onOpen = onOpenHistoricalPending
                    )
                }
            }

            item {
                DailyReviewCard(
                    title = reviewTitle,
                    totalCount = notes.size,
                    pendingCount = pendingReviewCount,
                    reviewedCount = reviewedCount,
                    isReviewMode = isReviewMode,
                    currentNote = currentReviewNote,
                    reviewIndex = reviewIndex,
                    onStartReview = onStartReview,
                    onStopReview = onStopReview,
                    onSkip = onSkipReview,
                    onConfirmIntent = onConfirmIntent,
                    onDelete = {
                        currentReviewNote?.let(onDeleteFromReview)
                    }
                )
            }

            if (notes.isEmpty()) {
                item {
                    EmptyInboxCard(showQuickInput = showQuickInput)
                }
            } else {
                items(
                    items = notes,
                    key = { note -> note.id }
                ) { note ->
                    NoteCard(
                        note = note,
                        onEdit = { onEdit(note) },
                        onCopy = { onCopy(note) },
                        onDelete = { onDelete(note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiOrganizeTodayCard(
    totalCount: Int,
    pendingCount: Int,
    pendingDraftCount: Int,
    reviewedCount: Int,
    isOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    progress: AiTaskProgress?,
    statusMessage: String?,
    onOrganize: () -> Unit
) {
    val canOrganize = pendingCount > 0 && pendingDraftCount == 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "AI 整理今日收件箱",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "跳过逐条整理，让 AI 先合并、分类和打标签，你只需要确认草稿。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "今日 $totalCount 条 · 未处理 $pendingCount 条 · 待确认草稿 $pendingDraftCount 条 · 已处理 $reviewedCount 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            progress?.let { taskProgress ->
                RequestProgressBlock(progress = taskProgress)
            }
            if (progress == null && isOrganizing && !statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!canOrganize) {
                Text(
                    text = when {
                        pendingDraftCount > 0 -> "已有待确认草稿，先到知识库确认后再整理。"
                        pendingCount == 0 -> "当前没有待整理内容。"
                        else -> "当前不可整理。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Button(
                onClick = onOrganize,
                enabled = canOrganize && !isKnowledgeTaskRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isOrganizing -> "AI 整理中"
                        isKnowledgeTaskRunning -> "知识处理中"
                        pendingDraftCount > 0 -> "先确认草稿"
                        pendingCount == 0 -> "暂无可整理"
                        else -> "AI 整理今日"
                    }
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
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = progress.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
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

@Composable
private fun DailyReviewCard(
    title: String,
    totalCount: Int,
    pendingCount: Int,
    reviewedCount: Int,
    isReviewMode: Boolean,
    currentNote: Note?,
    reviewIndex: Int,
    onStartReview: () -> Unit,
    onStopReview: () -> Unit,
    onSkip: () -> Unit,
    onConfirmIntent: (IntentType) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (title == "今日回顾") "可选人工整理" else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "AI 整理是主流程；这里保留逐条人工整理作为补充。待处理 $pendingCount · 已处理 $reviewedCount · 总计 $totalCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            if (isReviewMode && currentNote != null) {
                ReviewPanel(
                    note = currentNote,
                    position = reviewIndex + 1,
                    total = pendingCount,
                    onConfirmIntent = onConfirmIntent,
                    onSkip = onSkip,
                    onDelete = onDelete,
                    onStop = onStopReview
                )
            } else {
                Button(
                    onClick = onStartReview,
                    enabled = pendingCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (pendingCount > 0) "逐条人工整理" else "今日已完成")
                }
            }
        }
    }
}

@Composable
private fun ReviewPanel(
    note: Note,
    position: Int,
    total: Int,
    onConfirmIntent: (IntentType) -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$position / $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = note.title ?: "未命名记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${note.sourceType.label()} · ${note.createdAt.formatTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { onConfirmIntent(IntentType.QUOTABLE) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "可引用")
            }
            OutlinedButton(
                onClick = { onConfirmIntent(IntentType.INSPIRATION) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "灵感")
            }
            OutlinedButton(
                onClick = { onConfirmIntent(IntentType.READ_LATER) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "稍后看")
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onStop) {
                    Text(text = "退出")
                }
                TextButton(onClick = onSkip) {
                    Text(text = "跳过")
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun HistoricalPendingCard(
    pendingCount: Int,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "历史待回顾",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "还有 $pendingCount 条过去日期的记录未沉淀。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "查看日历")
            }
        }
    }
}

@Composable
private fun QuickInputCard(
    input: String,
    sourceType: SourceType,
    onInputChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "快速记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (sourceType == SourceType.SHARE) {
                Text(
                    text = "已读取分享内容，请确认后保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (sourceType == SourceType.CLIPBOARD) {
                Text(
                    text = "已读取剪贴板，请确认后保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("保存文章摘录、链接或灵感") }
            )
            OutlinedButton(
                onClick = onPasteClipboard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "粘贴剪贴板")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "保存到今日收件箱")
            }
        }
    }
}

@Composable
private fun InboxSummary(
    label: String,
    notes: List<Note>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${notes.size} 条",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun EmptyInboxCard(showQuickInput: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (showQuickInput) "今天还没有保存内容" else "这一天没有记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (showQuickInput) {
                    "先保存一段文章摘录、链接或灵感。"
                } else {
                    "可以回到日历选择其他日期查看。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = note.title ?: "未命名记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${note.sourceType.label()} · ${note.status.label()} · ${note.createdAt.formatTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            note.userRemark?.takeIf { it.isNotBlank() }?.let { remark ->
                Text(
                    text = "备注：$remark",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopy) {
                    Text(text = "复制")
                }
                TextButton(onClick = onEdit) {
                    Text(text = "编辑")
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun EditNoteDialog(
    note: Note,
    onDismiss: () -> Unit,
    onConfirm: (content: String, remark: String) -> Unit
) {
    var content by remember(note.id) { mutableStateOf(note.content) }
    var remark by remember(note.id) { mutableStateOf(note.userRemark.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text("正文") }
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("备注，可选") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) {
                        onConfirm(content.trim(), remark.trim())
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
private fun DeleteNoteDialog(
    note: Note,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除记录") },
        text = {
            Text(
                text = "确定删除“${note.title ?: "未命名记录"}”吗？删除后不会出现在今日收件箱。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun InboxPreview() {
    val now = System.currentTimeMillis()
    MaterialTheme {
        InboxScreen(
            screenTitle = "纳知",
            screenSubtitle = "今日",
            summaryLabel = "今日保存",
            reviewTitle = "今日回顾",
            notes = listOf(
                Note(
                    id = "1",
                    content = "产品第一版要先验证文本保存、今日回顾和引用是否成立。",
                    title = "产品第一版要先验证文本保存",
                    sourceType = SourceType.MANUAL,
                    sourceApp = null,
                    sourceUrl = null,
                    createdAt = now,
                    createdDate = now.toLocalDateId(),
                    updatedAt = now,
                    status = NoteStatus.INBOX,
                    userRemark = null
                )
            ),
            input = "",
            inputSourceType = SourceType.MANUAL,
            showQuickInput = true,
            historyPendingCount = 0,
            snackbarHostState = SnackbarHostState(),
            pendingReviewCount = 1,
            pendingDraftCount = 0,
            reviewedCount = 0,
            isAiOrganizing = false,
            isKnowledgeTaskRunning = false,
            aiOrganizeProgress = null,
            aiOrganizeMessage = null,
            isReviewMode = false,
            currentReviewNote = null,
            reviewIndex = 0,
            onInputChange = {},
            onPasteClipboard = {},
            onSave = {},
            onEdit = {},
            onCopy = {},
            onDelete = {},
            onAiOrganizeToday = {},
            onStartReview = {},
            onStopReview = {},
            onSkipReview = {},
            onConfirmIntent = { _ -> },
            onDeleteFromReview = {},
            onOpenHistoricalPending = {},
            onNavigateBack = null
        )
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

private fun SourceType.label(): String {
    return when (this) {
        SourceType.SHARE -> "分享"
        SourceType.MANUAL -> "手动输入"
        SourceType.CLIPBOARD -> "剪贴板"
        SourceType.TEXT_SELECTION -> "划词"
    }
}

private fun NoteStatus.label(): String {
    return when (this) {
        NoteStatus.INBOX -> "待回顾"
        NoteStatus.REVIEWED -> "已沉淀"
        NoteStatus.ARCHIVED -> "已归档"
        NoteStatus.DELETED -> "已删除"
    }
}

private fun IntentType.label(): String {
    return when (this) {
        IntentType.READ_LATER -> "稍后看"
        IntentType.QUOTABLE -> "可引用"
        IntentType.INSPIRATION -> "灵感"
    }
}

private fun Throwable.toUserFacingMessage(): String {
    return when (this) {
        is NazhiBackendException -> when {
            statusCode == 401 || code == "UNAUTHORIZED" -> "鉴权失败，请检查设置页中的 NAZHI_DEV_TOKEN。"
            code == "MINIMAX_CHAT_FAILED" -> "模型整理失败，请稍后重试或检查服务器日志。"
            code == "MINIMAX_NOT_CONFIGURED" -> "服务器模型配置缺失，请检查 .env。"
            else -> publicMessage
        }
        else -> {
            val raw = message.orEmpty()
            when {
                raw.contains("Failed to connect", ignoreCase = true) -> "无法连接后端，请检查服务器地址、端口和防火墙。"
                raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> {
                    "请求超时，请检查服务器网络或稍后重试。"
                }
                raw.isNotBlank() -> raw
                else -> "请求失败，请检查后端服务。"
            }
        }
    }
}

private fun Long.formatTime(): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(this))
}

private fun ReviewSession?.toConfirmedReviewSession(
    dateId: String,
    currentNotes: List<Note>,
    remainingCount: Int,
    now: Long
): ReviewSession {
    val deletedCount = this?.deletedCount ?: 0
    val confirmedCount = currentNotes.count { it.status == NoteStatus.REVIEWED } + 1
    val totalCount = maxOf(this?.totalCount ?: 0, confirmedCount + deletedCount + remainingCount)
    return ReviewSession(
        id = dateId,
        date = dateId,
        totalCount = totalCount,
        confirmedCount = confirmedCount,
        deletedCount = deletedCount,
        completedAt = if (remainingCount == 0) now else this?.completedAt
    )
}

private fun ReviewSession?.toDeletedReviewSession(
    dateId: String,
    currentNotes: List<Note>,
    remainingCount: Int,
    now: Long
): ReviewSession {
    val deletedCount = (this?.deletedCount ?: 0) + 1
    val confirmedCount = currentNotes.count { it.status == NoteStatus.REVIEWED }
    val totalCount = maxOf(this?.totalCount ?: 0, confirmedCount + deletedCount + remainingCount)
    return ReviewSession(
        id = dateId,
        date = dateId,
        totalCount = totalCount,
        confirmedCount = confirmedCount,
        deletedCount = deletedCount,
        completedAt = if (remainingCount == 0) now else this?.completedAt
    )
}

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun Context.readClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
