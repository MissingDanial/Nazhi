package com.nazhi.app.core.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NazhiBackendClientTest {
    @Test
    fun v1GetPostAndMultipartRefreshOnceAndReplayTheUserRequest() = runBlocking {
        for (method in listOf("GET", "POST", "MULTIPART")) {
            withServer { server ->
                val refreshCalls = AtomicInteger()
                val expiredCalls = AtomicInteger()
                server.respond = { request ->
                    if (request.authorization == "Bearer old-token") {
                        Reply(401, unauthorized)
                    } else {
                        Reply(200, when (method) {
                            "GET" -> """{"ok":true}"""
                            "POST" -> validEmbedding
                            else -> validTask
                        })
                    }
                }
                val client = client(
                    server,
                    tokenProvider = { "old-token" },
                    refresher = { failedToken ->
                        assertEquals("old-token", failedToken)
                        refreshCalls.incrementAndGet()
                        "new-token"
                    },
                    onExpired = { expiredCalls.incrementAndGet() }
                )
                when (method) {
                    "GET" -> assertTrue(client.checkAuth().ok)
                    "POST" -> assertEquals(2, client.createEmbeddings("req-1", inputs).dimensions)
                    else -> {
                        val audio = File.createTempFile("nazhi-client", ".wav")
                        try {
                            audio.writeText("test audio bytes")
                            assertEquals("task-1", client.createAudioTranscriptionJob(audio, 1_000).taskId)
                        } finally {
                            audio.delete()
                        }
                    }
                }
                assertEquals(1, refreshCalls.get())
                assertEquals(0, expiredCalls.get())
                assertEquals(listOf("Bearer old-token", "Bearer new-token"), server.requests.map { it.authorization })
                assertEquals(2, server.requests.size)
                assertEquals(server.requests[0].body, server.requests[1].body)
                assertTrue(server.requests.none { it.authorization == "Bearer developer-token" })
            }
        }
    }

    @Test
    fun repeated401StopsAfterOneRetryAndExpiresTheSession() = runBlocking {
        for (userEndpoint in listOf(false, true)) {
            withServer { server ->
                val refreshCalls = AtomicInteger()
                val expiredCalls = AtomicInteger()
                server.respond = { Reply(401, unauthorized) }
                val client = client(
                    server,
                    tokenProvider = { "old-token" },
                    refresher = { failedToken ->
                        assertEquals("old-token", failedToken)
                        refreshCalls.incrementAndGet()
                        "new-token"
                    },
                    onExpired = { expiredCalls.incrementAndGet() }
                )
                expectBackendFailure("AUTH_SESSION_EXPIRED", 401) {
                    if (userEndpoint) client.currentUser() else client.checkAuth()
                }
                assertEquals(1, refreshCalls.get())
                assertEquals(1, expiredCalls.get())
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun v2GetAndPostRefreshTheFailedTokenAndRetryOnce() = runBlocking {
        for (method in listOf("GET", "POST")) {
            withServer { server ->
                val refreshCalls = AtomicInteger()
                val expiredCalls = AtomicInteger()
                server.respond = { request ->
                    if (request.authorization == "Bearer old-token") {
                        Reply(401, unauthorized)
                    } else {
                        Reply(200, if (method == "GET") {
                            """{"user":{"id":"user-1","email":"test@example.test","username":"test","status":"active"}}"""
                        } else {
                            """{"ok":true}"""
                        })
                    }
                }
                val client = client(
                    server,
                    tokenProvider = { "old-token" },
                    refresher = { failedToken ->
                        assertEquals("old-token", failedToken)
                        refreshCalls.incrementAndGet()
                        "new-token"
                    },
                    onExpired = { expiredCalls.incrementAndGet() }
                )
                if (method == "GET") {
                    assertEquals("user-1", client.currentUser().user.id)
                } else {
                    assertTrue(client.changePassword("old-password", "new-password").ok)
                }
                assertEquals(1, refreshCalls.get())
                assertEquals(0, expiredCalls.get())
                assertEquals(listOf("Bearer old-token", "Bearer new-token"), server.requests.map { it.authorization })
                assertEquals(server.requests[0].body, server.requests[1].body)
            }
        }
    }

    @Test
    fun temporaryRefreshFailuresPreserveSessionAndDoNotUseDeveloperToken() = runBlocking {
        val failures = listOf(
            IOException("network unavailable"),
            NazhiBackendException(503, "SERVICE_UNAVAILABLE", "temporary service failure")
        )
        for (failure in failures) {
            withServer { server ->
                val expiredCalls = AtomicInteger()
                server.respond = { Reply(401, unauthorized) }
                val client = client(
                    server,
                    tokenProvider = { "old-token" },
                    refresher = { throw failure },
                    onExpired = { expiredCalls.incrementAndGet() }
                )
                val caught = runCatching { client.checkAuth() }.exceptionOrNull()
                assertTrue(caught is IOException)
                assertEquals(failure.javaClass, caught?.javaClass)
                assertEquals(failure.message, caught?.message)
                if (failure is NazhiBackendException) {
                    caught as NazhiBackendException
                    assertEquals(503, caught.statusCode)
                    assertEquals("SERVICE_UNAVAILABLE", caught.code)
                }
                assertEquals(0, expiredCalls.get())
                assertEquals(listOf("Bearer old-token"), server.requests.map { it.authorization })
            }
        }
    }

    @Test
    fun signedOutCompatibilityUsesDeveloperTokenWithoutRefreshing() = runBlocking {
        withServer { server ->
            val refreshCalls = AtomicInteger()
            server.respond = { Reply(200, """{"ok":true}""") }
            val client = client(server, refresher = { refreshCalls.incrementAndGet(); null })
            assertTrue(client.checkAuth().ok)
            assertEquals(0, refreshCalls.get())
            assertEquals("Bearer developer-token", server.requests.single().authorization)
        }
    }

    @Test
    fun proxyUsesQueryMetadataAndAnExplicitTypeForUnspecifiedInputs() = runBlocking {
        withServer { server ->
            server.respond = { Reply(200, validEmbedding) }
            val client = client(server)
            client.createEmbeddings("req-1", inputs.map { it.copy(metadata = mapOf("type" to "query")) })
            client.createEmbeddings("req-1", inputs, type = "query")
            client.createEmbeddings("req-1", inputs)
            assertEquals(listOf("query", "query", "db"), server.requests.map { request ->
                Json.parseToJsonElement(request.body).jsonObject["type"]?.jsonPrimitive?.contentOrNull
            })
        }
    }

    @Test
    fun embeddingTypeConflictsFailBeforeSendingRequests() = runBlocking {
        withServer { server ->
            val client = client(server)
            val mixed = listOf(inputs[0].copy(metadata = mapOf("type" to "query")), inputs[0].copy(id = "id-2"))
            expectBackendFailure("MIXED_EMBEDDING_TYPES", 400) { client.createEmbeddings("req-1", mixed) }
            expectBackendFailure("MIXED_EMBEDDING_TYPES", 400) {
                client.createEmbeddings("req-1", inputs.map { it.copy(metadata = mapOf("type" to "db")) }, type = "query")
            }
            expectBackendFailure("INVALID_EMBEDDING_TYPE", 400) { client.createEmbeddings("req-1", inputs, type = "") }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun proxyRejectsMismatchedIdsCountsDimensionsAndInvalidVectors() = runBlocking {
        val invalidReplies = listOf(
            validEmbedding.replace("req-1", "other-request"),
            validEmbedding.replace("id-1", "other-id"),
            """{"requestId":"req-1","model":"test-model","dimensions":2,"items":[]}""",
            validEmbedding.replace("\"dimensions\":2", "\"dimensions\":3"),
            validEmbedding.replace("[1.0,0.5]", "[0.0,0.0]"),
            validEmbedding.replace("[1.0,0.5]", "[1e400,0.5]"),
            """{"requestId":"req-1","model":"test-model","dimensions":0,"items":[{"id":"id-1","embedding":[]}]}""",
            """{"requestId":"req-1","model":"test-model","dimensions":2,"items":[{"id":"id-1","embedding":[1,0]},{"id":"id-1","embedding":[1,0]}]}""",
            "not json"
        )
        withServer { server ->
            val client = client(server)
            for (body in invalidReplies) {
                server.respond = { Reply(200, body) }
                expectBackendFailure("EMBEDDING_RESPONSE_INVALID", 502) { client.createEmbeddings("req-1", inputs) }
            }
        }
    }

    @Test
    fun directMinimaxUsesTheSameQueryTypeAndRejectsMalformedVectors() = runBlocking {
        withServer { server ->
            val config = server.config.copy(
                serviceMode = AiServiceMode.DIRECT_API,
                vendor = AiVendor.MINIMAX,
                directApiBaseUrl = server.baseUrl,
                directApiKey = "test-api-key",
                directEmbeddingModel = "test-model"
            )
            val client = NazhiBackendClient(configProvider = { config })
            server.respond = { Reply(200, """{"vectors":[[1.0,0.5]]}""") }
            client.createEmbeddings("req-1", inputs, type = "query")
            assertEquals("query", Json.parseToJsonElement(server.requests.single().body).jsonObject["type"]?.jsonPrimitive?.contentOrNull)
            for (body in listOf(
                """{"vectors":[[0,0]]}""",
                """{"vectors":[["not a number",1]]}""",
                """{"vectors":[[1e400,1]]}""",
                """{"vectors":[]}""",
                """{"vectors":[[1,0],[0,1]]}"""
            )) {
                server.respond = { Reply(200, body) }
                expectBackendFailure("EMBEDDING_RESPONSE_INVALID", 502) { client.createEmbeddings("req-1", inputs) }
            }
        }
    }

    @Test
    fun directOpenAiRejectsDuplicateOutOfRangeIndicesAndMixedDimensions() = runBlocking {
        withServer { server ->
            val config = server.config.copy(
                serviceMode = AiServiceMode.DIRECT_API,
                vendor = AiVendor.OPENAI_COMPATIBLE,
                directApiBaseUrl = server.baseUrl,
                directApiKey = "test-api-key",
                directEmbeddingModel = "test-model"
            )
            val client = NazhiBackendClient(configProvider = { config })
            val batch = inputs + inputs.single().copy(id = "id-2")
            for (body in listOf(
                """{"data":[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[0,1]}]}""",
                """{"data":[{"index":0,"embedding":[1,0]},{"index":2,"embedding":[0,1]}]}""",
                """{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1]}]}"""
            )) {
                server.respond = { Reply(200, body) }
                expectBackendFailure("EMBEDDING_RESPONSE_INVALID", 502) { client.createEmbeddings("req-1", batch) }
            }
        }
    }

    private fun client(
        server: LocalServer,
        tokenProvider: suspend () -> String? = { null },
        refresher: suspend (String) -> String? = { null },
        onExpired: suspend () -> Unit = {}
    ) = NazhiBackendClient(
        configProvider = { server.config },
        accessTokenProvider = tokenProvider,
        accessTokenRefresher = refresher,
        authSessionExpiredHandler = onExpired
    )

    private suspend fun expectBackendFailure(code: String, status: Int, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $code, received $error", error is NazhiBackendException)
        error as NazhiBackendException
        assertEquals(status, error.statusCode)
        assertEquals(code, error.code)
    }

    private suspend fun withServer(action: suspend (LocalServer) -> Unit) {
        val server = LocalServer()
        try {
            action(server)
        } finally {
            server.close()
        }
    }

    private data class Request(val path: String, val authorization: String?, val body: String)
    private data class Reply(val status: Int, val body: String)

    private class LocalServer {
        private val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<Request>()
        val baseUrl = "http://127.0.0.1:${httpServer.address.port}"
        val config = BackendConfig(baseUrl = baseUrl, devToken = "developer-token")
        @Volatile var respond: (Request) -> Reply = { Reply(200, "{}") }

        init {
            httpServer.createContext("/") { exchange -> handle(exchange) }
            httpServer.start()
        }

        private fun handle(exchange: HttpExchange) {
            exchange.use {
                val request = Request(
                    path = exchange.requestURI.path,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                )
                requests.add(request)
                val reply = respond(request)
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        fun close() { httpServer.stop(0) }
    }

    companion object {
        private val inputs = listOf(EmbeddingInput("id-1", "test input"))
        private const val unauthorized = """{"error":{"code":"UNAUTHORIZED","message":"expired"}}"""
        private const val validEmbedding = """{"requestId":"req-1","model":"test-model","dimensions":2,"items":[{"id":"id-1","embedding":[1.0,0.5]}]}"""
        private const val validTask = """{"taskId":"task-1","requestId":"req-1","type":"asr","status":"PENDING","stage":"PENDING"}"""
    }
}
