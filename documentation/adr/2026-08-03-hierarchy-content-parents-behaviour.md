---
title: hierarchyContent keeps unmaterializable ancestors as bodyless pointers, with an opt-in cut
date: 2026-08-03
updated: 2026-08-03 14:32
status: proposed
kind: fix
issues: [1365, 1343]
prs: []
areas: [evita_query/src/main/java/io/evitadb/api/query/require, evita_engine/src/main/java/io/evitadb/core/query/fetch, evita_engine/src/main/java/io/evitadb/index/hierarchy, evita_api/src/main/java/io/evitadb/api/requestResponse/data, evita_external_api]
supersedes: []
superseded-by: []
relates: []
---

# `hierarchyContent` gains a parents-behaviour argument; the parent chain is never silently holed

A query carrying `entityLocaleEquals(cs)` together with `hierarchyContent(entityFetch(...))` drops
any ancestor that holds no data in `cs`. `hierarchyContent` gains an optional
`HierarchyParentsBehaviour` argument: under `ANY` (the default) such an ancestor stays in the chain
as a bodyless pointer and the chain continues above it with bodies; under `MATCHING` the chain is
cut there, and the caller opted into that. The same rule is extended to chains broken by a deleted
ancestor. Decision accepted 2026-08-03; implementation scheduled against milestone 2026.3 because
the external-API half is a breaking change.

## Why

The failure is silent in one shape and misleading in the other, and both are reachable from a query
that already asks for exactly what it is being denied.

With the locale-less ancestor as the **immediate parent**, `getParentEntity()` returns a bodyless
classifier and dereferencing it throws `ContextMissingException` telling the caller to add
`hierarchyContent` with `entityFetch` — which the query already carries. With the locale-less
ancestor **further up**, it disappears entirely and its child looks like a root; a caller walking
the chain cannot tell a dropped ancestor from a genuine root at all.

The constraint that made this non-obvious: an ancestor is not a reference. For a reference, "omit
the target" is a valid resolution and #1343 settled it that way. You cannot remove a node from the
middle of a parent chain without leaving a hole, and `EntityReferenceWithParent` — a linked chain —
has no representation for one. Every legitimate truncation (`stopAt`) is a suffix toward the root.

### Previous state

`ReferencedEntityFetcher#prefetchParents` derives the parent fetch request with the two-argument
`deriveCopyWith`, which drops `filterBy` but **inherits** `locale`. `EntityCollection#fetchEntityDecorator`
then re-applies that inherited locale as an *existence* predicate, so a content requirement silently
changed the structure of the result.

The decisive symptom: `identifyParents` builds the complete ancestor axis unconditionally, and only
the body-fetch branch can drop nodes. So `hierarchyContent()` returned the whole chain while
`hierarchyContent(entityFetch(...))` returned a shorter one — asking for *more detail* returned
*fewer ancestors*.

## Options considered

### Option A — a caller-selected behaviour argument (chosen)

Add an enum to `hierarchyContent` mirroring `referenceContent`'s `ManagedReferencesBehaviour`: `ANY`
returns the node without a body, `MATCHING` removes it and everything above it.

- **Pros:** one rule learned once across references and parents; the caller chooses; the `ANY`
  pointer is a visible, typed signal rather than a hole; `MATCHING` is a data-dependent `stopAt`,
  a shape the chain can already represent.
- **Cons:** a new constraint argument across five surfaces (EvitaQL, Kryo, gRPC, GraphQL, REST); the
  external-API half is breaking.

### Option B — exempt parents from the locale gate entirely (declined)

Fetch parent bodies with the locale constraint lifted: global attributes stay available, localized
ones are simply absent, and the chain is always complete with bodies.

- **Pros:** smallest change; no new constraint argument; fixes both reported shapes outright.
- **Cons:** makes an ancestor behave unlike every other fetched entity in the system, and offers no
  way to express "prune the breadcrumb where there is no translation".
- **Rejected because:** it hard-codes one of the two reasonable answers. A locale-pruned breadcrumb
  is a legitimate requirement, and under B it becomes inexpressible — the caller would have to
  post-filter a chain the engine already walked. Revisit only if the `MATCHING` mode proves unused.

### Option C — an explicit, reportable truncation marker (declined)

Keep dropping the ancestor, but make the truncation detectable — a marker distinguishable from
`stopAt` concealment and from a real root.

- **Pros:** preserves current behaviour; smallest behavioural delta for existing callers.
- **Cons:** invents a third chain-ending concept alongside `stopAt` and a real root.
- **Rejected because:** the marker is precisely the hint that reference `EXISTING` deliberately
  withholds, so it would make parents *less* consistent, not more — and it would legitimise the
  asymmetry where adding `entityFetch` removes ancestors, rather than removing it.

## Decision

**Chosen: Option A.** It is the only option that keeps the reference and parent stories identical
while leaving both outcomes expressible. B and C each pick one outcome and make the other
unreachable.

`ANY` is the default because the current behaviour is the defect: a default that preserved it would
leave every existing caller crashing until they discovered a new flag.

For A to lose, `referenceContent`'s `ANY`/`EXISTING` split would have to be abandoned — the whole
argument is consistency with it.

## Behaviour matrix

The agreed behaviour for every chain shape, and the reason for each. This is the acceptance
criterion for the implementation and the source for the E2E scenarios.

`standard` = `hierarchyContent(MODE, entityFetch(attributeContentAll()))`, wrapped in `entityFetch(attributeContentAll(), …)`, filtered by `entityPrimaryKeyInSet(leaf) + entityLocaleEquals(cs)`.

Chains read **leaf → root** in every column. `(cs,en)` = the entity holds data in both locales, `(en)` = English only — the unmaterializable case; every node is annotated, nothing is implied by omission. The queried entity is **bold** and always holds `cs`, because the top-level `entityLocaleEquals(cs)` would otherwise not return it at all. In the result columns `B(x)` = present with body, `P(x)` = present as a bodyless pointer, `—` = the chain ends. The arrow is the nesting: `B(2) → P(1)` means `B(2).getParentEntity()` yields `P(1)`.

| # | fixture (leaf → root) | requirement | `ANY` (default) | `MATCHING` | why |
|---|---|---|---|---|---|
| S1 | **23**(cs,en) → 22(cs,en) → 21(cs,en) | standard | `B(22) → B(21)` | `B(22) → B(21)` | No ancestor lacks `cs`, so nothing is gated: both modes return the full chain with bodies. |
| S2 | **12**(cs,en) → 11(en) | standard | `P(11)` | `—` | Parent 11 lacks `cs`. `ANY` keeps it as a bodyless pointer; `MATCHING` removes it, leaving no parent at all. |
| S3 | **3**(cs,en) → 2(cs,en) → 1(en) | standard | `B(2) → P(1)` | `B(2) → —` | Root 1 lacks `cs`. `ANY` keeps it as a pointer above the materialized 2; `MATCHING` cuts at 1 and keeps 2. |
| S4 | **33**(cs,en) → 32(en) → 31(cs,en) | standard | `P(32) → B(31)` | `—` | Middle ancestor 32 lacks `cs`. `ANY` replaces it with a pointer and still fetches 31 above it; `MATCHING` cuts at 32 and loses 31 with it. |
| S5 | **44**(cs,en) → 43(en) → 42(en) → 41(cs,en) | standard | `P(43) → P(42) → B(41)` | `—` | 43 and 42 both lack `cs`. `ANY` makes both pointers and still reaches 41; `MATCHING` cuts at 43, the nearest one, losing 42 and 41 too. |
| S6 | **54**(cs,en) → 53(en) → 52(cs,en) → 51(en) | standard | `P(53) → B(52) → P(51)` | `—` | 53 and 51 lack `cs`, 52 does not. `ANY` judges each node on its own; `MATCHING` cuts at 53 and discards the perfectly materializable 52 with it. |
| S7 | **63**(cs,en) → 62(en) → 61(en) | standard | `P(62) → P(61)` | `—` | Every ancestor lacks `cs`. `ANY` returns the complete chain, all pointers; `MATCHING` returns nothing. |
| S8 | non-localized schema, 3 levels | standard | `B → B` | `B → B` | The schema is not localized, so the locale gate never applies: both modes return full bodies. |
| K1 | **72**(cs,en) → 71 deleted | standard | `—` | `—` | Parent 71 no longer exists. The chain ends at the break in both modes; locale is never consulted. |
| K2 | **83**(cs,en) → 82(cs,en) → 81 deleted | standard | `B(82) → —` | `B(82) → —` | 82 materializes, 81 no longer exists. Both modes return 82 with a body and stop at the break. |
| K3 | **93**(cs,en) → 92(en) → 91 deleted | standard | `P(92) → —` | `—` | 92 lacks `cs` and 91 no longer exists. `ANY` makes 92 a pointer, then stops at the break; `MATCHING` cuts at 92 before the break is ever reached. |
| I1 | **3**(cs,en) → 2(cs,en) → 1(en) | no `entityFetch` | PK chain `2 → 1` | identical | No bodies were requested, so nothing can fail to materialize. `MATCHING` has nothing to cut, and both modes return the full PK chain. |
| I2 | **33**(cs,en) → 32(en) → 31(cs,en) | `+ stopAt(distance(1))` | `P(32)` | `—` | `stopAt` limits the walk to 32, which lacks `cs`. `ANY` returns it as a pointer; `MATCHING` removes it, leaving nothing. |
| I4 | **83**(cs,en) → 82(cs,en) → 81 deleted | `+ stopAt(level(2))` | `—` | `—` | The chain is broken, so `level` cannot be counted from a real root. Traversal stops immediately in both modes and no parent is returned. |
| I5 | **3**(cs,en) → 2(cs,en) → 1(en) | `+ dataInLocales(en)` | `B(2) → P(1)` | `B(2) → —` | `dataInLocales` widens what is projected, not what is filtered. 1 still lacks `cs`, so both modes behave exactly as S3. |
| I6 | **3**(cs,en) → 2(cs,en) → 1(en) | no query locale | `B(2) → B(1)` | `B(2) → B(1)` | With no query locale the gate is inactive and nothing is unmaterializable: both modes return the full chain with bodies. |

Two rows carry most of the model. **S4** is the only one that proves the walk no longer stops at the first missing body — if that regresses, S1–S3 still pass. **S6** is where `MATCHING` visibly discards an ancestor it could have returned: the cut happens at the *nearest* unmaterializable ancestor and everything above goes with it, so `MATCHING` is a truncation, not a per-node filter.

## Subsidiary decisions

| Decision | Why | Rejected alternative |
|---|---|---|
| A second enum, `HierarchyParentsBehaviour` | purely additive | renaming `ManagedReferencesBehaviour` to share it — breaks the Java and C# APIs, `GrpcManagedReferencesBehaviour`, and probably the generated GraphQL/REST schema, and still leaves two types because Java cannot alias an enum type |
| Constant named `MATCHING`, not `EXISTING` | for a reference `EXISTING` means "the row is really in the database"; for an ancestor that exact condition behaves identically in **both** modes, so the word would name the one thing the enum does not control | `EXISTING` for symmetry |
| No marker for a `MATCHING` cut | `stopAt(distance(N))` already yields a chain indistinguishable from a real root and nobody objects, because the caller asked | a reportable cut — see Option C |
| Broken chains report up to the break | today the same situation yields nothing at all, or an internal error two levels up; one rule for all three | leaving orphan handling alone |
| `stopAt(level(N))` on a broken chain reports no parents | a fragment has no real root to count levels from; throwing would blame a reader for data only the ingester can fix, producing a cryptic error nobody downstream can act on | counting levels from the fragment top (a lie); throwing a typed exception |
| Exceptions only when the caller's own query is at fault | an unmaterializable body is not a client error; type and primary key never depend on a body | keeping the current throw, which fires on a query that already asked for the body |
| GraphQL union + REST `oneOf` | `EntityDescriptor` declares `primaryKey`, `type`, `scope`, `locales`, `allLocales` **non-null**; a pointer knows only the first two, and in GraphQL the non-null violation bubbles and collapses the whole `parents` list | entity objects carrying nulls; relaxing those fields to nullable, which weakens the contract for every entity rather than just for parents |

## Key technical details

- `ReferencedEntityFetcher#replaceWithSealedEntities` is the core: it currently **short-circuits the
  recursion** when a body is missing. The fix is twofold — substitute a pointer *and keep recursing
  past it*. The ancestor bitmap is already correct; `identifyParents` registers the whole axis
  before any body is fetched.
- Three fetcher answers must stay distinguishable: *did not run* (fall back to the delegate at
  `EntityDecorator:411-413`), *`ANY` pointer*, *`MATCHING` cut*. The distinction is derived from
  `HierarchySerializablePredicate.wasFetched()`, not from a sentinel.
- `EntityClassifierWithParent.CONCEALED_ENTITY` is the sentinel responsible for the silent
  disappearance and its only producer is this defect. It throws from `getType()`/`getPrimaryKey()`,
  which the exception policy forbids, and cannot be repaired in place — a singleton has no type or
  primary key to return. Deprecated, not deleted: it is public `evita_api`.
- `HierarchyIndex#traverseHierarchyToRoot` returns **without visiting anything** when any ancestor is
  unreachable. Its only two callers are reporting paths; hierarchy *filtering* excludes orphans
  structurally via `levelIndex`, so changing it cannot affect the documented orphan invariant in
  `documentation/user/en/use/schema.md`.
- `GrpcEntityReferenceWithParent.parent` is recursively pointer-only, so a body-above-pointer chain
  is not transmissible. The new field must be populated **alongside** the legacy one so old clients
  receive a complete chain degraded to pointers rather than a truncated one.
- `ProxyUtils.createOptionalWrapper` picks a swallowing or rethrowing wrapper from the method
  signature. Tests must assert against the raw `SealedEntity` API or a `throws`-declaring proxy
  method — a swallowing wrapper returns empty for both "never requested" and "unmaterializable" and
  would let a broken implementation pass.

## Verification

**Nothing is verified yet — no code has been written.** The acceptance criterion is the scenario
catalogue in `specifications/1365-hierarchy-content-parents-behaviour/README.md`: eight chain-shape ×
mode cells, seven interaction scenarios, five broken-chain scenarios, extra-result parity, and
regression guards that `ManagedReferenceLocaleFunctionalTest` (the #1343 behaviour) and hierarchy
filtering are untouched.

Two facts *were* verified against the code during analysis and the implementation depends on them:
a materialized ancestor exposes global attributes normally, because `Attributes.java:331` falls back
to the non-localized key; and the gRPC converter carries a body-bearing parent with no
locale-intersection assertion.

One premise is **not** verified: that a chain broken two or more levels up currently throws from
`HierarchyIndex.getParentNodeOrThrowException`. It is a code read. A characterisation test is the
first task of the implementation.

## Consequences & open follow-ups

- **Moved from milestone 2026.2 to 2026.3.** The GraphQL union and REST `oneOf` are breaking changes
  to two public APIs and cannot ship into a release already in testing.
- **Behaviour changes for existing callers even under the default.** A locale-less ancestor stops
  vanishing, and accessing an unmaterializable ancestor stops throwing. Both are the fix, but they
  are observable, hence the `breaking change` label on the issue.
- `ContextMissingException.hierarchyEntityContextMissing()` needs **no rewording** — it was
  misleading only because the unmaterializable case reached it. Its remaining case is a caller who
  genuinely did not request bodies, for which the message is correct.
- **Deliberately left undone:** references under `ManagedReferencesBehaviour.ANY` have the same
  shape of problem — a locale-unmaterializable target yields a bodyless reference. #1343 settled
  `EXISTING` only. Not folded in here to keep the change surgical; it is the obvious next candidate
  if the principle is adopted more broadly.
- **Unverified adjacent risk:** `QueryPlanningContext.fabricateFetchRequest` shares the same
  two-argument `deriveCopyWith`, so other derived nested fetches may inherit the locale as an
  existence predicate too. Only the `parents` extra result was traced and is in scope.
- The in-flight plan, scenario catalogue and external-API option analysis live in
  `specifications/1365-hierarchy-content-parents-behaviour/`. This record flips to `accepted` and
  that folder is deleted when the work lands.

## Timeline

- **2026-08-03** — reported as #1365 with a five-test reproduction; both failure shapes traced to
  `replaceWithSealedEntities` and the `EntityDecorator` delegate fallback
- **2026-08-03** — design agreed; external-API analysis forced the breaking-change conclusion and
  the move to milestone 2026.3; work stopped at the analysis phase
