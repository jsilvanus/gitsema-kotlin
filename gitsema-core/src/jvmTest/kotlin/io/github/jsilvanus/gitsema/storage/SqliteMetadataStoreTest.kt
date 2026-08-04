package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteMetadataStoreTest {
    private fun newStore(): SqliteMetadataStore {
        val driver = createSqlDriver(":memory:")
        return SqliteMetadataStore(GitsemaDatabase(driver))
    }

    @Test
    fun `putBlob then blobExists and blobCount reflect it`() = runTest {
        val store = newStore()
        val hash = BlobHash("a".repeat(40))

        assertFalse(store.blobExists(hash))
        store.putBlob(hash, size = 123, indexedAtEpochSeconds = 1000)

        assertTrue(store.blobExists(hash))
        assertEquals(1L, store.blobCount())
    }

    @Test
    fun `putBlob is idempotent -- calling it twice does not duplicate or error`() = runTest {
        val store = newStore()
        val hash = BlobHash("b".repeat(40))

        store.putBlob(hash, size = 10, indexedAtEpochSeconds = 1000)
        store.putBlob(hash, size = 10, indexedAtEpochSeconds = 1000) // must not throw

        assertEquals(1L, store.blobCount())
    }

    @Test
    fun `the same blob can be registered under multiple paths`() = runTest {
        val store = newStore()
        val hash = BlobHash("c".repeat(40))
        store.putBlob(hash, size = 5, indexedAtEpochSeconds = 1000)

        store.addPath(hash, RepoPath("a.txt"))
        store.addPath(hash, RepoPath("b.txt"))
        store.addPath(hash, RepoPath("a.txt")) // duplicate add -- must not duplicate the row

        val paths = store.pathsFor(hash).map { it.value }.toSet()
        assertEquals(setOf("a.txt", "b.txt"), paths)
    }

    @Test
    fun `commit and blob-commit linking round-trips, and first-seen is the earliest timestamp`() = runTest {
        val store = newStore()
        val hash = BlobHash("d".repeat(40))
        store.putBlob(hash, size = 1, indexedAtEpochSeconds = 1000)

        val early = CommitHash("1".repeat(40))
        val late = CommitHash("2".repeat(40))
        store.putCommit(CommitMeta(early, timestampEpochSeconds = 500, authorName = "A", authorEmail = "a@example.com", message = "first"))
        store.putCommit(CommitMeta(late, timestampEpochSeconds = 900, authorName = "A", authorEmail = "a@example.com", message = "second"))
        store.linkBlobCommit(hash, early)
        store.linkBlobCommit(hash, late)

        assertEquals(500L, store.firstSeenFor(hash))
    }

    @Test
    fun `firstSeenFor returns null when the blob has never been committed`() = runTest {
        val store = newStore()
        assertNull(store.firstSeenFor(BlobHash("e".repeat(40))))
    }

    @Test
    fun `markCommitIndexed and isCommitIndexed round-trip`() = runTest {
        val store = newStore()
        val hash = CommitHash("f".repeat(40))

        assertFalse(store.isCommitIndexed(hash))
        store.markCommitIndexed(hash, indexedAtEpochSeconds = 1234)
        assertTrue(store.isCommitIndexed(hash))
    }

    @Test
    fun `embed config upsert refreshes fields on repeat write`() = runTest {
        val store = newStore()
        val config = EmbedConfigMeta(model = "fake-model", provider = "fake", dimensions = 8, chunker = "file", lastUsedAtEpochSeconds = 1)
        store.upsertEmbedConfig(config)
        store.upsertEmbedConfig(config.copy(lastUsedAtEpochSeconds = 999))

        val stored = store.embedConfigFor("fake-model")
        assertEquals(999L, stored?.lastUsedAtEpochSeconds)
        assertEquals(8, stored?.dimensions)
    }

    @Test
    fun `embedConfigFor returns null for an unknown model`() = runTest {
        val store = newStore()
        assertNull(store.embedConfigFor("unknown-model"))
    }
}
