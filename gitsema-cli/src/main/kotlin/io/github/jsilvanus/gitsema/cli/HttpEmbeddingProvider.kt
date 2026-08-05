package io.github.jsilvanus.gitsema.cli

import io.github.jsilvanus.gitsema.embedding.ContextLengthExceededException
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Which wire protocol an endpoint speaks. Mirrors gitsema-TS's `GITSEMA_PROVIDER` values. */
enum class ProviderFlavour { OLLAMA, OPENAI }

/**
 * The `EmbeddingProvider` the library refuses to contain.
 *
 * gitsema-core never loads or calls a model — the seam exists so the host
 * supplies one (kotlin-port.md §3.1). On Android that host is Aidos, handing
 * over whatever admission-queued on-device path it runs. On the desktop it is
 * this: an HTTP client for Ollama or any OpenAI-compatible endpoint, living in
 * the CLI module rather than the library precisely so the library keeps no
 * network dependency.
 *
 * **Where the error sniffing went.** Decision C #4 replaced gitsema-TS's regex
 * matching on provider error *text* with a typed
 * [ContextLengthExceededException] — but something still has to translate a
 * wire error into it, and that is this class's job. Doing it here, once, at
 * the boundary that actually knows the protocol, is the whole point: the
 * indexer's fallback chain reacts to a type, and each provider owns the
 * translation for its own endpoint rather than the library guessing at every
 * provider's phrasing.
 */
class HttpEmbeddingProvider(
    private val baseUrl: String,
    override val modelId: String,
    override val dimensions: Int,
    private val flavour: ProviderFlavour,
    private val apiKey: String? = null,
    private val client: HttpClient = defaultClient(),
) : EmbeddingProvider {

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return when (flavour) {
            // Ollama's /api/embeddings takes one prompt per call, so a batch
            // is N calls. They run sequentially here on purpose: the
            // EmbeddingOrchestrator upstream already owns concurrency, and a
            // provider that fans out on its own would silently multiply the
            // limit the host set.
            ProviderFlavour.OLLAMA -> texts.map { embedOneOllama(it) }
            ProviderFlavour.OPENAI -> embedBatchOpenAi(texts)
        }
    }

    private suspend fun embedOneOllama(text: String): FloatArray {
        val body = buildJsonObject {
            put("model", modelId)
            put("prompt", text)
        }
        val response = post("$baseUrl/api/embeddings", body, text.length)
        return response["embedding"]?.jsonArray?.toFloatArray()
            ?: throw EmbeddingProtocolException("Ollama response had no 'embedding' field: ${response.truncated()}")
    }

    private suspend fun embedBatchOpenAi(texts: List<String>): List<FloatArray> {
        val body = buildJsonObject {
            put("model", modelId)
            putJsonArray("input") { texts.forEach { add(it) } }
        }
        val response = post("$baseUrl/v1/embeddings", body, texts.sumOf { it.length })
        val data = response["data"]?.jsonArray
            ?: throw EmbeddingProtocolException("OpenAI-compatible response had no 'data' field: ${response.truncated()}")

        // Order is the contract (EmbeddingProvider.embed: "result[i]
        // corresponds to texts[i]"), and the API documents `index` rather than
        // promising array order. Sort by it instead of trusting position.
        val byIndex = data.mapIndexed { position, element ->
            val obj = element.jsonObject
            val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: position
            val vector = obj["embedding"]?.jsonArray?.toFloatArray()
                ?: throw EmbeddingProtocolException("response entry $position had no 'embedding'")
            index to vector
        }.sortedBy { it.first }.map { it.second }

        if (byIndex.size != texts.size) {
            throw EmbeddingProtocolException("asked for ${texts.size} embeddings, got ${byIndex.size}")
        }
        return byIndex
    }

    private suspend fun post(url: String, body: JsonObject, requestTextLength: Int): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            apiKey?.let { builder.header("Authorization", "Bearer $it") }

            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw translateError(response.statusCode(), response.body().orEmpty(), requestTextLength)
            }
            JSON.parseToJsonElement(response.body()).jsonObject
        }

    /**
     * Maps a failed HTTP response to an exception the indexer can act on.
     *
     * A context-length overflow has to become [ContextLengthExceededException]
     * or the fallback chain (§2.4) cannot distinguish "this file is too long"
     * from "the endpoint is down" — one should be re-chunked smaller, the
     * other should not. Endpoints signal it inconsistently, so this matches on
     * the message: that inexactness is real, and confining it to one method in
     * one provider is what the typed exception bought.
     */
    private fun translateError(status: Int, body: String, requestTextLength: Int): Exception {
        val looksLikeContextOverflow = CONTEXT_ERROR.containsMatchIn(body)
        return if (looksLikeContextOverflow) {
            ContextLengthExceededException(
                "embedding endpoint rejected input as too long (HTTP $status): ${body.take(200)}",
                textLength = requestTextLength,
            )
        } else {
            EmbeddingProtocolException("embedding request failed (HTTP $status): ${body.take(200)}")
        }
    }

    private fun JsonArray.toFloatArray(): FloatArray =
        FloatArray(size) { i -> this[i].jsonPrimitive.float }

    private fun JsonObject.truncated(): String = toString().take(200)

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val JSON = Json { ignoreUnknownKeys = true }
        private val CONTEXT_ERROR = Regex(
            "context|input length|too long|exceeds|maximum.*token|token.*limit",
            RegexOption.IGNORE_CASE,
        )

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        /**
         * Builds a provider, measuring [dimensions] by embedding one short
         * probe string.
         *
         * `EmbeddingProvider.dimensions` is a plain `val`, but neither Ollama
         * nor the OpenAI-compatible API reports a model's dimensionality
         * without embedding something. Probing once at startup is the honest
         * way to satisfy a non-suspend property: the alternative is making the
         * operator pass `--dimensions` and silently corrupting the vector file
         * when they get it wrong.
         */
        suspend fun create(
            baseUrl: String,
            model: String,
            flavour: ProviderFlavour,
            apiKey: String? = null,
            client: HttpClient = defaultClient(),
        ): HttpEmbeddingProvider {
            val probe = HttpEmbeddingProvider(baseUrl, model, dimensions = 0, flavour = flavour, apiKey = apiKey, client = client)
            val measured = probe.embed(listOf("gitsema")).single().size
            require(measured > 0) { "embedding endpoint returned a zero-length vector for model '$model'" }
            return HttpEmbeddingProvider(baseUrl, model, measured, flavour, apiKey, client)
        }
    }
}

/** A wire-level failure: bad status, malformed body, wrong number of results. */
class EmbeddingProtocolException(message: String) : Exception(message)
