package io.github.jsilvanus.gitsema

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.storage.FlatFileVectorStore
import io.github.jsilvanus.gitsema.storage.SqliteFtsStore
import io.github.jsilvanus.gitsema.storage.SqliteMetadataStore
import io.github.jsilvanus.gitsema.storage.createSqlDriver
import io.github.jsilvanus.gitsema.testutil.FakeEmbeddingProvider
import io.github.jsilvanus.gitsema.testutil.FakeGitRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the public [SemanticIndex] facade: index a fake
 * repo, then search it, against real (SQLite + flat-file) storage.
 */
class SemanticIndexTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("gitsema-semantic-index-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sharedDatabase(): GitsemaDatabase =
        GitsemaDatabase(createSqlDriver(File(tempDir, "index.db").absolutePath))

    private fun indexOn(database: GitsemaDatabase, files: Map<String, String>, provider: FakeEmbeddingProvider = FakeEmbeddingProvider()): SemanticIndex =
        GitsemaSemanticIndex(
            repository = FakeGitRepository(files),
            provider = provider,
            metadataStore = SqliteMetadataStore(database),
            vectorStore = FlatFileVectorStore(database, File(tempDir, "vectors")),
            ftsStore = SqliteFtsStore(database),
        )

    private fun buildIndex(files: Map<String, String>, provider: FakeEmbeddingProvider = FakeEmbeddingProvider()): SemanticIndex =
        indexOn(sharedDatabase(), files, provider)

    @Test
    fun `search before indexing returns nothing, not an error`() = runTest {
        val index = buildIndex(emptyMap())

        val results = index.search(Query("anything", topK = 5))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search on a partial index (FTS only, no vectors yet) returns degraded results rather than blocking`() = runTest {
        // Deliverable (docs/design/kotlin-port.md): "search on a partial
        // index returns degraded results rather than blocking." Simulate
        // "partial" by indexing FTS content directly, bypassing the
        // embedding/vector step entirely -- exactly the state a real index
        // is in while embeddings are still catching up (constraint 6: FTS
        // works before any embedding model exists).
        val files = mapOf("auth.txt" to "authentication middleware handles login requests")
        val database = sharedDatabase()
        val index = indexOn(database, files)
        // Index normally first so the FTS store has real content...
        index.index("HEAD")
        // ...then verify the degraded path specifically by asking a second
        // SemanticIndex sharing the SAME underlying storage, but with a
        // *different* embedding model -- i.e. a provider whose vectors
        // haven't been built against this data yet, exactly the state a real
        // index is in while embeddings are still catching up.
        val partialIndex = indexOn(database, files, FakeEmbeddingProvider(modelId = "a-different-not-yet-indexed-model"))

        val results = partialIndex.search(Query("authentication middleware", topK = 5))

        assertTrue(results.isNotEmpty(), "FTS content exists even though this model has no vectors yet -- search must not return nothing or block")
        assertTrue(results.all { it.degraded }, "every result must be explicitly marked degraded, not silently treated as a full match")
        assertTrue(results.all { it.provenance == MatchProvenance.FTS })
    }

    @Test
    fun `search after full indexing returns non-degraded hybrid results`() = runTest {
        val index = buildIndex(
            mapOf(
                "auth.txt" to "authentication middleware handles login requests",
                "unrelated.txt" to "a completely different topic about rendering graphics",
            ),
        )
        index.index("HEAD")

        val results = index.search(Query("authentication middleware", topK = 5))

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { !it.degraded })
        assertTrue(results.all { it.provenance == MatchProvenance.HYBRID })
        assertEquals("auth.txt", results.first().paths.first().value)
    }

    @Test
    fun `status reflects index coverage before and after indexing`() = runTest {
        val index = buildIndex(mapOf("a.txt" to "alpha", "b.txt" to "beta"))

        val before = index.status()
        assertEquals(0L, before.blobCount)
        assertEquals(0L, before.embeddedBlobCount)

        index.index("HEAD")

        val after = index.status()
        assertEquals(2L, after.blobCount)
        assertEquals(2L, after.embeddedBlobCount)
        assertEquals("fake-test-model", after.embeddingModel)
    }

    @Test
    fun `status reports lastIndexedCommit only after index() has actually run`() = runTest {
        val index = buildIndex(mapOf("a.txt" to "alpha"))

        assertEquals(null, index.status().lastIndexedCommit)

        index.index("HEAD")

        assertEquals("fakehead".padEnd(40, '0'), index.status().lastIndexedCommit?.value)
    }

    @Test
    fun `indexing twice then searching still finds results (idempotent index feeding a working search)`() = runTest {
        val index = buildIndex(mapOf("a.txt" to "unique searchable content about widgets"))
        index.index("HEAD")
        index.index("HEAD") // must not duplicate or corrupt anything

        val results = index.search(Query("widgets", topK = 5))

        assertEquals(1, results.size)
    }

    @Test
    fun `status reports lastIndexedCommit from durable storage, surviving a fresh instance sharing the same database`() = runTest {
        // PR #1 review finding #3: a process-local "last indexed ref" field
        // under-reports after process death, which on Android (Aidos's
        // target) is routine rather than exceptional. Simulate that by
        // building a brand-new GitsemaSemanticIndex against the SAME
        // underlying storage -- it has never called index() itself, so if
        // status() relied on any in-process state, this would come back
        // null despite the durable resume cursor being right there.
        val database = sharedDatabase()
        val files = mapOf("a.txt" to "alpha")
        val index = indexOn(database, files)
        index.index("HEAD")

        val freshIndexOnSameStorage = indexOn(database, files)

        assertEquals("fakehead".padEnd(40, '0'), freshIndexOnSameStorage.status().lastIndexedCommit?.value)
    }

    @Test
    fun `branch filtering restricts hybrid search results to blobs indexed under that ref`() = runTest {
        val index = buildIndex(mapOf("a.txt" to "unique searchable content about widgets"))
        index.index("main")

        val onMain = index.search(Query("widgets", topK = 5, branch = "main"))
        val onOther = index.search(Query("widgets", topK = 5, branch = "never-indexed-branch"))
        val unfiltered = index.search(Query("widgets", topK = 5))

        assertEquals(1, onMain.size, "the blob was indexed under 'main', so branch=main should find it")
        assertTrue(onOther.isEmpty(), "no blob was ever indexed under this branch name")
        assertEquals(1, unfiltered.size, "no branch filter should behave exactly as before")
    }

    @Test
    fun `branch filtering also restricts the degraded FTS-only search path`() = runTest {
        val files = mapOf("auth.txt" to "authentication middleware handles login requests")
        val database = sharedDatabase()
        val index = indexOn(database, files)
        index.index("main")
        // Same "different, not-yet-vectored model" trick as the degraded-search test above.
        val partialIndex = indexOn(database, files, FakeEmbeddingProvider(modelId = "a-different-not-yet-indexed-model"))

        val onMain = partialIndex.search(Query("authentication middleware", topK = 5, branch = "main"))
        val onOther = partialIndex.search(Query("authentication middleware", topK = 5, branch = "never-indexed-branch"))

        assertTrue(onMain.isNotEmpty())
        assertTrue(onMain.all { it.degraded })
        assertTrue(onOther.isEmpty())
    }
}
