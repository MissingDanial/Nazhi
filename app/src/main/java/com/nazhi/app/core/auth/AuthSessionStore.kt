package com.nazhi.app.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nazhi.app.core.network.AuthSessionResponse
import com.nazhi.app.core.settings.EncryptedSetting
import com.nazhi.app.core.settings.EncryptedSettingsStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session"
)

class AuthSessionStore(context: Context) {
    private val dataStore = context.authSessionDataStore
    private val encryptedSettingsStore = EncryptedSettingsStore(context)

    val session: Flow<AuthSession?> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            val userId = preferences[Keys.USER_ID].orEmpty()
            val email = preferences[Keys.EMAIL].orEmpty()
            val username = preferences[Keys.USERNAME].orEmpty()
            val status = preferences[Keys.STATUS].orEmpty()
            val expiresAtEpochSeconds = preferences[Keys.EXPIRES_AT_EPOCH_SECONDS] ?: 0L
            val accessToken = encryptedSettingsStore.read(EncryptedSetting.AuthAccessToken)
            val refreshToken = encryptedSettingsStore.read(EncryptedSetting.AuthRefreshToken)
            if (userId.isBlank() || email.isBlank() || accessToken.isBlank() || refreshToken.isBlank()) {
                null
            } else {
                AuthSession(
                    userId = userId,
                    email = email,
                    username = username,
                    status = status,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtEpochSeconds = expiresAtEpochSeconds
                )
            }
        }

    suspend fun currentAccessToken(): String? {
        return session.first()?.accessToken?.takeIf { it.isNotBlank() }
    }

    suspend fun currentRefreshToken(): String? {
        return session.first()?.refreshToken?.takeIf { it.isNotBlank() }
    }

    suspend fun save(response: AuthSessionResponse) {
        encryptedSettingsStore.write(EncryptedSetting.AuthAccessToken, response.accessToken)
        encryptedSettingsStore.write(EncryptedSetting.AuthRefreshToken, response.refreshToken)
        val expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + response.expiresIn
        dataStore.edit { preferences ->
            preferences[Keys.USER_ID] = response.user.id
            preferences[Keys.EMAIL] = response.user.email
            preferences[Keys.USERNAME] = response.user.username
            preferences[Keys.STATUS] = response.user.status
            preferences[Keys.EXPIRES_AT_EPOCH_SECONDS] = expiresAtEpochSeconds
        }
    }

    suspend fun clear() {
        encryptedSettingsStore.clearAuthSessionSecrets()
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val USERNAME = stringPreferencesKey("username")
        val STATUS = stringPreferencesKey("status")
        val EXPIRES_AT_EPOCH_SECONDS = longPreferencesKey("expires_at_epoch_seconds")
    }
}

data class AuthSession(
    val userId: String,
    val email: String,
    val username: String,
    val status: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long
)
