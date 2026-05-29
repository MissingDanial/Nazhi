package com.nazhi.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.nazhi.app.core.ui.NazhiTheme
import com.nazhi.app.feature.inbox.InboxPreview
import com.nazhi.app.feature.home.NazhiHomeRoute

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedSource by mutableStateOf<String?>(null)
    private var showOverlayPermissionPrompt by mutableStateOf(false)
    private var hasPromptedOverlayPermissionThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as NazhiApp).appContainer
        applyShareIntent(intent)
        setContent {
            NazhiAppRoot {
                NazhiHomeRoute(
                    repository = appContainer.repository,
                    backendSettingsStore = appContainer.backendSettingsStore,
                    backendClient = appContainer.backendClient,
                    knowledgeIngestionCoordinator = appContainer.knowledgeIngestionCoordinator,
                    knowledgeChatCoordinator = appContainer.knowledgeChatCoordinator,
                    initialShareText = sharedText,
                    initialShareSource = sharedSource,
                    onShareConsumed = {
                        sharedText = null
                        sharedSource = null
                    }
                )
                if (showOverlayPermissionPrompt) {
                    OverlayPermissionPromptDialog(
                        onEnable = {
                            showOverlayPermissionPrompt = false
                            openOverlayPermissionSettings()
                        },
                        onDismiss = {
                            showOverlayPermissionPrompt = false
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncOverlayPermissionState()
    }

    override fun onStart() {
        super.onStart()
        if (FloatingCaptureService.isRunning) {
            startService(
                Intent(this, FloatingCaptureService::class.java)
                    .setAction(FloatingCaptureService.ACTION_HIDE)
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (FloatingCaptureService.isRunning) {
            startService(
                Intent(this, FloatingCaptureService::class.java)
                    .setAction(FloatingCaptureService.ACTION_SHOW)
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyShareIntent(intent)
    }

    private fun applyShareIntent(intent: Intent?) {
        sharedText = intent.extractSharedText()
        sharedSource = intent.extractShareSource()
    }

    private fun syncOverlayPermissionState() {
        if (Settings.canDrawOverlays(this)) {
            showOverlayPermissionPrompt = false
            if (!FloatingCaptureService.isRunning) {
                startService(
                    Intent(this, FloatingCaptureService::class.java)
                        .setAction(FloatingCaptureService.ACTION_START_HIDDEN)
                )
            }
            return
        }

        if (!hasPromptedOverlayPermissionThisLaunch) {
            hasPromptedOverlayPermissionThisLaunch = true
            showOverlayPermissionPrompt = true
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching {
            startActivity(intent)
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }
}

@Composable
private fun NazhiAppRoot(content: @Composable () -> Unit) {
    NazhiTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NazhiAppRootPreview() {
    NazhiAppRoot {
        InboxPreview()
    }
}

@Composable
private fun OverlayPermissionPromptDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "开启悬浮球快捷收纳") },
        text = {
            Text(
                text = "悬浮球用于在其他 App 中复制文本后快速收纳到纳知。未开启时，分享入口仍可使用，但跨 App 一键收纳体验会受到影响。"
            )
        },
        confirmButton = {
            Button(onClick = onEnable) {
                Text(text = "去开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "暂不开启")
            }
        }
    )
}

private fun Intent?.extractSharedText(): String? {
    if (this == null || action != Intent.ACTION_SEND || type != "text/plain") {
        return null
    }
    return getStringExtra(Intent.EXTRA_TEXT)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun Intent?.extractShareSource(): String? {
    if (this == null || action != Intent.ACTION_SEND) {
        return null
    }
    return getStringExtra(Intent.EXTRA_REFERRER_NAME)
        ?: getStringExtra(Intent.EXTRA_TITLE)
}
