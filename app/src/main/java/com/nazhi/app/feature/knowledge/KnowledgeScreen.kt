package com.nazhi.app.feature.knowledge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nazhi.app.R
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.SemanticSearchResult
import com.nazhi.app.core.model.findDuplicateEntry
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionState
import com.nazhi.app.core.knowledge.KnowledgeTaskKind
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.ui.EditableKnowledgeEntryDialog
import com.nazhi.app.core.ui.EditableSourceNoteDialog
import com.nazhi.app.core.ui.KnowledgeEntryDetailDialog
import com.nazhi.app.core.util.extractFirstUrl
import com.nazhi.app.core.util.todayDateId
import com.nazhi.app.core.util.toNazhiTitle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun KnowledgeRoute(
    repository: NazhiRepository,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator,
    focusedEntryId: String? = null,
    onFocusedEntryConsumed: () -> Unit = {}
) {
    val today = remember { todayDateId() }
    val entries by remember(repository) {
        repository.observeKnowledgeEntries()
    }.collectAsState(initial = emptyList())
    val drafts by remember(repository, today) {
        repository.observeKnowledgeDraftsForDate(today)
    }.collectAsState(initial = emptyList())
    val dayStatus by remember(repository, today) {
        repository.observeDayKnowledgeStatus(today)
    }.collectAsState(initial = DayKnowledgeStatus(today, 0, 0, 0, 0, 0, 0, 0, 0))
    val knowledgeIngestionState by remember(knowledgeIngestionCoordinator) {
        knowledgeIngestionCoordinator.state
    }.collectAsState(initial = KnowledgeIngestionState())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SemanticSearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var entrySearchQuery by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var isUpdatingEntry by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<KnowledgeEntryDraft?>(null) }
    var editingEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var editingSourceNote by remember { mutableStateOf<Note?>(null) }
    var sourceDialogTitle by remember { mutableStateOf<String?>(null) }
    var sourceDialogNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var detailEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var detailSourceNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    val pendingDrafts = drafts.filter { it.status == KnowledgeDraftStatus.PENDING }
    val indexedEntryCount = entries.count { it.indexStatus == KnowledgeIndexStatus.INDEXED }
    val pendingIndexCount = entries.count {
        it.indexStatus == KnowledgeIndexStatus.PENDING || it.indexStatus == KnowledgeIndexStatus.INDEXING
    }
    val failedIndexCount = entries.count { it.indexStatus == KnowledgeIndexStatus.FAILED }
    val hasDuplicateDrafts = pendingDrafts.any { draft ->
        draft.findDuplicateEntry(entries) != null
    }
    var handledKnowledgeIngestionEventId by remember {
        mutableStateOf(knowledgeIngestionState.eventId)
    }

    LaunchedEffect(focusedEntryId, entries) {
        val entryId = focusedEntryId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val entry = entries.firstOrNull { it.id == entryId } ?: repository.getKnowledgeEntry(entryId)
        if (entry != null) {
            detailEntry = entry
            detailSourceNotes = repository.getNotesByIds(entry.sourceNoteIds)
            onFocusedEntryConsumed()
        }
    }

    LaunchedEffect(knowledgeIngestionState.eventId) {
        val message = knowledgeIngestionState.message
        if (knowledgeIngestionState.eventId != handledKnowledgeIngestionEventId && !message.isNullOrBlank()) {
            handledKnowledgeIngestionEventId = knowledgeIngestionState.eventId
            snackbarHostState.showSnackbar(message)
        }
    }

    KnowledgeScreen(
        today = today,
        entries = entries,
        drafts = drafts,
        dayStatus = dayStatus,
        indexedEntryCount = indexedEntryCount,
        pendingIndexCount = pendingIndexCount,
        failedIndexCount = failedIndexCount,
        query = query,
        results = results,
        hasSearched = hasSearched,
        entrySearchQuery = entrySearchQuery,
        isOrganizing = knowledgeIngestionState.isRunning &&
            knowledgeIngestionState.taskKind == KnowledgeTaskKind.ORGANIZE,
        organizeProgress = knowledgeIngestionState.progress,
        isSubmitting = isSubmitting || knowledgeIngestionState.isRunning,
        isUpdatingEntry = isUpdatingEntry,
        knowledgeIngestionState = knowledgeIngestionState,
        hasDuplicateDrafts = hasDuplicateDrafts,
        snackbarHostState = snackbarHostState,
        onQueryChange = {
            query = it
            if (it.isBlank()) {
                hasSearched = false
                results = emptyList()
            }
        },
        onEntrySearchQueryChange = { entrySearchQuery = it },
        onOrganizeToday = {
            knowledgeIngestionCoordinator.organizeToday(today)
        },
        onSubmitDraft = { draft ->
            knowledgeIngestionCoordinator.submitDraft(draft.id)
        },
        onEditDraft = { draft ->
            editingDraft = draft
        },
        onViewSources = { draft ->
            coroutineScope.launch {
                val notes = repository.getNotesByIds(draft.sourceNoteIds)
                sourceDialogTitle = draft.title
                sourceDialogNotes = notes
            }
        },
        onSkipDraft = { draft ->
            coroutineScope.launch {
                isSubmitting = true
                val message = runCatching {
                    repository.skipKnowledgeDraft(draft.id)
                    "已跳过该草稿"
                }.getOrElse { error ->
                    "跳过失败：${error.toUserFacingMessage()}"
                }
                isSubmitting = false
                snackbarHostState.showSnackbar(message)
            }
        },
        onSubmitAll = {
            val hasReviewRequiredDrafts = drafts.any {
                it.status == KnowledgeDraftStatus.PENDING && it.needsReview
            }
            knowledgeIngestionCoordinator.submitAll(
                date = today,
                hasDuplicateDrafts = hasDuplicateDrafts,
                hasReviewRequiredDrafts = hasReviewRequiredDrafts
            )
        },
        onRetryIndex = {
            knowledgeIngestionCoordinator.indexPending()
        },
        onSearch = {
            coroutineScope.launch {
                results = repository.searchSimilarKnowledgeEntries(query, topK = 5)
                hasSearched = true
                snackbarHostState.showSnackbar("语义检索完成")
            }
        },
        onCopy = { entry ->
            context.copyToClipboard(
                label = "纳知引用",
                text = entry.toReferenceText()
            )
            coroutineScope.launch {
                snackbarHostState.showSnackbar("已复制引用")
            }
        },
        onViewEntry = { entry ->
            coroutineScope.launch {
                detailEntry = entry
                detailSourceNotes = repository.getNotesByIds(entry.sourceNoteIds)
            }
        },
        onEditEntry = { entry ->
            editingEntry = entry
        },
        onReindexEntry = { entry ->
            coroutineScope.launch {
                isUpdatingEntry = true
                val message = runCatching {
                    if (repository.indexKnowledgeEntry(entry.id)) {
                        "问答能力已更新"
                    } else {
                        "问答能力更新失败，请检查网络或 API 配置后重试"
                    }
                }.getOrElse { error ->
                    "问答能力更新失败：${error.toUserFacingMessage()}"
                }
                isUpdatingEntry = false
                snackbarHostState.showSnackbar(message)
            }
        }
    )

    editingDraft?.let { draft ->
        DraftEditDialog(
            draft = draft,
            onDismiss = { editingDraft = null },
            onConfirm = { updatedDraft ->
                coroutineScope.launch {
                    repository.updateKnowledgeDraft(updatedDraft)
                    editingDraft = null
                    snackbarHostState.showSnackbar("草稿已更新")
                }
            }
        )
    }

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
                            if (detailEntry?.id == updatedEntry.id) {
                                detailEntry = repository.getKnowledgeEntry(updatedEntry.id) ?: updatedEntry
                            }
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
                    val now = System.currentTimeMillis()
                    repository.updateNoteContent(
                        id = note.id,
                        content = content,
                        title = content.toNazhiTitle(),
                        sourceUrl = content.extractFirstUrl(),
                        userRemark = remark.takeIf { it.isNotBlank() },
                        updatedAt = now
                    )
                    editingSourceNote = null
                    detailSourceNotes = repository.getNotesByIds(detailEntry?.sourceNoteIds.orEmpty())
                    snackbarHostState.showSnackbar("原始 Note 已更新")
                }
            }
        )
    }

    sourceDialogTitle?.let { title ->
        SourceNotesDialog(
            title = title,
            notes = sourceDialogNotes,
            onDismiss = {
                sourceDialogTitle = null
                sourceDialogNotes = emptyList()
            }
        )
    }

    detailEntry?.let { entry ->
        KnowledgeEntryDetailDialog(
            entry = entry,
            sourceNotes = detailSourceNotes,
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
                editingEntry = entry
            },
            onEditNote = { note ->
                detailEntry = null
                detailSourceNotes = emptyList()
                editingSourceNote = note
            },
            onDismiss = {
                detailEntry = null
                detailSourceNotes = emptyList()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeScreen(
    today: String,
    entries: List<KnowledgeEntry>,
    drafts: List<KnowledgeEntryDraft>,
    dayStatus: DayKnowledgeStatus,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    query: String,
    results: List<SemanticSearchResult>,
    hasSearched: Boolean,
    entrySearchQuery: String,
    isOrganizing: Boolean,
    organizeProgress: AiTaskProgress?,
    isSubmitting: Boolean,
    isUpdatingEntry: Boolean,
    knowledgeIngestionState: KnowledgeIngestionState,
    hasDuplicateDrafts: Boolean,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onEntrySearchQueryChange: (String) -> Unit,
    onOrganizeToday: () -> Unit,
    onSubmitDraft: (KnowledgeEntryDraft) -> Unit,
    onEditDraft: (KnowledgeEntryDraft) -> Unit,
    onViewSources: (KnowledgeEntryDraft) -> Unit,
    onSkipDraft: (KnowledgeEntryDraft) -> Unit,
    onSubmitAll: () -> Unit,
    onRetryIndex: () -> Unit,
    onSearch: () -> Unit,
    onCopy: (KnowledgeEntry) -> Unit,
    onViewEntry: (KnowledgeEntry) -> Unit,
    onEditEntry: (KnowledgeEntry) -> Unit,
    onReindexEntry: (KnowledgeEntry) -> Unit
) {
    var selectedEntryFilter by remember { mutableStateOf(KnowledgeEntryStatusFilter.ALL) }
    val visibleEntries = remember(entries, entrySearchQuery) {
        entries.filter { entry ->
            entry.matchesKeyword(entrySearchQuery)
        }
    }
    val filteredEntries = remember(visibleEntries, selectedEntryFilter) {
        visibleEntries.filter { entry -> selectedEntryFilter.matches(entry) }
    }
    val statusFilterCounts = remember(entries) {
        KnowledgeEntryStatusFilter.values().associateWith { filter ->
            entries.count { entry -> filter.matches(entry) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "知识库")
                        Text(
                            text = "检索与查看已沉淀知识",
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
                KnowledgeSearchCard(
                    entryCount = entries.size,
                    indexedEntryCount = indexedEntryCount,
                    pendingIndexCount = pendingIndexCount,
                    failedIndexCount = failedIndexCount,
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch
                )
            }

            if (hasSearched) {
                item {
                    SectionTitle(text = "语义检索结果")
                }
                if (results.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "没有找到相近内容。可先完成今日沉淀，或换一个问题。")
                    }
                } else {
                    items(
                        items = results,
                        key = { result -> result.entry.id }
                    ) { result ->
                        KnowledgeResultCard(
                            result = result,
                            onViewEntry = { onViewEntry(result.entry) },
                            onCopy = { onCopy(result.entry) }
                        )
                    }
                }
            } else {
                item {
                    SectionTitle(text = "已沉淀内容")
                }
                item {
                    KnowledgeEntryManagementCard(
                        query = entrySearchQuery,
                        totalCount = entries.size,
                        visibleCount = filteredEntries.size,
                        selectedFilter = selectedEntryFilter,
                        filterCounts = statusFilterCounts,
                        onQueryChange = onEntrySearchQueryChange,
                        onFilterChange = { selectedEntryFilter = it }
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "完成 AI 整理并提交后，知识条目会显示在这里。")
                    }
                } else if (filteredEntries.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "没有匹配的知识条目。")
                    }
                } else {
                    items(
                        items = filteredEntries,
                        key = { entry -> entry.id }
                    ) { entry ->
                        KnowledgeEntryCard(
                            entry = entry,
                            onViewEntry = { onViewEntry(entry) },
                            onCopy = { onCopy(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayKnowledgeStatusCard(
    today: String,
    status: DayKnowledgeStatus,
    hasReviewRequiredDrafts: Boolean,
    hasDuplicateDrafts: Boolean,
    isOrganizing: Boolean,
    organizeProgress: AiTaskProgress?,
    isSubmitting: Boolean,
    knowledgeIngestionState: KnowledgeIngestionState,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    onOrganizeToday: () -> Unit,
    onSubmitAll: () -> Unit,
    onRetryIndex: () -> Unit
) {
    val canOrganize = status.pendingNoteCount > 0 && status.pendingDraftCount == 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "今日沉淀状态 · $today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = status.statusText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "笔记 ${status.noteCount} 条 · 待整理 ${status.pendingNoteCount} 条 · 待确认 ${status.pendingDraftCount} 条 · 已沉淀 ${status.indexedEntryCount} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            organizeProgress?.let { progress ->
                RequestProgressBlock(progress = progress)
            }
            val shouldShowKnowledgeTaskMessage = knowledgeIngestionState.progress == null ||
                !knowledgeIngestionState.isRunning
            knowledgeIngestionState.message?.takeIf { shouldShowKnowledgeTaskMessage }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (knowledgeIngestionState.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (knowledgeIngestionState.isRunning && knowledgeIngestionState.progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOrganizeToday,
                    enabled = !isOrganizing && !knowledgeIngestionState.isRunning && canOrganize,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when {
                            isOrganizing -> "整理中"
                            status.pendingDraftCount > 0 -> "先确认草稿"
                            status.pendingNoteCount == 0 -> "暂无可整理"
                            else -> "AI整理"
                        }
                    )
                }
                Button(
                    onClick = onSubmitAll,
                    enabled = !isSubmitting &&
                        !knowledgeIngestionState.isRunning &&
                        status.pendingDraftCount > 0 &&
                        !hasReviewRequiredDrafts &&
                        !hasDuplicateDrafts,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when {
                            knowledgeIngestionState.isRunning -> "沉淀中"
                            isSubmitting -> "提交中"
                            hasDuplicateDrafts -> "先处理重复"
                            hasReviewRequiredDrafts -> "先确认草稿"
                            else -> "确认沉淀"
                        }
                    )
                }
            }
            if (hasReviewRequiredDrafts) {
                Text(
                    text = "存在待确认草稿，需逐条查看后再沉淀。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasDuplicateDrafts) {
                Text(
                    text = "存在疑似重复草稿，请先跳过或编辑后再沉淀。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!canOrganize) {
                Text(
                    text = when {
                        status.pendingDraftCount > 0 -> "已有待确认草稿，确认或跳过后再重新整理。"
                        status.pendingNoteCount == 0 -> "当前没有待整理内容。"
                        else -> "当前不可整理。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onRetryIndex,
                enabled = !knowledgeIngestionState.isRunning && (failedIndexCount > 0 || pendingIndexCount > 0),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        knowledgeIngestionState.isRunning -> knowledgeIngestionState.runningLabel ?: "沉淀中"
                        failedIndexCount > 0 -> "重试沉淀"
                        pendingIndexCount > 0 -> "完成已沉淀知识"
                        else -> "已沉淀"
                    }
                )
            }
        }
    }
}

@Composable
private fun KnowledgeDraftCard(
    draft: KnowledgeEntryDraft,
    duplicateEntry: KnowledgeEntry?,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
    onEdit: () -> Unit,
    onViewSources: () -> Unit,
    onSkip: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = draft.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = draft.summary.ifBlank { draft.content },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "来源 ${draft.sourceNoteIds.size} 条 · ${draft.reviewLabel()} · 置信度 ${"%.2f".format(draft.confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (draft.tags.isNotEmpty()) {
                Text(
                    text = draft.tags.joinToString(prefix = "标签："),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            draft.insight?.let { insight ->
                Text(
                    text = "AI推断：$insight",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            duplicateEntry?.let { entry ->
                Text(
                    text = "疑似重复：${entry.userTitle?.takeIf { it.isNotBlank() } ?: "未命名知识"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onViewSources,
                        enabled = draft.sourceNoteIds.isNotEmpty()
                    ) {
                        Text(text = "查看来源")
                    }
                    TextButton(onClick = onEdit) {
                        Text(text = "编辑草稿")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onSkip,
                        enabled = !isSubmitting
                    ) {
                        Text(text = "跳过")
                    }
                    TextButton(
                        onClick = onSubmit,
                        enabled = !isSubmitting && duplicateEntry == null
                    ) {
                        Text(text = if (duplicateEntry == null) "确认沉淀" else "重复，需处理")
                    }
                }
            }
        }
    }
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
    var intentType by remember(draft.id) { mutableStateOf(draft.intentType) }
    val canSave = title.isNotBlank() && content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑 AI 草稿") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
private fun SourceNotesDialog(
    title: String,
    notes: List<Note>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "来源笔记") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (notes.isEmpty()) {
                    Text(text = "没有找到对应的原始 Note。")
                } else {
                    notes.forEachIndexed { index, note ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${index + 1}. ${note.title ?: "未命名记录"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = note.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${note.sourceType.label()} · ${note.createdDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (index != notes.lastIndex) {
                            HorizontalDivider()
                        }
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
private fun KnowledgeSearchCard(
    entryCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    KnowledgeAssetBox(
        spec = KnowledgeAssetSpecs.SearchPanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "语义搜索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(text = "向知识库提问或描述想查找的内容") }
            )
            Button(
                onClick = onSearch,
                enabled = query.isNotBlank() && indexedEntryCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        entryCount == 0 || indexedEntryCount == 0 -> "暂无可检索内容"
                        else -> "搜索相关知识"
                    }
                )
            }
            KnowledgeIndexStatusHint(
                entryCount = entryCount,
                indexedEntryCount = indexedEntryCount,
                pendingIndexCount = pendingIndexCount,
                failedIndexCount = failedIndexCount
            )
        }
    }
}

@Composable
private fun KnowledgeEntryManagementCard(
    query: String,
    totalCount: Int,
    visibleCount: Int,
    selectedFilter: KnowledgeEntryStatusFilter,
    filterCounts: Map<KnowledgeEntryStatusFilter, Int>,
    onQueryChange: (String) -> Unit,
    onFilterChange: (KnowledgeEntryStatusFilter) -> Unit
) {
    KnowledgeAssetBox(
        spec = KnowledgeAssetSpecs.FilterPanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "本地筛选",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$visibleCount / $totalCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "搜索标题、摘要、正文或标签") },
                singleLine = true
            )
            KnowledgeEntryStatusFilterRow(
                selectedFilter = selectedFilter,
                filterCounts = filterCounts,
                onFilterChange = onFilterChange
            )
        }
    }
}

@Composable
private fun KnowledgeEntryStatusFilterRow(
    selectedFilter: KnowledgeEntryStatusFilter,
    filterCounts: Map<KnowledgeEntryStatusFilter, Int>,
    onFilterChange: (KnowledgeEntryStatusFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KnowledgeEntryStatusFilter.values().toList().chunked(2).forEach { rowFilters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowFilters.forEach { filter ->
                    val count = filterCounts[filter] ?: 0
                    val modifier = Modifier.weight(1f)
                    if (filter == selectedFilter) {
                        Button(
                            onClick = { onFilterChange(filter) },
                            modifier = modifier
                        ) {
                            Text(
                                text = filter.labelWithCount(count),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onFilterChange(filter) },
                            modifier = modifier
                        ) {
                            Text(
                                text = filter.labelWithCount(count),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeIndexStatusHint(
    entryCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int
) {
    val text = when {
        entryCount == 0 -> "当前还没有知识条目。"
        indexedEntryCount == 0 && pendingIndexCount > 0 -> {
            "知识库已有内容但尚未完成沉淀，请点击上方“重试沉淀”后再检索或问答。"
        }
        indexedEntryCount == 0 && failedIndexCount > 0 -> {
            "知识处理失败，请检查网络或 API 配置后重试。"
        }
        pendingIndexCount > 0 || failedIndexCount > 0 -> {
            "部分知识尚未完成沉淀，当前检索和问答只会使用已沉淀内容。"
        }
        else -> null
    }
    text?.let {
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

@Composable
private fun KnowledgeEntryStatusBadge(status: KnowledgeIndexStatus) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = when (status) {
        KnowledgeIndexStatus.FAILED -> colorScheme.onErrorContainer
        KnowledgeIndexStatus.INDEXED -> colorScheme.onPrimaryContainer
        KnowledgeIndexStatus.PENDING,
        KnowledgeIndexStatus.INDEXING -> colorScheme.onSecondaryContainer
    }

    KnowledgeAssetBox(
        spec = KnowledgeAssetSpecs.StatusBadge,
        modifier = Modifier.widthIn(min = 76.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = status.badgeText(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun KnowledgeResultCard(
    result: SemanticSearchResult,
    onViewEntry: () -> Unit,
    onCopy: () -> Unit
) {
    KnowledgeEntryCard(
        entry = result.entry,
        leadingText = "相似度 ${"%.3f".format(result.score)}",
        onViewEntry = onViewEntry,
        onCopy = onCopy
    )
}

@Composable
private fun KnowledgeEntryCard(
    entry: KnowledgeEntry,
    leadingText: String? = null,
    onViewEntry: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onReindex: (() -> Unit)? = null,
    actionsEnabled: Boolean = true
) {
    KnowledgeAssetBox(
        spec = KnowledgeAssetSpecs.EntryCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leadingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = entry.displayTitleText(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                KnowledgeEntryStatusBadge(status = entry.indexStatus)
            }
            Text(
                text = entry.summary.ifBlank { entry.content },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "来源 ${entry.sourceNoteIds.size} 条 · ${entry.confirmedDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.tags.isNotEmpty()) {
                Text(
                    text = entry.tags.joinToString(prefix = "标签："),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onViewEntry) {
                    Text(text = "查看")
                }
                TextButton(onClick = onCopy) {
                    Text(text = "复制")
                }
                onEdit?.let {
                    TextButton(
                        onClick = it,
                        enabled = actionsEnabled
                    ) {
                        Text(text = "编辑")
                    }
                }
                onReindex?.let {
                    TextButton(
                        onClick = it,
                        enabled = actionsEnabled && entry.indexStatus != KnowledgeIndexStatus.INDEXING
                    ) {
                        Text(text = entry.indexActionText())
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun EmptyKnowledgeCard(text: String) {
    KnowledgeAssetBox(
        spec = KnowledgeAssetSpecs.EmptyPanel,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun KnowledgeAssetBox(
    spec: KnowledgeAssetSpec,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val image = ImageBitmap.imageResource(id = spec.backgroundRes)
    Box(
        modifier = modifier
            .heightIn(min = spec.minHeight)
            .drawBehind {
                drawKnowledgeNineSliceImage(image = image, spec = spec)
            }
            .padding(spec.contentPadding),
        contentAlignment = contentAlignment,
        propagateMinConstraints = true,
        content = content
    )
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

private data class KnowledgeAssetSpec(
    val backgroundRes: Int,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceRight: Int,
    val sourceBottom: Int,
    val destinationLeft: Dp,
    val destinationTop: Dp,
    val destinationRight: Dp,
    val destinationBottom: Dp,
    val minHeight: Dp,
    val contentPadding: PaddingValues
)

private object KnowledgeAssetSpecs {
    val SearchPanel = KnowledgeAssetSpec(
        backgroundRes = R.drawable.knowledge_search_panel_bg,
        sourceLeft = 72,
        sourceTop = 72,
        sourceRight = 72,
        sourceBottom = 72,
        destinationLeft = 28.dp,
        destinationTop = 28.dp,
        destinationRight = 28.dp,
        destinationBottom = 28.dp,
        minHeight = 196.dp,
        contentPadding = PaddingValues(start = 28.dp, top = 30.dp, end = 28.dp, bottom = 28.dp)
    )

    val FilterPanel = KnowledgeAssetSpec(
        backgroundRes = R.drawable.knowledge_filter_panel_bg,
        sourceLeft = 72,
        sourceTop = 72,
        sourceRight = 72,
        sourceBottom = 72,
        destinationLeft = 28.dp,
        destinationTop = 28.dp,
        destinationRight = 28.dp,
        destinationBottom = 28.dp,
        minHeight = 224.dp,
        contentPadding = PaddingValues(start = 28.dp, top = 30.dp, end = 28.dp, bottom = 28.dp)
    )

    val EntryCard = KnowledgeAssetSpec(
        backgroundRes = R.drawable.knowledge_entry_card_bg,
        sourceLeft = 64,
        sourceTop = 64,
        sourceRight = 64,
        sourceBottom = 64,
        destinationLeft = 24.dp,
        destinationTop = 24.dp,
        destinationRight = 24.dp,
        destinationBottom = 24.dp,
        minHeight = 172.dp,
        contentPadding = PaddingValues(start = 28.dp, top = 30.dp, end = 28.dp, bottom = 26.dp)
    )

    val EmptyPanel = KnowledgeAssetSpec(
        backgroundRes = R.drawable.knowledge_empty_panel_bg,
        sourceLeft = 72,
        sourceTop = 64,
        sourceRight = 72,
        sourceBottom = 64,
        destinationLeft = 28.dp,
        destinationTop = 24.dp,
        destinationRight = 28.dp,
        destinationBottom = 24.dp,
        minHeight = 96.dp,
        contentPadding = PaddingValues(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 24.dp)
    )

    val StatusBadge = KnowledgeAssetSpec(
        backgroundRes = R.drawable.knowledge_status_badge_bg,
        sourceLeft = 36,
        sourceTop = 24,
        sourceRight = 36,
        sourceBottom = 24,
        destinationLeft = 12.dp,
        destinationTop = 8.dp,
        destinationRight = 12.dp,
        destinationBottom = 8.dp,
        minHeight = 30.dp,
        contentPadding = PaddingValues(start = 12.dp, top = 5.dp, end = 12.dp, bottom = 5.dp)
    )
}

private fun DrawScope.drawKnowledgeNineSliceImage(
    image: ImageBitmap,
    spec: KnowledgeAssetSpec
) {
    val dstWidth = size.width.roundToInt()
    val dstHeight = size.height.roundToInt()
    if (dstWidth <= 0 || dstHeight <= 0 || image.width <= 0 || image.height <= 0) {
        return
    }

    val srcLeft = spec.sourceLeft.coerceIn(0, image.width)
    val srcTop = spec.sourceTop.coerceIn(0, image.height)
    val srcRight = spec.sourceRight.coerceIn(0, image.width - srcLeft)
    val srcBottom = spec.sourceBottom.coerceIn(0, image.height - srcTop)
    val srcCenterWidth = (image.width - srcLeft - srcRight).coerceAtLeast(0)
    val srcCenterHeight = (image.height - srcTop - srcBottom).coerceAtLeast(0)

    val rawDstLeft = spec.destinationLeft.toPx()
    val rawDstRight = spec.destinationRight.toPx()
    val rawDstTop = spec.destinationTop.toPx()
    val rawDstBottom = spec.destinationBottom.toPx()
    val horizontalScale = if (rawDstLeft + rawDstRight > dstWidth) {
        dstWidth / (rawDstLeft + rawDstRight)
    } else {
        1f
    }
    val verticalScale = if (rawDstTop + rawDstBottom > dstHeight) {
        dstHeight / (rawDstTop + rawDstBottom)
    } else {
        1f
    }
    val dstLeft = (rawDstLeft * horizontalScale).roundToInt()
    val dstRight = (rawDstRight * horizontalScale).roundToInt()
    val dstTop = (rawDstTop * verticalScale).roundToInt()
    val dstBottom = (rawDstBottom * verticalScale).roundToInt()
    val dstCenterWidth = (dstWidth - dstLeft - dstRight).coerceAtLeast(0)
    val dstCenterHeight = (dstHeight - dstTop - dstBottom).coerceAtLeast(0)

    fun drawPatch(
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(sourceX, sourceY),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset(targetX, targetY),
            dstSize = IntSize(targetWidth, targetHeight),
            filterQuality = FilterQuality.None
        )
    }

    val srcMiddleX = srcLeft
    val srcRightX = image.width - srcRight
    val srcMiddleY = srcTop
    val srcBottomY = image.height - srcBottom
    val dstMiddleX = dstLeft
    val dstRightX = dstWidth - dstRight
    val dstMiddleY = dstTop
    val dstBottomY = dstHeight - dstBottom

    drawPatch(0, 0, srcLeft, srcTop, 0, 0, dstLeft, dstTop)
    drawPatch(srcMiddleX, 0, srcCenterWidth, srcTop, dstMiddleX, 0, dstCenterWidth, dstTop)
    drawPatch(srcRightX, 0, srcRight, srcTop, dstRightX, 0, dstRight, dstTop)

    drawPatch(0, srcMiddleY, srcLeft, srcCenterHeight, 0, dstMiddleY, dstLeft, dstCenterHeight)
    drawPatch(srcMiddleX, srcMiddleY, srcCenterWidth, srcCenterHeight, dstMiddleX, dstMiddleY, dstCenterWidth, dstCenterHeight)
    drawPatch(srcRightX, srcMiddleY, srcRight, srcCenterHeight, dstRightX, dstMiddleY, dstRight, dstCenterHeight)

    drawPatch(0, srcBottomY, srcLeft, srcBottom, 0, dstBottomY, dstLeft, dstBottom)
    drawPatch(srcMiddleX, srcBottomY, srcCenterWidth, srcBottom, dstMiddleX, dstBottomY, dstCenterWidth, dstBottom)
    drawPatch(srcRightX, srcBottomY, srcRight, srcBottom, dstRightX, dstBottomY, dstRight, dstBottom)
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

private fun DayKnowledgeStatus.statusText(): String {
    return when {
        noteCount == 0 -> "待整理"
        pendingDraftCount > 0 -> "待确认"
        failedIndexCount > 0 -> "处理失败"
        isComplete -> "已沉淀"
        knowledgeEntryCount > indexedEntryCount -> "已沉淀"
        draftCount > 0 -> "已沉淀"
        pendingNoteCount > 0 -> "待整理"
        else -> "待整理"
    }
}

private enum class KnowledgeEntryStatusFilter(
    val label: String
) {
    ALL("全部"),
    INDEXED("可问答"),
    PROCESSING("沉淀中"),
    FAILED("处理失败");

    fun matches(entry: KnowledgeEntry): Boolean {
        return when (this) {
            ALL -> true
            INDEXED -> entry.indexStatus == KnowledgeIndexStatus.INDEXED
            PROCESSING -> entry.indexStatus == KnowledgeIndexStatus.PENDING ||
                entry.indexStatus == KnowledgeIndexStatus.INDEXING
            FAILED -> entry.indexStatus == KnowledgeIndexStatus.FAILED
        }
    }

    fun labelWithCount(count: Int): String {
        return "$label $count"
    }
}

private fun KnowledgeEntryDraft.reviewLabel(): String {
    return "待确认"
}

private fun KnowledgeEntry.matchesKeyword(query: String): Boolean {
    val keyword = query.trim()
    if (keyword.isBlank()) return true

    return listOf(
        userTitle.orEmpty(),
        summary,
        content,
        userRemark.orEmpty(),
        createdDate,
        confirmedDate,
        indexStatus.label()
    ).any { text -> text.contains(keyword, ignoreCase = true) } ||
        tags.any { tag -> tag.contains(keyword, ignoreCase = true) }
}

private fun KnowledgeEntry.indexActionText(): String {
    return when (indexStatus) {
        KnowledgeIndexStatus.FAILED -> "重试沉淀"
        KnowledgeIndexStatus.INDEXING -> "沉淀中"
        else -> "更新沉淀"
    }
}

private fun KnowledgeEntry.displayTitleText(): String {
    return userTitle
        ?: summary.takeIf { it.isNotBlank() }?.compactText(32)
        ?: content.lineSequence().firstOrNull().orEmpty().ifBlank { "未命名知识" }.compactText(32)
}

private fun KnowledgeIndexStatus.badgeText(): String {
    return when (this) {
        KnowledgeIndexStatus.INDEXED -> "可问答"
        KnowledgeIndexStatus.PENDING,
        KnowledgeIndexStatus.INDEXING -> "沉淀中"
        KnowledgeIndexStatus.FAILED -> "处理失败"
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

private fun String.toTagList(): List<String> {
    return split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(8)
}

private fun com.nazhi.app.core.model.SourceType.label(): String {
    return when (this) {
        com.nazhi.app.core.model.SourceType.SHARE -> "分享"
        com.nazhi.app.core.model.SourceType.MANUAL -> "手动输入"
        com.nazhi.app.core.model.SourceType.CLIPBOARD -> "剪贴板"
        com.nazhi.app.core.model.SourceType.TEXT_SELECTION -> "划词"
        com.nazhi.app.core.model.SourceType.AUDIO_TRANSCRIPTION -> "音频转写"
    }
}

private fun Throwable.toUserFacingMessage(): String {
    return when (this) {
        is NazhiBackendException -> when {
            statusCode == 401 || code == "UNAUTHORIZED" -> "鉴权失败，请检查设置页中的 NAZHI_DEV_TOKEN。"
            code == "MINIMAX_CHAT_FAILED" -> "模型处理失败，请稍后重试或检查服务器日志。"
            code == "MINIMAX_NOT_CONFIGURED" -> "服务器模型配置缺失，请检查 .env。"
            code == "MINIMAX_EMBEDDING_FAILED" -> "Embedding 模型调用失败，请稍后重试或检查服务器日志。"
            else -> publicMessage
        }
        else -> {
            val raw = message.orEmpty()
            when {
                raw.contains("Failed to connect", ignoreCase = true) -> "无法连接后端，请检查服务器地址、端口和防火墙。"
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

private fun com.nazhi.app.core.model.KnowledgeIndexStatus.label(): String {
    return when (this) {
        com.nazhi.app.core.model.KnowledgeIndexStatus.PENDING -> "已沉淀"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXING -> "已沉淀"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXED -> "已沉淀"
        com.nazhi.app.core.model.KnowledgeIndexStatus.FAILED -> "处理失败"
    }
}

private fun KnowledgeEntry.toReferenceText(): String {
    val title = userTitle?.takeIf { it.isNotBlank() } ?: "未命名知识"
    return "“$title”\n$content\n—— 纳知 $confirmedDate"
}

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

