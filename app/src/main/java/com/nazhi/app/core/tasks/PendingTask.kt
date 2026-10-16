package com.nazhi.app.core.tasks

data class PendingTask(
    val id: String,
    val kind: PendingTaskKind,
    val title: String,
    val date: String,
    val statusLabel: String,
    val reason: String?,
    val action: PendingTaskAction,
    val targetId: String? = null
) {
    val key: String get() = "${kind.name}:$id"
}

enum class PendingTaskKind { ORGANIZE, INDEX, CHAT }
enum class PendingTaskAction { QUERY_EXISTING, RETRY_MODEL, NONE }
