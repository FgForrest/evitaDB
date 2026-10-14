---
title: The filter of referenceContent is evaluated row by row over the fetched owners' references, and its index route is deleted
date: 2026-10-14
updated: 2026-10-14 23:36
status: accepted
kind: refactor
issues: [1615]
prs: [1738]
areas:
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/filter
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_engine/src/main/java/io/evitadb/index/attribute
supersedes: []
superseded-by: []
relates: [2026-10-12-single-owner-partitions-as-registry-rows, 2026-09-23-reference-decode-narrowing-by-referenced-key, 2026-09-28-indexed-reference-scope-requires-entity-component, 2026-09-17-row-scoped-reference-having-body]
---

# The filter of `referenceContent` is evaluated row by row over the fetched owners' references

`referenceContent(name, filterBy(...))` decides which references of the returned entities are fetched. It is now
decided **reference row by reference row, over the rows of the owners the query fetched**: the filter is compiled once
per fetch and reference into a small tree of row tests (`ReferenceRowFilterCompiler`), every existing row of every
fetched owner is judged by that tree alone, and an admitted row is recorded for its owner alone
(`ValidEntityToReferenceMapping#admit`). No reduced reference index, type index, group index or virtual partition is
read. The route that evaluated the same filter on those index structures - and everything only it reached - is deleted.
On a production retail catalog (160,216 products, 18 collections) the row route took 0.07x-0.81x the time of the index
route and allocated 0.03x-0.84x the memory in every measured case.

## Why

Three things came together in #1615:

- **The index route stands on the structures #1615 turns into registry rows.** A single-owner partition is a row of a
  per-reference registry, and an index object exists only while a partition has two or more owners
  ([2026-10-12-single-owner-partitions-as-registry-rows](2026-10-12-single-owner-partitions-as-registry-rows.md)). The
  index route had to evaluate every constraint of the filter twice - against index objects and, through
  `VirtualPartitionBodyEvaluator`, against registry rows - and keep the two answers equal for every constraint it
  admitted. That second evaluator already read the owners' bodies.
- **Its answer depended on what the scopes indexed.** An owner living in a scope that does not index the reference got
  the filter ignored or its duplicates hidden, a reference indexed in no queried scope was never validated at all, and a
  reference attribute had to be filterable or unique in every examined scope. A filter on data the query already holds
  was refused or silently skipped because of a schema flag meant for the main query.
- **The data in hand already answers the question.** A fetch decodes the owners' reference rows anyway
  ([2026-09-23-reference-decode-narrowing-by-referenced-key](2026-09-23-reference-decode-narrowing-by-referenced-key.md)
  keeps every row the filter could admit). The index route looked the verdict up per *referenced key* and then
  reconstructed a per-row verdict by restricting the key for every owner known to the fetch - which is how one owner's
  rows decided another owner's answer.

### Previous state

The fetch compiled nothing. `ReferencedEntityFetcher#getFilteredReferencedEntityIds` located, per examined scope, the
reduced entity index of each referenced key (the partitions of the owners' references), translated the filter against
it (index objects, plus the registry-row evaluator for single-owner partitions), collected the referenced primary keys
that passed, and `ValidEntityToReferenceMapping` narrowed those keys per owner (`restrictTo`, `forbidAll*`,
`RepresentativeMapping#unrestrictedKeysAllowed`). The planning check translated the filter through the same index
lookup. Known defects, all of one class - a verdict per key applied to every owner:

- one owner's rows decided another owner's answer (a root `groupHaving` judged per key and unioned over the owners of
  the page; an archived owner losing keys a live row was evaluated for; the answer varying with the page mates);
- duplicates of owners in a scope not indexing the reference were hidden, and a reference to a missing entity could not
  be hidden by `EXISTING` on a duplicate-cardinality reference;
- a root `scope(...)` of the filter raised `GenericEvitaInternalError` whenever a virtual candidate existed;
- a group-only filter on a schema without `REFERENCED_GROUP_ENTITY` passed planning and failed at fetch depending on
  the data.

The planning check was the weakest part: it refused shapes the fetch could evaluate (non-filterable attributes, a
reference not indexed in a scope, a missing entity or group component) and validated nothing for a reference indexed
nowhere, which is the default (`ReferenceIndexType.NONE`).

## Options considered

### Option A - judge every row of every fetched owner by itself (chosen)

The compiler turns the filter into a closed set of row tests; the fetch applies it to the raw rows of each owner. The
verdict of a row is a function of its owner's scope, the owner's locales, the row, the existence set and the nested
target sets, and of nothing else. A reference attribute leaf is built on the very normalizer and comparator of
`FilterIndex` (plus the range encoders of the `attributeBetween` / `attributeInRange` translators), so it answers what
the index would answer.

- **Pros:** no data-dependent answer; one evaluator for every owner; the planning check compiles with the same compiler
  and cannot drift from the fetch; far less work (see *Verification*).
- **Cons:** the owner must carry every row the filter could admit (a guard, below); a closed set of admitted constraints,
  everything else refused; user-visible changes (answered where it was refused, refused where it was ignored).

### Option B - keep the index route, adapted to registry rows (declined)

Leave the evaluation on index objects and make the registry-row evaluator carry what #1615 removes from them.

- **Pros:** no user-visible change; no new compiler; the measurement would not have been needed.
- **Cons:** two evaluators of one constraint set kept equal forever; the per-key reconstruction of a per-row verdict
  stays.
- **Rejected because:** the defects above are not bugs in either evaluator but in the join between them and the per-key
  mapping, so adapting the route moves them rather than removing them. The randomized parity sweep written to hold the
  two routes together reported 356-369 differences per 800 outcomes and about 1,400 per 3,000, every one of them in a
  decided class (a non-filterable attribute, a scope not indexing the reference, duplicates per row, a root
  `groupHaving`, the locale of a localized leaf, ...). That is how far the routes had drifted apart, and a route that
  needs a sweep that large to stay honest is the cost this decision removes. **Revisit if** a constraint is ever
  admitted in the filter whose answer cannot be a function of a row plus nested target sets (see *Decision*).

### Option C - keep the reductions already shipped and fix the defects in place (declined)

The first answer to "the logic is too complex" was a plan of behaviour-preserving cleanups of the index route
(dead branches, one flush per key, one comparator initialization, non-indexed scopes known before discovery, a named null
supplier, no per-reference body accessor). Six of them landed before this decision (`fb43cf6203`..`55fc057a22`). The two
that would have touched behaviour (a helper for the group-id call, and the decision on a root `groupHaving` and a
group-only filter without the group component) did not.

- **Pros:** each step safe on its own; nothing user-visible.
- **Cons:** every step is behaviour-preserving, so none can remove a defect that *is* the behaviour.
- **Rejected because:** the open choice was exactly the defects - `groupHaving` per key or per row, the group component
  required or not - and each answer on the table (route the root `groupHaving` through candidate discovery like a
  nested one, keep it per key and check each owner's own row, or only file the defect) either left the per-key mapping
  in place or moved a breaking change onto an architecture about to be replaced.

### Option D - a hybrid: index objects for indexed scopes, rows for the rest (declined)

Keep the index route where a scope indexes the reference and evaluate rows only where it does not (non-indexed scopes,
single-owner partitions).

- **Pros:** the index keeps whatever advantage it has on selective filters.
- **Cons:** the join of the two answers is where the defects sit; a filter would mean different things for owners of
  different scopes.
- **Rejected because:** it keeps "the answer depends on which scopes index what" - the U1/U2 problem - and the A/B
  found no case where the index route is faster: the largest BODY/INDEX ratio of any case in any round is 0.866.

### Decisions taken inside Option A

The labels in parentheses (R1, U2, Q1, ...) are the numbers the maintainer's decisions had in the design. They are kept
as tags only; every place states the decision itself.

| Fork | Chosen | Rejected because |
|---|---|---|
| Existence gate under `ANY` with a filter (R11) | **keep it**: a filtered row whose target does not exist in the examined scopes is not returned | dropping it returns bodiless rows (the body request inherits the request scopes) for live owners referencing archived targets - routine: the corpus of the #1615 record holds 40,344 archived products next to 130,033 live ones - and flips `not(entityHaving(f))` to TRUE for missing targets. **Revisit** as its own change with a docs change and an upgrade note |
| Scope of the filter: apply to all owners or only to owners of scopes indexing the reference (U1/U2) | **all scopes**; `inScope(S, ...)` confines it | the ignore-or-refuse behaviour was incidental to the trait loop of `AttributeSchemaAccessor`, not a decision |
| Reference attribute leaves require `filterable`/`unique` (R1) | **no**, but they behave exactly as index filtering | a requirement that exists to guard an index lookup guards nothing when no index is read; the behaviour is kept identical so a user does not meet two meanings of one constraint |
| A constraint without a meaning on a row (R6), `scope(...)` below the root | **refused** with `EvitaInvalidUsageException`, at planning and at fetch | admitting `entityPrimaryKeyBetween`, `priceBetween`, `hierarchyWithin`, ... would keep their accidental owner-level reading; omitting a nested `scope(...)` from its boolean parent leaves `not(scope(...))` without a meaning to omit into |
| Locale of a localized leaf (Q1) | the locale of the filter's own `entityLocaleEquals`, found **exactly** as `findReferenceFilterLocale` finds it, else the owner query's | a dedicated finder that stops at `entityHaving` would split the leaves from the body fetch, the `EXISTING` gate and the ordering in exactly the shape `entityHaving(entityLocaleEquals(L))` |
| Readings that depend on attribute flags | chosen **per owner scope** by that scope's own declaration (OQ-U) | one reading per leaf made a LIVE row's verdict change when ARCHIVED was added to the query |
| Sharing the leaf test with the main query's prefetch predicates | no; the main query is untouched | widening the change into the main query; the divergences are parked (below) |
| Recording only the groups of admitted rows on the unsorted paths | no, they stay keyed by referenced primary key | `isGroupAssignmentVarying` reads the same translator; the effect is extra group bodies, never a visible row |

## Decision

**Chosen: Option A.** The index route is deleted in the same pull request as #1615, after the A/B measured both routes
in one build.

The reasons are the drivers above: the route depended on index structures that no longer exist as objects, its answer
depended on schema flags that mean nothing to a fetch that holds the rows, and a per-row verdict reconstructed from
per-key results cannot be made right, only less wrong.

**What would have to change for the index route to win:** the fetch would have to stop holding the rows - for instance
if owners were fetched without their reference rows decoded - or the filter would have to admit a constraint that can
only be answered by an index (`facetHaving`, `referenceHaving`, `hierarchyWithin` on a reference row). Neither is on the
table. A measured case where the index route is faster (none of the eight was) would also reopen Option D.

## Key technical details

- **Entry points.** `ReferencedEntityFetcher#admitFetchedReferences` reads the owners' raw rows, applies the existence
  gate, the compiled `ReferenceRowFilter`, and records each admitted row. `ReferenceRowFilterCompiler#compile` builds the
  `RowTest` tree (`and`, `or`, `not`, `inScope`, the twelve attribute leaves, `entityPrimaryKeyInSet`,
  `entityLocaleEquals`, `entityHaving`, `groupHaving`; a root `scope(...)` only widens the examined scopes and produces
  no node). `AttributeValueMatcher` is the per-leaf test. `NestedSet` holds the target sets of `entityHaving` /
  `groupHaving` (planned by `FilterByVisitor#getMatchingEntityPrimaryKeys`).
  `ValidEntityToReferenceMapping#admit` / `isReferenceSelected` and the sorted and unsorted slicers of `BitmapSlicer`
  consume the verdicts.
- **The planning check compiles the same filter** (`ReferencedEntityFetcher#verifyReferenceContentConstraints`,
  called from `ReferenceContentTranslator`) with a constraint-check visitor and throws the result away. In check mode `NestedSet`
  resolves its nested query eagerly, so nested filters are validated and their capabilities counted whatever the data;
  in a fetch it is lazy and restricted to the targets and groups of the rows tested. A nested level is planned in the
  locale its fetch uses (`ReferenceContentTranslator#getOwnerLocale` folds
  `ReferencedEntityFetcher#getReferencedEntityLocale` over the enclosing `referenceContent` requirements, outermost
  first - the level-2 test pins that order).
- **The owner must carry every row** (`getOwnerCarryingEveryRow`). Rows are read off the raw entity, never off the
  decorator, which hides names, rows and attributes the request did not ask for. A `ServerEntityDecorator` whose
  reference predicate covers the name carries every row that matters; one that requires no references is accepted on
  call order alone, so a caller must hand over the output of `EntityCollection#ensureReferencesFetched`. Anything else
  is a `GenericEvitaInternalError`, not a filter that silently sees no rows.
- **Existence gate (R11 a).** With a filter, or under `EXISTING`, and a managed referenced type, a row is tested only
  if its target exists in the examined scopes (and in the required locale for a localized target schema). It applies to
  owners of every scope.
- **Per-owner-scope readings.** A localized attribute is bound to the locale in scope `S` iff it is not unique there or
  unique within a locale. `equals` / `inSet` / `is` read the leaf locale when bound and any locale when not; each owner
  scope uses its own declaration. A scope outside the examined ones can occur - an owner archived, moved or enriched by
  a request reading another scope - so the compile context carries the owners' scopes too.
- **A `BigDecimal` is compared as the HALF_UP-scaled value** of `NumberUtils.scaleToInteger`: the index's int encoding
  without its final overflow, identical wherever the int fits. `attributeBetween(from > to)` on a scalar is refused at
  compile with the index's own message.
- **No representative keys without a row-level verdict.** The mapping builds `RepresentativeReferenceKey`s only for a
  duplicate-cardinality reference with a filter; otherwise the verdict is a function of the referenced primary key.
- **Slicing is by row where rows can share a key.** The unsorted slicer pages the selected rows, not the distinct keys,
  when several rows may share a key; the sorted one filters by row before sorting and slicing, so a rejected duplicate
  never takes the slot of an admitted one.

## Verification

Tests that guard it. The full functional suite after the code-quality pass ran 28,835 tests; its only 12 failures are the
environmental ones every run of this branch has (`EvitaServerTest` misses a certificate key absent from the test environment,
`ExportS3ServiceTest` needs Docker). The whole reactor test-compiles.

- `ReferenceContentRowFilterFunctionalTest` (nested `VerdictsPerRow`, `Readings`, `RefusalsAtPlanning`,
  `OwnersOutsideRequestScopes`) - per-row verdicts, the locale reading incl. level 2, every refusal at planning on an
  owner with no rows and on no owner, and owners outside the request scopes.
- `ReferenceContentCrossScopeRowsTest` - the hand-written cross-scope expectations that
  `VirtualReferenceContentFilterParityTest` carried: singletons and duplicates in two scopes, a root `scope(...)`
  over a single-owner partition, Czech brand order with and without a query locale and under `inScope`.
- `ReferenceRowFilterCompilerTest`, `AttributeValueMatcherTest` (every leaf pinned against a real `FilterIndex` /
  `RangeIndex` loaded with the same values), `ValidEntityToReferenceMappingTest`, `BitmapSlicerTest` (page and strip,
  sorted and unsorted, duplicates).
- `RequestedCapabilityAccumulationTest` - row leaves count no `FILTERABLE` / `INDEXED`; nested filters still count on
  their own collections, with and without a row.
- `EvitaArchivingTest.MultiScopeQueryingTest`, `ReferenceWithoutEntityComponentQueryGuardFunctionalTest.Allowed`,
  `AbstractEntityByFacetFilteringFunctionalTest` - the three refusals that became answers (U2, R3, R1).

Counterfactuals, each run and watched fail: eager nested resolution in check mode made lazy (15 failures); the owner
locale chain replaced by the top locale (the level-2 test); the leaves requiring `FILTERABLE` (27 failures); the leaves
recording `FILTERABLE` / `INDEXED` (4 failures); the check skipped (19 failures); the owner scopes left out of the
compile context (the `OwnersOutsideRequestScopes` tests and a compiler test); the slicer always slicing by key (both
page tests).

**The sweep that held the routes together is deleted.** `ReferenceContentRouteParityTest` generated filters over a
fixture with duplicates told apart by an attribute, dropped groups and owners in both scopes, fetched each as rows, pages,
strips (with and without an ordering), named sets, a nested level, an enrichment and `getEntity`, and compared both
routes with an independent oracle; a run had to meet every situation that makes it discriminate. It was deleted with the
route it compared. Besides the differences above it found **three defects that existed on both routes**, each fixed with
its own test:

- a strip starting past the last row sliced from the last row while the decorator starts at the first
  (`dd768dea2a`);
- a row whose group was dropped still carried that group's body through `getGroupEntity()` (`3c03198702`);
- an owner whose duplicates of one referenced entity sit in two groups lost the second group from the groups the
  unsorted slicer records, so its body was never fetched (`196dbe547c`).

A fourth, found by review and confirmed on both routes, was the unsorted page of a duplicate-cardinality reference
paging distinct keys while the decorator pages rows (`deeecc635c`).

A code-quality pass over the finished line found one defect of the row route alone: the compiler checked `inScope`
against the scopes of the request, so `filterBy(scope(ARCHIVED), inScope(ARCHIVED, ...))` was refused in a query of
`LIVE`, which the deleted route - running the filter in the examined scopes - answered. The check now takes the scopes
the container is compiled in (`ReferenceRowFilterCompilerTest#shouldAcceptInScopeOfScopeNamedByRootScope`,
`ReferenceContentRowFilterFunctionalTest#shouldAnswerInScopeOfScopeNamedByRootScope`, both watched fail with the old
check).

**Performance** (JMH `-bm avgt -f 1 -wi 5 -w 2s -i 10 -r 2s -prof gc`; one jar for both arms, the route set as the first
statement of each fork; `-Xmx24g`, result cache off; the production retail catalog above, migrated to protocol 8 on open;
5 rounds interleaved INDEX BODY | BODY INDEX; each run started quiet, mean busy 22.6-29.3 % of 24 CPUs; route identity
proven in all 80 forks by a filter on a non-filterable reference attribute, refused by INDEX in 8/8 forks and answered by
BODY in 8/8). Ratio = BODY / INDEX, geometric mean over 5 rounds (min-max):

| case | INDEX us/op | BODY us/op | time ratio | alloc ratio | INDEX B/op | BODY B/op |
|---|---:|---:|---:|---:|---:|---:|
| unfiltered fetch of 100 products (control) | 2,466-2,812 | 2,361-2,956 | 0.984 (0.906-1.053) | 0.787 | 4.81 M | 3.79 M |
| detail of one product, nested `entityHaving(referenceHaving(...))` | 694-826 | 570-644 | 0.806 (0.731-0.863) | 0.837 | 1.54 M | 1.29 M |
| page of 20 products | 1,121-1,273 | 862-1,034 | 0.805 (0.711-0.866) | 0.544 | 2.89 M | 1.57 M |
| page of 100 products | 4,261-4,971 | 2,228-2,554 | 0.521 (0.473-0.599) | 0.148 | 23.4 M | 3.47 M |
| page of 100, `scope(LIVE, ARCHIVED)`, filter in `inScope(LIVE, ...)` | 5,649-7,374 | 2,608-2,975 | 0.442 (0.354-0.491) | 0.091 | 36.5 M | 3.34 M |
| page of 1,000 products | 54,023-64,942 | 15,666-17,438 | 0.275 (0.241-0.310) | 0.028 | 786 M | 22.0 M |
| duplicate references, strip of 20 | 190-218 | 35-41 | 0.195 (0.178-0.215) | 0.060 | 1.72 M | 0.10 M |
| owners with 72,868 rows, strip of 20 | 101,042-110,340 | 7,015-7,922 | 0.071 (0.064-0.075) | 0.399 | 39.0 M | 15.5 M |

The noise floor is the control's time spread, 9.4 %; every case moved the same way in all five rounds and by more than
that. Result shapes were identical in every case and round except duplicates: the index route attached 159 referenced
bodies to 160 references, the row route 160 - most likely the unsorted paging defect above, not proven.

Two things about the table. The control is not a same-path control: an unfiltered `referenceContent` also took the index
route's key collection, so its 0.787 allocation ratio (stable to 0.3 %) is a real saving of the row route at equal time,
and the allocation column measures the whole reference fetch, not the filter alone. The heaviest case filters on the
referenced entity, not a reference attribute: the only filterable scalar reference attribute there holds one value on
all rows, so the harness used a boolean attribute of the referenced entity matching 7.7 % of the rows. The rig and the
route switch are gone with the route, so the table is not reproducible from the tree.

## Consequences & open follow-ups

### What a client sees (breaking; upgrade note on #1615)

**Answered where it was refused:**

- a filter on a reference attribute that is not filterable or unique (R1);
- an attribute leaf over a scope that does not index the reference, e.g. `scope(LIVE, ARCHIVED)` over a reference
  indexed in `LIVE` only (U2, was `ReferenceNotIndexedException`);
- a filtered `referenceContent` on a reference without `REFERENCED_ENTITY`, and `groupHaving` without
  `REFERENCED_GROUP_ENTITY` (R3, R4);
- a locale-less query with a filter that names its locale (Q1), and an attribute filterable in only one of two scopes
  (OQ-U).

**Refused where it was ignored or passed:**

- a filter on a reference indexed in no queried scope, the default, is applied and validated: a wrong attribute, a
  missing locale or a disallowed constraint now fails (U1);
- constraints without a meaning on a row, and a `scope(...)` below the root, whatever the data (R6);
- `attributeBetween(from > to)` on a scalar whatever the data (R10);
- a nameless `referenceContent(filterBy(attribute...))` fails when **any** reference of the schema lacks the attribute,
  where the index route failed only where a partition held data;
- nested `entityHaving` / `groupHaving` filters of a reference not indexed in a queried scope, which the old check never
  translated.

**Answered differently:**

- the filter is applied to the rows of owners in every scope; `inScope(S, ...)` confines it (U1);
- a root `groupHaving` beside other constraints is judged per row, not per referenced key (R5);
- a localized reference attribute leaf reads the locale of the filter's own `entityLocaleEquals`, else the query's (Q1);
- a dropped group is no group, and a dropped attribute value is no value.

Reference attribute leaves of a `referenceContent` filter count no `FILTERABLE` / `INDEXED` capability in the
statistics (R7); nested filters keep counting on their own collections. GraphQL and REST keep offering only unique and
filterable reference attributes in the filter (Q4); the wider acceptance is available to EvitaQL, the Java API and gRPC.

### Parked, not filed

- **The main query's prefetch predicates diverge from the index** and could be pointed at `AttributeValueMatcher`: the
  `between` bounds are not converted, range rows are not rescaled, `inRange` with a non-`BigDecimal` number differs.
- **`applyOnFirstUniqueIndex` skips a scope where the attribute is only filterable** (suspected): `attributeEquals` on
  an attribute unique in `LIVE` and filterable in both never matches `ARCHIVED` entities in the main query. The row
  route reads such a scope by its own declaration; the main query keeps its reading.
- **A nested query ignores its own filter's `entityLocaleEquals`** and reads the parent's locale (suspected).
- **A root `scope(...)` in the reference filter widens the examined scopes** so targets in the added scope pass the
  existence gate, but the body request keeps the request scopes, so those targets come back without bodies (suspected;
  no test asserts body presence).
- **`EXISTING` means "exists in the examined scopes"**; whether it should mean "exists in any scope" is a separate
  question.
- **Dropped groups are counted** by the slicer and comparator group translators (`ReferenceMapping`).
- **`getEntity(..., {ARCHIVED})` on a collection without an archive index returns the LIVE entity** (found while fixing
  owners outside the request scopes; left alone).
- **Range indexes of partitions sharing a border** differ between the routes' oracles (known issue #1699); the sweep that
  met it is deleted.

### Traps for a later change

- `getOwnerCarryingEveryRow` fails loudly for an owner whose reference predicate may lack the name. A caller that builds
  owners without `ensureReferencesFetched` will hit it, and weakening the guard turns it into rows that silently
  disappear.
- The level-n locale depends on `getEntityContentRequireChain` returning outermost first.
- The compile context holds the scopes of the owners, not only of the request: remove them and an archived owner fetched
  by `archiveEntity`, `enrichEntity` or `upsertAndFetchEntity` fails an unconfined attribute leaf with an internal error.
  Those scopes widen only the readings of the attribute leaves. An `inScope` may name only the scopes it is compiled
  in - the examined scopes (the request and the root `scope(...)`), or the scope of an enclosing `inScope` - checking
  it against the owner scopes would admit a scope the query never asked for, checking it against the request alone
  refuses one the filter itself added.
- `FilterByVisitor`, `HavingTranslatorHelper` and `ProcessingScope` lost the nested restriction and comparator members
  the index route fed them; `referenceHaving` and the main query never used them.

## Related work

- [2026-10-12-single-owner-partitions-as-registry-rows](2026-10-12-single-owner-partitions-as-registry-rows.md) - the
  record whose registry rows made the index route a double evaluator; this one lands in the same pull request.
- [2026-09-23-reference-decode-narrowing-by-referenced-key](2026-09-23-reference-decode-narrowing-by-referenced-key.md) -
  the decode narrowing that guarantees an owner carries every row the filter could admit.
- [2026-09-28-indexed-reference-scope-requires-entity-component](2026-09-28-indexed-reference-scope-requires-entity-component.md) -
  its refusal of a filtered `referenceContent` over a reference lacking `REFERENCED_ENTITY` no longer applies to the
  filter (the main query still refuses).
- [2026-09-17-row-scoped-reference-having-body](2026-09-17-row-scoped-reference-having-body/README.md) - the row-scoped
  evaluation of `referenceHaving` this route borrows its nested-query planning from.

## Timeline

- **2026-10-13** - direction decided (row by row, index route deleted from the fetch, folded into PR #1738); the design
  reviewed twice; the compiler, the per-row mapping and the fetch switch land behind a temporary route switch.
- **2026-10-14** - both routes measured in one build; the index route, the switch and the sweep deleted; the record
  written (re-dated at merge).
