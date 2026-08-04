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

    /**
     * The ancestry-aware resume cursor (kotlin-port.md §7.2, Decision C #3):
     * [ref]'s tip commit as of the last FULLY completed index run — never
     * derived from write-insertion order, unlike gitsema-TS's
     * `getLastIndexedCommit()`. Callers should only call [setResumeCursor]
     * after an entire [io.github.jsilvanus.gitsema.git.GitRepository.streamCommits]
     * pass has been consumed successfully, not incrementally mid-run — an
     * interrupted run should leave the previous cursor untouched so the next
     * run conservatively re-walks from the last known-good point.
     */
    suspend fun setResumeCursor(ref: String, commitHash: CommitHash, updatedAtEpochSeconds: Long)
    suspend fun getResumeCursor(ref: String): CommitHash?

    /**
     * The ref whose [setResumeCursor] was most recently called, i.e. the
     * ref a caller most recently finished indexing — durable, so it survives
     * process death (PR #1 review finding #3: an in-process "last indexed
     * ref" variable under-reports after eviction, which on Android is
     * routine). Null if [setResumeCursor] has never been called.
     */
    suspend fun mostRecentlyIndexedRef(): String?

    /**
     * Records that [blobHash] was seen while indexing under [ref] — see the
     * precise semantic note on `blob_branch_entry` in `BlobBranches.sq`:
     * this means "visited while indexing this ref by name," not full git
     * branch-topology membership.
     */
    suspend fun addBlobBranch(blobHash: BlobHash, ref: String)
    suspend fun branchesFor(blobHash: BlobHash): List<String>

    /** Every blob hash ever recorded as seen while indexing [ref] by name. */
    suspend fun blobHashesOnBranch(ref: String): Set<BlobHash>
}
