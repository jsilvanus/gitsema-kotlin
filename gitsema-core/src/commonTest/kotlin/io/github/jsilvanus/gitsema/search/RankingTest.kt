package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankingTest {
    @Test
    fun `pathRelevanceScore is the fraction of query tokens present as a substring`() {
        assertEquals(1.0, pathRelevanceScore("auth", "src/auth/oauth.ts"))
        assertEquals(0.5, pathRelevanceScore("auth missing", "src/auth/oauth.ts"))
        assertEquals(0.0, pathRelevanceScore("nothing matches", "src/auth/oauth.ts"))
    }

    @Test
    fun `pathRelevanceScore is case-insensitive`() {
        assertEquals(1.0, pathRelevanceScore("AUTH", "src/Auth/OAuth.ts"))
    }

    @Test
    fun `pathRelevanceScore is 0 for an empty query`() {
        assertEquals(0.0, pathRelevanceScore("", "src/anything.ts"))
    }

    @Test
    fun `computeRecencyScores is linear min-max over the pool, not decay`() {
        val candidates = listOf(
            RankingCandidate(BlobHash("a".repeat(40)), emptyList(), 0.9, firstSeenEpochSeconds = 1000),
            RankingCandidate(BlobHash("b".repeat(40)), emptyList(), 0.8, firstSeenEpochSeconds = 2000),
            RankingCandidate(BlobHash("c".repeat(40)), emptyList(), 0.7, firstSeenEpochSeconds = 3000),
        )

        val scores = computeRecencyScores(candidates)!!

        assertEquals(0.0, scores.getValue(BlobHash("a".repeat(40))))
        assertEquals(0.5, scores.getValue(BlobHash("b".repeat(40))))
        assertEquals(1.0, scores.getValue(BlobHash("c".repeat(40))))
    }

    @Test
    fun `computeRecencyScores returns null when any candidate lacks a first-seen timestamp`() {
        val candidates = listOf(
            RankingCandidate(BlobHash("a".repeat(40)), emptyList(), 0.9, firstSeenEpochSeconds = 1000),
            RankingCandidate(BlobHash("b".repeat(40)), emptyList(), 0.8, firstSeenEpochSeconds = null),
        )

        assertNull(computeRecencyScores(candidates))
    }

    @Test
    fun `computeRecencyScores gives every candidate 1-0 when all timestamps tie`() {
        val candidates = listOf(
            RankingCandidate(BlobHash("a".repeat(40)), emptyList(), 0.9, firstSeenEpochSeconds = 500),
            RankingCandidate(BlobHash("b".repeat(40)), emptyList(), 0.8, firstSeenEpochSeconds = 500),
        )

        val scores = computeRecencyScores(candidates)!!

        assertEquals(1.0, scores.getValue(BlobHash("a".repeat(40))))
        assertEquals(1.0, scores.getValue(BlobHash("b".repeat(40))))
    }

    @Test
    fun `rankByThreeSignal renormalizes weights when recency is unavailable, rather than faking a neutral value`() {
        val candidates = listOf(
            RankingCandidate(BlobHash("a".repeat(40)), listOf(RepoPath("a.txt")), vectorScore = 1.0, firstSeenEpochSeconds = null),
            RankingCandidate(BlobHash("b".repeat(40)), listOf(RepoPath("b.txt")), vectorScore = 0.0, firstSeenEpochSeconds = null),
        )

        val ranked = rankByThreeSignal("irrelevant query", candidates)

        assertTrue(ranked.none { it.recencyAvailable })
        // With recency dropped (weight 0), only vector (0.7) and path (0.1)
        // remain, renormalized over their own sum: score = (0.7*vectorScore +
        // 0.1*pathScore) / 0.8. Neither path matches the query, so pathScore
        // is 0 for both -- but the path weight still contributes to the
        // denominator, so this does NOT reduce to raw vectorScore: it's
        // 0.7/0.8 = 0.875 for a, 0 for b.
        val a = ranked.first { it.blobHash == BlobHash("a".repeat(40)) }
        val b = ranked.first { it.blobHash == BlobHash("b".repeat(40)) }
        assertTrue(kotlin.math.abs(a.score - 0.875) < 1e-9, "expected ~0.875, got ${a.score}")
        assertTrue(kotlin.math.abs(b.score - 0.0) < 1e-9, "expected ~0.0, got ${b.score}")
    }

    @Test
    fun `rankByThreeSignal sorts descending by score`() {
        val candidates = listOf(
            RankingCandidate(BlobHash("low".padEnd(40, '0')), emptyList(), vectorScore = 0.1, firstSeenEpochSeconds = 100),
            RankingCandidate(BlobHash("high".padEnd(40, '0')), emptyList(), vectorScore = 0.9, firstSeenEpochSeconds = 100),
            RankingCandidate(BlobHash("mid".padEnd(40, '0')), emptyList(), vectorScore = 0.5, firstSeenEpochSeconds = 100),
        )

        val ranked = rankByThreeSignal("query", candidates)

        assertEquals(listOf("high".padEnd(40, '0'), "mid".padEnd(40, '0'), "low".padEnd(40, '0')), ranked.map { it.blobHash.value })
    }
}
