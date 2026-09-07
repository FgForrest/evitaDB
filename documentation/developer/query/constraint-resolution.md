# Constraint Resolution

A query can say the same thing twice. `entityFetch(attributeContent("code"), attributeContent("name"))`,
two `referenceContent("category", …)` with different `filterBy`, `page` beside `strip`, two
`hierarchyOfReference("category", …)` with different `orderBy`. Nothing stops a client from writing it — a
query is assembled from variables, from helper methods, from a GraphQL selection set stitched together by a
component tree — and the engine has to decide what it means.

**The wrong answer is to pick one.** A silent pick is invisible at the call site, changes with the order the
constraints happen to be written in, and produces a response that quietly answers a different question than
the one asked. Issue #1493 was filed for one such place; an audit of the rest of the query language found
eleven more, and these three rules replaced all of them.

This document is for the person adding, removing or relaxing a refusal that concerns two requirements about
one thing — and for anyone tightening a `combineWith`, which silently tightens the prefetch union with it and
has broken valid queries twice.

## The three rules

### 1. Specificity replaces, never merges

A named form completely defines what its target gets. It does not inherit the generic form's requirements, add
to them, or subtract from them.

`referenceSummaryOfReference("brand", …)` beside `referenceSummary(…)` means the brand summary is *exactly*
what the specific constraint says — not the union, not an overlay of the two. Same for
`facetSummaryOfReference`, and for a `referenceContent("category")` beside a catch-all `referenceContent()`.

The migration when this bites someone is always the same: repeat on the specific constraint whatever it needs
from the generic one. That is more typing and it is legible; an inheritance rule is neither.

**Where it lives:** `ReferenceSummaryProducer` (per-reference resolution of one request), and
`EvitaRequest#getReferenceEntityFetch` (per-name fold of overlapping name sets).

### 2. A contradiction at one named target fails loudly

Two requirements aimed at the same *named target* — the same reference name, price name, attribute name,
accompanying-price name, hierarchy output name — must agree. Where they agree the engine folds them into one.
Where they cannot, it raises `EvitaInvalidUsageException` naming both, and it does so during **planning**,
before any work is done.

"Cannot agree" is not a judgement call. It is any of:

- **Different values for a parameter that shapes the answer** — a bucket count, a histogram behaviour, a
  price-list sequence, a chunking constraint, an `orderBy`, a `filterBy`.
- **A pair that selects different result shapes** — `page` and `strip`; `priceContent(NONE)` and a
  `priceContent` that fetches.
- **One side deferring to a query-level default while the other states the value explicitly.** These may agree
  today, by coincidence of what the default currently is; the next edit to either one turns the agreement into
  a disagreement no reader of the query can see. Refuse the pair while the contradiction is still hypothetical.
  (`accompanyingPriceContent()` beside `accompanyingPriceContent("vip", "basic")` is the worked example.)

**The one deliberate asymmetry is `orderBy`.** An order shapes the result; the *absence* of an order is not a
competing claim about it. So a side that carries no `orderBy` defers to the side that does, and only two
*different* stated orders are refused. The same reasoning is why an order-less `hierarchyOfSelf` beside an
ordered one no longer wipes the order.

### 3. The engine's own requirements are exempt from rule 2, and widen freely

Rule 2 governs what the client asked to be **returned**. It must not govern what the engine decides to
**load**.

The prefetch collector unions requirements from unrelated parts of the plan — a hierarchy-scoped
`entityFetch`, a sort, a filter — into a single "load at least this" statement. Two such requirements are
never in contradiction, because neither is a projection: a `filterBy` on one of them restricts *its* output,
not what has to be in memory. So the union takes the superset and never refuses.

That is what `EntityContentRequire#forPrefetch()` is for: it strips the output-shaping parts (`filterBy`,
`orderBy`, chunking, and `ManagedReferencesBehaviour`) before a requirement enters the union, so no output
restriction can reach the collector and be mistaken for a contradiction there — or, worse, *narrow* it.

`ManagedReferencesBehaviour` is the one that is easy to miss, and it was missed once: `EXISTING` beats `ANY`
in `combineWith`, so a client's `referenceContent(EXISTING, "brand")` used to narrow the bare
`referenceContent("brand")` the planner contributes for a filtered reference. Suppressing references whose
target entity does not exist is a projection like the other three, and the prefetched body is what a
`referenceHaving` is then evaluated against — leaving it in made the same query answer differently depending
on which plan the planner picked.

**`forPrefetch()` is not one method — every requirement kind that carries an output bound needs its own
override.** `HierarchyContent` had none, so its `stopAt` reached the union and `combineWith` refused two
different bounds. The pair that collides there does not even describe one output slot: a `hierarchyContent` in
the query's `entityFetch` bounds the parent chain of the *returned entities*, while one written inside a
`hierarchyOfSelf` computer bounds the parent chain of the *hierarchy node bodies*, and both are honoured in the
response from their own derived requests. When a new requirement kind gains a bound, ask what the union should
see before asking what `combineWith` should do.

**This exemption is invisible in the code unless you go looking for it**, which is the trap. The collector and
the client-facing fold both call `isCombinableWith` / `combineWith`, so a change that makes `combineWith`
stricter silently makes the *union* stricter too, and a valid query that never contained a contradiction
starts failing. It has happened twice: on the `#1432` branch, and again in round 2 of #1493.

> **Whenever you tighten a `combineWith`, check `DefaultPrefetchRequirementCollector` first.**

The collector's own defence is that it tests containment in **both** directions before falling back to
`combineWith` — so a pair that nests one way round is resolved without ever asking the stricter question, and
the answer no longer depends on which of the two arrived first.

## Where the rules are implemented

| Rule | Site | Decides |
|---|---|---|
| 1 | `ReferenceSummaryProducer` | a reference-specific summary defines all of its own requirements |
| 1 | `EvitaRequest#getReferenceEntityFetch` | overlapping `referenceContent` name sets, folded per name |
| 2 | `EntityFetchRequire#combineDuplicateRequirements` | the keyed fold every `entityFetch` goes through |
| 2 | `ReferenceContent#combineWith` | `filterBy` / `orderBy` / chunking for one reference name |
| 2 | `PriceContent#combineWith` | `NONE` beside a fetching mode |
| 2 | `AccompanyingPriceContent#combineWith` | price-list sequence, explicit vs deferred, for one price name |
| 2 | `HierarchyContent#combineWith` | `stopAt` and the parent-chain requirement |
| 2 | `EvitaRequest#collectFacetGroupSettings` | one reference + relation level, two different filters |
| 2 | `ReferenceSummaryProducer#assertDefaultSummaryNotRedeclared` | two all-references summaries of one spelling |
| 2 | `ReferenceSummaryProducer#assertReferenceSummaryNotRedeclared` | two summaries of one named reference |
| 2 | `EvitaRequest#initPagination` | `page` beside `strip` |
| 2 | `EvitaRequest#getHierarchyWithin` | two different hierarchy filters, *when statistics are requested* |
| 2 | `AttributeHistogramProducer#addAttributeHistogramRequest` | bucket count and behaviour, per attribute |
| 2 | `PriceHistogramTranslator` | bucket count and behaviour, on a path the planner always walks |
| 2 | `HierarchyStatisticsProducer#assertOrderNotContradicted` | two `orderBy` for one hierarchy target |
| 2 | `HierarchyStatisticsProducer#assertOutputNameFree` | one hierarchy output name claimed twice |
| 2 | `EntityFetchRequireResolver` (GraphQL) | one accompanying price name selected two ways |
| 2 | `AttributeHistogramResolver` (GraphQL) | one attribute's histogram selected with two bucket counts |
| 2 | `HierarchyOfResolver` (GraphQL) | one output name inside a single hierarchy selection |
| 3 | `EntityContentRequire#forPrefetch` | what a requirement looks like once it is only about loading |
| 3 | `ReferenceContent#forPrefetch` | strips `filterBy`, `orderBy`, chunking, `ManagedReferencesBehaviour` |
| 3 | `HierarchyContent#forPrefetch` | strips `stopAt` |
| 3 | `DefaultPrefetchRequirementCollector` | the widening union, order-independent in both directions |

## What decides *where* a refusal goes

**Put it on a path the planner always walks.** A refusal placed where the code only runs under some queries is
not a refusal, it is a coin flip. `priceHistogram` was decided inside a branch that only executes when the
query filters on price, so the same duplicated pair threw for a price-filtered query and returned silently for
an attribute-filtered one. It now lives in `PriceHistogramTranslator`, which runs for every requirement.

> **The extra result phase is deliberately not such a path when the data is empty, and that is settled.**
> `QueryPlanner#planQuery` returns `QueryPlanBuilder.empty(context)` as soon as
> `IndexSelectionResult#isEmpty()`, before any extra result producer is created — so every refusal that lives
> in an extra result translator is skipped for a query whose index selection came out empty. Measured:
> `hierarchyOfReference(CATEGORY, fromRoot("megaMenu", …), fromRoot("megaMenu", …))` is refused under
> `hierarchyWithin(CATEGORY, entityPrimaryKeyInSet(1))` and returns an empty result under
> `entityPrimaryKeyInSet(999999)`.
>
> **Do not "fix" this.** The early stop predates the rules and applies equally to the schema-validation
> refusals `EvitaArchivingTest` asserts (`ReferenceNotFacetedException`, `AttributeNotFilterableException`,
> `HierarchyNotIndexedException`), so no query ever answers *wrongly* — one merely starts failing once data
> appears. The shortcut is kept on purpose: a filter that produces nothing must not pay for a validation pass
> whose only product is an error message. Closing the inconsistency would mean a data-independent validation
> phase over the require tree ahead of index selection, and that price is not worth the consistency.

**Prefer keying over refusing, when the consumer can name what it wants.** A refusal is the answer only when
two requirements genuinely compete for one slot. If the slot exists merely because the value was stored globally,
key it and the competition disappears: `QueryPlanningContext` used to hold one hierarchy-roots formula per query
and aborted on the second `hierarchyWithin`, so a union of two subtrees was an internal error; the roots are now
keyed by the constraint, and the requirement phase asks for the roots of the constraint it decided to describe.
Nothing had to be refused, and a cross-reference leak — statistics of one reference reading another's roots —
disappeared with the shared slot. Reach for a refusal only once keying is impossible.

**Refuse where the ambiguity is consumed, not where it is written.** Two hierarchy filters in one query are a
perfectly ordinary disjunction; they are only ambiguous for the code that has to pick *one* of them to seed
hierarchy statistics. So the refusal sits in `EvitaRequest#getHierarchyWithin`, whose only production callers are
extra-result planning — the filter keeps working, and only asking for statistics over an ambiguous restriction
fails. Refusing at the point of writing would have cost filtering expressiveness and bought nothing.

**A protocol layer must not invent a refusal the engine does not have.** `HierarchyOfResolver` folded the GraphQL
extra-results selection set into a map keyed by reference name and threw *"Duplicate hierarchies for single
reference."* on any collision, so two `hierarchy` selections aimed at one target were refused at the surface even
though the engine merges them into a single result container indexed by output name — and separate selections are
the only way to ask for one target's hierarchy in two scopes, because `inScope` wraps the whole selection.
Everything below the fold was already output-name keyed: `HierarchyDataFetcher` hands back the merged container and
`SpecificHierarchyDataFetcher` looks each hierarchy up by its own output name. The resolver now emits one constraint
per selection and lets `HierarchyStatisticsProducer#assertOutputNameFree` decide. Its own refusal *within* a single
selection is kept, because it catches the same mistake one layer earlier, before a query is built.

## Deliberately not enforced

Each of these was looked at and left alone. Do not "fix" one without reading its reason.

- **`getFacetGroupNegation` answers at either relation level.** Negation declared at one level is returned for
  the other one too, because by De Morgan's laws negating each facet and combining with AND is the same set as
  negating the group's own disjunction. `facetGroupsNegation(referenceName, filterBy)` — the common form —
  states no level at all, so exact-level matching would silently drop the negation whenever the engine asks at
  the other one. The equivalence holds only while the *other* relation keeps its system default;
  `FacetCalculationRules` can break it. **Revisit trigger:** the first non-default `FacetCalculationRules` that
  makes within-group combination anything but OR. The fix then is to require an explicit level and refuse the
  bare form, not to remove the fallback.
- **`ReferenceContent#getChunking()` takes `findFirst()` over its children.** Unreachable: every constructor
  accepts a single `ChunkingRequireConstraint` and the `@Creator` marks `uniqueChildren = true`, so neither
  the fluent API nor the EvitaQL parser can produce a `page` + `strip` pair inside one `referenceContent`.

## Checking

Find every refusal that concerns two requirements about one thing:

```shell
rg -n "Cannot combine|cannot combine|is requested twice|two different" --glob '*.java' \
  evita_query/src/main evita_api/src/main evita_engine/src/main evita_external_api
```

Find every site that folds duplicates rather than refusing them:

```shell
rg -n "combineWith|isCombinableWith|isFullyContainedWithin|combineDuplicateRequirements" --glob '*.java'
```

Every hit in the first query must name both offending constraints and must be reachable on a path the planner
always walks. Every change to a hit in the second query must be checked against
`DefaultPrefetchRequirementCollector` before it lands — see rule 3.

The record of how these rules were settled, and of the alternatives that lost, is
`documentation/adr/2026-09-05-duplicate-content-requirements-reduction.md`.
