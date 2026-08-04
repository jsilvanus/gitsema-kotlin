package io.github.jsilvanus.gitsema.git

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * JGit-backed [GitRepository] — the replacement for gitsema-TS's subprocess
 * `git` calls (kotlin-port.md §7.3, and the porting brief: "Android has none.
 * Use JGit... A rewrite against a different API, not a port."). Pure Java, no
 * native dependencies — safe on Android.
 *
 * [streamBlobs] uses JGit's [ObjectWalk], the direct equivalent of
 * `git rev-list --objects`: it walks the commit graph and the tree/blob graph
 * together, visiting each object exactly once, which is what makes
 * `revList.ts` cheap on the TS side and what this must preserve here — a naive
 * per-commit full-tree walk would revisit every blob once per commit that
 * still contains it, which is exactly the buffering/re-walk problem
 * kotlin-port.md Decision C #2 calls out in the TS indexer's own commit-mapping
 * pass. `ObjectWalk` avoids that by construction.
 */
class JGitRepository(repoDir: File) : GitRepository, AutoCloseable {
    private val repository: Repository = FileRepositoryBuilder()
        .setGitDir(File(repoDir, ".git").takeIf { it.exists() } ?: repoDir)
        .readEnvironment()
        .findGitDir()
        .build()

    override suspend fun resolveRef(ref: String): CommitHash? = withContext(Dispatchers.IO) {
        val id = repository.resolve(ref) ?: return@withContext null
        CommitHash(id.name)
    }

    override fun streamBlobs(ref: String, since: CommitHash?): Flow<BlobPathEntry> = flow {
        val startId = repository.resolve(ref) ?: return@flow
        ObjectWalk(repository).use { walk ->
            val startCommit = walk.parseCommit(startId)
            walk.markStart(startCommit)
            if (since != null) {
                val sinceId = repository.resolve(since.value)
                if (sinceId != null) {
                    walk.markUninteresting(walk.parseCommit(sinceId))
                }
            }

            // Drain the commit half of the walk before switching to nextObject(),
            // exactly as ObjectWalk requires.
            while (walk.next() != null) { /* just advance the commit walk */ }

            var obj = walk.nextObject()
            while (obj != null) {
                if (obj.type == Constants.OBJ_BLOB) {
                    val path = walk.pathString
                    if (path != null) {
                        emit(BlobPathEntry(RepoPath(path), BlobHash(obj.toObjectId().name)))
                    }
                }
                obj = walk.nextObject()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun readBlob(hash: BlobHash, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val id = ObjectId.fromString(hash.value)
        val loader: ObjectLoader = try {
            repository.open(id, Constants.OBJ_BLOB)
        } catch (e: MissingObjectException) {
            return@withContext null
        }
        if (loader.size > maxBytes) return@withContext null
        loader.cachedBytes
    }

    override fun close() {
        repository.close()
    }
}

/** A single named commit reachable during a walk — not yet used by [JGitRepository]; kept for the commit-mapping pass (kotlin-port.md §7.1 Phase C). */
internal fun RevCommit.epochSeconds(): Long = commitTime.toLong()
