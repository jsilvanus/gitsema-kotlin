# gitsema-kotlin

A Kotlin Multiplatform (JVM + Android) port of [gitsema](https://github.com/jsilvanus/gitsema)'s
indexing and search core, built as a standalone library for
[Aidos](https://github.com/jsilvanus/aidos).

**Status: Tier 1 core loop working end-to-end** (index → search, through the
public API), **not yet publishable, not yet feature-complete.**

The full specification — algorithms, constants with their rationale, cost
profiles, and the decisions behind every departure from gitsema-TS — lives in
[`docs/design/kotlin-port.md`](https://github.com/jsilvanus/gitsema/blob/main/docs/design/kotlin-port.md)
in the gitsema repo, not here. That document is authoritative; this README
only orients you to what's actually built.

## What's here so far

`gitsema-core`, the library module:

- **Public API** (`SemanticIndex`, `GitsemaSemanticIndex`) — `index(ref,
  onProgress)`, `search(query)`, `status()`, per the porting brief.
- **Models** (`BlobHash`, `Chunk`, `Match`, `Query`, `IndexProgress/Result/Status`)
  and the `EmbeddingProvider` seam (kotlin-port.md §3.1, §8) — `commonMain`.
- **Chunking**: `FileChunker`, the default whole-file strategy (§2.1). Tier
  1 scope only — no function/fixed chunkers or the context-limit fallback
  chain yet (§2.4).
- **Git access**: `GitRepository` interface (porting brief seam #3) with a
  JGit-backed implementation in `jvmAndroidMain` (§7.3), using `ObjectWalk`
  specifically to match `git rev-list --objects`'s real one-path-per-blob
  semantics (verified empirically, not assumed — see the git history).
- **Storage** (§6): `MetadataStore`, `VectorStore`, `FtsStore`, all
  SQLite-backed via SQLDelight.
  - `VectorStore` is the §9 redesign: int8-quantized vectors in a flat,
    memory-mapped file (one per model), a tiny SQLite table mapping
    `blob_hash → file_offset`, and streamed top-K search via a bounded
    min-heap — memory use is O(topK), never O(stored vector count).
  - `FtsStore` is SQLite FTS5 (Porter/ASCII), with query sanitization before
    every `MATCH` (§4.3).
- **Embedding orchestration**: `EmbeddingOrchestrator` — batched,
  concurrency-limited (default 4, matching gitsema-TS's `p-limit` default),
  with per-item failure containment so one bad item never sinks its batch (§7.4).
- **Indexer**: ties the above together — content-addressed dedup, per-blob
  error containment, oversized-blob handling. Two known scope gaps, not
  silent ones (see the class doc comment): no commit-mapping yet (so
  `firstSeenFor` has nothing to return), and blobs are still drained from
  the git stream into a list before batching rather than fully streamed.
- **Search**: `SearchEngine` composes `VectorStore`/`FtsStore`/`MetadataStore`
  into gitsema's actual contract — FTS-only + `degraded=true` when no
  vectors exist yet for the active model (constraints 5 & 6), hybrid-blended
  (§4.3) and three-signal-ranked (§4.2) once they do. Recency degrades
  gracefully (weight redistributed, not faked) when first-seen data isn't
  available.

**69 tests passing** (`jvm` target), covering: chunking determinism,
quantization accuracy/determinism, storage idempotency and multi-path blobs,
FTS sanitization, vector store correctness/dedup/topK-boundedness plus a
memory-ceiling smoke test, embedding batching and per-item failure isolation,
indexer content-addressed dedup and resume-after-partial-run, ranking and
hybrid-blend edge cases, and an end-to-end `SemanticIndex` test exercising the
degraded-search deliverable specifically.

## What's deliberately not here yet

- **The `androidTarget()` build configuration itself.** The source layout
  already anticipates it (`jvmAndroidMain` holds everything both targets will
  share), but this was developed in a sandboxed environment with neither an
  Android SDK nor network access to Google's Maven repository (confirmed via
  a direct request, not assumed), so it could not be added and verified
  honestly. Wiring it is a mechanical follow-up once run somewhere with both
  available — no `commonMain` or `jvmAndroidMain` code needs to change.
- **Commit-mapping** (gitsema-TS's `commitMap.ts` equivalent) — needed for
  `firstSeenFor`/recency ranking, branch filtering, and `status().lastIndexedCommit`
  to be real rather than absent.
- **Function/fixed chunkers and the context-limit fallback chain** (§2.4).
- **Fully-streaming blob ingestion** (the indexer currently drains the git
  walk into a list before batching — documented as a known gap, not silent).
- **Tier 2** (impact, experts, evolution, and the rest of the analysis
  capabilities) and **Tier 3** (knowledge graph, narrator/LLM, HTTP/MCP,
  multi-repo, multi-tenant auth, remote indexing, Postgres/Qdrant, HNSW) —
  out of scope until Tier 1 is done; Tier 3 is out of scope for this library
  entirely except where kotlin-port.md's Decision A says otherwise (co-change
  graph edges; a desktop-built, phone-imported structural graph).

A full "what was deliberately not ported and why" note will be written once
enough of Tier 1/2 exists for it to be a real accounting rather than a
placeholder — see kotlin-port.md deliverable #4.

## Building

```bash
./gradlew build   # jvm target only, for now — see "What's deliberately not here yet"
```

No Android SDK is required to build or test what currently exists.
