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
import kotlinx.serialization.json.Json

class NazhiBackendClient(
    private val baseUrl: String,
    private val devToken: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun createEmbeddings(
        requestId: String,
        input: List<EmbeddingInput>,
        model: String = EMBEDDING_MODEL
    ): EmbeddingResponse = post(
        path = "/v1/embeddings",
        body = EmbeddingRequest(
            requestId = requestId,
            model = model,
            input = input
        )
    )

    suspend fun organizeNotes(
        requestId: String,
        date: String,
        notes: List<Note>,
        maxDrafts: Int = 10
    ): OrganizeNotesResponse = post(
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
        )
    )

    private suspend inline fun <reified Request, reified Response> post(
        path: String,
        body: Request
    ): Response = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (devToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $devToken")
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
            throw IOException(backendError?.message ?: "Backend request failed with HTTP $statusCode.")
        }

        json.decodeFromString(responseText)
    }

    companion object {
        const val EMBEDDING_MODEL = "embo-01"
    }
}

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
private data class BackendErrorResponse(
    val error: BackendError
)

@Serializable
private data class BackendError(
    val code: String? = null,
    @SerialName("message") val message: String? = null
)
