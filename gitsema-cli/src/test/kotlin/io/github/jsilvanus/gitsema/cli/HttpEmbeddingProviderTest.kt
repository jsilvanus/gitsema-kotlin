package io.github.jsilvanus.gitsema.cli

import io.github.jsilvanus.gitsema.embedding.ContextLengthExceededException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpEmbeddingProviderTest {
    private val server = StubEmbeddingServer(dimensions = 8)

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `ollama flavour embeds each text and preserves order`() = runTest {
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OLLAMA)
        server.received.clear()

        val vectors = provider.embed(listOf("alpha", "beta", "gamma"))

        assertEquals(3, vectors.size)
        assertEquals(listOf("alpha", "beta", "gamma"), server.received)
        assertTrue(vectors.all { it.size == 8 })
    }

    @Test
    fun `openai flavour sends one batched request`() = runTest {
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OPENAI)
        server.received.clear()

        val vectors = provider.embed(listOf("alpha", "beta", "gamma"))

        assertEquals(3, vectors.size)
        assertEquals(listOf("alpha", "beta", "gamma"), server.received)
    }

    @Test
    fun `openai results are re-sorted by index rather than trusting array order`() = runTest {
        // The API documents `index`; array order is not promised. A provider
        // that trusted position would return every vector attached to the
        // wrong text here — silently, with no error anywhere.
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OPENAI)
        val ordered = provider.embed(listOf("alpha", "beta", "gamma"))

        server.shuffleOpenAiResponse = true
        val shuffled = provider.embed(listOf("alpha", "beta", "gamma"))

        assertContentEquals(ordered[0], shuffled[0])
        assertContentEquals(ordered[1], shuffled[1])
        assertContentEquals(ordered[2], shuffled[2])
    }

    @Test
    fun `the same text always embeds to the same vector`() = runTest {
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OLLAMA)

        assertContentEquals(provider.embed(listOf("stable")).single(), provider.embed(listOf("stable")).single())
    }

    @Test
    fun `dimensions are measured by probing, not asserted by the caller`() = runTest {
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OLLAMA)

        assertEquals(8, provider.dimensions)
    }

    @Test
    fun `a context-length rejection becomes the typed exception the fallback chain reacts to`() = runTest {
        // Decision C #4: the indexer must be able to tell "this file is too
        // long, re-chunk it smaller" from "the endpoint is broken, give up".
        // Translating the wire error is this provider's job, once, here.
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OLLAMA)
        server.failWith = 400 to """{"error":"input length 9000 exceeds the context window"}"""

        val thrown = assertFailsWith<ContextLengthExceededException> { provider.embed(listOf("x".repeat(9000))) }

        assertEquals(9000, thrown.textLength)
    }

    @Test
    fun `an unrelated failure stays a protocol error and is not mistaken for an oversized input`() = runTest {
        // The distinction matters in the other direction too: treating a dead
        // endpoint as "too long" would send the indexer re-chunking forever
        // against a server that is simply down.
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OLLAMA)
        server.failWith = 503 to """{"error":"model is loading"}"""

        val thrown = assertFailsWith<EmbeddingProtocolException> { provider.embed(listOf("anything")) }

        assertTrue(thrown.message!!.contains("503"), "the message should carry the status: ${thrown.message}")
    }

    @Test
    fun `an empty batch makes no request at all`() = runTest {
        val provider = HttpEmbeddingProvider.create(server.baseUrl, "test-model", ProviderFlavour.OPENAI)
        server.received.clear()
        server.failWith = 500 to "should never be called"

        assertEquals(emptyList(), provider.embed(emptyList()))
        assertTrue(server.received.isEmpty())
    }
}
