# async-profiler: attributeFiltering hotspots (2026-07-10, post SortIndex fix)

Tool: async-profiler 3.0 (itimer for CPU — perf_events blocked in this env; alloc via native). JMH
`-wi 2 -i 4 -w 12s -r 12s -f 1`, read-only session, fixed engine (anti-stale-jar checked).
Score: **2083 ± 201 ops/s** (consistent with the 2238 re-measure). Collapsed profiles:
`scratchpad/asyncprof/{cpu/...collapsed-itimer.csv, alloc/...collapsed-alloc.csv}` (copied to
`docs/reports/asyncprof/`).

## Headline: the JFR "ThreadLocal tax" was a sampling artifact. The real bottleneck is RoaringBitmap OR allocation.

The earlier JFR run put `ThreadLocal.get` (from `TransactionalBitmap.getRoaringBitmap`) at ~21% of CPU.
The itimer + alloc profiles refute that:
- `getTransactionalMemoryLayerIfExists` chain = **0.9% CPU** (JFR over-attributed it — execution-sampling
  bias). The reviewer's proposed `OrFormula` ThreadLocal hoist would buy ~nothing. **Do not pursue it.**

## CPU (itimer, 51,869 samples) — top self-frames
| % | frame |
|---|---|
| 12.45 | `roaringbitmap.Util.fillArray` (fills a `long[1024]` BitmapContainer from an array container) |
| 11.27 | `roaringbitmap.ConstantMemoryContainerAppender.<init>` |
| 9.38 | `SortedRecordsProvider.resolvePositions` (the sort resolution) |
| 6.07 | `roaringbitmap.BitmapContainer.computeCardinality` |
| 4.82 | `G1ParScanThreadState::trim_queue` (GC — allocation pressure) |
| 3.73 | `BitmapBatchIterator.next` |
| 3.58 | `ConstantMemoryContainerAppender.add` |
| 2.65 | `Container.lazyIOR` · 2.49 `Util.hybridUnsignedBinarySearch` · 2.30 `RoaringBitmapBackedBitmap.getRoaringBitmap` |

Chain totals: **OrFormula 51% of CPU**, resolvePositions 25.5%, fillArray/loadData 12.5%.

## Allocation (native alloc profile) — DECISIVE
- **92.7% of all bytes = `long[]`.**
- **95.8% of bytes flow through `ConstantMemoryContainerAppender`; 96.9% under `OrFormula`.**
- All other named types (ArrayContainer, BitmapContainer, RoaringArray, SingleRecordBitmap,
  ValueToRecordBitmap, …) are <0.5% each.

## Interpretation
`OrFormula.computeInternal` (`PersistentRoaringBitmap.or(theBitmaps)`, OrFormula.java:184) unions many
attribute-value bitmaps. The fork's OR path builds full `long[1024]` (8 KB) `BitmapContainer` backings via
`ConstantMemoryContainerAppender` — so the union allocates one dense bitmap container per (roughly) OR step,
driving **~93% of allocation** and, with the resulting GC pressure + `fillArray`/`computeCardinality`,
roughly a third-plus of CPU. This is almost certainly THE `attributeFiltering` regression driver: it's a
#760 fork addition (`ConstantMemoryContainerAppender`), not present as such on `dev`.

## Next steps (in order)
1. **Confirm dev-vs-760**: profile `attributeFiltering` on `dev` (worktree/checkout, own benchmarks.jar) and
   diff — prove `ConstantMemoryContainerAppender`/`long[]` churn is #760-introduced before touching the fork.
2. Target the OR path: does `PersistentRoaringBitmap.or` need to eagerly materialize a dense
   `long[1024]` container per union step, or can it use array-container / in-place / lazy-OR accumulation
   that dev used? The `ConstantMemoryContainerAppender.<init>` at 11% CPU + 8 KB/alloc is the lever.
3. The `ThreadLocal` "tax" and the eager `fillArray` are downstream of this allocation pattern — fixing the
   container-materialization strategy should shrink several frames at once.

(The `SortIndex` sort cache is done; `resolvePositions` at ~9-25% is the already-optimized sparse-probe sort
path and is not the primary target here.)
