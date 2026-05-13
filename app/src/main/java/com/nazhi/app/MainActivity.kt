package com.nazhi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.nazhi.app.feature.inbox.InboxPreview
import com.nazhi.app.feature.home.NazhiHomeRoute

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedSource by mutableStateOf<String?>(null)

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
                    initialShareText = sharedText,
                    initialShareSource = sharedSource,
                    onShareConsumed = {
                        sharedText = null
                        sharedSource = null
                    }
                )
            }
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
}

@Composable
private fun NazhiAppRoot(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface {
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
