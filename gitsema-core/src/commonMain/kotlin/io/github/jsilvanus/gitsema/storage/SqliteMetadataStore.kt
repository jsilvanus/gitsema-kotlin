package io.github.jsilvanus.gitsema.storage

import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.CommitHash
import io.github.jsilvanus.gitsema.model.RepoPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MetadataStore] backed by the SQLDelight-generated [GitsemaDatabase]. Every
 * write goes through `INSERT OR IGNORE` / upsert, mirroring gitsema-TS's
 * content-addressed idempotency discipline (kotlin-port.md §1.1) — repeat
 * calls for the same key are always safe, which is what makes blob-atomic
 * resumability (§7.2/§8.2) correct without any extra bookkeeping here.
 */
class SqliteMetadataStore(private val database: GitsemaDatabase) : MetadataStore {
    private val blobs = database.blobsQueries
    private val commits = database.commitsQueries
    private val embedConfig = database.embedConfigQueries

    override suspend fun putBlob(blobHash: BlobHash, size: Long, indexedAtEpochSeconds: Long) =
        withContext(Dispatchers.IO) {
            blobs.insertBlob(blobHash.value, size, indexedAtEpochSeconds)
        }

    override suspend fun addPath(blobHash: BlobHash, path: RepoPath) = withContext(Dispatchers.IO) {
        blobs.insertPath(blobHash.value, path.value)
    }

    override suspend fun pathsFor(blobHash: BlobHash): List<RepoPath> = withContext(Dispatchers.IO) {
        blobs.pathsForBlob(blobHash.value).executeAsList().map { RepoPath(it) }
    }

    override suspend fun blobExists(blobHash: BlobHash): Boolean = withContext(Dispatchers.IO) {
        blobs.blobExists(blobHash.value).executeAsOne()
    }

    override suspend fun blobCount(): Long = withContext(Dispatchers.IO) {
        blobs.blobCount().executeAsOne()
    }

    override suspend fun putCommit(commit: CommitMeta) = withContext(Dispatchers.IO) {
        commits.insertCommit(
            commit_hash = commit.hash.value,
            timestamp = commit.timestampEpochSeconds,
            author_name = commit.authorName,
            author_email = commit.authorEmail,
            message = commit.message,
        )
    }

    override suspend fun linkBlobCommit(blobHash: BlobHash, commitHash: CommitHash) = withContext(Dispatchers.IO) {
        commits.linkBlobCommit(blobHash.value, commitHash.value)
    }

    override suspend fun markCommitIndexed(commitHash: CommitHash, indexedAtEpochSeconds: Long) =
        withContext(Dispatchers.IO) {
            commits.markCommitIndexed(commitHash.value, indexedAtEpochSeconds)
        }

    override suspend fun isCommitIndexed(commitHash: CommitHash): Boolean = withContext(Dispatchers.IO) {
        commits.isCommitIndexed(commitHash.value).executeAsOne()
    }

    override suspend fun firstSeenFor(blobHash: BlobHash): Long? = withContext(Dispatchers.IO) {
        commits.firstSeenForBlob(blobHash.value).executeAsOne().first_seen
    }

    override suspend fun upsertEmbedConfig(config: EmbedConfigMeta) = withContext(Dispatchers.IO) {
        embedConfig.upsertEmbedConfig(
            model = config.model,
            provider = config.provider,
            dimensions = config.dimensions.toLong(),
            chunker = config.chunker,
            last_used_at = config.lastUsedAtEpochSeconds,
        )
    }

    override suspend fun embedConfigFor(model: String): EmbedConfigMeta? = withContext(Dispatchers.IO) {
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
}
