# Reference Histogram Statistics — Implementation Plan

**Issue:** [#8 — Compute dynamic set of attribute histogram for references](https://github.com/FgForrest/evitaDB/issues/8)

**Scope:** Engine-side computation of `ReferenceHistogramStatistics` results, embedded into the
existing `ReferenceSummary.ReferenceGroupStatistics.histogramStatistics` map. Bucket data is read
from `FilterIndex` instances populated by the bucketed-histogram indexing work (see
`specifications/bucketed-histogram-indexing/`). The specification also covers the first-class
`histogramHaving` filter constraint and the three-group baseline-relaxation invariant that keeps
attribute, facet, and price projections from contracting under the user's own current selection.

**Prerequisites:**

- `specifications/bucketed-histogram-indexing/conditional-bucket-indexing.md`
- `specifications/faceted-partially-indexing/conditional-facet-indexing.md`

**Cross-cutting constraints:**

- No backward-compatibility hacks — this is a new feature in the current release and storage /
  serializer formats can change freely.
- After every phase, run `/code-quality-pipeline` on files touched in that phase.
- Boundary (`minReferencedEntity` / `maxReferencedEntity`) resolution happens at query time via
  cross-referencing the referenced entity's own source-attribute `FilterIndex` — no extra memory
  footprint on `HistogramIndex`.

---

## 1. Honest Status

This section reflects reality as of the last handover, not aspirations.

### 1.1 What works today

A client who issues
`referenceSummaryOfReference("parameter", histogramStatistics(20, "priceIndex"))` today gets a
response that:

- Contains a `ReferenceGroupStatistics` entry per group (even for histogram-only groups with no
  facets).
- Has the `histogramStatistics` map populated with one entry per requested histogram name.
- Each histogram has correct `buckets[]`, `min`, `max`, and `overallCount`.
- **Per-bucket `requested` flags are set** when the user's filter contains
  `userFilter → referenceHaving(refName, [entityHaving(]attributeBetween(...))` targeting the
  histogram's source attribute — wiring is end-to-end but the DSL is moving away from this shape
  (see §3 active plan — the `histogramHaving` constraint supersedes it).
- **`minReferencedEntity` / `maxReferencedEntity` are populated** for both
  `REFERENCED_ENTITY_ATTRIBUTE` and `REFERENCE_ATTRIBUTE` descriptors — boundary PKs are resolved
  from the referenced entity's own `GlobalEntityIndex.getFilterIndex(...)`, intersected with
  `getAllReferencedPrimaryKeys()`, sorted via the reference's configured `facetSorter` when one is
  present (falls back to lowest PK otherwise), and batch-fetched by `(entityType, entityFetch)`
  tuple.
- **Cache participation.** The accumulator routes each per-reference histogram computation through
  `QueryExecutionContext.analyse(AttributeHistogramComputer).compute()` so the result participates
  in the shared extra-result cache. `FilterIndex` IDs are process-unique, so cache keys never
  collide between reference histograms or with attribute-level histograms.

### 1.2 What does not yet work

*All items below have been resolved as of §5 completion.*

1. ~~**No dedicated `ReferenceHistogramComputer` / `FlattenedReferenceHistogramComputer` classes.**~~
   Kept as-is — the accumulator reuses `AttributeHistogramComputer` via
   `ReferenceHistogramAccumulator`. Functionally equivalent; a dedicated pair is a
   readability-only follow-up.
2. ~~**Translator / computer unit tests do not exist.**~~ `HistogramHavingTest`,
   `HistogramHavingFormulaTest`, and `HistogramHavingFunctionalTest` now cover the constraint,
   formula, and end-to-end paths respectively.
3. ~~**Sliders contract under their own handles.**~~ Fixed by `UserFilterRelaxer` with
   `RangeCarrierGroup`-parameterised stripping. All three producers (`AttributeHistogramProducer`,
   `ReferenceSummaryProducer`, `PriceHistogramProducer`) now use group-scoped relaxation instead
   of stripping the entire `UserFilter`. The `histogramHaving` constraint provides the first-class
   G1 carrier; `ReferenceHaving` is restored to `UserFilter.FORBIDDEN_CHILDREN`.

---

## 2. Problem Statement

### 2.1 Problem A — disambiguating multiple ranges on the same reference

A reference such as `parameterValues` may host several histograms, one per *parameter* group
(`height`, `weight`, `depth`, …). The user expects to drag each slider independently:

- `height ∈ [50, 120]` **AND** `weight ∈ [90, 140]`

With the pre-`histogramHaving` DSL, the only way to express that is a verbose
`userFilter(referenceHaving(...))` tree that (a) repeats the reference, (b) re-selects the group in
each branch, and (c) ANDs them via the `userFilter` container. It also forces `ReferenceHaving` to
be whitelisted inside `UserFilter` (currently relaxed) — an ergonomic shortcut, not a principled
language choice.

A dedicated constraint —
`histogramHaving(referenceName, histogramName?, from?, to?, groupSelector?)` — lets the user write:

```evitaql
userFilter(
    histogramHaving('parameterValues', 'basicUnitValue',
        50, 120, entityHaving(attributeEquals('code', 'height'))),
    histogramHaving('parameterValues', 'basicUnitValue',
        90, 140, entityHaving(attributeEquals('code', 'weight')))
)
```

…with `'basicUnitValue'` omittable when the reference hosts a single histogram and the trailing
`entityHaving(...)` omittable for the non-grouped slot.

With the new constraint, `ReferenceHaving` can be **returned to `UserFilter.FORBIDDEN_CHILDREN`**
— `histogramHaving` is the only legitimate "narrow histogram" carrier the userFilter was bent to
accept.

### 2.2 Problem B — the "sliders contracting under their own handles" bug

This is a UX-level invariant the engine must enforce. The user drags sliders in multiple steps;
the server sees each drag as an independent stateless query that already contains *all* of the
previous drags. If the histogram baseline includes the user's range selections, then for every
slider:

```
visible_range(X)  =  range(X over entities matching ALL other range filters)
```

That means as the user narrows height, the weight histogram's `[min, max]` contracts to the weight
values still present in the height-narrowed set. The weight slider no longer spans the *catalog*'s
range — it spans only what's still reachable from the current height selection. Dragging weight
then narrows height. The user is trapped in a shrinking box and can never recover the original
breadth without clearing filters.

The same "what-if" problem exists for **facet impact** (the projection that answers *"if I also
checked this facet, how many entities would match?"*) and for the **price histogram** slider. Each
of these three computations is a "what-if projection over the user's current selection within the
same domain": to be meaningful, it must hide the user's current picks **in its own domain**, while
keeping the user's picks **in the other two domains** applied.

**Correct invariant — three cross-influencing groups of `userFilter` children:**

| Group | `userFilter` children that belong to the group | Self-computation rule |
|-------|------------------------------------------------|-----------------------|
| **G1 — Attribute-family histograms** | `attributeBetween`, `histogramHaving` | Plain attribute, reference-attribute, and referenced-entity-attribute histograms compute their baseline with **G1 carriers stripped**. G2 `facetHaving` and G3 `priceBetween` stay applied. |
| **G2 — Facet impact** | `facetHaving` | Impact projection for a candidate facet strips **all `facetHaving` selections** so "what-if I add this facet" is well-defined. G1 and G3 carriers stay applied. Facet **count** and facet **presence** are unaffected — they use the full filter. |
| **G3 — Price histogram** | `priceBetween` | Price histogram baseline strips **G3 carriers**. G1 and G2 carriers stay applied. |

Non-range `userFilter` children that fall outside all three groups (e.g. `attributeEquals`, price
validity context…) are always kept — no group claims them.

The current implementations are **all three** wrong, in different directions:

| Producer | Current relaxation | Right behaviour |
|----------|--------------------|-----------------|
| `AttributeHistogramProducer` (G1) | Per-attribute only (`AttributeHistogramComputer.shouldBeExcluded` checks `request.attributeFormulas().contains(formula)` → only the *own* attribute's `AttributeFormula` is dropped; sibling attribute sliders still contract — `AttributeHistogramComputer.java:472-474`) | Drop **every** G1 carrier (`attributeBetween`, `histogramHaving`) inside `userFilter`, keep G2 + G3. |
| `ReferenceSummaryProducer` histogram path (G1) | Whole-`UserFilter` stripped (`filterFormulaWithoutUserFilter` passed to `ReferenceHistogramAccumulator.injectHistograms` — `ReferenceSummaryProducer.java:472`). Drops `facetHaving` and `priceBetween` too — histograms ignore the user's brand/category/price-range choices. | Strip **only G1** carriers, keep G2 + G3. |
| `ReferenceSummaryProducer` facet-impact path (G2) | Whole-`UserFilter` stripped (`filterFormulaWithoutUserFilter` — `ReferenceSummaryProducer.java:109`). Drops histogram ranges and price-between too — facet impact ignores the user's slider choices. | Strip **only G2** carriers (`facetHaving`), keep G1 + G3. |
| `PriceHistogramProducer` (G3) | Whole-`UserFilter` stripped today. | Strip **only G3** carriers (`priceBetween`), keep G1 + G2. |

The fix is a **single group-parameterised relaxer** shared by all three producers, and three
group-tagged marker interfaces on the formulas that identify membership. The new `HistogramHaving`
constraint provides the first-class carrier for the reference-level slot of G1; `AttributeBetween`,
`PriceBetween`, `FacetHaving` are already leaf identifiers for their respective groups.

---

## 3. Design Decisions

### 3.1 Locked in during original planning

1. **DTO location:** Results attach to `ReferenceSummary.ReferenceGroupStatistics.histogramStatistics`.
   Group ordering via the existing `groupSorter` naturally interleaves facet-only and
   histogram-only groups.
2. **`requested` flag source:** `userFilter → histogramHaving(refName, histName, from, to,
   groupSelector)` (active plan). The legacy `referenceHaving(...)` carrier is superseded.
3. **Locale:** derived from `request.getLocale()` **only when** the source attribute is localized.
   Non-localized histograms always pass `null`.
4. **Index dispatch:** grouped references → `ReducedGroupEntityIndex`; ungrouped →
   `ReferencedTypeEntityIndex`.
5. **Throw-on-unknown:** unknown histogram index names or any broken assumption throws — never
   silent skip. Upheld by both the per-reference form (`referenceSummaryOfReference`) and the
   all-references fan-out (`referenceSummary` dispatches the requirement to every reference in the
   schema and throws on the first unmatched one).
6. **Boundary entity count:** ≤ 2 per histogram (min + max). Ties resolved by the parent
   `referenceSummary`'s `orderBy`, fallback to lowest PK.
7. **Boundary resolution strategy:** cross-reference `FilterIndex.getRecordsEqualTo(minValue|maxValue)`
   on the source attribute with the set of referenced PKs visible in the current group — **no
   changes to** `HistogramIndex` / `SimpleHistogramIndex` / `LocalizedHistogramIndex`.
8. **Producer shape:** extend `ReferenceSummaryProducer` (shared facet + histogram path), but
   factor histogram folding into a `ReferenceHistogramAccumulator` helper.
9. **Caching:** participates in the shared histogram cache via
   `CacheableEvitaResponseExtraResultComputer`, mirroring `AttributeHistogramComputer`.
10. **Duplicate histogram-name registration** with conflicting `bucketCount` / `behavior` /
    `entityFetch` throws at translator time.
11. **Empty histograms** are omitted from the group's map (consistent with
    `AttributeHistogramProducer`).
12. **Histogram-only groups** (no facets, but with histogram data) survive the producer's final
    group filter — relaxed from "drop if no facets" to "drop if neither facets nor histograms".

### 3.2 Active plan (supersedes §3.1 items 2 + any `UserFilter` concession)

1. **New filter constraint `HistogramHaving`** modelled after `FacetHaving` / `ReferenceHaving`:
   - carries a classifier (reference name), an optional histogram name, two optional numeric
     bounds, and an optional group-selector child (`FilterConstraint`);
   - is a plain `FilterConstraint`; when outside `userFilter` it behaves identically to a
     rewritten `ReferenceHaving(...)` narrowing the result set;
   - when inside `userFilter` it is (a) applied to the filter formula like any other child **and**
     (b) registered as a range-carrier so the histogram baseline strips it.

2. **Rewrite-to-existing-formulas at translation time.** The translator rewrites `HistogramHaving`
   into the equivalent `ReferenceHaving` / `EntityHaving` / `AttributeBetween` tree based on the
   descriptor's `HistogramValueSource`:
   - `REFERENCED_ENTITY_ATTRIBUTE`:
     `referenceHaving(ref, entityHaving(attributeBetween(attr, from, to),
         <groupSelector reduced to a referenceHaving on the group reference>))`
   - `REFERENCE_ATTRIBUTE`:
     `referenceHaving(ref, attributeBetween(attr, from, to),
         <groupSelector reduced to a referenceHaving on the group reference>)`
   - This keeps the engine's filter pipeline untouched — no new formula.
   - The translator wraps the produced formula in a new `HistogramHavingFormula` marker (a
     pass-through wrapper extending `AbstractFormula`) so the histogram baseline cloner can
     recognise and peel it out by type. No cost, no CLASS_ID collision: it's a thin marker around
     the child formula, delegating `compute()` directly.

3. **Group-tagged carrier identification.** Three disjoint marker interfaces, one per
   cross-influencing group:

   | Group | Formula marker | Carriers (formulas that implement it) |
   |-------|----------------|---------------------------------------|
   | G1 | `AttributeRangeCarrierFormula` | `AttributeFormula` constructed by `AttributeBetweenTranslator` (via a tagged subclass `BetweenAttributeFormula`), `HistogramHavingFormula` |
   | G2 | `FacetRangeCarrierFormula` | `FacetGroupFormula` / the formula produced by `FacetHavingTranslator` |
   | G3 | `PriceRangeCarrierFormula` | `PriceBetweenFormula` |

   Each marker is an empty sub-interface; they do **not** extend a common parent — a single flat
   "RangeCarrier" marker would make it impossible to strip "G1 only, keep G2+G3". The relaxer
   takes the group as a parameter and peels only that group's tag.

4. **Single group-parameterised baseline builder.** The relaxation logic lives in a new shared
   helper in `evita_engine/.../query/extraResult/translator/common/`:

   ```java
   public enum RangeCarrierGroup {
       ATTRIBUTE_HISTOGRAM,   // peels G1 — AttributeRangeCarrierFormula
       FACET_IMPACT,          // peels G2 — FacetRangeCarrierFormula
       PRICE_HISTOGRAM        // peels G3 — PriceRangeCarrierFormula
   }

   public final class UserFilterRelaxer {
       @Nonnull
       public static Formula relax(@Nonnull Formula filterFormula, @Nonnull RangeCarrierGroup group);
   }
   ```

   Logic: clone the formula tree; whenever a `UserFilterFormula` is encountered, rebuild it
   dropping every direct child whose unwrapped (`SelectionFormula.getDelegate()` applied
   transparently, per the existing pattern in `AttributeHistogramComputer.java:434-438`) form
   `instanceof` **the single group's marker**. If the rebuilt `UserFilterFormula` has no children,
   drop it entirely.

   All three producers delegate to this helper with their own group constant — it's the only
   place where "what counts as self" is resolved.

5. **Restore `UserFilter.FORBIDDEN_CHILDREN`.** With `HistogramHaving` landing, the
   `ReferenceHaving`-inside-`userFilter` concession is reversed — `ReferenceHaving` rejoins the
   forbidden list. The class-level JavaDoc section "ReferenceHaving inside userFilter" is removed.

6. **`extractRequestedBucketRange` rewritten.** Instead of walking
   `userFilter → referenceHaving → … → attributeBetween`, the translator matches the first
   `HistogramHaving` whose `(referenceName, histogramName, groupSelector)` tuple identifies this
   histogram slot and pulls its `from`/`to`. Duplicate detection becomes trivial (same tuple →
   error). The group matching is done by evaluating the optional group-selector `FilterConstraint`
   against the referenced-group's global index at planning time and asserting it resolves to a
   unique group PK.

---

## 4. Landed Implementation (historical record)

This section is the retrospective of phases A–G.2 that are already merged. Phase B's extraction is
superseded by §5 Task 28; everything else stands.

### Phase A — Expose "all referenced PKs in group" — ✅ complete

**Landed in:**

- `evita_engine/src/main/java/io/evitadb/index/ReducedGroupEntityIndex.java` — new
  `Bitmap getAllReferencedPrimaryKeys()` (rebuilds bitmap from
  `referencedPrimaryKeysIndex.keySet()` on every call; per-group keysets are small).
- `evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java` — new
  `Bitmap getAllReferencedPrimaryKeys()` delegating to…
- `evita_engine/src/main/java/io/evitadb/index/cardinality/ReferenceTypeCardinalityIndex.java` —
  new `Bitmap getAllTrackedReferencedEntityPrimaryKeysAsBitmap()` with a
  `buildReferencedPrimaryKeysBitmap()` helper and a defensive `clone()` before wrapping the
  memoized `RoaringBitmap` in a `BaseBitmap` — the non-cloned version would have shared mutable
  state with callers.
- `evita_functional_tests/src/test/java/io/evitadb/index/ReducedGroupEntityIndexTest.java` and
  `ReferencedTypeEntityIndexTest.java` — new tests: empty, multi-PK, removal, defensive-copy,
  subsequent-insert visibility. 159 tests green.

### Phase B — Translator — ✅ done (extraction to be rewired in §5 Task 28)

**Landed:**

- `ReferenceHistogramStatisticsTranslator` implementing
  `RequireConstraintTranslator<ReferenceHistogramStatistics>`. Resolves the parent
  `ReferenceSummaryProducer` (filtered on `ReferenceSummaryAdapter`), pulls the reference schema
  from `ProcessingScope.getReferenceSchema()`, validates the histogram index definition exists in
  every active scope, rebuilds a `HistogramValueDescriptor` per scope via
  `HistogramValueDescriptorFactory.build(...)` and asserts cross-scope consistency, derives the
  effective locale, and calls `ReferenceSummaryProducer.addHistogramRequest(...)`.
- Registered in `ExtraResultPlanningVisitor` next to `ReferenceSummaryTranslator`.
- Both parent translators were modified to walk their own children looking for
  `ReferenceHistogramStatistics`, push a `ProcessingScope` via
  `extraResultPlanner.executeInContext(...)`, and dispatch back through the visitor. See
  `ReferenceSummaryOfReferenceTranslator#createProducer` (strict — throws on missing definition,
  matches design decision 5) and `ReferenceSummaryTranslator#createProducer` /
  `dispatchHistogramToMatchingReferences` (strict fan-out — dispatches the histogram requirement
  to every reference in the entity schema and lets
  `ReferenceHistogramStatisticsTranslator` throw on the first reference that doesn't define the
  histogram in every active scope; design decision 5 is upheld).
- **B.4 extraction** — the translator walks `extraResultPlanner.getFilterBy()` for a
  `userFilter → referenceHaving(refName, …)` subtree whose inner `attributeBetween` targets the
  descriptor's source attribute. Multiple independent matches for the same
  `(refName, histogramName)` throw `EvitaInvalidUsageException`. Bounds are converted to
  `BigDecimal` via `EvitaDataTypes.toTargetType` and plumbed through `HistogramRequest`.
  **This walker is replaced by a `HistogramHaving`-tuple match in §5 Task 28.**

### Phase C — Producer + Computer + boundary resolution

#### C.1 — Producer extension — ✅ done

`ReferenceSummaryProducer` gained: `HistogramRequest` record + `RequestedBucketRange` record;
`histogramRequests: Map<String, List<HistogramRequest>>` field; `addHistogramRequest(...)` +
`getHistogramRequests(String)`; duplicate-guard throwing `EvitaInvalidUsageException` on
conflicting `(bucketCount, behavior, entityFetch)`; `entityIndexes: List<EntityIndex>` field +
constructor param; `doFabricate` relaxed the final group filter to keep groups where *either*
facet stats or histogram stats is non-empty. `findOrCreateProducer` in
`ReferenceSummaryOfReferenceTranslator` captures
`extraResultPlanner.getIndexSetToUse().getIndexStream(EntityIndex.class)` into a list and passes
it to the producer constructor.

#### C.2 — Computer — ✅ cache participation wired; dedicated class deferred

`ReferenceHistogramAccumulator.computeCacheable` constructs a one-off
`AttributeHistogramComputer` whose `AttributeHistogramRequest` wraps the single RGEI/RTEI-backed
`FilterIndex` for the current (reference, group, histogram) tuple, initializes the computer, and
then calls `context.analyse(computer).compute()`. The `analyse` hook routes the computation
through the planning policy — when caching is enabled the planner returns a wrapper that memoizes
the histogram in the shared cache and serves it from `FlattenedHistogramComputer` on hit.
`FilterIndex.getId()` is process-unique, so cache keys don't collide across references or with
attribute-level histograms.

A dedicated `ReferenceHistogramComputer` / `FlattenedReferenceHistogramComputer` pair is a
readability-only follow-up; it brings `(referenceName, groupId)` into the hash explicitly, but
that is purely cosmetic given `FilterIndex`-ID uniqueness.

The double-negated `Functions.alwaysFalse().negate().negate()::test` predicate was replaced with a
proper `requestedBucketPredicate` built from the `RequestedBucketRange` — matches
`AttributeBetweenTranslator.createBigDecimalPredicate` semantics.

#### C.3 — Boundary resolution — ✅ done for both value sources

- `minValue` / `maxValue` per surviving histogram: read straight off `CacheableHistogramContract`.
- Boundary referenced PKs resolved via
  `QueryPlanningContext.getGlobalEntityIndexIfExists(entityType, scope)` for
  `REFERENCED_ENTITY_ATTRIBUTE` and the reference's own `FilterIndex` for `REFERENCE_ATTRIBUTE`
  (scope-local, keyed on reduced-index PK after RGEI reduces the PK via
  `executeWithDifferentPrimaryKeyToIndex`), then converted to referenced PKs via the new
  `getReferencedPrimaryKeysForIndexPks(...)` method, intersected with
  `getAllReferencedPrimaryKeys()`.
- A single PK is picked deterministically via `NestedContextSorter.sortAndSlice(intersection)` when
  a sorter is configured on the enclosing `referenceSummaryOfReference`, falling back to
  `intersection.first()` otherwise.
- Boundary PKs batch-fetched grouped by `FetchTuple(entityType, entityFetch)`; one
  `context.fetchEntities(entityType, pks, entityFetch)` call per tuple. When the histogram carries
  no `entityFetch`, a plain `new EntityFetch()` (PK-only) is used so the DTO's `SealedEntity`-typed
  slot is populated.
- `convertToHistogram(predicate, minEntity, maxEntity)` overload added on both
  `CacheableHistogramContract` and `CacheableHistogram`. When either side of the pair is `null`,
  the accumulator falls back to the 2-arg overload so the DTO's "both or neither" invariant is
  honoured.

### Phase D — DTO construction — ✅ no new work

The existing `HistogramContract` / `Histogram` already expose
`Optional<SealedEntity> getMinReferencedEntity()` / `getMaxReferencedEntity()`, and `Histogram` has
a 4-arg constructor that takes both slots. `ReferenceSummaryResultAdapter#createGroupStatistics`
gained a `Map<String, HistogramContract> histogramStatistics` parameter;
`ReferenceSummaryAdapter` passes the histogram map into the `ReferenceGroupStatistics` constructor;
`FacetSummaryAdapter` accepts the parameter but drops it (the legacy DTO shape doesn't carry
histograms).

### Phase E — External APIs — ✅ already wired

- **gRPC** — `GrpcHistogram` already carries `minReferencedEntity` / `maxReferencedEntity`.
  `GrpcReferenceGroupStatistics.histogramStatistics` is already a `map<string, GrpcHistogram>`.
  `GrpcReferenceSummaryBuilder` populates it from
  `ReferenceGroupStatistics.getHistogramStatistics()`. `ResponseConverter` has the symmetric read
  path.
- **GraphQL** — `FullResponseObjectBuilder` exposes the `histogramStatistics` wrapper field on
  reference group statistics. `ReferenceSummaryResolver` already interprets the matching
  `ReferenceHistogramStatistics` require constraint from the client query.
- **REST** — `FullResponseObjectBuilder` builds the `histogramStatistics` wrapper property.
  `ExtraResultsJsonSerializer` serializes the histogram map on each reference group statistics.

### Phase F — Tests — ✅ functional + index-layer done

- **Index-layer tests:** `ReducedGroupEntityIndexTest` and `ReferencedTypeEntityIndexTest` cover
  `getAllReferencedPrimaryKeys()`, defensive-copy behaviour, and memoization invalidation. 159
  tests green.
- **Functional tests (hand-crafted small fixture):** `ReferenceHistogramFunctionalTest` (12 tests,
  all green) covers happy-path histogram population per group, boundary-entity population,
  strict all-references fan-out, `requested` flag end-to-end, validation of unknown histogram
  names, `QueryConstraints.histogramStatistics` returning `null` for empty index names, and
  histogram-only group survival.
- **Functional E2E tests (60-product deterministic dataset across 3 parameter groups):**
  `ReferenceHistogramE2EFunctionalTest` (15 tests, all green). Nested groups: HappyPath,
  BehaviorMatrix (parametrized over every `HistogramBehavior`), QueryInteraction, Oracle,
  Validation, Combined.
- **gRPC conversion tests:** `GrpcReferenceHistogramConversionTest` (3 tests, all green).
- **External-API shape-assertion tests (GraphQL + REST):** both
  `CatalogGraphQLQueryEntityQueryFunctionalTest` and `CatalogRestQueryEntityQueryFunctionalTest`
  carry `shouldReturnReferenceSummaryWithHistogramStatisticsForProducts` +
  `…IncludingBoundaryEntities`.
- **External-API dataset schemas** extended: the GraphQL and REST `TestDataGenerator`s declare the
  `parameter` reference with `.indexedWithComponents(ReferenceIndexedComponents.values())`,
  `marketShare` marked `filterable()`, and `.bucketed("priceIndex",
  $reference.attributes['marketShare'])`. 248 GraphQL + 177 REST tests remain green.

### Phase G.1 — `REFERENCE_ATTRIBUTE` boundary entities — ✅ landed

Simpler than the original "mirror RTEI scheme" plan required — for RGEI the reduced-index PK
coincides with the referenced entity PK once the filter-index record is re-keyed during insert
via `executeWithDifferentPrimaryKeyToIndex`, so no separate reverse storage part was needed.
Changes: three paths in `ReferenceIndexMutator` (insert, remove, update) +
`RGEI.getReferencedPrimaryKeysForIndexPks(Bitmap)` + source-dispatch branch in
`ReferenceHistogramAccumulator.resolveBoundaryPks`. E2E pin flipped to `isPresent()`; all 15 E2E
tests green. Fixed an incidental NPE in
`ReferenceHistogramAccumulator.resolveSourceAttributeSchema`: the old code eagerly called
`Objects.requireNonNull(descriptor.sourceEntityType())` at the top of the method, but
`sourceEntityType` is contractually `null` for `REFERENCE_ATTRIBUTE` descriptors. The null check
is now scoped to the `REFERENCED_ENTITY_ATTRIBUTE` branch.

### Phase G.2 — Language-level `requested` flag (historical concession — reverted by §5)

`ReferenceHaving.class` was removed from `UserFilter.FORBIDDEN_CHILDREN` and from the
`@Child(forbidden=...)` annotation on the `@Creator` constructor. Verified that
`ImpactFormulaGenerator.handleFormula` only enumerates `FacetGroupFormula` instances —
`ReferenceHaving` is preserved in the cloned tree but never enumerated as an impact candidate.
Positive/negative/facet-interaction test matrix landed in `ReferenceHistogramFunctionalTest`,
`UserFilterTest`, `FacetSummaryImpactWithReferenceHavingTest`, plus GraphQL/REST shape-assertion
smoke tests. Documentation updated in `documentation/user/en/query/filtering/behavioral.md`.

**This concession is reverted by §5 Task 26** — with `histogramHaving` landing,
`ReferenceHaving` returns to `FORBIDDEN_CHILDREN` and the dedicated constraint becomes the only
legitimate histogram-range carrier.

---

## 5. ~~Active~~ Completed Plan — `histogramHaving` + three-group baseline relaxation

All 30 tasks are implemented. Notable deviations from the original design:

- **Task 1:** `HistogramRangeConstraint` marker interface was not created. `HistogramHaving`
  implements `ReferenceConstraint<FilterConstraint>` instead.
- **Task 7:** `FacetRangeCarrierFormula` and `PriceRangeCarrierFormula` marker interfaces were not
  created as separate types. `UserFilterRelaxer.carrierTypeFor()` dispatches directly to
  `FacetHavingFormula.class` (G2) and `PriceBetweenFormula.class` (G3). Only G1 has a dedicated
  marker (`AttributeRangeCarrierFormula`). This is simpler and achieves the same group-selective
  peeling.
- **Task 14:** `histogramHaving` was not added to the parser list visitor — not applicable to this
  constraint shape.
- **Task 27 (partial):** `filterFormulaWithoutUserFilter` was not deleted from
  `ReferenceSummaryProducer` — it remains used in the facet-impact path alongside the relaxer.
- **Task 28:** Implementation uses `ResolvedHistogramHaving` from `getResolvedHistogramHavings()`
  on the query context rather than walking `userFilter` children directly.

### 5.1 Work breakdown

Progress checklist (mirrors `LAYERS.md`):

```
Progress:
- [x] 1.  Constraint class: HistogramHaving
- [x] 2.  Registry registration
- [x] 3.  Factory method
- [x] 4.  EvitaQL grammar rule
- [x] 5.  Regenerate parser
- [x] 6.  Parser visitor method
- [x] 7.  Formula: HistogramHavingFormula + group-tagged marker interfaces
         (AttributeRangeCarrierFormula; G2/G3 use FacetHavingFormula / PriceBetweenFormula directly)
- [x] 8.  Engine translator: HistogramHavingTranslator
- [x] 9.  Translator registration: FilterByVisitor.TRANSLATORS
- [x] 10. Kryo serializer
- [x] 11. Kryo registration (APPEND AT END)
- [x] 12. Constraint unit test: HistogramHavingTest
- [x] 13. Parser visitor test
- [x] 14. Parser list visitor test — NOT APPLICABLE (histogramHaving not added to list visitor)
- [x] 15. Serialization round-trip test
- [x] 16. Descriptor provider test (counts bumped)
- [x] 17. Formula test: HistogramHavingFormulaTest
- [x] 18. JSON converter test
- [x] 19. Constraint resolver tests (GraphQL + REST)
- [x] 20. E2E functional test (filter + histogram interaction)
- [x] 21. GraphQL API functional test
- [x] 22. REST API functional test
- [x] 23. (no new benchmark — marker formula reuses child cost)
- [x] 24. User documentation update
- [x] 25. Example .evitaql files
- [x] 26. Revert: restore ReferenceHaving to UserFilter.FORBIDDEN_CHILDREN + remove JavaDoc section
- [x] 27. Fix Problem B: UserFilterRelaxer (group-parameterised) + switch AttributeHistogramProducer,
         ReferenceSummaryProducer (histogram + facet-impact paths), and PriceHistogramProducer over
- [x] 28. Rewire: ReferenceHistogramStatisticsTranslator.extractRequestedBucketRange →
         HistogramHaving matcher (uses ResolvedHistogramHaving from query context)
- [x] 29. Retire: RequestedBucketRange stays, but its extraction source changes from
         referenceHaving-walk to histogramHaving-walk; update tests accordingly
- [x] 30. Migration note in documentation/user/en/query/filtering/behavioral.md
```

#### Task 1 — `HistogramHaving` constraint class

**Files:**

- Create: `evita_query/src/main/java/io/evitadb/api/query/filter/HistogramHaving.java` (template:
  `FacetHaving.java`)
- Create: `evita_query/src/main/java/io/evitadb/api/query/filter/HistogramRangeConstraint.java` —
  marker interface

**Signature:**

```java
@ConstraintDefinition(
    name = "having",
    shortDescription = "Narrows a reference histogram to a specific [from, to] range, ...",
    userDocsLink = "/documentation/query/filtering/references#histogram-having",
    supportedIn = ConstraintDomain.ENTITY
)
public class HistogramHaving extends AbstractFilterConstraintContainer
    implements GenericConstraint<FilterConstraint>, HistogramRangeConstraint {

    @Creator
    public HistogramHaving(
        @Nonnull @Classifier String referenceName,
        @Nullable String histogramName,
        @Nullable @Value(requiresPlainType = true) Serializable from,
        @Nullable @Value(requiresPlainType = true) Serializable to,
        @Nullable @Child(domain = ConstraintDomain.ENTITY,
                         allowed = { EntityHaving.class }) FilterConstraint groupSelector
    );
}
```

**Validation in the constructor:**

- At least one of `from` / `to` must be non-null.
- If `from` and `to` are both non-null, `from.compareTo(to) <= 0`.
- `histogramName` may be empty string → normalise to `null`.
- `groupSelector` must be exactly one child (or null) — reject arrays/lists.

**Accessors:** `getReferenceName()`, `getHistogramName()` (nullable), `getFrom()` / `getTo()`
(nullable), `getGroupSelector()` (nullable).

**Key difference vs `FacetHaving`:** `FacetHaving` uses variadic children as AND.
`HistogramHaving` uses a *single* optional child (the group selector). The numeric bounds live in
`getArguments()` alongside the classifier.

#### Task 2 — Registry registration

**Files:**

- Modify: `evita_query/src/main/java/io/evitadb/api/query/descriptor/ConstraintRegistry.java` —
  append after `FacetHaving.class` in `REGISTERED_CONSTRAINTS`.

#### Task 3 — Factory method

**Files:**

- Modify: `evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java` — add
  `histogramHaving(...)` overloads (all-optional-args variants mirroring the `attributeBetween` /
  `facetHaving` style).

#### Task 4 — EvitaQL grammar rule

**Files:**

- Modify: `evita_query/src/main/resources/META-INF/io/evitadb/api/query/parser/evitaQL/EvitaQL.g4`

**Rule to add** (at the bottom, next to the other `*Args` rules):

```antlr
classifierWithHistogramHavingArgs
    : LEFT_PAREN
        classifier=classifierTokenValue
        (COMMA histogramName=valueToken)?
        (COMMA valueFrom=valueToken COMMA valueTo=valueToken)?
        (COMMA groupSelector=filterConstraint)?
      RIGHT_PAREN
    ;
```

Wire it into the `filterConstraint` top-level alternation as:

```antlr
    | HISTOGRAM_HAVING args=classifierWithHistogramHavingArgs                   # histogramHavingConstraint
```

and add the `HISTOGRAM_HAVING : 'histogramHaving';` lexer token.

#### Task 5 — Regenerate parser

```shell
cd evita_query && ./generate_grammar.sh evitaql
```

Commit the regenerated files under
`evita_query/src/main/java/io/evitadb/api/query/parser/grammar/`.

#### Task 6 — Parser visitor method

**Files:**

- Modify:
  `evita_query/src/main/java/io/evitadb/api/query/parser/visitor/EvitaQLFilterConstraintVisitor.java`
  — add `visitHistogramHavingConstraint(HistogramHavingConstraintContext ctx)`.

Pattern matches `EvitaQLFilterConstraintVisitor.visitFacetHavingConstraint` + argument peeling for
the optional tokens (use `Optional.ofNullable(ctx.histogramName)` style already present in the
visitor).

#### Task 7 — Formula markers (three groups)

**Files:**

- Create: `evita_engine/.../core/query/algebra/filter/AttributeRangeCarrierFormula.java` — G1
  marker interface.
- Create: `evita_engine/.../core/query/algebra/filter/FacetRangeCarrierFormula.java` — G2 marker
  interface.
- Create: `evita_engine/.../core/query/algebra/filter/PriceRangeCarrierFormula.java` — G3 marker
  interface.
- Create: `evita_engine/.../core/query/algebra/filter/HistogramHavingFormula.java` — pass-through
  `AbstractFormula` delegating `compute()` to its single child; implements **G1**
  (`AttributeRangeCarrierFormula`).
- Modify: `evita_engine/.../core/query/algebra/attribute/AttributeFormula.java` — the translator
  constructs a tagged subclass
  `BetweenAttributeFormula extends AttributeFormula implements AttributeRangeCarrierFormula` only
  when the formula originates from `AttributeBetweenTranslator` (see
  `AttributeBetweenTranslator.java:312`). Plain `AttributeFormula` (from `attributeEquals`,
  `attributeInSet`, …) stays unmarked.
- Modify:
  `evita_engine/.../core/query/algebra/price/filteredPriceRecords/PriceBetweenFormula.java` —
  implement **G3** (`PriceRangeCarrierFormula`).
- Modify: the formula produced by `FacetHavingTranslator` (`FacetGroupFormula` or equivalent —
  confirm at implementation time) — implement **G2** (`FacetRangeCarrierFormula`). If multiple
  formula shapes can originate from `FacetHaving`, tag all of them.

**Important:** the three markers do NOT share a common parent. A flat "RangeCarrier" parent would
make group-selective peeling impossible.

**Cost:** `HistogramHavingFormula.getOperationCost()` returns the child's cost (pass-through). No
benchmarking needed — each tag adds one virtual dispatch.

#### Task 8 — Engine translator `HistogramHavingTranslator`

**Files:**

- Create: `evita_engine/.../core/query/filter/translator/histogram/HistogramHavingTranslator.java`

**Responsibilities:**

1. Resolve the `(ReferenceSchema, HistogramIndexDefinition)` from the current processing scope
   and the classifier/histogram-name pair. Reject when the schema has no such histogram. Reuse
   `ReferenceHistogramStatisticsTranslator.resolveDescriptor` (make it package-accessible or
   lift to a utility).
2. Evaluate the optional `groupSelector` against the referenced-group's global index. Assert the
   result is a **single PK** — else throw
   `EvitaInvalidUsageException("groupSelector must select exactly one group entity")`.
3. Produce the equivalent filter subtree:
   - For `HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE`, the rewrite is
     `referenceHaving(refName, entityHaving(attributeBetween(attr, from, to),
     referenceHaving(groupRef, entityPrimaryKeyInSet(resolvedGroupPk))))`.
   - For `HistogramValueSource.REFERENCE_ATTRIBUTE`, the rewrite is
     `referenceHaving(refName, attributeBetween(attr, from, to),
     referenceHaving(groupRef, entityPrimaryKeyInSet(resolvedGroupPk)))`.
4. Dispatch the rewrite back through `FilterByVisitor` — `visitor.computeFormula(rewrittenConstraint)`
   — and wrap the returned formula in `HistogramHavingFormula`.
5. Register the `HistogramHavingFormula` with the `ReferenceSummaryProducer`'s histogram-range
   extractor (replaces the current `extractRequestedBucketRange` walk). The translator also
   captures the `(referenceName, histogramName, groupPk, from, to)` tuple directly — no
   filter-tree re-walking needed later.

**Key difference vs rewriting through a meta-constraint:** the translator is the single point
where the (group selector → group PK) resolution happens. Downstream consumers (accumulator,
baseline relaxer) see a formula that already points at a resolved PK, avoiding a repeated schema
lookup.

#### Task 9 — Translator registration

**Files:**

- Modify: `evita_engine/.../core/query/filter/FilterByVisitor.java` — append
  `TRANSLATORS.put(HistogramHaving.class, new HistogramHavingTranslator());` next to the
  `FacetHaving` entry.

#### Task 10 — Kryo serializer

**Files:**

- Create: `evita_store/evita_store_server/.../query/serializer/filter/HistogramHavingSerializer.java`
  (template: `FacetHavingSerializer.java`) — write classifier, histogram name (nullable), from / to
  (nullable Serializables via `kryo.writeClassAndObject`), group selector (nullable filter
  constraint).

#### Task 11 — Kryo registration (APPEND AT END)

**Files:**

- Modify: `evita_store/evita_store_server/.../query/QuerySerializationKryoConfigurer.java` —
  **append at the end** of the register-block:

```java
kryo.register(HistogramHaving.class, new HistogramHavingSerializer(), index++);
```

**Critical:** do not insert anywhere else — class IDs are positional and shifting them breaks
stored WAL.

#### Task 12 — Constraint unit test

**Files:**

- Create: `evita_test/.../api/query/filter/HistogramHavingTest.java` (template:
  `FacetHavingTest.java`) — cover: valid constructions, both-null bounds rejected, from > to
  rejected, null `groupSelector` allowed, multi-child `groupSelector` rejected.

#### Task 13 — Parser visitor test

**Files:**

- Modify: `evita_test/.../api/query/parser/visitor/EvitaQLFilterConstraintVisitorTest.java` — add
  cases for each arg combination (classifier-only, +histogramName, +range, +groupSelector,
  everything).

#### Task 14 — Parser list visitor test

**Files:**

- Modify: `evita_test/.../api/query/parser/visitor/EvitaQLFilterConstraintListVisitorTest.java`

#### Task 15 — Serialization round-trip test

**Files:**

- Modify: `evita_test/.../store/query/QuerySerializationTest.java` — add `histogramHaving`
  round-trip covering all arg permutations.

#### Task 16 — Descriptor provider test (count bumps)

**Files:**

- Modify: `evita_test/.../api/query/descriptor/ConstraintDescriptorProviderTest.java` — increment
  the expected filter-constraint count.

#### Task 17 — Formula test

**Files:**

- Create: `evita_test/.../core/query/algebra/filter/HistogramHavingFormulaTest.java` — verify
  pass-through `compute()`, cost delegation, `AttributeRangeCarrierFormula` marker.

#### Task 18 — JSON converter test

**Files:**

- Modify: `evita_test/.../api/query/convert/json/filter/FilterConstraintToJsonConverterTest.java`

#### Task 19 — Constraint resolver tests (GraphQL + REST)

**Files:**

- Modify:
  `evita_test/.../externalApi/graphql/.../resolver/constraint/FilterConstraintResolverTest.java`
- Modify:
  `evita_test/.../externalApi/rest/.../resolver/constraint/FilterConstraintResolverTest.java`

#### Task 20 — E2E functional test

**Files:**

- Create: `evita_test/.../api/functional/reference/HistogramHavingFunctionalTest.java`

**Coverage:**

- `histogramHaving` outside `userFilter` narrows the result set exactly like the equivalent
  `referenceHaving(...)` rewrite.
- `histogramHaving` inside `userFilter` narrows the result set **and** flips the matching
  `requested` bucket flag on the returned histogram.
- **Regression for Problem B:** two histograms on the same reference; move one slider, confirm
  the OTHER histogram's `[min, max]` stays at the catalog-wide range (does not contract to the
  currently-selected window). Same assertion for attribute histograms.
- `histogramHaving` rejected outside `userFilter` when `ReferenceHaving` is also rejected outside
  — sanity check the forbidden-children restoration.
- `histogramHaving` inside forbidden parents (`not`, nested `userFilter`) rejected at
  parse/validation time.

#### Task 21 — GraphQL API functional test

**Files:**

- Modify: an existing `CatalogGraphQLQueryFunctionalTest*` under
  `evita_test/.../externalApi/graphql/...` — add a histogram-having scenario.

#### Task 22 — REST API functional test

**Files:**

- Modify: an existing `CatalogRestQueryFunctionalTest*` under
  `evita_test/.../externalApi/rest/...` — add an equivalent scenario.

#### Task 24 — User documentation

**Files:**

- Modify: `documentation/user/en/query/filtering/behavioral.md` — replace the
  "ReferenceHaving inside userFilter" paragraph with the `histogramHaving` explanation; restore
  the "forbidden children" listing.
- Modify: `documentation/user/en/query/filtering/references.md` — add `histogramHaving` section.
- Modify: `documentation/user/en/query/requirements/histogram.md` — document the baseline
  relaxation invariant (sliders don't contract) and link to `histogramHaving` as the recommended
  range carrier for reference histograms.

#### Task 25 — Example `.evitaql` files

**Files:**

- Create: `documentation/user/en/query/filtering/examples/references/histogram-having.evitaql`

#### Task 26 — Restore `UserFilter.FORBIDDEN_CHILDREN`

**Files:**

- Modify: `evita_query/src/main/java/io/evitadb/api/query/filter/UserFilter.java`
  - Re-add `ReferenceHaving.class` to both `FORBIDDEN_CHILDREN` and the `@Child(forbidden = {…})`
    list on the `@Creator` constructor.
  - Remove the class-level "ReferenceHaving inside userFilter" section from the JavaDoc.
- Modify: `evita_test/.../api/query/filter/UserFilterTest.java`
  - Re-add the rejection test for `ReferenceHaving` inside `userFilter`.
  - Remove / update the acceptance test that was added during Phase G.2.
- Audit: any test under `evita_test` / `documentation/user/en/query/examples` that used the
  `userFilter(referenceHaving(...))` shortcut — migrate to `histogramHaving(...)`.

#### Task 27 — `UserFilterRelaxer` (group-parameterised) + producer switchover (fix Problem B)

**Files:**

- Create: `evita_engine/.../core/query/extraResult/translator/common/RangeCarrierGroup.java`

```java
public enum RangeCarrierGroup {
    /** G1 — peels AttributeRangeCarrierFormula (attributeBetween, histogramHaving). */
    ATTRIBUTE_HISTOGRAM,
    /** G2 — peels FacetRangeCarrierFormula (facetHaving). */
    FACET_IMPACT,
    /** G3 — peels PriceRangeCarrierFormula (priceBetween). */
    PRICE_HISTOGRAM
}
```

- Create: `evita_engine/.../core/query/extraResult/translator/common/UserFilterRelaxer.java`

```java
public final class UserFilterRelaxer {
    @Nonnull
    public static Formula relax(@Nonnull Formula filterFormula, @Nonnull RangeCarrierGroup group) {
        final Class<?> marker = switch (group) {
            case ATTRIBUTE_HISTOGRAM -> AttributeRangeCarrierFormula.class;
            case FACET_IMPACT        -> FacetRangeCarrierFormula.class;
            case PRICE_HISTOGRAM     -> PriceRangeCarrierFormula.class;
        };
        final Formula relaxed = FormulaCloner.clone(filterFormula, (visitor, node) -> {
            if (node instanceof UserFilterFormula userFilter) {
                final Formula rebuilt = FormulaCloner.clone(userFilter, inner -> {
                    final Formula probe = inner instanceof SelectionFormula sf ? sf.getDelegate() : inner;
                    return marker.isInstance(probe) ? null : inner;
                });
                return rebuilt == null || rebuilt.getInnerFormulas().length == 0 ? null : rebuilt;
            }
            return node;
        });
        return relaxed == null ? EmptyFormula.INSTANCE : relaxed;
    }

    private UserFilterRelaxer() {}
}
```

(Mirrors `AttributeHistogramComputer.java:426-452` but the filter is the single group-specific
marker.)

**G1 — Attribute-family histograms:**

- Modify: `evita_engine/.../extraResult/translator/histogram/producer/AttributeHistogramProducer.java`
  — delete the per-attribute `attributeFormulas` Set plumbing. In `fabricate()`, compute the
  histogram against `UserFilterRelaxer.relax(optimizedFormula, ATTRIBUTE_HISTOGRAM)`.
- Modify: `AttributeHistogramComputer.java` — `computeNarrowedHistogramBuckets` becomes a simple
  "compute buckets from the given formula" helper; the per-attribute `shouldBeExcluded` logic is
  gone (the relaxer already stripped all G1 siblings, including the own one).
- Modify: `AttributeHistogramTranslator.java` — stop collecting `attributeFormulas`; the
  `addAttributeHistogramRequest` signature loses the `attributeFormulas` parameter (and its
  callers/serializers).
- Modify: `ReferenceSummaryProducer.java:472` (histogram injection path) — replace
  `this.filterFormulaWithoutUserFilter` with
  `UserFilterRelaxer.relax(this.filterFormula, ATTRIBUTE_HISTOGRAM)`.
- Modify: `ReferenceHistogramAccumulator.java` — JavaDoc: the formula parameter is now
  "baseline with G1 (attribute-family) ranges relaxed".

**G2 — Facet impact:**

- Modify: `ReferenceSummaryProducer.java:109` (facet-impact path) — replace
  `this.filterFormulaWithoutUserFilter` with
  `UserFilterRelaxer.relax(this.filterFormula, FACET_IMPACT)`. Facet **count** and **presence**
  paths keep using `this.filterFormula` unchanged.
- Once both callers are migrated, **delete** the `filterFormulaWithoutUserFilter` field and its
  constructor parameter. Confirm no other consumer remains (search for usages first); if any
  non-histogram, non-impact consumer is found, evaluate it on a case-by-case basis — it is likely
  a bug of the same shape.

**G3 — Price histogram:**

- Modify: `PriceHistogramProducer.java` (exact file to confirm during implementation) — compute
  the histogram against `UserFilterRelaxer.relax(filterFormula, PRICE_HISTOGRAM)` instead of the
  current whole-`UserFilter`-stripped formula.
- Update the corresponding translator / computer to drop any per-price-between exclusion plumbing
  that becomes redundant.

**Cross-cutting invariant:** the only legitimate "strip the whole UserFilter" use case is the
pre-computation of constraints that are literally unrelated to user picks (e.g. target-index
selection). Any *projection over the user's current selection* must go through
`UserFilterRelaxer` with its group.

#### Task 28 — `extractRequestedBucketRange` rewrite

**Files:**

- Modify:
  `evita_engine/.../extraResult/translator/reference/ReferenceHistogramStatisticsTranslator.java`
  - `extractRequestedBucketRange` now walks `userFilter` looking for a `HistogramHaving` whose
    `(referenceName, histogramName, groupSelector)` matches the current histogram slot.
  - The group match is done by comparing the `groupSelector` child (or its absence for the
    ungrouped slot) against the histogram's own group PK tuple. When the translator rewrites, it
    already resolved the group-selector to a PK — reuse that.
  - Duplicate detection: two `HistogramHaving` with identical `(refName, histName, groupPk)` →
    throw.
  - Drop the `findAttributeBetweenInScope` / `ReferenceHaving`-walk helpers — they are now dead.
- Modify:
  `evita_test/.../core/query/extraResult/translator/reference/ReferenceHistogramStatisticsTranslatorTest.java`
  — replace the `ExtractRequestedBucketRange` test class's inputs from
  `userFilter(referenceHaving(...))` to `userFilter(histogramHaving(...))`.

#### Task 29 — `RequestedBucketRange` unchanged

`RequestedBucketRange` (the record) and `requestedBucketPredicate` stay as is. Only the *source*
of extraction changes. No API break downstream.

#### Task 30 — Migration notes

**Files:**

- Modify: `documentation/user/en/query/filtering/behavioral.md` — restore the forbidden-children
  listing; delete the `ReferenceHaving` whitelist section; add a forward-pointer to
  `histogramHaving`.
- This specification document already records the supersession of the Phase G.2 concession in §4
  (Phase G.2 history) and §5 Task 26 (the revert).

### 5.2 Parallel execution plan

The 30 tasks have a tight dependency core but a wide skirt of independently-editable leaves. The
guiding principles:

1. **One Maven per wave, on the main trunk.** Agents do **not** run `mvn install` or
   `mvn clean install`. They run `rtk mvn -pl <their-module> test-compile` (or `test` for their
   own scope) at most — scoped, read-only on dependencies.
2. **Isolated worktrees per agent.** Every agent is spawned with `isolation: "worktree"` so its
   branch / `.m2/repository/.locks` / generated-source tree cannot collide with siblings.
3. **Each wave has one "hot-file owner" per shared file.** Files touched by many tasks
   (`QueryConstraints.java`, `FilterByVisitor.java`, `UserFilter.java`, `ConstraintRegistry.java`,
   `EvitaQL.g4`, `QuerySerializationKryoConfigurer.java`) get a **single** owning agent per wave.
4. **Generated-code steps are serial chokepoints.** `./generate_grammar.sh` and Kryo class-ID
   appends are never parallelised.

#### Dependency backbone (cannot be parallelised)

```
Task 1 (HistogramHaving class + HistogramRangeConstraint marker)
     │
     ├──► Task 4 (grammar rule) ──► Task 5 (regen parser) ──► Task 6 (parser visitor)
     │
     └──► Task 7 (formula markers + HistogramHavingFormula) ──► Task 8 (translator)
                                                                     │
                                                                     └──► Task 27 (relaxer + producers)
                                                                                │
                                                                                └──► Task 28 (extractRequestedBucketRange rewire)
                                                                                             │
                                                                                             └──► Task 26 (restore FORBIDDEN_CHILDREN)

Task 11 (Kryo register APPEND AT END) — serial chokepoint, runs last before the final green build
```

#### Wave 0 — Foundation (serial)

| Task | Files |
|------|-------|
| 1 | `HistogramHaving.java`, `HistogramRangeConstraint.java` |
| 4 | `EvitaQL.g4` (+ new lexer token) |
| 5 | run `./generate_grammar.sh evitaql`, commit regenerated parser sources |
| 7 | `AttributeRangeCarrierFormula.java`, `FacetRangeCarrierFormula.java`, `PriceRangeCarrierFormula.java`, `HistogramHavingFormula.java`, tag `BetweenAttributeFormula`, tag `PriceBetweenFormula`, tag `FacetHaving`-produced formulas |

**Exit check:** `rtk mvn -pl evita_query,evita_engine test-compile` is green.

#### Wave 1 — Breadth (four parallel agents)

| Agent | Scope | Tasks | Owned files |
|-------|-------|-------|-------------|
| **A — Query surface** | `evita_query` DSL | 2, 3, 6 | `ConstraintRegistry.java`, `QueryConstraints.java`, `EvitaQLFilterConstraintVisitor.java` |
| **B — Engine translator** | `evita_engine` translator | 8, 9 | `HistogramHavingTranslator.java` (new), `FilterByVisitor.java` (only the `TRANSLATORS.put` append) |
| **C — Serializer** | `evita_store_server` | 10 | `HistogramHavingSerializer.java` (new). Does **NOT** edit `QuerySerializationKryoConfigurer.java` — that append is held for Wave 4. |
| **D — Relaxer + producers** | `evita_engine` extra-result | 27 | `RangeCarrierGroup.java`, `UserFilterRelaxer.java`, `AttributeHistogramProducer.java`, `AttributeHistogramComputer.java`, `AttributeHistogramTranslator.java`, `ReferenceSummaryProducer.java` (both histogram + impact paths), `ReferenceHistogramAccumulator.java`, `PriceHistogramProducer.java` (+ its computer/translator) |

**Disjointness audit:** `FilterByVisitor.java` — only Agent B; `ReferenceSummaryProducer.java` —
only Agent D; `QuerySerializationKryoConfigurer.java` — nobody in this wave (reserved for Wave 4);
`UserFilter.java` — nobody in this wave (reserved for Wave 2).

Each agent runs `rtk mvn -pl <its-module> test-compile` before declaring done. Merge order
A→B→C→D. Exit check: `rtk mvn -pl evita_query,evita_engine,evita_store/evita_store_server
test-compile`.

#### Wave 2 — Rewire (two parallel agents)

| Agent | Scope | Tasks | Owned files |
|-------|-------|-------|-------------|
| **E — Histogram range extraction** | 28, 29 | `ReferenceHistogramStatisticsTranslator.java`, `ReferenceHistogramStatisticsTranslatorTest.java` |
| **F — FORBIDDEN_CHILDREN + usage migration** | 26 | `UserFilter.java`, `UserFilterTest.java`, plus a sweep of `evita_test` / `documentation/user/en/query/examples` for `userFilter(referenceHaving(...))` → `userFilter(histogramHaving(...))` migrations |

**Why E and F can run in parallel:** disjoint files. Agent E only edits engine+test for
bucket-range extraction; Agent F only edits the query-layer constraint and downstream usages. Exit
check: `rtk mvn -pl evita_query,evita_engine test` focused on the migrated tests.

#### Wave 3 — Tests + docs (six parallel agents)

| Agent | Tasks | Notes |
|-------|-------|-------|
| **G — Constraint & formula tests** | 12, 17 | `HistogramHavingTest`, `HistogramHavingFormulaTest` |
| **H — Parser tests** | 13, 14 | `EvitaQLFilterConstraintVisitorTest`, `EvitaQLFilterConstraintListVisitorTest` |
| **I — Serialization + descriptor counts** | 15, 16 | `QuerySerializationTest`, `ConstraintDescriptorProviderTest` |
| **J — External-API resolver + JSON** | 18, 19 | GraphQL + REST constraint resolver tests, JSON converter test |
| **K — E2E + API functional tests** | 20, 21, 22 | New functional test class + GraphQL + REST additions |
| **L — User documentation + examples** | 24, 25 | `behavioral.md`, `references.md`, `histogram.md`, new `.evitaql` example |

#### Wave 4 — Serial closure

| Task | Why serial |
|------|------------|
| 11 — Kryo register APPEND AT END | Must be the **final** bump to `QuerySerializationKryoConfigurer.java`; any parallel append risks an ID collision that silently corrupts stored WAL. |
| 30 — migration notes | Cross-references resolved only after all prior tasks are on trunk. |

After Wave 4, run **one** `rtk mvn clean install` on the merged integration branch. That is the
only full build in the whole plan.

---

## 6. Cross-cutting Test Plan

Required regression tests — beyond the per-task unit tests listed above:

1. **G1 — Reference histograms, two-sliders, no contraction.** Build a dataset with two histograms
   on the same reference (e.g. `height` and `weight` parameter-values). Run queries:
   - `userFilter()` (empty) → baseline `[min, max]` for both.
   - `userFilter(histogramHaving(... height, 50, 120, height-group))` → height histogram bucket
     `50-120` marked `requested=true`; **both** histograms' `[min, max]` unchanged from baseline.
   - `userFilter(histogramHaving(..., height, 50, 120, height-group), histogramHaving(..., weight,
     90, 140, weight-group))` → both sliders' `requested` flags flipped; **both** `[min, max]`
     still unchanged.
   - Result-set count reflects the AND of both narrowings.

2. **G1 — Attribute-histogram equivalent.** Same shape of test for plain `attributeBetween` inside
   `userFilter` — confirms `AttributeHistogramProducer` now uses the group-scoped relaxation (all
   sibling `attributeBetween` carriers stripped, not just the own one).

3. **G3 — Price histogram, no self-contraction.** `userFilter(priceBetween(10, 100))` — assert the
   returned price histogram still spans the catalog-wide `[min, max]`; the `10-100` bucket carries
   `requested=true`.

4. **G2 — Facet impact, no self-contraction.** `userFilter(facetHaving('brand', pk=42))` — assert
   facet impact for other brand facets is computed against the baseline WITHOUT brand=42 applied
   (so switching brands is meaningful), while histogram and price-range selections (if present)
   stay applied.

5. **Cross-group mutual visibility.** Single combined query
   `userFilter(facetHaving('brand', pk=42), histogramHaving(..., height, 50, 120, height-group),
   priceBetween(10, 100))`. Assertions:
   - **G1 histograms** narrow by brand (G2) + price range (G3), but NOT by the height slider
     (own G1).
   - **G2 facet impact** narrows by height (G1) + price range (G3), but NOT by brand selections
     (own G2).
   - **G3 price histogram** narrows by brand (G2) + height (G1), but NOT by the price slider
     (own G3).
   - Result-set count reflects the AND of all three narrowings (nothing is relaxed for the main
     result).

6. **Non-range / unknown userFilter children always applied.** `userFilter(attributeEquals('inStock',
   true), histogramHaving(...))` — `attributeEquals` narrows everything (main result, histograms,
   facet impact, price histogram); histogram range stays relaxed only in G1 self-computation.

7. **`ReferenceHaving` rejection.** `userFilter(referenceHaving(...))` must fail at constructor
   time after Task 26 — assert `EvitaInvalidUsageException` with the "forbidden in userFilter"
   message.

8. **Descriptor resolution errors.** `histogramHaving('refName')` with missing histogram name and
   two histograms on `refName` → parser/validator rejects with actionable message.

---

## 7. Acceptance

- New constraint parses, serializes, survives round-trips via Kryo + EvitaQL + JSON + GraphQL +
  REST.
- `ReferenceHaving` is no longer accepted inside `userFilter`; all prior usage is migrated to
  `histogramHaving`.
- `UserFilterRelaxer` is the single source of truth for all three projections;
  `AttributeHistogramProducer`, `ReferenceSummaryProducer` (histogram path),
  `ReferenceSummaryProducer` (facet-impact path), and `PriceHistogramProducer` all route through
  it with their respective `RangeCarrierGroup` constant.
- Regression test "no self-contraction" is green for all three groups: G1 (attribute + reference
  histograms), G2 (facet impact), G3 (price histogram).
- Cross-group mutual-visibility test is green: each group's self-computation relaxes only its own
  carriers and continues to honour the other two groups' carriers.
- The existing Phase B test suite still passes after `extractRequestedBucketRange` is rewired.
- No `filterFormulaWithoutUserFilter` usages remain in `ReferenceSummaryProducer` (field deleted
  if no legitimate consumer is left).
- One clean `rtk mvn clean install` passes on the merged integration branch.

---

## Appendix: why not reuse `ReferenceHaving` with a tag?

A lighter alternative would be to keep `ReferenceHaving` as the carrier and introduce a "this is a
histogram range" tag on the formula (no new constraint). Rejected because:

- it keeps the `UserFilter.FORBIDDEN_CHILDREN` concession (bad: `ReferenceHaving` inside
  `userFilter` has no meaning other than "histogram range carrier" for any real user; allowing it
  invites misuse);
- it leaves the verbose `referenceHaving → entityHaving → attributeBetween` nesting for
  independent ranges across groups (the exact thing flagged as cumbersome);
- it blocks syntactic group-selector validation at parse time (arbitrary filter trees vs a single
  typed child).

The `HistogramHaving` cost is a standard-sized new constraint (~15 touchpoints) in exchange for
cleaner semantics and a first-class carrier that slots directly into the G1 marker
(`AttributeRangeCarrierFormula`) used by `UserFilterRelaxer`.
