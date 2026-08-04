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

    /** Stores [vector] for [blobHash] under [model], quantizing it. A no-op if already indexed (content-addressed idempotency). */
    suspend fun upsert(blobHash: BlobHash, model: String, vector: FloatArray)

    /**
     * Top-[topK] cosine-similarity matches for [queryVector] against every
     * vector stored under [model], optionally restricted to [candidateFilter].
     * Memory use during a call is O(topK), never O(stored vector count).
     */
    suspend fun search(model: String, queryVector: FloatArray, topK: Int, candidateFilter: Set<BlobHash>? = null): List<VectorHit>

    suspend fun countForModel(model: String): Long
}
