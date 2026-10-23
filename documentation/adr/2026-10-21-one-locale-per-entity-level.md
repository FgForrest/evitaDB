---
title: A filter names one locale per entity level, and a level without one inherits from its enclosing level
date: 2026-10-21
updated: 2026-10-21 16:20
status: accepted
kind: fix
issues: [925]
prs: []
areas:
  - evita_query/src/main/java/io/evitadb/api/query
  - evita_query/src/main/java/io/evitadb/api/query/filter
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_engine/src/main/java/io/evitadb/core/query
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/filter
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/facet
  - evita_engine/src/main/java/io/evitadb/core/query/algebra/facet
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_external_api/evita_external_api_graphql
  - evita_external_api/evita_external_api_rest
  - evita_external_api/evita_external_api_grpc/shared
supersedes: []
superseded-by: []
relates: [2026-10-20-reference-content-filter-row-by-row, 2026-10-08-scope-faithful-planning-and-facet-summary-parity]
---

# A filter names one locale per entity level, and a level without one inherits from its enclosing level

`entityLocaleEquals` used to be allowed once per query: every lookup searched the whole filter for a single
occurrence. A filter now has one locale **per entity level**. A level is the set of `entityLocaleEquals` that speak
about the same entity. A new level starts at a container whose children filter a different entity
(`DifferentEntityFilterContainer`: `entityHaving`, `groupHaving`, `hierarchyWithin`, `hierarchyWithinRoot`, and the
`includingChildrenHaving` / `includingChildrenExcept` of a hierarchical `facetHaving`).

- Equal opinions at one level coalesce; different ones are refused.
- Levels may differ from one another.
- A level without an opinion inherits the resolved locale of its enclosing level, never the other way round.

## Why

The query of #925 could not be written: products in `cs` whose `referenceContent` fetches brands filtered and ordered
by their English name. A reference-level `entityLocaleEquals(cs)` and an `entityHaving(entityLocaleEquals(en))` made
every lookup throw `MoreThanSingleResultException`, and so did the same locale written twice.

Fixing the lookup alone was not enough. Building the fix found propagation paths that ignored a nested level's own
locale or inherited from the wrong level:
- nested query planning;
- the hierarchy parent filter;
- the nested `entityHaving` / `groupHaving` of a `referenceContent` filter;
- the planning-time check of named localized data in nested bodies.

The rule had to be stated once and applied at every site, or the sites would keep disagreeing.

### Previous state

- Each site had its own finder: `QueryUtils#findFilter` / `findConstraint`, either without a stopper or stopping at
  every `SeparateEntityScopeContainer`. Each expected at most one `entityLocaleEquals`.
- A locale written only inside `entityHaving` / `groupHaving` / a hierarchy filter became the main query's locale.
- The derived request for nested query planning ignored the nested filter's own locale. `hierarchyWithin` always
  appended the enclosing locale to its parent filter, so a hierarchy level with its own locale got two.
- In a `referenceContent` filter, a localized reference attribute read the locale found directly in the filter **or
  directly inside its `entityHaving`** (decision Q1 of `2026-10-20-reference-content-filter-row-by-row`).
- Group bodies, and the `EXISTING` gate for groups, used the same locale as the referenced entity (released since
  2026.2.1).
- Named localized attributes and associated data of a nested `entityFetch` / `entityGroupFetch` were checked against
  the main request, so a nested body fetched in `en` was refused in a query without a locale.
- A direct `entityLocaleEquals` inside `facetHaving` filtered nothing: the facet filter is evaluated in the
  referenced-type index, where the leaf translates to a skip. It only set the query's locale. A locale inside its
  `includingChildrenHaving` / `includingChildrenExcept` did the same, although it speaks about the subcategories.
- `facetHaving` was not a `SeparateEntityScopeContainer`, unlike `referenceHaving`: its facet keys counted as the
  owner's keys for `entityPrimaryKeyInFilter` ordering (a query with owner keys failed, one without was ordered by
  facet keys), and its reference attributes for `attributeSetInFilter` ordering.

## Options considered

### Option A — strict levels: own opinion, else the enclosing level (chosen)

Locales flow only downward. Each level resolves its own opinion with one resolver; without one it inherits the
enclosing level's resolved locale; an explicit override (an API's locale argument) wins.

- **Pros:** one rule for every site — the main query, nested planning, hierarchy filters, `referenceContent` rows,
  bodies, ordering, summaries, planning checks and the API converters. A nested filter cannot change the meaning of
  the filters around it, and no tie rule is needed.
- **Cons:** reverses two earlier behaviours (see Consequences). A reference-level locale and an entity-level locale
  can now legitimately differ, so a reference attribute and the referenced body may be read in different locales.

### Option B — adopt upward everywhere (declined)

A level without an opinion first adopts the locale of its nested levels (when they agree), then inherits.

- **Pros:** keeps today's results for every query that wrote its locale only in a nested level.
- **Cons:** inheritance runs both ways; two nested levels with different locales need a tie rule; whether the main
  query's localized attributes are fetched in `en` depends on a filter about a different entity.
- **Rejected because:** it makes the meaning of a level depend on what its children filter, which is exactly the
  coupling #925 had to remove. The tie rule would refuse queries that only filter two different related entities.

### Option C — adopt upward inside `referenceContent` only (declined)

Keep Q1 of the row-by-row record (reference attributes, groups and the `EXISTING` gate take `entityHaving`'s locale
when the reference level has none) and use strict levels everywhere else.

- **Pros:** no change for `referenceContent` filters written for the row-by-row work, nor for the released group
  behaviour.
- **Cons:** two rules for the same constraint, depending on where the filter sits.
- **Rejected because:** Q1 was chosen so that the leaves, the body fetch, the `EXISTING` gate and the ordering would
  not split for `entityHaving(entityLocaleEquals(L))`. Under levels that split is the meaning, not an accident:
  - reference attributes belong to the reference level;
  - the body and its ordering belong to the entity level;
  - the group belongs to the group level.

  Each is consistent within its level. Revisit only if levels are abandoned.

### Option D — keep one locale per query (declined)

- **Rejected because:** the #925 query (owner in `cs`, referenced bodies in `en`) is a legitimate need, and the
  refusal of a repeated identical locale was an accident of the finder, not a rule.

## Decision

**Chosen: Option A.** It is the only option with one rule, and the rule is what every site now calls. The maintainer
decided it on 2026-10-20 ("a new locale level starts on each different entity level") and refined it the same day to
"inherit from the enclosing level". It was confirmed again on 2026-10-21, after the reversals were laid out.

The same rule gives the direct `entityLocaleEquals` of `facetHaving` its meaning. `facetHaving` does not start a level,
so the leaf is an opinion of the owner level and now requires the owner to hold the locale, as in `referenceHaving`.
The maintainer folded this into the same change instead of leaving the leaf a silent no-op.

`facetHaving` and `referenceHaving` behave alike in the rest, too, by the maintainer's choice of 2026-10-21 ("these
two constraints are similar in behavior — couldn't we lean on [the same marker]?"):
- `facetHaving` takes `referenceHaving`'s `SeparateEntityScopeContainer` marker, so its keys, reference attributes and
  scopes stay out of the owner's lookups;
- the child-category filters `includingChildrenHaving` / `includingChildrenExcept` take the
  `DifferentEntityFilterContainer` marker of the other containers whose children filter another entity.

Declined at the same time: a dedicated fix of the facet-key ordering bug for the released line. Every variant is an
edge case, so the fix ships with this change in 2026.3 only.

## Key technical details

- **`QueryUtils#resolveLocale`** gives the own opinion of one level:
  - equal opinions coalesce;
  - different ones (also under `or`) raise `EvitaInvalidUsageException` naming both locales and the filter;
  - it stops at `DifferentEntityFilterContainer`.

  Every lookup site uses it; inheritance stays at each site. `QueryUtils#resolveReferenceBodyLocale` adds the
  entity / group body chain of a reference filter (body level, then reference level, then enclosing).
- **The two markers are different boundaries.** `DifferentEntityFilterContainer` extends
  `SeparateEntityScopeContainer`, but `referenceHaving` and `facetHaving` isolate entity keys and `entityScope`
  (Separate) while their direct children keep the owner's locale (not Different). Do not unify the stop predicates.
  `QueryUtils#startsSeparateEntityContext` exempts both from the nested-scope check: their direct children filter the
  owner's references, so an `inScope` there is still the owner's and is refused.
- **`segment(entityHaving(...))`** is a level of its own, although that `entityHaving` filters the queried entities
  themselves. Its marker is not special-cased for one parent; the maintainer judged the case too rare to justify it.
- **`EvitaRequest`:** both derivation constructors resolve the nested filter's own opinion before inheriting. A failed
  resolution is not memoized, so a conflict is refused on every access. `QueryPlanner#selectIndexes` resolves the
  locale even for key-only queries, so a conflict is never silently ignored.
- **`QueryPlanningContext#computeOnlyOnce`:** the root formula cache key includes the caller's locale. Identical
  predicates evaluated in different inherited locales must not share an answer.
- **`NestedSet`** passes the reference level's locale as **context**, not as an added `entityLocaleEquals`. An
  `attributeIsNull` on a target without data in that locale must still match.
- **Planning checks of named localized data in nested bodies** derive the body's own request:
  - `ExtraResultPlanningVisitor#getEntityFetchRequest` walks the fetch chain outermost inward;
  - `deriveBodyRequest` computes the parent's required locales first, so an owner-level `dataInLocales` still
    licenses nested named data;
  - the reference summary translators derive their entity / group requests from their own `filterBy` /
    `filterGroupBy`.

  A body whose level resolves no locale and requests none is still refused.
- **The `facetHaving` owner leaf:**
  - `FacetHavingTranslator#translateOwnerLocale` splits the facet filter on the locale into a true and a false branch,
    each wrapped in `FacetLocaleFormula(owner guard, facet selection)`, which keeps `and` / `or` / `not` exact.
  - A query without such a leaf keeps the previous facet evaluator and pays nothing.
    `MemoizingFacetCalculator` decides once per summary whether any guard exists.
  - Facet summary counts and impacts keep the guard (`FacetLocaleFormula#retainOwnerConditions`). They project it
    only from inside `userFilter`, because the mandatory part is already in the base formula. A disjunct that selects
    no facet of the reference does not void the guard.
  - A branch that accepts **any** facet (a bare locale) is an OR of plain `ConstantFormula`s over the facet owner
    bitmaps, never of `FacetGroupFormula`s. The summary generators read every `FacetGroupFormula` in the user filter
    as a selected group and enrich it, which made the predicted impact differ from the query with the facet selected.
- **The Java driver** reconstructs body predicates (`EntityConverter`, `ResponseConverter`) by the same rule, so a
  driver and a server must agree on it for queries whose levels name different locales.

## Verification

- **`EntityLevelLocaleFunctionalTest`** uses a bilingual fixture with opposite matches and sort orders, and asserts its
  premises. It covers:
  - repeated and conflicting opinions;
  - every boundary;
  - owner-level `referenceHaving` / `facetHaving`;
  - a nested-only locale;
  - the hierarchy parent and specifications;
  - `referenceContent` row inheritance, body fetch and ordering, and two levels of nesting;
  - summary bodies;
  - the facet owner leaf under `and` / `or` / `not`, facet-summary counts equal to the selected query's in both
    summary spellings, and a negated mandatory `facetHaving`;
  - GraphQL, REST and gRPC.
- **`ReferenceBodyLocaleFunctionalTest`** covers named attributes and associated data of nested bodies: the reference
  level, `entityHaving` / `groupHaving`, inherited and local `dataInLocales`, depth two, summaries and hierarchy
  bounds. It also holds a nine-case guard that the `entityHaving` cache key still separates two references to one
  target type under the default, index-scan and prefetch routes.
- **`FacetChildrenLocaleFunctionalTest`**: a locale inside `includingChildrenHaving` / `includingChildrenExcept`
  filters the subcategories without setting or conflicting with the product locale; a localized subcategory
  attribute without one inherits the product locale.
- **`FacetPrimaryKeyInFilterFunctionalTest`**: facet keys inside `facetHaving` (in `userFilter` and directly in
  `filterBy`) neither fail nor drive `entityPrimaryKeyInFilter` / `attributeSetInFilter` ordering, matching the same
  query with `referenceHaving`.
- **`EntityLevelLocaleFunctionalTest$FacetLocales#shouldPredictSelectedQueryWhenLocaleBranchAcceptsAnyFacet`**: the
  impact of a facet equals the query with it selected, plain and negated (before the fix: 4 instead of 3, 3 instead
  of 2).
- **Unit tests:** `FacetLocaleFormulaTest`, `QueryUtilsTest$LocaleTest`, `QueryUtilsTest$ReferenceBodyLocaleTest`,
  `QueryUtilsTest$AssertNoNestedScopeContainersTest`, `EvitaRequestTest$LocaleInheritanceTest`.
- **The #925 regression:**
  `EntityDeepFetchFunctionalTest#shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedViaGetEntity`.
- **Counterfactuals:** each new test was seen to fail against a deliberately broken implementation. The breaks were:
  - a single finder;
  - a resolver descending the marker;
  - first locale taken;
  - the owner visitor instead of the reference context;
  - a locale-blind cache key;
  - the old propagation;
  - main-request validation;
  - the skipped facet leaf;
  - a guard dropped from the counts.
- **Targeted regression** of the affected areas: 3,351 tests, 0 failures.
- **Full functional module:** 29,534 tests on the final tree. Two cases of `ReferenceRowFilterCompilerTest` still
  encoded Q1 of the row-by-row record (a locale inside `entityHaving` read by the leaves; two levels with different
  locales refused) and were rewritten to the levels (39 / 0 / 0). The only failures, `CollationKeyCacheSweeperTest`,
  `CatalogGoLiveSessionDrainTest`, `EvitaOnDemandTrafficRecordingTest` and `SystemGraphQLSubscriptionsFunctionalTest`,
  time out under full-suite load and pass alone (32 / 0 / 0).

## Consequences & open follow-ups

Upgrade-visible (also in the upgrade note on #925):

- A locale written only inside `entityHaving` / `groupHaving` / a hierarchy filter no longer sets the main query's
  locale.
- Group bodies and the `EXISTING` gate for groups no longer follow `entityHaving`'s locale. They use `groupHaving`'s,
  else the reference filter's, else the owner's (released behaviour since 2026.2.1).
- A localized reference attribute in a `referenceContent` filter reads the reference level's locale, no longer the one
  inside `entityHaving`. This supersedes decision Q1 of `2026-10-20-reference-content-filter-row-by-row` (dev only);
  the rest of that record stands.
- A direct `entityLocaleEquals` in `facetHaving` now filters owners.
- A locale inside `includingChildrenHaving` / `includingChildrenExcept` is the subcategories' and no longer sets or
  conflicts with the query's locale.
- The keys inside `facetHaving(..., entityPrimaryKeyInSet(...))` are facet keys: `entityPrimaryKeyInFilter` no longer
  reads them.
- A locale inside a segment's `entityHaving` also decides the locale in which that segment's filter reads localized
  attributes.
- Different locales at one level fail with `EvitaInvalidUsageException` instead of `MoreThanSingleResultException`;
  the same locale repeated is accepted.

Known, not part of this change:

- Named localized associated data with `dataInLocalesAll()` returned no localized value
  (`DefaultEntityCollectionPersistenceService#fetchAssociatedData` treated the empty "all" set as no locale). It is
  pre-existing and fixed separately in #1783; tests here use explicit locales.

## Related work

- `2026-10-20-reference-content-filter-row-by-row`:
  - its decision Q1 (the locale of a localized leaf) is superseded by this record;
  - its suspicion that "a nested query ignores its own filter's `entityLocaleEquals`" is confirmed and fixed here.
- `2026-10-08-scope-faithful-planning-and-facet-summary-parity`: facet-only filters keep their pooled-reference
  semantics and group relations; the owner guard is added beside them, never inside the facet selection.

## Timeline

- **2026-10-20** — rule decided; `entityHaving` / `groupHaving` take several children; the #925 regression test.
- **2026-10-21** — levels applied at every site; strict levels re-confirmed; the `facetHaving` owner leaf and the
  nested-body planning checks; `facetHaving` takes `referenceHaving`'s marker, the child-category filters become
  levels, and the impact of an any-facet branch is fixed.
