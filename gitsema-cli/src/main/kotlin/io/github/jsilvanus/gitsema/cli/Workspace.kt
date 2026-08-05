package io.github.jsilvanus.gitsema.cli

import app.cash.sqldelight.db.SqlDriver
import io.github.jsilvanus.gitsema.GitsemaSemanticIndex
import io.github.jsilvanus.gitsema.SemanticIndex
import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.git.JGitRepository
import io.github.jsilvanus.gitsema.storage.FlatFileVectorStore
import io.github.jsilvanus.gitsema.storage.SqliteFtsStore
import io.github.jsilvanus.gitsema.storage.SqliteMetadataStore
import io.github.jsilvanus.gitsema.storage.createSqlDriver
import java.io.File

/**
 * Opens (creating if needed) the on-disk index for a repository and wires the
 * library's four seams together.
 *
 * Layout matches gitsema-TS — `.gitsema/index.db` plus `.gitsema/vectors/`
 * beside the repository — so the two implementations' working directories are
 * recognisably the same thing, and `.gitsema/` remains the one path to
 * gitignore. Note this is the *desktop* convention; on Android the host picks
 * the location (aidos D21/D29 put it in `.aidos/index/`), which is why it is
 * decided here in the CLI rather than inside the library.
 */
class Workspace private constructor(
    val repoDir: File,
    private val driver: SqlDriver,
    private val repository: JGitRepository,
    val index: SemanticIndex,
) : AutoCloseable {

    override fun close() {
        repository.close()
        driver.close()
    }

    companion object {
        fun open(repoDir: File, provider: EmbeddingProvider, concurrency: Int = 4, batchSize: Int = 1): Workspace {
            require(File(repoDir, ".git").exists()) {
                "${repoDir.absolutePath} is not a git repository (no .git directory)"
            }
            val gitsemaDir = File(repoDir, ".gitsema").apply { mkdirs() }
            val driver = createSqlDriver(File(gitsemaDir, "index.db").absolutePath)
            val database = GitsemaDatabase(driver)
            val repository = JGitRepository(repoDir)

            return Workspace(
                repoDir = repoDir,
                driver = driver,
                repository = repository,
                index = GitsemaSemanticIndex(
                    repository = repository,
                    provider = provider,
                    metadataStore = SqliteMetadataStore(database),
                    vectorStore = FlatFileVectorStore(database, File(gitsemaDir, "vectors")),
                    ftsStore = SqliteFtsStore(database),
                    concurrency = concurrency,
                    batchSize = batchSize,
                ),
            )
        }
    }
}
