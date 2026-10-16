package com.nazhi.app.core.data

import com.nazhi.app.core.audio.AudioTaskProcessor
import com.nazhi.app.core.repository.NazhiRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recovery queries accepted work; creating a new model call stays a user action. */
class TaskRecoveryCoordinator(
    private val repository: NazhiRepository,
    private val audioProcessor: AudioTaskProcessor,
    private val scope: CoroutineScope
) {
    private val initialized = CompletableDeferred<Unit>()
    private val scanMutex = Mutex()
    private val active = mutableMapOf<String, Job>()

    init {
        scope.launch {
            try {
                repository.normalizeInterruptedOperations()
                initialized.complete(Unit)
                requestRecovery()
            } catch (error: Throwable) {
                initialized.completeExceptionally(error)
            }
        }
    }

    fun requestRecovery() {
        scope.launch {
            initialized.await()
            scanMutex.withLock {
                active.entries.removeAll { !it.value.isActive }
                repository.getRecoverableAiTasks().forEach { task ->
                    if (active[task.id]?.isActive != true) {
                        active[task.id] = scope.launch {
                            // Each processor records a recoverable error without submitting new input.
                            runCatching {
                                when (task.type) {
                                    "AUDIO_TRANSCRIPTION" -> audioProcessor.resume(task.id)
                                    "ORGANIZE_NOTES" -> repository.recoverOrganizeTask(task)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
