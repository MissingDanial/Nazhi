package com.nazhi.app.feature.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nazhi.app.core.settings.BackupHistory
import com.nazhi.app.core.settings.BackupHistoryStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupFileAndHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun documentWriteTruncatesPreviousBytesAndReadsBackExactSnapshot() {
        val text = "{\"notes\":[{\"content\":\"备份中的原文字🙂\"}]}\n"
        val snapshot = BackupDocumentIO.stageExport(context, text)
        val target = File(context.cacheDir, "backup-test-${UUID.randomUUID()}.json")
        try {
            target.writeText("obsolete-content".repeat(100))
            val uri = Uri.fromFile(target)
            assertTrue(BackupDocumentIO.writeDocument(context, uri, snapshot).readBackVerified)
            assertEquals(text, BackupDocumentIO.readDocument(context, uri))
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), target.length())
        } finally {
            snapshot.delete()
            target.delete()
        }
    }

    @Test
    fun stagedSnapshotCanBeReopenedByPathAfterPickerStateRestore() {
        val text = "{\"content\":\"选择保存位置期间的数据快照\"}"
        val snapshot = BackupDocumentIO.stageExport(context, text)
        try {
            val restored = File(snapshot.absolutePath)
            assertTrue(restored.isFile)
            assertEquals(text, restored.inputStream().use { BackupDocumentIO.readUtf8(it) })
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun completedExportHistorySurvivesStoreRecreationAndFailedWriteDoesNotAdvanceIt() = runBlocking {
        val historyStore = BackupHistoryStore(context)
        val at = 1_797_523_200_000L
        val snapshotAt = at - 300_000L
        historyStore.recordSuccessfulExport(23, at, snapshotAt, fileVerified = false)
        val expectedHistory = BackupHistory(at, 23, snapshotAt, fileVerified = false)
        assertEquals(expectedHistory, BackupHistoryStore(context).history.first())
        val missing = File(context.cacheDir, "missing-export-${UUID.randomUUID()}.json")
        val target = File(context.cacheDir, "backup-failed-${UUID.randomUUID()}.json")
        assertFalse(missing.exists())
        try {
            assertThrows(IllegalArgumentException::class.java) {
                BackupDocumentIO.writeDocument(context, Uri.fromFile(target), missing)
            }
            assertEquals(expectedHistory, BackupHistoryStore(context).history.first())
        } finally {
            target.delete()
        }
    }
}
