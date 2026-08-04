package io.github.jsilvanus.gitsema.search

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.storage.FtsHit
import io.github.jsilvanus.gitsema.storage.VectorHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridSearchTest {
    private val a = BlobHash("a".repeat(40))
    private val b = BlobHash("b".repeat(40))
    private val c = BlobHash("c".repeat(40))

    @Test
    fun `a blob strong in both signals outranks one strong in only one`() {
        val vectorHits = listOf(VectorHit(a, 0.9), VectorHit(b, 0.9), VectorHit(c, 0.1))
        val ftsHits = listOf(FtsHit(a, bm25Score = -5.0), FtsHit(c, bm25Score = -5.0)) // lower bm25 raw = better match

        val blended = hybridBlend(vectorHits, ftsHits)

        assertTrue(blended.getValue(a) > blended.getValue(b))
        assertTrue(blended.getValue(a) > blended.getValue(c))
    }

    @Test
    fun `a blob present in only one signal still gets a score, not dropped`() {
        val vectorHits = listOf(VectorHit(a, 0.5))
        val ftsHits = listOf(FtsHit(b, bm25Score = -3.0))

        val blended = hybridBlend(vectorHits, ftsHits)

        assertEquals(setOf(a, b), blended.keys)
    }

    @Test
    fun `bm25Weight of 0 makes the blend pure vector`() {
        val vectorHits = listOf(VectorHit(a, 0.8), VectorHit(b, 0.2))
        val ftsHits = listOf(FtsHit(a, bm25Score = 100.0), FtsHit(b, bm25Score = -100.0)) // would favor b if BM25 mattered

        val blended = hybridBlend(vectorHits, ftsHits, bm25Weight = 0.0)

        assertTrue(blended.getValue(a) > blended.getValue(b))
    }

    @Test
    fun `bm25Weight of 1 makes the blend pure keyword`() {
        val vectorHits = listOf(VectorHit(a, 0.8), VectorHit(b, 0.2)) // would favor a if vector mattered
        val ftsHits = listOf(FtsHit(a, bm25Score = 100.0), FtsHit(b, bm25Score = -100.0)) // lower raw = better -> b wins on BM25

        val blended = hybridBlend(vectorHits, ftsHits, bm25Weight = 1.0)

        assertTrue(blended.getValue(b) > blended.getValue(a))
    }

    @Test
    fun `ties on one side normalize to the same neutral score on both sides`() {
        // kotlin-port.md §4.3: gitsema-TS ties inconsistently (BM25 ties -> 0.5,
        // vector ties -> 1.0). This port fixes that -- both use 0.5.
        val vectorHits = listOf(VectorHit(a, 0.5), VectorHit(b, 0.5))
        val ftsHits = listOf(FtsHit(a, bm25Score = -1.0), FtsHit(b, bm25Score = -1.0))

        val blended = hybridBlend(vectorHits, ftsHits, bm25Weight = 0.3)

        assertEquals(0.5, blended.getValue(a))
        assertEquals(0.5, blended.getValue(b))
    }

    @Test
    fun `empty inputs blend to an empty result, not an error`() {
        assertEquals(emptyMap(), hybridBlend(emptyList(), emptyList()))
    }
}
