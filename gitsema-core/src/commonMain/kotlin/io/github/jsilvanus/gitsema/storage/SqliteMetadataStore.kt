package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * [MetadataStore] backed by the SQLDelight-generated [GitsemaDatabase]. Every
 * write goes through `INSERT OR IGNORE` / upsert, mirroring gitsema-TS's
 * content-addressed idempotency discipline (kotlin-port.md §1.1) — repeat
 * calls for the same key are always safe, which is what makes blob-atomic
 * resumability (§7.2/§8.2) correct without any extra bookkeeping here.
 *
 * @param ioContext the context every blocking call here is dispatched to.
 * Injected rather than hardcoded to `Dispatchers.IO` because the consuming
 * application owns the resource envelope, not this library (aidos D29: Aidos
 * owns the lifecycle and "the resource envelope — background dispatcher,
 * cancellable batches"). A host that must keep this work off its own IO pool,
 * bound its parallelism, or confine it to one thread can only do so if it
 * supplies the context. The default preserves the previous behaviour.
 */
class SqliteMetadataStore(
    private val database: GitsemaDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : MetadataStore {
    private val blobs = database.blobsQueries
    private val commits = database.commitsQueries
    private val embedConfig = database.embedConfigQueries
    private val resumeCursor = database.resumeCursorQueries
    private val blobBranches = database.blobBranchesQueries

    override suspend fun putBlob(blobHash: BlobHash, size: Long, indexedAtEpochSeconds: Long) =
        withContext(ioContext) {
            blobs.insertBlob(blobHash.value, size, indexedAtEpochSeconds)
        }

    override suspend fun addPath(blobHash: BlobHash, path: RepoPath) = withContext(ioContext) {
        blobs.insertPath(blobHash.value, path.value)
    }

    override suspend fun pathsFor(blobHash: BlobHash): List<RepoPath> = withContext(ioContext) {
        blobs.pathsForBlob(blobHash.value).executeAsList().map { RepoPath(it) }
    }

    override suspend fun blobExists(blobHash: BlobHash): Boolean = withContext(ioContext) {
        blobs.blobExists(blobHash.value).executeAsOne()
    }

    override suspend fun blobCount(): Long = withContext(ioContext) {
        blobs.blobCount().executeAsOne()
    }

    override suspend fun putCommit(commit: CommitMeta) = withContext(ioContext) {
        commits.insertCommit(
            commit_hash = commit.hash.value,
            timestamp = commit.timestampEpochSeconds,
            author_name = commit.authorName,
            author_email = commit.authorEmail,
            message = commit.message,
        )
    }

    override suspend fun linkBlobCommit(blobHash: BlobHash, commitHash: CommitHash) = withContext(ioContext) {
        commits.linkBlobCommit(blobHash.value, commitHash.value)
    }

    override suspend fun markCommitIndexed(commitHash: CommitHash, indexedAtEpochSeconds: Long) =
        withContext(ioContext) {
            commits.markCommitIndexed(commitHash.value, indexedAtEpochSeconds)
        }

    override suspend fun isCommitIndexed(commitHash: CommitHash): Boolean = withContext(ioContext) {
        commits.isCommitIndexed(commitHash.value).executeAsOne()
    }

    override suspend fun firstSeenFor(blobHash: BlobHash): Long? = withContext(ioContext) {
        commits.firstSeenForBlob(blobHash.value).executeAsOne().first_seen
    }

    override suspend fun upsertEmbedConfig(config: EmbedConfigMeta) = withContext(ioContext) {
        embedConfig.upsertEmbedConfig(
            model = config.model,
            provider = config.provider,
            dimensions = config.dimensions.toLong(),
            chunker = config.chunker,
            last_used_at = config.lastUsedAtEpochSeconds,
        )
    }

    override suspend fun embedConfigFor(model: String): EmbedConfigMeta? = withContext(ioContext) {
        embedConfig.embedConfigForModel(model).executeAsOneOrNull()?.let {
            EmbedConfigMeta(
                model = it.model,
                provider = it.provider,
                dimensions = it.dimensions.toInt(),
                chunker = it.chunker,
                lastUsedAtEpochSeconds = it.last_used_at,
            )
        }
    }

    override suspend fun setResumeCursor(ref: String, commitHash: CommitHash, updatedAtEpochSeconds: Long) =
        withContext(ioContext) {
            resumeCursor.setResumeCursor(ref, commitHash.value, updatedAtEpochSeconds)
        }

    override suspend fun getResumeCursor(ref: String): CommitHash? = withContext(ioContext) {
        resumeCursor.getResumeCursor(ref).executeAsOneOrNull()?.let { CommitHash(it) }
    }

    override suspend fun mostRecentlyIndexedRef(): String? = withContext(ioContext) {
        resumeCursor.mostRecentRef().executeAsOneOrNull()
    }

    override suspend fun addBlobBranch(blobHash: BlobHash, ref: String) = withContext(ioContext) {
        blobBranches.addBlobBranch(blobHash.value, ref)
    }

    override suspend fun branchesFor(blobHash: BlobHash): List<String> = withContext(ioContext) {
        blobBranches.branchesFor(blobHash.value).executeAsList()
    }

    override suspend fun blobHashesOnBranch(ref: String): Set<BlobHash> = withContext(ioContext) {
        blobBranches.blobHashesOnBranch(ref).executeAsList().map { BlobHash(it) }.toHashSet()
    }
}
