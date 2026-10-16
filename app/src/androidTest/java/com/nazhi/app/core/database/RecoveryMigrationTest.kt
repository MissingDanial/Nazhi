package com.nazhi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NazhiDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun versionEightUpgradeRetainsNotesAndAudioAndValidatesNewSchema() {
        val name = "recovery-migration-test"
        helper.createDatabase(name, 8).apply {
            execSQL("""INSERT INTO notes(id,content,title,sourceType,sourceApp,sourceUrl,audioDurationMs,
                createdAt,createdDate,updatedAt,status,userRemark)
                VALUES('note-old','原有笔记',NULL,'MANUAL',NULL,NULL,NULL,1,'2026-10-13',1,'INBOX',NULL)""")
            execSQL("""INSERT INTO audio_transcription_jobs(id,sourceApp,backendSource,filePath,durationMs,
                byteSize,status,noteId,transcriptText,errorMessage,retryCount,lastTriedAt,createdAt,
                createdDate,updatedAt,audioDeletedAt)
                VALUES('audio-old','microphone','floating_ball','/test/retained.wav',1000,32000,
                'TRANSCRIBING',NULL,NULL,NULL,0,1,1,'2026-10-13',1,NULL)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, NazhiDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT content,status FROM notes WHERE id='note-old'").use {
                check(it.moveToFirst())
                assertEquals("原有笔记", it.getString(0))
                assertEquals("INBOX", it.getString(1))
            }
            db.query("SELECT filePath,status,requiresConfirmation FROM audio_transcription_jobs WHERE id='audio-old'").use {
                check(it.moveToFirst())
                assertEquals("/test/retained.wav", it.getString(0))
                assertEquals("TRANSCRIBING", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM local_ai_tasks").use {
                check(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
