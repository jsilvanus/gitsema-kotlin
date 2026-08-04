package io.github.jsilvanus.gitsema.chunking

import io.github.jsilvanus.gitsema.model.Chunk
import io.github.jsilvanus.gitsema.model.ChunkStrategy

/**
 * Character-count windowing with line-boundary snapping — no language
 * awareness, matching gitsema-TS's `fixedChunker.ts` exactly (kotlin-port.md
 * §2.2). Defaults (1500/200) have no benchmarked rationale in gitsema-TS
 * either — treat them as a starting point, not a calibrated constant.
 *
 * Used both standalone and as the second/third steps of the context-limit
 * fallback chain (§2.4, windows 1500 then 800) once that's wired — not yet
 * wired into [io.github.jsilvanus.gitsema.indexing.Indexer], which currently
 * has no chunk-level vector storage to hold a fallback chunk's embedding
 * distinctly from its blob's whole-file one. That's a real architecture
 * decision (extend [io.github.jsilvanus.gitsema.storage.VectorStore]'s key
 * model, or a parallel chunk store) deferred rather than rushed.
 */
class FixedChunker(
    windowSize: Int = DEFAULT_WINDOW_SIZE,
    overlap: Int = DEFAULT_OVERLAP,
) : Chunker {
    private val windowSize: Int = windowSize.coerceAtLeast(1)
    private val overlap: Int = overlap.coerceIn(0, (this.windowSize - 1).coerceAtLeast(0))

    override fun chunk(content: String, path: String): List<Chunk> {
        val lines = content.split("\n")
        if (lines.isEmpty()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        var startLine = 1 // 1-indexed, matching gitsema-TS's convention

        while (startLine <= lines.size) {
            // Accumulate lines from startLine until the window fills (or we
            // run out of lines) -- this chunk's boundary always snaps to a
            // line end, never mid-line.
            var charCount = 0
            var endLine = startLine
            var idx = startLine - 1
            while (idx < lines.size) {
                charCount += lines[idx].length + 1 // +1 for the line's trailing '\n'
                endLine = idx + 1
                idx++
                if (charCount >= windowSize) break
            }
            chunks += Chunk(
                content = lines.subList(startLine - 1, endLine).joinToString("\n"),
                startLine = startLine,
                endLine = endLine,
                strategy = ChunkStrategy.FIXED,
            )

            if (endLine >= lines.size) break

            // Find the next window's start: walk forward from THIS window's
            // start (not from where it ended) until cumulative length
            // reaches stepTarget -- the landing line becomes the next
            // window's start, which is what re-includes roughly the last
            // `overlap` characters of this window in the next one.
            val stepTarget = charCount - overlap
            var stepped = 0
            var landingIdx = startLine - 1
            var i = startLine - 1
            while (i < lines.size) {
                stepped += lines[i].length + 1
                landingIdx = i
                if (stepped >= stepTarget) break
                i++
            }
            val nextStart = landingIdx + 1

            // Forward-progress guard: always advance by at least one line,
            // even if overlap arithmetic would otherwise stall on the same
            // start line forever.
            startLine = maxOf(nextStart, startLine + 1)
        }

        return chunks
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 1500
        const val DEFAULT_OVERLAP = 200
    }
}
