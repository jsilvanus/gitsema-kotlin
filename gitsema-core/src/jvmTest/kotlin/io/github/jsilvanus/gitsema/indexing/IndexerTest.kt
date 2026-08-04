package io.github.jsilvanus.gitsema.indexing

import io.github.jsilvanus.gitsema.chunking.FileChunker
import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.embedding.embed
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

class IndexerTest {
    private lateinit var tempDir: File
    private lateinit var database: GitsemaDatabase
    private lateinit var metadataStore: SqliteMetadataStore
    private lateinit var vectorStore: FlatFileVectorStore
    private lateinit var ftsStore: SqliteFtsStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("gitsema-indexer-test").toFile()
        val driver = createSqlDriver(File(tempDir, "index.db").absolutePath)
        database = GitsemaDatabase(driver)
        metadataStore = SqliteMetadataStore(database)
        vectorStore = FlatFileVectorStore(database, File(tempDir, "vectors"))
        ftsStore = SqliteFtsStore(database)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun indexerFor(files: Map<String, String>, provider: FakeEmbeddingProvider = FakeEmbeddingProvider()): Pair<Indexer, FakeEmbeddingProvider> {
        val indexer = Indexer(
            repository = FakeGitRepository(files),
            provider = provider,
            metadataStore = metadataStore,
            vectorStore = vectorStore,
            ftsStore = ftsStore,
            chunker = FileChunker(),
        )
        return indexer to provider
    }

    @Test
    fun `indexes every distinct blob and reports accurate stats`() = runTest {
        val (indexer, _) = indexerFor(mapOf("a.txt" to "alpha content", "b.txt" to "beta content", "c.txt" to "gamma content"))

        val result = indexer.index("HEAD")

        assertEquals(3, result.blobsSeen)
        assertEquals(3, result.blobsIndexed)
        assertEquals(0, result.blobsSkipped)
        assertEquals(0, result.blobsFailed)
        assertEquals(3L, metadataStore.blobCount())
    }

    @Test
    fun `the same blob content indexed twice under different paths embeds once`() = runTest {
        val provider = FakeEmbeddingProvider()
        val (indexer, _) = indexerFor(
            mapOf("a.txt" to "identical content", "b.txt" to "identical content", "c.txt" to "different content"),
            provider,
        )

        val result = indexer.index("HEAD")

        // Two paths share one blob hash (content-addressed) -- only 2 distinct
        // blobs exist, so only 2 embed calls should have happened, and the
        // stored blob count should be 2, not 3.
        assertEquals(2, result.blobsIndexed)
        assertEquals(2L, metadataStore.blobCount())
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `re-indexing an already-fully-indexed repo does not re-embed anything`() = runTest {
        val files = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        val provider = FakeEmbeddingProvider()
        val (indexer, _) = indexerFor(files, provider)
        indexer.index("HEAD")
        val callCountAfterFirstRun = provider.callCount

        val second = indexer.index("HEAD")

        assertEquals(0, second.blobsIndexed)
        assertEquals(2, second.blobsSkipped)
        assertEquals(callCountAfterFirstRun, provider.callCount, "no new embedding calls should have happened on the second run")
    }

    @Test
    fun `resuming after a partial run only indexes what's still missing, never re-embedding completed work`() = runTest {
        // Simulates "interrupted, then resumed": index a smaller file set
        // first (standing in for a run that got this far before being
        // killed), then index the FULL file set with a fresh Indexer/provider
        // pointed at the SAME stores -- the already-completed blobs must not
        // be re-embedded, and the new ones must be picked up correctly. This
        // is the actual guarantee blob-atomic writes give (kotlin-port.md
        // §7.2/§8.2): the smallest unit of work that need not be redone is
        // one blob.
        val partial = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        val (firstIndexer, firstProvider) = indexerFor(partial)
        firstIndexer.index("HEAD")
        assertEquals(2, firstProvider.callCount)

        val full = mapOf("a.txt" to "alpha", "b.txt" to "beta", "c.txt" to "gamma", "d.txt" to "delta")
        val (secondIndexer, secondProvider) = indexerFor(full)
        val result = secondIndexer.index("HEAD")

        assertEquals(4, result.blobsSeen)
        assertEquals(2, result.blobsIndexed, "only the 2 new blobs should be embedded this run")
        assertEquals(2, result.blobsSkipped, "the 2 already-indexed blobs should be skipped, not re-embedded")
        assertEquals(2, secondProvider.callCount, "the second indexer's provider should only have been called for the new blobs")
        assertEquals(4L, metadataStore.blobCount(), "all 4 blobs should be present in the end")
        assertEquals(4L, vectorStore.countForModel(secondProvider.modelId))
    }

    @Test
    fun `an oversized blob is skipped and counted, not indexed or crashed on`() = runTest {
        val huge = "x".repeat(1000)
        val indexerWithCap = Indexer(
            repository = FakeGitRepository(mapOf("small.txt" to "tiny", "huge.txt" to huge)),
            provider = FakeEmbeddingProvider(),
            metadataStore = metadataStore,
            vectorStore = vectorStore,
            ftsStore = ftsStore,
            chunker = FileChunker(),
            maxBlobBytes = 100,
        )

        val result = indexerWithCap.index("HEAD")

        assertEquals(1, result.blobsIndexed)
        assertEquals(1, result.blobsOversized)
    }

    @Test
    fun `a failing embedding provider counts the failure without crashing the run`() = runTest {
        val provider = FakeEmbeddingProvider(maxTextLength = 5)
        val (indexer, _) = indexerFor(mapOf("ok.txt" to "hi", "bad.txt" to "this text is too long"), provider)

        val result = indexer.index("HEAD")

        assertEquals(1, result.blobsIndexed)
        assertEquals(1, result.blobsFailed)
    }

    @Test
    fun `indexed content is searchable via FTS and vector store after indexing`() = runTest {
        val (indexer, provider) = indexerFor(mapOf("auth.txt" to "authentication middleware for the request pipeline"))
        indexer.index("HEAD")

        val ftsHits = ftsStore.search("authentication middleware", limit = 10)
        assertTrue(ftsHits.isNotEmpty())

        val queryVector = provider.embed("authentication middleware for the request pipeline")
        val vectorHits = vectorStore.search(provider.modelId, queryVector, topK = 5)
        assertTrue(vectorHits.isNotEmpty())
    }

    @Test
    fun `progress callback reports increasing blobsIndexed as batches complete`() = runTest {
        val files = (1..5).associate { "f$it.txt" to "content number $it" }
        val (indexer, _) = indexerFor(files)
        val progressReports = mutableListOf<io.github.jsilvanus.gitsema.model.IndexProgress>()

        indexer.index("HEAD", onProgress = { progressReports.add(it) })

        assertTrue(progressReports.isNotEmpty())
        assertEquals(5, progressReports.last().blobsIndexed)
    }
}
