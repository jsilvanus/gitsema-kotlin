package io.github.jsilvanus.gitsema.chunking

import io.github.jsilvanus.gitsema.model.Chunk
import io.github.jsilvanus.gitsema.model.ChunkStrategy

/**
 * The default strategy (`--chunker file` in gitsema-TS): the whole blob is one
 * chunk. No boundary logic — matches `fileChunker.ts` exactly (kotlin-port.md
 * §2.1): split on `\n`, one [Chunk] spanning line 1 to the last line.
 *
 * This is why the speed/balanced profiles default to it (kotlin-port.md §3.2) —
 * it's the cheapest possible embedding unit, one call per blob.
 */
class FileChunker : Chunker {
    override fun chunk(content: String, path: String): List<Chunk> {
        val lineCount = content.count { it == '\n' } + 1
        return listOf(
            Chunk(
                content = content,
                startLine = 1,
                endLine = lineCount,
                strategy = ChunkStrategy.FILE,
            ),
        )
    }
}
