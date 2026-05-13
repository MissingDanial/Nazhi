package com.nazhi.app.core.network

data class BackendConfig(
    val baseUrl: String,
    val devToken: String
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')
}

