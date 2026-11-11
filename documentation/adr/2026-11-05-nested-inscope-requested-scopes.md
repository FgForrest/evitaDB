---
title: A nested `inScope` is admitted by the requested scopes and evaluated by the processed ones, and a misplaced scope is refused up front
date: 2026-11-05
updated: 2026-11-11 01:47
status: accepted
kind: fix
issues: [1880, 1883, 1897, 1915]
prs: [1884, 1913, 1914]
areas:
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_query/src/main/java/io/evitadb/api/query
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/behavioral
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference/producer
  - evita_engine/src/main/java/io/evitadb/core/query/QueryPlanner.java
  - evita_engine/src/main/java/io/evitadb/core/collection
supersedes: []
superseded-by: []
relates: [2026-10-08-scope-faithful-planning-and-facet-summary-parity,
  2026-10-20-reference-content-filter-row-by-row, 2026-10-21-one-locale-per-entity-level]
---

# A nested `inScope` is admitted by the requested scopes and evaluated by the processed ones, and a misplaced scope is refused up front

A filter of another entity (`entityHaving`, `groupHaving`) is translated once per scope the enclosing query processes.
This record separates the two questions that translation asks about scopes. *May this `inScope` name scope S at all?*
is answered by the scopes the query **requested**, in every pass. *Does it restrict anything in this pass?* is answered
by the scopes the pass **processes**. Every nested filter now behaves exactly as the same filter at the top level.

The verdict on a scope no longer depends on the data. A request is checked at its entry point, before anything of it
runs, for an `inScope` of a scope it does not request and for a `scope(...)` that nothing would read.

## Why

An application composing its reference constraints once, without knowing the scopes of the query, sent a correct query:

```
filterBy(
  scope(LIVE, ARCHIVED),
  referenceHaving("r", entityHaving(inScope(LIVE, attributeEquals("x", 1))))
)
```

`x` is filterable only in the live scope, which is what `inScope` is for. The query failed in one of two ways, and
which one depended on the data:

- with a target entity type holding no archived global index, with `Scope LIVE used in inScope filter container was
  not requested by scope constraint!`, although the query requests `LIVE`;
- with a target holding one, with a `ClassCastException` of `EntityScope` to `FilterBy`.

### Previous state

`EvitaRequest#getScopes` was one set doing two jobs: the scopes searched and the scopes an `inScope` may name. A nested
pass narrows the first, so the second narrowed with it.

- **The refused query is a regression of `90f3d80d22`** (2026-10-05, merged in #1720). It moved the check of a nested
  filter over scopes without a global index into the nested context, derived with the checked scopes alone. Its
  reasoning was that the check must see what the nested query sees. That holds for the context and not for the filter:
  the real plan translated a clone with the foreign `inScope` removed, the check translated the original. The fixtures
  of #1720 always archived a target, and their witnesses used a nested `scope(LIVE)` or no `inScope`, so no test could
  reach it.
- **The same work read its own rule too widely.** `2026-10-08-scope-faithful-planning-and-facet-summary-parity` plans
  and checks every requested scope "as if it were queried alone". That is right for what a pass *searches*, and wrong
  for what the query *requests*: a pass over one scope is not a query that requested one scope.
- **The `ClassCastException` is older** (`ScopeEnforcer`, `ca897c3a27`, 2024-12-18). It added the enforced scope with
  `addOnCurrentLevel`, which at the root of the visitor writes the result. A surviving `FilterBy` then overwrote it, so
  the scope silently vanished from the copy's filter. A `FilterBy` that lost its only child left the bare `EntityScope`
  as the result, and the cast failed.
- **Removing the foreign `inScope` from the clone gave wrong answers with no error.** At the top level a foreign
  `inScope` stands for every entity of the processed scope. Removed from the clone it stood for nothing. On the data of
  the test class below, `or(inScope(LIVE, x=1), status=ACTIVE)` selected values `{1..6}` at the top level, while nested
  in `entityHaving` it selected owners `1, 2, 3, 4, 6` where all six were due. `not(inScope(LIVE, x=1))` selected value
  `{2}` at the top level and every owner when nested, where owners `1, 2, 3` were due.
- **Whether a wrong scope was refused depended on the rows.** A fetch with an `inScope` of an unrequested scope failed
  only when a row of the owner reached that node. `archiveEntity` and `upsertAndFetchEntity` fetch after they write, so
  a refusal arrived after the mutation, and in `WARMING_UP`, which has no transaction to roll back, the owner stayed
  archived although the call threw.
- **A `scope(...)` in a place nothing reads did nothing, silently.** `scope` is read at the root of a filter, in the
  nested query of `entityHaving` and `groupHaving`, and by the lookup of fetched references. In `hierarchyWithin` or as
  a direct child of `referenceHaving` the query simply meant something else than it said, and two such scopes in one
  hierarchy failed with a `MoreThanSingleResultException` naming neither of them.

## Options considered

### Option A — requested scopes as their own property of the request (chosen)

`EvitaRequest#getRequestedScopes` is inherited by a derived request and united with what it searches. The clone keeps
every `inScope`, and `FilterInScopeTranslator` asks the two questions separately, as it does at the top level.

- **Pros:** one mechanism fixes the refusal, the cast and both wrong results. No new semantics, because the translator
  and `InScopeFormulaPostProcessor` already define what a foreign `inScope` means.
- **Cons:** a second scope set on the request, which every copy constructor has to carry. A candidate built under a
  foreign `inScope` now reaches `IndexSelectionVisitor`, which has to keep it out of the plan (it already does).

### Option B — keep narrowing the request in a nested pass (declined)

- **Pros:** one scope set; the check sees the same scopes as the plan.
- **Cons:** this is the regression.
- **Rejected because:** `inScope(LIVE)` in a pass over the archived scope is then indistinguishable from `inScope(LIVE)`
  in a query of the archived scope, and only the second is an error. It would be worth revisiting only if an `inScope`
  of an unrequested scope stopped being an error.

### Option C — drop the foreign `inScope` in the clone and judge admission by a walk of the original filter (declined)

This was the first implementation of this fix, and it passed its tests.

- **Pros:** translators untouched; smaller diff.
- **Cons:** keeps the wrong `or` and `not` results above.
- **Rejected because:** an adversarial review measured the discrepancy with the top level, and a new false refusal:
  `filterBy(entityLocaleEquals(ENGLISH), inScope(LIVE, entityHaving(inScope(ARCHIVED, status=ACTIVE))))` failed, because
  `NestedSet#createLocaleVisitor` derives its context with the processing scopes. Every further narrowing path would
  have needed its own exception. The visitor it names is now `NestedSet#createNestedVisitor`.

### Option D — translate the enforced clone in the check (declined)

- **Pros:** one line; check and plan see the same filter.
- **Cons:** keeps the wrong `or` and `not` results above.
- **Rejected because:** a clone without the container cannot tell a foreign scope from an unrequested one, so
  `scope(ARCHIVED)` with `entityHaving(inScope(LIVE, x=1))` would pass silently instead of being refused.

### Option E — a boolean-aware enforcer rewriting `or(foreign, …)` and `not(foreign)` (declined)

- **Pros:** keeps the clone free of foreign containers.
- **Cons:** a second implementation of the semantics of the translator.
- **Rejected because:** the query language has no constant-false constraint to rewrite `not(foreign)` to, and adding one
  to express what `InScopeFormulaPostProcessor` already computes buys nothing.

### Option F — restructure the per-scope plan of a nested query (declined)

- **Pros:** one nested query over all scopes would have no foreign pass at all.
- **Cons:** reopens the planning of #1681 and #1686.
- **Rejected because:** the plan is correct, and only admission and the clone were wrong. A target without a global
  index in one scope still needs the per-scope residual check for data independence.

### Option G — refuse only where a filter is translated (declined)

- **Pros:** no walk of the request, no new method in `QueryUtils`.
- **Cons:** a node no row reaches is never translated, so the verdict depends on the data, and on a call that writes
  before it fetches it arrives too late.
- **Rejected because:** a probe measured it: an `archiveEntity` in `WARMING_UP` threw and left the owner archived. It
  would be worth revisiting only if those calls fetched before they wrote.

### Option H — leave a `scope(...)` nothing reads without an effect (declined)

- **Pros:** no query that runs today starts to fail.
- **Cons:** the query keeps meaning something else than it says, and nothing can tell its author.
- **Rejected because:** the project owner chose an error over a silent no-op, as the row filter of `referenceContent`
  already does below its root. It would be worth revisiting for a container that starts reading its own scope.

### #1883 — the scope of fetched bodies

The fetch derives the request of the bodies of a `referenceContent` from the criteria of the referenced entity level,
so `entityHaving(and(scope(LIVE), …))` fetches the bodies in `LIVE` alone, and a `referenceContent` of those bodies
admits `LIVE` alone. Neither the planning check nor the pre-flight knew that: both handed the owners of the nested
`referenceContent` the scopes of the query. The issue offered two fixes, and each one alone leaves a hole.

#### Option I — resolve the scope of the bodies in the planning check only (declined)

- **Pros:** smallest change; the check then plans the nested filter in the scopes the fetch plans it in.
- **Rejected because:** the direct calls plan no check, so their verdict stays dependent on the rows. Measured with the
  fix in the check and without it in the pre-flight: `getEntity` answered the query of the issue for an owner without
  rows, and `archiveEntity` of an owner no row of which reaches the filter archived it and threw nothing - the reason
  Option G lost.

#### Option J — hand the scope of the bodies down in the pre-flight only (declined)

- **Pros:** closes every entry point, the direct calls included.
- **Rejected because:** the pre-flight judges `inScope` admission only. The planning check also plans the nested filter
  against the schemas, in the scopes it is handed, so it kept refusing a correct query: a filter of the tags of
  `LIVE`-only bodies reading an attribute filterable in `LIVE` alone failed with `AttributeNotFilterableException` for
  `ARCHIVED`, a scope no body is fetched in. The issue had assumed no correct query was affected.

#### Option K — both, from one rule (chosen)

One rule in `QueryUtils` decides the scope of the bodies, and the fetch and both checks call it. The pre-flight hands
that scope to everything below the fetch of the bodies, and `ReferenceContentTranslator` checks the owners of a nested
`referenceContent` in it. `HierarchyContentTranslator` checks the `stopAt(node(...))` bound of a `hierarchyContent`
below the bodies in it too, through the same walk of the requirement chain
(`ReferenceContentTranslator#resolveOwnerScope`): handing the scope down in the pre-flight alone made `getEntity`
answer such a bound while a planned query refused it. #1883 read that scope from the criteria of the referenced
entity level (`QueryUtils#extractReferenceBodyFilter`), and the fetch found it there a second time, through the
lookup of the `EvitaRequest` copy constructor. #1915 changed what the rule says and removed the second lookup, see
below.

### #1915 — the scope of the group bodies, and of bodies below `or`, `not` and `inScope`

#1883 left two defects in the rule, both measured on a data set of their own (`nestedInScopeGroupBodies` below), the
same through a query and `getEntity`:

- **The group bodies took the referenced entity level.** `entityHaving(scope(LIVE))` beside an `entityGroupFetch` over
  both scopes withheld the body of an archived group, its mirror the body of a live group, and
  `groupHaving(and(scope(ARCHIVED), …))` in a live query fetched none of the groups it selected. Each row kept its group
  key and lost the body. The locale already treated the group as a level of its own
  (`2026-10-21-one-locale-per-entity-level`).
- **An `or`, `not` or `inScope` was read as an `and`.** `QueryUtils#extractReferenceBodyFilter` collected every
  `entityHaving` of the filter and flattened their children into one filter of the bodies, so their scope, locale and
  price criteria applied to every body. `or(entityHaving(and(scope(ARCHIVED), …)), attributeEquals("rank", 1))` withheld
  the body of the live value its second branch admits, `not(entityHaving(and(scope(ARCHIVED), …)))` the bodies of
  every admitted row, and `or` of two `entityHaving` naming different scopes threw `MoreThanSingleResultException`
  naming neither, for an owner with rows only. A locale did the same: `or(entityHaving(entityLocaleEquals(ENGLISH)),
  …)` withheld the body of a value held in German only, and `or` of two `entityHaving` naming different locales was
  refused as two locales of one level. It is the defect class of #1897 and #1917: a constraint read through a container
  that changes its meaning.

#### Option L — keep the group bodies at the referenced entity level (declined)

- **Pros:** one scope for both kinds of bodies; the fetch as it was.
- **Rejected because:** it withholds the bodies above. Nothing in the documentation said the scope of `entityHaving`
  applies to the group, and `groupHaving` documents a `scope` of its own.

#### Option M — each level reads the scope found anywhere in its containers (declined)

The first fix of #1915: the group bodies read `groupHaving`, the entity bodies `entityHaving`, both looked up through
every `or`, `not` and `inScope`, as #1883 had done for the entity level.

- **Pros:** small; the lookup of #1883 applied to the group.
- **Rejected because:** it moved the second defect to the group level, where the state before had answered. Measured:
  `or(groupHaving(and(scope(ARCHIVED), …)), attributeEquals("rank", 1))` and `not(groupHaving(and(scope(ARCHIVED),
  …)))` lost the bodies of live groups, `or(groupHaving(scope(LIVE)), entityHaving(…))` the body of an archived group,
  and `or` of two scoped `groupHaving` threw for an owner with rows only.

#### Option N — the union of the scopes the containers name (declined)

- **Pros:** answers `or` of two scoped containers.
- **Rejected because:** a way to match that names no scope admits rows whose bodies live in the scopes of the owners.
  Under `or(groupHaving(and(scope(ARCHIVED), …)), attributeEquals("rank", 1))` the union is the archived scope, and the
  live group of the row the second branch admits loses its body.

#### Option O — refuse an `or` or `not` of scoped containers (declined)

- **Pros:** one scope per level, nothing to unite.
- **Rejected because:** a `scope` inside an `entityHaving` or `groupHaving` below `or` is valid and selects the scope
  of that container alone - #1917, which refuses a `scope` directly below `or`, keeps it so. The row filter answers
  such a filter, and refusing it for the bodies would refuse a correct query.

#### Option P — follow the scope inside a reference-level `inScope` (declined)

- **Pros:** reads `inScope(LIVE, entityHaving(and(scope(ARCHIVED), …)))` as the archived scope, as an `and` would.
- **Rejected because:** `inScope(S, X)` admits every owner outside `S` whatever `X` says. Measured on the archived
  owner: its rows into live values lost their bodies, where with no filter they have them.

#### Option Q — follow `and` only, otherwise the scopes of every way to match (chosen)

The rule of the project owner, for both levels: the scope of an `entityHaving` or `groupHaving`, and its criteria,
apply to the bodies only when the container is reachable from the root of the reference filter through `and` alone.
Otherwise the bodies are fetched in the union of the scopes of every way a row can match: the scope each branch names,
and the scopes of the owners for each branch or `not` that names none. The locale and the price criteria follow the
same extraction, so they come only from containers reachable through `and`, otherwise from the reference level and
then the owners. Two scopes side by side in one container still fail, as at the root.

## Decision

**Chosen: Option A, with the checks Options G and H decline to do without.** Option A is the only option under which a
nested filter and the same filter at the top level are the same code path, so they cannot disagree. Option C would win
only if a foreign `inScope` were to stop meaning "restricts nothing" at the top level.

The refusal of an inert `scope(...)` and the refusal before a mutation are deliberate breaking changes. The upgrade note
is on #1880.

A wrongly composed query stays refused. `scope(ARCHIVED)` with `entityHaving(inScope(LIVE, x=1))` requests no live
scope, and the composer of the query has to leave the container out.

**#1883: Option K.** Neither half of it fixes the issue alone, and the half the issue did not ask for fixed a refusal of
a correct query. The refusal it adds is not a breaking change: the query contradicts itself and already failed for any
owner with rows.

**#1915: Option Q**, by the project owner's rule. Under it a body is fetched in the scopes of every way its row can be
admitted at the level of the body - the scopes its containers name, the scopes of the owners where they name none -
and the verdict on a filter below the bodies no longer depends on the rows. Two uses that worked are refused now
(Consequences); the project owner decided against a `breaking change` label and an upgrade note for the first one.

## Key technical details

- `EvitaRequest#getRequestedScopes`: NULL in the field means the same as `getScopes()`. The five-argument
  `deriveCopyWith` unites the inherited set with the searched scopes and reads no `scope(...)` of the filter. The
  six-argument one takes the set from a caller that honours such a scope. Reading it in the five-argument overload,
  which every derived request goes through, changed the locale a top-level `hierarchyWithin` resolved in.
- `EvitaRequest.ScopeEnforcer`: the root of the copy is always a `FilterBy` holding the enforced scope once. Every
  `inScope` is retained, and so is a `scope(...)` below a `SeparateEntityScopeContainer`, which targets another entity.
- `QueryUtils#assertFilterScopesApplicable` is the one walk behind every verdict. `QueryPlanner#planQuery` and
  `EntityCollection#assertScopeUsageValid` run it on the filter section and on every filter of the require section - of
  a `referenceContent`, a hierarchy `node`, a facet summary - at any depth, before anything of the request runs. A new
  client-facing entry point has to call it.
- **The one exception, #1897:** a `scope(...)` below `not` is refused by `QueryUtils#assertNoNegatedScope`, a walk of
  the same filters and of the `entityHaving` of a `segment`, which needs no scopes. Both entry points run it **before**
  they resolve the requested scopes, because the resolution (`EvitaRequest#getScopes`, and the lookups of a level's
  own scope inside the walk above) reads a scope through the negation as a positive one, and fails on two of them with
  a `MoreThanSingleResultException` that names neither. A new entry point calls it before `getRequestedScopes()`. A
  negated scope a container would not apply at all gets the "has no effect" diagnostic of the walk above, which names
  the more fundamental mistake. The negation walk raises it itself, because the walk above does not reach the order
  section, where `segment(entityHaving(referenceHaving("r", not(scope(LIVE)))))` would otherwise reach `NotTranslator`.
- Only `entityHaving` and `groupHaving` read their own `scope(...)`, in `HavingTranslatorHelper#planNestedQuery`. Below
  any other separate container the walk refuses it. A level without its own scope inherits what encloses it.
- `HavingTranslatorHelper#planNestedQuery` runs the walk again before `computeOnlyOnce`, because that memo reuses the
  plan of an equal filter, which a sibling may have admitted more widely.
- `ReferenceRowFilterCompiler#compile` admits the enclosing requested scopes, the scopes the request searches and the
  `scope(...)` in the root of its filter, and `NestedSet` hands that set to the nested query. A scope of a deeper entity
  level admits nothing, it only widens the lookup of the rows: `ReferencedEntityFetcher#gatherSearchedScopes` unites
  the scopes of every level, so `entityHaving` and `groupHaving` may each name their own. The compiler runs the walk
  too, for requests the engine derives after the entry point.
- `FilteringFormulaPredicate` searches the scopes named by the `scope(...)` in the root of its filter, and the scopes of
  the enclosing query when it names none. This corrects the first version of this record, where that `scope(...)`
  selected nothing in the group filter of a `facetGroups*` requirement and of a facet summary and decided only what an
  `inScope` of that filter may name, an inert scope the walk of `QueryUtils#assertFilterScopesApplicable` neither
  applied nor refused.
- **Invariant:** `IndexSelectionVisitor#coversFewerScopesThanQuery` must keep a candidate built under a foreign
  `inScope` out of a plan. Retaining the containers relies on it.
- **Looks like a bug and is not:** the check of a nested filter searches fewer scopes than it admits. A retained
  `inScope` outside the checked scopes restricts nothing, while its children are not checked in that pass.
- **Looks like a bug and is not:** `entityHaving(referenceHaving("tags", entityHaving(inScope(LIVE, y=1))))` over both
  scopes matches no archived value through a live tag, because the archived pass searches archived tags only.
- Performance: no benchmark. The clone allocates as before. The walk runs once per request, per nested plan and per
  compiled reference filter, never per row, and allocates nothing unless the filter holds an `inScope`. Only queries
  with a nested foreign `inScope` take a different path. The summary of the body scopes is an `int`; the fetch and
  the group `EXISTING` gate each resolve it once per fetched reference batch, and the pre-flight only after the
  allocation-free search for an `inScope` below the bodies. It allocates only the lookup of the own scope of each
  container of the level. The encoding holds up to 15 scopes.
- **The scope of fetched bodies (#1883, #1915).** `QueryUtils#resolveReferenceBodyScopes` summarizes one body level -
  the referenced entity below `entityFetch`, the group below `entityGroupFetch` - in one walk of the reference filter:
  the scopes a matching row's body can be in (`N`), and whether some way to match names none (`D`). A container of the
  level with its own scope gives `({s}, false)`; one without, the other level and any leaf give `(∅, true)`; `or`
  unites; `and` intersects, a side with `D` letting the other side's scopes through; `not` gives the scopes named below
  it with `D`; a reference-level `inScope` its child's `N` with `D`. `QueryUtils#materializeReferenceBodyScopes` turns
  the summary into the scopes of the bodies: plain inheritance of what the owners search and admit when `N` is empty,
  or when `D` holds and the owners already search `N`; otherwise searched `N ∪ (D ? owners' searched : ∅)` and
  admitted `N ∪ (D ? owners' admitted : ∅)`, which `EvitaRequest#deriveCopyWithScopes` sets on the request of the
  bodies. The fetch, the group `EXISTING` gate, the pre-flight and the planning checks call these two and nothing else.
  The `scope(...)` in the root of the filter widens only the lookup of the references and is not handed down.
- **The criteria of the bodies follow `and` only.** `QueryUtils#extractReferenceBodyFilter` collects the containers of
  the level reachable from the root through `and` alone, so the locale (`QueryUtils#resolveReferenceBodyLocale` and
  every caller: the fetch, the `EXISTING` gates, the ordering, planning, GraphQL and the gRPC client) and the price
  criteria of the bodies come from them, otherwise from the reference level and then the owners (the price part read
  from the code, not measured). The fetch strips the scopes of the level from that filter, because a
  `referenceContent` of the bodies without a filter keeps it in its request, whose copy constructor would look the
  scope up again; a scope below a nested separate container stays.
- **Looks like a bug and is not:** a followed scope equal to the owners' searched scopes is still set explicitly. It
  replaces the admitted scopes too, and the owners may admit more than they search.
- **Looks like a bug and is not:** the searched and the admitted set of an explicit pair are equal for every request
  today. With two scopes and owners searching at least one, `D` adds the scopes of the owners to both sides and
  covers the difference, and without `D` both are `N`. The pair keeps admission from following the searched set
  silently once that changes; no test can tell them apart now.
- **Looks like a bug and is not:** `and(entityHaving(and(scope(LIVE), …)), entityHaving(and(scope(ARCHIVED), …)))`
  fetches the bodies in both named scopes. No row matches, so any superset is sound, and an empty `N` would mean
  inheritance.
- **Every planning check below fetched bodies has to use their scope.** The pre-flight judges every filter below the
  fetch of the bodies by it, so a check that plans one of those filters in the scopes of the query refuses what the
  fetch and `getEntity` answer. `ReferenceContentTranslator` and `HierarchyContentTranslator#verifyStopAtNode` do so; a
  new check of a requirement nested in an `entityFetch` has to call `ReferenceContentTranslator#resolveOwnerScope`.
- `ReferencedEntityFetcher#verifyReferenceContentConstraints` takes the requested scopes of the owners explicitly; NULL
  keeps the five-argument `deriveCopyWith`, which unites the scopes of the query with the searched ones. Its shortcut
  reusing the context of the query applies only then, so a self-referencing type cannot skip a scope of its bodies.

## Verification

- **The reported queries.** `NestedInScopeFilterFunctionalTest` (102 tests, 0 failures) runs its cases against a target
  family with and without archived data, through a query, `getEntity` and `enrichEntity`.
  `$ScopedTargets#shouldFetchScopedReferencesWhenBothScopesAreRequested` and
  `#shouldFilterOwnersByScopedReferencesWhenBothScopesAreRequested` hold the two shapes reported in #1880.
- **Parity with the top level.** `$BooleanParity` asserts the values above for `or` and `not`.
- **A wrong scope stays refused.** `$RefusedFilters#shouldRefuseUnrequestedScopeSoleChildWhenScopeIsUnrequested` and
  `#shouldRefuseUnrequestedScopeDuringGetWhenScopeIsUnrequested`; whatever the rows,
  `$AdmissionWithoutRows#shouldRefuseUnrequestedScopeWhenGotOwnerLivesInAnotherScope`.
- **Refused before the write**, in a catalog in `WARMING_UP`, where nothing would undo it. `$RefusalBeforeMutation`
  holds the archive, the upsert and the hierarchy bound. The last one failed with `expected: <1> but was: <2>` before
  the check covered every filter of the require section.
- **The refused `scope`.** `QueryUtilsTest$RequestedFilterScopes#shouldRefuseScopeWhenContainerDoesNotApplyIt` (12
  containers), `#shouldAcceptScopeWhenLevelAppliesIt` (10 levels) and `$QueryFilterScopes` for the require section.
- **The tests can fail.** Eleven counterfactuals of the implementation each ran the 305 tests of the class and its
  neighbours and failed the listed count: the check admitting the checked scopes only (19), the old root
  `addOnCurrentLevel` (5), the translator reading the searched scopes (41), admission by the enclosing scopes only (2)
  or the processing scopes only (10), an ignored nested own scope (2), scopes replaced at every depth (3), no admission
  walk in the compiler (4), a return at an own-scope container (3), foreign containers dropped from the clone (17), and
  the five-argument copy admitting the searched scopes only (4). They ran before the last two review rounds, whose fixes
  each went from red to green, the last 12 failing tests included.
- **Full suite**, `mvn -B -pl evita_test/evita_functional_tests -am test -P unitAndFunctional`, 18:58 min: bitmap 19,215
  twice with 0 failures and 1 skip, functional 33,057 with 0 failures, 71 skipped and 1 error. The error,
  `CatalogGraphQLSchemaSubscriptionsFunctionalTest#shouldAcceptCurrentCatalogIdAndReturnItInCatalogSchemaChanges`, is
  a `SessionProtocolNegotiationException` in a class this change does not touch. The class passes alone, 13 of 13.
- **Whole reactor.** A clean `test-compile` under `-P unitAndFunctional,full` built the documentation and long-running
  test modules, and the performance test module compiled online. The javadoc build reports no error.
- **#1897, the negated scope.** `NegatedScopeFunctionalTest` (24 tests) runs every filter of the issue on its fixture,
  the `facetGroups*` and facet summary group filters, a fetch by `getEntity` and two `segment` filters, and pins the
  unchanged answers (`[4]`, `[20]`, `[3]`); `QueryUtilsTest$NegatedScopes` (25) covers the walk, and
  `CatalogGraphQLQueryEntityQueryFunctionalTest#shouldRefuseScopeWhenNegated` the GraphQL API. Before the fix, 18 of
  the then 22 functional tests failed: twelve internal errors, two `MoreThanSingleResultException`s, three silent
  results, and the message of the row filter compiler for a `referenceContent` filter.
  With the refusal disabled, 31 tests failed across `NegatedScopeFunctionalTest`, `QueryUtilsTest` and
  `ReferenceContentRowFilterFunctionalTest`, and the GraphQL test got the old internal error. With the walk run after
  the scopes are resolved, exactly the 7 tests naming two scopes failed with a `MoreThanSingleResultException`. The
  `segment` test failed with the internal error before the walk covered the order section. An adversarial review
  found the negated scope of a `referenceHaving` inside a `segment` still reaching it, because the walk left such a
  scope to the check of the filter section; `$OrderSection#shouldRefuseScopeAsInertWhenNegatedInSegmentReferenceFilter`
  failed with the internal error until the walk raised the diagnostic itself. Full suite on the final code, 15:28 min:
  33,305 tests with 0 failures and 71 skipped; 183 release upgrade tests errored on fixtures the run skipped
  (`-DreleaseFixtures.skip=true`), every one with "Fixture ... is not on the class path", and their 9 classes then
  passed in a run that generated the fixtures, 217 tests with 0 failures.
- **#1883, the scope of fetched bodies.** `NestedInScopeFilterFunctionalTest$FetchedBodyScopes` runs the query of the
  issue for an owner with rows and one without, through a query and `getEntity`, the false refusal of a filter
  filterable in the scope of the bodies alone, and a query admitting the scope of the bodies it does not request;
  `$RefusalBeforeMutation#shouldRefuseScopeExcludedByBodyScopeBeforeEntityChangesScopeWhenArchiving` archives an owner
  no row of which reaches the filter. On a data set of its own, a hierarchy of three nodes, the class also holds the
  `stopAt(node(...))` bound below the bodies, with an `inScope` of their scope and with an attribute filterable in it
  alone, and a self-reference below bodies of the queried type. `QueryUtilsTest$QueryFilterScopes` holds eight rows for
  the walk, the group shape and a sibling `referenceContent` among them, and `$ReferenceBodyScope` six for the rule.
  Before the fix the issue's query and `getEntity` accepted owner 7, and the filter of the live-only attribute failed
  with `AttributeNotFilterableException` for `ARCHIVED`. The classes run 114 and 136 tests with 0 failures.
- **#1883, the tests can fail.** Without the scope handed down by the pre-flight, 4 rows of `QueryUtilsTest` and 5
  functional tests fail (the `getEntity` refusals, the archive and the admitted scope); without the scope in
  `ReferenceContentTranslator`, 4 (the attribute filterable in the live scope alone and the admitted scope, each in both
  families); without it in `verifyStopAtNode`, both hierarchy bounds, one with the `inScope` refused and one with
  `AttributeNotFilterableException` for `ARCHIVED`. Dropping `requestedScopes == null` from the shortcut of
  `verifyReferenceContentConstraints` fails the self-reference, and handing the scope of the bodies to the filter of
  their own `referenceContent` fails `shouldRefuseScopeInFilterOfFetchedReferenceWhenOnlyItsBodiesAreFetchedInIt`.
- **#1883, review.** An adversarial review by Codex (GPT-6.1 Sol) returned GO with one low finding, an `Optional` the
  pre-flight allocated per filtered `referenceContent`, now gone. The code-quality pass found the split of the
  hierarchy bound (Option K above); it is fixed with its test.
- **#1883, full suite**, `-P unitAndFunctional -DreleaseFixtures.skip=true` with fixed parallelism 8, 16:36 min, after
  the review fixes: 33,282 functional tests, 71 skipped, 185 not passing and none of them in a query, a reference or a
  fetch. 183 are the release upgrade tests, each naming the fixture the skipped generation did not write; 2 timed out in
  `EvitaServerTest`, which passes alone, 14 of 14.
- **#1915, the scope and the criteria of the bodies.** `NestedInScopeFilterFunctionalTest$GroupBodyScopes` (34 tests)
  runs on a data set of its own, `nestedInScopeGroupBodies`: rows into live and archived values and groups, an owner
  without rows and an archived one, a `rank` reference attribute, values and groups localized in one language each.
  Every case runs through a planned query and `getEntity`, for an owner with rows and one without: the group bodies
  following `groupHaving`, `or` and `not` with and without a scope in the branch, `or` of two scoped containers under
  `ANY` and `EXISTING`, a reference-level `inScope`, a nested `inScope` below the united bodies admitted and refused,
  two followed containers naming one scope above a `referenceContent` without a filter, and the locale shapes with
  `dataInLocales`. `QueryUtilsTest$ReferenceBodyScope` (44) holds a row per row of the algebra, the materialization
  against narrow and wide owners and the extraction through `and` only, replacing the six rows of #1883;
  `$QueryFilterScopes` (27) the pre-flight. Before the production change, 13 of the new functional invocations
  failed: bodies lost below `or`, `not` and `inScope`, a `MoreThanSingleResultException` for `or` of two scoped
  containers, and a branch locale withholding bodies. The 13 classes around the change run 741 tests with 0 failures
  and the one skip `@Disabled` before it.
- **#1915, the tests can fail.** Each counterfactual ran `NestedInScopeFilterFunctionalTest` and `QueryUtilsTest`
  (with `EvitaRequestTest` for the fetch without the explicit pair), before the last review and quality rounds
  reshaped the tests:
  `or` read as `and` failed 7 tests, the conjunction as a union 2, `not` read as `and` 6, a reference-level `inScope`
  without the default 5, no fallback on a contradiction 2, the old flattening extraction 12, the fetch without the
  explicit pair 12, the group `EXISTING` gate on the scopes of the owners 1, the pre-flight always inheriting 18,
  planning always inheriting 9, and a followed scope inherited when the owners search it 23. Keeping the scope of the
  level in the filter of the bodies failed the nested `referenceContent` without a filter with
  `MoreThanSingleResultException`; until that test existed, only a test calling the private method by reflection
  could see it. Handing the planning checks the searched set for the admitted one changes nothing observable, see
  Key technical details.
- **#1915, review.** The design was reviewed by an advisor and by Codex (GPT-6.1 Sol), whose six findings shaped the
  materialization (never by set equality), the planning chain and the locale. The adversarial review of the change
  returned GO WITH CHANGES with five low findings - wording of this record and of the user documentation, the
  15-scope limit of the summary, and the label bodies the nested test did not inspect - all addressed. The
  code-quality pass found no defect; it added a literal test of the materialization, which fails with 10 rows of the
  matrix when the inheritance reads the admitted set instead of the searched one.
- **#1915, full suite** on the tree merged with `dev` (#1897), `-P unitAndFunctional` with fixed parallelism 8 and the
  release fixtures generated, 17:02 min: bitmap 19,215 tests with 0 failures and 1 skip, functional 33,447 with 0
  failures, 71 skipped and 1 error - a two-minute timeout in
  `CatalogGraphQLDataSubscriptionsFunctionalTest#shouldReceiveCatalogCaptureWithBodyUntyped`, whose class passes alone,
  13 of 13.
- **#1915, measured and left alone.** A named localized attribute of bodies with no locale source is refused by a
  planned query and answered by `getEntity`, alike for a locale below `or` and a filter without any `entityHaving`
  (`$GroupBodyScopes#shouldRefusePlannedLocalizedBodyAttributesWhenFetchLocaleIsMissing`,
  `#shouldKeepDirectFetchParityWhenNeitherShapeSuppliesBodyLocale`). The price criteria of the bodies are read from
  the code only: the data set has no prices.

## Consequences & open follow-ups

- **Visible to clients, both ways.** A query over both scopes with a nested `inScope` now works where it failed. `or`
  and `not` around a nested `inScope` now return what they return at the top level.
- **Breaking, now refused whatever the data.** An `inScope` of an unrequested scope in a filter or a fetched reference
  filter, including `getEntity`, `archiveEntity` and the other direct calls, and a `scope(...)` below a separate
  container other than `entityHaving` and `groupHaving`.
- **A composer that adds `inScope(LIVE, …)` unconditionally now fails on archived-only fetches.** That is the
  composer's defect, and the error says so.
- **Closed by #1883, both ways.** A `referenceContent` of bodies whose `entityHaving` names a narrower scope used to be
  refused for an owner with rows and answered for one without; it is refused for every owner, before anything runs. A
  query not requesting the scope those bodies are fetched in used to be refused although the fetch admitted it; it is
  answered. The root `scope(...)` of a filter is still not handed down, as
  `QueryUtilsTest$QueryFilterScopes#shouldRefuseScopeWhenOnlyEnclosingFetchedReferenceFilterNamesIt` pins.
- **Closed by #1915.** A group body is fetched by the `groupHaving` of the filter, whatever `entityHaving` names: in
  the scope of one reachable through `and`, otherwise in the scopes of every way to match, which for a filter naming
  no group scope are the scopes of the owners. The same holds for the referenced entities and `entityHaving`, so a
  row admitted by a branch naming no scope keeps its body, and `or` of two `entityHaving` or `groupHaving` naming
  different scopes is answered for every owner, where it threw for an owner with rows. `or` of two of them naming
  different locales is no longer refused as two locales of one level.
- **Refused now, worked before (#1915):**
  - an `inScope` below `entityGroupFetch` naming a scope the `groupHaving` of the filter excludes - the group bodies
    used to inherit the scopes of the owners;
  - a named localized attribute of bodies whose locale was named only below `or`, `not` or `inScope`, with no other
    locale source, by a planned query (`EntityLocaleMissingException`, the check of the locale record). It used to be
    answered in that locale, dropping every body without data in it. `dataInLocales` in the fetch of the bodies
    brings every body back, with the attribute where the body has it.
- **A Java driver and the server must agree on the body locale.** The driver reconstructs the predicates of the
  bodies by `QueryUtils#resolveReferenceBodyLocale` (`2026-10-21-one-locale-per-entity-level`), so a driver older
  than #1915 disagrees with this server for a locale below `or`, `not` or `inScope`.
- **Open, left by #1915:**
  - `getEntity` plans no fetch, so it answers the named localized attribute above, which a planned query refuses. It
    does so with or without a locale below `or` - also for a filter with no `entityHaving` at all - so the split is
    older than this change.
  - #1920: an `inScope` in the require section narrows the scopes the planning check of a nested `referenceContent`
    inherits, while the pre-flight and the fetch do not, so the planned query refuses a filter they admit.
  - The `EXISTING` gate of the referenced entities searches every scope the filter names at any level
    (`ReferencedEntityFetcher#gatherSearchedScopes`), the bodies only the scopes of their level, so a row the widened
    lookup admits can lack its body.
  - The price lookups of the `EvitaRequest` copy constructor do not stop at a nested separate container, so a price
    criterion of a deeper level can reach the price context of the outer bodies.
  - Two scopes side by side in one container (`groupHaving(scope(LIVE), scope(ARCHIVED))`) fail as before, as the same
    pair does at the root, and for the bodies only when a row reaches the fetch or an `inScope` sits below them. Out of
    scope by the project owner's decision.
- **Known and left alone**, each found by the final review and each harmless to stored data:
  - `enrichEntity` takes no scope, so its request searches the live scope, and an `inScope` of the archived scope is
    refused on an archived entity. `getEntity` with scopes admits it. Letting the entity's scope admit it would need a
    request derived from the entity, and it changes the lookup of its bodies. How the previous code answered this call
    was not measured.
  - `upsertAndFetchEntity` with automatic evolution creates a missing collection before the check, so a refused call can
    leave an empty collection behind. The entity is not written.
  - The GraphQL selections of an unknown entity build their requirements only for a found entity, so a missing one
    returns null without a verdict.
  - `referenceContent` takes a root `scope(...)` only as a direct child of its `filterBy`. Below `and` the check accepts
    it and the row filter compiler refuses it, on a write path after the write.
  - `segment(entityHaving(...))` unwraps the container and translates its children in the enclosing context, and
    `assertFilterScopesApplicable` does not walk the order section, so a `scope(...)` there has no effect and is not
    refused. A negated one is refused since #1897 by `assertNoNegatedScope`, which does walk it - it used to end in the
    internal error of `NotTranslator`.
  - A facet summary filter over the queried entity type itself is planned in the enclosing context, so its own
    `scope(...)` does not narrow what an `inScope` of that filter may name
    (`FilteringFormulaPredicate#createTargetQueryContext`). The searched scopes follow the `scope(...)` of the filter.
- **Breaking, #1897.** A `scope(...)` below `not`, at any depth of its entity level, is refused. It used to end in a
  `GenericEvitaInternalError` from `NotTranslator`, or, beside a sibling, to be applied as if it stood outside the
  `not` - `not(and(scope(ARCHIVED), code=a))` returned archived items. Giving it a meaning (the complement of the named
  scopes) was declined: a query's scopes would then depend on a negation, and naming the scopes says the same.
- **Open, left by #1897.** `or(scope(ARCHIVED), code=a)` applies the scope to the whole filter, not to its branch. The
  GraphQL API wraps sibling constraints in `and`, so `scope` must stay legal in `and`; `or` has no such need, and
  whether to refuse it there was not decided.
- **Backport: none, by the project owner's decision.** The `ClassCastException` stays on `release_2026-2`, where it
  depends on the data. The refusal never shipped, because that branch has no residual check.

## Related work

- `2026-10-08-scope-faithful-planning-and-facet-summary-parity` — this record corrects how its "as if queried alone" is
  to be read; its residual check is what exposed the conflation.
- `2026-10-20-reference-content-filter-row-by-row` — the row filter compiler and `NestedSet`, whose locale visitor and
  `getEntity` path shaped the admission walk; the refusal of `scope` below the root of its filter is the precedent for
  refusing an inert `scope` everywhere.
- `2026-10-21-one-locale-per-entity-level` — the body chain of `QueryUtils#resolveReferenceBodyLocale` reads the
  extraction of the body level, which since #1915 follows `and` only. The scope of the bodies uses the summary of
  every way to match instead, so a locale below `or` gives the bodies none, while a scope below `or` adds its scope.

## Timeline

- **2024-12-18** — `ScopeEnforcer` introduced (`ca897c3a27`).
- **2026-10-05** — the residual check moved into the nested context (`90f3d80d22`), merged on 2026-10-08 in #1720.
- **2026-11-04** — #1880 analysed; the first implementation (Option C) replaced by Option A after review; the pre-flight
  and the refusal of an inert `scope` added after a second review, every filter of the require section after a third;
  #1883 split off; PR #1884 opened.
- **2026-11-05** — PR #1884 merged.
- **2026-11-10** — #1897: a `scope(...)` below `not` refused before the scopes are resolved; PR #1914 opened.
- **2026-11-10** — #1883 fixed: the scope of fetched bodies resolved by one rule in the pre-flight, the planning check
  and the fetch. The red run found a false refusal of a correct query the issue had not expected.
- **2026-11-10** — #1915 split off the review of #1883 and folded into PR #1913. The first fix (Option M) measured
  worse at the group level; the project owner set the rule of Option Q, reviewed by an advisor and an adversarial
  Codex review of the design before it was implemented.
