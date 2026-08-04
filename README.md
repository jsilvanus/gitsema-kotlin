# gitsema-kotlin

A Kotlin Multiplatform (JVM + Android) port of [gitsema](https://github.com/jsilvanus/gitsema)'s
indexing and search core, built as a standalone library for
[Aidos](https://github.com/jsilvanus/aidos).

**Status: Tier 1 core loop complete and operational on the JVM target** — index →
search through the public API, with commit-mapping, recency ranking, and an
ancestry-aware resume cursor all real (not stubbed). Not yet publishable to a
real audience (still 0.1.0-SNAPSHOT), and `androidTarget()` isn't wired yet —
see below.

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
- **Chunking**: `FileChunker` (default, whole-file, §2.1) and `FixedChunker`
  (character-window splitting with line-boundary snapping, §2.2). Not yet
  wired: the context-limit fallback chain (§2.4) and the function chunker —
  see "What's deliberately not here yet."
- **Git access**: `GitRepository` interface (porting brief seam #3) with a
  JGit-backed implementation in `jvmAndroidMain` (§7.3).
  - `streamBlobs` uses `ObjectWalk` specifically to match `git rev-list
    --objects`'s real one-path-per-blob-per-walk semantics (verified
    empirically, not assumed).
  - `streamCommits` (`RevWalk` + a first-parent tree diff per commit) is the
    equivalent of gitsema-TS's `commitMap.ts` — records what each commit
    added/modified, with root commits diffed against an empty tree.
- **Storage** (§6): `MetadataStore`, `VectorStore`, `FtsStore`, all
  SQLite-backed via SQLDelight, plus a `resume_cursor` table.
  - `VectorStore` is the §9 redesign: int8-quantized vectors in a flat,
    memory-mapped file (one per model), a tiny SQLite table mapping
    `blob_hash → file_offset`, and streamed top-K search via a bounded
    min-heap — memory use is O(topK), never O(stored vector count).
  - `FtsStore` is SQLite FTS5 (Porter/ASCII), with query sanitization before
    every `MATCH` (§4.3).
- **Embedding orchestration**: `EmbeddingOrchestrator` — batched,
  concurrency-limited (default 4, matching gitsema-TS's `p-limit` default),
  with per-item failure containment so one bad item never sinks its batch (§7.4).
- **Indexer**: content-addressed dedup, per-blob error containment, oversized-blob
  handling, AND commit-mapping — every commit reachable from `ref` is recorded,
  every blob it changed is linked and has its path registered (even if that
  blob was already embedded, closing a real gap: an already-indexed blob
  resurfacing at a new path now gets that path registered instead of silently
  not). This is what makes `firstSeenFor`/recency ranking real.
  - **Resume cursor** (Decision C #3): `ref`'s tip is captured and persisted
    only after a full run completes successfully — not derived from
    write-insertion order the way gitsema-TS's cursor is (a documented bug
    class this port avoids). `since` defaults to the stored cursor
    automatically, so calling `index()` again only walks what's new.
- **Search**: `SearchEngine` composes `VectorStore`/`FtsStore`/`MetadataStore`
  into gitsema's actual contract — FTS-only + `degraded=true` when no
  vectors exist yet for the active model (constraints 5 & 6), hybrid-blended
  (§4.3) and three-signal-ranked (§4.2, recency included for real now) once
  they do.
- **Publishing**: `maven-publish` targeting GitHub Packages, wired but not
  yet used — see `.github/workflows/publish.yml` (manual `workflow_dispatch`
  only, not on every push).

**86 tests passing** (`jvm` target), covering: chunking determinism (both
strategies) and coverage/overlap invariants, quantization accuracy/determinism,
storage idempotency and multi-path blobs, FTS sanitization, vector store
correctness/dedup/topK-boundedness plus a memory-ceiling smoke test, embedding
batching and per-item failure isolation, real multi-commit history against
JGit (ordering, root-commit diffing, since-filtering, modified vs. added),
indexer dedup/resumability/the resume-cursor's full lifecycle, ranking and
hybrid-blend edge cases, and end-to-end `SemanticIndex` tests including the
degraded-search deliverable and `status()`'s coverage reporting.

## What's deliberately not here yet

- **The `androidTarget()` build configuration itself.** The source layout
  already anticipates it (`jvmAndroidMain` holds everything both targets will
  share), but this was developed in a sandboxed environment with neither an
  Android SDK nor network access to Google's Maven repository (confirmed via
  a direct request, not assumed), so it could not be added and verified
  honestly. Wiring it is a mechanical follow-up once run somewhere with both
  available — no `commonMain` or `jvmAndroidMain` code needs to change.
- **The context-limit fallback chain and function chunker** (§2.4). The
  function chunker specifically needs tree-sitter, which Decision A in the
  design doc says not to build on-device without asking first. `FixedChunker`
  exists and is tested, but wiring the fallback chain into the indexer needs
  chunk-level vector storage (a fallback chunk's embedding stored distinctly
  from its blob's whole-file one), which `VectorStore` doesn't support yet —
  a real architecture decision (extend `VectorStore`'s key model vs. a
  parallel chunk store), deferred rather than rushed or faked.
- **Branch filtering** (`Query.branch`) — needs a `blob_branches`-equivalent
  table and JGit branch-membership computation on top of commit-mapping.
- **Fully-streaming blob/commit ingestion** — the indexer still drains each
  git walk into a list before batching (lightweight metadata only, not blob
  content; documented as a known gap, not silent).
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

## Publishing

`.github/workflows/publish.yml`, run manually from the Actions tab (never on
push). Publishes to `maven.pkg.github.com/jsilvanus/gitsema-kotlin`. Not run
yet — there's nothing worth publishing to a real audience until this library
is further along — but it's wired and verified (via `publishToMavenLocal`)
for whenever that's true.
