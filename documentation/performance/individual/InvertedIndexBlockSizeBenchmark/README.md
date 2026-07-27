# InvertedIndex — B+ tree leaf block-size measurement

**Benchmark source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/InvertedIndexBlockSizeBenchmark.java`

The tables below are the authoritative record — raw JMH CSV output is not retained. Re-run the benchmark (command
below) to regenerate the numbers, and update this document when the tree's read or write path changes so the two never
drift apart.

## Why this benchmark exists

`InvertedIndex` (and therefore every `FilterIndex`) is backed by a comparator-ordered
`TransactionalObjectBPlusTree` keyed by the normalized attribute value, with a `ValueToRecord` bucket (the record-id
set for that value) as the tree value. The tree's leaf block size was an **inherited guess of 64** — the tree default.
`SortIndex` already measured its own way to `256` (for a read-dominated ORDER BY full sweep), but the filter workload
is a different mix: **point-lookup + bounded-range + write heavy**, not high-N sequential scans. Block size is a
read-vs-write trade-off — bigger leaves give fewer, more sequential scans but a larger array to copy on every in-leaf
insert and every commit path-copy — so the optimum for this index is its own question, answered here on data instead
of a guess.

## What is measured

The benchmark exercises the **real value type** (`ValueToRecordBitmap`, a transactional producer) through the tree's
wrapper-aware block-size constructor, so the `commit` measurement includes the genuine per-value layer merge — not a
stand-in. It measures the tree directly (faithful access-pattern replicas of the InvertedIndex hot paths) rather than
the full index, because block size is a property of the tree; the surrounding index logic (formula building,
normalization) is block-size invariant.

| method | mirrors | what it stresses |
|---|---|---|
| `pointLookup` | `getRecordsEqualTo` | in-leaf binary-search depth (`log₂ blockSize`) |
| `rangeScan` | `getSortedRecords(from,to)` | bounded forward leaf-walk locality (2 000-bucket window) |
| `fullScan` | `getValueToRecordBitmap` / histogram | full ordered sweep locality |
| `bulkLoad` | deserialization / restore | cumulative in-leaf arraycopy + node splits on build |
| `commit` | `addRecord` + transaction commit | `O(touched leaves · blockSize)` path-copy on commit (100 spread inserts) |

### Parameters
- `blockSize ∈ {32, 64, 128, 256, 512}` — the variable under study.
- `distinctValues ∈ {100 000, 1 000 000}` — number of buckets (block size matters at scale).
- `recordsPerValue ∈ {1, 16}` — single-record (high-cardinality attr) vs moderate fan-out.

## How to run

```bash
# build the benchmarks jar (performance module is outside the default reactor)
mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests package

# full matrix (both modes, two forks — the publishable configuration)
java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
  'io\.evitadb\.spike\.InvertedIndexBlockSizeBenchmark' \
  -f 2 -foe true -rf csv -rff invertedindex_blocksize.csv
```

Pin a slice with `-p blockSize=64,256 -p distinctValues=1000000 -p recordsPerValue=1`; latency only with `-bm avgt`.
On a shared machine, `rm -f /tmp/jmh.lock` (and `-Djmh.ignoreLock=true`).

## Results

JDK 21.0.11 (OpenJDK 64-Bit Server VM), JMH 1.37, **2 forks**, 3 warmup + 5 measurement iterations × 2 s, single
thread. Numbers below are `avgt` **µs/op** (lower is better) — the decision metric; the throughput-mode run agreed (it
is the reciprocal). The decision regime is the **1 000 000-value** slice (block size barely matters at 100k); the
100k slice is shown last for context.

### `distinctValues = 1 000 000`, `recordsPerValue = 1` — µs/op vs blockSize

| method | 32 | 64 *(default)* | 128 | 256 | 512 | trend |
|---|--:|--:|--:|--:|--:|---|
| `pointLookup` | 0.508 | 0.527 | 0.533 | 0.539 | 0.523 | **flat** (~0.52) |
| `rangeScan` | 43.79 | 38.88 | 31.31 | 28.66 | 27.18 | improves, knee ≈256 |
| `fullScan` | 21 498 | 19 441 | 15 447 | 14 118 | 13 035 | improves, taper after 256 |
| `commit` | 70 172 | 84 846 | 74 145 | 74 336 | 72 006 | flat/noisy (64 is a spike) |
| `bulkLoad` | 136 678 | 131 423 | 136 584 | 134 003 | 142 968 | flat (within ±) |

### `distinctValues = 1 000 000`, `recordsPerValue = 16` — µs/op vs blockSize

| method | 32 | 64 *(default)* | 128 | 256 | 512 | trend |
|---|--:|--:|--:|--:|--:|---|
| `pointLookup` | 0.508 | 0.543 | 0.540 | 0.535 | 0.521 | flat |
| `rangeScan` | 43.81 | 39.18 | 31.57 | 28.88 | 26.82 | improves, knee ≈256 |
| `fullScan` | 22 296 | 20 526 | 15 815 | 13 858 | 13 001 | improves, taper after 256 |
| `commit` | 78 141 | 98 362 | 83 803 | 89 208 | 81 419 | flat/noisy |
| `bulkLoad` | 171 201 | 165 313 | 167 221 | 164 041 | 162 838 | flat |

### `distinctValues = 100 000`, `recordsPerValue = 1` — µs/op vs blockSize (context)

| method | 32 | 64 | 128 | 256 | 512 |
|---|--:|--:|--:|--:|--:|
| `pointLookup` | 0.224 | 0.211 | 0.192 | 0.198 | 0.195 |
| `rangeScan` | 16.30 | 11.60 | 9.43 | 7.71 | 7.45 |
| `fullScan` | 1 096 | 881 | 685 | 548 | 507 |
| `commit` | 6 397 | 7 130 | 8 863 | 6 891 | 6 051 |
| `bulkLoad` | 12 059 | 11 770 | 11 281 | 12 179 | 12 192 |

## How to read it

1. **The point-lookup tax never materializes.** `pointLookup` is flat across the whole 32→512 range (~0.52 µs at 1M,
   ~0.2 µs at 100k) — the comparator binary search (`log₂ blockSize`: 5→9 compares) is lost in the noise of the
   cache-miss-bound root→leaf descent. Block size is unconstrained from above by reads.
2. **The write penalty never materializes either.** `commit` and `bulkLoad` are flat-to-noisy across block size — they
   do **not** grow with bigger leaves the way a pure structural model would predict. As §6 of the measurement plan
   warned, the block-size-sensitive path-copy term is **diluted** by the block-size-invariant per-value transactional
   layer merge, which dominates a real commit. (The `commit` 64-column spike at 1M is a single-fork noise artifact, not
   a trend — note its wide 99.9 % error band.)
3. **The reads that matter improve, and the knee is ~256.** `rangeScan` and `fullScan` improve monotonically with block
   size. From the default 64 to 256 the gain is real and large — `fullScan` 19 441→14 118 µs (≈27 % faster),
   `rangeScan` 38.9→28.7 µs (≈26 % faster), both far outside the error bars. Beyond 256 the point/bounded-range gains
   taper to single digits (`rangeScan` 256→512 only ≈5 %); only the full-ordered sweep keeps gaining (`fullScan`
   256→512 ≈8 %).
4. **So the trade-off here is one-sided** (reads improve, writes do not regress), which pushes the optimum well above
   the default. The honest verdict is **not** "keep 64" — 64 leaves a real ~25 % point/range read win on the table.

## Verdict: **adopt block size 256 for InvertedIndex** (up from the inherited 64)

For the filter workload — point-lookup + bounded-range dominant, write-heavy — `256` is the knee: it captures
essentially all of the point/range read gain, the point-lookup cost is flat, and neither `commit` nor `bulkLoad`
regresses. It is the largest size before the only remaining benefit is full-ordered-scan locality (which is the
`SortIndex` ORDER BY workload, not the filter workload), and it lines up with `SortIndex.VALUE_BLOCK_SIZE = 256` —
a consistent, already-validated value for a comparator-ordered object tree.

`512` is marginally better for scan-dominated use and never worse here, but the extra leaf array (larger arraycopy on
every live in-leaf insert, more per-leaf object-reference footprint) is not justified for a write/point-heavy index
when the point/range knee has already flattened by 256.

### Wiring (one line per index — not yet applied)

`InvertedIndex.createEmptyTree` (`InvertedIndex.java:182`) currently calls the default-block-size constructor. To adopt
256, add a `VALUE_BLOCK_SIZE` constant (mirroring `SortIndex.java:107`) and switch to the wrapper-aware block-size
constructor that the measurement enabler already added:

```java
private static final int VALUE_BLOCK_SIZE = 256;
private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
// in createEmptyTree(...):
return new TransactionalObjectBPlusTree<>(
    VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
    Comparable.class, ValueToRecord.class, VALUE_TO_RECORD_WRAPPER, comparator
);
```

On-disk format is unaffected — block size is a runtime-only property; the tree is rebuilt on load.

## Threats to validity

- **Tree-direct, not full-index**: absolute numbers exclude index overhead; the **relative** block-size effect (the
  decision variable) is preserved.
- **`commit` dilution**: it omits WAL persistence and uses pure inserts (not value-upserts), isolating the structural
  path-copy. The real per-value merge term dilutes the block-size effect further — so treat the (already negligible)
  `commit` block-size penalty as an upper bound on the real write regression.
- **Single-fork noise spikes**: a few cells (notably `commit` at blockSize 64) carry wide 99.9 % bands; read the trend
  across sizes, not an individual cell. The second fork already tightened the read curves, which are stable.
- Uniform key distribution and uniform `recordsPerValue`; real attributes are skewed. The knee location is robust to
  this, the absolute magnitudes less so.
