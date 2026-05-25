package com.nazhi.app.core.farm

import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.DailyFarmSnapshot
import com.nazhi.app.core.model.DayKnowledgeStatus
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.model.SourceType

object DailyFarmRuleEngine {
    const val RULE_VERSION = 1

    fun buildSnapshot(
        dateId: String,
        notes: List<Note>,
        knowledgeStatus: DayKnowledgeStatus,
        audioJobs: List<AudioTranscriptionJob>,
        updatedAt: Long = System.currentTimeMillis()
    ): DailyFarmSnapshot {
        val wordScore = notes.sumOf { it.content.length }
        val audioCount = notes.count { it.sourceType == SourceType.AUDIO_TRANSCRIPTION }
        val failedAudioCount = audioJobs.count { it.status == AudioTranscriptionJobStatus.FAILED }
        val indexedCount = knowledgeStatus.indexedEntryCount
        val plantCount = knowledgeStatus.knowledgeEntryCount
        val treeCount = ((wordScore / 1200) + (indexedCount / 4)).coerceAtMost(8)
        val maturityScore = (
            knowledgeStatus.pendingNoteCount * 8 +
                knowledgeStatus.pendingDraftCount * 18 +
                plantCount * 28 +
                indexedCount * 12 +
                audioCount * 6 -
                (knowledgeStatus.failedIndexCount + failedAudioCount) * 10
            ).coerceIn(0, 100)

        return DailyFarmSnapshot(
            dateId = dateId,
            seedCount = knowledgeStatus.pendingNoteCount,
            sproutCount = knowledgeStatus.pendingDraftCount,
            plantCount = plantCount,
            treeCount = treeCount,
            wordScore = wordScore,
            audioCount = audioCount,
            indexedCount = indexedCount,
            failedCount = knowledgeStatus.failedIndexCount + failedAudioCount,
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

