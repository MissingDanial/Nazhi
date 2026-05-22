package com.nazhi.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.audio.RecordedAudio
import com.nazhi.app.core.audio.WavAudioRecorder
import com.nazhi.app.core.capture.saveCapturedText
import com.nazhi.app.core.capture.toToastMessage
import com.nazhi.app.core.model.SourceType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudioTranscriptionActivity : ComponentActivity() {
    private val recorder by lazy { WavAudioRecorder(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioTranscriptionScreen(
                recorder = recorder,
                onFinish = { finish() }
            )
        }
    }

    override fun onDestroy() {
        recorder.cancel()
        if (FloatingCaptureService.isRunning && Settings.canDrawOverlays(this)) {
            startService(
                Intent(this, FloatingCaptureService::class.java)
                    .setAction(FloatingCaptureService.ACTION_SHOW)
            )
        }
        super.onDestroy()
    }
}

@Composable
private fun AudioTranscriptionScreen(
    recorder: WavAudioRecorder,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val appContainer = remember(context) {
        (context.applicationContext as NazhiApp).appContainer
    }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(AudioTranscriptionStage.READY) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("点击开始后，纳知会录制外放声音并上传云端转写。") }
    var transcribedText by remember { mutableStateOf("") }
    var recordedAudio by remember { mutableStateOf<RecordedAudio?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var errorText by remember { mutableStateOf("") }

    fun beginRecording() {
        recordingJob = startRecording(
            recorder = recorder,
            scope = scope,
            onStarted = {
                stage = AudioTranscriptionStage.RECORDING
                errorText = ""
                progress = 0
                statusText = "录音中"
                startedAt = SystemClock.elapsedRealtime()
                elapsedMs = 0L
            },
            onRecorded = { audio ->
                recordedAudio = audio
                submitAudio(
                    audio = audio,
                    appContainer = appContainer,
                    onUploading = {
                        stage = AudioTranscriptionStage.UPLOADING
                        progress = 8
                        statusText = if (audio.reachedLimit) {
                            "已达到 15 分钟上限，正在上传"
                        } else {
                            "正在上传录音"
                        }
                    },
                    onTranscribing = { message, nextProgress ->
                        stage = AudioTranscriptionStage.TRANSCRIBING
                        progress = nextProgress
                        statusText = message
                    },
                    onSucceeded = { text ->
                        transcribedText = text
                        stage = AudioTranscriptionStage.PREVIEW
                        progress = 100
                        statusText = "转写完成"
                    },
                    onFailed = { message ->
                        stage = AudioTranscriptionStage.FAILED
                        errorText = message
                        statusText = message
                    }
                )
            },
            onFailed = { message ->
                stage = AudioTranscriptionStage.FAILED
                errorText = message
                statusText = message
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginRecording()
        } else {
            stage = AudioTranscriptionStage.PERMISSION_DENIED
            statusText = "未授权麦克风，无法录音转写"
        }
    }

    LaunchedEffect(stage, startedAt) {
        while (stage == AudioTranscriptionStage.RECORDING) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(250)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "录音转写",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "最长 15 分钟。转写完成后先预览，确认后才保存到今日。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AudioStatusCard(
                    stage = stage,
                    elapsedMs = elapsedMs,
                    progress = progress,
                    statusText = statusText,
                    errorText = errorText
                )

                when (stage) {
                    AudioTranscriptionStage.READY,
                    AudioTranscriptionStage.PERMISSION_DENIED,
                    AudioTranscriptionStage.FAILED -> {
                        Button(
                            onClick = {
                                if (recorder.hasRecordPermission()) {
                                    beginRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = if (stage == AudioTranscriptionStage.FAILED) "重新录音" else "开始录音")
                        }
                    }
                    AudioTranscriptionStage.RECORDING -> {
                        Button(
                            onClick = { recorder.stop() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "停止并转写")
                        }
                    }
                    AudioTranscriptionStage.UPLOADING,
                    AudioTranscriptionStage.TRANSCRIBING -> {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AudioTranscriptionStage.PREVIEW -> {
                        OutlinedTextField(
                            value = transcribedText,
                            onValueChange = { transcribedText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            label = { Text(text = "转写文本") }
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val message = runCatching {
                                        saveCapturedText(
                                            repository = appContainer.repository,
                                            rawText = transcribedText,
                                            sourceType = SourceType.AUDIO_TRANSCRIPTION,
                                            sourceApp = "悬浮球录音转写"
                                        ).toToastMessage(emptyMessage = "转写文本为空，未保存")
                                    }.getOrElse {
                                        "保存失败，请稍后重试"
                                    }
                                    recordedAudio?.file?.delete()
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    onFinish()
                                }
                            },
                            enabled = transcribedText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "保存到今日")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            recordingJob?.cancel()
                            recorder.cancel()
                            recordedAudio?.file?.delete()
                            onFinish()
                        }
                    ) {
                        Text(text = "取消")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AudioStatusCard(
    stage: AudioTranscriptionStage,
    elapsedMs: Long,
    progress: Int,
    statusText: String,
    errorText: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stage.label(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (stage == AudioTranscriptionStage.RECORDING) {
                Text(
                    text = elapsedMs.formatDuration(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (stage == AudioTranscriptionStage.FAILED || stage == AudioTranscriptionStage.PERMISSION_DENIED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (progress in 1..99 && stage != AudioTranscriptionStage.RECORDING) {
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (errorText.isNotBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun startRecording(
    recorder: WavAudioRecorder,
    scope: kotlinx.coroutines.CoroutineScope,
    onStarted: () -> Unit,
    onRecorded: suspend (RecordedAudio) -> Unit,
    onFailed: (String) -> Unit
): Job {
    return scope.launch {
        onStarted()
        val result = runCatching {
            recorder.record()
        }
        result.fold(
            onSuccess = { audio ->
                if (audio.byteSize <= 0) {
                    audio.file.delete()
                    onFailed("没有录到有效声音")
                } else {
                    onRecorded(audio)
                }
            },
            onFailure = { error ->
                onFailed(error.message ?: "录音失败")
            }
        )
    }
}

private suspend fun submitAudio(
    audio: RecordedAudio,
    appContainer: com.nazhi.app.core.data.AppContainer,
    onUploading: () -> Unit,
    onTranscribing: (String, Int) -> Unit,
    onSucceeded: (String) -> Unit,
    onFailed: (String) -> Unit
) {
    onUploading()
    val createdTask = runCatching {
        appContainer.backendClient.createAudioTranscriptionJob(
            audioFile = audio.file,
            durationMs = audio.durationMs
        )
    }.getOrElse { error ->
        onFailed(error.toAudioUserMessage())
        return
    }

    var currentTask = createdTask
    repeat(90) {
        if (currentTask.status == "SUCCEEDED") {
            val text = currentTask.result?.text.orEmpty().trim()
            if (text.isBlank()) {
                onFailed("转写结果为空")
            } else {
                onSucceeded(text)
            }
            return
        }
        if (currentTask.status == "FAILED") {
            onFailed(currentTask.error?.message ?: currentTask.message.ifBlank { "转写失败" })
            return
        }
        onTranscribing(
            currentTask.message.ifBlank { "正在转写" },
            currentTask.progress.coerceIn(10, 95)
        )
        delay(1500)
        currentTask = runCatching {
            appContainer.backendClient.getAudioTranscriptionJob(currentTask.taskId)
        }.getOrElse { error ->
            onFailed(error.toAudioUserMessage())
            return
        }
    }
    onFailed("转写超时，请稍后重试")
}

private enum class AudioTranscriptionStage {
    READY,
    RECORDING,
    UPLOADING,
    TRANSCRIBING,
    PREVIEW,
    FAILED,
    PERMISSION_DENIED
}

private fun AudioTranscriptionStage.label(): String {
    return when (this) {
        AudioTranscriptionStage.READY -> "准备录音"
        AudioTranscriptionStage.RECORDING -> "录音中"
        AudioTranscriptionStage.UPLOADING -> "上传中"
        AudioTranscriptionStage.TRANSCRIBING -> "转写中"
        AudioTranscriptionStage.PREVIEW -> "等待确认"
        AudioTranscriptionStage.FAILED -> "转写失败"
        AudioTranscriptionStage.PERMISSION_DENIED -> "未授权麦克风"
    }
}

private fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun Throwable.toAudioUserMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("Failed to connect", ignoreCase = true) -> "无法连接后端，请检查服务器地址和网络。"
        raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> {
            "转写请求超时，请稍后重试。"
        }
        raw.contains("401", ignoreCase = true) -> "后端鉴权失败，请检查服务 Token。"
        raw.isNotBlank() -> raw
        else -> "转写失败，请稍后重试。"
    }
}
