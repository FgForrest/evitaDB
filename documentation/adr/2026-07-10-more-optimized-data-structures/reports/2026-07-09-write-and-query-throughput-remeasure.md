# #760 overnight re-measure — morning report (2026-07-09)

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, tip `3263d8628`,
compared against `dev` (`9dc4b9427`). Two independent measurement tracks: (A) write-churn
CPU/alloc profile of the 5 recently-shipped optimization commits, (B) JMH throughput comparison
of `ArtificialEntitiesThroughputBenchmark` (branch vs dev) covering both write and read/query
paths, with async-profiler follow-up on the two large regressions found.

**Headline**: the 5 write-churn fixes all work as designed (CRC32C −84%, Kryo pooling −84%,
RoaringBitmap COW mechanism correct). The query/write throughput comparison surfaced one
**critical, well-diagnosed regression** (a linear-scan bug in the new B+tree, ~45× slower
transactional single-entity upserts) and two **large, less-precisely-diagnosed read regressions**
(attribute filtering −60 to −66%) alongside two genuine **improvements** (facet/price filtering
+10-20%). Full detail and every number below.

---

## 1. Write-churn re-measure (deliverable 1)

`EvitaWarmUpInsertionTest#shouldGenerateLoadOfDataInWarmUpPhase[ALIVE]` (500k initial insert +
500k churn ops, unique/URL index), profiled with async-profiler 4.4 (`-e cpu` and `-e alloc`,
separate runs), same config as the pre-existing [`alive-cpu-hotspots-1gb-0-4`] baseline. Full
detail: `write-churn-findings.md`. Two independent passes were run and merged: my own
`categorize.py` substring-attribution pass, and a deeper sub-agent trace that pinpointed exact
call sites (superseded my weaker hypothesis on the RoaringBitmap anomaly — see below).

### Verdict on the 3 targeted allocation fixes

| Fix | Baseline | Post-fix | Verdict |
|---|---|---|---|
| **CRC32C combine ladder** (`c12952397`) | CPU 11.82% | **CPU 3.09%** | ✅ Clean win, −84% relative. `gf2MatrixTimes` leaf 9.08%→0.44%. |
| **Kryo/WAL buffer pooling** (`2bc70680c`) | Alloc 14.5% (~18.9GB) | **Alloc 2.9% (~3.0GB)** | ✅ Clean win, −84% absolute bytes. Target frame `createInitialOutput` now 0.004%, essentially eliminated. |
| **RoaringArray COW** (`b306267e6`) | Alloc 12.2% (~15.9GB) | Alloc 20.09% (~21.1GB) — category total **up** 33% | ✅ Fixed on its actual target, see below. |

**RoaringBitmap — root cause traced, not a broken fix.** `clone()` no longer deep-copies
`long[]`/`Container[]` backing arrays (5.26% of category — exactly the shallow-wrapper cost the
fix should produce). The category's overall growth is a **different** allocator:
`InvertedIndex.collectChangedPages` → `ValueToRecordBitmap.<init>` rebuilds fresh bitmap objects
when persistence pages are re-materialized, and this path is hit 5× by the 5 compactions that
fired in the profiling window (driven by the new `maxWasteActiveShare=0.1` hard override, not the
`minimalActiveRecordShare` interval gate — confirms the compaction-auto-tuner model). Unrelated
to the transactional-commit clone path the fix addressed — **not a regression**.

### Aggregate numbers
- Total allocation volume: 130.64GB → 105.17GB (**−20%**).
- Total CPU work: ~38% down (19,380→12,088 async-profiler samples).
- GC CPU share: **31.1%→13.4%** — GC is dethroned as the #1 hotspot, dropped 77% in absolute samples.
- Churn wall-clock: ~4m5s–4m38s (matches historical ~4m27s for this config — no regression).

### New CPU landscape — near three-way tie (~13-14% each)
FrontCoded string decode (13.82%, genuine +27% absolute — the only category that got worse in
absolute terms, steady-state trunk-incorporation cost), I/O syscalls (13.72%, compaction-driven —
5 full-collection rewrites), residual GC (13.42%). No new mystery bottleneck; everything traces to
already-understood FrontCoded decode cost or compaction-induced re-serialization.

### FrontCoded — still #1 allocator, "don't touch" decision reconfirmed
32%/33.7GB, but this is steady-state decode cost. The aggressive arena rewrite evaluated earlier
was explicitly not shipped (near-zero wall-clock gain); this profile reconfirms that call — GC is
no longer dominant, so the rewrite's value proposition still doesn't clear the bar. **Do not
revisit unless GC regains dominance.**

---

## 2. Ranked write-side optimizations (deliverable 2)

### 0. [VERIFIED] `CumulativeWeightBPlusTree.descend()` linear scan — a real but *partial* fix; the dominant cost is still locale-string comparison
**`transactionalUpsertThroughput` is ~45× slower on this branch than `dev`** (1.99-2.02 ops/s vs
~92.6 ops/s — see §3). This was investigated end-to-end tonight, including implementing and
*measuring* a candidate fix rather than shipping a diagnosis alone — the honest result is a
partial win with the true bottleneck still open.

**What was found and fixed**: `evita_common/.../dataType/bPlusTree/CumulativeWeightBPlusTree.java:377-381`,
`descend()`, located the correct child of an internal node with a **linear scan**
(`while (childIndex < internal.childCount - 1 && compare(key, internal.separators[childIndex]) >= 0) childIndex++;`
— up to `blockSize - 1` = 63 comparator calls per internal node at the default block size 64),
while `leafInsertionIndex()` in the same file correctly binary-searches the equivalent leaf-level
lookup. Changed `descend()` to binary-search the separators (mirroring `leafInsertionIndex()`),
rebuilt, and re-ran `transactionalUpsertThroughput` twice:

| | ops/s |
|---|---|
| Pre-fix (original suite + independent reconfirmation) | 2.02, 2.02, 2.02, 1.99 |
| **Post-fix (binary search in `descend()`)** | **3.41, 3.17** |
| `dev` | 92.2, 93.4, 92.2 |

**The fix is real (≈1.6× improvement, consistent across two runs) but explains only a small slice
of the 45× gap — 28× still separates this branch from `dev` after the fix.** A second wall-clock
profile taken *after* the fix shows why: `java.text.CollationElementIterator.next` (the core
Unicode-collation-iteration cost inside `RuleBasedCollator.compare()`) is now **the dominant leaf
frame on both the benchmark thread (39.4% of its wall time) and the trunk-incorporation replay
thread (67.5%)** — i.e. after removing the linear-scan tax, what's left is still overwhelmingly
locale-string comparison cost, paid twice per commit (once building the session's local view,
once again in `TrunkIncorporationTransactionStage`'s replay into the shared trunk index).
`LocalizedStringComparator` (`evita_common/.../comparator/LocalizedStringComparator.java`) is
byte-for-byte unchanged from `dev`, so per-call cost is identical on both branches — **the
remaining ~28× gap must come down to how many more times this branch calls the comparator per
commit than `dev` does**, not a per-call cost difference. This was not fully isolated tonight
(would need a comparator-call-counting instrumented run on both branches — a clean, cheap next
step: wrap `LocalizedStringComparator.compare` with an `AtomicLong` counter, run one `dev` and one
branch760 rep, compare counts directly).
- `bulkInsertThroughput` also regressed (−24%, see §3) — **re-measured post-fix to triangulate**:
  552.4→563.4 ops/s, a **+2% change, essentially within noise, not a meaningful recovery**. This
  is an important negative result: the `descend()` fix helps `transactionalUpsertThroughput`
  specifically but does *not* meaningfully help `bulkInsertThroughput`, so bulk's −24% has a
  **different, still-unidentified cause** — don't assume it shares this root cause.

**Recommended next steps, in order:**
1. **Ship the `descend()` binary-search fix only after `CumulativeWeightBPlusTreeTest` passes on
   it** — this fix was measured (JMH throughput, twice, consistent ~1.6× improvement) but **not
   correctness-validated tonight**: the test class's `@Test` methods live in `@Nested` classes and
   the surefire invocation used (`-Dtest=CumulativeWeightBPlusTreeTest`) matched 0 methods in this
   environment three attempts running; a throughput benchmark producing a plausible number is not
   a substitute for the actual correctness suite passing, since `descend()` backs
   `insert`/`remove`/`updateWeight`/every query on this tree and a subtly-wrong version could
   return wrong *results* without crashing or slowing down. (For what it's worth: hand-deriving
   the binary search against the original linear scan, including the duplicate-key case
   `separators=[5,5,5]`, key=5, both give `childIndex=3` — so the logic appears sound; this is a
   process gap, not a known defect.) Currently only applied in the scratch worktree used for
   tonight's measurement, **not committed**.
2. **[Primary open lever]** Count comparator calls per commit on both branches to confirm/refute
   the "more calls, not slower calls" hypothesis above, then attack whichever structural cause
   the count confirms (candidates: B+tree split/merge/rebalancing overhead on every insert at
   this small "few thousand records" scale; upsert doing a full remove-then-insert descent twice;
   or something else the call-count comparison will surface). **Count separately on the
   transactional-session insert path and the `TrunkIncorporationTransactionStage` replay path** —
   the "twice per commit" doubling observed in the wall-clock profile may itself be a delta from
   `dev` (worth checking whether `dev` does an equivalent second full-index insert on replay, or
   only this branch does), which would itself be a distinct, separately-fixable finding.
3. **[Complementary, addresses cost-per-call directly]** Cache `Collator.getCollationKey()` for
   locale-sensitive sortable attribute values instead of repeatedly calling
   `collator.compare(String, String)` (which re-normalizes both operands from scratch every
   call). `CollationKey` comparison is a cheap byte-array compare — this would reduce the cost of
   *every* remaining comparator call regardless of how many there are, so it's worth doing even
   before item 2's root cause is nailed down, but item 2 should confirm where the call-count is
   actually going first so effort isn't misdirected.
4. **Investigate `bulkInsertThroughput`'s −24% separately** — confirmed tonight to be a different
   cause than the `descend()` linear scan (see the triangulation result above). Not profiled
   tonight given the time budget; a CPU profile of `bulkInsertThroughput` alone would be the
   natural next step.

**Caveat**: the `-wi 0 -i 1` single-shot measurement (required for this write benchmark's
`Level.Iteration` state, see §5) makes absolute ops/s imprecise, but pre/post-fix deltas were each
reproduced twice and the direction is unambiguous — treat the ~1.6× fix-improvement and ~28×
remaining-gap figures as solid orders of magnitude, not precise numbers.

### 1. RoaringBitmap allocation during compaction — `InvertedIndex.collectChangedPages` → `ValueToRecordBitmap.<init>`
Now the single largest *addressable* allocator in the write-churn profile (20.09%/21.13GB, up
from 12.2%/15.9GB — NOT the transactional clone path, which the RoaringArray COW fix already
handles correctly). Rebuilds fresh bitmap objects every time a persistence page is
re-materialized; hit once per compaction (5× in the profiling window). Two angles:
- Pool/reuse `ValueToRecordBitmap` wrapper objects across page re-materializations within a single
  compaction pass, analogous to the COW approach already proven for the transactional clone path.
- Investigate whether `collectChangedPages` needs to eagerly re-materialize *all* changed pages,
  or could defer/batch construction.

### 2. I/O syscalls during compaction (13.72% CPU: `read` 7.61%, `__write` 2.80%, `llseek` 1.07%)
Compaction-driven (5 full-collection rewrites in the window), not steady-state. Tune the
compaction auto-tuner thresholds further, or reduce per-compaction I/O volume (larger sequential
buffers, fewer redundant `llseek` calls) — lower priority, more invasive.

### 3. FrontCoded string decode — reconfirm "do not touch"
Still #1 allocator (32.01%/33.66GB) but steady-state, and the aggressive rewrite was already
evaluated and rejected (near-zero wall-clock gain). Don't revisit unless GC regains dominance.

### 4. STM / InvertedIndex page re-serialization growth (+9.42%/+52% abs respectively)
Plausibly compaction-driven side effects of the same 5 rewrites, not independent problems. Lower
priority — re-evaluate after #1 lands, since fixing the compaction-amplified RoaringBitmap
allocator may reduce these as a side effect.

### Not recommended
- Kryo/OutBuf: fixed at its target, residual share increase is compaction-driven serialization
  *write* CPU, not allocation — no further action.
- OffsetIndex (CHAMP `long[]`): flat in absolute terms this round — no live problem in this
  profile despite being flagged as "next target" in an earlier, differently-configured churn
  profile. Re-verify with a dedicated profile before investing.

---

## 3. Query/write throughput results + assessment (deliverable 3)

`ArtificialEntitiesThroughputBenchmark`, JMH 1.37, 24 threads (`@Threads(Threads.MAX)` on read
methods, `@Threads(1)` on write methods), `-f 1`. Write methods forced to `-wi 0 -i 1` (repeated
3× as separate process invocations) due to a pre-existing engine-reconstruction race in
`Level.Iteration` JMH state (see §5) — not a #760 issue, a harness quirk. Read methods used real
`-wi 2 -i 5 -w 15s -r 15s` for proper error bars (`Level.Trial` state builds the engine once,
unaffected by the race). Both trees built from the same dataset generator (`git diff dev HEAD`
confined to `io.evitadb.spike.*` and this session's two harness patches — zero drift in
`DataGenerator`/`artificial/` state classes).

**A pre-existing benchmark-harness bug had to be fixed before any of this data was trustworthy —
see the footnote at the end of this section.**

### Write side (single-shot, 3 reps each — see caveat in §5)

| Method | branch760 | dev | Δ |
|---|---|---|---|
| `bulkInsertThroughput` | 552.4 ops/s (range 549.5-556.3) | 726.7 ops/s (range 721.4-730.0) | **−24.0%** |
| `transactionalUpsertThroughput` | 2.02 ops/s (2.02 all 3 reps — reproduced independently at 1.99 ops/s) | 92.6 ops/s (range 92.2-93.4) | **−97.8% (~45× slower)** |

### Read side (`-wi 2 -i 5`, error bars included)

| Method | branch760 | dev | Δ |
|---|---|---|---|
| `singleEntityRead` | 81,658.9 ± 7,659.7 ops/s | 83,236.5 ± 998.9 ops/s | −1.9% (within noise) |
| `paginatedEntityRead` | 2,606.4 ± 123.0 ops/s | 2,731.2 ± 195.1 ops/s | −4.6% (within noise) |
| `attributeFiltering` | 1,998.6 ± 209.4 ops/s | 5,858.4 ± 1,055.5 ops/s | **−65.9%** |
| `attributeAndHierarchyFiltering` | 5,799.4 ± 215.2 ops/s | 14,879.5 ± 619.6 ops/s | **−61.0%** |
| `priceFiltering` | 561.0 ± 56.2 ops/s | 509.4 ± 41.5 ops/s | **+10.1%** |
| `facetFiltering` | 161,432.3 ± 1,376.8 ops/s | 134,125.5 ± 1,418.5 ops/s | **+20.4%** |

### Assessment

This is **not** the "small, acceptable slowdown" scenario — it's a mixed picture with one severe
outlier, two real regressions, and two genuine improvements:

- **`transactionalUpsertThroughput` (~45× slower) is partly a fixable bug, partly still
  unexplained.** A linear-scan-vs-binary-search defect (§2, item 0) was found, fixed, and
  *measured* tonight — real ~1.6× improvement, but 28× still separates this branch from `dev`
  after the fix. The remaining gap traces (via a second, post-fix profile) to locale-string
  comparator cost that must be called far more often on this branch than on `dev`, since the
  comparator itself is unchanged. Not yet fully root-caused — see §2 item 0 for the concrete next
  step (comparator call-count instrumentation).
- **`attributeFiltering`/`attributeAndHierarchyFiltering` (−60 to −66%) are real, substantial
  regressions**, not noise — both cleanly separated from `dev` even accounting for `dev`'s wide
  error bars. This is exactly the "B+tree complexity" risk Johnny flagged, but at a magnitude well
  past "small." Profiled below (§4) — the dominant cost is bitmap construction in the
  sort/slice-result path, not (as first suspected) string comparison.
- **`singleEntityRead`/`paginatedEntityRead` are flat** — small deltas fully inside the error
  bars, no action needed.
- **`priceFiltering`/`facetFiltering` genuinely improved** (+10% / +20%) — consistent with the
  substantial Price/Facet index rework in this branch (`PriceListAndCurrencyPriceSuperIndex` +258
  lines, `FacetIndex`/`FacetGroupIndex`/`FacetReferenceIndex` reworked; matches the
  previously-measured "PriceSuper backing spike" finding that the new element-keyed tree dominates
  the old `LongBPlusTree`).

**Bottom line for Johnny's question**: the B+tree migration is *not* uniformly slower — it's a mix
of flat, faster, and two significantly slower read paths, plus one severe write-side regression
that's only partially explained. The two attribute-filtering regressions and the remaining
(post-fix) transactional-upsert gap are the items to close out before this can be called
performance-neutral-or-better overall.

### Footnote: benchmark-harness bug found and fixed (not a #760 issue)
`EvitaCatalogSetup.java` hardcoded `.maxOpenedReadHandles(12)`, while every read benchmark method
uses `@Threads(Threads.MAX)` (=24 on this machine). This guaranteed `PoolExhaustedException` on
every warmup/measurement invocation of every read method — and **JMH silently reported "Run
complete" with an empty `[]` result JSON** instead of failing loudly. Confirmed pre-existing on
both `dev` and this branch (`git diff` on the file is empty; only one unrelated historical commit
touched it). Patched locally in both scratch worktrees
(`maxOpenedReadHandles(Runtime.getRuntime().availableProcessors() * 4)`) to unblock tonight's
measurement — **not yet applied to git history**. Worth a small standalone PR so nobody else
silently gets zero-sample "results" running these benchmarks on a modern many-core box.

---

## 4. Ranked query-side optimizations (deliverable 4)

### 1. [Primary target] `attributeFiltering`/`attributeAndHierarchyFiltering` −60 to −66% — bitmap construction in the sort/slice path
CPU-profiled `attributeFiltering` directly (async-profiler, `-e cpu`, isolated query-execution
samples from dataset-setup samples by filtering on the `EvitaSession.query` vs
`EvitaSession.upsertEntity` stack markers — the first unfiltered pass conflated the two and
over-attributed cost to string comparison; the corrected, isolated numbers below supersede that).

**41.44% of query-only CPU time is inside `RoaringArray.<init>`**, reached via:
`OrFormula.getRoaringBitmaps`/`AndFormula.computeInternal` (conjunction evaluation, ~38-54% of
query time) → `RoaringBitmapBackedBitmap.getRoaringBitmap`/`fromArray` (38%/37%) →
`SortedRecordsSupplier.resolvePositions`/`resolvePositionsByDenseWalk` (37% each) →
`PreSortedRecordsSorter.sortAndSlice`/`MergedComparableSortedRecordsSupplierSorter.sortAndSlice`
(37% each) → `QueryPlan.sortAndSliceResult` (37%). Supporting leaf costs: `Util.fillArray`
(15.1% of query time), `Util.hybridUnsignedBinarySearch` (10.3%), `BitmapContainer.computeCardinality`
(4.4%), `BitmapContainer.contains` (3.6%) — all RoaringBitmap internals, consistent with heavy
fresh-bitmap construction and manipulation during result assembly.

**This traces to #760's `SortedRecordsSupplier` rework** (332 new lines; `git diff dev HEAD --stat`
confirms `SortedRecordsSupplier.java`, `RoaringBitmapBackedBitmap.java`,
`MergedComparableSortedRecordsSupplierSorter.java` all substantially changed). The class's own
javadoc documents a three-tier dispatch: sparse selections (`K <= N/64`) take an `O(log N)`
per-record tree probe with no materialization; dense selections on a *warm* supplier take a tight
`O(N+K)` merge-walk over already-materialized arrays; dense selections on a *cold* supplier fall
back to `resolvePositionsByDenseWalk` — an `O(N)` cursor walk that allocates a fresh bitmap mask
and a cloned "not found" bitmap on every call, and never warms the materialized arrays.

**Hypothesis (not yet confirmed empirically — the next concrete step)**: this JMH benchmark
issues many distinct random attribute-filter queries per thread across 24 threads, which may mean
individual `SortedRecordsSupplier` instances rarely stay "warm" long enough for the fast merge-walk
path to kick in, and rarely fall under the `K <= N/64` sparse threshold either — pushing most
queries onto the `O(N)` cold dense-walk path with its per-call bitmap allocation, where `dev`'s
simpler (pre-#760) array-backed supplier had no such warm/cold distinction and was effectively
always on the equivalent of the fast path.

**Recommended next step**: instrument or log which of the three `resolvePositions` branches
(`sparse` / `warm dense merge-walk` / `cold dense walk`) this benchmark's queries actually take
(the `SortResolutionStrategy` enum returned by `PositionResolution` looks like it already carries
this label — a cheap counter keyed on that enum during a repeat run would confirm or refute the
hypothesis in minutes). If the cold path dominates as suspected, the fix is either (a) making the
warm-up threshold more aggressive so arrays materialize sooner, or (b) revisiting whether
`TREE_PATH_SELECTIVITY_DIVISOR = 64` is well-tuned for this workload's actual selectivity
distribution.

### 2. Locale-string comparator cost (secondary, shared root cause with write-side item §2.0)
The *first*, uncorrected profiling pass (before separating setup-phase from query-phase samples)
showed `LocalizedStringComparator.compare` at 38.8% — this was contaminated by dataset-setup
samples (which also build sort indexes via the same comparator) and should **not** be read as a
query-time cost in its own right. However, since `attributeFiltering` does exercise sort-attribute
lookups through the same `CumulativeWeightBPlusTree` machinery, the write-side fix in §2 item 0
(binary-search `descend()`) may partially benefit this path too if any of the 41.44%
`RoaringArray.<init>` chain bottoms out in tree traversal rather than pure bitmap construction —
not confirmed, worth checking after item #1 is fixed and re-profiled.

### 3. `priceFiltering`/`facetFiltering` — no action needed
Both improved (+10%, +20%). No investigation warranted; flagged here only so the "biggest impact
first" list is complete and doesn't look like an oversight.

---

## 5. Methodology notes / caveats

- **Write-side single-shot measurements** (`-wi 0 -i 1`, repeated 3× as separate process
  invocations): required because `ArtificialTransactionalWriteBenchmarkState`/similar write states
  use `Level.Iteration` JMH setup, which reconstructs a fresh `Evita()` engine on every iteration.
  A second iteration in the same JMH process boots a second engine pointed at the same storage
  directory, and its catalog-inventory-divergence scan finds a stray UUID-named leftover directory
  from the first iteration's teardown, whose name fails classifier validation whenever the UUID
  happens to start with a digit — an unrelated pre-existing harness quirk (confirmed identical
  behavior on `dev`), not a #760 issue. `-wi 0 -i 1` keeps engine construction to exactly once per
  process, sidestepping it. This makes single-run absolute numbers imprecise for small deltas —
  the 3× repeats give a rough range, and only large deltas (24×, 45×) should be trusted at face
  value; small differences on write methods were not encountered this run, but would need more
  reps to trust.
- **Read-side measurements** used real `-wi 2 -i 5` (proper JMH warmup + 5 measurement iterations,
  error bars reported) since `ArtificialFullDatabaseBenchmarkState`'s `@Setup(Level.Trial)` builds
  the engine once per fork regardless of iteration count — unaffected by the above race.
- **Dataset generation dominates read-method wall-clock** (~5 min of every ~6-7 min method run is
  building the 100k-product dataset from scratch, since `/tmp/evita` is wiped between methods to
  avoid an unrelated stray-catalog-directory issue). `ArtificialFullDatabaseBenchmarkState` does
  implement `EvitaCatalogReusableSetup` (catalog reuse across JMH forks is architecturally
  supported), but reuse was not attempted tonight — the wipe-every-method approach was judged
  safer given the time budget, at the cost of ~5× more dataset-generation overhead than strictly
  necessary. If these benchmarks become a recurring exercise, enabling reuse (only removing stray
  UUID-named directories between runs, not the whole `/tmp/evita` tree) could cut total suite time
  roughly 5-6×.
- **Comparability**: `git diff dev HEAD -- evita_test/evita_performance_tests` is confined to
  `io.evitadb.spike.*` (unrelated dev-time measurement spikes) plus this session's two
  `EvitaCatalogSetup.java` pool-size patches (identical patch applied to both trees) — zero drift
  in the dataset generator or benchmark state classes between the two measurement runs.
- All profiling used async-profiler 4.4. CPU profiles for compute-bound work; wall-clock profiles
  (`-e wall`) were used for the `transactionalUpsertThroughput` investigation specifically because
  a blocked/parked thread shows no CPU samples — ruling out a timer/lock wait as the cause required
  wall-clock sampling, which also happened to pinpoint the actual (compute) cause directly.

## Artifacts
- `write-churn-findings.md`, `write-churn/` — write-churn profiles + categorize.py.
- `optimization-recommendations.md` — standalone copy of §2/§4's ranked lists.
- `jmh-branch760/`, `jmh-devbaseline/` — all raw JMH JSON + logs.
- `profile-txn/` — wall-clock profiles of `transactionalUpsertThroughput`, pre-fix
  (`txn-wall.jfr`/`.collapsed`) and post-fix (`txn-wall-postfix.jfr`/`.collapsed`), plus the
  pre/post JSON results (`reconfirm.json`, `postfix.json`, `postfix2.json`). The `descend()`
  binary-search fix itself is only applied in the scratch worktree
  (`760-branch-worktree/evita_common/.../CumulativeWeightBPlusTree.java`), **not committed** to
  the actual branch — apply it there if adopting.
- `profile-attrfilter/` — CPU profile of `attributeFiltering` (jfr + collapsed + analysis).
- All paths above are relative to this file's own directory (`docs/reports/`).
