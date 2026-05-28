package com.nazhi.app.feature.knowledge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.AiTaskProgress
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.IntentType
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
import com.nazhi.app.core.ui.KnowledgeEntryDetailDialog
import com.nazhi.app.core.util.todayDateId
import kotlinx.coroutines.launch

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
    val embeddingCount by remember(repository) {
        repository.observeEmbeddingCount()
    }.collectAsState(initial = 0)
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
        embeddingCount = embeddingCount,
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
        KnowledgeEntryEditDialog(
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
    embeddingCount: Int,
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
    val visibleEntries = remember(entries, entrySearchQuery) {
        entries.filter { entry ->
            entry.matchesKeyword(entrySearchQuery)
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
                    embeddingCount = embeddingCount,
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
                        visibleCount = visibleEntries.size,
                        onQueryChange = onEntrySearchQueryChange
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "完成 AI 整理并提交后，知识条目会显示在这里。")
                    }
                } else if (visibleEntries.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "没有匹配的知识条目。")
                    }
                } else {
                    items(
                        items = visibleEntries,
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
                    text = "存在需确认草稿，需逐条查看后再沉淀。",
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
                        pendingIndexCount > 0 -> "完成待沉淀知识"
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
                        Text(text = if (duplicateEntry == null) "确认沉淀" else "重复不可沉淀")
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
private fun KnowledgeEntryEditDialog(
    entry: KnowledgeEntry,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (KnowledgeEntry) -> Unit
) {
    var title by remember(entry.id) { mutableStateOf(entry.userTitle.orEmpty()) }
    var summary by remember(entry.id) { mutableStateOf(entry.summary) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var tagsText by remember(entry.id) { mutableStateOf(entry.tags.joinToString("，")) }
    var remark by remember(entry.id) { mutableStateOf(entry.userRemark.orEmpty()) }
    var intentType by remember(entry.id) { mutableStateOf(entry.intentType) }
    val canSave = content.isNotBlank() && !isSaving

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) {
                onDismiss()
            }
        },
        title = { Text(text = "编辑知识条目") },
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
                    minLines = 5,
                    label = { Text(text = "正文") }
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "标签，用逗号分隔") }
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text(text = "备注，可选") }
                )
                Text(
                    text = if (isSaving) "正在保存并更新问答能力" else "保存后会更新该条目的问答能力。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        entry.copy(
                            userTitle = title.trim().takeIf { it.isNotBlank() },
                            summary = summary.trim(),
                            content = content.trim(),
                            intentType = intentType,
                            tags = tagsText.toTagList(),
                            userRemark = remark.trim().takeIf { it.isNotBlank() },
                            indexStatus = KnowledgeIndexStatus.PENDING
                        )
                    )
                },
                enabled = canSave
            ) {
                Text(text = if (isSaving) "保存中" else "保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
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
    embeddingCount: Int,
    indexedEntryCount: Int,
    pendingIndexCount: Int,
    failedIndexCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "本地知识检索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "已沉淀知识 $indexedEntryCount 条",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(text = "输入想检索的问题") }
            )
            Button(
                onClick = onSearch,
                enabled = query.isNotBlank() && indexedEntryCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        entryCount == 0 || indexedEntryCount == 0 -> "暂无可检索内容"
                        else -> "语义检索"
                    }
                )
            }
        }
    }
}

@Composable
private fun KnowledgeEntryManagementCard(
    query: String,
    totalCount: Int,
    visibleCount: Int,
    onQueryChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "条目管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "搜索标题、摘要、正文或标签") },
                singleLine = true
            )
            Text(
                text = "显示 $visibleCount / $totalCount 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            "知识沉淀失败，请检查网络或 API 配置后重试。"
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leadingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = entry.userTitle ?: entry.content.lineSequence().firstOrNull().orEmpty().ifBlank { "未命名知识" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.summary.ifBlank { entry.content },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.confirmedDate} · ${entry.indexStatus.label()}",
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
            Text(
                text = "来源笔记 ${entry.sourceNoteIds.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                onEdit?.let {
                    TextButton(
                        onClick = it,
                        enabled = actionsEnabled
                    ) {
                        Text(text = "编辑")
                    }
                }
                TextButton(onClick = onViewEntry) {
                    Text(text = "查看详情")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                onReindex?.let {
                    TextButton(
                        onClick = it,
                        enabled = actionsEnabled && entry.indexStatus != KnowledgeIndexStatus.INDEXING
                    ) {
                        Text(text = entry.indexActionText())
                    }
                }
                TextButton(onClick = onCopy) {
                    Text(text = "复制引用")
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
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

private fun DayKnowledgeStatus.statusText(): String {
    return when {
        noteCount == 0 -> "未收纳"
        pendingDraftCount > 0 -> "草稿待确认"
        failedIndexCount > 0 -> "部分失败，可重试"
        isComplete -> "已沉淀"
        knowledgeEntryCount > indexedEntryCount -> "沉淀中或待重试"
        draftCount > 0 -> "草稿已处理"
        pendingNoteCount > 0 -> "已保存，待整理"
        else -> "未整理"
    }
}

private fun KnowledgeEntryDraft.reviewLabel(): String {
    return if (needsReview) "需确认" else "可确认"
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
            code == "MINIMAX_CHAT_FAILED" -> "模型生成失败，请稍后重试或检查服务器日志。"
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
        com.nazhi.app.core.model.KnowledgeIndexStatus.PENDING -> "待沉淀"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXING -> "沉淀中"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXED -> "已沉淀"
        com.nazhi.app.core.model.KnowledgeIndexStatus.FAILED -> "沉淀失败"
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

