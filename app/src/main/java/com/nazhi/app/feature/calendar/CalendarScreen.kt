package com.nazhi.app.feature.calendar

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
fun CalendarRoute(repository: NazhiRepository) {
    var visibleMonthDate by remember { mutableStateOf(todayDateId()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    val startDate = remember(visibleMonthDate) { monthStartDateId(visibleMonthDate) }
    val endDate = remember(visibleMonthDate) { nextMonthStartDateId(visibleMonthDate) }
    val daySummaries by remember(repository, startDate, endDate) {
        repository.observeDaySummaries(startDate, endDate)
    }.collectAsState(initial = emptyList())

    val date = selectedDate
    if (date != null) {
        DateNotesRoute(
            repository = repository,
            dateId = date,
            screenTitle = "日历",
            screenSubtitle = displayDateLabel(date),
            summaryLabel = "当天记录",
            reviewTitle = "日期回顾",
            showQuickInput = false,
            onNavigateBack = { selectedDate = null }
        )
    } else {
        CalendarScreen(
            visibleMonthDate = visibleMonthDate,
            daySummaries = daySummaries,
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
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onDateClick: (String) -> Unit
) {
    val summaryByDate = daySummaries.associateBy { it.date }

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
                    onDateClick = onDateClick
                )
            }
            item {
                MonthSummary(
                    daySummaries = daySummaries,
                    onDateClick = onDateClick
                )
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
                                isToday = dateId == todayDateId(),
                                onClick = { onDateClick(dateId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
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
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPending = (summary?.pendingCount ?: 0) > 0
    val hasRecord = summary != null && summary.totalCount > 0
    val containerColor = when {
        hasPending -> MaterialTheme.colorScheme.tertiaryContainer
        isToday -> MaterialTheme.colorScheme.primaryContainer
        hasRecord -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .height(76.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor)
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
            if (summary != null) {
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
private fun MonthSummary(
    daySummaries: List<DaySummary>,
    onDateClick: (String) -> Unit
) {
    if (daySummaries.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
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
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "本月记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            daySummaries.forEach { summary ->
                DaySummaryCard(
                    summary = summary,
                    onClick = { onDateClick(summary.date) }
                )
            }
        }
    }
}

@Composable
private fun DaySummaryCard(
    summary: DaySummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = displayDateLabel(summary.date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "保存 ${summary.totalCount} 条 · 待回顾 ${summary.pendingCount} 条 · 已沉淀 ${summary.reviewedCount} 条",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (summary.pendingCount > 0) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "继续回顾这一天")
                }
            }
        }
    }
}
