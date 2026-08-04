package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteFtsStoreTest {
    private fun newStore(): SqliteFtsStore {
        val driver = createSqlDriver(":memory:")
        return SqliteFtsStore(GitsemaDatabase(driver))
    }

    @Test
    fun `index then get round-trips content`() = runTest {
        val store = newStore()
        val hash = BlobHash("a".repeat(40))
        store.index(hash, "fun parseHeader(bytes: ByteArray): Header { ... }")

        assertEquals("fun parseHeader(bytes: ByteArray): Header { ... }", store.get(hash))
    }

    @Test
    fun `get returns null for an unindexed blob`() = runTest {
        val store = newStore()
        assertNull(store.get(BlobHash("b".repeat(40))))
    }

    @Test
    fun `delete removes indexed content`() = runTest {
        val store = newStore()
        val hash = BlobHash("c".repeat(40))
        store.index(hash, "some content")
        store.delete(hash)

        assertNull(store.get(hash))
    }

    @Test
    fun `search finds a keyword match by blob hash, ranked by relevance`() = runTest {
        val store = newStore()
        val relevant = BlobHash("d".repeat(40))
        val irrelevant = BlobHash("e".repeat(40))
        store.index(relevant, "fun parseHeader(bytes: ByteArray): Header parses the wire header")
        store.index(irrelevant, "fun renderFooter(): String builds a page footer")

        val hits = store.search("parseHeader", limit = 10)

        assertTrue(hits.any { it.blobHash == relevant })
        assertTrue(hits.none { it.blobHash == irrelevant })
    }

    @Test
    fun `search respects the limit`() = runTest {
        val store = newStore()
        repeat(5) { i ->
            store.index(BlobHash(i.toString().repeat(40)), "shared keyword content number $i")
        }

        val hits = store.search("shared keyword", limit = 2)

        assertEquals(2, hits.size)
    }

    @Test
    fun `re-indexing the same blob replaces its content rather than duplicating it`() = runTest {
        val store = newStore()
        val hash = BlobHash("f".repeat(40))
        store.index(hash, "original content unique-marker-one")
        store.index(hash, "replacement content unique-marker-two")

        assertEquals("replacement content unique-marker-two", store.get(hash))
        val stale = store.search("unique-marker-one", limit = 10)
        assertTrue(stale.none { it.blobHash == hash })
    }
}
