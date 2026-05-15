package com.nazhi.app.core.repository

class DuplicateKnowledgeEntryException(
    duplicateTitle: String?
) : IllegalStateException(
    "已存在相同或同源知识：${duplicateTitle?.takeIf { it.isNotBlank() } ?: "未命名知识"}，已跳过重复草稿。"
)
