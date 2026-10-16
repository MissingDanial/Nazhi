package com.nazhi.app.core.audio

import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.network.AudioTranscriptionResult
import com.nazhi.app.core.network.AudioTranscriptionTaskResponse
import com.nazhi.app.core.network.NazhiBackendException
import com.nazhi.app.core.repository.NazhiRepository
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioTaskProcessorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `identity is durable before upload and repeated submit does not call provider`() = runBlocking {
        val store = store()
        val remote = Remote()
        remote.createResponse = {
            assertEquals(it, store.task?.requestId)
            assertEquals("CREATED", store.task?.status)
            success(it)
        }
        val processor = processor(store, remote)
        assertEquals(AudioTranscriptionJobStatus.READY_TO_SAVE, processor.submit("audio").status)
        assertEquals("APPLIED", store.task?.status)
        processor.submit("audio")
        assertEquals(1, remote.creates)
        assertEquals(1, store.applications)
    }

    @Test fun `lost upload response recovers with original request lookup and no second upload`() = runBlocking {
        val store = store()
        val remote = Remote().apply { createResponse = { throw IOException("response lost") } }
        processor(store, remote).submit("audio")
        val request = requireNotNull(store.task).requestId
        assertEquals("WAITING", store.task?.status)
        assertNull(store.task?.backendTaskId)
        remote.lookupResponse = { success(it) }
        processor(store, remote).resume("audio")
        assertEquals(request, remote.lookedUp)
        assertEquals(1, remote.creates)
        assertEquals(1, store.applications)
    }

    @Test fun `cancelled polling preserves accepted task identity for the next process`() = runBlocking {
        val store = store()
        val remote = Remote().apply { createResponse = { running(it) } }
        try {
            AudioTaskProcessor(store.repository, remote, waitForPoll = { throw CancellationException("killed") }).submit("audio")
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals("remote-audio", store.task?.backendTaskId)
        assertEquals("WAITING", store.task?.status)
        remote.queryResponse = { success(requireNotNull(store.task).requestId) }
        processor(store, remote).resume("audio")
        assertEquals(1, remote.creates)
        assertEquals(1, remote.queries)
        assertEquals(AudioTranscriptionJobStatus.READY_TO_SAVE, store.job.status)
    }

    @Test fun `missing server task becomes explicit retry and keeps the file`() = runBlocking {
        val store = store().apply { task = savedTask() }
        val remote = Remote().apply { queryResponse = { throw NazhiBackendException(404, "TASK_NOT_FOUND", "lost") } }
        processor(store, remote).resume("audio")
        assertEquals("INTERRUPTED", store.task?.status)
        assertTrue(store.job.errorMessage.orEmpty().contains("服务器任务已丢失"))
        assertTrue(java.io.File(store.job.filePath).exists())
        assertEquals(0, remote.creates)
        val oldRequest = store.task?.requestId
        processor(store, remote).submit("audio")
        assertNotEquals(oldRequest, store.task?.requestId)
        assertEquals(1, remote.creates)
    }

    @Test fun `switching accounts blocks both recovery and manual resubmission`() = runBlocking {
        val store = store().apply { task = savedTask().copy(status = "FAILED") }
        val remote = Remote().apply { currentBinding = "backend:other-user" }
        processor(store, remote).submit("audio")
        assertEquals("FAILED", store.task?.status)
        assertTrue(store.job.errorMessage.orEmpty().contains("切回"))
        assertEquals(0, remote.creates + remote.queries + remote.lookups)
    }

    @Test fun `local commit failure retries applying the same remote result`() = runBlocking {
        val store = store().apply { failNextApplication = true }
        val remote = Remote()
        val processor = processor(store, remote)
        processor.submit("audio")
        assertEquals("WAITING", store.task?.status)
        assertEquals("remote-audio", store.task?.backendTaskId)
        remote.queryResponse = { success(requireNotNull(store.task).requestId) }
        processor.submit("audio")
        assertEquals(1, remote.creates)
        assertEquals(1, store.applications)
        assertEquals("APPLIED", store.task?.status)
    }

    @Test fun `concurrent foreground submit and startup recovery share one task`() = runBlocking {
        val store = store()
        val remote = Remote().apply { createResponse = { delay(30); success(it) } }
        val processor = processor(store, remote)
        val foreground = async { processor.submit("audio") }
        val recovery = async { processor.resume("audio") }
        foreground.await()
        recovery.await()
        assertEquals(1, remote.creates)
        assertEquals(1, store.applications)
    }

    @Test fun `the last allowed poll can still apply a successful result`() = runBlocking {
        val store = store()
        val remote = Remote().apply {
            createResponse = { running(it) }
            queryResponse = { success(requireNotNull(store.task).requestId) }
        }
        AudioTaskProcessor(store.repository, remote, pollingLimit = 1, waitForPoll = {}).submit("audio")
        assertEquals("APPLIED", store.task?.status)
        assertEquals(1, remote.queries)
    }

    @Test fun `unfinished polling leaves a queryable waiting task without a permanent spinner`() = runBlocking {
        val store = store()
        val remote = Remote().apply { createResponse = { running(it) } }
        AudioTaskProcessor(store.repository, remote, pollingLimit = 0, waitForPoll = {}).submit("audio")
        assertEquals("WAITING", store.task?.status)
        assertEquals(AudioTranscriptionJobStatus.FAILED, store.job.status)
        assertTrue(store.job.canRetry)
        assertEquals(0, remote.queries)
    }

    @Test fun `expired login keeps remote identity and retries only the query`() = runBlocking {
        val store = store().apply { task = savedTask() }
        val remote = Remote().apply { queryResponse = { throw NazhiBackendException(401, "UNAUTHORIZED", "expired") } }
        val processor = processor(store, remote)
        processor.resume("audio")
        assertEquals("WAITING", store.task?.status)
        remote.queryResponse = { success(requireNotNull(store.task).requestId) }
        processor.submit("audio")
        assertEquals(0, remote.creates)
        assertEquals("APPLIED", store.task?.status)
    }

    @Test fun `legacy recording without remote identity never uploads during recovery`() = runBlocking {
        val store = store()
        val remote = Remote()
        processor(store, remote).resume("audio")
        assertEquals(AudioTranscriptionJobStatus.FAILED, store.job.status)
        assertEquals(0, remote.creates + remote.queries + remote.lookups)
    }

    @Test fun `HTTP latency consumes the polling deadline rather than adding another polling budget`() = runBlocking {
        val store = store()
        var clock = 0L
        val remote = Remote().apply {
            createResponse = { running(it) }
            queryResponse = { clock += 300_000L; running(requireNotNull(store.task).requestId) }
        }
        AudioTaskProcessor(store.repository, remote, waitForPoll = {}, elapsedNow = { clock }).submit("audio")
        assertEquals(1, remote.queries)
        assertEquals("WAITING", store.task?.status)
    }

    @Test fun `a response for another request cannot overwrite the recording`() = runBlocking {
        val store = store().apply { task = savedTask() }
        val remote = Remote().apply { queryResponse = { success("other-request") } }
        processor(store, remote).resume("audio")
        assertEquals(0, store.applications)
        assertEquals("WAITING", store.task?.status)
        assertEquals("original-request", store.task?.requestId)
    }

    @Test fun `account switch after response blocks application of the result`() = runBlocking {
        val store = store().apply { task = savedTask() }
        val remote = Remote().apply {
            queryResponse = { currentBinding = "backend:other-user"; success("original-request") }
        }
        processor(store, remote).resume("audio")
        assertEquals(0, store.applications)
        assertEquals("WAITING", store.task?.status)
    }

    private fun store(): Store = Store(AudioTranscriptionJob(
        id = "audio", sourceApp = "麦克风转写", backendSource = "in_app_mic",
        filePath = temporary.newFile().apply { writeText("retained recording") }.absolutePath,
        durationMs = 1000L, byteSize = 12L, status = AudioTranscriptionJobStatus.PENDING,
        createdAt = 1L, createdDate = "2026-10-13", updatedAt = 1L, requiresConfirmation = true
    ))

    private fun savedTask() = PersistedAiTask(
        id = "audio", type = "AUDIO_TRANSCRIPTION", requestId = "original-request",
        bindingKey = "backend:user", date = "2026-10-13", backendTaskId = "remote-audio",
        status = "RUNNING", createdAt = 1L, updatedAt = 1L
    )

    private fun processor(store: Store, remote: Remote) =
        AudioTaskProcessor(store.repository, remote, pollingLimit = 1, waitForPoll = {})

    private class Store(var job: AudioTranscriptionJob) {
        var task: PersistedAiTask? = null
        var applications = 0
        var failNextApplication = false
        val repository = Proxy.newProxyInstance(
            NazhiRepository::class.java.classLoader, arrayOf(NazhiRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAudioTranscriptionJob" -> job
                "getPersistedAiTask" -> task
                "savePersistedAiTask" -> { task = args[0] as PersistedAiTask; Unit }
                "updateAudioTranscriptionJobStatus" -> {
                    job = job.copy(status = args[1] as AudioTranscriptionJobStatus,
                        errorMessage = args[2] as String?, retryCount = job.retryCount + (args[3] as Int))
                    Unit
                }
                "storeAudioTranscript" -> {
                    if (failNextApplication) { failNextApplication = false; throw IllegalStateException("disk full") }
                    applications++
                    job = job.copy(status = AudioTranscriptionJobStatus.READY_TO_SAVE, transcriptText = args[1] as String)
                    task = task?.copy(status = "APPLIED")
                    job
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as NazhiRepository
    }

    private class Remote : AudioTaskTransport {
        var creates = 0
        var queries = 0
        var lookups = 0
        var lookedUp: String? = null
        var currentBinding = "backend:user"
        var createResponse: suspend (String) -> AudioTranscriptionTaskResponse = { success(it) }
        var queryResponse: suspend (String) -> AudioTranscriptionTaskResponse = { running("request") }
        var lookupResponse: suspend (String) -> AudioTranscriptionTaskResponse = { running(it) }
        override suspend fun binding() = currentBinding
        override suspend fun create(job: AudioTranscriptionJob, requestId: String, bindingKey: String): AudioTranscriptionTaskResponse {
            assertEquals(currentBinding, bindingKey)
            creates++
            return createResponse(requestId)
        }
        override suspend fun query(taskId: String, bindingKey: String): AudioTranscriptionTaskResponse {
            assertEquals(currentBinding, bindingKey)
            queries++
            return queryResponse(taskId)
        }
        override suspend fun lookup(requestId: String, bindingKey: String): AudioTranscriptionTaskResponse {
            assertEquals(currentBinding, bindingKey)
            lookups++
            lookedUp = requestId
            return lookupResponse(requestId)
        }
    }

    companion object {
        private fun running(requestId: String) = AudioTranscriptionTaskResponse(
            taskId = "remote-audio", requestId = requestId, type = "AUDIO_TRANSCRIPTION",
            status = "RUNNING", stage = "TRANSCRIBING"
        )
        private fun success(requestId: String) = running(requestId).copy(
            status = "SUCCEEDED", result = AudioTranscriptionResult(text = "这是可靠保存的转写文本")
        )
    }
}
