package io.github.jsilvanus.gitsema.embedding

import io.github.jsilvanus.gitsema.testutil.FakeEmbeddingProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbeddingOrchestratorTest {
    @Test
    fun `embeds every request successfully when the provider has no failures`() = runTest {
        val orchestrator = EmbeddingOrchestrator(FakeEmbeddingProvider(), concurrency = 4, batchSize = 1)
        val requests = (1..10).map { EmbedRequest(id = "item-$it", text = "text number $it") }

        val outcomes = orchestrator.embedAll(requests)

        assertEquals(10, outcomes.size)
        assertTrue(outcomes.all { it is EmbedOutcome.Success })
        assertEquals(requests.map { it.id }.toSet(), outcomes.map { it.id }.toSet())
    }

    @Test
    fun `batching reduces the number of provider calls`() = runTest {
        val provider = FakeEmbeddingProvider()
        val orchestrator = EmbeddingOrchestrator(provider, concurrency = 1, batchSize = 5)
        val requests = (1..20).map { EmbedRequest(id = "item-$it", text = "text $it") }

        orchestrator.embedAll(requests)

        // 20 requests batched 5-at-a-time is 4 provider calls, not 20.
        assertEquals(4, provider.callCount)
    }

    @Test
    fun `one oversized item in a batch does not sink the rest of that batch`() = runTest {
        // The fake provider throws ContextLengthExceededException for any
        // text longer than maxTextLength -- when that happens inside a
        // batched embed() call, the whole call throws, and the orchestrator
        // must fall back to embedding this batch's items one at a time so
        // the other (fine) items in the batch still succeed.
        val provider = FakeEmbeddingProvider(maxTextLength = 20)
        val orchestrator = EmbeddingOrchestrator(provider, concurrency = 1, batchSize = 4)
        val requests = listOf(
            EmbedRequest("ok-1", "short text"),
            EmbedRequest("too-long", "this text is deliberately far too long for the limit"),
            EmbedRequest("ok-2", "also short"),
            EmbedRequest("ok-3", "short again"),
        )

        val outcomes = orchestrator.embedAll(requests)

        val byId = outcomes.associateBy { it.id }
        assertIs<EmbedOutcome.Success>(byId.getValue("ok-1"))
        assertIs<EmbedOutcome.Success>(byId.getValue("ok-2"))
        assertIs<EmbedOutcome.Success>(byId.getValue("ok-3"))
        val failed = assertIs<EmbedOutcome.Failed>(byId.getValue("too-long"))
        assertIs<ContextLengthExceededException>(failed.cause)
    }

    @Test
    fun `results preserve the correct vector per request even when batched`() = runTest {
        val orchestrator = EmbeddingOrchestrator(FakeEmbeddingProvider(), concurrency = 2, batchSize = 3)
        val requests = listOf(
            EmbedRequest("a", "alpha content"),
            EmbedRequest("b", "beta content"),
            EmbedRequest("c", "gamma content"),
        )

        val outcomes = orchestrator.embedAll(requests).associateBy { it.id }

        // Same text embedded directly should match what came back for that id
        // (the fake provider is deterministic), proving no cross-item mixup
        // happened during batching/unbatching.
        val direct = FakeEmbeddingProvider().embed(listOf("alpha content", "beta content", "gamma content"))
        assertEquals(direct[0].toList(), (outcomes.getValue("a") as EmbedOutcome.Success).vector.toList())
        assertEquals(direct[1].toList(), (outcomes.getValue("b") as EmbedOutcome.Success).vector.toList())
        assertEquals(direct[2].toList(), (outcomes.getValue("c") as EmbedOutcome.Success).vector.toList())
    }
}
