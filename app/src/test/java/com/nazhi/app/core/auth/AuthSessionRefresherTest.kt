package com.nazhi.app.core.auth

import java.io.IOException
import com.nazhi.app.core.network.AuthSessionResponse
import com.nazhi.app.core.network.AuthUser
import com.nazhi.app.core.network.NazhiBackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AuthSessionRefresherTest {
    @Test
    fun serviceFailureAndCancellationPreserveTheSession() = runBlocking {
        val failures = listOf(
            NazhiBackendException(503, "SERVICE_UNAVAILABLE", "temporary failure"),
            CancellationException("cancelled")
        )
        for (failure in failures) {
            val original = AuthSession("user", "user@example.test", "name", "active", "access", "refresh", 100)
            var stored: AuthSession? = original
            val refresher = AuthSessionRefresher(
                currentSession = { stored },
                saveSession = { fail("A failed refresh must not write tokens") },
                expireSession = { stored = null },
                epochSeconds = { 200 }
            )
            val caught = runCatching { refresher.accessToken(refresh = { throw failure }) }.exceptionOrNull()
            assertEquals(failure.javaClass, caught?.javaClass)
            assertEquals(failure.message, caught?.message)
            assertEquals(original, stored)
        }
    }

    @Test
    fun confirmedInvalidRefreshCredentialExpiresTheSession() = runBlocking {
        for (code in listOf("INVALID_REFRESH_TOKEN", "UNAUTHORIZED")) {
            var stored: AuthSession? = AuthSession("user", "user@example.test", "name", "active", "access", "refresh", 100)
            var expireCalls = 0
            val refresher = AuthSessionRefresher(
                currentSession = { stored },
                saveSession = { fail("Invalid credentials must not write tokens") },
                expireSession = { stored = null; expireCalls++ },
                epochSeconds = { 200 }
            )
            val failure = runCatching {
                refresher.accessToken(refresh = { throw NazhiBackendException(401, code, "invalid refresh") })
            }.exceptionOrNull() as NazhiBackendException
            assertEquals("AUTH_SESSION_EXPIRED", failure.code)
            assertEquals(null, stored)
            assertEquals(1, expireCalls)
        }
    }

    @Test
    fun validAndSignedOutSessionsDoNotCallRefresh() = runBlocking {
        var stored: AuthSession? = AuthSession("user", "user@example.test", "name", "active", "access", "refresh", 1_000)
        val refresher = AuthSessionRefresher(
            currentSession = { stored },
            saveSession = { fail("No tokens need to change") },
            expireSession = { fail("No credential is invalid") },
            epochSeconds = { 200 }
        )
        assertEquals("access", refresher.accessToken(refresh = { error("Must not refresh a valid token") }))
        stored = null
        assertEquals(null, refresher.accessToken(refresh = { error("Must not refresh a signed-out session") }))
    }

    @Test
    fun requestsRejectedWithTheSameOldTokenReuseTheRotatedToken() = runBlocking {
        var stored: AuthSession? = AuthSession("user", "user@example.test", "name", "active", "old", "refresh", 500)
        var refreshCalls = 0
        val refresher = AuthSessionRefresher(
            currentSession = { stored },
            saveSession = { response -> stored = stored!!.copy(accessToken = response.accessToken, refreshToken = response.refreshToken) },
            expireSession = { stored = null },
            epochSeconds = { 200 }
        )
        repeat(2) {
            assertEquals("new", refresher.accessToken(
                refresh = {
                    refreshCalls++
                    AuthSessionResponse(AuthUser("user", "user@example.test", "name", "active"), "new", "new-refresh", 900)
                },
                force = true,
                failedAccessToken = "old"
            ))
        }
        assertEquals(1, refreshCalls)
    }

    @Test
    fun networkFailurePreservesSessionAndCanBeRetried() = runBlocking {
        val original = AuthSession("user", "user@example.test", "name", "active", "access", "refresh", 100)
        var stored: AuthSession? = original
        val refresher = AuthSessionRefresher(
            currentSession = { stored },
            saveSession = { fail("A failed refresh must not write tokens") },
            expireSession = { stored = null },
            epochSeconds = { 200 }
        )
        val disconnected = IOException("offline")
        repeat(2) {
            try {
                refresher.accessToken(refresh = { throw disconnected })
                fail("The network error must reach the caller")
            } catch (error: IOException) {
                assertEquals(disconnected.message, error.message)
            }
            assertEquals(original, stored)
        }
    }
}
