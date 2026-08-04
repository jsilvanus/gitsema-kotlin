package io.github.jsilvanus.gitsema.testutil

import io.github.jsilvanus.gitsema.git.BlobPathEntry
import io.github.jsilvanus.gitsema.git.CommitInfo
import io.github.jsilvanus.gitsema.git.GitRepository
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One synthetic commit for [FakeGitRepository]'s commit history. */
data class FakeCommit(
    val hash: String,
    val timestampEpochSeconds: Long,
    val authorName: String = "Test Author",
    val authorEmail: String = "test@example.com",
    val message: String = "test commit",
    /** Files added/modified in this commit (path -> content). */
    val files: Map<String, String>,
)

/**
 * A pure-Kotlin, no-I/O [GitRepository] double for tests that don't need a
 * real repository on disk — proving the porting brief's seam #3 claim
 * ("the library is testable without a real repository") for real, not just
 * in principle. Blob hashes are content-addressed the same way real Git's
 * are: identical content always maps to the same fake hash, distinct
 * content (almost certainly) doesn't.
 *
 * [files] is the final (HEAD) snapshot used by [streamBlobs]/[readBlob].
 * [commits], **newest first** (matching [GitRepository.streamCommits]'s real
 * contract), defaults to one synthetic commit containing every file in
 * [files] — enough for tests that only care about blob-level behavior. Tests
 * exercising commit-mapping/resume-cursor behavior should pass a real
 * multi-commit history explicitly.
 */
class FakeGitRepository(
    private val files: Map<String, String>,
    private val commits: List<FakeCommit> = listOf(
        FakeCommit(hash = "fakehead".padEnd(40, '0'), timestampEpochSeconds = 0, files = files),
    ),
) : GitRepository {
    override suspend fun resolveRef(ref: String): CommitHash? =
        if (ref.isBlank()) null else commits.firstOrNull()?.let { CommitHash(it.hash) }

    override fun streamBlobs(ref: String, since: CommitHash?): Flow<BlobPathEntry> = flow {
        for ((path, content) in files) {
            emit(BlobPathEntry(RepoPath(path), fakeBlobHash(content)))
        }
    }

    override suspend fun readBlob(hash: BlobHash, maxBytes: Long): ByteArray? {
        val content = files.entries.firstOrNull { fakeBlobHash(it.value) == hash }?.value ?: return null
        val bytes = content.encodeToByteArray()
        return if (bytes.size > maxBytes) null else bytes
    }

    override fun streamCommits(ref: String, since: CommitHash?): Flow<CommitInfo> = flow {
        val sinceIdx = if (since != null) commits.indexOfFirst { it.hash == since.value } else -1
        val toEmit = if (sinceIdx >= 0) commits.subList(0, sinceIdx) else commits
        for (c in toEmit) {
            emit(
                CommitInfo(
                    hash = CommitHash(c.hash),
                    timestampEpochSeconds = c.timestampEpochSeconds,
                    authorName = c.authorName,
                    authorEmail = c.authorEmail,
                    message = c.message,
                    changedBlobs = c.files.map { (path, content) -> BlobPathEntry(RepoPath(path), fakeBlobHash(content)) },
                ),
            )
        }
    }
}

/** FNV-1a-derived, 40-hex-char fake hash -- deterministic and content-addressed, not a real SHA-1. */
fun fakeBlobHash(content: String): BlobHash {
    var h = 1469598103934665603UL
    for (ch in content) {
        h = h xor ch.code.toULong()
        h *= 1099511628211UL
    }
    val hex = h.toString(16).padStart(16, '0')
    return BlobHash((hex + hex + hex).take(40))
}
