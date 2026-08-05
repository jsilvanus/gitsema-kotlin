package io.github.jsilvanus.gitsema.cli

import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import io.github.jsilvanus.gitsema.eval.EvalCase
import io.github.jsilvanus.gitsema.eval.evaluate
import io.github.jsilvanus.gitsema.model.IndexCoverage
import io.github.jsilvanus.gitsema.model.Query
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream
import kotlin.math.roundToInt

/**
 * Exit codes, matching gitsema-TS's contract so scripts behave the same
 * against either implementation: 0 ok, 1 runtime error, 2 usage error.
 */
object ExitCode {
    const val OK = 0
    const val RUNTIME_ERROR = 1
    const val USAGE_ERROR = 2
}

/**
 * The CLI, as a function of its arguments and streams rather than of the
 * process — so tests drive it end to end without spawning a JVM or capturing
 * global stdout. [main] is the only part that knows about the process.
 *
 * [providerFactory] is injectable for the same reason: tests need a provider
 * that does not require a running model server, and the library's whole design
 * is that whoever hosts it supplies the provider.
 */
class Cli(
    private val out: PrintStream,
    private val err: PrintStream,
    private val env: (String) -> String? = System::getenv,
    private val providerFactory: suspend (ProviderConfig) -> EmbeddingProvider = { config ->
        HttpEmbeddingProvider.create(
            baseUrl = config.baseUrl,
            model = config.model,
            flavour = config.flavour,
            apiKey = config.apiKey,
        )
    },
) {
    /** Resolved endpoint settings — flags first, then environment, then defaults. */
    data class ProviderConfig(
        val flavour: ProviderFlavour,
        val baseUrl: String,
        val model: String,
        val apiKey: String?,
    )

    suspend fun run(argv: Array<String>): Int =
        try {
            when (val command = argv.firstOrNull()) {
                null, "help", "--help", "-h" -> {
                    printUsage(out)
                    ExitCode.OK
                }
                "index" -> index(rest(argv))
                "search" -> search(rest(argv))
                "status" -> status(rest(argv))
                "eval" -> eval(rest(argv))
                else -> {
                    err.println("unknown command '$command'")
                    printUsage(err)
                    ExitCode.USAGE_ERROR
                }
            }
        } catch (e: Args.UsageException) {
            err.println("error: ${e.message}")
            ExitCode.USAGE_ERROR
        } catch (e: IllegalArgumentException) {
            // require()/check() failures from the library and Workspace are
            // the operator's mistake (not a git repo, bad --dimensions), not a
            // crash worth a stack trace.
            err.println("error: ${e.message}")
            ExitCode.USAGE_ERROR
        } catch (e: Exception) {
            err.println("error: ${e.message ?: e::class.simpleName}")
            ExitCode.RUNTIME_ERROR
        }

    private fun rest(argv: Array<String>) = argv.drop(1)

    private suspend fun index(argv: List<String>): Int {
        val args = Args.parse(argv, booleanFlags = setOf("quiet"))
        val ref = args.positionals.firstOrNull() ?: "HEAD"
        val repoDir = repoDir(args)
        val quiet = args.boolean("quiet")

        withWorkspace(args, repoDir, requireEmbedding = true) { workspace ->
            val result = workspace.index.index(ref) { progress ->
                if (!quiet) {
                    out.print(
                        "\rcommits ${progress.commitsProcessed}  seen ${progress.blobsSeen}  " +
                            "indexed ${progress.blobsIndexed}  failed ${progress.blobsFailed}",
                    )
                    out.flush()
                }
            }
            if (!quiet) out.println()
            out.println(
                "indexed ${result.blobsIndexed} blob(s) from ${result.commitsProcessed} commit(s) " +
                    "(${result.blobsSkipped} already indexed, ${result.blobsFailed} failed, " +
                    "${result.blobsOversized} too large)",
            )
        }
        return ExitCode.OK
    }

    private suspend fun search(argv: List<String>): Int {
        val args = Args.parse(argv)
        val queryText = args.positionals.joinToString(" ").ifBlank {
            throw Args.UsageException("search needs a query")
        }
        val topK = args.positiveInt("top", 10)

        withWorkspace(args, repoDir(args)) { workspace ->
            val result = workspace.index.search(
                Query(text = queryText, topK = topK, branch = args.string("branch")),
            )
            // Coverage prints before the results, not after, because it is
            // what tells the reader how to weigh them (aidos D29).
            out.println(coverageLine(result.coverage, result.degraded))
            if (result.matches.isEmpty()) {
                out.println("no matches")
            } else {
                for ((rank, match) in result.matches.withIndex()) {
                    val paths = match.paths.joinToString(", ") { it.value }.ifBlank { "(no path recorded)" }
                    out.println(
                        "${(rank + 1).toString().padStart(2)}. ${"%.4f".format(match.score)}  " +
                            "$paths  [${match.provenance.name.lowercase()}]  ${match.blobHash.value.take(8)}",
                    )
                }
            }
        }
        return ExitCode.OK
    }

    private suspend fun status(argv: List<String>): Int {
        val args = Args.parse(argv)
        withWorkspace(args, repoDir(args)) { workspace ->
            val status = workspace.index.status()
            out.println("repository:   ${workspace.repoDir.absolutePath}")
            out.println("model:        ${status.embeddingModel ?: "(none recorded)"}")
            out.println("dimensions:   ${status.embeddingDimensions ?: "(unknown)"}")
            out.println("last indexed: ${status.lastIndexedCommit?.value ?: "(never completed a run)"}")
            out.println("coverage:     ${coverageLine(status.coverage, degraded = false)}")
        }
        return ExitCode.OK
    }

    private suspend fun eval(argv: List<String>): Int {
        val args = Args.parse(argv)
        val file = args.positionals.firstOrNull()?.let(::File)
            ?: throw Args.UsageException("eval needs a JSONL file of {query, expectedPaths} cases")
        if (!file.isFile) throw Args.UsageException("no such file: ${file.absolutePath}")
        val k = args.positiveInt("top", 10)
        val cases = readEvalCases(file)
        if (cases.isEmpty()) throw Args.UsageException("no usable cases in ${file.absolutePath}")

        withWorkspace(args, repoDir(args)) { workspace ->
            val report = evaluate(workspace.index, cases, k)
            out.println("cases:        ${report.cases.size}  (k=$k)")
            out.println("precision@$k:  ${"%.3f".format(report.precisionAtK)}")
            out.println("recall@$k:     ${"%.3f".format(report.recallAtK)}")
            out.println("MRR:          ${"%.3f".format(report.mrr)}")
            out.println("coverage:     ${coverageLine(report.minCoverage, report.anyDegraded)}")
            if (report.anyDegraded || !report.minCoverage.isComplete) {
                // Stated rather than left to the reader: these numbers are not
                // a ranking measurement when the index is partial.
                err.println(
                    "warning: at least one case ran against a partial or vector-less index — " +
                        "these scores measure indexing progress, not retrieval quality",
                )
            }
        }
        return ExitCode.OK
    }

    /**
     * Reads gitsema-TS's eval format: one JSON object per line, blank lines
     * and `#`/`//` comments skipped, malformed lines skipped rather than
     * failing the run (matching TS's tolerance for a hand-maintained file).
     */
    private fun readEvalCases(file: File): List<EvalCase> =
        file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return@mapNotNull null
            runCatching {
                val obj = Json.parseToJsonElement(trimmed).jsonObject
                val query = obj["query"]?.jsonPrimitive?.content ?: return@runCatching null
                val expected = obj["expectedPaths"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                EvalCase(query, expected)
            }.getOrNull()
        }

    private fun coverageLine(coverage: IndexCoverage, degraded: Boolean): String {
        val percent = (coverage.fraction * 100).roundToInt()
        val suffix = when {
            degraded -> "  [degraded: no vectors for this model yet, keyword search only]"
            coverage.isComplete -> ""
            else -> "  [partial index]"
        }
        return "${coverage.blobsEmbedded}/${coverage.blobsKnown} blobs embedded ($percent%)$suffix"
    }

    private fun repoDir(args: Args): File =
        File(args.string("repo") ?: ".").canonicalFile

    /**
     * @param requireEmbedding true for commands that are pointless without a
     * model (index), false for read-only ones that must keep working when no
     * endpoint is reachable — see [OfflineEmbeddingProvider].
     */
    private suspend fun withWorkspace(
        args: Args,
        repoDir: File,
        requireEmbedding: Boolean = false,
        block: suspend (Workspace) -> Unit,
    ) {
        val config = providerConfig(args)
        val provider = try {
            providerFactory(config)
        } catch (e: Exception) {
            if (requireEmbedding) throw e
            // Connection failures often carry a null message (ConnectException),
            // so fall back to the type name rather than printing "(null)".
            val reason = e.message?.takeUnless { it.isBlank() } ?: e::class.simpleName
            err.println("warning: ${config.baseUrl} is not reachable ($reason) - continuing without embeddings")
            OfflineEmbeddingProvider(config.model)
        }
        Workspace.open(
            repoDir = repoDir,
            provider = provider,
            concurrency = args.positiveInt("concurrency", 4),
            batchSize = args.positiveInt("batch-size", 1),
        ).use { block(it) }
    }

    /** Flags override environment, which overrides defaults — gitsema-TS's precedence, and its variable names. */
    private fun providerConfig(args: Args): ProviderConfig {
        val flavourName = args.string("provider") ?: env("GITSEMA_PROVIDER") ?: "ollama"
        val flavour = when (flavourName.lowercase()) {
            "ollama" -> ProviderFlavour.OLLAMA
            "http", "openai" -> ProviderFlavour.OPENAI
            else -> throw Args.UsageException("--provider must be 'ollama' or 'http', got '$flavourName'")
        }
        val defaultUrl = if (flavour == ProviderFlavour.OLLAMA) "http://localhost:11434" else null
        val baseUrl = args.string("url") ?: env("GITSEMA_HTTP_URL") ?: defaultUrl
            ?: throw Args.UsageException("--url (or GITSEMA_HTTP_URL) is required for --provider http")
        val model = args.string("model") ?: env("GITSEMA_MODEL") ?: "nomic-embed-text"
        return ProviderConfig(flavour, baseUrl.trimEnd('/'), model, args.string("api-key") ?: env("GITSEMA_API_KEY"))
    }

    private fun printUsage(stream: PrintStream) {
        stream.println(
            """
            gitsema - semantic index over a git repository's full history

            usage:
              gitsema index [ref] [options]        walk history and embed every new blob (default ref: HEAD)
              gitsema search <query...> [options]  search the index
              gitsema status [options]             show coverage, model, and resume point
              gitsema eval <cases.jsonl> [options] score retrieval: precision@k, recall@k, MRR

            common options:
              --repo <dir>          repository to work on (default: current directory)
              --provider <name>     ollama | http          (env: GITSEMA_PROVIDER, default ollama)
              --url <url>           endpoint base URL      (env: GITSEMA_HTTP_URL, default http://localhost:11434)
              --model <name>        embedding model        (env: GITSEMA_MODEL, default nomic-embed-text)
              --api-key <key>       bearer token           (env: GITSEMA_API_KEY)
              --concurrency <n>     parallel embed calls (default 4)
              --batch-size <n>      texts per embed call (default 1)

            index options:
              --quiet               suppress the progress line

            search options:
              --top <n>             results to return (default 10)
              --branch <name>       restrict to blobs indexed under this ref

            eval options:
              --top <n>             k for precision@k / recall@k (default 10)

            The index lives in <repo>/.gitsema/ - add it to .gitignore.
            Exit codes: 0 ok, 1 runtime error, 2 usage error.
            """.trimIndent(),
        )
    }
}
