package com.nazhi.app.core.audio

import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.network.AudioTranscriptionTaskResponse
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A submitted recording is queried on recovery; only an explicit submit can create work. */
class AudioTaskProcessor internal constructor(
    private val repository: NazhiRepository,
    private val transport: AudioTaskTransport,
    private val pollingLimit: Int = 200,
    private val waitForPoll: suspend () -> Unit = { delay(1500L) },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pollingTimeoutMs: Long = 300_000L
) {
    constructor(repository: NazhiRepository, backendClient: NazhiBackendClient) : this(
        repository,
        object : AudioTaskTransport {
            override suspend fun binding() = backendClient.currentTaskBinding(forAudio = true)
            override suspend fun create(job: AudioTranscriptionJob, requestId: String, bindingKey: String) =
                backendClient.createAudioTranscriptionJob(
                    audioFile = File(job.filePath), durationMs = job.durationMs,
                    source = job.backendSource, requestId = requestId, expectedBinding = bindingKey
                )
            override suspend fun query(taskId: String, bindingKey: String) = backendClient.getAudioTranscriptionJob(taskId, bindingKey)
            override suspend fun lookup(requestId: String, bindingKey: String) = backendClient.getAudioTaskByRequestId(requestId, bindingKey)
        }
    )

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun submit(jobId: String, onProgress: (String) -> Unit = {}): AudioTranscriptionJob =
        locks.getOrPut(jobId) { Mutex() }.withLock {
            val job = requireJob(jobId)
            if (job.isFinished()) return@withLock job
            val old = repository.getPersistedAiTask(jobId)
            val binding = transport.binding()
            if (old != null && old.bindingKey != binding) {
                val message = "请切回提交任务时的账号和后端，再继续查询"
                return@withLock if (old.status in setOf("FAILED", "INTERRUPTED")) {
                    markFailed(jobId, message)
                } else waiting(jobId, old, message)
            }
            if (old != null && old.status in setOf("CREATED", "RUNNING", "WAITING")) {
                return@withLock resumeLocked(job, old, onProgress)
            }
            val file = File(job.filePath)
            if (!file.exists() || file.length() <= 0L) {
                return@withLock markFailed(jobId, "暂存音频文件不存在，无法重试")
            }
            val time = now()
            val task = PersistedAiTask(
                id = jobId, type = "AUDIO_TRANSCRIPTION", requestId = UUID.randomUUID().toString(),
                bindingKey = binding, date = job.createdDate,
                status = "CREATED", createdAt = time, updatedAt = time
            )
            // Store the request identity before any upload, including a lost HTTP response.
            repository.savePersistedAiTask(task)
            repository.updateAudioTranscriptionJobStatus(
                jobId, AudioTranscriptionJobStatus.UPLOADING,
                retryIncrement = if (old != null || job.status == AudioTranscriptionJobStatus.FAILED) 1 else 0,
                lastTriedAt = time, updatedAt = time
            )
            onProgress("正在上传录音")
            try {
                val remote = transport.create(job, task.requestId, task.bindingKey)
                validateRemote(remote, task)
                repository.savePersistedAiTask(task.copy(
                    backendTaskId = remote.taskId, status = "RUNNING", updatedAt = now()
                ))
                poll(jobId, task.bindingKey, remote, onProgress)
            } catch (cancelled: CancellationException) {
                preserveCancellation(task)
                throw cancelled
            } catch (error: Exception) {
                handleError(jobId, task, error)
            }
        }

    suspend fun resume(jobId: String, onProgress: (String) -> Unit = {}): AudioTranscriptionJob =
        locks.getOrPut(jobId) { Mutex() }.withLock {
            val job = requireJob(jobId)
            if (job.isFinished()) return@withLock job
            val task = repository.getPersistedAiTask(jobId)
                ?: return@withLock markFailed(jobId, "转写已中断，录音已保留，请点击重试")
            resumeLocked(job, task, onProgress)
        }

    private suspend fun resumeLocked(
        job: AudioTranscriptionJob, task: PersistedAiTask, onProgress: (String) -> Unit
    ): AudioTranscriptionJob {
        if (task.status in setOf("FAILED", "INTERRUPTED", "APPLIED")) return job
        if (task.bindingKey != transport.binding()) {
            return waiting(job.id, task, "请切回提交任务时的账号和后端，再继续查询")
        }
        onProgress("正在查询已提交的转写任务")
        return try {
            val remote = task.backendTaskId?.let { transport.query(it, task.bindingKey) }
                ?: transport.lookup(task.requestId, task.bindingKey)
            validateRemote(remote, task)
            repository.savePersistedAiTask(task.copy(
                backendTaskId = remote.taskId, status = "RUNNING", errorMessage = null, updatedAt = now()
            ))
            poll(job.id, task.bindingKey, remote, onProgress)
        } catch (cancelled: CancellationException) {
            preserveCancellation(task)
            throw cancelled
        } catch (error: Exception) {
            handleError(job.id, task, error)
        }
    }

    private suspend fun poll(
        jobId: String, bindingKey: String, first: AudioTranscriptionTaskResponse, onProgress: (String) -> Unit
    ): AudioTranscriptionJob {
        var remote = first
        val deadline = elapsedNow() + pollingTimeoutMs
        repeat(pollingLimit + 1) { attempt ->
            val task = requireNotNull(repository.getPersistedAiTask(jobId))
            validateRemote(remote, task)
            when (remote.status) {
                "SUCCEEDED" -> {
                    if (transport.binding() != bindingKey) {
                        return waiting(jobId, task, "请切回提交任务时的账号和后端，再继续保存结果")
                    }
                    val text = remote.result?.text.orEmpty().trim()
                    if (text.isEmpty()) return failedTask(jobId, "转写结果为空，请确认录音内容后重试")
                    onProgress("正在保存转写结果")
                    // Repository commits the transcript, note (for floating captures), and task together.
                    return repository.storeAudioTranscript(jobId, text)
                }
                "FAILED" -> return failedTask(jobId,
                    audioErrorMessage(remote.error?.code, remote.error?.message ?: remote.message.ifBlank { "转写失败" }))
            }
            repository.updateAudioTranscriptionJobStatus(jobId, AudioTranscriptionJobStatus.TRANSCRIBING)
            onProgress(remote.message.ifBlank { "音频转写中" })
            if (attempt == pollingLimit || elapsedNow() >= deadline) return waitingAfterTimeout(jobId)
            val completed = withTimeoutOrNull((deadline - elapsedNow()).coerceAtLeast(1L)) { waitForPoll(); true }
            if (completed == null || elapsedNow() >= deadline) return waitingAfterTimeout(jobId)
            remote = withTimeoutOrNull((deadline - elapsedNow()).coerceAtLeast(1L)) {
                transport.query(remote.taskId, bindingKey)
            } ?: return waitingAfterTimeout(jobId)
        }
        return waitingAfterTimeout(jobId)
    }

    private suspend fun waitingAfterTimeout(jobId: String): AudioTranscriptionJob {
        val task = requireNotNull(repository.getPersistedAiTask(jobId))
        return waiting(jobId, task, "服务器仍在处理，录音已保留；重开应用或点击重试可继续查询")
    }

    private fun validateRemote(remote: AudioTranscriptionTaskResponse, task: PersistedAiTask) {
        if (remote.requestId != task.requestId || remote.type != "AUDIO_TRANSCRIPTION" ||
            remote.taskId.isBlank() || (task.backendTaskId != null && remote.taskId != task.backendTaskId) ||
            remote.status !in setOf("RUNNING", "SUCCEEDED", "FAILED")) {
            throw NazhiBackendException(502, "INVALID_TASK_RESPONSE", "转写任务响应不匹配，录音已保留")
        }
    }

    private suspend fun handleError(jobId: String, task: PersistedAiTask, error: Exception): AudioTranscriptionJob {
        if (error is NazhiBackendException && error.code == "TASK_BINDING_CHANGED") {
            return waiting(jobId, repository.getPersistedAiTask(jobId) ?: task, "请切回提交任务时的账号和后端，再继续查询")
        }
        if (error is NazhiBackendException && error.statusCode == 401) {
            return waiting(jobId, repository.getPersistedAiTask(jobId) ?: task, "登录已失效，录音已保留；重新登录后继续查询已有任务")
        }
        if (error is NazhiBackendException && error.statusCode == 404) {
            return failedTask(jobId, "服务器任务已丢失，录音已保留，请点击重试", "INTERRUPTED")
        }
        if (error is NazhiBackendException && error.statusCode in 400..499 && error.statusCode != 408) {
            return failedTask(jobId, audioErrorMessage(error.code, error.publicMessage))
        }
        if (error is IOException) {
            val latest = repository.getPersistedAiTask(jobId) ?: task
            return waiting(jobId, latest, "网络暂时不可用，录音已保留；恢复连接后继续查询已有任务")
        }
        // A local commit failure must retain the remote identity so retry does not call ASR again.
        val latest = repository.getPersistedAiTask(jobId) ?: task
        return waiting(jobId, latest, "结果尚未保存，录音已保留，请稍后继续查询：${error.message.orEmpty()}")
    }

    private suspend fun preserveCancellation(task: PersistedAiTask) = withContext(NonCancellable) {
        val latest = repository.getPersistedAiTask(task.id) ?: task
        if (latest.status != "APPLIED") {
            waiting(task.id, latest, "转写已中断，录音已保留；重开应用后继续查询")
        }
    }

    private suspend fun waiting(jobId: String, task: PersistedAiTask, message: String): AudioTranscriptionJob {
        repository.savePersistedAiTask(task.copy(status = "WAITING", errorMessage = message, updatedAt = now()))
        return markFailed(jobId, message)
    }

    private suspend fun failedTask(jobId: String, message: String, status: String = "FAILED"): AudioTranscriptionJob {
        repository.getPersistedAiTask(jobId)?.let { task ->
            repository.savePersistedAiTask(task.copy(status = status, errorMessage = message, updatedAt = now()))
        }
        return markFailed(jobId, message)
    }

    private suspend fun markFailed(jobId: String, message: String): AudioTranscriptionJob {
        repository.updateAudioTranscriptionJobStatus(jobId, AudioTranscriptionJobStatus.FAILED, errorMessage = message)
        return requireJob(jobId)
    }

    private suspend fun requireJob(id: String) = requireNotNull(repository.getAudioTranscriptionJob(id)) {
        "录音任务不存在"
    }
}

internal interface AudioTaskTransport {
    suspend fun binding(): String
    suspend fun create(job: AudioTranscriptionJob, requestId: String, bindingKey: String): AudioTranscriptionTaskResponse
    suspend fun query(taskId: String, bindingKey: String): AudioTranscriptionTaskResponse
    suspend fun lookup(requestId: String, bindingKey: String): AudioTranscriptionTaskResponse
}

private fun AudioTranscriptionJob.isFinished(): Boolean = status in setOf(
    AudioTranscriptionJobStatus.READY_TO_SAVE, AudioTranscriptionJobStatus.SAVED, AudioTranscriptionJobStatus.AUDIO_CLEANED
)

private fun audioErrorMessage(code: String?, fallback: String): String = when (code) {
    "AUTH_SESSION_EXPIRED", "UNAUTHORIZED", "INVALID_TOKEN" -> "登录已失效，请重新登录后重试；录音已保留"
    "AUDIO_TRANSCRIPTION_REQUIRES_NAZHI_SERVICE" -> "录音转写需要纳知后端服务，暂不支持自带模型 API 直连；录音已保留"
    "ASR_TIMEOUT", "ASR_REQUEST_TIMEOUT" -> "语音转写超时，录音已保留，请点击重试"
    "EMPTY_TRANSCRIPT" -> "语音识别结果为空，请确认录音内容后重试"
    "TASK_CAPACITY_REACHED" -> "服务器任务已满，录音已保留，请稍后重试"
    else -> fallback.ifBlank { "转写失败，录音已保留，请点击重试" }
}
