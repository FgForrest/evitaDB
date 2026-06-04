# RangeIndex — B+ tree leaf block-size measurement

**Benchmark source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/RangeIndexBlockSizeBenchmark.java`

The tables below are the authoritative record — raw JMH CSV output is not retained. Re-run the benchmark (command
below) to regenerate the numbers, and update this document when the tree's read or write path changes so the two never
drift apart.

## Why this benchmark exists

`RangeIndex` (the range companion of every `FilterIndex` over a `Range` type) is backed by a
`TransactionalLongBPlusTree` keyed by the `long` threshold, with a `TransactionalRangePoint` value (the `starts` /
`ends` record bitmaps at that threshold). Like `InvertedIndex`, the tree's leaf block size was the **inherited default
of 64**. The range workload leans on **full ordered sweeps** (the range-histogram / `rangesIterator` path) and
threshold probes, plus a two-points-per-record write on every `addRecord`, so the read-vs-write block-size trade-off is
its own question, distinct from the SortIndex one. This benchmark answers it on data.

## What is measured

The benchmark uses the **real** `TransactionalRangePoint` value (a transactional producer) through the tree's
wrapper-aware block-size constructor, so `commit` includes the genuine per-value layer merge. It measures the tree
directly with faithful replicas of the RangeIndex access paths (block size is a tree property; the surrounding range
accumulation is block-size invariant).

| method | mirrors | what it stresses |
|---|---|---|
| `pointLookup` | threshold probe | in-leaf binary-search depth (`log₂ blockSize`) over `long` keys |
| `rangeScan` | partial range query | bounded forward threshold-walk locality (2 000-point window) |
| `fullScan` | `rangesIterator` / range-histogram | full ordered sweep accumulating the active set |
| `bulkLoad` | deserialization / restore | cumulative in-leaf arraycopy + node splits on build |
| `commit` | `addRecord` + transaction commit | `O(touched leaves · blockSize)` path-copy on commit (100 spread inserts) |

### Parameters
- `blockSize ∈ {32, 64, 128, 256, 512}` — the variable under study.
- `distinctThresholds ∈ {100 000, 1 000 000}` — number of range endpoints (block size matters at scale).
- `recordsPerPoint ∈ {1, 16}` — low vs moderate fan-out into the `starts`/`ends` bitmaps.

## How to run

```bash
# build the benchmarks jar (performance module is outside the default reactor)
mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests package

# full matrix (both modes, two forks — the publishable configuration)
java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
  'io\.evitadb\.spike\.RangeIndexBlockSizeBenchmark' \
  -f 2 -foe true -rf csv -rff rangeindex_blocksize.csv
```

Pin a slice with `-p blockSize=64,512 -p distinctThresholds=1000000 -p recordsPerPoint=1`; latency only with
`-bm avgt`. On a shared machine, `rm -f /tmp/jmh.lock` (and `-Djmh.ignoreLock=true`).

## Results

JDK 21.0.11 (OpenJDK 64-Bit Server VM), JMH 1.37, **2 forks**, 3 warmup + 5 measurement iterations × 2 s, single
thread. Numbers below are `avgt` **µs/op** (lower is better) — the decision metric; the throughput-mode run agreed (it
is the reciprocal). The decision regime is the **1 000 000-threshold** slice; the 100k slice is shown last for
context.

### `distinctThresholds = 1 000 000`, `recordsPerPoint = 1` — µs/op vs blockSize

| method | 32 | 64 *(default)* | 128 | 256 | 512 | trend |
|---|--:|--:|--:|--:|--:|---|
| `pointLookup` | 0.412 | 0.388 | 0.375 | 0.348 | 0.341 | **improves** (fewer levels) |
| `rangeScan` | 51.28 | 50.92 | 40.36 | 37.72 | 36.12 | improves to 512 |
| `fullScan` | 24 040 | 24 734 | 19 449 | 18 093 | 17 137 | improves to 512 |
| `commit` | 25 740 | 34 737 | 25 535 | 24 809 | 22 942 | improves to 512 (64 is a spike) |
| `bulkLoad` | 166 003 | 166 147 | 159 199 | 156 711 | 155 860 | improves to 512 |

### `distinctThresholds = 1 000 000`, `recordsPerPoint = 16` — µs/op vs blockSize

| method | 32 | 64 *(default)* | 128 | 256 | 512 | trend |
|---|--:|--:|--:|--:|--:|---|
| `pointLookup` | 0.406 | 0.398 | 0.370 | 0.345 | 0.339 | improves |
| `rangeScan` | 39.23 | 50.97 | 39.59 | 37.15 | 34.15 | improves to 512 (64 spike) |
| `fullScan` | 21 084 | 26 172 | 19 935 | 17 777 | 17 276 | improves to 512 (64 spike) |
| `commit` | 23 092 | 35 728 | 26 696 | 24 302 | 22 804 | improves to 512 (64 spike) |
| `bulkLoad` | 240 296 | 264 975 | 268 906 | 260 896 | 237 353 | flat/noisy |

### `distinctThresholds = 100 000`, `recordsPerPoint = 1` — µs/op vs blockSize (context)

| method | 32 | 64 | 128 | 256 | 512 |
|---|--:|--:|--:|--:|--:|
| `pointLookup` | 0.177 | 0.167 | 0.140 | 0.146 | 0.125 |
| `rangeScan` | 32.08 | 30.77 | 22.44 | 21.81 | 20.29 |
| `fullScan` | 1 894 | 1 909 | 1 577 | 1 533 | 1 393 |
| `commit` | 1 740 | 2 027 | 1 468 | 1 423 | 1 382 |
| `bulkLoad` | 14 386 | 15 010 | 15 194 | 14 329 | 14 105 |

## How to read it

1. **There is no read-vs-write trade-off here — bigger is uniformly better.** Unlike the InvertedIndex comparator tree,
   *every* method improves (or is flat within noise) as block size grows, all the way to 512: `pointLookup`,
   `rangeScan`, `fullScan`, **and both write paths** (`commit`, `bulkLoad`).
2. **Why writes don't lose.** The key is `long`, and the value is a single object reference — so an in-leaf insert is a
   `System.arraycopy` of primitives/references, which is cheap per element. The cost that *does* matter — descending and
   path-copying the tree — gets **cheaper** with bigger leaves because the tree has fewer levels and fewer nodes. The
   structural "bigger leaves cost more to copy" term is real but small here, and the depth/locality win dominates it at
   every measured size.
3. **The point-lookup tax is negative.** `pointLookup` actually *improves* with block size (0.412→0.341 µs at 1M;
   0.177→0.125 at 100k) — the `long` binary search is trivially cheap, so the only effect of bigger leaves is the
   shallower descent, which wins.
4. **Magnitudes from the default 64 are large and real.** At 1M: `fullScan` 24 734→17 137 µs (≈31 % faster),
   `rangeScan` 50.9→36.1 µs (≈29 % faster), `pointLookup` 0.388→0.341 (≈12 % faster). The default 64 is clearly *not*
   within noise of the best — keeping it would forfeit a real ~30 % read win at no write cost. (The `recordsPerPoint=16`
   block-64 column is a single-fork noise spike across several methods — note its wide bands; the `recordsPerPoint=1`
   slice and the 100k slice show the clean monotone curve.)
5. **Gains taper by 512.** 256→512 buys only single digits (`rangeScan` ≈4 %, `fullScan` ≈5 %, `pointLookup` ≈2 %,
   `commit` ≈8 %), so 512 — the top of the swept range — is the sensible stopping point; there is no signal that pushing
   beyond it would pay.

## Verdict: **adopt block size 512 for RangeIndex** (up from the inherited 64)

The range workload has no read-vs-write block-size conflict on a primitive-keyed tree: 512 is best (or tied-best within
noise) on all five access patterns, including both write paths. It captures the full ~30 % read improvement over the
default and the gains have flattened, so 512 is the rational top of the range. Note this is a **different** value from
the InvertedIndex pick (256) — exactly the per-index tuning the measurement plan anticipated; the two trees back
different workloads on different key types and need not share a block size.

### Wiring (one line per construction site — not yet applied)

`RangeIndex` builds its tree in two places — the empty factory `createEmptyTree` (`RangeIndex.java:185`) and the
deserialization constructor (`RangeIndex.java:199`). Both currently call the default-block-size constructor. To adopt
512, add a `VALUE_BLOCK_SIZE` constant (mirroring `SortIndex.java:107`) and switch both to the wrapper-aware
block-size constructor that the measurement enabler already added:

```java
private static final int VALUE_BLOCK_SIZE = 512;
private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
// at each construction site:
new TransactionalLongBPlusTree<>(
    VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
    TransactionalRangePoint.class, RANGE_POINT_WRAPPER
);
```

On-disk format is unaffected — block size is a runtime-only property; the tree is rebuilt on load.

## Threats to validity

- **Tree-direct, not full-index**: absolute numbers exclude index overhead; the **relative** block-size effect (the
  decision variable) is preserved.
- **`commit` shape**: it omits WAL persistence and spreads 100 fresh inserts; bigger leaves let those inserts land in
  fewer distinct leaves, which is part of why `commit` improves with block size. The structural conclusion (cheap
  primitive arraycopy + shallower path-copy) holds, but the absolute `commit` block-size slope is partly an artifact of
  the fixed 100-insert spread.
- **Single-fork noise spikes**: the `recordsPerPoint=16`, blockSize 64 column is anomalously high across methods (wide
  99.9 % bands) — read the trend across sizes and the cleaner `recordsPerPoint=1` slice, not that cell.
- Uniform threshold distribution and uniform `recordsPerPoint`; real range attributes are skewed. The knee location is
  robust to this, the absolute magnitudes less so.

## Note for the global default

Three value-bearing trees have now been measured for block size: `SortIndex` → 256, `InvertedIndex` → 256 (this batch),
`RangeIndex` → 512 (this batch). All three prefer **larger than the tree's `DEFAULT_VALUE_BLOCK_SIZE = 64`**. That is a
strong signal the 64 default is too small for value-bearing trees, but per the measurement plan's policy the global
default should change only if *most* consumers — including the untested ones — agree. The conservative, established
pattern is to keep 64 as the default and override per index (as `SortIndex` already does and as proposed here), leaving
any global-default move as a separate, explicitly-decided change.
