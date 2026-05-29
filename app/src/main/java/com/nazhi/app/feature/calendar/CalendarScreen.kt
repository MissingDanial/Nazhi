package com.nazhi.app.feature.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.model.CalendarFarmMarker
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.util.displayDateLabel
import com.nazhi.app.core.util.localDateFromId
import com.nazhi.app.core.util.monthStartDateId
import com.nazhi.app.core.util.monthTitle
import com.nazhi.app.core.util.nextMonthDateId
import com.nazhi.app.core.util.nextMonthStartDateId
import com.nazhi.app.core.util.previousMonthDateId
import com.nazhi.app.core.util.todayDateId
import com.nazhi.app.feature.inbox.DateNotesRoute
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarRoute(
    repository: NazhiRepository,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator
) {
    var visibleMonthDate by remember { mutableStateOf(todayDateId()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    val startDate = remember(visibleMonthDate) { monthStartDateId(visibleMonthDate) }
    val endDate = remember(visibleMonthDate) { nextMonthStartDateId(visibleMonthDate) }
    val daySummaries by remember(repository, startDate, endDate) {
        repository.observeDaySummaries(startDate, endDate)
    }.collectAsState(initial = emptyList())
    val farmMarkers by remember(repository, startDate, endDate) {
        repository.observeCalendarFarmMarkers(startDate, endDate)
    }.collectAsState(initial = emptyList())

    val date = selectedDate
    if (date != null) {
        DateNotesRoute(
            repository = repository,
            knowledgeIngestionCoordinator = knowledgeIngestionCoordinator,
            dateId = date,
            screenTitle = "这一天的知识农场",
            screenSubtitle = displayDateLabel(date),
            showQuickInput = false,
            onNavigateBack = { selectedDate = null }
        )
    } else {
        CalendarScreen(
            visibleMonthDate = visibleMonthDate,
            daySummaries = daySummaries,
            farmMarkers = farmMarkers,
            onPreviousMonth = {
                visibleMonthDate = previousMonthDateId(visibleMonthDate)
            },
            onNextMonth = {
                visibleMonthDate = nextMonthDateId(visibleMonthDate)
            },
            onToday = {
                visibleMonthDate = todayDateId()
            },
            onDateClick = { selectedDate = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen(
    visibleMonthDate: String,
    daySummaries: List<DaySummary>,
    farmMarkers: List<CalendarFarmMarker>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onDateClick: (String) -> Unit
) {
    val summaryByDate = daySummaries.associateBy { it.date }
    val markerByDate = farmMarkers.associateBy { it.date }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "日历")
                        Text(
                            text = "按日期查看记录",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MonthHeader(
                    visibleMonthDate = visibleMonthDate,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onToday = onToday
                )
            }
            item {
                MonthGrid(
                    visibleMonthDate = visibleMonthDate,
                    summaryByDate = summaryByDate,
                    markerByDate = markerByDate,
                    onDateClick = onDateClick
                )
            }
            if (daySummaries.isEmpty() && markerByDate.isEmpty()) {
                item {
                    EmptyMonthCard()
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    visibleMonthDate: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPreviousMonth) {
                    Text(text = "上月")
                }
                Text(
                    text = monthTitle(visibleMonthDate),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onNextMonth) {
                    Text(text = "下月")
                }
            }
            OutlinedButton(
                onClick = onToday,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "回到本月")
            }
        }
    }
}

@Composable
private fun MonthGrid(
    visibleMonthDate: String,
    summaryByDate: Map<String, DaySummary>,
    markerByDate: Map<String, CalendarFarmMarker>,
    onDateClick: (String) -> Unit
) {
    val month = YearMonth.from(localDateFromId(visibleMonthDate))
    val firstDay = month.atDay(1)
    val leadingBlankCount = firstDay.dayOfWeek.value - 1
    val dates = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val rows = (List<LocalDate?>(leadingBlankCount) { null } + dates).chunked(7)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WeekHeader()
            rows.forEach { rowDates ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(7) { index ->
                        val date = rowDates.getOrNull(index)
                        if (date == null) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(76.dp)
                            )
                        } else {
                            val dateId = date.toString()
                            DateCell(
                                date = date,
                                summary = summaryByDate[dateId],
                                marker = markerByDate[dateId],
                                isToday = dateId == todayDateId(),
                                onClick = { onDateClick(dateId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            CalendarFarmLegend()
        }
    }
}

@Composable
private fun WeekHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DateCell(
    date: LocalDate,
    summary: DaySummary?,
    marker: CalendarFarmMarker?,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPending = (summary?.pendingCount ?: 0) > 0
    val hasRecord = summary != null && summary.totalCount > 0
    val hasFarmData = marker?.hasFarmData == true
    val containerColor = when {
        marker?.hasPendingWork == true || hasPending -> MaterialTheme.colorScheme.tertiaryContainer
        isToday -> MaterialTheme.colorScheme.primaryContainer
        marker?.matureCount.orZero() > 0 || hasRecord -> MaterialTheme.colorScheme.secondaryContainer
        hasFarmData -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val border = when {
        marker == null || !marker.hasFarmData -> null
        marker.maturityScore >= 80 -> BorderStroke(1.5f.dp, MaterialTheme.colorScheme.primary)
        marker.maturityScore >= 45 -> BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = modifier
            .height(76.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = border
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
            )
            if (marker?.hasFarmData == true) {
                CalendarFarmMarkerStrip(marker = marker)
            } else if (summary != null) {
                Text(
                    text = if (hasPending) "待 ${summary.pendingCount}" else "记 ${summary.totalCount}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun CalendarFarmMarkerStrip(marker: CalendarFarmMarker) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (marker.saplingCount > 0) {
            MiniCropMarker(stage = MiniCropStage.SAPLING, count = marker.saplingCount)
        }
        if (marker.plantCount > 0) {
            MiniCropMarker(stage = MiniCropStage.PLANT, count = marker.plantCount)
        }
        if (marker.matureCount > 0) {
            MiniCropMarker(stage = MiniCropStage.MATURE, count = marker.matureCount)
        }
        if (marker.issueCount > 0) {
            MiniIssueMarker(count = marker.issueCount)
        }
    }
}

@Composable
private fun MiniCropMarker(stage: MiniCropStage, count: Int) {
    Canvas(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(13.dp)
    ) {
        val level = when {
            count >= 8 -> 3
            count >= 3 -> 2
            else -> 1
        }
        when (stage) {
            MiniCropStage.SAPLING -> drawMiniSapling(level)
            MiniCropStage.PLANT -> drawMiniPlant(level)
            MiniCropStage.MATURE -> drawMiniMature(level)
        }
    }
}

@Composable
private fun MiniIssueMarker(count: Int) {
    Canvas(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(9.dp)
    ) {
        val radius = if (count > 1) size.minDimension * 0.45f else size.minDimension * 0.36f
        drawCircle(
            color = Color(0xFFB3261E),
            radius = radius,
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }
}

private fun DrawScope.drawMiniSapling(level: Int) {
    val base = Offset(size.width / 2f, size.height * 0.82f)
    val top = Offset(size.width / 2f, size.height * (0.34f - level * 0.03f))
    drawLine(
        color = Color(0xFF5E7F4A),
        start = base,
        end = top,
        strokeWidth = size.minDimension * 0.10f,
        cap = StrokeCap.Round
    )
    val radius = size.minDimension * (0.13f + level * 0.01f)
    drawCircle(color = Color(0xFF6EAD64), radius = radius, center = Offset(top.x - radius * 0.85f, top.y + radius * 0.35f))
    drawCircle(color = Color(0xFF83C477), radius = radius, center = Offset(top.x + radius * 0.85f, top.y + radius * 0.12f))
}

private fun DrawScope.drawMiniPlant(level: Int) {
    val base = Offset(size.width / 2f, size.height * 0.84f)
    val top = Offset(size.width / 2f, size.height * (0.24f - level * 0.025f))
    drawLine(
        color = Color(0xFF4F7742),
        start = base,
        end = top,
        strokeWidth = size.minDimension * 0.11f,
        cap = StrokeCap.Round
    )
    val radius = size.minDimension * (0.15f + level * 0.012f)
    drawCircle(color = Color(0xFF3F9160), radius = radius, center = Offset(top.x, top.y + radius * 0.12f))
    drawCircle(color = Color(0xFF69B979), radius = radius * 0.88f, center = Offset(top.x - radius * 0.92f, top.y + radius * 0.86f))
    drawCircle(color = Color(0xFF2F7D57), radius = radius * 0.88f, center = Offset(top.x + radius * 0.92f, top.y + radius * 0.64f))
}

private fun DrawScope.drawMiniMature(level: Int) {
    val base = Offset(size.width / 2f, size.height * 0.86f)
    val top = Offset(size.width / 2f, size.height * (0.22f - level * 0.02f))
    drawLine(
        color = Color(0xFF7A6A4D),
        start = base,
        end = top,
        strokeWidth = size.minDimension * 0.12f,
        cap = StrokeCap.Round
    )
    val radius = size.minDimension * (0.17f + level * 0.012f)
    drawCircle(color = Color(0xFF276E4B), radius = radius, center = top)
    drawCircle(color = Color(0xFF348B5E), radius = radius * 0.82f, center = Offset(top.x - radius * 0.78f, top.y + radius * 0.36f))
    drawCircle(color = Color(0xFF61AD70), radius = radius * 0.76f, center = Offset(top.x + radius * 0.76f, top.y + radius * 0.26f))
    if (level >= 2) {
        drawCircle(color = Color(0xFFE16B6F), radius = size.minDimension * 0.055f, center = Offset(top.x + radius * 0.24f, top.y + radius * 0.18f))
    }
}

private enum class MiniCropStage {
    SAPLING,
    PLANT,
    MATURE
}

@Composable
private fun CalendarFarmLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarFarmLegendItem(stage = MiniCropStage.SAPLING, text = "待整理")
        CalendarFarmLegendItem(stage = MiniCropStage.PLANT, text = "待确认")
        CalendarFarmLegendItem(stage = MiniCropStage.MATURE, text = "已沉淀")
    }
}

@Composable
private fun CalendarFarmLegendItem(
    stage: MiniCropStage,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniCropMarker(stage = stage, count = 1)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun Int?.orZero(): Int = this ?: 0

@Composable
private fun EmptyMonthCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "本月暂无记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "保存内容后，日历会标记对应日期。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
