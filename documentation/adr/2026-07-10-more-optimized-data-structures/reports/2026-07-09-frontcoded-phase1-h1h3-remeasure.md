# FrontCodedStringColumn Phase 1 (H1 flat-buffer + H3 duplicate-share) — post-implementation re-measure

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, on top of tip
`a97ba014e` (uncommitted). Implements Phase 1 of
`docs/design/2026-07-09-frontcoded-allocation-impl-plan.md`: `insertKeyAt` / `removeKeyAt` /
`copyRangeTo` rewritten to splice a thread-local flat `byte[]` + `int[]` offset table instead of
decoding to a transient `byte[][]` of `size` individually-allocated entries (H1); `duplicate()`
structurally shares `data`/`restartOffsets` instead of deep-copying (H3).

Same test (`EvitaWarmUpInsertionTest#shouldGenerateLoadOfDataInWarmUpPhase`, `ALIVE` parameterization),
same async-profiler 4.4 methodology (`-e alloc`, attached at the second/ALIVE-phase "is now alive!"
transition, stopped on the second churn-completion line) as
`docs/reports/2026-07-09-invertedindex-bucket-flyweight-remeasure.md`, whose post-fix run (also at tip
`a97ba014e`) is used as the baseline here.

**This is a cross-session, not same-session, comparison** — the baseline run is from an earlier
session's report, not a revert/restore of this diff in this session. The workload itself is fully
deterministic (`churn()` uses `new Random(42)`, `insertInitial` is a plain sequential loop — both
runs process the *identical* sequence of entity operations, not "different random churn keys" as an
earlier draft of this report incorrectly claimed), and compaction count matches exactly (**4 vs 4**,
§4), which rules out the most likely confound (compaction cadence is wall-clock-gated and varied
4-vs-5 between two *baseline* runs in the prior report). That still leaves the small systematic
co-movement in every untouched category (§2) unexplained by anything checked here — see §2's caveat.
A same-session revert-diff/reprofile/restore run would fully isolate it; not done here (see §6).

ALIVE-phase churn: 4m 6s this run vs ~4m (3m59s CPU / ~4m alloc) baseline — comparable workload
duration, not a shorter/easier run.

`mvn test` on the touched code green throughout (see implementation notes below); no exceptions in
the profiled run's log.

---

## 1. Headline result

Two kinds of evidence here carry different weight. Ranked most to least robust:

1. **Within-run, workload-independent (strongest — doesn't depend on comparing across sessions):**
   `insertKeyAt`/`removeKeyAt` are now **effectively zero allocation** (0.028 GiB combined across
   500k churn ops — just the one unavoidable per-insert `getBytes()`; see §3). H1 did exactly what it
   was designed to do for those two mutators, full stop, independent of any baseline.
2. **FrontCoded category, cross-session:** **33.63 GiB (42.17%) → 21.14 GiB (32.83%), −12.49 GiB
   (−37.1%).** The workload driving both runs is deterministic and identical (§ above), so this delta
   is directly attributable to the code paths this diff touches — a real, large reduction.
3. **Total allocation, cross-session (weakest — carries the unexplained cross-run drift):**
   **79.75 GiB → 64.41 GiB, −15.34 GiB (−19.2%).** FrontCoded accounts for 81% of this (12.49 of 15.34
   GiB); the remaining 19% is a small, *systematic* (all-negative, not scattered) shift across every
   untouched category (§2) that this report could not fully attribute to a known confound. Treat the
   FrontCoded number (#2) as the load-bearing claim, not this total.

This under-shoots the plan's optimistic ~24.8 GiB (H1+H3) estimate, for a concrete, verified reason
(§3): `copyRangeTo`'s "owned slice" compromise (kept as small `byte[][]` allocations rather than a
full flat-buffer rewrite, because the plan assumed it was cold/rare) turned out to be the single
largest remaining FrontCoded allocator under a churn-heavy workload, where B+ tree split/merge/steal
fires far more often than assumed.

## 2. Allocation profile — category breakdown

| category | baseline GiB (%) | this run GiB (%) | Δ GiB | Δ% |
|---|--:|--:|--:|--:|
| FrontCoded | 33.63 (42.17%) | 21.14 (32.83%) | **−12.49** | **−37.1%** |
| other | 9.96 (12.49%) | 9.17 (14.24%) | −0.79 | −7.9% |
| streams | 9.04 (11.34%) | 8.45 (13.12%) | −0.59 | −6.5% |
| InvertedIndex | 8.89 (11.15%) | 8.38 (13.02%) | −0.51 | −5.7% |
| STM | 8.25 (10.34%) | 7.77 (12.06%) | −0.48 | −5.8% |
| OffsetIndex | 5.65 (7.08%) | 5.47 (8.49%) | −0.18 | −3.2% |
| WAL-read | 1.84 (2.31%) | 1.76 (2.73%) | −0.08 | −4.3% |
| Kryo/OutBuf | 1.86 (2.34%) | 1.70 (2.64%) | −0.16 | −8.6% |
| RoaringBitmap | 0.61 (0.77%) | 0.54 (0.85%) | −0.07 | −11.5% |
| I/O | 0.013 (0.02%) | 0.014 (0.02%) | ~flat | |
| CRC32C | 0.002 (0.00%) | 0.001 (0.00%) | ~flat | |

**Caveat, stated plainly:** every non-FrontCoded category moved in the *same direction* (down), all by
single-digit percent. For a workload that is fully deterministic between runs (§ intro) and has an
identical compaction count (4 vs 4, §4 — ruling out the specific confound the baseline report's own
§5 flagged as the likely explanation for a *different* run-to-run comparison it made), nine-for-nine
same-direction movement is not obviously "noise" and this report does not have a confirmed cause for
it. A plausible but *unverified* mechanism: this diff makes the touched code faster, which can shift
*when* (wall-clock) the cadence-gated compaction trigger fires relative to op count, changing exactly
which records land in each compaction batch even with the same compaction *count* — that would ripple
into OffsetIndex/streams/Kryo/STM without being a direct effect of the FrontCoded change. This is a
hypothesis, not a finding; a same-session revert/restore run (§5) would confirm or rule it out. The
category *shares* (%) shifted additionally because the denominator (total) shrank — the **absolute Δ
GiB column** is the more meaningful read, and on it FrontCoded is unambiguously the dominant term even
if the small residual drift elsewhere isn't fully explained.

## 3. Where the remaining FrontCoded allocation lives (by originating method)

| method | GiB | % of total | % of FrontCoded |
|---|--:|--:|--:|
| `decodeRangeBytes` (→ `byte[]` per slice entry) | 6.74 | 10.46% | 31.9% |
| `copyRangeTo` (→ `byte[]` splice output) | 4.40 | 6.84% | 20.8% |
| `decodeAt` (→ `byte[]` scratch growth) | 4.03 | 6.26% | 19.1% |
| `decodeAt` (→ `String` result) | 2.00 | 3.11% | 9.5% |
| `encode` (→ `byte[]` trimmed blob) | 1.96 | 3.04% | 9.3% |
| `copyRangeTo` (→ `int[]` offsets) | 0.63 | 0.97% | 3.0% |
| `decodeRangeBytes` (→ `byte[][]` slice array) | 0.54 | 0.84% | 2.6% |
| `decodeAllBytes` (→ `byte[]`, now only `clearAt`/`fillEmpty`) | 0.53 | 0.83% | 2.5% |
| `encode` (→ `int[]` restarts) | 0.13 | 0.21% | 0.6% |
| `decodeAllBytes` (→ `byte[][]`, now only `clearAt`/`fillEmpty`) | 0.05 | 0.07% | 0.2% |
| `insertKeyAt` (→ `byte[]`, the one unavoidable `getBytes()`) | 0.03 | 0.04% | 0.1% |

**`copyRangeTo` + its `decodeRangeBytes` helper together are now ~58% of all remaining FrontCoded
allocation (12.3 of 21.14 GiB)** — larger than `decodeAt` (the search-path `String`/scratch-growth
cost, 28.6%) or `encode` (9.9%). This is the plan's Step 1.1.5 "fiddly method" compromise showing up
exactly where predicted it might: the plan explicitly chose a small **owned `byte[][]`** slice for
`copyRangeTo` rather than a full flat-buffer splice, reasoning it was "split/merge (far colder than
per-record insert/remove)" — cheap in isolation, but this churn workload (500k random remove/update)
drives B+ tree rebalancing (steal/merge/split) far more often than that assumption anticipated.

By contrast, `insertKeyAt`/`removeKeyAt` (not shown above except the one unavoidable per-insert
`getBytes()`) are now **effectively zero allocation** — no `decodeAllBytes`/`encode(byte[][],int)`
byte[]/byte[][] cost at all, confirming H1 eliminated exactly what it targeted for those two mutators.
`decodeAllBytes` (0.58 GiB combined) is now only reached via the still-untouched `clearAt`/`fillEmpty`
cold paths, as designed.

## 4. Compaction cadence comparison

| | baseline (prior report, "this run" therein) | this run |
|---|--:|--:|
| compactions | 4 | 4 |

Identical count. This rules out compaction *count* as the driver of §2's residual cross-category
drift — but, as noted there, it does not rule out the trigger *timing* (wall-clock-gated cadence
potentially firing at a different point relative to op count if the touched code runs faster), which
this report has not checked and does not claim to have ruled out.

## 5. What this means for the plan

- **H1 for insert/remove: fully validated**, near-zero residual allocation, exactly as designed —
  this finding does not depend on the cross-session comparison at all (§1, evidence tier 1).
- **H3 (duplicate share)**: not independently isolated in this profile (its ~1 GiB target was
  always a small fraction of the total and is folded into the overall FrontCoded number); no
  correctness regression across 4005 indexing + 1367 transaction tests (see PR notes).
- **H1 for `copyRangeTo`: partially validated, and this is the real gap vs. the plan's target.**
  The plan's Step 1.4 gate was ~55 GiB total allocation; this run measured 64.4 GiB. The owned-slice
  compromise in `copyRangeTo`/`decodeRangeBytes` reduced allocation there (no more full-column
  `decodeAllBytes()` + `Arrays.copyOf(dstAll, newSize)` per call) but did not eliminate it, and per
  §3 it is now ~58% of all remaining FrontCoded allocation — the single biggest lever left on the
  table. **This is a decision for Johnny, not a footnote:** extend the flat-buffer treatment to
  `copyRangeTo`'s slice snapshot (replacing the small `byte[][]` in `decodeRangeBytes` with a bounded
  flat/offset pair) now, folding it into this same Phase-1 PR before it's measured again — or land
  Phase 1 as-is and treat the `copyRangeTo` rewrite as a separate, later follow-up. Neither choice is
  made here.
- **Net result**: the FrontCoded-category number (−37.1%, §1 tier 2) is the most defensible headline;
  the total-allocation number (−19.2%, §1 tier 3) is directionally real but carries unexplained
  cross-run drift and should not be quoted as a clean, isolated Phase-1 effect without the same-session
  check in §6.

## 6. Methodology notes / caveats

- Absolute GiB figures use binary units (÷ 1024³) to match the prior report's convention; percentages
  are unit-invariant and are the more load-bearing comparison.
- **This is a cross-session comparison, not a same-session revert/restore.** Both runs are the same
  test, same config, same tip (`a97ba014e`), and the workload is deterministic (intro), but the
  baseline run predates this session (and this session also reinstalled `evita_engine`/
  `evita_store_server` to a fresh local-repo jar along the way, for an unrelated reason — a stale
  jar from earlier uncommitted work, not this diff). None of that should affect *this* run's own
  profiled numbers (the profiling build recompiles from source in its own reactor session regardless
  of what's in `~/.m2`), but it means "the only difference between the two runs is the diff" is not a
  claim this report can make — only "the workload and config are identical" is. **A same-session
  isolation run (revert this diff → reprofile at the same tip → restore) would fully close this gap**
  and is offered as an option, not performed here since the conclusions above don't depend on it.
- The first profiling attempt on this run mis-attached to the test's short `WARMING_UP`
  parameterization (a priming round, ~20s) instead of the `ALIVE` round (~4min, the one the baseline
  profiled) due to a script bug (grep matched the first of two identical-text log lines); that capture
  (~1 MB, discarded) is not used anywhere in this report.
