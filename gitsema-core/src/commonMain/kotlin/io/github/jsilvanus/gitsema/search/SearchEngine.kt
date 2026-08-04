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
 *   data isn't available yet.
 * - [Query.branch], when set, restricts results to blobs
 *   [MetadataStore.blobHashesOnBranch] reports for that ref — see the
 *   semantic note on `BlobBranches.sq` (indexed-under-this-ref-name, not
 *   full git branch topology). A branch with no recorded blobs (never
 *   indexed by that name) simply yields no results, not an error.
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
) {
    suspend fun search(query: Query): List<Match> {
        val vectorCount = vectorStore.countForModel(provider.modelId)
        if (vectorCount == 0L) {
            return searchFtsOnly(query)
        }
        return searchHybrid(query)
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
