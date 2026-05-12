package com.nazhi.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nazhi.app.core.database.entity.NoteEntity
import com.nazhi.app.core.model.DaySummary
import com.nazhi.app.core.model.NoteStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE status != :deletedStatus ORDER BY createdAt DESC")
    fun observeNotes(deletedStatus: NoteStatus = NoteStatus.DELETED): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE createdAt >= :startOfDay
          AND createdAt < :startOfNextDay
          AND status != :deletedStatus
        ORDER BY createdAt DESC
        """
    )
    fun observeNotesForDay(
        startOfDay: Long,
        startOfNextDay: Long,
        deletedStatus: NoteStatus = NoteStatus.DELETED
    ): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE createdDate = :date
          AND status != :deletedStatus
        ORDER BY createdAt DESC
        """
    )
    fun observeNotesForDate(
        date: String,
        deletedStatus: NoteStatus = NoteStatus.DELETED
    ): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE createdDate = :date
          AND status != :deletedStatus
        ORDER BY createdAt DESC
        """
    )
    suspend fun getNotesForDate(
        date: String,
        deletedStatus: NoteStatus = NoteStatus.DELETED
    ): List<NoteEntity>

    @Query(
        """
        SELECT * FROM notes
        WHERE createdDate < :date
          AND status = :pendingStatus
        ORDER BY createdAt DESC
        """
    )
    fun observePendingNotesBeforeDate(
        date: String,
        pendingStatus: NoteStatus = NoteStatus.INBOX
    ): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM notes
        WHERE createdDate < :date
          AND status = :pendingStatus
        """
    )
    fun observePendingCountBeforeDate(
        date: String,
        pendingStatus: NoteStatus = NoteStatus.INBOX
    ): Flow<Int>

    @Query(
        """
        SELECT createdDate AS date,
               COUNT(*) AS totalCount,
               CAST(SUM(CASE WHEN status = 'INBOX' THEN 1 ELSE 0 END) AS INTEGER) AS pendingCount,
               CAST(SUM(CASE WHEN status = 'REVIEWED' THEN 1 ELSE 0 END) AS INTEGER) AS reviewedCount
        FROM notes
        WHERE createdDate >= :startDate
          AND createdDate < :endDate
          AND status != :deletedStatus
        GROUP BY createdDate
        ORDER BY createdDate DESC
        """
    )
    fun observeDaySummaries(
        startDate: String,
        endDate: String,
        deletedStatus: NoteStatus = NoteStatus.DELETED
    ): Flow<List<DaySummary>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: NoteStatus, updatedAt: Long)

    @Query(
        """
        UPDATE notes
        SET content = :content,
            title = :title,
            sourceUrl = :sourceUrl,
            userRemark = :userRemark,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateContent(
        id: String,
        content: String,
        title: String?,
        sourceUrl: String?,
        userRemark: String?,
        updatedAt: Long
    )
}
