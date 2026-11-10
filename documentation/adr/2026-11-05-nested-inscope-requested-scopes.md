---
title: A nested `inScope` is admitted by the requested scopes and evaluated by the processed ones, and a misplaced scope is refused up front
date: 2026-11-05
updated: 2026-11-10 13:59
status: accepted
kind: fix
issues: [1880, 1897]
prs: [1884]
areas:
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_query/src/main/java/io/evitadb/api/query
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/behavioral
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
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

## Decision

**Chosen: Option A, with the checks Options G and H decline to do without.** Option A is the only option under which a
nested filter and the same filter at the top level are the same code path, so they cannot disagree. Option C would win
only if a foreign `inScope` were to stop meaning "restricts nothing" at the top level.

The refusal of an inert `scope(...)` and the refusal before a mutation are deliberate breaking changes. The upgrade note
is on #1880.

A wrongly composed query stays refused. `scope(ARCHIVED)` with `entityHaving(inScope(LIVE, x=1))` requests no live
scope, and the composer of the query has to leave the container out.

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
  with a nested foreign `inScope` take a different path.

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
  failed with the internal error until the walk raised the diagnostic itself. Full suite, 18:47 min:
  33,304 tests with 0 failures and 71 skipped; 183 release upgrade tests errored on fixtures the run skipped
  (`-DreleaseFixtures.skip=true`), every one of them with "Fixture ... is not on the class path".

## Consequences & open follow-ups

- **Visible to clients, both ways.** A query over both scopes with a nested `inScope` now works where it failed. `or`
  and `not` around a nested `inScope` now return what they return at the top level.
- **Breaking, now refused whatever the data.** An `inScope` of an unrequested scope in a filter or a fetched reference
  filter, including `getEntity`, `archiveEntity` and the other direct calls, and a `scope(...)` below a separate
  container other than `entityHaving` and `groupHaving`.
- **A composer that adds `inScope(LIVE, …)` unconditionally now fails on archived-only fetches.** That is the
  composer's defect, and the error says so.
- **Open, #1883.** The bodies of a `referenceContent` filter whose root names a wider scope: one owner is refused and
  another answered. The pre-flight keeps to what the query as a whole requests, and
  `QueryUtilsTest$QueryFilterScopes#shouldRefuseScopeWhenOnlyEnclosingFetchedReferenceFilterNamesIt` pins that a scope
  named for the bodies is not handed down. That issue may revise it deliberately.
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

## Timeline

- **2024-12-18** — `ScopeEnforcer` introduced (`ca897c3a27`).
- **2026-10-05** — the residual check moved into the nested context (`90f3d80d22`), merged on 2026-10-08 in #1720.
- **2026-11-04** — #1880 analysed; the first implementation (Option C) replaced by Option A after review; the pre-flight
  and the refusal of an inert `scope` added after a second review, every filter of the require section after a third;
  #1883 split off; PR #1884 opened.
- **2026-11-05** — PR #1884 merged.
- **2026-11-10** — #1897: a `scope(...)` below `not` refused before the scopes are resolved.
