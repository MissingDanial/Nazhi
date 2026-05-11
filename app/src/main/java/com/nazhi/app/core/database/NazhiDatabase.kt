package com.nazhi.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nazhi.app.core.database.dao.KnowledgeEntryDao
import com.nazhi.app.core.database.dao.NoteDao
import com.nazhi.app.core.database.dao.ReviewSessionDao
import com.nazhi.app.core.database.entity.KnowledgeEntryEntity
import com.nazhi.app.core.database.entity.NoteEntity
import com.nazhi.app.core.database.entity.ReviewSessionEntity

@Database(
    entities = [
        NoteEntity::class,
        KnowledgeEntryEntity::class,
        ReviewSessionEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(NazhiTypeConverters::class)
abstract class NazhiDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun knowledgeEntryDao(): KnowledgeEntryDao
    abstract fun reviewSessionDao(): ReviewSessionDao

    companion object {
        private const val DATABASE_NAME = "nazhi.db"

        fun create(context: Context): NazhiDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NazhiDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
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
    }
}
