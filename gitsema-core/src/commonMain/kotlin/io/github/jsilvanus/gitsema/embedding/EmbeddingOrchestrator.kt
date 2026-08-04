package io.github.jsilvanus.gitsema.embedding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One text to embed, carrying whatever identifier the caller wants back attached to the result. */
data class EmbedRequest(val id: String, val text: String)

sealed class EmbedOutcome {
    abstract val id: String
    data class Success(override val id: String, val vector: FloatArray) : EmbedOutcome()
    data class Failed(override val id: String, val cause: Throwable) : EmbedOutcome()
}

/**
 * Batches and concurrency-limits calls to an [EmbeddingProvider] (kotlin-port.md
 * §3.2). Default concurrency of 4 matches gitsema-TS's `p-limit` default
 * (CLAUDE.md design constraint 2: "don't remove this throttle") — in Kotlin
 * this is a bounded [Semaphore] rather than a library, same effect.
 *
 * Per-item failure containment (kotlin-port.md §7.4): one bad item never
 * sinks a whole batch. A batch call that throws falls back to embedding each
 * of its items individually, exactly mirroring gitsema-TS's `BatchingProvider`
 * retry-then-per-item-fallback shape — simplified here to a single fallback
 * pass rather than gitsema-TS's exponential-backoff retry loop, which is a
 * deliberate Tier 1 simplification, not an oversight: correctness (no lost
 * work, no batch-wide failure from one bad item) matters more here than
 * matching the retry timing exactly.
 */
class EmbeddingOrchestrator(
    private val provider: EmbeddingProvider,
    private val concurrency: Int = 4,
    private val batchSize: Int = 1,
) {
    suspend fun embedAll(requests: List<EmbedRequest>): List<EmbedOutcome> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        requests.chunked(batchSize.coerceAtLeast(1))
            .map { chunk -> async { semaphore.withPermit { embedChunk(chunk) } } }
            .awaitAll()
            .flatten()
    }

    private suspend fun embedChunk(chunk: List<EmbedRequest>): List<EmbedOutcome> {
        if (chunk.size == 1) return listOf(embedOne(chunk.single()))
        return try {
            val vectors = provider.embed(chunk.map { it.text })
            chunk.zip(vectors).map { (request, vector) -> EmbedOutcome.Success(request.id, vector) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            chunk.map { embedOne(it) }
        }
    }

    private suspend fun embedOne(request: EmbedRequest): EmbedOutcome = try {
        EmbedOutcome.Success(request.id, provider.embed(request.text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EmbedOutcome.Failed(request.id, e)
    }
}
