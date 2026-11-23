---
title: A nested `inScope` is admitted by the requested scopes and evaluated by the processed ones, and a misplaced scope is refused up front
date: 2026-11-05
updated: 2026-11-23 15:43
status: accepted
kind: fix
issues: [1880, 1883, 1897, 1915, 1917, 1920, 2035]
prs: [1884, 1913, 1914, 1919, 2034, 2043]
areas:
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_query/src/main/java/io/evitadb/api/query
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/behavioral
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference/producer
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram
  - evita_engine/src/main/java/io/evitadb/core/query/sort/price
  - evita_engine/src/main/java/io/evitadb/core/query/QueryPlanner.java
  - evita_engine/src/main/java/io/evitadb/core/collection
supersedes: []
superseded-by: []
relates: [2026-10-08-scope-faithful-planning-and-facet-summary-parity,
  2026-10-20-reference-content-filter-row-by-row, 2026-10-21-one-locale-per-entity-level,
  2026-11-19-direct-entity-calls-check-requested-content, 2026-05-06-reference-histogram-statistics,
  2026-11-19-one-histogram-store-per-group, 2026-09-05-duplicate-content-requirements-reduction,
  2026-11-20-reference-owner-scope]
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

### Option I — declare the placement rule in the constraint descriptors (declined)

- **Pros:** `UserFilter` already refuses a list of children in its constructor (`FORBIDDEN_CHILDREN`), and its
  `@Child(forbidden = ...)` reaches the GraphQL and REST schemas, so `userFilter(scope(...))` would be refused as soon
  as the query is built.
- **Cons:** the constructor sees only direct children, so `userFilter(and(scope(...)))` would pass, and no descriptor
  can say "once per level" or tell an `or` of one child from an `or` of several. The walk is needed anyway.
- **Rejected because:** the constructor also runs when a stored query is read back - `UserFilterSerializer` and the
  EvitaQL parser build `UserFilter` through it - so a recorded query holding the shape could no longer be read, and
  the generated GraphQL and REST schemas would change. The rule is checked on the finished tree at the entry points,
  as the refusal below `not` of #1897 is. Worth revisiting only if stored queries stop being read back through the
  constructors.

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

### #1920 — a require-level `inScope` around the main fetch

A require-level `inScope(S, …)` is meant for extra results with no index outside `S` - a facet summary, a histogram,
hierarchy statistics. `RequireInScopeTranslator` narrows the processing scopes of `ExtraResultPlanningVisitor` to `S`,
and `ExtraResultPlanningVisitor#executeInContext` copies the narrowed set into every context below it, the main
`entityFetch` included. The fetch itself ignores the container: `EvitaRequest#isRequiresEntity` finds the main fetch
through it (`QueryUtils.findRequire(…, EntityFetch.class, SeparateEntityContentRequireContainer.class)`), and the
requests of the bodies derive from the query's scopes. The pre-flight passes the requested scopes through it too. So
four planning checks of fetched content judged a fetch over both scopes in `S` alone: the owner scopes of
`ReferenceContentTranslator#createProducer` and the inheritance base of `#resolveOwnerScope`, the walk of
`HierarchyContentTranslator#verifyStopAtNode`, and the uniqueness exemption of
`AttributeContentTranslator#verifyAttributes`.

Measured on `dev` (`328fb082b1`) over `scope(LIVE, ARCHIVED)`, every main fetch wrapped in `inScope(LIVE, …)` or
`inScope(ARCHIVED, …)`:

- **The fetch answered alike under either wrapper and without one**, on the prefetch route and the index route:
  archived owners got their rows under `inScope(LIVE, …)`, live owners under `inScope(ARCHIVED, …)`.
- **A correct query was refused.** The query of the issue (an `inScope(ARCHIVED, …)` in the `labels` filter of the
  group bodies, wrapped in `inScope(LIVE, …)`) failed with ``Scope `ARCHIVED` used in `inScope` filter container was
  not requested``, and so did its mirrors, the same filter of the value bodies, an `inScope` of the nested order, and
  a named localized attribute unique only outside the wrapper's scope, with `EntityLocaleMissingException`.
- **The verdict depended on the data.** A nested filter or order reading an attribute indexed only in the live scope,
  and a hierarchy bound on such an attribute at the first level or below the bodies, are refused without the wrapper.
  Under `inScope(LIVE, …)` they were refused only for an owner whose rows reached an archived body that the fetch
  plans the filter in (a tag filter: owners with tag rows refused, owners without answered; a hierarchy bound: an
  archived node with a parent refused, a live one or a root answered).
- **The filter and order of a first-level `referenceContent` were not affected.**
  `ReferencedEntityFetcher#verifyReferenceContentConstraints` reuses the query's own planning context when the owners
  are the queried type in the query's locale, and that context plans in the query's scopes. The hierarchy bound has
  no such shortcut, so its first level was affected.

#### Option R — the main fetch is planned in the query's searched scopes (chosen)

`ExtraResultPlanningVisitor#executeEntityFetchInContext`, through which `EntityFetchTranslator` plans every fetch,
starts the frame of the main fetch with `QueryPlanningContext#getScopes()` instead of the narrowed set. The four
checks read the processing scopes as before, which below the main fetch are the scopes the fetch searches.

- **Pros:** every check below the main fetch, present or future, sees the scopes of the fetch, so no reader has to
  know the rule; the extra results and their bodies keep the narrowed scopes; a query without a require-level
  `inScope` takes the unchanged path, because the narrowed set and the query's are then equal.
- **Cons:** all or nothing for the main fetch - it brings the nested filter, the nested order, the hierarchy bound and
  the uniqueness exemption along together, with the new refusals listed under Consequences.

#### Option S — the four checks read the query's scopes (declined)

- **Pros:** the smallest diff; it could stop at the reference filter and order, if fewer new refusals were wanted.
- **Rejected because:** `AttributeContentTranslator#verifyAttributes` also serves the bodies of the facet and
  reference summaries and of hierarchy statistics, so it would change their uniqueness exemption without a
  measurement, and a new check below the fetch would have to know the rule. Worth revisiting only if a check below
  the main fetch ever needs the narrowed scopes.

#### Option T — the fetch honours the container (declined)

- **Pros:** the documented sentence "restrict require conditions so that they only apply to a specific scope" would
  hold for the fetch as well.
- **Rejected because:** it changes answers that work today (archived owners lose their rows under `inScope(LIVE, …)`),
  needs a request per scope through `EvitaRequest`, `ReferencedEntityFetcher` and every derived request, and the only
  shape that would make it useful - one main fetch per scope - is refused by the single-constraint lookup
  (`MoreThanSingleResultException`, #2027). Worth revisiting if fetch content per scope becomes a feature; #2027 holds
  the question.

#### Option U — fix only the nested body levels (declined)

The issue's own scope: the owner scopes of a nested `referenceContent`.

- **Pros:** narrowest change.
- **Rejected because:** the hierarchy bound at the first level and the uniqueness exemption of the main bodies were
  measured with the same defect.

#### Option V — refuse `entityFetch` inside a require-level `inScope` (declined)

- **Pros:** honest about a fetch that ignores the container.
- **Rejected because:** it breaks every client that wraps its fetch today, on every data set; EvitaQL and REST accept
  the shape.

#### Option W — validate the folded fetch requirement instead of the written one (declined)

`HierarchyContent#combineWith` folds `hierarchyContent()` and `hierarchyContent(stopAt(…))` into one requirement
without the bound, so the fetch never evaluates that bound.

- **Pros:** would keep the one newly refused shape no fetch can fail on answered under the wrapper.
- **Rejected because:** planning validates the requirements as written, the rule the project owner set for #1949
  (`2026-11-19-direct-entity-calls-check-requested-content`), and the same query without the wrapper is refused
  today. Changing that is a separate decision covering every query, not one this fix makes for the wrapper alone.

### #2035 — histograms inside a require-level `inScope`

The user documentation promised a histogram inside `inScope(S, …)` for the entities of `S`; the engine only checked
the indexes of `S` and computed over everything the query matched. Measured on `dev` (`0ad50efa40`) with one live
product (price 12.1, `metric` 1) and one archived (121, `metric` 5), prices and `metric` indexed in both scopes, in a
query searching both scopes:

- `priceHistogram` and `attributeHistogram` under `inScope(LIVE, …)` or `inScope(ARCHIVED, …)` counted 2 where the
  same query searching that scope alone counts 1. `PriceHistogramTranslator` handed the producer the whole-query
  filtering formula; `AttributeHistogramTranslator` collected the filter index of every target index.
- A `facetSummary` under the same wrapper already counted 1: the reference summary keeps only the indexes of its
  processing scopes.
- The `histogramStatistics` of a reference summary ignored the wrapper and, without one, reported only the last
  scope: `ReferenceHistogramAccumulator` computed one histogram per query scope and the later `put` overwrote the
  earlier (`share` 3 live, 7 archived: count 1, 7..7 under `inScope(LIVE, …)` and unwrapped alike).
- The same histogram asked for twice - one wrapper per scope, or wrapped beside unwrapped - was answered with one
  histogram over both scopes. GraphQL already refused both shapes ("Even across different scopes").

#### Option X — the histogram of the matched entities that live in `S` (chosen)

Restrict what the query matched to the entities of `S`: the attribute histogram takes only the target indexes of
`S`, the price histogram intersects the filtering formula with the global indexes of `S`, the reference histogram
reads the indexes of the scopes it was planned in. Without a require-level `inScope` the price and attribute
histograms plan exactly as before; a reference histogram of a query searching several scopes combines them.

- **Pros:** the histogram describes the entities the client gets back ("what you see is what you get"), as the facet
  summary already did; one cheap restriction per histogram, nothing re-planned.
- **Cons:** for a filter whose answer depends on the searched scopes it differs from a query searching `S` alone -
  see Option Y.

#### Option Y — the histogram of the same query planned for `S` alone (declined)

The literal reading of the documentation's "only for entities in live scope".

- **Pros:** equal to a separate single-scope query for every filter.
- **Rejected because:** a filter crossing scopes matches differently. `referenceHaving(brands, entityHaving(code =
  b))` with brand `b` archived matches a live product in a query searching both scopes (`[1, 2]`) and none searching
  `LIVE` alone (`[]`); under `not(…)` the two answer `[3]` and `[1, 3]`. The histogram would then describe entities
  the client did not get and leave out ones it did, and every wrapped histogram would need a second plan of the
  filter. The project owner chose X: "histogram must reflect entities returned to user".

#### Option Z — merge one histogram slot asked for in several scope sets (declined)

The behaviour the attribute producer was written for: a repeat with equal bucket count and behaviour widened the
index list (`c5342c3926`).

- **Pros:** no newly refused query.
- **Rejected because:** the response has one price histogram, one histogram per attribute and one per named
  histogram of a reference; a merged one answers a requirement for `S` with entities outside `S`. The project owner
  chose refusal, which also matches GraphQL. The merge itself was never reached: the translator resolved the same
  index list for every repeat, so it added nothing, and it was removed with the refusal.

### #2035, review round — the boundary entity and the controls of a reference histogram

The adversarial review of the first implementation found five gaps around reference histograms, each shown red by a
test before it was fixed (Verification):

- The boundary entity of an **ungrouped** histogram (`HistogramContract#getMinReferencedEntity` and its maximum
  twin) was picked among every referenced entity carrying the bound value, so it could name one that only an owner
  the query excluded references. Grouped histograms already preferred candidates of the owners they count.
- The facet sorter, group predicate and group fetch were held once per reference: with two summaries of one
  reference in `inScope(LIVE, …)` and `inScope(ARCHIVED, …)`, every histogram of the reference used the last
  summary's controls, built in its scope, so a LIVE boundary tie was broken by a sorter built for ARCHIVED.
- Boundary ties ignored the query's scopes: an ARCHIVED candidate with a lower primary key, or ranked first by the
  facet sorter, beat a LIVE one in a query searching `scope(LIVE, ARCHIVED)`, and in a query searching `LIVE` alone
  it won the pick, did not load and left the histogram without boundary entities.
- GraphQL resolved an unwrapped reference histogram's names in `LIVE` only, whatever the query's `scope`.
- REST read a query body into a JSON tree in which the last of two equal properties wins, so a second `inScope` of
  the root `require` dropped the first without an error.

#### Option AA — confine every boundary pick to the counted owners (chosen)

Every reference histogram, grouped or not and with or without `entityFetch`, prefers the candidates referenced by the
owners it counts at the bound, falls back to every candidate when none qualifies, and carries the pick among every
candidate as the alternative for a preferred entity that does not load. An ungrouped histogram checks the owners
through the reference partitions of its type index: the owner of a single-owner registry row, the owner bitmap of a
materialized partition.

- **Pros:** the boundary entity is one a counted owner references whenever such a candidate exists and loads; the
  pick among all candidates is kept only as the bound's fallback. The same query names the same entity whatever
  `entityFetch` it carries. Boundary entities are returned without `entityFetch` too, with identity and standard
  metadata only.
- **Cons:** an ungrouped bound checks every tied candidate through its partition, without the early stop of the
  grouped merge. Measured (Verification): no cost the benchmark resolves at 1,000 tied candidates.

#### Option AB — confine the ungrouped pick only when the histogram requests `entityFetch` (declined)

The first implementation of the fix: no owner work for a histogram without `entityFetch`.

- **Pros:** a histogram without `entityFetch` keeps its cost exactly.
- **Rejected because:** boundary entities are returned without `entityFetch` too, so adding an empty `entityFetch()`
  changed which entity the same query named, and the saving it bought was below what the benchmark resolves. Worth
  revisiting only if a workload ties many thousands of referenced entities at a bound and the confined scan
  measurably costs there.

#### Option AC — rank the candidates by the query's `scope(...)` order inside the pick (chosen)

Before the facet sorter or the lowest primary key decides a tie, a candidate of an earlier scope of
`EvitaRequest#getScopesAsArray()` wins; a candidate in no searched scope - in an unsearched scope, or a referenced
entity that is not stored at all - ranks last. Owned before unowned still comes first, and a chosen entity that
lacks the query's locale still falls back to the alternative.

- **Pros:** decided on primary keys before anything is fetched, with one read of each scope's global primary keys
  per reference.
- **Cons:** a reference to an entity that is not stored no longer wins a tie by its primary key: eight existing
  expectations changed, which the project owner accepted.

#### Option AD — retry a pick the fetch rejects as out of scope (declined)

A side channel from `EntityCollection` reported a picked body outside the fetched scopes, and the accumulator fetched
the next candidate.

- **Pros:** no ranking structure in the pick.
- **Rejected because:** it put a hook for one extra result into the general fetch path and paid a second fetch;
  ranking before the fetch needs neither.

#### Option AE — keep "defined in every processing scope" in the engine and fix GraphQL (chosen)

`ReferenceHistogramStatisticsTranslator#resolveDescriptor` still refuses a histogram missing from a processing scope
("Histogram `x` is not defined on reference `r` in scope `S`."). GraphQL resolves an unwrapped histogram's names
against the query's `filterBy` `scope` (default `LIVE`) and a wrapped one against its wrapper's scope, so it returns
what the Java API returns and refuses what it refuses.

#### Option AF — accept a histogram defined in at least one processing scope (declined)

- **Pros:** an unwrapped query searching both scopes returns a histogram defined in one of them.
- **Rejected because:** the project owner's rule: a histogram that exists in one scope is addressed with
  `inScope(<that scope>, …)`, as every other scope-specific index is. The relaxation had been forced by a GraphQL test
  that demanded a result the Java API refuses.

The other two fixes had no alternative on the table. Each histogram request carries the sorter, group predicate and
group fetch of the summary that declared it, built in that summary's processing scopes, except that a histogram
declared by the generic summary takes the reference-specific summary's controls when one exists - the override rule
of `2026-09-05-duplicate-content-requirements-reduction` ("a reference-specific summary governs its reference
entirely"). REST reads query bodies with strict duplicate detection and answers a repeated property with HTTP 400
naming it; no list form for several root `inScope` wrappers was added.

#### Option AG — no boundary entities for an unmanaged referenced type (chosen)

Found while correcting the API descriptions of the boundary entities: a histogram over a reference whose referenced
entity type evitaDB does not manage failed the whole query. The pick named the unmanaged type and the boundary
prefetch asked for its collection (`EntityCollectionRequiredException: Collection type is required in query in order
to compute fetch entities!`, from `BoundaryEntityCache#prefetch`). The same pick and fetch exist on `dev`.
`computePendingHistogram` now returns `BoundaryPks.NONE` for such a reference before any boundary work; the
histogram itself is returned whole.

- **Pros:** nothing is picked that cannot be loaded; the API descriptions ("referenced entities that can be loaded")
  hold for every reference.
- **Cons:** a client gets no anchor entity for the ends of such a histogram, only its `min` and `max`.

An `entityFetch` inside such a histogram stays refused at planning, as the summary's own `entityFetch` on the
reference is; the refusal's message ("EntityFetch constraint is probably incorrectly nested in require part of the
query.") names the wrong cause and was left as it is.

#### Option AH — return the picked primary key as a bare reference (declined)

- **Pros:** a client would learn which external entity anchors each end.
- **Rejected because:** the boundary slots are typed as full entities in all four APIs (`SealedEntity` in
  `HistogramContract`, `GrpcSealedEntity`, the GraphQL and REST entity objects); carrying a bare reference is a
  contract change across them, not a fix. Worth revisiting if clients ask for anchors of external references.

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

**#1920: Option R**, by the project owner's decision. A require-level `inScope` plans the extra results it wraps in its
scope; the main `entityFetch` is applied to every returned entity whatever its scope, so its content is checked in the
scopes the fetch reads it in - every scope the query searches, unless a reference filter selects the scopes of its
bodies - as the fetch, the pre-flight and the direct calls already did. The shapes it newly refuses were all
refused without the wrapper, and all but the folded hierarchy bound were refused under it for other data; the project
owner accepted the folded bound's refusal too, and labelled the change breaking with an upgrade note on #1920.

**#2035: Option X**, by the project owner's decision. A histogram describes the entities the query matches: inside a
require-level `inScope(S, …)` those that live in `S`, without one those of every searched scope - for the price
histogram, attribute histograms and the histograms of a reference summary. One histogram slot requested with
different scope sets is refused (Option Z declined).

**#2035, review round: Options AA, AC and AE**, by the project owner's decisions. The boundary entity of a reference
histogram is a candidate of the owners the histogram counts where one exists, ranked by the query's scope order,
whether or not the histogram requests `entityFetch`; the owner chose to confine always (AA over AB) after the cost
was measured. The engine keeps refusing a histogram missing from a processing scope, and GraphQL follows it. A
reference to an unmanaged entity type resolves no boundary entities (Option AG); the project owner had the fix folded
into the same pull request.

## Key technical details

- `EvitaRequest#getRequestedScopes`: NULL in the field means the same as `getScopes()`. The five-argument
  `deriveCopyWith` unites the inherited set with the searched scopes and reads no `scope(...)` of the filter. The
  six-argument one takes the set from a caller that honours such a scope. Reading it in the five-argument overload,
  which every derived request goes through, changed the locale a top-level `hierarchyWithin` resolved in.
- `EvitaRequest.ScopeEnforcer`: the root of the copy is always a `FilterBy` holding the enforced scope once. Every
  `inScope` is retained, and so is a `scope(...)` below a `SeparateEntityScopeContainer`, which targets another entity.
- `QueryUtils#assertFilterScopesApplicable` is the scope-admission walk. `QueryPlanner#planQuery` and
  `EntityCollection#assertScopeUsageValid` run it on the filter section and on every filter of the require section - of
  a `referenceContent`, a hierarchy `node`, a facet summary - at any depth, before anything of the request runs. A new
  client-facing entry point has to call it.
- **Scope placement, #1897 and #1917:** `QueryUtils#assertScopePlacementValid` allows one `scope(...)` per entity
  level, at its filter root or on an `and` chain from it; single-child `or` is transparent. All other wrappers,
  including `inScope` and `userFilter`, refuse a scope in their own level. Both entry points call it before
  `getRequestedScopes()`: lookup reads through forbidden wrappers and otherwise fails on duplicate selections with
  `MoreThanSingleResultException`. The first pass validates placement across filter, order and require sections;
  only then does the second pass check duplicates per level, including identical selections. Repeated values inside
  one scope remain valid and retain their requested order. For one scope's ancestors, inertness wins over `not`,
  multi-child `or`, then the nearest other forbidden ancestor. Every `SeparateEntityScopeContainer` starts a fresh
  level; its entire sibling body is checked together and contributes no scope to its parent.
- **Two passes, not one:** a single walk that throws at the second scope of a level stops before a misplaced scope
  later in the tree, so `filterBy(scope(LIVE), scope(ARCHIVED), not(scope(LIVE)))` would report the duplicate
  instead of the `not`.
- **Why `and` stays allowed:** GraphQL and REST turn each object in a list of constraints into an implicit `and`
  (`ConstraintResolver#resolveWrapperContainer`; the wrapper is `And` in the `FilterConstraintResolver` of both APIs),
  so a scope written beside other constraints in such an object reaches the engine inside `and`.
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
- Performance: no benchmark or allocation measurement. The clone allocates as before. The client entry-point
  placement validator makes two traversals using stack state and constraint references, with no collections or copied
  trees. The admission walk runs per request, nested plan and compiled reference filter, never per row, and allocates
  nothing unless the filter holds an `inScope` or fails. Only queries with a nested foreign `inScope` take a different
  path. The summary of the body scopes is an `int`; the fetch and the group `EXISTING` gate each resolve it once per
  fetched reference batch, and the pre-flight only after the allocation-free search for an `inScope` below the bodies.
  It allocates only the lookup of the own scope of each container of the level. The encoding holds up to 15 scopes.
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
  Where no enclosing level names a scope, it inherits the processing scopes, which below the main fetch are the
  query's searched scopes (#1920) and below the body fetch of an extra result the scopes of its `inScope`.
- **The main fetch, structurally (#1920).** `ExtraResultPlanningVisitor#visit` remembers the outermost
  `SeparateEntityContentRequireContainer` on the active path, and `#executeEntityFetchInContext` starts the frame
  with the query's scopes only for an `entityFetch` that is that container itself - the boundary
  `EvitaRequest#isRequiresEntity` stops at - and only when the processing scopes differ from the query's, so a query
  without a require-level `inScope` takes the inherited scopes as before.
  **Not `isRootScope()`:** `HierarchyOfSelf#getCopyWithNewChildren` (and that of `HierarchyOfReference`) accepts a bare
  `entityFetch` child, and its translator visits it without a new frame, at the root depth - measured, both
  copied-hierarchy guards of `$RequireLevelInScope` fail with the frame depth in place of the structural test. **Not
  identity with the fetch `QueryUtils.findRequire` returns:** the Java API lets one `EntityFetch` object stand both as
  the main fetch and as the child of such a hierarchy, and identity then planned the hierarchy's body in the query's
  scopes (`$RequireLevelInScope#shouldRefuseCopiedHierarchyBodyWithoutLocaleWhenItSharesTheMainFetchObject`).
- **Looks like a bug and is not (#1920):** an extra result that a Java copy places inside the main fetch - e.g.
  `hierarchyContent().getCopyWithNewChildren(...)` holding an `attributeHistogram` - is planned in the main fetch's
  scopes, not in those of an enclosing `inScope`. The EvitaQL parser refuses that placement; it is not a supported
  tree.
- **Left narrowed on purpose (#1920):** the bodies of a facet or reference summary and of hierarchy statistics under
  a require-level `inScope` keep the narrowed uniqueness exemption, although
  `QueryPlanningContext#fabricateFetchRequest` fetches them in the query's scopes - suspected to be the same defect,
  not measured (Consequences).
- `ReferencedEntityFetcher#verifyReferenceContentConstraints` takes the requested scopes of the owners explicitly; NULL
  keeps the five-argument `deriveCopyWith`, which unites the scopes of the query with the searched ones. Its shortcut
  reusing the context of the query applies only then, so a self-referencing type cannot skip a scope of its bodies.
- **Histogram scopes (#2035).** `AttributeHistogramTranslator` keeps the target indexes whose key names a processing
  scope, as `ReferenceSummaryTranslator` does for facets. `PriceHistogramTranslator#restrictToScopes` wraps the
  filtering formula in `new AndFormula(F, or(global index of each scope))` only when the processing scopes differ
  from the query's - not `FormulaFactory.and`, which flattens `F` and drops its memoized result, and not
  `FilterByVisitor#getSuperSetFormula(Scope)`, which depends on the visitor's frame and falls back to candidate index
  sets. `ReferenceHistogramStatisticsTranslator` stores the processing scopes in each `HistogramRequest`, and
  `ReferenceHistogramAccumulator` computes one histogram per request and group from the indexes of all its scopes,
  resolving the boundary entities across them.
- **Slider relaxation is unchanged (#2035):** a histogram ignores its own range constraints in `userFilter`, wrapped
  exactly like unwrapped; the scope restriction applies to that relaxed baseline. An adversarial review read "what you
  see is what you get" as excluding the relaxed entities too; that would make a wrapped slider shrink itself.
- **One slot, one scope set (#2035):** `PriceHistogramProducer`, `AttributeHistogramProducer` (per attribute name,
  not per producer - GraphQL wraps each attribute in its own `inScope`) and
  `ReferenceSummaryProducer#addHistogramRequest` compare scope sets, never index lists, so the refusal does not depend
  on which scope holds data.
- **Removed as dead (#2035):** the `FilteredPricesSorter` lookup result the price histogram reused was assigned in
  `sortAndSlice`, at execution, and read while the plan was built - always null. The lookup, its sorter field, the
  planner's `findSorter` and the collector constructor taking it went with it.
- **Cache keys need no scope (#2035):** the price computer hashes its filtering formula, which carries the scope
  restriction, and the attribute computer hashes the ids of its filter indexes; two requests over the same indexes and
  formula compute the same histogram and may share the entry.
- **Boundary owners (#2035).** `ReferenceHistogramAccumulator#computePendingHistogram` passes the histogram's filter
  indexes as the owner filter of every pick, and a query planning context to an ungrouped one; `countedOwners`
  reads the owners at the bound narrowed by the baseline. An ungrouped pick maps its candidates to partition
  primary keys (`ReferencedTypeEntityIndex#getIndexPrimaryKeys`), splits them (`ReferencePartitions#split`) and
  checks each virtual row's owner and each materialized partition's owner bitmap
  (`referencedByCountedOwners(…, QueryPlanningContext)`), every tied candidate. A grouped pick without a sorter keeps
  the merge that stops at the first owned candidate (`pickLowestOwnedBoundaryPk`). No path picks without an owner
  filter; with no owner counted at the bound, every candidate stays.
- **Scope order (#2035).** `ReferenceHistogramAccumulator.BoundaryScopeOrder` reads
  `GlobalEntityIndex#getAllPrimaryKeys()` lazily, once per scope and reference: `contains` ranks the candidates the
  merge pops, `restrict` ranks the small candidate sets of the other picks. A reference to an unmanaged entity type
  never reads it: `computePendingHistogram` returns `BoundaryPks.NONE` first (Option AG).
- **Controls per histogram (#2035).** `ReferenceSummaryProducer.RegisteredHistogram` holds the request with the
  sorter, group predicate and group fetcher captured when its summary registered it; `resolveHistogramControls`
  swaps in the reference-specific summary's controls for a histogram the generic summary declared. A synthetic
  group shared by several histograms keeps one body, fetched by the lowest histogram name that requests one.
- **External APIs (#2035).** GraphQL: `QueryEntitiesDataFetcher` passes `filterBy` to `buildRequire`, and
  `ReferenceSummaryResolver#resolveHistogramIndexDefinition` takes the scopes. REST: `QueryOrientedEntitiesHandler`
  reads query, list and delete-by-query bodies with `StreamReadFeature.STRICT_DUPLICATE_DETECTION`
  (`parseQueryBody`); the shared mapper is unchanged. The schema descriptions of `minReferencedEntity` and
  `maxReferencedEntity` - `ReferenceHistogramDescriptor`, which GraphQL and REST share, and `GrpcExtraResults.proto`
  with its generated classes - state when both are returned and what they carry, without naming `entityFetch` as
  the condition: one text serves GraphQL, where the field selection is the request, and REST, where it is not.

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
- **#1915, measured and left alone.** A named localized attribute of bodies with no locale source was refused by a
  planned query and answered by `getEntity`, alike for a locale below `or` and a filter without any `entityHaving`
  (`$GroupBodyScopes#shouldRefusePlannedLocalizedBodyAttributesWhenFetchLocaleIsMissing`). Since #1949
  (`2026-11-19-direct-entity-calls-check-requested-content`) `getEntity` refuses it too
  (`$GroupBodyScopes#shouldRefuseDirectFetchWhenNeitherShapeSuppliesBodyLocale`). The price criteria of the bodies
  are read from the code only: the data set has no prices.

- **#1917, scope placement and uniqueness.** The clean nine-class gate passes 792 tests with no failures, errors
  or skips: `ScopePlacementFunctionalTest` 23, `ScopeInDisjunctionFunctionalTest` 26, `NegatedScopeFunctionalTest` 23,
  `QueryUtilsTest` 188, `ReferenceContentRowFilterFunctionalTest` 40, `ReferenceRowFilterCompilerTest` 40,
  `CatalogGraphQLQueryEntityQueryFunctionalTest` 269, `FilterInScopeTest` 30 and `EvitaRequestTest` 153.
  The tests were written first and run against unchanged production code: 791 tests, 14 failures and 13 errors.
  With the placement check removed from `EntityCollection#assertScopeUsageValid` only, exactly the five
  `getEntity` refusals and the refused `upsertAndFetchEntity` of `ScopePlacementFunctionalTest` failed: the
  `inScope` and `userFilter` rows met the row compiler's message instead of the pinned placement message, and the
  two duplicate rows threw nothing. An adversarial review ran a throwaway probe, not kept as a repository test, over
  every wrapper chain of length one to four that the constructors accept, built from `and`, single-child and
  multi-child `or`, `not`, `inScope`, `userFilter`, nested `filterBy` / `filterGroupBy`, `entityHaving`, `groupHaving`,
  `referenceHaving` and `facetHaving`: with ten explicit boundary and duplicate cases, 21,768 checks matched the
  expected verdict, and the constructors refused 862 other chains before validation. Full suite on the final code
  with the release fixtures generated, 15:57 min: 33,436 tests with 0 failures, 0 errors and 71 skipped.
  They exposed accepted `inScope` / `userFilter` selections, the generic duplicate-lookup exception, an unnegated
  inert order scope, root conjunction compilation failures and an owner still present after a refused write-and-fetch.
  `QueryUtilsTest$ScopePlacement` checks placement before duplicates across sections, independent sibling levels,
  identical duplicates and repeated values inside one selection. On owner 60, which references live item 1 and
  archived item 3 with code a, `filterBy(entityHaving(code=a))` returns `[1]`; a direct root scope over both scopes,
  root conjunction chains and singleton `or` mixtures return `[1, 3]`. `ScopePlacementFunctionalTest` verifies this
  difference through `queryList`, `getEntity` and `upsertAndFetchEntity`, and verifies that a refused scope leaves
  no owner written. The GraphQL tests pin the full `inScope` and `userFilter` diagnostics. The negation and disjunction
  functional classes are unchanged; the two reference-content `inScope(LIVE, scope(LIVE))` rows now expect the
  entry-point diagnostic instead of the late row compiler's diagnostic.

- **#1920, red first.** `NestedInScopeFilterFunctionalTest$RequireLevelInScope` was written against unchanged
  production code (`328fb082b1`): 36 tests, 18 failures, every one an invocation of the answered or the refused group
  and every guard green. The answered group failed with the messages of the issue (``Scope `ARCHIVED` used in
  `inScope` filter container was not requested``, its `LIVE` and order mirrors, `EntityLocaleMissingException`); the
  refused group failed with "nothing was thrown" for exactly the owners the data-dependence above names, and the
  nested order of the archived scope with the admission message instead of `AttributeNotSortableException`. With the
  fix: 38 tests, 0 failures - two added after the red run, the shared fetch object above and a wrapper equal to the
  query's single scope. Each test asserts a literal answer or the exact exception and message, for owners with and
  without rows and the unfiltered query; the locale-less fetches also fetch the same bodies in English, so their empty
  answer is the missing locale, not missing data.
- **#1920, counterfactual.** With `ExtraResultPlanningVisitor#executeEntityFetchInContext` always taking the
  inherited scopes: 37 tests, 18 failures - the answered and the refused groups exactly - and 19 green
  guards: a first-level filter and order still refused under every wrapper, first-level filters naming either scope
  answered, the rows of the fetch identical on the prefetch and the index route (`EXECUTION_PREFETCH` in the
  telemetry of the one, absent in the other), a self-referencing nested level with inherited and with explicit
  scopes, a live-only facet summary beside the fetch in both child orders with counts of live owners only, a summary
  body keeping its narrowed refusal, and both copied-hierarchy guards. With the frame depth (`isRootScope()`) in place
  of the structural test: exactly the two copied-hierarchy guards fail. With the marker of the outermost container
  never cleared: exactly the facet-summary guard with the summary visited first fails, its main fetch refused with
  `EntityLocaleMissingException` - the guard fetches node bodies whose `code` only a check in both scopes admits. The
  whole `NestedInScopeFilterFunctionalTest` passes 186 tests with the fix.
- **#1920, neighbours.** 57 classes touching require-level and nested `inScope`, fetched-body scopes and the query
  model, 7,155 tests, 0 failures, 0 errors, 0 skipped.
- **#1920, full suite** on `328fb082b1` plus the change, `-P unitAndFunctional` with fixed parallelism 8, 16:53 min:
  bitmap 19,215 tests with 0 failures and 1 skip; functional 39,503 with 0 failures, 70 skipped and 183 errors, every
  one in the nine release-upgrade classes and reading `Fixture ... is not on the class path` - the release fixtures
  were not generated, because the sandbox could not resolve release 2026.2.18 offline. The pull request's CI builds
  them.
- **#2035, red first.** `RequireInScopeHistogramFunctionalTest` on `dev` `0ad50efa40`: 9 tests, 4 failures (price and
  attribute histogram under either wrapper). Extended on `870631292c` before the fix: 40 tests, 27 failures - the
  scoped price and attribute cases, the duplicate refusals, the wrapped and the merged reference histograms. With
  the fix the class holds 51 tests, all green, every query without a forced route run with the debug modes
  `VERIFY_ALTERNATIVE_INDEX_RESULTS` and `VERIFY_POSSIBLE_CACHING_TREES` switched on (whether the first varies the
  plan is an open question below). The route tests prove their route: `PlanPreference` asserts `EXECUTION_PREFETCH`
  in the telemetry of the prefetch arm and its absence on the index scan, and with the price restriction off 15
  tests fail, all six route invocations among them, after their route assertion. Hierarchy statistics
  (`hierarchyOfReference` with queried entity and children counts) under either wrapper equal the single-scope query.
- **#2035, counterfactuals** on the 40-test class, each mechanism broken alone, each failing exactly its own tests:
  the attribute index scope filter off - 5; the price `AndFormula` restriction off - 10; the duplicate-scope
  refusals off - 8; the reference histogram requests back on the query's scopes - 4; one histogram per scope with
  the overwrite back - 2. The last two were repeated after the review rounds, and the tests added by the
  code-quality pass for a group held in the later scope only and for referenced-entity boundaries in each scope fail
  when the outer group loop or the global-index loop is limited to the first scope.
- **#2035, neighbours** after the first implementation: 4,139 tests in the histogram, price, attribute, facet and
  reference-summary suites, 0 failures, 1 skip (an existing assumption); `CatalogGraphQLQueryEntityQueryFunctionalTest`
  269 and `CatalogRestQueryEntityQueryFunctionalTest` 188, green.
- **#2035, full suite** on `870631292c` plus the change, `-P unitAndFunctional`, 16:06 min, with the release fixtures
  generated: bitmap 19,215 tests with 0 failures and 1 skip; functional 39,874 with 0 failures, 0 errors and 70
  skipped.
- **#2035 review round, red first**, each on the branch before its fix: REST duplicate properties 2 of 2 (`Expected
  status code <400> but was <200>`); ungrouped boundary owners 2 of 2 (`expected: <102> but was: <100>`, `<302>`
  but `<300>` for a referenced-entity attribute); sibling-summary controls 1 (`<102>` but `<100>`); scope order 7 of
  12 (the LIVE-first ties, the LIVE-only boundary absent, the wrapped one); GraphQL scopes 2 of 3; the boundary
  without `entityFetch` 2 of 2 (`<102>` but `<100>`, `<302>` but `<300>`).
- **#2035 review round, counterfactuals**, each mechanism broken alone: duplicate detection off - only the 2 REST
  cases fail; the ungrouped owner list empty - only the 2 owner cases; controls re-resolved per reference - only
  the sibling case; the GraphQL lookup in `LIVE` only - 2 of the 4 GraphQL cases; every reference `UNRANKED` - 20
  invocations of 8 tests, each a scope-order witness or an expectation that depends on the order; the
  generic-summary override bypassed - 1; the `entityFetch` condition back on
  the ungrouped owner list - the 2 no-fetch cases and the 2 call-count cases that pin the owner reads, of 1,237
  tests in the 40 gate classes.
- **Cost of always confining (AA).** JMH, embedded engine with the formula and result caches off, 100,000 products
  each referencing 8 of 5,000 targets, a filter keeping 10,000; 1, 100 or 1,000 targets tied at the minimum, each
  referenced by 1 counted owner (a virtual partition) or 50 (a materialized one); a grouped reference as control,
  whose two arms do identical work. The arms: the histogram without `entityFetch` (not confined before AA) and with
  an empty one (confined). Sweep, one fork, 3 x 2 s warm-up, 5 x 2 s: no axis moved the ratio beyond the control's
  own 0.72-1.05. Final, tie 1,000, both owner shapes, three rounds alternating which arm ran first, one fork, 5 x
  2 s warm-up, 10 x 2 s: 10-13 ms per query; confinement minus the control's arm difference -0.51 to +1.05 ms
  (-4.4% to +8.6%), while the control's identical arms differed by up to 2.4 ms on the three-round mean. A debugger
  on the same harness confirmed the work measured: every minimum bound checked all 1,000 tied candidates, 999 of
  them owned, through 1,000 virtual rows or 1,000 materialized partitions. The benchmark was written for this
  decision and not kept.
- **#2035 review round, full suite** on `0f0568ee5a` plus the review round, `-P unitAndFunctional`, 18:12 min: bitmap
  19,215 tests with 0 failures and 1 skip; functional 39,901 with 1 failure, 0 errors and 70 skipped. The failure,
  `ChangeCaptureRingBufferTest#shouldHandleParallelOfferAndCopyToOperations`, is a concurrency test of the change
  capture ring buffer, which the change does not touch; its class passed 41 of 41 when rerun alone.
- **Boundary entities without `entityFetch`, per API** - the evidence behind the corrected descriptions: the Java
  API returns both (`RequireInScopeHistogramFunctionalTest`, the no-fetch cases); REST returns them with
  `primaryKey`, `type`, `version`, `scope` and locales in the recorded documentation example
  `reference-summary-histograms`, whose `histogramStatistics` has no fetch; the gRPC builder sets both fields
  whenever the histogram has them (`GrpcReferenceHistogramConversionTest`). No gRPC call was run end to end.
- **Unmanaged referenced types (AG)**, `UnmanagedReferenceHistogramBoundaryFunctionalTest`: red first on `eba12480e9`,
  both cases without `entityFetch` (ungrouped, and with a managed group) with `EntityCollectionRequiredException:
  Collection type is required in query in order to compute fetch entities!` from `BoundaryEntityCache#prefetch`. Five
  cases: those two, a reference without facets summarized by `referenceSummary`, and the two planning refusals of
  `entityFetch` (inside the histogram, and the summary's own), each asserted by class and message. The guard removed
  alone fails exactly the three cases without `entityFetch`. Full `-P unitAndFunctional` run: bitmap 19,215 tests,
  0 failures; functional 39,906 tests, 0 failures, 0 errors, 70 skipped.

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
- **Open, left by #1915:**
  - **Resolved in #1949:** `getEntity` planned no fetch, so it did not refuse the named localized attribute above,
    which a planned query refuses; it returned the entity, or the referenced entity bodies, without the attribute.
    The direct calls now check the requested content against the schema next to the scope check
    (`2026-11-19-direct-entity-calls-check-requested-content`).
  - **Resolved in #1920:** an `inScope` in the require section narrowed the scopes the planning checks of the main
    fetch inherited, while the pre-flight and the fetch did not. They now check it in the query's searched scopes
    (Option R).
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
    leave an empty collection behind. The entity is not written. Measured in #1949: the collection stays only when the
    refusal is caught inside `updateCatalog` or the catalog is in warm-up; a refusal escaping a bare session call
    marks the transaction rollback-only, and closing the session discards the collection with the session's other
    writes.
  - The GraphQL selections of an unknown entity build their requirements only for a found entity, so a missing one
    returns null without a verdict.
  - **Resolved in #1917:** the row compiler of `referenceContent` flattens the root `and` and single-child `or`
    path, skips scope selections there and delegates every other node to ordinary compilation. Direct fetch and
    write-and-fetch now accept the same root path as planning; an accepted conjunction no longer fails after writing.
  - A hierarchy requirement's `node` filter evaluates against the supplied hierarchy index without extracting its
    root scope. A root `scope(...)` there is validated for placement and uniqueness but has no effect on the tree.
  - `segment(entityHaving(...))` unwraps the container and translates its children in the enclosing processing
    scopes without extracting its root scope. That root selection is validated but has no effect. Placement and
    uniqueness are still checked throughout order filters, including nested reference/entity levels; an unnegated
    inert scope in a reference filter there is now refused too.
  - The engine's conditional-expression evaluator builds its own filter with a root scope and translates the formula
    directly, bypassing `QueryPlanner`. The client entry-point placement check does not cover this internal evaluator;
    its expression translator constructs no scope leaves.
  - A facet summary filter over the queried entity type itself is planned in the enclosing context, so its own
    `scope(...)` does not narrow what an `inScope` of that filter may name
    (`FilteringFormulaPredicate#createTargetQueryContext`). The searched scopes follow the `scope(...)` of the filter.
- **Breaking, #1897.** A `scope(...)` below `not`, at any depth of its entity level, is refused. It used to end in a
  `GenericEvitaInternalError` from `NotTranslator`, or, beside a sibling, to be applied as if it stood outside the
  `not` - `not(and(scope(ARCHIVED), code=a))` returned archived items. Giving it a meaning (the complement of the named
  scopes) was declined: a query's scopes would then depend on a negation, and naming the scopes says the same.
- **Breaking, #1917.** One `scope(...)` per entity level, at the root or on an `and` chain, replaces the
  logical-container block-list. A single-child `or` is transparent on normalized queries and direct fetches alike.
  Multi-child `or`, `inScope`, `userFilter` and every other wrapper outside the allowed path refuse a scope;
  inert levels retain their own diagnostic. Separate entity/group levels start their own valid root path.
  A scope selects the whole level's searched scopes and produces no entity predicate, so reading it through these
  wrappers silently changes the meaning of the query. Measured on the four-item fixture,
  `inScope(LIVE, scope(LIVE, ARCHIVED), code=a)` returned `[1, 3, 4]`, and
  `inScope(ARCHIVED, scope(ARCHIVED), code=a)` returned `[3]`. Name the scopes at the root of that same level and
  keep conditions in `inScope`.
  **The `userFilter` refusal intentionally breaks a working use:** `userFilter(scope(ARCHIVED), code=a)` returned
  `[3]`, as did `scope(ARCHIVED), userFilter(code=a)`. It is refused by the project owner's decision, so that the
  rule names a single conjunction, `and`; the upgrade is to move the `scope(...)` to the root of the filter.
  Duplicate constraints, even identical ones, get a structural diagnostic with advice to name all scopes in one
  ordered selection; previously `scope(LIVE), and(scope(LIVE))` raised `MoreThanSingleResultException`.
  `referenceContent` accepts the root path too, by flattening `and` and single-child `or` before compiling its row
  predicates. Scope lookup, `inScope` compilation and target-bound extraction keep their existing meanings.
- **Breaking, #1920.** Under a require-level `inScope`, the main fetch's content is refused for every owner where
  the same query without the wrapper is refused: a nested filter or order reading an attribute not indexed in a
  searched scope, and a hierarchy bound on such an attribute at any level. All of them were refused for some owners
  before. An `inScope` of the other scope in a nested order is refused with `AttributeNotSortableException`, as
  without the wrapper, instead of the admission message. The exception is a bounded `hierarchyContent` beside an
  unbounded one in the same fetch: the fetch folds them and never evaluates the bound, so it was answered for every
  entity under the wrapper and is now refused as without it. The upgrade note on #1920: drop the redundant bounded
  sibling, index the attribute in the searched scopes, or scope the nested filter.
- **Open, left by #1920:**
  - **Suspected, not measured:** the bodies of facet and reference summaries and of hierarchy statistics under a
    require-level `inScope` are fetched in the query's scopes and checked with the narrowed uniqueness exemption (Key
    technical details), the defect #1920 fixed for the main fetch. A fix would widen what those bodies admit.
  - #2027: a main fetch per scope (`inScope(LIVE, entityFetch(…)), inScope(ARCHIVED, entityFetch(…))`) is refused by
    the generic single-constraint lookup with `MoreThanSingleResultException`, when the plan is executed.
- **Visible to clients, #2035.** A histogram inside a require-level `inScope` changes its numbers in a query
  searching more than one scope: it counts the matched entities of its scope only. A reference histogram of a query
  searching several scopes without the wrapper now covers all of them, where it showed the last one. The same
  histogram requested with different scope sets is refused in EvitaQL, Java, REST and gRPC, as GraphQL already did;
  equal scope sets stay accepted. The upgrade note is on #2035.
- **Visible to clients, #2035 review round.** The boundary entity of a reference histogram may change: it is one a
  counted owner references where one exists, for ungrouped histograms too and with or without `entityFetch`. Ties
  follow the query's scope order, and a referenced entity that is not stored, or lives in an unsearched scope, ranks
  last. With a summary of one reference in each of two require-level `inScope` containers, each histogram breaks its
  ties and fetches its groups by the summary that requested it, where the last summary's settings applied to all. A
  REST query body repeating a property is answered with HTTP 400. GraphQL returns an unwrapped reference histogram
  defined in the searched scopes. The GraphQL, REST and gRPC schema descriptions of `minReferencedEntity` and
  `maxReferencedEntity` say what the APIs return; the text change alone changes no response. The upgrade note is on
  #2035.
- **Known corner, the override rule.** A histogram declared by a generic summary in `inScope(LIVE, …)` takes the
  controls of a reference-specific summary in `inScope(ARCHIVED, …)`, so its ties are broken by a sorter built in
  ARCHIVED. That is what the rule of `2026-09-05-duplicate-content-requirements-reduction` says; no test asks for
  anything else.
- **Visible to clients, unmanaged referenced types.** A reference summary with a histogram over a reference to an
  entity type evitaDB does not manage returns the histogram without boundary entities, where the whole query failed
  with `EntityCollectionRequiredException`. An `entityFetch` inside such a histogram is refused at planning.
- **Open, left by #2035** - found by its adversarial code reviews, outside the change:
  - #2062: a reference whose group type is unmanaged loses its histogram - the group comes back with its facets and
    without the histogram. Shown red; the routing behind it (the index writer stores the values under the group's
    primary key, `ReferenceHistogramAccumulator#computeForReference` reads only a managed group per group) is read
    from the code. Neither side is changed by #2035.
  - **Suspected:** a managed referenced type whose collection does not exist yet - allowed until the schema is
    validated when the session closes - would fail the boundary fetch the way an unmanaged one did.
  - #2053: a `histogramHaving` keeps no scope, so `or(inScope(LIVE, histogramHaving(…)), inScope(ARCHIVED,
    histogramHaving(…)))` on one histogram is refused as two siblings, and a range inside `inScope(ARCHIVED, …)`
    marks the buckets of a LIVE histogram `requested`. Both shown red.
  - **Suspected:** `PriceHistogramComputer` drops the cache-admission callback its clone receives, so a price
    histogram is probably never cached; the debug mode verifying alternative plans iterates its policies without
    using the loop variable (`QueryPlanner`).
  - **Suspected, external APIs:** GraphQL refuses a histogram requested at the root and in `inScope` of the one
    searched scope, and refuses a repeated reference summary.
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
- `2026-11-19-direct-entity-calls-check-requested-content` — closes the open `getEntity` follow-up above: the direct
  calls check the requested content next to the scope check this record added.
- `2026-05-06-reference-histogram-statistics`, `2026-11-19-one-histogram-store-per-group` — the reference histograms
  and the per-group store #2035 combines across the scopes a histogram is requested in. The boundary pick of the
  latter gained an owner filter for every histogram here (Option AA): an ungrouped pick checks every tied candidate
  through its partitions, outside the merge that record describes.
- `2026-09-05-duplicate-content-requirements-reduction` — the rule that a reference-specific summary governs its
  reference entirely, which the controls per histogram keep for a histogram the generic summary declares.
- `2026-11-20-reference-owner-scope` — the `referenceContent` ordering's `inScope` gates by the owner's scope; its
  refusals (nested orderings across gates, a reflected chain over several scopes) follow this record's "decide before
  any row" rule.

## Timeline

- **2024-12-18** — `ScopeEnforcer` introduced (`ca897c3a27`).
- **2026-10-05** — the residual check moved into the nested context (`90f3d80d22`), merged on 2026-10-08 in #1720.
- **2026-11-04** — #1880 analysed; the first implementation (Option C) replaced by Option A after review; the pre-flight
  and the refusal of an inert `scope` added after a second review, every filter of the require section after a third;
  #1883 split off; PR #1884 opened.
- **2026-11-05** — PR #1884 merged.
- **2026-11-10** — #1897: a `scope(...)` below `not` refused before the scopes are resolved; PR #1914 opened and
  merged.
- **2026-11-10** — #1883 fixed: the scope of fetched bodies resolved by one rule in the pre-flight, the planning check
  and the fetch. The red run found a false refusal of a correct query the issue had not expected.
- **2026-11-10** — #1915 split off the review of #1883 and folded into PR #1913. The first fix (Option M) measured
  worse at the group level; the project owner set the rule of Option Q, reviewed by an advisor and an adversarial
  Codex review of the design before it was implemented.
- **2026-11-10** — #1917: a `scope(...)` below `or` refused before scope lookup, with inert scope usage and
  negation retaining diagnostic precedence; entity levels enclosed by `or` keep their own scopes. PR #1919 opened.
- **2026-11-10** — #1917 extended within PR #1919 to the scope allow-list and per-level uniqueness after design
  review and placement probes. `userFilter` refusal accepted as a breaking change; reference row compilation made
  transparent through root conjunctions and single-child disjunctions. Placement across all sections precedes
  duplicate detection. Hierarchy-node and segment root-scope exceptions and the internal evaluator caveat recorded.
- **2026-11-11** — PR #1919 merged.
- **2026-11-13** — PR #1913 merged.
- **2026-11-17** — #1949: the direct calls check the requested content, recorded in
  `2026-11-19-direct-entity-calls-check-requested-content`.
- **2026-11-19** — #1920: probes on `dev` showed the fetch ignoring a require-level `inScope` and the narrowed checks
  depending on the data. Option R reviewed by an advisor and two adversarial Codex reviews, one of them writing its
  own design first and reaching the same contract; it moved the detection of the main fetch from the frame depth to
  the structure. The project owner chose R, accepted the refusal of the folded hierarchy bound and the `breaking
  change` label. #2027 split off, and #2035 from the review's histogram finding.
- **2026-11-20** — #2035: a failing test on `dev` showed the wrapped price and attribute histograms counting both
  scopes. The design was reviewed by an advisor and by an adversarial review that measured the reference histograms'
  overwrite and the scope-dependent `entityHaving`; the project owner chose Option X ("what you see is what you get")
  and refusal over merging. Implemented with a 40-test class red on `dev`, one counterfactual per mechanism, two
  review rounds and a code-quality pass.
- **2026-11-22** — #2035 review round: the review's histogram findings fixed in four rounds - owners of ungrouped
  boundaries, controls per histogram, scope-ordered ties, GraphQL scopes, REST duplicates - each red first with a
  counterfactual; one round reverted an engine relaxation the project owner had not decided (Option AF). The owner
  chose to confine every boundary pick (AA) after a benchmark and a debugger count showed no resolvable cost, and
  accepted that unstored referenced entities rank last. #2053 filed from the review's `histogramHaving` finding.
- **2026-11-23** — The external API descriptions of the boundary entities corrected to what the APIs return. A
  histogram over a reference to an unmanaged entity type, suspected while checking them, reproduced as a failing query
  and was fixed in the same pull request (Option AG); its review found the unmanaged-group gap, filed as #2062.
