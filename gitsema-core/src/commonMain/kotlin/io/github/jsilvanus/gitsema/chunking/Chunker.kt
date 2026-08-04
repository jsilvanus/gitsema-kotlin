package io.github.jsilvanus.gitsema.chunking

import io.github.jsilvanus.gitsema.model.Chunk

/**
 * A pure, stateless splitter: content in, [Chunk]s out. No I/O, no suspension —
 * matches gitsema-TS's chunkers exactly (kotlin-port.md §2, §8: "pure, stateless,
 * no native deps... port verbatim").
 */
interface Chunker {
    fun chunk(content: String, path: String): List<Chunk>
}
