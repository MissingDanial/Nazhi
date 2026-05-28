package com.nazhi.app.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore
import com.nazhi.app.feature.calendar.CalendarRoute
import com.nazhi.app.feature.chat.KnowledgeChatRoute
import com.nazhi.app.feature.inbox.InboxRoute
import com.nazhi.app.feature.knowledge.KnowledgeRoute
import com.nazhi.app.feature.settings.SettingsRoute

private enum class MainTab {
    TODAY,
    CALENDAR,
    CHAT,
    KNOWLEDGE,
    SETTINGS
}

@Composable
fun NazhiHomeRoute(
    repository: NazhiRepository,
    backendSettingsStore: BackendSettingsStore,
    backendClient: NazhiBackendClient,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator,
    knowledgeChatCoordinator: KnowledgeChatCoordinator,
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
                NavigationBarItem(
                    selected = selectedTab == MainTab.CHAT,
                    onClick = { selectedTab = MainTab.CHAT },
                    icon = { Text(text = "问") },
                    label = { Text(text = "问答") }
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.KNOWLEDGE,
                    onClick = { selectedTab = MainTab.KNOWLEDGE },
                    icon = { Text(text = "知") },
                    label = { Text(text = "知识库") }
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
                    knowledgeIngestionCoordinator = knowledgeIngestionCoordinator,
                    initialShareText = initialShareText,
                    initialShareSource = initialShareSource,
                    onShareConsumed = onShareConsumed,
                    onOpenCalendar = { selectedTab = MainTab.CALENDAR },
                    onOpenKnowledge = { selectedTab = MainTab.KNOWLEDGE }
                )

                MainTab.CALENDAR -> CalendarRoute(
                    repository = repository,
                    knowledgeIngestionCoordinator = knowledgeIngestionCoordinator
                )

                MainTab.CHAT -> KnowledgeChatRoute(
                    repository = repository,
                    knowledgeChatCoordinator = knowledgeChatCoordinator
                )

                MainTab.KNOWLEDGE -> KnowledgeRoute(
                    repository = repository,
                    knowledgeIngestionCoordinator = knowledgeIngestionCoordinator
                )

                MainTab.SETTINGS -> SettingsRoute(
                    repository = repository,
                    backendSettingsStore = backendSettingsStore,
                    backendClient = backendClient
                )
            }

            if (selectedTab != MainTab.SETTINGS) {
                SettingsGearButton(
                    onClick = { selectedTab = MainTab.SETTINGS },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .zIndex(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingsGearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .alpha(0.94f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        IconButton(onClick = onClick) {
            Text(
                text = "⚙",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
