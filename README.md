# gitsema-kotlin

A Kotlin Multiplatform (JVM + Android) port of [gitsema](https://github.com/jsilvanus/gitsema)'s
indexing and search core, built as a standalone library for
[Aidos](https://github.com/jsilvanus/aidos).

**Status: Tier 1, in progress.** Not yet publishable, not yet feature-complete.

The full specification — algorithms, constants with their rationale, cost
profiles, and the decisions behind every departure from gitsema-TS — lives in
[`docs/design/kotlin-port.md`](https://github.com/jsilvanus/gitsema/blob/main/docs/design/kotlin-port.md)
in the gitsema repo, not here. That document is authoritative; this README
only orients you to what's actually built.

## What's here so far

- `gitsema-core` — the library module.
  - Core models (`BlobHash`, `Chunk`, `Match`, `Query`, ...) and the
    `EmbeddingProvider` seam (kotlin-port.md §3.1, §8) — `commonMain`.
  - `FileChunker`, the default whole-file chunking strategy (kotlin-port.md §2.1).
  - `GitRepository`, an interface over repository access (porting brief seam #3),
    with a JGit-backed implementation (kotlin-port.md §7.3) in `jvmAndroidMain` —
    shared between the `jvm` and (once wired) `androidTarget` builds, since both
    compile to JVM bytecode.

## What's deliberately not here yet

- **The `androidTarget()` build configuration itself.** The source layout
  already anticipates it (see the comment in `gitsema-core/build.gradle.kts`),
  but this was developed in a sandboxed environment with neither an Android SDK
  nor network access to Google's Maven repository, so it could not be added and
  verified honestly. Wiring it is a mechanical follow-up once run somewhere
  with both available — no commonMain or jvmAndroidMain code needs to change.
- **Storage, embedding orchestration, the indexer, vector search, FTS/BM25,
  hybrid blend, and ranking** — the rest of Tier 1 per kotlin-port.md, not yet
  built.
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
