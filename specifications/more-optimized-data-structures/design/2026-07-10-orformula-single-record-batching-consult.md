# Consult (for Fable 5): two-phase OR to avoid per-single-record bitmap rebuild

**Purpose.** Get an independent feasibility read on a proposed two-phase `OrFormula` union. This document is
self-contained — it assumes no prior session context. Please assess correctness, feasibility, performance,
and whether you'd do it differently. Concrete questions are in §6.

---

## 1. Background / the problem being solved

evitaDB branch `#760` regressed the `attributeFiltering` query benchmark to ~2.6× slower than the `dev`
baseline (JMH throughput: `#760` ≈ 2238 ops/s vs `dev` ≈ 5858 ops/s; read-only session).

An async-profiler **allocation** profile of `#760`'s `attributeFiltering` shows the cost is almost entirely
one thing:

- **92.7% of all allocated bytes are `long[]`**, and **95.4% of bytes** flow through this exact chain:
  `OrFormula.getRoaringBitmaps()` → `RoaringBitmapBackedBitmap.getRoaringBitmap(input)` →
  `RoaringBitmapBackedBitmap.fromArray(input.getArray())` → a `RoaringBitmapWriter.constantMemory()` writer,
  whose appender allocates a **`long[1024]` (8 KB) buffer** per built bitmap.
- `PersistentRoaringBitmap.or` itself barely registers. **The OR algorithm is not the regression.**

**Root cause (a write-optimization that taxes reads).** `#760` introduced an InvertedIndex bucket flyweight:
a value that maps to a *single* record is stored as `io.evitadb.index.bitmap.SingleRecordBitmap`
(`ValueToRecordPrimitive.getRecordIds()` returns `new SingleRecordBitmap(recordId)`). This saved ~20 GB of
*write*-time churn. But `SingleRecordBitmap implements Bitmap` only — it is **not** a
`RoaringBitmapBackedBitmap`. So when `OrFormula` unions many single-record attribute values, each
`SingleRecordBitmap` takes the `else` branch of `getRoaringBitmap` and is **rebuilt into a full
RoaringBitmap through an 8 KB constant-memory writer — to hold one record**.

On `dev` the same InvertedIndex bucket returned a `TransactionalBitmap` (which **is**
`RoaringBitmapBackedBitmap`), so the unwrap was a free reference return — no rebuild. `SingleRecordBitmap` /
`ValueToRecordPrimitive` do not exist on `dev`.

Only `TransactionalBitmap` and `BaseBitmap` implement `RoaringBitmapBackedBitmap` (the cheap path);
`SingleRecordBitmap`, `ArrayBitmap`, `EmptyBitmap` do not. **18 formulas** share this same
`RoaringBitmapBackedBitmap.getRoaringBitmap` unwrap boundary (`AndFormula`, `NotFormula`, `FacetGroup*`, the
price formulas, etc.), so the tax is systemic; `attributeFiltering` is just the OR-dominated case that
exposed it.

## 2. Current code (the hot path)

`io.evitadb.core.query.algebra.base.OrFormula`:

```java
protected Bitmap computeInternal() {
    final PersistentRoaringBitmap[] theBitmaps = getRoaringBitmaps();          // <-- per-input rebuild here
    if (theBitmaps.length == 0)      return EmptyBitmap.INSTANCE;
    else if (theBitmaps.length == 1) return new BaseBitmap(theBitmaps[0]);
    else                             return new BaseBitmap(PersistentRoaringBitmap.or(theBitmaps));
}

private PersistentRoaringBitmap[] getRoaringBitmaps() {
    // this.bitmaps != null branch shown; the getInnerFormulas() branch is analogous
    final PersistentRoaringBitmap[] result = new PersistentRoaringBitmap[this.bitmaps.length];
    for (int i = 0; i < this.bitmaps.length; i++) {
        result[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(this.bitmaps[i]);   // fromArray() for singles
    }
    return result;
}
```

`io.evitadb.index.bitmap.RoaringBitmapBackedBitmap`:

```java
static PersistentRoaringBitmap fromArray(int... array) {
    if (isEmpty(array)) return new PersistentRoaringBitmap();
    final RoaringBitmapWriter<PersistentRoaringBitmap> writer =
        RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();  // 8 KB buffer, ALWAYS
    writer.addMany(array);                                                        // handles unsorted input
    return writer.get();
}
static PersistentRoaringBitmap getRoaringBitmap(Bitmap bitmap) {
    if (bitmap instanceof RoaringBitmapBackedBitmap) return ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap();
    else return fromArray(bitmap.getArray());
}
```

`ConstantMemoryContainerAppender` (the writer's appender) keeps a reused `long[1024]` buffer and, per 16-bit
chunk, materializes the best-fitting container (downgrading to array/run when sparse). It expects values in
**ascending** order; keys that drop below the current mark fall back to a direct `underlying.add(value)`.
`addMany` optionally partial-radix-sorts by high 16 bits first.

## 3. The proposal under consultation — two-phase OR

Replace the "rebuild every input, then N-way OR" with:

1. **Phase 1 — batch the primitives.** Partition `OrFormula`'s inputs into (a) inputs that are already
   `RoaringBitmapBackedBitmap` (cheap unwrap, no rebuild) and (b) the rest (single-record / small
   non-RoaringBacked bitmaps). Feed **all** of (b)'s record ids into **one** `RoaringBitmapWriter`, producing
   **one** `PersistentRoaringBitmap` — a single 8 KB buffer amortized over every primitive, instead of one
   8 KB buffer per primitive.
2. **Phase 2 — standard OR.** `PersistentRoaringBitmap.or(phase1Result, unwrapped_a_1, unwrapped_a_2, ...)` —
   the ordinary library union over the batched-primitives bitmap and the already-materialized multi-value
   bitmaps.

Net effect: `N` per-single 8 KB rebuilds → **1** batched build + the same final union.

## 4. Our current feasibility analysis

- **Correctness.** OR is commutative, associative, and idempotent, so grouping the primitives first is
  equivalent to unioning them individually; duplicates between a primitive and a multi-value bitmap are
  handled by the phase-2 union. Edge cases to preserve: zero inputs → `EmptyBitmap`; a single effective input
  → `new BaseBitmap(theBitmap)`; all-primitive (phase 2 degenerates to phase-1 result); no-primitive (phase 1
  is skipped).
- **Allocation.** One writer (one 8 KB buffer) for all primitives instead of `N`. Also avoids building `N`
  intermediate `PersistentRoaringBitmap` objects purely to be OR'd and discarded.
- **The ordering wrinkle (main open risk).** The record ids of the collected primitives come from different
  InvertedIndex buckets in **arbitrary** order. The constant-memory appender prefers ascending input and
  falls back to a slower direct-add on out-of-order keys (`addMany` can partial-radix-sort by high 16 bits).
  So phase 1 likely wants to collect ids into one `int[]` and either pre-sort or rely on `addMany`'s partial
  sort. Whether unsorted `addMany` is fast enough, or an explicit sort is needed, is an empirical question.
- **Scope.** This is OR-specific: it exploits that OR = set union. `AndFormula`/`NotFormula` cannot batch the
  same way (intersection/difference of single-record sets is different), so this does **not** generalize to
  the other 17 formulas that share the unwrap boundary.
- **Detection.** Prefer partitioning by `!(input instanceof RoaringBitmapBackedBitmap)` (general — also
  catches `ArrayBitmap`) rather than `instanceof SingleRecordBitmap` (couples the algebra to one flyweight).

## 5. Relationship to the other candidate fix (kept separate on purpose)

The independently-considered fix is **size-adaptive `fromArray`**: don't use the 8 KB `constant-memory`
writer for tiny/sparse arrays (use a plain `new PersistentRoaringBitmap()` + `add()`, ~tens of bytes),
keeping `constant-memory` only above a JMH-tuned size threshold. That is **systemic** (fixes all 18
formulas' unwrap) but touches a hot shared boundary and needs threshold tuning.

The two-phase OR (this doc) is **surgical** (OR only) and reduces the *count* of conversions (N → 1); the
adaptive `fromArray` reduces the *cost per* conversion. They compose. Note a batched phase-1 build is large
enough that `constant-memory` is actually appropriate for it — so the two-phase can stand alone for OR
without the threshold change. We are keeping them as separate, composable changes.

## 6. Questions for Fable 5

1. **Correctness:** any case where two-phase OR is not equivalent to the N-way OR (ordering, duplicates,
   negative/`Integer.MIN..MAX` ids, empty/singleton inputs, run-optimized inputs)?
2. **Ordering/perf:** for an unsorted union of many single ids, is one `addMany` on the `constant-memory`
   writer (with its partial radix sort) efficient, or should phase 1 explicitly sort the collected `int[]`
   first? Any better appender/writer choice for "many scattered singletons"?
3. **Representation:** is a `PersistentRoaringBitmap` the right phase-1 target, or is there a cheaper way to
   fold `M` singletons into the union — e.g. adding the ints directly onto one of the multi-value results, or
   building array containers directly — without the writer at all?
4. **Library heuristics:** does replacing an N-way `RoaringBitmap.or` with a `(1 batched + K multi)`-way OR
   lose any of the library's internal N-way/priority-queue OR optimizations, or is it neutral-to-better?
5. **Placement:** OR-specific logic inside `OrFormula`, or a small shared helper "unwrap inputs, batching the
   non-RoaringBacked ones"? Any maintainability concerns coupling the query algebra to bitmap-type internals?
6. **Priority:** given the tax is systemic across 18 formulas but the two-phase only helps OR, would you lead
   with the systemic adaptive-`fromArray` fix, the surgical two-phase, or both? Trade-offs?
7. **Anything we're missing** — a cleaner structural fix that removes the read tax while preserving the
   flyweight's at-rest memory win (do NOT propose making `SingleRecordBitmap` carry a RoaringBitmap; that
   reintroduces the ~20 GB the flyweight removed).

## 7. Appendix — key references

- `evita_engine/.../core/query/algebra/base/OrFormula.java` — `computeInternal`, `getRoaringBitmaps`.
- `evita_engine/.../index/bitmap/RoaringBitmapBackedBitmap.java` — `getRoaringBitmap`, `fromArray` (uses
  `.constantMemory()` unconditionally).
- `evita_engine/.../index/bitmap/SingleRecordBitmap.java` — `implements Bitmap` (NOT
  `RoaringBitmapBackedBitmap`); `getArray()` returns `{recordId}`.
- `evita_engine/.../index/invertedIndex/ValueToRecordPrimitive.java` — `getRecordIds()` returns
  `new SingleRecordBitmap(recordId)`.
- `evita_roaring_bitmap/.../roaringbitmap/ConstantMemoryContainerAppender.java` — the 8 KB reused buffer;
  ascending-order expectation; `addMany` partial radix sort.
- Profiling evidence: `docs/reports/asyncprof-attrfilter-2026-07-10.md` and
  `docs/reports/asyncprof/attrfilter-alloc.collapsed.csv`.
- Confirmation test (green): `RoaringBitmapBackedBitmapTest.shouldRebuildNonRoaringBackedSingleRecordBitmapViaFromArray`
  / `shouldUnwrapRoaringBackedBitmapTypesWithoutRebuild`.
