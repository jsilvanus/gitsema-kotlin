package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.storage.FtsHit
import io.github.jsilvanus.gitsema.storage.VectorHit

/** Default matches gitsema-TS's `hybridSearch.ts` (kotlin-port.md §4.3). */
const val DEFAULT_BM25_WEIGHT = 0.3

/**
 * Min-max normalizes vector cosine scores and FTS5 BM25 scores independently
 * to `[0,1]`, then blends `(1 - bm25Weight) * normVec + bm25Weight * normBm25`
 * (kotlin-port.md §4.3). SQLite's raw `bm25()` is lower-is-better, so
 * [ftsHits] scores are negated before normalizing.
 *
 * One deliberate departure from gitsema-TS, called out in kotlin-port.md
 * §4.3 as worth fixing rather than preserving: gitsema-TS's tie-handling is
 * inconsistent between the two sides (BM25 ties normalize to a neutral 0.5,
 * vector ties normalize to 1.0, unexplained in source). Both sides here use
 * the same neutral 0.5 for ties — "every candidate is equally good" should
 * mean the same thing regardless of which score produced the tie.
 */
fun hybridBlend(
    vectorHits: List<VectorHit>,
    ftsHits: List<FtsHit>,
    bm25Weight: Double = DEFAULT_BM25_WEIGHT,
): Map<BlobHash, Double> {
    val vectorScores = vectorHits.associate { it.blobHash to it.score }
    val bm25Scores = ftsHits.associate { it.blobHash to -it.bm25Score } // negate: higher is now better, matching vector convention

    val normVector = minMaxNormalize(vectorScores)
    val normBm25 = minMaxNormalize(bm25Scores)

    val allHashes = vectorScores.keys + bm25Scores.keys
    return allHashes.associateWith { hash ->
        val v = normVector[hash] ?: 0.0
        val b = normBm25[hash] ?: 0.0
        (1 - bm25Weight) * v + bm25Weight * b
    }
}

private const val NEUTRAL_TIE_SCORE = 0.5

/** Also used by [io.github.jsilvanus.gitsema.search.SearchEngine] to scale a lone FTS score list to a comparable [0,1] range when no vectors are indexed yet. */
internal fun minMaxNormalize(scores: Map<BlobHash, Double>): Map<BlobHash, Double> {
    if (scores.isEmpty()) return emptyMap()
    val values = scores.values
    val min = values.min()
    val max = values.max()
    val range = max - min
    return scores.mapValues { (_, v) -> if (range == 0.0) NEUTRAL_TIE_SCORE else (v - min) / range }
}
