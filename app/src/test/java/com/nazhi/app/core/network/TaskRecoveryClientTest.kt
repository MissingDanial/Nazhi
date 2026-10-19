package com.nazhi.app.core.network

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskRecoveryClientTest {
    @Test
    fun stableRequestIdIsSentInMultipartAndLookupUsesGetWithEscapedIdentity() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
        server.createContext("/") { exchange ->
            requests.add(Triple(exchange.requestMethod, exchange.requestURI.toASCIIString(), exchange.requestBody.bufferedReader().readText()))
            val text = """{"taskId":"t1","requestId":"stable-id","type":"AUDIO_TRANSCRIPTION","status":"RUNNING","stage":"ASR"}"""
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val audio = File.createTempFile("recovery", ".wav")
        try {
            audio.writeText("audio-bytes")
            val config = BackendConfig("http://127.0.0.1:${server.address.port}", "dev")
            val client = NazhiBackendClient({ config })
            val binding = client.currentTaskBinding(forAudio = true)
            client.createAudioTranscriptionJob(audio, 1000, requestId = "stable-id", expectedBinding = binding)
            client.getAudioTaskByRequestId("request /+&?", expectedBinding = binding)
            assertEquals("POST", requests[0].first)
            assertTrue(requests[0].third.contains("name=\"requestId\"\r\n\r\nstable-id\r\n"))
            assertEquals("GET", requests[1].first)
            assertEquals("/v1/tasks/by-request/request%20%2F%2B%26%3F?type=AUDIO_TRANSCRIPTION", requests[1].second)
            assertEquals("", requests[1].third)
        } finally {
            audio.delete()
            server.stop(0)
        }
    }

    @Test
    fun accountSwitchWhileGettingAccessTokenStopsTheRequest() = runBlocking {
        val config = BackendConfig("http://127.0.0.1:1", "dev")
        var account = "account-a"
        val client = NazhiBackendClient(
            configProvider = { config },
            accountIdProvider = { account },
            accessTokenProvider = { account = "account-b"; "b-token" }
        )
        val binding = client.currentTaskBinding()
        try {
            client.getTask("task-a", expectedBinding = binding)
            fail("Must not send a request with a different owner")
        } catch (error: NazhiBackendException) {
            assertEquals("TASK_BINDING_CHANGED", error.code)
        }
    }

    @Test
    fun staleCapturedConfigurationCannotSendToAnotherServerAfterSwitchingBack() = runBlocking {
        val original = BackendConfig("http://127.0.0.1:1", "dev")
        val other = original.copy(baseUrl = "http://127.0.0.1:2")
        var reads = 0
        val client = NazhiBackendClient(configProvider = { if (reads++ == 0) other else original })
        try {
            client.getTask("task-a", expectedBinding = TaskBindingKey.create(original, null))
            fail("A captured configuration must also match the persisted task")
        } catch (error: NazhiBackendException) {
            assertEquals("TASK_BINDING_CHANGED", error.code)
        }
    }

    @Test
    fun bindingTracksServerAccountAndModeWithoutPersistingSecrets() {
        val config = BackendConfig("https://test.example/", "private-dev-token")
        val accountBinding = TaskBindingKey.create(config, "account-a")
        assertEquals(accountBinding, TaskBindingKey.create(config.copy(devToken = "changed-dev-token"), "account-a"))
        assertNotEquals(accountBinding, TaskBindingKey.create(config, "account-b"))
        assertNotEquals(accountBinding, TaskBindingKey.create(config.copy(baseUrl = "https://other.example"), "account-a"))
        assertNotEquals(TaskBindingKey.create(config, null), TaskBindingKey.create(config.copy(devToken = "other"), null))
        assertFalse(accountBinding.contains("account-a"))
        assertFalse(TaskBindingKey.create(config, null).contains("private-dev-token"))
        val direct = config.copy(serviceMode = AiServiceMode.DIRECT_API, directApiBaseUrl = "https://model.example", directApiKey = "secret-key")
        assertTrue(TaskBindingKey.create(direct, null).startsWith("direct:"))
        assertTrue(TaskBindingKey.create(direct, null, forAudio = true).startsWith("backend:"))
        assertNotEquals(TaskBindingKey.create(config, null), TaskBindingKey.create(direct, null, forAudio = true))
    }
}
