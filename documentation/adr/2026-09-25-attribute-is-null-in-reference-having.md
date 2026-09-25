---
title: attributeIsNull inside referenceHaving widens candidate discovery and is answered one reference row at a time
date: 2026-09-25
updated: 2026-09-25 23:10
status: accepted
kind: fix
issues: [1584]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute, evita_query/src/main/java/io/evitadb/api/query/filter/EntityScope.java, evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaRequest.java, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference/ReferenceBodyTransposer.java, evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java, evita_engine/src/main/java/io/evitadb/index/cardinality/ReferenceTypeCardinalityIndex.java]
supersedes: []
superseded-by: []
relates: [2026-09-17-row-scoped-reference-having-body, 2026-09-15-bidirectional-reference-counterpart-rewrite]
---

# `attributeIsNull` inside `referenceHaving` widens candidate discovery and is answered one reference row at a time

`referenceHaving(R, attributeIsNull(a))` returned empty, or too few owners, whenever one partition of `R` held
a row carrying `a` next to a row lacking it. The type-level discovery step now widens a null test to the whole
candidate set, exactly as it already did for `not`, and the null test itself is built per reduced index so the
body is evaluated against the row that index holds. Unique attributes, which skipped every index lacking a
unique index, take the same path. Keeping the fix affordable needed a third change: the transposer's union fast
path had never applied to any attribute leaf, which made a plain `attributeIsNotNull` or `attributeEquals` on a
large reference take minutes on `dev`.

## Why

Issue #1584 reported the symptom: `attributeIsNull` on a reference attribute answers empty through reduced
indexes. `2026-09-17-row-scoped-reference-having-body` measured it as its defect D2 (0 owners where 154 are
correct) and left it open. The investigation found three defects stacked on each other, each masking the next:

| # | defect | shape | wrong | correct |
|---|---|---|---|---|
| A | discovery drops mixed partitions | `RH(categories, isNull(a))`, BIDI fixture | 0 | 154 |
| B | the null leaf carries no index tag, so the transposer keeps it whole for every index | `RH(R, and(isNull(a), eq(b, 2)))` | `[1, 2]` | `[2]` |
| C | a unique attribute ignores indexes without a unique index | `RH(R, isNull(u))` | `[4]` + 20 | `[1, 2, 4]` + 20 |
| D | a unique `attributeEquals` / `attributeInSet` leaf carries no index tag either | `RH(R, not(attributeEquals(u, x1)))`, owner with rows `u=x1`, `u=x2` | owner missing | owner matches via its `x2` row |

B was invisible until A was fixed: before that, discovery kept only partitions where *no* row carries `a`, and
there "some row lacks `a`" and "this row lacks `a`" coincide. C was wrong with or without A, on the owner-side
route and on the counterpart rewrite alike. D is B's sibling outside the null test: `FilterByVisitor
#applyOnFirstUniqueIndex` was the one per-index helper that did not tag its result. It bit negations and
multi-value sets only - a single unique value lives in one partition, and discovery narrows the body to it.

### Previous state

`referenceHaving` runs in two stages (see the row-scoped record): discovery evaluates the body against the
type-level `ReferencedTypeEntityIndex` under `NegationResolution.PER_ROW` to pick candidate partitions, then
each chosen `ReducedEntityIndex` evaluates the body again, rebuilt per index by `ReferenceBodyTransposer`.

The type-level `FilterIndex` holds a partition as soon as **any** of its rows carries `a`, so
`allPartitions \ partitionsWithACarrier` at discovery is exactly "partitions where no row carries `a`" — it drops
every partition that also holds a null row. `NotTranslator` had widened to the super set under `PER_ROW` since
the row-scoped work; `AttributeIsTranslator` never learned to. The per-index null formulas were built by
iterating `getEntityIndexStream()` directly rather than through `FilterByVisitor#applyOnIndexes`, which is what
attaches the `IndexTaggedFormula` the transposer relies on.

Why the existing tests stayed green: `EntityByDuplicateReferencesFunctionalTest` uses a `representative`
attribute, so its null rows get their own partition `(target, null)` — the one shape the type-level subtraction
answers correctly.

## Options considered

### Option A — widen to the super set during `PER_ROW` discovery (chosen)

`AttributeIsTranslator#translateIsNull` returns `getSuperSetFormula()` when the processing scope resolves
negation per row, after the attribute schema is resolved so an undeclared attribute is still refused.
`attributeIsNull(a)` is `not(attributeIsNotNull(a))`, and this makes the two spellings plan identically.

- **Pros:** the established rule for `PER_ROW` negation, sound by construction (widening a candidate set never
  loses a row), one guard.
- **Cons:** a null body makes every partition of `R` a candidate; the fixed build pays ~0.76 µs per partition
  to prove even an empty answer (measured below).

### Option B — exact type-level answer from cardinalities (declined)

"Partition p holds a row lacking `a`" ⇔ `rows(p) > Σᵥ rows(v, p)`, from `ReferenceTypeCardinalityIndex` and the
per-attribute cardinality index of the type-level index.

- **Pros:** keeps the candidate set exact.
- **Cons / Rejected because:** wrong for array-valued attributes (one row contributes several values) and for
  localized ones (counts per locale); it is a full scan of the cardinality map per query with no bitmap to
  intersect; and it is a second implementation of the null semantics that must agree with the per-row one
  forever. Revisit only if the cardinality index ever counts **rows carrying `a`** rather than values.

### Option C — tighter candidates: partitions with no carrier ∪ partitions with ≥ 2 rows (deferred)

A single-row partition that carries `a` cannot hold a null row, so only multi-row partitions and carrier-free
partitions can contribute. `ReferenceTypeCardinalityIndex` already stores the row count per partition under
`+pack(indexPk, 0)`, and only counts above 1 — so the multi-row set is exactly its positive keys, no full scan.

- **Pros:** sound regardless of arrays and locales; would take `media` `IS_NULL` from 129 ms to near zero.
- **Cons:** helps only references whose partitions mostly hold one row. `media` does (169,299 rows over
  169,073 partitions, so at most 226 partitions hold two or more); `parameterValues` (1,206,483 rows over 21,632
  partitions), `relatedProducts` and `categories` hold many rows per partition and would gain almost nothing.
- **Rejected because (for now):** after the fix a widened `IS_NULL` costs about **half** of `IS_NOT_NULL` on
  the same reference on every fixture measured, so the fix introduces no outlier; and the single-row partitions
  it would skip are exactly what #1615 (single-owner partition compression) restructures. Revisit with #1615,
  or if a null body on a single-row-heavy reference shows up in a production profile.

### Option D — rewrite `attributeIsNull(a)` to `not(attributeIsNotNull(a))` at the constraint level (declined)

- **Rejected because:** equivalent to A with more machinery, and the rewritten `not` would still need B and C.

## Decision

**Chosen: A, with B and C fixed through the same per-index builder, and the transposer fast path extended.**

- **B + C, one builder.** `createNullSubtractionFormula` builds every per-index null formula through
  `FilterByVisitor#applyOnIndexes`, which tags it with its producing index inside a reference body. Per index:
  no records → nothing; no structure for `a` → every record (the index where everything is null, not one with
  nothing to say); otherwise `superSet \ carriers`, dropped when provably empty.
  **Not** `applyOnFilterIndexes` (nor the unique-index twin, removed once nothing called it): they turn an index without the attribute into `∅`
  before the lambda runs — for a null test, the index where every record matches. A counterfactual with that
  helper turns 12 of 37 tests red.
- **Null and not-null read the same carriers, from filter indexes only.** `getCarriersFormula` is the one source
  both `attributeIsNull` (subtracts it) and `attributeIsNotNull` (returns it) use per index, so the two always
  split an index's records between them. `EntityIndex#upsertAttribute` writes the filter index for every attribute
  that is unique **or** filterable, on every index type; the unique index is not kept everywhere - the group
  indexes (`ReducedGroupEntityIndex`) never get one, and the type-level index of a reference keeps an empty one
  for a localized reference attribute unique across locales, so a unique-index read there found nothing. A
  localized attribute is read in the query locale, except in two cases where it is read as the union of its
  per-locale filter indexes: when it is unique *across* locales in that index's scope (carrying a value means
  carrying it in any locale, which is what its unique index meant), and when the query has no locale at all -
  admitted only because another requested scope is unique across locales, and a locale-less lookup of a
  per-locale filter index would read every record as null.
- **The fast path sees through `AttributeFormula`.** `ReferenceBodyTransposer#combinedOnlyByUnion` returned
  false for any node that was not an `Or`, and every attribute translator wraps its per-index contributions in
  an `AttributeFormula` — so no attribute leaf ever took the fast path the row-scoped record describes. With B,
  a single null leaf would have become projectable and joined them on the quadratic rebuild. The wrapper is a
  unary pass-through, the union distributes over it, and the body is returned with the wrapper still at its
  root, where `AttributeHistogramProducer`, `FilterFormulaAttributeOptimizeVisitor` and `RequirementsDefiner`
  look for it.

### A unique value held by several scopes resolves to the scope listed first in `scope(...)`

Fixing D exposed a second question about the same helper. Uniqueness is enforced per scope, so an archived entity
may carry a unique value a live one carries too, and `applyOnFirstUniqueIndex` keeps the first match. The
`EntityScope` javadoc and the user documentation (`constant.md`) already promised that "first" follows the order of
`scope(...)`, but `EvitaRequest` built its scope array from an `EnumSet`, so LIVE always won:
`scope(ARCHIVED, LIVE)` answered like `scope(LIVE, ARCHIVED)`. Options on the table:

- **Join the first match of every scope** — returns both entities. **Rejected because** a lookup by unique key
  (`getX(code: …)`, `queryOne`) must name one entity, and `EvitaArchivingTest
  #shouldBeAbleToViolateUniqueConstraintsWhenEntityIsArchived` pins that the live one wins under
  `scope(LIVE, ARCHIVED)`.
- **Join only inside `referenceHaving`**, where the two matches are different owners — **rejected because** it
  gives one constraint two rules depending on where it stands.
- **Prefer the scope listed first (chosen)** — keeps the documented contract, and the caller decides the preference
  by the order they write.

Consequences a later change must keep:

- `EntityScope#getScopesInRequestedOrder()` carries the order; `getScope()` stays a membership set. `EntityScope`
  equality is **order-sensitive** — two orders are two queries; the per-query `computeOnlyOnce` cache is the one
  consumer of constraint equality and needs exactly that. `EntityScopeSerializer` writes the requested order in
  the unchanged wire shape.
- A **negated** unique lookup complements the preferred answer, so over both scopes
  `not(attributeEquals(code, v))` returns the entity in the later scope that carries `v`. Documented, pinned.
- The same rule applies to globally unique attributes (`applyOnFirstGlobalUniqueIndex`) and inside the nested
  query of `entityHaving` / `groupHaving`.
- `EvitaRequest#getScopes()` stops at `SeparateEntityScopeContainer`. Before, a `scope(...)` nested in
  `referenceHaving` either failed the query with `MoreThanSingleResultException` (when an outer scope existed) or
  silently became the scope of the whole query (when none did).

## Key technical details

- `FilterByVisitor#applyOnFirstUniqueIndex` tags its result like `applyOnIndexes` does. Every per-index leaf
  that can appear inside a `referenceHaving` body must carry `IndexTaggedFormula` - an untagged one is read as
  index-independent and applied to every row of the owner, with no error.
- `AttributeIsTranslator#translateIsNull` — the `PER_ROW` widening; `#createNullSubtractionFormula`,
  `#getCarriersFormula` — the per-index builder and the carrier source.
- The globally-unique branch of `translateIsNull` is kept as it was: `getOptionalGlobalAttributeSchema` is empty
  inside a reference body (`AbstractAttributeTranslator`), so it is unreachable there.
- `facetHaving(attributeIsNull(a))` is untouched. It is the only `IN_PLACE` consumer and reads "facet none of
  whose rows carries `a`", consistent with how `IN_PLACE` resolves every negation; pinned by tests, not changed.

## Verification

Tests: `ReferenceHavingAttributeIsNullFunctionalTest` (row scoping, unique on the owner route, the counterpart
rewrite route with the route asserted from telemetry, the fetch path, multiple scopes, array-valued and
localized attributes, sibling `referenceHaving`s, identity I1 `RH(φ) ∪ RH(¬φ) = RH()` on data where both sides
are non-empty, `facetHaving` pins), `AttributeIsNullPlanningSkipFunctionalTest` (its three `@Disabled` #1584 rows
re-enabled), `ReferenceBodyTransposerTest` (fast path through the wrapper).

| step | red without the change | green with it | counterfactual |
|---|---|---|---|
| A — widening | 6 of 25 | 25 / 0 | — |
| fast path | 2 of 4 | 176 / 0 targeted | revert: 2 of 4 red |
| B + C | 10 of 16 | 188 / 0 targeted | revert: 10 of 37 red; `applyOnFilterIndexes` helper: 12 of 37 red |
| D — unique tagging | 2 of 4 | 6 / 0 | revert: 3 of 6 red |
| scope order | 7 of 576 | 641 / 0 | revert array derivation / serializer: 6 red |
| nested scope boundary | 4 of 4 (3 threw, 1 leaked) | 4 / 0 | stop class removed: 4 red; `EnumSet` restored: 2 red |
| localized null test without a locale | 2 of 2 | 18 / 0 | revert: the same 2 red |
| not-null from the null side's carriers | 2 of 20 (`EntityLocaleMissingException`; `[]` for `[1]`) | 20 / 0 | revert: 2 of 20 red |

D is proven by `ReferenceHavingUniqueAttributeFunctionalTest`, the scope order by
`UniqueAttributeScopePreferenceFunctionalTest` and `NestedEntityScopeFunctionalTest`, the locale and not-null
rows by the nested `LocalizedAttributeWithScopeDependentUniqueness` of `ReferenceHavingAttributeIsNullFunctionalTest`
(a schema only a raw `SetAttributeSchemaUniqueMutation` can declare: unique across locales in LIVE, within a
locale in ARCHIVED), including `isNull ∪ isNotNull = all` at entity level and I1 inside `referenceHaving`. Last
regression: 5,773 tests across the reference, attribute, facet, query, fetch, archiving, parser, gRPC and
serialization packages and `core/query/**`, 0 failures.

Performance, production retail corpus (130,033 products), interleaved A/B, 2 rounds × 5 × 2 s, ms/op.
Dense fixtures — every row carries the attribute, so `IS_NULL` is empty on both builds and the old build answers
it correctly; what it prices is the cost of *proving* the empty answer. Every run's answer was checked against
an oracle computed from the entity bodies.

| reference (partitions) | shape | before (`dev`) | after | |
|---|---|---|---|---|
| `media` (169,073) | `IS_NULL` | 0.006 | 128.7 | +129 ms |
| `media` | `IS_NOT_NULL` | 281,574 (single op) | 246.9 | ~1,140× |
| `media` | `EQUALS` | 111,670 (single op) | 187.1 | ~600× |
| `parameterValues` (21,632) | `IS_NULL` | 0.17 | 26.2 | +26 ms |
| `parameterValues` | `IS_NOT_NULL` / `EQUALS` | 1,903 / 1,881 | 43.0 / 43.9 | 44× / 43× |
| `relatedProducts` (13,312) | `IS_NULL` | 0.005 | 10.3 | +10 ms |
| `relatedProducts` | `IS_NOT_NULL` / `EQUALS` | 749 / 342 | 22.6 / 15.1 | 33× / 23× |
| `categories` (591) | `IS_NULL` | 0.12 | 1.12 | +1 ms |
| `categories` | `IS_NOT_NULL` | 2.42 | 0.85 | 2.9× |

The old build's near-zero `IS_NULL` *is* defect A: it never looks inside a partition. Profiled with
async-profiler on `media` `IS_NULL` after the change: 99.9 % planning, 99.5 % inside
`createNullSubtractionFormula` — scope filtering of the index stream 33 %, filter-index lookup 24 %, partition
resolution 20 %, owner sets 11 %; allocation is dominated by two `ConstantFormula`s per partition. Flat
per-partition overhead, which only visiting fewer partitions removes — Option C.

Harness: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/ReferenceHavingNullBenchmark.java`
(`-p denseFixture=true` for the fixtures above; its `main` prints the census that picks them).

## Consequences & open follow-ups

- **A null body now costs a walk over the whole partition family** — ~0.76 µs per partition. Option C is the
  lever and belongs with #1615.
- **The row-scoped record's fast-path claim was wrong for attribute leaves until now.** It measured the fast
  path on a synthetic body; in the engine every attribute leaf is wrapped, so a plain
  `referenceHaving(R, attributeEquals(a, v))` took the quadratic rebuild on `dev`. Anything else that wraps
  per-index leaves in a unary container must either be looked through the same way or it silently loses the
  fast path — there is no error, only minutes of planning.
- Traffic recorded before this change stored `scope(...)` in enum order, because the serializer wrote the set.
  The wire shape is unchanged, so old recordings still read, but a recorded `scope(ARCHIVED, LIVE)` replays as
  `scope(LIVE, ARCHIVED)` - the order was never captured and cannot be recovered.
- `hierarchyWithin` accepts a nested `scope(...)` but resolves its parent within the queried scope, ignoring it.
  Pinned as it stands in `NestedEntityScopeFunctionalTest`; whether it should be honoured or refused is open.
- **`OwnerUniqueIndex#recordIds` loses records that still own values.** `unregisterUniqueKeyValue` drops the
  record id eagerly, which its comment calls transient - but in a type-level index one partition owns the values
  of all its rows, so archiving one owner dropped a partition whose other rows still carry values. Only the
  standalone (localized, unique across locales) index is affected; the value tree stays correct, so lookups by
  value are right. No query reads that bitmap any more, but `IndexCardinalityProjection` reports it and the
  storage part persists it.
- Found on the way, not part of #1584:
  - reflected references with an archived owner answer bare `referenceHaving(R)` wrong in `ARCHIVED` (0 vs 1),
    the #1583 family;
  - a filtered `referenceContent(R, filterBy(…))` drops rows from the other scope that the unfiltered one
    returns, even with a filter every row satisfies;
  - whether `facetHaving(attributeIsNull(a))` should keep its "no row carries `a`" reading is a specification
    question.

## Related work

- `2026-09-17-row-scoped-reference-having-body` — defined the row-scoped semantics, the `PER_ROW` negation
  rule this record applies to `attributeIsNull`, and the transposer fast path this record extends; it listed
  #1584 as open.
- `2026-09-15-bidirectional-reference-counterpart-rewrite` — the counterpart route, which evaluated a
  filterable null test correctly per row already and inherited only defect C.

## Timeline

- **2026-09-24** — defects A, B, C reproduced with counterfactuals; design reviewed by an advisor and Codex
- **2026-09-24** — A, fast path, B + C implemented; pilot measurements and profiles on the production corpus
- **2026-09-25** — full interleaved A/B; Option C deferred to #1615; D reproduced and fixed; unique lookups made
  to follow the order of `scope(...)`, and a nested `scope(...)` kept from leaking into the outer query
