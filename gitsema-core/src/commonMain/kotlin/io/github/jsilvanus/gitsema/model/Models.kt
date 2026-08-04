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

/** Coverage snapshot — kotlin-port.md §12.3 notes gitsema-TS has no model-facing text for this; it is originated here. */
data class IndexStatus(
    val blobCount: Long,
    val embeddedBlobCount: Long,
    val lastIndexedCommit: CommitHash?,
    val embeddingModel: String?,
    val embeddingDimensions: Int?,
)
