package com.nazhi.app.core.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

object LocalEmbeddingEngine {
    const val MOCK_MODEL = "mock-local-bow-v1"
    const val MOCK_DIMENSIONS = 128

    fun embedMock(text: String, dimensions: Int = MOCK_DIMENSIONS): FloatArray {
        val vector = FloatArray(dimensions)
        val normalized = text.lowercase(Locale.getDefault())
        normalized.forEachIndexed { index, char ->
            if (!char.isWhitespace()) {
                val primary = Math.floorMod(char.code * 31 + index * 17, dimensions)
                val secondary = Math.floorMod(char.code * 13 + index * 7, dimensions)
                vector[primary] += 1f
                vector[secondary] += 0.25f
            }
        }
        return normalize(vector)
    }

    fun toBlob(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun fromBlob(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(blob.size / Float.SIZE_BYTES)
        for (index in vector.indices) {
            vector[index] = buffer.getFloat()
        }
        return vector
    }

    fun dot(left: FloatArray, right: FloatArray): Float {
        val size = minOf(left.size, right.size)
        var sum = 0f
        for (index in 0 until size) {
            sum += left[index] * right[index]
        }
        return sum
    }

    fun norm(vector: FloatArray): Float {
        var sum = 0f
        vector.forEach { value -> sum += value * value }
        return sqrt(sum)
    }

    fun textHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun normalize(vector: FloatArray): FloatArray {
        val norm = norm(vector)
        if (norm == 0f) {
            return vector
        }
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }
}
