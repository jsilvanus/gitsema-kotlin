package io.github.jsilvanus.gitsema.indexing

import io.github.jsilvanus.gitsema.chunking.Chunker
import io.github.jsilvanus.gitsema.embedding.EmbedOutcome
import io.github.jsilvanus.gitsema.embedding.EmbedRequest
import io.github.jsilvanus.gitsema.embedding.EmbeddingOrchestrator
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.git.GitRepository
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.IndexProgress
import io.github.jsilvanus.gitsema.model.IndexResult
import io.github.jsilvanus.gitsema.model.RepoPath
import io.github.jsilvanus.gitsema.storage.EmbedConfigMeta
import io.github.jsilvanus.gitsema.storage.FtsStore
import io.github.jsilvanus.gitsema.storage.MetadataStore
import io.github.jsilvanus.gitsema.storage.VectorStore
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock

/**
 * Ties the git, chunking, embedding, and storage seams together
 * (kotlin-port.md §7). Scope note: this is the blob-indexing pipeline only —
 * dedup, chunk, embed, store vector + FTS content + path. Commit metadata
 * (`streamCommits`, the equivalent of gitsema-TS's `commitMap.ts`) is a
 * separate, not-yet-built slice; until it lands, [MetadataStore.firstSeenFor]
 * has nothing to return and ranking's recency signal degrades to neutral
 * (kotlin-port.md §4.2/constraint 5's "mark as degraded" principle applied to
 * a sub-signal, not just whole results) rather than silently pretending to work.
 *
 * Only [io.github.jsilvanus.gitsema.chunking.FileChunker] is wired in Tier 1
 * (per the porting brief's "chunking (file strategy first)") — the
 * context-limit fallback chain (kotlin-port.md §2.4: function chunker then
 * fixed 1500/800) needs the function/fixed chunkers, not yet built, so an
 * oversized-context failure is currently just a failed blob, counted in
 * stats, not retried with a smaller chunk. Documented here, not silently
 * dropped.
 */
class Indexer(
    private val repository: GitRepository,
    private val provider: EmbeddingProvider,
    private val metadataStore: MetadataStore,
    private val vectorStore: VectorStore,
    private val ftsStore: FtsStore,
    private val chunker: Chunker,
    concurrency: Int = 4,
    batchSize: Int = 1,
    private val maxBlobBytes: Long = 200 * 1024,
    private val dedupBatchSize: Int = 500,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val orchestrator = EmbeddingOrchestrator(provider, concurrency, batchSize)

    suspend fun index(ref: String, since: io.github.jsilvanus.gitsema.model.CommitHash? = null, onProgress: (IndexProgress) -> Unit = {}): IndexResult {
        metadataStore.upsertEmbedConfig(
            EmbedConfigMeta(
                model = provider.modelId,
                provider = provider::class.simpleName ?: "unknown",
                dimensions = provider.dimensions,
                chunker = "file",
                lastUsedAtEpochSeconds = clock(),
            ),
        )

        var commitsProcessed = 0
        var blobsSeen = 0
        var blobsIndexed = 0
        var blobsSkipped = 0
        var blobsFailed = 0
        var blobsOversized = 0

        // Known departure from constraint 4 ("never buffer entire history"),
        // shared with gitsema-TS's own indexer (kotlin-port.md Decision C #2,
        // which flags this exact pattern in the TS original as something the
        // port should improve, not replicate uncritically). Draining the
        // whole stream here before batching is the pragmatic Tier 1 version;
        // it holds only lightweight (path, blobHash) pairs, never blob
        // content, so at gitsema's target scale (tens of thousands of blobs)
        // this is megabytes, not the ~150MB+ vector-materialization problem
        // §9 exists to solve -- but it is not what "streaming" should mean
        // long-term. A true fix processes GitRepository.streamBlobs' Flow in
        // bounded windows without a full upfront collect; tracked as
        // follow-up work, not silently accepted as done.
        val entries = repository.streamBlobs(ref, since).toList()
        for (batch in entries.chunked(dedupBatchSize)) {
            blobsSeen += batch.size
            val batchHashes = batch.map { it.blobHash }
            val toEmbed = vectorStore.filterNewBlobs(batchHashes, provider.modelId)
            blobsSkipped += batch.size - toEmbed.size

            val pathsByHash: Map<BlobHash, RepoPath> = batch.associate { it.blobHash to it.path }
            val requests = mutableListOf<EmbedRequest>()
            val contentByHash = mutableMapOf<BlobHash, String>()

            for (hash in toEmbed) {
                val bytes = repository.readBlob(hash, maxBlobBytes)
                if (bytes == null) {
                    blobsOversized++
                    continue
                }
                val text = bytes.decodeToString()
                val path = pathsByHash.getValue(hash)
                val chunks = chunker.chunk(text, path.value)
                // Tier 1 (file chunker): always exactly one chunk. A future
                // function/fixed chunker would produce more than one here,
                // each needing its own EmbedRequest keyed distinctly -- not
                // yet needed since only FileChunker is wired.
                val wholeFileContent = chunks.first().content
                contentByHash[hash] = wholeFileContent
                requests += EmbedRequest(id = hash.value, text = wholeFileContent)
            }

            val outcomes = orchestrator.embedAll(requests)
            for (outcome in outcomes) {
                val hash = BlobHash(outcome.id)
                when (outcome) {
                    is EmbedOutcome.Success -> {
                        vectorStore.upsert(hash, provider.modelId, outcome.vector)
                        metadataStore.putBlob(hash, size = contentByHash.getValue(hash).length.toLong(), indexedAtEpochSeconds = clock())
                        metadataStore.addPath(hash, pathsByHash.getValue(hash))
                        ftsStore.index(hash, contentByHash.getValue(hash))
                        blobsIndexed++
                    }
                    is EmbedOutcome.Failed -> {
                        blobsFailed++
                    }
                }
            }

            onProgress(IndexProgress(commitsProcessed, blobsSeen, blobsIndexed, blobsFailed))
        }

        return IndexResult(
            commitsProcessed = commitsProcessed,
            blobsSeen = blobsSeen,
            blobsIndexed = blobsIndexed,
            blobsSkipped = blobsSkipped,
            blobsFailed = blobsFailed,
            blobsOversized = blobsOversized,
        )
    }
}
