package io.github.jsilvanus.gitsema

import io.github.jsilvanus.gitsema.chunking.Chunker
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.git.GitRepository
import io.github.jsilvanus.gitsema.indexing.Indexer
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.IndexProgress
import io.github.jsilvanus.gitsema.model.IndexResult
import io.github.jsilvanus.gitsema.model.IndexStatus
import io.github.jsilvanus.gitsema.model.Match
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.search.SearchEngine
import io.github.jsilvanus.gitsema.storage.FtsStore
import io.github.jsilvanus.gitsema.storage.MetadataStore
import io.github.jsilvanus.gitsema.storage.VectorStore

/**
 * The library's public surface (porting brief's sketch, kept close to
 * verbatim). One deliberate reconciliation: the brief's rough sketch shows
 * `search(query: Query, limit: Int)`, but [Query] already carries `topK` —
 * a separate `limit` parameter would either duplicate or fight it. `topK`
 * on [Query] is the one source of truth for result count.
 *
 * A [Match] never carries a caller's domain types (porting brief) — it's
 * path, blob hash, span, score, provenance, exactly as specified.
 */
interface SemanticIndex {
    suspend fun index(ref: String, onProgress: (IndexProgress) -> Unit = {}): IndexResult
    suspend fun search(query: Query): List<Match>
    suspend fun status(): IndexStatus
}

/**
 * The default [SemanticIndex] implementation: wires one [GitRepository]
 * (already scoped to a specific repo — no cwd-implicit invocation model,
 * unlike gitsema-TS's `index` tool description, kotlin-port.md §12.3's
 * flagged entry) to an [Indexer] and a [SearchEngine] sharing the same
 * storage seam.
 */
class GitsemaSemanticIndex(
    repository: GitRepository,
    private val provider: EmbeddingProvider,
    private val metadataStore: MetadataStore,
    private val vectorStore: VectorStore,
    private val ftsStore: FtsStore,
    chunker: Chunker = io.github.jsilvanus.gitsema.chunking.FileChunker(),
    concurrency: Int = 4,
    batchSize: Int = 1,
) : SemanticIndex {
    private val indexer = Indexer(
        repository = repository,
        provider = provider,
        metadataStore = metadataStore,
        vectorStore = vectorStore,
        ftsStore = ftsStore,
        chunker = chunker,
        concurrency = concurrency,
        batchSize = batchSize,
    )
    private val searchEngine = SearchEngine(metadataStore, vectorStore, ftsStore, provider)

    override suspend fun index(ref: String, onProgress: (IndexProgress) -> Unit): IndexResult =
        indexer.index(ref, since = null, onProgress = onProgress)

    override suspend fun search(query: Query): List<Match> = searchEngine.search(query)

    override suspend fun status(): IndexStatus {
        val embedConfig = metadataStore.embedConfigFor(provider.modelId)
        return IndexStatus(
            blobCount = metadataStore.blobCount(),
            embeddedBlobCount = vectorStore.countForModel(provider.modelId),
            // Not tracked yet -- depends on the not-yet-built commit-mapping
            // pass (kotlin-port.md's Indexer scope note), same gap as
            // MetadataStore.firstSeenFor. Left explicitly null rather than a
            // guessed/stale value.
            lastIndexedCommit = null as CommitHash?,
            embeddingModel = embedConfig?.model,
            embeddingDimensions = embedConfig?.dimensions,
        )
    }
}
