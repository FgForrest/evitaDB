---
title: The range counting kernel drives its merge from chunk buckets, and a finished chunk crosses into the bitmap writer whole
date: 2026-09-18
updated: 2026-09-18 20:30
status: proposed
kind: optimization
issues: [1604]
prs: []
areas: [evita_engine/core/query/algebra/base, evita_roaring_bitmap, evita_test/evita_performance_tests]
supersedes: []
superseded-by: []
relates: [2026-09-18-range-index-counting-kernel]
---

# The range counting kernel drives its merge from chunk buckets, and a finished chunk crosses into the bitmap writer whole

## Why

[2026-09-18-range-index-counting-kernel](2026-09-18-range-index-counting-kernel.md) closes by naming what was
left: *"What remains is therefore per-operand overhead rather than the counting loop, which is what a further
optimisation has to attack, and why the production figure lands at 2.24× where the mid-range reaches 5.52×."*

This record is that further optimisation. It also retires `SPARSE_FACTOR`, the one constant that record flags as
a reasoned estimate rather than a swept crossover.

### Previous state

`computeNarrow` swept the **whole** cursor array twice per chunk — once to find the lowest chunk any cursor still
sat in, once to drain that chunk — and never compacted dead cursors out. At the measured production shape
(k = 7,602 operands, N = 29,159 endpoints, 14 chunks) that is `2 × 7,602 × 14 = 212,856` cursor visits to perform
29,159 counter increments: **7.3 visits per record id actually moved**, each a pointer chase from an array slot to
a `Cursor` object to its own `int[]` batch buffer, over roughly a megabyte of small scattered objects carrying
114 KB of data.

Emission then made a round trip the writer immediately undid. The kernel gathered survivors into a `long[1024]`
word bitmap purely to restore ascending order, decomposed it to ints, and `ConstantMemoryContainerAppender.add(int)`
set the very same bits again in a word buffer of exactly the same shape.

## Options considered

### Option A — bucket the cursors, flatten their state, hand the chunk over whole (chosen)

Cursors are *filed* under the chunk their next id belongs to and re-filed when they cross into a later one, so
opening a chunk is one array read plus an intrusive list walk. Per-cursor state becomes parallel primitive arrays
with one shared batch arena. The finished chunk is handed to the writer as words.

- Driving the merge costs `k` filings plus one re-filing per chunk a cursor actually spans — about 15,000
  operations at the production shape against ~213,000.
- Steady-state allocation for cursor state falls to one `BatchIterator` per operand and nothing else.
- Needs two additive methods on the vendored fork, neither of which exposes a container.

### Option B — set the emission bit during the scatter instead of appending to a touched list (declined)

Strictly tidier: duplicates collapse for free, the capacity bound disappears, and the map is already in the
writer's layout. **It was built this way first.**

- **Rejected because:** measured **5× more expensive in the scatter loop's own time** — 12 µs → 69 µs at k = 64,
  N = 29,159, by JMH sampled stacks on both arms of one box. `words[low >>> 6] |= 1L << low` is a random
  read-modify-write with a load-use dependency where `touched[n++] = low` is a sequential store the store buffer
  absorbs. Across the k sweep it cost ~300 µs at *every* k — the signature of a per-record-id cost, since N is
  held constant — and turned the whole change into a net regression below k ≈ 2,000. Revisit only with a
  measurement showing the scatter loop is no longer the place that cost lands.

### Option C — container-level dispatch inside the roaring fork (declined, again)

Reach past `BatchIterator` to the containers themselves: a run-container prefix scan, bit-sliced adders over
dense containers.

- **Rejected because:** unchanged from the previous record — `getContainerPointer()` is typed with a
  package-private interface and widening that is a separate decision belonging with the container kernels. The two
  additive methods this record does add are deliberately the narrow alternative: they widen an existing contract
  (`nextBatch` gains a slice bound, the writer gains a word-level append) and leak no container type.

### Option D — a bit-sliced index over the endpoints (declined)

`SliceZ`-shaped: decompose values into 64 bit-slices per 65,536-row block, so the work per block is bounded by the
**bit width of the value** rather than by the number of distinct endpoints — which is exactly the cost this record
attacks, made constant by construction.

- **Rejected because:** it requires owning an immutable, rebuilt-on-write layout, and `RangeIndex` is a
  `TransactionalLongBPlusTree` mutated per upsert. The trade is not query cost but immutable-build versus
  transactional-mutate, which is a far larger decision than a kernel swap. Worth revisiting only if the endpoint
  count, rather than the per-operand constant, becomes the dominant term.

## Decision

Take Option A. Keep the touched list the scatter writes, because Option B measured worse where it mattered most.

## Key technical details

- **A cursor only ever moves to a higher chunk**, and chunks are consumed in ascending order, so a cursor filed
  while chunk `c` drains always lands in a bucket not yet visited. This is what makes the bucket table safe to
  clear as it is read, and it is the invariant to check first if the walk ever loops.
- **The bucket table is sized from each operand's `last()`, not from its first value.** Sizing it from first
  values is the obvious mistake: a cursor that starts low and ends high would be filed past the end of the table.
- **`TOUCHED_CAPACITY` is a capacity bound, not a crossover.** While the list fits, walking it is unconditionally
  cheaper than scanning the chunk's span — at most 8,192 reads against up to 65,536 — so there is nothing to
  sweep. The span scan is reached only on overflow, and a chunk that overruns the bound is by construction denser
  than one write per eight offsets, which is the chunk a span scan handles well. This is what replaces
  `SPARSE_FACTOR`, and it is why the replacement needs no measurement of its own.
- **The word bitmap is cleared on the way *in*.** Nothing has to tidy up after the last chunk, and the pooled
  array carries no cleanliness contract — unlike the counters, which every emission must leave fully zeroed.
- **The two fork additions are recorded in `evita_roaring_bitmap/UPSTREAM_SYNC.md`** under *Local API additions*,
  with the note that they must be re-applied on re-sync. Upstream has no counterpart; there is nothing to
  contribute back.
- **Pooled cursor state has retention caps.** The arrays are sized by the computation that borrowed them and never
  shrink, so one outsized query would otherwise park its arrays in the pool for the JVM's lifetime.

## Verification

- **Vendored fork** — `evita_roaring_bitmap`, **17,915 tests, 0 failures**. This is the suite that covers the
  changed `BatchIterator` / `ContainerBatchIterator` / writer paths directly.
- **Kernel and index** — `RangeCountKernelTest`, `RangeIndexQueryOracleTest`, `RangeIndexTest`,
  `RangeFormulaCacheKeyProbeTest`: **109 tests, 0 failures**, including the independent counting reference that
  shares no code with any kernel, the brute-force interval-scan oracle, randomised operand families, counter-width
  selection and the pooled-scratch concurrency test.
- **Full functional suite** — **24,336 tests run, 0 failures**, 45 skipped. Three errors are environmental and
  unrelated: one needs a Docker environment absent from the box, two are Armeria TLS session timeouts on loopback
  in REST CDC/streaming tests.
- **A/B** (`RangeCountKernelBenchmark`, JDK 21.0.12, 24 cores, 2 forks × 8 iterations, `-prof gc`, box asserted
  quiet by a `/proc/stat` busy measurement rather than load average). N held at 29,159 so only the operand count
  varies. `frozenBaselinePair` is unchanged in both arms and drifted 0.94×–1.06×, which bounds the noise:

| k | before | after | speed-up | allocation |
|---|---|---|---|---|
| 4 | 424.7 µs | 288.0 µs | 1.47× | 1.13× less |
| 64 | 500.5 µs | 365.6 µs | 1.37× | 2.84× less |
| 256 | 685.4 µs | 562.6 µs | 1.22× | 3.63× less |
| 1,024 | 1,294.1 µs | 1,115.8 µs | 1.16× | 2.74× less |
| 7,602 | 3,066.3 µs | 1,395.1 µs | **2.20×** | 2.02× less |

  End to end through `RangeCountFormula` the same sweep reads 1.51× / 1.34× / 1.19× / 1.12× / 2.15×.

  **The production shape gains most, which is the point.** Stacked on the previous record's 2.24× against the
  deleted `JoinFormula`/`DisentangleFormula` pair, the production shape now stands at roughly 4.9× against that
  pair rather than 2.24×.

## Consequences & open follow-ups

- **`SPARSE_FACTOR` is gone**, and with it the previous record's open follow-up that it was never swept.
- **The fork now carries two evitaDB-only methods.** Every re-sync must re-apply them; the ledger says so, and the
  `roaring-bitmap-sync` skill reads the ledger.
- **The scatter loop is now the cheap part and emission is the expensive one** — measured at k = 64 as ~154 µs
  emission, ~68 µs container materialisation inside the writer, ~12 µs scatter, ~27 µs cursor refills. A future
  optimisation should aim there, not at the counting.
- **Container materialisation inside the writer did not move** (~68 µs at k = 64, identical in both arms) and is
  now a visible share of the total. `chooseBestContainer` scans the full 1,024-word buffer and converts to an
  array container; for a chunk that will end up sparse that scan is mostly wasted. Not attempted here.
- **The `int` kernel still has no benchmark**, deliberately — no fixture reaches 32,768 operands because no
  production shape does. It remains held to the same counting reference by the test suite.

## Related work

- [2026-09-18-range-index-counting-kernel](2026-09-18-range-index-counting-kernel.md) — the kernel this optimises,
  and the record that identified per-operand overhead as what was left.
- [2026-09-10-simd-vector-api-feasibility](2026-09-10-simd-vector-api-feasibility/README.md) — the scatter this
  record leaves alone is the loop that analysis found unvectorisable; the min-reduction it *removes* was the part
  that would have vectorised.
