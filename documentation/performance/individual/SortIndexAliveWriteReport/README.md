# Sort index — `ALIVE` single-entity write latency report

**Report source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/SortIndexAliveWriteReport.java`

Every number below was produced by running that report (and, for the read-path rows, the unit-level probe described
in §4). When the sort index's first-touch path changes, re-run and update this document so the two never drift
apart. The flat-scaling property the numbers demonstrate is guarded automatically by
`SortIndexRankScalingTest` in `evita_functional_tests`.

## Why this report exists

A sort index answers one question on every insert: *which record does the new one belong after?* Historically that
answer was produced from a **separate derived rank structure** (a cumulative-weight B+ tree in `SortIndexChanges`),
which was `transient` and re-created per transaction. The first write of every transaction therefore rebuilt the
whole structure by inserting **every distinct value** — `O(V log V)` comparisons, each one a potential collation key
computation on a localized attribute.

Cost scaled with `V`, the number of **distinct** values, so it was invisible below ~100 k distinct values and became
a functional defect above it. It is a *latency* defect on a single-entity write, not a throughput nicety, which is
why it is reported as a per-transaction median rather than as a JMH throughput score: JMH's steady-state averaging
tends to hide a cost paid on the first operation of each transaction.

The replacement answers the same question **bucket-locally** from the authoritative inverted index — the anchor is
the greatest lower record id in the value's own bucket, else the last record of the preceding bucket — so no derived
structure exists and nothing is ever rebuilt.

## What is measured

`SortIndexAliveWriteReport` bulk-loads `N` distinct Czech (localized, collation-ordered) titles in `WARM_UP`, goes
live, then times individual single-entity upsert transactions and reports the **median of the second half** of them
(the first transactions pay JIT compilation).

Parameters: `-Dreport.n` (distinct values, default 320 000), `-Dreport.txs` (measured transactions, default 12).

## Results

Measured 2026-07-30 on one machine, both sides in the same session, same corpus and seed. `before` is dev at
`ca6a91b7f`; `after` is the bucket-anchored insertion path.

### Single-entity `ALIVE` write — steady-state median

| distinct values | before | after | change |
|---|---|---|---|
| 320 000 | 1 557 ms | **16.8 ms** | 93x faster |
| 640 000 | 5 404 ms | **23.6 ms** | 229x faster |

The decisive property is not the ratio but the **shape**: doubling the distinct-value count adds ~7 ms after the
change, against ~3.9 s before it. The `after` figures sit at the floor established by an attribution A/B on the same
corpus — the identical schema with `sortable()` removed costs ~15 ms per write — i.e. the sort index has stopped
being the dominant cost of the transaction. The first (JIT-cold) transaction costs ~110 ms rather than seconds.

Bulk import improved as a side effect, since the same rebuild ran per flush: 640 000 entities loaded in 35.7 s
before, 32.3 s after.

### Read path — first `getRecordsEqualTo` after a commit

The read path reached the same rebuild through exactly one route (`computeBlockStart` →
`getRecordsEqualToInternal`), whose only query-path caller is `AttributeExactSorter`. Ordinary attribute filtering is
served by `FilterIndex`, and ordering by a sortable attribute is served from memoized supplier arrays — neither ever
touched the rank structure, so **broad query benchmarks are not expected to move** (the artificial query suite's
run-to-run spread is ±9 %, well above any effect on paths that never used it).

Measured at 320 000 distinct values with the per-transaction helper discarded exactly as the commit path discards
it:

| | before | after |
|---|---|---|
| first read after commit | 677.7 ms | **16.1 ms** (~42x faster) |
| second read (steady state) | 0.129 ms | 0.079 ms |

The residual 16.1 ms on the first read is JIT and collation warm-up on the descent, not a rebuild — the second read
at 0.079 ms is the true steady state. Steady-state reads are not regressed.

## The automated guard, and proof that it works

`SortIndexRankScalingTest` (functional suite, tagged `slow`) measures first-touch write and read cost at 50 000 and
200 000 distinct values inside one JVM and asserts the growth ratio stays below `3.0x`. A **ratio** is asserted
rather than a duration so the test is independent of machine speed and CI load.

The threshold was not guessed — it was placed between two measured regimes, and the test was verified to fail on the
pre-change code (the equivalent of a mutation test for a performance invariant):

| growth for 4x distinct values | before (`ca6a91b7f`) | after | limit |
|---|---|---|---|
| first write after commit | **6.04x** (fails) | 1.18x (passes) | 3.0x |
| first read after commit | **7.59x** (fails) | 0.86x (passes) | 3.0x |

Absolute first-touch cost at 200 000 distinct values tells the same story: 232 ms / 280 ms before, 0.066 ms /
0.043 ms after. The threshold has roughly 2x of headroom on both sides, so ordinary timing noise cannot flip the
verdict in either direction.

If this test ever fails, the sort index has started paying a cost proportional to the number of distinct values on
the first touch of a transaction again — which is the exact defect this work removed.

## Measurement conditions and caveats

- Both sides ran from separate git worktrees with isolated `target/` trees and **without** installing to `~/.m2`, so
  neither side could link the other's classes. A run that resolves `evita_engine` from `~/.m2` instead of the
  reactor measures whichever build was installed last — a `-pl` run without `-am` did exactly that during this work
  and silently reported the baseline twice.
- The machine was not otherwise idle (a Maven reactor build was resident), so absolute values carry ordinary
  wall-clock noise. The comparison is nonetheless sound because both sides ran under the same conditions, and the
  effect is two orders of magnitude — far outside any plausible noise band.
- The corpus is **all-distinct**, so every bucket holds a single record. This deliberately does not exercise the
  multi-record-bucket path; that path's correctness is covered by `SortIndexBucketAnchorParityTest` and its cost is
  bounded by the transaction's diff size rather than the bucket size (the predecessor query is answered from the
  `BitmapChanges` diff layer, never through a merged bitmap).
