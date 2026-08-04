package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [FtsStore] backed by SQLite's FTS5 virtual table (kotlin-port.md §4.3). */
class SqliteFtsStore(private val database: GitsemaDatabase) : FtsStore {
    private val fts = database.ftsQueries

    override suspend fun index(blobHash: BlobHash, content: String) = withContext(Dispatchers.IO) {
        // FTS5 has no natural primary key / upsert -- delete-then-insert is
        // the standard idiom, and is still safe under gitsema's dedup
        // discipline since this is only ever called once per blob hash.
        fts.deleteFtsContent(blobHash.value)
        fts.insertFtsContent(blobHash.value, content)
    }

    override suspend fun get(blobHash: BlobHash): String? = withContext(Dispatchers.IO) {
        fts.ftsContentFor(blobHash.value).executeAsOneOrNull()?.content
    }

    override suspend fun delete(blobHash: BlobHash) = withContext(Dispatchers.IO) {
        fts.deleteFtsContent(blobHash.value)
    }

    override suspend fun search(query: String, limit: Int): List<FtsHit> = withContext(Dispatchers.IO) {
        // FTS5's virtual-table columns can't carry a NOT NULL constraint, so
        // SQLDelight infers them nullable even though `index()` never writes
        // a null blob_hash/score -- requireNotNull documents that invariant
        // rather than silently `!!`-ing it.
        fts.searchFts(sanitizeFtsQuery(query), limit.toLong()).executeAsList().map {
            FtsHit(BlobHash(requireNotNull(it.blob_hash)), requireNotNull(it.score))
        }
    }
}

/**
 * Quotes every whitespace-split token before it reaches FTS5's MATCH parser
 * (kotlin-port.md §4.3) -- FTS5's query grammar treats bare `-`, `:`, `*`,
 * and other characters as operators (a raw query like "unique-marker-one"
 * fails with "no such column: marker", not a hit-count of zero), so an
 * un-sanitized natural-language or code-token query is a real correctness
 * bug, not just a style nit. Consecutive quoted phrase terms are FTS5's
 * implicit AND, matching gitsema-TS's `sanitizeFtsQuery()` exactly.
 */
internal fun sanitizeFtsQuery(query: String): String =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ") { token -> "\"" + token.replace("\"", "\"\"") + "\"" }
