package io.github.jsilvanus.gitsema.git

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.flow.Flow

/** One (path, blob) pairing as it appears somewhere in the walked history. */
data class BlobPathEntry(val path: RepoPath, val blobHash: BlobHash)

/**
 * One commit, with the blobs it added or modified relative to its first
 * parent (root commits diff against an empty tree — everything is "added").
 * Deletions and content-unchanged renames are excluded, matching
 * gitsema-TS's `commitMap.ts` (kotlin-port.md §7.3) — a pure rename still
 * surfaces here as an ADD-type entry for its new path (same blob hash,
 * because renames aren't detected specially), which is exactly how an
 * existing blob accumulates a new path over the index's lifetime.
 */
data class CommitInfo(
    val hash: CommitHash,
    val timestampEpochSeconds: Long,
    val authorName: String,
    val authorEmail: String,
    val message: String,
    val changedBlobs: List<BlobPathEntry>,
)

/**
 * Seam #3 from the porting brief: repository access lives behind this
 * interface so the library is testable without a real repository on disk, and
 * so the concrete implementation can be JGit on JVM/Android without that
 * choice leaking into commonMain (kotlin-port.md §7.3, §8).
 *
 * Every method here is a direct replacement for one of gitsema-TS's
 * subprocess-based files under `src/core/git/` — see kotlin-port.md §7.3 for
 * the exact mapping. [streamBlobs] in particular must preserve `revList.ts`'s
 * streaming contract: never buffer the whole walked history in memory
 * (constraint 4) — a real requirement, not just a nice-to-have, since
 * `revList.ts` is the one file in the TS indexer that actually keeps this
 * promise (kotlin-port.md Decision C #2 flags where the TS indexer itself does not).
 */
interface GitRepository {
    /** Resolve a ref (branch, tag, commit hash, `"HEAD"`, ...) to a commit hash, or null if it doesn't resolve. */
    suspend fun resolveRef(ref: String): CommitHash?

    /**
     * Stream every (path, blobHash) pair reachable from [ref]'s history, each
     * blob emitted once per path it was ever committed under (deduplication by
     * blob hash alone is the indexer's job, not this method's — see
     * kotlin-port.md §1.1). If [since] is given, only commits not already
     * reachable from [since] are walked (mirrors `git rev-list <since>..<ref>`).
     */
    fun streamBlobs(ref: String, since: CommitHash? = null): Flow<BlobPathEntry>

    /**
     * Fetch a blob's raw content. Returns null if the blob exceeds [maxBytes]
     * (mirrors `showBlob.ts`'s early-abort-on-size behavior) rather than
     * reading and discarding it — the whole point of the cap is to never hold
     * an oversized blob in memory even transiently.
     */
    suspend fun readBlob(hash: BlobHash, maxBytes: Long): ByteArray?

    /**
     * Stream commits reachable from [ref], newest first (mirrors `commitMap.ts`'s
     * `streamCommitMap()`, kotlin-port.md §7.3), each with its added/modified
     * blobs. If [since] is given, only commits not already reachable from
     * [since] are walked. **The first commit emitted, when [since] is null,
     * is [ref]'s current tip** — callers that want an ancestry-aware resume
     * cursor (kotlin-port.md Decision C #3, not gitsema-TS's insertion-order
     * cursor) should capture that first hash and persist it only after the
     * whole stream has been consumed successfully.
     */
    fun streamCommits(ref: String, since: CommitHash? = null): Flow<CommitInfo>
}
