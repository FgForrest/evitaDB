---
title: Gate cross-entity histogram removal on a pre-mutation condition pre-pass, not bucket membership
date: 2026-08-31
updated: 2026-08-31 11:20
status: accepted
kind: fix
issues: [1467]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/mutation, evita_engine/src/main/java/io/evitadb/core/collection]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing]
---

# Cross-entity histogram removal is gated on the pre-mutation condition, captured before the batch is applied

A conditional (`bucketedPartially`) reference histogram could lose an owner entirely when two of that
owner's references carried source values that normalise to the same bucket key. The cross-entity
re-evaluation path decided whether to remove a contribution by asking *"is this owner in that
bucket?"*, which cannot distinguish *this* reference's contribution from a sibling's. The fix gives
it the question it actually needs — *"did this reference contribute?"* — by evaluating the trigger's
condition once **before** the batch's local mutations are applied and carrying the answer to the
executor on the mutation envelope.

## Why

The histogram is a **multiset**. `SimpleHistogramIndex.insertValue` / `removeValue` gate the
underlying `FilterIndex` membership bitmap on an `AttributeCardinalityIndex` counter keyed by
`(ownerPK, value)`, so the bucket only gains or loses the owner on a `0→1` / `1→0` transition. That
is what lets an owner with two qualifying references survive the removal of one of them, and
[[2026-04-23-bucketed-histogram-indexing]] states the invariant explicitly: *"an owner PK appears at
most once per bucket per histogram — enforced by the cardinality-gated insert/remove pair"*.

The pairing is the whole contract, and the cross-entity path broke it. `ReevaluateExpressionExecutor`
performs a remove-before-add for every affected owner on every dependency change, guarded by
`histogramContainsOwner`. That guard exists to skip a removal for a contribution that was never
created (the condition was false when the value was indexed) — but it tests membership, which is
true as soon as **anybody's** contribution for `(value, owner)` exists. When a sibling reference
occupies the bucket, the guard passes for a reference that contributed nothing and the removal
decrements the sibling's cardinality unit. The counter then reads one where it should read two, and
the next legitimate removal empties the bucket and drops the owner from the histogram outright.

The constraint that made this non-obvious: the *old* condition is genuinely unavailable at the point
of the fix. `LocalMutationExecutorCollector` deliberately runs the index-trigger phase **after** the
container implicit-mutation phase — *"Container mutations must finish first so that storage state is
fully consistent before cross-entity triggers read it"* — so by dispatch time every readable source,
index and storage container alike, already reflects the post-mutation state. There is no
pre-mutation seam on that path; one had to be built.

### Previous state

The local (owner-side) path had already met this problem and solved it:
`EntityIndexLocalMutationExecutor.removeHistogramWithConditionGuard` evaluates each trigger's
condition against pre-mutation storage and skips the removal when it is false, with a JavaDoc naming
the failure verbatim — *"removing values would incorrectly decrement another reference's cardinality
for the same `(value, ownerPK)` pair"*. `ReferenceIndexMutator.isValueInHistogram` sits **behind**
that guard, so the identical membership probe there is belt-and-braces on an already-correct
decision. Only the cross-entity path never got the treatment, because it is the one path where
pre-mutation state is out of reach.

## Options considered

### Option A — read-only pre-pass, answer carried on the mutation (chosen)

Evaluate the histogram conditions twice: once read-only before the batch's local mutations are
applied (so the answer is the pre-mutation one), and once as today. The captured owners ride to the
executor on `ReevaluateExpressionMutation.previouslyIndexedOwnerPKs`, which narrows what removal may
touch.

- **Pros:** both sides use the **same** index-backed evaluator, so old and new conditions cannot
  disagree for any reason other than the mutation itself; no on-disk format change; the added field
  is engine-internal, so no external API moves.
- **Cons:** one extra condition evaluation per firing cross-entity histogram trigger; adds a phase to
  the write-path orchestration that a future change must keep read-only; repairs nothing in catalogs
  that have already drifted.

### Option B — pre-mutation read overlay (declined)

Evaluate the old condition with `AbstractExpressionIndexTrigger.evaluate(...)` against an
`EntityStoragePartAccessor` overlay substituting `preMutationSourceValues` for the mutated entity.

- **Pros:** no change to write-path phase ordering; reuses the expression evaluator the local path
  already relies on.
- **Rejected because:** the cross-entity path has no `EntityStoragePartAccessor` at all — the only
  implementation is `ContainerizedLocalMutationExecutor`, which is mutation-scoped — so a read-only
  accessor over the catalog's `DataStoreReader`s would have to be written from scratch, plus the
  overlay, plus new accessors on `IndexMutationTarget`. Worse, it would introduce **two evaluation
  engines for one condition** (expression evaluator for the old answer, index filter for the new),
  whose divergence would be a fresh class of drift bug. Revisit only if a read-only storage accessor
  appears for another reason *and* the two evaluators are proven equivalent.

### Option C — re-key the histogram cardinality by contributing reference (declined)

Replace the `(ownerPK, value) → count` counter with a `(ownerPK, value) → set of referencedEntityPK`,
making insert and remove idempotent per reference and deleting the guard entirely.

- **Pros:** semantically exact; removes the class of bug rather than the instance; no pre-pass, no
  extra evaluation.
- **Rejected because:** `HistogramCardinalityStoragePart` is a **released** on-disk format. This needs
  a `serialVersionUID` bump plus backward-compatible readers in every configurer that registers it
  (see the `kryo-bwc-audit` skill), and — the real blocker — existing catalogs store counts that
  cannot be converted into attribution sets without rebuilding every histogram. Revisit if the
  histogram storage format is being reworked for another reason and a rebuild is on the table anyway.

### Option D — reconcile to the desired count (declined)

Recompute, per affected `(owner, value)`, how many of the owner's references currently carry that
source value *and* satisfy the condition, then adjust the counter to match.

- **Pros:** self-healing — would repair already-drifted indexes on the next touch; no format change.
- **Rejected because:** there is no owner→references mapping to enumerate. It would have to fan out
  from `sourceFilterIndex.getRecordsEqualTo(V)` across every referenced entity sharing the value,
  with a reduced-index lookup and a condition evaluation each — unbounded work on the write path.
  Revisit if an owner→references mapping ever lands on the `ReferencedTypeEntityIndex`.

## Decision

**Chosen: Option A.** It is the only option that fixes the defect without either changing a released
storage format (C), introducing a second evaluation engine for the same condition (B), or putting
unbounded work on the write path (D). It also mirrors what the local path already does — evaluate
the condition, then decide — which keeps one mental model for "may I remove this contribution?"
across both paths.

The cost that would flip this decision is the doubled condition evaluation. If cross-entity trigger
fan-out ever dominates a write-path profile, Option C becomes the answer — but only alongside a
format rework that can absorb the histogram rebuild.

## Key technical details

- **The pre-pass** — `LocalMutationExecutorCollector.capturePreMutationConditionState`. Runs before
  `entityIndexUpdater.prepare(...)`, which is not cosmetic: `prepare` inserts the entity into the
  global index and can therefore already flip a condition for a freshly created entity.
- **Trigger discovery without side effects** —
  `EntityIndexLocalMutationExecutor.peekIndexImplicitMutations`. It cannot read the removal branch off
  the container (nothing has been applied), so the caller states it. The two agree *by construction*:
  `popIndexImplicitMutations` branches on `containerAccessor.isEntityRemovedEntirely()`, which is a
  `final` field set from `entityMutation instanceof EntityRemoveMutation` at
  `ContainerizedLocalMutationExecutor`'s single construction site — the same expression the caller
  evaluates. **If that flag ever becomes dynamic, the peek must take the same signal instead of
  inferring it**: a disagreement would make the pre-pass capture a narrower trigger set than dispatch
  fires, and the uncaptured triggers would silently fall back to unrestricted removal. The peek may
  report a superset of the triggers that actually fire; surplus captures are never looked up.
- **`null` versus empty is load-bearing.** `ReevaluateExpressionExecutor.evaluateHistogramConditionState`
  returns `null` for "no histogram trigger, nothing to guard" (removal stays unrestricted) and a map
  with a **possibly empty** bitmap for "the pre-pass ran and nobody qualified" (every removal
  suppressed). Collapsing the two reintroduces the bug.
- **The pre-pass must stay read-only.** Every `resolveAffected` path it reaches uses
  `findReferencedTypeEntityIndex` / `getIndexByPrimaryKeyIfExists`, never a `getOrCreate…` variant.
  A future resolution path that creates an index would make the pre-pass mutate state.
- **Bitmaps are materialised** (`new BaseBitmap(split.shouldBeIndexed().getArray())`) because the
  mutations the pre-pass runs ahead of are about to modify the very indexes a passthrough filter plan
  may hand back by reference.
- **`ReevaluateExpressionMutation` identity still covers only its four core fields.** That is what
  lets the pre-pass key its captures by the mutation itself and match them to the dispatched copy —
  and it is why `preMutationConditionState` uses put-if-absent: a collector is constructed per root
  entity mutation and shared by its nested invocations, so the first capture is the genuinely
  pre-batch one. **Do not** clear that map on the dispatch path; a nested invocation would wipe the
  enclosing one's captures before it dispatches.
- **The unrestricted fallback is unreachable in production.** `LocalMutationExecutorCollector` is the
  sole caller of `EntityCollection.applyIndexMutations` and always attaches the captured state; the
  `null` branch in `restrictToPreviouslyIndexed` exists for tests that construct a mutation directly.
  A second dispatch path that skips the pre-pass would silently reintroduce the defect.
- **`ReevaluateExpressionExecutor` stays package-private.** The pre-pass entry point is on
  `IndexMutationExecutorRegistry`, already the package's public door for acting on an `IndexMutation`.
- **Pre-mutation attribute capture now has one funnel** —
  `EntityIndexLocalMutationExecutor.recordCapturedOldEntityAttributeValue`. The two capture sites
  reached the same canonical `BigDecimal` form by different routes (a `normalizing` supplier wrapper
  on one, a hand-written `normalizeIfBigDecimal` call on the other) with the contract written down in
  neither; the funnel applies it once, idempotently.

## Verification

- `ConditionalBucketIndexingTest` — three new cases, each ~0.3 s and each run over `WARMING_UP` **and**
  `ALIVE`. `shouldKeepSiblingContributionWhenSecondReferenceStartsQualifyingForSameBucket` (condition
  change) and `shouldKeepSiblingContributionWhenNonQualifyingReferenceChangesItsValue` (value change,
  the `knownOldValues` branch) both failed on both catalog states before the fix and pass after.
  `shouldKeepQualifyingContributionWhenNonQualifyingSiblingReferenceIsRemoved` passed before and after
   — it pins the local path's guard-before-remove ordering as a regression detector.
- `EvitaConditionalBucketGenerationalTest#shouldSurviveGenerationalTestWithReferencedEntityAttributeExpression`
  with `-Dtest.seed=2095323828`: failed at generation 4 in ~2 s of test time before
  (`expected: <{5=[1]}> but was: <{}>`), runs a full 61 s interval clean after. Pinned as
  `shouldNotSurfaceHistogramDriftForSeed2095323828`.
- `ReevaluateExpressionExecutorTest` — new `PreMutationConditionStateTest` nested class pins the
  `null`-versus-empty contract and the mutation's identity-ignores-payload property directly. Worth
  knowing: the class's 28 pre-existing tests construct mutations without the captured state, so they
  exercise the *fallback* branch, not the guard.
- Regression sweep: `ConditionalBucketIndexingTest`, `ConditionalFacetIndexingTest`,
  `ConditionalBucketQueryTest`, `ReevaluateExpressionExecutorTest`, `ReferencedTypeEntityIndexTest`,
  `ReducedGroupEntityIndexTest` — 395 tests, 0 failures, 0 errors.

## Consequences & open follow-ups

- **Already-drifted catalogs are not repaired.** A catalog that lost histogram entries before this
  fix keeps the wrong cardinality until the affected histograms are rebuilt. Only Option D would have
  been self-healing, and it lost on write-path cost.
- **The write path now pays a second condition evaluation** per firing cross-entity histogram trigger.
  Mutations that fire none allocate nothing (the pre-pass short-circuits on an empty trigger
  collection), so the cost is confined to catalogs using `bucketedPartially`. Not measured — if
  cross-entity fan-out shows up on a write profile, this is the first thing to look at.
- **The pre-pass evaluates a superset of triggers**, since it cannot yet know which attribute writes
  turn out to be no-ops. Cheap to tighten if it ever matters; deliberately not done, because
  narrowing it requires duplicating the executor's no-op detection before the executor runs.

## Related work

- [[2026-04-23-bucketed-histogram-indexing]] — introduced the cardinality-gated insert/remove pair and
  stated the once-per-bucket invariant this record restores. The defect was a violation of that
  record's invariant, not a change to it.

## Timeline

- **2026-08-31** — reproduced deterministically on `release_2026-2` (`e623f2c84`), root-caused via
  JDWP trace of generation 4, reduced to sub-second functional tests, fixed and verified
