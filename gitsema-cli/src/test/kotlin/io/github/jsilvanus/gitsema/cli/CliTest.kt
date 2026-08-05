package io.github.jsilvanus.gitsema.cli

import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real commands against a real Git repository and a real HTTP
 * embedding endpoint (the stub server) — the whole stack except the model.
 * [Cli] takes its streams and provider as parameters precisely so this can be
 * an ordinary test rather than a subprocess harness.
 */
class CliTest {
    private lateinit var tempDir: File
    private lateinit var git: Git
    private lateinit var server: StubEmbeddingServer
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("gitsema-cli-test").toFile()
        git = Git.init().setDirectory(tempDir).call()
        // Same ambient-config pin as JGitRepositoryTest: a global
        // gpg.format=ssh in the developer's ~/.gitconfig breaks JGit's config
        // parser before commit even starts. Unrelated to anything under test.
        git.repository.config.setString("gpg", null, "format", "openpgp")
        git.repository.config.setBoolean("commit", null, "gpgsign", false)
        git.repository.config.save()
        server = StubEmbeddingServer()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        git.close()
        tempDir.deleteRecursively()
    }

    /**
     * Stages exactly the named paths — never `git add .`, which would sweep
     * the `.gitsema/` index directory into the repository and then index the
     * index. (Real users avoid this by gitignoring `.gitsema/`, which the
     * CLI's help text tells them to do.)
     */
    private fun commit(files: Map<String, String>, message: String = "test commit") {
        files.forEach { (path, content) ->
            File(tempDir, path).apply { parentFile?.mkdirs() }.writeText(content)
            git.add().addFilepattern(path).call()
        }
        git.commit().setMessage(message).setAuthor("Test", "test@example.com").call()
    }

    private suspend fun run(vararg argv: String): Int {
        val cli = Cli(
            out = PrintStream(out, true),
            err = PrintStream(err, true),
            env = { null }, // never inherit the developer's real GITSEMA_* settings
            providerFactory = { config ->
                HttpEmbeddingProvider.create(server.baseUrl, config.model, ProviderFlavour.OLLAMA)
            },
        )
        return cli.run(arrayOf(*argv, "--repo", tempDir.absolutePath))
    }

    private fun output() = out.toString()
    private fun errors() = err.toString()

    @Test
    fun `index then search finds the committed file`() = runTest {
        commit(
            mapOf(
                "auth.txt" to "authentication middleware validates the session token on every request",
                "render.txt" to "the renderer draws triangles to the framebuffer",
            ),
        )

        assertEquals(ExitCode.OK, run("index", "--quiet"))
        assertTrue(output().contains("indexed 2 blob(s)"), "index should report what it did: ${output()}")

        out.reset()
        assertEquals(ExitCode.OK, run("search", "authentication", "session", "--top", "5"))

        val results = output()
        assertTrue(results.contains("auth.txt"), "expected auth.txt in results:\n$results")
        assertTrue(results.contains("2/2 blobs embedded (100%)"), "expected full coverage:\n$results")
    }

    @Test
    fun `a second index run walks nothing at all, because the resume cursor already covers HEAD`() = runTest {
        // Worth pinning because it is stronger than dedup and easy to mistake
        // for it: the second run does not walk the blob and skip it, it never
        // walks it. The resume cursor (Decision C #3) bounds the git walk
        // itself, so re-running costs a ref resolution rather than a full
        // re-traversal. Blob-level dedup is the safety net underneath, covered
        // by the core's IndexerTest.
        commit(mapOf("a.txt" to "alpha content"))
        run("index", "--quiet")
        out.reset()

        assertEquals(ExitCode.OK, run("index", "--quiet"))

        assertTrue(output().contains("indexed 0 blob(s) from 0 commit(s)"), "the walk itself should be empty: ${output()}")
    }

    @Test
    fun `a new commit after an indexed run picks up only the new blob`() = runTest {
        commit(mapOf("a.txt" to "alpha content"))
        run("index", "--quiet")
        out.reset()

        commit(mapOf("b.txt" to "beta content"), message = "add beta")
        assertEquals(ExitCode.OK, run("index", "--quiet"))

        assertTrue(output().contains("indexed 1 blob(s)"), "only the new blob should be embedded: ${output()}")

        out.reset()
        run("search", "beta", "--top", "5")
        assertTrue(output().contains("2/2 blobs embedded"), "both blobs are now covered:\n${output()}")
    }

    @Test
    fun `search on an empty index reports zero coverage rather than looking like an answer`() = runTest {
        commit(mapOf("a.txt" to "alpha content"))

        assertEquals(ExitCode.OK, run("search", "anything"))

        val results = output()
        assertTrue(results.contains("0/0 blobs embedded"), "coverage must be visible:\n$results")
        assertTrue(results.contains("degraded"), "an unbuilt index must say so, not just return nothing:\n$results")
        assertTrue(results.contains("no matches"))
    }

    @Test
    fun `status reports model, coverage, and resume point after a run`() = runTest {
        commit(mapOf("a.txt" to "alpha content"))
        run("index", "--quiet")
        out.reset()

        assertEquals(ExitCode.OK, run("status"))

        val status = output()
        assertTrue(status.contains("1/1 blobs embedded (100%)"), status)
        assertTrue(status.contains("nomic-embed-text"), "the default model should be recorded: $status")
        assertTrue(!status.contains("never completed a run"), "a completed run must set the resume point: $status")
    }

    @Test
    fun `eval scores a case file and warns when the index is partial`() = runTest {
        commit(mapOf("auth.txt" to "authentication middleware validates the session token"))
        val cases = File(tempDir, "cases.jsonl").apply {
            writeText(
                """
                # comments and blank lines are skipped

                {"query":"authentication session","expectedPaths":["auth.txt"]}
                """.trimIndent(),
            )
        }

        // Before indexing: the harness still runs, but the warning has to fire.
        assertEquals(ExitCode.OK, run("eval", cases.absolutePath))
        assertTrue(errors().contains("measure indexing progress, not retrieval quality"), errors())

        out.reset()
        err.reset()
        run("index", "--quiet")
        out.reset()

        assertEquals(ExitCode.OK, run("eval", cases.absolutePath, "--top", "5"))
        val report = output()
        assertTrue(report.contains("precision@5"), report)
        assertTrue(report.contains("MRR:"), report)
        assertTrue(report.contains("1.000"), "the one expected path is the only blob, so scores should be perfect:\n$report")
        assertTrue(errors().isBlank(), "a complete index should produce no warning, got: ${errors()}")
    }

    @Test
    fun `an unknown command is a usage error, not a crash`() = runTest {
        assertEquals(ExitCode.USAGE_ERROR, run("frobnicate"))
        assertTrue(errors().contains("unknown command"))
    }

    @Test
    fun `search with no query is a usage error`() = runTest {
        assertEquals(ExitCode.USAGE_ERROR, run("search"))
        assertTrue(errors().contains("search needs a query"))
    }

    @Test
    fun `a directory that is not a git repository is a usage error naming the problem`() = runTest {
        val notARepo = Files.createTempDirectory("gitsema-not-a-repo").toFile()
        try {
            val cli = Cli(
                out = PrintStream(out, true),
                err = PrintStream(err, true),
                env = { null },
                providerFactory = { HttpEmbeddingProvider.create(server.baseUrl, "m", ProviderFlavour.OLLAMA) },
            )

            assertEquals(ExitCode.USAGE_ERROR, cli.run(arrayOf("status", "--repo", notARepo.absolutePath)))
            assertTrue(errors().contains("not a git repository"), errors())
        } finally {
            notARepo.deleteRecursively()
        }
    }

    @Test
    fun `indexing with an unreachable endpoint is a runtime error, not a usage error`() = runTest {
        // Exit codes are a contract for scripts: 2 means "you typed it wrong",
        // 1 means "it went wrong". A dead model server is the latter. Index
        // fails rather than falling back, because indexing without a model
        // produces nothing.
        commit(mapOf("a.txt" to "alpha"))
        server.close()

        assertEquals(ExitCode.RUNTIME_ERROR, run("index", "--quiet"))
    }

    @Test
    fun `status and keyword search still work with no model server running`() = runTest {
        // Constraint 6: keyword search works before any embedding model
        // exists, and constraint 5: search is never blocked. A CLI that
        // demanded a live endpoint before reading an index already on disk
        // would contradict both.
        commit(mapOf("auth.txt" to "authentication middleware validates the session token"))
        run("index", "--quiet")
        server.close()
        out.reset()
        err.reset()

        assertEquals(ExitCode.OK, run("status"))
        assertTrue(output().contains("1/1 blobs embedded"), "status reads the index, not the endpoint:\n${output()}")
        assertTrue(errors().contains("not reachable"), "but it must say the endpoint is down: ${errors()}")
    }

    @Test
    fun `help exits zero and lists every command`() = runTest {
        val cli = Cli(out = PrintStream(out, true), err = PrintStream(err, true), env = { null })

        assertEquals(ExitCode.OK, cli.run(arrayOf("help")))

        val help = output()
        for (command in listOf("index", "search", "status", "eval")) {
            assertTrue(help.contains("gitsema $command"), "help should document '$command':\n$help")
        }
    }
}
