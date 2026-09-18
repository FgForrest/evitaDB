---
title: The range index computes its signed multiplicity in one counting pass, and the JoinFormula/DisentangleFormula pair is deleted
date: 2026-09-17
updated: 2026-09-18 07:15
status: proposed
kind: optimization
issues: [1539, 1546]
prs: []
areas: [evita_engine/core/query/algebra/base, evita_engine/index/range, evita_test/evita_performance_tests]
supersedes: []
superseded-by: []
relates: [2026-09-10-simd-vector-api-feasibility]
---

# The range index computes its signed multiplicity in one counting pass, and the JoinFormula/DisentangleFormula pair is deleted

## Why

`RangeIndex` answered every range query by building `DisentangleFormula(JoinFormula(plus), JoinFormula(minus))`.
That pair computes a *signed multiplicity*: a record survives when it occurs in more `starts` bitmaps than `ends`
bitmaps over the scanned prefix (or the reverse over a suffix). `JoinFormula` reached that answer by materializing
a duplicate-carrying array in a k-way merge, and `DisentangleFormula` then cancelled the duplicates in a second
merge. The duplicates were never an output — only an encoding of the counts.

### Previous state

Per computation the pair paid a `PriorityQueue` poll-and-offer per element with a virtual call each, over batch
iterators allocated **per operand**: `new int[256]` regardless of how many record ids the operand actually held.
`RangeIndex` additionally wrapped every point bitmap in a `ConstantFormula`, collected them into `LinkedList`s and
streamed them back out to `Bitmap[]` twice before the formulas were even constructed.

## Options considered

### Option A — per-chunk counting kernel with sparse emission (chosen)

One formula, `RangeCountFormula`, carrying both operand families as `Bitmap[]`. `RangeCountKernel` walks the
inputs at 65 536-value chunk granularity, scatters `±1` into a pooled signed `short[65536]`, and emits per chunk
by whichever of two paths is cheaper: walking the list of offsets the scatter actually wrote, or scanning the
touched span.

### Option B — bit-sliced counting over roaring bitmaps (declined)

Carry each family's count as `B = ceil(log2(k+1))` binary planes of roaring bitmaps; add an operand with a ripple
of `XOR`/`AND` across planes, compare the two plane sets with a borrow chain. Every operation is a container-native
set operation, so the cost should follow the containers touched rather than the chunk, and no dense scratch array
is needed.

- **Rejected because:** measured **2.6× slower** than Option A at the production shape (19,764 vs 3,746 µs/op) and
  allocating **87.6 MB/op** — 4.8× *more* than the baseline it was meant to beat. `PersistentRoaringBitmap` set
  operations are immutable, so each of the ~200,000 plane operations (13 planes × 15,205 operands) allocates a
  fresh bitmap. "Container-native and density-independent" says nothing about allocation, which is the term that
  dominated. Revisit only for genuinely dense operands (`BitmapContainer`s throughout), where a plane step is a
  word-parallel pass and the scatter has no comparable advantage.

### Option C — keep the merge, size its buffers to the operand (declined)

The pair allocated 15.6 MB of `int[256]` buffers to process 233 KB of record ids. A three-line change sizing each
buffer to its operand would remove almost all of it.

- **Rejected because:** measured. Cutting allocation **4.24×** (18.3 MB → 4.3 MB per op) bought **1.11×** in time.
  Allocation was not the bottleneck, so the cheap fix does not substitute for the reformulation. Worth stating
  explicitly because it is the obvious first thing a reader will propose.

### Option D — XOR/parity fold (declined)

Each range contributes one start and one end, so `count_start − count_end ∈ {0,1}` and the whole computation
collapses to an XOR-fold of every operand — no counters at all.

- **Rejected because:** unsound under the class contract. `RangeIndex`'s javadoc forbids only *shared borders*, so
  one record may hold overlapping ranges: `(2,15)` and `(5,20)` at `t=10` give `count_starts=2, count_ends=0`, so
  `>` says present while parity says absent. No production feeder can currently produce that shape
  (`FilterIndex.addRange` consolidates through `Range.consolidateRange` first), which is exactly why it is
  dangerous: parity would pass every existing test and every current caller, and break the first time the
  invariant moved. Revisit only if the index itself starts enforcing at-most-one-active-range-per-record.

## Decision

**Chosen: Option A.** It is the only option measured faster than the code it replaces across the whole operand
range, and the only one whose advantage grows with the shape production actually exhibits.

The sparse emission path is the larger half of the win and was **not** in the original design. It exists because
the measured id distribution is sparse within a chunk: on the production operand dump a chunk holds ~1,325 record
ids inside a span of nearly 65,536, so a span scan reads ~50× more counters than were ever written. Walking the
touched offsets instead is ~28× less work there, against the ~8–16× a vector scan of the same span would buy —
**so the algorithmic fix comes before the lanes**, which inverts the order `#1546` assumed.

### And the four range queries collapse onto one prefix count

With the counting kernel in place, `getRecordsEnvelopingInclusive` and `getRecordsWithRangesOverlapping` were
still doing the old positional work *around* it: `materializeRanges()` copied every threshold point into an array
on every call, `RangeLookup` binary-searched that array, a prefix **and** a suffix family pair were collected, two
`RangeCountFormula`s were intersected by an `AndFormula`, and a boundary `OR` put back the point the search had
landed on.

None of it is necessary. A range's start never exceeds its end and **both** endpoints are indexed here, so a range
whose end is already behind the queried point necessarily has its start behind it too. Subtracting ends from
starts over one ascending prefix therefore cancels exactly the ranges that are over, and leaves each still-matching
range counted once:

| query | plus family | minus family | selects |
|---|---|---|---|
| `getRecordsTo(t)` | starts at key ≤ t | ends at key ≤ t | `a ≤ t < b` |
| `getRecordsEnvelopingInclusive(t)` | starts at key ≤ t | ends at key **< t** | `a ≤ t ≤ b` |
| `getRecordsWithRangesOverlapping(f, t)` | starts at key ≤ t | ends at key **< f** | `[a,b] ∩ [f,t] ≠ ∅` |

All three are now one call to `createPrefixCountFormula(startsBound, startsInclusive, endsBound, endsInclusive)`
— a single forward walk that **stops at the queried point** instead of scanning the index end to end. Deleted with
the old shape: `materializeRanges`, `RangeLookup` and its binary search, `collectsStartsAndEnds`, the `AndFormula`,
the boundary `OR` and the `between` union. The strict ends bound is what absorbs the boundary case the `OR` was
there to patch.

`getRecordsFrom` is deliberately **not** routed through it. Per record `E(≥t) − S(≥t)` equals `S(<t) − E(<t)`, so
it could be expressed as `(t, false, t, false)` — but at `Long.MIN_VALUE` the prefix form collects nothing and
short-circuits to `EmptyFormula`, where the suffix form returns a zero-valued `RangeCountFormula` carrying the
index id. Same records, different formula identity, and that identity is read as a staleness token. The query is
already a single counting pass and has no production caller, so the change would be churn against a real risk.

## Key technical details

- **Semantics, unchanged:** `result = { v : count(plus, v) − count(minus, v) > 0 }`.
- **Counter width is `short`, and intermediate overflow is deliberately allowed.** Two's-complement addition is
  exact modulo 2^16 regardless of the order operands are applied in, so an intermediate that overflows cancels
  back correctly; only the *final* difference must be representable, and it is bounded by the number of disjoint
  ranges one record holds in one chunk. `byte` was rejected: after `Range.consolidateRange` a daily availability
  calendar yields hundreds of disjoint ranges per record, and a `byte` wraps back to *positive* at 256, so a sign
  check cannot even detect it.
- **The sparse path records every scatter write, duplicates included, and needs no visited-set.** The first visit
  to an offset zeroes its counter, so a later duplicate reads zero and contributes nothing — which also clears
  negative counters that an "only clear what was emitted" pass would leave to poison the next chunk. Building the
  list by appending only on a zero-to-nonzero transition would *not* be safe, because `+1, −1, +1` revisits zero.
- **Output goes through a 1 024-word bitmap, not straight to the writer**, because the touched list is unordered
  (scattered operand by operand) while `RoaringBitmapWriter` requires ascending input.
- **`getOperationCost()` = 1462 is derived, not chosen.** The pair estimates `2560*(Np+Nm) + 2130*Np` =
  211,402,750 units for the production shape and takes 7,840.5 µs; this formula takes 3,161.9 µs, so at the same
  model scale it should estimate 85,254,047 units over 58,318 elements. This constant drives plan ranking and
  cache admission, so a guessed value mis-ranks silently. (Those two timings are the run that was mislabelled
  "replayed" in an earlier version of the table below — the *measurement* was sound and is of exactly the
  58,318-element shape the arithmetic uses; only its label was wrong, so the constant stands.)
- **`FormulaCloner` lost its `DisentangleFormula` branch, and the hazard went with it.** That guard existed
  because the formula held two *positional inner formulas* which `FormulaDeduplicator` could unify down to one
  child. `RangeCountFormula` holds bitmap arrays and has no inner formulas, so there is nothing to collapse; the
  `disentangle(X, X) = ∅` case it also covered is now plain arithmetic.
- **`withoutEmpty` tests `isEmpty()` where the old code tested `instanceof EmptyBitmap`.** A threshold point
  legitimately carries an empty `TransactionalBitmap` on one side, and the old test let it through as an operand.
  Results are identical; formula *structure* can differ, and therefore so can the cache key.

## Verification

- `RangeCountKernelTest` — 11 cases, 0 failures. Both kernels are byte-identical to an independent `TreeMap`-based
  reference across hand-written edge cases and **1,460 randomised operand families** (1,000 dense small-id, 400
  sparse wide-id spanning ~5 M ids, 60 at 250 operands). A `Counterfactual` case asserts the oracle itself
  discriminates a strictly-positive count from a cancelled one, so a green run cannot mean both sides computed the
  same constant. Before `JoinFormula`/`DisentangleFormula` were removed, the same suite confirmed both kernels
  byte-identical to *them*.
- `RangeIndexTest`, `FormulaClonerTest`, `FormulaPlanVisitorTest` — **129 tests, 0 failures** against the wired
  `RangeIndex`, including transactional add/remove/commit/rollback, version identity and structural sharing.
- **A/B** (`RangeCountKernelBenchmark`, JDK 21.0.12, Zen 5, 1 fork, `-prof gc`, box asserted quiet by the runner's
  own pre-flight). N held at 29,159 so only the operand count varies:

| k | replaced pair | this change | speed-up |
|---|---|---|---|
| 4 | 1,315 µs | 426 µs | 3.09× |
| 64 | 2,755 µs | 500 µs | 5.52× |
| 256 | 3,417 µs | 694 µs | 4.92× |
| 1,024 | 4,871 µs | 1,284 µs | 3.79× |
| 7,602 | 7,405 µs | 3,300 µs | 2.24× |

  Allocation at the production shape falls from **18,280,650 B/op to 2,222,373 B/op**.

  Every row above is the **generated** fixture, whose operand count, endpoint total and id span come from the
  census. An earlier version of this table carried a sixth row labelled "replayed production operands"
  (7,841 µs → 3,162 µs, 2.48×). That row was withdrawn: the dump path travelled to the forked JVM as
  `-Drange.operands` through `-jvmArgsAppend`, and `ArtificialTestRunner` — the benchmark jar's `Main-Class` —
  set `jvmArgsAppend` on its own builder, which overrides the parent's rather than merging, so the property
  never arrived. The fixture then fell back to the generated shape without saying so, making the row a second
  measurement of the `k7602` row rather than an independent one. Both halves are fixed: the runner merges the
  caller's fork arguments, and the benchmark takes the dump path as its `@Param` — which reaches the fork, is
  recorded in the result JSON, and throws rather than substituting when it cannot be read.

**Read the ratio, not the absolute microseconds.** Repeated runs put the same comparison between **2.27× and
2.54×**; the absolute figures move by ~10 % with the state of the machine, while the ratio is stable because both
arms are measured under the same conditions. One re-measurement taken while a test suite was still winding down
produced a 2× error on one shape and was discarded — the run that produced the table above asserted zero competing
processes before starting.

- **A/B on replayed operands from a second production catalog.** A different e-commerce catalog (21 collections,
  9,277 attribute range indexes with more than two threshold points, 2,070 price-list/currency indexes) was
  censused the same way, and the operand families its `validity` indexes actually build were dumped and replayed.
  Each dump is cross-checked against the `RangeCountFormula` the index itself constructs — same operand counts,
  same totals — and the probe aborts on any mismatch. The `k7602` generated control ran in the same session and
  reproduced the table above (2.25×), so these rows sit on the same scale:

| replayed family | k+ | k− | N | replaced pair | this change | speed-up | allocation |
|---|---|---|---|---|---|---|---|
| `Product.validity` | 119 | 51 | 1,574 | 40.4 µs | 15.7 µs | 2.56× | 90,864 → 39,880 B/op |
| `Product.validity` | 486 | 41 | 3,234 | 87.1 µs | 35.1 µs | 2.48× | 197,920 → 94,296 B/op |
| `Product.validity` | 857 | 125 | 7,626 | 303.2 µs | 66.6 µs | **4.55×** | 422,841 → 177,719 B/op |
| `PriceList.validity` | 843 | 704 | 3,434 | 171.1 µs | 60.6 µs | 2.83× | 325,305 → 219,488 B/op |

  Planning stays negligible throughout — `rangeCountPlanOnly` costs 0.5–5.2 µs against 16–67 µs of computation,
  so the formula remains cheap to build and pays only when computed, which is the property the shape was chosen
  for.

**`frozenBaselinePair` is a conservative stand-in, not an exact replica.** It keeps a copy of the replaced
algorithm inside the benchmark so this table can be re-measured rather than only cited, but it sizes each batch
buffer to its operand where the original allocated a flat `int[256]`. Measured on a quiet box it runs at
6,996–7,097 µs against the deleted pair's 7,405–7,841 µs, i.e. **5–11 % faster**, so a speed-up measured against
it (2.27–2.29×) *understates* the gain over the code that was actually removed.

**The issue's estimate was 5–20× "from the reformulation alone". The measured figure at the production shape is
2.24–2.48×** across runs; the mid-range reaches 5.5×. The estimate should be read as wrong, not as unmet.

- `RangeIndexQueryOracleTest` — 4 cases over **150 independently generated indexes**, each query cross-checked
  against a brute-force scan of the very intervals the test inserted, probed at every threshold, every threshold
  ±1, and both `Long` borders. The oracle shares no code with the index: it tests the interval predicate directly
  where the index counts over a prefix, so a defect would have to appear identically in both to survive.

  **The counterfactual is the part worth reading.** Flipping the ends bound from strict to inclusive — one boolean
  — turns the oracle red (`getRecordsEnvelopingInclusive(2) disagreed with the brute-force scan over 27 spans`)
  while **all 14 hand-written `RangeIndexTest.RangeQueries` assertions still pass**. Not one of them probes a point
  at which a range *ends*, which is exactly where the collapsed boundary handling does its work. The suite had a
  blind spot precisely there, and it is now closed.

- **A/B of the query shape** (`RangeQueryBenchmark`, JDK 21.0.12, Zen 5, 1 fork, `-prof gc`). The fixture replays
  `validity` spans dumped from a restored production catalog — `Product` (7,267 spans over 932 threshold points)
  and `PriceList` (1,791 spans over 1,633 points) — plus a generated `wide` control at roughly ten times the
  largest range index that catalog holds. `probe` says where the query lands in the index's own threshold
  ordering: `p50+` mid-tree, `p95+` near the end, where the prefix walk is longest and the collapse buys least.
  The baseline is the positional algorithm frozen inside the benchmark, and the fixture refuses to run unless both
  sides compute the identical bitmap on the inputs about to be timed. The runner refuses to start until three
  consecutive three-second windows measure ≥ 85 % CPU idle.

| query | shape | probe | positional | prefix count | speed-up | allocation |
|---|---|---|---|---|---|---|
| enveloping | `Product` | p50+ | 103.7 µs | 34.7 µs | **2.99×** | 237.4 → 91.3 kB |
| enveloping | `Product` | p95+ | 129.0 µs | 70.5 µs | 1.83× | 264.8 → 179.9 kB |
| enveloping | `PriceList` | p50+ | 96.8 µs | 31.4 µs | **3.09×** | 304.7 → 133.0 kB |
| enveloping | `PriceList` | p95+ | 106.4 µs | 75.1 µs | 1.42× | 328.5 → 243.1 kB |
| enveloping | `wide` | p50+ | 3,663.9 µs | 1,184.9 µs | **3.09×** | 4,321.6 → 1,814.3 kB |
| enveloping | `wide` | p95+ | 3,165.1 µs | 2,472.0 µs | 1.28× | 4,024.7 → 3,436.5 kB |
| overlapping | `Product` | p50+ | 127.4 µs | 50.7 µs | **2.51×** | 328.0 → 129.1 kB |
| overlapping | `PriceList` | p50+ | 127.2 µs | 45.3 µs | **2.81×** | 428.2 → 137.1 kB |
| overlapping | `wide` | p50+ | 4,306.2 µs | 1,146.5 µs | **3.76×** | 4,086.8 → 1,686.6 kB |

  **Planning alone** — building the formula without computing it, which every matching index pays during query
  planning whether or not the result is ever needed — falls from 21.2 µs to 6.5 µs on `Product` (3.27×) and from
  496.9 µs to 155.1 µs on `wide` (3.20×), with allocation down 4.2× on the latter. That is the half of the win
  that lands on queries whose formula is discarded by the planner.

  **An accidental repeat measurement anchors the run's variance.** The overlapping window is fixed at the 25th and
  75th percentile thresholds and does not move with `probe`, so its `p50+` and `p95+` rows are the same
  measurement taken in two separate forks. `Product` gave 127.4 µs vs 126.1 µs positional and 50.7 µs vs 51.6 µs
  prefix-count — agreement within 2 %, which is the noise floor this table should be read at.

## Consequences & open follow-ups

- **The workload is the tail, and the tail differs by an order of magnitude between catalogs.** Two production
  e-commerce catalogs were censused, and they disagree about this code path more than any other measurement here:

  | | catalog A | catalog B |
  |---|---|---|
  | formula trees inspected | 388,225 | 11,347 |
  | trees reaching this code | **2** | **215** |
  | k (operands) p50 / p95 / max | 7,602 / — / 7,602 | 15 / 222 / **1,547** |
  | N (operand elements) p50 / max | 29,159 | 45 / 7,626 |
  | which indexes | two `ParameterValue` attribute indexes | 159 indexes, **every one an attribute named `validity`** |

  In catalog A, 92,413 range indexes average 4.08 threshold points and the path is reached twice. In catalog B it
  is ordinary traffic on `Product`, `PriceList`, `AdjustedPricePolicy` and `Voucher` validity — which is the hot
  `attributeInRangeNow` path. **Do not generalise a range-index cost model from one catalog.** Optimising the mean
  is still pointless in both, but the shape of the tail is a property of the data, not of the engine.

- **Price validity does not reach this code at `now`, on either catalog, and the reason is structural.** All 2,070
  price-list/currency indexes of catalog B return `PriceIdContainerFormula(AndFormula)` with no
  `RangeCountFormula` anywhere in the tree. `createRangeCountFormulaIfNecessary` builds one only when *both*
  families are non-empty, and the minus family is "ranges that have already ended"; a catalog whose prices have
  not yet lapsed has nothing to cancel, so the query collapses to an `OrFormula`. A catalog carrying many expired
  price validities would reach it. This is worth knowing before anyone sizes price-path work from the assumption
  that price validity exercises the counting kernel — it does not, until prices expire.
- **The SIMD premise of `#1546` is weakened, not refuted.** The mask emission it is built around targets the dense
  span scan. After sparse emission that scan covers ~61,700 slots of the production dump instead of 643,427 — the
  dense path still runs on 4 of 14 chunks, so the kernel is not dead code, but it is now worth a few percent
  rather than being the headline. `#1541` should be sized against that, not against the original estimate.
  Catalog B is sparser still — its largest family yields 6,908 ids spread over 8 chunks, a density of 1.3 % —
  so on that data the dense span scan is reached even less often.
- **The Vector API emission primitive is settled for when it is built.** On JDK 21.0.12 / Zen 5,
  `ShortVector.compare(GT, 0).toLong()` compiles to `vpcmpnlew k7,zmm,zmm` + `kmovq` with **no `VectorMask` object
  and no allocation**; the mask never leaves a k-register. `LongVector` shift/or packing degenerates into lane
  extraction and should not be used.
- **Tooling trap, recorded so it is not repeated:** a capstone-backend `hsdis` silently truncates its listing at
  the first EVEX k-register instruction. Use `-XX:CompileCommand=print` *without* hsdis and decode HotSpot's raw
  `[MachCode]` with `objdump -D -b binary -m i386:x86-64`.
- **The prefix count now decides where to stop, but not which way to walk.** The collapse is implemented and
  measured above. What it does not do is take the *cheaper* side: the same number falls out of a suffix walk
  (`E(≥t) − S(>t)`), so a query whose point sits late in the threshold ordering could scan the short tail instead
  of the long head. The `p95+` rows are exactly that cost — 1.28× where `p50+` reaches 3.09× — so the remaining
  win is bounded by them and is real. It needs a cheap way to learn how many points lie either side of the query
  point, which `TransactionalLongBPlusTree` does not expose today; adding it is the next step, not a rewrite.
- **An earlier note in the working file recorded H5 as "confirmed for a point, refuted for an interval".** That
  refutation was aimed at a *different* claim — that the two sides of the old `AndFormula` compute the same count
  — and does not touch the prefix form. For an interval, a range with `b < from` also has `a < from ≤ to`, so it
  is inside the plus family and cancels exactly; the eight `shouldPassValidWithRangesOverlapping` assertions and
  the randomised oracle both hold. Recorded here because the intermediate note read as a blocker and is not one.
- **A one-`if` invariant with no test.** A range formula built inside a transaction hashes identically to the
  committed one — `TransactionalBitmap#getId()` is stable across the overlay. That is harmless *only* because
  `HeapMemoryCacheSupervisor#analyse` skips the cache for every non-read-only session and `createTransaction()`
  asserts `!isReadOnly()`. Nothing currently fails if that `if` is deleted.
  `RangeFormulaCacheKeyProbeTest` documents the collision and states plainly that it does not cover the gate.
- `SPARSE_FACTOR = 4` is a reasoned estimate, not a swept crossover; its javadoc says so.
- `RangeBitSlicedKernel` lives in test sources as a third independent implementation for the differential test,
  with its measured loss recorded in its javadoc so it is not re-proposed.

## Related work

- `2026-09-10-simd-vector-api-feasibility` — the analysis that proposed this kernel (§11) and estimated 5–20×.
  This record revises that estimate and reorders its SIMD step behind the sparse-emission fix.

## Timeline

- **2026-09-17** — census of a production catalog, two kernels implemented and differential-tested, A/B measured,
  `JoinFormula`/`DisentangleFormula` deleted, record written
- **2026-09-18** — the four range queries collapsed onto one two-bound prefix count; positional lookup deleted;
  A/B re-measured against replayed production `validity` spans
