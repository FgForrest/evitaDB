---
title: Usage statistics are switchable off, and the absence is reported as "not measured" rather than as zero
date: 2026-08-23
updated: 2026-08-24 09:15
status: accepted
kind: feature
issues: [1429]
prs: [1430]
areas: [evita_api/api/configuration, evita_api/api/statistics, evita_engine/index, evita_engine/index/usage, evita_engine/core/query, evita_engine/core/collection, evita_engine/core/catalog, evita_store/evita_store_server, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: [2026-08-16-per-index-usage-statistics, 2026-08-19-per-schema-capability-usage-statistics]
---

# Usage statistics are switchable off, and the absence is reported as "not measured" rather than as zero

`server.usageStatisticsTracking` (default `true`) gates both usage-counting surfaces at once — the
per-index `IndexActivity` counters and the per-schema-capability registry. With it off no activity
holder is allocated for any index, and neither the query nor the write path resolves a capability
holder. The management calls keep working and keep listing every index and every declared capability;
each row simply carries `measured = false`.

## Why

The two prior records left the cost question half-answered. The JMH gate in
[2026-08-19](2026-08-19-per-schema-capability-usage-statistics.md) measured **read-path throughput**
and found no regression, which is true and was read as "there is nothing to switch off". Two costs sit
outside what that benchmark can see:

- **Footprint.** `EntityIndex.getBaseHeapSizeInBytes` charged `sizeOfObject(5 * Long.BYTES)` = 56 B per
  index unconditionally. A large production catalog holds hundreds of thousands of indexes, so this is
  megabytes of pure telemetry that no throughput benchmark observes.
- **The write path was never benchmarked at all**, and `EntityIndexLocalMutationExecutor.applyChanges`
  does a `ConcurrentHashMap` resolve per touched capability plus a linear `touchedElements` dedup scan
  per entity mutation.

There is also a per-candidate-plan cost on the read path that the benchmark's two scenarios did not
stress: `QueryPlanner` re-translates the filter **once per candidate index set** (`QueryPlanner:348`),
so `recordRequestedCapability` runs N×M times per logical query, each run minting a
`SchemaCapabilityKey` and hashing it. The accumulator dedup happens on the *resolved holder*, after the
allocation and the lookup, so it bounds the flush but not the lookups.

## Options considered

### Where the switch lives

`ServerOptions` in `evita_api`. **Chosen.** The engine already reads it (`Evita`, `SessionKiller`,
`CollationKeyCacheSweeper`).

`ObservabilityOptions` — the original proposal. **Rejected because** it is mechanically impossible:
`evita_external_api_observability` depends on `evita_engine`, never the reverse, and every measurement
site is in the engine. It is also the wrong semantics — these counters feed the **gRPC management API**
(`BrowseIndexes`, `ListSchemaCapabilityUsage`), not the Prometheus endpoint, so gating them there would
mean disabling one API to silence another. `allowedEvents`, the knob that *does* live there, gates
event *export* inside the observability module and cannot reach engine counters for the same reason.

### How "off" removes the holder

`@Nullable EntityIndex.activity`, allocation skipped. **Chosen.** Recovers the 56 B per index and makes
the absence unforgeable. Costs a null check at five projection sites and three recording sites.

A shared immutable `IndexActivity.NOT_TRACKED` sentinel. **Rejected because** it keeps
`@Nonnull getActivity()` handing callers an object that claims to have been observed, and any future
site that calls `recordQuery` on it without checking the flag silently accumulates one global counter.
The heap charge would still need a special case, so it saves nothing it was supposed to save. Revisit
only if the null checks ever show up in a profile, which they will not — they are predictable branches.

Lazy allocation on first record. **Rejected because** it breaks the documented meaning of
`observedSince`: an index never queried would have no window at all, and "not queried in the 17 days
observed" is exactly the statement the feature exists to make.

### What the read surfaces report when off

An explicit per-row `measured` flag. **Chosen**, adjudicated with Johnny.

Returning zero counts against a live window. **Rejected because** it is actively harmful: it reads as
*"nothing uses this flag, drop it"*, which is the destructive conclusion this whole surface was built to
inform. A wrong answer here costs a schema mutation.

Returning empty. **Rejected because** it is indistinguishable from "this schema declares nothing".

A response-level flag rather than per-row. **Rejected because** `UnusableCatalog.listCapabilityUsage()`
already returns rows for a catalog that is not loaded, which would force a third state to be invented
for its response-level flag; and eight `List<SchemaCapabilityUsageStatistics>` signatures would have to
become a wrapper. The marker belongs next to the counts it qualifies, exactly like `observedSince`.

## Decision

One boolean, `ServerOptions.usageStatisticsTracking`, default `true` — a diagnostic that must be
switched on before it can answer is one nobody has switched on when the question is finally asked.

## Key technical details

- **`alignWith` keeps running with the switch off.** It executes on collection creation and schema
  adoption, never on a query or a write, so it costs nothing measurable and preserves the one fact that
  stays true without counters: *which capabilities the schema declares*. This is why capability rows
  still exist when tracking is off — keys present, counts zero, `measured=false`.
- **The switch is resolved per catalog, never per index** (`Catalog#isUsageStatisticsTracked`). A
  catalog holding some observing and some non-observing indexes would report two different meanings for
  the same zero, and the setting cannot change under a running catalog anyway.
- **Fresh-construction sites take the flag; merge-copy sites do not.** The commit-time copies pass the
  holder (or the null) through by reference, unchanged. The sites that decide are `EntityCollection`'s
  four, the two restore-from-storage reload plans via `LoadContext#createActivity`, and `Catalog`'s
  three `CatalogIndex` creations.
- **The record constructors reject the contradiction.** `SchemaCapabilityUsageStatistics`,
  `BrowsedIndex` and `IndexDetail` all throw when `measured == false` alongside non-zero counts or
  populated stamps, so a projection bug cannot deliver an uninterpretable row to an operator.
- **On the wire `measured` is presence-tracked (`google.protobuf.BoolValue`), not a bare `bool`.** A
  bare `bool` defaults to `false`, so an older server's silence would decode as *"not measured"* — and
  that is wrong twice over: such a server had no switch and therefore always measured, so the decode
  would discard a whole server's real counts, and it would hand the record a self-contradictory row
  (unmeasured, yet carrying counts) that its own premise check rejects with an exception. Absent
  decodes as `true`; only an explicit `false` means counting was switched off.
- **The ranked page cut must not substitute the frozen ranked value when there is no holder.** Under
  `ENTITY_COUNT` that value is the entity count, which stays real with the counters off, so reporting it
  as `queryCount` would invent traffic out of a cardinality — and trip the same premise check. Both
  counts are `0` whenever the holder is absent, whatever the ordering.
- **Existing three-argument index constructors were kept**, delegating with
  `DEFAULT_USAGE_STATISTICS_TRACKING`. ~80 test call sites use them and none of those cares about the
  switch.

## Verification

`UsageStatisticsTrackingTest` (11 tests) covers the default, the builder and its copy constructor, the
absent holder on `GlobalEntityIndex` / `ReducedEntityIndex` / `CatalogIndex`, the heap charge dropping
when the holder is absent, capability rows surviving with `measured=false`, and all three record
constructors rejecting the contradictory shape.

Two defects found by adversarial review after the first implementation, both of which threw at runtime
rather than reporting wrongly, and both now covered by **calibrated** tests — each was re-run against the
reinstated defect and confirmed to fail:

- `IndexBrowseProjectionTest.UsageStatisticsOff` (3 tests) — a ranked browse over an unmeasured catalog.
  `shouldNotLeakTheRankedValueIntoTheCountsWhenUnmeasured` and `shouldRankByAnAbsentCounterWithoutThrowing`
  both error against the substituted ranked value.
- `CatalogStatisticsConverterTest.shouldDecodeAServerPredatingTheMeasuredFlagAsMeasured` — errors when the
  absence is decoded as `false` instead of `true`.

Regression batch over the two prior records' suites — `IndexBrowseProjectionTest`,
`SchemaCapabilityUsageProjectionTest`, `SchemaCapabilityUsageRegistryTest`,
`CatalogStatisticsConverterTest`, `IndexActivityTest`, `IndexUsageStatisticsTest`,
`EntityIndexLocalMutationExecutorUsageTest`, `EntityCollectionUsageRegistryTest`,
`RequestedCapabilityAccumulationTest`, `CatalogIndexHeapSizeTest`, `EntityIndexRoundTripTest`,
`EntityIndexReloadPlanSymmetryTest` and the three mutator suites — **244 tests, 0 failures.**

**Not measured:** the reclaimed footprint and the write-path saving were argued from the accounting
(`EntityIndex.getBaseHeapSizeInBytes:882`) and from the code, not from a benchmark. The write path
still has no JMH gate at all; `wal-replay-profiling` is the harness that would provide one.

## Consequences & open follow-ups

- **evitaLab must learn the third state.** Issue
  [FgForrest/evitalab#485](https://github.com/FgForrest/evitalab/issues/485) describes *declared* vs
  *not declared*; a present-but-unmeasured row is a third case that did not exist when it was filed. A
  client that renders it as "never requested" reproduces exactly the false statement the marker exists
  to prevent.
- **No JMH evidence for what the switch saves.** Anyone tempted to remove it should measure first — the
  argument here is structural, and a structural argument is not a measurement.
- **The switch is server-wide and start-time only.** Per-catalog granularity and runtime toggling were
  not built; runtime toggling in particular would need an answer for what `observedSince` means on an
  index whose observation began midway.

## Related work

- [2026-08-16-per-index-usage-statistics](2026-08-16-per-index-usage-statistics.md) — the `IndexActivity`
  holder whose allocation this switch elides.
- [2026-08-19-per-schema-capability-usage-statistics](2026-08-19-per-schema-capability-usage-statistics.md)
  — the capability registry whose hot paths this switch short-circuits; its JMH gate is the measurement
  discussed under *Why*.

## Timeline

- **2026-08-23** — dependency direction analysed, the two forks (holder removal, absence reporting)
  adjudicated with Johnny, implemented and gated
