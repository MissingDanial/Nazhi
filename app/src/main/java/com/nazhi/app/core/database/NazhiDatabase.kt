package com.nazhi.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nazhi.app.core.database.dao.ChatCitationDao
import com.nazhi.app.core.database.dao.ChatMessageDao
import com.nazhi.app.core.database.dao.ChatSessionDao
import com.nazhi.app.core.database.dao.EmbeddingDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDraftDao
import com.nazhi.app.core.database.dao.KnowledgeEntryDao
import com.nazhi.app.core.database.dao.NoteDao
import com.nazhi.app.core.database.dao.ReviewSessionDao
import com.nazhi.app.core.database.entity.ChatCitationEntity
import com.nazhi.app.core.database.entity.ChatMessageEntity
import com.nazhi.app.core.database.entity.ChatSessionEntity
import com.nazhi.app.core.database.entity.EmbeddingRecordEntity
import com.nazhi.app.core.database.entity.KnowledgeEntryDraftEntity
import com.nazhi.app.core.database.entity.KnowledgeEntryEntity
import com.nazhi.app.core.database.entity.NoteEntity
import com.nazhi.app.core.database.entity.ReviewSessionEntity

@Database(
    entities = [
        NoteEntity::class,
        KnowledgeEntryEntity::class,
        KnowledgeEntryDraftEntity::class,
        ReviewSessionEntity::class,
        EmbeddingRecordEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatCitationEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(NazhiTypeConverters::class)
abstract class NazhiDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun knowledgeEntryDao(): KnowledgeEntryDao
    abstract fun knowledgeEntryDraftDao(): KnowledgeEntryDraftDao
    abstract fun reviewSessionDao(): ReviewSessionDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatCitationDao(): ChatCitationDao

    companion object {
        private const val DATABASE_NAME = "nazhi.db"

        fun create(context: Context): NazhiDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NazhiDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN createdDate TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE notes
                    SET createdDate = strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime')
                    WHERE createdDate = ''
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_createdDate ON notes(createdDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_status ON notes(status)")

                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN createdDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN confirmedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE knowledge_entries
                    SET createdDate = strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime')
                    WHERE createdDate = ''
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE knowledge_entries
                    SET confirmedDate = strftime('%Y-%m-%d', confirmedAt / 1000, 'unixepoch', 'localtime')
                    WHERE confirmedDate = ''
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_entries_createdDate ON knowledge_entries(createdDate)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_entries_confirmedDate ON knowledge_entries(confirmedDate)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS embedding_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        ownerType TEXT NOT NULL,
                        ownerId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        textHash TEXT NOT NULL,
                        model TEXT NOT NULL,
                        dimensions INTEGER NOT NULL,
                        precision TEXT NOT NULL,
                        vectorBlob BLOB NOT NULL,
                        vectorNorm REAL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_embedding_records_ownerType_ownerId_model_chunkIndex
                    ON embedding_records(ownerType, ownerId, model, chunkIndex)
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_embedding_records_ownerType ON embedding_records(ownerType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_embedding_records_ownerId ON embedding_records(ownerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_embedding_records_textHash ON embedding_records(textHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_embedding_records_model ON embedding_records(model)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN sourceNoteIds TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE knowledge_entries ADD COLUMN indexStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL(
                    """
                    UPDATE knowledge_entries
                    SET sourceNoteIds = '["' || noteId || '"]'
                    WHERE sourceNoteIds = '[]' AND noteId != ''
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_entries_indexStatus ON knowledge_entries(indexStatus)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_entry_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        date TEXT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        content TEXT NOT NULL,
                        intentType TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        sourceNoteIds TEXT NOT NULL,
                        evidenceQuotes TEXT NOT NULL,
                        insight TEXT,
                        confidence REAL NOT NULL,
                        needsReview INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_entry_drafts_date ON knowledge_entry_drafts(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_entry_drafts_status ON knowledge_entry_drafts(status)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        status TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_createdAt ON chat_messages(createdAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_citations (
                        id TEXT NOT NULL PRIMARY KEY,
                        messageId TEXT NOT NULL,
                        knowledgeEntryId TEXT NOT NULL,
                        sourceNoteIds TEXT NOT NULL,
                        quote TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        score REAL NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(messageId) REFERENCES chat_messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_citations_messageId ON chat_citations(messageId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_citations_knowledgeEntryId ON chat_citations(knowledgeEntryId)"
                )
            }
        }
    }
}
