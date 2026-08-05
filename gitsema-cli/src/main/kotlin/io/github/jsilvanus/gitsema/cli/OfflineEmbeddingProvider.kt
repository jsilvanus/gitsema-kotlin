package io.github.jsilvanus.gitsema.cli

import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider

/**
 * Stands in for a model that isn't reachable, so read-only commands still work
 * without one.
 *
 * Constraint 6 (kotlin-port.md §9.2 point 5) says keyword search works before
 * any embedding model exists, and constraint 5 says search is never blocked on
 * indexing. A CLI that demanded a live endpoint before it would run `status`
 * or a degraded `search` would contradict both — the index on disk is a
 * complete answer to "what do you know", and asking a model server for
 * permission to read it is backwards.
 *
 * [modelId] comes from the flags/environment rather than from the endpoint,
 * which is what makes this possible: coverage counts vectors *for a model
 * name*, and the name is known without asking anyone. [dimensions] is 0
 * because nothing can legitimately read it here — any path that needs a real
 * dimensionality is a path that embeds, and [embed] refuses.
 *
 * Never used for `index`: indexing without a model produces nothing, so that
 * command fails loudly instead of falling back.
 */
class OfflineEmbeddingProvider(override val modelId: String) : EmbeddingProvider {
    override val dimensions: Int = 0

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        throw EmbeddingProtocolException(
            "no embedding endpoint is reachable, so '$modelId' cannot embed anything right now — " +
                "keyword (FTS) results are still available, vector search is not",
        )
}
