package com.nazhi.app.core.model

import java.util.Locale

private const val MIN_DUPLICATE_KEY_LENGTH = 12
private val ignoredDuplicateChars = Regex("[\\s\\p{Punct}，。！？、；：、“”‘’（）【】《》￥·—…]+")

fun String.toKnowledgeDuplicateKey(): String {
    return lowercase(Locale.ROOT)
        .replace(ignoredDuplicateChars, "")
        .take(2000)
}

fun String.isMeaningfulKnowledgeDuplicateKey(): Boolean {
    return length >= MIN_DUPLICATE_KEY_LENGTH
}

fun KnowledgeEntry.knowledgeDuplicateKey(): String {
    return content.toKnowledgeDuplicateKey()
}

fun KnowledgeEntryDraft.knowledgeDuplicateKey(): String {
    return content.toKnowledgeDuplicateKey()
}

fun KnowledgeEntryDraft.findDuplicateEntry(entries: List<KnowledgeEntry>): KnowledgeEntry? {
    val draftKey = knowledgeDuplicateKey()
    val draftSourceIds = sourceNoteIds.toSet()
    return entries.firstOrNull { entry ->
        val entryKey = entry.knowledgeDuplicateKey()
        val hasSameContent = draftKey.isMeaningfulKnowledgeDuplicateKey() &&
            entryKey.isMeaningfulKnowledgeDuplicateKey() &&
            draftKey == entryKey
        val hasSameSource = draftSourceIds.isNotEmpty() &&
            entry.sourceNoteIds.any { it in draftSourceIds }
        hasSameContent || hasSameSource
    }
}
