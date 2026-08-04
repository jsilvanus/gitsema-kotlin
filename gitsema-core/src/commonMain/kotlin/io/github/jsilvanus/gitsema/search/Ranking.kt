package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.RepoPath

/**
 * Default weights match gitsema-TS's `vectorSearch.ts` exactly (kotlin-port.md
 * §4.2) — but per that section's finding, these are **inherited, unvalidated
 * defaults**: no eval history, no cited rationale exists anywhere in
 * gitsema-TS for 0.7/0.2/0.1 specifically. Treat them as a starting point to
 * re-validate at phone-index scale, not settled constants.
 */
data class RankingWeights(val vector: Double = 0.7, val recency: Double = 0.2, val path: Double = 0.1)

/**
 * Pure substring token match, matching gitsema-TS's `pathRelevanceScore()`
 * exactly (kotlin-port.md §4.2): no stemming, no directory-proximity, no
 * fuzzy matching — the fraction of [query]'s whitespace-split tokens that
 * appear as a substring anywhere in [path] (case-insensitive).
 */
fun pathRelevanceScore(query: String, path: String): Double {
    val tokens = query.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return 0.0
    val lowerPath = path.lowercase()
    val matches = tokens.count { lowerPath.contains(it) }
    return matches.toDouble() / tokens.size
}

/**
 * Linear min-max normalization of first-seen timestamps **within the current
 * candidate pool** (kotlin-port.md §4.2 — this is NOT exponential decay,
 * correcting an assumption in the original porting brief). The oldest blob in
 * the pool always scores 0, the newest always scores 1; there is no absolute
 * decay curve from "now."
 *
 * Returns null — recency unavailable for this whole ranking call — if any
 * candidate has no first-seen timestamp (e.g. commit-mapping hasn't been
 * built/run yet, kotlin-port.md's Indexer scope note). This is
 * constraint 5's "mark as degraded" principle applied to one ranking signal:
 * silently treating missing recency as a fixed neutral value would quietly
 * change every score's denominator without saying so; dropping the signal
 * entirely (and renormalizing the other weights, see [rankByThreeSignal]) is
 * the honest alternative.
 */
fun computeRecencyScores(candidates: List<RankingCandidate>): Map<BlobHash, Double>? {
    val timestamps = candidates.mapNotNull { it.firstSeenEpochSeconds }
    if (timestamps.size != candidates.size) return null
    if (timestamps.isEmpty()) return emptyMap()
    val minTs = timestamps.min()
    val maxTs = timestamps.max()
    val range = maxTs - minTs
    return candidates.associate { c ->
        c.blobHash to if (range == 0L) 1.0 else (c.firstSeenEpochSeconds!! - minTs).toDouble() / range
    }
}

/** One scored candidate going into three-signal ranking, before path/recency are applied. */
data class RankingCandidate(
    val blobHash: BlobHash,
    val paths: List<RepoPath>,
    val vectorScore: Double,
    val firstSeenEpochSeconds: Long?,
)

data class RankedResult(val blobHash: BlobHash, val paths: List<RepoPath>, val score: Double, val recencyAvailable: Boolean)

/**
 * `score = (wv*vectorScore + wr*recency + wp*pathScore) / (wv+wr+wp)`
 * (kotlin-port.md §4.2), with the weights actually used renormalized to
 * exclude recency's term when [computeRecencyScores] reports it unavailable
 * — rather than injecting a fixed neutral value that would silently dilute
 * every score by a fixed, meaningless amount.
 */
fun rankByThreeSignal(
    query: String,
    candidates: List<RankingCandidate>,
    weights: RankingWeights = RankingWeights(),
): List<RankedResult> {
    val recencyScores = computeRecencyScores(candidates)
    val wv = weights.vector
    val wr = if (recencyScores != null) weights.recency else 0.0
    val wp = weights.path
    val total = (wv + wr + wp).let { if (it == 0.0) 1.0 else it }

    return candidates.map { c ->
        val pathScore = c.paths.maxOfOrNull { pathRelevanceScore(query, it.value) } ?: 0.0
        val recencyScore = recencyScores?.get(c.blobHash) ?: 0.0
        val score = (wv * c.vectorScore + wr * recencyScore + wp * pathScore) / total
        RankedResult(c.blobHash, c.paths, score, recencyAvailable = recencyScores != null)
    }.sortedByDescending { it.score }
}
