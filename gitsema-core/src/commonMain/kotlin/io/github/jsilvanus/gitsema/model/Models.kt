package io.github.jsilvanus.gitsema.model

/**
 * A Git blob's content hash (SHA-1, 40 lowercase hex chars today; Git's SHA-256
 * transition would change the length, not this type). This is gitsema's unit of
 * identity end to end — see docs/design/kotlin-port.md §1.1: every table, every
 * cache key, every dedup check pivots on this, never on [path] or a commit hash.
 */
@JvmInline
value class BlobHash(val value: String) {
    override fun toString(): String = value
}

/** A file path as it appears in the repository tree at some point in history. */
@JvmInline
value class RepoPath(val value: String) {
    override fun toString(): String = value
}

/** A Git commit hash. Distinct type from [BlobHash] — the two are never interchangeable. */
@JvmInline
value class CommitHash(val value: String) {
    override fun toString(): String = value
}

/** Which chunking strategy produced a [Chunk]. See kotlin-port.md §2. */
enum class ChunkStrategy { FILE, FIXED, FUNCTION }

/**
 * A unit of text to embed. `startLine`/`endLine` are 1-indexed and inclusive,
 * matching gitsema-TS's convention exactly (kotlin-port.md §2).
 */
data class Chunk(
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val strategy: ChunkStrategy,
    /** Present only for FUNCTION chunks with resolved symbol identity (kotlin-port.md §1.2). */
    val symbol: SymbolIdentity? = null,
)

/**
 * The path-free, blob-intrinsic identity described in kotlin-port.md §1.2 —
 * immutable per (blobHash, qualifiedName, signatureHash), computed once per blob
 * and never re-derived from a path. Only populated by a tree-sitter-backed
 * chunker (Tier 2+); the file chunker never sets this.
 */
data class SymbolIdentity(
    val qualifiedName: String,
    val signatureHash: String,
    val parentQualifiedName: String?,
    val symbolKind: String,
)

/** A search query. `text` is embedded with the text/query-side [io.github.jsilvanus.gitsema.embedding.EmbeddingProvider]. */
data class Query(
    val text: String,
    val topK: Int = 10,
    val branch: String? = null,
)

/** Where a [Match]'s score came from — kept explicit so a degraded (FTS-only) result never masquerades as a full vector match. */
enum class MatchProvenance { VECTOR, FTS, HYBRID }

/**
 * One search result. Carries only what the porting brief specifies (path, blob
 * hash, span, score, provenance) — never a caller's domain types.
 */
data class Match(
    val blobHash: BlobHash,
    val paths: List<RepoPath>,
    val startLine: Int,
    val endLine: Int,
    val score: Double,
    val provenance: MatchProvenance,
    /** True if this result was produced while embeddings were still catching up (constraint 5: search never blocks on indexing). */
    val degraded: Boolean = false,
)

/** Progress callback payload for [io.github.jsilvanus.gitsema.SemanticIndex.index]. */
data class IndexProgress(
    val commitsProcessed: Int,
    val blobsSeen: Int,
    val blobsIndexed: Int,
    val blobsFailed: Int,
)

/** Final tally from an [io.github.jsilvanus.gitsema.SemanticIndex.index] run. Mirrors gitsema-TS's indexer stats shape. */
data class IndexResult(
    val commitsProcessed: Int,
    val blobsSeen: Int,
    val blobsIndexed: Int,
    val blobsSkipped: Int,
    val blobsFailed: Int,
    val blobsOversized: Int,
)

/**
 * Two counters: how much of what the index knows about has actually been
 * embedded for the active model.
 *
 * This exists because a consumer cannot otherwise tell "there is no retry
 * logic in this repository" from "I have not read most of it yet" — the
 * distinction aidos D29 requires every query to carry, not just a status
 * call, since it is at answer time that the difference misleads. It rides on
 * [SearchResult] for exactly that reason.
 *
 * [blobsKnown] counts blobs recorded in metadata (seen while walking
 * history); [blobsEmbedded] counts those with a vector for the model the
 * query ran against. A different model has different coverage over the same
 * repository, which is why this is never a single global number.
 */
data class IndexCoverage(
    val blobsEmbedded: Long,
    val blobsKnown: Long,
) {
    /**
     * `blobsEmbedded / blobsKnown`, or `0.0` when nothing is known yet —
     * an empty index is 0% covered, not undefined, and callers should not
     * have to special-case a division they did not ask to perform.
     */
    val fraction: Double get() = if (blobsKnown == 0L) 0.0 else blobsEmbedded.toDouble() / blobsKnown.toDouble()

    /** True once every known blob has a vector for this model. */
    val isComplete: Boolean get() = blobsKnown > 0L && blobsEmbedded >= blobsKnown
}

/**
 * What [io.github.jsilvanus.gitsema.SemanticIndex.search] returns: results
 * plus the coverage they were produced under.
 *
 * A bare `List<Match>` cannot answer "how much of the repository did this
 * look at", and the answer is not a property of any individual match — it is
 * a property of the search. [degraded] is hoisted here for the same reason:
 * it describes the whole search (no vectors existed for this model, so this
 * was FTS-only), and asking a caller to infer that by checking whether every
 * element happens to be flagged is an invitation to get it wrong. It stays
 * on [Match] as well, so a match separated from its result set still carries
 * its own provenance.
 */
data class SearchResult(
    val matches: List<Match>,
    val coverage: IndexCoverage,
    /** True when this search ran without any vectors for the active model (constraint 5's FTS-only path). */
    val degraded: Boolean,
)

/** Coverage snapshot — kotlin-port.md §12.3 notes gitsema-TS has no model-facing text for this; it is originated here. */
data class IndexStatus(
    val coverage: IndexCoverage,
    val lastIndexedCommit: CommitHash?,
    val embeddingModel: String?,
    val embeddingDimensions: Int?,
)
