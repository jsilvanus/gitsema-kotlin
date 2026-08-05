package io.github.jsilvanus.gitsema.eval

import io.github.jsilvanus.gitsema.SemanticIndex
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.IndexCoverage
import io.github.jsilvanus.gitsema.model.IndexProgress
import io.github.jsilvanus.gitsema.model.IndexResult
import io.github.jsilvanus.gitsema.model.IndexStatus
import io.github.jsilvanus.gitsema.model.Match
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.model.RepoPath
import io.github.jsilvanus.gitsema.model.SearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metrics are the point here, so the index is a stub returning fixed
 * results — this measures the harness, not retrieval.
 */
class EvaluationTest {
    private class StubIndex(
        private val resultsByQuery: Map<String, List<String>>,
        private val coverage: IndexCoverage = IndexCoverage(10, 10),
        private val degraded: Boolean = false,
    ) : SemanticIndex {
        override suspend fun index(ref: String, onProgress: (IndexProgress) -> Unit): IndexResult =
            IndexResult(0, 0, 0, 0, 0, 0)

        override suspend fun search(query: Query): SearchResult {
            val paths = resultsByQuery[query.text].orEmpty().take(query.topK)
            return SearchResult(
                matches = paths.mapIndexed { i, path ->
                    Match(
                        blobHash = BlobHash("hash$i".padEnd(40, '0')),
                        paths = listOf(RepoPath(path)),
                        startLine = 1,
                        endLine = 1,
                        score = 1.0 - i * 0.1,
                        provenance = if (degraded) MatchProvenance.FTS else MatchProvenance.HYBRID,
                        degraded = degraded,
                    )
                },
                coverage = coverage,
                degraded = degraded,
            )
        }

        override suspend fun status(): IndexStatus = IndexStatus(coverage, null, null, null)
    }

    @Test
    fun `precision recall and MRR for a perfect first hit`() = runTest {
        val index = StubIndex(mapOf("auth" to listOf("src/auth.kt", "src/other.kt")))

        val report = evaluate(index, listOf(EvalCase("auth", setOf("src/auth.kt"))), k = 2)

        val case = report.cases.single()
        assertEquals(0.5, case.precisionAtK, "1 of 2 retrieved results is relevant")
        assertEquals(1.0, case.recallAtK, "the one expected path was found")
        assertEquals(1.0, case.reciprocalRank, "it was ranked first")
        assertEquals(1, case.firstRelevantRank)
    }

    @Test
    fun `reciprocal rank falls off with the rank of the first relevant hit`() = runTest {
        val index = StubIndex(mapOf("auth" to listOf("a.kt", "b.kt", "src/auth.kt")))

        val report = evaluate(index, listOf(EvalCase("auth", setOf("src/auth.kt"))), k = 3)

        assertEquals(1.0 / 3.0, report.mrr)
        assertEquals(3, report.cases.single().firstRelevantRank)
    }

    @Test
    fun `nothing relevant retrieved scores zero, not NaN`() = runTest {
        val index = StubIndex(mapOf("auth" to listOf("a.kt", "b.kt")))

        val report = evaluate(index, listOf(EvalCase("auth", setOf("src/auth.kt"))), k = 2)

        assertEquals(0.0, report.precisionAtK)
        assertEquals(0.0, report.recallAtK)
        assertEquals(0.0, report.mrr)
        assertNull(report.cases.single().firstRelevantRank)
    }

    @Test
    fun `an empty result set scores zero precision rather than dividing by zero`() = runTest {
        val index = StubIndex(emptyMap())

        val report = evaluate(index, listOf(EvalCase("nothing matches", setOf("src/auth.kt"))), k = 5)

        assertEquals(0.0, report.precisionAtK)
        assertEquals(0.0, report.recallAtK)
    }

    @Test
    fun `a case naming no expected paths is vacuously satisfied, matching gitsema-TS`() = runTest {
        val index = StubIndex(mapOf("anything" to listOf("a.kt")))

        val report = evaluate(index, listOf(EvalCase("anything", emptySet())), k = 5)

        assertEquals(1.0, report.recallAtK)
    }

    @Test
    fun `recall counts distinct expected paths, not repeated hits on the same one`() = runTest {
        // The deviation from gitsema-TS documented on evaluate(): TS compares
        // one path per result and would score this 1.0 by counting the same
        // expected path twice. Two of three expected paths were found.
        val index = StubIndex(mapOf("auth" to listOf("src/auth.kt", "src/auth.kt", "src/login.kt")))

        val report = evaluate(
            index,
            listOf(EvalCase("auth", setOf("src/auth.kt", "src/login.kt", "src/session.kt"))),
            k = 5,
        )

        assertEquals(2.0 / 3.0, report.cases.single().recallAtK)
    }

    @Test
    fun `aggregates are unweighted means across cases`() = runTest {
        val index = StubIndex(
            mapOf(
                "hit" to listOf("src/hit.kt"),
                "miss" to listOf("src/wrong.kt"),
            ),
        )

        val report = evaluate(
            index,
            listOf(EvalCase("hit", setOf("src/hit.kt")), EvalCase("miss", setOf("src/right.kt"))),
            k = 1,
        )

        assertEquals(0.5, report.precisionAtK)
        assertEquals(0.5, report.mrr)
        assertEquals(2, report.cases.size)
    }

    @Test
    fun `a report carries the coverage it was measured under and flags a degraded run`() = runTest {
        // A score measured against a 20%-covered index measures indexing
        // progress, not ranking quality. The report has to say so on its face.
        val index = StubIndex(
            mapOf("auth" to listOf("src/auth.kt")),
            coverage = IndexCoverage(blobsEmbedded = 2, blobsKnown = 10),
            degraded = true,
        )

        val report = evaluate(index, listOf(EvalCase("auth", setOf("src/auth.kt"))), k = 5)

        assertEquals(2L, report.minCoverage.blobsEmbedded)
        assertEquals(10L, report.minCoverage.blobsKnown)
        assertEquals(0.2, report.minCoverage.fraction)
        assertTrue(report.anyDegraded, "an FTS-only run says nothing about vector ranking and must not read as a clean measurement")
    }

    @Test
    fun `an empty case list produces a zeroed report rather than NaN`() = runTest {
        val report = evaluate(StubIndex(emptyMap()), emptyList(), k = 5)

        assertEquals(0.0, report.precisionAtK)
        assertEquals(0.0, report.mrr)
        assertEquals(0L, report.minCoverage.blobsKnown)
    }
}
