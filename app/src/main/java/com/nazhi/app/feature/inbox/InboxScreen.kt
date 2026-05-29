package com.nazhi.app.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.core.content.ContextCompat
import com.nazhi.app.AudioTranscriptionService
import com.nazhi.app.core.farm.DailyFarmRuleEngine
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionState
import com.nazhi.app.core.knowledge.KnowledgeTaskKind
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.DailyFarmSnapshot
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.model.findDuplicateEntry
import com.nazhi.app.core.model.isMeaningfulKnowledgeDuplicateKey
import com.nazhi.app.core.model.toKnowledgeDuplicateKey
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.toLocalDateId
import com.nazhi.app.core.util.todayDateId
import com.nazhi.app.feature.farm.DailyFarmPreview
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
    val audioJobs by remember(repository, dateId) {
        repository.observeAudioTranscriptionJobsForDate(dateId)
    }.collectAsState(initial = emptyList())
    val dayKnowledgeStatus by remember(repository, dateId) {
        repository.observeDayKnowledgeStatus(dateId)
    }.collectAsState(initial = DayKnowledgeStatus(dateId, 0, 0, 0, 0, 0, 0, 0, 0))
    val drafts by remember(repository, dateId) {
        repository.observeKnowledgeDraftsForDate(dateId)
    }.collectAsState(initial = emptyList())
    val knowledgeEntries by remember(repository, dateId) {
        repository.observeKnowledgeEntriesForDate(dateId)
    }.collectAsState(initial = emptyList())
    val knowledgeIngestionState by remember(knowledgeIngestionCoordinator) {
        knowledgeIngestionCoordinator?.state ?: flowOf(KnowledgeIngestionState())
    }.collectAsState(initial = KnowledgeIngestionState())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pendingReviewNotes = notes.filter { it.status == NoteStatus.INBOX }
    val visibleAudioJobs = audioJobs.filter { it.shouldShowInInbox() }
    val pendingDrafts = drafts.filter { it.status == KnowledgeDraftStatus.PENDING }
    val hasDuplicateDrafts = pendingDrafts.any { draft ->
        draft.findDuplicateEntry(knowledgeEntries) != null
    }
    val hasReviewRequiredDrafts = pendingDrafts.any { it.needsReview }
    val farmSnapshot = remember(dateId, notes, dayKnowledgeStatus, visibleAudioJobs) {
        DailyFarmRuleEngine.buildSnapshot(
            dateId = dateId,
            notes = notes,
            knowledgeStatus = dayKnowledgeStatus,
            audioJobs = visibleAudioJobs
        )
    }
    val reviewedCount = notes.count { it.status == NoteStatus.REVIEWED }
    var input by remember(initialShareText) { mutableStateOf(initialShareText.orEmpty()) }
    var inputSourceType by remember(initialShareText) {
        mutableStateOf(if (initialShareText.isNullOrBlank()) SourceType.MANUAL else SourceType.SHARE)
    }
    var inputSourceApp by remember(initialShareText, initialShareSource) {
        mutableStateOf(initialShareSource)
    }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var deletingNote by remember { mutableStateOf<Note?>(null) }
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
    LaunchedEffect(knowledgeIngestionState.eventId) {
        val message = knowledgeIngestionState.message
        if (knowledgeIngestionState.eventId != handledKnowledgeTaskEventId && !message.isNullOrBlank()) {
            handledKnowledgeTaskEventId = knowledgeIngestionState.eventId
            snackbarHostState.showSnackbar(message)
        }
    }

    InboxScreen(
        screenTitle = screenTitle,
        screenSubtitle = screenSubtitle,
        notes = notes,
        audioJobs = visibleAudioJobs,
        farmSnapshot = farmSnapshot,
        dayKnowledgeStatus = dayKnowledgeStatus,
        pendingDrafts = pendingDrafts,
        knowledgeEntries = knowledgeEntries,
        input = input,
        inputSourceType = inputSourceType,
        showQuickInput = showQuickInput,
        historyPendingCount = historyPendingCount,
        pendingReviewCount = pendingReviewNotes.size,
        pendingDraftCount = dayKnowledgeStatus.pendingDraftCount,
        reviewedCount = reviewedCount,
        hasDuplicateDrafts = hasDuplicateDrafts,
        hasReviewRequiredDrafts = hasReviewRequiredDrafts,
        isAiOrganizing = isAiOrganizing,
        isKnowledgeTaskRunning = isKnowledgeTaskRunning,
        aiOrganizeProgress = aiOrganizeProgress,
        aiOrganizeMessage = aiOrganizeMessage,
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
        },
        onRetryAudioJobs = {
            val intent = Intent(context, AudioTranscriptionService::class.java)
                .setAction(AudioTranscriptionService.ACTION_RETRY_PENDING)
            ContextCompat.startForegroundService(context, intent)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("已提交待转写音频重试")
            }
        },
        onAiOrganizeToday = {
            knowledgeIngestionCoordinator?.organizeToday(dateId)
        },
        onSubmitAllDrafts = {
            knowledgeIngestionCoordinator?.submitAll(
                date = dateId,
                hasDuplicateDrafts = hasDuplicateDrafts,
                hasReviewRequiredDrafts = hasReviewRequiredDrafts
            )
        },
        onRetryIndex = {
            knowledgeIngestionCoordinator?.indexPending()
        },
        onOpenHistoricalPending = onOpenHistoricalPending,
        onOpenKnowledge = onOpenKnowledge,
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
            },
            onConfirm = {
                deletingNote = null
                coroutineScope.launch {
                    val now = System.currentTimeMillis()
                    repository.softDeleteNote(
                        id = note.id,
                        updatedAt = now
                    )
                    snackbarHostState.showSnackbar("已删除记录")
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
    notes: List<Note>,
    audioJobs: List<AudioTranscriptionJob>,
    farmSnapshot: DailyFarmSnapshot,
    dayKnowledgeStatus: DayKnowledgeStatus,
    pendingDrafts: List<KnowledgeEntryDraft>,
    knowledgeEntries: List<KnowledgeEntry>,
    input: String,
    inputSourceType: SourceType,
    showQuickInput: Boolean,
    historyPendingCount: Int,
    pendingReviewCount: Int,
    pendingDraftCount: Int,
    reviewedCount: Int,
    hasDuplicateDrafts: Boolean,
    hasReviewRequiredDrafts: Boolean,
    isAiOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    aiOrganizeProgress: AiTaskProgress?,
    aiOrganizeMessage: String?,
    snackbarHostState: SnackbarHostState,
    onInputChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit,
    onEdit: (Note) -> Unit,
    onCopy: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onRetryAudioJobs: () -> Unit,
    onAiOrganizeToday: () -> Unit,
    onSubmitAllDrafts: () -> Unit,
    onRetryIndex: () -> Unit,
    onOpenHistoricalPending: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onNavigateBack: (() -> Unit)?
) {
    var selectedPanel by remember { mutableStateOf<TodayPanel?>(null) }
    val retryableAudioCount = audioJobs.count { it.canRetry }
    val hasIssue = retryableAudioCount > 0 || dayKnowledgeStatus.failedIndexCount > 0

    LaunchedEffect(input, showQuickInput) {
        if (showQuickInput && input.isNotBlank() && selectedPanel == null) {
            selectedPanel = TodayPanel.CAPTURED
        }
    }

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
            item {
                TodayStatusChips(
                    totalCount = notes.size,
                    pendingCount = pendingReviewCount,
                    pendingDraftCount = pendingDraftCount,
                    knowledgeCount = dayKnowledgeStatus.knowledgeEntryCount,
                    issueCount = retryableAudioCount + dayKnowledgeStatus.failedIndexCount,
                    selectedPanel = selectedPanel,
                    onSelectPanel = { panel ->
                        selectedPanel = if (selectedPanel == panel) null else panel
                    }
                )
            }

            item {
                DailyFarmPreview(snapshot = farmSnapshot)
            }

            item {
                TodayPrimaryActionCard(
                    showQuickInput = showQuickInput,
                    totalCount = notes.size,
                    pendingCount = pendingReviewCount,
                    pendingDraftCount = pendingDraftCount,
                    reviewedCount = reviewedCount,
                    knowledgeCount = dayKnowledgeStatus.knowledgeEntryCount,
                    hasIssue = hasIssue,
                    isAiOrganizing = isAiOrganizing,
                    isKnowledgeTaskRunning = isKnowledgeTaskRunning,
                    progress = aiOrganizeProgress,
                    statusMessage = aiOrganizeMessage,
                    hasDuplicateDrafts = hasDuplicateDrafts,
                    hasReviewRequiredDrafts = hasReviewRequiredDrafts,
                    onAddContent = { selectedPanel = TodayPanel.CAPTURED },
                    onOrganize = onAiOrganizeToday,
                    onOpenDrafts = { selectedPanel = TodayPanel.DRAFTS },
                    onSubmitDrafts = onSubmitAllDrafts,
                    onOpenIssues = { selectedPanel = TodayPanel.ISSUES }
                )
            }

            if (showQuickInput && historyPendingCount > 0) {
                item {
                    HistoricalPendingCard(
                        pendingCount = historyPendingCount,
                        onOpen = onOpenHistoricalPending
                    )
                }
            }

            selectedPanel?.let { panel ->
                item {
                    TodayPanelCard(
                        panel = panel,
                        notes = notes,
                        pendingDrafts = pendingDrafts,
                        knowledgeEntries = knowledgeEntries,
                        audioJobs = audioJobs,
                        showQuickInput = showQuickInput,
                        input = input,
                        inputSourceType = inputSourceType,
                        onInputChange = onInputChange,
                        onPasteClipboard = onPasteClipboard,
                        onSave = onSave,
                        onEditNote = onEdit,
                        onCopyNote = onCopy,
                        onDeleteNote = onDelete,
                        onRetryAudioJobs = onRetryAudioJobs,
                        onRetryIndex = onRetryIndex,
                        onSubmitAllDrafts = onSubmitAllDrafts,
                        onOpenKnowledge = onOpenKnowledge
                    )
                }
            }
        }
    }
}

private enum class TodayPanel {
    CAPTURED,
    DRAFTS,
    INGESTED,
    ISSUES
}

@Composable
private fun TodayStatusChips(
    totalCount: Int,
    pendingCount: Int,
    pendingDraftCount: Int,
    knowledgeCount: Int,
    issueCount: Int,
    selectedPanel: TodayPanel?,
    onSelectPanel: (TodayPanel) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TodayStatusChip(
                label = "收纳",
                count = totalCount,
                selected = selectedPanel == TodayPanel.CAPTURED,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.CAPTURED) }
            )
            TodayStatusChip(
                label = "待整理",
                count = pendingCount,
                selected = selectedPanel == TodayPanel.CAPTURED && pendingCount > 0,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.CAPTURED) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TodayStatusChip(
                label = "待确认",
                count = pendingDraftCount,
                selected = selectedPanel == TodayPanel.DRAFTS,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.DRAFTS) }
            )
            TodayStatusChip(
                label = "已入库",
                count = knowledgeCount,
                selected = selectedPanel == TodayPanel.INGESTED,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.INGESTED) }
            )
            TodayStatusChip(
                label = "异常",
                count = issueCount,
                selected = selectedPanel == TodayPanel.ISSUES,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.ISSUES) }
            )
        }
    }
}

@Composable
private fun TodayStatusChip(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Text(
            text = "$label $count",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TodayPrimaryActionCard(
    showQuickInput: Boolean,
    totalCount: Int,
    pendingCount: Int,
    pendingDraftCount: Int,
    reviewedCount: Int,
    knowledgeCount: Int,
    hasIssue: Boolean,
    isAiOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    progress: AiTaskProgress?,
    statusMessage: String?,
    hasDuplicateDrafts: Boolean,
    hasReviewRequiredDrafts: Boolean,
    onAddContent: () -> Unit,
    onOrganize: () -> Unit,
    onOpenDrafts: () -> Unit,
    onSubmitDrafts: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val label = when {
        isAiOrganizing -> "AI 整理中"
        isKnowledgeTaskRunning -> "知识处理中"
        hasIssue -> "处理异常"
        pendingDraftCount > 0 && (hasDuplicateDrafts || hasReviewRequiredDrafts) -> "查看待确认"
        pendingDraftCount > 0 -> "确认入库"
        pendingCount > 0 -> "AI 整理今日"
        totalCount == 0 && showQuickInput -> "添加内容"
        else -> "今日已完成"
    }
    val enabled = when {
        isAiOrganizing || isKnowledgeTaskRunning -> false
        totalCount == 0 && !showQuickInput -> false
        label == "今日已完成" -> false
        else -> true
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "今日进度",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "收纳 $totalCount · 待整理 $pendingCount · 待确认 $pendingDraftCount · 已入库 $knowledgeCount · 已处理 $reviewedCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            progress?.let { taskProgress ->
                RequestProgressBlock(progress = taskProgress)
            }
            if (progress == null && !statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (isAiOrganizing || isKnowledgeTaskRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Button(
                onClick = {
                    when {
                        hasIssue -> onOpenIssues()
                        pendingDraftCount > 0 && (hasDuplicateDrafts || hasReviewRequiredDrafts) -> onOpenDrafts()
                        pendingDraftCount > 0 -> onSubmitDrafts()
                        pendingCount > 0 -> onOrganize()
                        totalCount == 0 && showQuickInput -> onAddContent()
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = label)
            }
        }
    }
}

@Composable
private fun TodayPanelCard(
    panel: TodayPanel,
    notes: List<Note>,
    pendingDrafts: List<KnowledgeEntryDraft>,
    knowledgeEntries: List<KnowledgeEntry>,
    audioJobs: List<AudioTranscriptionJob>,
    showQuickInput: Boolean,
    input: String,
    inputSourceType: SourceType,
    onInputChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit,
    onEditNote: (Note) -> Unit,
    onCopyNote: (Note) -> Unit,
    onDeleteNote: (Note) -> Unit,
    onRetryAudioJobs: () -> Unit,
    onRetryIndex: () -> Unit,
    onSubmitAllDrafts: () -> Unit,
    onOpenKnowledge: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = panel.title(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            when (panel) {
                TodayPanel.CAPTURED -> {
                    if (showQuickInput) {
                        QuickInputCard(
                            input = input,
                            sourceType = inputSourceType,
                            onInputChange = onInputChange,
                            onPasteClipboard = onPasteClipboard,
                            onSave = onSave
                        )
                    }
                    if (notes.isEmpty()) {
                        EmptyInboxCard(showQuickInput = showQuickInput)
                    } else {
                        notes.take(5).forEach { note ->
                            NoteCard(
                                note = note,
                                onEdit = { onEditNote(note) },
                                onCopy = { onCopyNote(note) },
                                onDelete = { onDeleteNote(note) }
                            )
                        }
                        if (notes.size > 5) {
                            Text(
                                text = "还有 ${notes.size - 5} 条记录未展开。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                TodayPanel.DRAFTS -> {
                    if (pendingDrafts.isEmpty()) {
                        Text(
                            text = "当前没有待确认草稿。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        pendingDrafts.take(5).forEach { draft ->
                            KnowledgeDraftSummaryCard(draft = draft)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenKnowledge,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "编辑草稿")
                            }
                            Button(
                                onClick = onSubmitAllDrafts,
                                enabled = pendingDrafts.none { it.needsReview },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "确认入库")
                            }
                        }
                    }
                }
                TodayPanel.INGESTED -> {
                    if (knowledgeEntries.isEmpty()) {
                        Text(
                            text = "今天还没有入库知识。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        knowledgeEntries.take(5).forEach { entry ->
                            KnowledgeEntrySummaryCard(entry = entry)
                        }
                    }
                }
                TodayPanel.ISSUES -> {
                    val failedJobs = audioJobs.filter { it.canRetry || it.status == AudioTranscriptionJobStatus.FAILED }
                    if (failedJobs.isEmpty()) {
                        Text(
                            text = "当前没有待处理异常。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        failedJobs.take(5).forEach { job ->
                            Text(
                                text = job.audioJobLine(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRetryIndex,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "重试索引")
                        }
                        Button(
                            onClick = onRetryAudioJobs,
                            enabled = failedJobs.any { it.canRetry },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "重试音频")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeDraftSummaryCard(draft: KnowledgeEntryDraft) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = draft.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = draft.summary.ifBlank { draft.content },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "来源 ${draft.sourceNoteIds.size} 条 · ${draft.intentType.label()} · ${draft.reviewLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KnowledgeEntrySummaryCard(entry: KnowledgeEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = entry.userTitle?.takeIf { it.isNotBlank() } ?: "未命名知识",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.summary.ifBlank { entry.content },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.intentType.label()} · ${entry.indexStatus.label()} · 来源 ${entry.sourceNoteIds.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun TodayPanel.title(): String {
    return when (this) {
        TodayPanel.CAPTURED -> "今日收纳内容"
        TodayPanel.DRAFTS -> "待确认草稿"
        TodayPanel.INGESTED -> "今日入库结果"
        TodayPanel.ISSUES -> "待处理异常"
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

@Composable
private fun AudioTranscriptionJobsCard(
    jobs: List<AudioTranscriptionJob>,
    onRetry: () -> Unit
) {
    val retryableCount = jobs.count { it.canRetry }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "待转写音频",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$retryableCount 条可重试 · 失败音频已暂存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            jobs.take(3).forEach { job ->
                Text(
                    text = job.audioJobLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onRetry,
                enabled = retryableCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (retryableCount > 0) "重试待转写" else "暂无可重试")
            }
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
    val isAudioNote = note.isAudioNote()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isAudioNote) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isAudioNote) {
                Text(
                    text = note.audioMetaText(includeStatus = true),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = note.title ?: "未命名记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (isAudioNote) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (isAudioNote) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!isAudioNote) {
                Text(
                    text = "${note.sourceType.label()} · ${note.status.label()} · ${note.createdAt.formatTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            audioJobs = emptyList(),
            farmSnapshot = DailyFarmRuleEngine.buildSnapshot(
                dateId = now.toLocalDateId(),
                notes = emptyList(),
                knowledgeStatus = DayKnowledgeStatus(now.toLocalDateId(), 1, 1, 0, 0, 0, 0, 0, 0),
                audioJobs = emptyList()
            ),
            dayKnowledgeStatus = DayKnowledgeStatus(now.toLocalDateId(), 1, 1, 0, 0, 0, 0, 0, 0),
            pendingDrafts = emptyList(),
            knowledgeEntries = emptyList(),
            input = "",
            inputSourceType = SourceType.MANUAL,
            showQuickInput = true,
            historyPendingCount = 0,
            snackbarHostState = SnackbarHostState(),
            pendingReviewCount = 1,
            pendingDraftCount = 0,
            reviewedCount = 0,
            hasDuplicateDrafts = false,
            hasReviewRequiredDrafts = false,
            isAiOrganizing = false,
            isKnowledgeTaskRunning = false,
            aiOrganizeProgress = null,
            aiOrganizeMessage = null,
            onInputChange = {},
            onPasteClipboard = {},
            onSave = {},
            onEdit = {},
            onCopy = {},
            onDelete = {},
            onRetryAudioJobs = {},
            onAiOrganizeToday = {},
            onSubmitAllDrafts = {},
            onRetryIndex = {},
            onOpenHistoricalPending = {},
            onOpenKnowledge = {},
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
        SourceType.AUDIO_TRANSCRIPTION -> "音频转写"
    }
}

private fun Note.isAudioNote(): Boolean {
    return sourceType == SourceType.AUDIO_TRANSCRIPTION
}

private fun Note.audioMetaText(includeStatus: Boolean): String {
    val parts = mutableListOf(audioSourceLabel())
    audioDurationMs?.takeIf { it > 0L }?.let { duration ->
        parts += "时长 ${duration.formatDuration()}"
    }
    if (includeStatus) {
        parts += status.label()
    }
    parts += createdAt.formatTime()
    return parts.joinToString(" · ")
}

private fun Note.audioSourceLabel(): String {
    val source = sourceApp.orEmpty()
    return when {
        source.contains("系统") -> "系统音频转写"
        source.contains("麦克风") || source.contains("录音") -> "麦克风转写"
        else -> "音频转写"
    }
}

private fun AudioTranscriptionJob.shouldShowInInbox(): Boolean {
    return status == AudioTranscriptionJobStatus.PENDING ||
        status == AudioTranscriptionJobStatus.UPLOADING ||
        status == AudioTranscriptionJobStatus.TRANSCRIBING ||
        status == AudioTranscriptionJobStatus.FAILED
}

private fun AudioTranscriptionJob.audioJobLine(): String {
    val source = when {
        sourceApp.contains("系统") -> "系统音频"
        sourceApp.contains("麦克风") || sourceApp.contains("录音") -> "麦克风"
        else -> "音频"
    }
    val error = errorMessage?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "$source · ${durationMs.formatDuration()} · ${status.label()} · 重试 $retryCount 次$error"
}

private fun AudioTranscriptionJobStatus.label(): String {
    return when (this) {
        AudioTranscriptionJobStatus.PENDING -> "待转写"
        AudioTranscriptionJobStatus.UPLOADING -> "上传中"
        AudioTranscriptionJobStatus.TRANSCRIBING -> "转写中"
        AudioTranscriptionJobStatus.FAILED -> "失败"
        AudioTranscriptionJobStatus.SAVED -> "已生成文本"
        AudioTranscriptionJobStatus.AUDIO_CLEANED -> "原音频已清理"
    }
}

private fun Long.formatDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}分${seconds.toString().padStart(2, '0')}秒"
    } else {
        "${seconds}秒"
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

private fun KnowledgeEntryDraft.reviewLabel(): String {
    return if (needsReview) "需确认" else "可入库"
}

private fun KnowledgeIndexStatus.label(): String {
    return when (this) {
        KnowledgeIndexStatus.PENDING -> "待索引"
        KnowledgeIndexStatus.INDEXING -> "索引中"
        KnowledgeIndexStatus.INDEXED -> "已索引"
        KnowledgeIndexStatus.FAILED -> "索引失败"
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
