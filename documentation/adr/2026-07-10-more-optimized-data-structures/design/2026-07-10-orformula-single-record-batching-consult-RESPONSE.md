# Response: critical analysis of the two-phase OR proposal

**Verdict up front.** The two-phase OR is correct and would work, but the consult document has the
priority inverted. The "systemic" size-adaptive `fromArray` fix is not the riskier of the two
candidates — the codebase has **already benchmarked, threshold-tuned, and equivalence-tested this
exact dispatch** in `BaseBitmap(int...)` (`WRITER_DISPATCH_DENSITY = 4096` + `isDense` probe +
`removeRunCompression` normalization); the fix is to push that existing dispatch down into
`fromArray` itself, healing all 18 formulas including the AND/NOT nodes that sit above the ORs in
the same `attributeFiltering` plans (see §3.A, including a census of all five `fromArray` call
sites). Lead with that, re-profile, and treat the two-phase OR as a follow-up that is only worth
its code if `or()`/unwrap CPU still registers — and if you do build it, build it **without the
phase-1 writer entirely** (see §3.B), which is cheaper than the proposal as written.

Three factual corrections to the document, verified against the vendored sources, materially change
the analysis:

---

## 1. Fact checks against the code

### 1.1 `fromArray` never radix-sorts — the phase-1 premise is weaker than stated

§2/§4 say `addMany` "optionally partial-radix-sorts by high 16 bits". *Optionally* is doing all the
work in that sentence: `doPartialRadixSort()` is an opt-in builder flag
(`RoaringBitmapWriter.Wizard`, `RoaringBitmapWriter.java:168`) and neither
`RoaringBitmapBackedBitmap.fromArray` nor `buildWriter()` sets it. With the current configuration,
unsorted input hits the appender's out-of-order fallback — a direct `underlying.add(value)` per
out-of-order key — so a phase-1 batched `addMany` over ids collected from arbitrary buckets in
arbitrary order would largely degenerate into per-value `add()` calls *plus* the 8 KB buffer that
then buys nothing. The library already ships the right tool for "many scattered values":
`PersistentRoaringBitmap.bitmapOfUnordered` (`PersistentRoaringBitmap.java:418`), which does enable
`doPartialRadixSort`. Answer to **Q2**: neither pre-sort nor plain `addMany`; if a phase-1 bitmap
is built at all, use `bitmapOfUnordered` — but §3.B argues it shouldn't be built at all.

Related caveat if you ever do pre-sort: `Arrays.sort` is **signed** order, roaring order is
**unsigned** (`add` javadoc, `PersistentRoaringBitmap.java:1614-1616`), and negative record ids are
a real thing in this codebase — the existing `RoaringBitmapBackedBitmap.and()` helper explicitly
splits negative bitmaps out, and `toSignedArray()` exists precisely because of this. Signed-sorted
input containing negatives lands in the out-of-order fallback again (correct, just slow).

### 1.2 `PersistentRoaringBitmap.or(varargs)` is `naive_or`, not a priority queue

**Q4** worries about losing the library's N-way/priority-queue optimizations. There are none to
lose on this path: `PersistentRoaringBitmap.or(...)` → `FastAggregation.or(...)` → **`naive_or`**
(`FastAggregation.java:787` → `:716`), a linear pairwise fold into a fresh accumulator via
`naivelazyor` + one `repairAfterLazy`. Fewer inputs = strictly fewer folds; batching is
neutral-to-better. Two sub-observations:

- `naivelazyor` promotes every accumulator container whose key also appears in the input to a full
  `BitmapContainer` (`toBitmapContainer()`, `PersistentRoaringBitmap.java:3646-3649`) — an 8 KB
  `long[1024]` per distinct result chunk, downgraded only in `repairAfterLazy`. So the union itself
  has an 8 KB-per-result-chunk floor regardless of what this proposal does. It "barely registers"
  in the profile only because the result spans few chunks — worth knowing when reading the
  post-fix profile, because after the per-single rebuilds are gone this becomes the next-largest
  `long[]` source on the OR path, and it is *expected*, not a new regression.
- The fold accumulator is **fresh** (`naive_or` line 717), and non-overlapping containers carried
  in by structural sharing have their `shared` flags raised. `add(int)` does `copyIfShared`
  (`PersistentRoaringBitmap.java:1623-1625`). This is what makes §3.B safe.

### 1.3 The actual cost asymmetry: ~8,192+ B vs ~150 B per single

The writer path allocates the `long[1024]` (8 KB) plus writer plumbing per built bitmap. The
trivial alternative for one value — `PersistentRoaringBitmap.bitmapOf(id)` — allocates a
`PersistentRoaringBitmap`, a small `RoaringArray`, and one `ArrayContainer` with
`DEFAULT_INIT_SIZE = 4` chars (`ArrayContainer.java:41,85-87`): roughly 150 bytes, ~50× less. For
the length-1 case there is no threshold to tune and no empirical question to answer; the writer is
categorically the wrong tool.

---

## 2. Assessment of the two-phase OR itself

**Correctness (Q1): sound.** Union is commutative, associative, idempotent; duplicates across
partitions collapse in phase 2. Unsigned-int semantics are uniform across `add`/`addMany`/`or`, so
unlike the `and()` helper no negative-id split is needed (that split exists only because `and`
uses the range-bounded `and(iterator, min, max)` overload). The edge cases listed in §4 are the
right ones; also preserve the final `isEmpty() → EmptyBitmap.INSTANCE` normalization already in
`OrFormula.computeInternal` (`OrFormula.java:186`).

One subtlety the document misses: with **K==1 multi-value input + M singles**, you must not fold
the singles into the unwrapped multi in place — `getRoaringBitmap()` documents that it returns the
**internal reference** ("modifications to it will affect this bitmap directly",
`RoaringBitmapBackedBitmap.java:189-195`), i.e. you'd be mutating the index's own bitmap. Either
run it through `naive_or` anyway (the fresh accumulator is a de-facto copy) or `clone()` it — the
COW fork makes `clone` cheap (container sharing + flag raise) and subsequent `add`s copy only the
touched containers.

**Detection nit (§4 last bullet):** partitioning by `!(instanceof RoaringBitmapBackedBitmap)` is
right, but don't collect ids via `getArray()` — `SingleRecordBitmap.getArray()` allocates a fresh
`int[1]` per input (`SingleRecordBitmap.java:138-140`). Use `size()==1 → getFirst()`, else iterate.

**Placement (Q5):** there is exact precedent for keeping this out of `OrFormula`: the static
`RoaringBitmapBackedBitmap.and(PersistentRoaringBitmap[])` helper
(`RoaringBitmapBackedBitmap.java:143`). A sibling `or(Bitmap[])` helper that partitions, batches
and unions keeps the algebra free of bitmap-type internals and is reusable by any other
union-shaped caller.

**Scope framing (§4):** "does not generalize to the other 17 formulas" is true of the *batching*,
but it undersells the consequence: `attributeFiltering` plans have AND/NOT nodes above the ORs, and
every one of them pays the same per-single 8 KB unwrap through the same
`getRoaringBitmap → fromArray` boundary. A fix that only touches OR leaves that tax in place. This
is the strongest argument for leading with the boundary fix.

---

## 3. Recommended plan (my solutions, ranked)

### A. Lead: adaptive `fromArray` — reuse the dispatch the codebase already validated

**Call-site census** (addresses the "other callers have different layout needs" concern):
`fromArray` has exactly **five** production call sites, all inside the bitmap package, and no
callers anywhere else in the repository:

| Call site | What reaches `fromArray` |
|---|---|
| `getRoaringBitmap` / `getRoaringBitmapClone` (`RoaringBitmapBackedBitmap.java:76,91`) | fallback for non-roaring-backed bitmaps only — `SingleRecordBitmap` / `ArrayBitmap` / `EmptyBitmap`; overwhelmingly tiny (this is the profiled hot path) |
| `BaseBitmap(int...)` (`BaseBitmap.java:95`) | **already density-gated**: calls `fromArray` only when `length ≥ 4096 && isDense(...)`; small/sparse arrays take an incremental `add(int...)` path instead |
| `BaseBitmap(Bitmap)` (`BaseBitmap.java:138`) | fallback for non-roaring-backed bitmaps only (same tiny population) |
| `TransactionalBitmap(Bitmap)` (`TransactionalBitmap.java:95`) | fallback for non-roaring-backed bitmaps only; result is mutated later — both build paths yield ordinary, fully mutable `PersistentRoaringBitmap`s with no COW-shared flags, so this is unaffected |

Crucially, `BaseBitmap(int...)` already contains a **JMH-validated version of exactly this
dispatch** (`BitmapConstructionBenchmark`, documented at `BaseBitmap.java:62-106`): the
constant-memory writer measured ≈2–3× faster with ≈⅓ the allocation on large **dense** arrays but
**6–7× slower on large sparse** ones, with the crossover at `WRITER_DISPATCH_DENSITY = 4096` ids
per 65,536-wide container — gated by an O(1) density probe (`isDense`) that reads only the array
ends and falls back to the always-correct incremental path on unsorted input. It also identified
and solved the one real representation divergence between the paths: the writer canonicalizes a
completely-full container to a `RunContainer` while incremental append leaves a `BitmapContainer`
(equal content, different `hashCode`), normalized via `removeRunCompression()` and locked in by an
equivalence test in `BaseBitmapTest`.

Two consequences:

1. **The "different layout needs" risk points the other way.** Below 4096 values per container,
   *both* paths produce identical `ArrayContainer` layouts (with `runCompress(false)` the writer
   never emits run containers below full, and array→bitmap promotion happens only above 4096
   cardinality), so tiny inputs cannot observe the change; and the callers feeding `fromArray`
   large **sparse** data today (a big `ArrayBitmap` through the unwrap helpers, or the two
   `(Bitmap)` constructors) are currently on the writer path that `BaseBitmap`'s own benchmark
   showed is 6–7× *slower* for them. The current unconditional-writer `fromArray` is the one
   mis-serving diverse callers; the adaptive version serves every caller at least as well.

2. **The fix is a code move, not new science:** push `BaseBitmap(int...)`'s dispatch
   (`length ≥ 4096 && isDense → writer + removeRunCompression`, else incremental `add(int...)`)
   down into `fromArray` itself, and let `BaseBitmap(int...)` delegate. Every caller — including
   the profiled unwrap boundary where singles then cost ~150 B instead of ≥8 KB — inherits the
   already-benchmarked, already-equivalence-tested behavior. Extend the `BaseBitmapTest`
   equivalence test to cover `fromArray` directly (tiny, sparse, dense, negative-id, unsorted
   inputs).

The profile says 95.4% of allocated bytes flow through *single-record* rebuilds; those all land on
the incremental path (`bitmapOf`-equivalent) under this dispatch, which should erase the
regression on its own. This is systemic (all 18 formulas), zero API change, and reuses a threshold
the project has already measured rather than inventing a new one.

**Not** the interface route: making `SingleRecordBitmap implement RoaringBitmapBackedBitmap` with
an on-demand build would violate the documented contract of `getRoaringBitmap()` (internal
reference; mutations visible) — a lazily-built throwaway can't honor it. The doc's Q7 prohibition
covers *caching* the bitmap; this is the additional reason the *non-caching* interface variant is
also wrong. The static-helper/`fromArray` boundary is the correct seam.

### B. Follow-up (only if post-A profile still shows OR): two-phase without the phase-1 writer

The proposal builds an intermediate phase-1 bitmap just to feed it into `or()`. Cheaper: never
build it.

- **K ≥ 2 multis:** `result = PersistentRoaringBitmap.or(multis)` (fresh accumulator), then
  `result.add(collectedSingles)` — no intermediate bitmap, no writer, no sort (`add` is
  unsigned-correct on unordered input, and `copyIfShared` guards the structurally-shared
  containers).
- **K == 1:** `clone()` the multi (cheap COW clone), then `add` the singles.
- **K == 0:** `fromArray(singles)` — efficient once A is in; for very large M,
  `bitmapOfUnordered(singles)`.

Implement as `RoaringBitmapBackedBitmap.or(Bitmap[])` mirroring the existing `and(...)` helper,
called from `OrFormula.computeInternal`. Add an equivalence property test (random bitmap mixes:
two-phase result == N-way `or` result).

Honest sizing: with A in place, N singles cost ~150 B each to unwrap plus one `naivelazyor` fold
each. B removes those folds and allocations too, but that is now a CPU micro-optimization measured
in container merges, not the 92.7%-of-bytes problem. Measure before building.

### C. Optional hygiene: enable `doPartialRadixSort` on the writer path

`fromArray`'s javadoc already admits unsorted input is expected ("unsorted input is also handled
correctly"), yet the writer is configured so that unsorted input silently takes the slow
per-value fallback. If any real callers pass unsorted arrays above the tiny threshold, adding
`.doPartialRadixSort()` to `fromArray`/`buildWriter` is a one-line win; if all large callers pass
sorted data, document that instead.

### D. Rejected

- **ThreadLocal pooled writer/buffer** — the 8 KB buffer is writer-internal and doesn't escape, so
  pooling is technically feasible, but this benchmark already exposed ThreadLocal overhead
  elsewhere, reset/lifecycle logic is real complexity, and A makes the whole question moot.
- **Restructuring `ValueToRecordPrimitive`/InvertedIndex to emit range-batched bitmaps** — attacks
  the "why so many singleton unions per query" question at the source, but it's a wide-blast-radius
  index API change that is unjustified once A removes the tax.

---

## 4. Direct answers to §6

1. **Correctness:** equivalent; watch only (a) the K==1 in-place-mutation trap (§2), (b) keep the
   `EmptyBitmap` normalization, (c) don't `Arrays.sort` if negatives are possible (signed vs
   unsigned order).
2. **Ordering/perf:** neither pre-sort nor rely on `addMany` — the current writer config never
   radix-sorts (opt-in flag, not set). Use `bitmapOfUnordered` if you build phase 1 at all;
   better, use §3.B and skip the intermediate bitmap.
3. **Representation:** yes, there is a cheaper way — fold the collected ints into the `or()`
   accumulator (or a COW clone) via `add(int...)`; no writer, no intermediate bitmap.
4. **Library heuristics:** nothing lost — the varargs `or` is `naive_or`, a linear fold; fewer
   inputs is strictly fewer folds. (No priority queue on this path.)
5. **Placement:** static helper next to the existing `RoaringBitmapBackedBitmap.and(...)`;
   `OrFormula` stays internals-free.
6. **Priority:** invert the document's framing — the boundary fix (A) is the surgical one *and*
   the systemic one; the len==1 case needs no tuning and is 95.4% of the measured bytes. Ship A,
   re-run `attributeFiltering` JMH (expect recovery toward the ~5858 ops/s dev baseline), then
   decide on B from the new profile.
7. **Missing:** (a) the two verified misconceptions above (no radix sort; no priority queue);
   (b) the `naivelazyor` 8 KB-per-result-chunk floor that will dominate the *post-fix* OR profile
   and should not be mistaken for a new regression; (c) the AND/NOT nodes above the ORs in the
   same query plans pay the identical tax, which the OR-only fix cannot recover.
