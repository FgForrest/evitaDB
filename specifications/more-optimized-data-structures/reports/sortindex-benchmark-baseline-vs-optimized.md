# SortIndex — array vs B+ tree read benchmark

Benchmark source: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/SortIndexArrayVsBPlusTreeBenchmark.java`

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

Full sweep, JDK 21, 1 fork, 3 warmup + 5 measurement iterations × 2 s, single thread.
Raw data: `sortindex_jmh_results.csv` / `sortindex_jmh.log` (project root).

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
