---
title: A pick-first reference ordering sorts on the first row of every selected owner, resolved from the selection rather than from the filter
date: 2026-09-23
updated: 2026-09-25 16:25
status: accepted
kind: fix
issues: [1614]
prs: [1643]
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
resolves it leniently, drops group-family and vanished indexes, and falls back to the whole family when the scope has
no membership or when the selection holds more covered owners than the reference has partitions. Under the plain
primary key target order it does **not** probe each resolved index against the selection: the sorter intersects
every index with the owners still unclaimed anyway, and repeating that intersection in the resolver measured at half
the sort time for 80,187 owners over 4,022 partitions; a target's rank is its own key there, so the extra targets of
the superset cannot move the others. Under any other target order every index **is** probed, because a nested order
may depend on the whole set it orders - `pickFirstByEntityProperty(randomWithSeed(...))` is a seeded Fisher-Yates
permutation of the whole target set (`RandomSorter`), so one more target changes the draws and with them the relative
order of the others. An unprobed superset therefore let a target referenced only by an unselected owner change which
row of a selected owner is picked, identically on both routes (an adversarial review found it; the route-agreement
tests could not).

- **Pros:** cost follows the selection (`O(|S| + rows(S) + residual)`) instead of the family; the rule "all rows"
  comes for free because the set is defined by the owners, not by the filter.
- **Cons:** a narrowed query now visits every partition of the selected owners where it used to visit the filter's
  candidates only - bounded by `rows(S)/C` plus the residual set; measured below.

### Finding the partitions — always walk the whole family (declined)

- **Rejected because:** it honours "all rows" at the price of today's worst case on every query - 158 ms for one owner.

### Claiming the owners — per-partition claims by bitmap and a lazy merge of their runs (chosen)

The sorter walks the resolved partitions in target order and lets each claim the still-unclaimed owners it holds a
value for. A claim reads no value: it is the positions of the claimed owners in the partition's sorted provider, and
their removal from the unclaimed rest. Each partition is asked in the cheaper of two ways - a rest at least 16×
smaller than the partition is resolved against it directly (dev's own per-provider step: the not-found result is the
new rest), anything else is intersected with the partition first - a partition without a sort index of the value is
skipped before any bitmap is touched, and one holding no unclaimed owner before its provider is built. The positions one partition claimed, walked in ascending order, are already
in value-then-owner order in the ordering direction, so each partition yields a sorted run; the runs are merged
lazily by their head values and the merge stops at the end of the requested page.

- **Pros:** per-partition work follows the partition's own unclaimed owners, never the size of the selection times
  the number of partitions; values are read for the returned page only; the walk ends once every owner is claimed.
- **Cons:** owners that carry the value on no row force a visit of every partition that holds them - the same as on
  `dev`, and the reason the cheap per-partition checks exist.

The shape was reached by measuring five alternatives on the production catalog (tables under Verification):

- **Rejected: the k-way merge of one provider per partition.** The first build kept it over the gathered partitions
  and made narrowed queries on `Product.media` **4-5× slower** than before (0.07 → 0.28 ms at 10 seeds, 11.7 → 59 ms
  at 1,000, 0.34 → 1.5 s at 10,000): every provider reports the whole unclaimed rest back as a new bitmap, so the merge
  costs `partitions × |S|`, and "all rows" multiplies the partitions by the rows per owner.
- **Rejected: an eager projection** that read `(owner, value)` for every claimed owner and sorted all of them once. It
  fixed the merge's cost but is not bounded by the page - page 1 of 20 read and sorted every claimed value, which an
  adversarial review flagged for high-fan-in references. The lazy merge of runs replaced it; on the measured shapes
  the two did not differ within noise, so the change is kept for the bound, not for a measured gain.
- **Rejected: intersecting every partition with the unclaimed rest.** On `Product.groups` (4,022 partitions, 129,963
  owners) it was 1.4-2.7× slower than `dev` for 8,508-80,187 owners; async-profiler put 67 % of the sort in array
  container intersections, most of them against partitions that could claim nothing.
- **Rejected: looking every owner of a small partition up in the rest** instead of intersecting. 2.8× slower still
  (4,658 vs 1,657 µs at 8,508 owners): summed over the partitions it touches every row of every owner of the
  reference, where an intersection touches the rows of the selection.
- **Rejected: resolving the rest directly whenever it is no larger than the partition** (dev's step at comparable
  sizes). A narrowed query over 3,336 owners on `Product.categories` looked the whole rest up in each of the 342
  large residual partitions: 1,650 µs against 574 µs with the 16× threshold.
### A persistent owner projection maintained on the write path — deferred

The issue's "default ordering, full family" design: one owner-keyed sort index per reference, attribute and locale,
kept current per touched owner. It is the only design whose cost does not grow with `rows(S)`. Deferred: after the
query-local claims, a 9,762-owner selection sorts in about 0.1 s against 4.9 s before, and the write-path maintenance
(recomputing the winner when the winning row is removed or loses its value) plus its heap and persistence were not
worth taking on without a production shape that needs it.

## Decision

**Chosen: all rows, owner-pk ties in the ordering direction, representative order for duplicates, the membership
gather resolved at execution time, and per-partition claims merged lazily instead of the provider merge.** Both
routes now share one source of truth for the target order - `PickFirstReducedIndexResolver` - so they can no longer
drift apart. Unnarrowed orderings and narrowed ones over large selections are at parity or up to 10,000× faster than
before. The one shape that got slower is a narrowed query over a small or moderate selection on a reference with many
residual partitions (1.5-3.1×, sub-millisecond to a few milliseconds): "all rows" obliges it to check every partition that may
hold a row of a selected owner, residual ones included, where it used to look at the filter's partitions only. The
maintainer accepted that cost on 2026-09-24 because it falls on queries that are fast in absolute terms.
Revisit the deferred persistent projection when selections of tens of thousands of owners ordered this way become
a measured production shape: that is where the per-query cost still follows `rows(S)`.

## Key technical details

- `core/query/sort/reference/sorter/PickFirstReducedIndexResolver` - resolves the partitions of a selection (index
  route), the target rank (prefetch route, `getTargetRank`) and, lazily, the planning-time array for the consumers
  that still need one (`getPlanningIndexes`). One instance per query plan; memoizes the last selection by identity.
- `core/query/sort/reference/sorter/PickFirstReferenceSorter` - the index route; resolves at `sortAndSlice`, then
  claims owners partition by partition (the projection). The owners one partition claims come out in its provider's
  order, which is already value then owner primary key, both in the ordering direction (the descending supplier is
  the exact mirror of the ascending one), so each partition yields a sorted run; the runs are merged lazily and the
  merge stops at the end of the requested page. A provider whose ties did not follow the owner primary key in the
  ordering direction would break that merge - the unit test fixture builds its providers exactly as `SortIndex` does
  for that reason.
- `core/query/sort/attribute/comparator/PickFirstReferenceOrder` - the prefetch route's reference order (target rank,
  then `RepresentativeReferenceKey.GENERIC_COMPARATOR`, which puts a `null` representative value first);
  `EntityComparator#prepareForSelection` hands the comparators
  the selection, which a rank over a nested target order needs. The comparators sort only owners living in a scope
  the ordering processes (`PickFirstReferenceTargetRanking#admits`): under `inScope(LIVE, ...)` over a two-scope
  filter the index route never sees the archived owners, so the prefetch route must hand them to the next sorter
  too.
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
  targets missing from a nested target order and two-value chains (69 cases in total, with the scope cases below).
- `shouldOrderOwnersOfOrderingScopeOnlyIdenticallyOnBothRoutes` (18 cases in the oracle test) - `inScope(LIVE, ...)`
  over both scopes on both routes against the oracle. Counterfactual: admitting every scope in the prefetch
  comparators turns exactly these 18 red, each on the prefetch route only.
- Unit level: `PickFirstReducedIndexResolverTest` (18) and `PickFirstReferenceSorterTest` (13, including every page
  window of the full order in both directions, the direct-resolution path, lazy provider creation and no provider
  for a partition holding no unclaimed owner) over a
  hand-built index fixture. Counterfactuals: concatenating the runs instead of merging them turns 80 of 147 ordering
  cases red across the unit, witness, oracle and existing reference-ordering suites; dropping the probe under a nested
  target order turns the 4 set-dependence cases red; building providers before the intersection turns the laziness
  case red, and building one on the direct path before it turns the no-unclaimed-owner case red; `PreSortedRecordsSorterTest` pins the tie direction of the multi-provider merge in both directions.
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

  The table above is the build measured on 2026-09-23. The final build (per-partition claims, lazy merge, lazily
  built providers) measured on 2026-09-24, one round: unnarrowed 0.010 / 0.037 / 0.27 / 5.6 / 94 ms and narrowed
  0.016 / 0.054 / 0.33 / 5.9 / 104 ms for the same five selections, against `dev` 158-172 ms / 686-698 /
  1,027-1,072 / 1,697-1,726 / 4,765-5,175 ms and 0.015-0.017 / 0.069-0.071 / 0.68-0.78 / 11.7-12.0 / 323-344 ms.
  The 999-owner points spread by up to ±40 % between runs (5.6-9.8 ms narrowed across the intermediate builds).
- Performance at high fan-in - `Product.categories` ordered by `orderInCategory` under an explicit
  `pickFirstByEntityProperty` (the target is hierarchical, so the default would be traverse): 591 partitions over
  228,126 rows, 342 of them residual, every selected owner carrying the value on some row. Same rig, `dev` against
  the final build, two interleaved rounds each, µs/op:

  | `k` → `|S|` | unnarrowed dev | unnarrowed after | narrowed dev | narrowed after |
  |---|---|---|---|---|
  | 10 → 3,336 | 3,670 / 3,730 | 469 / 438 | 198 / 206 | 549 / 545 |
  | 100 → 22,301 | 12,492 / 13,163 | 2,252 / 2,301 | 5,942 / 5,570 | 2,576 / 2,528 |
  | 500 → 80,924 | 46,925 / 48,304 | 4,021 / 3,682 | 52,144 / 52,743 | 4,783 / 4,957 |

  The 3,336-owner narrowed point was re-measured on request with three builds interleaved (`dev` 198 / 336,
  a build resolving the rest directly at comparable sizes 1,685 / 1,645, the 16× threshold 550 / 573): the loss is
  real and is the cost of rule 2 described under Decision.

  Review of the PR found that the direct branch of the claim built a partition's provider before checking that the
  partition held an unclaimed owner. Under a primary-key target order the resolver hands over partitions without
  probing them, so disjoint partitions reached the sorter and each paid for a provider sized to its index. Gating
  both branches on the intersection, measured on 2026-09-25 against the build before it (same fixture, two
  interleaved rounds each, median of the iterations, µs/op): unnarrowed 464 → 306, 2,158 → 1,915, 3,535 → 3,637;
  narrowed 523 → 369, 2,743 → 2,111, 4,388 → 4,390 for the three selections above. The 80,924-owner points are
  inside the noise of single slow iterations (up to 6,500 µs in both builds). `Product.media` (one round) moved by
  -9 % to +5 % with no consistent direction. `Product.groups` at 80,187 owners, over three interleaved rounds:
  unnarrowed 1,608 → 1,536, narrowed 2,815 → 2,942 µs. The narrowed +4.5 % is within the 8 % spread between the
  pre-fix build's own rounds; if it is real, it is the intersection test finding an overlap in nearly every
  partition (3,150 of 4,022) and saving nothing. The smaller `groups` selections moved by -4 % to +0.3 %.
- Performance with no value at all - `Product.groups` ordered by `assignmentPriority` (4,022 partitions, 576
  residual). **No row of the corpus carries that attribute** (0 of 427,163; the only populated one, `orderInGroup`,
  is a `Predecessor` chain and takes the unchanged chain path), so every selected owner stays unclaimed and every
  partition holding one is visited: the worst case for the walk, not a high-fan-in ordering. `dev` (mean of two
  rounds) → final, one round, µs/op: unnarrowed 894 → 115 (477 owners), 939 → 422 (8,508), 1,768 → 1,734 (80,187);
  narrowed 49 → 152, 334 → 633, 1,967 → 2,885.

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
- **Found, fixed for pick-first only: the prefetch route ignores `inScope`.** The pick-first comparators now admit
  owners of the ordering's scopes only. The entity-level `AttributeComparator` (and the other entity comparators)
  still sort owners of every scope on the prefetch route under `inScope`, while their index route sorts the named
  scope only; that general gap needs the same admission check in each of them and is left for its own fix.
- `SortedRecordsProvider`s are still built per query, but only for partitions that hold an unclaimed owner: the
  factory the sorter receives answers "has this partition a sort index of the value" cheaply and hands back a lazy
  source, because building a provider creates its value seeker eagerly
  (`SortIndex#createSortedComparableForwardSeeker`, ~13 % of a 999-owner `media` sort when it was built for every
  candidate).
- **The narrowed small-selection cost of rule 2** (see Decision) comes from the residual partitions - those above the
  membership's per-owner coverage threshold - being checked on every narrowed query. Tracking, per owner, the large
  partitions it belongs to as well would remove most of it at a memory cost in `ReducedIndexMembership`; that belongs
  with the membership rework of #1615, not here.
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
- **2026-09-24** - quality pass and adversarial review (scope admission on the prefetch route, page-bounded merge);
  high-fan-in and no-value fixtures measured, the claim loop reshaped by profiling, the narrowed small-selection cost
  accepted by the maintainer; a second adversarial review restored the index probe for set-dependent target orders.
- **2026-09-25** - PR review: no provider is built for a partition holding no unclaimed owner on the direct claim
  path either; re-measured on all three fixtures.
