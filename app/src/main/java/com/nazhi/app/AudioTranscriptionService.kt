package com.nazhi.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nazhi.app.core.audio.PlaybackAudioRecorder
import com.nazhi.app.core.audio.RecordedAudio
import com.nazhi.app.core.audio.WavAudioRecorder
import com.nazhi.app.core.capture.CaptureSaveResult
import com.nazhi.app.core.capture.saveCapturedText
import com.nazhi.app.core.capture.toToastMessage
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.util.toLocalDateId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudioTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: WavAudioRecorder
    private lateinit var playbackRecorder: PlaybackAudioRecorder
    private var recordingJob: Job? = null
    private var activeAudio: RecordedAudio? = null
    private var activeMode: AudioCaptureMode = AudioCaptureMode.MICROPHONE

    override fun onCreate() {
        super.onCreate()
        recorder = WavAudioRecorder(applicationContext)
        playbackRecorder = PlaybackAudioRecorder(applicationContext.cacheDir)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_START_SYSTEM_AUDIO -> startSystemAudioRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_FINISH -> finishRecording()
            ACTION_CANCEL -> cancelRecording()
            ACTION_RETRY_PENDING -> retryPendingAudio()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        recorder.cancel()
        playbackRecorder.cancel()
        scope.cancel()
        state = AudioFloatingState.IDLE
        statusText = ""
        statusChipVisibleUntilMs = 0L
        notifyFloating()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (!hasRecordPermission()) {
            updateState(AudioFloatingState.FAILED, "未授权麦克风")
            Toast.makeText(this, "未授权麦克风，无法录音", Toast.LENGTH_SHORT).show()
            return
        }
        if (state == AudioFloatingState.PAUSED) {
            resumeRecording()
            return
        }
        if (state == AudioFloatingState.RECORDING || state == AudioFloatingState.UPLOADING || state == AudioFloatingState.TRANSCRIBING) {
            return
        }

        activeMode = AudioCaptureMode.MICROPHONE
        startAudioForeground("录音中", AudioCaptureMode.MICROPHONE)
        updateState(AudioFloatingState.RECORDING, "录音中")
        recordingJob = scope.launch {
            val result = runCatching {
                recorder.record()
            }
            result.fold(
                onSuccess = { audio ->
                    if (audio.byteSize <= 0) {
                        audio.file.delete()
                        fail("没有录到有效声音")
                    } else {
                        val persistedAudio = persistAudioForRetry(audio, AudioCaptureMode.MICROPHONE)
                        activeAudio = persistedAudio
                        val job = createLocalAudioJob(persistedAudio, AudioCaptureMode.MICROPHONE)
                        processAudio(persistedAudio, AudioCaptureMode.MICROPHONE, job.id)
                    }
                },
                onFailure = { error ->
                    fail(error.message ?: "录音失败")
                }
            )
        }
    }

    private fun startSystemAudioRecording(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail("系统音频模式需要 Android 10 或更高版本")
            return
        }
        if (state == AudioFloatingState.PAUSED) {
            resumeRecording()
            return
        }
        if (state == AudioFloatingState.RECORDING || state == AudioFloatingState.UPLOADING || state == AudioFloatingState.TRANSCRIBING) {
            return
        }
        val projectionData = intent.projectionData()
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
        if (resultCode == 0 || projectionData == null) {
            fail("未获得系统音频捕获授权")
            return
        }
        activeMode = AudioCaptureMode.SYSTEM_AUDIO
        startAudioForeground("系统音频录制中", AudioCaptureMode.SYSTEM_AUDIO)
        updateState(AudioFloatingState.RECORDING, "系统音频录制中")
        updateNotification(statusText)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching {
            projectionManager.getMediaProjection(resultCode, projectionData)
        }.getOrNull()
        if (mediaProjection == null) {
            fail("系统音频授权已失效，请重新授权")
            return
        }
        recordingJob = scope.launch {
            val result = runCatching {
                playbackRecorder.record(mediaProjection)
            }
            result.fold(
                onSuccess = { audio ->
                    when {
                        audio.byteSize <= 0 -> {
                            audio.file.delete()
                            fail("没有捕获到系统音频，当前 App 可能不允许音频捕获")
                        }
                        audio.durationMs < MIN_SYSTEM_AUDIO_DURATION_MS -> {
                            audio.file.delete()
                            fail("系统音频录制时间过短，请播放内容后再结束")
                        }
                        audio.isLikelySilentSystemAudio() -> {
                            audio.file.delete()
                            fail("没有捕获到有效系统音频，当前 App 可能不允许音频捕获")
                        }
                        else -> {
                            val persistedAudio = persistAudioForRetry(audio, AudioCaptureMode.SYSTEM_AUDIO)
                            activeAudio = persistedAudio
                            val job = createLocalAudioJob(persistedAudio, AudioCaptureMode.SYSTEM_AUDIO)
                            processAudio(persistedAudio, AudioCaptureMode.SYSTEM_AUDIO, job.id)
                        }
                    }
                },
                onFailure = { error ->
                    fail(error.message ?: "系统音频录制失败")
                }
            )
        }
    }

    private fun pauseRecording() {
        if (state != AudioFloatingState.RECORDING) return
        pauseActiveRecorder()
        updateState(AudioFloatingState.PAUSED, activeMode.pausedText())
        updateNotification(statusText)
    }

    private fun resumeRecording() {
        if (state != AudioFloatingState.PAUSED) return
        resumeActiveRecorder()
        updateState(AudioFloatingState.RECORDING, activeMode.recordingText())
        updateNotification(statusText)
    }

    private fun finishRecording() {
        if (state != AudioFloatingState.RECORDING && state != AudioFloatingState.PAUSED) return
        updateState(
            AudioFloatingState.FINISHING,
            "录音已结束，正在上传",
            floatingStatusMs = 1600
        )
        updateNotification(statusText)
        Toast.makeText(this, "录音已结束，正在转写", Toast.LENGTH_SHORT).show()
        stopActiveRecorder()
    }

    private fun cancelRecording() {
        cancelActiveRecorder()
        activeAudio?.file?.delete()
        activeAudio = null
        updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun retryPendingAudio() {
        if (state == AudioFloatingState.RECORDING ||
            state == AudioFloatingState.PAUSED ||
            state == AudioFloatingState.UPLOADING ||
            state == AudioFloatingState.TRANSCRIBING ||
            state == AudioFloatingState.SAVING
        ) {
            Toast.makeText(this, "当前音频任务仍在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("准备重试待转写音频"))
        updateState(AudioFloatingState.UPLOADING, "准备重试待转写音频", floatingStatusMs = 1600)
        recordingJob = scope.launch {
            val repository = (application as NazhiApp).appContainer.repository
            val jobs = repository.getRetryableAudioTranscriptionJobs()
            if (jobs.isEmpty()) {
                updateState(AudioFloatingState.SAVED, "没有待转写音频", floatingStatusMs = 1600)
                delay(1800)
                updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            var succeeded = 0
            var failed = 0
            jobs.forEachIndexed { index, job ->
                val message = "正在重试 ${index + 1}/${jobs.size}"
                updateState(AudioFloatingState.UPLOADING, message, floatingStatusMs = 1200)
                updateNotification(message)
                if (retryStoredAudio(job)) {
                    succeeded += 1
                } else {
                    failed += 1
                }
            }
            activeAudio = null
            val resultMessage = if (failed == 0) {
                "已完成 $succeeded 条音频转写"
            } else {
                "完成 $succeeded 条，处理失败 $failed 条"
            }
            updateState(
                if (failed == 0) AudioFloatingState.SAVED else AudioFloatingState.FAILED,
                resultMessage,
                floatingStatusMs = 2200
            )
            updateNotification(resultMessage)
            Toast.makeText(this@AudioTranscriptionService, resultMessage, Toast.LENGTH_SHORT).show()
            delay(2400)
            updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun retryStoredAudio(job: AudioTranscriptionJob): Boolean {
        val file = File(job.filePath)
        if (!file.exists() || file.length() <= 0L) {
            (application as NazhiApp).appContainer.repository.updateAudioTranscriptionJobStatus(
                id = job.id,
                status = AudioTranscriptionJobStatus.FAILED,
                errorMessage = "暂存音频文件不存在，无法重试",
                updatedAt = System.currentTimeMillis()
            )
            return false
        }
        val mode = AudioCaptureMode.fromBackendSource(job.backendSource)
        activeMode = mode
        val audio = RecordedAudio(
            file = file,
            durationMs = job.durationMs,
            byteSize = job.byteSize,
            reachedLimit = false
        )
        activeAudio = audio
        return processAudio(
            audio = audio,
            mode = mode,
            localJobId = job.id,
            retryIncrement = 1,
            stopWhenFinished = false
        )
    }

    private fun persistAudioForRetry(audio: RecordedAudio, mode: AudioCaptureMode): RecordedAudio {
        val outputDir = File(filesDir, "audio_transcriptions").apply { mkdirs() }
        if (audio.file.parentFile?.absolutePath == outputDir.absolutePath) {
            return audio
        }
        val target = File(outputDir, "nazhi-${mode.backendSource}-${System.currentTimeMillis()}.wav")
        audio.file.copyTo(target, overwrite = true)
        audio.file.delete()
        return audio.copy(file = target)
    }

    private suspend fun createLocalAudioJob(
        audio: RecordedAudio,
        mode: AudioCaptureMode
    ): AudioTranscriptionJob {
        val now = System.currentTimeMillis()
        val job = AudioTranscriptionJob(
            id = UUID.randomUUID().toString(),
            sourceApp = mode.sourceApp,
            backendSource = mode.backendSource,
            filePath = audio.file.absolutePath,
            durationMs = audio.durationMs,
            byteSize = audio.byteSize,
            status = AudioTranscriptionJobStatus.PENDING,
            createdAt = now,
            createdDate = now.toLocalDateId(),
            updatedAt = now
        )
        (application as NazhiApp).appContainer.repository.saveAudioTranscriptionJob(job)
        return job
    }

    private suspend fun processAudio(
        audio: RecordedAudio,
        mode: AudioCaptureMode,
        localJobId: String,
        retryIncrement: Int = 0,
        stopWhenFinished: Boolean = true
    ): Boolean {
        val appContainer = (application as NazhiApp).appContainer
        val startedAt = System.currentTimeMillis()
        appContainer.repository.updateAudioTranscriptionJobStatus(
            id = localJobId,
            status = AudioTranscriptionJobStatus.UPLOADING,
            errorMessage = null,
            retryIncrement = retryIncrement,
            lastTriedAt = startedAt,
            updatedAt = startedAt
        )
        updateState(
            AudioFloatingState.UPLOADING,
            if (audio.reachedLimit) "已达到 15 分钟上限，正在上传" else mode.uploadingText(),
            floatingStatusMs = 1600
        )
        updateNotification(statusText)
        val createdTask = runCatching {
            appContainer.backendClient.createAudioTranscriptionJob(
                audioFile = audio.file,
                durationMs = audio.durationMs,
                source = mode.backendSource
            )
        }.getOrElse { error ->
            failTranscription(localJobId, error.toAudioUserMessage(), stopWhenFinished)
            return false
        }

        var currentTask = createdTask
        repeat(90) {
            if (currentTask.status == "SUCCEEDED") {
                val text = currentTask.result?.text.orEmpty().trim()
                if (text.isBlank()) {
                    failTranscription(localJobId, "转写结果为空", stopWhenFinished)
                    return false
                } else {
                    return saveTranscript(text, audio, localJobId, stopWhenFinished)
                }
            }
            if (currentTask.status == "FAILED") {
                failTranscription(
                    localJobId,
                    currentTask.error.toAudioTaskMessage(currentTask.message.ifBlank { "处理失败" }),
                    stopWhenFinished
                )
                return false
            }
            appContainer.repository.updateAudioTranscriptionJobStatus(
                id = localJobId,
                status = AudioTranscriptionJobStatus.TRANSCRIBING,
                updatedAt = System.currentTimeMillis()
            )
            updateState(
                AudioFloatingState.TRANSCRIBING,
                currentTask.message.ifBlank { "音频转写中" }
            )
            updateNotification(statusText)
            delay(1500)
            currentTask = runCatching {
                appContainer.backendClient.getAudioTranscriptionJob(currentTask.taskId)
            }.getOrElse { error ->
                failTranscription(localJobId, error.toAudioUserMessage(), stopWhenFinished)
                return false
            }
        }
        failTranscription(localJobId, "转写超时，请稍后重试", stopWhenFinished)
        return false
    }

    private suspend fun saveTranscript(
        text: String,
        audio: RecordedAudio,
        localJobId: String,
        stopWhenDone: Boolean
    ): Boolean {
        updateState(AudioFloatingState.SAVING, "正在加入今日收件箱")
        updateNotification(statusText)
        val repository = (application as NazhiApp).appContainer.repository
        val saveResult = runCatching {
            saveCapturedText(
                repository = repository,
                rawText = text,
                sourceType = SourceType.AUDIO_TRANSCRIPTION,
                sourceApp = activeMode.sourceApp,
                title = activeMode.noteTitle(audio.durationMs),
                audioDurationMs = audio.durationMs
            )
        }.getOrElse {
            null
        }
        activeAudio = null
        if (saveResult is CaptureSaveResult.Saved) {
            repository.markAudioTranscriptionJobSaved(
                id = localJobId,
                noteId = saveResult.noteId,
                transcriptText = text,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            repository.updateAudioTranscriptionJobStatus(
                id = localJobId,
                status = AudioTranscriptionJobStatus.FAILED,
                errorMessage = saveResult?.toToastMessage(emptyMessage = "转写文本为空，未保存")
                    ?: "保存失败，请稍后重试",
                updatedAt = System.currentTimeMillis()
            )
        }
        val userMessage = when (saveResult) {
            is CaptureSaveResult.Saved -> "音频已加入今日收件箱"
            null -> "保存失败，请稍后重试"
            else -> saveResult.toToastMessage(emptyMessage = "转写文本为空，未保存")
        }
        updateState(AudioFloatingState.SAVED, userMessage, floatingStatusMs = 2200)
        updateNotification(userMessage)
        Toast.makeText(this, userMessage, Toast.LENGTH_SHORT).show()
        if (stopWhenDone) {
            delay(2600)
            updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return saveResult is CaptureSaveResult.Saved
    }

    private fun fail(message: String) {
        activeAudio?.file?.delete()
        activeAudio = null
        updateState(AudioFloatingState.FAILED, message, floatingStatusMs = 2200)
        updateNotification(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        scope.launch {
            delay(2200)
            updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun failTranscription(
        localJobId: String,
        message: String,
        stopWhenDone: Boolean
    ) {
        (application as NazhiApp).appContainer.repository.updateAudioTranscriptionJobStatus(
            id = localJobId,
            status = AudioTranscriptionJobStatus.FAILED,
            errorMessage = message,
            updatedAt = System.currentTimeMillis()
        )
        activeAudio = null
        updateState(AudioFloatingState.FAILED, message, floatingStatusMs = 2200)
        updateNotification(message)
        Toast.makeText(this, "$message，音频已暂存，可稍后重试", Toast.LENGTH_SHORT).show()
        if (stopWhenDone) {
            delay(2200)
            updateState(AudioFloatingState.IDLE, "", floatingStatusMs = 0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pauseActiveRecorder() {
        when (activeMode) {
            AudioCaptureMode.MICROPHONE -> recorder.pause()
            AudioCaptureMode.SYSTEM_AUDIO -> playbackRecorder.pause()
        }
    }

    private fun resumeActiveRecorder() {
        when (activeMode) {
            AudioCaptureMode.MICROPHONE -> recorder.resume()
            AudioCaptureMode.SYSTEM_AUDIO -> playbackRecorder.resume()
        }
    }

    private fun stopActiveRecorder() {
        when (activeMode) {
            AudioCaptureMode.MICROPHONE -> recorder.stop()
            AudioCaptureMode.SYSTEM_AUDIO -> playbackRecorder.stop()
        }
    }

    private fun cancelActiveRecorder() {
        when (activeMode) {
            AudioCaptureMode.MICROPHONE -> recorder.cancel()
            AudioCaptureMode.SYSTEM_AUDIO -> playbackRecorder.cancel()
        }
    }

    private fun updateState(
        next: AudioFloatingState,
        message: String = next.defaultStatusText(),
        floatingStatusMs: Long? = null
    ) {
        state = next
        statusText = message
        floatingStatusMs?.let { duration ->
            statusChipVisibleUntilMs = if (duration > 0) {
                System.currentTimeMillis() + duration
            } else {
                0L
            }
        }
        notifyFloating()
    }

    private fun notifyFloating() {
        if (FloatingCaptureService.isRunning) {
            startService(
                Intent(this, FloatingCaptureService::class.java)
                    .setAction(FloatingCaptureService.ACTION_REFRESH)
            )
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startAudioForeground(text: String, mode: AudioCaptureMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode.foregroundServiceType != 0) {
            startForeground(NOTIFICATION_ID, buildNotification(text), mode.foregroundServiceType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("纳知录音转写")
            .setContentText(text)
            .setOngoing(state == AudioFloatingState.RECORDING || state == AudioFloatingState.PAUSED)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "纳知录音转写",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.nazhi.app.audio.START"
        const val ACTION_START_SYSTEM_AUDIO = "com.nazhi.app.audio.START_SYSTEM_AUDIO"
        const val ACTION_PAUSE = "com.nazhi.app.audio.PAUSE"
        const val ACTION_RESUME = "com.nazhi.app.audio.RESUME"
        const val ACTION_FINISH = "com.nazhi.app.audio.FINISH"
        const val ACTION_CANCEL = "com.nazhi.app.audio.CANCEL"
        const val ACTION_RETRY_PENDING = "com.nazhi.app.audio.RETRY_PENDING"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "nazhi_audio_transcription"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var state: AudioFloatingState = AudioFloatingState.IDLE
            private set

        @Volatile
        var statusText: String = ""
            private set

        @Volatile
        var statusChipVisibleUntilMs: Long = 0L
            private set

        fun shouldShowFloatingStatus(nowMs: Long = System.currentTimeMillis()): Boolean {
            return statusText.isNotBlank() && statusChipVisibleUntilMs > nowMs
        }

        fun floatingStatusRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
            return (statusChipVisibleUntilMs - nowMs).coerceAtLeast(0L)
        }
    }
}

enum class AudioFloatingState {
    IDLE,
    RECORDING,
    PAUSED,
    FINISHING,
    UPLOADING,
    TRANSCRIBING,
    SAVING,
    SAVED,
    FAILED
}

private enum class AudioCaptureMode(
    val sourceApp: String,
    val backendSource: String,
    val foregroundServiceType: Int
) {
    MICROPHONE(
        sourceApp = "麦克风转写",
        backendSource = "floating_ball_mic",
        foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
    ),
    SYSTEM_AUDIO(
        sourceApp = "系统音频转写",
        backendSource = "floating_ball_system_audio",
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    );

    fun recordingText(): String {
        return when (this) {
            MICROPHONE -> "录音中"
            SYSTEM_AUDIO -> "系统音频录制中"
        }
    }

    fun pausedText(): String {
        return when (this) {
            MICROPHONE -> "录音已暂停"
            SYSTEM_AUDIO -> "系统音频已暂停"
        }
    }

    fun uploadingText(): String {
        return when (this) {
            MICROPHONE -> "音频上传中"
            SYSTEM_AUDIO -> "系统音频上传中"
        }
    }

    fun noteTitle(durationMs: Long): String {
        return "${sourceApp} · ${durationMs.toDurationLabel()}"
    }

    companion object {
        fun fromBackendSource(source: String): AudioCaptureMode {
            return if (source.contains("system", ignoreCase = true)) {
                SYSTEM_AUDIO
            } else {
                MICROPHONE
            }
        }
    }
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

private fun Intent.projectionData(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(AudioTranscriptionService.EXTRA_PROJECTION_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(AudioTranscriptionService.EXTRA_PROJECTION_DATA)
    }
}

private fun RecordedAudio.isLikelySilentSystemAudio(): Boolean {
    return rmsAmplitude < MIN_SYSTEM_AUDIO_RMS && peakAmplitude < MIN_SYSTEM_AUDIO_PEAK
}

private const val MIN_SYSTEM_AUDIO_DURATION_MS = 800L
private const val MIN_SYSTEM_AUDIO_RMS = 25.0
private const val MIN_SYSTEM_AUDIO_PEAK = 200

private fun Throwable.toAudioUserMessage(): String {
    if (this is NazhiBackendException && (statusCode == 401 || code == "UNAUTHORIZED")) {
        return "后端鉴权失败，请在设置页检查服务访问 Token。"
    }
    if (this is NazhiBackendException) {
        return code.toAudioErrorMessage(publicMessage)
    }
    val raw = message.orEmpty()
    return when {
        raw.contains("Failed to connect", ignoreCase = true) -> "无法连接后端，请检查服务器地址和网络。"
        raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> {
            "转写请求超时，请稍后重试。"
        }
        raw.contains("401", ignoreCase = true) -> "后端鉴权失败，请检查服务 Token。"
        raw.isNotBlank() -> raw
        else -> "处理失败，请稍后重试。"
    }
}

private fun com.nazhi.app.core.network.BackendTaskError?.toAudioTaskMessage(fallback: String): String {
    return this?.code.toAudioErrorMessage(this?.message ?: fallback)
}

private fun String?.toAudioErrorMessage(fallback: String): String {
    return when (this) {
        "XFYUN_NOT_CONFIGURED" -> "服务器未配置科大讯飞语音服务，请检查 XFYUN_APP_ID / XFYUN_API_KEY / XFYUN_API_SECRET。"
        "WEBSOCKET_UNAVAILABLE" -> "服务器 Node.js 运行时不支持短音频 WebSocket，请升级 Node.js 或改用长音频转写。"
        "EMPTY_AUDIO" -> "录音文件为空，请重新录制。"
        "EMPTY_TRANSCRIPT" -> "语音识别结果为空，请确认录音内容清晰后重试。"
        "ASR_TIMEOUT" -> "语音转写超时，请稍后重试。"
        "XFYUN_IAT_FAILED",
        "XFYUN_IAT_CLOSED" -> "科大讯飞短音频识别失败，请稍后重试。"
        "XFYUN_UPLOAD_FAILED",
        "XFYUN_UPLOAD_SHAPE_UNSUPPORTED" -> "科大讯飞长音频上传失败，请稍后重试。"
        "XFYUN_CREATE_TASK_FAILED",
        "XFYUN_CREATE_TASK_SHAPE_UNSUPPORTED" -> "科大讯飞长音频任务创建失败，请检查服务开通状态。"
        "XFYUN_QUERY_TASK_FAILED",
        "XFYUN_TASK_FAILED" -> "科大讯飞长音频转写失败，请稍后重试。"
        "XFYUN_HTTP_FAILED" -> "科大讯飞语音服务请求失败，请检查密钥、服务额度或控制台权限。"
        "UNSUPPORTED_ASR_PROVIDER" -> "服务器配置了不支持的语音服务商。"
        else -> fallback
    }
}

private fun AudioFloatingState.defaultStatusText(): String {
    return when (this) {
        AudioFloatingState.IDLE -> ""
        AudioFloatingState.RECORDING -> "录音中"
        AudioFloatingState.PAUSED -> "录音已暂停"
        AudioFloatingState.FINISHING -> "录音已结束，正在准备转写"
        AudioFloatingState.UPLOADING -> "音频上传中"
        AudioFloatingState.TRANSCRIBING -> "音频转写中"
        AudioFloatingState.SAVING -> "正在加入今日收件箱"
        AudioFloatingState.SAVED -> "音频已加入今日收件箱"
        AudioFloatingState.FAILED -> "处理失败"
    }
}
