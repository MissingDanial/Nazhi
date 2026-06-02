package com.nazhi.app.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
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
import com.nazhi.app.core.util.displayDateLabel
import com.nazhi.app.core.util.toLocalDateId
import com.nazhi.app.core.util.todayDateId
import com.nazhi.app.feature.farm.DailyFarmPreview
import com.nazhi.app.feature.farm.FarmContentItem
import com.nazhi.app.feature.farm.FarmOwnerType
import com.nazhi.app.feature.farm.FarmPlotUiModel
import com.nazhi.app.feature.farm.FarmStage
import com.nazhi.app.feature.farm.buildFarmPlotModels
import com.nazhi.app.core.ui.EditableKnowledgeEntryDialog
import com.nazhi.app.core.ui.FarmNoticeCard
import com.nazhi.app.core.ui.KnowledgeEntryDetailDialog
import com.nazhi.app.core.ui.NazhiStatusChip
import com.nazhi.app.core.ui.NazhiStatusKind
import com.nazhi.app.core.ui.NazhiTheme
import com.nazhi.app.core.ui.NazhiTokens
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
    val settledKnowledgeEntries = knowledgeEntries.filter { it.indexStatus == KnowledgeIndexStatus.INDEXED }
    val hasDuplicateDrafts = pendingDrafts.any { draft ->
        draft.findDuplicateEntry(knowledgeEntries) != null
    }
    val hasReviewRequiredDrafts = pendingDrafts.any { it.needsReview }
    val farmSnapshot = remember(dateId, notes, drafts, knowledgeEntries, dayKnowledgeStatus, visibleAudioJobs) {
        DailyFarmRuleEngine.buildSnapshot(
            dateId = dateId,
            notes = notes,
            drafts = drafts,
            knowledgeEntries = knowledgeEntries,
            knowledgeStatus = dayKnowledgeStatus,
            audioJobs = visibleAudioJobs
        )
    }
    var input by remember(initialShareText) { mutableStateOf(initialShareText.orEmpty()) }
    var inputSourceType by remember(initialShareText) {
        mutableStateOf(if (initialShareText.isNullOrBlank()) SourceType.MANUAL else SourceType.SHARE)
    }
    var inputSourceApp by remember(initialShareText, initialShareSource) {
        mutableStateOf(initialShareSource)
    }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var editingDraft by remember { mutableStateOf<KnowledgeEntryDraft?>(null) }
    var editingKnowledgeEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var deletingNote by remember { mutableStateOf<Note?>(null) }
    var isUpdatingKnowledgeEntry by remember { mutableStateOf(false) }
    var handledKnowledgeTaskEventId by remember { mutableStateOf(knowledgeIngestionState.eventId) }
    val isToday = dateId == todayDateId()
    val activeTaskDateId = knowledgeIngestionState.activeDateId
    val isCurrentDateTask = activeTaskDateId == null || activeTaskDateId == dateId
    val activeOtherDateLabel = activeTaskDateId
        ?.takeIf { knowledgeIngestionState.isRunning && it != dateId }
        ?.let { displayDateLabel(it) }
    val isAiOrganizing = knowledgeIngestionState.isRunning &&
        knowledgeIngestionState.taskKind == KnowledgeTaskKind.ORGANIZE &&
        isCurrentDateTask
    val isKnowledgeTaskRunning = knowledgeIngestionState.isRunning
    val aiOrganizeProgress = if (isAiOrganizing) knowledgeIngestionState.progress else null
    val aiOrganizeMessage = when {
        activeOtherDateLabel != null -> "正在处理 $activeOtherDateLabel，完成后可处理当前日期。"
        knowledgeIngestionState.isRunning &&
            isCurrentDateTask &&
            knowledgeIngestionState.progress == null -> knowledgeIngestionState.message
        else -> null
    }
    LaunchedEffect(knowledgeIngestionState.eventId) {
        val message = knowledgeIngestionState.message
        if (knowledgeIngestionState.eventId != handledKnowledgeTaskEventId && !message.isNullOrBlank()) {
            handledKnowledgeTaskEventId = knowledgeIngestionState.eventId
            snackbarHostState.showSnackbar(message)
        }
    }

    fun saveNoteContent(
        rawContent: String?,
        sourceType: SourceType,
        sourceApp: String?,
        emptyMessage: String,
        successMessage: String,
        afterSaved: () -> Unit = {}
    ) {
        val content = rawContent?.trim().orEmpty()
        if (content.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(emptyMessage)
            }
            return
        }

        val duplicateKey = content.toKnowledgeDuplicateKey()
        val hasDuplicateToday = duplicateKey.isMeaningfulKnowledgeDuplicateKey() &&
            notes.any { note -> note.content.toKnowledgeDuplicateKey() == duplicateKey }
        if (hasDuplicateToday) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("今日已存在相同内容，未重复保存")
            }
            return
        }

        val now = System.currentTimeMillis()
        val note = Note(
            id = UUID.randomUUID().toString(),
            content = content,
            title = content.toTitle(),
            sourceType = sourceType,
            sourceApp = sourceApp,
            sourceUrl = content.extractFirstUrl(),
            createdAt = now,
            createdDate = now.toLocalDateId(),
            updatedAt = now,
            status = NoteStatus.INBOX,
            userRemark = null
        )

        coroutineScope.launch {
            repository.saveNote(note)
            afterSaved()
            snackbarHostState.showSnackbar(successMessage)
        }
    }

    InboxScreen(
        screenTitle = screenTitle,
        screenSubtitle = screenSubtitle,
        isToday = isToday,
        notes = notes,
        audioJobs = visibleAudioJobs,
        farmSnapshot = farmSnapshot,
        dayKnowledgeStatus = dayKnowledgeStatus,
        pendingDrafts = pendingDrafts,
        knowledgeEntries = settledKnowledgeEntries,
        input = input,
        inputSourceType = inputSourceType,
        showQuickInput = showQuickInput,
        historyPendingCount = historyPendingCount,
        pendingReviewCount = pendingReviewNotes.size,
        pendingDraftCount = dayKnowledgeStatus.pendingDraftCount,
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
        onPasteSave = {
            val clipboardText = context.readClipboardText()
            saveNoteContent(
                rawContent = clipboardText,
                sourceType = SourceType.CLIPBOARD,
                sourceApp = "系统剪贴板",
                emptyMessage = "剪贴板没有可收纳文本",
                successMessage = "已收纳到今日"
            )
        },
        onSave = {
            saveNoteContent(
                rawContent = input,
                sourceType = inputSourceType,
                sourceApp = inputSourceApp,
                emptyMessage = "请输入要保存的内容",
                successMessage = "已保存到今日收件箱"
            ) {
                input = ""
                inputSourceType = SourceType.MANUAL
                inputSourceApp = null
                onShareConsumed()
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
        onSubmitDraft = { draft ->
            knowledgeIngestionCoordinator?.submitDraft(draft.id)
        },
        onEditDraft = { draft ->
            editingDraft = draft
        },
        onEditKnowledgeEntry = { entry ->
            editingKnowledgeEntry = entry
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

    editingDraft?.let { draft ->
        DraftEditDialog(
            draft = draft,
            onDismiss = { editingDraft = null },
            onConfirm = { updatedDraft ->
                coroutineScope.launch {
                    repository.updateKnowledgeDraft(
                        updatedDraft.copy(
                            needsReview = false,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    editingDraft = null
                    snackbarHostState.showSnackbar("已保存草稿，待确认")
                }
            }
        )
    }

    editingKnowledgeEntry?.let { entry ->
        EditableKnowledgeEntryDialog(
            entry = entry,
            isSaving = isUpdatingKnowledgeEntry,
            onDismiss = {
                if (!isUpdatingKnowledgeEntry) {
                    editingKnowledgeEntry = null
                }
            },
            onConfirm = { updatedEntry ->
                coroutineScope.launch {
                    isUpdatingKnowledgeEntry = true
                    val result = runCatching {
                        repository.updateKnowledgeEntry(updatedEntry, reindex = true)
                    }
                    isUpdatingKnowledgeEntry = false
                    result.fold(
                        onSuccess = { indexed ->
                            editingKnowledgeEntry = null
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
    isToday: Boolean,
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
    hasDuplicateDrafts: Boolean,
    hasReviewRequiredDrafts: Boolean,
    isAiOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    aiOrganizeProgress: AiTaskProgress?,
    aiOrganizeMessage: String?,
    snackbarHostState: SnackbarHostState,
    onInputChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onPasteSave: () -> Unit,
    onSave: () -> Unit,
    onEdit: (Note) -> Unit,
    onCopy: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onRetryAudioJobs: () -> Unit,
    onAiOrganizeToday: () -> Unit,
    onSubmitDraft: (KnowledgeEntryDraft) -> Unit,
    onEditDraft: (KnowledgeEntryDraft) -> Unit,
    onEditKnowledgeEntry: (KnowledgeEntry) -> Unit,
    onSubmitAllDrafts: () -> Unit,
    onRetryIndex: () -> Unit,
    onOpenHistoricalPending: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onNavigateBack: (() -> Unit)?
) {
    var selectedPanel by remember { mutableStateOf<TodayPanel?>(null) }
    var panelScrollRequest by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val retryableAudioCount = audioJobs.count { it.canRetry }
    val hasIssue = retryableAudioCount > 0 || dayKnowledgeStatus.failedIndexCount > 0
    val hasHistoryPendingCard = showQuickInput && historyPendingCount > 0
    val pendingReviewNotesForFarm = remember(notes) {
        notes.filter { it.status == NoteStatus.INBOX }
    }
    val farmPlots = remember(farmSnapshot.dateId, pendingReviewNotesForFarm, pendingDrafts, knowledgeEntries) {
        buildFarmPlotModels(
            dateId = farmSnapshot.dateId,
            notes = pendingReviewNotesForFarm,
            drafts = pendingDrafts,
            knowledgeEntries = knowledgeEntries
        )
    }
    val context = LocalContext.current
    var selectedFarmPlot by remember(farmSnapshot.dateId) { mutableStateOf<FarmPlotUiModel?>(null) }
    var selectedFarmNote by remember(farmSnapshot.dateId) { mutableStateOf<Note?>(null) }
    var selectedFarmDraft by remember(farmSnapshot.dateId) { mutableStateOf<KnowledgeEntryDraft?>(null) }
    var selectedFarmDraftSourceNotes by remember(farmSnapshot.dateId) { mutableStateOf<List<Note>>(emptyList()) }
    var selectedFarmEntry by remember(farmSnapshot.dateId) { mutableStateOf<KnowledgeEntry?>(null) }
    var selectedFarmEntrySourceNotes by remember(farmSnapshot.dateId) { mutableStateOf<List<Note>>(emptyList()) }

    fun openPanel(panel: TodayPanel) {
        val nextPanel = if (selectedPanel == panel) null else panel
        selectedPanel = nextPanel
        if (nextPanel != null) {
            panelScrollRequest += 1
        }
    }

    LaunchedEffect(panelScrollRequest, selectedPanel, hasHistoryPendingCard) {
        if (panelScrollRequest > 0 && selectedPanel != null) {
            val panelItemIndex = 3 + if (hasHistoryPendingCard) 1 else 0
            listState.animateScrollToItem(panelItemIndex)
        }
    }

    LaunchedEffect(input, showQuickInput) {
        if (showQuickInput && input.isNotBlank() && selectedPanel == null) {
            selectedPanel = TodayPanel.CAPTURED
        }
    }

    Scaffold(
        containerColor = NazhiTokens.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NazhiTokens.colors.background,
                    titleContentColor = NazhiTokens.colors.textPrimary,
                    navigationIconContentColor = NazhiTokens.colors.grassDark
                ),
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
                            text = "$screenSubtitle · ${displayDateLabel(farmSnapshot.dateId)}",
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
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TodayStatusChips(
                    pendingCount = pendingReviewCount,
                    pendingDraftCount = pendingDraftCount,
                    knowledgeCount = dayKnowledgeStatus.indexedEntryCount,
                    issueCount = retryableAudioCount + dayKnowledgeStatus.failedIndexCount,
                    selectedPanel = selectedPanel,
                    onSelectPanel = { panel ->
                        openPanel(panel)
                    }
                )
            }

            item {
                DailyFarmPreview(
                    snapshot = farmSnapshot,
                    plots = farmPlots,
                    selectedPlotId = selectedFarmPlot?.plotId,
                    onPlotClick = { plot ->
                        selectedFarmPlot = plot
                    }
                )
            }

            item {
                TodayPrimaryActionCard(
                    isToday = isToday,
                    showQuickInput = showQuickInput,
                    totalCount = notes.size,
                    pendingCount = pendingReviewCount,
                    pendingDraftCount = pendingDraftCount,
                    knowledgeCount = dayKnowledgeStatus.indexedEntryCount,
                    hasIssue = hasIssue,
                    isAiOrganizing = isAiOrganizing,
                    isKnowledgeTaskRunning = isKnowledgeTaskRunning,
                    progress = aiOrganizeProgress,
                    statusMessage = aiOrganizeMessage,
                    hasDuplicateDrafts = hasDuplicateDrafts,
                    hasReviewRequiredDrafts = hasReviewRequiredDrafts,
                    onAddContent = { openPanel(TodayPanel.CAPTURED) },
                    onPasteSave = onPasteSave,
                    onOrganize = onAiOrganizeToday,
                    onOpenDrafts = { openPanel(TodayPanel.DRAFTS) },
                    onSubmitDrafts = onSubmitAllDrafts,
                    onOpenIssues = { openPanel(TodayPanel.ISSUES) }
                )
            }

            if (hasHistoryPendingCard) {
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
                        isToday = isToday,
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
                        onOpenNoteDetail = { note -> selectedFarmNote = note },
                        onEditNote = onEdit,
                        onCopyNote = onCopy,
                        onDeleteNote = onDelete,
                        onRetryAudioJobs = onRetryAudioJobs,
                        onRetryIndex = onRetryIndex,
                        isKnowledgeTaskRunning = isKnowledgeTaskRunning,
                        onEditDraft = onEditDraft,
                        onSubmitDraft = onSubmitDraft,
                        onSubmitAllDrafts = onSubmitAllDrafts,
                        onOpenKnowledge = onOpenKnowledge,
                        onOpenKnowledgeEntry = { entry ->
                            selectedFarmEntry = entry
                            selectedFarmEntrySourceNotes = notes.filter { note -> note.id in entry.sourceNoteIds }
                        }
                    )
                }
            }
        }
    }

    selectedFarmPlot?.let { plot ->
        FarmPlotContentDialog(
            plot = plot,
            onOpenPanel = { item ->
                selectedFarmPlot = null
                when (item.ownerType) {
                    FarmOwnerType.NOTE -> {
                        val note = notes.firstOrNull { it.id == item.ownerId }
                        if (note != null) {
                            selectedFarmNote = note
                        } else {
                            openPanel(TodayPanel.CAPTURED)
                        }
                    }
                    FarmOwnerType.DRAFT -> {
                        val draft = pendingDrafts.firstOrNull { it.id == item.ownerId }
                        if (draft != null) {
                            selectedFarmDraft = draft
                            selectedFarmDraftSourceNotes = notes.filter { note -> note.id in draft.sourceNoteIds }
                        } else {
                            openPanel(TodayPanel.DRAFTS)
                        }
                    }
                    FarmOwnerType.KNOWLEDGE_ENTRY -> {
                        val entry = knowledgeEntries.firstOrNull { it.id == item.ownerId }
                        if (entry != null) {
                            selectedFarmEntry = entry
                            selectedFarmEntrySourceNotes = notes.filter { note -> note.id in entry.sourceNoteIds }
                        } else {
                            openPanel(TodayPanel.INGESTED)
                        }
                    }
                }
            },
            onDismiss = {
                selectedFarmPlot = null
            }
        )
    }

    selectedFarmNote?.let { note ->
        FarmNoteDetailDialog(
            note = note,
            onCopy = {
                selectedFarmNote = null
                onCopy(note)
            },
            onEdit = {
                selectedFarmNote = null
                onEdit(note)
            },
            onDelete = {
                selectedFarmNote = null
                onDelete(note)
            },
            onOpenPanel = {
                selectedFarmNote = null
                openPanel(TodayPanel.CAPTURED)
            },
            onDismiss = {
                selectedFarmNote = null
            }
        )
    }

    selectedFarmDraft?.let { draft ->
        FarmDraftDetailDialog(
            draft = draft,
            sourceNotes = selectedFarmDraftSourceNotes,
            isSubmitting = isKnowledgeTaskRunning,
            onSubmit = {
                selectedFarmDraft = null
                selectedFarmDraftSourceNotes = emptyList()
                onSubmitDraft(draft)
            },
            onOpenKnowledge = {
                selectedFarmDraft = null
                selectedFarmDraftSourceNotes = emptyList()
                onEditDraft(draft)
            },
            onDismiss = {
                selectedFarmDraft = null
                selectedFarmDraftSourceNotes = emptyList()
            }
        )
    }

    selectedFarmEntry?.let { entry ->
        KnowledgeEntryDetailDialog(
            entry = entry,
            sourceNotes = selectedFarmEntrySourceNotes,
            dialogTitle = "农场知识条目",
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
                selectedFarmEntry = null
                selectedFarmEntrySourceNotes = emptyList()
                onEditKnowledgeEntry(entry)
            },
            onEditNote = { note ->
                selectedFarmEntry = null
                selectedFarmEntrySourceNotes = emptyList()
                onEdit(note)
            },
            onDismiss = {
                selectedFarmEntry = null
                selectedFarmEntrySourceNotes = emptyList()
            }
        )
    }
}

private enum class TodayPanel {
    CAPTURED,
    DRAFTS,
    INGESTED,
    ISSUES
}

@Composable
private fun FarmPlotContentDialog(
    plot: FarmPlotUiModel,
    onOpenPanel: (FarmContentItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = plot.farmDialogTitle()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = plot.contentSummaryText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = plot.items,
                        key = { item -> "${item.ownerType}:${item.ownerId}" }
                    ) { item ->
                        FarmPlotContentRow(
                            item = item,
                            onOpenPanel = { onOpenPanel(item) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Composable
private fun FarmPlotContentRow(
    item: FarmContentItem,
    onOpenPanel: () -> Unit
) {
    OutlinedButton(
        onClick = onOpenPanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.ownerType.dialogLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (item.ownerType) {
                    FarmOwnerType.NOTE -> MaterialTheme.colorScheme.tertiary
                    FarmOwnerType.DRAFT -> MaterialTheme.colorScheme.primary
                    FarmOwnerType.KNOWLEDGE_ENTRY -> MaterialTheme.colorScheme.secondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.preview.ifBlank { "暂无摘要" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.charCount} 字 · ${item.ownerType.panelActionLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FarmNoteDetailDialog(
    note: Note,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenPanel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "收纳内容") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = note.title ?: "未命名记录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${note.sourceType.label()} · ${note.status.label()} · ${note.createdAt.formatTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                note.userRemark?.takeIf { it.isNotBlank() }?.let { remark ->
                    Text(
                        text = "备注：$remark",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                OutlinedButton(
                    onClick = onOpenPanel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "打开收纳栏目")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Composable
private fun FarmDraftDetailDialog(
    draft: KnowledgeEntryDraft,
    sourceNotes: List<Note>,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "待确认内容") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = draft.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "来源 ${draft.sourceNoteIds.size} 条 · ${draft.reviewLabel()} · 置信度 ${"%.2f".format(draft.confidence)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (draft.needsReview) {
                    Text(
                        text = "这条草稿需要人工确认，建议到知识库编辑后再沉淀。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (draft.summary.isNotBlank()) {
                    Text(
                        text = draft.summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = draft.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (draft.tags.isNotEmpty()) {
                    Text(
                        text = draft.tags.joinToString(prefix = "标签："),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                draft.insight?.takeIf { it.isNotBlank() }?.let { insight ->
                    Text(
                        text = "AI 推断：$insight",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (draft.evidenceQuotes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "依据摘录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    draft.evidenceQuotes.take(3).forEachIndexed { index, quote ->
                        Text(
                            text = "${index + 1}. $quote",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (sourceNotes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "来源 Note",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    sourceNotes.take(3).forEach { note ->
                        FarmSourceNoteSnippet(note = note)
                    }
                }
            }
        },
        confirmButton = {
            if (draft.needsReview) {
                Button(onClick = onOpenKnowledge) {
                    Text(text = "编辑草稿")
                }
            } else {
                Button(
                    onClick = onSubmit,
                    enabled = !isSubmitting
                ) {
                    Text(text = if (isSubmitting) "沉淀中" else "确认沉淀")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Composable
private fun DraftEditDialog(
    draft: KnowledgeEntryDraft,
    onDismiss: () -> Unit,
    onConfirm: (KnowledgeEntryDraft) -> Unit
) {
    var title by remember(draft.id) { mutableStateOf(draft.title) }
    var summary by remember(draft.id) { mutableStateOf(draft.summary) }
    var content by remember(draft.id) { mutableStateOf(draft.content) }
    var tagsText by remember(draft.id) { mutableStateOf(draft.tags.joinToString("，")) }
    var insight by remember(draft.id) { mutableStateOf(draft.insight.orEmpty()) }
    val intentType = draft.intentType
    val canSave = title.isNotBlank() && content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑 AI 草稿") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "标题") }
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text(text = "摘要") }
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text(text = "正文") }
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "标签，用逗号分隔") }
                )
                OutlinedTextField(
                    value = insight,
                    onValueChange = { insight = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text(text = "AI 推断，可选") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        draft.copy(
                            title = title.trim(),
                            summary = summary.trim(),
                            content = content.trim(),
                            intentType = intentType,
                            tags = tagsText.toTagList(),
                            insight = insight.trim().takeIf { it.isNotBlank() }
                        )
                    )
                },
                enabled = canSave
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
private fun FarmSourceNoteSnippet(note: Note) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = note.title ?: "未命名记录",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = note.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TodayStatusChips(
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
            NazhiStatusChip(
                label = "待整理",
                count = pendingCount,
                kind = NazhiStatusKind.PENDING,
                selected = selectedPanel == TodayPanel.CAPTURED && pendingCount > 0,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.CAPTURED) }
            )
            NazhiStatusChip(
                label = "待确认",
                count = pendingDraftCount,
                kind = NazhiStatusKind.DRAFT,
                selected = selectedPanel == TodayPanel.DRAFTS,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.DRAFTS) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NazhiStatusChip(
                label = "已沉淀",
                count = knowledgeCount,
                kind = NazhiStatusKind.SETTLED,
                selected = selectedPanel == TodayPanel.INGESTED,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.INGESTED) }
            )
            NazhiStatusChip(
                label = "已失败",
                count = issueCount,
                kind = NazhiStatusKind.ISSUE,
                selected = selectedPanel == TodayPanel.ISSUES,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPanel(TodayPanel.ISSUES) }
            )
        }
    }
}

@Composable
private fun TodayPrimaryActionCard(
    isToday: Boolean,
    showQuickInput: Boolean,
    totalCount: Int,
    pendingCount: Int,
    pendingDraftCount: Int,
    knowledgeCount: Int,
    hasIssue: Boolean,
    isAiOrganizing: Boolean,
    isKnowledgeTaskRunning: Boolean,
    progress: AiTaskProgress?,
    statusMessage: String?,
    hasDuplicateDrafts: Boolean,
    hasReviewRequiredDrafts: Boolean,
    onAddContent: () -> Unit,
    onPasteSave: () -> Unit,
    onOrganize: () -> Unit,
    onOpenDrafts: () -> Unit,
    onSubmitDrafts: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val periodLabel = if (isToday) "今日" else "这一天"
    val completeLabel = "$periodLabel 已完成"
    val emptyHistoryLabel = "这一天无内容"
    val label = when {
        isAiOrganizing -> "AI 整理中"
        isKnowledgeTaskRunning -> "知识处理中"
        hasIssue -> "处理异常"
        pendingDraftCount > 0 && (hasDuplicateDrafts || hasReviewRequiredDrafts) -> "查看待确认"
        pendingDraftCount > 0 -> "确认沉淀"
        pendingCount > 0 -> if (isToday) "AI 整理今日" else "整理这一天"
        totalCount == 0 && showQuickInput -> "添加内容"
        totalCount == 0 -> emptyHistoryLabel
        else -> completeLabel
    }
    val enabled = when {
        isAiOrganizing || isKnowledgeTaskRunning -> false
        totalCount == 0 && !showQuickInput -> false
        label == completeLabel || label == emptyHistoryLabel -> false
        else -> true
    }
    val statusKind = when {
        hasIssue -> NazhiStatusKind.ISSUE
        pendingDraftCount > 0 -> NazhiStatusKind.DRAFT
        pendingCount > 0 -> NazhiStatusKind.PENDING
        totalCount == 0 -> NazhiStatusKind.CAPTURED
        else -> NazhiStatusKind.SETTLED
    }
    val progressContent: (@Composable () -> Unit)? = when {
        progress != null -> {
            { RequestProgressBlock(progress = progress, contentColor = NazhiTokens.colors.textSecondary) }
        }
        !statusMessage.isNullOrBlank() && (isAiOrganizing || isKnowledgeTaskRunning) -> {
            { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        else -> null
    }
    FarmNoticeCard(
        title = "${periodLabel}告示牌",
        message = statusMessage
            ?: "收纳：$totalCount · 已沉淀：$knowledgeCount",
        statusKind = statusKind,
        primaryActionLabel = label,
        primaryActionEnabled = enabled,
        onPrimaryAction = {
            when {
                hasIssue -> onOpenIssues()
                pendingDraftCount > 0 && (hasDuplicateDrafts || hasReviewRequiredDrafts) -> onOpenDrafts()
                pendingDraftCount > 0 -> onSubmitDrafts()
                pendingCount > 0 -> onOrganize()
                totalCount == 0 && showQuickInput -> onAddContent()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        secondaryActionLabel = if (showQuickInput) "粘贴收纳" else null,
        onSecondaryAction = if (showQuickInput) onPasteSave else null,
        progressContent = progressContent
    )
}

@Composable
private fun TodayPanelCard(
    panel: TodayPanel,
    isToday: Boolean,
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
    onOpenNoteDetail: (Note) -> Unit,
    onEditNote: (Note) -> Unit,
    onCopyNote: (Note) -> Unit,
    onDeleteNote: (Note) -> Unit,
    onRetryAudioJobs: () -> Unit,
    onRetryIndex: () -> Unit,
    isKnowledgeTaskRunning: Boolean,
    onEditDraft: (KnowledgeEntryDraft) -> Unit,
    onSubmitDraft: (KnowledgeEntryDraft) -> Unit,
    onSubmitAllDrafts: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenKnowledgeEntry: (KnowledgeEntry) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = panel.title(isToday),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            when (panel) {
                TodayPanel.CAPTURED -> {
                    if (notes.isEmpty()) {
                        EmptyInboxCard(showQuickInput = showQuickInput)
                    } else {
                        notes.forEach { note ->
                            NoteSummaryListItem(
                                note = note,
                                onOpen = { onOpenNoteDetail(note) }
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
                        pendingDrafts.forEach { draft ->
                            KnowledgeDraftSummaryCard(
                                draft = draft,
                                isSubmitting = isKnowledgeTaskRunning,
                                onEdit = { onEditDraft(draft) },
                                onSubmit = { onSubmitDraft(draft) }
                            )
                        }
                        if (pendingDrafts.size > 1 && pendingDrafts.none { it.needsReview }) {
                            Button(
                                onClick = onSubmitAllDrafts,
                                enabled = !isKnowledgeTaskRunning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "全部确认沉淀")
                            }
                        }
                    }
                }
                TodayPanel.INGESTED -> {
                    if (knowledgeEntries.isEmpty()) {
                        Text(
                            text = if (isToday) "今天还没有沉淀知识。" else "这一天还没有沉淀知识。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        knowledgeEntries.take(5).forEach { entry ->
                            KnowledgeEntrySummaryCard(
                                entry = entry,
                                onOpen = { onOpenKnowledgeEntry(entry) }
                            )
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
                            Text(text = "重试沉淀")
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
private fun KnowledgeDraftSummaryCard(
    draft: KnowledgeEntryDraft,
    isSubmitting: Boolean,
    onEdit: () -> Unit,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, NazhiTokens.colors.wheat.copy(alpha = 0.58f)),
        colors = CardDefaults.cardColors(
            containerColor = NazhiTokens.colors.wheatSoft.copy(alpha = 0.68f)
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
                color = NazhiTokens.colors.soil,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = draft.summary.ifBlank { draft.content },
                style = MaterialTheme.typography.bodySmall,
                color = NazhiTokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "来源 ${draft.sourceNoteIds.size} 条 · ${draft.reviewLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = NazhiTokens.colors.soil.copy(alpha = 0.82f)
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = NazhiTokens.colors.soil)
                ) {
                    Text(text = "编辑")
                }
                TextButton(
                    onClick = onSubmit,
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = NazhiTokens.colors.grassDark,
                        disabledContentColor = NazhiTokens.colors.textSecondary
                    )
                ) {
                    Text(text = if (isSubmitting) "沉淀中" else "确认沉淀")
                }
            }
        }
    }
}

@Composable
private fun KnowledgeEntrySummaryCard(
    entry: KnowledgeEntry,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
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
                text = "${entry.indexStatus.label()} · 来源 ${entry.sourceNoteIds.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun TodayPanel.title(isToday: Boolean): String {
    val periodLabel = if (isToday) "今日" else "这一天"
    return when (this) {
        TodayPanel.CAPTURED -> "${periodLabel}收纳内容"
        TodayPanel.DRAFTS -> "待确认草稿"
        TodayPanel.INGESTED -> "${periodLabel}沉淀结果"
        TodayPanel.ISSUES -> "待处理异常"
    }
}

@Composable
private fun RequestProgressBlock(
    progress: AiTaskProgress,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${progress.stage.label()} · ${progress.progress}%",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
        Text(
            text = progress.message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
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
                text = "$retryableCount 条可重试 · 处理失败音频已暂存",
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
        com.nazhi.app.core.model.AiTaskStage.FAILED -> "处理失败"
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
                text = "历史待整理",
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
                label = { Text("保存文章摘录、链接或临时想法") }
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
                    "先保存一段文章摘录、链接或临时想法。"
                } else {
                    "可以回到日历选择其他日期查看。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun NoteSummaryListItem(
    note: Note,
    onOpen: () -> Unit
) {
    OutlinedButton(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = note.title ?: "未命名记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${note.sourceType.label()} · ${note.createdAt.formatTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
    NazhiTheme {
        InboxScreen(
            screenTitle = "纳知",
            screenSubtitle = "今日",
            isToday = true,
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
                drafts = emptyList(),
                knowledgeEntries = emptyList(),
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
            hasDuplicateDrafts = false,
            hasReviewRequiredDrafts = false,
            isAiOrganizing = false,
            isKnowledgeTaskRunning = false,
            aiOrganizeProgress = null,
            aiOrganizeMessage = null,
            onInputChange = {},
            onPasteClipboard = {},
            onPasteSave = {},
            onSave = {},
            onEdit = {},
            onCopy = {},
            onDelete = {},
            onRetryAudioJobs = {},
            onAiOrganizeToday = {},
            onSubmitDraft = {},
            onEditDraft = {},
            onEditKnowledgeEntry = {},
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
        AudioTranscriptionJobStatus.FAILED -> "处理失败"
        AudioTranscriptionJobStatus.SAVED -> "已沉淀"
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
        NoteStatus.INBOX -> "待整理"
        NoteStatus.REVIEWED -> "已沉淀"
        NoteStatus.ARCHIVED -> "已沉淀"
        NoteStatus.DELETED -> "处理失败"
    }
}

private fun KnowledgeEntryDraft.reviewLabel(): String {
    return "待确认"
}

private fun KnowledgeIndexStatus.label(): String {
    return when (this) {
        KnowledgeIndexStatus.PENDING -> "已沉淀"
        KnowledgeIndexStatus.INDEXING -> "已沉淀"
        KnowledgeIndexStatus.INDEXED -> "已沉淀"
        KnowledgeIndexStatus.FAILED -> "处理失败"
    }
}

private fun FarmStage.dialogTitle(): String {
    return when (this) {
        FarmStage.SAPLING -> "待整理内容"
        FarmStage.PLANT -> "待确认内容"
        FarmStage.MATURE -> "已沉淀内容"
    }
}

private fun FarmPlotUiModel.farmDialogTitle(): String {
    return "这块农田：${stage.dialogTitle()}"
}

private fun FarmPlotUiModel.contentSummaryText(): String {
    val prefix = if (items.size == 1) {
        "这个地块对应 1 条内容"
    } else {
        "这个地块聚合了 ${items.size} 条内容"
    }
    val statusParts = listOf(
        FarmOwnerType.NOTE to "待整理",
        FarmOwnerType.DRAFT to "待确认",
        FarmOwnerType.KNOWLEDGE_ENTRY to "已沉淀"
    ).mapNotNull { (ownerType, label) ->
        val count = items.count { it.ownerType == ownerType }
        if (count > 0) "$label $count" else null
    }
    return if (statusParts.isEmpty()) {
        prefix
    } else {
        "$prefix：${statusParts.joinToString(" · ")}"
    }
}

private fun FarmOwnerType.dialogLabel(): String {
    return when (this) {
        FarmOwnerType.NOTE -> "待整理"
        FarmOwnerType.DRAFT -> "待确认"
        FarmOwnerType.KNOWLEDGE_ENTRY -> "已沉淀"
    }
}

private fun FarmOwnerType.panelActionLabel(): String {
    return when (this) {
        FarmOwnerType.NOTE -> "打开收纳"
        FarmOwnerType.DRAFT -> "打开确认"
        FarmOwnerType.KNOWLEDGE_ENTRY -> "打开沉淀"
    }
}

private fun KnowledgeEntry.toReferenceText(): String {
    val title = userTitle?.takeIf { it.isNotBlank() } ?: "未命名知识"
    return "“$title”\n$content\n—— 纳知 $confirmedDate"
}

private fun String.toTagList(): List<String> {
    return split(',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
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
