package com.nazhi.app.core.model

data class DailyFarmSnapshot(
    val dateId: String,
    val seedCount: Int,
    val sproutCount: Int,
    val plantCount: Int,
    val treeCount: Int,
    val wordScore: Int,
    val audioCount: Int,
    val indexedCount: Int,
    val failedCount: Int,
    val maturityScore: Int,
    val dominantSource: String?,
    val themeLevel: Int,
    val ruleVersion: Int,
    val updatedAt: Long
)

