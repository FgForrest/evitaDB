---
title: Inside referenceContent an inScope ordering applies to the owners of that scope, and the ordering is one chain of links walked by both fetch sites
date: 2026-11-20
updated: 2026-11-21 18:07
status: accepted
kind: fix
issues: [2045]
prs: [2048]
areas:
  - evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure
  - evita_engine/src/main/java/io/evitadb/core/query/sort
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/response
  - documentation/user/en/query/ordering
supersedes: []
superseded-by: []
relates:
  - 2026-10-20-reference-content-filter-row-by-row
  - 2026-11-05-nested-inscope-requested-scopes
  - 2026-10-29-traverse-ordering-route-parity
  - 2026-09-28-indexed-reference-scope-requires-entity-component
  - 2026-09-23-reference-decode-narrowing-by-referenced-key
---

# Inside referenceContent an inScope ordering applies to the owners of that scope

In `referenceContent(name, orderBy(...))` an ordering wrapped in `inScope(S, ...)` orders only the references of
owners living in scope `S`; the references of other owners pass to the next ordering of the same `orderBy`, and
ascending referenced primary key applies last. The ordering is a chain of links owned by `ReferenceOrderByVisitor`;
the pre-fetch slicer and the decorator run the same walk over it, which ranks, per owner and per link, only the rows
the link can rank and hands the rest on.

## Why

`inScope` is documented as gating by the scope of the entity, the entity-level orderings behave that way, and the
`referenceContent` filter already evaluates `inScope` against the owner's scope (`RowPredicate.InScope`). The
ordering did not: `OrderInScopeTranslator` pushed the scope only while the comparator was built, so
`inScope(LIVE, attributeNatural('rank'))` in a query over `scope(LIVE, ARCHIVED)` ordered archived owners' references
too, and a following `inScope(ARCHIVED, ...)` never ran for them.

Fixing the gate exposed the protocol it had to plug into, which was broken on its own:

- each comparator held its successor and counted the rows it could not rank in a referenced-key set that was never
  reset per owner. The walk took the size delta of that set as "the tail I could not rank". A second owner's
  unranked rows were therefore not handed on, and because the slicer and the decorator share one comparator
  instance, the slicer could prefetch a page the decorator then laid out differently - a page without its bodies;
- composition replaced or dropped links: the head's `andThen` was called for every new ordering, some comparators
  returned a copy whose successor replaced the old one, two returned `this`, and the nested entity comparator's copy
  lost the state the fetcher had prepared ("The comparator is not initialized!");
- chain orderings read the scope stack at compare time, after it had been popped.

The route-parity work (`2026-10-29-traverse-ordering-route-parity`) did not catch any of this: `referenceContent`
ordering has a single route (a comparator over the owner's rows), so an index-versus-prefetch oracle has nothing to
compare.

### Previous state

A `ReferenceComparator` exposed `andThen`, `getNextComparator`, `getNonSortedReferenceCount` and, for chain lookups,
`EntityPrimaryKeyAwareComparator#setEntityPrimaryKey`. `EntityDecorator#sortAndFilterSubList` and
`BitmapSlicer#sortReferencesByComparatorChain` each walked the successors, sorted the whole remaining window with
each comparator and advanced by the counter delta, clamped so a stale cumulative reading could not run past the end.

## Options considered

### Owner-scope gate on visitor-owned links, rows classified in one shared walk (chosen)

The visitor appends a `ReferenceComparator.Link` per ordering - the owner scope it applies to (all, or the enclosing
`inScope`), its delegate, its successor. `ReferenceComparator#sort` walks the links for one owner: a link whose scope
does not admit the owner is skipped without preparing its delegate; otherwise the delegate is prepared for the owner,
the window is stably partitioned into the rows it `canRank` and the rest, the ranked prefix is sorted and the rest
goes to the next link.

- **Pros:** the same meaning of scope as the filter and the entity-level orderings; both fetch sites agree by
  construction because they call one method; declaration order and delegate identity are structural.
- **Cons:** an in-place change of a public interface; a mixed window needs one temporary buffer; each comparator must
  state a `canRank` rule consistent with its `compare`.

### Keep the scope for index validation only (declined)

- **Pros:** no change of any result for owners outside the gate.
- **Cons:** contradicts the documented `inScope` and the filter; a following ordering meant for the other scope's
  owners never runs.
- **Rejected because:** the ordering would keep a meaning of `inScope` that no other part of the query has, and the
  red tests show the second `inScope` silently ignored. Revisit if the documentation ever redefines `inScope` inside
  `referenceContent` as an index-only hint.

### Repair comparator-owned successors and reset the counters per owner (declined)

- **Pros:** smallest diff, localised to each comparator.
- **Cons:** the count of distinct referenced keys a comparator met cannot identify which rows it left unranked - one
  key can sit on several rows (duplicates) and in several owners - and the slicer and the decorator need the very
  same unranked rows before paging.
- **Rejected because:** a counter cannot carry row identity, so the paging disagreement stays. Revisit never; the
  classification replaces the counter.

### Sub-decisions

| Question | Chosen | Declined, and why it lost |
|---|---|---|
| `ReferenceComparator` compatibility | Change in place: drop the counter, `andThen`, `getNextComparator` and `EntityPrimaryKeyAwareComparator`; add `prepareOwner` and `canRank` | Defaults plus deprecation: the only implementations are the engine's comparators, `DEFAULT`, `Link` and two test fakes (the gRPC client returns `null`), so a deprecated parallel protocol would keep the counter alive for no implementor. Revisit if an external implementation is reported. |
| `entityProperty` and `entityGroupProperty` under different `inScope` containers | Refused at translation; under one container (or both unwrapped) they form one nested stage as before | One nested stage per gate: well defined but more code for a shape no user is known to need, while the shared nested comparator computes one combined position. Revisit if a user needs per-scope entity orderings. |
| Chain lookup scope (changed during review) | A plain chain is looked up in the owner's scope (`prepareOwner`); a reflected chain applying to several scopes is refused at translation; the entity-component check runs at translation for every scope a link applies to. A plain chain's scope set is the request's scopes plus the scopes of the owners being decorated - an enrichment selects no scope and may reuse bodies fetched in another scope - while every other ordering is still validated in the request's scopes | Keep the lookup-time "cannot be executed in multiple scopes" refusal: it fires only when a row needs a lookup, so a query works until an owner gets a second row, and the classification made lookups earlier still - one row was enough. Refusing plain chains over several scopes at translation: would refuse the common working case, since distinct targets never read a chain. |
| Rows without a value for the ordering's attribute (also a missing predecessor) | After the ranked rows: to the next ordering, then ascending referenced primary key - the rule of every comparator, plain `Predecessor` included | `dev`'s placement, which put them first when the chain was the last ordering: a special case in the walk, and with a following ordering `dev` already handed the tail on, so "first" only ever showed without one. |

## Decision

**Chosen: the owner-scope gate on visitor-owned links with a shared classifying walk.** It is the only option under
which `inScope` means the same in the filter and in the ordering and under which the slicer and the decorator cannot
disagree on a page. The comparator interface changes in place because nothing outside the engine implements it. The
refusals added on the way - nested orderings across gates, a reflected chain over several scopes - are decided from
the query and the schema alone, never from the data: a query that works must not start failing after upserts.

## Key technical details

- `ReferenceComparator#sort` (`evita_api`) is the walk; `EntityDecorator#sortAndFilterSubList` (owner scope:
  `getScope()`; named path `ServerEntityDecorator`: `entity.getScope()`) and `BitmapSlicer#sliceSortedOwner` (owner
  scope: the key of its `Map<Scope, int[]>`) both call it. Paging applies to the ordered rows before the slicer
  collapses them to body keys.
- `ReferenceOrderByVisitor#addComparator` appends links; `getOrCreateNestedQueryComparator` refuses a second gate;
  `translateChainLookup` makes both chain refusals at translation.
- Can-rank rules: attribute - value present; exact primary key - listed; natural primary key and `DEFAULT` - always;
  compound - always (each element's null policy already orders the tuple); plain `Predecessor` - the row has a
  predecessor value (across targets it orders by key; a chain is read only between duplicate rows of one target);
  `ReferencedEntityPredecessor` - a position is found; nested
  entity or group - a position the stage's own sorters ranked (a referenced entity or group without the ordered
  value goes to the next ordering; ordering by the referenced primary key ranks every row). A `canRank` that accepts a row `compare` treats as missing is
  the bug to look for when adding a comparator.
- The ordering descriptor exposes the exact nested delegate the walk calls, so the fetcher prepares the instance that
  compares.
- Representative keys of duplicate rows are computed from the raw reference row, so an attribute the client did not
  request cannot make a comparison fail.
- The entity-component check at translation also refuses an ordering over a legacy schema whose scope lacks the
  entity component when the result is empty - which `dev` accepted only because no lookup happened. That is the
  data-independent refusal `2026-09-28-indexed-reference-scope-requires-entity-component` asks for.

## Verification

- `ReferenceContentOrderingInScopeFunctionalTest` - 50 cases (38 red on `dev`): scoped orderings, fallback, scoped
  comparators, unranked rows, chain composition, nested gates and their refusal, pins of orderings without `inScope`,
  unpaged and paged.
- `ReferenceContentChainOrderingInScopeFunctionalTest` - 25 cases: plain chains over `scope(LIVE, ARCHIVED)` read each
  owner's own partition (duplicates, bodies on page 2), acceptance on an empty collection and with zero, one and three
  rows; reflected chains over two scopes refused in the same four states and accepted inside `inScope(LIVE, ...)`;
  the entity-component refusal at translation; the placement of unpositioned rows, unpaged and paged; a nullable
  plain chain with and without a following ordering; enrichment of LIVE and ARCHIVED owners with zero, one and three
  rows; duplicates ordered without the representative attribute requested; a reused archived body during enrichment.
- Nested orderings followed by another ordering, for a brand and for a group without the ordered value; ordering by
  the referenced primary key inside the nested stage ranks every row.
- `CatalogGraphQLScopedEntityContentFunctionalTest` - 9 cases, archived owners pinned.
- Related selection (slicer, decorator, reference content, pagination, fetch, chains, predecessors, virtual
  partitions, traverse, duplicates, scope suites): 3,903 tests in 149 classes, 0 failures.

## Consequences & open follow-ups

Results that change (the upgrade note on #2045 lists them):

- archived owners' references under `inScope(LIVE, ...)` come in the order of the next ordering, finally ascending
  referenced primary key;
- references without a value for the ordering's attribute come after the others, also for chain orderings, which
  placed references without a chain position first when the chain was the last ordering;
- `entityProperty` and `entityGroupProperty` in different `inScope` containers are refused, including two sibling
  containers of the same scope;
- an unwrapped reflected chain ordering over several scopes is refused at translation, also for data that never
  compared two rows;
- `ReferenceComparator` implementations must move to `prepareOwner` / `canRank`.

Deferred:

- **Reflected chains spanning forward partitions of different scopes** need their own ordering contract; today they
  are refused unless wrapped in one `inScope`.
- **Reflected chain cache and representative partitions** (suspected, not proven): the last-lookup cache of a
  reflected chain ignores representative values, so duplicates spread over representative partitions may read one
  partition's positions; the classification moved the first lookup earlier, which can change which partition seeds
  the cache. Pin a concrete expected order before changing the cache key - adding the representative values alone
  broke the virtual-versus-materialized parity test.
- **Plain `Predecessor` position cache** is keyed by the record primary key only, so duplicate rows of one target in
  different representative partitions can reuse one partition's position (visible only with duplicates).
- **Nested orderings inside `inScope` sort the referenced entities in the gate's scope.** In
  `inScope(ARCHIVED, entityProperty(attributeNatural('name')))` the gate correctly admits the archived owners, but
  their LIVE brands are sorted in ARCHIVED, rank nowhere and come in key order - the same output as `dev`. Sorting
  them in the scopes their bodies are fetched in was measured and reverted: that also validates the sorted
  attribute in every searched scope, which refused two scoped group orderings whose attribute is sortable in LIVE
  only and an accepted nested `inScope(LIVE, ...)` ordering of `2026-11-05-nested-inscope-requested-scopes`. A
  narrower contract (sort in the body scopes where the attribute is sortable, refuse only when there is none) was
  not tried. Tracked as #2050, which proposes admitting an inner `inScope(LIVE, ...)` inside `entityProperty`.
- **Cost of classification** (suspected, not measured): `canRank` reads each row's value once more before the sort
  reads it again; a scalar comparator could carry the value from the classification into the comparison.
- **Group membership** is built from raw rows, including rows the reference filter rejects.
- **gRPC strip reconstruction**, found on the way and unrelated to this change: the client re-applies a strip to the
  server's already-stripped chunk (`EntityConverter`, `StripTransformer`).

## Related work

- `2026-10-20-reference-content-filter-row-by-row` - the row filter whose `RowPredicate.InScope` gates by the owner's
  scope; the ordering now has the same meaning.
- `2026-11-05-nested-inscope-requested-scopes` - admits and refuses `inScope` of the filter up front; the ordering's
  new refusals follow the same "decide before any row" rule.
- `2026-10-29-traverse-ordering-route-parity` - the route-parity work; `referenceContent` ordering has one route, which
  is why its oracles never saw this defect.
- `2026-09-28-indexed-reference-scope-requires-entity-component` - the entity-component check, which chain orderings
  now make at translation for every scope a link applies to.
- `2026-09-23-reference-decode-narrowing-by-referenced-key` - decode narrowing; the walk orders exactly the rows the
  narrowing decodes.

## Timeline

- **2026-11-20** - defect confirmed, red tests written, design reviewed; Johnny chose the in-place interface change,
  the refusal of nested orderings across gates, the breaking-change label and this record; implemented.
- **2026-11-21** - review fixes: chain lookups in the owner's scope (enrichment included), chain refusals at
  translation, representative keys from the raw row; Johnny confirmed that a row whose ordering attribute is missing
  goes to the next ordering and then to ascending primary key.
- **2026-11-21** - code-quality pass: chain scope set separated from attribute validation, nested stages hand
  unvalued rows on; the nested sorting scope deferred after measurement.
