package com.nazhi.app.core.util

fun String.toNazhiTitle(): String {
    val compact = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: "未命名记录"
    return compact.take(32)
}

fun String.extractFirstUrl(): String? {
    val pattern = Regex("""https?://\S+""")
    return pattern.find(this)?.value
}
