package io.github.jsilvanus.gitsema.testutil

import io.github.jsilvanus.gitsema.embedding.ContextLengthExceededException
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import kotlin.math.sin

/**
 * A deterministic, dependency-free [EmbeddingProvider] for tests — no network,
 * no model, no randomness. The same text always produces the same vector, and
 * distinct texts produce distinct vectors, which is all the indexing/dedup
 * tests need (kotlin-port.md deliverable #3: "everything runs against a fake
 * EmbeddingProvider").
 *
 * Optionally simulates a context-limit failure above [maxTextLength], so the
 * fallback chain (kotlin-port.md §2.4) can be tested without a real provider.
 */
class FakeEmbeddingProvider(
    override val dimensions: Int = 8,
    override val modelId: String = "fake-test-model",
    private val maxTextLength: Int? = null,
) : EmbeddingProvider {
    var callCount: Int = 0
        private set

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        callCount++
        return texts.map { text ->
            maxTextLength?.let {
                if (text.length > it) {
                    throw ContextLengthExceededException(
                        "fake provider: text length ${text.length} exceeds context limit $it",
                        textLength = text.length,
                    )
                }
            }
            deterministicVector(text, dimensions)
        }
    }
}

/** A stable hash-derived unit vector — same text always maps to the same vector, distinct text (almost) never collides. */
fun deterministicVector(text: String, dimensions: Int): FloatArray {
    var seed = 1469598103934665603UL // FNV-1a 64-bit offset basis
    for (ch in text) {
        seed = seed xor ch.code.toULong()
        seed *= 1099511628211UL // FNV-1a prime
    }
    val vector = FloatArray(dimensions)
    var state = seed
    for (i in 0 until dimensions) {
        state = state * 6364136223846793005UL + 1442695040888963407UL
        // Map to [-1, 1] via sin of the scrambled state, then let normalization below fix magnitude.
        vector[i] = sin(state.toDouble()).toFloat()
    }
    val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
    if (norm > 0f) {
        for (i in vector.indices) vector[i] = vector[i] / norm
    }
    return vector
}
