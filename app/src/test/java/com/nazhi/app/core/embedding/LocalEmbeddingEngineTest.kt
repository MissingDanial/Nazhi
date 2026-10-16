package com.nazhi.app.core.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEmbeddingEngineTest {
    @Test(expected = IllegalArgumentException::class)
    fun dotRejectsDifferentDimensions() {
        LocalEmbeddingEngine.dot(floatArrayOf(1f, 2f), floatArrayOf(1f))
    }

    @Test
    fun largeFiniteVectorsKeepTheirDirectionDuringNormalization() {
        val large = floatArrayOf(1e30f, 1e30f)
        assertTrue(LocalEmbeddingEngine.norm(large).isFinite())
        assertArrayEquals(
            floatArrayOf(0.70710677f, 0.70710677f),
            LocalEmbeddingEngine.normalize(large),
            0.000001f
        )
        assertArrayEquals(
            floatArrayOf(0.70710677f, -0.70710677f),
            LocalEmbeddingEngine.normalize(floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE)),
            0.000001f
        )
    }

    @Test
    fun compatibleVectorsRoundTripWithoutChangingValuesOrScores() {
        val vector = floatArrayOf(0.6f, 0.8f)
        val decoded = LocalEmbeddingEngine.decodeCompatibleVector(
            blob = LocalEmbeddingEngine.toBlob(vector),
            dimensions = 2,
            queryDimensions = 2
        )!!

        assertArrayEquals(vector, decoded, 0f)
        assertEquals(1f, LocalEmbeddingEngine.dot(vector, decoded), 0.000001f)
    }

    @Test
    fun declaredDimensionsActualBlobAndQueryMustAllAgree() {
        val blob = LocalEmbeddingEngine.toBlob(floatArrayOf(0.6f, 0.8f))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob, dimensions = 2, queryDimensions = 1))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob, dimensions = 1, queryDimensions = 1))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob, dimensions = 0, queryDimensions = 0))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob, dimensions = -1, queryDimensions = -1))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob, dimensions = Int.MAX_VALUE, queryDimensions = Int.MAX_VALUE))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob.copyOf(7), dimensions = 2, queryDimensions = 2))
        assertNull(LocalEmbeddingEngine.decodeCompatibleVector(blob.copyOf(9), dimensions = 2, queryDimensions = 2))
    }

    @Test
    fun nonFiniteAndZeroRecordsAreSkipped() {
        for (vector in listOf(
            floatArrayOf(Float.NaN, 1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 1f),
            floatArrayOf(Float.NEGATIVE_INFINITY, 1f),
            floatArrayOf(0f, -0f)
        )) {
            assertNull(LocalEmbeddingEngine.decodeCompatibleVector(
                blob = LocalEmbeddingEngine.toBlob(vector),
                dimensions = 2,
                queryDimensions = 2
            ))
        }
    }

    @Test
    fun mockVectorsKeep128DimensionsAndStableSelfSimilarity() {
        val vector = LocalEmbeddingEngine.embedMock("本地知识库引用检索")
        val decoded = LocalEmbeddingEngine.decodeCompatibleVector(
            blob = LocalEmbeddingEngine.toBlob(vector),
            dimensions = LocalEmbeddingEngine.MOCK_DIMENSIONS,
            queryDimensions = vector.size
        )!!

        assertEquals(128, vector.size)
        assertArrayEquals(vector, decoded, 0f)
        assertEquals(1f, LocalEmbeddingEngine.norm(decoded), 0.000001f)
        assertEquals(1f, LocalEmbeddingEngine.dot(vector, decoded), 0.000001f)
    }
}
