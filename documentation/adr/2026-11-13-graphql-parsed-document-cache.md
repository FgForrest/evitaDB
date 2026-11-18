---
title: Cache parsed and validated GraphQL documents per GraphQL instance in a Caffeine cache bounded by bytes
date: 2026-11-13
updated: 2026-11-18 12:12
status: accepted
kind: optimization
issues: [1191]
prs: [1958]
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
- Metrics: on a hit `GraphQLDocumentCache` marks the request's `ExecutedEvent` (`markDocumentCached()`, found in
  the GraphQL context), and `RequestMetricInstrumentation#beginExecuteOperation` then calls
  `ExecutedEvent#skipParseAndValidation()` instead of `finishValidation()`. The hit is reported by the cache rather
  than inferred from the missing `beginParse` callback, because a hit failing before execution (unknown
  `operationName`, variable coercion) never reaches `beginExecuteOperation`, and an aborted request lacks
  `beginParse` too. Parse and validation histograms record
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

- `GraphQLDocumentCacheTest.MetricsTest` (follow-up of 2026-11-18) - requests failing before execution on a miss
  and on a hit (syntax, validation, unknown `operationName`, variable coercion): bounded durations, `documentCached`
  set on a hit, the failing phase closed. Before the fix 6 of these failed (operation duration ~1.8 × 10¹² ms).
- `GraphQLDocumentCacheTest` - hit/miss, errors never cached, the byte bound (documents fitting the budget are all
  retained, ≥ 90 of 100 re-parsed with a budget of 10), concurrent cold start (16 threads, shared and distinct
  keys), wiring and the disabled cache, option validation, and the metric path on a hit (fails with `GraphQLInternalError` without the metrics fix).
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
  session is put into the context only in `beginExecuteOperation`, and graphql-java completes the validation context
  with a `null` throwable) - pre-existing defect, unaffected by the cache (error entries are never cached), reported
  as a separate issue.
- Fixed in a follow-up on 2026-11-18 (`GraphQLDocumentCacheTest.MetricsTest`): a hit failing before
  `beginExecuteOperation` reported `documentCached=false`, and - pre-existing, independent of the cache - every
  request failing before execution exported the current epoch time in milliseconds as its operation duration
  (`finishOperationExecution()` was unguarded), as did the result serialization of every system API response. Such
  requests now report zero operation / serialization time and the duration of the phase they failed in.
- A hit-ratio metric could be fed from Caffeine's `recordStats()` if dashboards need it; not done.

## Timeline

- **2026-11-13** - plan reviewed, Caffeine accepted, implemented, PR #1958 merged
- **2026-11-18** - metrics of requests failing before execution fixed (hit flag set by the cache, guarded durations)
