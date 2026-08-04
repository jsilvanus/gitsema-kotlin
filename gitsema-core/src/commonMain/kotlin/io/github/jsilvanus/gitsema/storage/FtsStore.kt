package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.model.BlobHash

/** A keyword-search hit. Lower [bm25Score] is a better match (SQLite's raw convention) -- see kotlin-port.md §4.3. */
data class FtsHit(val blobHash: BlobHash, val bm25Score: Double)

/**
 * FTS5 keyword search (kotlin-port.md §4.3, constraint 6). This is the search
 * baseline: it works before any [io.github.jsilvanus.gitsema.embedding.EmbeddingProvider]
 * has produced a single vector, which is why constraint 6 treats it as the
 * thing that must never depend on indexing having finished.
 */
interface FtsStore {
    suspend fun index(blobHash: BlobHash, content: String)
    suspend fun get(blobHash: BlobHash): String?
    suspend fun delete(blobHash: BlobHash)

    /** Raw FTS5 MATCH query (already sanitized/quoted by the caller) against up to [limit] hits, ranked by BM25. */
    suspend fun search(query: String, limit: Int): List<FtsHit>
}
