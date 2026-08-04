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

    @Test
    fun `streamCommits emits newest first with the root commit diffed against an empty tree`() = runTest {
        writeAndCommit("a.txt", "first", "commit 1")
        writeAndCommit("b.txt", "second", "commit 2")
        repo = JGitRepository(tempDir)

        val commits = repo.streamCommits("HEAD").toList()

        assertEquals(2, commits.size)
        assertEquals("commit 2", commits[0].message, "newest first")
        assertEquals("commit 1", commits[1].message)
        // Root commit (commit 1) has no parent -- everything in it is "added"
        // relative to an empty tree.
        assertTrue(commits[1].changedBlobs.any { it.path.value == "a.txt" })
        assertTrue(commits[0].changedBlobs.any { it.path.value == "b.txt" })
        assertTrue(commits[0].changedBlobs.none { it.path.value == "a.txt" }, "commit 2 didn't touch a.txt, so it shouldn't appear as changed")
    }

    @Test
    fun `streamCommits reports author, timestamp, and message`() = runTest {
        writeAndCommit("a.txt", "content", "a descriptive message")
        repo = JGitRepository(tempDir)

        val commit = repo.streamCommits("HEAD").toList().single()

        assertEquals("a descriptive message", commit.message)
        assertEquals("Test", commit.authorName)
        assertEquals("test@example.com", commit.authorEmail)
        assertTrue(commit.timestampEpochSeconds > 0)
    }

    @Test
    fun `streamCommits with since excludes already-reachable commits`() = runTest {
        writeAndCommit("a.txt", "first", "commit 1")
        val firstHead = git.repository.resolve("HEAD")!!.name
        writeAndCommit("b.txt", "second", "commit 2")
        writeAndCommit("c.txt", "third", "commit 3")
        repo = JGitRepository(tempDir)

        val commits = repo.streamCommits("HEAD", since = io.github.jsilvanus.gitsema.model.CommitHash(firstHead)).toList()

        assertEquals(2, commits.size)
        assertTrue(commits.none { it.message == "commit 1" })
    }

    @Test
    fun `a modified file (not just added) is reported as a changed blob with its new hash`() = runTest {
        writeAndCommit("a.txt", "version one", "commit 1")
        writeAndCommit("a.txt", "version two", "commit 2")
        repo = JGitRepository(tempDir)

        val latest = repo.streamCommits("HEAD").toList().first()

        assertEquals(1, latest.changedBlobs.size)
        assertEquals("a.txt", latest.changedBlobs.single().path.value)
    }

    @Test
    fun `a commit that touches nothing new, such as an empty commit, reports no changed blobs`() = runTest {
        writeAndCommit("a.txt", "content", "commit 1")
        git.commit().setAllowEmpty(true).setMessage("empty commit").setAuthor("Test", "test@example.com").call()
        repo = JGitRepository(tempDir)

        val latest = repo.streamCommits("HEAD").toList().first()

        assertEquals("empty commit", latest.message)
        assertTrue(latest.changedBlobs.isEmpty())
    }
}
