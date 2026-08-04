package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.testutil.deterministicVector
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MODEL = "fake-test-model"
private const val DIMS = 32

class FlatFileVectorStoreTest {
    private lateinit var tempDir: File
    private lateinit var store: FlatFileVectorStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("gitsema-vector-store-test").toFile()
        val driver = createSqlDriver(File(tempDir, "index.db").absolutePath)
        store = FlatFileVectorStore(GitsemaDatabase(driver), vectorDir = File(tempDir, "vectors"))
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `upsert then search finds the nearest match first`() = runTest {
        val target = deterministicVector("the target text", DIMS)
        val unrelatedA = deterministicVector("something else entirely", DIMS)
        val unrelatedB = deterministicVector("yet another unrelated string", DIMS)

        store.upsert(BlobHash("target".padEnd(40, '0')), MODEL, target)
        store.upsert(BlobHash("a".repeat(40)), MODEL, unrelatedA)
        store.upsert(BlobHash("b".repeat(40)), MODEL, unrelatedB)

        // Querying with the exact same vector as "target" should rank it first
        // (cosine similarity ~1.0 to itself, modulo quantization error).
        val hits = store.search(MODEL, target, topK = 3)

        assertEquals(3, hits.size)
        assertEquals("target".padEnd(40, '0'), hits.first().blobHash.value)
        assertTrue(hits.first().score > hits[1].score)
        // Descending order.
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun `search respects topK even when more candidates are stored`() = runTest {
        repeat(10) { i ->
            store.upsert(BlobHash(i.toString().repeat(40)), MODEL, deterministicVector("text number $i", DIMS))
        }

        val hits = store.search(MODEL, deterministicVector("text number 3", DIMS), topK = 4)

        assertEquals(4, hits.size)
    }

    @Test
    fun `search on an empty store returns no results rather than throwing`() = runTest {
        val hits = store.search(MODEL, deterministicVector("anything", DIMS), topK = 5)

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `search respects a candidate filter`() = runTest {
        val included = BlobHash("1".repeat(40))
        val excluded = BlobHash("2".repeat(40))
        store.upsert(included, MODEL, deterministicVector("included text", DIMS))
        store.upsert(excluded, MODEL, deterministicVector("excluded text", DIMS))

        val hits = store.search(MODEL, deterministicVector("included text", DIMS), topK = 10, candidateFilter = setOf(included))

        assertEquals(1, hits.size)
        assertEquals(included, hits.single().blobHash)
    }

    @Test
    fun `isIndexed and upsert are idempotent -- the same blob indexed twice does not duplicate`() = runTest {
        val hash = BlobHash("c".repeat(40))
        assertFalse(store.isIndexed(hash, MODEL))

        store.upsert(hash, MODEL, deterministicVector("content", DIMS))
        store.upsert(hash, MODEL, deterministicVector("content", DIMS)) // re-embedding the same blob must be a no-op

        assertTrue(store.isIndexed(hash, MODEL))
        assertEquals(1L, store.countForModel(MODEL))
    }

    @Test
    fun `filterNewBlobs returns only the hashes not yet indexed for this model`() = runTest {
        val already = BlobHash("d".repeat(40))
        val new1 = BlobHash("e".repeat(40))
        val new2 = BlobHash("f".repeat(40))
        store.upsert(already, MODEL, deterministicVector("already indexed", DIMS))

        val toEmbed = store.filterNewBlobs(listOf(already, new1, new2), MODEL)

        assertEquals(setOf(new1, new2), toEmbed)
    }

    @Test
    fun `different models for the same blob coexist independently`() = runTest {
        val hash = BlobHash("g".repeat(40))
        store.upsert(hash, "model-a", deterministicVector("content", DIMS))

        assertTrue(store.isIndexed(hash, "model-a"))
        assertFalse(store.isIndexed(hash, "model-b"))
    }

    @Test
    fun `a blob stored as several fallback chunks is deduplicated to one hit, its best-scoring chunk`() = runTest {
        // kotlin-port.md §2.4's context-limit fallback storage: a blob that
        // couldn't be embedded whole gets one vector_entry row per surviving
        // chunk, all sharing one blob_hash. search() must still return
        // exactly one hit for that blob, not one per chunk.
        val bigBlob = BlobHash("h".repeat(40))
        val query = deterministicVector("the query text", DIMS)
        // chunk 1 happens to be a closer match to the query than chunk 0.
        store.upsert(bigBlob, MODEL, deterministicVector("chunk zero content, unrelated", DIMS), chunkIndex = 0)
        store.upsert(bigBlob, MODEL, query, chunkIndex = 1) // identical to the query -> highest possible score
        store.upsert(BlobHash("other".padEnd(40, '0')), MODEL, deterministicVector("something else", DIMS), chunkIndex = null)

        val hits = store.search(MODEL, query, topK = 10)

        val bigBlobHits = hits.filter { it.blobHash == bigBlob }
        assertEquals(1, bigBlobHits.size, "the blob's two chunks must collapse into a single hit")
        assertTrue(bigBlobHits.single().score > 0.99, "the hit should carry chunk 1's (the better match's) score")
    }

    @Test
    fun `chunk-fallback records for the same blob are independently addressable and idempotent`() = runTest {
        val hash = BlobHash("i".repeat(40))
        store.upsert(hash, MODEL, deterministicVector("chunk 0", DIMS), chunkIndex = 0)
        store.upsert(hash, MODEL, deterministicVector("chunk 1", DIMS), chunkIndex = 1)
        store.upsert(hash, MODEL, deterministicVector("chunk 0 -- retried, must not duplicate", DIMS), chunkIndex = 0)

        assertTrue(store.isIndexed(hash, MODEL))
        // Both chunks plus the blob overall should still count as ONE indexed blob.
        assertEquals(1L, store.countForModel(MODEL))
    }

    @Test
    fun `a blob can have a whole-file vector OR chunk vectors without conflating the two`() = runTest {
        val whole = BlobHash("j".repeat(40))
        store.upsert(whole, MODEL, deterministicVector("whole file content", DIMS), chunkIndex = null)

        val chunked = BlobHash("k".repeat(40))
        store.upsert(chunked, MODEL, deterministicVector("chunk a", DIMS), chunkIndex = 0)
        store.upsert(chunked, MODEL, deterministicVector("chunk b", DIMS), chunkIndex = 1)

        assertEquals(2L, store.countForModel(MODEL), "one whole-file blob + one chunked blob = 2 distinct blobs, regardless of row count")
    }

    @Test
    fun `search memory use is bounded by topK, not by the number of stored vectors`() = runTest {
        // Deliverable: "a memory ceiling test on a synthetic large repo"
        // (docs/design/kotlin-port.md). This is necessarily a coarse smoke
        // test -- JVM heap measurements are noisy (GC timing, JIT, test
        // harness overhead) -- but it exercises the actual property that
        // matters: searching several thousand stored vectors must not pull
        // them all into memory at once (kotlin-port.md §9's whole point).
        // If FlatFileVectorStore regressed to loading every candidate into a
        // List<FloatArray> before scoring (the exact gitsema-TS pattern this
        // redesign replaces), this test's bound would fail.
        val recordCount = 5_000
        val dims = 256
        repeat(recordCount) { i ->
            store.upsert(BlobHash(i.toString().padStart(40, '0')), MODEL, deterministicVector("synthetic content $i", dims))
        }
        assertEquals(recordCount.toLong(), store.countForModel(MODEL))

        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(50)
        val before = runtime.totalMemory() - runtime.freeMemory()

        val hits = store.search(MODEL, deterministicVector("synthetic content 42", dims), topK = 10)

        val after = runtime.totalMemory() - runtime.freeMemory()
        val deltaBytes = after - before

        assertEquals(10, hits.size)
        // Loading all 5,000 candidates as Float32 vectors (the pattern being
        // replaced) would be roughly 5,000 * 256 * 4 bytes =~ 5MB, before
        // typical JVM object/array overhead pushes it several times higher.
        // A bounded top-10 heap should cost a tiny fraction of that. 8MB
        // leaves generous headroom for GC noise and JIT/harness overhead
        // while still meaningfully failing if the whole pool got retained.
        assertTrue(deltaBytes < 8 * 1024 * 1024, "search() retained ${deltaBytes / 1024}KB above baseline, expected a bounded (topK-sized) footprint")
    }
}
