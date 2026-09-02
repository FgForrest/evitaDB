---
title: Conditionally index per-reference attribute histograms via a dedicated HistogramIndex family
date: 2026-04-23
updated: 2026-07-31 12:00
status: accepted
kind: feature
issues: [8]
prs: [1136]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/schema, evita_engine/src/main/java/io/evitadb/index, evita_engine/src/main/java/io/evitadb/core/expression/trigger, evita_engine/src/main/java/io/evitadb/core/catalog]
supersedes: []
superseded-by: []
relates: [2026-05-06-reference-histogram-statistics, 2026-05-27-range-and-multi-histogram-schema, 2026-04-23-conditional-facet-indexing, 2026-08-31-cross-entity-histogram-removal-pre-pass]
---

# Conditionally index per-reference attribute histograms via a dedicated HistogramIndex family

A reference (e.g. Product → ParameterValue) can now declare one or more named **bucketed
histograms**: a value expression picks the numeric bucket for each referenced entity, an optional
condition expression decides which references participate, and the engine keeps a per-group
(or per-reference-type, for ungrouped references) histogram index up to date incrementally —
through schema changes, attribute mutations on either side of the reference, group moves, and
scope changes — without a full catalog rebuild.

## Why

Issue #8 asked for a dynamic per-reference attribute histogram: e.g. "distribution of `height`
values among Products referencing INTERVAL-typed Parameters", where `height` lives on the
*referenced* entity (ParameterValue) and only applies to references whose *group* entity
(Parameter) satisfies a condition (`inputWidgetType == 'INTERVAL'`). Nothing like this existed:
`AttributeHistogram` only computes over the owning entity's own attributes, and no index tracked
"bucket value → owner PKs" for reference-scoped, conditionally-participating data.

evitaDB had already solved the adjacent problem for facets — conditional per-reference indexing
driven by expression triggers, cross-entity re-evaluation, and a deferred local-mutation queue
(see [`2026-04-23-conditional-facet-indexing`](2026-04-23-conditional-facet-indexing.md), issue #18). The constraint that made this non-obvious was making
histograms conditionally participate and cross-entity-update *without* duplicating that entire
trigger machinery for a second, structurally similar feature.

### Previous state

References carried `faceted` / `facetedPartially` (boolean-per-scope conditional indexing into a
`FacetIndex`) but no numeric/bucketed concept at all. `ReducedGroupEntityIndex` and
`ReferencedTypeEntityIndex` had no histogram-shaped storage. The expression-trigger system
(`FacetExpressionTrigger`, `CatalogExpressionTriggerRegistry`) existed solely for facets, with no
shared base for a second trigger type.

## Options considered

Extending the existing facet-trigger architecture to a second data shape (histograms) was treated
as the only viable path — the source documents propose no alternative overall architecture (e.g.
computing histograms on the fly, or a separate non-trigger-based mechanism), and none is evident
in the shipped code either. Two concrete forks *within* that extension were explicitly argued:

### Fork — one unified cross-entity mutation, or two parallel ones

**Option A — unify `ReevaluateExpressionMutation` for both facet and histogram triggers (chosen).**
Both mutation shapes need identical fields (`referenceName`, `mutatedEntityPK`, `dependencyType`,
`scope`); a single executor partitions the triggers it finds by `instanceof` and dispatches to
facet-branch or histogram-branch logic.

- **Pros:** no duplicate mutation class, executor, `IndexMutationExecutorRegistry` entry, or WAL
  serializer; naturally coalesces the case where the same attribute change fires both a facet and
  a histogram re-evaluation on the same `(referenceName, scope)` into one mutation instead of two.
- **Cons:** the executor must partition triggers and evaluate the facet condition and the
  histogram condition *separately* even though PK resolution is shared, because
  `facetedPartially` and `bucketedPartially` are independent expressions.

### Option B — a dedicated `ReevaluateHistogramExpressionMutation` (declined)

- **Rejected because:** the fields are identical to the facet mutation, so a second class buys no
  new capability — only a second executor, a second serializer, and deduplication complexity
  wherever a single attribute change would otherwise need to fire both mutation types for the
  same target.

### Fork — a separate `histogramCardinalities` map vs. reusing `cardinalityIndexes`

**Option A — a separate `TransactionalMap<String, AttributeCardinalityIndex>` keyed by histogram
name (chosen).**

- **Pros:** histogram removal (all-entries-for-a-name) is a single map-key removal, not a scan;
  no change to `AttributeIndexKey`, which is used uniformly across the codebase.
- **Cons:** one more `TransactionalMap` with its own lifecycle wiring (`createLayer`,
  `createCopyWithMergedTransactionalMemory`, etc.), duplicating the pattern already present for
  `cardinalityIndexes`.

**Option B — fold histogram cardinality into the existing `cardinalityIndexes` map (declined).**

- **Rejected because:** `cardinalityIndexes` is keyed by `AttributeIndexKey` and iterated
  uniformly assuming `AttributeIndexType.CARDINALITY`; mixing histogram entries in would require
  either a discriminator on `AttributeIndexKey` (wide blast radius) or a parallel key set that
  negates the savings.

## Decision

**Chosen: extend the facet-trigger architecture rather than build a parallel one**, concretely via
the unified mutation (Option A above) and a shared `AbstractExpressionIndexTrigger` base class
that `FacetExpressionTrigger`/`HistogramExpressionTrigger`-backing implementations both extend —
verified in the shipped tree (see *Key technical details*). This reused the cross-entity trigger
registry, the deferred local re-evaluation queue, and session-optional query planning wholesale,
at the cost of a `instanceof`-partitioning step in the shared executor.

**Deviation from the plan, unexplained.** The originating document
(`conditional-bucket-indexing.md`, since retired) proposed storing histogram buckets **inside**
the existing `FilterIndex`, distinguished from ordinary attribute filter indexes by a new
`AttributeIndexType.HISTOGRAM` enum value on `AttributeIndexStoragePart`. The shipped code instead
introduces a dedicated `HistogramIndex` abstraction (`SimpleHistogramIndex` /
`LocalizedHistogramIndex`, see *Key technical details*) with its own storage parts
(`HistogramIndexStoragePart`, `HistogramCardinalityStoragePart`) — `AttributeIndexType` in
`evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/index/AttributeIndexStoragePart.java`
still has only `UNIQUE`, `FILTER`, `SORT`, `CHAIN`, `CARDINALITY`; no `HISTOGRAM` value was ever
added. No commit message or source document records why the storage design changed between plan
and implementation — flagged here rather than reconstructed. The net effect is the same
(a persisted bucket → owner-PK-bitmap structure per histogram name), so this is a mechanism change,
not unfinished work.

## Key technical details

Schema layer (`evita_api`):

- `HistogramIndexDefinition` — `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/HistogramIndexDefinition.java`.
  Immutable record: `nameOfTheIndex`, `nameVariants`, `valueExpression`, `assignedWhen`
  (see [[2026-05-27-range-and-multi-histogram-schema]] for how `assignedWhen` came to exist and
  why it isn't called `bucketedPartially`).
- `ReferenceSchema.bucketedInScopes` — `Map<Scope, Map<String, HistogramIndexDefinition>>` (already
  multi-histogram-per-scope shaped at this point; see
  [[2026-05-27-range-and-multi-histogram-schema]] for the annotation-level array surface that came
  later). `bucketedPartiallyInScopes` — `Map<Scope, Expression>`, the reference-level eligibility
  gate.
- `SetReferenceSchemaBucketedMutation`, `ScopedHistogramIndexDefinition`, `ScopedBucketedPartially`
  in `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/` — mirror
  the `SetReferenceSchemaFacetedMutation` family exactly (entire-state replacement, `null` means
  "inherited" for reflected references, "don't change" for direct ones).
- **Reflected references do not inherit `bucketed`/`bucketedPartially`.** Verified in
  `ReflectedReferenceSchema.java` (`evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java`,
  around the `_internalBuild` merge): `bucketedInScopes != null ? bucketedInScopes :
  Collections.emptyMap()` — an absent value resolves to *empty*, never to the declaring side's
  definitions. This differs deliberately from `facetedInherited`, because a histogram's value
  expression is direction-specific (`$reference.referencedEntity?.attributes[...]` resolves to the
  opposite entity type on the reflecting side).

Engine layer (`evita_engine`):

- `HistogramIndex` (abstract) / `SimpleHistogramIndex` / `LocalizedHistogramIndex` —
  `evita_engine/src/main/java/io/evitadb/index/`. Cardinality-gated: `insertValue` /
  `removeValue` only touch the underlying `FilterIndex` on a 0→1 / 1→0 cardinality transition.
- `HistogramCapableEntityIndex` — shared base mixed into `ReducedGroupEntityIndex` (grouped
  references) and `ReferencedTypeEntityIndex` (ungrouped references), each holding
  `TransactionalMap<String, HistogramIndex> histogramIndexes` keyed by histogram name.
- `AbstractExpressionIndexTrigger` — `evita_engine/src/main/java/io/evitadb/core/expression/trigger/`
  — the extracted common base for `FacetExpressionTrigger` and `HistogramExpressionTrigger`
  implementations (fields, both constructors, `evaluate()`, `convertResult()`).
  `HistogramExpressionTriggerFactory` builds triggers per `(scope, histogramName)`, resolving a
  `HistogramValueDescriptor` (`REFERENCE_ATTRIBUTE` or `REFERENCED_ENTITY_ATTRIBUTE`, plus plain
  numeric type and array-ness) via `HistogramValueDescriptorFactory`, which validates the source
  attribute is filterable and numeric at schema-mutation time.
- `CatalogExpressionTriggerRegistry` plus extracted `CrossEntityTriggerIndex` and
  `LocalTriggerIndex` — `evita_engine/src/main/java/io/evitadb/core/catalog/` — hold both trigger
  types; consumers filter by `instanceof`.
- `ReevaluateExpressionMutation` / `ReevaluateExpressionExecutor` —
  `evita_engine/src/main/java/io/evitadb/index/mutation/` — the unified cross-entity re-evaluation
  path described above.
- **Invariant:** an owner PK appears at most once per bucket per histogram — enforced by the
  cardinality-gated insert/remove pair, not by a defensive scan on every write.
- **Scope-aware evaluation:** trigger `evaluate()` takes a `Scope`; a referenced/group entity whose
  storage scope doesn't match causes the nested proxy — and therefore the whole condition — to
  resolve to `false`, so cross-scope chains never populate a histogram.

## Verification

No dedicated performance/correctness numbers are re-derived here; the plan's 500 ms / 10 000-PK
fan-out gate and "zero mismatches across 1000+ fuzz batches" were **acceptance criteria set in the
plan**, not measurements recorded anywhere in the tree — treat them as targets the implementation
was built against, not as achieved results.

What is directly verified present on `dev` (file exists, `@Test` methods counted; not re-run in
this session):

- `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/ReducedGroupEntityIndexTest.java` — 59 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/ReferencedTypeEntityIndexTest.java` — 60 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/HistogramExpressionTriggerFactoryTest.java` — 5 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/HistogramValueDescriptorFactoryTest.java` — 35 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/ReevaluateExpressionExecutorTest.java` — 28 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/schema/mutation/reference/SetReferenceSchemaBucketedMutationTest.java` — 26 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/schema/dto/HistogramIndexDefinitionTest.java` — 20 tests.

## Consequences & open follow-ups

- This record covers schema + conditional indexing only. Query-time computation (histogram
  statistics output, the `histogramHaving` constraint) is a separate decision — see
  [[2026-05-06-reference-histogram-statistics]].
- Range-typed source attributes and multiple histograms per reference were **not** in this PR —
  `@Reference.bucketed()` was still a single `@Histogram`, not an array, at merge time. See
  [[2026-05-27-range-and-multi-histogram-schema]] for that follow-up (issue #1161, five weeks
  later).
- The plan documents (`bucketed-histogram-schema-support.md`, `conditional-bucket-indexing.md`)
  cited a prerequisite document `multi-histogram-schema-change.md` that was never committed to the
  repository under that name — `git log --diff-filter=A` and `--diff-filter=D` both return nothing
  for it. The corresponding work most likely shipped as commit `35e8814df` ("support multiple named
  histogram definitions per scope in reference schema", same day as the base schema-support commit
  `a66d28a09`), but no planning document for it was ever found or retired.
- The storage-mechanism deviation noted in *Decision* (dedicated `HistogramIndex` family instead
  of `FilterIndex` + `AttributeIndexType.HISTOGRAM`) was further restructured into paged/columnar
  storage by the unrelated issue #760 performance campaign
  (`more-optimized-data-structures`, not yet converted at time of writing) — `HistogramIndexStoragePart`
  and `HistogramRangeIndexLeafPagePart` reflect that later work, not this record's decision.

## Related work

- [[faceted-partially-indexing]] (folder slug, not yet converted; issue #18) — the conditional
  facet-indexing architecture this record extends rather than reinvents.
- [[2026-05-06-reference-histogram-statistics]] — same PR (#1136), the query-time layer built on
  top of the index this record establishes.
- [[2026-05-27-range-and-multi-histogram-schema]] — the follow-up (issue #1161) that added
  `Range`-typed source attributes and promoted `bucketed()` to an array.

## Timeline

- **2026-02-17** — branch `8-compute-dynamic-set-of-attribute-histogram-for-references` started
  (`51f68b397`, `@Reference` annotation support for histogram expression and faceted predicate).
- **2026-03-23** — schema layer landed: `a66d28a09` (bucketed histogram schema support), same day
  `35e8814df` (multiple named histogram definitions per scope).
- **2026-03-27** — `016d93255` implements conditional bucket indexing with the `HistogramIndex`
  infrastructure and the shared trigger base.
- **2026-03-28 to 2026-04-03** — hardening: histogram removal race fix (`a4271649d`), surgical
  removal via `PreMutationHistogramSnapshot` (`5a7941eb8`), scope-aware trigger infrastructure
  (`676befd3e`), cross-reference contamination / cardinality persistence fixes (`197074702`),
  generational and functional test coverage (`92573446c`, `9f9aa4f48`).
- **2026-04-23** — PR #1136 merged (`7ba51ae72`), landing schema + indexing together with the
  statistics layer covered by [[2026-05-06-reference-histogram-statistics]].
- **2026-07-31** — planning documents retired, replaced by this record and its two siblings.
