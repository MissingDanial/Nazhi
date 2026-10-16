package com.nazhi.app.core.auth

import com.nazhi.app.core.network.AuthSessionResponse
import com.nazhi.app.core.network.NazhiBackendException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes token rotation and keeps persistence outside the refresh decision. */
internal class AuthSessionRefresher(
    private val currentSession: suspend () -> AuthSession?,
    private val saveSession: suspend (AuthSessionResponse) -> Unit,
    private val expireSession: suspend () -> Unit,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private val mutex = Mutex()

    suspend fun accessToken(
        refresh: suspend (String) -> AuthSessionResponse,
        force: Boolean = false,
        refreshSkewSeconds: Long = 60,
        failedAccessToken: String? = null
    ): String? = mutex.withLock {
        val current = currentSession() ?: return@withLock null
        if (force && failedAccessToken != null && current.accessToken != failedAccessToken) {
            // Another request already rotated the token while this request waited.
            return@withLock current.accessToken
        }
        if (!force && current.expiresAtEpochSeconds > epochSeconds() + refreshSkewSeconds) {
            return@withLock current.accessToken
        }
        val response = try {
            refresh(current.refreshToken)
        } catch (error: Throwable) {
            if (error is NazhiBackendException && error.statusCode == 401 &&
                error.code in setOf("INVALID_REFRESH_TOKEN", "UNAUTHORIZED")) {
                expireSession()
                throw NazhiBackendException(401, "AUTH_SESSION_EXPIRED", "登录已过期，请重新登录。")
            }
            // Network failures, service errors and cancellation do not invalidate
            // the refresh credential. Keep it so the next attempt can succeed.
            throw error
        }
        saveSession(response)
        response.accessToken
    }
}
