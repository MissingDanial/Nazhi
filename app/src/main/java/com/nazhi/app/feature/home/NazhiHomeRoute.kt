package com.nazhi.app.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nazhi.app.R
import com.nazhi.app.core.auth.AuthSessionEvent
import com.nazhi.app.core.auth.AuthSessionStore
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.network.AuthLoginRequest
import com.nazhi.app.core.network.AuthRegisterRequest
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore
import com.nazhi.app.core.ui.NazhiTokens
import com.nazhi.app.feature.calendar.CalendarRoute
import com.nazhi.app.feature.chat.KnowledgeChatRoute
import com.nazhi.app.feature.inbox.InboxRoute
import com.nazhi.app.feature.knowledge.KnowledgeRoute
import com.nazhi.app.feature.settings.LoginDialog
import com.nazhi.app.feature.settings.RegisterDialog
import com.nazhi.app.feature.settings.SettingsRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    authSessionStore: AuthSessionStore,
    backendClient: NazhiBackendClient,
    knowledgeIngestionCoordinator: KnowledgeIngestionCoordinator,
    knowledgeChatCoordinator: KnowledgeChatCoordinator,
    initialShareText: String? = null,
    initialShareSource: String? = null,
    onShareConsumed: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(MainTab.TODAY) }
    val authSession by authSessionStore.session.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var hasCheckedAuthSession by remember { mutableStateOf(false) }
    var hasDismissedStartupAuth by remember { mutableStateOf(false) }
    var isAuthSubmitting by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showRegisterDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authSessionStore.session.first()
        hasCheckedAuthSession = true
    }

    LaunchedEffect(Unit) {
        authSessionStore.events.collect { event ->
            when (event) {
                AuthSessionEvent.SessionExpired -> {
                    hasDismissedStartupAuth = false
                    showRegisterDialog = false
                    showLoginDialog = true
                    snackbarHostState.showSnackbar("登录已过期，请重新登录")
                }
            }
        }
    }

    LaunchedEffect(initialShareText) {
        if (!initialShareText.isNullOrBlank()) {
            selectedTab = MainTab.TODAY
        }
    }

    LaunchedEffect(hasCheckedAuthSession, authSession, hasDismissedStartupAuth, initialShareText) {
        if (authSession != null) {
            showLoginDialog = false
            showRegisterDialog = false
            return@LaunchedEffect
        }
        if (
            hasCheckedAuthSession &&
            !hasDismissedStartupAuth &&
            initialShareText.isNullOrBlank()
        ) {
            showLoginDialog = true
        }
    }

    Scaffold(
        containerColor = NazhiTokens.colors.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = NazhiTokens.colors.navigationBar,
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == MainTab.TODAY,
                    onClick = { selectedTab = MainTab.TODAY },
                    icon = { NavIcon(drawableId = R.drawable.nav_today) },
                    label = { Text(text = "今日") },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.CALENDAR,
                    onClick = { selectedTab = MainTab.CALENDAR },
                    icon = { NavIcon(drawableId = R.drawable.nav_calendar) },
                    label = { Text(text = "日历") },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.CHAT,
                    onClick = { selectedTab = MainTab.CHAT },
                    icon = { NavIcon(drawableId = R.drawable.nav_chat) },
                    label = { Text(text = "问答") },
                    colors = navItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.KNOWLEDGE,
                    onClick = { selectedTab = MainTab.KNOWLEDGE },
                    icon = { NavIcon(drawableId = R.drawable.nav_knowledge) },
                    label = { Text(text = "知识库") },
                    colors = navItemColors()
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
                    authSessionStore = authSessionStore,
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

    if (showLoginDialog) {
        LoginDialog(
            isSubmitting = isAuthSubmitting,
            onDismiss = {
                hasDismissedStartupAuth = true
                showLoginDialog = false
            },
            onConfirm = { email, password ->
                coroutineScope.launch {
                    isAuthSubmitting = true
                    val message = runCatching {
                        val response = backendClient.login(AuthLoginRequest(email = email, password = password))
                        authSessionStore.save(response)
                        hasDismissedStartupAuth = true
                        showLoginDialog = false
                        "登录成功"
                    }.getOrElse { error ->
                        "登录失败：${error.toStartupAuthMessage()}"
                    }
                    isAuthSubmitting = false
                    snackbarHostState.showSnackbar(message)
                }
            },
            onOpenRegister = {
                hasDismissedStartupAuth = true
                showLoginDialog = false
                showRegisterDialog = true
            }
        )
    }

    if (showRegisterDialog) {
        RegisterDialog(
            isSubmitting = isAuthSubmitting,
            onDismiss = {
                hasDismissedStartupAuth = true
                showRegisterDialog = false
            },
            onConfirm = { email, username, password ->
                coroutineScope.launch {
                    isAuthSubmitting = true
                    val message = runCatching {
                        val response = backendClient.register(
                            AuthRegisterRequest(
                                email = email,
                                username = username,
                                password = password
                            )
                        )
                        authSessionStore.save(response)
                        hasDismissedStartupAuth = true
                        showRegisterDialog = false
                        "注册成功"
                    }.getOrElse { error ->
                        "注册失败：${error.toStartupAuthMessage()}"
                    }
                    isAuthSubmitting = false
                    snackbarHostState.showSnackbar(message)
                }
            },
            onOpenLogin = {
                showRegisterDialog = false
                showLoginDialog = true
            }
        )
    }
}

private fun Throwable.toStartupAuthMessage(): String {
    return when (this) {
        is NazhiBackendException -> publicMessage
        else -> message ?: "请检查账号信息、网络或后端服务。"
    }
}

@Composable
private fun SettingsGearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(50.dp)
            .alpha(0.96f)
    ) {
        Image(
            painter = painterResource(id = R.drawable.settings_gear_bg),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit
        )
        IconButton(onClick = onClick) {
            Text(
                text = "⚙",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun NavIcon(drawableId: Int) {
    Image(
        painter = painterResource(id = drawableId),
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = NazhiTokens.colors.grassDark,
    selectedTextColor = NazhiTokens.colors.grassDark,
    indicatorColor = NazhiTokens.colors.grassSoft,
    unselectedIconColor = NazhiTokens.colors.textSecondary,
    unselectedTextColor = NazhiTokens.colors.textSecondary
)
