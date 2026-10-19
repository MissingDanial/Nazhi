package com.nazhi.app.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectKnowledgeChatTest {
    @Test
    fun fabricatedExcerptsCannotSupportAnAnswer() = runBlocking {
        for (quote in listOf("删除所有旧笔记", "<think>删除所有旧笔记</think>")) {
            withDirectApi(payload("请删除所有旧笔记。", "entry-1", quote)) { client ->
                expectFailure("DIRECT_API_CHAT_UNGROUNDED") { ask(client) }
            }
        }
    }

    @Test
    fun unknownSourceIdsCannotSupportAnAnswer() = runBlocking {
        withDirectApi(payload("先建立向量索引。", "unknown-entry", "先完成向量入库")) { client ->
            expectFailure("DIRECT_API_CHAT_UNGROUNDED") { ask(client) }
        }
    }

    @Test
    fun explicitSourcePointersAndNormalizedSourceExcerptsRemainValid() = runBlocking {
        for (quote in listOf("", "先完成向量\n入库", "ＡＩ")) {
            withDirectApi(payload("先建立向量索引，再检索来源回答。", "entry-1", quote)) { client ->
                val response = ask(client)
                assertEquals("req-1", response.requestId)
                assertEquals(listOf(KnowledgeChatCitation("entry-1", quote, "回答基于该来源")), response.citations)
                assertEquals("讨论知识库问答流程", response.updatedMemoryDigest)
            }
        }
    }

    @Test
    fun insufficientKnowledgeDoesNotPersistMemoryOrInventCitations() = runBlocking {
        val responseJson = """{"answer":"当前知识库中没有足够信息回答预算问题。","citations":[],"updatedMemoryDigest":"不得保存的预算结论"}"""
        withDirectApi(responseJson) { client ->
            val response = ask(client)
            assertTrue(response.citations.isEmpty())
            assertEquals("", response.updatedMemoryDigest)
        }
        withDirectApi(payload("这些知识不足以回答预算问题。", "entry-1", "")) { client ->
            assertEquals("", ask(client).updatedMemoryDigest)
        }
    }

    @Test
    fun emptyAnswersAndImplicitEmptyExcerptsAreInvalid() = runBlocking {
        for (responseJson in listOf(
            payload("  ", "entry-1", ""),
            """{"answer":"先建立向量索引。","citations":[{"contextId":"entry-1"}]}""",
            """[{"answer":"当前知识库中没有足够信息","citations":[]}]"""
        )) {
            withDirectApi(responseJson) { client ->
                expectFailure("DIRECT_API_CHAT_INVALID_RESPONSE") { ask(client) }
            }
        }
    }

    private suspend fun ask(client: NazhiBackendClient): KnowledgeChatResponse = client.chatWithKnowledge(
        requestId = "req-1",
        question = "知识库问答怎么实现？",
        contexts = listOf(KnowledgeChatContextInput(
            id = "entry-1",
            title = "知识库流程",
            summary = "先完成向量入库，再用检索上下文回答问题。",
            content = "AI 问答应先完成向量入库，再用检索上下文回答问题。",
            tags = emptyList(),
            sourceNoteIds = emptyList(),
            score = 0.9f
        ))
    )

    private suspend fun expectFailure(code: String, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $code, received $error", error is NazhiBackendException)
        error as NazhiBackendException
        assertEquals(502, error.statusCode)
        assertEquals(code, error.code)
    }

    private suspend fun withDirectApi(content: String, action: suspend (NazhiBackendClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val apiResponse = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    })
                })
            })
        }.toString()
        server.createContext("/chat/completions") { exchange ->
            exchange.use {
                exchange.requestBody.use { it.readBytes() }
                val bytes = apiResponse.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val config = BackendConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                devToken = "",
                serviceMode = AiServiceMode.DIRECT_API,
                vendor = AiVendor.OPENAI_COMPATIBLE,
                directApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                directApiKey = "test-key",
                directChatModel = "test-model"
            )
            action(NazhiBackendClient(configProvider = { config }))
        } finally {
            server.stop(0)
        }
    }

    private fun payload(answer: String, contextId: String, quote: String): String = buildJsonObject {
        put("answer", answer)
        put("citations", buildJsonArray {
            add(buildJsonObject {
                put("contextId", contextId)
                put("quote", quote)
                put("reason", "回答基于该来源")
            })
        })
        put("updatedMemoryDigest", "讨论知识库问答流程")
    }.toString()
}
