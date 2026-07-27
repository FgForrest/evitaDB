# Leaf-granular formula-cache staleness (narrow) + RangeIndex under-invalidation fix

Issue: #760 (payoff of Part B granular storage) — with a correctness prerequisite touching #37.

## 1. Problem & scope

A cached `Formula`'s validity is a single `long` token, `getTransactionalIdHash()` =
`hashLongs(sortAndDedup(gatherTransactionalIds()))`, compared with `==` on cache read
(`CacheEden.java:200`). The token is a hash over the **set** of transactional ids the formula
reads — the "dependency footprint" already exists as a `long[]`.

For a range/histogram slice over an `InvertedIndex`, the built `OrFormula` carries:

- **≤ `EXCESSIVE_HIGH_CARDINALITY` (100) operand bitmaps** → the *individual bucket bitmap ids*
  (`AbstractBitmapCacheableFormula.java:96-118`). Already **per-bucket** — finer than per-page.
  A cached formula over an untouched value range already survives writes to other buckets.
- **> 100 operand bitmaps** → collapses to a single coarse `indexTransactionId`
  (`AbstractBitmapCacheableFormula.java:90-95,110-112`; `JoinFormula.java:249-250`), to bound the
  id-array allocation in `CacheEden` (rationale: `TransactionalDataRelatedStructure.java:43-49`).

So the *only* gap the feature targets is the **>100 fallback**: a wide-range query is invalidated on
any write to the whole index even though it read a handful of pages. This plan refines that fallback
from one whole-index id to the small, bounded set of **leaf/page version ids** the slice actually
crossed. The cache layer is unchanged (it is already a set→hash→`==` compare); only the id-source at
the index read boundary gets smarter.

**Non-goal:** touching the ≤100 path. It is already finer than pages; adding a page footprint there
would only add cost.

## 2. Correctness prerequisite — RangeIndex serves stale results (#37 class)

`RangeIndex implements VoidTransactionMemoryProducer` (`RangeIndex.java:90`) and never overrides
`getId()`, so it returns the interface default `1L` (`VoidTransactionMemoryProducer.java:38-39`). Its
`JoinFormula`/`DisentangleFormula` are all seeded with `getId()==1L`
(`RangeIndex.java:456,488,513,514,650,651` → `createJoinFormulaIfNecessary` → `new JoinFormula(1L,…)`
`:627`). For **>100 range buckets**, `JoinFormula.gatherBitmapIdsInternal` returns `{1L}`
(`JoinFormula.java:249-250`) → `transactionalIdHash` is **constant across commits** → the cached
result is **never invalidated** → inconsistent reads. This matches issue #37 ("inconsistent results,
fixed by disabling cache", open since 2023).

Enumeration of the three fallback-capable formulas (`OrFormula`, `AndFormula`, `JoinFormula`) across
all main-source construction sites confirms **RangeIndex is the only under-invalidating injector**;
every other site uses a versioned id (InvertedIndex `:157`, OwnerFilterIndex `:62`, FilterIndex view
delegation, EntityIndex `:109`, `AttributeHistogramProducer` FilterIndex ids). Price/facet/
histogram/cardinality indexes do not inject `getId()` into a fallback formula.

The `≤100` RangeIndex path is safe (real per-bitmap ids, `JoinFormula.java:258-265`). Only the
high-cardinality collapse is wrong.

## 3. Design — leaf `id` as a page-version token

Every B+ tree leaf already carries `@Getter long id = TransactionalObjectVersion.SEQUENCE.nextId()`
(via `TransactionalLayerProducer`). On commit-merge
(`TransactionalLongBPlusTree.java:2514-2588`) a leaf returns **`this` (same id)** iff no value inside
it changed, no structural change hit it, and it is not split/merge-born; otherwise it is rebuilt as a
fresh instance (fresh id). Untouched sibling leaves are never visited by the merge (only dirtied nodes
get `getStateCopyWithCommittedChanges`), so they keep identity by reference — the identical mechanism
the whole-tree id already relies on (`InvertedIndex.java:148-157`).

Therefore **`leaf.id` is a ready-made per-page version token**: stable when the page is untouched,
fresh when anything on the page changes. Properties:

- Not persisted (regenerated on load). Fine — the formula cache is in-memory and cold after restart.
- `pageSequence` is **not** usable as the token: it is deliberately *carried forward* on in-place
  rewrite (`:2587`), so it is a stable page *identity*, not a *version*. We need the version.
- Page-granular over-invalidation is inherent and accepted: a write to bucket X invalidates a cached
  read of bucket Y when X and Y share a leaf. That is exactly why the ≤100 per-bucket path is kept.

Footprint bound: a K-bucket range crosses `⌈K / min-leaf-fill⌉` leaves (leaf capacity 256, ~128
half-full) → 100–10 000 buckets ⇒ ~1–80 leaf ids. To honor the memory rationale, hard-cap the leaf-id
set: if the range crosses more than `EXCESSIVE_HIGH_CARDINALITY` **leaves**, fall back to the single
whole-index id. This yields a three-tier granularity, footprint ≤100 longs in all cases:

```
operand buckets ≤ 100            → per-bucket bitmap ids           (unchanged)
100 < buckets, leaves ≤ 100      → per-leaf version ids            (new)
leaves > 100 (huge ranges)       → { index.getId() }              (existing coarse fallback)
```

## 4. Implementation phases

### Phase 0 — RangeIndex correctness (independent, ship first) — ✅ DONE (uncommitted)
**Status:** implemented + green. `RangeIndex` now carries `@Getter private final long id =
TransactionalObjectVersion.SEQUENCE.nextId();` (`RangeIndex.java`), overriding the
`VoidTransactionMemoryProducer` default `1L`. Proven safe by direct in-tree precedent: `InvertedIndex`
is itself a `VoidTransactionMemoryProducer` that overrides `getId()` the same way, and `RangeIndex`
never creates its own transactional layer (it mutates through its inner `ranges` tree + `dirty` flag,
each with their own ids), so the id is used *only* as the formula-cache token — no layer-keying impact.
TDD RED-first: `RangeIndexTest.VersionIdentity` (3 tests) — `distinctInstancesHaveDistinctVersionIds`
and `commitAfterMutationChangesVersionId` both failed on `1L` before the fix, pass after;
`commitWithoutMutationPreservesVersionId` guards the no-over-invalidation direction. Regression sweep:
RangeIndexTest 75/75 + full range/formula suite **419 tests 0F/0E**. No on-disk change, no BWC reader.

- Add `@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();` to `RangeIndex`,
  mirroring `InvertedIndex.java:157`; remove reliance on the `VoidTransactionMemoryProducer` default.
  (Keep implementing `VoidTransactionMemoryProducer` for the `Void` diff layer; only override the
  `getId()` accessor.)
- Verify `createCopyWithMergedTransactionalMemory` mints a **new** RangeIndex instance when dirty
  (fresh id) and `return this` when clean (`:837`, stable id) — confirm the dirty branch above.
- Effect: >100 range formulas now invalidate on any range mutation. Coarse but **correct**. No
  on-disk change (id is runtime-only), no BWC reader.
- **RED test first** (TDD): cache-enabled test that (a) runs a `>100`-bucket range filter, (b) mutates
  one range, (c) re-runs and asserts the result reflects the mutation. Must fail on current code,
  pass after the override.

### Phase 1 — read-path leaf-version collection — ✅ DONE (uncommitted, additive/non-behavioral)
**Status:** the cursor now exposes the per-leaf version token. `long currentLeafId()` added to
`BucketCursor` (`TransactionalBucketBPlusTree.java`) and all three impls (`SingleLeafBucketCursor`
captures `leaf.getId()` in its ctor; `ForwardBucketCursor`/`ReverseBucketCursor` capture it in
`loadCurrentLeaf()`), each `positioned`-guarded like the other accessors. No behavior change yet — the
formula wiring is untouched (that is P2). TDD: `TransactionalBucketBPlusTreeTest.LeafVersionTokenTest`
proves the contract — mutating one bucket (single→multi promotion, an in-leaf change) re-mints ONLY its
leaf's id while every sibling leaf keeps its id verbatim. Full bucket-tree suites 89/89 0F/0E.
**Still TODO for full P1:** the RangeIndex range tree (`TransactionalLongBPlusTree`) cursor needs the
same `currentLeafId()` exposure before P3 can wire it.

**Integration point RESOLVED (2026-07-07 investigation):** the InvertedIndex range slice
(`getRecordsInternal`, `InvertedIndex.java:1149`) already walks the tree leaf-by-leaf via a
`BucketCursor` (`this.buckets.cursor(...)`), one bucket per `next()`; it does NOT pre-materialize a
flat array. The `ForwardBucketCursor.loadCurrentLeaf()` (`TransactionalBucketBPlusTree.java:4387`)
already resolves the current `BPlusLeafTreeNode` — it just discards everything but the columns. Adding
`long currentLeafId()` to `BucketCursor` (capture `leaf.getId()` in `loadCurrentLeaf`; 3 impls:
`SingleLeafBucketCursor`, `ForwardBucketCursor`, `ReverseBucketCursor`) exposes the per-leaf version
token with no structural change. **Premise verified for BOTH trees**: the bucket tree's leaf
`createCopyWithMergedTransactionalMemory` returns `this` (stable id) when the leaf had no diff layer,
no overflow-bitmap change, and is a committed (non-split-born) node — `...BucketBPlusTree.java:3801` —
and a fresh instance (fresh id) otherwise; the range tree mirrors this (`TransactionalLongBPlusTree`).
So `leaf.id` is a genuine page-version token (stable-when-untouched) in both, i.e. the optimization has
real precision value, not just correctness.

- Locate the InvertedIndex range-slice read path (bucket iteration for `[from,to]`) and confirm it is
  leaf-visible (walks tree leaves) rather than materializing a flat bucket array first. If it
  materializes, add a leaf-aware slice.
- Add a tree API to collect the **distinct leaf version ids** crossed by a key range, e.g.
  `long[] leafVersionsForRange(from, to)` on the bucket tree, or thread an accumulator through the
  existing range cursor that records `currentLeafNode().getId()` as it advances (dedup consecutive).
  Expose leaf `getId()` on the leaf SPI (`LeafBPlusTreeNode`) — the concrete node already has it via
  `TransactionalLayerProducer`.
- Unit tests: id set stability across an untouched commit; id change after mutating a bucket in one
  covered leaf but not another; correct dedup.

### Phase 2 — wire InvertedIndex >100 fallback to leaf ids — ✅ DONE (uncommitted, behavioral)
**Status:** implemented + green. `InvertedIndexSubSet` now carries a `long[] indexTransactionIds` (was scalar) and a
`BiFunction<long[], ValueToRecord[], Formula>` lambda. `InvertedIndex.getRecordsInternal` collects the distinct
leaf-version ids the slice crosses via a `LeafVersionAccumulator` (consecutive-dedup over the forward `BucketCursor`
+ `currentLeafId()`, capped at `EXCESSIVE_HIGH_CARDINALITY` leaves → else `{getId()}`), sorted to canonical form, and
returns them in a `HistogramSlice` holder alongside the buckets; `getRecordsMatchingFormula`/`toSortedOrFormula` do the
same. The SORTED (`OrFormula`) and UNSORTED (`HistogramBitmapSupplier`) lambdas thread the `long[]` straight through —
the formula layer was already `long[]`-keyed. `HistogramBitmapSupplier` gained a `long[]` ctor (canonical `hashLongs`);
its scalar ctor now delegates to it (so its id-hash is `hashLongs({id})` not `hashLong(id)` — one test assertion
updated). `FilterIndex`'s own range-histogram path was migrated to the `long[]` type but **kept whole-index coarse**
(`{getId()}`) — its buckets come from the RangeIndex point sweep, not InvertedIndex leaves, so its refinement is a
follow-up.
**Acceptance test** (`InvertedIndexPrimitiveBucketTest.LeafGranularStalenessTest`): a > 100-bucket slice's token is
UNCHANGED after a commit that mutated a non-crossed leaf **and changed `getId()`** (proving leaf-granularity beats the
whole-index coarseness — a `{getId()}` token would have invalidated), and CHANGES when a crossed leaf mutates.
Regression: InvertedIndex/Filter/Range/attribute unit + histogram/inSet/string/comparison functional suites, ~600
tests 0F/0E. No on-disk/format change.

- Where the sorted/unsorted subset is built (`InvertedIndex.java:135-146`, `:838-846`;
  `HistogramBitmapSupplier` unsorted path `:129-131`), when operand bitmaps > 100, pass the leaf-id
  set (capped per §3) as `indexTransactionId` instead of `{getId()}`. The formula layer is untouched:
  `indexTransactionId` is already a `long[]` and the `>100` branch already `arraycopy`s it.
- FilterIndex inherits this via its InvertedIndex delegation. `AttributeHistogramProducer` can adopt
  the same in a follow-up (it already passes an N-id array).

### Phase 3 — wire RangeIndex >100 fallback to leaf ids — ⛔ SKIPPED (investigated 2026-07-07; not worth doing)
**Finding:** leaf-granularity is INAPPLICABLE to RangeIndex — its query semantics are inherently
whole-tree-dependent. Every RangeIndex query method that is actually reached from a query
(`getRecordsEnvelopingInclusive` / `getRecordsValidNowFormula` → `AttributeInRange…Now` + price validity;
`getRecordsWithRangesOverlapping` → `AttributeInRange` from/to) reads the WHOLE range-point tree via
`materializeRanges()` (`RangeIndex.java:204`). This is fundamental: the records valid at / overlapping a
threshold depend on *all* starts before it AND *all* ends after it, so a change to any point can change any
range result. The leaf set is therefore always "the whole tree" → always caps to `{getId()}` — the
leaf-granular path would add second-tree cursor plumbing (`TransactionalLongBPlusTree` iterators) for zero
precision gain. The only sub-whole-tree readers, `getRecordsFrom`/`getRecordsTo`, have **no main-source
callers**. P0's versioned whole-index token is not merely acceptable here but semantically correct: any
mutation genuinely can invalidate any cached range result. **Conclusion: RangeIndex is done at P0
(correct); no leaf-granular wiring.** (Optional hardening §4b — sourcing the token from `ranges.getId()`
instead of the dirty-flag-driven instance id — remains available but is orthogonal to granularity.)

### Phase 4 — defensive guard against regression — ✅ DONE (uncommitted)
**Status:** implemented + green. The `> EXCESSIVE_HIGH_CARDINALITY` fallback branch in both
`AbstractBitmapCacheableFormula.gatherBitmapIdsInternal` (strengthened its existing non-null premise) and
`JoinFormula.gatherBitmapIdsInternal` (new premise) now `Assert.isPremiseValid` that the
`indexTransactionId` set is **non-null AND non-empty** — an empty set would leave a high-cardinality
cacheable formula with no staleness dependency (impossible to invalidate = #37). A `{1L}`-style constant
can't be caught structurally, but P0 removed the only such injector and a full sweep confirmed no others.
Test: `OrFormulaTest.HighCardinalityStalenessGuardTest` — 101-bitmap formula with an empty token throws
`GenericEvitaInternalError`; with a non-empty token constructs cleanly. Formula-algebra suites (Join/
Disentangle/Or/And = 81) 0F/0E.

---

## STATUS (2026-07-07): feature COMPLETE, uncommitted

- **P0** RangeIndex versioned `getId()` — ✅ #37-class stale-read fix.
- **P1** `BucketCursor.currentLeafId()` + version-token test — ✅ infra.
- **P2** InvertedIndex/FilterIndex >100 fallback → leaf-version id set — ✅ the leaf-granular payoff.
- **P3** RangeIndex leaf-granular wiring — ⛔ SKIPPED (queries are whole-tree-dependent; P0 token is
  correct — see the Phase 3 rationale above).
- **P4** defensive empty-token guard — ✅.

Net: >100-bucket attribute-filter reads now invalidate only when a leaf they crossed changes (was: any
write to the index). RangeIndex is correct (no longer serves stale results). ~600 functional + 393 unit
tests across the touched surface 0F/0E. No on-disk/serialization change, no BWC reader, runtime-only ids.
Follow-ups (not blocking): FilterIndex range-histogram leaf refinement; `AttributeHistogramProducer`
adoption; optional §4b tree-id hardening.

## 4b. Verified prerequisite — the coarse (tier-3) whole-index id must change on ANY content change

Confirmed for `InvertedIndex`/`FilterIndex`: content change ⟹ new `getId()`, via the chain — sole
mutators `addRecord`/`removeRecord` (`InvertedIndex.java:564,576,590`) each raise a
`TransactionalBoolean dirty` **unconditionally** (`:567,580,593`; `removeRecord` even on a no-op) →
commit reads `getStateCopyWithCommittedChanges(this.dirty)` (`:905`) → `new InvertedIndex` fresh id
(`:917`) or `return this` (`:928`). Imprecision is one-directional and **safe**: it over-invalidates
on no-op removes, never under-invalidates. `OwnerFilterIndex` mirrors this (`:66,250,266`).

Two caveats:
- **RangeIndex fails this today** — its dirty→new-instance machinery works (`:349,381,822,831`) but
  `getId()≡1L` ignores the instance, so a fresh instance carries the old id. **Phase 0 is exactly the
  fix**: an instance-derived versioned id makes the working recreation yield a new id.
- **The guarantee is convention-based** (every mutator must raise `dirty`), not structural. A future
  mutation path that writes the tree without raising `dirty` would silently reintroduce #37.

**Hardening (adopt): source the tier-3 coarse id from the committed tree's own id**
(`buckets.getId()` / `ranges.getId()`) instead of the index's dirty-driven id. The B+ tree root is a
`TransactionalReference`; changed tree → new instance (new id), unchanged → `return this` (stable id)
**by construction** (same return-this-when-clean pattern confirmed at leaf level,
`TransactionalLongBPlusTree.java:2582`). Removes the mutator-discipline dependency and drops the
no-op-remove over-invalidation. Verify the tree *wrapper*'s `createCopyWithMergedTransactionalMemory`
mirrors the leaf-level pattern.

## 5. Correctness invariant (must hold in every phase)

The leaf-id set injected for a slice **must be a superset of the versions of every leaf whose change
could alter the slice's result.** Missing a leaf → stale served → wrong result (the #37 failure mode).
Collection runs over one consistent read snapshot; splits/merges during the txn only *add* fresh ids
(over-invalidation, safe). Tests must include: split/merge inside the covered range, empty range,
single-leaf range, range spanning exactly the leaf cap boundary.

## 6. Verification
- New TDD tests per phase (Phase 0 RED-first).
- Full attribute/range/inverted-index functional suites 0F/0E.
- A cache-enabled churn scenario over a `between` and a datetime-validity filter: assert results always
  match a cache-disabled oracle across a mutation batch (this is the #37 regression guard).
- No serialization/format change anywhere → no BWC reader, no serialVersionUID bump (runtime-only ids).

## 7. Risks
- **Phase 0 changes cache semantics** for >100 range formulas (never-invalidate → always-invalidate on
  range change). Strictly more correct; Phase 3 restores precision.
- **Read-path hook cost**: leaf-id accumulation adds a few longs per slice; negligible vs the slice
  itself, and only on the >100 path.
- **Integration-point unknown (Phase 1)**: whether the InvertedIndex slice keeps leaf boundaries. If
  not, a small leaf-aware slice is the extra work. Resolve before Phase 2.
```
