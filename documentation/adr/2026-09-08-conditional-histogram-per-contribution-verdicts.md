---
title: Conditional histogram triggers answer per contribution, and the mutated entity's PK is pinned inside the scope container
date: 2026-09-08
updated: 2026-09-09 00:20
status: accepted
kind: fix
issues: [1470]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/mutation, evita_engine/src/main/java/io/evitadb/core/collection]
supersedes: []
superseded-by: []
relates: [2026-08-31-cross-entity-histogram-removal-pre-pass, 2026-04-23-bucketed-histogram-indexing]
---

# A cross-entity condition is answered per `(referencedEntity, owner)` contribution, not per owner

A `bucketedPartially` condition decides whether **one reference** of an owner belongs in a histogram bucket, but
an evitaDB filter answers in **owner primary keys**. The cross-entity trigger executor ran one filter and applied
its single answer to every reference the owner held, so a non-qualifying reference could be indexed on a
qualifying sibling's verdict, and a withdrawn contribution could survive because a sibling still qualified. Three
changes close that: the mutated entity's PK is now merged *inside* the scope container the condition already has
rather than appended beside it; the condition is evaluated once per resolved contribution wherever it can tell an
owner's references apart; and implicit local mutations reach trigger discovery, with the pre-pass widened to
match.

## Why

The histogram is a multiset gated by `AttributeCardinalityIndex`: an owner enters a bucket when its
`(value, owner)` count goes 0→1 and leaves when it goes 1→0. Every add and remove therefore has to be attributed
to **one** reference. The trigger path had no way to do that — it asked "does this owner satisfy the condition",
which is a strictly weaker question, and the answer collapses as soon as an owner holds two references that
disagree.

The constraint that made this non-obvious is a property of the query language rather than of the histogram code:
**`and` under a `referenceHaving` intersects owner sets — it does not require one reference to satisfy every
branch.** Only the constraints inside a single `entityHaving` / `groupHaving` are guaranteed to describe the same
target entity. Every mechanism below follows from that one fact.

### Previous state

Scoping existed and was believed sufficient. `parameterize` injected the mutated entity's PK into the trigger
filter so the query could only answer for the reference being re-evaluated, and `evaluateConditionPerGroup`
handled the group axis for `REFERENCED_ENTITY_*` dependencies carrying a `groupHaving`. Both were correct for the
shapes they were written against and are exercised by the existing suite.

They stopped binding in three places:

1. **`injectPkScope` inspected only the direct children of the `referenceHaving`.** When
   `ExpressionToQueryTranslator#mergeReferenceHaving` collapses same-name siblings into
   `referenceHaving(name, and(…))` — which it does for *any* condition with two predicates on the referenced
   entity, including the `bucketedPartially` + `assignedWhen` pair that is built that way by construction — the
   scope container sits one level down. The injector did not find it and appended the PK as an `and`-sibling,
   which asserts only "the owner has *a* reference to the mutated entity". Any owner with a second reference
   satisfies that for free. Facets share the translation and the injector, so they had it too.
2. **`GROUP_*` and `PARENT_*` mutations were never pinned to a reference at all**, because the mutated entity is
   not the reference target on those paths. `parameterize` returned the filter unchanged for `PARENT_*` on the
   grounds that "the children bitmap from the resolution step already limits the scope to the right owners" —
   true of *owners*, and silent about *references*.
3. **Implicit local mutations never reached trigger discovery.** `popIndexImplicitMutations` scanned the root
   batch only, so an attribute written by `GENERATE_ATTRIBUTES` / `GENERATE_REFERENCE_ATTRIBUTES` fired nothing.
   Recorded as a known gap in [[2026-08-31-cross-entity-histogram-removal-pre-pass]], which also predicted the
   ordering constraint: widening dispatch requires widening the pre-pass in the same change.

## Options considered

### Option A — merge the PK inside the scope container, and take both pin axes from the contribution (chosen)

`injectPkScope` descends through `And` / `Or` to the `EntityHaving` / `GroupHaving` that is already there and
merges the PK into it, so the identity is asserted in the one place that guarantees same-target semantics. On top
of that, `parameterizeForContribution` takes **both** axes from the resolved contribution itself —
`entityHaving(pk = referencedEntityPK)` always, `groupHaving(pk = groupPK)` when grouped — which makes the pin
uniform across every dependency type and deletes the dependency-type switch rather than extending it.

- **Pros:** one mechanism covers all six dependency types; injection order stops mattering, because a descending
  injector finds the container wherever an earlier pass left it; for `REFERENCED_ENTITY_*` it reduces to the pin
  that already shipped, so the existing suite is a regression net; the inner referenced-entity subquery is
  narrowed to a single PK *before* the predicates run, which is strictly less work than intersecting each
  predicate's owner set over the whole referenced collection.
- **Cons:** the descent must stop at `Not`, which needs its own explanation and leaves a documented residual.

### Option B — reorder the two injection passes (declined)

Inject the group PK before the entity PK (or the reverse), so whichever container the translator produced is
still a direct child when its pass runs.

- **Pros:** a two-line change; no new traversal.
- **Rejected because:** it cannot work with two axes and a non-descending injector. Whichever pass runs second
  faces an `And` the first one created, so exactly one axis is always wrong — the order only chooses which. It
  also does nothing for the single-axis ungrouped case, which is where the widest-blast-radius defect actually
  lives.

### Option C — keep the owner-level answer and filter the contributions afterwards (declined)

Evaluate once, then re-check each `(reference, owner)` pair against the expression evaluator before acting.

- **Pros:** one filter evaluation per mutation, whatever the fan-out.
- **Rejected because:** it puts a second evaluation engine behind one condition — the filter for the set, the
  expression evaluator for the per-contribution check — and the two can disagree. That is precisely the hazard
  [[2026-08-31-cross-entity-histogram-removal-pre-pass]] declined its Option B to avoid, and a disagreement here
  is silent: it shows up as a drifted cardinality counter, not an error.

### Option D — answer a captured-state key miss with the owner-level set (declined)

When the pre-pass captured no entry for a referenced entity that dispatch later resolves, fall back to the
owner-level answer rather than to nothing.

- **Pros:** tolerant of any drift between the capture-time and dispatch-time resolutions.
- **Rejected because:** it reinstates the collapse the whole change exists to remove, in exactly the case where
  reference granularity matters. A miss is not "unknown": the map is built from the same resolution its consumers
  iterate, so a referenced entity absent from it was absent from the resolution and contributed nothing.

## Decision

**Chosen: Option A, with key misses suppressed (against Option D).**

The single fact driving all of it is that `and` under a `referenceHaving` is an owner-set intersection. Any fix
that leaves the PK *beside* the condition is asserting something weaker than intended; only merging it *inside* a
scope container gets same-target semantics from the query engine rather than from a second evaluator. Once the
merge descends, taking both axes from the contribution is free, and the dependency-type switch — which had grown
one branch per path and still missed two — disappears.

Option C would win if the per-contribution filter cost turned out to dominate at realistic fan-out **and** the
two evaluation engines could be shown to agree by construction rather than by testing. The first half is
measurable (see *Consequences*); the second is the part that would need a real argument.

Option D would win if the capture-time and dispatch-time resolutions were shown to diverge for reasons unrelated
to the batch. They should not: a cross-entity mutation does not touch the owners' references.

## Key technical details

- **`ContributionVerdicts`** (`evita_engine/.../index/mutation/ContributionVerdicts.java`) is the answer plus its
  granularity. `perReferencedEntity == null` means the condition provably cannot tell an owner's references
  apart, so the owner-level bitmap *is* the per-contribution answer and no per-reference evaluation was spent.
  **A missing key means "no contribution", never "unknown"** — see Option D.
- **The gate is in `ReevaluateExpressionExecutor#evaluateCondition`**: per-contribution evaluation runs when the
  condition contains an `EntityHaving` or a `GroupHaving`, and only then. A pure `$entity.…` or
  `$entity.parentEntity.…` condition keeps its single filter run, because every reference of an owner shares one
  verdict by construction. This gate is the entire cost story: widening it would make every trigger pay
  per-contribution evaluation for nothing.
  **The number of resolved contributions is deliberately not part of the test**, and an earlier revision that
  added `&& affected.groups().size() > 1` was wrong. `affected` enumerates only the contributions *this
  mutation* resolves; an owner's other references never appear in it, and they are exactly what an unpinned
  `groupHaving` can be satisfied by. A single resolved contribution therefore still needs both pins — it just
  costs one filter run to apply them, which is what the unpinned path spent anyway.
- **`injectPkScope` deliberately does not descend into `Not`.** `not(entityHaving(x))` asks whether the owner has
  *some* reference failing `x`; merging the PK inside asks whether some reference fails "x and is the mutated
  entity", which any unrelated sibling satisfies. Pinning a negated branch means *restructuring* it to
  `entityHaving(and(pk, not(x)))`, not merging into it. Currently unreachable — `!=` and `!` are folded *inside*
  the scope container by `ExpressionToQueryTranslator#wrapForPathType`, never around it.
- **`rewriteMatchingReferenceHavings` guards `HierarchyWithin` / `HierarchyWithinRoot`** alongside
  `EntityHaving` / `GroupHaving`. `PARENT_ENTITY_REFERENCE_ATTRIBUTE` translates to
  `hierarchyWithinSelf(referenceHaving(otherRef, …), directRelation())`, whose inner clause describes the
  *parent's* reference, not the owner's. Required once the `PARENT_*` early return went; it also closes a latent
  bug on the pre-existing path, since a condition mixing a parent predicate with a referenced-entity one carries
  both halves in the filter whichever dependency type fires.
- **The implicit batch is captured between generation and application.** Implicit mutations are derived from the
  containers *after* the root batch has been applied, so they cannot be captured alongside it; the collector
  captures them the moment they are known and before any is applied, and put-if-absent keeps the root batch's
  genuinely pre-batch answer wherever the two fire the same trigger.
- **Trigger discovery joins the two mutation lists after the registry null-check**, not at the call site, so a
  catalog declaring no expression trigger allocates nothing for a feature it does not use.

## Verification

Every case below was written as a failing test first and shown to fail against the unfixed engine — issue #1470
states, correctly, that a test which does not fail beforehand is not evidence.

- `ConditionalBucketIndexingTest#shouldDrainOwnerFromBucketWhenEverySiblingStopsQualifyingUnderTwoPredicateCondition`
  and `…UnderCompoundCondition` — the scope-container defect, ungrouped and grouped. Both failed on both catalog
  states before Fix 1.
- `…#shouldDrainOwnerFromBucketWhenEverySiblingReferenceStopsQualifying` is the **one-variable discriminator**:
  identical fixture, one predicate instead of two, passing before and after. It is what proves the two-predicate
  failure is the merge and not the drain logic.
- `…#shouldNotInflateOwnerCardinalityWhenGroupMutationResolvesNonQualifyingSibling` — #1470's own scenario. It
  survives Fix 1 *by rule* (with `isGroupScope = true`, `EntityHaving` is the opposite scope container, so the
  answer stays owner-level) and goes green only with per-contribution evaluation, so it gates that specifically
  rather than passing for a neighbouring reason.
- `ConditionalBucketIndexingTest$CrossEntityParentEntityTriggerTest#shouldNotIndexNonQualifyingReferenceWhenParentMutationFires`
  — the parent path, previously uncovered by any fixture. Its sibling
  `#shouldIndexEveryReferenceWhenParentOnlyConditionTurnsTrue` is the discriminator, and passed throughout: a
  pure-parent condition is genuinely owner-level, so one answer per owner is the correct granularity there.
- `…#shouldFireCrossEntityTriggerForImplicitlyDefaultedReferenceAttribute` — the implicit-mutation gap.
- `ReevaluateExpressionExecutorTest$ContributionVerdictsTest` pins the three-way contract directly, including
  `#shouldAnswerUnknownReferencedEntityWithNothing` for the Option D decision.
- Targeted sweep — `ConditionalBucketIndexingTest`, `ConditionalFacetIndexingTest`, `ConditionalBucketQueryTest`,
  `ReevaluateExpressionExecutorTest`, `AffectedEntityResolutionTest`,
  `EntityIndexLocalMutationExecutorTriggerTest`: **296 tests, 0 failures, 0 errors**.
- Full functional sweep (`-P unitAndFunctional`, fixed parallelism 8, 12 GB fork heap): **23,730 tests,
  0 failures**, the single error being `ExportS3ServiceTest` failing to find a Docker environment — the
  standing environmental exception on this workstation, unrelated to this change.
- **Three white-box tests earned their keep.** `shouldPreventCrossReferenceFalsePositive`,
  `shouldInjectGroupPkIntoEverySiblingGroupHaving` and `shouldInjectGroupPkIntoEveryOrBranchReferenceHaving`
  failed against a first revision of the gate that also required more than one resolved contribution, which
  would have dropped the group pin for single-contribution mutations. They assert the injected filter's shape
  rather than an outcome, which is why they caught a hole the functional suite did not.
- `shouldUseGlobalEvaluationWhenNoGroupHaving` was **deliberately superseded**, not repaired: "no `groupHaving`
  ⇒ global evaluation" is no longer the contract, because a condition reading the referenced entity can tell an
  owner's references apart with or without a group. It was restated as
  `shouldEvaluatePerContributionWhenConditionReadsReferencedEntity` (same fixture and outcome assertions, now
  expecting one evaluation per contribution) and paired with
  `shouldUseGlobalEvaluationWhenConditionCannotTellReferencesApart` for the other side of the gate.

### Measured cost

`ConditionalHistogramTriggerBenchmark` (`evita_test/evita_performance_tests/.../performance/conditionalindex/`),
JMH 1.37 on JDK 21.0.12, 3 forks x (3 x 2 s warmup + 5 x 2 s measurement), 12 points, idle workstation. The
measured operation applies one prepared `EntityUpsertMutation` writing a single attribute on a hierarchy root
with 50 owners beneath it, each holding `fanOut` references carrying a conditional bucketed histogram. The
catalog stays in `WARM_UP`: the trigger path is the same code in both catalog states, so the *delta* transfers
to `ALIVE`, but the absolute numbers do not - `ALIVE` adds WAL append and trunk incorporation on top.

Three schemas, differing in one factor each, so the granularity is not confounded with predicate count:
`ownerLevel` (one parent-only predicate, one evaluation per mutation), `ownerLevelTwoPredicates` (two
parent-only predicates, still one evaluation), `referenceGrained` (two predicates, the second reading the
referenced entity, one evaluation per resolved contribution).

**With the per-mutation fsync removed** (`-Devita.benchmark.syncWrites=false`), which is what prices the index
work itself - ms/op, +- 99.9% confidence:

| fanOut | ownerLevel | ownerLevelTwoPredicates | referenceGrained |
|---|---|---|---|
| 1 | 0.245 +- 0.111 | 0.326 +- 0.128 | 0.346 +- 0.180 |
| 4 | 0.269 +- 0.093 | 0.342 +- 0.100 | 0.450 +- 0.143 |
| 16 | 0.473 +- 0.166 | 0.515 +- 0.094 | 0.931 +- 0.117 |
| 64 | 1.202 +- 0.257 | 1.228 +- 0.225 | 3.317 +- 0.245 |

- **Per-contribution cost is ~26 us and linear.** `referenceGrained - ownerLevelTwoPredicates` divided by
  fan-out gives 20, 27, 26 and 33 us across the four fan-outs - flat, which is what "one filter evaluation per
  contribution" predicts. It is a slope, not a step: nothing is paid per mutation, everything per contribution.
- **Below fan-out 16 the cost is not measurable.** At fan-out 1 and 4 the gap (0.020 and 0.108 ms) sits inside
  the combined error bars. The first fan-out at which the difference clears its own noise is 16.
- **The extra predicate is nearly free, and the control confirms the gate.** `ownerLevelTwoPredicates` sits
  0.081, 0.073, 0.042 and 0.027 ms above `ownerLevel` - no fan-out scaling. This was designed as a falsifiable
  check on the gate: had the executor been sending owner-level conditions down the per-contribution path, this
  series would have sloped like `referenceGrained`. It does not, so the gate discriminates on condition shape
  as intended. The functional suite cannot see this, because it asserts outcomes rather than evaluation counts.

**With durable writes on** (the engine default), the fsync floor is ~18 ms and dominates: `referenceGrained`
costs +13.3% at fan-out 64 (22.03 vs 19.44 ms) and +6.1% at 16, and is inside the noise at 4 and below. The
absolute granularity cost is 2.59 ms at fan-out 64, statistically the same 2.09 ms measured without the fsync -
as expected, since it is the same index work either way. Both framings belong in the record: a durable
deployment pays the smaller *relative* number, and the larger relative numbers above are what the index work
costs once IO is out of the way.

**Allocation is the sharper cost.** `gc.alloc.rate.norm` at fan-out 64 rises from 2.9 MB/op
(`ownerLevelTwoPredicates`) to 9.5 MB/op (`referenceGrained`) - roughly 100 kB per contribution, against ~26 us
of CPU. Each contribution re-parameterises the filter and runs it as its own query, and that allocation, not
the CPU time, is what a high-fan-out schema will notice first. Reducing it is the obvious follow-up if the path
ever shows up in a profile.

## Consequences & open follow-ups

- **Where the added cost actually lands.** The fan-out is the number of distinct
  `(referencedEntityPK, groupPK)` contributions the mutation resolves, and that differs sharply by dependency
  type. A `REFERENCED_ENTITY_*` mutation on an **ungrouped** reference resolves exactly one — the mutated entity
  — so it still spends one filter evaluation, unchanged; on a grouped reference it resolves one per group the
  entity appears in. A `GROUP_*` mutation resolves one per referenced entity in the group, and a `PARENT_*`
  mutation one per referenced entity across the whole subtree. The cost therefore concentrates on group and
  parent mutations, which are the rarer of the six paths — but it is unbounded in the schema, so it needs a
  number rather than an argument.
- **The cost is a slope on fan-out, and it is affordable up to the fan-outs measured.** See *Measured cost*:
  ~26 µs and ~100 kB per contribution, not measurable below fan-out 16, +13.3% on a durable write at fan-out
  64. The number that matters for a schema author is the second one — a `PARENT_*` mutation over a subtree with
  a few hundred referenced entities allocates tens of megabytes per write. The benchmark is committed, so the
  next change to this path can be compared against it rather than argued about.
- **The pre-pass's own second evaluation is still unmeasured.**
  [[2026-08-31-cross-entity-histogram-removal-pre-pass]] added a condition evaluation per firing trigger and
  never measured it. This benchmark does not isolate it either: it prices owner-level against
  reference-grained, both of which pay the pre-pass. Separating it needs a build with the pre-pass removed,
  which is a different experiment.
- **A production catalog is not a substitute here.** The production e-commerce catalog available for
  benchmarking (18 collections; 2.8 GB product collection) declares no conditional bucketed histogram at all and
  carries no WAL slice, so `WalReplayBenchmark` would measure zero of the changed path. It remains useful as the
  *regression* baseline — proving the change costs nothing where the feature is unused.
- **Negated branches are not pinned.** See *Key technical details*; unreachable through the current translator,
  and the fix if it ever becomes reachable is a restructure rather than a merge.
- **Already-drifted catalogs are not repaired**, unchanged from
  [[2026-08-31-cross-entity-histogram-removal-pre-pass]]: a catalog that lost or gained histogram entries before
  this fix keeps the wrong cardinality until the affected histograms are rebuilt.

## Related work

- [[2026-08-31-cross-entity-histogram-removal-pre-pass]] — introduced the pre-pass this change makes
  reference-grained, and named both remaining gaps (owner-grained capture, implicit mutations) as #1470. Its
  `GENERATE_ATTRIBUTES`-is-benign claim was corrected in place while this work was under way.
- [[2026-04-23-bucketed-histogram-indexing]] — the cardinality-gated insert/remove pair and the once-per-bucket
  invariant. This change restores that invariant on the paths where a fan-out could violate it.

## Timeline

- **2026-09-08** — claims in #1470 verified, two of them corrected; five failing tests written; three fixes
  implemented
