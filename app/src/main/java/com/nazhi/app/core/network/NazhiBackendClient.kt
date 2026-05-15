package com.nazhi.app.core.network

import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.Note
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class NazhiBackendClient(
    private val configProvider: suspend () -> BackendConfig
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun checkHealth(
        config: BackendConfig? = null
    ): BackendHealthResponse = get(
        path = "/health",
        config = config
    )

    suspend fun checkAuth(
        config: BackendConfig? = null
    ): BackendAuthCheckResponse = get(
        path = "/v1/auth-check",
        config = config
    )

    suspend fun checkDirectApi(config: BackendConfig): DirectApiCheckResponse {
        val embeddingResponse = createDirectEmbeddings(
            config = config,
            requestId = "settings-embedding-check",
            input = listOf(
                EmbeddingInput(
                    id = "settings-check",
                    text = "纳知 API 连通性测试",
                    metadata = mapOf("type" to "query")
                )
            ),
            model = config.directEmbeddingModel
        )
        val embeddingItem = embeddingResponse.items.firstOrNull()
            ?: throw NazhiBackendException(
                statusCode = 502,
                code = "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED",
                publicMessage = "Embedding API 返回为空，请检查模型名和服务兼容性。"
            )
        val chatReply = chatDirect(
            config = config,
            messages = listOf(
                DirectChatMessage(
                    role = "system",
                    content = "你是纳知的 API 连通性测试助手。只回复“ok”。"
                ),
                DirectChatMessage(
                    role = "user",
                    content = "请回复 ok。"
                )
            ),
            temperature = 0f,
            maxTokens = 16
        )
        return DirectApiCheckResponse(
            vendor = config.vendor,
            chatModel = config.directChatModel.trim(),
            embeddingModel = embeddingResponse.model,
            embeddingDimensions = embeddingItem.embedding.size,
            chatReplyPreview = chatReply.take(24)
        )
    }

    suspend fun createEmbeddings(
        requestId: String,
        input: List<EmbeddingInput>,
        model: String = EMBEDDING_MODEL
    ): EmbeddingResponse {
        val backendConfig = configProvider()
        if (backendConfig.serviceMode == AiServiceMode.DIRECT_API) {
            return createDirectEmbeddings(
                config = backendConfig,
                requestId = requestId,
                input = input,
                model = backendConfig.directEmbeddingModel.ifBlank { model }
            )
        }
        return post(
            path = "/v1/embeddings",
            body = EmbeddingRequest(
                requestId = requestId,
                model = model,
                input = input
            ),
            config = backendConfig
        )
    }

    suspend fun organizeNotes(
        requestId: String,
        date: String,
        notes: List<Note>,
        maxDrafts: Int = 10
    ): OrganizeNotesResponse {
        val backendConfig = configProvider()
        if (backendConfig.serviceMode == AiServiceMode.DIRECT_API) {
            return organizeNotesDirect(
                config = backendConfig,
                requestId = requestId,
                date = date,
                notes = notes,
                maxDrafts = maxDrafts
            )
        }
        return post(
            path = "/v1/organize-notes",
            body = OrganizeNotesRequest(
                requestId = requestId,
                date = date,
                language = "zh-CN",
                notes = notes.map { note ->
                    OrganizeNoteInput(
                        id = note.id,
                        title = note.title,
                        content = note.content,
                        sourceType = note.sourceType.name,
                        createdAt = note.createdAt
                    )
                },
                options = OrganizeOptions(
                    maxDrafts = maxDrafts,
                    mergeSimilar = true
                )
            ),
            config = backendConfig
        )
    }

    suspend fun createOrganizeNotesJob(
        requestId: String,
        date: String,
        notes: List<Note>,
        maxDrafts: Int = 10
    ): BackendTaskResponse {
        val backendConfig = configProvider()
        if (backendConfig.serviceMode == AiServiceMode.DIRECT_API) {
            throw NazhiBackendException(
                statusCode = 400,
                code = "DIRECT_API_MODE",
                publicMessage = "自带 API Key 模式不使用后端任务接口。"
            )
        }
        return post(
            path = "/v1/organize-notes/jobs",
            body = OrganizeNotesRequest(
                requestId = requestId,
                date = date,
                language = "zh-CN",
                notes = notes.map { note ->
                    OrganizeNoteInput(
                        id = note.id,
                        title = note.title,
                        content = note.content,
                        sourceType = note.sourceType.name,
                        createdAt = note.createdAt
                    )
                },
                options = OrganizeOptions(
                    maxDrafts = maxDrafts,
                    mergeSimilar = true
                )
            ),
            config = backendConfig
        )
    }

    suspend fun getTask(taskId: String): BackendTaskResponse = get(
        path = "/v1/tasks/$taskId"
    )

    suspend fun chatWithKnowledge(
        requestId: String,
        question: String,
        contexts: List<KnowledgeChatContextInput>,
        language: String = "zh-CN"
    ): KnowledgeChatResponse {
        val backendConfig = configProvider()
        if (backendConfig.serviceMode == AiServiceMode.DIRECT_API) {
            return chatWithKnowledgeDirect(
                config = backendConfig,
                requestId = requestId,
                question = question,
                language = language,
                contexts = contexts
            )
        }
        return post(
            path = "/v1/knowledge-chat",
            body = KnowledgeChatRequest(
                requestId = requestId,
                question = question,
                language = language,
                contexts = contexts
            ),
            config = backendConfig
        )
    }

    private suspend inline fun <reified Request, reified Response> post(
        path: String,
        body: Request,
        config: BackendConfig? = null
    ): Response = withContext(Dispatchers.IO) {
        val backendConfig = config ?: configProvider()
        val url = URL(backendConfig.normalizedBaseUrl + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (backendConfig.devToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${backendConfig.devToken.trim()}")
            }
        }

        val payload = json.encodeToString(body).toByteArray(Charsets.UTF_8)
        connection.outputStream.use { output -> output.write(payload) }

        val statusCode = connection.responseCode
        val responseText = try {
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }

        if (statusCode !in 200..299) {
            val backendError = runCatching {
                json.decodeFromString<BackendErrorResponse>(responseText).error
            }.getOrNull()
            throw NazhiBackendException(statusCode, backendError?.code, backendError?.message ?: "Backend request failed with HTTP $statusCode.")
        }

        json.decodeFromString(responseText)
    }

    private suspend inline fun <reified Response> get(
        path: String,
        config: BackendConfig? = null
    ): Response = withContext(Dispatchers.IO) {
        val backendConfig = config ?: configProvider()
        val url = URL(backendConfig.normalizedBaseUrl + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            if (backendConfig.devToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${backendConfig.devToken.trim()}")
            }
        }

        val statusCode = connection.responseCode
        val responseText = try {
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }

        if (statusCode !in 200..299) {
            val backendError = runCatching {
                json.decodeFromString<BackendErrorResponse>(responseText).error
            }.getOrNull()
            throw NazhiBackendException(statusCode, backendError?.code, backendError?.message ?: "Backend request failed with HTTP $statusCode.")
        }

        json.decodeFromString(responseText)
    }

    private suspend fun createDirectEmbeddings(
        config: BackendConfig,
        requestId: String,
        input: List<EmbeddingInput>,
        model: String
    ): EmbeddingResponse {
        if (input.isEmpty()) {
            throw NazhiBackendException(400, "INVALID_INPUT", "`input` must be a non-empty array.")
        }
        return if (config.vendor == AiVendor.MINIMAX) {
            createMinimaxDirectEmbeddings(config, requestId, input, model)
        } else {
            createOpenAiCompatibleEmbeddings(config, requestId, input, model)
        }
    }

    private suspend fun createMinimaxDirectEmbeddings(
        config: BackendConfig,
        requestId: String,
        input: List<EmbeddingInput>,
        model: String
    ): EmbeddingResponse {
        val type = if (input.all { it.metadata["type"] == "query" }) "query" else "db"
        val responseText = postDirectJson(
            config = config,
            path = "/embeddings",
            capability = DirectApiCapability.EMBEDDING,
            body = buildJsonObject {
                put("texts", buildJsonArray {
                    input.forEach { item -> add(item.text) }
                })
                put("model", model)
                put("type", type)
            }.toString()
        )
        val payload = json.parseToJsonElement(responseText).jsonObject
        payload["base_resp"]?.jsonObject?.let { baseResp ->
            val statusCode = baseResp["status_code"]?.jsonPrimitive?.contentOrNull
            if (statusCode != null && statusCode != "0") {
                throw NazhiBackendException(
                    502,
                    "DIRECT_API_EMBEDDING_FAILED",
                    baseResp["status_msg"]?.jsonPrimitive?.contentOrNull ?: "Embedding API returned an error."
                )
            }
        }
        val vectors = payload["vectors"]?.jsonArray.orEmpty().map { vector ->
            vector.jsonArray.map { value -> value.jsonPrimitive.floatOrNull ?: 0f }
        }
        if (vectors.size != input.size) {
            throw NazhiBackendException(502, "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED", "Embedding response shape does not match input.")
        }
        return EmbeddingResponse(
            requestId = requestId,
            model = model,
            dimensions = vectors.firstOrNull()?.size ?: 0,
            items = input.mapIndexed { index, item ->
                EmbeddingItem(
                    id = item.id,
                    embedding = vectors[index],
                    metadata = item.metadata
                )
            }
        )
    }

    private suspend fun createOpenAiCompatibleEmbeddings(
        config: BackendConfig,
        requestId: String,
        input: List<EmbeddingInput>,
        model: String
    ): EmbeddingResponse {
        val responseText = postDirectJson(
            config = config,
            path = "/embeddings",
            capability = DirectApiCapability.EMBEDDING,
            body = json.encodeToString(
                OpenAiEmbeddingRequest(
                    model = model,
                    input = input.map { it.text }
                )
            )
        )
        val payload = json.decodeFromString<OpenAiEmbeddingResponse>(responseText)
        val vectorsByIndex = payload.data.associateBy { it.index }
        val items = input.mapIndexed { index, item ->
            val vector = vectorsByIndex[index]?.embedding
                ?: throw NazhiBackendException(502, "DIRECT_API_EMBEDDING_SHAPE_UNSUPPORTED", "Embedding response shape does not match input.")
            EmbeddingItem(
                id = item.id,
                embedding = vector,
                metadata = item.metadata
            )
        }
        return EmbeddingResponse(
            requestId = requestId,
            model = payload.model ?: model,
            dimensions = items.firstOrNull()?.embedding?.size ?: 0,
            items = items
        )
    }

    private suspend fun organizeNotesDirect(
        config: BackendConfig,
        requestId: String,
        date: String,
        notes: List<Note>,
        maxDrafts: Int
    ): OrganizeNotesResponse {
        val content = chatDirect(
            config = config,
            messages = listOf(
                DirectChatMessage(
                    role = "system",
                    content = "你是纳知的知识整理助手。只基于用户提供的原始笔记整理可复用知识条目。禁止输出 Markdown 或解释，只返回合法 JSON。"
                ),
                DirectChatMessage(
                    role = "user",
                    content = buildOrganizePrompt(date, notes, maxDrafts)
                )
            ),
            temperature = 0.3f,
            maxTokens = 1600
        )
        val jsonText = extractJsonObject(content)
        val parsed = json.decodeFromString<DirectOrganizePayload>(jsonText)
        return OrganizeNotesResponse(
            requestId = requestId,
            date = date,
            drafts = parsed.drafts
        )
    }

    private suspend fun chatWithKnowledgeDirect(
        config: BackendConfig,
        requestId: String,
        question: String,
        language: String,
        contexts: List<KnowledgeChatContextInput>
    ): KnowledgeChatResponse {
        if (contexts.isEmpty()) {
            return KnowledgeChatResponse(
                requestId = requestId,
                answer = "当前知识库中没有足够信息回答这个问题。",
                citations = emptyList()
            )
        }
        val content = chatDirect(
            config = config,
            messages = listOf(
                DirectChatMessage(
                    role = "system",
                    content = "你是纳知的个人知识库问答助手。只能基于用户提供的 contexts 回答。禁止输出 Markdown 或解释，只返回合法 JSON。"
                ),
                DirectChatMessage(
                    role = "user",
                    content = buildKnowledgeChatPrompt(question, language, contexts)
                )
            ),
            temperature = 0.2f,
            maxTokens = 1200
        )
        val jsonText = extractJsonObject(content)
        val parsed = json.decodeFromString<DirectKnowledgeChatPayload>(jsonText)
        val allowedContextIds = contexts.map { it.id }.toSet()
        return KnowledgeChatResponse(
            requestId = requestId,
            answer = parsed.answer,
            citations = parsed.citations.filter { it.contextId in allowedContextIds }.take(5)
        )
    }

    private suspend fun chatDirect(
        config: BackendConfig,
        messages: List<DirectChatMessage>,
        temperature: Float,
        maxTokens: Int
    ): String {
        val responseText = postDirectJson(
            config = config,
            path = "/chat/completions",
            capability = DirectApiCapability.CHAT,
            body = buildDirectChatBody(config, messages, temperature, maxTokens).toString()
        )
        val payload = json.decodeFromString<DirectChatResponse>(responseText)
        val content = payload.choices.firstOrNull()?.message?.content.orEmpty()
        if (content.isBlank()) {
            throw NazhiBackendException(
                statusCode = 502,
                code = "DIRECT_API_CHAT_RESPONSE_EMPTY",
                publicMessage = "Chat API 返回内容为空，请检查模型名和服务兼容性。"
            )
        }
        return content
    }

    private fun buildDirectChatBody(
        config: BackendConfig,
        messages: List<DirectChatMessage>,
        temperature: Float,
        maxTokens: Int
    ): JsonObject {
        return buildJsonObject {
            put("model", config.directChatModel.trim())
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    )
                }
            })
            put("temperature", temperature)
            if (config.vendor == AiVendor.MINIMAX) {
                put("max_completion_tokens", maxTokens)
            } else {
                put("max_tokens", maxTokens)
            }
        }
    }

    private suspend fun postDirectJson(
        config: BackendConfig,
        path: String,
        capability: DirectApiCapability,
        body: String
    ): String = withContext(Dispatchers.IO) {
        val baseUrl = when (capability) {
            DirectApiCapability.CHAT -> config.normalizedDirectApiBaseUrl
            DirectApiCapability.EMBEDDING -> config.effectiveDirectEmbeddingApiBaseUrl
        }
        val apiKey = when (capability) {
            DirectApiCapability.CHAT -> config.directApiKey.trim()
            DirectApiCapability.EMBEDDING -> config.effectiveDirectEmbeddingApiKey
        }
        if (apiKey.isBlank()) {
            throw NazhiBackendException(400, "DIRECT_API_KEY_MISSING", "请先在设置页填写 API Key。")
        }
        if (baseUrl.isBlank()) {
            throw NazhiBackendException(400, "DIRECT_API_BASE_URL_MISSING", "请先在设置页填写 API Base URL。")
        }
        val endpoint = baseUrl + path
        val url = URL(buildDirectEndpoint(config, endpoint))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val responseText = try {
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        if (statusCode !in 200..299) {
            val providerMessage = runCatching {
                val element = json.parseToJsonElement(responseText).jsonObject
                element["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: element["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw NazhiBackendException(
                statusCode,
                directApiErrorCode(statusCode),
                directApiPublicMessage(statusCode, capability, providerMessage)
            )
        }
        responseText
    }

    private fun directApiErrorCode(statusCode: Int): String {
        return when (statusCode) {
            400 -> "DIRECT_API_BAD_REQUEST"
            401, 403 -> "DIRECT_API_UNAUTHORIZED"
            402 -> "DIRECT_API_QUOTA_EXHAUSTED"
            404 -> "DIRECT_API_ENDPOINT_NOT_FOUND"
            408 -> "DIRECT_API_TIMEOUT"
            429 -> "DIRECT_API_RATE_LIMITED"
            in 500..599 -> "DIRECT_API_PROVIDER_UNAVAILABLE"
            else -> "DIRECT_API_REQUEST_FAILED"
        }
    }

    private fun directApiPublicMessage(
        statusCode: Int,
        capability: DirectApiCapability,
        providerMessage: String?
    ): String {
        val target = when (capability) {
            DirectApiCapability.CHAT -> "Chat API"
            DirectApiCapability.EMBEDDING -> "Embedding API"
        }
        val suffix = providerMessage?.takeIf { it.isNotBlank() }?.let { "服务返回：$it" }.orEmpty()
        val baseMessage = when (statusCode) {
            400 -> "$target 请求参数不被服务接受，请检查模型名、Base URL 和厂商兼容性。"
            401, 403 -> "$target 鉴权失败，请检查 API Key 是否正确、是否已启用对应模型。"
            402 -> "$target 余额或额度不足，请检查 API 服务账户。"
            404 -> "$target 地址或模型不存在，请检查 Base URL、endpoint 和模型名称。"
            408 -> "$target 请求超时，请稍后重试。"
            429 -> "$target 请求过于频繁或额度受限，请稍后重试或检查限额。"
            in 500..599 -> "$target 服务暂时不可用，请稍后重试。"
            else -> "$target 请求失败，HTTP $statusCode。"
        }
        return listOf(baseMessage, suffix).filter { it.isNotBlank() }.joinToString(separator = " ")
    }

    private fun buildDirectEndpoint(config: BackendConfig, endpoint: String): String {
        if (config.vendor != AiVendor.MINIMAX || config.directExtraId.isBlank() || !endpoint.contains("api.minimax.chat")) {
            return endpoint
        }
        if (endpoint.contains("GroupId=")) {
            return endpoint
        }
        val separator = if (endpoint.contains("?")) "&" else "?"
        return "$endpoint${separator}GroupId=${java.net.URLEncoder.encode(config.directExtraId.trim(), "UTF-8")}"
    }

    private fun buildOrganizePrompt(date: String, notes: List<Note>, maxDrafts: Int): String {
        val notePayload = notes.map { note ->
            buildJsonObject {
                put("id", note.id)
                put("title", note.title.orEmpty())
                put("sourceType", note.sourceType.name)
                put("createdAt", note.createdAt)
                put("content", note.content)
            }
        }
        return """
            你正在整理用户在 $date 保存的原始笔记。请只基于 notes 生成可复用知识草稿。
            规则：
            1. 不要引入外部事实。
            2. 可以合并主题相近的 notes，但必须保留 sourceNoteIds。
            3. tags 使用 1-5 个中文短标签。
            4. intentType 只能是 READ_LATER、QUOTABLE、INSPIRATION。
            5. evidenceQuotes 放 0-3 条来自原文的短引用。
            6. 信息不足时 needsReview=true，confidence 低于 0.7。
            7. 最多输出 $maxDrafts 条。
            8. 只输出 JSON，不要 Markdown。
            输出格式：
            {"drafts":[{"id":"draft-id","title":"标题","summary":"摘要","content":"正文","intentType":"READ_LATER","tags":["标签"],"sourceNoteIds":["note-id"],"evidenceQuotes":["引用"],"insight":"可选推断","confidence":0.86,"needsReview":false}]}
            notes:
            ${JsonArray(notePayload)}
        """.trimIndent()
    }

    private fun buildKnowledgeChatPrompt(
        question: String,
        language: String,
        contexts: List<KnowledgeChatContextInput>
    ): String {
        val contextPayload = contexts.map { context ->
            buildJsonObject {
                put("id", context.id)
                put("title", context.title)
                put("summary", context.summary)
                put("content", context.content)
                put("tags", buildJsonArray { context.tags.forEach { add(it) } })
                put("sourceNoteIds", buildJsonArray { context.sourceNoteIds.forEach { add(it) } })
                put("score", context.score)
            }
        }
        return """
            请只基于 contexts 回答用户问题。
            规则：
            1. 不要使用 contexts 之外的事实。
            2. 如果 contexts 不足以回答，answer 必须说明“当前知识库中没有足够信息”。
            3. citations 只能引用 contexts[].id。
            4. 使用 $language，保持简洁、可执行。
            5. 只输出 JSON，不要 Markdown。
            输出格式：
            {"answer":"回答正文","citations":[{"contextId":"knowledge-id","quote":"引用短句","reason":"引用理由"}]}
            用户问题：$question
            contexts:
            ${JsonArray(contextPayload)}
        """.trimIndent()
    }

    private fun extractJsonObject(text: String): String {
        val cleaned = text
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace("```json", "```")
            .trim()
        Regex("```([\\s\\S]*?)```").find(cleaned)?.groupValues?.getOrNull(1)?.trim()?.let { fenced ->
            if (fenced.startsWith("{")) {
                return fenced
            }
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1)
        }
        throw NazhiBackendException(502, "DIRECT_API_INVALID_JSON", "模型返回格式异常，未得到合法 JSON。")
    }

    companion object {
        const val EMBEDDING_MODEL = "embo-01"
    }
}

private enum class DirectApiCapability {
    CHAT,
    EMBEDDING
}

data class DirectApiCheckResponse(
    val vendor: AiVendor,
    val chatModel: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val chatReplyPreview: String
)

@Serializable
data class BackendHealthResponse(
    val ok: Boolean,
    val service: String,
    val embeddingProvider: String,
    val chatProvider: String
)

@Serializable
data class BackendAuthCheckResponse(
    val ok: Boolean,
    val service: String
)

class NazhiBackendException(
    val statusCode: Int,
    val code: String?,
    val publicMessage: String
) : IOException(publicMessage)

@Serializable
data class EmbeddingInput(
    val id: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
private data class EmbeddingRequest(
    val requestId: String,
    val model: String,
    val input: List<EmbeddingInput>
)

@Serializable
data class EmbeddingResponse(
    val requestId: String,
    val model: String,
    val dimensions: Int,
    val items: List<EmbeddingItem>
)

@Serializable
data class EmbeddingItem(
    val id: String,
    val embedding: List<Float>,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
private data class OrganizeNotesRequest(
    val requestId: String,
    val date: String,
    val language: String,
    val notes: List<OrganizeNoteInput>,
    val options: OrganizeOptions
)

@Serializable
private data class OrganizeNoteInput(
    val id: String,
    val title: String?,
    val content: String,
    val sourceType: String,
    val createdAt: Long
)

@Serializable
private data class OrganizeOptions(
    val maxDrafts: Int,
    val mergeSimilar: Boolean
)

@Serializable
data class OrganizeNotesResponse(
    val requestId: String,
    val date: String,
    val drafts: List<OrganizeDraft>
)

@Serializable
data class BackendTaskResponse(
    val taskId: String,
    val requestId: String,
    val type: String,
    val status: String,
    val stage: String,
    val progress: Int = 0,
    val message: String = "",
    val result: OrganizeNotesResponse? = null,
    val error: BackendTaskError? = null
)

@Serializable
data class BackendTaskError(
    val code: String? = null,
    val message: String? = null
)

@Serializable
private data class KnowledgeChatRequest(
    val requestId: String,
    val question: String,
    val language: String,
    val contexts: List<KnowledgeChatContextInput>
)

@Serializable
data class KnowledgeChatContextInput(
    val id: String,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String>,
    val sourceNoteIds: List<String>,
    val score: Float
)

@Serializable
data class KnowledgeChatResponse(
    val requestId: String,
    val answer: String,
    val citations: List<KnowledgeChatCitation> = emptyList()
)

@Serializable
data class KnowledgeChatCitation(
    val contextId: String,
    val quote: String = "",
    val reason: String = ""
)

@Serializable
data class OrganizeDraft(
    val id: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val content: String,
    val intentType: String? = null,
    val tags: List<String> = emptyList(),
    val sourceNoteIds: List<String> = emptyList(),
    val evidenceQuotes: List<String> = emptyList(),
    val insight: String? = null,
    val confidence: Float = 0.6f,
    val needsReview: Boolean = false
) {
    fun normalizedIntentType(): IntentType {
        return runCatching { IntentType.valueOf(intentType.orEmpty()) }
            .getOrDefault(IntentType.READ_LATER)
    }
}

@Serializable
private data class OpenAiEmbeddingRequest(
    val model: String,
    val input: List<String>
)

@Serializable
private data class OpenAiEmbeddingResponse(
    val data: List<OpenAiEmbeddingItem>,
    val model: String? = null
)

@Serializable
private data class OpenAiEmbeddingItem(
    val index: Int,
    val embedding: List<Float>
)

@Serializable
private data class DirectChatRequest(
    val model: String,
    val messages: List<DirectChatMessage>,
    val temperature: Float,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("response_format") val responseFormat: DirectResponseFormat? = null
)

@Serializable
private data class DirectChatMessage(
    val role: String,
    val content: String
)

@Serializable
private data class DirectResponseFormat(
    val type: String
)

@Serializable
private data class DirectChatResponse(
    val choices: List<DirectChatChoice>
)

@Serializable
private data class DirectChatChoice(
    val message: DirectChatMessage
)

@Serializable
private data class DirectOrganizePayload(
    val drafts: List<OrganizeDraft> = emptyList()
)

@Serializable
private data class DirectKnowledgeChatPayload(
    val answer: String,
    val citations: List<KnowledgeChatCitation> = emptyList()
)

@Serializable
private data class BackendErrorResponse(
    val error: BackendError
)

@Serializable
private data class BackendError(
    val code: String? = null,
    @SerialName("message") val message: String? = null
)
