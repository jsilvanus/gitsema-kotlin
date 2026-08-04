package io.github.jsilvanus.gitsema.chunking

import io.github.jsilvanus.gitsema.model.ChunkStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deliverable #3 (docs/design/kotlin-port.md): "chunking deterministic for
 * fixed input." FileChunker has no internal state and no randomness, so
 * determinism here means: same input -> same output, every time, and the
 * output matches fileChunker.ts's documented contract exactly (kotlin-port.md
 * §2.1) — one chunk, whole file, line 1 to the last line.
 */
class FileChunkerTest {
    private val chunker = FileChunker()

    @Test
    fun `whole file becomes exactly one chunk`() {
        val content = "line one\nline two\nline three"
        val chunks = chunker.chunk(content, "src/example.kt")

        assertEquals(1, chunks.size)
        assertEquals(content, chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(3, chunks[0].endLine)
        assertEquals(ChunkStrategy.FILE, chunks[0].strategy)
    }

    @Test
    fun `single line file spans line 1 to line 1`() {
        val chunks = chunker.chunk("just one line, no trailing newline", "a.txt")

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
    }

    @Test
    fun `trailing newline counts as an extra (empty) line, matching fileChunker-ts`() {
        // fileChunker.ts splits on '\n' with String#split, which yields a
        // trailing empty-string element for a trailing newline -- one more
        // "line" than a naive line-count would suggest. Preserve that exactly
        // rather than "fixing" it, since downstream span math assumes it.
        val chunks = chunker.chunk("a\nb\n", "a.txt")

        assertEquals(3, chunks[0].endLine)
    }

    @Test
    fun `empty content is still exactly one chunk`() {
        val chunks = chunker.chunk("", "empty.txt")

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
        assertEquals("", chunks[0].content)
    }

    @Test
    fun `repeated calls on identical input produce identical output`() {
        val content = "package foo\n\nfun bar() = 42\n"
        val first = chunker.chunk(content, "foo.kt")
        val second = chunker.chunk(content, "foo.kt")

        assertEquals(first, second)
    }
}
