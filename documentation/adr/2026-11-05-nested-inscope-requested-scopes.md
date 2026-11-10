---
title: A nested `inScope` is admitted by the requested scopes and evaluated by the processed ones, and a misplaced scope is refused up front
date: 2026-11-05
updated: 2026-11-10 14:11
status: accepted
kind: fix
issues: [1880, 1883]
prs: [1884]
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
relates: [2026-10-08-scope-faithful-planning-and-facet-summary-parity, 2026-10-20-reference-content-filter-row-by-row]
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

`QueryUtils#extractReferenceBodyFilter` is the rule the fetch derives the request of the bodies by (moved from
`ReferencedEntityFetcher`), and `QueryUtils#findReferenceBodyScope` reads the scope from it. The pre-flight hands that
scope to everything below the fetch of the bodies, and `ReferenceContentTranslator` checks the owners of a nested
`referenceContent` in it, as both their searched and their requested scopes. `HierarchyContentTranslator` checks the
`stopAt(node(...))` bound of a `hierarchyContent` below the bodies in it too, through the same walk of the requirement
chain (`ReferenceContentTranslator#resolveOwnerScope`): handing the scope down in the pre-flight alone made `getEntity`
answer such a bound while a planned query refused it. The fetch derives the request of the bodies
from the same body filter, and the `EvitaRequest` copy constructor finds the scope in it by the same lookup. Two choices
are still made in two places: that the group bodies take the referenced entity level (`unwrapFilterBy` and
`findReferenceBodyScope`), and the lookup of the scope itself (`EvitaRequest` and `findReferenceBodyScope`). A change to
either has to change both.

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
  with a nested foreign `inScope` take a different path.
- **The scope of fetched bodies (#1883).** The bodies of a `referenceContent` are fetched in the `scope(...)` among the
  children of its `entityHaving` containers (`QueryUtils#findReferenceBodyScope`), which *replaces* what encloses them
  for both searching and admission. A body level naming none inherits both. The `scope(...)` in the root of the filter
  widens only the lookup of the references and is not handed down.
- **Looks like a bug and is not:** the group bodies take the scope of the referenced entity level too, never the one of
  `groupHaving` - `ReferencedEntityFetcher#unwrapFilterBy` derives both requests from the referenced entity level, and
  the checks mirror it so that the verdict cannot depend on the rows. The group shape is pinned on the pre-flight
  alone (`QueryUtilsTest$QueryFilterScopes`); the fixture has no group type with references, so no functional test
  fetches below an `entityGroupFetch`. Whether the group bodies should follow
  `groupHaving` instead is a question about the fetch, and changing `findReferenceBodyScope` alone would recreate
  #1883 under `entityGroupFetch`.
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
- **Open: the group bodies follow the referenced entity level**, see Key technical details. Read from the code and not
  measured: `EntityCollection#getEntityById` returns no body outside the scopes of its request, so
  `entityHaving(scope(LIVE))` beside an `entityGroupFetch` would fetch no archived group body, and a
  `groupHaving(scope(...))` naming a scope the owners are not fetched in would fetch none of the groups it selects.
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
  - `segment(entityHaving(...))` unwraps the container and translates its children in the enclosing context, and the
    order section is not walked, so a `scope(...)` there has no effect and is not refused.
  - A facet summary filter over the queried entity type itself is planned in the enclosing context, so its own
    `scope(...)` does not narrow what an `inScope` of that filter may name
    (`FilteringFormulaPredicate#createTargetQueryContext`). The searched scopes follow the `scope(...)` of the filter.
- **Backport: none, by the project owner's decision.** The `ClassCastException` stays on `release_2026-2`, where it
  depends on the data. The refusal never shipped, because that branch has no residual check.

## Related work

- `2026-10-08-scope-faithful-planning-and-facet-summary-parity` — this record corrects how its "as if queried alone" is
  to be read; its residual check is what exposed the conflation.
- `2026-10-20-reference-content-filter-row-by-row` — the row filter compiler and `NestedSet`, whose locale visitor and
  `getEntity` path shaped the admission walk; the refusal of `scope` below the root of its filter is the precedent for
  refusing an inert `scope` everywhere.

## Timeline

- **2024-12-18** — `ScopeEnforcer` introduced (`ca897c3a27`).
- **2026-10-05** — the residual check moved into the nested context (`90f3d80d22`), merged on 2026-10-08 in #1720.
- **2026-11-04** — #1880 analysed; the first implementation (Option C) replaced by Option A after review; the pre-flight
  and the refusal of an inert `scope` added after a second review, every filter of the require section after a third;
  #1883 split off; PR #1884 opened.
- **2026-11-05** — PR #1884 merged.
- **2026-11-10** — #1883 fixed: the scope of fetched bodies resolved by one rule in the pre-flight, the planning check
  and the fetch. The red run found a false refusal of a correct query the issue had not expected.
