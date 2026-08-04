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

    private fun indexerForRepo(repository: FakeGitRepository, provider: FakeEmbeddingProvider = FakeEmbeddingProvider()): Indexer =
        Indexer(
            repository = repository,
            provider = provider,
            metadataStore = metadataStore,
            vectorStore = vectorStore,
            ftsStore = ftsStore,
            chunker = FileChunker(),
        )

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

    @Test
    fun `commit-mapping populates firstSeenFor, activating recency for real`() = runTest {
        val commits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c2".padEnd(40, '0'), timestampEpochSeconds = 200, files = mapOf("b.txt" to "beta")),
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c1".padEnd(40, '0'), timestampEpochSeconds = 100, files = mapOf("a.txt" to "alpha")),
        )
        val files = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        val indexer = indexerForRepo(FakeGitRepository(files, commits))

        val result = indexer.index("HEAD")

        assertEquals(2, result.commitsProcessed)
        assertEquals(100L, metadataStore.firstSeenFor(io.github.jsilvanus.gitsema.testutil.fakeBlobHash("alpha")))
        assertEquals(200L, metadataStore.firstSeenFor(io.github.jsilvanus.gitsema.testutil.fakeBlobHash("beta")))
    }

    @Test
    fun `resume cursor is set after a full run and used automatically on the next one`() = runTest {
        val roundOneCommits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c2".padEnd(40, '0'), timestampEpochSeconds = 200, files = mapOf("b.txt" to "beta")),
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c1".padEnd(40, '0'), timestampEpochSeconds = 100, files = mapOf("a.txt" to "alpha")),
        )
        val roundOneFiles = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        val firstResult = indexerForRepo(FakeGitRepository(roundOneFiles, roundOneCommits)).index("HEAD")
        assertEquals(2, firstResult.commitsProcessed)
        assertEquals(io.github.jsilvanus.gitsema.model.CommitHash("c2".padEnd(40, '0')), metadataStore.getResumeCursor("HEAD"))

        // History grew by one commit -- a real second run against the same
        // ref should only walk the new one, not re-walk c1/c2, because
        // `since` defaults to the stored resume cursor when not given.
        val roundTwoCommits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c3".padEnd(40, '0'), timestampEpochSeconds = 300, files = mapOf("c.txt" to "gamma")),
        ) + roundOneCommits
        val roundTwoFiles = roundOneFiles + ("c.txt" to "gamma")
        val secondResult = indexerForRepo(FakeGitRepository(roundTwoFiles, roundTwoCommits)).index("HEAD")

        assertEquals(1, secondResult.commitsProcessed, "only the new commit should be walked, not the two already-processed ones")
        assertEquals(io.github.jsilvanus.gitsema.model.CommitHash("c3".padEnd(40, '0')), metadataStore.getResumeCursor("HEAD"))
        // The new blob should still have been indexed correctly despite the narrower commit walk.
        assertEquals(1, secondResult.blobsIndexed)
    }

    @Test
    fun `an explicit since parameter overrides the stored resume cursor`() = runTest {
        val commits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c2".padEnd(40, '0'), timestampEpochSeconds = 200, files = mapOf("b.txt" to "beta")),
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c1".padEnd(40, '0'), timestampEpochSeconds = 100, files = mapOf("a.txt" to "alpha")),
        )
        val files = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        metadataStore.setResumeCursor("HEAD", io.github.jsilvanus.gitsema.model.CommitHash("c2".padEnd(40, '0'))) // pretend a previous run already got this far

        val result = indexerForRepo(FakeGitRepository(files, commits))
            .index("HEAD", since = io.github.jsilvanus.gitsema.model.CommitHash("c1".padEnd(40, '0'))) // explicitly force re-walking from further back

        assertEquals(1, result.commitsProcessed, "explicit since=c1 should only exclude c1 itself, leaving c2 to walk")
    }

    @Test
    fun `an already-embedded blob resurfacing at a new path gets that path registered`() = runTest {
        // Round 1: index "shared content" only under a.txt.
        val roundOneCommits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c1".padEnd(40, '0'), timestampEpochSeconds = 100, files = mapOf("a.txt" to "shared content")),
        )
        indexerForRepo(FakeGitRepository(mapOf("a.txt" to "shared content"), roundOneCommits)).index("HEAD")
        val hash = io.github.jsilvanus.gitsema.testutil.fakeBlobHash("shared content")
        assertEquals(listOf("a.txt"), metadataStore.pathsFor(hash).map { it.value })

        // Round 2: a NEW commit reuses the exact same content under a NEW
        // path (b.txt) -- the blob is already embedded (content-addressed,
        // same hash), so it won't go through the embedding loop again, but
        // its new path must still be registered via the commit-diff loop.
        val roundTwoCommits = listOf(
            io.github.jsilvanus.gitsema.testutil.FakeCommit(hash = "c2".padEnd(40, '0'), timestampEpochSeconds = 200, files = mapOf("b.txt" to "shared content")),
        ) + roundOneCommits
        val secondResult = indexerForRepo(FakeGitRepository(mapOf("a.txt" to "shared content", "b.txt" to "shared content"), roundTwoCommits))
            .index("HEAD")

        assertEquals(0, secondResult.blobsIndexed, "the blob content is unchanged -- no new embedding work")
        assertEquals(setOf("a.txt", "b.txt"), metadataStore.pathsFor(hash).map { it.value }.toSet())
    }

    // Uniform short lines so FixedChunker's line-boundary-snapped chunk
    // sizes stay close to the requested window size, making the fallback
    // chain's behavior predictable to test against.
    private fun uniformLineContent(lineCount: Int, lineLength: Int = 24): String =
        (1..lineCount).joinToString("\n") { "x".repeat(lineLength) }

    @Test
    fun `a blob too large to embed whole falls back to 1500-char chunks and still gets indexed`() = runTest {
        val bigContent = uniformLineContent(lineCount = 100) // ~2500 chars, well over any of the thresholds below
        // Whole file (~2500 chars) exceeds this; each ~1500-char fallback
        // chunk (~60 lines * 25) does not.
        val provider = FakeEmbeddingProvider(maxTextLength = 1700)
        val indexer = indexerForRepo(FakeGitRepository(mapOf("big.txt" to bigContent)), provider)

        val result = indexer.index("HEAD")

        assertEquals(1, result.blobsIndexed, "the blob should end up indexed via the fallback chain, not failed")
        assertEquals(0, result.blobsFailed)
        val hash = io.github.jsilvanus.gitsema.testutil.fakeBlobHash(bigContent)
        assertTrue(vectorStore.isIndexed(hash, provider.modelId))
        // FTS content is still stored once for the whole blob, regardless of
        // how many vector chunks it took to embed it.
        assertEquals(bigContent, ftsStore.get(hash))
    }

    @Test
    fun `a blob too large even for 1500-char chunks falls back further to 800-char chunks`() = runTest {
        val bigContent = uniformLineContent(lineCount = 100) // ~2500 chars
        // Whole file and 1500-char chunks (~60 lines * 25 =~ 1499 chars) both
        // exceed this; 800-char chunks (~32 lines * 25 =~ 799 chars) don't.
        val provider = FakeEmbeddingProvider(maxTextLength = 1000)
        val indexer = indexerForRepo(FakeGitRepository(mapOf("big.txt" to bigContent)), provider)

        val result = indexer.index("HEAD")

        assertEquals(1, result.blobsIndexed)
        assertEquals(0, result.blobsFailed)
        val hash = io.github.jsilvanus.gitsema.testutil.fakeBlobHash(bigContent)
        assertTrue(vectorStore.isIndexed(hash, provider.modelId))
    }

    @Test
    fun `a blob too large for every fallback size is counted failed, not silently dropped`() = runTest {
        val bigContent = uniformLineContent(lineCount = 100)
        // Even 800-char chunks (~799 chars) exceed this -- nothing succeeds.
        val provider = FakeEmbeddingProvider(maxTextLength = 200)
        val indexer = indexerForRepo(FakeGitRepository(mapOf("big.txt" to bigContent)), provider)

        val result = indexer.index("HEAD")

        assertEquals(0, result.blobsIndexed)
        assertEquals(1, result.blobsFailed)
        val hash = io.github.jsilvanus.gitsema.testutil.fakeBlobHash(bigContent)
        assertTrue(!vectorStore.isIndexed(hash, provider.modelId))
    }

    @Test
    fun `a partial success at 1500 is discarded, not kept alongside the 800 retry`() = runTest {
        // This test only asserts the observable outcome (indexed via
        // fallback, exactly one blob's worth of coverage) -- the "discard
        // partial 1500 results" behavior itself is implementation-internal,
        // but a regression that kept BOTH 1500 and 800 partial results would
        // show up as extra, inconsistent vector_entry rows and is the kind
        // of bug worth a dedicated end-to-end check rather than trusting the
        // code path was exercised correctly by the tests above alone.
        val bigContent = uniformLineContent(lineCount = 100)
        val provider = FakeEmbeddingProvider(maxTextLength = 1000)
        val indexer = indexerForRepo(FakeGitRepository(mapOf("big.txt" to bigContent)), provider)

        indexer.index("HEAD")

        val hash = io.github.jsilvanus.gitsema.testutil.fakeBlobHash(bigContent)
        val queryVector = provider.embed(bigContent.lines().take(30).joinToString("\n"))
        val hits = vectorStore.search(provider.modelId, queryVector, topK = 5)
        assertEquals(1, hits.count { it.blobHash == hash }, "the blob must appear exactly once in results despite having multiple chunk records")
    }

    @Test
    fun `every blob visited while indexing a ref is recorded as seen on that ref`() = runTest {
        val files = mapOf("a.txt" to "alpha", "b.txt" to "beta")
        val indexer = indexerFor(files).first

        indexer.index("main")

        assertEquals(setOf(io.github.jsilvanus.gitsema.testutil.fakeBlobHash("alpha"), io.github.jsilvanus.gitsema.testutil.fakeBlobHash("beta")), metadataStore.blobHashesOnBranch("main"))
        assertTrue(metadataStore.blobHashesOnBranch("some-other-branch-never-indexed").isEmpty())
    }
}
