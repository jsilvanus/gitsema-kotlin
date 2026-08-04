package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.embedding.cosineSimilarityQuantized
import io.github.jsilvanus.gitsema.embedding.quantizeVector
import io.github.jsilvanus.gitsema.model.BlobHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue

/** Sentinel stored in SQLite for "this is the whole-file vector, not a fallback chunk" (kotlin-port.md §2.4). */
private const val WHOLE_FILE_CHUNK_INDEX = -1L

/**
 * [VectorStore] per kotlin-port.md §9.2: one flat, append-only, fixed-record
 * file PER MODEL (so record length is a compile-time-known constant, `8 +
 * dims` bytes — 4-byte min + 4-byte scale + `dims` int8 bytes), memory-mapped
 * for reads, with a tiny SQLite table (`vector_entry`, kotlin-port.md §6.4)
 * mapping `(blob_hash, model, chunk_index) -> file_offset`.
 *
 * Deliberately brute-force: [search] streams every stored vector for a model
 * through [cosineSimilarityQuantized] and keeps only a bounded top-K
 * structure — never materializes the candidate pool (§9.1's redesign
 * target). No ANN index sits in front of this. §9.2 point 4 is explicit that
 * this is the right place to start: quantized, a 50,000-blob model's vector
 * file is already only ~38MB, comfortably inside a 200MB ceiling even before
 * ANN is considered, and ANN should only be added once real on-device
 * profiling shows brute force is actually too slow — not speculatively here.
 *
 * A single [FileChannel.map] call caps a mapped region at ~2GB (`ByteBuffer`
 * is int-indexed) — comfortably far above Tier 1's realistic scale (a
 * 50,000-blob repo's file is tens of MB), but a repo with tens of millions of
 * blobs would need a multi-region mapping scheme this does not implement.
 * Not a Tier 1 concern; flagged here rather than silently assumed away.
 *
 * **Dedup within the O(topK) bound (§2.4's chunk-fallback storage):** most
 * blobs have exactly one record (`chunk_index = -1`), so a plain bounded
 * min-heap is enough on its own. A blob that fell back to chunk-level
 * embedding has several records sharing one `blob_hash`, and without dedup
 * those could occupy multiple heap slots for the same blob — crowding out
 * other, distinct blobs and breaking the "one hit per blob" contract
 * gitsema-TS's own chunk/symbol search has. [search] keeps a second
 * structure, `bestByBlob`, mapping each blob CURRENTLY represented in the
 * heap to its heap entry; both structures are trimmed together, so
 * `bestByBlob` never exceeds `topK` entries either — the O(topK) memory
 * guarantee holds for the general case (no chunk-fallback data at all) and
 * the multi-record case alike, not just the common one.
 */
class FlatFileVectorStore(
    private val database: GitsemaDatabase,
    private val vectorDir: File,
) : VectorStore {
    private val writeMutex = Mutex()

    private fun vectorFileFor(model: String): File {
        val safeName = model.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
        return File(vectorDir, "vectors-$safeName.bin")
    }

    override suspend fun isIndexed(blobHash: BlobHash, model: String): Boolean = withContext(Dispatchers.IO) {
        database.vectorIndexQueries.isVectorIndexed(blobHash.value, model).executeAsOne()
    }

    override suspend fun filterNewBlobs(blobHashes: List<BlobHash>, model: String): Set<BlobHash> = withContext(Dispatchers.IO) {
        if (blobHashes.isEmpty()) return@withContext emptySet()
        val alreadyIndexed = database.vectorIndexQueries
            .filterNewBlobs(model, blobHashes.map { it.value })
            .executeAsList()
            .toHashSet()
        blobHashes.filterNot { it.value in alreadyIndexed }.toHashSet()
    }

    override suspend fun upsert(blobHash: BlobHash, model: String, vector: FloatArray, chunkIndex: Int?) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val chunkKey = chunkIndex?.toLong() ?: WHOLE_FILE_CHUNK_INDEX
            // Recheck under the lock: two concurrent upserts for the same
            // (blobHash, model, chunkIndex) must not both append a record --
            // the SQLite insert is `INSERT OR IGNORE` and would silently
            // keep only one row, but the flat file has no such protection
            // and would grow an orphaned, unreferenced record for the loser.
            if (database.vectorIndexQueries.vectorEntryFor(blobHash.value, model, chunkKey).executeAsOneOrNull() != null) return@withLock

            val quantized = quantizeVector(vector)
            vectorDir.mkdirs()
            val file = vectorFileFor(model)
            val offset: Long
            RandomAccessFile(file, "rw").use { raf ->
                offset = raf.length()
                raf.seek(offset)
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                header.putFloat(quantized.min)
                header.putFloat(quantized.scale)
                raf.write(header.array())
                raf.write(quantized.data)
            }
            database.vectorIndexQueries.insertVectorEntry(blobHash.value, model, chunkKey, offset)
        }
    }

    override suspend fun search(
        model: String,
        queryVector: FloatArray,
        topK: Int,
        candidateFilter: Set<BlobHash>?,
    ): List<VectorHit> = withContext(Dispatchers.IO) {
        val file = vectorFileFor(model)
        if (!file.exists() || topK <= 0) return@withContext emptyList()

        val dims = queryVector.size
        val recordSize = 8 + dims
        val entries = database.vectorIndexQueries.allVectorEntriesForModel(model).executeAsList()
        if (entries.isEmpty()) return@withContext emptyList()

        val heap = PriorityQueue<VectorHit>(topK, compareBy { it.score })
        val bestByBlob = HashMap<BlobHash, VectorHit>(topK * 2)
        val candidateBytes = ByteArray(dims)

        fun consider(blobHash: BlobHash, score: Double) {
            val existing = bestByBlob[blobHash]
            if (existing != null) {
                if (score <= existing.score) return // an earlier chunk of this same blob already scored at least as well
                heap.remove(existing)
                bestByBlob.remove(blobHash)
            }
            if (heap.size < topK) {
                val hit = VectorHit(blobHash, score)
                heap.add(hit)
                bestByBlob[blobHash] = hit
            } else if (score > heap.peek().score) {
                val worst = heap.poll()
                bestByBlob.remove(worst.blobHash)
                val hit = VectorHit(blobHash, score)
                heap.add(hit)
                bestByBlob[blobHash] = hit
            }
        }

        RandomAccessFile(file, "r").use { raf ->
            val channel: FileChannel = raf.channel
            // ByteBuffer/MappedByteBuffer defaults to BIG_ENDIAN; the write
            // side (upsert, above) writes the min/scale header as
            // LITTLE_ENDIAN explicitly. Without setting this to match, every
            // read silently decodes a garbled min/scale (not an exception --
            // a plausible-looking wrong float), corrupting every score
            // without any obvious failure signature. Caught by
            // FlatFileVectorStoreTest's nearest-match test, not by
            // inspection -- worth the explicit comment.
            val mapped: ByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                .order(ByteOrder.LITTLE_ENDIAN)
            for (entry in entries) {
                val blobHash = BlobHash(entry.blob_hash)
                if (candidateFilter != null && blobHash !in candidateFilter) continue

                val offset = entry.file_offset.toInt()
                if (offset < 0 || offset + recordSize > mapped.limit()) continue // defensive: skip a corrupt/truncated record rather than crash the whole search
                mapped.position(offset)
                val min = mapped.float
                val scale = mapped.float
                mapped.get(candidateBytes)

                val score = cosineSimilarityQuantized(queryVector, candidateBytes, min, scale)
                consider(blobHash, score)
            }
        }

        heap.sortedByDescending { it.score }
    }

    override suspend fun countForModel(model: String): Long = withContext(Dispatchers.IO) {
        database.vectorIndexQueries.countForModel(model).executeAsOne()
    }
}
