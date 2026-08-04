package io.github.jsilvanus.gitsema.git

import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
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

    override fun streamCommits(ref: String, since: CommitHash?): Flow<CommitInfo> = flow {
        val startId = repository.resolve(ref) ?: return@flow
        RevWalk(repository).use { walk ->
            val start = walk.parseCommit(startId)
            walk.markStart(start)
            if (since != null) {
                val sinceId = repository.resolve(since.value)
                if (sinceId != null) {
                    walk.markUninteresting(walk.parseCommit(sinceId))
                }
            }

            val reader = repository.newObjectReader()
            for (commit in walk) {
                val newTree = CanonicalTreeParser().apply { reset(reader, commit.tree) }
                val oldTree = if (commit.parentCount > 0) {
                    val parent = walk.parseCommit(commit.getParent(0))
                    CanonicalTreeParser().apply { reset(reader, parent.tree) }
                } else {
                    EmptyTreeIterator()
                }

                val diffs: List<DiffEntry> = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                    formatter.setRepository(repository)
                    // No rename detection: a plain content-identical rename
                    // then surfaces as a DELETE (ignored, see the filter
                    // below) + an ADD at the new path for the SAME blob hash
                    // -- exactly how an already-known blob is meant to pick
                    // up a new path over the index's lifetime (kotlin-port.md
                    // §1.1), not something to special-case away.
                    formatter.scan(oldTree, newTree)
                }

                val changedBlobs = diffs
                    .filter { it.changeType == DiffEntry.ChangeType.ADD || it.changeType == DiffEntry.ChangeType.MODIFY || it.changeType == DiffEntry.ChangeType.COPY }
                    .map { entry -> BlobPathEntry(RepoPath(entry.newPath), BlobHash(entry.newId.toObjectId().name)) }

                val authorIdent = commit.authorIdent
                emit(
                    CommitInfo(
                        hash = CommitHash(commit.name),
                        timestampEpochSeconds = commit.commitTime.toLong(),
                        authorName = authorIdent?.name ?: "",
                        authorEmail = authorIdent?.emailAddress ?: "",
                        message = commit.shortMessage,
                        changedBlobs = changedBlobs,
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        repository.close()
    }
}
