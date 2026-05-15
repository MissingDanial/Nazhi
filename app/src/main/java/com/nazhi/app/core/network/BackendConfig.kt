package com.nazhi.app.core.network

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
        get() = baseUrl.trim().trimEnd('/')

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
