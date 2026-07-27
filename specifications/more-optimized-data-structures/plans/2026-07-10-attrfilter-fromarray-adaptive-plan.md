# Plan: heal the `attributeFiltering` regression via adaptive `fromArray`

**Status:** PHASE A DONE — DECISIVE WIN + `/code-quality-pipeline` PASSED (uncommitted). Pipeline:
test-architect +1 guard test (`isDense` reversed-container-order), code-simplifier JavaDoc rewrap;
bug-hunter NO_WORK (verified refactor *fixes* a pre-existing latent hashCode inconsistency — only
`BaseBitmap(int...)` normalized before, now all 5 `fromArray` sites do); javadoc-writer NO_WORK. 121
bitmap tests 0F/0E, workaround + ephemeral sweeps clean. `attributeFiltering`
2237.6 ± 524.1 → **6538.96 ± 1091.8 ops/s** at the identical 24-thread/5-iter/1-fork config
(**2.92×**, and now **~12% past the dev baseline of 5858.4** — regression reversed, not just healed;
error bars disjoint: 5447 vs 2761). 120 bitmap tests green incl. 6 new direct-`fromArray`
equivalence tests. Phase B **NOT needed** (A alone exceeds dev). Next: alloc-profile mechanism
confirmation, then `/code-quality-pipeline`. Forged from Fable 5's review
(`docs/design/2026-07-10-orformula-single-record-batching-consult-RESPONSE.md`) of the two-phase-OR
consult. Fable's verdict **inverts** the priority we proposed: lead with the systemic boundary fix,
demote two-phase OR to a conditional follow-up. Every load-bearing claim below was re-verified
against the vendored sources in this session.

## The regression, restated

`attributeFiltering` on `#760` ≈ 2238 ops/s vs `dev` ≈ 5858 ops/s (~2.6× slower). async-profiler
alloc: **95.4% of bytes** flow through `OrFormula.getRoaringBitmaps()` →
`RoaringBitmapBackedBitmap.getRoaringBitmap(single)` → `fromArray(int[1])` → an **8 KB
constant-memory writer** rebuilding a whole bitmap to hold **one** record id. Cause: `#760`'s
`SingleRecordBitmap` flyweight (a write-churn win) is **not** `RoaringBitmapBackedBitmap`, so it
misses the cheap unwrap that `dev`'s `TransactionalBitmap` took. `fromArray`
(`RoaringBitmapBackedBitmap.java:51-63`) uses the writer **unconditionally**, so every single-record
unwrap pays 8 KB + writer plumbing (~50× the ~150 B a length-1 bitmap actually needs).

## Why the boundary fix leads (Fable's key correction)

`BaseBitmap(int...)` (`BaseBitmap.java:91-106`) **already** contains a JMH-validated, equivalence-
tested adaptive dispatch for exactly this choice:

- `recordIds.length >= WRITER_DISPATCH_DENSITY (4096) && isDense(recordIds)` → constant-memory
  writer + `removeRunCompression()` normalization (writer wins on large **dense** arrays: ≈2–3×
  faster, ≈⅓ alloc).
- else → incremental `new PersistentRoaringBitmap().add(int...)` (wins on small/sparse: the 8 KB
  buffer never amortizes; writer measured **6–7× slower** on large sparse).

`isDense` is an O(1) end-probe (index sets arrive ascending) that returns `false` on non-ascending
ends, so a scattered array can never be mis-routed to the writer. The full rationale +
`BitmapConstructionBenchmark` measurement is documented at `BaseBitmap.java:62-131`.

**The fix is a code move, not new science:** push that dispatch down into `fromArray` itself and let
`BaseBitmap(int...)` delegate. Then all **5** `fromArray` call sites — the 2 unwrap fallbacks
(`getRoaringBitmap`/`getRoaringBitmapClone`), the 2 `BaseBitmap` constructors, and
`TransactionalBitmap(Bitmap)` — inherit the already-benchmarked behavior. Single-record unwraps drop
from ≥8 KB to ~150 B and take the incremental path. Systemic across all 18 formulas (incl. the
AND/NOT nodes sitting above the ORs in the same plans, which the OR-only two-phase idea could not
heal), zero API change, reuses a threshold the project already measured.

Verified facts underpinning this (this session):
- `fromArray` unconditionally uses `.constantMemory()` (RoaringBitmapBackedBitmap.java:55-59). ✔
- Exactly 5 production `fromArray` call sites; no callers outside the bitmap package. ✔
- The writer path never radix-sorts here (`doPartialRadixSort` is opt-in on the Wizard,
  `RoaringBitmapWriter.java:168`; neither `fromArray` nor `buildWriter` sets it) — so the two-phase
  premise "addMany partial-radix-sorts" was **wrong**; unordered `addMany` degenerates to per-value
  `add()` + a wasted 8 KB buffer. ✔
- `PersistentRoaringBitmap.or(varargs)` is `naive_or` (a linear pairwise fold), **no** priority
  queue to lose by batching. `bitmapOfUnordered` (PersistentRoaringBitmap.java:418) is the
  radix-sorting builder if one is ever needed. ✔

---

## Phase A — adaptive `fromArray` (LEAD, ship first)

**A1. Move dispatch into `RoaringBitmapBackedBitmap.fromArray`.** New shape:

```java
static PersistentRoaringBitmap fromArray(@Nonnull int... array) {
    if (ArrayUtils.isEmpty(array)) {
        return new PersistentRoaringBitmap();
    } else if (array.length >= WRITER_DISPATCH_DENSITY && isDense(array)) {
        final RoaringBitmapWriter<PersistentRoaringBitmap> writer = buildWriter();
        writer.addMany(array);
        final PersistentRoaringBitmap result = writer.get();
        result.removeRunCompression(); // normalize full-container RunContainer → BitmapContainer
        return result;
    } else {
        final PersistentRoaringBitmap result = new PersistentRoaringBitmap();
        result.add(array);
        return result;
    }
}
```

- Relocate `WRITER_DISPATCH_DENSITY` (=4096) and `isDense(int[])` into the interface
  (`static` const + Java-9 `private static` method). Move the dispatch JavaDoc rationale onto
  `fromArray`.

**A2. Simplify `BaseBitmap(int...)` to delegate:**

```java
public BaseBitmap(@Nonnull int... recordIds) {
    this.roaringBitmap = RoaringBitmapBackedBitmap.fromArray(recordIds);
    this.memoizedCardinality = this.roaringBitmap.getCardinality();
}
```
Remove BaseBitmap's now-duplicated private `isDense` + `WRITER_DISPATCH_DENSITY`; leave a short
JavaDoc pointing at `fromArray`.

**A3. Representation note (must call out in review).** Folding `removeRunCompression()` into
`fromArray`'s dense branch changes the two unwrap-fallback callers' output in the *dense* case from
`RunContainer` → `BitmapContainer`. `equals()` is unaffected; only `hashCode()` of a completely-full
container changes — and it changes to *match* the incremental build, removing a latent
equal-but-different-hashCode divergence. Set operations (OR/AND) are representation-indifferent.
`TransactionalBitmap(Bitmap)` still yields a fully mutable, non-COW-shared bitmap on both branches —
mutability contract preserved.

**A4. Tests.**
- Extend `BaseBitmapTest`'s equivalence test to call `fromArray` directly across: length-1, tiny
  sparse, large sparse, large dense (crosses 4096/container), negative ids, unsorted input — assert
  content + `equals`/`hashCode` parity between writer and incremental paths.
- Existing `RoaringBitmapBackedBitmapTest.shouldRebuildNonRoaringBackedSingleRecordBitmapViaFromArray`
  must still pass (correctness unchanged; only cost drops).

**A5. Measurement gate (run via `run_in_background`, content-based completion — never PID-poll).**
1. async-profiler alloc on `attributeFiltering`: confirm the `ConstantMemoryContainerAppender` /
   `long[]` churn is gone from the single-record path.
2. `attributeFiltering` JMH throughput: expect recovery toward the ~5858 ops/s `dev` baseline.
3. Sanity: `bulkInsert`/write-churn benches unchanged (fromArray is not on the hot write path, but
   confirm no regression from the incremental branch).

**Expectation:** A alone should erase the regression (95.4% of the measured bytes are length-1
rebuilds, all now on the ~150 B incremental path).

---

## Phase B — two-phase OR *without* a phase-1 writer (CONDITIONAL follow-up)

Do **only if** the post-A profile still shows meaningful OR-unwrap CPU. Build it cheaper than the
original proposal — never materialize an intermediate phase-1 bitmap:

Implement `RoaringBitmapBackedBitmap.or(Bitmap[])`, mirroring the existing static `and(...)` helper;
call it from `OrFormula.computeInternal`. Partition inputs into RoaringBacked (cheap unwrap) vs the
rest (collect ids — use `size()==1 → getFirst()`, else iterate; **never** `getArray()`, which
allocates an `int[1]` per single, `SingleRecordBitmap.java:138`). Then:

- **K ≥ 2 multis:** `result = PersistentRoaringBitmap.or(multis)` (fresh `naive_or` accumulator),
  then `result.add(collectedSingles)` — no intermediate bitmap, no writer; `add` is unsigned-correct
  on unordered input and `copyIfShared`-guards the structurally-shared containers.
- **K == 1:** `clone()` the multi (cheap COW clone), then `add` the singles. **Do not** fold into the
  unwrapped multi in place — `getRoaringBitmap()` returns the index's *internal reference*
  (RoaringBitmapBackedBitmap.java:189-195); mutating it corrupts the index.
- **K == 0:** `fromArray(collectedSingles)` (cheap after A); `bitmapOfUnordered` for very large M.
- Preserve the `isEmpty() → EmptyBitmap.INSTANCE` and single-effective-input normalizations.
- Add an equivalence property test: random bitmap mixes, two-phase result `==` N-way `or` result.

Honest sizing: after A, the singles cost ~150 B each to unwrap + one `naivelazyor` fold each; B
removes those folds too, but that is a CPU micro-optimization in container merges, **not** the
92.7%-of-bytes problem. Measure before building.

---

## Phase C — optional hygiene

`fromArray`'s JavaDoc promises unsorted input "is handled correctly," yet the writer is configured so
unsorted input silently takes the slow per-value fallback. If any real caller passes large unsorted
arrays, add `.doPartialRadixSort()` to the writer; if all large callers pass sorted data, document
that instead. Low priority — A removes the hot single-record case entirely.

## Rejected (Fable D, endorsed)

- **Make `SingleRecordBitmap implement RoaringBitmapBackedBitmap`** (cached or lazy). Violates
  `getRoaringBitmap()`'s internal-reference contract; caching reintroduces the ~20 GB the flyweight
  removed. The static-helper/`fromArray` seam is correct.
- **ThreadLocal-pooled writer/buffer** — real reset/lifecycle complexity; A makes it moot.
- **Restructuring `ValueToRecordPrimitive`/InvertedIndex to emit range-batched bitmaps** — wide
  blast radius, unjustified once A removes the tax.

## Watch-outs when reading the post-fix profile

Fable's caveat 7b: after A, `naivelazyor` still promotes each distinct **result** chunk to an 8 KB
`long[1024]` `BitmapContainer` before `repairAfterLazy` downgrades it. That is an **expected** floor
of the union itself (present on `dev` too), *not* a new regression — do not chase it.

## Execution order

1. Phase A (A1–A4) + `advisor` review of the diff.
2. A5 gate (background JMH + alloc profile).
3. If regression healed → `/code-quality-pipeline` on the touched bitmap classes, done.
4. Only if OR-unwrap CPU still registers → Phase B, re-gate.
