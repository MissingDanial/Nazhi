package com.nazhi.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nazhi.app.core.audio.RecordedAudio
import com.nazhi.app.core.audio.WavAudioRecorder
import com.nazhi.app.core.capture.CaptureSaveResult
import com.nazhi.app.core.capture.saveCapturedText
import com.nazhi.app.core.capture.toToastMessage
import com.nazhi.app.core.model.SourceType
import com.nazhi.app.core.network.NazhiBackendException
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
    private var recordingJob: Job? = null
    private var activeAudio: RecordedAudio? = null

    override fun onCreate() {
        super.onCreate()
        recorder = WavAudioRecorder(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_FINISH -> finishRecording()
            ACTION_CANCEL -> cancelRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        recorder.cancel()
        scope.cancel()
        state = AudioFloatingState.IDLE
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

        startForeground(NOTIFICATION_ID, buildNotification("录音中"))
        updateState(AudioFloatingState.RECORDING, "录音中")
        recordingJob = scope.launch {
            val result = runCatching {
                recorder.record()
            }
            result.fold(
                onSuccess = { audio ->
                    activeAudio = audio
                    if (audio.byteSize <= 0) {
                        audio.file.delete()
                        fail("没有录到有效声音")
                    } else {
                        processAudio(audio)
                    }
                },
                onFailure = { error ->
                    fail(error.message ?: "录音失败")
                }
            )
        }
    }

    private fun pauseRecording() {
        if (state != AudioFloatingState.RECORDING) return
        recorder.pause()
        updateState(AudioFloatingState.PAUSED, "录音已暂停")
        updateNotification(statusText)
    }

    private fun resumeRecording() {
        if (state != AudioFloatingState.PAUSED) return
        recorder.resume()
        updateState(AudioFloatingState.RECORDING, "录音中")
        updateNotification(statusText)
    }

    private fun finishRecording() {
        if (state != AudioFloatingState.RECORDING && state != AudioFloatingState.PAUSED) return
        updateState(AudioFloatingState.FINISHING, "录音已结束，正在准备转写")
        updateNotification(statusText)
        Toast.makeText(this, "录音已结束，正在转写", Toast.LENGTH_SHORT).show()
        recorder.stop()
    }

    private fun cancelRecording() {
        recorder.cancel()
        activeAudio?.file?.delete()
        activeAudio = null
        updateState(AudioFloatingState.IDLE, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun processAudio(audio: RecordedAudio) {
        val appContainer = (application as NazhiApp).appContainer
        updateState(
            AudioFloatingState.UPLOADING,
            if (audio.reachedLimit) "已达到 15 分钟上限，正在上传" else "音频上传中"
        )
        updateNotification(statusText)
        val createdTask = runCatching {
            appContainer.backendClient.createAudioTranscriptionJob(
                audioFile = audio.file,
                durationMs = audio.durationMs
            )
        }.getOrElse { error ->
            fail(error.toAudioUserMessage())
            return
        }

        var currentTask = createdTask
        repeat(90) {
            if (currentTask.status == "SUCCEEDED") {
                val text = currentTask.result?.text.orEmpty().trim()
                if (text.isBlank()) {
                    fail("转写结果为空")
                } else {
                    saveTranscript(text, audio)
                }
                return
            }
            if (currentTask.status == "FAILED") {
                fail(currentTask.error?.message ?: currentTask.message.ifBlank { "转写失败" })
                return
            }
            updateState(
                AudioFloatingState.TRANSCRIBING,
                currentTask.message.ifBlank { "音频转写中" }
            )
            updateNotification(statusText)
            delay(1500)
            currentTask = runCatching {
                appContainer.backendClient.getAudioTranscriptionJob(currentTask.taskId)
            }.getOrElse { error ->
                fail(error.toAudioUserMessage())
                return
            }
        }
        fail("转写超时，请稍后重试")
    }

    private suspend fun saveTranscript(text: String, audio: RecordedAudio) {
        updateState(AudioFloatingState.SAVING, "正在加入今日收件箱")
        updateNotification(statusText)
        val repository = (application as NazhiApp).appContainer.repository
        val saveResult = runCatching {
            saveCapturedText(
                repository = repository,
                rawText = text,
                sourceType = SourceType.AUDIO_TRANSCRIPTION,
                sourceApp = "悬浮球录音转写"
            )
        }.getOrElse {
            null
        }
        audio.file.delete()
        activeAudio = null
        val userMessage = when (saveResult) {
            CaptureSaveResult.Saved -> "音频已加入今日收件箱"
            null -> "保存失败，请稍后重试"
            else -> saveResult.toToastMessage(emptyMessage = "转写文本为空，未保存")
        }
        updateState(AudioFloatingState.SAVED, userMessage)
        updateNotification(userMessage)
        Toast.makeText(this, userMessage, Toast.LENGTH_SHORT).show()
        delay(2600)
        updateState(AudioFloatingState.IDLE, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        activeAudio?.file?.delete()
        activeAudio = null
        updateState(AudioFloatingState.FAILED, message)
        updateNotification(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        scope.launch {
            delay(2200)
            updateState(AudioFloatingState.IDLE, "")
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

    private fun updateState(next: AudioFloatingState, message: String = next.defaultStatusText()) {
        state = next
        statusText = message
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
        const val ACTION_PAUSE = "com.nazhi.app.audio.PAUSE"
        const val ACTION_RESUME = "com.nazhi.app.audio.RESUME"
        const val ACTION_FINISH = "com.nazhi.app.audio.FINISH"
        const val ACTION_CANCEL = "com.nazhi.app.audio.CANCEL"
        private const val CHANNEL_ID = "nazhi_audio_transcription"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var state: AudioFloatingState = AudioFloatingState.IDLE
            private set

        @Volatile
        var statusText: String = ""
            private set
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

private fun Throwable.toAudioUserMessage(): String {
    if (this is NazhiBackendException && (statusCode == 401 || code == "UNAUTHORIZED")) {
        return "后端鉴权失败，请在设置页检查服务访问 Token。"
    }
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
        AudioFloatingState.FAILED -> "转写失败"
    }
}
