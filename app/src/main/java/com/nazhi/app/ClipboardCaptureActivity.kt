package com.nazhi.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.nazhi.app.core.capture.saveCapturedText
import com.nazhi.app.core.capture.toToastMessage
import com.nazhi.app.core.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipboardCaptureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hasHandledClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasHandledClipboard) {
            hasHandledClipboard = true
            captureClipboard()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun captureClipboard() {
        val clipboardText = readClipboardText()
        if (clipboardText.isNullOrBlank()) {
            toastAndFinish("剪贴板没有可收纳文本")
            return
        }

        val repository = (application as NazhiApp).appContainer.repository
        scope.launch {
            val message = runCatching {
                saveCapturedText(
                    repository = repository,
                    rawText = clipboardText,
                    sourceType = SourceType.CLIPBOARD,
                    sourceApp = "悬浮球剪贴板"
                ).toToastMessage(emptyMessage = "剪贴板没有可收纳文本")
            }.getOrElse {
                "收纳失败，请稍后重试"
            }
            Toast.makeText(this@ClipboardCaptureActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

private fun Context.readClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
