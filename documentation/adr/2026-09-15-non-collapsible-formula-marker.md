---
title: Mark the formulas an optimiser may not collapse, rather than special-casing the container that holds them
date: 2026-09-15
updated: 2026-09-15 13:05
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

**Emptiness had two incompatible meanings on one singleton, and it was a live wrong-answer defect.**
An earlier revision of this record described the ambiguity and concluded *"no defect was
demonstrated"*. That conclusion was wrong, and it was reached through the exact trap recorded two
paragraphs above: the row that would have shown it asserted `assertNotNull` on the extra result,
which cannot fail. Both producers build their wrapper unconditionally, and a producer is registered
from the query's `require` rather than from its filter (`QueryPlanner:562`), so no collapse can make
an extra result absent.

`UserFilterRelaxer#relax` returned `EmptyFormula.INSTANCE` both when relaxation had peeled every
carrier — *"no mandatory filter remains, every record passes"* — and, after the `attributeIs(NULL)`
skip, when the tree handed to it was **already** unsatisfiable — *"matches nothing"*. Its JavaDoc
defended the single value with *"genuine empty-result formulas can never surface here, because the
relaxer only removes nodes, it never synthesises an empty one"*: true of what `relax` **produces**,
silent on what it may be **handed**. Folding at planning time made `EmptyFormula` a routine input,
and `AttributeHistogramProducer:374` mapped it to `null`, the catalog-wide baseline.

Measured: `filterBy(attributeIsNull(alwaysSet))` returned **0 records together with the histogram of
all 8 buckets** — byte-identical to the same query carrying no filter at all. Likewise for the
`userFilter` form. The pre-skip engine (`git show e554d9974^`) answers *"histogram missing
entirely"*, so this optimisation **introduced** the wrong answer rather than exposing an older one.

The reference that settles such a row is **not** a no-filter baseline. The collapsed query *equals*
that baseline — which is the bug — so comparing against it pins the defect as correct. The reference
is a control reaching the same empty answer at **execution** time with its tree intact: two
`attributeEquals` on one attribute naming different values, so both leaves are populated and their
conjunction is empty. Its estimated cost must additionally be asserted non-zero, because a single
leaf whose index lookup is empty is priced at `estimated costs 0` and is therefore indistinguishable
from a filter the planner folded away.

The fix separates the two states **at the seam** instead of at each caller: `relax` returns
`Optional<Formula>`, empty meaning "all peeled", present meaning a real tree that may itself be
unsatisfiable. It also had to change the **drop decision inside the cloner**, where a `userFilter`
that already contained `EmptyFormula` on arrival is now kept rather than dropped. Both halves were
required — the return-type change alone fixes the bare-`EmptyFormula` root and leaves the
`UserFilterFormula(EmptyFormula)` shape broken by a second path through `containsEmptyFormula`, and
the two shapes fail with identical output, which is what hid the second one.

The keep-rule then needed a third correction, and the way it was found is the point. The first version
tested "is there an `EmptyFormula` anywhere below this node", justified by the claim that
`FormulaOptimizer` never leaves one inside a surviving disjunction. **That claim is false**, and
`FormulaOptimizerTest#orWithTwoNonEmptyAndOneEmpty_shouldKeepOrWithNonEmpty` already pinned the
opposite: an `OrFormula` with two or more non-empty children is returned untouched, dead child
included. In a disjunction `EmptyFormula` is the identity element, so the scope-blind test declared
`userFilter(or(slider, liveAlternative, EmptyFormula))` unsatisfiable, skipped relaxation, and let the
user's own slider contract the histogram it exists to span - the mirror image of the defect being
fixed, and reachable at both the keep site and the drop site. An adversarial review constructed the
shape and verified it against compiled classes; the 2529-row sweep had passed the broken version
clean, because no existing row puts a folded constraint in an `or` beside a live alternative.

The test is therefore **scope-aware**: a disjunction is empty only when every alternative is, a
conjunction when any child is, and anything else answers "not provably empty" - `NotFormula` being the
reason, since `NOT(empty)` is the superset. It reuses `FilterByVisitor#isConjunctiveFormula` so the
relaxer and the optimizer cannot drift apart on what "conjunctive" means. Note single-child carriers
(`AttributeFormula`, `FacetHavingFormula`, `SelectionFormula`) cannot discriminate the two rules at
all - with one child the two coincide - so only multi-child shapes test this.

`PriceHistogramProducer` deliberately keeps the collapsed mapping: it never substitutes a baseline for
the filter, so both states coincide there and no answer changes.

**The two error directions are not symmetric**, which is what makes the conservative default defensible rather
than merely cautious. Every caller re-checks the returned formula semantically, so *under*-detecting an emptiness
costs a wasted peel and nothing else - the real computation still finds it. *Over*-detecting is the only direction
that yields a wrong answer. A known under-detection is left in place on those grounds: `AndFormula` and `OrFormula`
have bitmap-only constructors that leave `getInnerFormulas()` empty, so the early return answers "not provably
empty" for them regardless of their contents. An adversarial pass then failed to break the scope-aware version
across six constructed shapes, having confirmed by source that every `CONJUNCTIVE_FORMULAS` member really does
compute an intersection - `ScopeContainerFormula` included, which was the one classified-but-possibly-not-AND risk.

**Open — the conditional `AttributeFormula` arm may now be dead code.** With the seam fixed, forcing
`AttributeFormula#isNonCollapsible()` to `false` changes no observable across the 134 targeted rows:
without the marker the conjunction collapses to a bare `EmptyFormula`, which the relaxer now reports
as present-and-unsatisfiable, so the histogram is omitted either way. That arm's only behavioural
proof was a row whose observable existed *because* of the defect it was compensating for; the row now
pins the corrected behaviour instead
(`shouldOmitTheAttributeHistogramWhenAUserFilterSiblingCollapsesAtPlanningTime`, which carries a
second query so an absence assertion cannot pass vacuously). Measured on the targeted set only, not
the broad sweep — removing the arm is a separate decision and is deliberately not taken here.


## Related work

- `2026-09-15-bidirectional-reference-counterpart-rewrite` — the optimisation whose planning-time
  `attributeIs(NULL)` fold turned this latent defect into an ordinary one, and where the original
  incomplete guard is recorded.

## Timeline

- **2026-09-14, evening** — the facet `requested` flag found red by the new suite; attributed to the
  optimizer's collapse and guarded at the `UserFilterFormula`
- **2026-09-15** — the guard measured incomplete (carrier count 0 beneath the surviving container),
  replaced by the marker; disjunction branch hardened; sweep green
