package io.github.jsilvanus.gitsema.eval

import io.github.jsilvanus.gitsema.SemanticIndex
import io.github.jsilvanus.gitsema.model.IndexCoverage
import io.github.jsilvanus.gitsema.model.Query

/**
 * One evaluation case: a query, and the paths a correct answer should
 * surface. Mirrors gitsema-TS's JSONL shape (`{query, expectedPaths}`) so
 * existing eval sets transfer unchanged — parsing JSONL is the host's job,
 * not this library's (it has no serialization dependency and does not want
 * one for this).
 */
data class EvalCase(val query: String, val expectedPaths: Set<String>)

/** Per-case outcome. [firstRelevantRank] is 1-based, or null when nothing relevant was retrieved. */
data class EvalCaseResult(
    val case: EvalCase,
    val retrievedPaths: List<String>,
    val precisionAtK: Double,
    val recallAtK: Double,
    val reciprocalRank: Double,
    val firstRelevantRank: Int?,
    /** Coverage this case's search ran under — see [EvalReport.minCoverage] for why it is recorded. */
    val coverage: IndexCoverage,
    /** True if this case's search was FTS-only (no vectors for the active model). */
    val degraded: Boolean,
)

/**
 * Aggregate over all cases. Means are unweighted — every case counts once,
 * regardless of how many expected paths it names.
 */
data class EvalReport(
    val k: Int,
    val cases: List<EvalCaseResult>,
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    /**
     * The lowest coverage any case ran under.
     *
     * Reported because a retrieval score measured against a 12%-covered index
     * is not a measurement of retrieval quality — it is a measurement of how
     * much indexing has happened. Reading `mrr` without reading this is the
     * mistake this field exists to prevent, which is why it sits on the
     * report rather than needing a separate `status()` call to reconstruct.
     */
    val minCoverage: IndexCoverage,
    /** True if any case ran degraded (FTS-only). Such a run says nothing about vector ranking. */
    val anyDegraded: Boolean,
)

/**
 * Runs [cases] against [index] and scores the results — precision@k,
 * recall@k, and MRR (kotlin-port.md Decision C #7, §9.2 point 6).
 *
 * **Why this exists at Tier 1 rather than later.** The three-signal ranking
 * weights (0.7/0.2/0.1) and the BM25 blend weight (0.3) are inherited from
 * gitsema-TS, where nothing in the source or its docs evaluates them. Copying
 * them into a phone-scale single-repo index and treating them as settled
 * would be inheriting an assumption. This harness is what makes them
 * checkable: construct a `GitsemaSemanticIndex` with different
 * `RankingWeights`/`bm25Weight` and compare reports on the same cases.
 *
 * **Metric definitions** match gitsema-TS's `eval.ts` so numbers are
 * comparable across the two implementations:
 * - **precision@k** = relevant retrieved / *retrieved* (not / k), and 0.0
 *   when nothing was retrieved. Dividing by the returned count rather than k
 *   is TS's choice; it means a small index is not penalised for having fewer
 *   than k results to give.
 * - **recall@k** = expected paths found / expected paths, and 1.0 when a
 *   case names no expected paths (vacuously satisfied, as in TS).
 * - **MRR** = 1 / rank of the first relevant result, 0.0 if none.
 *
 * One deliberate deviation: a [io.github.jsilvanus.gitsema.model.Match]
 * carries *all* paths its blob is known at, so a result counts as relevant
 * when **any** of its paths is expected, and recall counts **distinct**
 * expected paths found. TS compares one path per result and can therefore
 * double-count the same expected path appearing twice; that is a bug to not
 * reproduce, not a semantic to match.
 */
suspend fun evaluate(index: SemanticIndex, cases: List<EvalCase>, k: Int = 10): EvalReport {
    require(k > 0) { "k must be positive, was $k" }

    val results = cases.map { case ->
        val result = index.search(Query(text = case.query, topK = k))

        // A match is relevant if any path it is known at is expected.
        val relevantFlags = result.matches.map { match ->
            match.paths.any { it.value in case.expectedPaths }
        }
        val relevantCount = relevantFlags.count { it }
        val foundExpected = result.matches
            .flatMap { match -> match.paths.map { it.value } }
            .filter { it in case.expectedPaths }
            .toSet()
        val firstRelevantIndex = relevantFlags.indexOfFirst { it }

        EvalCaseResult(
            case = case,
            retrievedPaths = result.matches.map { it.paths.firstOrNull()?.value ?: "" },
            precisionAtK = if (result.matches.isEmpty()) 0.0 else relevantCount.toDouble() / result.matches.size,
            recallAtK = if (case.expectedPaths.isEmpty()) 1.0 else foundExpected.size.toDouble() / case.expectedPaths.size,
            reciprocalRank = if (firstRelevantIndex < 0) 0.0 else 1.0 / (firstRelevantIndex + 1),
            firstRelevantRank = firstRelevantIndex.takeIf { it >= 0 }?.plus(1),
            coverage = result.coverage,
            degraded = result.degraded,
        )
    }

    return EvalReport(
        k = k,
        cases = results,
        precisionAtK = results.meanOf { it.precisionAtK },
        recallAtK = results.meanOf { it.recallAtK },
        mrr = results.meanOf { it.reciprocalRank },
        // An empty case list has no coverage to report; 0/0 is the honest
        // stand-in, and its `fraction` is already defined as 0.0.
        minCoverage = results.minByOrNull { it.coverage.fraction } ?.coverage ?: IndexCoverage(0, 0),
        anyDegraded = results.any { it.degraded },
    )
}

/** Mean over cases, 0.0 for an empty set rather than NaN — a report is data, and NaN propagates. */
private inline fun <T> List<T>.meanOf(selector: (T) -> Double): Double =
    if (isEmpty()) 0.0 else sumOf(selector) / size
