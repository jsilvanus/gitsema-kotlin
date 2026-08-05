package io.github.jsilvanus.gitsema.indexing

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlowChunkingTest {
    @Test
    fun `emits full windows and a partial remainder`() = runTest {
        val windows = (1..7).asFlow().chunked(3).toList()

        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), windows)
    }

    @Test
    fun `an exact multiple emits no trailing empty window`() = runTest {
        val windows = (1..6).asFlow().chunked(3).toList()

        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), windows)
    }

    @Test
    fun `an empty upstream emits nothing`() = runTest {
        assertEquals(emptyList(), (1..0).asFlow().chunked(3).toList())
    }

    @Test
    fun `the upstream is not drained before the first window is delivered`() = runTest {
        // The whole point of this operator over `toList().chunked(n)`. If the
        // upstream were drained first, `emitted` would already be 10 when the
        // first window arrived.
        var emitted = 0
        val emittedWhenFirstWindowSeen = mutableListOf<Int>()

        val upstream = flow {
            repeat(10) {
                emitted++
                emit(it)
            }
        }

        upstream.chunked(3).collect { window ->
            if (emittedWhenFirstWindowSeen.isEmpty()) emittedWhenFirstWindowSeen += emitted
            assertTrue(window.isNotEmpty())
        }

        assertEquals(listOf(3), emittedWhenFirstWindowSeen, "the first window must arrive after exactly 3 upstream emissions")
    }

    @Test
    fun `a delivered window is not mutated by subsequent emissions`() = runTest {
        // The buffer is reused across windows; handing the collector the live
        // buffer would silently empty a batch it is still holding.
        val held = mutableListOf<List<Int>>()

        (1..6).asFlow().chunked(2).collect { held += it }

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), held)
    }

    @Test
    fun `a non-positive window size is rejected`() {
        assertFailsWith<IllegalArgumentException> { (1..3).asFlow().chunked(0) }
    }

    private fun IntRange.asFlow() = flow { for (i in this@asFlow) emit(i) }
}
