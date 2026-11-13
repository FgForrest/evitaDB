---
title: Cache
perex: The formula result cache reuses eligible intermediate computations across queries, with memory budgeting and sampled verification.
date: '14.11.2026'
author: 'Ing. Jan Novotný'
---

The formula result cache retains intermediate query computations in memory for reuse by later queries. Each engine
instance owns one cache shared by its catalogs. It is **disabled by default** and can be enabled through
[cache configuration](../operate/configure.md#cache-configuration).

## What is cached

Queries are evaluated as graphs of formulas. A formula computes a set of record identifiers, represented internally
as a bitmap. Eligible formulas can cache that bitmap. Price computations also retain the resolved price records
their consumers need, including per-inner-record prices when requested for a histogram. Attribute and price
histogram computations can retain their histogram results.

The cache does not retain entity bodies, complete query responses or query plans. A hit fills the existing formula's
result and price outputs; the formula and its children remain in the plan.

Each computation declares its **identity**: its operation, relevant parameters, data sources and ordered child
computations. Data sources are identified by versioned producer identifiers or bounded copies of their content.
For example, the price mode and requested range can matter even when two computations select the same records,
because their price outputs or histogram annotations may differ. Reuse requires equality of the complete identity;
a matching hash alone is insufficient.

A committed change to an operand changes its identity. Entries for unchanged operands can still be reused across
catalog versions, including by a session reading an older version. There is no blanket cache purge on each commit.
Keys and results remain in the running process and are never persisted or shared between servers.

Within a plan, deduplication can merge formulas with equal identities into one instance. Each instance memoizes its
own result. There is no separate query-scoped table sharing computed results between independently built derived
trees. Cross-query caching and per-instance memoization are distinct mechanisms.

## When a query can use the cache

A planning root can use the cache only when all of these conditions hold:

- caching is enabled and this engine's cache is active;
- the session is read-only;
- the catalog pinned by that session is live (`ALIVE`);
- the request targets an entity type;
- no transaction is bound to the executing thread, including a transaction belonging to an enclosing session call.

Nested planning contexts inherit their parent's decision. Entity-body fetching can create an independent planning
root, which checks eligibility for its own request. A read-only session alone is therefore insufficient to make a
query eligible when it is called inside another session's transaction.

Eager formula evaluation while building a plan bypasses both cache lookup and admission. Execution over prefetched
entities also bypasses both: its set of available entities can differ from the full index universe. Verification
dry runs of plans in the query's own context are executions and can use the cache under the same rules.

Eligibility does not guarantee a hit. Only supported formula and histogram computations meeting the configured
estimated-cost floor are candidates. An identity with unsupported inputs, such as an arbitrary function, is bound
to its holder instance and cannot be reused across queries. Oversized identities and ancestors depending on them
also stay outside the cross-query cache.

## Memory budget and admission

`cacheSizeInBytes` sets a target for the estimated retained size of entries. The charge includes the result, key and
entry overhead. Price payloads include both arrays and any component maps; histogram payloads include their buckets
and values. Unsupported retained representations are refused rather than assigned a guessed small size.

An entry is refused if its charge exceeds one percent of the configured budget or the supported integer weight
range. Keys above 4,096 weight units are ineligible. These limits are implementation limits, independent of
`minimalComplexityThreshold`, which is expressed in estimated computation cost units.

The concurrent cache manages admission and eviction within this weighted budget. The budget is an eventual target:
concurrent admissions can temporarily overshoot it. It does not bound the memory of queries in progress. Concurrent
misses for one key can compute the same result independently; only one result is published, and duplicate work is
counted.

`dropEntriesAfterSecondsOfInactivity` controls expiry after the last access and the periodic maintenance interval.
Reclamation is best effort, including when the cache is idle. A value of `0` disables idle expiry and its timer;
budget-based eviction still applies. Entries that no query can use after a data change remain until eviction,
expiry or engine shutdown reclaims them.

## Verification and mismatch handling

`verifyOneHitIn` gives each hit a random chance of recomputation. The default is one in 1,000 hits; `1` verifies every
hit and `0` disables sampling. During recomputation, cache lookup and admission are suspended throughout the query's
reached execution contexts, including independent fetch roots. The formula retains its recomputed result.

Verification compares complete result tuples: bitmap size and membership, full price records and component maps,
and histogram contents. Selling-price record order is checked where consumers depend on it; the histogram-only
per-inner-record output permits order differences within an entity.

A confirmed mismatch fails the detecting query with an internal error. The engine records a bounded diagnostic,
logs the first mismatch, emits a mismatch event for each confirmed detection and disables its formula result cache
for the rest of that engine instance's lifetime. Retained entries are discarded. A repeated request runs with this
cache disabled; other engine instances are unaffected. There is no automatic re-enable within the same instance.

Sampling is a detector, not a proof that every served result is correct. A recomputation may reuse results already
memoized in that query. Results injected into other queries already in progress are not retracted when the switch
turns off.

## Enable and observe

The server YAML below enables caching with the default cost floor, budget, expiry and verification rate:

```yaml
cache:
  enabled: true
  reflection: CACHE
  minimalComplexityThreshold: 10K
  cacheSizeInBytes: null
  dropEntriesAfterSecondsOfInactivity: 600
  verifyOneHitIn: 1000
```

See [cache configuration](../operate/configure.md#cache-configuration) for every option, its units and zero-value
behavior. Reflection metadata caching is controlled independently by `reflection`.

The [JFR event reference](../operate/reference/jfr-events.md#formula-cache) lists `FormulaCacheStatisticsEvent` and
`FormulaCacheMismatchEvent` under **Formula cache**. Their recording group is `io.evitadb.cache`; see
[observability](../operate/observe.md#list-event-groups-to-include-in-recording) for recording setup. The statistics
event has a default period of one minute. Its counters are deltas since the previous emission; the first emission
includes work since cache creation. Gauges report current observations. When caching is configured off, the engine
registers no cache statistics hook and emits neither cache event.

The [metrics reference](../operate/reference/metrics.md#formula-cache) describes the corresponding counters and
gauges. Observe hits and misses, unique and duplicate admissions, size refusals, prefetch and transaction bypasses,
evictions, verification work and mismatches, together with retained entries, retained weight, budget and the active
flag. Statistics are weakly consistent under concurrent work. Mismatch events export the catalog name, entity type
and formula class as labels. `EngineSettings.queryCacheEnabled` reports the effective enabled and active state.

[Query telemetry](../query/requirements/telemetry.md#the-formula-plan) shows the existing formula graph. An unsampled
hit records zero paid computation cost on the restored node. Its descendants may still be evaluated later if another
reader needs their outputs. A sampled hit keeps normal computation cost. Neither cost is a measurement of cache
lookup latency.

Cache usefulness depends on the workload, repeated computations, writes and the memory budget. No performance
measurements are available for this implementation, so enabling it carries no latency or throughput promise.
