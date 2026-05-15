package com.nazhi.app.core.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(setting: EncryptedSetting, plaintextFallback: String? = null): String {
        val encryptedValue = preferences.getString(setting.preferenceKey, null)
        if (encryptedValue.isNullOrBlank()) {
            return plaintextFallback.orEmpty()
        }
        return runCatching { decrypt(encryptedValue) }.getOrElse { plaintextFallback.orEmpty() }
    }

    fun write(setting: EncryptedSetting, value: String) {
        val trimmedValue = value.trim()
        if (trimmedValue.isBlank()) {
            preferences.edit().remove(setting.preferenceKey).apply()
            return
        }
        preferences.edit()
            .putString(setting.preferenceKey, encrypt(trimmedValue))
            .apply()
    }

    fun clearDirectApiSecrets() {
        preferences.edit()
            .remove(EncryptedSetting.DirectApiKey.preferenceKey)
            .remove(EncryptedSetting.DirectEmbeddingApiKey.preferenceKey)
            .apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            VALUE_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
        ).joinToString(separator = ":")
    }

    private fun decrypt(encryptedValue: String): String {
        val parts = encryptedValue.split(":")
        require(parts.size == 3 && parts[0] == VALUE_VERSION) { "Unsupported encrypted value." }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "encrypted_ai_service_settings"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nazhi_ai_service_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VALUE_VERSION = "v1"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

enum class EncryptedSetting(val preferenceKey: String) {
    DirectApiKey("direct_api_key"),
    DirectEmbeddingApiKey("direct_embedding_api_key")
}
