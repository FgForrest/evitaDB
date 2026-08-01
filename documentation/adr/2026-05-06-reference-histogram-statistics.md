---
title: Expose reference histograms via ReferenceSummary plus a first-class histogramHaving constraint, with group-scoped baseline relaxation
date: 2026-05-06
updated: 2026-07-31 12:00
status: accepted
kind: feature
issues: [8]
prs: [1136, 1150]
areas: [evita_engine/src/main/java/io/evitadb/core/query/extraResult, evita_engine/src/main/java/io/evitadb/core/query/algebra/filter, evita_query/src/main/java/io/evitadb/api/query/filter]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing, 2026-05-27-range-and-multi-histogram-schema]
---

# Expose reference histograms via ReferenceSummary, with a dedicated histogramHaving constraint

Clients can request `histogramStatistics(...)` inside `referenceSummary` /
`referenceSummaryOfReference` and get per-group bucket data (min/max, buckets, boundary entities)
computed from the index established in
[[2026-04-23-bucketed-histogram-indexing]]. Narrowing a specific histogram's visible range from
`userFilter` now goes through a new `histogramHaving(referenceName, histogramName?, from?, to?,
groupSelector?)` constraint instead of a `referenceHaving(...)` workaround, and all three
"what-if" projections (attribute/reference histograms, facet impact, price histogram) now relax
only their **own** range selections from their baseline — not each other's, and not the whole
`userFilter`.

## Why

Two problems, one fix. **Problem A:** a reference can host several histograms (e.g. `height`,
`weight` on grouped `parameterValues`); expressing "narrow height AND weight independently" with
only `referenceHaving`/`entityHaving`/`attributeBetween` requires a verbose nested tree and forces
`ReferenceHaving` onto `UserFilter`'s allowed-children list — a concession with no other legitimate
use. **Problem B ("sliders contracting under their own handles"):** the three producers that
compute "what if" projections over the user's current selection — `AttributeHistogramProducer`,
`ReferenceSummaryProducer`'s histogram path, and `PriceHistogramProducer` — each stripped either
too little (only the *exact same* formula instance, so sibling range sliders still contracted each
other) or too much (the *entire* `UserFilter`, so histograms ignored the user's brand/category/price
picks entirely). Both problems trace to the same root: there was no shared, principled definition
of "what counts as *my own* selection" for a self-computation.

### Previous state

`ReferenceHaving` was temporarily added to `UserFilter`'s allowed children (documented as a
concession in this feature's own history) purely so `userFilter(referenceHaving(...))` could act as
a histogram-range carrier. `AttributeHistogramComputer.shouldBeExcluded` dropped only the
formula matching the current attribute (Problem A's sibling-contraction case, still present for
attribute histograms even before this record touched them for references). `ReferenceSummaryProducer`
and `PriceHistogramProducer` both stripped the whole `UserFilter` for their respective self-views
(over-stripping, ignoring the other two groups' user picks).

## Options considered

### Appendix-recorded alternative — reuse `ReferenceHaving` with a tag, instead of a new constraint

**Option A — new `HistogramHaving` constraint (chosen).** A dedicated `FilterConstraint` carrying
classifier, optional histogram name, two optional bounds, and an optional single group-selector
child; rewritten at translation time into the equivalent `referenceHaving(...)` tree and wrapped
in a `HistogramHavingFormula` marker.

- **Pros:** lets `ReferenceHaving` return to `UserFilter.FORBIDDEN_CHILDREN` (it now has no
  legitimate meaning inside `userFilter` other than "histogram range carrier", which invited
  misuse); collapses the verbose nested tree for independent per-group ranges into one call per
  range; validates the group selector syntactically at parse time (a single typed child) instead
  of as an arbitrary filter tree.
- **Cons:** a standard-sized new constraint — grammar rule, translator, Kryo serializer, ~15
  touchpoints across query/engine/external-API layers (enumerated in *Key technical details*).

**Option B — keep `ReferenceHaving` as the carrier, tag the resulting formula (declined).**

- **Rejected because** (verbatim from the source document's appendix): it keeps the
  `UserFilter.FORBIDDEN_CHILDREN` concession — `ReferenceHaving` inside `userFilter` has no meaning
  other than "histogram range carrier" for any real user, and allowing it invites misuse; it
  leaves the verbose `referenceHaving → entityHaving → attributeBetween` nesting for independent
  ranges across groups, the exact ergonomics problem being fixed; and it blocks syntactic
  group-selector validation at parse time since the child would be an arbitrary filter tree rather
  than a single typed child.

### Baseline relaxation — one group-parameterised relaxer instead of three ad hoc ones

**Option A — `UserFilterRelaxer.relax(formula, RangeCarrierGroup)` with three disjoint marker
interfaces (chosen).** `AttributeRangeCarrierFormula` (G1: `attributeBetween`, `histogramHaving`),
and G2/G3 dispatched directly to `FacetHavingFormula` / `PriceBetweenFormula` (no separate marker
types were created for those — see *Key technical details*). Each of the three producers calls the
same helper with its own group constant; the helper clones the `UserFilterFormula` subtree dropping
only children matching that group's marker.

- **Pros:** single source of truth for "what counts as self" per group; each group's own carriers
  are stripped while the other two groups' selections stay applied, satisfying the "sliders don't
  contract under their own handles, but do respond to everything else" invariant simultaneously
  for all three groups.
- **Cons:** the three markers deliberately do **not** share a common parent — a flat "RangeCarrier"
  marker would make group-selective peeling (strip G1 only, keep G2+G3) impossible, so each
  producer must know its own group constant rather than reuse a single check.

**Option B — per-producer ad hoc stripping (previous state; implicitly declined).** No source
document argues for keeping the three independent implementations; the document instead documents
each one as a bug (over- or under-stripping) to be replaced. No alternative to a shared relaxer
was weighed — the fix follows directly from diagnosing the shared root cause.

## Decision

**Chosen: `HistogramHaving` (Option A of the first fork) plus the group-parameterised
`UserFilterRelaxer` (Option A of the second fork).** Both are verified present in the shipped tree
(see *Key technical details*). The two forks reinforce each other: `HistogramHaving`'s translator
produces a formula tagged with the G1 marker, which is exactly what makes it interchangeable with
`attributeBetween` inside the relaxer's peeling logic — a Range constraint that couldn't be tagged
this way would have needed its own carve-out in the relaxer.

## Key technical details

Query layer (`evita_query`):

- `HistogramHaving` — `evita_query/src/main/java/io/evitadb/api/query/filter/HistogramHaving.java`.
  Implements `ReferenceConstraint<FilterConstraint>` directly (the plan's separate
  `HistogramRangeConstraint` marker interface was **not** created — a documented deviation from the
  original task list).

Engine layer (`evita_engine`):

- `HistogramHavingTranslator` — `evita_engine/src/main/java/io/evitadb/core/query/filter/translator/histogram/HistogramHavingTranslator.java`
  — resolves `(ReferenceSchema, HistogramIndexDefinition)`, evaluates the optional group selector
  against the referenced-group's global index (must resolve to exactly one PK or it throws),
  rewrites to the equivalent `referenceHaving(...)` tree, and wraps the result in
  `HistogramHavingFormula`.
- `HistogramHavingFormula` — `evita_engine/src/main/java/io/evitadb/core/query/algebra/filter/HistogramHavingFormula.java`
  — pass-through `AbstractFormula`, implements the G1 `AttributeRangeCarrierFormula` marker.
- `UserFilterRelaxer` / `RangeCarrierGroup` —
  `evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/common/`. Per the
  source document's own deviation note: only G1 (`AttributeRangeCarrierFormula`) has a dedicated
  marker interface; the relaxer's `carrierTypeFor()` dispatches G2 and G3 straight to
  `FacetHavingFormula.class` / `PriceBetweenFormula.class` rather than through separate marker
  types. Simpler, same group-selective peeling.
- `ReferenceHistogramAccumulator` —
  `evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference/producer/ReferenceHistogramAccumulator.java`
  — folds histogram computation into the shared `ReferenceSummaryProducer` pipeline by wrapping the
  relevant `FilterIndex` in a one-off `AttributeHistogramComputer` and routing it through
  `QueryExecutionContext.analyse(...).compute()`, so it participates in the shared extra-result
  cache. **No dedicated `ReferenceHistogramComputer` / `FlattenedReferenceHistogramComputer` pair
  exists** — verified absent (`find` for both names returns nothing); the source document flagged
  this as a "readability-only" deferral, and that is still true.
- **`filterFormulaWithoutUserFilter` was not deleted.** Verified still present and used at
  `ReferenceSummaryProducer.java:364` for the facet-impact projection — the source document's own
  admission ("Task 27 (partial)") still holds; it was not fully superseded by the relaxer.
- **`assignedWhen` vs. the plan's `bucketedPartially` naming** — see
  [[2026-05-27-range-and-multi-histogram-schema]]; the per-histogram condition field this record's
  translator resolves through `HistogramIndexDefinition` carries that later name.

Broad-form lenience (PR #1150, the reason this record's accepted date is not the #1136 merge date):

- `ReferenceSummaryTranslator.dispatchHistogramToMatchingReferences` pre-filters references to
  those declaring the requested histogram in every active scope, silently skipping the rest, and
  raises `EvitaInvalidUsageException` only when **no** reference in the schema declares the
  requested name (the typo guard). `referenceSummaryOfReference(...)` (the named-reference form)
  stays strict — the user named the reference explicitly. This replaced an initial "strict on
  every reference" design that made `referenceSummary(..., histogramStatistics(...))` unusable on
  any schema with references that never carry histograms (categories, brands, tag sets).

## Verification

Not re-run in this session; presence and `@Test` counts on `dev` verified directly:

- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/histogram/ReferenceSummaryHistogramFunctionalTest.java` — 21 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/histogram/ReferenceSummaryHistogramValidationTest.java` — 14 tests (covers the broad-form lenience behaviour above).
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/reference/HistogramHavingFunctionalTest.java` — 24 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/query/filter/HistogramHavingTest.java` — constraint-construction tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/algebra/filter/HistogramHavingFormulaTest.java` — pass-through/marker tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/extraResult/translator/common/UserFilterRelaxerTest.java` — 13 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/extraResult/translator/reference/producer/ReferenceHistogramAccumulatorTest.java` — 6 tests.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/extraResult/translator/reference/ReferenceHistogramStatisticsTranslatorTest.java`.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/externalApi/grpc/builders/query/extraResults/GrpcReferenceHistogramConversionTest.java`.
- The named test classes in the (now-retired) source document — `ReferenceHistogramFunctionalTest`,
  `ReferenceHistogramE2EFunctionalTest` — do **not** exist under those names; the classes above are
  their shipped, differently-named, and further-evolved equivalents.

The plan's "10 000-Product / 500 ms" and cross-group mutual-visibility acceptance criteria are
targets set in the plan, not measurements found in the tree — no benchmark artifact for this
feature was located.

## Consequences & open follow-ups

- `ReferenceHaving` is forbidden inside `userFilter` again; any lingering
  `userFilter(referenceHaving(...))` usage from before this record is a bug, not a supported form.
- `filterFormulaWithoutUserFilter` remains a live field on `ReferenceSummaryProducer` — a future
  cleanup could re-examine whether the facet-impact path can also move onto `UserFilterRelaxer`
  (it computes a different projection — impact strips **all** `facetHaving` unconditionally rather
  than group-selectively — so this is not automatically a `UserFilterRelaxer.relax(..., FACET_IMPACT)`
  drop-in; needs its own look before merging the two).
- Histogram results participate in the shared extra-result cache via `FilterIndex.getId()`
  uniqueness — no separate cache-key namespace was introduced, so a collision here would also be a
  cross-feature bug, not one scoped to this record.

## Related work

- [[2026-04-23-bucketed-histogram-indexing]] — same PR (#1136); the index this record's
  `ReferenceHistogramAccumulator` reads from.
- [[2026-05-27-range-and-multi-histogram-schema]] — the follow-up that renamed the per-histogram
  condition to `assignedWhen` and made `bucketed()` an array; this record's `HistogramHavingTranslator`
  resolves through the DTO shape that follow-up finalized.

## Timeline

- **2026-04-16 to 2026-04-21** — `0a71b7bab` (dynamic reference histograms in extra results),
  `452f5861f` (compute dynamic reference histogram statistics), `854f7adb7` (histogramHaving filter
  constraint), `669502a7e` (spec marked all §5 tasks complete).
- **2026-04-23** — PR #1136 merged (`7ba51ae72`), titled "reference histogram statistics with
  histogramHaving constraint and baseline relaxation" — the merge subject names this record's
  scope, even though the same PR also carried [[2026-04-23-bucketed-histogram-indexing]].
- **2026-04-22 to 2026-04-23** — `72e47a548` (name variants on `HistogramIndexDefinition`),
  `14a427141` (more GQL/REST histogram statistics tests), `5a08405ee` (remove now-redundant
  `attributeHistograms` field from GQL extra results).
- **2026-05-06** — PR #1150 merged (`23eac4ad7`, branch `1142-fix-reference-summary-histogram-broad-form`)
  — the broad-form applies-where-defined refinement that supersedes this record's own §3.1
  "strict on every reference" design; taken as the accepted date per the "last PR that completed
  the line of work" tie-break.
- **2026-07-31** — planning document retired, replaced by this record.
