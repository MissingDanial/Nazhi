package com.nazhi.app.feature.settings

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDocumentIOTest {
    @Test
    fun restoredWritingStateWithoutProcessWriterIsInterruptedEvenWithCachedSnapshot() {
        val snapshot = File.createTempFile("backup-recovery-", ".json")
        try {
            snapshot.writeText("{\"notes\":[]}")
            assertEquals(BackupDocumentIO.PendingRecovery.INTERRUPTED,
                BackupDocumentIO.pendingRecovery(snapshot.absolutePath, BackupDocumentIO.PHASE_WRITING))
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun activityRecreationWaitsForRegisteredWriterOnlyUntilItFinishes() {
        val path = "backup-${UUID.randomUUID()}"
        try {
            BackupDocumentIO.registerWrite(path)
            assertEquals(BackupDocumentIO.PendingRecovery.WAIT_FOR_WRITE,
                BackupDocumentIO.pendingRecovery(path, BackupDocumentIO.PHASE_WRITING))
            BackupDocumentIO.unregisterWrite(path)
            assertEquals(BackupDocumentIO.PendingRecovery.INTERRUPTED,
                BackupDocumentIO.pendingRecovery(path, BackupDocumentIO.PHASE_WRITING))
        } finally {
            BackupDocumentIO.unregisterWrite(path)
        }
    }

    @Test
    fun restoredPickerStateCanStillReceiveActivityResultWithoutAWriter() {
        assertEquals(BackupDocumentIO.PendingRecovery.WAIT_FOR_PICKER,
            BackupDocumentIO.pendingRecovery("picker-snapshot", BackupDocumentIO.PHASE_PICKER))
        assertEquals(BackupDocumentIO.PendingRecovery.NONE, BackupDocumentIO.pendingRecovery(null, null))
    }

    @Test
    fun readsUtf8WithoutAlteringChineseOrNewlines() {
        val text = "{\"文字\":\"自己的记录🙂\\n下一行\"}\n"
        assertEquals(text, BackupDocumentIO.readUtf8(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingIt() {
        val bytes = byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentIO.readUtf8(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun rejectsFileOneByteAboveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentIO.readUtf8(ByteArrayInputStream(ByteArray(9) { 'a'.code.toByte() }), maxBytes = 8)
        }
    }

    @Test
    fun acceptsExactLimitAndCountsUtf8Bytes() {
        val text = "中文"
        assertEquals(text, BackupDocumentIO.readUtf8(ByteArrayInputStream(text.toByteArray()), maxBytes = 6))
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocumentIO.readUtf8(ByteArrayInputStream(text.toByteArray()), maxBytes = 5)
        }
    }
}
