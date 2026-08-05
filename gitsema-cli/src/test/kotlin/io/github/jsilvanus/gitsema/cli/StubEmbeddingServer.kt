package io.github.jsilvanus.gitsema.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.math.sqrt

/**
 * A real HTTP server speaking Ollama's and OpenAI's embedding protocols,
 * backed by `com.sun.net.httpserver` (JDK built-in, no dependency).
 *
 * The provider's whole job is wire behaviour — status codes, JSON shapes,
 * result ordering — so testing it against a mocked `HttpClient` would test the
 * mock. This exercises real sockets and real serialization; only the model is
 * fake, and deterministically so.
 */
class StubEmbeddingServer(
    private val dimensions: Int = 8,
    /** When set, every request fails with this status and body instead of embedding. */
    var failWith: Pair<Int, String>? = null,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Texts this server was asked to embed, in arrival order — lets tests assert batching behaviour. */
    val received = mutableListOf<String>()

    /** When true, the OpenAI handler returns `data` deliberately out of order to prove the provider re-sorts. */
    var shuffleOpenAiResponse: Boolean = false

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/api/embeddings") { exchange ->
            handle(exchange) { body ->
                val prompt = body["prompt"]!!.jsonPrimitive.content
                received += prompt
                """{"embedding":${vectorJson(prompt)}}"""
            }
        }
        server.createContext("/v1/embeddings") { exchange ->
            handle(exchange) { body ->
                val inputs = body["input"]!!.jsonArray.map { it.jsonPrimitive.content }
                received += inputs
                val entries = inputs.mapIndexed { i, text -> """{"index":$i,"embedding":${vectorJson(text)}}""" }
                val ordered = if (shuffleOpenAiResponse) entries.reversed() else entries
                """{"data":[${ordered.joinToString(",")}]}"""
            }
        }
        server.executor = null
        server.start()
    }

    private fun handle(exchange: HttpExchange, respond: (Map<String, kotlinx.serialization.json.JsonElement>) -> String) {
        val requestBody = exchange.requestBody.readBytes().decodeToString()
        val failure = failWith
        val (status, response) = if (failure != null) {
            failure
        } else {
            200 to respond(Json.parseToJsonElement(requestBody).jsonObject)
        }
        val bytes = response.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Deterministic unit vector per text — same text, same vector, no randomness. */
    private fun vectorJson(text: String): String {
        val raw = FloatArray(dimensions) { i ->
            ((text.hashCode() ushr (i % 16)) and 0xFF).toFloat() + i + 1f
        }
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        return raw.joinToString(",", "[", "]") { (it / norm).toString() }
    }

    override fun close() = server.stop(0)
}
