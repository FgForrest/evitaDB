---
title: Fold duplicate content requirements once per request; refuse only the pairs that have no superset
date: 2026-09-05
updated: 2026-09-06 00:26
status: accepted
kind: fix
issues: [1493]
prs: []
areas: [evita_query/src/main/java/io/evitadb/api/query/require, evita_api/src/main/java/io/evitadb/api/requestResponse]
supersedes: []
superseded-by: []
relates: []
---

# Two content requirements of one kind in one `entityFetch` are folded, not dropped and not refused

An `entityFetch` may carry two content requirements of the same kind — two `attributeContent`, two `referenceContent`
for one reference, two `dataInLocales`. Until now the outcome depended on which kind it was: `referenceContent`
silently kept one of the two, five other kinds failed with a `MoreThanSingleResultException` raised by the constraint
finder, and `accompanyingPriceContent` collapsed to whichever came last. `EvitaRequest` now reduces the fetch **once
per request**, through a keyed fold on `EntityFetchRequire#combineDuplicateRequirements()`: siblings addressing the same
thing are combined into the one requirement the query executes with, and only a pair that has no superset — two
different filters, orders, pages, `stopAt` bounds or accompanying price-list sequences — is refused, with an
`EvitaInvalidUsageException` that names the conflict.

## Why

Nothing in the query language forbids the duplicate, and nothing in the API prevents a caller from producing one:
a query assembled from several helpers, or an `entityFetchAllContentAnd(attributeContent("code"))` shorthand, arrives
at the engine with two requirements of one kind side by side. The three ways evitaDB then reacted were all wrong in
different directions, and the worst of them was silent.

The constraint that made this non-obvious is that the same `combineWith` protocol serves **three** callers, not one:
the request-level reduction added here, the query planner's prefetch union
(`DefaultPrefetchRequirementCollector`), and the facet summary's extension of a default fetch with a
reference-specific one (`ReferenceSummaryProducer`). The last two unite requirements written in *unrelated* parts of
one query — `ReferenceHavingTranslator` adds a bare `referenceContent(<name>)` for every filtered reference and
`ReferenceOrderByVisitor` adds one carrying attributes — so a rule chosen for the user-authored case is immediately
also a rule about queries no user wrote as a duplicate at all. That is what defeated the first, stricter design (see
Option E).

### Previous state

| kind | behaviour with two siblings |
|---|---|
| `referenceContent` | silent drop: first-wins for the default map, last-wins for the per-name and named maps |
| `attributeContent`, `associatedDataContent`, `priceContent`, `hierarchyContent` | `MoreThanSingleResultException` |
| `dataInLocales` | `MoreThanSingleResultException` — a sixth kind the issue did not list |
| `accompanyingPriceContent` | a seventh kind the issue missed — the prefetch union kept only the last one |

The accompanying-price case was the loosest of them: `isCombinableWith` accepted *any* pair and `combineWith`
returned its second operand, so every price but the last was discarded without a word.

The combining machinery the fold was going to be built on had four defects of its own, all pre-existing:

- `ReferenceContent#combineWith` kept only the **receiver's** `filterBy` and `orderBy`, and dropped the chunking of
  both sides, when it rebuilt the constraint.
- `ReferenceContent#isFullyContainedWithin` could never return `true` — its success path fell through to the final
  `return false`.
- `ReferenceContent#isCombinableWith` accepted only two *unnamed single-name* constraints with the same name, so two
  `referenceContentAll()` or two occurrences of one aliased instance were never combined at all.
- `EntityFetch#combineWith` united two bodies through a containment-first collector, so inside a united body
  a `referenceContent("brand")` written beside a `referenceContentAllWithAttributes()` was dropped as "contained",
  while the same pair written once in a query kept both.

## Options considered

### Option A — one keyed reduction per request, superset-wins per kind (chosen)

`EvitaRequest` folds the fetch exactly once, on the first call that needs it, and every per-kind accessor reads the
folded result. The fold is keyed: requirements are combined only when `isCombinableWith` says they address the same
thing, which for `referenceContent` means the same reference key and for `accompanyingPriceContent` the same price
name. Within a key the richer side wins each dimension.

- **Pros:** one rule, stated once, for all seven kinds; no per-getter special cases; the stored query stays verbatim;
  the same fold serves `EntityFetch#combineWith`, so a united body behaves like a body written once.
- **Cons:** `combineWith` gains the right to throw, which callers that merely *merge* requirements have to be
  prepared for; the fold is shallow, so each nesting level pays for its own reduction.

### Option B — refuse any duplicate key outright (declined)

Treat two requirements of one kind as a usage error and throw, uniformly, with a good message.

- **Pros:** trivially predictable; no merge semantics to define, document or get wrong.
- **Cons:** rejects queries that are unambiguous and already work.
- **Rejected because:** it breaks `entityFetchAllContentAnd(attributeContent("code"))` and every other composition
  helper that adds a requirement beside a fetch-all shorthand — the shorthand already contains `attributeContentAll()`
  and a bare `hierarchyContent()`. It also refuses the prefetch union outright, where the duplicate is produced by
  evitaDB itself and the user has no way to avoid it.

### Option C — normalize inside the `EntityFetch` constructor (declined)

Fold at construction, so no `EntityFetch` instance can ever hold two requirements of one kind.

- **Pros:** the invariant holds everywhere by construction; no consumer has to remember to reduce.
- **Cons:** the constraint object stops being a faithful model of the text that produced it.
- **Rejected because:** it changes `toString()`, `equals()` and therefore the EvitaQL round-trip — a parsed query
  would no longer print as it was written, breaking traffic recording and query logging. It also moves a *usage*
  error into the parser and into `ConstraintCloneVisitor`, where an `EvitaInvalidUsageException` has no request
  context to name and no caller prepared to attribute it.

### Option D — six per-getter loops in `EvitaRequest` (declined)

Leave the constraint model alone and make each per-kind accessor tolerate duplicates by looping and merging locally.

- **Pros:** the smallest possible diff; each getter's rule sits next to the getter.
- **Cons:** the reduction is invisible to anything that is not one of those getters.
- **Rejected because:** every other consumer of `getEntityRequirement()` — the derived requests built for referenced
  entities, the prefetch union, the facet summary — keeps seeing the raw duplicates and has to re-derive the same
  rules. The reference key rule in particular would have to be written a second time inside
  `getReferenceEntityFetch()`, and the two copies would drift.

### Option E — strict equality for a one-sided `filterBy`, `orderBy` or chunking (declined)

The original plan for this work: within one reference key, `filterBy`, `orderBy` and chunking must be **equal** on
both sides; any difference, including one side carrying nothing, is a conflict. The reasoning was that a filter is
a *selection*, not a richness level, so dropping it changes which references come back — the very silent loss this
issue is about.

- **Pros:** no query can lose a filter it asked for; the refusal message is unambiguous.
- **Cons:** "one side carries nothing" is not a disagreement about *which* references, it is a request for all of
  them.
- **Rejected because:** the quality gate found that the same `combineWith` serves the prefetch union and the facet
  summary merge, where `referenceHaving` and reference ordering inject a bare `referenceContent(<name>)`. Under
  strict equality a perfectly valid query that filters or pages that same reference in its own `referenceContent` was
  refused as a conflict, and the old code had only been hiding this by silently keeping the receiver's filter. Two
  modes — strict at request level, superset for the prefetch — were considered and declined as API growth that would
  also be inconsistent: a nested body would then reduce under a different rule than the container holding it. The
  `hierarchyContent` precedent from #1365 already answered the same question for `stopAt`, so the union follows it.

### Option F — superset-wins also for two *different* filters (declined)

Extend the superset rule all the way: when both sides carry a filter, keep their disjunction (or conjunction) instead
of refusing.

- **Pros:** no query would ever fail; the fold would be total.
- **Cons:** neither combinator expresses what either side asked for.
- **Rejected because:** a disjunction returns references the first requirement excluded and a conjunction returns
  fewer than either asked for. No union preserves both intents, so the disagreement has to surface. The same argument
  covers two different orders, two different pages and two different accompanying price-list sequences, where the list
  is a priority order and any merge invents a priority.

## Decision

**Chosen: Option A.** The drivers were the silent drop and the asymmetry it carried, and the only way to remove both
without rejecting queries that already work is a defined merge with a narrow, explicit refusal. Keying the merge is
what makes one rule cover all seven kinds: `referenceContent` and `accompanyingPriceContent` may legitimately occur
several times in one container, and they say so through their own key rather than through an exception in the fold.

Per-kind rules the fold applies:

| kind | combined | refused |
|---|---|---|
| `attributeContent`, `associatedDataContent` | names united, "all" absorbs | never |
| `dataInLocales` | locales united, `dataInLocalesAll` absorbs | never |
| `priceContent` | richer fetch mode, price lists united | never |
| `hierarchyContent` | bodies united, one-sided `stopAt` dropped | two different `stopAt` |
| `referenceContent`, same key | attributes and bodies united | different filter, order or page |
| `referenceContent`, overlapping keys | never combined | both claiming one reference |
| `accompanyingPriceContent`, one name | equal price lists collapse | different price lists |

Two details the table cannot hold. Reference attributes and nested bodies are united **recursively**, so a nested
`entityFetch` is itself folded when the derived request is built. A disagreement about
`ManagedReferencesBehaviour` narrows to `EXISTING`, so a request to suppress references pointing at missing entities
is never lost by folding. Two `accompanyingPriceContent` requirements naming *different* prices are the normal case
and both survive; only two lists for one name can conflict, and two lists differing only in order conflict too,
because the sequence is a priority order.

The superset rule and its one exception: a side carrying **no** `filterBy` and **no** chunking asks for every
reference, so it is the superset and the one-sided constraint is dropped — exactly as `attributeContentAll()` swallows
an `attributeContent("code")` written beside it, and exactly as `hierarchyContent()` swallows a bounded sibling. An
`orderBy` present on one side only is **kept** instead of dropped, because an order sequences the references without
removing any, so keeping it loses nothing.

`EntityFetch#combineWith` and `EntityGroupFetch#combineWith` are the same fold applied to the concatenation of both
sides. `EntityContentRequireCombiningCollector` had no other caller and was deleted.
`DefaultPrefetchRequirementCollector` keeps its containment check — a superset is exactly what prefetch wants.

For Option E to win again, `referenceHaving` and reference ordering would have to stop injecting bare
`referenceContent` requirements into the prefetch union, and the facet summary would have to stop extending a default
fetch with a reference-specific one. Both are load-bearing today.

## Key technical details

- **Where the reduction runs.** `EvitaRequest#isRequiresEntity()` performs it on first call and memoizes;
  `getEntityRequirement()` merely hands the result out. The derived copy constructor
  (`EvitaRequest(EvitaRequest, String, FilterBy, OrderBy, EntityFetchRequire)`) reduces before it stores the fetch
  **and** before it writes it into the derived query. `getQuery()` stays verbatim — traffic recording and query
  printing must reproduce what the client sent.
- **A refusal memoizes nothing.** When the fold throws, neither `requiresEntity` nor `entityRequirement` is
  assigned, so every later call re-attempts the reduction and fails identically instead of answering from a partial
  state. `getReferenceEntityFetch()` follows the same discipline: it builds the per-name map, the named map and the
  default into locals and publishes the fields only after the whole loop succeeded.
- **`MoreThanSingleResultException` is now an assertion, not a client error.** After the reduction the per-kind
  getters can only ever meet one requirement of a kind, so this exception raised from one of them means the reduction
  did not happen or did not cover that kind. Do not "fix" such a report by making the getter tolerant. Note that the
  type still extends `EvitaInvalidUsageException`, so an assertion failure would reach the client dressed as a usage
  error; reclassifying it was left out of this change because the class is shared with every other single-result
  lookup in the query API.
- **Containment is deliberately not consulted by the request-level fold.** A `referenceContent("category")` *is*
  contained within a `referenceContentAll()`, but the two are resolved through different lookups — the name-specific
  requirement wins for the reference it names and the default one is the fallback for the rest. Collapsing the
  specific into the default would silently widen the body fetched for `category`. This is the one place where the
  request-level fold and the prefetch union deliberately disagree.
- **Reduction depth is per request level.** The fold is shallow: it reconciles the direct children of the container
  it is called on. An `entityFetch` nested inside a `referenceContent` is reduced when the request for the referenced
  entity is derived, so each fetch scope is reduced by the request that executes it, not by its parent.
- **Duplicate-key internal errors in `getReferenceEntityFetch()`.** A second default requirement, or a named-instance
  key already present in the map, is impossible after the reduction and raises `GenericEvitaInternalError`. Two
  requirements with different but *overlapping* reference name sets both claiming one reference — `referenceContent`
  of `("a", "b")` beside one of `("b", "c")` — is a genuine usage error and raises `EvitaInvalidUsageException`
  naming the reference; a name repeated *inside* one requirement is not a conflict.
- **Where a refusal actually fires.** For a query the planner prefetches, the constraint-level `combineWith` runs
  first, inside `DefaultPrefetchRequirementCollector`, when `EntityFetchTranslator` hands the raw requirements to the
  prefetch union - so a same-key conflict is refused there, before `EvitaRequest` folds the fetch. The end-to-end
  refusal tests therefore guard the constraint rule; the request-level fold is guarded by the positive-path tests,
  which go red when it is disabled.
- **Only three surfaces can express the duplicate**: EvitaQL text, the Java API and gRPC (which transports EvitaQL).
  REST takes the entity fetch as an object keyed by the constraint name, and GraphQL turns an alias into a separate
  named reference instance, so neither can produce it.
- **User documentation**: `documentation/user/en/query/requirements/fetching.md`, section "Two content requirements
  of the same kind in one entityFetch". The Czech mirror is machine-translated and was not hand-edited.

## Verification

Unit and functional coverage, all with the mandated counterfactual (the production change disabled, the test observed
red, then restored):

| stage | green | counterfactual |
|---|---|---|
| `ReferenceContent` keyed rule | 33 + 10 + 4 | 8 red |
| request-level reduction | 437 | 13 red / 8 red on two disabled paths |
| quality gate fixes | 780 | 5 bugs red → green |
| end-to-end: Java API, EvitaQL text, driver over gRPC | 444 | 14 red without the fold, 5 without the visibility fix |

Test classes: `ReferenceContentTest`, `EntityFetchTest`, `EntityGroupFetchTest`, `EntityFetchRequireTest`,
`AccompanyingPriceContentTest`, `HierarchyContentTest`, `DefaultPrefetchRequirementCollectorTest`, the "Duplicate
content requirements" group of `EvitaRequestTest`, the prefetch-shape assertions added to
`EntityReferenceFetchFunctionalTest` and `EntityReferencePaginationFunctionalTest`, and the end-to-end
`EntityDuplicateContentRequirementFunctionalTest`.

A full functional-module run on the final tree executed 23,329 tests with 0 failures; the single error is
`ExportS3ServiceTest`, which needs a Docker daemon. (An earlier sweep on the quality-gate tree, 23,297 tests, also had
0 failures; its two extra errors were dataset setups starved of heap in a fork running 24 classes concurrently and
were green on isolated reruns. The final run used a fixed parallelism of 8 and a 12 GB fork heap.)

## Consequences & open follow-ups

User-visible behaviour changes, all of them in queries that previously failed or silently lost data:

- a query carrying two content requirements of one kind now succeeds where five of the seven kinds used to raise
  `MoreThanSingleResultException`; `entityFetchAllContentAnd(attributeContent("code"))` is one such query
- a `referenceContent` with a `filterBy` or a page, written beside a bare sibling for the same reference, now returns
  **all** references rather than the filtered or paged subset — the superset rule of Option A
- two siblings that genuinely conflict now fail with `EvitaInvalidUsageException` naming the conflicting part, where
  `referenceContent` used to keep one of them silently
- the prefetch union no longer drops all but the last `accompanyingPriceContent` when several name different prices
- `entityFetchAllContentAnd(hierarchyContent(stopAt(...)))` now fetches the **whole** parent chain, because the bare
  `hierarchyContent()` inside the shorthand is the superset

**Fixed in passing - the catch-all-beside-named shape hid the catch-all references.** The end-to-end specificity guard
(`referenceContentAllWithAttributes()` beside a bare `referenceContent` for one reference) exposed a defect older than
this branch: `ReferenceContractSerializablePredicate` and `EntityDecorator` each re-derived "did the client ask for this
reference?" from the per-name reference map alone, ignoring the default attribute request that signals a catch-all
requirement. The engine fetched the catch-all references, the predicate hid them behind a `ContextMissingException`,
and once admitted the decorator never built their chunk, so they came back empty. The rule now lives in the predicate
only (`isReferenceCovered`, `getRequestedReferenceNames`) and the decorator asks it. No test asserted the old
behaviour; every existing construction of that predicate passed a null default.

**Owed to the maintainer — one judgement call to confirm.** Dropping a one-sided `filterBy` or page is the point
where this work reversed its own plan (Option E above). It is the right rule for the prefetch and facet unions, and
it matches the `hierarchyContent` precedent, but at request level it means a user-authored
`referenceContent(<name>, filterBy(...))` beside a bare `referenceContent(<name>)` widens rather than refuses. The
alternative is two modes, which was declined for the reasons in Option E. This should be confirmed before the branch
merges.

**Pending textual conflict with PR #1370.** That PR (issue #1365) carries the same one-sided `stopAt` rule for
`HierarchyContent#combineWith` plus the parents-behaviour argument this branch does not have. The two edits touch the
same lines; resolve by taking #1370's version, which is the superset of this one. `isRequiresParent()` was left
untouched here for the same reason, so its per-getter combining loop becomes redundant-but-harmless once #1370 lands.

## Related work

- Issue #1365 / PR #1370 (`hierarchyContent` parents behaviour) — the source of the "the superset wins" precedent
  this record generalizes, and the branch this one must be merged with carefully. It has no decision record of its
  own yet.

## Timeline

- **2026-09-05** — issue #1493 studied; the reduction designed, implemented, hardened by a code-quality gate
  and documented.
