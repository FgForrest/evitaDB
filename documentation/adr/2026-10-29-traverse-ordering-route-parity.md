---
title: Bound reference ordering by the filtered hierarchy and sort each target as one block
date: 2026-10-29
updated: 2026-10-29 09:10
status: accepted
kind: fix
issues: [1670]
prs: [1828]
areas:
  - evita_engine/src/main/java/io/evitadb/core/query/sort
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/hierarchy
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - documentation/user/en/query/ordering
supersedes: []
superseded-by: []
relates:
  - 2026-09-23-pick-first-reference-ordering-from-selection
  - 2026-09-15-bidirectional-reference-counterpart-rewrite
---

# Bound reference ordering by the filtered hierarchy and sort each target as one block

Traverse reference orderings now return the same order through index scanning and prefetching. The filtered
hierarchy bounds the eligible targets; `referenceHaving` selects owners without narrowing their ordering rows.
Each target forms one block, including duplicate reference rows and owners of both processed scopes. Pick-first
uses the same hierarchy bound. This narrows two rules of related records, which otherwise stand: pick-first's
"every row counts" now holds for `referenceHaving` only, and the bidirectional rewrite no longer excludes traverse
orderings.

## Why

The two routes disagreed within a traversal block. Live owners preceded archived owners on the index route even
when their values required the opposite order; prefetch could claim a lone owner whose row had no value. Duplicate
rows to one target were reduced to the first representative key, and referenced-primary-key ordering could fail
instead of returning a page. Choosing either route as the oracle would have preserved some of those defects.

### Previous state

Both routes derived blocks from the first eligible index-selection candidate on the ordered reference, otherwise
from the full partition family. A hierarchy filter therefore already bounded a category listing, while
`referenceHaving` sometimes removed whole partitions. This was neither all rows nor matching rows: an owner could
be ordered by a non-matching row just because another row of its partition matched. Equivalent filter shapes and
the bidirectional rewrite could change the available blocks. The rewrite declined same-reference orderings to
preserve this accidental dependency.

The earlier pick-first fix deliberately removed `referenceHaving` narrowing, but its broad "every row counts"
wording also covered hierarchy filters that its resolver never read. A shared rule needed to preserve category
listings while making both strategies independent of reference-filter candidate selection.

## Options considered

### How filtering bounds reference ordering

The original narrowing decision was called D2; its options A–E are retained here to explain the fork.

**A — all rows.** Every row of a selected owner can open a target block. This is simple and matches pick-first's
previous general wording. **Rejected because:** it breaks the documented, working subtree listing. Both routes
already followed the hierarchy bound in all 24 probe pairs: an owner also assigned to an earlier category outside
the subtree must not jump ahead of the subtree's own products. Revisit only with an explicit alternative contract
for hierarchy listings.

**B — filter-admitted rows.** Admit rows satisfying all conjunctive reference and hierarchy conditions. This would
make the ordering appear to follow the filter closely. **Rejected because:** `inScope` needs per-scope semantics,
and two conjunctive `referenceHaving` containers bind independent existential rows; requiring one row to satisfy
both can admit none for a selected owner. The transposer's fast path supplies no per-partition projection, and the
bidirectional rewrite supplies no owner-side per-partition formulas at all. The proposal also retains filter-shape
sensitivity between equivalent conjunction/disjunction forms. Revisit only if a production requirement needs row
admission and supplies both a composition contract and a projection seam on every route.

**C — target-level conditions only.** Let hierarchy filters and target-only `referenceHaving` bodies narrow the
targets, leaving reference-attribute predicates out. This avoids a general row projection. **Rejected because:**
the distinction between target and reference-attribute conditions is arbitrary to a query author; moving the same
business condition across that boundary would change ordering semantics. Revisit only for an explicit ordering
constraint expressing that distinction.

**D — partition-level admission.** Preserve the current candidate partitions. This keeps the existing plan's work
small. **Rejected because:** a match by some row of a partition says nothing about which row should represent each
owner; admission remains an index-selection artifact, sensitive to candidate order and the rewrite. The earlier
pick-first decision rejected the same artifact. Revisit only if partitions become part of the public contract.

**E — the filtered hierarchy bounds traversal; reference filters select owners (chosen).** Use the resolved node
set of the applicable hierarchy filters and otherwise the full family of the ordering scopes. This preserves
category listings, gives `referenceHaving` one meaning under both strategies and needs no row-admission projection.
Hierarchy translators already resolve and memoize this set independently of reference index selection. The cost
is an intentional change for queries that relied on reference-filter partition narrowing.

### Exact attribute ordering over several partitions

Removing `referenceHaving` narrowing exposed a separate compatibility fork: a traverse with `attributeSetExact`
that sorted one candidate partition in 2026.2 now reached several and failed its single-partition assertion.

- **Generalize `attributeSetExact` block by block (chosen).** Reuse the traversal's representing-row rule and
  exact-value buckets within each visited target. This preserves a released query and makes it composable with
  subsequent nested sorters, later blocks and outer fallback orderings.
- **Keep refusing several partitions.** This would preserve the existing translator boundary and avoid new exact
  sorting logic. **Rejected because:** it turns a query that worked in 2026.2 into an error. Accepting that break
  would require an explicit withdrawal of the released query shape; no such withdrawal was chosen.

## Decision

Choose hierarchy-bounded ordering and block-local exact ordering. The following invariants define the result:

1. A `hierarchyWithin` or `hierarchyWithinRoot` on the ordered reference counts only when reached through
   `filterBy`, `and`, `inScope` or `userFilter`, never beneath `or` or `not`. A bound inside `inScope` affects that
   owner's scope only; an unbounded scope keeps its whole family. Use the resolved set, including `excluding`,
   `excludingRoot`, `having`, `anyHaving` and `directRelation`; for reference `hierarchyWithin`, direct relation
   admits the parent nodes. Union conjunctive occurrences: each is its own existential and an intersection can
   discard every row of an owner selected by different rows in disjoint subtrees. Neither `referenceHaving` nor
   `facetHaving` narrows the ordering. Pick-first observes this same bound before choosing its first valued row.
2. A value-less row passes the owner on; an owner with no valued row reaches the next nested sorter, later blocks,
   then the sorter after the traverse. Pulling such an owner into a block's tail would need a separate tail order,
   hide it from the query's next ordering and break symmetry with pick-first.
3. Blocks contain partitions from the ordering's processed scopes only. Live and archived owners within a target
   merge by value and then owner primary key in the ordering direction. Targets are walked in their own scope's
   hierarchy tree, live tree then archived tree, regardless of owner scope. The nested target order uses the
   traversal's target universe, including ancestors needed for the walk, rather than selected-owner targets.
4. One target is one block. An owner is represented by its first row to that target, in representative-key order,
   carrying the sorted value. Representative values choose the row, never a sub-block: separate representative
   sub-blocks could put value 100 before value 1 in ascending order. Equal values tie by owner primary key in the
   ordering direction. Referenced-primary-key ordering therefore compares owner primary keys within a block.
5. `attributeSetExact` uses that first valued row even when its value is unlisted and a later duplicate row has a
   listed value. Listed values follow list order, ties by ascending owner primary key; exact ordering has no
   direction argument. Unlisted or missing values pass to the next nested sorter and then later blocks. A block
   with no value claims nothing instead of failing. One valued partition retains its released order. Pick-first
   exact ordering and `attributeSetInFilter` inside reference ordering retain the single-partition requirement.
6. Chains keep provider precedence: target, representative values, then live before archived for the same key.
   Implicit pick-first chains now use that target/representative order on both routes too.

## Key technical details

- `ReferencePropertyTranslator` collects the hierarchy bound once per ordering from
  `QueryPlanningContext#getResolvedHierarchyNodes`. Bounded scopes resolve only their target partitions;
  unbounded scopes resolve their full family. `ReferenceOrderingHelper` shares the explicit/default strategy
  decision with the bidirectional rewriter.
- `SequentialSorter` binds a target-only block key and skips empty blocks. `MergedSortedRecordsSupplierSorter`
  claims owners by their first valued partition within that target, then lazily merges the claimed runs. The merge
  stops at the requested page and reads no values when at most one provider contributes. Chains concatenate their
  providers because they have positions rather than comparable values.
- `RepresentativeReferenceKey.STRICT_COMPARATOR` refines representative value order when unequal keys compare
  equal, such as decimal values with different scales. Traversal grouping and prefetch row picking share it.
  Implicit chains follow the planning providers' representative order.
- `PrefetchedRecordsSorter` classifies every entity before sorting, including a lone entity, through
  `EntityComparator#canSort`. Classification and comparison share the memoized representing row.
- `AttributeSetExactTranslator` selects the traverse path in `AttributeExactSorter`: first-valued-row masks are
  computed before listed-value lookups, for both materialized partitions and virtual registry rows. Prefetch reads
  the representing reference attribute with the query locale and block scopes, rather than an owner attribute of
  the same name. Each visited block performs listed-values × block-partitions lookups plus valued-record masks.
- `PickFirstReducedIndexResolver` prunes by the owner's scope bound before nested target ranking; the prefetch
  scalar, compound and primary-key pickers apply the same row admission. The bidirectional rewrite may now handle
  traverse orderings, but its conservative guard remains for non-traverse orderings, including implicit chains
  that still read candidate partitions when unbounded.
- The shared array-backed merge walk no longer reads an unwritten pooled-buffer slot on an empty or strict-subset
  selection. Scope admission, missing-data classification and segment handoff repairs found along this path also
  apply to ordinary entity orderings.

## Verification

These are recorded implementation runs; the documentation phase ran no builds or tests. Counts are actual XML
testcase elements, including nested cases.

- `EntityByReferenceAttributeTraverseOracleFunctionalTest`: **424/424 green** after hierarchy-bound planning,
  covering randomized targets, values, scopes, duplicates and both routes. The hand-built
  `EntityByReferenceAttributeTraverseFunctionalTest` grew from 149 to 151 cases at that step; its eight pick-first
  hierarchy failures were retained until the pick-first fix. Two alternative-plan count assertions were aligned
  with #1681's single eligible plan for partial scope coverage; their ordering assertions were already green.
- `SequentialSorterBlockMergeTest` (**11**), `SequentialSorterRepresentativeRowsTest` (**11**) and
  `SequentialSorterTest` (**5**) were green in the hierarchy-planning selection, covering scope merge,
  representing rows, handoff and paging. `EntityByDuplicateReferencesFunctionalTest` and `VirtualChainParityTest`
  passed together (**22/22**) after their expected traversal blocks were changed to include all selected-owner rows.
- Exact traversal added **27/27 green** invocations: `shouldOrderExactValuesBlockByBlock` (4),
  `shouldKeepEarlierExactBlockUnderReferenceHaving` (4), `shouldUseFirstValuedRowForExactOrder` (4),
  `shouldMergeExactValuesAcrossScopes` (4), `shouldBoundExactBlocksByHierarchy` (4),
  `shouldPassOnExactBlockWithoutSortIndex` (4), `shouldNormalizeExactReferenceValues` (2), and
  `VirtualTraverseParityTest#shouldTraverseSeveralExactBlocksIdentically` (1). All **55** previously failing
  exact-ordering invocations passed: migration (12), index-type change (4), promotion lifecycle (17), reload
  invariant (8), singleton lifecycle (1), sort value (12), and existing traverse parity (1). Six deliberately broken
  variants failed their intended assertions and were restored exactly.
- `shouldOrderImplicitPickFirstChainsByTargetAndRepresentativeValues`: **2/2 green**, with both routes expecting
  `[150, 151]`; the old index-only pin expected `[151, 150]`. The chain-only gate ran **1,453** cases with only
  the eight then-deferred pick-first hierarchy failures.
- The final hierarchy/chain targeted run was **23/23 green**: the chain parity pair,
  `shouldBoundPickFirstByHierarchyWithin` (4), `shouldBoundRankOrderedPickFirstByHierarchyWithin` (4),
  `shouldBoundPickFirstOnlyInScopeHoldingHierarchyConstraint` (4),
  `shouldRankOnlyHierarchyBoundTargetsForSeededPickFirst` (4),
  `PickFirstReferenceSorterTest#shouldPassOwnersWithoutRowInsideHierarchyToNextSorter` (1), and four direct
  scalar/compound checks in `PickFirstHierarchyBoundPrefetchTest`. Queries with a top-level filter `inScope` use
  the index route under both preferences (#1682); real-object direct tests prove the scoped prefetch behavior.
  Five chain/hierarchy counterfactuals failed as intended and were restored exactly.
- The final implementation selection was **1,461/1,461 green**. After the review fixes, the orchestrator reran the
  expanded selection once: **1,516/1,516 green**, including the corrected real-object prefetch fixture.
- An adversarial review of the finished change found three route divergences, and one more claim that did not
  reproduce. The three divergences were all on the prefetch route, plus the index route for scoped children:
  - a compound ordering inside a traverse sorted owners of scopes that do not index the compound;
  - an `inScope` container nested inside a traverse did not restrict its child ordering;
  - an implicit pick-first chain claimed a single owner that had no placeable valued row.
  Each now has a two-route test that was red before its fix: 15 invocations, 7 failing as intended. The tests are
  `EntitySegmentsFunctionalTest#shouldTraverseCompoundOnlyInScopesIndexingIt`,
  `#shouldNarrowTraverseRowsToScopeInsideReferenceOrder`, `#shouldPassBlockOutsideExactOrderingScopeToNextChild`,
  `#shouldPassValuelessSingletonThroughImplicitChain` and
  `PrefetchedRecordsSorterClassificationTest#shouldPassUnplaceableSingletonsThroughImplicitChain`. Each fix also
  has a counterfactual that fails again when the fix is removed, restored byte for byte.
- The claim that did not reproduce was that `attributeSetExact` over a chain attribute inside a traverse answers
  differently per route. It is already refused on both routes, before execution, because the exact sorter
  requires comparable values. `ReferenceChainExactOrderingFunctionalTest` pins this (**8/8**).
- The code-quality pass then found that a hierarchy bound nested in `userFilter` was ignored. `userFilter`
  refuses hierarchy constraints only as direct children, so `userFilter(inScope(LIVE, hierarchyWithin(...)))` is a
  legal filter. The bound collection did not descend into `userFilter`, so such a filter selected the right
  owners while traverse and pick-first stayed unbounded. Two tests now cover both routes and two filter shapes:
  `EntityByReferenceAttributeTraverseFunctionalTest#shouldBoundTraversalByHierarchyWithinUnderUserFilter` and
  `#shouldBoundPickFirstByHierarchyWithinUnderUserFilter`. All 8 rows were red before the fix.
- After the adversarial fixes the expanded selection ran **1,564/1,564 green**. The final full functional run came
  after the code-quality pass. It ran **30,923 tests, with 0 failures, 1 error and 65 skipped**. The error was an
  HTTPS session timeout in `CatalogGraphQLSchemaSubscriptionsFunctionalTest`, and that test passed when re-run
  alone (**1/1**).

### Cost

Measured on 2026-10-29 on a production e-commerce catalog (~172,000 products including archived ones, 18
collections). The catalog was restored and migrated once. Each arm ran in its own fresh JVM, in ABBA order: base
`1f716f2b1d`, this change, this change, base. The cache was off, every query read a page of 20, and each query had
3 s of warm-up and 6 s of measurement. The hierarchy has 648 categories under 10 roots. The traverse orders by
`traverseByEntityProperty(DEPTH_FIRST, entityPrimaryKeyNatural(ASC))` and then by a reference attribute. Pick-first
uses the same pair. The flat traverse runs over a reference with duplicates to 232,878 targets. The table gives the
mean of the two medians per arm:

| Query | Owners | Base | This change | Ratio |
|---|---:|---:|---:|---:|
| traverse, largest root subtree | 72,166 | 27.5 ms | 25.7 ms | 0.93 |
| traverse, median root subtree | 132 | 49 µs | 51 µs | 1.04 |
| traverse, unbounded | 133,879 | 72.2 ms | 69.1 ms | 0.96 |
| traverse on the prefetch route, 200 owners | 200 | 2.71 ms | 2.80 ms | 1.03 |
| pick-first, largest root subtree | 72,166 | 400 µs | 399 µs | 1.00 |
| pick-first, unbounded | 133,879 | 98 µs | 109 µs | 1.11 |
| flat traverse, unbounded | 133,879 | 96.2 ms | 95.6 ms | 0.99 |
| flat traverse, largest root subtree | 72,166 | 96.6 ms | 95.8 ms | 0.99 |
| control: primary key, largest root subtree | 72,166 | 279 µs | 278 µs | 1.00 |

Two runs of the same arm differed by up to 4 % on the traverse queries over many owners. They differed by up to
9 % on the queries that finish within 3 ms. The prefetch row and the median subtree row therefore show no
change. Two results held in all four runs:

- The bounded traverse got faster: both runs of this change were below both base runs, by 7 %.
- Unbounded pick-first got slower: both runs of this change were above both base runs, by 11 µs per query (11 %).
  That regression was accepted without profiling; see the follow-ups.

Several other effects did not show:

- Bounding pick-first by the hierarchy cost nothing measurable.
- Traverse cost grew with the number of owners, from 49 µs at 132 owners to 69 ms at 133,879. A whole-tree walk
  would show as a fixed floor under every traverse, and no such floor is visible at 648 categories.
- Every query returned the same total in all four runs.
- The first page differed only on the prefetch route. The prefetch query takes the subtree's 200 lowest owner
  primary keys. The index route's first page over the whole subtree holds its 20 lowest, which lie inside those
  200. Restricting the owners keeps their relative order, so that page is also the index route's answer over the
  200. This change's prefetch page equals it; base's prefetch page did not.

The corpus neither sorts the reference attribute nor indexes the hierarchy in the archived scope. Both arms therefore
refused the traverse queries over both scopes in the same way, and those queries were not measured.

## Consequences & open follow-ups

- A traverse combined with `referenceHaving` may place an owner in an earlier target block than before, even when
  that row does not match the reference filter. The filter still selects the same owners. Hierarchy-bounded
  category listings keep their subtree boundary.
- Pick-first combined with a qualifying hierarchy filter now ignores rows outside that bound. An owner's ordering
  value can therefore change even when its selected status does not. Implicit pick-first chains can change order
  when duplicate rows were formerly visited in partition-creation order.
- Missing values, cross-scope merges, duplicate representatives and descending ties now agree across routes and
  pages. `entityPrimaryKeyNatural` inside traverse returns the owner order instead of missing-reference-context or
  merge-accounting failures. Exact traversal also accepts several partitions and blocks with no attribute value.
- Related repairs make `inScope` orderings sort only that scope and pass other owners on, including primary-key,
  exact-attribute, price and random orderings. Compounds use only scopes indexing the compound, pass all-null tuples
  on and break descending ties by descending owner primary key. Price sorting without a price filter uses the
  existing primary-key fallback outside segments; an entity without a selling price passes to the next sorter.
- Segments now take only owners a declared sorter can place; exact sorters take listed values only, and the segment
  limit counts sorted owners. Missing or unlisted values reach subsequent sorters or segments. An explicit
  primary-key sorter can place every remaining owner. Nested `segments` are rejected at planning while stored
  constraint trees remain readable. The segment chapter already documents these rules.
- Inside a traverse, an `inScope` container restricts the nested sorter it wraps, on both routes. A sortable
  attribute compound does the same for the scopes that index it. Owners outside the scope pass to the next nested
  sorter within the same block.
- `attributeSetExact` over a chain attribute is refused inside a traverse. At the entity level, outside any
  reference ordering, dev diverges between routes: the index route fails with an internal error, the prefetch
  route returns an order. This change leaves that unchanged; #1830 refuses it on both routes.
- Unbounded pick-first is 11 µs (11 %) slower than before (see Cost). It was accepted without profiling: the cost
  is small in absolute terms and the bounded queries did not move. The cause is not established. If it ever
  matters, the first suspect is the scope and hierarchy-bound resolution `PickFirstReducedIndexResolver` now
  performs even without a bound.
- Multi-partition exact ordering under pick-first and `attributeSetInFilter` remains outside this extension.
  Removing the remaining bidirectional rewrite guard for implicit chains needs a separate candidate-independence
  decision. Filter `inScope` prefetch support remains #1682.

## Related work

- [Pick-first ordering from the selection](2026-09-23-pick-first-reference-ordering-from-selection.md) supplies the
  first-valued-row and lazy-claim design. This record narrows its filter-independent wording to `referenceHaving`
  and closes its traverse-narrowing and route-parity follow-ups.
- [Bidirectional reference counterpart rewrite](2026-09-15-bidirectional-reference-counterpart-rewrite.md) retained
  candidate partitions to protect ordering. Traverse ordering now derives its bounds independently, so that part
  of the guard is removed; implicit pick-first chains keep it.

## Timeline

- **2026-09-30** — hierarchy-only bounds, missing-value handoff, scope merge and one block per target accepted.
- **2026-10-01** — representative row selection and scope/segment repairs implemented; implicit-chain parity chosen.
- **2026-10-28** — integration with current development completed; block-local exact ordering chosen to preserve
  released queries, pick-first hierarchy bounds implemented and recorded verification completed.
