package com.nazhi.app.core.network

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent identity never stores an access token or API key in the task table. */
object TaskBindingKey {
    fun create(config: BackendConfig, accountId: String?, forAudio: Boolean = false): String {
        val parts = if (!forAudio && config.serviceMode == AiServiceMode.DIRECT_API) {
            listOf("direct", config.vendor.name, config.normalizedDirectApiBaseUrl,
                config.directChatModel.trim(), config.directApiKey.trim(), config.directExtraId.trim())
        } else {
            listOf("backend", config.serviceMode.name, config.normalizedBaseUrl,
                accountId?.takeIf { it.isNotBlank() }?.let { "user:$it" }
                    ?: "dev:${config.devToken.trim()}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(parts).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return (if (!forAudio && config.serviceMode == AiServiceMode.DIRECT_API) "direct:" else "backend:") + digest
    }
}
