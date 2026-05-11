package com.nazhi.app.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.feature.calendar.CalendarRoute
import com.nazhi.app.feature.inbox.InboxRoute

private enum class MainTab {
    TODAY,
    CALENDAR
}

@Composable
fun NazhiHomeRoute(
    repository: NazhiRepository,
    initialShareText: String? = null,
    initialShareSource: String? = null,
    onShareConsumed: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(MainTab.TODAY) }

    LaunchedEffect(initialShareText) {
        if (!initialShareText.isNullOrBlank()) {
            selectedTab = MainTab.TODAY
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == MainTab.TODAY,
                    onClick = { selectedTab = MainTab.TODAY },
                    icon = { Text(text = "今") },
                    label = { Text(text = "今日") }
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.CALENDAR,
                    onClick = { selectedTab = MainTab.CALENDAR },
                    icon = { Text(text = "历") },
                    label = { Text(text = "日历") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.TODAY -> InboxRoute(
                    repository = repository,
                    initialShareText = initialShareText,
                    initialShareSource = initialShareSource,
                    onShareConsumed = onShareConsumed,
                    onOpenCalendar = { selectedTab = MainTab.CALENDAR }
                )

                MainTab.CALENDAR -> CalendarRoute(repository = repository)
            }
        }
    }
}
