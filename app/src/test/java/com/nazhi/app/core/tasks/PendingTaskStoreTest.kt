package com.nazhi.app.core.tasks

import com.nazhi.app.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PendingTaskStoreTest {
    @Test
    fun openingTheListAggregatesDatesWithoutStartingWorkAndHidesSupersededFailures() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture { calls.incrementAndGet(); "done" }
        try {
            fixture.organize.value = listOf(
                organize("old", "2026-10-01", "FAILED", 1), organize("new", "2026-10-01", "APPLIED", 2),
                organize("waiting", "2026-10-02", "WAITING", 3),
                organize("audio", "2026-10-03", "FAILED", 4).copy(type = "AUDIO_TRANSCRIPTION")
            )
            fixture.entries.value = listOf(entry("failed", KnowledgeIndexStatus.FAILED), entry("indexed", KnowledgeIndexStatus.INDEXED))
            fixture.messages.value = listOf(
                message("question", ChatRole.USER, ChatMessageStatus.DONE, 1),
                message("failed-answer", ChatRole.ASSISTANT, ChatMessageStatus.FAILED, 2, "question"),
                message("done-answer", ChatRole.ASSISTANT, ChatMessageStatus.DONE, 3, "question", 2)
            )
            val tasks = fixture.store.tasks.first()
            assertEquals(setOf("ORGANIZE:waiting", "INDEX:failed"), tasks.map { it.key }.toSet())
            assertEquals(PendingTaskAction.QUERY_EXISTING, tasks.first { it.kind == PendingTaskKind.ORGANIZE }.action)
            assertEquals(PendingTaskAction.RETRY_MODEL, tasks.first { it.kind == PendingTaskKind.INDEX }.action)
            assertEquals(0, calls.get())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun completedRetryRemovesLegacyFailureAndOngoingWorkHasNoRetryAction() = runBlocking {
        val fixture = Fixture { "done" }
        try {
            fixture.entries.value = listOf(entry("indexing", KnowledgeIndexStatus.INDEXING))
            fixture.messages.value = listOf(
                message("q", ChatRole.USER, ChatMessageStatus.DONE, 1),
                message("legacy-failed", ChatRole.ASSISTANT, ChatMessageStatus.FAILED, 2),
                message("modern-done", ChatRole.ASSISTANT, ChatMessageStatus.DONE, 3, "q"),
                message("q2", ChatRole.USER, ChatMessageStatus.DONE, 4),
                message("pending", ChatRole.ASSISTANT, ChatMessageStatus.PENDING, 5, "q2")
            )
            val tasks = fixture.store.tasks.first()
            assertEquals(setOf("INDEX:indexing", "CHAT:pending"), tasks.map { it.key }.toSet())
            assertTrue(tasks.all { it.action == PendingTaskAction.NONE })
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun queryConfirmationCannotBecomeAModelRetryWhenStatusChanges() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture { calls.incrementAndGet(); "done" }
        try {
            fixture.organize.value = listOf(organize("task", "2026-10-01", "WAITING", 1))
            val queried = fixture.store.tasks.first().single()
            fixture.organize.value = listOf(organize("task", "2026-10-01", "INTERRUPTED", 1))
            assertTrue(fixture.store.perform(queried).contains("状态已变化"))
            assertEquals(0, calls.get())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun duplicateClicksShareOneOperationAndLeavingTheScreenDoesNotCancelIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val fixture = Fixture { calls.incrementAndGet(); entered.complete(Unit); release.await(); "done" }
        try {
            fixture.entries.value = listOf(entry("failed", KnowledgeIndexStatus.FAILED))
            val task = fixture.store.tasks.first().single()
            val leftScreen = async { fixture.store.perform(task) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.perform(task) }
            assertEquals(setOf(task.key), fixture.store.runningKeys.value)
            leftScreen.cancelAndJoin()
            assertEquals(setOf(task.key), fixture.store.runningKeys.value)
            release.complete(Unit)
            assertEquals("done", second.await())
            assertEquals(1, calls.get())
            assertTrue(fixture.store.runningKeys.value.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    private class Fixture(operation: suspend (PendingTask) -> String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val organize = MutableStateFlow<List<PersistedAiTask>>(emptyList())
        val entries = MutableStateFlow<List<KnowledgeEntry>>(emptyList())
        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val store = PendingTaskStore(organize, entries, messages, scope, operation)
    }

    private fun organize(id: String, date: String, status: String, time: Long) = PersistedAiTask(
        id, "ORGANIZE_NOTES", "request-$id", "binding", date, status = status, createdAt = time, updatedAt = time
    )
    private fun entry(id: String, status: KnowledgeIndexStatus) = KnowledgeEntry(
        id, "note-$id", "保存的知识", IntentType.INSPIRATION, "知识标题", null, 1, "2026-10-03", 1, "2026-10-03", indexStatus = status
    )
    private fun message(id: String, role: ChatRole, status: ChatMessageStatus, time: Long, parent: String? = null, attempt: Int = 1) = ChatMessage(
        id, "session", parent, role, "问题或回答", status, null, attempt = attempt, createdAt = time, updatedAt = time
    )
}
