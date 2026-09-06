---
title: Fold duplicate content requirements once per request, refuse the pairs that contradict, widen only the prefetch
date: 2026-09-05
updated: 2026-09-06 15:35
status: accepted
kind: fix
issues: [1493]
prs: []
areas: [evita_query/src/main/java/io/evitadb/api/query/require, evita_query/src/main/java/io/evitadb/api/query/visitor,
  evita_api/src/main/java/io/evitadb/api/requestResponse,
  evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference/producer,
  evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/hierarchyStatistics,
  evita_engine/src/main/java/io/evitadb/core/query/filter/translator/hierarchy,
  evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram,
  evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/requestResponse,
  .claude/rules/constraint-resolution.md]
supersedes: []
superseded-by: []
relates: []
---

# Two content requirements of one kind in one `entityFetch` are folded, and two rules decide how

An `entityFetch` may carry two content requirements of the same kind — two `attributeContent`, two `referenceContent`
for one reference, two `dataInLocales`. Until now the outcome depended on which kind it was: `referenceContent`
silently kept one of the two, five other kinds failed with a `MoreThanSingleResultException` raised by the constraint
finder, and `accompanyingPriceContent` collapsed to whichever came last. `EvitaRequest` now reduces the fetch **once
per request**, through a keyed fold on `EntityFetchRequire#combineDuplicateRequirements()`.

The reduction obeys two rules, and the whole of this record is about keeping them apart:

- **What the client receives is folded strictly.** Siblings addressing the same thing are united where a union
  exists, and a pair that contradicts — two different filters, orders, pages, `stopAt` bounds or accompanying
  price-list sequences, or a restriction only one of them names — is refused with an `EvitaInvalidUsageException`
  that names the conflict.
- **What the engine loads is widened freely.** The query planner contributes requirements of its own, and they enter
  the prefetch union stripped of every output projection, so they can never collide with the client's.

A third rule, of the same family and settled by the same work: **a specific constraint replaces the generic one for
its target, it never inherits from it.** That is why a reference-specific `referenceSummary` now defines all of its
own requirements.

The three rules were then enforced across the rest of the codebase — see *The generalisation* below. They are stated
for day-to-day use, with every site that implements them, in `.claude/rules/constraint-resolution.md`; this record is
the reasoning behind them and the alternatives that lost.

## Why

Nothing in the query language forbids the duplicate, and nothing in the API prevents a caller from producing one:
a query assembled from several helpers, or an `entityFetchAllContentAnd(attributeContent("code"))` shorthand, arrives
at the engine with two requirements of one kind side by side. The three ways evitaDB reacted were all wrong in
different directions, and the worst of them was silent.

The constraint that made this non-obvious is that the same `combineWith` protocol served **several** callers, not
one: the request-level reduction added here, the query planner's prefetch union (`DefaultPrefetchRequirementCollector`)
and the summary's extension of a generic fetch with a reference-specific one (`ReferenceSummaryProducer`). The latter
two unite requirements written in *unrelated* parts of one query — `ReferenceHavingTranslator` adds a bare
`referenceContent(<name>)` for every filtered reference and `ReferenceOrderByVisitor` adds one carrying the sort
attribute — so a rule chosen for the user-authored case is immediately also a rule about queries no user wrote as a
duplicate at all. Separating the callers, rather than finding one rule that suits them all, is the decision this
record exists to explain.

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

### Option A — one keyed reduction per request, with a separate rule per consumer (chosen)

`EvitaRequest` folds the fetch exactly once, on the first call that needs it, and every per-kind accessor reads the
folded result. The fold is keyed: requirements are combined only when `isCombinableWith` says they address the same
thing, which for `referenceContent` means the same reference key and for `accompanyingPriceContent` the same price
name. Each consumer of the protocol gets the rule its own question deserves — strict for the response, widening for
the prefetch.

- **Pros:** one rule per question, stated once, for all seven kinds; no per-getter special cases; the stored query
  stays verbatim; the same fold serves `EntityFetch#combineWith`, so a united body behaves like a body written once.
- **Cons:** `combineWith` gains the right to throw, which callers that merely *merge* requirements have to be
  prepared for; the fold is shallow, so each nesting level pays for its own reduction; two rules are more to
  document than one.

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

### Option E — one shared rule, superset-wins for a one-sided restriction (declined after being shipped once)

Let a `filterBy` or a page carried by one side only be **dropped**, on the grounds that the side carrying neither
asks for every reference and is therefore the superset — the rule `attributeContentAll()` already has over
`attributeContent("code")`, and the one #1365 chose for `hierarchyContent`'s `stopAt`. One rule, every caller.

This version shipped first, because the quality gate showed a strict rule refusing queries nobody wrote as
duplicates. It was then reverted on the maintainer's ruling, once the shape of the problem was measured properly.

- **Pros:** a single rule to document; no caller of `combineWith` has to be prepared for a refusal.
- **Cons:** the client's own `referenceContent("category", filterBy(...))` beside a bare sibling silently returns
  **every** category. A filter is a selection, not a richness level, and the silent widening is the same class of
  loss issue #1493 exists to remove.
- **Rejected because:** the conflict it was avoiding is not the client's at all — it belongs entirely to the
  prefetch union, and it is avoidable there. Measured on this branch: with the strict rule and no split,
  `orderBy(referenceProperty(CATEGORY, attributeNatural("categoryPriority", DESC)))` written beside
  `entityFetch(referenceContent(CATEGORY, filterBy(entityPrimaryKeyInSet(5)), entityFetch(attributeContent("code"))))`
  is refused **during planning**, on the index path as well as the prefetching one, because
  `ReferenceOrderByVisitor` contributes `referenceContentWithAttributes(CATEGORY, attributeContent("categoryPriority"))`
  and the collector reconciles it with the client's requirement using the client's rule. Stripping the output
  projections at the door of the collector removes that collision without spending the client's semantics on it.

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
without rejecting queries that already work is a defined merge with an explicit refusal — plus a separate,
deliberately lax rule for the requirements the planner invents. Keying the merge is what makes one rule cover all
seven kinds:
`referenceContent` and `accompanyingPriceContent` may legitimately occur several times in one container, and they say
so through their own key rather than through an exception in the fold.

Per-kind rules the client-facing fold applies:

| kind | combined | refused |
|---|---|---|
| `attributeContent`, `associatedDataContent` | names united, "all" absorbs | never |
| `dataInLocales` | locales united, `dataInLocalesAll` absorbs | never |
| `priceContent` | richer fetch mode, price lists united | never |
| `hierarchyContent` | bodies united, one-sided `stopAt` dropped | two different `stopAt` |
| `referenceContent`, same key | attributes and bodies united | one-sided or differing filter/page; differing order |
| `referenceContent`, overlapping keys | folded per shared reference name | the shared name's filter, order or page |
| `accompanyingPriceContent`, one name | equal price lists collapse | different price lists |

Two details the table cannot hold. Reference attributes and nested bodies are united **recursively**, so a nested
`entityFetch` is itself folded when the derived request is built. A disagreement about `ManagedReferencesBehaviour`
narrows to `EXISTING`, so a request to suppress references pointing at missing entities is never lost by folding.
Two `accompanyingPriceContent` requirements naming *different* prices are the normal case and both survive; only two
lists for one name can conflict, and two lists differing only in order conflict too, because the sequence is
a priority order.

**One asymmetry inside the strict rule, and it is deliberate.** An `orderBy` present on one side only is **kept**,
not refused, because an order sequences the references without removing any: keeping the only order present hides
nothing from either sibling. A `filterBy` or a page is a selection, and a selection named by one sibling has no union
with a sibling that named none — dropping it returns records the restricting side excluded, honouring it hides records
the unrestricted side asked for, and neither may be chosen on the client's behalf.

**`hierarchyContent` keeps the superset rule for `stopAt`**, unlike `referenceContent`. The reason is the shorthand:
`entityFetchAllContent()` contains a bare `hierarchyContent()`, so refusing a one-sided `stopAt` would break
`entityFetchAllContentAnd(hierarchyContent(stopAt(...)))`, a documented composition. The `referenceContent` the same
shorthand contains is a *catch-all*, which carries a different key and is never folded with a name-specific sibling,
so strictness there costs nothing. #1365 is the precedent for the `stopAt` half and stays untouched.

`EntityFetch#combineWith` and `EntityGroupFetch#combineWith` are the same fold applied to the concatenation of both
sides. `EntityContentRequireCombiningCollector` had no other caller and was deleted.

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
- **The prefetch union has its own door, and it is one line.** Every requirement entering
  `DefaultPrefetchRequirementCollector` is admitted through `EntityContentRequire#forPrefetch()`, which returns the
  requirement unchanged for every kind but `ReferenceContent`, where it drops `filterBy`, `orderBy` and chunking.
  A prefetch requirement is a lower bound — "load at least this" — so widening it can never make an answer wrong,
  while narrowing it can: an in-memory `referenceHaving` evaluated against a filtered or paged subset of the
  references would match the wrong entities.
- **Why the widening is invisible, which is what makes it safe.** The prefetched entity is narrowed back down from
  the client's own request: `EntityCollection#limitEntityInternal` builds fresh predicates from it, and the
  reference filter, order and page are read from `evitaRequest.getReferenceEntityFetch()` through
  `ServerChunkTransformerAccessor`. Measured with `debug(DebugMode.PREFER_PREFETCHING)`: a client
  `referenceContent(CATEGORY, filterBy(entityPrimaryKeyInSet(5)))` returns exactly that one reference and
  `page(1, 1)` exactly one, identical to the index path. `EntityCollection#fetchEntityDecorator` does not fetch
  referenced entity bodies at all, so the stripping costs nothing at fetch time either.
- **`forPrefetch()` is shallow on purpose.** A `referenceContent` nested inside another's `entityFetch` keeps its
  restrictions, because only *top-level* requirements are planner-contributed: two nested siblings that disagree
  were both written by the client, and refusing them is the client rule doing its job.
- **`MoreThanSingleResultException` is now an assertion, not a client error.** After the reduction the per-kind
  getters can only ever meet one requirement of a kind, so this exception raised from one of them means the reduction
  did not happen or did not cover that kind. Do not "fix" such a report by making the getter tolerant. Note that the
  type still extends `EvitaInvalidUsageException`, so an assertion failure would reach the client dressed as a usage
  error; reclassifying it was left out of this change because the class is shared with every other single-result
  lookup in the query API.
- **Containment is deliberately not consulted by the request-level fold.** A `referenceContent("category")` *is*
  contained within a `referenceContentAll()`, but the two are resolved through different lookups — the name-specific
  requirement wins for the reference it names and the default one is the fallback for the rest. Collapsing the
  specific into the default would silently widen the body fetched for `category`. The prefetch union does consult
  containment, and should: a superset is exactly what it wants.
- **Reduction depth is per request level.** The fold is shallow: it reconciles the direct children of the container
  it is called on. An `entityFetch` nested inside a `referenceContent` is reduced when the request for the referenced
  entity is derived, so each fetch scope is reduced by the request that executes it, not by its parent.
- **Overlapping name sets are folded per name, not refused.** `referenceContent("a", "b")` beside
  `referenceContent("b", "c")` share no key, so the request projects each requirement onto every name it lists
  (`ReferenceContent#forReferenceName`) and folds the projections per name through the ordinary `combineWith`. Only a
  genuine disagreement inside the shared name still refuses. A name repeated *inside* one requirement folds with
  itself and claims the reference once.
- **A reference-specific summary inherits nothing.** `ReferenceSummaryProducer#resolveReferenceRequest` returns
  a registered reference-specific `ReferenceSummaryRequest` as it is; only a reference with no specific request of
  its own is built from the generic summary through `buildFromDefault`. This is what the `ReferenceSummary` and
  `FacetSummary` class javadoc has always promised ("completely overrides … the constraints are not merged") and
  what the code did not do.
- **The histogram path can no longer drift from the facet path.** The group entity fetcher, the group predicate and
  the facet sorter read one request resolved once per reference. Before, the fetcher took the specific request
  directly while the predicate fell back to the generic one, so a histogram-only group synthesized at facet
  statistics depth `NONE` came back as a bare `EntityReference` while the same query at `COUNTS` returned a full
  body. The facet sorter reaching the fan-out path is a behaviour change of its own: a tie on a histogram boundary
  value (`ReferenceHistogramAccumulator#intersectAndPickBoundaryPk`, which decides
  `HistogramContract#getMinReferencedEntity()`) is now broken by the generic summary's `orderBy` instead of by the
  lowest primary key. It costs nothing — `buildFromDefault` already built that sorter and the old resolver discarded
  it.
- **Where a refusal actually fires.** For a query the planner prefetches, the constraint-level `combineWith` runs
  first, inside `DefaultPrefetchRequirementCollector`, when `EntityFetchTranslator` hands the raw requirements to the
  prefetch union — so a same-key conflict between two of the client's own requirements is refused there, before
  `EvitaRequest` folds the fetch. The end-to-end refusal tests therefore guard the constraint rule; the
  request-level fold is guarded by the positive-path tests, which go red when it is disabled.
- **Only three surfaces can express the duplicate**: EvitaQL text, the Java API and gRPC (which transports EvitaQL).
  REST takes the entity fetch as an object keyed by the constraint name, and GraphQL turns an alias into a separate
  named reference instance, so neither can produce it.
- **User documentation**: `documentation/user/en/query/requirements/fetching.md`, section "Two content requirements
  of the same kind in one entityFetch", and `documentation/user/en/query/requirements/reference.md` for the summary
  override. The Czech mirror is machine-translated and was not hand-edited.

## The generalisation across the codebase

The three rules were derived from one constraint kind. An audit of every other place where a query can state the
same thing twice about one target found nine further groups of defects, and the maintainer's ruling was to fold all
of them into this issue rather than file them separately, as one commit each.

**Every finding was reproduced before it was fixed, and the reproduction is quoted in its commit.** That discipline
paid for itself three times over: the audit's reading was wrong or incomplete in three places, and two tests passed
for the wrong reason before the real behaviour surfaced (a type-only `assertThrows` that the *old* exception also
satisfied, because `MoreThanSingleResultException` extends `EvitaInvalidUsageException`; and a query asking for
`SealedEntity` where the response held `EntityReference`, which threw for an unrelated reason).

| What | Was | Now |
|---|---|---|
| `priceContent(NONE)` beside a fetching `priceContent` | folded to the fetching mode | refused |
| `accompanyingPriceContent()` beside one naming price lists | refused, but by accident of array comparison | refused with a message that says why |
| one GraphQL accompanying price name selected two ways | wrong price list returned — measured, not deduced | refused |
| `attributeHistogram` bucket count and behaviour | producer-wide, last write won | carried per attribute, disagreement refused |
| facet relation level | the declared level was ignored | honoured; `facetGroupsDisjunction` defaults to the level it actually changes |
| duplicated `priceHistogram` | refused only when the query filtered on price | decided on a path the planner always walks |
| two `hierarchyOf...` with different `orderBy` | last wins, retroactively re-sorting the sibling's output | refused; an order-less sibling no longer wipes the order |
| a gRPC output name owned by the second `hierarchyOf...` | `NullPointerException` inside the driver | resolved across every constraint that could own it |
| `page` beside `strip` | `page` won, the result form flipped, HTTP 200 over REST | refused |
| two hierarchy filters for one target, with statistics | statistics described a subtree the records were not restricted to | refused, but only when statistics are requested |
| one hierarchy output name claimed twice | `IllegalStateException` at fabrication, carrying both result trees | refused at planning, carrying no result data |

### Two placement rules came out of this, and they generalise

**A refusal has to sit on a path the planner always walks, or it is a coin flip.** The duplicated `priceHistogram`
was decided inside a branch that only runs when the query filters on price, so the identical pair threw for a
price-filtered query and returned silently for an attribute-filtered one. Moving the decision into
`PriceHistogramTranslator`, which runs for every requirement whatever the query filters on, is what made it a rule
rather than a symptom.

**A refusal belongs where the ambiguity is consumed, not where it is written.** Two hierarchy filters in one query
are an ordinary disjunction; they are ambiguous only for the code that must pick *one* of them to seed hierarchy
statistics. Putting the refusal in `EvitaRequest#getHierarchyWithin`, whose only production callers are extra-result
planning, keeps the filter legal and fails only the combination that has no answer. Refusing at the point of writing
would have cost filtering expressiveness to solve a problem filtering does not have.

### Rejected outright

| Option | Rejected because |
|---|---|
| Refuse two `hierarchyOf...` constraints for one target, matching GraphQL | The merge is deliberate and tested — `EvitaArchivingTest` merges a `LIVE` and an `ARCHIVED` `hierarchyOfReference(CATEGORY, …)` into one container. Refusing it would delete a working feature to settle a surface disagreement in the harder direction. Revisit only together with a decision on `HierarchyOfResolver`. |
| Fix `ReferenceContent#getChunking()`'s `findFirst()` | Unreachable by construction: every constructor takes a single `ChunkingRequireConstraint` and the `@Creator` marks `uniqueChildren = true`, so neither the fluent API nor the parser can build a `page` + `strip` pair inside one `referenceContent`. A guard there would be untestable. |
| Make `EvitaRequest#initPagination` eager so `page` + `strip` fails at construction | Pagination is memoised-lazy and four getters trigger it; making construction eager changes the cost of every derived request to improve the timing of one error. Every execution path calls one of those getters, so the pair still cannot be executed. |

## Verification

Unit and functional coverage, all with the mandated counterfactual (the production change disabled, the test observed
red, then restored):

| stage | green | counterfactual |
|---|---|---|
| `ReferenceContent` keyed rule | 33 + 10 + 4 | 8 red |
| request-level reduction | 437 | 13 red / 8 red on two disabled paths |
| quality gate fixes | 780 | 5 bugs red → green |
| end-to-end: Java API, EvitaQL text, driver over gRPC | 444 | 14 red without the fold, 5 without the visibility fix |
| overlapping name sets | 1015 | 7 red without the per-name fold |
| enrichment monotonicity | 490 | 3 red |
| strict client rule + prefetch widening | 349 | 5 red lenient, 7 red without `forPrefetch()`, 9 red with it identity |
| summary override | 611 + 511 | 12 red without the override, 1 red isolated to the facet sorter |

Test classes: `ReferenceContentTest`, `EntityFetchTest`, `EntityGroupFetchTest`, `EntityFetchRequireTest`,
`AccompanyingPriceContentTest`, `HierarchyContentTest`, `DefaultPrefetchRequirementCollectorTest`, the "Duplicate
content requirements" group of `EvitaRequestTest`, the prefetch-shape assertions added to
`EntityReferenceFetchFunctionalTest` and `EntityReferencePaginationFunctionalTest`, the end-to-end
`EntityDuplicateContentRequirementFunctionalTest` — whose "Requirements contributed by the query planner" group forces
prefetching with `debug(PREFER_PREFETCHING)` and whose
`shouldNeverExposeTheWidenedPrefetchRequirementToTheClient` compares the reference key lists returned by the index
path and the prefetch path — `ReferenceSummaryFetchOverrideTest` for the summary override, the
`GenericSummaryOverrideLarge` and `GenericSummarySorterFanOut` groups of `ReferenceSummaryHistogramFunctionalTest`
for the histogram path, and the "Richer copy keeps what an earlier fetch made visible" group of
`ReferenceContractSerializablePredicateTest` for the enrichment merge.

A full functional-module run on the final tree executed **23,402 tests with 0 failures**; the single error is
`ExportS3ServiceTest`, which needs a Docker daemon. The run used a fixed parallelism of 8 and a 12 GB fork heap,
because the default dynamic parallelism exhausts the fork heap on a 24-core box and loses roughly 950 tests to an
engine-level `OutOfMemoryError`. (The run before the last two commits reported the same 23,402 with two failures:
two REST tests asserting HTTP 500 for a repeated hierarchy output name, which is a client error and now yields 400.)

**The `full` profile has to be compiled separately, and `clean` is not optional.** `evita_test/evita_performance_tests`
and `evita_external_api_grpc/client_all_in_one` are outside the default reactor, so a green
`mvn clean install` proves nothing about them: the per-attribute `AttributeHistogramRequest` broke
`BucketsRecordState` and the whole reactor stayed green over a module that did not compile at all. Worse, a plain
`mvn -P full … test-compile` on that module answered `Nothing to compile - all classes are up to date` and
`BUILD SUCCESS`. Only `mvn -o -P full -pl <those two modules> clean test-compile` is a real check.

## Consequences & open follow-ups

**This is a breaking change.** The behaviour differences, with old-versus-new examples, are recorded on issue #1493
and in the pull request description; the list below is the engineering summary.

Queries that used to fail and now succeed:

- a query carrying two content requirements of one kind, where five of the seven kinds used to raise
  `MoreThanSingleResultException`; `entityFetchAllContentAnd(attributeContent("code"))` is one such query
- a nested `referenceContent` naming several references at once inside a summary's `entityFetch`, which
  `ReferenceSummaryTranslator#verifyFetch` refused with "There are multiple reference names, cannot return single
  name." because it resolved the schema through `ReferenceContent#getReferenceName()`
- a `referenceContent` that filters or pages the very reference a `referenceHaving` or a reference ordering also
  names — refused during planning by the strict rule before the prefetch union got its own

Queries whose result changes:

- two `referenceContent` siblings for one reference where only one carries a `filterBy` or a page are now refused
  with `EvitaInvalidUsageException`; `referenceContent` used to keep one of them silently
- `entityFetchAllContentAnd(hierarchyContent(stopAt(...)))` now fetches the **whole** parent chain, because the bare
  `hierarchyContent()` inside the shorthand is the superset
- the prefetch union no longer drops all but the last `accompanyingPriceContent` when several name different prices
- two `referenceContent` requirements whose reference name sets **overlap** are folded per shared name instead of
  being refused; the shared reference is fetched with the union of both bodies
- a catch-all `referenceContentAllWithAttributes()` written beside a bare `referenceContent(<name>)` no longer hides
  the references only the catch-all covers — they were fetched, then hidden behind a `ContextMissingException` and,
  once admitted, returned as an empty chunk the decorator never built
- an enrichment (`ReferenceContractSerializablePredicate#createRicherCopyWith`) no longer hides reference attributes
  an earlier fetch had already made visible
- **a `referenceSummaryOfReference` / `facetSummaryOfReference` inherits nothing from the generic summary** — no
  entity fetch, no group fetch, no `filterGroupBy`, no order. A specific constraint carrying only a depth returns
  bare entity references; a specific `attributeContent(name)` beside a generic `attributeContent(code)` yields
  `[name]`, not `[code, name]`. The migration is to repeat on the specific constraint whatever the generic one
  supplied
- reference histograms return the same group shape at depth `NONE` as at `COUNTS`, and a boundary tie is broken by
  the governing summary's `orderBy`

Queries whose result changes, from the generalisation:

- `priceContent(NONE)` written beside a `priceContent` that fetches is refused instead of folded to the fetching mode
- a query whose two `accompanyingPriceContent` for one name mix a stated price-list sequence with one deferred to
  `defaultAccompanyingPriceLists` is refused, even though the two agree under today's default
- one GraphQL accompanying price name selected twice with different price lists is refused; it used to return the
  wrong price list, silently
- two `attributeHistogram` requirements naming one attribute with different bucket counts or behaviours are refused;
  the last one used to overwrite the first for every attribute in the query
- **a declared facet relation level now takes effect.** `facetGroupsDisjunction` defaults to
  `WITH_DIFFERENT_GROUPS`, the level at which it changes something, so it serialises without a level meaning
  inter-group; two same-level constraints for one reference with different filters are refused
- an attribute-filtered query carrying two differing `priceHistogram` requirements errors instead of returning one
  of them
- two `hierarchyOf...` constraints for one target declaring different `orderBy`s are refused, and one declaring no
  order no longer wipes the order declared beside it — a query relying on that wipe changes result order
- a query stating both `page` and `strip` errors instead of silently returning a paginated list
- a query restricting one hierarchy two different ways *and* asking for its statistics errors instead of describing
  a subtree the record set was not restricted to
- one hierarchy output name claimed by two requirements fails as a usage error at planning, instead of an internal
  `IllegalStateException` at fabrication whose message carried both computed result trees

Over gRPC, a hierarchy output name declared by the second of two `hierarchyOf...` constraints is now returned
correctly; it used to crash the driver with a `NullPointerException`. That is a fix in one direction only — no
working query changes behaviour.

**The one-sided restriction rule reversed once, and the reversal is the decision.** The first version of this work
shipped the superset rule for a one-sided `filterBy` or page (Option E) after the quality gate found the strict rule
refusing planner-built shapes. Measuring the refusal properly showed it fires during *planning*, on the index path as
well as the prefetching one, and that it belongs entirely to the prefetch union — which now strips the projections
that caused it. Anyone tempted to re-unify the two rules should read Option E first: the shape that defeats a single
strict rule is `referenceHaving` or a reference ordering beside a restricted `referenceContent` for the same
reference, and `shouldNeverExposeTheWidenedPrefetchRequirementToTheClient` is the test that fails when the widening
starts reaching the client instead.

**`ReferenceContent#isFullyContainedWithin`'s restriction clauses are now only reachable for nested requirements.**
With `forPrefetch()` stripping the restrictions of every top-level requirement entering the collector, the
filter/order/chunking comparisons in that method can only be exercised through `EntityFetch#isFullyContainedWithin`
on a nested body. The method was left as it is; whether those clauses still earn their keep is worth a look the next
time someone touches it.

### A hierarchy constraint outside a conjunction, and the two crashes behind it

Two pre-existing defects, unrelated to duplicate requirements, were found because the first draft of the
duplicate-hierarchy-filter test wrote its ambiguous filter with `or`. Both are fixed here rather than filed
separately, because between them they made every `hierarchyWithin(<reference>, …)` outside a conjunction
unusable — the constraint is legal, parses, plans, and then aborts with an internal error.

**`IndexSelectionVisitor` descends only through conjunctions.** It walks `And`, `FilterBy`, `FilterInScope` and
`ReferenceHaving`, so a hierarchy constraint nested in `or` or `not` is never registered as a target index
option and `FilterByVisitor#findTargetIndexSet` returns NULL for it. That is by design — an `or` branch is not a
restriction the whole query can be narrowed to — and it puts the constraint on
`AbstractHierarchyTranslator#createFormulaForReferencingEntities`'s *computed* branch, which is where both
defects lived.

1. **The computed branch took its schemas from the processing scope.** The processing scope carries a reference
   schema only inside `referenceHaving`; at the top level it is NULL, so `Objects.requireNonNull` threw a bare
   `NullPointerException` surfaced as `GenericEvitaInternalError: … null`. Both schemas now come from the queried
   schema and the reference the constraint itself names — which the method already resolved on the line above and
   threw away, and which is exactly what `IndexSelectionVisitor#addHierarchyIndexOption` feeds to the same
   `getReducedEntityIndexes` call, so the two branches build the same formula. Even had the reference schema been
   present, taking it from a `referenceHaving` scope would have addressed the wrong reference.
2. **`QueryPlanningContext#setRootHierarchyNodesFormula` was a write-once premise**, and
   `HierarchyWithinTranslator` calls it for every constraint it translates — so once the first defect was out of
   the way, a union of two subtrees aborted with *"The hierarchy filtering formula can be set only once!"*. The
   roots are now recorded **per constraint**, and the requirement phase asks for the roots of the constraint it
   decided to describe — the one `EvitaRequest#getHierarchyWithin` resolved for that target. Nothing is refused:
   the filter keeps all of its constraints, and the statistics read exactly the hierarchy they are about. Two
   further consequences fall out of the keying: statistics of one reference no longer observe the roots of a
   `hierarchyWithin` aimed at a *different* reference, which the single shared slot used to hand them; and a
   constraint translated once per scope index no longer collides with itself, the first formula recorded winning
   in line with the first-applicable-scope precedence the translation already uses.

Asking for statistics over the genuinely ambiguous filter still fails loudly at planning, through the refusal
for two different hierarchy filters at one target — `shouldRefuseHierarchyStatisticsWithTwoHierarchyFiltersJoinedByOr`
pins that the premise no longer beats it to it.

The third finding of the same reading is settled by the same change: the set-once premise did fire for two
`hierarchyWithin` naming *different* references, and the keying removes that as a side effect. Only the
cross-reference case remains untested, because the test datasets carry a single hierarchical reference.

**One thing was found and deliberately not fixed here**, because it needs a decision wider than this issue:

- **GraphQL and the engine disagree about whether repeating `hierarchyOf...` for one target is legal at all.**
  Measured on both sides. The engine returns both:
  `hierarchyOfSelf(fromRoot("megaMenu", stopAt(distance(1))))` beside
  `hierarchyOfSelf(fromRoot("sideMenu", stopAt(distance(1))))` yields five nodes under each name, and
  `EvitaArchivingTest#shouldGenerateResultsInOverMultipleScopes` pins the reference form of the same shape —
  `inScope(LIVE, hierarchyOfReference(CATEGORY, children("liveMenu", …)))` beside
  `inScope(ARCHIVED, hierarchyOfReference(CATEGORY, children("archiveMenu", …)))`. Both GraphQL spellings of
  those two queries fail with HTTP 200 and
  `errors: [{ message: "Duplicate hierarchies for single reference." }]` from `HierarchyOfResolver`, which folds
  the selection set into a map keyed by reference name and throws on any collision. The scoped shape needs field
  aliases (`live:` / `archived:`) to get that far at all, since two unaliased `inScope` selections with different
  arguments are refused by GraphQL's own field-merging validation first. Whichever way this is settled, one of
  the two surfaces changes: either GraphQL grows a per-output-name key and stops refusing, or the engine starts
  refusing and `EvitaArchivingTest` changes with it.

## Related work

- Issue #1365 / PR #1370 (`hierarchyContent` parents behaviour) — the source of the "the superset wins" precedent,
  which this record keeps for `hierarchyContent` and deliberately does not extend to `referenceContent`. That PR
  carries the same one-sided `stopAt` rule for `HierarchyContent#combineWith` plus a parents-behaviour argument this
  branch does not have; the two edits touch the same lines, and the conflict resolves by taking #1370's version,
  which is the superset of this one. `EvitaRequest#isRequiresParent()` was left untouched here for the same reason,
  so its per-getter combining loop becomes redundant-but-harmless once #1370 lands. It has no decision record of its
  own yet.

## Timeline

- **2026-09-05** — issue #1493 studied; the reduction designed, implemented, hardened by a code-quality gate and an
  adversarial review, and documented.
- **2026-09-06** — the maintainer reversed the one-sided restriction rule and ruled that a specific summary must
  define all of its own requirements; the prefetch union gained `forPrefetch()` and the summary overlay was replaced
  by the override.
- **2026-09-06** — the maintainer ruled the work should be generalised to the whole codebase; an audit of every
  other place a query can state the same thing twice was fixed finding by finding, each reproduced first, and the
  three rules were written down as `.claude/rules/constraint-resolution.md`.
