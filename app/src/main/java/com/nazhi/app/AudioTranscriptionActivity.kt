package com.nazhi.app

import android.Manifest
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.audio.RecordedAudio
import com.nazhi.app.core.audio.WavAudioRecorder
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.util.toLocalDateId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                initialJobId = intent.getStringExtra(EXTRA_JOB_ID),
                onJobCreated = { intent.putExtra(EXTRA_JOB_ID, it) },
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

    companion object {
        const val EXTRA_JOB_ID = "audio_transcription_job_id"
    }
}

@Composable
private fun AudioTranscriptionScreen(
    recorder: WavAudioRecorder,
    initialJobId: String?,
    onJobCreated: (String?) -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val appContainer = remember(context) {
        (context.applicationContext as NazhiApp).appContainer
    }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(AudioTranscriptionStage.READY) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("点击开始后，纳知会录制外放声音并上传云端转写。") }
    var transcribedText by rememberSaveable { mutableStateOf("") }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var errorText by remember { mutableStateOf("") }
    var localJobId by rememberSaveable { mutableStateOf(initialJobId) }
    var saving by remember { mutableStateOf(false) }
    val previewMutex = remember { Mutex() }

    fun displayJob(job: AudioTranscriptionJob) {
        elapsedMs = job.durationMs
        when (job.status) {
            AudioTranscriptionJobStatus.READY_TO_SAVE -> {
                transcribedText = job.transcriptText.orEmpty()
                stage = AudioTranscriptionStage.PREVIEW
                progress = 100
                statusText = "转写完成，确认后保存"
                errorText = ""
            }
            AudioTranscriptionJobStatus.SAVED, AudioTranscriptionJobStatus.AUDIO_CLEANED -> onFinish()
            else -> {
                stage = AudioTranscriptionStage.FAILED
                errorText = job.errorMessage ?: "录音已保留，请点击重试"
                statusText = errorText
            }
        }
    }

    suspend fun processJob(id: String, resumeOnly: Boolean) {
        stage = AudioTranscriptionStage.TRANSCRIBING
        progress = 10
        errorText = ""
        val result = try {
            if (resumeOnly) appContainer.audioTaskProcessor.resume(id) { statusText = it }
            else appContainer.audioTaskProcessor.submit(id) { statusText = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            stage = AudioTranscriptionStage.FAILED
            errorText = error.message ?: "处理失败，录音已保留"
            return
        }
        displayJob(result)
    }

    LaunchedEffect(localJobId) {
        val id = localJobId ?: return@LaunchedEffect
        val job = appContainer.repository.getAudioTranscriptionJob(id) ?: return@LaunchedEffect
        if (job.status == AudioTranscriptionJobStatus.READY_TO_SAVE ||
            job.status == AudioTranscriptionJobStatus.SAVED || job.status == AudioTranscriptionJobStatus.AUDIO_CLEANED) {
            displayJob(job)
        } else if (initialJobId == id) {
            // Reopening can query work already submitted, but cannot start a new provider call.
            processJob(id, resumeOnly = true)
        }
    }

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
                localJobId = null
                onJobCreated(null)
            },
            onRecorded = { audio ->
                // Closing the screen must not cancel the small local file + metadata handoff.
                val job = withContext(NonCancellable) {
                    val persisted = withContext(Dispatchers.IO) {
                        val outputDir = File(context.filesDir, "audio_transcriptions").apply { mkdirs() }
                        val target = File(outputDir, "nazhi-app-${UUID.randomUUID()}.wav")
                        audio.file.copyTo(target, overwrite = false)
                        audio.copy(file = target)
                    }
                    val time = System.currentTimeMillis()
                    AudioTranscriptionJob(
                        id = UUID.randomUUID().toString(), sourceApp = "麦克风转写",
                        backendSource = "in_app_mic", filePath = persisted.file.absolutePath,
                        durationMs = persisted.durationMs, byteSize = persisted.byteSize,
                        status = AudioTranscriptionJobStatus.PENDING, requiresConfirmation = true,
                        createdAt = time, createdDate = time.toLocalDateId(), updatedAt = time
                    ).also {
                        appContainer.repository.saveAudioTranscriptionJob(it)
                        withContext(Dispatchers.IO) { audio.file.delete() }
                    }
                }
                localJobId = job.id
                onJobCreated(job.id)
                processJob(job.id, resumeOnly = false)
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
                        if (stage == AudioTranscriptionStage.FAILED && localJobId != null) {
                            Button(
                                onClick = { scope.launch { processJob(requireNotNull(localJobId), resumeOnly = false) } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("重试保留的录音") }
                        }
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
                            onValueChange = { value ->
                                transcribedText = value
                                localJobId?.let { id ->
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        try {
                                            withContext(NonCancellable) { previewMutex.withLock {
                                                appContainer.repository.updateAudioTranscriptPreview(id, value)
                                            } }
                                        } catch (error: Exception) {
                                            errorText = "预览修改尚未保存，请点击保存重试：${error.message.orEmpty()}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            enabled = !saving,
                            label = { Text(text = "转写文本") }
                        )
                        Button(
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        previewMutex.withLock {
                                            appContainer.repository.confirmAudioTranscript(
                                                requireNotNull(localJobId), transcribedText
                                            )
                                        }
                                        Toast.makeText(context, "音频已加入今日收件箱", Toast.LENGTH_SHORT).show()
                                        onFinish()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        errorText = error.message ?: "保存失败，转写文本已保留，请重试"
                                        Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = transcribedText.isNotBlank() && !saving,
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
                            if (stage == AudioTranscriptionStage.PREVIEW && localJobId != null) {
                                scope.launch {
                                    previewMutex.withLock {
                                        appContainer.repository.updateAudioTranscriptPreview(requireNotNull(localJobId), transcribedText)
                                    }
                                    onFinish()
                                }
                            } else {
                                onFinish()
                            }
                        }
                    ) {
                        Text(text = if (localJobId != null) "关闭，保留任务" else "取消")
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
        try {
            val audio = recorder.record()
            if (audio.byteSize <= 0) {
                audio.file.delete()
                onFailed("没有录到有效声音")
            } else {
                onRecorded(audio)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailed(error.message ?: "录音失败")
        }
    }
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
        AudioTranscriptionStage.PREVIEW -> "待确认"
        AudioTranscriptionStage.FAILED -> "处理失败"
        AudioTranscriptionStage.PERMISSION_DENIED -> "未授权麦克风"
    }
}

private fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun Long.toDurationLabel(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}分${seconds.toString().padStart(2, '0')}秒"
    } else {
        "${seconds}秒"
    }
}
