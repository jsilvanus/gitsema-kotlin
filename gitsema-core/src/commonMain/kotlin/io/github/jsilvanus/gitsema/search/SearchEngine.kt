package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.embedding.embed
import io.github.jsilvanus.gitsema.model.IndexCoverage
import io.github.jsilvanus.gitsema.model.Match
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.model.SearchResult
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
 *   data isn't available yet.
 * - [Query.branch], when set, restricts results to blobs
 *   [MetadataStore.blobHashesOnBranch] reports for that ref — see the
 *   semantic note on `BlobBranches.sq` (indexed-under-this-ref-name, not
 *   full git branch topology). A branch with no recorded blobs (never
 *   indexed by that name) simply yields no results, not an error.
 *
 * - **Every search reports coverage** ([SearchResult.coverage]): blobs
 *   embedded for the active model over blobs known. Not a nicety — without
 *   it a half-built index answers "there is no retry logic here" when the
 *   truth is "I have not read most of it yet" (aidos D29).
 *
 * Not yet wired: chunk/symbol-level results (Tier 1 is whole-file only, so
 * [Match.startLine]/[Match.endLine] are placeholder `1..1` spans until a
 * real chunker records real spans).
 */
class SearchEngine(
    private val metadataStore: MetadataStore,
    private val vectorStore: VectorStore,
    private val ftsStore: FtsStore,
    private val provider: EmbeddingProvider,
    private val overFetchMultiplier: Int = 3,
    private val minOverFetch: Int = 50,
    // Constructor-injected rather than left to rankByThreeSignal/hybridBlend's
    // own defaults: kotlin-port.md §9.2 point 6 asks for these to be
    // configurable defaults instead of constants nobody can vary, precisely so
    // the eval harness (io.github.jsilvanus.gitsema.eval) can measure whether
    // 0.7/0.2/0.1 and 0.3 hold up at phone-index scale. Defaults unchanged.
    private val weights: RankingWeights = RankingWeights(),
    private val bm25Weight: Double = DEFAULT_BM25_WEIGHT,
) {
    suspend fun search(query: Query): SearchResult {
        val vectorCount = vectorStore.countForModel(provider.modelId)
        val coverage = IndexCoverage(blobsEmbedded = vectorCount, blobsKnown = metadataStore.blobCount())
        return if (vectorCount == 0L) {
            SearchResult(searchFtsOnly(query), coverage, degraded = true)
        } else {
            SearchResult(searchHybrid(query), coverage, degraded = false)
        }
    }

    private suspend fun searchFtsOnly(query: Query): List<Match> {
        val branchFilter = query.branch?.let { metadataStore.blobHashesOnBranch(it) }
        // Over-fetch before branch-filtering rather than after, so a narrow
        // branch doesn't starve the result count purely because FTS's own
        // topK cut happened first -- mirrors the over-fetch-then-narrow
        // shape searchHybrid already uses.
        val fetchLimit = if (branchFilter != null) maxOf(query.topK * overFetchMultiplier, minOverFetch) else query.topK
        val rawHits = ftsStore.search(query.text, fetchLimit)
        val hits = if (branchFilter != null) rawHits.filter { it.blobHash in branchFilter } else rawHits
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
        }.sortedByDescending { it.score }.take(query.topK)
    }

    private suspend fun searchHybrid(query: Query): List<Match> {
        val overFetch = maxOf(query.topK * overFetchMultiplier, minOverFetch)
        val branchFilter = query.branch?.let { metadataStore.blobHashesOnBranch(it) }
        val queryVector = provider.embed(query.text)
        val vectorHits = vectorStore.search(provider.modelId, queryVector, overFetch, candidateFilter = branchFilter)
        val ftsHitsRaw = ftsStore.search(query.text, overFetch)
        val ftsHits = if (branchFilter != null) ftsHitsRaw.filter { it.blobHash in branchFilter } else ftsHitsRaw
        val blended = hybridBlend(vectorHits, ftsHits, bm25Weight)

        val candidates = blended.map { (hash, score) ->
            RankingCandidate(
                blobHash = hash,
                paths = metadataStore.pathsFor(hash),
                vectorScore = score,
                firstSeenEpochSeconds = metadataStore.firstSeenFor(hash),
            )
        }
        val ranked = rankByThreeSignal(query.text, candidates, weights)

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
