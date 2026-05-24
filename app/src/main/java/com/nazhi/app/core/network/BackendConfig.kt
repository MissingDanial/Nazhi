package com.nazhi.app.core.network

import java.net.URI

data class BackendConfig(
    val baseUrl: String,
    val devToken: String,
    val serviceMode: AiServiceMode = AiServiceMode.NAZHI,
    val vendor: AiVendor = AiVendor.MINIMAX,
    val directApiBaseUrl: String = "",
    val directApiKey: String = "",
    val directChatModel: String = "",
    val directEmbeddingApiBaseUrl: String = "",
    val directEmbeddingApiKey: String = "",
    val directEmbeddingModel: String = "",
    val directExtraId: String = ""
) {
    val normalizedBaseUrl: String
        get() {
            val trimmed = baseUrl.trim().trimEnd('/')
            return runCatching {
                val uri = URI(trimmed)
                val scheme = uri.scheme ?: return@runCatching trimmed
                val authority = uri.rawAuthority ?: return@runCatching trimmed
                "$scheme://$authority"
            }.getOrDefault(trimmed)
        }

    val normalizedDirectApiBaseUrl: String
        get() = directApiBaseUrl.trim().trimEnd('/')

    val normalizedDirectEmbeddingApiBaseUrl: String
        get() = directEmbeddingApiBaseUrl.trim().trimEnd('/')

    val effectiveDirectEmbeddingApiBaseUrl: String
        get() = normalizedDirectEmbeddingApiBaseUrl.ifBlank { normalizedDirectApiBaseUrl }

    val effectiveDirectEmbeddingApiKey: String
        get() = directEmbeddingApiKey.trim().ifBlank { directApiKey.trim() }
}

enum class AiServiceMode {
    NAZHI,
    DIRECT_API
}

enum class AiVendor {
    MINIMAX,
    OPENAI_COMPATIBLE,
    QWEN,
    DEEPSEEK,
    CUSTOM
}
