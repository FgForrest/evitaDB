---
title: Answer a referenceHaving from whichever end of a bidirectional reference is cheaper, and stop emitting provably-empty null subtractions
date: 2026-09-15
updated: 2026-09-15 08:58
status: accepted
kind: optimization
issues: [1547, 1583, 1584, 1585]
prs: [1548, 1568]
areas: [evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference, evita_engine/src/main/java/io/evitadb/core/query/algebra/reference, evita_engine/src/main/java/io/evitadb/core/query/indexSelection, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute]
supersedes: []
superseded-by: []
relates: [2026-09-11-reference-name-narrowing, 2026-09-12-committed-snapshot-provenance-for-enrichment, 2026-09-13-per-entity-io-statistics-attribution, 2026-09-15-non-collapsible-formula-marker]
---

# Answer a `referenceHaving` from whichever end of a bidirectional reference is cheaper

A `referenceHaving(R, ...)` is evaluated by materializing one `ReducedEntityIndex` per *referenced
entity* that survives the filter, so a constraint reaching a large collection fans out over that
whole collection. When `R` is one end of a bidirectional (reflected) pair, the counterpart reference
partitions the very same rows the other way round — one reduced index per *owner* — so the identical
question can be answered by visiting one index per candidate owner instead. The planner now compares
the two fan-outs on bitmap cardinalities — one type-index union and one intersection, no reduced
index materialized — and takes the cheaper end when the constraint shape permits it. On the large e-commerce catalogue this replaces **44 390 index visits with 588**.

The same investigation found a second, independent planning-time cost on the same path: an
`attributeIs(NULL)` inside that constraint emitted 44 390 `NotFormula` nodes whose difference was
empty in every single case. Those entries are now omitted at the source.

## Why

After the storage-decode work landed (`2026-09-11-reference-name-narrowing`), the slowest production
GraphQL query on the restored large e-commerce catalogue was no longer bound by I/O. Re-profiled at
warm p50 **243 ms**:

| phase | ms | share |
|---|---|---|
| PLANNING | 203.6 | 75 % |
| EXECUTION | 67.8 | 25 % |
| FETCHING | 5.1 | 1.9 % |

CPU attribution agreed and pointed at one subtree: `HierarchyWithinTranslator.createFormulaFromHierarchyIndex`
74.8 % → `AbstractHierarchyTranslator.createAndStoreHavingPredicate` 73.0 % →
`ReferenceHavingTranslator.applySearchOnIndexes` 48.0 %. Deleting the `anyHaving` from the query took
it from **0.242 s to 0.013 s** — the constraint was 95 % of the query.

The constraint is `hierarchyWithinSelf(..., anyHaving(referenceHaving('products', entityHaving(...))))`.
A JDWP logpoint at `FilterByVisitor:919` counted the reduced indexes each `referenceHaving` selected:

| call | reduced indexes | reference |
|---|---|---|
| 1 | 7 | `Product.stockVisibilities` |
| 2 | **44 390** | `Category.products` |
| 3 | 19 | `Category.representedCategory` |

44 519 products match the sellability filter, so call 2 builds one index per sellable product. Only
**157 of 648** categories lie under the query's 20 hierarchy roots — a ~283× fan-out over what could
possibly matter.

The roots cannot narrow it. The filter handed to the nested query at `FilterByVisitor:882` is
`new FilterBy(referenceHaving.getChildren())` — only the `referenceHaving`'s own children. The
hierarchy constraint targets Category while the nested query runs against Product, so it is absent by
construction, for any root set. The roots *are* handed to the predicate
(`FilteringFormulaHierarchyEntityPredicate.parent`, `int[20]`) and stored, but never read.

### Previous state

Both ends of the pair index the same rows; they differ only in which key they partition by, and the
two partitions are wildly different sizes. Measured on the catalogue:

| | `Category.products` (what the query used) | `Product.categories` |
|---|---|---|
| schema | `ReflectedReferenceSchema`, reflects `Product.categories` | `ReferenceSchema` (the original) |
| reduced indexes | 44 390 | **588** |
| index key → records | product PK → 1–23 categories | category PK → 2 019–3 913 products |
| `indexed` | FOR_FILTERING, LIVE only | FOR_FILTERING_AND_PARTITIONING LIVE + FOR_FILTERING ARCHIVED |
| reference attributes | `assignmentValidity` only (inherited) | + `assignmentPriority`, `orderInCategory`, `categoryPriority` |

**Which end is the reflected one is the opposite of what it looks like**, and both the person who
proposed this work and the person who implemented it had it backwards until a JDWP probe settled it.
`Category.products` — the end that reads like the natural owner of the relation — is the *reflected*
schema; `Product.categories` is the original. This matters because reflected → original resolution is
O(1) from the schema (`getReferencedEntityType()` + `getReflectedReferenceName()`), while original →
reflected requires scanning the other collection's reference schemas.

The second cost sat inside the same constraint. Its `or(attributeInRangeNow(...), attributeIs(..., NULL))`
produced this tree:

```
AND
├── OR                                            or(attributeInRangeNow, attributeIs NULL)
│   ├── AttributeFormula → ConstantFormula 579 pks        folded — every input was a ConstantFormula
│   └── AttributeFormula → OrFormula ×44 390              NOT folded — inputs are NotFormula
│       └── NOT(ConstantFormula, ConstantFormula) ×44 390
└── DeferredFormula                               entityHaving → Product nested query → category PKs
```

The 44 390-way `OR` computed to **0**, and provably so rather than incidentally: every child was
`NotFormula(filterIndex.getAllRecordsFormula(), index.getAllPrimaryKeysFormula())` with both operands
`ConstantFormula`, `sum(subtracted) == sum(superSet) == 152 961`, and every pair equal individually.
Roughly 133 000 formula nodes existed to produce an empty set.

## Options considered

### Option A — evaluate the constraint from the counterpart end, under a cost gate (chosen)

Recognize a supported constraint shape, resolve the counterpart reference schema, and emit owner PKs
by asking one question per candidate owner — "does anything in this owner's reduced index survive the
shared narrowing?" — instead of unioning one index per surviving referenced entity.

- **Pros:** attacks the fan-out itself rather than its symptoms; works in both directions of the pair
  from one implementation; the decision is takeable from type-index cardinalities before a single
  reduced index is materialized; nothing downstream needs to know which end produced the formula.
- **Cons:** correctness surface is large — scopes, attribute inheritance, index components, ordering
  coupling and formula-tree contracts all have to be respected, and several of them fail silently.

### Option B — narrow the nested query with the hierarchy roots (declined)

Pass the enclosing hierarchy constraint down so the nested Product query only resolves products
reachable from the 20 roots.

- **Pros:** would cut the fan-out at its source with no new formula.
- **Rejected because:** the nested query runs against a *different collection* than the hierarchy
  constraint targets, so there is nothing to pass — the narrowing is absent by construction for any
  root set, not merely unimplemented. It would also do nothing for the top-level form of the same
  query, which has no roots at all and was measured slower than the hierarchy one.

### Option C — evaluate the hierarchy predicate lazily, per candidate node (declined)

Stop precomputing a bitmap over the whole collection and test each category as the traversal reaches
it, so only the ~157 reachable ones are ever evaluated.

- **Pros:** would bound the work by what the query can return.
- **Rejected because:** `FilteringFormulaHierarchyEntityPredicate` is a precomputed bitmap and
  `test(nodeId)` is a membership check — this is a redesign of the predicate contract, not a tuning
  change, and it would leave the identical fan-out in place for every non-hierarchical
  `referenceHaving`. Revisit only if hierarchy traversal becomes the dominant cost after this change.

### Option D — take the rewrite in `HavingTranslatorHelper#translateHavingConstraint` (declined)

The seam originally proposed, at the point where the nested query's result is turned into index keys.

- **Pros:** it is where the fan-out first becomes a number, so it is the obvious place to notice it.
- **Rejected because:** that branch runs in the index-*discovery* phase and its contract is "return
  the PKs of the reduced indexes of *this* reference" — referenced-entity PKs. Owner-side PKs
  returned there would not match the index family the evaluation phase subsequently walks. The
  substitution point has to be `ReferenceHavingTranslator#translate`, the one place that turns a whole
  `ReferenceHaving` into a formula over owner PKs with the schema, the scopes and the index supplier
  all in hand.

### Option E — rewrite unconditionally whenever a counterpart exists (declined)

- **Pros:** no cost model to maintain, no gate to get wrong.
- **Rejected because:** measured to regress. The win reverses when the owner collection is the larger
  one: in the reverse direction the owner side already partitions into 588 buckets and the counterpart
  into ~44 000. An early version that collected candidates *before* deciding regressed the reverse
  query from **0.0096 s to 0.017 s purely by declining**, which is why the gate now runs on O(1)
  cardinalities before a single candidate is collected.

### Option F for the null subtraction — fold each `NotFormula` into a constant (declined)

The originally planned fix: compute each empty difference at planning time so the disjunction folds.

- **Pros:** local, needs no new decision at the source.
- **Rejected because:** **it does not work.** This branch does not reach `FilterByVisitor#joinFormulas`;
  it builds its own array and hands it to `FutureNotFormula.postProcess(..., DISJUNCTION)`, which —
  with no `FutureNotFormula` among the inputs — calls the **one-argument** `FormulaFactory.or(Formula...)`.
  That overload never folds constants (only the two-argument `or(superSetSupplier, ...)` has the
  `allConstantOrEmpty` branch) and `getMergedOrFormulas` does not drop `EmptyFormula`s. Folding would
  have shrunk ~133 000 nodes to ~44 000 and collapsed nothing.

### Option G for the null subtraction — compare cardinalities instead of testing the subset (declined)

- **Pros:** O(1) per index instead of a container walk.
- **Rejected because:** equal sizes imply an empty difference only under the assumption that a filter
  index can never hold a record its entity index does not. That assumption is probably true and is
  nowhere stated; an optimization should not be the first thing to depend on it.

### Option H for the null subtraction — subtract eagerly at planning time (declined)

- **Pros:** would collapse the node to a constant in every case, not only the provable ones.
- **Rejected because:** `AbstractFormula#computeSortedConjunctionBitmaps:407` short-circuits on the
  first empty AND sibling, so a query that never evaluates this branch would pay for the subtraction
  anyway — a pessimisation dressed as an optimization.

## Decision

**Chosen: Option A, gated.** The fan-out is a property of which key the reference partitions by, and
that property is already known to the planner before anything is computed — so the planner should
choose. The gate (`MINIMAL_GAIN = 4`) exists because the owner-side count is an *upper* bound that the
nested query narrows further, so a plain "fewer is better" comparison would take losing trades; the
measured reverse-direction regression is what set the margin rather than a plain majority. The other
end becoming the cheaper one is not an exception to handle but the normal case in the reverse
direction, which is why one implementation serves both.

**Chosen for the null subtraction: skip provably-empty entries at the source**, using the exact
subset relation. An all-empty array then hits the zero-length branch of the one-argument `or` and
becomes `EmptyFormula.INSTANCE` — ~133 000 nodes to one — which is the collapse Option F could not
reach.

For Option A to lose, the counterpart end would have to stop being a faithful partition of the same
rows — e.g. if reflected rows were ever allowed to diverge from their originals, or if duplicate
cardinality were lifted without settling how the two index families line up.

## Key technical details

**Entry points.**

- `BidirectionalReferenceRewriter` — the whole decision. `preparePlan` resolves everything decidable
  without building a formula; `tryRewrite` builds one; `isApplicable` is the same decision exposed to
  index selection.
- `ReferenceHavingTranslator#translate` — consults the rewriter before `applySearchOnIndexes`; an
  empty `Optional` is the fall-through for every shape the rewriter cannot reproduce.
- `IndexSelectionVisitor#addReferenceIndexOption` — returns early when the rewrite will take over.
  This is not a micro-optimization: the method otherwise materializes all 44 390 reduced indexes and
  sums every bitmap size for its `HIGH_CARDINALITY` check, which is roughly **3×** of the win on the
  top-level query. It is a no-op inside a hierarchy `anyHaving`, where the filter is planned by a
  fresh `FilterByVisitor` built with `TargetIndexes.EMPTY`
  (`FilteringFormulaHierarchyEntityPredicate:149-153`) and no index selection ever runs.
- `ReferencedOwnerExistenceFormula` — the emitted formula.
- `AttributeIsTranslator#createNullFilterableSubtractionFormula` / `#createNullUniqueSubtractionFormula` —
  the null-subtraction skip, plus `subtractionIsProvablyEmpty`.

**Preconditions, all of which must hold** (each is a deliberate fall-through, not an oversight):
non-empty scope set; neither end allows duplicate cardinality; a counterpart exists and is available;
both ends are `isIndexedInScope` with `REFERENCED_ENTITY` among their indexed components in *every*
requested scope; every attribute the constraint names is inherited by the reflected end and is
filterable or unique on the counterpart; the constraint shape is at most one non-empty `entityHaving`
plus at most one attribute child that is a leaf or a pure `Or` of leaves; and the query does not sort
by `referenceProperty` on the same reference.

**Four traps, each of which produces a plausible wrong answer rather than a failure.**

1. `ReferencedTypeEntityIndex#getAllPrimaryKeys()` returns *reduced-index instance* PKs, not
   referenced-entity PKs — `ReferenceIndexMutator:765-767` inserts `referenceIndex.getPrimaryKey()`.
   The owner keys this rewrite is built on are `getAllReferencedPrimaryKeys()`. Both are int bitmaps
   of plausible size; using the wrong one produces plausible garbage.
2. **`and` inside a `referenceHaving` is cross-row today, and the documentation says per-row.** Each
   attribute leaf is OR-ed across *all* selected reduced indexes inside its own translator
   (`FilterByVisitor#joinFormulas:1582`) and only then conjuncted, so an owner currently qualifies
   with `A` on one reference row and `B` on another. `not` is worse: `NotTranslator` yields a
   `FutureNotFormula` resolved far above against the *owner* superset at the enclosing `filterBy`, so
   it reads as "no discovered row satisfies A", including owners with no references at all. This
   rewrite is per-row, so on those shapes the two paths would legitimately disagree — hence both are
   excluded. An optimization is the wrong place to settle a documented-versus-actual mismatch.
3. **Two independent mechanisms silently widen an enclosing `AND`.** `FormulaOptimizer:117` removes a
   `ChildrenDependentFormula` outright once its children are optimized away, and returning
   `EmptyFormula.INSTANCE` from `getCloneWithInnerFormulas` is the *identity* signal that makes
   `FormulaCloner` drop the node (contract at `Formula.java:79-92`). Removing a `referenceHaving` from
   a conjunction widens the result instead of emptying it. `ReferencedOwnerExistenceFormula` therefore
   does not implement the marker interface, and its clone returns a real instance wrapping an empty
   inner formula — it is the absorbing element of its parent, never the identity one.
4. `orderBy(referenceProperty(R, ...))` changes result *order* when the reference's `TargetIndexes`
   entry is gone: `ReferencePropertyTranslator#selectReducedEntityIndexSet:104-122` falls back to
   every reduced index of `R`, ordering owners by their first reference row rather than their first
   *matching* one. Such queries are declined.

**The per-owner formulas are internal state, not inner formulas.** They are positionally paired with
the owner keys, and `FormulaCloner` is allowed to drop a child its mutator rejects — a dropped child
would silently re-label every owner after it. `initialize`, `includeAdditionalHash`,
`gatherBitmapIdsInternal`, `getEstimatedBaseCost` and `getCostInternal` all override to account for
formulas the inner-formula walk cannot see.

**Candidate owners are intersected with the owner collection's in-scope global PKs.** This is a
correctness requirement, not a narrowing: the counterpart's reduced indexes are keyed by owner PK
*regardless of that owner's own scope*, so without the intersection the rewrite could emit an owner
the owner-side evaluation never would.

**Cache correctness.** A formula must either declare a transactional id for every bitmap it depends
on, or hash that bitmap's contents into its key. `ReferencedOwnerExistenceFormula` hashes the owner
key set, every per-owner formula hash and the counterpart type-index transactional id. For the null
subtraction the skip decision is encoded in the *tree shape*, and the shape is part of the cache key:
if a difference later turns non-empty the term reappears, the enclosing `OrFormula`'s hash changes,
and the query produces a different key — a miss, never a stale hit. The general invariant, and the
fact that `CacheSupervisor`'s JavaDoc documents a validity-span invalidation design `CacheEden` does
not implement, is written up on issue #37.

**The null-subtraction array can now be empty** where it previously held one entry per index in
scope. An empty array must mean an empty result, never "no filter".

## Verification

All timings on the restored large e-commerce catalogue (648 categories, 119 447 products), warm,
**median of 5 runs**, query cache disabled, `-Xms8g -Xmx16g`, no debugger attached. `q1` is the
production hierarchy query `edeeFulltextCategoriesQueryProductEvita`; `gt` is the top-level
`listCategory(referenceProductsHaving(entityHaving(sellable), or(validityInRangeNow, validityIs NULL)))`;
`rf` is the reverse-direction product query, where the gate is expected to decline.

**Equivalence, established before any code was written.** Ground truth `gt` returns **579**
categories — the same 579 a JDWP breakpoint showed inside `q1`. The opposite side, computed as 648
per-category probes `queryProduct(sellable, referenceCategoriesHaving(entityPrimaryKeyInSet:[c], or(validity)))`,
returns 579 with a non-zero count. **The two sets are exactly equal — the symmetric difference is
empty.**

**Counterpart rewrite, same build, toggle off versus on:**

| query | off | on |
|---|---|---|
| q1 — hierarchy `anyHaving` | 0.264 s | **0.055 s** |
| gt — top-level `referenceHaving` | 0.303 s | **0.024 s** |
| rf — reverse direction (gate declines) | 0.020 s | 0.021 s |

Identical 579-category result throughout, verified by exact set comparison and by byte-identical
response sizes. The constraint in isolation went **0.265 s → 0.0035 s (~75×)**, matching the
44 390 / 588 = 75.5 index ratio; the shared nested product query alone costs 0.0061 s.

**Null-subtraction skip, measured with the rewrite disabled** so the 44 390-index path is exercised:

| query | before | after |
|---|---|---|
| gt | 0.303 s | **0.198 s** |
| q1 | 0.264 s | **0.225 s** |

With the rewrite enabled these queries no longer fan out, so the skip is neutral there (q1 0.047 s,
gt 0.027 s) — its value now lies on every path the rewrite declines.

**Both changes enabled, final state** (medians of 5 warm runs; run 1 excluded as cold):

| query | median | response |
|---|---|---|
| q1 | 0.050 s | 7 385 B |
| gt | 0.027 s | 13 149 B |
| rf | 0.018 s | 67 B |

**Correctness probes.** `attributeIs(NULL)` and `attributeIs(NOT_NULL)` counts must sum to the
collection total: verified **648/648** on Category and **119 447/119 447** on Product, with
`refNull = 0` / `refNotNull = 87 513` on the reference attribute — which is exactly why all 44 390
differences were empty. The planning-time collapse was verified live: the collapsing constraint
returns 0 alone, absorbs correctly in a conjunction, behaves as the identity in a disjunction, and
extra-result production survives it.

**Automated coverage.** A suite was built specifically to attack both changes — four functional
classes over one shared `BIDI_REWRITE` dataset plus two unit classes — and is what found the defects
listed below. The full regression sweep across everything either change can reach stands at **2 525
tests, 0 failures, 7 skipped**; the seven skips are the six rows pinning the three pre-existing
defects filed as issues (kept in the tree, `@Disabled`, each naming its issue) plus one skip that
predates this work.

## Consequences & open follow-ups

### Six defects the test suite found, and what fixed them

A 124-row suite was built specifically to attack this change (four functional classes plus two unit
classes, over one shared `BIDI_REWRITE` dataset). It found seven defects, four of them in this work.

| Defect | Fix |
|---|---|
| Cross-scope owners silently dropped: the counterpart records its half of a relation in the **target's** scope, so reading it in the owner's requested scopes misses every cross-scope row | `counterpartScopes` — the requested scopes plus every scope where *both* ends are indexed, which is exactly where `isRelationMaintained` permits such a row. Applied to candidate collection, per-owner index lookup **and** the bare narrowing set; any two of the three still drops the owner |
| The nested `entityHaving` plan was installed as a **visible child**, so passes that match nodes by reference name, facet id or price accessor claimed target-namespace nodes as the owner's — leaking facets into the owner's reference summary and inflating hierarchy statistics | wrap it in a terminal `DeferredFormula`, the same device `HavingTranslatorHelper` already uses on the ordinary path. One change closed the facet leak at both `COUNTS` and `IMPACT` depth *and* the hierarchy inflation |
| The `attributeIs(NULL)` skip made `userFilter` collapse at **planning** time, and `FormulaOptimizer` replaced the collapsed container with a bare `EmptyFormula` — destroying the marker `ExtraResultPlanningVisitor` uses to compute the mandatory baseline, so facet and reference summaries were computed against nothing | the first fix special-cased `UserFilterFormula` in `FormulaOptimizer.Optimizer.apply`. It was **incomplete** — it guarded the container but not the conjunction *inside* it — and has been replaced by the `NonCollapsibleFormula` marker: `2026-09-15-non-collapsible-formula-marker`. Both shapes also fix a pre-existing instance on the unmatched-unique-lookup path |
| The cost gate summed candidate cardinalities per scope, double-counting an owner announced in two scopes and declining plans up to twice as cheap as the threshold | fold the gate onto the true union cardinality, which the candidate collection already computes |

**One inefficiency of this work was also closed**, and is recorded here because its fix introduced a
contract others will meet: the rewrite plan was derived twice per constraint, once by
`IndexSelectionVisitor#isApplicable` and again by `ReferenceHavingTranslator#tryRewrite`.
`QueryPlanningContext#computeOncePerConstraint(constraint, scopes, supplier)` now memoizes it. The key
is the **constraint identity together with the scope set** — index selection legitimately explores the
same constraint against different scope sets, so a key omitting the scopes would serve one alternative's
answer to another, and on a single-scope schema (`Scope.DEFAULT_SCOPES` is `{LIVE}`) the mistake would
never surface in a test. Unlike `computeOnlyOnce`, it is deliberately **not** delegated to a parent
context: that method's keys carry index identifiers, these do not.

The rest are **pre-existing and unrelated to either optimisation**. Each was proven pre-existing by
measuring it against unmodified `src/main`, filed as its own issue against milestone 2026.3, and left
pinned by a `@Disabled` row that names the issue — so the reproduction stays in the tree rather than
being deleted along with the failure:

- **#1583 — a reflected reference on an archived owner gets no index at all**, although the schema
  declares it indexed in that scope and the entity body still carries the rows. Original references on
  the same archived entity do get one. This makes the *ordinary* path under-report on a two-scope query
  and the *rewrite* under-report on a live-scope one — one defect, two signs.
- **#1584 — `attributeIsNull` on a reference attribute through reduced indexes returns empty.**
  `NOT_NULL` resolves correctly through the very same indexes (measured 76 and 230 where correct),
  `NULL` returns 0 where 154 is correct, so the two do not partition the owner set. One pre-existing
  green test contradicts the obvious mechanism and is cited in the pinning row.
- **#1585 — `not(...)` nested inside `referenceHaving` is silently ignored.** Owners all of whose rows
  satisfy the negated constraint come back present; the constraint does not narrow at all. This is the
  measurement behind the third corrected JavaDoc claim below, and it is why a nested `not` is a hard
  decline for the rewrite rather than a shape it reproduces.

### One deliberate narrowing

`FilterByVisitor#getEntityIndexStream` narrows the indexes it sees to the requested scopes. A per-owner
reduced index living outside them — precisely what the cross-scope fix reaches for — is filtered out
before a **reference-attribute** constraint is evaluated. Rather than widen the visitor's scope set,
which changes how every nested constraint in the engine resolves, the rewrite declines when attribute
constraints are present *and* an out-of-scope index actually announced an owner. Results stay correct
via the owner-side path. **Unreachable on a default schema** — `Scope.DEFAULT_SCOPES` is `{LIVE}`, so
the two scope sets coincide; it took a fixture declaring every reference in both scopes to reach it.
Widening the visitor is the obvious follow-up and was judged too large to take unsupervised.

### Behavioural pins beat source-derived preconditions — three times in one night

The claim that reduced indexes exist only at `FOR_FILTERING_AND_PARTITIONING`, and that
`referenceUsableInScopes` therefore needed tightening, was made **three times by three independent
routes** — the original test design, a reviewer reading the fan-out call site, and the method's own
JavaDoc. All three were wrong, and acting on any of them would have disabled this optimisation for its
own headline production query, since the catalogue's `Category.products` is `FOR_FILTERING`-only in
LIVE. One behavioural row asserting both the result **and** that the rewrite fired settled it each
time. That is the argument for this suite existing, and it generalises: an expectation computed by the
same traversal as the code under test is not an oracle.

### Three JavaDoc claims in `src/main` were measured to be false

All three were load-bearing — readers reasoned from them and reached wrong conclusions tonight.

- `referenceUsableInScopes` claimed to verify "entity-level partitioning"; it tests
  `getIndexedComponents(scope).contains(REFERENCED_ENTITY)`, an orthogonal axis any indexed reference
  satisfies. Corrected, with an explicit *do not tighten this* naming the row that pins it.
- `collectAttributeNames` claimed `and` matches **across** rows. Measured: two attribute siblings must
  be satisfied by a single row.
- The same paragraph claimed a nested `not` reads as "no row satisfies A". Measured: **it does not
  constrain at all** — owners all of whose rows satisfy the negated constraint come back present. Worth
  recording that the *first* correction to this bullet was also wrong ("some row does not satisfy A");
  only a second query, negating a value every row of two owners carried, could distinguish the two.

Both exclusions may therefore be conservative rather than necessary: if `and` and `not` are per-row and
the rewrite is per-row, the rewrite may faithfully reproduce shapes it currently refuses. **Not acted
on** — recorded as a follow-up with the two measurements as its evidence.

### Still open

- **588 isolated translator runs remain at plan time.** The cheaper form computes the leaf bitmaps once
  at planning and keeps `(ownerPk, Bitmap[])`, gathering transactional ids by hand.
- **Duplicate-allowing cardinality falls through.** Lifting it needs
  `EntityCollection#getIndexByPrimaryKeyIfExists`, because `getReducedEntityIndexes`' duplicate branch
  resolves index PKs against the *owner* collection's map.
- **Groups are excluded outright.** Whether per-row group values are mirrored onto reflected rows is
  unresolved — `ReferenceBlock` contains no group handling at all, which suggests they are not. Until
  that is settled, `entityGroupHaving` must remain a hard decline.
- `orderedByTheSameReference` over-matches a same-named `referenceProperty` nested inside an
  `entityProperty`, declining a rewrite that would have been safe. Cost only, never a wrong result.

## Related work

- `2026-09-11-reference-name-narrowing` — same issue, same query. That change moved storage decode off
  the profile, which is *why* planning became 75 % of `q1` and this fan-out became the top cost.
- `2026-09-13-per-entity-io-statistics-attribution` and
  `2026-09-12-committed-snapshot-provenance-for-enrichment` — the other two records from #1547,
  sharing PR #1548.
- `2026-09-15-non-collapsible-formula-marker` — grew directly out of this work. The
  `attributeIs(NULL)` skip moved emptiness from execution to planning time, which is what exposed
  `FormulaOptimizer`'s collapse as destructive to side-channel carriers. That record owns the marker,
  its rejected alternative and the carrier inventory.
- Issue #37 — the cache-invalidation invariant this change had to satisfy, and the gap between
  `CacheSupervisor`'s documented design and `CacheEden`'s implementation.

## Timeline

- **2026-09-09** — read-path query shapes profiled against a restored production catalogue (#1547)
- **2026-09-14** — `q1` re-profiled after the storage-decode work: 75 % planning, 2 % fetching; the
  fan-out located and counted (44 390 versus 588) and the two ends proven to return the same 579
  categories by exact set comparison
- **2026-09-14** — counterpart rewrite implemented and measured; four correctness traps found by
  review; null-subtraction skip implemented after the planned fold was disproved; feature toggle
  removed
- **2026-09-14, evening** — 124-row test suite built over one shared dataset; baseline taken against
  unmodified `src/main` (80 pass / 22 fail); four defects in this work fixed and two pre-existing ones
  isolated; regression swept clean across everything `FormulaOptimizer` can affect (342 tests, 0
  failures)
- **2026-09-15** — the three pre-existing defects filed as #1583/#1584/#1585 with their pinning rows
  disabled and cross-linked; the duplicated plan preparation memoized; the incomplete
  `UserFilterFormula` guard replaced by the `NonCollapsibleFormula` marker
  (`2026-09-15-non-collapsible-formula-marker`); full sweep green at 2 525 tests
