package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.embedding.embed
import io.github.jsilvanus.gitsema.model.Match
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.storage.FtsStore
import io.github.jsilvanus.gitsema.storage.MetadataStore
import io.github.jsilvanus.gitsema.storage.VectorStore

/**
 * Composes [FtsStore], [VectorStore], and [MetadataStore] into gitsema's
 * actual search contract (kotlin-port.md §4, constraints 5 and 6):
 *
 * - **FTS first** (constraint 6): if [VectorStore] has no vectors yet for
 *   this model, search is FTS-only — never blocked on embeddings existing.
 * - **Degraded, not blocked** (constraint 5): the FTS-only path marks every
 *   [Match] `degraded = true` rather than returning nothing while waiting
 *   for vectors, or silently pretending FTS-only results are as good as a
 *   hybrid search.
 * - Once vectors exist, results are the [hybridBlend] of vector cosine and
 *   BM25 (kotlin-port.md §4.3), then re-ranked by [rankByThreeSignal]
 *   (§4.2), with recency dropped gracefully (not faked) when first-seen
 *   data isn't available yet (kotlin-port.md's Indexer scope note —
 *   commit-mapping isn't built).
 *
 * Not yet wired: [Query.branch] filtering (needs `blob_branches`-equivalent
 * data from the not-yet-built commit-mapping pass — same gap noted on
 * [io.github.jsilvanus.gitsema.indexing.Indexer]) and chunk/symbol-level
 * results (Tier 1 is whole-file only, so [Match.startLine]/[Match.endLine]
 * are placeholder `1..1` spans until a real chunker records real spans).
 */
class SearchEngine(
    private val metadataStore: MetadataStore,
    private val vectorStore: VectorStore,
    private val ftsStore: FtsStore,
    private val provider: EmbeddingProvider,
    private val overFetchMultiplier: Int = 3,
    private val minOverFetch: Int = 50,
) {
    suspend fun search(query: Query): List<Match> {
        val vectorCount = vectorStore.countForModel(provider.modelId)
        if (vectorCount == 0L) {
            return searchFtsOnly(query)
        }
        return searchHybrid(query)
    }

    private suspend fun searchFtsOnly(query: Query): List<Match> {
        val hits = ftsStore.search(query.text, query.topK)
        val normalized = minMaxNormalize(hits.associate { it.blobHash to -it.bm25Score })
        return hits.map { hit ->
            Match(
                blobHash = hit.blobHash,
                paths = metadataStore.pathsFor(hit.blobHash),
                startLine = 1,
                endLine = 1,
                score = normalized[hit.blobHash] ?: 0.0,
                provenance = MatchProvenance.FTS,
                degraded = true,
            )
        }.sortedByDescending { it.score }
    }

    private suspend fun searchHybrid(query: Query): List<Match> {
        val overFetch = maxOf(query.topK * overFetchMultiplier, minOverFetch)
        val queryVector = provider.embed(query.text)
        val vectorHits = vectorStore.search(provider.modelId, queryVector, overFetch)
        val ftsHits = ftsStore.search(query.text, overFetch)
        val blended = hybridBlend(vectorHits, ftsHits)

        val candidates = blended.map { (hash, score) ->
            RankingCandidate(
                blobHash = hash,
                paths = metadataStore.pathsFor(hash),
                vectorScore = score,
                firstSeenEpochSeconds = metadataStore.firstSeenFor(hash),
            )
        }
        val ranked = rankByThreeSignal(query.text, candidates)

        return ranked.take(query.topK).map { r ->
            Match(
                blobHash = r.blobHash,
                paths = r.paths,
                startLine = 1,
                endLine = 1,
                score = r.score,
                provenance = MatchProvenance.HYBRID,
                degraded = false,
            )
        }
    }
}
