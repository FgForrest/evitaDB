# Roaring container census on a production retail catalog

Measured for issues #1541-#1544, so the kernel work is aimed at the container shapes and the operation mix a real
catalog actually produces rather than at synthetic ones.

**Dataset.** A restored production e-commerce catalog: 18 collections, 475,385 entities (160,216 products,
228,150 media, 46,693 pickup points, 29,159 parameter values, 648 categories), 565,534 entity indexes, 3.5 GB on
disk. Loaded in 51.7 s at `-Xmx48g`, catalog version 24304, state `ALIVE`.

**Box.** AMD Ryzen AI 9 HX 370 (Zen 5, 12c/24t), OpenJDK 21, `-Xmx48g -XX:+ExitOnOutOfMemoryError`.

**How.** Throwaway counters in this worktree's `evita_roaring_bitmap`: a hook on every container materialised by
`RoaringArray#deserialize`, a hook on each of `Container`'s twelve generic dispatchers, and three counters in
`Util#unsignedIntersect2by2`, plus lazy-OR structure counters in `FastAggregation`, `toBitmapContainer()` and
`repairAfterLazy()` (section 7). The harness is
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/RoaringContainerCensus.java`.
Raw log: `specifications/1541-simd-roaring/logs/census.log`.

**A JFR profile of the same workload is `census/PROFILE.md`.** Read it alongside section 3: the 12% below is the
share of query wall clock spent inside `Container`'s generic dispatchers, while the profile puts *all* roaring
work at **41.32% of query CPU**. Both are correct and they measure different things; the profile's number is the
one a kernel decision should be taken against.

---

## 1. Static histogram - containers materialised while the catalog loaded

12,294,802 containers in 6,594,481 bitmaps, 123,958,173 values in total (mean 10.1 values per container).

| type | 1 | 2-4 | 5-16 | 17-64 | 65-256 | 257-1024 | 1025-4096 | 4097-16384 | 16385-65536 | total | cardinality |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Array | 6,740,608 | 2,966,415 | 1,663,738 | 631,292 | 209,560 | 48,407 | 7,179 | 0 | 0 | 12,267,199 | 107,530,502 |
| Bitmap | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 549 | 61 | 610 | 6,707,210 |
| Run | 0 | 2,248 | 8,992 | 7,131 | 4,720 | 2,501 | 845 | 527 | 29 | 26,993 | 9,720,461 |
| **all** | 6,740,608 | 2,968,663 | 1,672,730 | 638,423 | 214,280 | 50,908 | 8,024 | 1,076 | 90 | 12,294,802 | 123,958,173 |
| **share** | 54.82% | 24.15% | 13.61% | 5.19% | 1.74% | 0.41% | 0.07% | 0.01% | 0.00% | | |

Containers per bitmap:

| containers | bitmaps | share |
|---|---|---|
| 1 | 5,117,738 | 77.61% |
| 2-4 | 1,034,596 | 15.69% |
| 5-16 | 366,303 | 5.55% |
| 17+ | 75,844 | 1.15% |

Three facts carry: **99.78% of stored containers are `ArrayContainer`**, **54.82% hold exactly one value**, and the
whole catalog holds **610 `BitmapContainer`s** - five thousandths of one percent. Bitmaps in this catalog are a
query-time phenomenon, not a storage one: they appear when a union promotes an array, and the load path barely
produces any.

Every collection was opened and its index count read before the histogram was printed, so nothing was left behind
a lazy open. Index counts are concentrated in `Product` (282,984), `ParameterValue` (161,982) and `Category`
(88,056).

---

## 2. Dynamic op mix - 2,600 queries, 52,451,574 container operations

Query mix, all against `Product`, single-threaded, one read-only session per pass:

| kind | queries | generator |
|---|---|---|
| facet + hierarchy summary, `COUNTS` | 800 | `ClientFacetAndHierarchyFilteringAndSummarizingCountState`'s generators |
| facet + hierarchy summary, `IMPACT` | 400 | `...ImpactState`'s generators |
| facet summary, `COUNTS`, no hierarchy | 800 | `ClientFacetFilteringAndSummarizingCountState`'s generators |
| attribute filtering + sort | 300 | `ClientAttributeFilteringState`'s generator |
| price filtering + sort | 300 | `ClientPriceFilteringState`'s generator |

Zero query failures; 38,848,574 records matched in total. Counters were reset after the load, so nothing below is
load-time work.

### Top 20 combinations

| # | operation | left | right | count | share |
|---|---|---|---|---|---|
| 1 | `lazyIOR` | Bitmap | Array | 48,615,487 | 92.69% |
| 2 | `iand` | Array | Array | 1,783,056 | 3.40% |
| 3 | `iand` | Array | Bitmap | 1,243,920 | 2.37% |
| 4 | `andNot` | Bitmap | Array | 228,681 | 0.44% |
| 5 | `iand` | Array | Run | 208,899 | 0.40% |
| 6 | `iand` | Bitmap | Array | 105,255 | 0.20% |
| 7 | `iand` | Bitmap | Bitmap | 85,100 | 0.16% |
| 8 | `iand` | Run | Array | 69,793 | 0.13% |
| 9 | `andNot` | Array | Array | 43,104 | 0.08% |
| 10 | `lazyIOR` | Bitmap | Bitmap | 34,638 | 0.07% |
| 11 | `and` | Array | Array | 12,584 | 0.02% |
| 12 | `and` | Array | Run | 4,574 | 0.01% |
| 13 | `or` | Array | Array | 4,056 | 0.01% |
| 14 | `andNot` | Bitmap | Bitmap | 2,903 | 0.01% |
| 15 | `lazyIOR` | Bitmap | Run | 2,887 | 0.01% |
| 16 | `iand` | Run | Run | 2,438 | 0.00% |
| 17 | `andNot` | Run | Array | 1,348 | 0.00% |
| 18 | `lazyOR` | Array | Array | 1,258 | 0.00% |
| 19 | `and` | Bitmap | Array | 801 | 0.00% |
| 20 | `andNot` | Array | Bitmap | 400 | 0.00% |

Four further combinations carry 336 operations between them.

### By operation and by operand pair

| operation | count | share |
|---|---|---|
| `lazyIOR` | 48,653,148 | 92.76% |
| `iand` | 3,498,661 | 6.67% |
| `andNot` | 276,436 | 0.53% |
| `and` | 18,015 | 0.03% |
| `or` | 4,056 | 0.01% |
| `lazyOR` | 1,258 | 0.00% |

| operand pair | count | share |
|---|---|---|
| Bitmap x Array | 48,950,224 | 93.32% |
| Array x Array | 1,844,194 | 3.52% |
| Array x Bitmap | 1,244,376 | 2.37% |
| Array x Run | 213,473 | 0.41% |
| Bitmap x Bitmap | 122,641 | 0.23% |
| Run x Array | 71,141 | 0.14% |
| Bitmap x Run | 2,919 | 0.01% |
| Run x Run | 2,438 | 0.00% |
| Run x Bitmap | 168 | 0.00% |

**`andCardinality`, `intersects`, `ior`, `iandNot`, `xor` and `ixor` were never invoked** - not once in 52.5
million operations. This confirms the design note's grep: `andCardinality` has no engine caller because #1540 has
not landed.

### Array x array intersection shapes

Rows are the smaller operand's cardinality, columns the larger one's. 1,795,640 intersections
(`and` + `iand` + `andCardinality` with two array operands); every one of them went through
`Util#unsignedIntersect2by2`, so the two totals agree exactly.

| min \ max | 1 | 2-4 | 5-16 | 17-64 | 65-256 | 257-1024 | 1025-4096 |
|---|---|---|---|---|---|---|---|
| 1 | 37,980 | 44,932 | 63,594 | 88,986 | 99,482 | 107,131 | 52,763 |
| 2-4 | | 57,383 | 61,828 | 67,585 | 85,711 | 117,630 | 61,764 |
| 5-16 | | | 60,223 | 69,718 | 82,800 | 113,816 | 94,179 |
| 17-64 | | | | 50,098 | 52,203 | 64,174 | 72,822 |
| 65-256 | | | | | 30,161 | 48,593 | 30,287 |
| 257-1024 | | | | | | 23,274 | 33,591 |
| 1025-4096 | | | | | | | 22,932 |

Nothing landed above 4,096 on either axis, which is the array-container promotion threshold.

| slice | count | share |
|---|---|---|
| smaller operand = 1 | 494,868 | 27.56% |
| smaller operand <= 4 | 946,769 | 52.73% |
| smaller operand <= 16 | 1,367,505 | 76.16% |
| smaller <= 16 **and** larger >= 257 | 547,283 | 30.48% |
| both operands >= 257 | 79,797 | 4.44% |

Result sizes:

| result | count | share |
|---|---|---|
| 0 (empty) | 325,164 | 18.11% |
| 1 | 405,052 | 22.56% |
| 2-4 | 363,754 | 20.26% |
| 5-16 | 332,139 | 18.50% |
| 17-64 | 199,639 | 11.12% |
| 65-256 | 93,745 | 5.22% |
| 257-1024 | 53,561 | 2.98% |
| 1025-4096 | 22,586 | 1.26% |

60.92% of intersections produce four values or fewer; 79.42% produce sixteen or fewer.

### `Util#unsignedIntersect2by2` branch split

| branch | count | share |
|---|---|---|
| galloping, left operand small | 870,652 | 48.49% |
| galloping, right operand small | 11,262 | 0.63% |
| linear merge | 913,726 | 50.89% |

The two are almost evenly split. The galloping side is nearly always entered with the *left* argument as the small
one, which is what `ArrayContainer#iand` passes when the current accumulator has already been narrowed.

### Bitmap x bitmap results

| operation | to Array | to Bitmap | to Run | demoted |
|---|---|---|---|---|
| `iand` | 50 | 85,050 | 0 | 0.06% |
| `lazyIOR` | 0 | 34,638 | 0 | 0.00% |
| `andNot` | 1,388 | 1,515 | 0 | 47.81% |

A bitmap intersected with a bitmap essentially always stays a bitmap here. Only `andNot` demotes, and it is 0.01%
of the workload.

---

## 3. Wall clock and the share of container arithmetic

Same 2,600 queries replayed four times in one JVM.

Figures below are from the final run, the same one section 7 and `PROFILE.md` come from.

| pass | wall (ms) | records | container ops counted |
|---|---|---|---|
| warm-up, uninstrumented | 32,326 | 38,842,917 | 0 |
| baseline, uninstrumented | 33,229 | 38,842,917 | 0 |
| counting + operand sampling | 31,060 | 38,842,917 | 52,451,466 |
| timing | 33,308 | 38,842,917 | 52,451,466 |
| lazy-OR structure | 29,887 | 38,842,917 | 0 |

| measure | value |
|---|---|
| time inside the outermost container operation | 3,858,251,770 ns |
| outermost operations timed | 52,451,466 |
| mean per operation | 73 ns |
| share of the timing pass's wall clock | 11.58% |
| share of the uninstrumented baseline | 11.61% |

Per query kind, in milliseconds for the whole 2,600-query pass:

| kind | queries | baseline | counting | timing |
|---|---|---|---|---|
| facet summary `COUNTS` | 800 | 24,236 | 21,818 | 23,793 |
| price filtering | 300 | 6,244 | 5,600 | 5,589 |
| facet + hierarchy `COUNTS` | 800 | 1,755 | 2,458 | 2,850 |
| facet + hierarchy `IMPACT` | 400 | 823 | 1,043 | 937 |
| attribute filtering | 300 | 164 | 135 | 131 |

**Read the 12% as a floor with a wide error bar, not as a number.** The clock is read around the twelve generic
`Container` dispatchers and nothing else, so it excludes `computeCardinality()` and `repairAfterLazy()` (which every
multi-way union pays), `Util#fillArray` extraction, iterator walks, `RoaringArray`-level merge bookkeeping and the
range-index kernels - all of which are roaring work that a kernel campaign could also touch. Against that, each
timed region pays two `System.nanoTime()` calls, which on 52.5 million regions is itself a large fraction of the
3.75 s measured. The two errors push in opposite directions and neither was quantified.

**The profile settles it from outside.** `census/PROFILE.md` measures the same workload with no instrumentation at
all and attributes **41.32% of query CPU** to `io.evitadb.roaringbitmap.*`, of which 26.38 points are the lazy-OR
machinery and only 2.80 points are intersection and difference. The dispatcher clock and the sampler disagree by a
factor of 3.5 because they are measuring different sets of methods, not because either is wrong; every table in
section 5 that reasons about *reachable* work should be read against the profile's split.

---

## 4. Operand dump

`census/operands.bin`, 38,158,754 bytes, **6,092 pairs** across 23 combinations, format documented in
`census/FORMAT.md`, which lists every operation id and all 23 combinations the file carries, alongside two further
fixtures that came out of the same hooks: **`census/unions.bin`** (300 whole multi-way unions, 17.3 MB, stratified
by input count) and **`census/repaired.bin`** (300 lazily-unioned bitmaps as `repairAfterLazy` received them,
2.5 MB). The second one is the fixture for a sparse-extraction kernel: its median container has **12 non-zero
words out of 1024**, and 89.67% of them leave at least 93.75% of the 8 KiB empty, while `Util.fillArray` walks all
of it. Reservoir sampling with a fixed seed, up to 300 pairs per (operation, left type, right type),
captured before the operation ran so an in-place operation cannot corrupt what was recorded.

Verified after writing: magic and version correct, every array side sorted ascending, every side's stored
cardinality equal to the popcount of its payload, no trailing bytes.

| operation | left | right | sampled | seen |
|---|---|---|---|---|
| `lazyIOR` | Bitmap | Array | 300 | 48,615,292 |
| `iand` | Array | Array | 300 | 1,783,084 |
| `iand` | Array | Bitmap | 300 | 1,243,920 |
| `andNot` | Bitmap | Array | 300 | 228,681 |
| `iand` | Array | Run | 300 | 208,899 |
| `iand` | Bitmap | Array | 300 | 105,295 |
| `iand` | Bitmap | Bitmap | 300 | 85,085 |
| `iand` | Run | Array | 300 | 69,793 |
| `andNot` | Array | Array | 300 | 43,104 |
| `lazyIOR` | Bitmap | Bitmap | 300 | 34,715 |
| `and` | Array | Array | 300 | 12,584 |
| `and` | Array | Run | 300 | 4,574 |
| `or` | Array | Array | 300 | 4,056 |
| `andNot` | Bitmap | Bitmap | 300 | 2,903 |
| `lazyIOR` | Bitmap | Run | 300 | 2,887 |
| `iand` | Run | Run | 300 | 2,438 |
| `andNot` | Run | Array | 300 | 1,348 |
| `and` | Bitmap | Array | 300 | 801 |
| `andNot` | Array | Bitmap | 300 | 400 |
| `iand` | Run | Bitmap | 168 | 168 |
| `lazyIOR` | Array | Array | 136 | 136 |
| `and` | Array | Bitmap | 56 | 56 |
| `iand` | Bitmap | Run | 32 | 32 |

Cardinality ranges in the three combinations that matter most:

| combination | left cardinality (mean / median / p90 / max) | right cardinality (mean / median / p90 / max) |
|---|---|---|
| `lazyIOR` Bitmap x Array | 5,817 / 4,297 / 13,607 / 18,425 | 42 / 4 / 78 / 2,250 |
| `iand` Array x Array | 102 / 4 / 121 / 2,430 | 627 / 236 / 1,745 / 3,798 |
| `iand` Array x Bitmap | 119 / 30 / 363 / 1,342 | 12,904 / 12,352 / 18,426 / 18,426 |

---

## 5. Interpretation

**The workload is one operation, and it is not a word-parallel one.** `lazyIOR` on a `BitmapContainer` with an
`ArrayContainer` right-hand side is 92.69% of all container arithmetic. Its implementation,
`BitmapContainer#ilazyor(ArrayContainer)`, is not a word-wise OR at all - it is a **scatter**: one
`bitmap[v >>> 6] |= 1L << v` per value of the array, with a data-dependent index, and no cardinality bookkeeping
(the recount is deferred to `repairAfterLazy`). The sampled operands say the array carries a **median of 4 values**
and a mean of 42, so the dominant call does a handful of dependent read-modify-writes into an 8 KiB word array and
returns. That is the shape SIMD helps least with: a 64-bit OR-scatter has no AVX-512 instruction, conflict
detection (`vpconflictd`) costs more than the four scalar stores it would replace, and at a median of four values
there is no loop to vectorise. **The 92% is, on this catalog, not addressable by the #1542 kernel set.**

What #1542's fused word kernels *do* reach is `lazyIOR` Bitmap x Bitmap (34,638 calls, 0.07%) and `iand` Bitmap x
Bitmap (85,100, 0.16%) - 0.23% of operations between them. Even a 3x win there moves nothing end to end.

**#1543's array x array intersection is 3.52% of operations** and the shapes are hostile to the issue's all-pairs
mechanism: the smaller operand holds <= 16 values in 76.16% of intersections and exactly one value in 27.56%. An
8x8 all-pairs block kernel needs both operands to fill blocks; here one side is usually shorter than a single
block. The regime that a vector kernel could plausibly win - both operands >= 257 - is **4.44%** of array x array
intersections, which is 0.16% of all container work. The branch counters agree with the shape matrix: galloping
takes 49.1% of intersections (almost all with the left operand as the small one) and the linear merge 50.9%, so
neither branch dominates, and the galloping half is exactly the skewed half a block kernel cannot help.

**#1544's array-versus-bitmap probe is the best-placed of the three.** `iand` Array x Bitmap (1,243,920, 2.37%)
plus `iand` Bitmap x Array (105,255) plus `andNot` Bitmap x Array (228,681) is 3.01% of operations, and its shape
is uniform: probe a median of 30 values against a dense bitmap of median cardinality 12,352. That is a gather with
a fixed, branch-free inner body - the one candidate whose operand shape matches its kernel.

**Ceiling on any of it.** Container arithmetic is ~12% of query wall clock, and the 92.69% inside that 12% is the
scatter. Unless `ilazyor(ArrayContainer)` itself is the target, the reachable fraction of end-to-end query time is
in the low single-digit percent before any speed-up multiplier is applied. The honest framing for the ADR is that
this catalog's query cost is dominated by producing and merging **many tiny arrays**, not by wide word arithmetic -
which is the same thing the static histogram says from the other side, with 54.82% of stored containers holding a
single value.

---

## 6. What could not be measured, and what was changed

- **`andCardinality` and `intersects` have no invocations**, so no shapes could be collected for them. This is not
  a sampling gap - the counters are exact and they are zero.
- **The wall-clock share is a floor.** The clock covers the twelve generic `Container` dispatchers only; see section 3.
  Container work reached through a typed overload from inside another container method is counted once, at the
  outermost dispatcher, which is the right unit for an op mix but means nested work is not separately visible.
- **The static histogram counts deserialization only.** Containers built during the query phase (every union
  promotion) are not in it, and neither is any storage part loaded lazily after the histogram was printed.
- **Query-generator statistics came from a 25,000-product sample** of 160,216, not from a full catalog read as the
  benchmark states do. Facet ids, attribute value ranges and price ranges therefore skew toward common values.
  The categories list (648) and all schema-derived inputs are complete.
- **Two deviations from the benchmark states were necessary to make the queries run.** The states key
  `facetedReferences` by *referenced entity type* while `updateFacetStatistics` and `facetHaving` both key by
  *reference name*; keyed the states' way, the maps stay empty for every reference whose name differs from its
  entity type. And `hierarchyWithin` names a reference, not an entity type - passing `"Category"` as the states do
  made all 1,200 hierarchy queries fail with *"Reference with name `Category` is not present in schema of
  `Product`"*. The first census run is kept at `logs/census-run1-hierarchy-failed.log` as evidence. Both were
  fixed in the harness by resolving the reference name from the schema; the states themselves were not touched.
- **The sampled operation set was widened** beyond the four the brief named. `and`/`andCardinality`/`or`/`andNot`
  together are 0.57% of the workload, so a fixture built only from them would replay the tail. `iand`, `ior` and
  `lazyIOR` were added, which is why the dump has 23 combinations.
- **Run-to-run stability.** Three full runs after the hierarchy fix agreed on the operation total to within
  108 operations out of 52.45 million (52,451,574 twice, 52,451,466 once) and on every per-combination count.
  Sections 1 and 2 quote the third run, sections 3 and 7 and `PROFILE.md` the fourth; the op-mix tables are
  unchanged between them at the precision printed. Baseline wall clock varied 29.1-33.2 s across runs and the
  container-arithmetic share 11.6-12.9%, which is the honest error bar on that figure. The counting pass varied
  28.3-43.1 s because operand sampling deep-copies 8 KiB bitmaps, so its wall clock is not comparable to the
  others. Another agent's Maven build was running on the box during part of the first run; the later runs were
  quiet.
- **The all-threads allocation counter is unusable** and the query-thread counter was used instead; see section
  7c for why.
- **Instrumentation is throwaway** and lives only in this worktree: `CensusHook.java` plus hooks in
  `Container.java`, `RoaringArray.java`, `Util.java`, `FastAggregation.java`, `ArrayContainer.java`,
  `BitmapContainer.java` and `RunContainer.java`, and the `RoaringContainerCensus` harness. Nothing is committed.

---

## 7. The lazy-OR machinery: structure, promotions and allocation

Measured in a fifth pass over the same 2,600 queries, with the container dispatchers switched off so only the
union counters fire. The CPU profile that goes with this section is `census/PROFILE.md`.

### 7a. How wide the multi-way unions are

`FastAggregation.naive_or` / `or(...)`: **294,264 calls folding 15,612,013 bitmaps**, a mean of **53.05 inputs per
union**. `horizontal_or` ran 1,666 times and `priorityqueue_or` never.

| inputs per union | calls | share of calls |
|---|---|---|
| 1 | 0 | 0.00% |
| 2-4 | 176,178 | 59.87% |
| 5-16 | 80,825 | 27.47% |
| 17-64 | 19,598 | 6.66% |
| 65-256 | 10,771 | 3.66% |
| 257-1024 | 2,765 | 0.94% |
| 1025+ | 4,127 | 1.40% |

The distribution is extremely long-tailed: 87.34% of unions fold sixteen bitmaps or fewer, yet the mean is 53
because the 1.40% of unions with 1,025 or more inputs dominate the total input count. Those are the facet-summary
unions over every entity carrying a facet.

### 7b. Promotions and repairs

`toBitmapContainer()` was called **48,654,699** times:

| called on | calls | share | cost |
|---|---|---|---|
| Bitmap | 46,783,385 | 96.15% | returns `this`, no allocation |
| Array | 1,871,046 | 3.85% | allocates an 8 KiB word array and scatters the values in |
| Run | 268 | 0.00% | allocates an 8 KiB word array |

`PersistentRoaringBitmap#naivelazyor` calls `toBitmapContainer()` unconditionally on the accumulator's container
for every shared key, so the 96.15% is the accumulator already being a bitmap from a previous fold. The 3.85% that
allocate are the first fold of each key: **1,871,314 allocating promotions (1,871,046 from arrays plus 268 from
runs) x 8 KiB = 15,329,804,288 bytes**.

Cardinality of the array containers that got promoted:

| cardinality | promotions | share |
|---|---|---|
| 1 | 385,299 | 20.59% |
| 2-4 | 412,309 | 22.04% |
| 5-16 | 464,120 | 24.81% |
| 17-64 | 342,573 | 18.31% |
| 65-256 | 184,921 | 9.88% |
| 257-1024 | 68,586 | 3.67% |
| 1025-4096 | 13,238 | 0.71% |

**67.44% of the arrays promoted into an 8 KiB bitmap hold sixteen values or fewer**, and 20.59% hold exactly one.

`repairAfterLazy()` was called 2,500,881 times - 1,930,067 on a bitmap, 570,797 on an array, 17 on a run - and
**1,929,873 of those were real repairs**, meaning the cardinality was still unknown and a full 1024-word popcount
ran. What the repaired bitmap became:

| became | count | share |
|---|---|---|
| **Array** | **1,838,876** | **95.28%** |
| Bitmap | 90,997 | 4.72% |
| Run | 0 | 0.00% |

Cardinality of the repaired containers:

| cardinality | count | share |
|---|---|---|
| 1 | 89,910 | 4.66% |
| 2-4 | 206,824 | 10.72% |
| 5-16 | 356,163 | 18.46% |
| 17-64 | 446,735 | 23.15% |
| 65-256 | 371,956 | 19.27% |
| 257-1024 | 251,481 | 13.03% |
| 1025-4096 | 115,807 | 6.00% |
| 4097-16384 | 85,180 | 4.41% |
| 16385-65536 | 5,817 | 0.30% |

### 7c. Allocation

`com.sun.management.ThreadMXBean#getThreadAllocatedBytes` on the query thread, which is where 99.99% of the JFR
samples land:

| pass | wall (ms) | bytes allocated by the query thread |
|---|---|---|
| warm-up, uninstrumented | 32,326 | 66,061,398,200 |
| baseline, uninstrumented | 33,229 | 65,948,827,856 |
| counting + sampling | 31,060 | 66,106,707,648 |
| timing | 33,308 | 65,902,557,344 |
| lazy-OR structure | 29,887 | 65,901,973,600 |

Set against that, the promotions:

| | |
|---|---|
| array-to-bitmap promotions | 1,871,046 |
| bytes of bitmap words they allocate | 15,327,608,832 |
| **share of the query workload's total allocation** | **23.26%** |

The all-threads sum was also collected but is not usable: it is computed over the threads alive at each sample, so
engine worker threads that existed at the start of a pass and had exited by its end subtract their whole counter
from the difference. It reported 26.3 GB for the baseline pass against 65.9 GB on the query thread alone, which is
the signature of that undercount. The query-thread figure is exact and every pass agrees on it to within 0.3%.

### 7d. Is the fixed cost significant next to the scatter?

**It is larger than the scatter.** The JFR profile puts the whole lazy-OR machinery at 26.38% of query CPU, of
which `ilazyor` - the scatter itself - is 11.99%; the other 14.4% is the promotion (`loadData`), the popcount
(`computeCardinality`, `getLongCardinality`), the extraction back to an array (`fillArray`, `toArrayContainer`)
and the per-key merge walk (`naivelazyor`). On the allocation side the promotions alone are 23.26% of every byte
the query workload allocates, and 67.44% of them wrap sixteen values or fewer in an 8 KiB array.

**And the round trip usually ends where it started: 95.28% of repaired containers demote straight back to an
`ArrayContainer`.** For those 1.84 million containers the engine allocated 8 KiB, zeroed it, scattered a median of
tens of values into it, scanned all 1024 words to count them, then walked the words again to extract the values
back into a small array - to produce a container that was an array before the union began. That is a structural
cost of the lazy-OR strategy, not of any kernel, and it is the largest single addressable item this census found.
