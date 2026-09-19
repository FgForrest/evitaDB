# Where a facet query's CPU actually goes

A JFR execution-sample profile of the **uninstrumented** query workload on the production retail catalog,
recorded for issues #1541-#1544. The companion measurement is `census/REPORT.md`; this file answers the one
question that report could not: how much of a query is roaring arithmetic at all, measured without the census
instrumentation in the way.

## How it was recorded

`jdk.jfr.Recording` started and stopped programmatically inside the harness, so **the catalog load is outside
the window** and only query replays are inside it. Settings: the stock `profile` configuration, with
`jdk.ExecutionSample` and `jdk.NativeMethodSample` re-enabled at a **1 ms** requested period because the stock
10 ms period yields only a few thousand samples over one pass.

| | |
|---|---|
| window | 88.802 s, three consecutive replays of the same 2,600 queries |
| instrumentation active | none - `MODE_OFF`, OR counters off, dump sampling off |
| `jdk.ExecutionSample` events | 67,779 |
| effective sampling interval | 1.31 ms (763 samples/s; JFR throttles below the 1 ms request) |
| samples on the query thread `main` | 67,769 (99.99%) |
| thread states sampled | `STATE_RUNNABLE`, 100% |
| GC pause time in the window | 0.059 s over 16 pauses = 0.07% |

Queries run on the calling thread: 99.99% of samples land on `main`, with ten stray samples on JFR's own
periodic task thread and on engine service threads. Every table below therefore describes one thread's CPU.

`jdk.ExecutionSample` samples Java threads executing Java code. Time inside the JVM itself - GC worker threads,
JIT compilation, safepoint bookkeeping - is not represented in these tables; the GC row above is measured
separately from `jdk.GCPhasePause` and is negligible.

## 1. Self samples by package

| package | self samples | share |
|---|---|---|
| `io.evitadb.roaringbitmap.*` | 28,009 | 41.32% |
| `java.util.*` | 20,552 | 30.32% |
| `io.evitadb.core.query.*` | 12,029 | 17.75% |
| other (third-party libraries) | 3,230 | 4.77% |
| JDK (other) | 1,695 | 2.50% |
| `io.evitadb.index.*` | 1,400 | 2.07% |
| `io.evitadb.*` (other) | 864 | 1.27% |
| **total** | **67,779** | **100.00%** |

`other (third-party libraries)` is dominated by `net.openhft.hashing` (xxHash, used for formula cache keys)
and `com.carrotsearch.hppc` primitive collections.

## 2. What the roaring 41.32% is made of

| group | self samples | share of all query CPU | share of roaring |
|---|---|---|---|
| lazy-OR machinery | 17,878 | 26.38% | 63.83% |
| hashing / equality | 3,032 | 4.47% | 10.83% |
| binary search | 2,036 | 3.00% | 7.27% |
| intersection / difference | 1,899 | 2.80% | 6.78% |
| mutation / build | 1,368 | 2.02% | 4.88% |
| iteration | 1,043 | 1.54% | 3.72% |
| other roaring | 753 | 1.11% | 2.69% |
| **all roaring** | **28,009** | **41.32%** | **100.00%** |

The grouping is by method name; `other roaring` is `RoaringArray.defrost`/`first`/`last`, the generic
`Container.lazyIOR` dispatcher itself, capacity management and batch iteration.

## 3. Top 40 methods by SELF samples

| # | self | share | method |
|---|---|---|---|
| 1 | 8,129 | 11.99% | `io.evitadb.roaringbitmap.BitmapContainer.ilazyor` |
| 2 | 4,984 | 7.35% | `java.util.HashMap.getNode` |
| 3 | 3,403 | 5.02% | `io.evitadb.roaringbitmap.PersistentRoaringBitmap.naivelazyor` |
| 4 | 3,358 | 4.95% | `io.evitadb.roaringbitmap.Util.fillArray` |
| 5 | 3,273 | 4.83% | `io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer$FacetGroupStatisticsCollector.lambda$finisher$12` |
| 6 | 2,935 | 4.33% | `java.util.HashMap$HashIterator.nextNode` |
| 7 | 2,650 | 3.91% | `io.evitadb.core.query.algebra.base.ConstantFormula.getEstimatedCostInternal` |
| 8 | 2,621 | 3.87% | `io.evitadb.roaringbitmap.ArrayContainer.hashCode` |
| 9 | 2,452 | 3.62% | `java.util.Comparator.lambda$comparingInt$7b0bb60$1` |
| 10 | 1,810 | 2.67% | `io.evitadb.roaringbitmap.Util.hybridUnsignedBinarySearch` |
| 11 | 1,326 | 1.96% | `io.evitadb.roaringbitmap.BitmapContainer.loadData` |
| 12 | 1,192 | 1.76% | `java.util.TimSort.mergeHi` |
| 13 | 988 | 1.46% | `java.util.TimSort.mergeLo` |
| 14 | 919 | 1.36% | `java.util.HashMap.putVal` |
| 15 | 825 | 1.22% | `java.util.Arrays.binarySearch0` |
| 16 | 800 | 1.18% | `java.util.DualPivotQuicksort.sort` |
| 17 | 782 | 1.15% | `com.carrotsearch.hppc.IntHashSet.add` |
| 18 | 744 | 1.10% | `io.evitadb.roaringbitmap.Util.unsignedLocalIntersect2by2` |
| 19 | 713 | 1.05% | `net.openhft.hashing.CompactLatin1CharSequenceAccess.getLong` |
| 20 | 644 | 0.95% | `java.lang.ThreadLocal$ThreadLocalMap.getEntry` |
| 21 | 617 | 0.91% | `io.evitadb.core.query.extraResult.translator.reference.producer.AbstractFacetFormulaGenerator$MutableFormulaFinderAndReplacer.visit` |
| 22 | 613 | 0.90% | `io.evitadb.index.price.AbstractPriceListAndCurrencyPriceIndex.forEachPriceRecord` |
| 23 | 607 | 0.90% | `io.evitadb.roaringbitmap.PersistentRoaringBitmap.getLongCardinality` |
| 24 | 597 | 0.88% | `java.util.TimSort.mergeCollapse` |
| 25 | 591 | 0.87% | `io.evitadb.roaringbitmap.BitmapContainer.toArrayContainer` |
| 26 | 581 | 0.86% | `net.openhft.hashing.XXH3.XXH3_64bits_internal` |
| 27 | 581 | 0.86% | `io.evitadb.core.query.algebra.facet.FacetGroupOrFormula.includeAdditionalHash` |
| 28 | 552 | 0.81% | `java.util.DualPivotQuicksort.mixedInsertionSort` |
| 29 | 521 | 0.77% | `io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer$GroupAccumulator.addStatistics` |
| 30 | 514 | 0.76% | `io.evitadb.roaringbitmap.PersistentRoaringBitmap$RoaringIntIterator.next` |
| 31 | 459 | 0.68% | `java.util.HashMap.resize` |
| 32 | 439 | 0.65% | `java.util.HashMap.compute` |
| 33 | 397 | 0.59% | `io.evitadb.roaringbitmap.BitmapContainer.computeCardinality` |
| 34 | 393 | 0.58% | `io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists` |
| 35 | 392 | 0.58% | `com.carrotsearch.hppc.IntHashSet.rehash` |
| 36 | 391 | 0.58% | `java.util.LinkedHashMap$LinkedHashIterator.nextNode` |
| 37 | 383 | 0.57% | `io.evitadb.roaringbitmap.BitmapContainer.and` |
| 38 | 370 | 0.55% | `io.evitadb.core.query.algebra.AbstractFormula.initFields` |
| 39 | 360 | 0.53% | `java.util.ArrayList.addAll` |
| 40 | 355 | 0.52% | `io.evitadb.roaringbitmap.PersistentRoaringBitmap.add` |

## 4. Top 25 methods by TOTAL (inclusive) samples

A frame is counted once per sample whose stack contains it, so recursion does not inflate the count.

| # | inclusive | share | method |
|---|---|---|---|
| 1 | 67,176 | 99.11% | `io.evitadb.core.collection.EntityCollection.getEntities` |
| 2 | 67,130 | 99.04% | `io.evitadb.core.session.EvitaSession.query` |
| 3 | 64,336 | 94.92% | `jdk.internal.reflect.DirectMethodHandleAccessor.invoke` |
| 4 | 64,325 | 94.90% | `java.lang.reflect.Method.invoke` |
| 5 | 64,018 | 94.45% | `io.evitadb.core.session.EvitaSessionProxy.invokeMethodSafely` |
| 6 | 64,007 | 94.43% | `io.evitadb.core.session.EvitaSessionProxy.lambda$executeMethodWithTracingAndMetrics$2` |
| 7 | 63,928 | 94.32% | `io.evitadb.core.session.EvitaSessionProxy.executeMethodWithTracingAndMetrics` |
| 8 | 63,922 | 94.31% | `io.evitadb.core.session.EvitaSessionProxy.lambda$executeWithinTransactionContext$3` |
| 9 | 63,899 | 94.28% | `io.evitadb.core.transaction.Transaction.executeInTransactionIfProvided` |
| 10 | 63,884 | 94.25% | `io.evitadb.core.session.EvitaSessionProxy.executeWithinTransactionContext` |
| 11 | 63,877 | 94.24% | `io.evitadb.core.session.EvitaSessionProxy.executeDelegateMethod` |
| 12 | 63,870 | 94.23% | `io.evitadb.spike.RoaringContainerCensus.profile` |
| 13 | 63,870 | 94.23% | `io.evitadb.spike.RoaringContainerCensus.replay` |
| 14 | 63,864 | 94.22% | `io.evitadb.core.session.EvitaSessionProxy.invoke` |
| 15 | 63,858 | 94.22% | `io.evitadb.core.session.$Proxy26.query` |
| 16 | 63,854 | 94.21% | `io.evitadb.spike.RoaringContainerCensus.run` |
| 17 | 63,845 | 94.20% | `io.evitadb.spike.RoaringContainerCensus.main` |
| 18 | 63,441 | 93.60% | `io.evitadb.core.traffic.TrafficRecordingEngine.recordQuery` |
| 19 | 63,415 | 93.56% | `io.evitadb.core.query.QueryPlan.execute` |
| 20 | 63,415 | 93.56% | `io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.executeWithinBlockInternal` |
| 21 | 63,414 | 93.56% | `io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.executeWithinBlockIfParentContextAvailable` |
| 22 | 52,787 | 77.88% | `io.evitadb.core.query.QueryPlan.fabricateExtraResults` |
| 23 | 52,783 | 77.88% | `io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.fabricate` |
| 24 | 52,783 | 77.88% | `io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.doFabricate` |
| 25 | 48,073 | 70.93% | `io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer$FacetGroupStatisticsCollector.lambda$finisher$12` |

## 5. Who calls what

Aggregated from the same recording, no new run. For each method, every sample in which it is the **top** frame is
attributed to the first `io.evitadb.core.*` or `io.evitadb.index.*` frame found by walking up the stack, up to
eight frames. The "frames walked" column says how far that was, so a one-line chain is distinguishable from a deep
one. This recording holds 67,779 execution samples.

### `ArrayContainer.hashCode` — 2,621 self samples, 3.87%

| first engine frame | samples | share | frames walked |
|---|---|---|---|
| `io.evitadb.index.facet.FacetIdIndex.hashCode` | 2,621 | 100.00% | 3 |

One caller, no tail. The full chain, identical on every one of those samples, is:

```
Collectors.lambda$groupingBy$53
  HashMap.computeIfAbsent
    HashMap.hash
      io.evitadb.index.facet.FacetGroupIndex.hashCode        5.42% inclusive
        io.evitadb.index.map.TransactionalMap.hashCode       5.42% inclusive
          HashMap$Node.hashCode  ->  Objects.hashCode
            io.evitadb.index.facet.FacetIdIndex.hashCode
              io.evitadb.index.bitmap.TransactionalBitmap.hashCode   4.55% inclusive
                io.evitadb.roaringbitmap.RoaringArray.hashCode       4.55% inclusive
                  io.evitadb.roaringbitmap.ArrayContainer.hashCode   3.87% self
```

**A `FacetGroupIndex` is being used as a `HashMap` key by a `groupingBy` collector**, and hashing that key walks
the whole index: its `TransactionalMap`, every `FacetIdIndex` in it, every `TransactionalBitmap` in those, and
every container of every one of those bitmaps. The `HashMap$HashIterator.nextNode` samples attributed to
`TransactionalMap.hashCode` below are the same walk seen from the map's side.

### `HashMap.getNode` — 4,984 self samples, 7.35%

| first engine frame | samples | share |
|---|---|---|
| `ReferenceSummaryProducer$FacetGroupStatisticsCollector.lambda$finisher$12` | 3,925 | 78.75% |
| `QueryPlanningContext.isFacetGroupRelationType` | 817 | 16.39% |
| `ReferenceSummaryProducer$FacetGroupStatisticsCollector.isRequested` | 100 | 2.01% |
| `ReferenceSummaryProducer$FacetGroupStatisticsCollector.getGroupEntity` | 46 | 0.92% |
| `io.evitadb.index.map.TransactionalMap.get` | 27 | 0.54% |
| `io.evitadb.index.map.PersistentTransactionalMap.get` | 19 | 0.38% |
| `FilterByVisitor.isConjunctiveFormula` | 11 | 0.22% |
| `ImpactFormulaGenerator.generateFormula` | 10 | 0.20% |

Immediate callers: `LinkedHashMap.get` 79.59%, `HashMap.get` 20.02%, `HashMap.containsKey` 0.38%. Frames walked:
two on 83.1% of samples, three on 16.8%.

### `HashMap$HashIterator.nextNode` — 2,935 self samples, 4.33%

| first engine frame | samples | share |
|---|---|---|
| `ReferenceSummaryProducer$FacetGroupStatisticsCollector.lambda$accumulator$7` | 2,463 | 83.92% |
| `io.evitadb.index.map.TransactionalMap.hashCode` | 458 | 15.60% |
| `FacetSummaryAdapter.createResult` | 5 | 0.17% |
| `…FacetGroupStatisticsCollector.getFacetEntitiesIndexedByReferenceName` | 4 | 0.14% |
| `ReferenceSummaryProducer.doFabricate` | 3 | 0.10% |
| `ExtraResultPlanningVisitor.getUserFilteringFormula` | 1 | 0.03% |

Immediate callers: `HashMap$ValueIterator.next` 83.99%, `HashMap$EntryIterator.next` 15.95%.

### `PersistentRoaringBitmap.naivelazyor` — 3,403 self samples, 5.02%

| first engine frame | samples | share | frames walked |
|---|---|---|---|
| `io.evitadb.core.query.algebra.base.OrFormula.computeInternal` | 3,355 | 98.59% | 4 |
| `AbstractFacetFormulaGenerator.getBaseEntityIds` | 48 | 1.41% | 4 |

The immediate caller is `FastAggregation.naive_or` on 100% of samples. `OrFormula.computeInternal` is on **29.80%**
of all stacks inclusively, so essentially every multi-way union in this workload is one `OrFormula`.

### What this means for the decision record

**A better-distributed `ArrayContainer.hashCode` would not recover this 3.87%.** The cost here is not collision
probing, it is the deep structural walk: hashing one `FacetGroupIndex` key descends through a map, through every
facet's index, through every bitmap, to every container. The measurement shows *where the time is spent*; it does
not measure collision rates, and no collision counter was collected. What can be said is that fixing only the hash
function's distribution would leave the walk exactly as expensive as it is now, because the walk happens before
any bucket is chosen. The cheaper fix is not to use a deeply-hashing index object as a `HashMap` key — key the
`groupingBy` on the group's primary key instead.

The shipped `ArrayContainer.hashCode` folding only the last seven values is still a latent correctness-adjacent
hazard on that same path, and the two interact badly: the code pays for a full walk of every container and then
throws away all but the tail of each one. Both belong in the same fix, but the walk is the part worth money.

**The facet summary owns the rest.** The collector's finisher lambda is on 70.93% of stacks and its accumulator
lambda on 6.68%, and between them they are the first engine frame for 78.75% of `HashMap.getNode` and 83.92% of
`HashMap$HashIterator.nextNode` samples. Together with `OrFormula.computeInternal` at 29.80% inclusive, the picture
is consistent: building the facet summary is the workload, the unions underneath it are its arithmetic, and the
map traffic around it is a cost of the same order as the arithmetic.

## 6. Reading the profile

**Roaring is 41.32% of query CPU, not 12%.** The census report's 12% came from a clock around `Container`'s twelve
generic dispatchers, which is the right unit for an operation mix and the wrong one for a cost share: it excludes
`PersistentRoaringBitmap.naivelazyor` (5.02% self), `Util.fillArray` (4.95%), `ArrayContainer.hashCode` (3.87%),
`Util.hybridUnsignedBinarySearch` (2.67%), `BitmapContainer.loadData` (1.96%), `getLongCardinality` (0.90%) and
`toArrayContainer` (0.87%) - all of which are roaring work, none of which passes through a dispatcher. Both numbers
are correct measurements of different things, and **41.32% is the one a kernel decision should be taken against**.

An earlier recording of the same workload, overwritten when the fixture dumps were captured, gave 40.89% / 26.41% /
2.78% for the three figures below; the run-to-run agreement is within half a point on each.

**Two thirds of that is the lazy-OR round trip.** `lazy-OR machinery` alone is 26.38% of all query CPU, with
`BitmapContainer.ilazyor` the single hottest method in the whole profile at 11.99%. The pieces around it are the
same round trip seen from different angles: `loadData` promotes an array into a fresh 8 KiB bitmap, `ilazyor`
scatters values into it, `computeCardinality`/`getLongCardinality` popcount it, and `fillArray`/`toArrayContainer`
extract it straight back to an array. Section 7 of `REPORT.md` counts how often that round trip ends where it
started: **95.28% of the time**.

**Intersection is 2.80% of query CPU.** Everything in the `and`/`iand`/`andNot` family, including both branches of
`Util.unsignedIntersect2by2` and the array/bitmap probes, adds up to less than one fourteenth of the roaring total
and about one ninth of the union machinery. A kernel that doubles intersection throughput moves under 1.4% of
query time even if it is perfect.

**Nearly a third of query CPU is not roaring and not the engine's query algebra either.** `java.util.*` is 30.32%,
split between hash-map traffic (`HashMap.getNode` 7.35%, `HashIterator.nextNode` 4.33%, `putVal` 1.36%, `resize`
0.68%, `compute` 0.65%) and sorting (`TimSort.mergeHi` 1.76%, `mergeLo` 1.46%, `binarySort` 0.44%,
`DualPivotQuicksort.sort` 1.18% plus `mixedInsertionSort` 0.81%), with a `Comparator.comparingInt` lambda at
3.62%. The facet-summary collector's finisher lambda is 4.83% self and 70.93% inclusive, so the accumulate-and-sort
step of building the facet summary is a cost centre of the same order as the bitmap arithmetic underneath it.
Section 5 attributes that map traffic to the code that owns it.

**The workload is a facet-summary workload.** `QueryPlan.fabricateExtraResults` is on 77.88% of stacks and
`ReferenceSummaryProducer.fabricate` on 77.88%, against `QueryPlan.execute` at 93.56%. Filtering proper is the
minority of the work; producing the facet summary is the majority, which is what makes the multi-way union the
dominant operation.
