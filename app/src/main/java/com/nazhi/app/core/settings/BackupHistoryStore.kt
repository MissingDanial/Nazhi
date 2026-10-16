package com.nazhi.app.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.backupHistoryDataStore by preferencesDataStore(name = "backup_history")

data class BackupHistory(
    val exportedAt: Long,
    val recordCount: Int,
    val snapshotAt: Long = exportedAt,
    val fileVerified: Boolean = true
)

/** Records completed file writes, not whether the current database is fully backed up. */
class BackupHistoryStore(context: Context) {
    private val dataStore = context.applicationContext.backupHistoryDataStore

    val history = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[EXPORTED_AT]?.let { at ->
                BackupHistory(at, preferences[RECORD_COUNT] ?: 0, preferences[SNAPSHOT_AT] ?: at,
                    preferences[FILE_VERIFIED] ?: false)
            }
        }

    suspend fun recordSuccessfulExport(
        recordCount: Int,
        exportedAt: Long = System.currentTimeMillis(),
        snapshotAt: Long = exportedAt,
        fileVerified: Boolean = true
    ) {
        require(recordCount >= 0)
        require(exportedAt > 0)
        require(snapshotAt > 0)
        dataStore.edit { preferences ->
            preferences[EXPORTED_AT] = exportedAt
            preferences[RECORD_COUNT] = recordCount
            preferences[SNAPSHOT_AT] = snapshotAt
            preferences[FILE_VERIFIED] = fileVerified
        }
    }

    private companion object {
        val EXPORTED_AT = longPreferencesKey("last_successful_export_at")
        val RECORD_COUNT = intPreferencesKey("last_successful_export_record_count")
        val SNAPSHOT_AT = longPreferencesKey("last_successful_export_snapshot_at")
        val FILE_VERIFIED = booleanPreferencesKey("last_successful_export_file_verified")
    }
}
