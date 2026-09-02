---
title: hierarchyContent gains HierarchyParentsBehaviour; MATCHING stays the default and COMPLETE opts into the whole chain
date: 2026-08-03
updated: 2026-09-02 20:50
status: proposed
kind: fix
issues: [1365]
prs: [1370]
areas: [evita_query/src/main/java/io/evitadb/api/query/require, evita_engine/src/main/java/io/evitadb/core/query/fetch, evita_engine/src/main/java/io/evitadb/index/hierarchy, evita_api/src/main/java/io/evitadb/api/requestResponse/data, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_rest]
supersedes: []
superseded-by: []
relates: []
---

# `hierarchyContent` gains a parents-behaviour argument, defaulting to today's shape

A query carrying `entityLocaleEquals(en)` together with `hierarchyContent(entityFetch(...))` loses
every ancestor above the first one that holds no data in `en`, and a deleted ancestor at one
particular depth throws. `hierarchyContent` gains an optional `HierarchyParentsBehaviour` argument
with two values. Under `COMPLETE` an ancestor whose *requested body* cannot be materialized stays in
the chain as a bodyless pointer and the walk continues above it with bodies. Under `MATCHING` the
chain is cut just below that ancestor. `MATCHING` is the default, because it is the closer of the
two to today and the only one that never introduces a new exception for an existing caller. The
external APIs stay additive: `parents` / `parentEntity` keep their present types and imply
`MATCHING`, and new sibling fields `parentsComplete` / `parentEntityComplete` carry `COMPLETE`.
Three adjacent defects found during review are folded in. Accepted 2026-08-03, revised 2026-09-02
after a measured review; scheduled against milestone 2026.3.

## Why

The failure is silent in one shape and misleading in the other, and both are reachable from a query
that already asks for exactly what it is being denied.

With the unmaterializable ancestor as the **immediate parent**, `getParentEntity()` returns a
bodyless classifier and a typed proxy getter throws `ContextMissingException` from
`GetParentEntityMethodClassifier`, telling the caller to add `hierarchyContent` with `entityFetch` —
which the query already carries. With the unmaterializable ancestor **further up**, it disappears
entirely *and takes every ancestor above it with it*, including ones that would have materialized;
its child then looks like a root, and a caller walking the chain cannot tell a dropped ancestor from
a genuine one.

The constraint that made this non-obvious: an ancestor is not a reference. For a reference, "omit
the target" is a valid resolution and #1343 settled it that way. You cannot remove a node from the
middle of a parent chain without leaving a hole, and `EntityReferenceWithParent` — a linked chain —
has no representation for one. Every legitimate truncation (`stopAt`) is a suffix toward the root.

### Previous state

Measured on 2026-09-02 against `dev`, not read off the code. **Today is not one behaviour but a
position-dependent hybrid**, and that is the fact the whole design rests on:

- the first unmaterializable ancestor **at distance 1** yields a bodyless pointer and nothing above
  it, which for a one-ancestor chain is exactly `COMPLETE`;
- the first one **at distance ≥ 2** yields a cut, which is exactly `MATCHING` — this is the shape
  #1365 reports;
- **two or more** unmaterializable ancestors, or a broken index, yield neither: a pointer at the
  nearest one and nothing above, discarding materializable ancestors.

The split has one cause at two sites. `ReferencedEntityFetcher#prefetchParents` collapses a missing
immediate-parent body to `null`, after which the getter `EntityDecorator#getParentEntity` falls back
to the raw stored chain and produces a pointer; higher up,
`ReferencedEntityFetcher#replaceWithSealedEntities` short-circuits the recursion and stamps
`EntityClassifierWithParent.CONCEALED_ENTITY`, which the same getter renders as `Optional.empty()`.
The same absence is a pointer at one position and a cut at another.

The gate itself: `ReferencedEntityFetcher#prefetchParents` derives the parent fetch request with the
two-argument `EvitaRequest#deriveCopyWith`, which **inherits** the query locale;
`EntityCollection#fetchEntityDecorator` then re-applies it as an *existence* predicate, so a content
requirement silently changed the structure of the result. Requesting only a **global** attribute does
not rescue the ancestor, and `dataInLocales` inside the parent `entityFetch` does not either.
`identifyParents` builds the complete ancestor axis unconditionally, so `hierarchyContent()` with no
inner fetch still returns the whole primary-key chain: asking for *more detail* returned *fewer
ancestors*.

Broken chains behave worse than the original design assumed. A deleted **mid-chain** node exactly two
levels above the queried entity throws `EvitaInvalidUsageException` from
`HierarchyIndex#getParentNodeOrThrowException`, regardless of locale or `entityFetch`, because
`traverseHierarchyToRoot` starts its pre-walk at the *parent* primary key. Three levels up it is
silent and demotes a perfectly fetchable ancestor to a pointer. A deleted **root** never throws at
all, because `EntityRemoveMutation` emits `RemoveParentMutation` only when the entity has a parent,
so a removed root is never un-indexed and its children are never orphaned — a phantom root that
still matches `hierarchyWithinRootSelf()`. An upsert with a parent primary key that was never created
is accepted and reaches the same assert from the ingest side.

## Options considered

### Option A — a caller-selected behaviour argument, defaulting to `MATCHING` (chosen)

Add `HierarchyParentsBehaviour { COMPLETE, MATCHING }` to `hierarchyContent`. `COMPLETE` keeps every
ancestor, substituting a bodyless pointer where the requested body cannot be materialized and
continuing above it. `MATCHING` cuts the chain just below the first such ancestor. The same rule
covers a chain broken by a deleted or never-created ancestor.

- **Pros:** both outcomes stay expressible; the pointer is a visible, typed signal rather than a
  hole; the `MATCHING` default is the closest mode to today and never adds an exception where there
  is none; a `MATCHING` cut is a data-dependent `stopAt`, a shape the chain can already represent.
- **Cons:** a new constraint argument across EvitaQL, Kryo, gRPC, GraphQL and REST, none of which
  `ManagedReferencesBehaviour` reached beyond EvitaQL and gRPC, so there is less template to copy
  than the original record assumed; #1365 is not fixed by default.

### Option B — materialize the ancestor with the locale gate lifted (declined)

Fetch parent bodies ignoring the query locale: global attributes stay available, localized ones are
simply absent, and the chain is always complete with bodies. This is what the issue reporter asked
for, since the attributes they need (`code`, `url`) are global.

- **Pros:** smallest change; no new argument; answers the reported use case directly.
- **Rejected because:** a client filtering by `entityLocaleEquals` expects every returned entity to
  carry all mandatory localized attributes. An entity shell without them is neither present nor
  absent, and downstream code cannot tell it from a complete one. `referenceContent` settled the same
  question the same way in #1343. Revisit only if a way to mark a partially-localized entity as such
  is introduced for entities generally, not for ancestors alone.

### Option C — `COMPLETE` as the default (declined)

Ship the same enum, but make the full chain the default so #1365 is fixed without opting in.

- **Pros:** the reported defect disappears for every caller; the default is the behaviour the issue
  argues is correct.
- **Rejected because:** it introduces a new `ContextMissingException` at positions that are silent
  today. A grandparent that vanishes today would come back as a pointer, and a typed
  `@ParentEntity` proxy getter throws on any parent that is not a `SealedEntity`. `MATCHING` never
  adds an exception for an existing caller; `COMPLETE` adds one wherever a chain has an
  unmaterializable ancestor at distance ≥ 2. Revisit at a major version, where a default flip is
  affordable.

### Also rejected

| Option | Rejected because | Revisit if |
|---|---|---|
| An explicit, reportable truncation marker for the `MATCHING` cut | It invents a third chain-ending concept alongside `stopAt` and a real root, and the marker is precisely the hint that reference `EXISTING` deliberately withholds — parents would become *less* consistent with references, not more | A marker is introduced for `stopAt` cuts too, so the two share one mechanism |
| A union / `oneOf` on the existing `parents` and `parentEntity` fields | Breaks every generated client and 18 documentation example snapshots, for a shape the sibling-field precedent already covers additively (`*Page` / `*Strip` on `WithNamedReferenceDescriptor`, the static `priceForSale` / `allPricesForSale` pair) | Never for these two fields; a future field can start out as a union |
| An additive entity object carrying a `bodyAvailable` flag and true `scope` / `version` / `allLocales` | It is an entity-shaped shell — the same thing Option B was rejected for, only reached through the API layer instead of the engine | Option B is ever reconsidered; the two stand or fall together |
| Merge rule "stricter wins", as `ReferenceContent#combineWith` does for `ManagedReferencesBehaviour` | A silent downgrade hides a query-authoring mistake. `HierarchyContent#combineWith` already throws on conflicting `stopAt`, so throwing is the local precedent, not the departure | The engine ever gains a way to report a silently-resolved requirement conflict back to the caller |

## Decision

**Chosen: Option A with `MATCHING` as the default.**

`MATCHING` is defined as *"cut the chain just below the first ancestor whose **requested body**
cannot be materialized"*. The definition carries the no-inner-fetch case for free: with no
`entityFetch` inside `hierarchyContent` no body is requested, nothing can fail, and the full
primary-key chain is returned exactly as today. It must be stated in the javadoc so nobody
implements it filter-relative, which would cut the chain of every existing `hierarchyContent()`
caller and of every `entityFetchAll()`, both of which emit a bare `hierarchyContent()`.

`COMPLETE` is defined as *"every ancestor appears; where the requested body cannot be materialized
the element is a bodyless pointer and the walk continues above it"*.

Two consequences must be said plainly rather than discovered:

- **The shapes #1365 reports are unchanged under the default.** A caller opts into `COMPLETE` to get
  the ancestors back. "We fixed #1365" and "the default preserves today" cannot both be true, and the
  release note has to say which one holds.
- **One behaviour-visible change lands for existing callers anyway**: the immediate-parent pointer
  disappears, so today's `ContextMissingException` on dereference becomes a silent root. That is the
  price of not adding an exception anywhere else, and it is the riskiest part of the design because
  it is silent.

For `MATCHING` to lose its default slot, a major version would have to make a default flip
affordable; for the enum itself to lose, the principle that a locale-filtered query returns only
fully-localized entities would have to be abandoned, which would reopen #1343 as well.

## Behaviour matrix

Rebuilt from the fixtures measured on 2026-09-02; the `today` column is an observation, so the
backward-compatibility claim above is checkable row by row.

`standard` = `hierarchyContent(<mode>, entityFetch(attributeContentAll()))` inside
`entityFetch(attributeContentAll())`, filtered by `entityPrimaryKeyInSet(leaf) +
entityLocaleEquals(en)`. The query locale is **`en`**; `(cs)` marks a node holding Czech only — the
unmaterializable case — and every other node holds English. Chains read **leaf → root**; the result
columns list the **parent chain only**, the queried leaf omitted. `B(pk)` = present with body,
`P(pk)` = present as a bodyless pointer, `—` = the chain ends.

| # | fixture (leaf → root) | requirement | today (measured) | `COMPLETE` | `MATCHING` (default) |
|---|---|---|---|---|---|
| P1 | 12 → **11(cs)** | standard | `P(11)` | `P(11)` | `—` |
| P2 | 23 → 22 → **21(cs)** | standard | `B(22)` | `B(22) → P(21)` | `B(22)` |
| P3 | 34 → 33 → **32(cs)** → 31 | standard | `B(33)` | `B(33) → P(32) → B(31)` | `B(33)` |
| P4 | 44 → **43(cs)** → **42(cs)** → 41 | standard | `P(43)` | `P(43) → P(42) → B(41)` | `—` |
| P5 | 54 → **53(cs)** → 52 → **51(cs)** | standard | `P(53)` | `P(53) → B(52) → P(51)` | `—` |
| P6 | 63 → **62(cs)** → **61(cs)** | standard | `P(62)` | `P(62) → P(61)` | `—` |
| N1 | 23 → 22 → **21(cs)** | `hierarchyContent()`, no `entityFetch` | `P(22) → P(21)` | identical | identical |
| N2 | 12 → **11(cs)** | `+ stopAt(distance(1))` | `P(11)` | `P(11)` | `—` |
| N3 | 34 → 33 → **32(cs)** → 31 | `+ stopAt(distance(2))` | `B(33)` | `B(33) → P(32)` | `B(33)` |
| N4 | 12 → **11(cs)** | no query locale | `B(11)` | `B(11)` | `B(11)` |
| N5 | 12 → **11(cs)** | `+ dataInLocales(en)` inside | `P(11)` | `P(11)` | `—` |
| K1 | 72 → **71 deleted** (was a root) | standard | `P(71)` | `P(71)` | `—` |
| K2 | 83 → 82 → **81 deleted** (was a root) | standard | `B(82)` | `B(82) → P(81)` | `B(82)` |
| K3 | 123 → **122 deleted** (mid-chain) → 121 | standard | `P(122)` | `P(122)` | `—` |
| K4 | 124 → 123 → **122 deleted** (mid-chain) → 121 | standard | **throws** | `B(123) → P(122)` | `B(123)` |
| K5 | 125 → 124 → 123 → **122 deleted** (mid-chain) | standard | `P(124)` | `B(124) → B(123) → P(122)` | `B(124) → B(123)` |
| K6 | 111 → **999**, never created | standard | `P(999)` | `P(999)` | `—` |

Every `today` cell is an observation. P6 was inferred from the measured P4 when this record was
drafted rather than observed on its own; it has since been measured directly by the P6 row of
`HierarchyContentParentsBehaviourFunctionalTest`, which returned the inferred `P(62)`.

Reading the table: **P2 / P3 / N3 / K2 confirm the default preserves today**, and they are the #1365
shapes. **P1 / P4 / P5 / P6 / N2 / N5 / K1 / K3 / K6 are where the immediate-parent pointer
disappears** — the one silent change. **K4 and K5 cannot preserve today under any mode**: K4 throws
today and an exception is not a shape a mode can reproduce, and K5 today demotes the materializable
124 to a pointer and truncates, which neither mode does. Both become correct chains. **P3 is the
load-bearing acceptance test for `COMPLETE`**: it is the only row proving the walk no longer stops
at the first missing body. **P5 is where `MATCHING` visibly discards an ancestor it could have
returned**, which is what makes it a truncation rather than a per-node filter.

Two rows are deliberately absent. A **non-localized schema under a query-level locale** returns no
queried entity at all, so that path is only reachable through a localized enveloping entity and
cannot be written as a top-level row. `stopAt(level(N))` **on a broken chain** is not characterised,
because with the phantom-root defect fixed the meaning of "broken" changes; it is covered by the
broken-chain rule instead.

## Subsidiary decisions

| Decision | Why | Rejected alternative |
|---|---|---|
| A second enum, `HierarchyParentsBehaviour`, not a reuse of `ManagedReferencesBehaviour` | the two name different axes: a reference is judged per element, an ancestor chain is judged by prefix | one shared enum, which would signal symmetry exactly where the semantics diverge |
| Constants `COMPLETE` / `MATCHING`, named on the parents axis | `ANY` is already spent by `ManagedReferencesBehaviour` and `ALL` by `PriceContentMode` in the same package; the project naming rule forbids a second constant of the same name there, and `EXISTING` names a condition that behaves identically in **both** modes | `ANY` / `EXISTING` for surface symmetry with `referenceContent` |
| No marker for a `MATCHING` cut | the caller opted into it, exactly as they opt into `stopAt(distance(N))`, whose cut is likewise indistinguishable from a real root | a reportable cut — the precedent is honestly weaker here than for `stopAt`, because a `stopAt` cut is caller-computable and a `MATCHING` cut is data-dependent |
| Broken chains report up to the break in both modes; the assert is replaced | today the same situation throws two levels up, is silent three levels up, and loses a fetchable ancestor in the process; one rule covers a deleted ancestor and a never-created parent primary key alike, the latter being a legitimate orphan state per `documentation/user/en/use/schema.md` | leaving the assert in place and documenting the depth-dependent throw |
| Fold in the phantom-root fix | `EntityRemoveMutation` emits `RemoveParentMutation` only when a parent exists, so a deleted root is never un-indexed and still matches `hierarchyWithinRootSelf()`; it changes what "deleted ancestor" means to the fetcher, so it cannot be deferred past this work | filing it separately and implementing against the phantom behaviour |
| Fold in the Kryo fix for `ManagedReferencesBehaviour` | `ReferenceContentSerializer` never writes it and the configurer never registers it, so a stored `referenceContent(EXISTING, …)` replays as `ANY`; the new enum needs the same wiring and would otherwise copy the defect | shipping `HierarchyParentsBehaviour` serialization while leaving the neighbouring gap |
| GraphQL: `parents` keeps `MATCHING`, new `parentsComplete(stopAt:)` returns a list of a union | additive on the schema; the union follows the `...Union` convention already established for mutation DTOs | typing the list as the existing `THIS_CLASSIFIER` interface, which mints no new type and reuses `EntityDtoTypeResolver` at the same fragment cost — a real alternative, decided on convention rather than capability |
| REST: `parentEntity` keeps `MATCHING`, new `parentEntityComplete` typed `oneOf` | `OpenApiUnion` already supports `ONE_OF`; and `parentEntity` becomes *honest*, since a `MATCHING` chain never contains a pointer | discriminating the `oneOf` on `type`, which cannot work — an entity and its own pointer carry the same type value |
| The resolver emits exactly one `hierarchyContent` | both fields selected means `COMPLETE` with the union of the two selection sets and equal `stopAt` required; `parents` is then derived by stopping the leaf→root walk at the first non-`SealedEntity`, which equals `MATCHING` and stays equal under `stopAt`, since a prefix of a truncated chain is a truncation of a prefix | two constraints and a merge, which the throw-on-conflict rule forbids |
| Conflict check ignores a side that requests no bodies | a bare `hierarchyContent()` has an inert mode by definition, so `entityFetchAll()` combined with an explicit `hierarchyContent(COMPLETE, entityFetch(...))` keeps working | a strict check, which would make `entityFetchAll()` unusable next to any explicit `COMPLETE` fetch |
| `CONCEALED_ENTITY` deprecated, not deleted | it is public `evita_api`, and it throws from `getType()` / `getPrimaryKey()`, which the exception policy forbids | deleting it; the `since` value is re-derived from the reactor pom at commit time rather than hardcoded now |

## Key technical details

- `ReferencedEntityFetcher#replaceWithSealedEntities` is the core: it short-circuits the recursion
  when a body is missing. `COMPLETE` substitutes a pointer *and keeps recursing past it*; `MATCHING`
  keeps the short-circuit. The ancestor bitmap is already correct — `identifyParents` registers the
  whole axis before any body is fetched.
- `EntityClassifierWithParent.CONCEALED_ENTITY` marks the top of **every** fetched chain — a real
  root, a `stopAt` cut and the defect alike — not only the defect. It is what stops the delegate
  fallback in `EntityDecorator#getParentEntity`; removing its producer before a replacement lands
  would leak one raw ancestor past every `stopAt` truncation.
- The live fallback that produces today's immediate-parent pointer is the **getter**
  `EntityDecorator#getParentEntity`, not the `EntityDecorator` constructor. An implementer aiming at
  the constructor fixes the wrong site.
- The derived parent request inherits the query locale through the two-argument
  `EvitaRequest#deriveCopyWith`, and `EntityCollection#fetchEntityDecorator` applies it as an
  existence gate. That pair is the mechanism the whole issue reduces to.
- `HierarchyIndex#traverseHierarchyToRoot` returns **without visiting anything** when the pre-walk
  finds an absent ancestor, and its orphan guard tests whether the parent is a registered orphan, not
  whether it exists. Its only two callers are reporting paths; hierarchy *filtering* excludes orphans
  structurally via `levelIndex`, so changing it cannot affect the documented orphan invariant.
- `GrpcEntityReferenceWithParent` is pointer-only recursively, so a body-above-pointer chain is not
  transmissible today. The new field must be populated **alongside** the legacy one, so an old client
  receives a complete chain degraded to pointers rather than a truncated one.
- `HierarchyContent#cloneWithArguments` throws on any argument and `#getCopyWithNewChildren`
  silently discards arguments. Both must be taught the new value before the constraint carries one.
- `ProxyUtils#createOptionalWrapper` picks a swallowing or rethrowing wrapper from the method
  signature. Tests must assert against the raw `SealedEntity` API or a `throws`-declaring proxy
  method — a swallowing wrapper returns empty for both "never requested" and "unmaterializable" and
  would let a broken implementation pass.
- The `ManagedReferencesBehaviour` precedent covers EvitaQL, gRPC and the engine only. GraphQL
  hardcodes `ANY`, REST never references it, it is not a `@Creator` parameter, and Kryo never
  serializes it. The constraint-schema layers therefore have no template to copy — although an enum
  `@Creator` parameter does yield the GraphQL argument and the REST JSON constraint value generically.

## Verification

**Nothing is implemented yet.** What *is* verified is the starting point: a throwaway
characterisation run on 2026-09-02 against `dev` executed every row of the behaviour matrix except
P6, which the landed P6 test measured afterwards, and classified today's behaviour as the
position-dependent hybrid described above. The test was deleted after the run; the matrix is its
record.

The implementation proves itself with:

- `HierarchyContentParentsBehaviourFunctionalTest` — the behaviour matrix, both modes plus the
  `today` column as the backward-compatibility guard. The `today` column has already landed: 32 test
  methods across four nested classes (locale gate, requirement variations, broken chains, defect
  pins), all green;
- `HierarchyIndexTest` — root removal and broken-chain traversal at two, three and more levels; four
  methods have already landed, one for root removal and three for the traversal depths. Before this
  line of work no test removed a hierarchical root at either layer, which is why the phantom root
  survived: the landed `MutationTest#shouldRemoveRootNodeAndOrphanItsSubtree` pins removal at the
  index level, and the functional suite's defect pin
  `DefectPinTest#shouldStillListDeletedRootInHierarchyToday_phantomRoot` pins it at the entity level;
- `QuerySerializationTest` — round-trip of both `HierarchyParentsBehaviour` values *and* both
  `ManagedReferencesBehaviour` values, the latter being the regression guard for the Kryo defect that
  only `ANY` round-tripping hid;
- GraphQL and REST functional tests for `parentsComplete` / `parentEntityComplete`, plus the existing
  `parents` tests unchanged as the additive-schema guard;
- `ManagedReferenceLocaleFunctionalTest` untouched and green, proving the #1343 reference behaviour
  is not disturbed.

## Consequences & open follow-ups

- **The default preserves today's shapes, so #1365 is not fixed by default.** Callers opt into
  `COMPLETE`. This needs an explicit release note; without one the issue reads as fixed and is not.
- **One silent change for existing callers:** the immediate-parent pointer disappears, turning a
  `ContextMissingException` on dereference into a silent root. The deleted-ancestor throw becomes a
  clean cut, which cannot be avoided by any mode.
- `ContextMissingException.hierarchyEntityContextMissing()` needs **no rewording** — it was misleading
  only because the unmaterializable case reached it. Its remaining case is a caller who genuinely did
  not request bodies, for which the message is correct.
- **Deliberately left undone:** references under `ManagedReferencesBehaviour.ANY` have the same shape
  of problem — a locale-unmaterializable target yields a bodyless reference. #1343 settled `EXISTING`
  only. Kept out to hold the change surgical; it is the obvious next candidate if the principle is
  adopted more broadly.
- **Unverified adjacent risk:** `QueryPlanningContext#fabricateFetchRequest` shares the same
  two-argument `deriveCopyWith`, so other derived nested fetches may inherit the locale as an
  existence predicate too. Only the parent fetch was traced and is in scope.
- The in-flight plan, the measured review reports and the external-API option analysis live in
  `specifications/1365-hierarchy-content-parents-behaviour/`, which is git-ignored. This record flips
  to `accepted` and that folder is deleted when the work lands.

## Timeline

- **2026-08-03** — reported as #1365 with a five-test reproduction; both failure shapes traced to
  `replaceWithSealedEntities` and the parent-chain fallback; first design agreed, with `ANY` as the
  default and a union on the existing external-API fields
- **2026-09-02** — review against `dev` measured today's behaviour and refuted two load-bearing
  premises: the external APIs already emit bodyless parent pointers, so no breaking union is needed,
  and the broken-chain rows described today incorrectly. Design revised — default flipped to
  `MATCHING`, constants renamed onto the parents axis, sibling fields replace the union, merge rule
  set to throw — and three adjacent defects folded in
