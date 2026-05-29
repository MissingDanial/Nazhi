package com.nazhi.app.core.model

data class CalendarFarmMarker(
    val date: String,
    val saplingCount: Int,
    val plantCount: Int,
    val matureCount: Int,
    val issueCount: Int,
    val maturityScore: Int
) {
    val hasFarmData: Boolean
        get() = saplingCount > 0 || plantCount > 0 || matureCount > 0 || issueCount > 0

    val hasPendingWork: Boolean
        get() = saplingCount > 0 || plantCount > 0 || issueCount > 0
}

data class CalendarNoteFarmSummary(
    val date: String,
    val pendingNoteCount: Int,
    val reviewedNoteCount: Int,
    val saplingUnits: Int,
    val wordScore: Int
)

data class CalendarDraftFarmSummary(
    val date: String,
    val pendingDraftCount: Int,
    val plantUnits: Int
)

data class CalendarKnowledgeFarmSummary(
    val date: String,
    val indexedEntryCount: Int,
    val matureUnits: Int,
    val failedIndexCount: Int
)
