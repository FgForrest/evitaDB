---
title: A pick-first reference ordering sorts on the first row of every selected owner, resolved from the selection rather than from the filter
date: 2026-09-23
updated: 2026-09-24 05:50
status: accepted
kind: fix
issues: [1614]
prs: []
areas:
  - evita_engine/src/main/java/io/evitadb/core/query/sort/reference
  - evita_engine/src/main/java/io/evitadb/core/query/sort/attribute
  - evita_engine/src/main/java/io/evitadb/core/query/sort/primaryKey
  - evita_engine/src/main/java/io/evitadb/index/membership
  - documentation/user/en/query/ordering
supersedes: []
superseded-by: []
relates: [2026-09-18-reference-planning-from-owner-membership, 2026-09-15-bidirectional-reference-counterpart-rewrite]
---

# A pick-first reference ordering sorts on the first row of every selected owner, resolved from the selection

`orderBy(referenceProperty(R, attributeNatural(A)))` under `pickFirstByEntityProperty` (explicit, or the default for
a non-hierarchical target) now means one thing on both routes the engine can take to it: an owner sorts on the value
of its **first row, in target order, that carries the value**; every row of the owner takes part whatever the filter
says about `R`; rows sharing one target follow in the order of their representative attribute values; owners with
equal values follow their primary key in the direction of the ordering. The reduced indexes the index route walks are
resolved at execution time from the selected owners, through the owner→partition map the facet trigger and the
planner already maintain, instead of at planning time from whatever candidate set a `referenceHaving` on the same
reference happened to produce.

## Why

Issue #1614 set out to replace the k-way merge of one sorted-records provider per partition with a single provider.
Writing the witness tests for that first showed the two routes did not agree on what the ordering *is*, so the
semantics had to be settled before any provider could be merged away. Measured on a hand-built fixture
(`EntityByReferenceAttributePickFirstFunctionalTest`) against `dev` `edc8601d5`, the same query returned up to four
different orders:

- **Ties.** A plain attribute sort breaks ties by primary key in the direction of the ordering. The reference sort
  broke them by primary key descending inside one partition (the mirrored descending provider) but ascending across
  partitions (`MergedComparableSortedRecordsSupplierSorter`), and the prefetch route broke them by the *referenced*
  primary key first.
- **Narrowing.** A `referenceHaving(R, …)` in the filter made the index route reuse its candidate partitions, so a row
  of a selected owner in a partition without any matching row stopped counting - an index-selection artifact that was
  neither "all rows" nor "matching rows" (the bidirectional rewrite had to decline every query ordering by the
  reference it filters on, only to keep that candidate list alive). The prefetch route never narrowed, and a target
  outside the candidates missed its rank map (`IntIntHashMap.get` → 0), tying it for first place: a third answer.
- **Duplicates.** Rows sharing one target were ordered by which reduced index was created first on the index route and
  by the owner's insertion order on the prefetch route.
- **Coupling.** The prefetch rank map was read off the index route's provider array, so reversing that array changed
  the prefetch answers too.

The cost side was just as lopsided. Without a narrowing filter the index route resolved, at planning time, every
reduced index of the reference and merged a provider for each, whatever the selection. On a production retail catalog
(`Product.media`, 169,073 partitions over 169,072 targets) that is 158 ms for a query selecting **one** owner, rising
to 5.1 s for 9,762 owners.

### Previous state

`ReferencePropertyTranslator` built one `ReducedEntityIndex[]` per query at planning time - the candidate set of a
`referenceHaving`/`hierarchyWithin` on `R` when the index selection built one, the whole family otherwise - ordered it
by target and installed it as `OrderByVisitor#getIndexesForSort()`. `AttributeNaturalTranslator` turned it into a
provider array; `PreSortedRecordsSorter` merged it (`APPEND_FIRST`), letting the first provider in array order claim
an owner, which is already "the first row in target order". `PickFirstReferenceAttributeComparator` derived its rank
map from the same provider array. It was acceptable while nobody compared the routes.

## Options considered

The line of work made four decisions; the maintainer took the three semantic ones on 2026-09-23 after the witnesses
had shown what each alternative meant on concrete owners.

### Narrowing — every row of a selected owner counts (chosen)

- **Pros:** the order of an owner is a function of the owner, as the fetch side and the documentation already
  described it; a filter changes *which* owners come back, never *how they compare*; independent of index selection.
- **Cons:** a narrowed query can no longer walk only the filter's candidates; it has to find every partition of every
  selected owner.

### Narrowing — only rows matching the filter count (declined)

- **Pros:** reads naturally for "products in category X ordered by their order in X".
- **Rejected because:** it needs a row-level evaluation of the `referenceHaving` body per partition on both routes
  (on the prefetch route an `entityHaving` in the body means a second fetch), and a filter would then change how
  owners compare - two queries selecting the same owners would order them differently.

### Narrowing — keep today's partition-level narrowing (declined)

- **Pros:** no cost change on the index route; the prefetch route could be adapted to it by passing the candidate key
  set into its rank map.
- **Rejected because:** it is an artifact of which candidate set the planner happened to build - a row counts when
  some *other* row of the same partition matches - and it forces the bidirectional rewrite to stay off for every query
  ordering by the reference it filters on.

### Ties — owner primary key in the direction of the ordering (chosen) vs. referenced primary key first (declined)

- **Rejected because:** a reference sort must not order equal values differently from a plain attribute sort on the
  same values; `shouldBreakTiesLikePlainAttributeSort` pins the equivalence. The referenced key decides *which* row is
  picked, never the order of the owners.

### Duplicate rows of one target — representative attribute values (chosen) vs. creation or insertion order (declined)

- **Rejected because:** both declined orders are history, not data - an owner re-inserting the same rows in another
  order, or a partition dropped and recreated, would reorder the result.

### Finding the partitions of the selection — membership gather at execution time (chosen)

Per scope, `ReducedIndexMembership` names the covered partitions of each owner plus a residual set; the union over the
selected owners is a superset of the partitions holding a row of the selection. `PickFirstReducedIndexResolver`
resolves it leniently, drops group-family and vanished indexes, probes each against the selection, and falls back to
the whole family when the scope has no membership or when the selection holds more covered owners than the reference
has partitions.

- **Pros:** cost follows the selection (`O(|S| + rows(S) + residual)`) instead of the family; the rule "all rows"
  comes for free because the set is defined by the owners, not by the filter.
- **Cons:** a narrowed query now visits every partition of the selected owners where it used to visit the filter's
  candidates only - bounded by `rows(S)/C` plus the residual set; measured below.

### Finding the partitions — always walk the whole family (declined)

- **Rejected because:** it honours "all rows" at the price of today's worst case on every query - 158 ms for one owner.

### Claiming the owners — one query-local projection (chosen) vs. the k-way merge of one provider per partition (declined)

The projection walks the resolved partitions in target order; each intersects its owners with the still-unclaimed
rest of the selection, resolves the positions of just those owners in its sort index, records `(owner, value)` for
the ones it holds a value for and removes them from the unclaimed set in place; the pairs are sorted once at the end.

- **Pros:** per-partition work is proportional to the partition's own claimed rows; one sort instead of a merge.
- **Cons:** reads the value of every claimed owner and sorts all of them, where a merge reads page-bounded.
- **Rejected because (the merge):** measured, not modelled. The first build kept the merge over the gathered
  partitions and made narrowed queries **4-5× slower** than before (0.07 → 0.28 ms at 10 seeds, 11.7 → 59 ms at
  1,000, 0.34 → 1.5 s at 10,000): every provider reports the whole unclaimed rest back as a new bitmap, so the merge
  costs `partitions × |S|`, and "all rows" multiplies the partitions by the rows per owner. The projection removed it
  (table below). Revisit the merge only for shapes with few, very large partitions and huge selections, where
  reading and sorting every claimed value would outweigh the per-partition copies.

### A persistent owner projection maintained on the write path — deferred

The issue's "default ordering, full family" design: one owner-keyed sort index per reference, attribute and locale,
kept current per touched owner. It is the only design whose cost does not grow with `rows(S)`. Deferred: after the
query-local projection, a 9,762-owner selection sorts in 84 ms against 4.9 s before, and the write-path maintenance
(recomputing the winner when the winning row is removed or loses its value) plus its heap and persistence were not
worth taking on without a production shape that needs it.

## Decision

**Chosen: all rows, owner-pk ties in the ordering direction, representative order for duplicates, the membership
gather resolved at execution time, and a query-local winner-per-owner projection instead of the merge.** Both routes
now share one source of truth for the target order - `PickFirstReducedIndexResolver` - so they can no longer drift
apart, and the measured cost of honouring "all rows" on a narrowed query turned out negative rather than positive.
Revisit the deferred persistent projection when selections of tens of thousands of owners ordered this way become
a measured production shape: that is where the per-query cost still follows `rows(S)`.

## Key technical details

- `core/query/sort/reference/sorter/PickFirstReducedIndexResolver` - resolves the partitions of a selection (index
  route), the target rank (prefetch route, `getTargetRank`) and, lazily, the planning-time array for the consumers
  that still need one (`getPlanningIndexes`). One instance per query plan; memoizes the last selection by identity.
- `core/query/sort/reference/sorter/PickFirstReferenceSorter` - the index route; resolves at `sortAndSlice`, then
  claims owners partition by partition (the projection) and sorts the claimed pairs once.
- `core/query/sort/attribute/comparator/PickFirstReferenceOrder` - the prefetch route's reference order (target rank,
  then `RepresentativeReferenceKey.GENERIC_COMPARATOR`, which puts a `null` representative value first);
  `EntityComparator#prepareForSelection` hands the comparators
  the selection, which a rank over a nested target order needs.
- `MergedComparableSortedRecordsSupplierSorter` / `PreSortedRecordsSorter` take the ordering direction and break
  ties by primary key in it. This also changes multi-scope plain attribute sorts, which had the same mixed rule.
- **Not changed, deliberately:** `traverseByEntityProperty`, and chain attributes (`Predecessor`,
  `ReferencedEntityPredecessor`) under an implicit `pickFirst`, which are sorted block by block. Both keep the
  planning-time, filter-narrowed index set (`ReferencePropertyTranslator#selectPlanningReducedIndexes`); changing
  which blocks exist changes their semantics, which needs its own decision.
- A reference with no reduced index of the referenced-entity family in any processed scope produces no sorter and
  its nested constraints are not planned at all (`ReferencePropertyTranslator#hasAnyReducedIndex`). Planning them
  anyway fails the single-index translators (`attributeSetExact` expects exactly one index), requires the referenced
  collection to exist, and - for a reference indexed for its group family only - lets the prefetch route sort owners
  the index route cannot claim.
- The membership is an accelerator, never an authority: a scope without one walks the whole family, and every
  resolved index is probed against the selection before it is kept. `ReducedIndexMembershipCompletenessTest` guards
  the `covered ∪ residual == advertised` invariant the gather relies on.
- The prefetch route of `entityPrimaryKeyNatural` inside a pick-first `referenceProperty` used to fail on its
  "referenced entity id must be set" premise; it now has its own comparator and prefetches the references it reads.
- Localized reference attributes: the prefetch comparators read the value in the query locale, as the sort index
  stores it; before, the scalar comparator treated a localized value as missing and the compound one tested
  presence without the locale.

## Verification

- `EntityByReferenceAttributePickFirstFunctionalTest` - 35 cases; the original 18 are red on `edc8601d5` except
  index-route ascending, and all are green now: first row with a value, descending ties, ties equal to a plain attribute sort, narrowing-independence,
  duplicates by representative values, paging across the claimed/unclaimed boundary, references no owner has and a
  group-only reference (identical on both routes); both routes, both indexing levels. Counterfactual: reversing the target order
  in `PickFirstReducedIndexResolver` turns all 8 index-route cases red (and 21 of the oracle's 39).
- `EntityByReferenceAttributePickFirstOracleFunctionalTest` - a randomized dataset (300 owners, 40 targets, two
  partitions above the membership threshold, every seventh owner archived) checked against an oracle computed from
  the written rows: 3 target orders × 3 value kinds × 4 selections (small gather, full walk, narrowed, both scopes)
  × 2 directions × 2 routes × 2 indexing levels, plus compound cross-route agreement. Counterfactual: dropping the
  membership's residual set from the gather turns 17 cases red - exactly the small selections, the only ones that
  take the gather rather than the family walk - so the test reaches the residual path it claims to. It also covers
  targets missing from a nested target order and two-value chains (51 cases in total).
- Unit level: `PickFirstReducedIndexResolverTest` (15) and `PickFirstReferenceSorterTest` (9) over a hand-built index
  fixture; `PreSortedRecordsSorterTest` pins the tie direction of the multi-provider merge in both directions.
- Existing ordering suites whose oracles encoded the old tie rule (`entityPrimaryKeyNatural(DESC)` inside
  `referenceProperty`: products of one brand followed in ascending primary key order) were updated to the decided
  rule; nothing else in the ordering, chain, duplicate-reference and bidirectional-rewrite suites changed.
- Performance - `ReferencePickFirstSortBenchmark` (JMH, AverageTime, 5×2 s warm-up and measurement, one fork per
  point, result cache off, `PREFER_INDEX_SCAN` on every measured query with a telemetry check that no point
  prefetched), on a restored production retail catalog: `Product.media` ordered by `mediaOrder`, 169,073
  partitions over 169,072 targets, singleton-dominated (≈5.3 rows per owner). The selection `S` is every owner of
  `k` evenly spaced targets; *narrowed* adds `referenceHaving(media, entityPrimaryKeyInSet(seeds))`. Baseline is
  `dev` `edc8601d5` with the harness copied in, interleaved with the change on the same machine and fixture,
  ms/op, mean of the rounds:

  | `k` → `|S|` | unnarrowed before | unnarrowed after | narrowed before | narrowed after |
  |---|---|---|---|---|
  | 1 → 1 | 165 | 0.011 | 0.015 | 0.017 |
  | 10 → 10 | 692 | 0.038 | 0.070 | 0.053 |
  | 100 → 100 | 1,049 | 0.244 | 0.71 | 0.32 |
  | 1,000 → 999 | 1,714 | 4.5 | 11.7 | 6.4 |
  | 10,000 → 9,762 | 4,928 | 84 | 338 | 104 |

  The same queries without `orderBy` (the rig's control, which the change does not touch) moved by at most 3 µs and
  in no consistent direction (5.2 → 5.2, 8.6 → 11.4, 15.0 → 13.6, 20.0 → 22.7, 63 → 60 µs unnarrowed).

## Consequences & open follow-ups

- **Traverse narrowing** (`traverseByEntityProperty`, and chains under implicit pick-first) still depends on the
  filter's candidate set - the same defect class for block orderings. Settling it needs a decision on whether a block
  of a non-matching target may appear at all.
- **The bidirectional rewrite's ordering guard** (`2026-09-15-bidirectional-reference-counterpart-rewrite`, point 4)
  declines queries that order by the reference they filter on because the ordering used to depend on the candidate
  set. A comparable pick-first ordering no longer does; the guard could be narrowed to traverse and chain orderings.
- **Persistent owner projection** (issue #1614's "default ordering, full family" design) - deferred; see Decision.
- **The chain-ordering gate issue #1614 hands to #1615 still stands.** Chains keep their per-partition blocks, so a
  virtual singleton partition must still resolve as chain position `0` with the record id the write path would have
  indexed before virtual partitions are activated for a chain path; nothing here implements that.
- **Found, not fixed: the prefetch route ignores `inScope`.** Under `orderBy(inScope(LIVE, referenceProperty(...)))`
  with a two-scope filter, the index route claims live owners only, while the prefetch comparators still rank the
  archived owners' rows. The entity-level `AttributeComparator` ignores `inScope` the same way, so this is a general
  prefetch gap rather than a pick-first one; fixing it needs a scope-aware admission check for every entity
  comparator.
- `SortedRecordsProvider`s are still built per query for every partition holding a row of the selection.
- **Found, not fixed: a localized sortable compound on a reference indexed for filtering alone cannot be ordered by
  on the index route.** `EntityIndexLocalMutationExecutor#insertInitialSuiteOfSortableAttributeCompounds` (and its
  removal twin) skip the reduced index unless the reference is `FOR_FILTERING_AND_PARTITIONING`, although
  `ReferenceIndexMutator#applySortableAttributeCompoundSuite` already gates only its entity-level half; the
  reference-level compound therefore never gets its per-locale entries, and the index route leaves every owner
  unsorted while the prefetch route orders them. It predates this change and lives on the write path, so it was
  left for its own fix; the oracle test declares the localized compound on the partitioning reference only and
  says why.

## Related work

- `2026-09-18-reference-planning-from-owner-membership` - widened the owner→partition map to every indexed reference;
  without that widening the gather would find no membership for most references and fall back to the family walk.
- `2026-09-15-bidirectional-reference-counterpart-rewrite` - its ordering guard exists because of the narrowing this
  record removes for comparable pick-first orderings.

## Timeline

- **2026-09-23** - witnesses written against `dev`, semantics decided by the maintainer, reviewed by two independent
  reviewers, implemented and measured.
