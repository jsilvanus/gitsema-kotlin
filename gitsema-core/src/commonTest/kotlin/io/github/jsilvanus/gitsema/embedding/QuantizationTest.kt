package io.github.jsilvanus.gitsema.embedding

import io.github.jsilvanus.gitsema.testutil.deterministicVector
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuantizationTest {
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    @Test
    fun `round trip stays within gitsema-TS's own quantize test bar (cosine similarity above 0-98)`() {
        repeat(20) { seed ->
            val original = deterministicVector("quantization-round-trip-$seed", dimensions = 256)
            val quantized = quantizeVector(original)
            val recovered = dequantizeVector(quantized)

            val similarity = cosine(original, recovered)
            assertTrue(similarity > 0.98, "round-trip cosine similarity $similarity should exceed 0.98 for seed $seed")
        }
    }

    @Test
    fun `quantized data is exactly 1 byte per dimension -- 4x smaller than Float32`() {
        val vector = deterministicVector("size-check", dimensions = 768)
        val quantized = quantizeVector(vector)

        assertEquals(768, quantized.data.size)
        // Float32Array would be 768 * 4 = 3072 bytes; int8 is 768 bytes -- exactly 4x smaller.
        assertEquals(vector.size, quantized.data.size)
    }

    @Test
    fun `an all-equal vector does not divide by zero`() {
        val flat = FloatArray(16) { 0.5f }

        val quantized = quantizeVector(flat)
        val recovered = dequantizeVector(quantized)

        assertTrue(recovered.all { it.isFinite() })
    }

    @Test
    fun `cosineSimilarityQuantized matches a plain dequantize-then-cosine computation`() {
        val query = deterministicVector("query", dimensions = 64)
        val candidate = deterministicVector("candidate", dimensions = 64)
        val quantized = quantizeVector(candidate)

        val direct = cosineSimilarityQuantized(query, quantized.data, quantized.min, quantized.scale)
        val viaDequantize = cosine(query, dequantizeVector(quantized))

        assertTrue(kotlin.math.abs(direct - viaDequantize) < 1e-9, "expected $direct to equal $viaDequantize")
    }

    @Test
    fun `quantization is deterministic -- same input always produces the same bytes`() {
        val vector = deterministicVector("determinism-check", dimensions = 128)

        val first = quantizeVector(vector)
        val second = quantizeVector(vector)

        assertEquals(first, second)
    }
}
