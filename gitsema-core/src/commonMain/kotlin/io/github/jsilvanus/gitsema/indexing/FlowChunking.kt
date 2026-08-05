package io.github.jsilvanus.gitsema.indexing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Groups a [Flow] into fixed-size lists, emitting each window as soon as it
 * fills and the remainder (if any) at completion.
 *
 * This is what lets the indexer batch without collecting: the alternative,
 * `flow.toList().chunked(n)`, holds the entire upstream in memory before the
 * first batch is processed. Peak memory here is one window, whatever the
 * history's length — the difference between honouring gitsema's "never buffer
 * entire repo history in memory" constraint and merely claiming it
 * (kotlin-port.md Decision C #2, which flags the TS original for exactly this
 * and says the port should not replicate it uncritically).
 *
 * Backpressure is preserved: the upstream is only pulled as fast as the
 * collector consumes windows, so a slow embedding pass throttles the git walk
 * rather than racing ahead of it.
 *
 * kotlinx.coroutines has no such operator (`Flow.chunked` remains unshipped),
 * hence a local one rather than a dependency.
 */
internal fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> {
    require(size > 0) { "chunk size must be positive, was $size" }
    return flow {
        val window = ArrayList<T>(size)
        collect { item ->
            window += item
            if (window.size == size) {
                // Copy rather than emit-and-clear: the collector may hold the
                // list past this emission, and clearing it underneath would
                // hand it a silently emptied batch.
                emit(window.toList())
                window.clear()
            }
        }
        if (window.isNotEmpty()) emit(window.toList())
    }
}
