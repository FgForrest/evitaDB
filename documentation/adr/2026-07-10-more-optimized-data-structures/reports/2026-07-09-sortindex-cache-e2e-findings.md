# SortIndex committed-snapshot cache — overnight end-to-end findings

Issue: #760. Companion to `docs/design/2026-07-09-sortindex-committed-snapshot-cache.md` (the
mechanism design doc, updated alongside this report) and
`docs/reports/2026-07-09-write-and-query-throughput-remeasure.md` §4.1 (the original regression
report this work responds to).

---

## UPDATE 2026-07-10 — root cause was WRONG; corrected, fixed, re-measuring

The "Root cause" below (line 1217 `getRecordsEqualToInternal` creating a layer) is **superseded** — it was
an incorrect inference. Johnny's mental model exposed the real bug:

1. The `attributeFiltering` benchmark uses `createReadOnlySession` (`ArtificialBenchmarkState:43`). A
   read-only session — and in fact any *plain query*, per `EvitaSession.query` → `catalog.getEntities`
   directly (only `execute(...)` opens a transaction) — binds **no `Transaction`**. So
   `Transaction.isTransactionAvailable()` is `false`.
2. The fast-path gate I wrote was `isTransactionAvailable() && getTransactionalMemoryLayerIfExists(this)
   == null`. The first conjunct is `false` for every read-only query → the fast path was **never** taken.
   That alone explains the 0/17,268 JFR samples. My earlier "confirmed" root cause read an
   `executeInTransactionIfProvided` frame in the profile as proof of a live transaction, but that method
   runs the lambda even when passed a `null` transaction (`Transaction.java:129/162`) — a bad inference.
3. The cache's own javadoc encoded the false premise ("every query opens a throwaway transaction"), which
   is where the bogus gate came from.

**Fix landed in the working tree** (`SortIndex.java`, uncommitted):
- Gate on both suppliers simplified to `getTransactionalMemoryLayerIfExists(this) == null` — null both for
  a no-transaction query (point 1) and a transaction that hasn't written this index (point 2).
- Because a leaf `SortIndex` cannot cheaply tell ALIVE from WARMUP (verified — `CatalogState` isn't
  plumbed to it), the never-swapped cache is now explicitly dropped on in-place (warm-up/bulk) writes via
  `invalidateCommittedSnapshotCacheIfNonTransactional()`, called from `addRecordInternal`/
  `removeRecordInternal`.
- Two regression tests added (`shouldDispatchDenseQueryToWarmMergeWalkWithoutAnyTransaction`,
  `shouldInvalidateCommittedCacheOnNonTransactionalInPlaceWrite`); the whole `SortIndexTest` is green
  (80/80). The old tests all wrapped reads in `new Transaction(sortIndex)`, which is exactly why they
  never caught the read-only gap.

**Status: re-measured, fix confirmed sound and net-positive** (full methodology `-wi 2 -i 5 -w 15s -r 15s
-f 1`, freshly-built jar, anti-stale-jar bytecode check passed — `invalidateCommittedSnapshotCacheIfNonTransactional`
present in the bundled `SortIndex.class`):

| Benchmark | Pre-fix (broken build) | This fix | `dev` |
|---|---|---|---|
| `attributeFiltering` | 1998.6 ± 209.4 | **2237.6 ± 524.1** ops/s | 5858.4 |
| `attributeAndHierarchyFiltering` | 5799.4 ± 215.2 | **10182.7 ± 410.2** ops/s | 14879.5 |

`attributeAndHierarchyFiltering` is a clear **~1.76× win** (non-overlapping CIs). `attributeFiltering` moved
within noise, BUT the JFR proves the fast path now engages: `resolvePositionsByDenseWalk` **4506 → 0** (the
cold walk is gone, replaced by a cheap sparse probe, 21 samples), `buildCachedSupplier` **0 → 4**. So the sort
fix works everywhere; the hierarchy benchmark was dense-walk-bound and reaped the full benefit, while
`attributeFiltering` is now bound by a DIFFERENT subsystem.

**`attributeFiltering`'s new dominant cost (JFR self-frames, 29,200 samples, `--stack-depth 128`)** — the
filter path, not the sort path:
- **~21% — RoaringBitmap container materialization**: `Util.fillArray` (6341), 100% from
  `ArrayContainer.loadData(BitmapContainer)`. Very likely the actual #760-vs-`dev` regression driver (from
  #760's InvertedIndex / bitmap-storage changes); needs a dev-vs-760 profile comparison to confirm.
- **~21% — transactional-bitmap `ThreadLocal` tax**: `ThreadLocal.get()` (5972), 6126 from
  `Transaction.getTransactionalMemoryLayerIfExists`, of which **6000 from `TransactionalBitmap.getRoaringBitmap()`**,
  called from `RoaringBitmapBackedBitmap.getRoaringBitmap(Bitmap)` under **`OrFormula.getRoaringBitmaps()`
  (5951)**. Every OR'd attribute-value bitmap pays a `ThreadLocal.get` that always returns null in a read-only
  query. Pre-existing pattern (not sort-specific); a safe fix means hoisting the per-thread transaction check
  out of the per-bitmap unwrap — cross-cutting, needs care.

Neither is the SortIndex sort cache; both are core, widely-used code. This is the natural boundary of the
sort-cache thread. **Recommended next step: a dev-vs-760 CPU-profile diff on `attributeFiltering` to confirm
which of the two is the real regression driver before editing the hottest query path.**

Point 2's read path (line 1217) is a real but narrower issue — it only bites inside an explicit *write*
transaction that also filters+sorts; deferred as a documented follow-up (needs care around the
non-`volatile` `sortIndexChanges` field).

---

## Headline (SUPERSEDED — see UPDATE 2026-07-10 above)

**The `attributeFiltering`/`attributeAndHierarchyFiltering` regression is NOT closed.** The
committed-snapshot cache mechanism is correct and passes every isolated/unit test, but a JFR profile
of the correctly-built end-to-end benchmark shows it fires in **0 of 17,268** real-query execution
samples. Johnny's conditional ("if the optimization is sound, proves net positive, finalize +
`/code-quality-pipeline`") is **not met**. Everything below is uncommitted in the working tree,
awaiting a decision.

## What was asked

Continue investigating the regression; if a fix proves sound end-to-end, finalize it, get an advisor
critical review, and run `/code-quality-pipeline`. Prepare a summary for the morning either way.

## What the isolated benchmark showed (real, but incomplete)

`SortIndexCommittedSnapshotCacheBenchmark` (a standalone `OwnerSortIndex`, real per-query
`Transaction` construct/bind/unbind, no `EvitaSession`/`Catalog`) showed a controlled, validated
1.04×–19.0× speedup at the `resolvePositions()` dispatch level once two bugs were fixed:

1. The per-transaction memoization in `SortIndexChanges` never survives a query, because
   `EvitaSession.executeInTransactionIfPossible()` opens and discards a fresh `Transaction` (and
   therefore a fresh `SortIndexChanges`) per query. Fixed by moving the cache to `SortIndex` itself
   (survives across transactions) gated on "no transactional layer exists yet for this instance."
2. Even with a warm cache, `SortedRecordsSupplier.resolvePositions()`'s dense-selection dispatch only
   takes the fast array-merge-walk when the **freshly built supplier instance's own fields** are
   non-null — a lazy `Supplier<int[]>`-backed cache is never consulted because nothing in the dispatch
   path calls the getter. Fixed with a new eager constructor that materializes the cache before
   building the supplier.

Both fixes are correct (`SortIndexTest.CommittedSnapshotCacheTest`, 7 cases, including an explicit
`SortResolutionStrategy` assertion — not just array-identity reuse). `bug-hunter-tdd`'s independent
Phase-1 review of the `/code-quality-pipeline` run found **NO_WORK_NEEDED**: no bugs in the six angles
it traced (racing first-touch safety, stale-cache-copy paths, reentrant-write ordering, view-mode
generality, eager-constructor null-safety, defensive-design compliance).

## Why the first end-to-end measurement looked flat, and why that was misleading

The first two overnight end-to-end runs (`attributeFiltering` 1928 ± 179 ops/s,
`attributeAndHierarchyFiltering` 5671 ± 344 ops/s) looked statistically indistinguishable from the
pre-fix baseline — but this was an artifact, not a real measurement. `evita_test/evita_performance_tests/target/benchmarks.jar`
was built at 19:18, **before** the `SortIndex.java` fix was finalized at 20:27. Confirmed via `javap`:
the bundled `SortIndex.class` inside that jar has none of the fix's fields or methods
(`cachedAscendingArrays`, `buildCachedSupplier`, etc.) — md5 `ba06858f...` vs. the freshly compiled
`e8fcc73c...`. This is the third occurrence this session of the same stale-classpath pattern (first:
a debug harness silently ran the old `~/.m2` jar; second: a Maven test run without `-am` resolved
`evita_engine` from the local repo instead of the reactor). Standing operational lesson: **always
verify the actual binary before trusting a benchmark result**, not just the source diff.

Getting a valid end-to-end measurement also required fixing three unrelated invocation bugs, all now
resolved for future runs:

- `ArtificialTestRunner.main()` (the shaded jar's manifest `Main-Class`) hardcodes
  `.include("io.evitadb.performance.externalApi.*")` and **silently ignores `args`** — a regex filter
  passed on the command line does nothing. Use `java -cp benchmarks.jar org.openjdk.jmh.Main <regex>
  ...` instead of `java -jar benchmarks.jar <regex> ...`.
- ByteBuddy proxy generation on JDK 21 needs `--add-opens java.base/java.lang=ALL-UNNAMED` on the
  **forked** benchmark JVM, not the outer launcher.
- `-Djava.io.tmpdir=...` on the outer `java` command does not propagate to JMH's forked benchmark
  JVM; it must go through `-jvmArgsAppend`.

## The real, correctly-measured end-to-end result

Rebuilt `benchmarks.jar` fresh (`mvn -pl evita_test/evita_performance_tests -P full package
-DskipTests -o`), confirmed via `javap` it now contains the fix, and re-ran both benchmarks with the
original report's exact methodology (`-wi 2 -i 5 -w 15s -r 15s -f 1`), each in its own isolated
`java.io.tmpdir` to avoid the shared `/tmp/evita` collision with other concurrent sessions:

| Benchmark | Pre-fix (branch) | Post-fix (this session, correct jar) | `dev` |
|---|---|---|---|
| `attributeFiltering` | 1998.6 ± 209.4 ops/s | **2129.6 ± 127.2 ops/s** | 5858.4 ± 1055.5 ops/s |
| `attributeAndHierarchyFiltering` | 5799.4 ± 215.2 ops/s | **5710.7 ± 278.4 ops/s** | 14879.5 ± 619.6 ops/s |

`attributeFiltering`'s confidence intervals overlap substantially with the pre-fix baseline — not a
real, decisive win. `attributeAndHierarchyFiltering` is flat, arguably slightly down (within noise).
Neither dents the ~60% gap to `dev`.

## Why: JFR profile of the fixed, correctly-built end-to-end run

Profiled `attributeFiltering` with JMH's built-in `-prof jfr` against the correct jar
(recording: `docs/reports/e2e-remeasure/attrfilter-fixed-cpu.jfr`, extracted via
`jfr print --events jdk.ExecutionSample`, 17,268 samples; curated counts in `analysis.txt` alongside
it). Two decisive, positive signals — not just an absence of the new code:

1. Sampled stacks show `SortIndexChanges.getDescendingOrderRecordsSupplier() line:131` →
   `SortIndex.createReversedSortedComparableForwardSeeker()`. That frame is reachable **only** on the
   pre-fix slow branch; the fast path builds the supplier directly and never routes through
   `SortIndexChanges` at all. Its presence is proof-positive the old path is what's running.
2. `resolvePositionsByDenseWalk` appears in 4,506 of 17,268 samples — the cold O(N) walk the fix
   exists to bypass is still dominant. (Dense queries are clearly common in this benchmark; the fix
   targets a genuinely hot path, it just never fires on it.)

Zero samples reach `SortIndex.buildCachedSupplier`/`getCachedAscendingArrays`/
`getCachedDescendingArrays`/`getCachedAllRecordsBitmap`. Since the fast path fires in 0 of 17,268
samples, the `attributeFiltering` table's ~6.5% uptick above isn't merely "within noise" — it is
*definitionally* measurement variance: code that never executes cannot have improved throughput.

**Root cause**: the fast-path gate — `Transaction.isTransactionAvailable() &&
Transaction.getTransactionalMemoryLayerIfExists(this) == null` — has two conditions, and which one
fails was checked explicitly rather than assumed. `jfr print` defaults to a 5-frame stack, too shallow
to see the outer `Transaction` wrapper in the first pass — re-extracted from the same recording with
`--stack-depth 128` and confirmed **all 6,343** sort-related samples (100%) carry an
`executeInTransactionIfProvided` frame, matching the pre-fix profile's 98.83% baseline. That rules out
"no transaction available at all": `Transaction.isTransactionAvailable()` is true. The failing
condition is the second one — `getTransactionalMemoryLayerIfExists(this) == null` is false, i.e. a
transactional memory layer for this `SortIndex` instance already exists by the time the sort step's
gate check runs.

`SortIndex.java` calls the *creating* accessor `getOrCreateSortIndexChanges()` (→
`Transaction.getOrCreateTransactionalMemoryLayer(this)`) from four sites: lines 561, 578, 1146 (a
genuine write, inside `addRecordInternal`), and **1217**, inside `getRecordsEqualToInternal()` — a
read-only attribute-equality lookup used during filtering. Any query that filters and then sorts
within the same transaction touches `getRecordsEqualToInternal()` first, which creates the layer, so
by the time the sort step's gate check runs, a layer already exists and the fast path is skipped. This
is a **confirmed layer-creating read path and the likely culprit** — it has not been proven to be the
*only* trigger (cost/cardinality estimation during query planning may also touch the index first); all
four call sites need auditing to separate genuine writers from reads that shouldn't create a layer.

## Assessment

The cache mechanism itself is not wrong — its *gate premise* ("no layer exists ⇒ safe to use the
committed cache") is false in practice, because ordinary read paths create layers before the sort step
ever runs its own check. This is salvageable, not dead:

- **(a)** Route genuinely read-only callers (starting with `getRecordsEqualToInternal`) through a
  non-creating accessor, so reads stop poisoning the gate.
- **(b)** Key the gate off "was this index *written* in this transaction" rather than "does a layer
  exist at all."

Both have real correctness implications — a read in a write-touched transaction must still observe
that transaction's own modifications — and are a design decision, not a mechanical patch tonight.

## Status / what's next

- Everything is **uncommitted** in the working tree: `SortIndex.java`, `SortIndexChanges.java`
  (visibility-only), `SortedRecordsSupplier.java`, `ReferenceSortedRecordsProvider.java`,
  `SortIndexTest.java` (`CommittedSnapshotCacheTest`), plus the untracked JMH spikes under
  `evita_test/evita_performance_tests/.../spike/` and the `EvitaCatalogSetup.java` pool-exhaustion fix
  (unrelated pre-existing bug, safe to keep regardless of the above).
- `/code-quality-pipeline` Phase 1 (read-only planning) completed for all four agents against the
  current diff: `bug-hunter-tdd` returned an explicit `NO_WORK_NEEDED` with a thorough six-angle trace
  (see above). The other three (`test-architect`, `code-simplifier`, `javadoc-writer`) went idle
  without their plan text being retrieved in this session. **Phase 2 execution was not run** — per
  Johnny's own conditional, the pipeline is a "finalize it" step gated on soundness, and soundness at
  the regression-closing level is exactly what's unresolved.
- Nothing was committed or pushed.
- **Recommended next step**: audit the four `getOrCreateSortIndexChanges()` call sites in
  `SortIndex.java` (561, 578, 1146, 1217), decide between levers (a)/(b) above, implement, then repeat
  the exact JFR-profiled end-to-end re-measurement in this report to confirm the fast path actually
  fires before trusting any new throughput number.

## Raw artifacts

- `docs/reports/e2e-remeasure/attrfilter-v5-results.json` / `hierarchy-v5-results.json` — final
  correct-jar JMH results (full methodology, matching the original report).
- `docs/reports/e2e-remeasure/attrfilter-fixed-cpu.jfr` — the JFR CPU recording backing the profile
  analysis above (`-prof jfr`, correct jar, `attributeFiltering`).
- `docs/reports/e2e-remeasure/analysis.txt` — curated frame-occurrence counts and the example stack
  extracted from that recording (the full `jfr print` text dump is ~12MB and was not committed). Note:
  `jfr print` defaults to a 5-frame stack; the transaction-frame confirmation above required
  re-extracting with `--stack-depth 128` — use that flag when re-analyzing `attrfilter-fixed-cpu.jfr`.
- `docs/reports/profile-attrfilter/` — original pre-fix async-profiler CPU profile (session-prior),
  kept for comparison.
