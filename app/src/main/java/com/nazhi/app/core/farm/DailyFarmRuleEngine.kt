package com.nazhi.app.core.farm

import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.DailyFarmSnapshot
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType

private const val TEXT_UNIT_SIZE = 500
private const val MAX_UNITS_PER_ITEM = 6
private const val MAX_VISIBLE_UNITS = 36

object DailyFarmRuleEngine {
    const val RULE_VERSION = 2

    fun buildSnapshot(
        dateId: String,
        notes: List<Note>,
        drafts: List<KnowledgeEntryDraft>,
        knowledgeEntries: List<KnowledgeEntry>,
        knowledgeStatus: DayKnowledgeStatus,
        audioJobs: List<AudioTranscriptionJob>,
        updatedAt: Long = System.currentTimeMillis()
    ): DailyFarmSnapshot {
        val wordScore = notes.sumOf { it.content.length }
        val failedAudioCount = audioJobs.count { it.status == AudioTranscriptionJobStatus.FAILED }
        val saplingCount = notes
            .filter { it.status == NoteStatus.INBOX }
            .sumOf { it.content.toFarmUnitCount() }
            .coerceAtMost(MAX_VISIBLE_UNITS)
        val plantCount = drafts
            .filter { it.status == KnowledgeDraftStatus.PENDING }
            .sumOf { it.content.toFarmUnitCount() }
            .coerceAtMost(MAX_VISIBLE_UNITS)
        val matureCount = knowledgeEntries
            .filter { it.indexStatus == KnowledgeIndexStatus.INDEXED }
            .sumOf { it.content.toFarmUnitCount() }
            .coerceAtMost(MAX_VISIBLE_UNITS)
        val issueCount = knowledgeStatus.failedIndexCount + failedAudioCount
        val maturityScore = (
            saplingCount * 8 +
                plantCount * 18 +
                matureCount * 28
            ).coerceIn(0, 100)

        return DailyFarmSnapshot(
            dateId = dateId,
            saplingCount = saplingCount,
            plantCount = plantCount,
            matureCount = matureCount,
            wordScore = wordScore,
            issueCount = issueCount,
            maturityScore = maturityScore,
            dominantSource = notes.dominantSourceLabel(),
            themeLevel = when {
                maturityScore >= 80 -> 3
                maturityScore >= 45 -> 2
                maturityScore > 0 -> 1
                else -> 0
            },
            ruleVersion = RULE_VERSION,
            updatedAt = updatedAt
        )
    }
}

private fun String.toFarmUnitCount(): Int {
    val length = trim().length
    if (length == 0) {
        return 0
    }
    return ((length + TEXT_UNIT_SIZE - 1) / TEXT_UNIT_SIZE).coerceIn(1, MAX_UNITS_PER_ITEM)
}

private fun List<Note>.dominantSourceLabel(): String? {
    return groupBy { it.sourceType }
        .maxByOrNull { (_, notes) -> notes.size }
        ?.key
        ?.let { sourceType ->
            when (sourceType) {
                SourceType.SHARE -> "分享"
                SourceType.MANUAL -> "手动"
                SourceType.CLIPBOARD -> "剪贴板"
                SourceType.TEXT_SELECTION -> "划词"
                SourceType.AUDIO_TRANSCRIPTION -> "音频"
            }
        }
}
