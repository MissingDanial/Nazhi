package com.nazhi.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nazhi.app.BuildConfig
import com.nazhi.app.core.network.BackendConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.backendSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "backend_settings"
)

class BackendSettingsStore(context: Context) {
    private val dataStore = context.backendSettingsDataStore

    val defaultConfig = BackendConfig(
        baseUrl = BuildConfig.NAZHI_BACKEND_BASE_URL,
        devToken = BuildConfig.NAZHI_DEV_TOKEN
    )

    val settings: Flow<BackendConfig> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            BackendConfig(
                baseUrl = preferences[Keys.BASE_URL] ?: defaultConfig.baseUrl,
                devToken = preferences[Keys.DEV_TOKEN] ?: defaultConfig.devToken
            )
        }

    suspend fun current(): BackendConfig {
        return settings.first()
    }

    suspend fun save(config: BackendConfig) {
        dataStore.edit { preferences ->
            preferences[Keys.BASE_URL] = config.normalizedBaseUrl
            preferences[Keys.DEV_TOKEN] = config.devToken.trim()
        }
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("backend_base_url")
        val DEV_TOKEN = stringPreferencesKey("backend_dev_token")
    }
}

