package io.github.jsilvanus.gitsema.testutil

import io.github.jsilvanus.gitsema.git.BlobPathEntry
import io.github.jsilvanus.gitsema.git.GitRepository
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A pure-Kotlin, no-I/O [GitRepository] double for tests that don't need a
 * real repository on disk — proving the porting brief's seam #3 claim
 * ("the library is testable without a real repository") for real, not just
 * in principle. Blob hashes are content-addressed the same way real Git's
 * are: identical [content] always maps to the same fake hash, distinct
 * content (almost certainly) doesn't.
 */
class FakeGitRepository(private val files: Map<String, String>) : GitRepository {
    override suspend fun resolveRef(ref: String): CommitHash? =
        if (ref.isNotBlank()) CommitHash("fakehead".padEnd(40, '0')) else null

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
