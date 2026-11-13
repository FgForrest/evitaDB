---
title: Reuse formula results by declared value identity, inject complete tuples at execution, and disable the owning cache on a verified mismatch
date: 2026-11-14
updated: 2026-11-14 00:50
status: accepted
kind: refactor
issues: [37]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/cache, evita_engine/src/main/java/io/evitadb/core/query, evita_engine/src/main/java/io/evitadb/index/hierarchy, evita_engine/src/main/java/io/evitadb/core/metric/event/cache, evita_api/src/main/java/io/evitadb/api/configuration, evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/observability/metric]
supersedes: []
superseded-by: []
relates: [2026-08-28-index-lifetime-formula-memoization, 2026-08-04-query-telemetry-actionable-profile, 2026-08-24-price-histogram-per-accessor-granularity, 2026-09-15-non-collapsible-formula-marker, 2026-09-18-range-index-counting-kernel, 2026-10-08-scope-faithful-planning-and-facet-summary-parity, 2026-10-20-single-owner-partitions-as-registry-rows, 2026-10-20-structure-guards-yield-to-javadoc-and-runtime-failures, 2026-09-23-internal-member-annotation, 2026-05-06-reference-histogram-statistics]
---

# Formula results are reused by declared identity and verified at execution

Formula deduplication and cross-query result caching use complete declared identities with value equality. The
legacy structural-hash and transactional-id-hash APIs, anteroom/eden cache, entity cache, flattened payloads and
their serializers are removed. One weighted concurrent cache per engine retains eligible bitmap, price and
histogram tuples. Hits fill existing nodes at execution. A sampled mismatch fails the detecting query and disables
that engine's cache. A separate plan detector compares a preferred execution with a freshly planned, key-free
baseline. Periodic statistics and per-detection mismatch events expose the cache's behavior.

## Why

A hash collision was treated as computational equality in both deduplication and caching. This could change answers
even with caching disabled. Primary-key range bounds folded to the same value; merged facet terms omitted plain
bitmap content; hierarchy filtering and descendant filtering could share an identity; requested attribute ranges
could be exchanged while histograms still read the surviving formula's predicate. Price mode and other side-output
inputs needed identity too: equal record sets alone do not make two formulas substitutable.

The durable constraint is that a reused computation must agree in every output a later consumer observes, without
retaining a session, catalog generation or execution context. Versioned committed operands provide freshness, but
that guarantee does not apply to warm-up writes or an ambient transaction. Prefetch runs have a different available
entity universe, and the execution mode is not known during eager planning evaluation.

### Previous state

The cache accumulated candidates in an anteroom and promoted them to eden using frequency and cost-to-performance
rules. Formula and transactional-id hashes decided reuse; cached subtrees were represented by flattened payload
formulas. Entity bodies participated in the same caching machinery. Deduplication also trusted the pair of hash
values. Formula construction eagerly maintained legacy hash and transactional-id arrays, even for computations
that would never reach the cache. Marker interfaces protected structure that substitution could hide.

## Options considered

| Option | Benefit | Decision and concrete reason |
|---|---|---|
| Declared records, detached keys and real equality | Makes semantic inputs reviewable; collisions cannot alone equate computations | **Chosen.** Hashes select buckets and reject unequal candidates; record and ordered-child equality decides reuse. |
| Keep the anteroom/eden cache and repair individual hash folds | Smaller local fixes and existing promotion machinery | **Rejected because** equal hash values still have no equality witness, and every omitted side output can change a surviving node's behavior. Repairing one fold cannot establish substitutability. |
| Mix migrated keys with a legacy-identity adapter | Incremental consumer migration | **Rejected because** a legacy holder can fold a migrated side child's hash back into the unsafe long-only identity. Build keys alongside the old path, switch consumers together, then delete that path. |
| Keep a live constraint object as an alternative's identity | Avoids formatting and copying | **Rejected because** its argument array can be mutated. Attribute alternatives capture immutable, type-faithful argument snapshots, including range precision and implicit query moment; unsupported forms are opaque. |
| Use input-object reference identity for arbitrary functions | Could merge holders using the same function object | **Rejected because** functions can carry mutable cursors. Opaque identities bind to the holder instance; a newly allocated clone remains distinct. Restartable price predicates also give clones fresh cursors. |
| Persistent one-slot equal-key memo | Cheap repeated comparisons | **Rejected because** an asymmetric side DAG still required 2,097,151 comparisons for 63 nodes in the design probe; persistent links also retain other key graphs and introduce races. A comparison-scoped pair memo bounds repeated work and releases references on exit. |
| Treat all conjunction/disjunction children as commutative | More deduplication hits | **Rejected because** price outputs concatenate in child order and first-wins readers observe it. Formula children stay ordered; only proven commutative raw operand families canonicalize, retaining multiplicity. |
| Replace a subtree with a cached payload leaf at planning | Could avoid descending into a hit subtree | **Rejected because** it hides structure read by later consumers, needs several replacement shapes, and makes a result available before prefetch mode is known. Inject complete tuples into existing nodes at compute instead. |
| Cache per catalog or collection | Natural lifecycle ownership | **Rejected because** catalog instances change per version, rename/replace/recreate need hooks, nested queries cross collections and separate budgets fragment memory. Producer identities already distinguish data sources. |
| Estimated-cost admission plus the concurrent cache's frequency policy | Floor is known before lookup; no second candidate store | **Chosen.** The anteroom/usage threshold duplicates frequency filtering. Observed time is unavailable before a miss; the removed cost-to-performance chain can compute children merely to price admission. A doorkeeper needs evidence of admission churn before adding it. |
| Read the transaction thread-local at every node | Directly checks every access | **Rejected because** planning and execution stay in one proxied call on one thread. Root and execution-context checks cover that lifetime without a thread-local read per bound node. |
| Serve eager planning computations, then clear injected memos if prefetch wins | Plan-time reuse | **Rejected because** every computed ancestor and other planning reference would also need clearing. Planning evaluation and prefetch bypass lookup and admission until their equivalence is specified and tested. |
| Compare prices only by amount or existing record equality | Fewer differences from ties | **Rejected because** equal amounts or internal IDs can conceal different physical records, SUM components and discount behavior. Full tuples and observable order are checked. The separate deterministic price tie fix is related work, not a weaker comparison rule. |
| Disable every cache in the JVM after a mismatch | Wider containment | **Rejected because** cache ownership and configured state are per engine. A global switch would disable unrelated embedded engines; the owning instance alone is disabled for its lifetime. |
| Automatically replan and retry after a mismatch | Hides the detecting error from the caller | **Rejected because** plan construction may already record capabilities and index activity, and ordinary execution has traffic-recording effects. Retrying can duplicate accounting and risks continuing with injected memos. Fail the detecting query; an explicit repeat bypasses the disabled cache. |
| Purge on each commit; maintain a reverse invalidation index | Reclaims dead entries immediately | **Rejected because** a purge discards unaffected operands' entries and adds cache work to the write path; a reverse index adds shared bookkeeping on every store. Expiry and weighted eviction reclaim unused entries. Revisit with measured dead retained weight. |
| Allow-list source/bytecode guards | Fails on unapproved structural sites | **Rejected because** harmless refactors break the list and an added list entry can clear a failure without proving behavior. Keep abstract identity contracts, runtime class-id checks, field-driven contract fixtures and counterfactual behavioral tests. |
| Deprecate obsolete cache options for one release | Preserves builder source compatibility temporarily | **Rejected because** the owner chose one configuration break instead of accepted-but-ignored options followed by another removal. The three anteroom options are removed; strict YAML rejects them and non-strict YAML warns and ignores them. |

## Decision

Use declared identity for deduplication and execution-time tuple reuse. A key contains the operation discriminator,
declared values, operand tokens, side keys and ordered child keys. Opaque or oversized identities refuse cross-query
reuse transitively. A cache entry retains detached keys and read-only tuples, never a formula or query context.

Keep the cache disabled by default. When enabled, resolve eligibility from a read-only session's pinned live catalog,
entity type and absence of an ambient transaction. Bypass eager planning evaluation and prefetch. Admit only
supported, completely sized results under the key, per-entry and total budget rules. Do not invalidate unaffected
entries on a commit or add a catalog version to every key.

Use sampled tuple recomputation as a production detector and key-free plan comparison as a separate debug detector.
Neither is a proof for every possible input. A confirmed sampled mismatch disables the owning engine's cache and
fails the detecting request; it does not undo already injected results in other in-flight queries.

## Key technical details

- [FormulaKey](../../evita_engine/src/main/java/io/evitadb/core/query/algebra/identity/FormulaKey.java) and
  [KeyAccumulator](../../evita_engine/src/main/java/io/evitadb/core/query/algebra/identity/KeyAccumulator.java):
  every identity component feeds the central hash and retained-weight model. Equality compares declared values and
  ordered children. Recursive comparisons share a thread-local pair scope cleared in `finally`, including exceptional
  exits; grown storage is released. Runtime construction checks the holder and identity class IDs agree.
- [FormulaDeduplicator](../../evita_engine/src/main/java/io/evitadb/core/query/filter/FormulaDeduplicator.java):
  intern after rebuilding over canonical children, with a pre-descent lookup for an already interned subtree.
  Its map belongs to one pass and is cleared when the result is obtained. This is plan deduplication, not a
  query-scoped result-sharing table.
- [FormulaCacheSupervisor](../../evita_engine/src/main/java/io/evitadb/core/cache/FormulaCacheSupervisor.java):
  one engine-owned cache; identity-visited binding walk; cost floor checked before requesting a candidate's key;
  query-local keys and keys above 4,096 weight units refused. A publication rechecks activity after `putIfAbsent`.
- [AbstractCacheableFormula](../../evita_engine/src/main/java/io/evitadb/core/query/algebra/AbstractCacheableFormula.java):
  memo first, then execution gate, lookup, complete tuple restoration or computation and admission. An unsampled hit
  sets the node's paid cost to zero; sampled hits keep recomputed tuples and normal cost. Clones start unbound.
  `clearMemory()` unbinds: mutable hypothetical ancestors can keep a stale memoized key and must never be analysed
  or interned after delegate replacement.
- [CachedResultWeigher](../../evita_engine/src/main/java/io/evitadb/core/cache/CachedResultWeigher.java) and
  [CachedResultEquivalence](../../evita_engine/src/main/java/io/evitadb/core/cache/CachedResultEquivalence.java):
  charge keys, overhead and complete payloads in `long`; refuse above one percent of budget or integer range.
  Unknown shapes, including `ArrayBitmap`, are unsized. Price arrays are frozen before publication. Bitmap
  verification checks size and membership in both directions; selling-price arrays preserve encounter order;
  only the LOWEST histogram-only array compares as a per-entity multiset. SUM maps compare complete components.
- [QueryPlanningContext](../../evita_engine/src/main/java/io/evitadb/core/query/QueryPlanningContext.java): root gate,
  independent fetch-root gate and shared verification/suspension state. `createVerificationSibling()` gives the
  detector a freshly planned baseline without deduplication, cache access, plan rendering or query recording.
  [QueryResponseComparator](../../evita_engine/src/main/java/io/evitadb/core/query/QueryResponseComparator.java)
  compares responses including fetched projections; cache tuple comparison is a different contract.
- [CacheOptions](../../evita_api/src/main/java/io/evitadb/api/configuration/CacheOptions.java): defaults match server
  YAML, including disabled caching, 10,000 estimated cost units, a budget of 25% of free heap at class initialization,
  600 seconds idle expiry and one-in-1,000 hit sampling. Zero budget selects the default; zero expiry or sampling
  disables that mechanism. Reflection caching is independent.
- [Cache events](../../evita_engine/src/main/java/io/evitadb/core/metric/event/cache) and the periodic hook in
  [Evita](../../evita_engine/src/main/java/io/evitadb/core/Evita.java): group `io.evitadb.cache`, category
  `Formula cache`, one-minute default statistics period. `FormulaCacheMismatchEvent` exports only catalog name,
  entity type and formula class. `EngineSettings.queryCacheEnabled` reports effective activity.

## Verification

The implementation log records the full functional gate on **1cf0115023: 34,319 tests, 0 failures, 0 errors**.
That gate includes the complete result cache and sampled verification; it predates the final metrics/lifecycle
test bulk. At **df0333bfe8** the latter bulk records **244 tests, 0 failures, 0 errors**, with repeated targeted runs.
Documentation regeneration ran `JfrDocumentation` and `JfrDocumentationStaticMetricsParityTest`: **1 test each,
2 total, 0 failures, 0 errors, 0 skipped**. The generated pages contain both new events, all **19 statistics metrics
and the mismatch invocation counter**, and none of the removed cache events or metrics. These are correctness runs,
not benchmarks. No performance measurements were made for the redesign; no latency, throughput or default-enablement
claim follows from these counts.

The proving tests and recorded counterfactuals are:

| Property | Tests and evidence |
|---|---|
| Complete identity, class IDs and bounded equality | `FormulaIdentityContractTest` in both test modules, `IdentityContractVerifierTest`, `FormulaClassIdUniquenessTest`, `FormulaKeyTest`, `KeyAccumulatorTest`, `FormulaDeduplicatorTest`. A disabled recursive comparison scope restored 16,777,216 leaf comparisons against a linear bound of 396; restoring it passed. Disabling grown-scope release retained 65,600 bytes where the test expected 576. |
| Identity errors visible without the cache | `EntityByPrimaryKeyFilteringFunctionalTest`, `FacetGroupTermsAcrossScopesFunctionalTest`, `AttributeHistogramRequestedRangeFunctionalTest`, `EntityByHierarchyFilteringFunctionalTest`; parent-source runs were red and declared-key consumer runs green. Facet scope membership, requested histogram buckets and the 58-node hierarchy difference were observed, not inferred from hashes. |
| Gate, ambient transactions, planning and prefetch | `FormulaCacheSupervisorTest` and `FormulaCacheTransactionIsolationTest`. Removing guards gave **38 tests / 8 failures**; removing both transaction checks gave **6 / 6 failures**, including wrong responses and forbidden cache probes. |
| Publication, caps, retention, tuple integrity and shutdown | `CachedResultWeigherTest`, `CachedResultEquivalenceTest`, `FormulaCacheSupervisorTest`, `FormulaCacheMutableAncestorTest`. Ordering mutants gave **5 / 5 failures**; retention/lifecycle mutants **5 / 5 failures**. Rate-zero corruptions deliberately serve a wrong answer; sampled counterparts throw and disable only the owning engine. |
| Independent plan detector and unchanged ordinary recording | `FormulaSharingDifferentialTest` rejects a deliberately incomplete identity, covers fetched references/projections and verifies populated-cache dry runs while the sibling makes zero key requests/lookups. `VerificationRecordingRegressionTest#shouldPreserveBypassedCacheRecording` compares results, telemetry, capability and index-activity accounting with parent sources in both default-off and enabled-but-gated modes. |
| Cross-query hits, payload readers and lifecycle | `FormulaCacheDifferentialTest`, `FormulaCachePayloadTest`, `FormulaCacheOracleTest`. The oracle runs **2 fixed seeds × 40 operations**, with sampling off/on, pinned readers and first reads after commits preserving price operands required to hit prior entries. Inverting the live-catalog gate gave **20 payload tests / 19 failures** and **2 oracle tests / 2 failures**. The final tests require population and warm hits before lifecycle changes, hits in alternating catalogs and a validity mutation visible to a cached price query. |
| Metrics | `FormulaCacheMetricsTest` uses public JFR recording to check deltas, cumulative exported totals, registration, period, labels and disabled silence. `FormulaCacheSupervisorTest#shouldEmitOnlyIdentifiersOncePerConfirmingDetector` checks each confirmed detection, including concurrent detections after shutdown. `JfrDocumentation` regenerates both references; `JfrDocumentationStaticMetricsParityTest` checks static-metric parity. |

## Consequences & open follow-ups

**Query-scoped result sharing is POSTPONED until measurements on real traffic.** It holds real potential for
speeding up queries and must not disappear from the backlog. Per-instance memoization and plan deduplication exist;
there is no additional table sharing results across separately built derived trees. From telemetry of production
listing queries, count **per query** how often equal subtrees, especially the mandatory filter, are computed across
the filter, filter without user filter, histogram relaxations, hierarchy-statistics bases and nested plans, and
measure the time spent, with the cross-query cache **off and on**. Present these numbers to the maintainer, then
design/build the table or decline it with the measured reason. If this line merges first, create a tracking issue
on the maintainer's instruction so the obligation survives retirement of the working plan. Never intern hypothetical
ancestors above a `MutableFormula`.

The final execution rulings are decisions taken, not open design questions:

| Ruling | Decision |
|---|---|
| R-1 | Verification dry runs in the query's own context use lookup, admission, sampling and cache counters under the ordinary gate. The key-free sibling never does. Suppressed query recording does not suppress cache statistics. |
| R-2 | “No cache at planning” means eager planning evaluation and prefetch, not verification executions called from `planQuery`. |
| R-3 | Each planning root decides eligibility independently, including body-fetch roots. Fetch roots carry shared sibling/execution state; they do not inherit an unrelated root's effective supervisor. |
| R-4 | Sampled recomputation suspends lookup and admission in every reached context, including captured contexts and independent fetch roots. Attribute histogram computation is initialized before analysis. |
| R-5 | Plan verification retains `QueryResponseComparator`; cached payload comparators remain separate. |
| R-6 | A confirmed mismatch fails the detecting request without an automatic retry. Construction may already have recorded capabilities/index activity; a dry-run failure may precede traffic recording. |
| R-7 | Bitmap comparison uses size and membership, including negative identifiers. The final comparator checks membership in both directions because duplicate-bearing representations defeat a one-way test. |
| R-8 | The kill switch belongs to the owning engine instance, not the JVM. Other engines remain active. |
| R-9 | Default-off and enabled-but-gated behavior is checked against parent-source results and recording fingerprints by test, not only inspection. |

**Metrics counters are deltas per emission.** `MetricHandler` adds every exported counter value, so repeatedly
exporting cumulative supervisor totals would overcount. The first emission includes totals since creation; gauges
report current observations. The engine hook serializes read, commit and baseline advancement with a lock outside
the query path, advancing only for enabled emissions. Counter differences are clamped at zero; a future regressed
source observation is not repaired by that clamp and could overcount on the next emission.

**Memory is an estimated target with transient overshoot.** It excludes query memos and duplicate computations in
flight. There is no single-flight loader. Idle reclamation is best effort. An unsampled hit's zero paid cost is
node accounting and may omit work subsequently requested below it; it is not a measurement of lookup cost.
Sampling can reuse existing query memos and cannot retract injected results in other queries already in progress.

**Not done:** coarse index-id keys for wide lookup results; a planner cache peek before choosing prefetch;
replay counters for prefetch choice, warm index paths and storage read/deserialization time; per-class probe events;
quiet-machine measurements. A referenced-type index ID alone is unsound for a whole `referenceHaving`: changes in
reduced indexes or referenced/group collections need not change that ID. Any composite coarse identity needs its
own freshness proof. Existing direct and named derived producer tokens do not implement that postponed proposal.

The measurement obligation also covers interleaved no-cache/default-floor/zero-floor/100,000-floor arms, read-only
and commit-stream runs; planning versus execution cost, latency distributions, throughput and allocation; key and
payload sizes, refusal causes, duplicate work, eviction/dead retained weight, query-local identities and maintenance
cost. Measure the disabled path and wide reference/filter-heavy planning before drawing performance conclusions.
No such traffic replay, benchmark or quiet-machine comparison has been performed. Enabling by default remains a
separate decision.

**Known test limit:** lifecycle hit assertions read the statistics surface. A cache that counts a hit but serves
nothing is caught only by the plan-inspecting `shouldRestoreSharedSubtree`, `shouldAccountForConcurrentQueries` and
the oracle comparison, not by those lifecycle counters alone.

Open inspection findings are retained as **Suspected, not proven**, rather than silently promoted to defects:

- **Named-module deployment:** `MetricHandler` reflects on private event fields via `ReflectionLookup`, which calls
  `setAccessible(true)`. On the module path an event package not opened to `evita.common` would fail that access.
  The new cache event package opens only to `jdk.jfr`, as existing transaction/storage event packages do; the cache
  package was not opened on `dev` either. This concern is pre-existing for other event packages, not a demonstrated
  regression. No module-path launch exists in the repository and no such launch was run. **Suspected, not proven.**
- The restricted-universe equivalence needed to admit prefetch results or inject full-index results into prefetch
  is unproved. Both directions and eager-planning cache use remain bypassed; there is no demonstrated wrong result
  through the guarded implementation. **Suspected, not proven.**
- `ExtraResultPlanningVisitor` performs a context-dependent strip on nodes that may be shared by mandatory and user
  filters. Whether this changes an answer was not reproduced. **Suspected, not proven.**
- Aggregation lambdas that ignore histogram leaf tokens were left for further cache analysis; no stale-hit witness
  was established for them. **Suspected, not proven.**
- The wider investigation also left explicit decimal-range precision loss in REST/GraphQL/EvitaQL and a stale
  price-for-sale memo in a builder created from a fetched entity unproved. They are separate from this cache and
  were not fixed here. **Suspected, not proven.**

The earlier suspicion that replacing a mutable facet delegate leaves ancestor keys stale **was proven**, and is
handled by the no-analysis/no-interning rule plus unbinding on `clearMemory()`. Earlier suspicions about price ties,
range change detection and absent collection removals were reproduced and addressed in separate work; they are not
open cache findings.

## Related work

- [Reference histogram statistics](2026-05-06-reference-histogram-statistics.md) — reference histogram accumulation
  also delegates to the cacheable attribute histogram computer; it uses the same complete identity and payload rules.
- [Internal-member annotation](2026-09-23-internal-member-annotation.md) — distinguishes literal `CLASS_ID`
  uniqueness checking from scanning member references. Class IDs remain generated discriminators; every concrete
  identity holder must declare its own matching pair.
- [Index-lifetime bitmap memoization](2026-08-28-index-lifetime-formula-memoization.md) — its no-query-state
  retention invariant remains. The bitmap content-hash memo described there is removed with the legacy hash path;
  named derived producer tokens identify the supported index memos.
- [Query telemetry](2026-08-04-query-telemetry-actionable-profile.md) — preserves rendering without computation;
  `hash` now reports the process-local declared key's `getHash64()`, never an equality proof.
- [Per-accessor price histograms](2026-08-24-price-histogram-per-accessor-granularity.md) — complete cached tuples
  preserve selling-price and optional per-inner-record histogram outputs.
- [Non-collapsible carriers](2026-09-15-non-collapsible-formula-marker.md) — structural readers still need their
  carriers. Cache injection preserves structure; removal of cache markers does not remove `NonCollapsibleFormula`.
- [Range counting](2026-09-18-range-index-counting-kernel.md) — multiplicity remains in identity. The previously
  untested read-only gate is covered, including the enclosing-transaction escape.
- [Scope-faithful planning](2026-10-08-scope-faithful-planning-and-facet-summary-parity.md) — the verification sibling
  suppresses its own recording, while ordinary capability/index activity behavior is preserved.
- [Single-owner partitions](2026-10-20-single-owner-partitions-as-registry-rows.md) — keeps the obligation that
  formula invalidation must not widen to every unrelated write.
- [Structure-guard policy](2026-10-20-structure-guards-yield-to-javadoc-and-runtime-failures.md) — field-driven
  identity fixtures and runtime invariants replace allow-list guards.
- The separately reviewed **#1938 price tie fix** is included as related prerequisite work: deterministic physical
  record selection makes strict sampled verification meaningful. It is not part of the cache redesign's comparison
  policy. Other price-for-sale agreement changes remain separate.

## Timeline

- **2026-11-11** — cache-off identity defects reproduced; old cache removal and identity design started.
- **2026-11-12** — declared identities and contract fixtures implemented; configuration reshaped after the removal
  decision; comparison-scoped equality replaced the persistent memo design.
- **2026-11-13** — consumers switched, legacy hashes removed, plan detector and result cache implemented and
  counterfactually checked; full functional cache gate passed at 1cf0115023; metrics and lifecycle/oracle tests added.
- **2026-11-14** — final metrics/lifecycle strengthening recorded at df0333bfe8; accepted decisions consolidated in
  this record. Pull request not yet open; performance and query-scoped sharing decisions remain deferred.
