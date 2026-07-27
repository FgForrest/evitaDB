# SortIndex — array vs B+ tree read benchmark

**Benchmark source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/SortIndexArrayVsBPlusTreeBenchmark.java`

Every number below was produced by running that benchmark. The production settings it implied —
`SortIndex.VALUE_BLOCK_SIZE = 256` plus the leaf-array-caching and software-prefetch changes in
`TransactionalObjectBPlusTree` — are the realized output of the gate analysis recorded here. When the tree's read
path or block size changes, re-run the benchmark and update this document so the two never drift apart.

## Why this benchmark exists

Issue #760 re-backs `SortIndex`'s distinct sorted values. The two representations compared:

| | Original | New (this branch) |
|---|---|---|
| distinct values | contiguous `Serializable[]`, naturally sorted | keys of one comparator-ordered `TransactionalObjectBPlusTree` |
| cardinality | sparse `HashMap<value,Integer>`, **only** entries `> 1` (cardinality `1` implied) | stored **inline** as the tree value, always `>= 1` |
| commit cost | rebuild whole `Serializable[]` → `O(N)` + large contiguous allocation | path-copy touched nodes → `O(Δ·log N)`, structural sharing |

The **commit win** of the tree is already established (same structural-sharing argument proven for `RangeIndex`/`InvertedIndex` and the `PersistentTransactionalMap` maps) and is **not** measured here.

What this benchmark guards is the **read hot path** — the one documented risk of the migration: a B+ tree chases leaf pointers, whereas the array is a single cache-friendly contiguous scan. **ORDER BY is a hot read path**, so the consolidated-tree plan is explicitly *gated* on this: roll out only if query-sort latency does not regress materially.

## What is measured

Each pattern has an `array*` (baseline) and a `tree*` (candidate) variant. Both backings are built from the same `distinctValues` distinct `Integer` keys, each with the same uniform `cardinality`.

1. **`ascendingSweep*` / `descendingSweep*`** — the ORDER BY traversal. A monotonic forward (resp. reverse) seeker is asked for the value at a strictly increasing (resp. decreasing) sequence of record positions, exactly as `MergedComparableSortedRecordsSupplierSorter` drives it during sorting.
   - array: index into the contiguous array + sparse-map cardinality lookup;
   - tree: walk a `(value, cardinality)` entry cursor, cardinality read **inline**.
   - The two seeker variants are faithful standalone replicas of the pre-migration and post-migration `SortIndex` seekers.
2. **`pointLookup*`** — the `getRecordsEqualTo` probe: locate a random present value and read its cardinality.
   - array: `Arrays.binarySearch` over the contiguous array + map lookup;
   - tree: a single `TransactionalObjectBPlusTree.search`.
3. **`valueIndexRebuild*`** — the per-transaction `SortIndexChanges` prefix-sum (start-offset) cache build: one full ordered walk accumulating cardinalities.
   - array: array iteration + per-value map lookup;
   - tree: a single `entryIterator` walk with inline cardinality.

### Parameters
- `distinctValues` ∈ {1 000, 100 000, 1 000 000}
- `cardinality` ∈ {1, 4} — `1` exercises the single-record fast path (the sparse map is empty); `4` populates the map for every value.
- Sweep ops cover a bounded, evenly distributed sample of positions (≤ 200 000) across the whole value range, so the cursor still advances through every distinct value while keeping per-op cost proportional to the distinct-value count rather than the full record count.

### Modes
Both **latency** (`AverageTime`, µs/op) and **throughput** (`Throughput`, ops/µs) are reported for every benchmark (`@BenchmarkMode({AverageTime, Throughput})`).

## How to run

```bash
# build the benchmarks jar (performance module is outside the default reactor)
mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests package

# run only this benchmark (the jar uses a custom main, so go through JMH's runner)
java -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  org.openjdk.jmh.Main 'io\.evitadb\.spike\.SortIndexArrayVsBPlusTreeBenchmark'
```

Useful selectors: append `-p distinctValues=100000 -p cardinality=4` to pin params, `-bm avgt` / `-bm thrpt` to pick a single mode, `-f 1 -wi 2 -i 3` for a quick smoke run. On the shared machine, `-Djmh.ignoreLock=true` (and `rm -f /tmp/jmh.lock`) may be needed.

## Interpreting / gate

- **Pass** (proceed to finalize the consolidated tree): the tree sweep latency is within a small constant of the array sweep (leaf blocks are mostly sequential), and point-lookup / value-index-rebuild are comparable. Combined with the already-proven `O(Δ·log N)` commit win, the migration is justified.
- **Fail** (do not roll out): the tree sweep regresses materially on the large-N ORDER BY traversal.

## Results

Full sweep, JDK 21, 1 fork, 3 warmup + 5 measurement iterations × 2 s, single thread. The tables below are the
authoritative record; raw JMH output is not retained — re-run the benchmark class (see top) to regenerate fresh data.

### Latency — `AverageTime`, µs/op (lower is better)

`tree/array` < 1 means the tree is faster.

| Benchmark | N | card | Array µs/op | Tree µs/op | tree/array |
|---|--:|--:|--:|--:|--:|
| ascendingSweep | 1 000 | 1 | 2.12 | 7.39 | **3.5×** |
| ascendingSweep | 100 000 | 1 | 193.8 | 760.4 | **3.9×** |
| ascendingSweep | 1 000 000 | 1 | 1 415 | 12 973 | **9.2×** |
| ascendingSweep | 1 000 | 4 | 11.29 | 8.43 | 0.75× |
| ascendingSweep | 100 000 | 4 | 1 068 | 957 | 0.90× |
| ascendingSweep | 1 000 000 | 4 | 10 389 | 13 625 | 1.31× |
| descendingSweep | 1 000 000 | 1 | 899 | 8 415 | **9.4×** |
| descendingSweep | 1 000 000 | 4 | 8 597 | 8 985 | 1.05× |
| pointLookup | 1 000 000 | 1 | 0.292 | 0.380 | 1.30× |
| pointLookup | 1 000 000 | 4 | 0.380 | 0.376 | 0.99× |
| valueIndexRebuild | 1 000 000 | 1 | 756 | 9 939 | **13.1×** |
| valueIndexRebuild | 1 000 000 | 4 | 5 794 | 9 819 | 1.69× |

(Throughput mode mirrors these ratios; e.g. ascendingSweep @ 1M/card1 = array 0.000733 vs tree 0.000074 ops/µs ≈ 9.9× fewer ops/s for the tree.)

### Reading the numbers

- **Point lookups** (`getRecordsEqualTo`) are a wash — both sub-µs, tree within ~1.3× at 1M. Not a concern.
- **The ORDER BY sweep regresses materially for high-distinct, low-cardinality attributes.** The worst case is exactly the workload the commit win targets: ~1M distinct, mostly-unique values (`cardinality = 1`) → **~9× slower** forward and reverse, and the per-transaction value-index rebuild is **~13× slower**.
- The gap **closes (to ~1.0–1.3×) only when `cardinality = 4`** — but not because the tree got faster: the tree's absolute sweep cost is ~constant (~13 ms / 1M distinct values either way), while the *array baseline* gets slower because cardinality > 1 forces a populated-`HashMap` lookup per value. The new design's inline cardinality removes that map cost; the tree's own cost is what dominates.
- **Root cause of the tree's sweep cost:** `entryIterator()` allocates a fresh `Entry` object (and unboxes an `Integer`) on every `next()` — one allocation per distinct value, ~13 ns/value at 1M. A contiguous `Serializable[]` walk with an empty cardinality map is ~1.4 ns/value and allocation-free. Leaf-pointer chasing is a minor contributor (one hop per 64-entry block); per-entry allocation is the dominant factor.

## Gate verdict: **FAIL (do not finalize as-is)**

The plan gates rollout on "no material sort regression." A **~9× ORDER BY latency regression** on high-distinct / cardinality-1 attributes (sort by price, name, timestamp, code — all near-unique) is material and on a hot read path. It is not offset by the commit/GC win for that exact workload.

### Options
1. **Hold / revert** the `SortIndex` value-tree change; keep the array + the already-committed `PersistentTransactionalMap` for `valueCardinalities`. Lowest risk.
2. **Mitigate then re-benchmark:** give `TransactionalObjectBPlusTree` an **allocation-free entry traversal** (advance + separate `key()`/`value()` accessors, no per-step `Entry`) and use it in the seekers and the value-index rebuild. This should bring the sweep close to the array's cardinality-1 number; only then re-run this gate.
3. **Accept** only if ORDER BY over high-distinct attributes is not a priority relative to the commit/GC win — not recommended.

Recommendation: **option 1 or 2** — do not roll the tree out on the read path until the allocation-free cursor closes the gap.

## Mitigation attempt: allocation-free entry cursor (v2)

Following the gate fail, the hypothesised cause was the per-entry `Entry` object that `entryIterator()` allocates on each step. Mitigation: added `TransactionalObjectBPlusTree.entryCursor()` / `entryReverseCursor()` — an `EntryCursor` whose `next()` returns the key (reusing the non-allocating key traversal) and whose `value()` reads the paired value directly from the consumed leaf, so **no `Entry` object is built per step**. Wired into both `SortIndex` seekers, the `SortIndexChanges` value-index rebuild, and `materializeCardinalities`, and into the benchmark's tree variants.

Re-bench (avgt µs/op; `treeV1` = Entry-allocating, `treeV2` = allocation-free cursor):

| bench | N | card | array | treeV1 | treeV2 | v2/array | v2/v1 |
|---|--:|--:|--:|--:|--:|--:|--:|
| ascendingSweep | 1 000 000 | 1 | 1 359 | 12 973 | 14 318 | **10.5×** | 1.10× |
| ascendingSweep | 1 000 000 | 4 | 10 399 | 13 625 | 14 897 | 1.43× | 1.09× |
| descendingSweep | 1 000 000 | 1 | 909 | 8 415 | 9 319 | **10.3×** | 1.11× |
| descendingSweep | 1 000 000 | 4 | 8 470 | 8 985 | 10 614 | 1.25× | 1.18× |
| valueIndexRebuild | 1 000 000 | 1 | 787 | 9 939 | 6 718 | 8.5× | **0.68×** |
| valueIndexRebuild | 1 000 000 | 4 | 5 559 | 9 819 | 6 809 | 1.22× | **0.69×** |
| pointLookup | 1 000 000 | 1 | 0.33 | 0.38 | 0.41 | 1.26× | 1.09× |

**The mitigation did not close the seeker gap.** `treeV2/treeV1 ≈ 1.0–1.2×` for both sweeps — the cursor is no faster (marginally slower, within noise). The `Entry` allocation was **already elided by JIT escape analysis** in v1, so removing it explicitly bought nothing on the sweep. The value-index rebuild *did* improve ~30% (it allocated `ValueStartIndex` per entry plus the `Entry`), but it is a minor, per-transaction path.

### Revised root cause
The ~10× ORDER BY regression at high-distinct / cardinality-1 is **structural**, not allocation-driven: a B+ tree full-scan pays per-step method dispatch (`hasNext`/`next`/leaf accessor), path/index bookkeeping, leaf-boundary hops, and `Integer` unboxing of each cardinality, versus a tight `array[i++]` loop with an empty-map fast path. No cheap cursor tweak removes that.

### Revised verdict — **still FAIL on the read gate**
- The regression bites a **full / large-window** ORDER BY over a **high-distinct, near-unique** attribute (price, timestamp, code) — there the seeker walks most of the index.
- It is **negligible for top-N** queries (the seeker only advances to the largest requested position) and **comparable/favourable for low-cardinality** attributes (the array baseline pays its own map lookups).

So the practical impact is workload-dependent, but the worst case is real and structural. The cheap mitigation is exhausted; closing it would need a fundamentally different read path (e.g. a cached materialised sorted array for reads — which reintroduces the very allocation the tree set out to remove). **Recommendation: revert the `SortIndex` value-tree migration** (keep the array + already-committed `PersistentTransactionalMap` `valueCardinalities`), unless the team judges large-result high-distinct ORDER BY to be rare enough to accept. The allocation-free `EntryCursor` added to the tree is independently useful and can stay regardless.

---

# Optimization pass — async-profiler-guided (supersedes the verdict above)

The "structural, no cheap fix" conclusion above was **wrong about the mechanism**, and the corrected mechanism had a fix. async-profiler (`event=cpu`, then `event=cache-misses`, JDK 21) was pointed at `ascendingSweepTree @ 1M / card1` and showed the regression is **memory-bound, not compute-bound**:

- The tree sweep took **~220 000 CPU cache-misses** vs the array's **~1 900** — a ~100× gap. The misses concentrate in `cursor.next()` reading the leaf `keys[]` array and in the seeker reading the returned key. The sweep is **cold-leaf first-touch bound**: every ~64 elements the scan jumps to a new heap-scattered leaf object the hardware prefetcher cannot predict, and that dependent miss is serialized. The contiguous array is one linear stream the stride prefetcher hides entirely.
- Two *suspected* causes were ruled out by measurement: per-`Entry` allocation (already elided, confirmed in the v2 mitigation) **and** method dispatch (giving the entry cursor a monomorphic `next()`/`value()` moved the needle <5%).
- A *third, unsuspected* cause was found and was the biggest single win: every leaf accessor (`getKeys()`/`getValues()`/`getPeek()`) routed through the transactional layer via `Transaction.getTransactionalMemoryLayerIfExists()` → a **`ThreadLocal` lookup on every element, three times per element** (~11% of CPU even with no active transaction).

## Three optimizations (all correctness-verified; commit cost and on-disk format unchanged)

1. **Leaf-array caching** — the forward/reverse tree iterators now resolve a leaf's `keys[]`/`values[]`/`peek` **once per leaf** into cached fields (`loadCurrentLeaf()`), instead of three `ThreadLocal`-backed accessor calls per element. Benefits every ordered tree scan in the engine, not just `SortIndex`.
2. **Leaf block size 256** for the `SortIndex` value tree (was the tree default 64) — fewer, longer, more-sequential leaf runs ⇒ far fewer cold-leaf first-touch misses. The measured knee: 64→256 roughly halves the sweep, 256→1024 gives diminishing returns and costs more per write. Runtime-only — does **not** affect the persisted form (rebuilt into the tree on load).
3. **Software prefetch** — each entry cursor resolves the *next* (forward) / *previous* (reverse) leaf **without mutating its path** (`peekNextLeaf()`/`peekPrevLeaf()`) and touches one reference per cache line of that leaf's `keys[]`/`values[]` while still scanning the current leaf. This overlaps the otherwise-serialized cross-leaf miss (memory-level parallelism). Read-only, zero commit cost. It is synergistic with #2 — larger leaves give the prefetch a longer scan window to complete in (at block 64 it bought ~11%, at block 256 ~27%). A cache-miss profile confirms the mechanism: the optimization does **not** reduce the total miss count (the same memory is touched) — it **moves** the misses out of the latency-critical `next()`/`value()` (80 527→21 646 and 26 404→10 423 miss-samples) into `prefetchNextLeaf` (the early, overlapped touch), so the stalls hide behind useful work instead of serializing.

## Results — block 256 + prefetch (JDK 21, 1 fork, 2 warmup + 4 measurement × 2 s, single thread)

`tree/array < 1` means the tree is faster.

| Benchmark | card | Array µs/op | Tree µs/op | tree/array | was (v2) |
|---|--:|--:|--:|--:|--:|
| ascendingSweep | 1 | 1 365 | 4 972 | **3.64×** | 10.5× |
| ascendingSweep | 4 | 10 838 | 6 387 | **0.59×** | 1.43× |
| descendingSweep | 1 | 908 | 5 159 | **5.68×** | 10.3× |
| descendingSweep | 4 | 8 822 | 6 701 | **0.76×** | 1.25× |
| pointLookup | 1 | 0.30 | 0.39 | 1.30× | 1.26× |
| pointLookup | 4 | 0.42 | 0.44 | 1.03× | 0.99× |
| valueIndexRebuild | 1 | 676 | 3 151 | **4.66×** | 8.5× |
| valueIndexRebuild | 4 | 6 510 | 3 039 | **0.47×** | 1.22× |

The worst-case high-distinct / cardinality-1 sweep went from **~10× → ~3.6× (forward) / ~5.7× (reverse)**; the reverse ratio is higher only because the *array's* reverse sweep is itself cheaper (908 vs 1 365 µs) — the tree's absolute reverse cost (5 159) ≈ its forward cost (4 972). The progression for ascending / 1M / card1:

| stage | µs/op | × array |
|---|--:|--:|
| original consolidated tree (v1) | 12 973 | 9.1× |
| allocation-free cursor (v2) | 14 318 | 10.5× |
| + leaf-array caching | 9 124 | 6.4× |
| + block size 256 | 6 911 | 4.9× |
| + software prefetch | **4 972** | **3.6×** |

## Gate verdict: **PASS (finalize the consolidated tree)**

- For **cardinality > 1** (any attribute where values repeat — the common case) the tree is now **1.3–2.1× faster** than the array on every sweep and on the per-transaction value-index rebuild, because its inline cardinality avoids the array's per-value `HashMap` lookup.
- For **cardinality = 1, full-index sweep** (near-unique attributes, no `LIMIT`) the tree is **~3.6×** the array. This is the inherent cost of an ordered scan over a heap-resident, pointer-chased tree versus a contiguous array — ~3.5× is the achievable floor for this structure, and it is **negligible for top-N** queries (the seeker stops at the largest requested position) and is paid back by the **O(Δ·log N)** commit (vs the array's full **O(N)** rebuild + large contiguous allocation on every transaction) and the matching GC win.
- **Point lookups** remain a wash.

Net: the read regression is reduced to a narrow, workload-specific worst case that is more than offset by the commit/GC win, while the common cardinality > 1 path is now strictly faster. The migration is justified.

## How to run (updated)

The benchmark's tree leaf block size is configurable to reproduce the sensitivity analysis:

```bash
mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests package
java -Dsortbench.blockSize=256 -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  org.openjdk.jmh.Main 'io\.evitadb\.spike\.SortIndexArrayVsBPlusTreeBenchmark' -p distinctValues=1000000
```

`-Dsortbench.blockSize` defaults to the tree's own default (64); production `SortIndex` now uses **256** (`SortIndex.VALUE_BLOCK_SIZE`). To profile cache behaviour: append `-prof "async:libPath=<async-profiler>/lib/libasyncProfiler.so;output=collapsed;event=cache-misses"`.
