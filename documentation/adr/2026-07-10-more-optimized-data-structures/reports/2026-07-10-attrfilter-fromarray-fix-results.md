# attributeFiltering fix — results (adaptive `fromArray`, 2026-07-10)

Phase A of `docs/plans/2026-07-10-attrfilter-fromarray-adaptive-plan.md`. Fix: move the
JMH-validated adaptive construction dispatch from `BaseBitmap(int...)` down into
`RoaringBitmapBackedBitmap.fromArray(int...)`; `BaseBitmap(int...)` now delegates. Single-record
(`SingleRecordBitmap`) unwraps stop rebuilding through an 8 KB constant-memory writer and take the
incremental `new PersistentRoaringBitmap().add()` path (~150 B).

## Throughput — DECISIVE

Identical config both rows: `ArtificialEntitiesThroughputBenchmark.attributeFiltering`,
`-t 24 -wi 2 -i 5 -w 12s -r 12s -f 1`, fresh isolated `-Djava.io.tmpdir` per run.

| build | ops/s |
|---|---|
| baseline #760 (SortIndex-fixed, **no** `fromArray` change) | 2237.6 ± 524.1 |
| **fixed (adaptive `fromArray`)** | **6538.96 ± 1091.8** |
| dev baseline (reference) | 5858.4 |

**2.92×** over the regressed baseline; **~12% past dev** — the regression is reversed, not just
healed (#760's other read-path wins net ahead once the 8 KB single-record tax is gone). Error bars
disjoint (5447 vs 2761).

## Allocation profile — mechanism confirmed

async-profiler `event=alloc`, same benchmark, `-t 24 -wi 1 -i 2`. 130,369 alloc samples.

| allocator | pre-fix | post-fix |
|---|---|---|
| `ConstantMemoryContainerAppender` (8 KB writer) | 95.8% | 6.77% (all **unrelated** `removeAll`/`retainAll`) |
| stacks through `fromArray` | 95.4% | 34.58% |
| ↳ `fromArray` **via writer path** | (essentially all) | **0.00%** |
| ↳ `fromArray` via incremental `add` | ~0% | 26.39% of `fromArray` (rest = the small bitmap/array-container objects) |

The 8 KB writer is now entirely off the `fromArray` hot path. The remaining `fromArray` allocation is
the many small per-single incremental bitmaps — ~50× cheaper each than the old writer rebuild.

## Tests
`BaseBitmapTest` + `RoaringBitmapBackedBitmapTest`: **120 tests, 0F/0E**, including 6 new direct
`fromArray` equivalence tests (length-1, tiny/large sparse, large dense crossover with equals+hashCode
parity locking the `removeRunCompression` normalization, negative ids, unsorted input).

## Phase B — NOT needed
Fable 5's follow-up (fold singles straight into the OR accumulator, skipping even the per-single small
bitmap) would further shrink the 34.58% residual, but throughput already exceeds dev. Left as an
optional future micro-optimization, not required to close the regression.

## Files changed
- `evita_engine/.../index/bitmap/RoaringBitmapBackedBitmap.java` — adaptive `fromArray` + relocated
  `WRITER_DISPATCH_DENSITY`/`isDense`.
- `evita_engine/.../index/bitmap/BaseBitmap.java` — `BaseBitmap(int...)` delegates.
- `evita_test/.../index/bitmap/BaseBitmapTest.java` — 6 new equivalence tests.
