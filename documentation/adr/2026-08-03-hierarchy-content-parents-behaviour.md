---
title: hierarchyContent gains HierarchyParentsBehaviour; MATCHING stays the default and COMPLETE opts into the whole chain
date: 2026-08-03
updated: 2026-09-02 23:48
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

Rebuilt from the fixtures measured on 2026-09-02; the `before #1365` column is an observation, so
the backward-compatibility claim above is checkable row by row.

`standard` = `hierarchyContent(<mode>, entityFetch(attributeContentAll()))` inside
`entityFetch(attributeContentAll())`, filtered by `entityPrimaryKeyInSet(leaf) +
entityLocaleEquals(en)`. The query locale is **`en`**; `(cs)` marks a node holding Czech only — the
unmaterializable case — and every other node holds English. Chains read **leaf → root**; the result
columns list the **parent chain only**, the queried leaf omitted. `B(pk)` = present with body,
`P(pk)` = present as a bodyless pointer, `—` = the chain ends.

| # | fixture (leaf → root) | requirement | before #1365 (measured) | `COMPLETE` | `MATCHING` (default) |
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

Every cell of the `before #1365` column is an observation. P6 was inferred from the measured P4 when
this record was drafted rather than observed on its own; it has since been measured directly by the
P6 row of `HierarchyContentParentsBehaviourFunctionalTest`, which returned the inferred `P(62)`.

**The traversal fix has already moved the broken-chain rows off that column, before
`HierarchyParentsBehaviour` exists at all.** The upward walk now reports every ancestor the index
still holds and stops at the first one it cannot resolve, which lands those rows directly on their
`MATCHING` values: K4 and each of its variants return `B(123)` — the Czech-only variant `B(133)`,
its own fixture — K5 returns `B(124) → B(123)`, and the K6 companion `112 → 111 → 999` returns
`B(111)`. K1, K2 and the K2 variant keep the cells they were measured with, even though the chain
below a removed root is now structurally broken rather than merely unreadable: what those rows
report does not depend on which of the two it is. The column is kept as the pre-fix baseline, not
as a description of the current engine.

Reading the table: **P2 / P3 / N3 / K2 confirm the default preserves the previous shapes**, and they
are the #1365 shapes. **P1 / P4 / P5 / P6 / N2 / N5 / K1 / K3 / K6 are where the immediate-parent
pointer disappears** — the one silent change. **K4 and K5 were the rows no mode could have
preserved**: K4 threw, and an exception is not a shape a mode can reproduce, while K5 demoted the
materializable 124 to a pointer and truncated, which neither mode does. The traversal fix settled
both ahead of the modes by making them report the ancestors the index can still reach. **P3 is the
load-bearing acceptance test for `COMPLETE`**: it is the only row proving the walk no longer stops
at the first missing body. **P5 is where `MATCHING` visibly discards an ancestor it could have
returned**, which is what makes it a truncation rather than a per-node filter.

One row is deliberately absent. A **non-localized schema under a query-level locale** returns no
queried entity at all, so that path is only reachable through a localized enveloping entity and
cannot be written as a top-level row. `stopAt(level(N))` **on a broken chain** has no row either,
because the answer does not depend on `N`: it is the level rule below, pinned by the two K5 variants
against the intact control on chain `1 → 2 → 3`.

## Subsidiary decisions

| Decision | Why | Rejected alternative |
|---|---|---|
| A second enum, `HierarchyParentsBehaviour`, not a reuse of `ManagedReferencesBehaviour` | the two name different axes: a reference is judged per element, an ancestor chain is judged by prefix | one shared enum, which would signal symmetry exactly where the semantics diverge |
| Constants `COMPLETE` / `MATCHING`, named on the parents axis | `ANY` is already spent by `ManagedReferencesBehaviour` and `ALL` by `PriceContentMode` in the same package; the project naming rule forbids a second constant of the same name there, and `EXISTING` names a condition that behaves identically in **both** modes | `ANY` / `EXISTING` for surface symmetry with `referenceContent` |
| No marker for a `MATCHING` cut | the caller opted into it, exactly as they opt into `stopAt(distance(N))`, whose cut is likewise indistinguishable from a real root | a reportable cut — the precedent is honestly weaker here than for `stopAt`, because a `stopAt` cut is caller-computable and a `MATCHING` cut is data-dependent |
| Broken chains report up to the break in both modes; the assert is replaced | before this work the same situation threw two levels up, was silent three levels up, and lost a fetchable ancestor in the process; one rule covers a deleted ancestor and a never-created parent primary key alike, the latter being a legitimate orphan state per `documentation/user/en/use/schema.md`. A parent the walk has already visited counts as the same break, which is what bounds a ring | leaving the assert in place and documenting the depth-dependent throw |
| Fold in the phantom-root fix | `EntityRemoveMutation` emits `RemoveParentMutation` only when a parent exists, so before this work a deleted root was never un-indexed and kept matching `hierarchyWithinRootSelf()`; it changes what "deleted ancestor" means to the fetcher, so it could not be deferred past this work. The removal path now tears the placement down itself, and a deleted root leaves the index with its descendants left as orphans | filing it separately and implementing against the phantom behaviour |
| Clearing a parent **re-roots** the entity rather than un-indexing it | a `RemoveParentMutation` means two different things: inside an entity removal it is one step of the tear-down, outside one it is a user promoting the entity to a root. The entity survives and reports no parent, so the index has to say the same; the two readings are told apart by whether the entity is marked as removed entirely | keeping the un-index for both, which is the exact mirror of the phantom root — a live entity would vanish from `hierarchyWithinRoot` and its whole subtree would be orphaned behind it |
| A tolerant tear-down on the removal and scope-change paths, while the mutation path keeps its assert | an entity of a hierarchical collection may legitimately hold no placement: making a collection hierarchical, or widening the scopes it indexes hierarchy in, re-places only the entities of the live global index, so anything sitting in another scope at that moment never receives one and would otherwise be undeletable. `removeNodeIfPresent` / `removeParentIfPresent` accept that absence; `removeNode` still refuses it where it really is a bug | one tolerant removal everywhere, which would also swallow a `removeParent` against an entity the index is supposed to hold — the class of defect this whole record is about |
| A node on a chain that does not reach a root has **no level** (`UNKNOWN_LEVEL`, the same `-1` `computeLevel` gives), and a level bound never cuts it in either direction | `HierarchyLevel` is defined as an absolute depth measured from the top of the tree, and a break makes the offset between the reachable fragment and the tree above it unknowable. Answering an absolute question with a relative number is the failure; refusing to answer it is not. `distance` is the bound that still holds on a broken chain, and the two traversal directions now share one predicate expression rather than agreeing by coincidence | **fragment-relative levels** (the top of the reachable fragment counted as level 1) — *rejected because* the bottom-up predicate `level >= N` would then drop reachable ancestors by an offset nobody can measure; K5 with `stopAt(level(2))` lost 123 under that rule. **Refusing `level(N)` on a broken chain** — *rejected because* the caller has no way to know the chain is broken, so the refusal would surface as an error on ordinary data |
| Fold in the Kryo fix for `ManagedReferencesBehaviour`, and accept the format break it causes | `ReferenceContentSerializer` never writes it and the configurer never registers it, so a stored `referenceContent(EXISTING, …)` replays as `ANY`; the new enum needs the same wiring and would otherwise copy the defect. There is no compatible middle ground for a query-constraint serializer — the reasoning is written down once in `documentation/adr/2026-08-04-query-telemetry-actionable-profile.md`. What the break reaches is wider than that record states: besides the traffic recorder and its replaying reader, the locally generated benchmark query corpora that `ClientSyntheticTestState` and `SanityChecker` load are consumers too. No corpus is tracked in git, so the cost is a local regeneration | shipping `HierarchyParentsBehaviour` serialization while leaving the neighbouring gap |
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
- `HierarchyIndex#traverseHierarchyToRoot` used to return **without visiting anything** when its
  pre-walk found an absent ancestor. It now collects the reachable fragment first and replays it to
  the visitor second, so the two phases cannot disagree about where the fragment ends; a parent it
  cannot resolve, and a parent it has already visited, both end the collection. Its only two callers
  are reporting paths — the `hierarchyContent` parents fetch and `ParentStatisticsComputer` —
  and hierarchy *filtering* excludes orphans structurally via `levelIndex`, so the change cannot
  affect the documented orphan invariant.
- `HierarchyIndexContract#UNKNOWN_LEVEL` is where the level rule lives. The upward walk hands it to
  the visitor for **every** node of a fragment whose top is not a real root, and
  `AbstractHierarchyTranslator#stopAtConstraintToPredicate` admits it outright in one expression
  shared by both traversal directions. Top-down already tolerated `-1` by accident; making it
  explicit is what stops the two halves from drifting. `distance` is untouched by any of this.
  Downward propagation is *not* covered — see the follow-ups below.
- `removeNodeIfPresent` is the tolerant sibling of `removeNode`, not a replacement for it. Reading a
  call site tells you which invariant it claims: `removeNode` asserts a placement exists and is
  reached from the `RemoveParentMutation` path, `removeNodeIfPresent` accepts its absence and is
  reached from entity removal and scope transitions. Both funnel into one private
  `finishNodeRemoval`, so a removal cannot come to cost different things on the two paths.
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

**`HierarchyParentsBehaviour` itself is not implemented yet** — no query carries the argument, which
is why this record is still `proposed`. What has landed is the groundwork the two modes will sit on:
the upward traversal, the level rule, the phantom-root and re-rooting fixes, the tolerant tear-down
and the three serializer fixes. The starting point is verified too: a throwaway characterisation run
on 2026-09-02 against `dev` executed every row of the behaviour matrix except P6, which the landed
P6 test measured afterwards, and classified the pre-#1365 behaviour as the position-dependent hybrid
described above. That test was deleted after the run; the matrix is its record.

The implementation proves itself with:

- `HierarchyContentParentsBehaviourFunctionalTest` — the behaviour matrix, both modes plus the
  pre-#1365 column as the backward-compatibility guard. That column has already landed and grown
  with the fixes: **38 tests across five nested classes** (locale gate, requirement variations,
  broken chains, `ParentStatisticsOverBrokenChainTest`, index invariants), all green.
  `ParentStatisticsOverBrokenChainTest` is the only coverage of the *second* production caller of
  the upward walk, and `IndexInvariantTest` carries the two invariants that replaced the defect pins
  — a deleted root leaves the index and orphans its descendants, and clearing a parent re-roots the
  entity instead of dropping it;
- `HierarchyIndexTest` — root removal, broken-chain traversal at two and three levels, the two ring
  shapes, the `-1` level pins and the two `listNodesIncludingParents` walks: **100 tests**, green.
  Before this line of work no test removed a hierarchical root at either layer, which is why the
  phantom root survived; `MutationTest#shouldRemoveRootNodeAndOrphanItsSubtree` pins removal at the
  index level and `IndexInvariantTest#shouldRemoveDeletedRootAndOrphanItsDescendants` pins it at the
  entity level;
- `EntityIndexLocalMutationExecutorHierarchyPlacementTest` — **5 tests** driving the placement state
  machine one local mutation at a time. Three of its states have no session-level route at all,
  which is why they are pinned at the mutator layer rather than through a query;
- `EvitaArchivingTest.HierarchicalScopeTransitionTest` — hierarchy placement across scope
  transitions: a root archived and restored, a root deleted while archived, a root whose archived
  scope does not index hierarchy, and an archived entity that never received a placement because
  hierarchy was declared on the collection after it was archived;
- `QuerySerializationTest` — round-trip of both `HierarchyParentsBehaviour` values *and* both
  `ManagedReferencesBehaviour` values, the latter being the regression guard for the Kryo defect that
  only `ANY` round-tripping hid; plus the rows the folded serializer fixes added — page spacing at
  top level, nested in `referenceContent` and with several gaps, the aliased zero-name and
  multi-name `referenceContent` shapes, and a multi-name `referenceContent` keeping its filter and
  order;
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
- **Follow-up: `EntityCollection#updateSchema` repairs only the LIVE global index.** When a
  collection becomes hierarchical, or widens the scopes it indexes hierarchy in, the existing
  entities of the live index are re-placed and nothing else is. An entity sitting in another scope
  at that moment therefore carries no hierarchy placement at all: it can be deleted and moved,
  because the tear-down was made tolerant here, but it is **not queryable through that scope's
  hierarchy** until it is upserted again. Repairing every indexed scope is the real fix and was
  deliberately not folded in — it is a re-indexing decision of its own, with a cost proportional to
  the collection rather than to the schema change.
- **Follow-up: the level rule stops at the pivot of a downward walk.**
  `HierarchyIndex#traverseHierarchyInternal` gives the pivot `computeLevel(...)` and then hands each
  descendant `level + 1`, so a walk whose pivot sits inside a detached fragment reports `-1` for the
  pivot and `0`, `1`, `2` … below it. A deep enough downward `stopAt(level(N))` inside such a
  fragment can therefore still cut, which the upward rule promises it will not. Propagating the
  unknown level downwards is a behaviour decision with a matrix row of its own and was not taken
  here.
- **The Kryo query-constraint format changed three times in this line of work, none of them with a
  compatibility reader** — the managed-references behaviour, the reference-content instance name,
  and the page spacing. That is deliberate and unavoidable for this serializer family (see the
  subsidiary decision above), but it means every payload written by an earlier build is unreadable
  and fails by desynchronizing rather than by reporting. The consumers are the traffic recorder with
  its replaying reader, and the locally generated benchmark query corpora read by
  `ClientSyntheticTestState` and `SanityChecker`; no corpus is tracked in git, so what a break costs
  is a local regeneration.
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
