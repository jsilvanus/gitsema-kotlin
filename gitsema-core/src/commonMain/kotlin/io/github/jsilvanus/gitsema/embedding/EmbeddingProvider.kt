package io.github.jsilvanus.gitsema.embedding

/**
 * Thrown by an [EmbeddingProvider] when a text exceeds the provider's context
 * window. Replaces gitsema-TS's regex-sniffing of provider error *text*
 * (`/context|input length|exceeds the context/i`, kotlin-port.md Decision C #4)
 * with a typed signal the indexer's fallback chain (kotlin-port.md §2.4) can
 * react to without depending on any one provider's error phrasing.
 */
class ContextLengthExceededException(
    message: String,
    val textLength: Int? = null,
) : Exception(message)

/**
 * The seam the porting brief requires: this library never loads a model. Every
 * concrete implementation is a thin client (HTTP, local process, or — on
 * Android — whatever admission-queued path the host provides) supplied by the
 * consuming application. See kotlin-port.md §3.1 and §8 ("confirmed portable as-is").
 *
 * [embed] must throw [ContextLengthExceededException] for a too-long input
 * rather than a generic exception, so the fallback chain (kotlin-port.md §2.4)
 * can distinguish "this text was too long" from "the provider is unreachable."
 */
interface EmbeddingProvider {
    /** Stable identifier for this provider+model, used as the dedup/provenance key (kotlin-port.md §1.1, §3.5). */
    val modelId: String

    /** Vector dimensionality this provider produces. Must be constant for a given [modelId] (kotlin-port.md §3.5). */
    val dimensions: Int

    /**
     * Embed a batch of texts, preserving order (`result[i]` corresponds to `texts[i]`).
     * Batch size is a request, not a guarantee — see kotlin-port.md §3.2 on host-owned
     * admission control; implementations may embed one at a time internally.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** Convenience for embedding a single text. */
suspend fun EmbeddingProvider.embed(text: String): FloatArray = embed(listOf(text)).single()
