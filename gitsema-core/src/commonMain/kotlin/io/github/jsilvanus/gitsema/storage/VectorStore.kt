package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.model.BlobHash

/** A vector search hit. Higher [score] (cosine similarity) is a better match. */
data class VectorHit(val blobHash: BlobHash, val score: Double)

/**
 * The redesigned vector store from kotlin-port.md §9 — the single most
 * important departure from gitsema-TS in this whole port. gitsema-TS's
 * `vectorSearch.ts` loads a capped-but-still-large candidate pool fully into
 * process memory before scoring (§4.1/§9.1); this interface's contract is the
 * opposite by construction: [search] must never hold more than [topK]
 * scored candidates in memory at once, regardless of how many vectors are
 * stored. See [io.github.jsilvanus.gitsema.storage.FlatFileVectorStore] (in
 * gitsema-core's jvmAndroidMain source set) for how — memory-mapped flat
 * file, int8 quantization by default, streamed scoring into a bounded min-heap.
 */
interface VectorStore {
    suspend fun isIndexed(blobHash: BlobHash, model: String): Boolean

    /** Which of [blobHashes] do NOT yet have a vector under [model] — the indexer's dedup check (kotlin-port.md §1.1). */
    suspend fun filterNewBlobs(blobHashes: List<BlobHash>, model: String): Set<BlobHash>

    /**
     * Stores [vector] for [blobHash] under [model], quantizing it.
     *
     * [chunkIndex] is null for the normal, whole-file path (Tier 1's default
     * `FileChunker`). A non-null index is a context-limit fallback sub-chunk
     * (kotlin-port.md §2.4): when a blob can't be embedded whole, the indexer
     * re-chunks it and stores one record per surviving sub-chunk instead —
     * several rows sharing [blobHash] rather than one. [search] deduplicates
     * these back down to one hit per blob (the best-scoring chunk wins),
     * matching gitsema-TS's own "best score per blob" behavior.
     */
    suspend fun upsert(blobHash: BlobHash, model: String, vector: FloatArray, chunkIndex: Int? = null)

    /**
     * Top-[topK] cosine-similarity matches for [queryVector] against every
     * vector stored under [model], optionally restricted to [candidateFilter],
     * deduplicated to one [VectorHit] per blob (its best-scoring chunk, if it
     * has more than one). Memory use during a call is O(topK), never
     * O(stored vector count) — see [io.github.jsilvanus.gitsema.storage.FlatFileVectorStore]'s
     * doc comment for how dedup is kept within that same bound.
     */
    suspend fun search(model: String, queryVector: FloatArray, topK: Int, candidateFilter: Set<BlobHash>? = null): List<VectorHit>

    /** Distinct blobs with at least one vector under [model] — not a raw row count (a blob may have several chunk-fallback rows). */
    suspend fun countForModel(model: String): Long
}
