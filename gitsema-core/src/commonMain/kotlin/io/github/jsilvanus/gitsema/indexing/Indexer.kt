package io.github.jsilvanus.gitsema.indexing

import io.github.jsilvanus.gitsema.chunking.Chunker
import io.github.jsilvanus.gitsema.chunking.FixedChunker
import io.github.jsilvanus.gitsema.embedding.ContextLengthExceededException
import io.github.jsilvanus.gitsema.embedding.EmbedOutcome
import io.github.jsilvanus.gitsema.embedding.EmbedRequest
import io.github.jsilvanus.gitsema.embedding.EmbeddingOrchestrator
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.git.GitRepository
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.IndexProgress
import io.github.jsilvanus.gitsema.model.IndexResult
import io.github.jsilvanus.gitsema.model.RepoPath
import io.github.jsilvanus.gitsema.storage.CommitMeta
import io.github.jsilvanus.gitsema.storage.EmbedConfigMeta
import io.github.jsilvanus.gitsema.storage.FtsStore
import io.github.jsilvanus.gitsema.storage.MetadataStore
import io.github.jsilvanus.gitsema.storage.VectorStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.Clock

/**
 * Ties the git, chunking, embedding, and storage seams together
 * (kotlin-port.md §7): blob dedup/chunk/embed/store, AND commit-mapping
 * (`streamCommits`, gitsema-TS's `commitMap.ts` equivalent) — every commit
 * reachable from [ref] is recorded, every blob it changed is linked and has
 * its path registered (regardless of whether that blob needed embedding this
 * run, closing a real gap the blob-only loop alone would have: an
 * already-embedded blob resurfacing under a new path used to not get that
 * path registered). This is what makes [MetadataStore.firstSeenFor] real, so
 * ranking's recency signal (§4.2) actually activates instead of always
 * degrading.
 *
 * [ref]'s resume cursor (kotlin-port.md §7.2, Decision C #3) is read
 * automatically when [since] isn't given, and written back to the tip commit
 * only after the WHOLE commit stream for this run has been consumed — an
 * interrupted run leaves the previous cursor untouched, so the next run
 * conservatively re-walks from the last known-good point rather than
 * gitsema-TS's insertion-order bug (which could point at the wrong commit
 * entirely). This is coarser than fully fine-grained per-commit durability
 * would be (a killed run re-walks the whole in-flight window next time, not
 * just what's left) — a deliberate simplicity/correctness tradeoff, not an
 * oversight: individual blob writes are still durable per-blob regardless
 * (§7.2/§8.2), so re-walking never re-embeds anything, only re-visits
 * already-safe ground.
 *
 * [io.github.jsilvanus.gitsema.chunking.FileChunker] is the primary
 * embedding strategy (per the porting brief's "chunking (file strategy
 * first)"). When a whole-file embed attempt fails specifically with
 * [ContextLengthExceededException], the context-limit fallback chain
 * (kotlin-port.md §2.4) kicks in: re-chunk with [FixedChunker] at window
 * size 1500, then 800, storing one chunk-indexed [VectorStore] record per
 * surviving sub-chunk only if EVERY sub-chunk at that window size embeds
 * successfully (matching gitsema-TS: a partial success at one window size
 * doesn't get kept — it moves to the next, smaller size instead). Not
 * wired: the function-chunker tier of the real fallback chain (needs
 * tree-sitter, which the design doc's Decision A says not to build
 * on-device without asking first) — this port's chain is whole-file → fixed
 * 1500 → fixed 800 → fail, skipping the function-chunker step entirely.
 *
 * Every blob visited while walking [ref] (not just ones needing embedding)
 * is recorded as seen on that ref via [MetadataStore.addBlobBranch] — see
 * `BlobBranches.sq` for the precise, deliberately narrower-than-full-git
 * semantic this claims (indexed-under-this-ref-name, not full branch
 * topology).
 *
 * [index] is cooperatively cancellable: [EmbeddingOrchestrator] already
 * rethrows [kotlinx.coroutines.CancellationException] ahead of its generic
 * failure handling, and both loops here call `ensureActive()` explicitly
 * (once per commit, once per [dedupBatchSize] blob batch) rather than
 * relying on their store calls happening to redispatch. A cancellation mid
 * run leaves the previous [MetadataStore.setResumeCursor] value untouched
 * (per the resume-cursor contract above), so the next run re-walks
 * conservatively rather than re-embedding anything already durably written.
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

    suspend fun index(ref: String, since: CommitHash? = null, onProgress: (IndexProgress) -> Unit = {}): IndexResult {
        metadataStore.upsertEmbedConfig(
            EmbedConfigMeta(
                model = provider.modelId,
                provider = provider::class.simpleName ?: "unknown",
                dimensions = provider.dimensions,
                chunker = "file",
                lastUsedAtEpochSeconds = clock(),
            ),
        )

        val effectiveSince = since ?: metadataStore.getResumeCursor(ref)

        var commitsProcessed = 0
        var blobsSeen = 0
        var blobsIndexed = 0
        var blobsSkipped = 0
        var blobsFailed = 0
        var blobsOversized = 0

        // Collected, not drained: the commit stream is consumed one commit at
        // a time, so peak memory is a single CommitInfo regardless of how much
        // history this run covers.
        var tipCommit: CommitHash? = null
        repository.streamCommits(ref, effectiveSince).collect { commit ->
            // PR #1 review finding #1: this loop's own body never suspends
            // (it only calls store writes), so whether it's cancellable used
            // to depend entirely on whether those store calls happened to
            // redispatch. Checking explicitly here makes cancellation
            // observable every iteration, regardless of the caller's
            // dispatcher or the store implementation's internals.
            currentCoroutineContext().ensureActive()
            if (tipCommit == null) tipCommit = commit.hash // first emitted, when since is null, is ref's current tip
            metadataStore.putCommit(
                CommitMeta(
                    hash = commit.hash,
                    timestampEpochSeconds = commit.timestampEpochSeconds,
                    authorName = commit.authorName,
                    authorEmail = commit.authorEmail,
                    message = commit.message,
                ),
            )
            for (changed in commit.changedBlobs) {
                metadataStore.linkBlobCommit(changed.blobHash, commit.hash)
                metadataStore.addPath(changed.blobHash, changed.path)
            }
            metadataStore.markCommitIndexed(commit.hash, clock())
            commitsProcessed++
        }

        // Streamed in bounded windows (kotlin-port.md Decision C #2 and
        // constraint 4, "never buffer entire repo history in memory"): the
        // walk is pulled [dedupBatchSize] entries at a time and each window is
        // fully processed before the next is requested, so peak memory is one
        // window rather than one entry per blob-path pair in history. The
        // embedding pass therefore also backpressures the git walk instead of
        // the walk racing ahead of it.
        //
        // Uses effectiveSince (not the raw `since` parameter) so the resume
        // cursor bounds this walk the same way it bounds the commit walk
        // above -- a blob only reachable through already-processed history
        // is correctly skipped, while a blob still reachable via a NEW
        // commit (even at an already-known path, or an unchanged blob
        // surfacing under a genuinely new one) is still visited: JGit's
        // ObjectWalk excludes an object only when EVERY path to it runs
        // through commits marked uninteresting, not merely because it also
        // happens to be reachable from old history.
        repository.streamBlobs(ref, effectiveSince).chunked(dedupBatchSize).collect { batch ->
            // Same reasoning as the commit loop above -- checked once per
            // dedupBatchSize chunk (not per blob) to keep the overhead
            // negligible while still bounding how much in-flight work a
            // cancellation has to wait out.
            currentCoroutineContext().ensureActive()
            blobsSeen += batch.size
            val batchHashes = batch.map { it.blobHash }
            val toEmbed = vectorStore.filterNewBlobs(batchHashes, provider.modelId)
            blobsSkipped += batch.size - toEmbed.size

            val pathsByHash: Map<BlobHash, RepoPath> = batch.associate { it.blobHash to it.path }
            // Every blob VISITED this walk is "on" ref, whether or not it
            // needed embedding -- branch membership and embed-need are
            // independent questions.
            for (entry in batch) {
                metadataStore.addBlobBranch(entry.blobHash, ref)
            }

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
                // Tier 1 (file chunker): always exactly one chunk. On
                // failure with a context-length cause, the fallback pass
                // below re-chunks this same content with FixedChunker
                // instead -- this first attempt is always whole-file.
                val wholeFileContent = chunks.first().content
                contentByHash[hash] = wholeFileContent
                requests += EmbedRequest(id = hash.value, text = wholeFileContent)
            }

            val outcomes = orchestrator.embedAll(requests)
            val needsFallback = mutableListOf<BlobHash>()
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
                        if (outcome.cause is ContextLengthExceededException) {
                            needsFallback += hash
                        } else {
                            blobsFailed++
                        }
                    }
                }
            }

            for (hash in needsFallback) {
                val content = contentByHash.getValue(hash)
                if (tryFallbackChunking(hash, content)) {
                    metadataStore.putBlob(hash, size = content.length.toLong(), indexedAtEpochSeconds = clock())
                    metadataStore.addPath(hash, pathsByHash.getValue(hash))
                    ftsStore.index(hash, content)
                    blobsIndexed++
                } else {
                    blobsFailed++
                }
            }

            onProgress(IndexProgress(commitsProcessed, blobsSeen, blobsIndexed, blobsFailed))
        }

        // Only reached after the entire commit AND blob streams for this run
        // have been consumed without an unhandled exception -- an
        // interruption anywhere above skips this, leaving the previous
        // cursor (or none) in place for the next run to resume from.
        if (tipCommit != null) {
            metadataStore.setResumeCursor(ref, tipCommit, clock())
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

    /**
     * The context-limit fallback chain (kotlin-port.md §2.4), whole-file →
     * fixed 1500 → fixed 800 → fail (function chunker skipped, see the class
     * doc comment). At each window size, EVERY resulting sub-chunk must
     * embed successfully before any of them are persisted — a partial
     * success at 1500 is discarded entirely and 800 is tried fresh, matching
     * gitsema-TS exactly rather than keeping a partial result. Returns
     * whether [hash] ended up with vectors stored at all.
     */
    private suspend fun tryFallbackChunking(hash: BlobHash, content: String): Boolean {
        for (windowSize in FALLBACK_WINDOW_SIZES) {
            val chunks = FixedChunker(windowSize, FALLBACK_OVERLAP).chunk(content, path = "")
            val requests = chunks.mapIndexed { i, chunk -> EmbedRequest(id = "${hash.value}#$i", text = chunk.content) }
            val outcomes = orchestrator.embedAll(requests)
            if (outcomes.size == requests.size && outcomes.all { it is EmbedOutcome.Success }) {
                outcomes.forEachIndexed { i, outcome ->
                    vectorStore.upsert(hash, provider.modelId, (outcome as EmbedOutcome.Success).vector, chunkIndex = i)
                }
                return true
            }
        }
        return false
    }

    private companion object {
        val FALLBACK_WINDOW_SIZES = listOf(1500, 800)
        const val FALLBACK_OVERLAP = 200
    }
}
