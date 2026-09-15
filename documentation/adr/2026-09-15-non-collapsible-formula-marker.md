---
title: Mark the formulas an optimiser may not collapse, rather than special-casing the container that holds them
date: 2026-09-15
updated: 2026-09-15 08:58
status: accepted
kind: fix
issues: [1547]
prs: [1548, 1568]
areas: [evita_engine/src/main/java/io/evitadb/core/query/filter/FormulaOptimizer.java, evita_engine/src/main/java/io/evitadb/core/query/algebra, evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/common]
supersedes: []
superseded-by: []
relates: [2026-09-15-bidirectional-reference-counterpart-rewrite]
---

# Mark the formulas an optimiser may not collapse

`FormulaOptimizer` replaces a conjunctive container with `EmptyFormula` as soon as one of its
children is empty. For *filtering* that is free and correct — the record set is identical and the
tree is smaller. But several later phases do not evaluate the tree, they **read its shape**: they
locate a `facetHaving`, an attribute range carrier or a price carrier inside a `userFilter` and
derive an output from finding it. Collapsing the conjunction deletes the evidence those phases
depend on, and none of them fail when it is missing — a dropped facet selection simply reports
`requested = false` for a facet the user did select.

Formulas that carry such information now implement the marker interface
`NonCollapsibleFormula`, and the optimizer refuses to collapse any container holding one **anywhere
beneath it**.

## Why

The collapse has always been able to do this, but until now it almost never did: a container became
empty only when a bitmap turned out empty at *execution* time, long after extra-result planning had
read the tree. The `attributeIs(NULL)` skip in `2026-09-15-bidirectional-reference-counterpart-rewrite`
moved that emptiness to **planning** time, which put it in front of every structural reader in the
engine. The defect was pre-existing; the optimisation made it ordinary.

### Previous state

The first fix special-cased the container:

```java
if (formula instanceof UserFilterFormula) {
    return formula;
}
```

It guards the wrong node. `UserFilterTranslator` aggregates its children through
`FutureNotFormula#postProcess`, which returns a **single** `Formula` built by `FormulaFactory::and`,
so a `UserFilterFormula` always has exactly one child — an `AndFormula` as soon as there are two or
more constraints. For `userFilter(facetHaving(...), attributeIs(..., NULL))` the planned tree is

```
UserFilterFormula                 <- guarded
└── AndFormula                    <- collapses; not guarded
    ├── FacetHavingFormula        <- the carrier, destroyed with it
    └── EmptyFormula
```

The guard kept the marker and lost everything it marked. A probe counting `FacetHavingFormula`
nodes under the surviving `userFilter` returned **0**, which is also how a claim made from reading
the guard — that the carrier survived and the flag was lost for some other reason — was disproved.

## Options considered

### Option A — a marker interface on the carriers, checked transitively (chosen)

`NonCollapsibleFormula` is an empty marker, modelled on the `NonCacheableFormula` /
`NonCacheableFormulaScope` pair already in the algebra package. `FormulaOptimizer` asks
`holdsNonCollapsibleFormula(container)` before collapsing, memoizing the answer per node.

- **Pros:** the knowledge lives on the formula that owns it, so a new carrier type is protected by
  declaring the interface rather than by editing the optimizer; the check is transitive, so it does
  not care how deep the translators happen to nest the carrier; keeping the container costs
  effectively nothing at execution time, because `AbstractFormula#computeSortedConjunctionBitmaps`
  sorts children by ascending estimated cost, reaches the zero-cost `EmptyFormula` first and
  short-circuits without computing the carrier beside it.
- **Cons:** the protected tree stays large for hashing, cost estimation and cache-key computation;
  and the marker has to be *remembered* on a new carrier type, which nothing enforces.

### Option B — keep special-casing containers in the optimizer (declined)

Extend the original guard to the shapes that were found to break.

- **Pros:** no new type; a one-line change per shape.
- **Rejected because:** it encodes the tree shape the translators happen to produce today into the
  optimizer. The `userFilter`-with-one-`AndFormula` shape is an implementation detail of
  `FutureNotFormula#postProcess`, not a contract; the guard was already wrong for the only shape
  that mattered, and the next translator to wrap a carrier one level deeper would break it again
  silently.

### Option C — collapse to a node that stays traversable (declined)

Replace the container with a formula that computes empty but still exposes its original children,
so structural walks keep finding the carriers while evaluation keeps the short-circuit.

- **Pros:** would keep both properties at once — the small evaluation path *and* the readable
  structure — and would need no marker on any formula.
- **Rejected because:** emptiness in this engine is represented by one shared singleton and the
  codebase reasons about it by identity. `EmptyFormula.INSTANCE` is referenced **125 times across 57
  files** in `evita_engine`, of which **11** are identity comparisons (`== EmptyFormula.INSTANCE`)
  and **22** are `instanceof EmptyFormula` tests. A second kind of empty would have to satisfy every
  one of them, and it would also have to hash differently from `EmptyFormula` while computing the
  same result, or two queries that differ only in a collapsed subtree would share a cache entry.
  Most decisively, `UserFilterRelaxer#containsEmptyFormula` documents the invariant that
  `EmptyFormula` inside a relaxed user filter means *"the relaxer peeled this filter down to
  nothing"*; a traversable empty would have to be excluded from that walk by hand, restating the
  same knowledge the marker holds — with none of the marker's locality.

## Decision

**Chosen: Option A.** The property "this node carries information a later phase reads off the tree"
belongs to the node, not to a list inside the optimizer — that is the same argument the codebase
already made for `NonCacheableFormula`, and the marker deliberately mirrors it.

The two marker sets are **not** the same set and must not be merged: a formula can be perfectly
cacheable and still be structurally load-bearing (`PriceBetweenFormula`), and a non-cacheable
formula need carry no structure anyone reads.

The rule is *replace, never drop*. A collapsed conjunctive node becomes `EmptyFormula`; it is never
removed from its parent. Removing it would let an enclosing `AND` widen to its surviving siblings —
`A AND nothing` degrading to `A` — which is a wrong-results bug this project has shipped once
before, and which is why the disjunction branch was hardened in the same change.

## Key technical details

- `NonCollapsibleFormula` — the marker. Empty by design.
- `FormulaOptimizer#holdsNonCollapsibleFormula` — transitive, memoized in an identity-keyed
  `HashMap` (identity is what a plain `HashMap` gives here: `AbstractFormula` overrides neither
  `equals` nor `hashCode`). The optimizer walks bottom-up, so the pass stays linear rather than
  rescanning a subtree per container.
- **The six marked carrier types**, five unconditional and one conditional. `AttributeRangeCarrierFormula` (the interface — covers
  `BetweenAttributeFormula` and `HistogramHavingFormula`), `FacetHavingFormula`, `FacetGroupFormula`
  (also an interface — covers `FacetGroupOrFormula` and `FacetGroupAndFormula`),
  `UserFilterFormula` and `PriceBetweenFormula`. Three are
  `UserFilterRelaxer#carrierTypeFor`'s switch arms; `UserFilterFormula` is what
  `ExtraResultPlanningVisitor#getUserFilteringFormula` locates; `FacetGroupFormula` is what
  `ReferenceSummaryOfReferenceTranslator:312` and `FilterFormulaFacetOptimizeVisitor:61` match on.

  **The rule is "read structurally ⇒ marked", and it must be applied to the node that is read, not to
  an ancestor of it.** `FacetGroupFormula` was initially left unmarked on the reasoning that
  `FacetHavingFormula` sits above it and is marked. That reasoning is wrong:
  `holdsNonCollapsibleFormula` searches a node's subtree **downwards**, and `FacetHavingTranslator:429`
  builds `FacetHavingFormula(referenceName, composed)` where `composed` is an `Or`, an `And` or a
  `CombinedFacetFormula` over the groups — so that intermediate container held no marked formula, and a
  marked carrier above it protects nothing. Marking the groups rather than the container is what makes
  this robust: `CombinedFacetFormula` is deliberately left unmarked (it is `NonCacheableFormula` only)
  and is nevertheless protected, because the transitive check finds the marked groups beneath it. The omission was found by an adversarial review of the
  commits, not by the original enumeration, which *did* return `FacetGroupFormula` and had it reasoned
  away.
- **`AttributeFormula` is a *conditional* carrier, and the only one.** `AttributeHistogramProducer:361`
  harvests the per-bucket `requested` predicate by walking for `AttributeFormula` under each
  `UserFilterFormula`, and `AbstractAttributeComparisonTranslator:110` attaches one to a **plain**
  `AttributeFormula` for `attributeLessThan(Equals)` / `attributeGreaterThan(Equals)` over a numeric
  attribute. Marking the type outright would make the commonest leaf in the engine uncollapsible
  everywhere, so `NonCollapsibleFormula` carries `isNonCollapsible()`, defaulting to `true`, and
  `AttributeFormula` answers `requestedPredicate != null`. The value is derived from final constructor
  state, which the optimizer's per-node memo requires.

  `BetweenAttributeFormula` must **re-assert `true`** — a class method beats an interface default, so it
  would otherwise inherit the conditional answer, and a range carrier over a non-numeric attribute (no
  histogram predicate, still peeled by `UserFilterRelaxer`) would become collapsible.

  **How this was nearly missed, twice.** The first pass reasoned the shape was safe. The second wrote a
  behavioural row that came back green and concluded there was no defect. Both were wrong: the row
  asserted `anyMatch(bucket.requested())`, and the failure mode is
  `AttributeHistogramProducer:393-394` falling back to `Functions::alwaysTrue`, which marks **every**
  bucket requested — so the assertion passed precisely when the predicate was lost. The falsifiable
  assertion is the opposite one, that a bucket above the threshold reports *not* requested. Rewritten
  that way the row went red immediately, with all eight buckets flagged. **A green test whose assertion
  cannot fail is worse than no test**: it retires the question.

- **The disjunction hardening.** `FormulaOptimizer`'s OR branch returned its single surviving
  child, which is `null` when every disjunct collapsed; it now returns `EmptyFormula.INSTANCE`. The
  branch is *currently unreachable* — `EmptyFormula`'s constructor is private, `AbstractFormula`
  defines no `equals`/`hashCode`, so the cloner's identity-keyed `LinkedHashSet` merges N collapsed
  children into one entry and the clone path handles it — but that is three unrelated facts holding
  up a correctness property, none of them visible at the branch.

## Verification

`FormulaOptimizerTest.OrSimplificationTest` gained two rows placing an all-empty disjunction inside
a conjunction with a surviving sibling, so a dropped node would show as the sibling widening the
result rather than as an empty one. Both assert against `input.compute()` as well as against the
literal expected array, so the optimised and unoptimised trees must agree.

`AttributeIsNullPlanningSkipFunctionalTest` carries the behavioural rows; the facet row
(`shouldKeepTheFacetSelectionWhenASiblingUserFilterConstraintCollapsesAtPlanningTime`) is the one
that moved from red to green on this change, and it asserts the `requested` flag because the record
set is empty under every variant and therefore cannot distinguish them.

Full sweep across the 106 classes where a mistake in `FormulaOptimizer` or `QueryPlanningContext`
could surface — both run on every planned query, so a green targeted suite would prove nothing:
**2 525 tests, 2 518 passed, 0 failures, 7 skipped**, against 2 523 / 1 failure / 7 skips before the
marker. The seven skips are the six `@Disabled` rows pinning #1583/#1584/#1585 plus
`ReferenceSummaryHistogramBoundaryResolutionTest#shouldPickMinMarketShareCandidateAndHonorSorterOnTies`,
which predates this work. Each of those figures was read from the surefire XML per row rather than
inferred from the aggregate — an aggregate cannot distinguish a row that passed from a row that
silently stopped running.

## Consequences & open follow-ups

**The marker is a contract nothing enforces.** A new formula type that later phases locate by walking
the tree must declare `NonCollapsibleFormula`, and nothing will fail if it does not — the symptom is a
quietly wrong extra result, never an exception.

The check to repeat is: enumerate every `FormulaFinder.find(...)` target and every `instanceof` /
`Class::isInstance` structural match in `evita_engine`, then confirm **each matched type itself**
carries the marker. Enumerating is not the hard part — `FacetGroupFormula` was in the first
enumeration and was still missed, because it was judged covered by the marked `FacetHavingFormula`
above it. `holdsNonCollapsibleFormula` only ever looks down, so "an ancestor is marked" is never an
argument. The gap was caught by an adversarial review of the commits, which is the second check worth
repeating.

**Two independent readers failed to explain a green result that turned out to be a broken test.** The
`AttributeFormula` case above was declared a non-defect twice — once from reading the code, once from a
behavioural row — before the assertion was made falsifiable. Both readers had correctly traced that the
conjunction *should* collapse; neither questioned why the observation disagreed. The lesson for anyone
extending the marked set: when a trace and an observation disagree, suspect the observation first, and
check that the assertion can fail before trusting it.

**`RangeCarrierGroup.FACET_IMPACT` has no production caller.** `UserFilterRelaxer#carrierTypeFor` maps
it to `FacetHavingFormula` and `UserFilterRelaxerTest` covers it, but no code in `src/main` passes it
to `relax` — the three live call sites pass `ATTRIBUTE_HISTOGRAM` (twice) or `PRICE_HISTOGRAM`. The
`FacetHavingFormula` marker is therefore earned by a different consumer:
`ReferenceSummaryOfReferenceTranslator:306-312`, which locates `FacetGroupFormula` nodes under
`getUserFilteringFormula()` to set the facet `requested` flag. Noticed while enumerating the carriers;
not acted on.

**`MutableFormula` is the one `Formula` in the engine that overrides `equals`/`hashCode`** (it delegates
to its wrapped `FacetGroupFormula`). That matters because `holdsNonCollapsibleFormula`'s memo is a plain
`HashMap` relying on identity keys. It is safe today and structurally so, not by luck: `MutableFormula`
is built only by `AbstractFacetFormulaGenerator:686` during facet-summary generation, while
`FormulaOptimizer` is instantiated only by `QueryPlanner:304` over the filtering tree — the two phases
never meet. The memo's JavaDoc should be read as "no formula reachable by `FormulaOptimizer` overrides
them", which is the property actually relied on.

**Emptiness has two incompatible meanings on one singleton, and this record does not settle it.**
`UserFilterRelaxer#relax`'s JavaDoc requires callers to read a returned `EmptyFormula` as *"no
mandatory filter remains / all records pass, never as empty result"*, and the live call sites obey it
(`ReferenceSummaryProducer:429` maps it to `null` to span the catalog). `UserFilterRelaxerTest:119-121`
comments the same sentinel as *"so downstream AND-chains short-circuit correctly"* — an empty result.
Both readings are in the tree. This matters more now than before, because the `attributeIs(NULL)` skip
means a user's own query can put an `EmptyFormula` inside a `userFilter`, which `containsEmptyFormula`'s
JavaDoc explicitly assumes cannot happen. **No defect was demonstrated**: facet counts are computed
against `getFilteringFormulaWithoutUserFilter()` and so never see it, and the histogram baselines that
do see it are *designed* to span the catalog when the user filter is relaxed away. A test asserting the
opposite for facet counts was written, measured against the two green rows that pin the documented
design, and deleted as a wrong expectation.

## Related work

- `2026-09-15-bidirectional-reference-counterpart-rewrite` — the optimisation whose planning-time
  `attributeIs(NULL)` fold turned this latent defect into an ordinary one, and where the original
  incomplete guard is recorded.

## Timeline

- **2026-09-14, evening** — the facet `requested` flag found red by the new suite; attributed to the
  optimizer's collapse and guarded at the `UserFilterFormula`
- **2026-09-15** — the guard measured incomplete (carrier count 0 beneath the surviving container),
  replaced by the marker; disjunction branch hardened; sweep green
