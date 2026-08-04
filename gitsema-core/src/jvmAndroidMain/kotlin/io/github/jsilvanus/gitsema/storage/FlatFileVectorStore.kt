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

/**
 * [VectorStore] per kotlin-port.md §9.2: one flat, append-only, fixed-record
 * file PER MODEL (so record length is a compile-time-known constant, `8 +
 * dims` bytes — 4-byte min + 4-byte scale + `dims` int8 bytes), memory-mapped
 * for reads, with a tiny SQLite table (`vector_entry`, kotlin-port.md §6.4)
 * mapping `(blob_hash, model) -> file_offset`.
 *
 * Deliberately brute-force: [search] streams every stored vector for a model
 * through [cosineSimilarityQuantized] and keeps only a bounded top-K min-heap
 * — never materializes the candidate pool (§9.1's redesign target). No ANN
 * index sits in front of this. §9.2 point 4 is explicit that this is the
 * right place to start: quantized, a 50,000-blob model's vector file is
 * already only ~38MB, comfortably inside a 200MB ceiling even before ANN is
 * considered, and ANN should only be added once real on-device profiling
 * shows brute force is actually too slow — not speculatively here.
 *
 * A single [FileChannel.map] call caps a mapped region at ~2GB (`ByteBuffer`
 * is int-indexed) — comfortably far above Tier 1's realistic scale (a
 * 50,000-blob repo's file is tens of MB), but a repo with tens of millions of
 * blobs would need a multi-region mapping scheme this does not implement.
 * Not a Tier 1 concern; flagged here rather than silently assumed away.
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

    override suspend fun upsert(blobHash: BlobHash, model: String, vector: FloatArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            // Recheck under the lock: two concurrent upserts for the same
            // (blobHash, model) must not both append a record -- the SQLite
            // insert is `INSERT OR IGNORE` and would silently keep only one
            // row, but the flat file has no such protection and would grow
            // an orphaned, unreferenced record for the loser.
            if (database.vectorIndexQueries.isVectorIndexed(blobHash.value, model).executeAsOne()) return@withLock

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
            database.vectorIndexQueries.insertVectorEntry(blobHash.value, model, offset)
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

        // Min-heap on score: the smallest of the current top-K sits at the
        // head, so a new candidate that beats it is a cheap poll+add and
        // anything worse than the current worst top-K member is a cheap
        // peek+skip. Heap size never exceeds topK -- this IS the "memory use
        // is O(topK), never O(candidate count)" guarantee from the interface
        // doc, not just a comment.
        val heap = PriorityQueue<VectorHit>(topK, compareBy { it.score })
        val candidateBytes = ByteArray(dims)

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
                if (heap.size < topK) {
                    heap.add(VectorHit(blobHash, score))
                } else if (score > heap.peek().score) {
                    heap.poll()
                    heap.add(VectorHit(blobHash, score))
                }
            }
        }

        heap.sortedByDescending { it.score }
    }

    override suspend fun countForModel(model: String): Long = withContext(Dispatchers.IO) {
        database.vectorIndexQueries.countForModel(model).executeAsOne()
    }
}
