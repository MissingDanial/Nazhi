package com.nazhi.app.core.model

data class DailyFarmSnapshot(
    val dateId: String,
    val saplingCount: Int,
    val plantCount: Int,
    val matureCount: Int,
    val wordScore: Int,
    val issueCount: Int,
    val maturityScore: Int,
    val dominantSource: String?,
    val themeLevel: Int,
    val ruleVersion: Int,
    val updatedAt: Long
)
