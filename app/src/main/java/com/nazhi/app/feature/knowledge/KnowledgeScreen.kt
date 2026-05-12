package com.nazhi.app.feature.knowledge

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.SemanticSearchResult
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.todayDateId
import kotlinx.coroutines.launch

@Composable
fun KnowledgeRoute(repository: NazhiRepository) {
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
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SemanticSearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var isOrganizing by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    KnowledgeScreen(
        today = today,
        entries = entries,
        drafts = drafts,
        dayStatus = dayStatus,
        embeddingCount = embeddingCount,
        query = query,
        results = results,
        hasSearched = hasSearched,
        isOrganizing = isOrganizing,
        isSubmitting = isSubmitting,
        snackbarHostState = snackbarHostState,
        onQueryChange = {
            query = it
            if (it.isBlank()) {
                hasSearched = false
                results = emptyList()
            }
        },
        onOrganizeToday = {
            coroutineScope.launch {
                isOrganizing = true
                val message = runCatching {
                    val count = repository.organizeNotesForDate(today)
                    if (count == 0) "今日没有可整理的笔记" else "已生成 $count 条 AI 草稿"
                }.getOrElse { error ->
                    "AI 整理失败：${error.message ?: "请检查后端服务"}"
                }
                isOrganizing = false
                snackbarHostState.showSnackbar(message)
            }
        },
        onSubmitDraft = { draft ->
            coroutineScope.launch {
                isSubmitting = true
                val message = runCatching {
                    val entry = repository.submitKnowledgeDraft(draft.id)
                    if (entry == null) "草稿已处理或不存在" else "已提交知识库并尝试生成向量"
                }.getOrElse { error ->
                    "提交失败：${error.message ?: "请检查后端服务"}"
                }
                isSubmitting = false
                snackbarHostState.showSnackbar(message)
            }
        },
        onSubmitAll = {
            coroutineScope.launch {
                isSubmitting = true
                val message = runCatching {
                    val count = repository.submitAllKnowledgeDraftsForDate(today)
                    if (count == 0) "没有待提交草稿" else "已提交 $count 条草稿"
                }.getOrElse { error ->
                    "批量提交失败：${error.message ?: "请检查后端服务"}"
                }
                isSubmitting = false
                snackbarHostState.showSnackbar(message)
            }
        },
        onRetryIndex = {
            coroutineScope.launch {
                val message = runCatching {
                    val count = repository.indexPendingKnowledgeEntries()
                    if (count == 0) "没有可重试的向量任务" else "已完成 $count 条向量入库"
                }.getOrElse { error ->
                    "向量入库失败：${error.message ?: "请检查后端服务"}"
                }
                snackbarHostState.showSnackbar(message)
            }
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
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeScreen(
    today: String,
    entries: List<KnowledgeEntry>,
    drafts: List<KnowledgeEntryDraft>,
    dayStatus: DayKnowledgeStatus,
    embeddingCount: Int,
    query: String,
    results: List<SemanticSearchResult>,
    hasSearched: Boolean,
    isOrganizing: Boolean,
    isSubmitting: Boolean,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onOrganizeToday: () -> Unit,
    onSubmitDraft: (KnowledgeEntryDraft) -> Unit,
    onSubmitAll: () -> Unit,
    onRetryIndex: () -> Unit,
    onSearch: () -> Unit,
    onCopy: (KnowledgeEntry) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "知识库")
                        Text(
                            text = "AI 整理、提交入库与本地向量检索",
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
                DayKnowledgeStatusCard(
                    today = today,
                    status = dayStatus,
                    isOrganizing = isOrganizing,
                    isSubmitting = isSubmitting,
                    onOrganizeToday = onOrganizeToday,
                    onSubmitAll = onSubmitAll,
                    onRetryIndex = onRetryIndex
                )
            }

            if (drafts.any { it.status == KnowledgeDraftStatus.PENDING }) {
                item {
                    SectionTitle(text = "待确认 AI 草稿")
                }
                items(
                    items = drafts.filter { it.status == KnowledgeDraftStatus.PENDING },
                    key = { draft -> draft.id }
                ) { draft ->
                    KnowledgeDraftCard(
                        draft = draft,
                        isSubmitting = isSubmitting,
                        onSubmit = { onSubmitDraft(draft) }
                    )
                }
            }

            item {
                KnowledgeSearchCard(
                    entryCount = entries.size,
                    embeddingCount = embeddingCount,
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
                        EmptyKnowledgeCard(text = "没有找到相近内容。可先完成今日入库，或换一个问题。")
                    }
                } else {
                    items(
                        items = results,
                        key = { result -> result.entry.id }
                    ) { result ->
                        KnowledgeResultCard(
                            result = result,
                            onCopy = { onCopy(result.entry) }
                        )
                    }
                }
            } else {
                item {
                    SectionTitle(text = "已入库内容")
                }
                if (entries.isEmpty()) {
                    item {
                        EmptyKnowledgeCard(text = "完成 AI 整理并提交后，知识条目会显示在这里。")
                    }
                } else {
                    items(
                        items = entries,
                        key = { entry -> entry.id }
                    ) { entry ->
                        KnowledgeEntryCard(
                            entry = entry,
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
    isOrganizing: Boolean,
    isSubmitting: Boolean,
    onOrganizeToday: () -> Unit,
    onSubmitAll: () -> Unit,
    onRetryIndex: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "今日入库状态 · $today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = status.statusText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "笔记 ${status.noteCount} 条 · 待回顾 ${status.pendingNoteCount} 条 · 草稿 ${status.pendingDraftCount}/${status.draftCount} 条 · 已入库 ${status.knowledgeEntryCount} 条 · 已索引 ${status.indexedEntryCount} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOrganizeToday,
                    enabled = !isOrganizing && status.noteCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (isOrganizing) "整理中" else "AI整理")
                }
                Button(
                    onClick = onSubmitAll,
                    enabled = !isSubmitting && status.pendingDraftCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (isSubmitting) "提交中" else "提交入库")
                }
            }
            OutlinedButton(
                onClick = onRetryIndex,
                enabled = status.failedIndexCount > 0 || status.knowledgeEntryCount > status.indexedEntryCount,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "重试向量入库")
            }
        }
    }
}

@Composable
private fun KnowledgeDraftCard(
    draft: KnowledgeEntryDraft,
    isSubmitting: Boolean,
    onSubmit: () -> Unit
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
                text = "来源 ${draft.sourceNoteIds.size} 条 · ${draft.intentType.label()} · 置信度 ${"%.2f".format(draft.confidence)}",
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
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onSubmit,
                    enabled = !isSubmitting
                ) {
                    Text(text = "确认提交")
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSearchCard(
    entryCount: Int,
    embeddingCount: Int,
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
                text = "本地向量检索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "知识条目 $entryCount 条 · 本地向量 $embeddingCount 条",
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
                enabled = query.isNotBlank() && embeddingCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "语义检索")
            }
        }
    }
}

@Composable
private fun KnowledgeResultCard(
    result: SemanticSearchResult,
    onCopy: () -> Unit
) {
    KnowledgeEntryCard(
        entry = result.entry,
        leadingText = "相似度 ${"%.3f".format(result.score)}",
        onCopy = onCopy
    )
}

@Composable
private fun KnowledgeEntryCard(
    entry: KnowledgeEntry,
    leadingText: String? = null,
    onCopy: () -> Unit
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
                text = "${entry.intentType.label()} · ${entry.confirmedDate} · ${entry.indexStatus.label()}",
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

private fun DayKnowledgeStatus.statusText(): String {
    return when {
        noteCount == 0 -> "未收纳"
        pendingDraftCount > 0 -> "草稿待确认"
        failedIndexCount > 0 -> "部分失败，可重试"
        isComplete -> "已完成入库"
        knowledgeEntryCount > indexedEntryCount -> "向量入库中或待重试"
        draftCount > 0 -> "草稿已处理"
        pendingNoteCount > 0 -> "已保存，待整理"
        else -> "未整理"
    }
}

private fun IntentType.label(): String {
    return when (this) {
        IntentType.READ_LATER -> "稍后看"
        IntentType.QUOTABLE -> "可引用"
        IntentType.INSPIRATION -> "灵感"
    }
}

private fun com.nazhi.app.core.model.KnowledgeIndexStatus.label(): String {
    return when (this) {
        com.nazhi.app.core.model.KnowledgeIndexStatus.PENDING -> "待索引"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXING -> "索引中"
        com.nazhi.app.core.model.KnowledgeIndexStatus.INDEXED -> "已索引"
        com.nazhi.app.core.model.KnowledgeIndexStatus.FAILED -> "索引失败"
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

