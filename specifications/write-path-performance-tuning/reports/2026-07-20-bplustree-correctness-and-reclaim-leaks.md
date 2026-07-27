# B+ tree shared-array corruption and the dropped-index reclaim leak

Consolidated from four working documents (2026-07-19 → 07-20). These are correctness fixes discovered
*while* pursuing write-path performance — both bugs sat in the same transactional-memory seam the
tuning work was already touching, and both are recorded here because that seam keeps recurring across
the whole tuning line (see also the by-PK view drift and orphaned-facet-layer fixes referenced from
`../MEASUREMENT_RECIPE.md`-adjacent memory).

**Staleness note.** Status language is a historical snapshot; both fixes have merged.

---

## Bug 1 — shared-array shrink corrupts a co-holder's B+ tree leaf

### Symptom and root cause

`LongRunningEvitaReferencesGenerationalTest` (seed `1623796816`) failed at modification 284 with a bare
`NullPointerException` inside `TransactionalElementBPlusTree.validateDirtyScope` — the pre-commit
dirty-scope validator, whose entire purpose is to fail loudly on stale-twin corruption instead of
healing it silently. **It was doing its job; it just had no usable diagnostic.**

The first theory — that the emptiness guard (`getPeek()`) and the boundary-key read
(`getLeftBoundaryKey()`) observed different states (layer vs base) — was **false**. Both go through
`currentState()` on the same object and cannot disagree. The real mechanism was array aliasing between
**two different node objects sharing one array**, not two accessors on one node:

`BPlusLeafTreeNode.setPeek`'s `layer == null` branch shrank a leaf by blanking the vacated tail **in
place** (`Arrays.fill(this.values, peek + 1, originPeek + 1, null)`). But `createLayer()` hands the diff
layer *the very same array*, and it stays shared until something decouples it. Emptying a merge donor
(`nextNode.setPeek(-1)` in `mergeWithRight`) therefore blanked an array a co-holder still read, leaving
a node that reported `peek == 63` while carrying nothing. That node was a registered dirty-scope token,
so validation dereferenced `values[0]` on a null slot.

**The layer branch already guarded exactly this with copy-on-write** (`if (layer.values == this.values)`
copies before mutating). The base branch was the asymmetry — it never checked whether it was safe to
mutate in place. Fix: shrink by installing a private copy (`newValueArrayLike` + arraycopy `[0..peek]`),
correct whether the aliased array is live or dead, so no sharing-detection logic is needed at all.

### Method note — a lesson worth repeating for any aliasing bug

Two diagnostic probes failed because they watched the wrong subject: one recorded state changes on the
node that ended up bad, another watched `setPeek` calls *against it* — neither fires when the mutation
happens on a **different object sharing the array**. What worked was a temporary
`IdentityHashMap<valueArray, StackTraceElement[]>` keyed on the **shared resource itself**, which named
`mergeWithRight → setPeek(-1)` immediately.

> **For aliasing bugs, key the diagnostic on the shared resource, not on the object you suspect.**

Also confirmed by direct A/B bisect rather than inspection: an uncommitted `IndexComponent` split
present at the time was **not** the cause — reverting it reproduced the identical failure.

JDWP is acutely timing-sensitive on this test class: working logpoints slowed it ~70× and the bug
stopped reproducing; suspending breakpoints made an unrelated commit deadline fire first. Prefer a
typed exception carrying full state (zero timing perturbation) over live debugging for this class of
bug.

### The fix's cost, and the escape hatch that was NOT taken

The copy-on-write fix turns a zero-allocation `Arrays.fill` into a block-size array allocation
(~272 B) on every base-branch shrink — and the base branch fires both inside a transaction (split/merge
nodes never get an STM layer there) and during warm-up bulk load, i.e. on a path actively being
allocation-profiled.

Two narrower fixes were checked and rejected: calling `decoupleTransactionalArrays()` at the merge site
is a no-op exactly when `layer == null` (the case that needed fixing), and detecting "is this array
actually shared" is **undetectable from the shrinking node** — a layer object has no reference back to
its owner, so it cannot compare arrays.

The free alternative considered — **don't null the tail at all**, relying on `peek`-bounded reads — was
explicitly not adopted, because it does not close a second, unobserved hazard: the old code left arrays
**still shared** after a shrink, so a later insert into the emptied node would write through into the
co-holder's array. That path was never observed but follows directly from the object model. The
copy-on-write fix closes both the read hazard and this write hazard; "don't blank" would have closed
only the first. **The asymmetry between the two hazards was the deciding argument for paying the
allocation.**

### The defect is family-wide — only one of ten node classes was fixed

Every transactional B+ tree in `io.evitadb.index.bPlusTree` shares the construction that produced this
bug: `createLayer()` adopts the base node's arrays by reference, and only the `layer != null` branch of
`setPeek` guards mutation with copy-on-write.

| node class | arrays shared | base branch blanks in place | dirty-scope validation |
|---|---|---|---|
| Element leaf | `values` | **FIXED** | yes, + typed belt |
| Element / IntToLong internal | `keys` + `children` | yes | n/a (leaf-only) |
| IntToLong leaf | `keys` + `values` | yes | **NO — silent** |
| Object leaf | `keys` + `values` | yes | **NO — silent** |
| Object internal | `keys` + `children` | yes | n/a |
| Long leaf | `keys` + `values` | yes | yes, but no typed belt |
| Long internal | `keys` + `children` | yes | n/a |
| Bucket leaf (columnar) | `keys`, `records`, `overflow` | yes | yes, but no typed belt |
| Bucket internal | `keys` + `children` | yes | n/a |

Three aggravating factors the Element leaf did **not** have, which is why this bug could easily recur
silently in a sibling tree:

1. **Silent corruption where no validator exists.** `TransactionalObjectBPlusTree` and
   `TransactionalIntToLongBPlusTree` implement no dirty-scope validator at all — the same corruption
   there produces no pre-commit failure whatsoever.
2. **Primitive arrays fail silently even where validation exists.** Long/IntToLong keys blank to `0`
   / `0L`, not `null`. A zeroed key does not throw — it **mis-routes** a descent instead. The loud NPE
   that exposed the Element bug was, in retrospect, luck: only an object array fails loudly.
3. **The columnar tree truncates rather than blanks.** For a front-coded value column, the empty-fill
   *drops* entries rather than writing a sentinel, so an in-place shrink physically truncates a
   co-holder's column.

**One claim explicitly not made:** the audit established the defect *shape* everywhere by construction
(the same split/merge machinery), but did not re-trace each tree's merge path to prove a live co-holder
is reachable in every one. Only the Element leaf has an *observed* reproduction — the other nine are a
structural risk, not a confirmed incident.

**Left open deliberately:** whether a live *committed* array was ever blanked (which would make this
silent committed-state corruption rather than merely an aborted commit) follows from the object model
but was never observed and is not claimed either way — the fix protects against it regardless. Also
open: why the triggering merge donor had `transactionalLayer == false` when healthy live leaves have
`true`, and an unexplained asymmetry where `mergeWithRight`'s layer branch decouples arrays before
emptying its donor but the base branch does not.

### What shipped

`assertBoundaryKeyReadable` now throws a typed `BPlusTreeCorruptedException` carrying tree/leaf id,
base+layer peek, array lengths, the shared-array flag, and the first null slot — converting any future
occurrence of this class of bug into an actionable report. `ElementLeafSharedArrayShrinkTest` pins the
exact state combination (sabotage-verified: restoring the old `Arrays.fill` turns both new tests red).
Regression: 1954 targeted tests plus the full functional suite, **20 588 tests, 0 failures, 0 errors**.

### Generational fuzz coverage that followed

A gap this bug exposed: only 3 of 5 B+ tree families had both a WARM_UP (non-transactional) and an
ALIVE (transactional, `assertStateAfterCommit`) generational fuzz test, and none exercised `upsert`
consistently. All 5 trees now have both, with insert/upsert/delete mixed per generation and verified
against a `TreeMap`/direct oracle. Two reusable gotchas from that work:

- **Never bulk-edit these test files with regex/sed.** A greedy DOTALL regex merged two churn blocks
  and corrupted 5 files in one pass.
- **`git checkout HEAD -- <file>` discards every uncommitted layer in that file, not just the intended
  one.** These test files carried two independent uncommitted layers (validation updates + churn); a
  restore reverted both at once.

---

## Bug 2 — dropping an emptied reduced index leaks its entire on-disk footprint, permanently

### The leak, in one paragraph

When a reduced (`REFERENCED_ENTITY`) `EntityIndex` that has **already been flushed to disk** is later
emptied and dropped in a transaction, **none** of its persisted storage parts are removed from the
append-only OffsetIndex live set: its manifest, its bitmaps, and every sub-index leaf page it ever
wrote all leak, permanently. The append-only store only shrinks via explicit removal records; the drop
path emits none; **compaction copies the orphans forward forever** — unbounded catalog growth and
progressively slower compaction, invisible to functional tests. The production trigger is ordinary
churn: deleting the last entity that references a given target empties and drops that target's reduced
index (discontinued products, re-categorisation).

### Mechanism, traced end to end

Flush is **pull-model**: `DataStoreChanges.popTrappedUpdates()` is the single drain point, and it walks
**only** the dirty-index map — that walk is the sole seam that emits reclaim diffs and the sole caller
of the reclaim-baseline advance (`notifyFlushed`). `removeIndex` pulls the index **out** of the dirty
set, records an undo, and calls the propagation that drops it from the collection's maps — **emitting
nothing to disk**. The trigger, `EntityIndexLocalMutationExecutor.applyChanges`, drops any touched
non-GLOBAL/LIVE index that is now empty **in the same transaction that emptied it** — before that
transaction's flush ever runs. So the reclaim diff that would have fired for it never runs at all.

Compaction cannot rescue it: it copies the live-set keyset **verbatim, with no reachability sweep**. A
record leaves the live set only through an explicit removal record — exactly the record the drop path
fails to emit. Nor is there a manifest cascade: each reduced index is a top-level entry with its own
manifest record, discovered on reload by scanning; nothing emits a sibling's removal when its owner
disappears.

Two other hypotheses from the audit's original suspect list were **refuted**: there is no rogue
out-of-band index-part writer that could advance the reclaim baseline while sending removals elsewhere,
and rollback is symmetric — the undo journal re-inserts the index into the dirty set on savepoint
rollback, and the diff-layer savepoint reverts the reclaim baseline in lockstep (now pinned by a
sabotage-verified test and a call-site comment recording the guarantee).

### Empirical proof

`RemovedReferenceIndexReclaimTest` warm-up-persists a reduced reference index, captures its manifest
primary key, empties and drops it in a later `WAIT_FOR_CHANGES_VISIBLE` transaction, closes and reopens
the catalog, and asserts the manifest is gone from the on-disk live set. **It failed on `dev`** —
the empty bitmap `[]` in the assertion failure is the emptied index whose manifest was never reclaimed.
This is both the audit's regression guard and its own red→green fix oracle.

### The subtlety that makes this more than "add a removal"

Because the index is already empty at the drop, the components' existing baseline-vs-live reclaim
already yields "remove all pages", and the bitmaps branch already yields the bitmap-part removal — both
come free from simply running the empty index's reclaim. **The only genuinely missing model is removing
the manifest itself** — and naively running the empty index's ordinary reclaim would `re-emit a fresh,
empty manifest` rather than remove it, because the diffing logic sees a structural change (empty vs the
persisted non-empty original) and writes, not removes. **The fix must replace the manifest re-emit with
a manifest removal on the drop path specifically** — it is not a matter of adding one more emission.

The emission primitive (`trapRemoveStoragePart`) is gated on `containsStoragePart`, making it safe and
idempotent for the case where an index is created and emptied inside one never-flushed transaction —
nothing on disk means nothing is removed, so no over-removal risk exists across that boundary.

### Candidate seams — recorded for the eventual implementer

| seam | shape | trade-off |
|---|---|---|
| **A — emit at `removeIndex`** | run the empty index's reclaim into the commit's trapped changes, then flip the manifest write to a removal, before dropping it | localized, but needs the flush accumulator reachable at `removeIndex` time and a dedicated "emit removals" walk rather than the ordinary reclaim call |
| **B — full-footprint removal method** | `EntityIndex.emitRemovalStorageParts(cv, sink)` walks every component asking it to emit removals for everything it persisted | self-contained and symmetric, but each component needs a new "emit all persisted as removed" entry point |
| **C — one final flush** | apply `removalPropagation` immediately (so queries stop seeing it) but leave the index in the dirty set for one more `popTrappedUpdates`, so its existing reclaim runs naturally; add only the manifest-removal flip | keeps reclaim logic in exactly one place, but bends the `removeIndex` contract — a removed-from-collection index lingers in the dirty set for one cycle, and nothing must re-materialize or double-drop it in that window |

Also flagged for whoever implements the fix: the same drop loop runs during warm-up (no transaction),
where an index emptied and dropped before its first flush has nothing on disk — `containsStoragePart`
returns false and the removal is correctly a no-op, by design. Confirm no warm-up scenario persists an
index and drops it within the *same* pre-`goLive` flush window in a way the chosen seam would miss. The
`GroupCardinality` reference-drop component leaks the identical way and is addressed independently of
this file's manifest — whether to fold it into the same fix or handle it at the schema-mutation seam is
an open call for the implementer.

### Verification bar for whichever seam is chosen

`RemovedReferenceIndexReclaimTest` must flip red → green; the full functional suite must stay at
20 588 / 0 failures / 0 errors (the `ExportS3ServiceTest` Docker error is environmental, unrelated); and
a targeted sweep over `*OffsetIndex*`, `*Persist*`, `*EntityIndex*`, `*Compaction*`, `*Transactional*`
must pass.
