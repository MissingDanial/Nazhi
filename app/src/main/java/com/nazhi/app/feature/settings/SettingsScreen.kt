package com.nazhi.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.network.AiServiceMode
import com.nazhi.app.core.network.AiVendor
import com.nazhi.app.core.network.BackendAuthCheckResponse
import com.nazhi.app.core.network.BackendConfig
import com.nazhi.app.core.network.BackendHealthResponse
import com.nazhi.app.core.network.DirectApiCheckResponse
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.export.LocalDataImportPreview
import com.nazhi.app.core.export.LocalDataImportResult
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore
import com.nazhi.app.FloatingCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
    repository: NazhiRepository,
    backendSettingsStore: BackendSettingsStore,
    backendClient: NazhiBackendClient
) {
    val savedConfig by backendSettingsStore.settings.collectAsState(
        initial = backendSettingsStore.defaultConfig
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(context.hasOverlayPermission()) }
    var isFloatingCaptureRunning by remember { mutableStateOf(FloatingCaptureService.isRunning) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = context.hasOverlayPermission()
        isFloatingCaptureRunning = FloatingCaptureService.isRunning
    }
    var pendingExportText by remember { mutableStateOf<String?>(null) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<LocalDataImportPreview?>(null) }
    var importResult by remember { mutableStateOf<LocalDataImportResult?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var isReindexingAfterImport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val exportText = pendingExportText
        if (uri == null || exportText == null) {
            pendingExportText = null
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    context.writeTextToUri(uri, exportText)
                }
                "本地数据已导出"
            }.getOrElse { error ->
                "导出失败：${error.message ?: "无法写入文件"}"
            }
            pendingExportText = null
            snackbarHostState.showSnackbar(message)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            val importText = runCatching {
                withContext(Dispatchers.IO) {
                    context.readTextFromUri(uri)
                }
            }.getOrElse { error ->
                snackbarHostState.showSnackbar("导入失败：${error.message ?: "无法读取文件"}")
                return@launch
            }
            val preview = runCatching {
                repository.previewLocalDataImportJson(importText)
            }.getOrElse { error ->
                snackbarHostState.showSnackbar("导入失败：${error.message ?: "文件格式不支持"}")
                return@launch
            }
            pendingImportText = importText
            importPreview = preview
        }
    }
    var baseUrl by remember { mutableStateOf(savedConfig.baseUrl) }
    var devToken by remember { mutableStateOf(savedConfig.devToken) }
    var serviceMode by remember { mutableStateOf(savedConfig.serviceMode) }
    var vendor by remember { mutableStateOf(savedConfig.vendor) }
    var directApiBaseUrl by remember { mutableStateOf(savedConfig.directApiBaseUrl) }
    var directApiKey by remember { mutableStateOf(savedConfig.directApiKey) }
    var directChatModel by remember { mutableStateOf(savedConfig.directChatModel) }
    var directEmbeddingApiBaseUrl by remember { mutableStateOf(savedConfig.directEmbeddingApiBaseUrl) }
    var directEmbeddingApiKey by remember { mutableStateOf(savedConfig.directEmbeddingApiKey) }
    var directEmbeddingModel by remember { mutableStateOf(savedConfig.directEmbeddingModel) }
    var directExtraId by remember { mutableStateOf(savedConfig.directExtraId) }
    var isDirty by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<ConnectionResult?>(null) }
    var showClearDirectApiDialog by remember { mutableStateOf(false) }

    LaunchedEffect(savedConfig) {
        if (!isDirty) {
            baseUrl = savedConfig.baseUrl
            devToken = savedConfig.devToken
            serviceMode = savedConfig.serviceMode
            vendor = savedConfig.vendor
            directApiBaseUrl = savedConfig.directApiBaseUrl
            directApiKey = savedConfig.directApiKey
            directChatModel = savedConfig.directChatModel
            directEmbeddingApiBaseUrl = savedConfig.directEmbeddingApiBaseUrl
            directEmbeddingApiKey = savedConfig.directEmbeddingApiKey
            directEmbeddingModel = savedConfig.directEmbeddingModel
            directExtraId = savedConfig.directExtraId
        }
    }

    SettingsScreen(
        savedConfig = savedConfig,
        baseUrl = baseUrl,
        devToken = devToken,
        serviceMode = serviceMode,
        vendor = vendor,
        directApiBaseUrl = directApiBaseUrl,
        directApiKey = directApiKey,
        directChatModel = directChatModel,
        directEmbeddingApiBaseUrl = directEmbeddingApiBaseUrl,
        directEmbeddingApiKey = directEmbeddingApiKey,
        directEmbeddingModel = directEmbeddingModel,
        directExtraId = directExtraId,
        isDirty = isDirty,
        isTesting = isTesting,
        isImporting = isImporting,
        connectionResult = connectionResult,
        hasOverlayPermission = hasOverlayPermission,
        isFloatingCaptureRunning = isFloatingCaptureRunning,
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
        onServiceModeChange = {
            serviceMode = it
            isDirty = true
            connectionResult = null
        },
        onVendorChange = {
            vendor = it
            isDirty = true
            connectionResult = null
        },
        onDirectApiBaseUrlChange = {
            directApiBaseUrl = it
            isDirty = true
            connectionResult = null
        },
        onDirectApiKeyChange = {
            directApiKey = it
            isDirty = true
            connectionResult = null
        },
        onDirectChatModelChange = {
            directChatModel = it
            isDirty = true
            connectionResult = null
        },
        onDirectEmbeddingApiBaseUrlChange = {
            directEmbeddingApiBaseUrl = it
            isDirty = true
            connectionResult = null
        },
        onDirectEmbeddingApiKeyChange = {
            directEmbeddingApiKey = it
            isDirty = true
            connectionResult = null
        },
        onDirectEmbeddingModelChange = {
            directEmbeddingModel = it
            isDirty = true
            connectionResult = null
        },
        onDirectExtraIdChange = {
            directExtraId = it
            isDirty = true
            connectionResult = null
        },
        onClearDirectApiConfig = {
            showClearDirectApiDialog = true
        },
        onExportLocalData = {
            coroutineScope.launch {
                val exportText = runCatching {
                    repository.buildLocalDataExportJson()
                }.getOrElse { error ->
                    snackbarHostState.showSnackbar("导出失败：${error.message ?: "无法读取本地数据"}")
                    return@launch
                }
                pendingExportText = exportText
                exportLauncher.launch("nazhi-export-${System.currentTimeMillis()}.json")
            }
        },
        onImportLocalData = {
            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        },
        onRequestOverlayPermission = {
            overlayPermissionLauncher.launch(context.overlayPermissionIntent())
        },
        onStartFloatingCapture = {
            coroutineScope.launch {
                hasOverlayPermission = context.hasOverlayPermission()
                if (!hasOverlayPermission) {
                    snackbarHostState.showSnackbar("请先开启悬浮窗权限")
                    return@launch
                }
                context.startService(
                    Intent(context, FloatingCaptureService::class.java)
                        .setAction(FloatingCaptureService.ACTION_START_HIDDEN)
                )
                isFloatingCaptureRunning = true
                snackbarHostState.showSnackbar("悬浮球已开启，离开纳知后显示")
            }
        },
        onStopFloatingCapture = {
            coroutineScope.launch {
                context.stopService(Intent(context, FloatingCaptureService::class.java))
                isFloatingCaptureRunning = false
                snackbarHostState.showSnackbar("悬浮球已关闭")
            }
        },
        onSave = {
            val config = BackendConfig(
                baseUrl = baseUrl,
                devToken = devToken,
                serviceMode = serviceMode,
                vendor = vendor,
                directApiBaseUrl = directApiBaseUrl,
                directApiKey = directApiKey,
                directChatModel = directChatModel,
                directEmbeddingApiBaseUrl = directEmbeddingApiBaseUrl,
                directEmbeddingApiKey = directEmbeddingApiKey,
                directEmbeddingModel = directEmbeddingModel,
                directExtraId = directExtraId
            )
            coroutineScope.launch {
                backendSettingsStore.save(config)
                isDirty = false
                snackbarHostState.showSnackbar("AI 服务配置已保存")
            }
        },
        onTestConnection = {
            val config = BackendConfig(
                baseUrl = baseUrl,
                devToken = devToken,
                serviceMode = serviceMode,
                vendor = vendor,
                directApiBaseUrl = directApiBaseUrl,
                directApiKey = directApiKey,
                directChatModel = directChatModel,
                directEmbeddingApiBaseUrl = directEmbeddingApiBaseUrl,
                directEmbeddingApiKey = directEmbeddingApiKey,
                directEmbeddingModel = directEmbeddingModel,
                directExtraId = directExtraId
            )
            coroutineScope.launch {
                isTesting = true
                val result = if (serviceMode == AiServiceMode.NAZHI) {
                    runCatching {
                        val health = backendClient.checkHealth(config)
                        val auth = backendClient.checkAuth(config)
                        ConnectionResult.Success(health, auth)
                    }.getOrElse { error ->
                        ConnectionResult.Failure(error.toUserMessage())
                    }
                } else {
                    val validationError = validateDirectApiConfig(config)
                    if (validationError != null) {
                        ConnectionResult.Failure(validationError)
                    } else {
                        runCatching {
                            ConnectionResult.DirectApiReady(
                                apiBaseUrl = config.normalizedDirectApiBaseUrl,
                                embeddingApiBaseUrl = config.effectiveDirectEmbeddingApiBaseUrl,
                                check = backendClient.checkDirectApi(config)
                            )
                        }.getOrElse { error ->
                            ConnectionResult.Failure(error.toUserMessage())
                        }
                    }
                }
                connectionResult = result
                isTesting = false
                snackbarHostState.showSnackbar(
                    when (result) {
                        is ConnectionResult.Success -> "后端连接可用"
                        is ConnectionResult.DirectApiReady -> "API 连通性测试通过"
                        is ConnectionResult.Failure -> "配置检查失败"
                    }
                )
            }
        }
    )

    if (showClearDirectApiDialog) {
        AlertDialog(
            onDismissRequest = { showClearDirectApiDialog = false },
            title = { Text(text = "清除个人 API 配置") },
            text = { Text(text = "将清除本机保存的个人 API Key、模型名和 API 地址，不影响纳知服务配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDirectApiDialog = false
                        coroutineScope.launch {
                            backendSettingsStore.clearDirectApiConfig()
                            serviceMode = AiServiceMode.NAZHI
                            vendor = AiVendor.MINIMAX
                            directApiBaseUrl = ""
                            directApiKey = ""
                            directChatModel = ""
                            directEmbeddingApiBaseUrl = ""
                            directEmbeddingApiKey = ""
                            directEmbeddingModel = ""
                            directExtraId = ""
                            isDirty = false
                            connectionResult = null
                            snackbarHostState.showSnackbar("个人 API 配置已清除")
                        }
                    }
                ) {
                    Text(text = "清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDirectApiDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    val preview = importPreview
    val importText = pendingImportText
    if (preview != null && importText != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isImporting) {
                    pendingImportText = null
                    importPreview = null
                }
            },
            title = { Text(text = "确认导入本地数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = preview.toPreviewText())
                    preview.warnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isImporting,
                    onClick = {
                        coroutineScope.launch {
                            isImporting = true
                            val result = runCatching {
                                repository.importLocalDataJson(importText)
                            }.getOrElse { error ->
                                isImporting = false
                                snackbarHostState.showSnackbar("导入失败：${error.message ?: "无法写入本地数据库"}")
                                return@launch
                            }
                            isImporting = false
                            pendingImportText = null
                            importPreview = null
                            importResult = result
                            snackbarHostState.showSnackbar(result.toImportMessage())
                        }
                    }
                ) {
                    Text(text = if (isImporting) "导入中" else "导入")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImporting,
                    onClick = {
                        pendingImportText = null
                        importPreview = null
                    }
                ) {
                    Text(text = "取消")
                }
            }
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = {
                if (!isReindexingAfterImport) {
                    importResult = null
                }
            },
            title = { Text(text = "导入完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = result.toResultText())
                    if (result.shouldOfferIndexRebuild()) {
                        Text(
                            text = "导入的知识条目未包含本地向量，需要重建索引后才能用于语义检索和知识库问答。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (result.shouldOfferIndexRebuild()) {
                    TextButton(
                        enabled = !isReindexingAfterImport,
                        onClick = {
                            coroutineScope.launch {
                                isReindexingAfterImport = true
                                val message = runCatching {
                                    val count = repository.indexPendingKnowledgeEntries()
                                    if (count == 0) "没有完成新的索引，请检查网络、API 配置或知识库状态" else "已重建 $count 条知识索引"
                                }.getOrElse { error ->
                                    "重建索引失败：${error.toUserMessage()}"
                                }
                                isReindexingAfterImport = false
                                importResult = null
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    ) {
                        Text(text = if (isReindexingAfterImport) "重建中" else "重建索引")
                    }
                } else {
                    TextButton(onClick = { importResult = null }) {
                        Text(text = "知道了")
                    }
                }
            },
            dismissButton = {
                if (result.shouldOfferIndexRebuild()) {
                    TextButton(
                        enabled = !isReindexingAfterImport,
                        onClick = { importResult = null }
                    ) {
                        Text(text = "稍后处理")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    savedConfig: BackendConfig,
    baseUrl: String,
    devToken: String,
    serviceMode: AiServiceMode,
    vendor: AiVendor,
    directApiBaseUrl: String,
    directApiKey: String,
    directChatModel: String,
    directEmbeddingApiBaseUrl: String,
    directEmbeddingApiKey: String,
    directEmbeddingModel: String,
    directExtraId: String,
    isDirty: Boolean,
    isTesting: Boolean,
    isImporting: Boolean,
    connectionResult: ConnectionResult?,
    hasOverlayPermission: Boolean,
    isFloatingCaptureRunning: Boolean,
    snackbarHostState: SnackbarHostState,
    onBaseUrlChange: (String) -> Unit,
    onDevTokenChange: (String) -> Unit,
    onServiceModeChange: (AiServiceMode) -> Unit,
    onVendorChange: (AiVendor) -> Unit,
    onDirectApiBaseUrlChange: (String) -> Unit,
    onDirectApiKeyChange: (String) -> Unit,
    onDirectChatModelChange: (String) -> Unit,
    onDirectEmbeddingApiBaseUrlChange: (String) -> Unit,
    onDirectEmbeddingApiKeyChange: (String) -> Unit,
    onDirectEmbeddingModelChange: (String) -> Unit,
    onDirectExtraIdChange: (String) -> Unit,
    onClearDirectApiConfig: () -> Unit,
    onExportLocalData: () -> Unit,
    onImportLocalData: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartFloatingCapture: () -> Unit,
    onStopFloatingCapture: () -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    val canUseConfig = when (serviceMode) {
        AiServiceMode.NAZHI -> baseUrl.isValidBackendUrl() && devToken.isNotBlank()
        AiServiceMode.DIRECT_API -> {
            directApiBaseUrl.isValidBackendUrl() &&
                (directEmbeddingApiBaseUrl.isBlank() || directEmbeddingApiBaseUrl.isValidBackendUrl()) &&
                directApiKey.isNotBlank() &&
                directChatModel.isNotBlank() &&
                directEmbeddingModel.isNotBlank()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "设置")
                        Text(
                            text = "AI 服务、后端连接与本地配置",
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
                    serviceMode = serviceMode,
                    vendor = vendor,
                    directApiBaseUrl = directApiBaseUrl,
                    directApiKey = directApiKey,
                    directChatModel = directChatModel,
                    directEmbeddingApiBaseUrl = directEmbeddingApiBaseUrl,
                    directEmbeddingApiKey = directEmbeddingApiKey,
                    directEmbeddingModel = directEmbeddingModel,
                    directExtraId = directExtraId,
                    isDirty = isDirty,
                    isTesting = isTesting,
                    canUseConfig = canUseConfig,
                    onBaseUrlChange = onBaseUrlChange,
                    onDevTokenChange = onDevTokenChange,
                    onServiceModeChange = onServiceModeChange,
                    onVendorChange = onVendorChange,
                    onDirectApiBaseUrlChange = onDirectApiBaseUrlChange,
                    onDirectApiKeyChange = onDirectApiKeyChange,
                    onDirectChatModelChange = onDirectChatModelChange,
                    onDirectEmbeddingApiBaseUrlChange = onDirectEmbeddingApiBaseUrlChange,
                    onDirectEmbeddingApiKeyChange = onDirectEmbeddingApiKeyChange,
                    onDirectEmbeddingModelChange = onDirectEmbeddingModelChange,
                    onDirectExtraIdChange = onDirectExtraIdChange,
                    onClearDirectApiConfig = onClearDirectApiConfig,
                    onSave = onSave,
                    onTestConnection = onTestConnection
                )
            }
            item {
                BackendSecurityNoteCard()
            }
            item {
                FloatingCaptureCard(
                    hasOverlayPermission = hasOverlayPermission,
                    isRunning = isFloatingCaptureRunning,
                    onRequestPermission = onRequestOverlayPermission,
                    onStart = onStartFloatingCapture,
                    onStop = onStopFloatingCapture
                )
            }
            item {
                LocalDataExportCard(
                    isImporting = isImporting,
                    onExportLocalData = onExportLocalData,
                    onImportLocalData = onImportLocalData
                )
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
                text = "当前 AI 服务",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (savedConfig.serviceMode) {
                    AiServiceMode.NAZHI -> savedConfig.serviceMode.label()
                    AiServiceMode.DIRECT_API -> "${savedConfig.serviceMode.label()} · ${savedConfig.vendor.label()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = when (savedConfig.serviceMode) {
                    AiServiceMode.NAZHI -> savedConfig.normalizedBaseUrl.ifBlank { "未配置" }
                    AiServiceMode.DIRECT_API -> savedConfig.normalizedDirectApiBaseUrl.ifBlank { "API 地址未配置" }
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when (savedConfig.serviceMode) {
                    AiServiceMode.NAZHI -> if (savedConfig.devToken.isBlank()) "服务 Token 未填写" else "服务 Token 已填写"
                    AiServiceMode.DIRECT_API -> if (savedConfig.directApiKey.isBlank()) "API Key 未填写" else "API Key 已加密保存"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (savedConfig.serviceMode == AiServiceMode.DIRECT_API) {
                Text(
                    text = "Chat: ${savedConfig.directChatModel.ifBlank { "未配置" }} · Embedding: ${savedConfig.directEmbeddingModel.ifBlank { "未配置" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            when (connectionResult) {
                is ConnectionResult.Success -> {
                    HealthResultView(connectionResult.health)
                    AuthResultView(connectionResult.auth)
                }
                is ConnectionResult.DirectApiReady -> DirectApiReadyView(connectionResult)
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
            text = "service: ${health.service.ifBlank { "unknown" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "embedding: ${health.embeddingProvider.ifBlank { "unknown" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "chat: ${health.chatProvider.ifBlank { "unknown" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (health.asrProvider.isNotBlank()) {
            Text(
                text = "asr: ${health.asrProvider}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthResultView(auth: BackendAuthCheckResponse) {
    Text(
        text = if (auth.ok) "鉴权可用：token 已通过校验" else "鉴权返回异常",
        style = MaterialTheme.typography.bodySmall,
        color = if (auth.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun DirectApiReadyView(result: ConnectionResult.DirectApiReady) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "API 连通性可用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${result.check.vendor.label()} · Chat API: ${result.apiBaseUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Embedding API: ${result.embeddingApiBaseUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Chat: ${result.check.chatModel} · Embedding: ${result.check.embeddingModel} (${result.check.embeddingDimensions}维)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Chat 返回：${result.check.chatReplyPreview.ifBlank { "已通过" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BackendConfigCard(
    baseUrl: String,
    devToken: String,
    serviceMode: AiServiceMode,
    vendor: AiVendor,
    directApiBaseUrl: String,
    directApiKey: String,
    directChatModel: String,
    directEmbeddingApiBaseUrl: String,
    directEmbeddingApiKey: String,
    directEmbeddingModel: String,
    directExtraId: String,
    isDirty: Boolean,
    isTesting: Boolean,
    canUseConfig: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onDevTokenChange: (String) -> Unit,
    onServiceModeChange: (AiServiceMode) -> Unit,
    onVendorChange: (AiVendor) -> Unit,
    onDirectApiBaseUrlChange: (String) -> Unit,
    onDirectApiKeyChange: (String) -> Unit,
    onDirectChatModelChange: (String) -> Unit,
    onDirectEmbeddingApiBaseUrlChange: (String) -> Unit,
    onDirectEmbeddingApiKeyChange: (String) -> Unit,
    onDirectEmbeddingModelChange: (String) -> Unit,
    onDirectExtraIdChange: (String) -> Unit,
    onClearDirectApiConfig: () -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "AI 服务配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            ModeSelector(
                selected = serviceMode,
                onSelect = onServiceModeChange
            )
            if (serviceMode == AiServiceMode.NAZHI) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = baseUrl.isNotBlank() && !baseUrl.isValidBackendUrl(),
                    label = { Text(text = "纳知服务地址") },
                    supportingText = { Text(text = "测试阶段可使用 http://公网IP:8787") }
                )
                OutlinedTextField(
                    value = devToken,
                    onValueChange = onDevTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(text = "服务访问 Token") },
                    supportingText = { Text(text = "需要与服务器 NAZHI_DEV_TOKEN 保持一致") }
                )
            } else {
                VendorSelector(
                    selected = vendor,
                    onSelect = onVendorChange
                )
                DirectApiConfigFields(
                    vendor = vendor,
                    apiBaseUrl = directApiBaseUrl,
                    apiKey = directApiKey,
                    chatModel = directChatModel,
                    embeddingApiBaseUrl = directEmbeddingApiBaseUrl,
                    embeddingApiKey = directEmbeddingApiKey,
                    embeddingModel = directEmbeddingModel,
                    extraId = directExtraId,
                    onApiBaseUrlChange = onDirectApiBaseUrlChange,
                    onApiKeyChange = onDirectApiKeyChange,
                    onChatModelChange = onDirectChatModelChange,
                    onEmbeddingApiBaseUrlChange = onDirectEmbeddingApiBaseUrlChange,
                    onEmbeddingApiKeyChange = onDirectEmbeddingApiKeyChange,
                    onEmbeddingModelChange = onDirectEmbeddingModelChange,
                    onExtraIdChange = onDirectExtraIdChange
                )
                OutlinedButton(
                    onClick = onClearDirectApiConfig,
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "清除个人 API 配置")
                }
            }
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
private fun ModeSelector(
    selected: AiServiceMode,
    onSelect: (AiServiceMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "服务模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiServiceMode.entries.forEach { mode ->
                if (selected == mode) {
                    Button(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = mode.label())
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = mode.label())
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorSelector(
    selected: AiVendor,
    onSelect: (AiVendor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "模型厂商",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiVendor.entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { vendor ->
                        if (selected == vendor) {
                            Button(
                                onClick = { onSelect(vendor) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = vendor.label())
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelect(vendor) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = vendor.label())
                            }
                        }
                    }
                    if (row.size == 1) {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }
        Text(
            text = "厂商选择会决定后续直连 API 的请求适配方式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DirectApiConfigFields(
    vendor: AiVendor,
    apiBaseUrl: String,
    apiKey: String,
    chatModel: String,
    embeddingApiBaseUrl: String,
    embeddingApiKey: String,
    embeddingModel: String,
    extraId: String,
    onApiBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onChatModelChange: (String) -> Unit,
    onEmbeddingApiBaseUrlChange: (String) -> Unit,
    onEmbeddingApiKeyChange: (String) -> Unit,
    onEmbeddingModelChange: (String) -> Unit,
    onExtraIdChange: (String) -> Unit
) {
    OutlinedTextField(
        value = apiBaseUrl,
        onValueChange = onApiBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = apiBaseUrl.isNotBlank() && !apiBaseUrl.isValidBackendUrl(),
        label = { Text(text = "Chat API Base URL") },
        supportingText = { Text(text = vendor.baseUrlHint()) }
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(text = "Chat API Key") },
        supportingText = { Text(text = "保存在本机，用于直接调用你选择的会话模型服务") }
    )
    OutlinedTextField(
        value = chatModel,
        onValueChange = onChatModelChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(text = "Chat 模型") },
        supportingText = { Text(text = vendor.chatModelHint()) }
    )
    OutlinedTextField(
        value = embeddingApiBaseUrl,
        onValueChange = onEmbeddingApiBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = embeddingApiBaseUrl.isNotBlank() && !embeddingApiBaseUrl.isValidBackendUrl(),
        label = { Text(text = "Embedding API Base URL（可选）") },
        supportingText = { Text(text = "留空则复用 Chat API Base URL") }
    )
    OutlinedTextField(
        value = embeddingApiKey,
        onValueChange = onEmbeddingApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(text = "Embedding API Key（可选）") },
        supportingText = { Text(text = "留空则复用 Chat API Key；当 embedding 与 chat 套餐不同时单独填写") }
    )
    OutlinedTextField(
        value = embeddingModel,
        onValueChange = onEmbeddingModelChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(text = "Embedding 模型") },
        supportingText = { Text(text = vendor.embeddingModelHint()) }
    )
    OutlinedTextField(
        value = extraId,
        onValueChange = onExtraIdChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(text = vendor.extraIdLabel()) },
        supportingText = { Text(text = vendor.extraIdHint()) }
    )
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
                text = "自带 API Key 模式会把 API 信息保存在本机，后续由 App 直接调用模型服务。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "个人 API Key 会通过 Android Keystore 加密后保存在本机；移动端仍有泄露风险，建议只填写可随时撤销或限额的 Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "当前测试阶段允许 HTTP 公网地址。正式使用前建议切换 HTTPS 域名，并关闭公网直连端口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FloatingCaptureCard(
    hasOverlayPermission: Boolean,
    isRunning: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "悬浮球快捷收纳",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "权限：${if (hasOverlayPermission) "已开启" else "未开启"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasOverlayPermission) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Text(
                text = "状态：${if (isRunning) "运行中" else "未启动"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "点击悬浮球时读取剪贴板；不会后台监听剪贴板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasOverlayPermission) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "开启悬浮窗权限")
                }
            } else if (isRunning) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "关闭悬浮球")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "启动悬浮球")
                }
            }
        }
    }
}

@Composable
private fun LocalDataExportCard(
    isImporting: Boolean,
    onExportLocalData: () -> Unit,
    onImportLocalData: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "本地数据备份与恢复",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "导出或导入 Note、KnowledgeEntry、AI 草稿和问答记录，便于换机、备份或检查。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "导入导出都不会处理 API Key、服务 Token、后端配置或本地向量 BLOB；同 ID 数据导入时会跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onExportLocalData,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "导出本地数据")
            }
            Button(
                onClick = onImportLocalData,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isImporting) "导入中" else "导入本地数据")
            }
        }
    }
}

private sealed interface ConnectionResult {
    data class Success(
        val health: BackendHealthResponse,
        val auth: BackendAuthCheckResponse
    ) : ConnectionResult
    data class DirectApiReady(
        val apiBaseUrl: String,
        val embeddingApiBaseUrl: String,
        val check: DirectApiCheckResponse
    ) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

private fun validateDirectApiConfig(config: BackendConfig): String? {
    return when {
        !config.directApiBaseUrl.isValidBackendUrl() -> {
            "Chat API Base URL 需要以 http:// 或 https:// 开头。"
        }
        config.directEmbeddingApiBaseUrl.isNotBlank() && !config.directEmbeddingApiBaseUrl.isValidBackendUrl() -> {
            "Embedding API Base URL 需要以 http:// 或 https:// 开头，或留空复用 Chat API。"
        }
        config.directApiKey.isBlank() -> {
            "请填写 Chat API Key。"
        }
        config.directChatModel.isBlank() -> {
            "请填写 Chat 模型名称。"
        }
        config.directEmbeddingModel.isBlank() -> {
            "请填写 Embedding 模型名称。"
        }
        else -> null
    }
}

private fun AiServiceMode.label(): String {
    return when (this) {
        AiServiceMode.NAZHI -> "纳知服务"
        AiServiceMode.DIRECT_API -> "自带 API Key"
    }
}

private fun AiVendor.label(): String {
    return when (this) {
        AiVendor.MINIMAX -> "MiniMax"
        AiVendor.OPENAI_COMPATIBLE -> "OpenAI 兼容"
        AiVendor.QWEN -> "Qwen"
        AiVendor.DEEPSEEK -> "DeepSeek"
        AiVendor.CUSTOM -> "自定义"
    }
}

private fun AiVendor.baseUrlHint(): String {
    return when (this) {
        AiVendor.MINIMAX -> "MiniMax API 地址，例如 https://api.minimaxi.com/v1"
        AiVendor.OPENAI_COMPATIBLE -> "OpenAI 兼容地址，例如 https://api.openai.com/v1"
        AiVendor.QWEN -> "DashScope/OpenAI 兼容地址，例如 https://dashscope.aliyuncs.com/compatible-mode/v1"
        AiVendor.DEEPSEEK -> "DeepSeek 地址，例如 https://api.deepseek.com/v1"
        AiVendor.CUSTOM -> "填写你的模型服务 Base URL"
    }
}

private fun AiVendor.chatModelHint(): String {
    return when (this) {
        AiVendor.MINIMAX -> "例如 MiniMax-M2.7"
        AiVendor.OPENAI_COMPATIBLE -> "例如 gpt-4.1-mini 或兼容模型名"
        AiVendor.QWEN -> "例如 qwen-plus"
        AiVendor.DEEPSEEK -> "例如 deepseek-chat"
        AiVendor.CUSTOM -> "填写服务支持的 chat 模型"
    }
}

private fun AiVendor.embeddingModelHint(): String {
    return when (this) {
        AiVendor.MINIMAX -> "例如 embo-01"
        AiVendor.OPENAI_COMPATIBLE -> "例如 text-embedding-3-small"
        AiVendor.QWEN -> "例如 text-embedding-v4"
        AiVendor.DEEPSEEK -> "DeepSeek 当前常见场景需另配 embedding 兼容服务"
        AiVendor.CUSTOM -> "填写服务支持的 embedding 模型"
    }
}

private fun AiVendor.extraIdLabel(): String {
    return when (this) {
        AiVendor.MINIMAX -> "Group ID（可选）"
        else -> "附加参数（可选）"
    }
}

private fun AiVendor.extraIdHint(): String {
    return when (this) {
        AiVendor.MINIMAX -> "MiniMax 如需 groupId 可填这里"
        else -> "预留给 endpoint、tenant、organization 等额外配置"
    }
}

private fun String.isValidBackendUrl(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("http://") || trimmed.startsWith("https://")
}

private fun Throwable.toUserMessage(): String {
    if (this is NazhiBackendException) {
        return when (code) {
            "DIRECT_API_KEY_MISSING" -> "请先填写 API Key。"
            "DIRECT_API_BASE_URL_MISSING" -> "请先填写 API Base URL。"
            "DIRECT_API_UNAUTHORIZED" -> publicMessage
            "DIRECT_API_ENDPOINT_NOT_FOUND" -> publicMessage
            "DIRECT_API_RATE_LIMITED" -> publicMessage
            "DIRECT_API_QUOTA_EXHAUSTED" -> publicMessage
            "DIRECT_API_TIMEOUT" -> publicMessage
            "DIRECT_API_BAD_REQUEST" -> publicMessage
            "DIRECT_API_PROVIDER_UNAVAILABLE" -> publicMessage
            "DIRECT_API_CHAT_RESPONSE_EMPTY" -> publicMessage
            "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED" -> publicMessage
            else -> publicMessage
        }
    }
    val rawMessage = message.orEmpty()
    return when {
        rawMessage.contains("Failed to connect", ignoreCase = true) ||
            rawMessage.contains("Unable to resolve host", ignoreCase = true) ||
            rawMessage.contains("No address associated", ignoreCase = true) ||
            rawMessage.contains("Network is unreachable", ignoreCase = true) ||
            rawMessage.contains("Connection refused", ignoreCase = true) -> {
            "无法连接服务，请检查网络、Base URL、端口和防火墙。"
        }
        rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true) -> {
            "连接超时，请检查网络或稍后重试。"
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

private fun LocalDataImportPreview.toPreviewText(): String {
    return """
        将导入 schemaVersion=$schemaVersion 的纳知数据：
        Note：$noteCount
        知识条目：$knowledgeEntryCount
        AI 草稿：$knowledgeDraftCount
        问答会话：$chatSessionCount
        问答消息：$chatMessageCount
        问答引用：$chatCitationCount
    """.trimIndent()
}

private fun LocalDataImportResult.toImportMessage(): String {
    return "导入完成：新增 $insertedCount，跳过 $skippedCount，失败 $failedCount"
}

private fun LocalDataImportResult.toResultText(): String {
    return """
        新增：$insertedCount
        跳过：$skippedCount
        失败：$failedCount
        新增知识条目：${knowledgeEntries.insertedCount}
    """.trimIndent()
}

private fun LocalDataImportResult.shouldOfferIndexRebuild(): Boolean {
    return knowledgeEntries.insertedCount > 0
}

private fun Context.readTextFromUri(uri: Uri): String {
    return contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    } ?: error("无法打开导入文件")
}

private fun Context.writeTextToUri(uri: Uri, text: String) {
    contentResolver.openOutputStream(uri)?.use { output ->
        output.write(text.toByteArray(Charsets.UTF_8))
    } ?: error("无法打开导出文件")
}

private fun Context.hasOverlayPermission(): Boolean {
    return AndroidSettings.canDrawOverlays(this)
}

private fun Context.overlayPermissionIntent(): Intent {
    return Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )
}
