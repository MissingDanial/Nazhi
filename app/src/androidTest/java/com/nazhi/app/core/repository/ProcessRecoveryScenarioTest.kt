package com.nazhi.app.core.repository

import com.nazhi.app.NazhiApp
import com.nazhi.app.core.model.AudioTranscriptionJob
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.PersistedAiTask
import com.nazhi.app.core.network.BackendConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual two-phase check on a disposable emulator; ordinary suites skip this scenario. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryScenarioTest {
    @Test
    fun acceptedJobsSurviveARealProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val app = ApplicationProvider.getApplicationContext<NazhiApp>()
        val container = app.appContainer
        val repository = container.repository
        val date = "2026-10-12"
        if (phase == "seed") {
            val port = InstrumentationRegistry.getArguments().getString("recoveryPort")!!.toInt()
            container.backendSettingsStore.save(BackendConfig("http://10.0.2.2:$port", "test-recovery"))
            // Allow startup/settings-triggered empty scans to finish before planting the interruption.
            delay(2000)
            val binding = container.backendClient.currentTaskBinding(forAudio = true)
            for ((id, keepRemoteId) in listOf("lost-create-response" to false, "finished-before-save" to true, "network-interruption" to true)) {
                val audio = File(app.filesDir, "$id.wav").apply { writeText("retained test audio") }
                val time = System.currentTimeMillis()
                repository.saveAudioTranscriptionJob(AudioTranscriptionJob(
                    id = id, sourceApp = "测试录音", backendSource = "floating_ball",
                    filePath = audio.absolutePath, durationMs = 1000, byteSize = audio.length(),
                    status = AudioTranscriptionJobStatus.UPLOADING,
                    createdAt = time, createdDate = date, updatedAt = time
                ))
                val task = PersistedAiTask(id, "AUDIO_TRANSCRIPTION", id, binding, date,
                    createdAt = time, updatedAt = time)
                repository.savePersistedAiTask(task)
                val remote = container.backendClient.createAudioTranscriptionJob(
                    audio, 1000, requestId = id, expectedBinding = binding
                )
                // A crash here loses either the response or the result application, respectively.
                if (keepRemoteId) repository.savePersistedAiTask(task.copy(
                    backendTaskId = remote.taskId, status = "RUNNING"
                ))
                if (id == "network-interruption") {
                    container.audioTaskProcessor.resume(id)
                    assertEquals("WAITING", repository.getPersistedAiTask(id)?.status)
                    assertEquals(AudioTranscriptionJobStatus.FAILED, repository.getAudioTranscriptionJob(id)?.status)
                }
            }
        } else {
            container.taskRecoveryCoordinator.requestRecovery()
            for (id in listOf("lost-create-response", "finished-before-save", "network-interruption")) {
                repeat(100) {
                    if (repository.getAudioTranscriptionJob(id)?.status != AudioTranscriptionJobStatus.SAVED) delay(100)
                }
                val job = repository.getAudioTranscriptionJob(id)!!
                assertEquals(AudioTranscriptionJobStatus.SAVED, job.status)
                assertEquals("APPLIED", repository.getPersistedAiTask(id)?.status)
                assertTrue(File(job.filePath).exists())
                assertEquals("测试恢复结果 $id", repository.getNote(job.noteId!!)?.content)
                // A second result application, including a reopened app, preserves the edited note.
                repository.updateNoteContent(job.noteId, "已编辑 $id", null, null, null, System.currentTimeMillis())
                repository.storeAudioTranscript(id, "测试恢复结果 $id")
                assertEquals("已编辑 $id", repository.getNote(job.noteId)?.content)
            }
            assertEquals(3, repository.observeNotesForDate(date).first().size)
        }
    }
}
