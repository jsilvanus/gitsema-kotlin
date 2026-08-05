# gitsema-kotlin

A Kotlin Multiplatform (JVM + Android) port of [gitsema](https://github.com/jsilvanus/gitsema)'s
indexing and search core, built as a standalone library for
[Aidos](https://github.com/jsilvanus/aidos).

**Status: Tier 1 complete and operational on the JVM target; `androidTarget()`
is wired and compiling, but has never run on a device** — index → search
through the public API, with commit-mapping, recency ranking, an
ancestry-aware resume cursor, branch filtering, and the context-limit
fallback chain all real (not stubbed). Not yet publishable to a real audience
(still 0.1.0-SNAPSHOT). What "compiling but unverified" means for the Android
target specifically is spelled out in [What still needs a
device](#what-still-needs-a-device) — read that before depending on it.

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
  (character-window splitting with line-boundary snapping, §2.2), wired
  together via the **context-limit fallback chain** (§2.4): whole-file → fixed
  1500 → fixed 800 → fail, storing one chunk-indexed vector per surviving
  sub-chunk only when every sub-chunk at a given window size embeds
  successfully. The function-chunker tier is still skipped (needs tree-sitter,
  out of scope per the design doc's Decision A without asking first).
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
    min-heap — memory use is O(topK), never O(stored vector count). Supports
    chunk-indexed records for the fallback chain (a blob can have several,
    deduplicated back to one hit — its best-scoring chunk — at search time,
    without breaking the O(topK) memory bound).
  - `FtsStore` is SQLite FTS5 (Porter/ASCII), with query sanitization before
    every `MATCH` (§4.3).
  - A `blob_branch_entry` table backs `Query.branch` filtering: a row means
    "this blob was visited while indexing this ref by name" (reusing
    `ObjectWalk`, not full git branch-topology computation) — see
    `BlobBranches.sq` for the precise semantic.
- **Embedding orchestration**: `EmbeddingOrchestrator` — batched,
  concurrency-limited (default 4, matching gitsema-TS's `p-limit` default),
  with per-item failure containment so one bad item never sinks its batch (§7.4).
- **Indexer**: content-addressed dedup, per-blob error containment, oversized-blob
  handling, AND commit-mapping — every commit reachable from `ref` is recorded,
  every blob it changed is linked and has its path registered (even if that
  blob was already embedded, closing a real gap: an already-indexed blob
  resurfacing at a new path now gets that path registered instead of silently
  not). This is what makes `firstSeenFor`/recency ranking real.
  - **Streamed, not drained**: both the commit walk and the blob walk are
    consumed as `Flow`s — the blob walk in bounded `dedupBatchSize` windows
    via a local `Flow.chunked` — so peak memory is one window regardless of
    history length, and the embedding pass backpressures the git walk instead
    of the walk racing ahead of it (Decision C #2, constraint 4).
  - **Resume cursor** (Decision C #3): `ref`'s tip is captured and persisted
    only after a full run completes successfully — not derived from
    write-insertion order the way gitsema-TS's cursor is (a documented bug
    class this port avoids). `since` defaults to the stored cursor
    automatically, so calling `index()` again only walks what's new.
- **Search**: `SearchEngine` composes `VectorStore`/`FtsStore`/`MetadataStore`
  into gitsema's actual contract — FTS-only + `degraded=true` when no
  vectors exist yet for the active model (constraints 5 & 6), hybrid-blended
  (§4.3) and three-signal-ranked (§4.2, recency included for real now) once
  they do. Ranking and BM25 weights are constructor-injected, not constants.
  - **Every search reports coverage.** `search()` returns a `SearchResult`
    (matches + `IndexCoverage` + a search-level `degraded` flag), not a bare
    `List<Match>`. Blobs embedded for the active model over blobs known —
    two counters, per aidos D29, so a half-built index answers "I have not
    read most of it yet" instead of "there is nothing here". Coverage is
    per-model: the same repository is covered for the model that indexed it
    and uncovered for one that has not.
- **Evaluation** (`eval/`): `evaluate(index, cases, k)` → precision@k,
  recall@k, MRR, matching gitsema-TS's metric definitions so numbers are
  comparable. Exists at Tier 1 on purpose (Decision C #7): the 0.7/0.2/0.1
  ranking weights and 0.3 BM25 weight are inherited from a project that never
  evaluated them, and this is what makes them checkable rather than assumed.
  Reports carry the coverage they were measured under — a score against a
  20%-covered index measures indexing progress, not ranking.
- **Resource envelope**: every store and `JGitRepository` takes an
  `ioContext: CoroutineContext` (default `Dispatchers.IO`). The consuming
  application owns the dispatcher, per aidos D29 — a host that must keep this
  work off its own IO pool or confine it to one thread can.
- **CI**: `.github/workflows/ci.yml` builds and tests both targets on every
  push to `main` and every pull request.
- **Publishing**: `maven-publish` targeting GitHub Packages, wired but not
  yet used — see `.github/workflows/publish.yml` (manual `workflow_dispatch`
  only, not on every push).
- **Android target**: `androidTarget()` (library, `compileSdk` 34, `minSdk`
  26, one `release` publication), with `androidMain` holding the only two
  genuinely Android-specific pieces — the SQLite driver and a JGit
  configuration guard. Both are documented at their definitions;
  [What still needs a device](#what-still-needs-a-device) says what remains
  unproven.

**117 tests passing** (`jvm` target) and **50** (Android `debug`/`release` unit
tests — `commonTest`'s pure-Kotlin suites compiled against the Android
variant), covering: chunking determinism (both
strategies) and coverage/overlap invariants, quantization accuracy/determinism,
storage idempotency and multi-path blobs, FTS sanitization, vector store
correctness/dedup/topK-boundedness plus a memory-ceiling smoke test, embedding
batching and per-item failure isolation, real multi-commit history against
JGit (ordering, root-commit diffing, since-filtering, modified vs. added),
indexer dedup/resumability/the resume-cursor's full lifecycle, ranking and
hybrid-blend edge cases, end-to-end `SemanticIndex` tests including the
degraded-search deliverable and `status()`'s coverage reporting, the
context-limit fallback chain (all-or-nothing per window size, chunk-indexed
storage, and the function-chunker tier staying skipped), branch filtering
across both the hybrid and degraded FTS-only search paths, coverage reporting
on every query (including the per-model and empty-result cases), bounded-window
flow chunking and the indexer's streaming behaviour (asserted by observing that
embedding starts after one window, not after the whole walk), and the eval
harness's metrics including its deliberate divergence from gitsema-TS on
repeated-path recall.

## What's deliberately not here yet

- **The function chunker** (§2.4's remaining tier). It needs tree-sitter,
  which Decision A in the design doc says not to build on-device without
  asking first. The rest of the fallback chain (whole-file → fixed 1500 →
  fixed 800 → fail, with chunk-indexed vector storage) is wired and tested.
- **Tier 2** (impact, experts, evolution, and the rest of the analysis
  capabilities) and **Tier 3** (knowledge graph, narrator/LLM, HTTP/MCP,
  multi-repo, multi-tenant auth, remote indexing, Postgres/Qdrant, HNSW) —
  out of scope until Tier 1 is done; Tier 3 is out of scope for this library
  entirely except where kotlin-port.md's Decision A says otherwise (co-change
  graph edges; a desktop-built, phone-imported structural graph).

A full "what was deliberately not ported and why" note will be written once
enough of Tier 1/2 exists for it to be a real accounting rather than a
placeholder — see kotlin-port.md deliverable #4.

## What still needs a device

The Android target compiles, dexes, and runs `commonTest` as JVM-hosted unit
tests. None of that touches a real Android runtime, and the gap between
"compiles" and "works" is where this library's Android risk actually lives.
Everything below was established statically — bytecode, exception tables,
`android.jar`'s class list — and none of it has run on an emulator or a
phone. There are no instrumented (`connectedAndroidTest`) tests yet; adding
them is the first item on this list, not the last.

- **JGit's JMX registration is a real crash, already guarded.** `WindowCache`
  registers an MBean by default on first packfile read, through classes that
  do not exist on Android, and JGit catches only the checked exceptions —
  not the resulting `NoClassDefFoundError`. `configureJGitForAndroid()` in
  `androidMain` turns it off and **must be called once before any
  `JGitRepository` is constructed**. That the guard is necessary is
  established; that it is sufficient is not.
- **JGit's other platform assumptions.** `FS_POSIX` probes for a system `git`
  executable (Android has none), and `FileStoreAttributes` measures
  filesystem timestamp resolution. Both are known hazards on Android and
  neither is addressed here.
- **The SQLite driver's path handling.** `createSqlDriver(context, path)`
  passes an absolute path as the database *name*, relying on
  `Context.getDatabasePath` resolving it to that exact file. That is AOSP's
  documented behaviour, asserted rather than observed.
- **Everything about resource behaviour.** The memory-mapped vector store's
  page-cache behaviour under Android memory pressure, indexing throughput
  inside Android's execution windows, and whether the §9.2 brute-force scan
  is fast enough before any ANN work — all of it is the measurement
  kotlin-port.md §9.2 point 4 explicitly defers to a real device.

## Building

```bash
./gradlew build   # builds and tests both targets: jvm and android
```

Building the Android target needs an SDK: set `sdk.dir` in
`local.properties`, or `ANDROID_HOME`, with platform 34 and build-tools
34.0.0 installed. A machine without one can still do JVM-only work —

```bash
./gradlew :gitsema-core:jvmTest   # verified to run with no SDK present at all
```

— it is only the Android tasks that fail. GitHub's `ubuntu-latest` runners
ship an SDK, so `.github/workflows/publish.yml` needs no change; note that it
runs `./gradlew build`, which now builds and publishes an `androidRelease`
variant alongside `jvm`.

## Publishing

`.github/workflows/publish.yml`, run manually from the Actions tab (never on
push). Publishes to `maven.pkg.github.com/jsilvanus/gitsema-kotlin`. Not run
yet — there's nothing worth publishing to a real audience until this library
is further along — but it's wired and verified (via `publishToMavenLocal`)
for whenever that's true.
