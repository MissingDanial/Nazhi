package com.nazhi.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nazhi.app.BuildConfig
import com.nazhi.app.core.network.AiServiceMode
import com.nazhi.app.core.network.AiVendor
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
    private val encryptedSettingsStore = EncryptedSettingsStore(context)

    val defaultConfig = BackendConfig(
        baseUrl = BuildConfig.NAZHI_BACKEND_BASE_URL,
        devToken = BuildConfig.NAZHI_DEV_TOKEN,
        serviceMode = AiServiceMode.NAZHI,
        vendor = AiVendor.MINIMAX,
        directApiBaseUrl = "",
        directApiKey = "",
        directChatModel = "",
        directEmbeddingApiBaseUrl = "",
        directEmbeddingApiKey = "",
        directEmbeddingModel = "",
        directExtraId = ""
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
                baseUrl = preferences[Keys.BASE_URL].nonBlankOrDefault(defaultConfig.baseUrl),
                devToken = preferences[Keys.DEV_TOKEN].nonBlankOrDefault(defaultConfig.devToken),
                serviceMode = preferences[Keys.SERVICE_MODE].toEnumOrDefault(defaultConfig.serviceMode),
                vendor = preferences[Keys.VENDOR].toEnumOrDefault(defaultConfig.vendor),
                directApiBaseUrl = preferences[Keys.DIRECT_API_BASE_URL] ?: defaultConfig.directApiBaseUrl,
                directApiKey = encryptedSettingsStore.read(
                    setting = EncryptedSetting.DirectApiKey,
                    plaintextFallback = preferences[Keys.DIRECT_API_KEY]
                ),
                directChatModel = preferences[Keys.DIRECT_CHAT_MODEL] ?: defaultConfig.directChatModel,
                directEmbeddingApiBaseUrl = preferences[Keys.DIRECT_EMBEDDING_API_BASE_URL] ?: defaultConfig.directEmbeddingApiBaseUrl,
                directEmbeddingApiKey = encryptedSettingsStore.read(
                    setting = EncryptedSetting.DirectEmbeddingApiKey,
                    plaintextFallback = preferences[Keys.DIRECT_EMBEDDING_API_KEY]
                ),
                directEmbeddingModel = preferences[Keys.DIRECT_EMBEDDING_MODEL] ?: defaultConfig.directEmbeddingModel,
                directExtraId = preferences[Keys.DIRECT_EXTRA_ID] ?: defaultConfig.directExtraId
            )
        }

    suspend fun current(): BackendConfig {
        return settings.first()
    }

    suspend fun save(config: BackendConfig) {
        encryptedSettingsStore.write(EncryptedSetting.DirectApiKey, config.directApiKey)
        encryptedSettingsStore.write(EncryptedSetting.DirectEmbeddingApiKey, config.directEmbeddingApiKey)
        dataStore.edit { preferences ->
            config.normalizedBaseUrl.takeIf { it.isNotBlank() }?.let { value ->
                preferences[Keys.BASE_URL] = value
            } ?: preferences.remove(Keys.BASE_URL)
            config.devToken.trim().takeIf { it.isNotBlank() }?.let { value ->
                preferences[Keys.DEV_TOKEN] = value
            } ?: preferences.remove(Keys.DEV_TOKEN)
            preferences[Keys.SERVICE_MODE] = config.serviceMode.name
            preferences[Keys.VENDOR] = config.vendor.name
            preferences[Keys.DIRECT_API_BASE_URL] = config.normalizedDirectApiBaseUrl
            preferences[Keys.DIRECT_CHAT_MODEL] = config.directChatModel.trim()
            preferences[Keys.DIRECT_EMBEDDING_API_BASE_URL] = config.normalizedDirectEmbeddingApiBaseUrl
            preferences[Keys.DIRECT_EMBEDDING_MODEL] = config.directEmbeddingModel.trim()
            preferences[Keys.DIRECT_EXTRA_ID] = config.directExtraId.trim()
            preferences.remove(Keys.DIRECT_API_KEY)
            preferences.remove(Keys.DIRECT_EMBEDDING_API_KEY)
        }
    }

    suspend fun clearDirectApiConfig() {
        encryptedSettingsStore.clearDirectApiSecrets()
        dataStore.edit { preferences ->
            preferences[Keys.SERVICE_MODE] = AiServiceMode.NAZHI.name
            preferences[Keys.VENDOR] = defaultConfig.vendor.name
            preferences.remove(Keys.DIRECT_API_BASE_URL)
            preferences.remove(Keys.DIRECT_API_KEY)
            preferences.remove(Keys.DIRECT_CHAT_MODEL)
            preferences.remove(Keys.DIRECT_EMBEDDING_API_BASE_URL)
            preferences.remove(Keys.DIRECT_EMBEDDING_API_KEY)
            preferences.remove(Keys.DIRECT_EMBEDDING_MODEL)
            preferences.remove(Keys.DIRECT_EXTRA_ID)
        }
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("backend_base_url")
        val DEV_TOKEN = stringPreferencesKey("backend_dev_token")
        val SERVICE_MODE = stringPreferencesKey("ai_service_mode")
        val VENDOR = stringPreferencesKey("ai_vendor")
        val DIRECT_API_BASE_URL = stringPreferencesKey("direct_api_base_url")
        val DIRECT_API_KEY = stringPreferencesKey("direct_api_key")
        val DIRECT_CHAT_MODEL = stringPreferencesKey("direct_chat_model")
        val DIRECT_EMBEDDING_API_BASE_URL = stringPreferencesKey("direct_embedding_api_base_url")
        val DIRECT_EMBEDDING_API_KEY = stringPreferencesKey("direct_embedding_api_key")
        val DIRECT_EMBEDDING_MODEL = stringPreferencesKey("direct_embedding_model")
        val DIRECT_EXTRA_ID = stringPreferencesKey("direct_extra_id")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(defaultValue: T): T {
    return this?.let { value ->
        enumValues<T>().firstOrNull { it.name == value }
    } ?: defaultValue
}

private fun String?.nonBlankOrDefault(defaultValue: String): String {
    return this?.trim()?.takeIf { it.isNotBlank() } ?: defaultValue
}
