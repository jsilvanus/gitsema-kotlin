package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath

data class CommitMeta(
    val hash: CommitHash,
    val timestampEpochSeconds: Long,
    val authorName: String,
    val authorEmail: String,
    val message: String,
)

data class EmbedConfigMeta(
    val model: String,
    val provider: String,
    val dimensions: Int,
    val chunker: String,
    val lastUsedAtEpochSeconds: Long,
)

/**
 * The relational facts that are always present, regardless of embedding
 * backend: blob/path registry, commit metadata, and provenance. One of the
 * four store interfaces from kotlin-port.md §6.1 — kept narrow and separate
 * from [VectorStore]/[FtsStore] on purpose (§6.2): the conceptual split is
 * worth preserving even though, unlike gitsema-TS, there is only ever one
 * physical backend (SQLite) behind it.
 */
interface MetadataStore {
    suspend fun putBlob(blobHash: BlobHash, size: Long, indexedAtEpochSeconds: Long)
    suspend fun addPath(blobHash: BlobHash, path: RepoPath)
    suspend fun pathsFor(blobHash: BlobHash): List<RepoPath>
    suspend fun blobExists(blobHash: BlobHash): Boolean
    suspend fun blobCount(): Long

    suspend fun putCommit(commit: CommitMeta)
    suspend fun linkBlobCommit(blobHash: BlobHash, commitHash: CommitHash)
    suspend fun markCommitIndexed(commitHash: CommitHash, indexedAtEpochSeconds: Long)
    suspend fun isCommitIndexed(commitHash: CommitHash): Boolean

    /** Earliest commit timestamp this blob has ever been seen at, or null if never committed. */
    suspend fun firstSeenFor(blobHash: BlobHash): Long?

    suspend fun upsertEmbedConfig(config: EmbedConfigMeta)
    suspend fun embedConfigFor(model: String): EmbedConfigMeta?
}
