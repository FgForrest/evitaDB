# InvertedIndex bitmap → single-record demotion under churn (commit-time, deferred)

Issue: #760 (heap refinement of the columnar bucket store). Not a correctness gap — queries are
already correct either way; this reclaims heap that single-record compaction was designed to save but
currently leaks for buckets that were briefly multi-record and then shrank back to one.

## 1. Problem & scope

`TransactionalBucketBPlusTree` (the sole backing store of `InvertedIndex` — the index fully delegates,
`InvertedIndex.java:566,595`) stores each value→records bucket in one of two forms, discriminated
**only** by `overflow == null || overflow[i] == null` (never a sentinel int,
`TransactionalBucketBPlusTree.java:75-78`):

- **single** — the lone id as a bare `int` in the `IntRecordColumn` (`ValueToRecordPrimitive`
  flyweight). No bitmap, no inner transactional layer.
- **multi** — a `TransactionalBitmap` in the lazy overflow column (`ValueToRecordBitmap` flyweight).

Transitions today are **one-way** (`:80-85`):

- promote single→multi on a second distinct id (`addToExistingBucket:4058-4060`,
  `addRecordsToExistingBucket:4080-4084`);
- delete a multi bucket only when it drains to **zero** (`removeFromBucket:4100-4104` →
  `deleteBucketAt:4168-4180`, releasing the bitmap layer via `discardRemovedValueLayer:498-502`).

**The gap.** When `removeRecord` churns a multi bucket down to **exactly one** remaining id,
`removeFromBucket:4105` returns `false` and keeps the full `TransactionalBitmap` forever (until the
bucket empties entirely). The ≈7–8× heap that the primitive form saves is not reclaimed for
formerly-multi, now-singleton buckets.

**Addressable population — bounded, not the whole tail.** Genuinely-unique values were *never*
promoted and are already primitive. Demotion only reclaims buckets that had ≥2 records and churned
back to 1 (product deletions, attribute-value reassignment). This is a real but bounded subset; see
§8 for an optional measurement gate before/after.

**Non-goals.** No change to the `≤2` promotion rules, the `LongPayloadBucketTree` UNIQUE path (never
promoted, `:641,853`), the on-disk format, or the query path.

## 2. Why the two "deferred" concerns are resolved

The issue defers this over *1↔2 oscillation* and asks for hysteresis. Analysis of the code settles it:

- **Cross-commit hysteresis ("demote after N stable commits") is rejected — self-defeating.** It needs
  per-bucket state that survives commits, but (a) the tree discards its transactional layers each
  commit — no cross-commit scratch space; (b) a bucket has no stable identity across commits —
  split/merge/steal relocate it between leaf slots (`ensureOverflowForSteal`, merge/steal paths); (c)
  the only way to persist per-bucket state is a new parallel column, re-imposing exactly the per-bucket
  heap overhead on the near-unique tail that the primitive form exists to eliminate. It costs the
  memory it reclaims.

- **The clean fix is *deferred* demotion decided once at commit — oscillation becomes impossible by
  construction.** Within a transaction a multi bucket is **one** `TransactionalBitmap` mutated in
  place; add/remove/add just mutates its diff layer with zero allocation churn. The *only* op that
  frees the bitmap is demotion. Eager demotion (in `removeFromBucket` the instant size hits 1) is
  precisely what creates thrash — a later add re-promotes → re-allocates. So: **never demote
  mid-transaction; decide once at commit on the final committed cardinality.** At most one
  promote-alloc and one demote-free per bucket per transaction, regardless of how many times it
  crossed the 1/2 boundary. No cross-commit memory, nothing to remember.

## 3. Design — demote in the leaf commit-merge

The hook is `BPlusLeafTreeNode.createCopyWithMergedTransactionalMemory` (`:3723-3808`), inside the
existing overflow commit-wrap loop (`:3746-3767`). This method is invoked by the commit sweep **only
for touched leaves**; untouched leaves are carried by reference and never visited — so demotion is
naturally scoped to leaves that changed this transaction, and legacy singletons on untouched leaves
are left until their leaf is next touched (low churn, opportunistic).

Current loop, per overflow slot `i`:

```
original  = theOverflow[i]                                   // may be null (single bucket)
committed = wrapOverflow(getStateCopyWithCommittedChanges(original))   // plain Bitmap re-wrapped
// lazily allocate newOverflow when committed != original, then newOverflow[i] = committed
```

**New behavior.** `getStateCopyWithCommittedChanges(original)` returns the committed state as a plain
`Bitmap` (`wrapOverflow:443-449` confirms it is a `Bitmap`). Read its cardinality *before* wrapping:

```
Object committedState = transactionalLayer.getStateCopyWithCommittedChanges(original);
Bitmap committedBitmap = (Bitmap) committedState;
int    card           = committedBitmap.size();          // Bitmap.size():56
if (card == 1) {
    // DEMOTE: write the surviving id into the records column, drop the bitmap slot
    ensureNewOverflow();                                 // allocate newOverflow if not yet (copy of theOverflow)
    ensureNewRecords();                                  // COW-clone theRecords on first demotion in this leaf
    newRecords.setAt(i, committedBitmap.getFirst());     // sole surviving id — Bitmap.getFirst():140
    newOverflow[i] = null;                               // slot becomes single (overflow[i] == null)
    // do NOT wrap / do NOT re-store the bitmap
} else if (card == 0) {
    throw new GenericEvitaInternalError(...);            // a present overflow slot cannot be empty (see §4.4)
} else {
    TransactionalBitmap committed = wrapOverflow(committedState);
    // ...existing keep-multi path (allocate newOverflow if committed != original; newOverflow[i]=committed)...
}
```

**Read the surviving id from the committed bitmap, never from `records[i]`.** After promotion
`records[i]` retains the *first* id and is don't-care (`:77`); the id that survives a drain may be a
different one. Use `committedBitmap.getFirst()` (a cardinality-1 bitmap's first == its only element).

**`newRecords` copy-on-write mirrors the existing `newOverflow` idiom.** Track a `RecordColumn
newRecords = null`; on the first demotion in a leaf, `newRecords = theRecords.duplicate()`
(`RecordColumn.duplicate():80` — deep copy) and thereafter `newRecords.setAt(i, id)`. The node rebuild
uses `newRecords != null ? newRecords : theRecords`. This is self-evidently safe (never touches the
pre-commit base array) and costs one small-array copy (≤64 ints, `DEFAULT_VALUE_BLOCK_SIZE`) per
affected leaf — negligible. (An in-place write into `theRecords` would also be safe because every
transactional mutation decouples the records column up front — `addRecord:3843`, `removeRecords:4030`
→ `decoupleTransactionalArrays:4232-4234` — but the COW form removes any dependence on that reasoning
and is the robust choice for this plan.)

**Node rebuild.** A demotion always sets `newOverflow[i] = null`, so `newOverflow != null` whenever we
demote ⟹ the existing `newOverflow != null` rebuild branch (`:3770-3778`) is taken; pass `newRecords`
there. `newRecords != null ⟹ newOverflow != null` (assert it). The other rebuild branches
(`layer != null`, `!transactionalLayer`, `else return this`) are reached only when no bitmap changed
and thus never carry a demotion — they keep passing `theRecords` unchanged.

## 4. Lifecycle correctness (the issue's real risk area)

### 4.1 records-column safety — handled by COW
`theRecords` is carried by reference into the rebuilt node (`:3773`). The `newRecords` COW clone
(§3) guarantees we never mutate the pre-commit base. No reliance on decouple timing.

### 4.2 No explicit layer discard needed at commit — and why this differs from the delete path
The mutation-time `deleteBucketAt` must call `discardRemovedValueLayer` (`:4171`) because it removes
the bitmap from the leaf's overflow array **mid-transaction**: at commit the leaf's merge loop never
visits the orphaned producer, its layer is never merged, and it is detected ALIVE → 
`StaleTransactionMemoryException` (`:488-494`).

Commit-time demotion is the opposite situation: the bitmap is **still present** in `theOverflow` at
commit, we **do** call `getStateCopyWithCommittedChanges(original)` on it (identical to the kept-multi
path, which also never explicitly discards — it relies on the sweep), and only *then* drop the result.
Because its layer is consumed by the same merge call every surviving multi bucket uses, no explicit
`removeLayer` is expected. For an unchanged legacy singleton (`committed == original`) no layer was
ever opened, so dropping it is trivially free.

**Risk & guard.** This is the one fact I cannot fully settle by static reading — whether
`getStateCopyWithCommittedChanges` alone marks the layer consumed, or a follow-up `removeLayer` is
also required. The §7 test suite must include a drain-to-one-then-commit case that would surface a
`StaleTransactionMemoryException`; if it fires, add `original.removeLayer()` on the demote branch
(idempotent, matches the delete path) and re-run. Treat the no-discard assumption as *verified by test*,
not asserted.

### 4.3 `RecordColumn.setAt` does not exist yet — add it
`RecordColumn` (sealed, `:55`) exposes `insertAt`/`removeAt`/`clearAt`/`intAt`/`longAt`/`capacity`/
`duplicate` but no in-place set. Add `void setAt(int index, long value)` to the interface and both
impls (`IntRecordColumn` narrows the `long` back to `int` as `insertAt` already does; `LongRecordColumn`
stores verbatim — it is never demoted, but the method belongs to the shared surface). Pure addition,
no call-site churn.

### 4.4 `card == 0` is a premise violation
A bucket that drains to zero is deleted at mutation time (`removeFromBucket:4100-4104`), so a present
`overflow[i]` at commit always has `card ≥ 1`. `card == 0` ⇒ throw `GenericEvitaInternalError`
(project defensive-design rule — never silently skip an impossible branch).

### 4.5 dirty / page re-emission — already covered
The `removeRecords` that drained the bucket already set `layer.dirty = true` (`:4020`), so the page is
flagged for re-emission. Demotion adds no new dirty-tracking.

## 5. Wire format & BWC — none

`InvertedIndex.getValueToRecordBitmap()` already materializes single→bitmap at the persistence boundary
(`:659-671`), so the on-disk form is normalized by cardinality: a one-record bucket serializes
identically whether it is in-memory primitive or bitmap. Demotion is **wire-neutral** — no serializer
change, no `serialVersionUID` bump, no BWC reader (consistent with the intra-dev no-bump policy).

## 6. Implementation steps

1. **`RecordColumn.setAt`** — add `void setAt(int index, long value)` to the sealed interface (`:55`)
   and implement in `IntRecordColumn` (narrow to `int`) and `LongRecordColumn` (store `long`). JavaDoc.
2. **Leaf commit-merge demotion** — in `BPlusLeafTreeNode.createCopyWithMergedTransactionalMemory`
   (`:3723-3808`): split the committed-state read from the wrap; add the `card==1` demote branch, the
   `card==0` throw, and the `newRecords` COW; thread `newRecords` into the `newOverflow != null`
   rebuild branch. Assert `newRecords != null ⟹ newOverflow != null`.
3. **JavaDoc** — update the class-level "Promotion / demotion" note (`:80-85`) and the
   `removeFromBucket`/`deleteBucketAt` docs to describe deferred commit-time demotion and the
   never-demote-mid-transaction invariant. Update the mirror sentence in `InvertedIndex.java:83-84`
   ("there is no demotion back") to describe the new commit-time demotion.
4. **Tests** — §7.
5. **Verify** — `mvn -pl evita_engine test` for the tree/inverted suites, then the long-running
   churn/savepoint suites; full functional gate before commit.

## 7. Test plan (TDD — red first)

Unit (`TransactionalBucketBPlusTreeTest`, `InvertedIndexPrimitiveBucketTest`):

- **Drain-to-one demotes** — add ids `{a,b,c}` (promotes), remove `{b,c}` in a transaction, commit;
  assert the bucket reads back as single (`cursor.singleRecordId()` path / `ValueToRecordPrimitive`
  form, `overflow[i] == null`) holding `a`, and the query result is `{a}`.
- **Surviving id ≠ first id** — add `{a,b}`, remove `a`, commit; assert single bucket holds `b` (proves
  we read `committedBitmap.getFirst()`, not the stale `records[i]==a`).
- **No mid-transaction oscillation** — add `{a,b}`, remove `b`, add `c`, remove `c`, all in one
  transaction; commit; assert single `{a}` and (via a spy / allocation assertion if feasible) that the
  bucket was not re-promoted/re-demoted mid-transaction — the bitmap object was allocated at most once.
- **No stale layer** — the drain-to-one-then-commit case must complete without
  `StaleTransactionMemoryException` (guards §4.2). Also a variant that mutates *other* buckets in the
  same leaf so `newOverflow`/`newRecords` COW paths are exercised alongside a demotion.
- **Non-transactional path unaffected** — bulk build / non-transactional removal still deletes on
  drain-to-zero and leaves a drained-to-one bucket correctly single after the operation.
- **UNIQUE long-payload tree untouched** — its buckets never enter the overflow path; a regression
  guard that the long-payload API still round-trips.

Long-running (`LongRunningTransactionalBucketBPlusTreeTest`, `LongRunningInvertedIndexTest`,
`LongRunningSavepointInvertedIndexTest`): the existing randomized churn/savepoint harnesses are the
real oscillation oracle — run them post-change and confirm 0F/0E, with a savepoint/rollback case that
crosses the 1↔2 boundary repeatedly.

## 8. Optional measurement gate (recommended before merge, not before coding)

Because the addressable population is bounded (§1), quantify it on real churn to decide whether the
refinement pays: instrument a decodoma churn run to count, at rest, buckets with
`overflow[i] != null && bitmap.size() == 1` (formerly-multi singletons) and the heap they hold. If the
count is trivial the feature stays behind the gate; if material, the commit-time design above ships.
This satisfies the issue's "profiling on real churn patterns" ask and matches the #760 measurement-gate
discipline. The implementation is cheap and low-risk enough to build first and gate on the number.

## 9. Risks & rollback

- **Layer lifecycle (§4.2)** — primary risk; nailed by the no-stale-layer test. Fallback is a
  one-line `original.removeLayer()`.
- **Over-invalidation of the formula cache** — a demotion rewrites the leaf (fresh `leaf.id`), which is
  already true for any drain (`removeRecords` rebuilds the leaf). No new invalidation surface.
- **Scope creep into legacy-singleton sweeps** — explicitly avoided: only touched leaves are visited;
  no forced full-tree rewrite.
- **Rollback** — the change is contained to `RecordColumn.setAt` + one leaf-commit branch; reverting
  both restores today's keep-forever behavior with no format implications.

## 10. Out of scope

Cross-commit hysteresis (§2, rejected); demotion of the UNIQUE long-payload path; any change to the
≤2 promotion thresholds; on-disk format changes.
