package com.nazhi.app.core.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.SourceType

@Composable
fun KnowledgeEntryDetailDialog(
    entry: KnowledgeEntry,
    sourceNotes: List<Note>,
    dialogTitle: String = "知识条目详情",
    citationQuote: String? = null,
    citationReason: String? = null,
    onCopyEntry: () -> Unit,
    onCopyNote: (Note) -> Unit,
    onCopyCitation: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var copyFeedback by remember(entry.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialogTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!citationQuote.isNullOrBlank() || !citationReason.isNullOrBlank()) {
                    citationQuote?.takeIf { it.isNotBlank() }?.let { quote ->
                        Text(
                            text = "引用短句",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "“$quote”",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    citationReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            text = "引用理由",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HorizontalDivider()
                    if (onCopyCitation != null) {
                        OutlinedButton(
                            onClick = {
                                onCopyCitation()
                                copyFeedback = "已复制引用信息"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "复制引用信息")
                        }
                    }
                    Text(
                        text = "对应知识",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = entry.userTitle?.takeIf { it.isNotBlank() }
                        ?: entry.content.lineSequence().firstOrNull().orEmpty().ifBlank { "未命名知识" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${entry.intentType.label()} · ${entry.confirmedDate} · ${entry.indexStatus.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "来源 ${sourceNotes.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        onCopyEntry()
                        copyFeedback = "已复制知识条目"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "复制知识条目")
                }
                copyFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (entry.summary.isNotBlank()) {
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (entry.tags.isNotEmpty()) {
                    Text(
                        text = entry.tags.joinToString(prefix = "标签："),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.userRemark?.takeIf { it.isNotBlank() }?.let { remark ->
                    Text(
                        text = "备注：$remark",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                Text(
                    text = "原始 Note",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (sourceNotes.isEmpty()) {
                    Text(text = "没有找到对应的原始 Note。")
                } else {
                    sourceNotes.forEachIndexed { index, note ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${index + 1}. ${note.title ?: "未命名记录"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${note.sourceType.label()} · ${note.createdDate}",
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
                            TextButton(
                                onClick = {
                                    onCopyNote(note)
                                    copyFeedback = "已复制第 ${index + 1} 条原始 Note"
                                }
                            ) {
                                Text(text = "复制这条 Note")
                            }
                        }
                        if (index != sourceNotes.lastIndex) {
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

private fun IntentType.label(): String {
    return when (this) {
        IntentType.READ_LATER -> "稍后看"
        IntentType.QUOTABLE -> "可引用"
        IntentType.INSPIRATION -> "灵感"
    }
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

private fun KnowledgeIndexStatus.label(): String {
    return when (this) {
        KnowledgeIndexStatus.PENDING -> "待沉淀"
        KnowledgeIndexStatus.INDEXING -> "沉淀中"
        KnowledgeIndexStatus.INDEXED -> "已沉淀"
        KnowledgeIndexStatus.FAILED -> "沉淀失败"
    }
}
