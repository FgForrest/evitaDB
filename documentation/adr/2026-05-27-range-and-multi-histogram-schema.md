---
title: Range-typed source attributes and an array-shaped bucketed() annotation for reference histograms
date: 2026-05-27
updated: 2026-07-31 12:00
status: accepted
kind: feature
issues: [1161]
prs: [1192, 1247, 1248, 1249]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation, evita_engine/src/main/java/io/evitadb/index, evita_engine/src/main/java/io/evitadb/core/expression/trigger]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing, 2026-05-06-reference-histogram-statistics]
---

# Range-typed source attributes and an array-shaped bucketed() annotation for reference histograms

Reference histograms can now bucket over `NumberRange<Byte|Short|Integer|Long|BigDecimal>` source
attributes (a record whose value spans `[from, to]` contributes to every bucket its interval
covers), and `@Reference.bucketed()` / `@ScopeReferenceSettings.bucketed()` changed from a single
`@Histogram` to `Histogram[]`, with each `@Histogram` gaining its own secondary partition selector
(shipped as `assignedWhen()`) that AND-combines with the reference-level `bucketedPartially` gate.

## Why

Follow-up issue #1161, filed after
[[2026-04-23-bucketed-histogram-indexing]] shipped. Two gaps in the original feature: (1) the DTO
layer (`ReferenceSchema.bucketedInScopes: Map<Scope, Map<String, HistogramIndexDefinition>>`)
already supported multiple histograms per reference per scope, but the annotation surface
(`@Reference.bucketed()`) only ever accepted one `@Histogram`, so class-based schema declaration
couldn't reach what the DTO already allowed; (2) histograms could only bucket over plain numeric
attributes — a `NumberRange`-typed source (e.g. a product's `validityPeriod`) had no way to
contribute to every bucket its span covers, only to a single point.

### Previous state

Per the source document's own audit of `dev` at commit `e318f10d1` (recorded verbatim as it framed
the decision): the DTO-level map-of-maps was already correct, `HistogramIndex.insertValue` was
narrowed to `Number` (rejecting `Range`), and `@Reference.bucketed()` returned a bare `Histogram`.
`HistogramValueDescriptorFactory`'s plain-type validator accepted only `Byte`/`Short`/`Integer`/
`Long`/`BigDecimal`, rejecting `Range` subtypes outright.

## Options considered

### Fork — widen `HistogramIndex.insertValue` in place, or add a parallel range-specific method

**Option A — widen `insertValue(Locale, Number, int)` to `insertValue(Locale, Serializable, int)`
(chosen).** The leaf implementations (`SimpleHistogramIndex` / `LocalizedHistogramIndex`) already
know their `valueType` at construction time and route internally.

- **Pros:** no new API surface at every call site for a concern (value type) already known at
  construction; cardinality wiring, bucket-emptiness checks, and transactional-layer mechanics are
  untouched.
- **Cons:** callers that assumed `Number` lose that compile-time guarantee; a `Serializable` that
  is neither `Number` nor `Range` is now representable and must be rejected defensively at runtime
  rather than by the type system.

**Option B — a parallel `insertRangeValue(Locale, Range<?>, int)` method (declined).**

- **Rejected because:** adds API surface to every call site for a value-type concern that's
  already resolvable once, at construction; and pre-decomposing a `Range` into `(from, to)` at the
  call site would lose the spanning information the leaf needs to drive its own query-time sweep.

### Fork — annotation-array migration: silent single-to-array promotion, or a breaking change

**Option A — `Histogram[] bucketed()`, source-incompatible (chosen).** Java does not auto-promote
`bucketed = @Histogram(...)` to `bucketed = {@Histogram(...)}`; every caller must add array braces.

- **Pros:** the DTO already supported multiple histograms; leaving the annotation single-valued
  would have meant two different multiplicities at two different layers of the same feature
  indefinitely.
- **Cons:** breaks every existing `@Reference(bucketed = @Histogram(...))` call site at compile
  time — accepted because reference-histogram support was brand-new in this same release cycle
  (2026.2) with no external clients yet depending on the prior shape.

**Option B — keep `Histogram bucketed()` singular, add a separate `bucketedAll()` array method
(not discussed).** No source document raises this as an alternative, and it is not evident in the
shipped code — the single existing method's return type changed in place rather than a second
method being added alongside it.

## Decision

**Chosen: Option A on both forks**, verified in the shipped tree. `HistogramIndex.insertValue` /
`removeValue` take `Serializable` (confirmed in
`evita_engine/src/main/java/io/evitadb/index/HistogramIndex.java`), and
`Reference.bucketed()` / `ScopeReferenceSettings.bucketed()` return `Histogram[]` (confirmed in
`evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java:162` and
the corresponding line in `ScopeReferenceSettings.java`).

**Naming deviation from the plan.** The plan's per-histogram secondary condition was specified as
`Histogram.bucketedPartially()`. It shipped as **`Histogram.assignedWhen()`** instead, with the
JavaDoc drawing an explicit distinction the plan's naming did not: `bucketedPartially` (on
`Reference`/`ScopeReferenceSettings`) is the **eligibility gate** ("is this reference bucketed at
all"), while `assignedWhen` (on `Histogram`) is the **classification predicate** ("given
eligibility, which specific histogram does this entity land in"). Mechanically the two still
AND-combine at trigger-build time exactly as planned; only the field name and its documented
rationale changed.

## Key technical details

Schema/annotation layer (`evita_api`):

- `Histogram` — `evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Histogram.java`
  — `nameOfTheIndex()`, `value()`, `assignedWhen()`.
- `Reference.bucketed()` / `ScopeReferenceSettings.bucketed()` — both `Histogram[]`, both paired
  with a reference/scope-level `bucketedPartially()` eligibility gate.
- `HistogramIndexDefinition` — `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/HistogramIndexDefinition.java`
  — 4-component record: `nameOfTheIndex`, `nameVariants`, `valueExpression`, `assignedWhen`.

Engine layer (`evita_engine`):

- `HistogramIndex.insertValue(Locale, Serializable, int)` /
  `removeValue(Locale, Serializable, int)` — `evita_engine/src/main/java/io/evitadb/index/HistogramIndex.java`.
  JavaDoc explicitly documents the value as "a `Number` for plain numeric attributes or a `Range`
  instance for Range-typed attributes".
- Range-typed histogram storage got its own leaf-page shape:
  `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/index/HistogramRangeIndexLeafPagePart.java`.
  This — like the base `HistogramIndexStoragePart` shape from
  [[2026-04-23-bucketed-histogram-indexing]] — was further reworked by the unrelated issue #760
  paged/columnar storage campaign; this record's decision is the `Range` support, not the exact
  storage layout, which moved again afterward.
- `HistogramValueDescriptor` / `HistogramValueDescriptorFactory` —
  `evita_engine/src/main/java/io/evitadb/core/expression/trigger/` — extended to accept
  `NumberRange<Byte|Short|Integer|Long|BigDecimal>` plain types (still rejecting `DateTimeRange` —
  histograms remain numeric-only).

## Verification

Not re-run in this session; presence and `@Test` counts on `dev` verified directly:

- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/histogram/ReferenceRangeHistogramFunctionalTest.java` — 7 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/histogram/ReferenceDecimalRangeHistogramFunctionalTest.java` — 1 test.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/extraResult/translator/histogram/producer/RangeHistogramDataCruncherTest.java` — 21 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/schema/mutation/reference/ScopedHistogramIndexDefinitionTest.java` — 7 tests.
- The source document's own commit message (`54f02b153`) states the test suite "add[s] an
  oracle-based per-bucket assertion for range histograms that independently re-derives expected
  occurrences from the seeded ranges, replacing the prior tautological `sum == overallCount`
  check" — a specific, checkable claim about test design, not carried forward as an unverified
  number.

## Consequences & open follow-ups

- **Source-incompatible change, in-repo callers migrated in the same PR.** Every existing
  `@Reference(bucketed = @Histogram(...))` call site had to be rewritten with array braces; the
  plan called for a release-note entry documenting the migration — whether that note actually
  shipped in the release notes was not verified in this session (out of scope: no `release-notes.md`
  history was checked).
- Three follow-up fix PRs landed after the initial merge, all addressing range-histogram
  correctness rather than new decisions:
  - PR #1247 (`6cd39ba2c`, 2026-06-16) — fixed range-typed bucketed histograms rendering with
    point semantics instead of overlap semantics.
  - PR #1248 (`a1d33dbb4`, 2026-06-16) — fixed `filterGroupBy` being ignored by the reference
    `histogramStatistics` path.
  - PR #1249 (`d7e0e2294`, 2026-06-22) — normalized `BigDecimal` range values at the
    histogram-index write boundary (scale mismatches were producing duplicate/missed buckets).
  These are bug fixes to the shipped behavior, not separate decisions — noted here rather than
  given their own records.
- Overlapping `assignedWhen` predicates across histograms on the same reference/scope are allowed
  (an entity can land in more than one histogram) and are not validated for exclusivity — mirrors
  the pre-existing facet/bucket exclusivity stance from
  [[2026-04-23-bucketed-histogram-indexing]]. Whether the planned INFO-level advisory log for
  overlapping `DependencyKey`s at trigger-build time actually shipped was not verified.

## Related work

- [[2026-04-23-bucketed-histogram-indexing]] — the base feature this record extends; issue #8 vs.
  this record's issue #1161.
- [[2026-05-06-reference-histogram-statistics]] — the query-time statistics layer whose
  `HistogramHavingTranslator` resolves `HistogramIndexDefinition` instances that, after this
  record, may carry an `assignedWhen` predicate and/or a `Range`-typed source.

## Timeline

- **2026-05-27** — PR #1192 merged (`41abada3c`, branch
  `1161-histograms-add-range-source-attribute-support-and-multi-histogram-schema`; feature commit
  `54f02b153`, "Ref: #1161"), landing range source-attribute support and the array-shaped
  `bucketed()` annotation together.
- **2026-06-16** — PR #1247 and PR #1248 (point-semantics and `filterGroupBy` fixes).
- **2026-06-22** — PR #1249 (`BigDecimal` scale normalization fix).
- **2026-07-31** — planning document retired, replaced by this record.
