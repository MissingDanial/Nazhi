package com.nazhi.app.core.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note

@Composable
fun EditableKnowledgeEntryDialog(
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
                modifier = Modifier
                    .heightIn(max = 520.dp)
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
                    text = if (isSaving) "正在保存并更新问答能力" else "保存后会自动更新该条目的问答能力。",
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
fun EditableSourceNoteDialog(
    note: Note,
    onDismiss: () -> Unit,
    onConfirm: (content: String, remark: String) -> Unit
) {
    var content by remember(note.id) { mutableStateOf(note.content) }
    var remark by remember(note.id) { mutableStateOf(note.userRemark.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑原始 Note") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text(text = "正文") }
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text(text = "备注，可选") }
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

private fun String.toTagList(): List<String> {
    return split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(8)
}
