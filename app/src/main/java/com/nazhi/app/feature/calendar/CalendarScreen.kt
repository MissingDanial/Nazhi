package com.nazhi.app.feature.calendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazhi.app.R
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
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

private const val CalendarNavAssetWidth = 960f
private const val CalendarNavAssetHeight = 180f
private const val CalendarTodayButtonAssetWidth = 960f
private const val CalendarTodayButtonAssetHeight = 128f
private const val CalendarPanelAssetWidth = 960f
private const val CalendarPanelAssetHeight = 1500f
private const val CalendarDayAssetWidth = 160f
private const val CalendarDayAssetHeight = 256f
private const val CalendarGridCellCount = 42

private val CalendarNavPreviousZone = PixelSafeZone(x = 56f, y = 46f, width = 180f, height = 88f)
private val CalendarNavTitleZone = PixelSafeZone(x = 272f, y = 34f, width = 416f, height = 112f)
private val CalendarNavNextZone = PixelSafeZone(x = 724f, y = 46f, width = 180f, height = 88f)
private val CalendarTodayButtonTextZone = PixelSafeZone(x = 240f, y = 32f, width = 480f, height = 64f)
private val CalendarPanelWeekZone = PixelSafeZone(x = 48f, y = 56f, width = 864f, height = 70f)
private val CalendarPanelGridZone = PixelSafeZone(x = 48f, y = 150f, width = 864f, height = 1120f)
private val CalendarPanelLegendZone = PixelSafeZone(x = 96f, y = 1320f, width = 768f, height = 100f)
private val CalendarDayNumberZone = PixelSafeZone(x = 24f, y = 16f, width = 112f, height = 42f)
private val CalendarDayCountZone = PixelSafeZone(x = 20f, y = 190f, width = 120f, height = 44f)

private data class PixelSafeZone(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PixelAssetFrame(
            resId = R.drawable.calendar_month_nav_bg,
            assetWidth = CalendarNavAssetWidth,
            assetHeight = CalendarNavAssetHeight,
            contentDescription = "月份切换背景"
        ) {
            PixelZone(
                assetWidth = CalendarNavAssetWidth,
                assetHeight = CalendarNavAssetHeight,
                zone = CalendarNavPreviousZone
            ) {
                CalendarHeaderAction(text = "上月", onClick = onPreviousMonth)
            }
            PixelZone(
                assetWidth = CalendarNavAssetWidth,
                assetHeight = CalendarNavAssetHeight,
                zone = CalendarNavTitleZone
            ) {
                Text(
                    text = monthTitle(visibleMonthDate),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            PixelZone(
                assetWidth = CalendarNavAssetWidth,
                assetHeight = CalendarNavAssetHeight,
                zone = CalendarNavNextZone
            ) {
                CalendarHeaderAction(text = "下月", onClick = onNextMonth)
            }
        }
        PixelAssetFrame(
            resId = R.drawable.calendar_today_button_bg,
            assetWidth = CalendarTodayButtonAssetWidth,
            assetHeight = CalendarTodayButtonAssetHeight,
            contentDescription = "回到本月背景"
        ) {
            PixelZone(
                assetWidth = CalendarTodayButtonAssetWidth,
                assetHeight = CalendarTodayButtonAssetHeight,
                zone = CalendarTodayButtonTextZone
            ) {
                CalendarHeaderAction(text = "回到本月", onClick = onToday)
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
    val calendarCells = List<LocalDate?>(leadingBlankCount) { null } + dates
    val rows = (calendarCells + List(CalendarGridCellCount - calendarCells.size) { null }).chunked(7)

    PixelAssetFrame(
        resId = R.drawable.calendar_month_panel_bg,
        assetWidth = CalendarPanelAssetWidth,
        assetHeight = CalendarPanelAssetHeight,
        contentDescription = "月历主体背景"
    ) {
        PixelZone(
            assetWidth = CalendarPanelAssetWidth,
            assetHeight = CalendarPanelAssetHeight,
            zone = CalendarPanelWeekZone
        ) {
            WeekHeader()
        }
        PixelZone(
            assetWidth = CalendarPanelAssetWidth,
            assetHeight = CalendarPanelAssetHeight,
            zone = CalendarPanelGridZone
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rows.forEach { rowDates ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(7) { index ->
                            val date = rowDates.getOrNull(index)
                            if (date == null) {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            } else {
                                val dateId = date.toString()
                                DateCell(
                                    date = date,
                                    summary = summaryByDate[dateId],
                                    isToday = dateId == todayDateId(),
                                    onClick = { onDateClick(dateId) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            }
        }
        PixelZone(
            assetWidth = CalendarPanelAssetWidth,
            assetHeight = CalendarPanelAssetHeight,
            zone = CalendarPanelLegendZone
        ) {
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
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCount = summary?.totalCount ?: 0

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(calendarDayBackgroundRes(totalCount)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        PixelZone(
            assetWidth = CalendarDayAssetWidth,
            assetHeight = CalendarDayAssetHeight,
            zone = CalendarDayNumberZone
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 12.sp
                ),
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        PixelZone(
            assetWidth = CalendarDayAssetWidth,
            assetHeight = CalendarDayAssetHeight,
            zone = CalendarDayCountZone
        ) {
            if (totalCount > 0) {
                Text(
                    text = calendarCountLabel(totalCount),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 9.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
        if (isToday) {
            Image(
                painter = painterResource(R.drawable.calendar_day_today_overlay),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

@Composable
private fun CalendarFarmLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarCountLegendItem(resId = R.drawable.calendar_day_empty, text = "0条")
        CalendarCountLegendItem(resId = R.drawable.calendar_day_sprout, text = "1-10")
        CalendarCountLegendItem(resId = R.drawable.calendar_day_growing, text = "11-25")
        CalendarCountLegendItem(resId = R.drawable.calendar_day_harvest, text = "26+")
    }
}

@Composable
private fun CalendarCountLegendItem(
    resId: Int,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier.size(width = 14.dp, height = 22.dp),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun calendarDayBackgroundRes(totalCount: Int): Int {
    return when {
        totalCount <= 0 -> R.drawable.calendar_day_empty
        totalCount <= 10 -> R.drawable.calendar_day_sprout
        totalCount <= 25 -> R.drawable.calendar_day_growing
        else -> R.drawable.calendar_day_harvest
    }
}

private fun calendarCountLabel(totalCount: Int): String {
    return if (totalCount > 99) "99+" else "$totalCount 条"
}

@Composable
private fun CalendarHeaderAction(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun PixelAssetFrame(
    resId: Int,
    assetWidth: Float,
    assetHeight: Float,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(assetWidth / assetHeight)
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        content()
    }
}

@Composable
private fun BoxWithConstraintsScope.PixelZone(
    assetWidth: Float,
    assetHeight: Float,
    zone: PixelSafeZone,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .offset(
                x = maxWidth * (zone.x / assetWidth),
                y = maxHeight * (zone.y / assetHeight)
            )
            .size(
                width = maxWidth * (zone.width / assetWidth),
                height = maxHeight * (zone.height / assetHeight)
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}
