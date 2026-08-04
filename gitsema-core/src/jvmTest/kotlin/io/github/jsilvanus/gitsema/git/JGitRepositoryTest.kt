package io.github.jsilvanus.gitsema.git

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [JGitRepository] against a real (throwaway, temp-dir) Git repository
 * built with JGit's own porcelain API -- no `git` binary involved, matching the
 * whole point of this seam (kotlin-port.md §7.3, porting brief seam #3).
 */
class JGitRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var git: Git
    private lateinit var repo: JGitRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("gitsema-jgit-test").toFile()
        git = Git.init().setDirectory(tempDir).call()
        // Pin repo-local config so it can't inherit an ambient global
        // ~/.gitconfig this test doesn't control -- e.g. `gpg.format=ssh`,
        // which this JGit version's config parser rejects outright
        // (IllegalArgumentException before CommitCommand even checks whether
        // signing was requested). Unrelated to anything this port changes.
        git.repository.config.setString("gpg", null, "format", "openpgp")
        git.repository.config.setBoolean("commit", null, "gpgsign", false)
        git.repository.config.save()
    }

    @After
    fun tearDown() {
        repo.close()
        git.close()
        tempDir.deleteRecursively()
    }

    private fun writeAndCommit(path: String, content: String, message: String) {
        val file = File(tempDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        git.add().addFilepattern(path).call()
        git.commit().setMessage(message).setAuthor("Test", "test@example.com").call()
    }

    @Test
    fun `resolveRef resolves HEAD after a commit`() = runTest {
        writeAndCommit("a.txt", "hello", "initial commit")
        repo = JGitRepository(tempDir)

        val head = repo.resolveRef("HEAD")

        assertNotNull(head)
        assertEquals(40, head.value.length)
    }

    @Test
    fun `resolveRef returns null for an unresolvable ref`() = runTest {
        writeAndCommit("a.txt", "hello", "initial commit")
        repo = JGitRepository(tempDir)

        assertNull(repo.resolveRef("refs/heads/does-not-exist"))
    }

    @Test
    fun `streamBlobs visits each unique blob object exactly once per walk`() = runTest {
        // Verified empirically against real `git rev-list --objects` (not assumed):
        // when two paths share identical content, the walk emits exactly ONE
        // (hash, path) entry for that shared object -- the second path is never
        // re-emitted, because rev-list/ObjectWalk mark objects seen by OID, not
        // by (commit, path). This is why gitsema-TS's "one blob, many paths"
        // paths-table invariant (kotlin-port.md §1.1) is built up across
        // *repeated* walks over the index's lifetime, never from a single walk.
        // ObjectWalk is the correct, faithful equivalent of rev-list --objects
        // specifically because it reproduces this one-path-per-object behavior.
        writeAndCommit("a.txt", "same content", "commit 1")
        writeAndCommit("b.txt", "same content", "commit 2") // identical content -> same blob hash as a.txt
        writeAndCommit("c.txt", "different content", "commit 3")
        repo = JGitRepository(tempDir)

        val entries = repo.streamBlobs("HEAD").toList()

        assertEquals(2, entries.size, "the shared blob is visited once, not once per path")
        val distinctHashes = entries.map { it.blobHash }.toSet()
        assertEquals(2, distinctHashes.size, "identical content across two paths must share one blob hash")
        assertTrue(
            entries.any { it.path.value == "a.txt" } || entries.any { it.path.value == "b.txt" },
            "the shared blob surfaces under whichever of its paths the walk reaches first",
        )
        assertTrue(entries.any { it.path.value == "c.txt" })
    }

    @Test
    fun `readBlob returns exact content for a known blob`() = runTest {
        writeAndCommit("a.txt", "hello world", "initial commit")
        repo = JGitRepository(tempDir)
        val entry = repo.streamBlobs("HEAD").toList().single { it.path.value == "a.txt" }

        val bytes = repo.readBlob(entry.blobHash, maxBytes = 1024)

        assertNotNull(bytes)
        assertEquals("hello world", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `readBlob returns null when content exceeds maxBytes, never partially reading it`() = runTest {
        writeAndCommit("big.txt", "x".repeat(1000), "initial commit")
        repo = JGitRepository(tempDir)
        val entry = repo.streamBlobs("HEAD").toList().single { it.path.value == "big.txt" }

        val bytes = repo.readBlob(entry.blobHash, maxBytes = 10)

        assertNull(bytes)
    }

    @Test
    fun `readBlob returns null for an unknown hash rather than throwing`() = runTest {
        writeAndCommit("a.txt", "hello", "initial commit")
        repo = JGitRepository(tempDir)

        val bytes = repo.readBlob(io.github.jsilvanus.gitsema.model.BlobHash("0".repeat(40)), maxBytes = 1024)

        assertNull(bytes)
    }
}
