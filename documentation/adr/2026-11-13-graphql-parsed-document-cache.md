---
title: Cache parsed and validated GraphQL documents per GraphQL instance in a Caffeine cache bounded by bytes
date: 2026-11-13
updated: 2026-11-13 12:00
status: proposed
kind: optimization
issues: [1191]
prs: []
areas: [evita_external_api/evita_external_api_graphql]
supersedes: []
superseded-by: []
relates: []
---

# Cache parsed and validated GraphQL documents per GraphQL instance in a Caffeine cache bounded by bytes

Every `GraphQL` instance evitaDB builds (data and schema API of each catalog, system API) now gets a
`PreparsedDocumentProvider` - `GraphQLDocumentCache` - so an identical query text is lexed, parsed and validated
once instead of on every request. The cache is Caffeine, newly adopted as a project library, bounded by an
approximate memory budget (`api.endpoints.graphQL.documentCacheSizeInBytes`, default `16MB` per instance).

## Why

A CPU profile of a repeated product-detail-page workload (issue #1191) attributed ~9.3 % of CPU to lexing, parsing
and validation of query texts that never change - front stores send build-time static documents with values passed
as variables. The real saving is somewhat lower: `ValuesResolverOneOfValidation`, named in the issue, runs during
per-request variable coercion in `graphql.execution`, not in the validation phase, and stays.

### Previous state

graphql-java's default `NoOpPreparsedDocumentProvider` re-parsed and re-validated every request. The request
metric instrumentation (`RequestMetricInstrumentation` → `ExecutedEvent`) relied on that: it is a state machine
driven by `beginParse` → `beginValidation` → `beginExecuteOperation`, and `ExecutedEvent#finishValidation` asserts
that validation started. graphql-java invokes `beginParse` / `beginValidation` only inside the provider's compute
function, i.e. only on a miss - so plugging in any cache without changing the metrics would fail **every** cached
request to the catalog data and schema API with a `GraphQLInternalError` (HTTP and WebSocket alike).

## Options considered

### Option A - Caffeine as a direct dependency (chosen)

- **Pros:** W-TinyLFU eviction (one-off queries do not push out hot ones), weight-based bound, battle-tested
  concurrency, least own code.
- **Cons:** a new third-party library (+ `jspecify` and `error_prone_annotations` transitively, both already on
  the server classpath via other libraries).

### Option B - an in-house `ConcurrentHashMap`-based bounded cache

Lock-free reads, eviction under a `ReentrantLock` on the (rare) write path, simple clock or sampled-LRU policy.

- **Pros:** no new dependency.
- **Cons:** own eviction and bound code to write, test and maintain; no frequency-aware admission.
- **Rejected because:** Caffeine does the same job better and the next cache anywhere in the codebase would face
  the same choice; the team accepted the library. Revisit only if a dependency diet becomes a goal.

### Option C - harden and reuse `evita_external_api_core`'s `LRUCache` (Undertow port)

- **Pros:** already in the codebase.
- **Cons:** its only user runs it in FIFO mode; the LRU path is unused, untested and has real defects (unguarded
  `accessQueue.poll()` NPE in `add` under a same-key race, an entry that can stay in the map without a queue node,
  eviction by key instead of identity, only a soft size bound).
- **Rejected because:** fixing someone else's subtle lock-free code costs more than A or B and touches the routing
  cache `PathHandlingService` depends on.

### Option D - `LinkedHashMap` in access order (the interim proposed in the issue)

- **Rejected because:** access order mutates the map on every read, so every hit would need the global lock;
  `synchronized` is forbidden and a `ReentrantLock` would serialize all requests of an instance.

### Bound: entry count vs. memory budget

An entry count (e.g. 256) is simpler, but its memory worst case depends on query sizes nobody controls (≈ 23 MB
with the largest measured documents). A byte budget with a weigher derived from the query length gives a real
memory guarantee regardless of query size and follows the existing `…SizeInBytes` configuration vocabulary.
**Chosen: memory budget**, `16MB` per instance (~200 of the largest or ~1000 average measured documents).

## Decision

**Chosen: Option A with a byte budget.** Caffeine gives the strongest eviction policy for the least code, and the
cache is hidden behind one class, so the store stays swappable.

Entries with parse or validation errors are **not cached** - a client spraying distinct invalid queries must not
evict hot valid ones. The cost is that a client looping on one invalid query pays the full cost, as before. This
deviates from the issue's acceptance criterion "invalid query reports errors when served from cache".

## Key technical details

- `GraphQLDocumentCache` (`evita_external_api_graphql/.../api/`) - the provider. It uses explicit `getIfPresent` /
  `put`, never `cache.get(key, fn)`: parsing must not run under Caffeine's per-key lock and error entries must not
  be stored. Concurrent misses of one key parse twice - harmless and intentional.
- Eviction maintenance runs on the calling thread (`executor(Runnable::run)`) so the bound is enforced without a
  dependency on the common fork-join pool; writes happen only on misses.
- Key = query text only. Validation in graphql-java receives neither variables nor operation name, and its locale
  falls back to `Locale.getDefault()` because evitaDB never sets one. If anyone starts setting
  `ExecutionInput#locale`, a validation predicate hint or overrides `instrumentDocumentAndVariables`, the key must
  grow accordingly.
- No invalidation code: `GraphQLManager#registerCatalog` / `#refreshCatalog` build brand-new `GraphQL` instances
  on every schema change and the cache is created inside `CatalogGraphQLBuilder#build` / `SystemGraphQLBuilder#build`.
  Sharing a cache across instances would serve documents validated against an old schema.
- `documentCacheSizeInBytes: 0` attaches no provider at all; negative values are rejected.
- Metrics: on a hit `RequestMetricInstrumentation#beginExecuteOperation` sees that parse never started and calls
  `ExecutedEvent#skipParseAndValidation()` instead of `finishValidation()`. Parse and validation histograms record
  `0 ms` (they are whole milliseconds, most real parses already land in the 0 ms bucket, and keeping their `_count`
  equal to the request count matches the other phase histograms). The JFR event gets a non-exported boolean
  `documentCached`; it is deliberately not a metric label, which would double series cardinality.

### Sizing measurements

The retained size of a parsed `Document` was measured with JOL (graphql-java 25.0) on 124 static operations of a
production e-commerce storefront framework (fragments inlined): **~6.3-7.4× the query text length**, not 20-100×
as the issue guessed. Largest: 12 KB text → 78 KB, 9.9 KB → 62 KB; average 12.5 KB; all 124 together 1.5 MB (+ keys
~0.2 MB). A traffic recording of ~2 minutes of browsing on a production e-commerce site (252 sessions) showed 21
distinct operations. Tiny documents are dominated by a fixed cost instead (JOL: a 9-character query retains ~650 B, a 42-character one
~1.7 KB), which matters exactly for the pathological case the bound exists for - many short distinct texts. Hence
the weigher `1 KB + 8 × query.length()` (document, key, cache node) and the conclusion that a realistic
hot set is tens of entries / a few MB per instance; the bound matters only against pathological clients (e.g.
literals inlined into query texts).

## Verification

- `GraphQLDocumentCacheTest` - hit/miss, errors never cached, the byte bound (≥ 90 of 100 documents re-parsed with
  a budget of 10, the most recent one retained), concurrent cold start (16 threads, shared and distinct keys), wiring and the disabled cache,
  option validation, and the metric path on a hit (fails with `GraphQLInternalError` without the metrics fix).
- `CatalogGraphQLDocumentCacheFunctionalTest` - repeated query, variables on a cached document, repeated syntax and
  validation errors, a repeated subscription over WebSocket (the sub-protocol accepts subscriptions only), a schema
  change discarding cached documents, and the cache being attached to catalog instances.
- With the `RequestMetricInstrumentation` change reverted, 5 of these tests fail (HTTP responses non-200,
  `GraphQLInternalError: Validation didn't started`) - the metrics fix is load-bearing. The whole `graphql`-tagged
  functional suite passes (686 tests).
- `EvitaServerTest#shouldResolveGraphQLDocumentCacheSizeFromConfiguration` - size shorthand + property override.
- `GraphQLDocumentCacheBenchmark` (`evita_performance_tests`, `-P full`) - a static product detail page document
  with variables executed in-process against the catalog data API of an artificial catalog (1,000 products), HTTP
  and JSON left out; 1 fork, 1 thread, 3×10 s warm-up, 5×10 s measurement, `-prof gc`:

  | document | cache | avg. time | allocated per op |
  |---|---|---|---|
  | 1.4 KB (product detail + 1 listing) | off | 582 ± 105 µs | 1.54 MB |
  | 1.4 KB | 16 MB | 398 ± 28 µs (−32 %) | 1.13 MB (−27 %) |
  | 12.6 KB (product detail + 20 listings) | off | 5,635 ± 695 µs | 16.1 MB |
  | 12.6 KB | 16 MB | 4,444 ± 423 µs (−21 %) | 12.6 MB (−22 %) |

  Over HTTP the relative saving is smaller (transport and JSON (de)serialization are untouched); the CPU profile of
  the issue's workload was not re-run.

## Consequences & open follow-ups

- Memory: the budget applies per `GraphQL` instance, i.e. 2 × catalogs + 1 instances - documented in
  `configure.md`.
- Failed-source-query recording in `SourceQueryRecordingInstrumentation#logFailedSourceQuery` never fires (the
  session is put into the context only in `beginExecuteOperation`, and graphql-java completes the parse/validation
  contexts with a `null` throwable) - pre-existing defect, unaffected by the cache, to be reported separately.
- On a hit that fails before `beginExecuteOperation` (unknown `operationName`, variable coercion error) the event
  reports `documentCached=false` and zero preparation; such requests return errors anyway and are rare. Pre-existing
  and independent of the cache: `instrumentExecutionResult` calls `finishOperationExecution()` unguarded, so any
  request failing before execution records `now - 0` as its operation duration.
- A hit-ratio metric could be fed from Caffeine's `recordStats()` if dashboards need it; not done.

## Timeline

- **2026-11-13** - plan reviewed, Caffeine accepted, implemented (status `proposed` until the PR merges)
