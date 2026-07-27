# Follow-up optimization recommendations

**Superseded by `2026-07-09-write-and-query-throughput-remeasure.md` §2/§4, which is the authoritative, up-to-date version
of this list** (this file was drafted mid-session before the `descend()` fix was implemented and
*measured* — the write-side item 0 below describes the diagnosis-only stage; the morning report
has the verified before/after/dev numbers and the corrected conclusion that the fix is a real but
partial ~1.6× win, not the full 45× explanation). Kept here only as raw working notes /
chronological record of how the investigation evolved; read the morning report first.

Ranked by estimated impact, based on the post-5-fixes write-churn re-measure
(`write-churn-findings.md`, `write-churn-remeasure-post-5fixes.md` memory).

## Write-side (ranked, highest impact first)

### 0. [SUPERSEDED — see MORNING-REPORT.md §2 item 0] `CumulativeWeightBPlusTree.descend()` linear scan
Original hypothesis (below, kept for the record): this linear scan alone explains the full 45×
`transactionalUpsertThroughput` regression. **This was wrong** — implementing and measuring the
fix showed only a ~1.6× improvement (2.0→3.3 ops/s vs `dev`'s 92.6 ops/s), with a post-fix
wall-clock profile confirming raw `LocalizedStringComparator`/`RuleBasedCollator` comparison cost
(`CollationElementIterator.next`) still dominates both the benchmark and trunk-incorporation
threads. The fix is still worth shipping (real, measured, low-risk), just not billed as *the*
answer. See the morning report for the corrected analysis and the actual next step (comparator
call-count instrumentation to find where the remaining ~28× gap comes from).

Original (superseded) diagnosis, for reference:
- `evita_common/.../dataType/bPlusTree/CumulativeWeightBPlusTree.java:377-381`, `descend()`,
  finds the correct child of an internal node with a **linear scan**:
  `while (childIndex < internal.childCount - 1 && compare(key, internal.separators[childIndex]) >= 0) childIndex++;`
  — up to `blockSize - 1` (default `DEFAULT_BLOCK_SIZE = 64`, so up to 63) comparator calls per
  internal node visited.
- Contrast with `leafInsertionIndex()` (same file, line 414+), which correctly **binary-searches**
  within a leaf's keys for the same kind of lookup.
- `LocalizedStringComparator` itself is **unchanged** between `dev` and this branch (`git diff`
  empty).

**Caveat**: root cause is diagnosed from a wall-clock profile + source inspection, not yet
validated by implementing the fix and re-measuring. `-wi 0 -i 1` (required for this Level.Iteration
write-benchmark state — see jmh-driver-write.sh) means the absolute ops/s numbers are single-shot,
but a 45× delta, reproduced twice, is far beyond what single-shot noise could produce — treat the
*direction and rough magnitude* as solid, the exact "45×" figure as approximate.

### 1. RoaringBitmap allocation during compaction — `InvertedIndex.collectChangedPages` → `ValueToRecordBitmap.<init>`
**Now the single largest *addressable* allocator** (20.09% / 21.13GB of total alloc, up from
12.2%/15.9GB baseline — the rise is NOT the transactional clone path, which the RoaringArray COW
fix already handles correctly). This path rebuilds fresh `PersistentRoaringBitmap`/`RoaringArray`/
`Container[]` objects every time a persistence page is re-materialized, and gets hit once per
compaction (5× in the profiling window). Two possible angles:
- Pool/reuse `ValueToRecordBitmap` wrapper objects across page re-materializations within a single
  compaction pass, analogous to the COW approach already proven for the transactional clone path.
- Investigate whether `collectChangedPages` needs to re-materialize *all* changed pages eagerly, or
  could defer/batch construction — worth a design pass before committing to pooling.
Estimated effect: could claw back a meaningful share of the +5.2GB absolute growth in this category;
exact number needs a targeted before/after alloc profile once implemented.

### 2. I/O syscalls during compaction (13.72% CPU, `read` 7.61% / `__write` 2.80% / `llseek` 1.07%)
Compaction-driven (5 full-collection rewrites in the window), not steady-state. Two levers, in
order of effort:
- Tune the compaction auto-tuner thresholds further (already landed: `maxWasteActiveShare`,
  `minCompactionIntervalMilliseconds`) — check whether the 5-compactions-per-window cadence for
  this workload can be safely reduced without growing waste share past its cap.
- Reduce per-compaction I/O volume itself (larger sequential read/write buffers, avoid redundant
  `llseek` calls) — lower priority, smaller expected win, more invasive.

### 3. FrontCoded string dictionary decode — reconfirm "do not revisit" unless GC regains dominance
Still the #1 single allocator category (32.01% / 33.66GB) but this is *steady-state* trunk-
incorporation decode cost, not a fixable inefficiency in the current design without the aggressive
arena rewrite already evaluated and explicitly rejected (near-zero wall-clock gain when tested).
**Do not revisit** unless a future profile shows GC CPU share climbing back toward the ~31%
pre-fix baseline — the precondition ("FrontCoded dominant *and* GC dominant") is currently false
(GC is now 13.4%, down from 31.1%).

### 4. STM / InvertedIndex page re-serialization growth (+9.42% / +52% abs respectively)
Both grew in this profile but are plausibly compaction-driven side effects of the same 5 full-
collection rewrites rather than independent steady-state problems. Lower priority — re-evaluate
after item #1 and #2 land, since fixing the compaction-amplified RoaringBitmap allocator may also
reduce these categories' absolute footprint as a side effect (shared root cause: compaction pass
re-materializing pages).

### Not recommended
- Kryo/OutBuf: fixed at its actual target (−84% absolute), residual share increase is compaction-
  driven serialization *write* CPU, not an allocation problem — no further action.
- OffsetIndex (CHAMP `long[]`): flat in absolute terms this round (share rose only because the
  denominator shrank) — no evidence of a live problem in this profile, despite being flagged as
  "next target" in an earlier, unrelated churn profile (different workload/config). Re-verify with
  a dedicated profile before investing here.

## Query-side
Pending — JMH benchmark suite in progress (`ArtificialEntitiesThroughputBenchmark`, branch760 vs
dev). Will be appended once results are in.

## Bonus finding: benchmark-harness bug (not a #760 issue)
`EvitaCatalogSetup.java` (`evita_test_performance_tests`) hardcodes
`.maxOpenedReadHandles(12)`, while all read benchmarks in
`ArtificialEntitiesThroughputBenchmark` use `@Threads(Threads.MAX)`. On this 24-core machine that
guarantees `PoolExhaustedException` on every warmup/measurement invocation of every read
benchmark — confirmed identical on both `dev` and the branch tip (`git diff` empty for this file),
so it's a pre-existing harness defect, not something introduced by #760. Patched locally in both
worktrees (`maxOpenedReadHandles(Runtime.getRuntime().availableProcessors() * 4)`) to unblock
tonight's measurement; **not yet applied to the actual git history** — worth a small standalone PR
so nobody else hits this the next time they run these benchmarks on a modern many-core box.
