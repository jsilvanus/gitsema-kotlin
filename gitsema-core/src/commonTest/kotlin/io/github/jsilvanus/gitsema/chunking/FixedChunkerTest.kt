package io.github.jsilvanus.gitsema.chunking

import io.github.jsilvanus.gitsema.model.ChunkStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedChunkerTest {
    @Test
    fun `content smaller than the window becomes a single chunk`() {
        val chunker = FixedChunker(windowSize = 1500, overlap = 200)
        val content = "line one\nline two\nline three"

        val chunks = chunker.chunk(content, "a.txt")

        assertEquals(1, chunks.size)
        assertEquals(content, chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(3, chunks[0].endLine)
        assertEquals(ChunkStrategy.FIXED, chunks[0].strategy)
    }

    @Test
    fun `content larger than the window splits into multiple chunks with overlap`() {
        // 50 lines of 20 chars each (~21 bytes with newline) -> ~1050 chars
        // total, comfortably requiring a split at windowSize=200.
        val lines = (1..50).map { "line number $it here!" } // ~22 chars each
        val content = lines.joinToString("\n")
        val chunker = FixedChunker(windowSize = 200, overlap = 40)

        val chunks = chunker.chunk(content, "a.txt")

        assertTrue(chunks.size > 1, "expected multiple chunks, got ${chunks.size}")
        // Every chunk boundary must land on a real line (1-indexed, within range).
        for (c in chunks) {
            assertTrue(c.startLine in 1..lines.size)
            assertTrue(c.endLine in c.startLine..lines.size)
        }
        // Consecutive chunks must overlap (next chunk's start <= previous chunk's end),
        // and must always make forward progress (next start > previous start).
        for (i in 0 until chunks.size - 1) {
            assertTrue(chunks[i + 1].startLine > chunks[i].startLine, "must always advance")
            assertTrue(chunks[i + 1].startLine <= chunks[i].endLine, "consecutive windows should overlap")
        }
        // The last chunk must reach the end of the file.
        assertEquals(lines.size, chunks.last().endLine)
    }

    @Test
    fun `every character in the original content is covered by at least one chunk`() {
        val lines = (1..30).map { "x".repeat(15) }
        val content = lines.joinToString("\n")
        val chunker = FixedChunker(windowSize = 100, overlap = 20)

        val chunks = chunker.chunk(content, "a.txt")

        val coveredLines = mutableSetOf<Int>()
        for (c in chunks) {
            (c.startLine..c.endLine).forEach { coveredLines.add(it) }
        }
        assertEquals((1..lines.size).toSet(), coveredLines, "no line should be skipped entirely")
    }

    @Test
    fun `overlap is clamped below windowSize to guarantee forward progress`() {
        // overlap >= windowSize would otherwise be nonsensical (a window
        // that never advances) -- must not hang or produce a broken result.
        val chunker = FixedChunker(windowSize = 10, overlap = 999)
        val content = (1..20).joinToString("\n") { "a".repeat(10) }

        val chunks = chunker.chunk(content, "a.txt")

        assertTrue(chunks.isNotEmpty())
        assertEquals(20, chunks.last().endLine)
    }

    @Test
    fun `empty content is a single empty chunk`() {
        val chunker = FixedChunker()

        val chunks = chunker.chunk("", "empty.txt")

        assertEquals(1, chunks.size)
        assertEquals("", chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
    }

    @Test
    fun `chunking is deterministic for the same input`() {
        val content = (1..100).joinToString("\n") { "content line $it with some extra padding text" }
        val chunker = FixedChunker(windowSize = 300, overlap = 50)

        val first = chunker.chunk(content, "a.txt")
        val second = chunker.chunk(content, "a.txt")

        assertEquals(first, second)
    }

    @Test
    fun `default constructor uses windowSize 1500 and overlap 200`() {
        assertEquals(1500, FixedChunker.DEFAULT_WINDOW_SIZE)
        assertEquals(200, FixedChunker.DEFAULT_OVERLAP)
    }
}
