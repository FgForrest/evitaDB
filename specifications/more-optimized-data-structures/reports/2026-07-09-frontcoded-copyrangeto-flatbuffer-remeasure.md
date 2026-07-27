# FrontCodedStringColumn copyRangeTo flat-buffer follow-up — re-measure

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, on top of commit
`55ed58adb` (Phase 1: H1 insert/remove + H3 duplicate-share, committed). Uncommitted diff on top of
that: `copyRangeTo`'s slice snapshot (`decodeRangeBytes` → `decodeRangeToFlat`) and splice-assembly
output now use two more thread-local flat `byte[]` + `int[]` buffer pairs
(`DecodeScratch.flat2`/`offsets2`, `flat3`/`offsets3`) instead of an owned `byte[][]` slice plus a
fresh `new byte[]`/`new int[]` per call — the follow-up flagged (not undertaken) in
`docs/reports/2026-07-09-frontcoded-phase1-h1h3-remeasure.md`, scoped and verified by `advisor`
before implementation.

Same test, same methodology (`EvitaWarmUpInsertionTest`, `ALIVE` phase, `-e alloc`, 2nd-occurrence
attach). **This comparison is tighter than the Phase 1 report's**: the baseline here is the Phase 1
report's own run, done in the same session with the same toolchain, and the diff between the two runs
is *exactly* this one change (`git diff --stat 55ed58adb` touches only `FrontCodedStringColumn.java`
and its test). Compaction count matches exactly (4 vs 4, same as Phase 1's own comparison), and —
unlike the Phase 1 report's residual drift — every other category now sits within ~2% of the Phase 1
baseline (§2), including one category (`RoaringBitmap`) moving in the *opposite* direction, which
breaks the suspicious all-negative pattern flagged there. Read that as: this isolated diff produces a
materially cleaner signal than the larger cross-session comparison did.

ALIVE-phase churn: 3m 57s this run vs 4m 6s (Phase 1 report) — modestly faster, consistent with doing
less work, not just less garbage.

Correctness verified first, before profiling (per advisor's shape): 4203 indexing tests + the full
transaction group green, including a new capacity-64/40-entry test exercising `copyRangeTo` across
restart-block boundaries (advisor's condition for a positive verdict — the existing suite's
`BLOCK_SIZE=8` never exceeded one restart block, so the multi-block splice path had zero oracle
coverage before this).

---

## 1. Headline result

| | Phase 1 (H1+H3 only) | this run (+ copyRangeTo flat-buffer) | Δ |
|---|--:|--:|--:|
| **total allocation** | **64.41 GiB** | **51.53 GiB** | **−12.88 GiB (−20.0%)** |
| **FrontCoded category** | **21.14 GiB (32.83%)** | **8.72 GiB (16.92%)** | **−12.42 GiB (−58.7%)** |

FrontCoded accounts for 96.4% of the total drop (12.42 of 12.88 GiB) — essentially clean attribution,
much tighter than Phase 1's 81%. This lands almost exactly on advisor's predicted range (~12 GiB
reclaimed, FrontCoded from ~21 GiB toward ~9-10 GiB): **landed at 8.72 GiB**.

**Cumulative, vs the original pre-Phase-1 baseline** (`docs/reports/2026-07-09-invertedindex-bucket-flyweight-remeasure.md`,
79.75 GiB total / 33.63 GiB FrontCoded): total allocation is now **51.53 GiB (−28.22 GiB, −35.4%)**,
FrontCoded is now **8.72 GiB (−24.91 GiB, −74.1%)** — all from H1 + H3 + this follow-up, with H2 not
even attempted yet.

## 2. Non-FrontCoded categories — confirming clean attribution

| category | Phase 1 GiB (%) | this run GiB (%) | Δ GiB | Δ% |
|---|--:|--:|--:|--:|
| other | 9.17 (14.24%) | 9.05 (17.56%) | −0.12 | −1.3% |
| streams | 8.45 (13.12%) | 8.42 (16.34%) | −0.03 | −0.4% |
| InvertedIndex | 8.38 (13.02%) | 8.24 (15.98%) | −0.14 | −1.7% |
| STM | 7.77 (12.06%) | 7.62 (14.79%) | −0.15 | −1.9% |
| OffsetIndex | 5.47 (8.49%) | 5.45 (10.58%) | −0.02 | −0.4% |
| WAL-read | 1.76 (2.73%) | 1.76 (3.42%) | ~flat | |
| Kryo/OutBuf | 1.70 (2.64%) | 1.70 (3.29%) | ~flat | |
| RoaringBitmap | 0.54 (0.85%) | 0.57 (1.10%) | **+0.03** | **+5.0%** |
| I/O | 0.014 (0.02%) | 0.015 (0.03%) | ~flat | |
| CRC32C | 0.001 (0.00%) | 0.002 (0.00%) | ~flat | |

Every category is within ~2% except `RoaringBitmap`, which moved *up* — the opposite direction from
FrontCoded's drop, and the opposite direction every category moved in the Phase 1 report's comparison.
This is exactly what genuine run-to-run noise should look like (scattered, small, not systematically
one-directional), in contrast to Phase 1's nine-for-nine same-direction shift. Combined with the
identical compaction count (§ intro) and the near-total (96.4%) attribution to FrontCoded, this
comparison does not carry Phase 1's caveat — the total-allocation number here can be quoted directly.

## 3. Where the remaining FrontCoded allocation lives

| method | GiB | % of total | % of FrontCoded |
|---|--:|--:|--:|
| `decodeAt` (→ `byte[]` scratch growth) | 3.98 | 7.72% | 45.6% |
| `decodeAt` (→ `String` result) | 2.03 | 3.95% | 23.3% |
| `finishEncode` (→ `byte[]` trimmed blob) | 1.90 | 3.68% | 21.8% |
| `decodeAllBytes` (→ `byte[]`, `clearAt`/`fillEmpty` only) | 0.50 | 0.96% | 5.7% |
| `newRestartTable` (→ `int[]` restarts) | 0.14 | 0.27% | 1.6% |
| `findKeyPosition` (→ `InsertionPosition`) | 0.07 | 0.14% | 0.8% |
| `decodeAllBytes` (→ `byte[][]`, `clearAt`/`fillEmpty` only) | 0.04 | 0.07% | 0.4% |
| column instance (→ `FrontCodedStringColumn`, split/allocate) | 0.03 | 0.07% | 0.4% |
| `insertKeyAt` (→ `byte[]`, the one unavoidable `getBytes()`) | 0.03 | 0.05% | 0.3% |

**`copyRangeTo` and `decodeRangeToFlat` do not appear in this table at all** — both collapsed to
allocation below the profiler's noise floor, exactly like `insertKeyAt`/`removeKeyAt` did after H1.
The 12.3 GiB the Phase 1 report attributed to `decodeRangeBytes` + `copyRangeTo`'s `byte[]`/`int[]`
assembly is gone.

What's left is now dominated by the **search path**: `decodeAt`'s `byte[]`/`String` cost (6.01 GiB,
69% of remaining FrontCoded) is `findKeyPosition`'s binary search decoding each candidate to compare
it — this is exactly what **H2** (the BMP/ASCII byte-compare hypothesis, not yet implemented) targets.
`finishEncode`'s 1.90 GiB is the one allocation this design cannot remove without breaking the
reallocation-not-in-place invariant H3 depends on (§ the impl plan's deferred in-place-`data`
follow-up) — every mutator must produce a fresh trimmed blob. The remaining lines are all cold paths
(`clearAt`/`fillEmpty`, restart-table allocation, column instantiation on split) at their expected
small, bounded cost.

## 4. What this means

- **Advisor's prediction confirmed almost exactly**: ~12 GiB reclaimed (predicted "~7 to ~12 GiB
  taking the win from ~7 to ~12 GiB" for the flat2 slice alone, plus flat3 assembly reclaiming "that
  ~5 GiB too" — landed at 12.42 GiB combined, matching the upper end of the combined prediction).
- **The multi-restart-block test closed a real, pre-existing coverage gap** (not just this change's
  gap) — every `copyRangeTo` test in the suite before this had run at `BLOCK_SIZE=8`, below
  `RESTART_INTERVAL=16`, so the restart-seek-then-rebase index arithmetic this rewrite depends on
  had never been exercised by any test, old or new, before now.
- **Phase 1 is now, in total, past its original ~55 GiB gate**: 51.53 GiB, better than the plan's
  Step 1.4 target. The gap that made Phase 1 "under-shoot" in the original report is closed.
- **Next natural target is H2** — the search-path `decodeAt` cost (6.01 GiB, now the single largest
  remaining FrontCoded allocator) is precisely what the BMP/ASCII byte-compare hypothesis was designed
  to attack. Per the agreed sequencing (H1+H3 → JMH for H2 → H2 if it pays), the next step is the
  Phase 2 JMH microbenchmark, not implementing H2 directly.

## 5. Methodology notes

- Absolute GiB figures use binary units (÷ 1024³), matching prior reports.
- Unlike the Phase 1 report, this comparison's baseline is a same-session, same-toolchain run with an
  exactly-scoped diff (`git diff --stat 55ed58adb`), so it does not carry that report's cross-session
  caveat — see §2 for the confirming evidence (compaction count, category drift direction).
- Correctness was verified before profiling: `FrontCodedStringColumnTest` (25 tests, including the new
  multi-restart-block test), the full `TransactionalBucketBPlusTree*`/sibling value-column suites,
  the `indexing & !slow` group (4203 tests), and the `transaction & !slow` group (excluding the one
  pre-existing, unrelated `shouldRemoveOldDataFilesAndVerifyTimeTravel` failure already flagged in the
  Phase 1 report) — all 0 failures/errors.

## 6. Post-measurement cleanup (behavior-preserving, not re-profiled)

After this measurement, code review found two remaining duplications and one compiler warning, all
fixed and re-verified against the same test suites (4203 indexing + targeted column/tree tests, 0
failures) but **not** re-profiled — both fixes are structurally behavior-preserving (same bytes
computed, same allocations), so re-running the ~5-minute alloc profile would not have told us anything
the reasoning below doesn't already establish:

- **`encode(byte[][], int)`** (used only by the cold `clearAt`/`fillEmpty` truncation paths since
  Phase 1) duplicated the entire per-entry shared-prefix/varint-encode loop already present in
  `encode(byte[], int[], int)`. It now flattens its `byte[][]` into a temporary flat/offsets pair once
  and delegates, so that loop exists in exactly one place. Zero hot-path impact — this method is never
  called by `insertKeyAt`/`removeKeyAt`/`copyRangeTo`.
- **`decodeAllToFlat`/`decodeRangeToFlat`** (both hot-path) duplicated the entire per-entry
  varint-decode/self-referential-copy loop, differing only in start position, entry count, and which
  `DecodeScratch` buffer pair they read/write. Unified into a shared `decodeRangeToFlatCore(scratch,
  secondary, fromInclusive, toExclusive)`, with the `flat`-vs-`flat2` buffer choice as a boolean
  resolved *once* per call, outside the per-entry loop — the loop body itself is now byte-for-byte
  identical to before, so this adds no per-entry branching on the hot `decodeAllToFlat` path.
- The constructor was reverted from calling a shared `resetToEmpty()` helper back to inline field
  assignment: calling it from the constructor made the compiler's definite-assignment analysis unable
  to verify the `@Nonnull byte[] data` / `@Nonnull int[] restartOffsets` fields are initialized,
  producing a warning `resetToEmpty()` itself doesn't have when called later (fields are already
  definitely-assigned by the constructor before any other method can run). `resetToEmpty()` remains
  shared between the two `encode()` overloads' `n == 0` branches, where this issue doesn't apply.
