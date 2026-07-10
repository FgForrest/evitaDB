# BucketBPlusTreePayloadBenchmark — payload-column refactor A/B

Neutrality gate for generalizing the `TransactionalBucketBPlusTree` leaf's single-record column from a raw `int[]` to
the pluggable `RecordColumn` SPI (`IntRecordColumn` default, `LongRecordColumn` for the global-unique value→entity tree).
The benchmark drives the bucket tree directly (faithful replicas of the inverted / owner-unique hot paths) across both
payload regimes — single-record (`recordsPerValue=1`, the column the refactor touches) and multi-record / overflow
(`recordsPerValue=16`, the lazy bitmap path, untouched).

## Method

Classpath-shadow A/B (no commit / worktree needed): the current pre-refactor engine jar (`int[]` payload) is saved aside
as the baseline; the refactored engine is installed to `.m2` and bundled into the performance uber-jar. The **same**
compiled benchmark runs against both engines — refactored = uber-jar alone; baseline = `baseline-engine.jar` first on
the classpath, shadowing the refactored engine classes. The benchmark uses only the bucket tree's **public** API (which
the refactor left byte-identical), so it links against either engine — a NoSuchMethodError would itself flag an API
break.

`gc.alloc.rate.norm` (allocation per op) is **deterministic** and is the regression oracle; `avgt` is the secondary
(noisy) signal. JDK 21 runtime both sides.

Config: `-p blockSize=256 -p distinctValues=100000 -p recordsPerValue={1,16} -bm avgt -prof gc`, at `-f 2` (full matrix)
and `-f 5 -wi 3 -i 5` (single-record confirmation, 25 measurements/op).

## Result — NEUTRAL (gate passed)

### Allocation (the oracle) — per-operation paths byte-identical

5-fork, `recordsPerValue=1`, `B/op`:

| op | refactored | baseline | delta |
|---|---|---|---|
| pointLookup | 272.001 | 272.001 | identical |
| mutate (add+remove) | 535.971 | 535.971 | identical |
| fullScan | 344.613 | 344.610 | identical (noise) |
| rangeScan | 416.009 | 416.009 | identical |
| commit | 436215.4 | 434608.0 | +1607 B/op (see below) |
| bulkLoad | 29629992 | 29597530 | +32462 B/op (see below) |

The per-operation light paths (point lookup, the most refactor-sensitive single-slot `mutate`, the cursor scans) are
identical to the byte — the `IntRecordColumn` wrapper adds **no** per-query or per-record allocation, and the backing
`int[]` is byte-for-byte what the leaf held before.

The two heavy ops carry one fully-explained delta: **one ~16-byte `IntRecordColumn` wrapper object per leaf that is
allocated or copy-on-write decoupled**. `commit` mutates ~100 spread leaves → ~100 × 16 ≈ 1600 B (matches +1607);
`bulkLoad` builds the whole tree (final leaves + split churn) → the +32 KB likewise tracks the leaf count. This is the
same per-leaf wrapper cost the **key** column already pays (`ValueColumn.duplicate()`), it occurs only on leaf
construction (never per-query, never per-record), and per-record retained footprint is unchanged. It is the irreducible
price of the abstraction and is what enables the `long` payload (global-unique) and the wider tree consolidation.

### Time — within run-to-run noise

avgt µs/op, 5-fork, `recordsPerValue=1`:

| op | refactored | baseline | delta |
|---|---|---|---|
| pointLookup | 0.113 | 0.114 | −0.9% (faster) |
| bulkLoad | 6645.9 | 6729.6 | −1.2% (faster) |
| fullScan | 88.92 | 88.67 | +0.3% |
| rangeScan | 1.323 | 1.308 | +1.1% |
| mutate | 0.188 | 0.185 | +1.6% |
| commit | 51.24 | 50.39 | +1.7% |

At `-f 2` the deltas were larger and one-directional (mutate +9 %, bulkLoad +4 %, commit +3 %); at `-f 5` they collapse
to ±1–2 % with **mixed signs** (point lookup and bulk load become *faster* refactored). Mixed direction at the noise
floor is run-to-run JVM session variance, not the refactor — the same pattern the int-keyed B+ tree dedup A/B documented
(a 2-fork +11 % `fullScan` that collapsed to +1.1 % at 5 forks).

## Conclusion

The `int[] records` → `RecordColumn` generalization is allocation- and time-neutral: zero per-record and per-operation
allocation change, time within noise, and the sole cost is a ~16-byte wrapper per leaf on tree construction — identical
in nature to the existing key-column overhead. The refactor is safe to keep, and the `LongRecordColumn` payload it
unlocks can back the global-unique value→entity tree.

Regenerate with:
`java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main 'io\.evitadb\.spike\.BucketBPlusTreePayloadBenchmark' -p blockSize=256 -p distinctValues=100000 -p recordsPerValue=1 -f 5 -wi 3 -w 1 -i 5 -r 1 -bm avgt -prof gc`
(baseline: prepend the saved pre-refactor `evita_engine` jar to the classpath).
