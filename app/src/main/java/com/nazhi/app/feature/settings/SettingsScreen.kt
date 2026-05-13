package com.nazhi.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.network.BackendConfig
import com.nazhi.app.core.network.BackendHealthResponse
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.settings.BackendSettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    backendSettingsStore: BackendSettingsStore,
    backendClient: NazhiBackendClient
) {
    val savedConfig by backendSettingsStore.settings.collectAsState(
        initial = backendSettingsStore.defaultConfig
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(savedConfig.baseUrl) }
    var devToken by remember { mutableStateOf(savedConfig.devToken) }
    var isDirty by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<ConnectionResult?>(null) }

    LaunchedEffect(savedConfig) {
        if (!isDirty) {
            baseUrl = savedConfig.baseUrl
            devToken = savedConfig.devToken
        }
    }

    SettingsScreen(
        savedConfig = savedConfig,
        baseUrl = baseUrl,
        devToken = devToken,
        isDirty = isDirty,
        isTesting = isTesting,
        connectionResult = connectionResult,
        snackbarHostState = snackbarHostState,
        onBaseUrlChange = {
            baseUrl = it
            isDirty = true
            connectionResult = null
        },
        onDevTokenChange = {
            devToken = it
            isDirty = true
            connectionResult = null
        },
        onSave = {
            val config = BackendConfig(baseUrl = baseUrl, devToken = devToken)
            coroutineScope.launch {
                backendSettingsStore.save(config)
                isDirty = false
                snackbarHostState.showSnackbar("后端配置已保存")
            }
        },
        onTestConnection = {
            val config = BackendConfig(baseUrl = baseUrl, devToken = devToken)
            coroutineScope.launch {
                isTesting = true
                val result = runCatching {
                    ConnectionResult.Success(backendClient.checkHealth(config))
                }.getOrElse { error ->
                    ConnectionResult.Failure(error.toUserMessage())
                }
                connectionResult = result
                isTesting = false
                snackbarHostState.showSnackbar(
                    if (result is ConnectionResult.Success) {
                        "后端连接可用"
                    } else {
                        "后端连接失败"
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    savedConfig: BackendConfig,
    baseUrl: String,
    devToken: String,
    isDirty: Boolean,
    isTesting: Boolean,
    connectionResult: ConnectionResult?,
    snackbarHostState: SnackbarHostState,
    onBaseUrlChange: (String) -> Unit,
    onDevTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    val canUseConfig = baseUrl.isValidBackendUrl()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "设置")
                        Text(
                            text = "后端连接与本地配置",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BackendStatusCard(
                    savedConfig = savedConfig,
                    connectionResult = connectionResult
                )
            }
            item {
                BackendConfigCard(
                    baseUrl = baseUrl,
                    devToken = devToken,
                    isDirty = isDirty,
                    isTesting = isTesting,
                    canUseConfig = canUseConfig,
                    onBaseUrlChange = onBaseUrlChange,
                    onDevTokenChange = onDevTokenChange,
                    onSave = onSave,
                    onTestConnection = onTestConnection
                )
            }
            item {
                BackendSecurityNoteCard()
            }
        }
    }
}

@Composable
private fun BackendStatusCard(
    savedConfig: BackendConfig,
    connectionResult: ConnectionResult?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "当前后端",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = savedConfig.normalizedBaseUrl.ifBlank { "未配置" },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (savedConfig.devToken.isBlank()) "Token 未填写" else "Token 已填写",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            when (connectionResult) {
                is ConnectionResult.Success -> HealthResultView(connectionResult.health)
                is ConnectionResult.Failure -> Text(
                    text = connectionResult.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                null -> Text(
                    text = "尚未测试连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HealthResultView(health: BackendHealthResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (health.ok) "服务可用" else "服务返回异常",
            style = MaterialTheme.typography.bodyMedium,
            color = if (health.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = "service: ${health.service}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "embedding: ${health.embeddingProvider}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "chat: ${health.chatProvider}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BackendConfigCard(
    baseUrl: String,
    devToken: String,
    isDirty: Boolean,
    isTesting: Boolean,
    canUseConfig: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onDevTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "后端配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = baseUrl.isNotBlank() && !canUseConfig,
                label = { Text(text = "后端地址") },
                supportingText = { Text(text = "示例：http://公网IP:8787") }
            )
            OutlinedTextField(
                value = devToken,
                onValueChange = onDevTokenChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(text = "NAZHI_DEV_TOKEN") },
                supportingText = { Text(text = "用于访问你的 nazhi-backend，不是 MiniMax API Key") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = canUseConfig && isDirty,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "保存")
                }
                Button(
                    onClick = onTestConnection,
                    enabled = canUseConfig && !isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (isTesting) "测试中" else "测试连接")
                }
            }
        }
    }
}

@Composable
private fun BackendSecurityNoteCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "安全说明",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "当前测试阶段允许 HTTP 公网地址。正式测试前建议切换到 HTTPS 域名，并关闭公网直连端口。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "/health 只验证服务可达和 provider 状态，token 会在 AI 整理与 embedding 请求中校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private sealed interface ConnectionResult {
    data class Success(val health: BackendHealthResponse) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

private fun String.isValidBackendUrl(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("http://") || trimmed.startsWith("https://")
}

private fun Throwable.toUserMessage(): String {
    val rawMessage = message.orEmpty()
    return when {
        rawMessage.contains("Failed to connect", ignoreCase = true) -> {
            "无法连接后端，请检查 IP、端口、防火墙和服务器状态。"
        }
        rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true) -> {
            "连接超时，请检查服务器网络或稍后重试。"
        }
        rawMessage.contains("Cleartext", ignoreCase = true) -> {
            "HTTP 明文请求被系统拦截，请检查网络安全配置。"
        }
        rawMessage.contains("401") ||
            rawMessage.contains("UNAUTHORIZED", ignoreCase = true) -> {
            "鉴权失败，请检查 NAZHI_DEV_TOKEN。"
        }
        rawMessage.isNotBlank() -> "连接失败：$rawMessage"
        else -> "连接失败，请检查后端地址和网络。"
    }
}
