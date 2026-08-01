# Write-churn re-measure — findings (2026-07-08/09 night run)

*Merged from two independent analyses: an orchestrator pass (categorize.py on both collapsed
profiles) and a deeper sub-agent pass that traced allocation to specific call sites. The
sub-agent's root-cause tracing for the RoaringBitmap anomaly supersedes the orchestrator's
weaker hypothesis (bitmap-demotion) — see below.*

## What was measured
`EvitaWarmUpInsertionTest#shouldGenerateLoadOfDataInWarmUpPhase[ALIVE]` (unique/url index,
500k initial insert + 500k churn ops), compaction config `fileSizeCompactionThresholdBytes=1GB`,
`minimalActiveRecordShare=0.4` (temporarily set to match the baseline measurement, reverted after),
on branch tip `3263d8628` (all 5 optimizations committed: CRC32C ladder `c12952397`, Kryo buffer
pooling `2bc70680c`, RoaringArray COW `b306267e6`, FrontCoded threadlocal `fac417755` — already in
the baseline number below — compaction auto-tuner `3263d8628`). All test-file edits reverted after
measurement (`git diff` on the test file is empty).

Profiled with async-profiler 4.4 (`-e cpu` and `-e alloc` as two separate full runs). Categorized
with a substring-based leaf-attribution script (`categorize.py`), validated by reproducing the
baseline's CRC32C 11.82% exactly from the pre-existing baseline collapsed file.

**Churn wall-clock**: 4m 5s (CPU run), 4m 38s (alloc run — profiling overhead). **5 compactions**
fired in each run, each rewriting the `theEntity` collection ~1.0GB→~90-108MB. Driven by the
`maxWasteActiveShare=0.1` hard override (active share repeatedly falls below 10%), NOT by the
`minimalActiveRecordShare=0.4` interval gate — confirms the compaction-auto-tuner model.

## Verdict on the 3 targeted fixes

| Fix | Baseline | New | Verdict |
|---|---|---|---|
| **CRC32C combine ladder** (`c12952397`) | CPU 11.82% (2291 samples) | **CPU 3.09% (373 samples)** | ✅ **FIXED**, −84% absolute. `gf2MatrixTimes` leaf 9.08%→0.44%. Only residual `reverseCrc32c` (1.87%, out of scope) remains. |
| **Kryo/WAL buffer pooling** (`2bc70680c`) | Alloc 14.5% (~18.9 GB) | **Alloc 2.9% (~3.05 GB)** | ✅ **FIXED**, −84% absolute bytes. Exact target frame `WriteOnlyOffHeapWithFileBackupHandle.createInitialOutput` now **0.004%** (essentially eliminated). |
| **RoaringArray COW** (`b306267e6`) | Alloc 12.2% (~15.9 GB) | Alloc 20.09% (~21.13 GB) — **category total up 33%** | ✅ **FIXED on its actual target** / secondary cause for the category rise, see below. |

Total CPU work fell **~38%** (19,380→12,088 async-profiler samples). GC fell **−77% in absolute
samples** (7080→1622; share 36.5%→13.4%, no longer the #1 hotspot) — confirms the alloc-pressure
relief is real and system-wide, not just a share artifact.

### RoaringBitmap — root cause traced, NOT a broken fix
The COW mechanism is verified correct: `clone()` no longer deep-copies `long[]`/`Container[]`
backing arrays — it now allocates only shallow `RoaringArray` + `PersistentRoaringBitmap` wrapper
objects plus a COW-ownership `boolean[]`, totaling **5.26%** of the category (the transactional
clone this fix targeted). The elevated **category total** (20.09%) comes from a *different* code
path: **new bitmap construction inside `InvertedIndex.collectChangedPages` → `ValueToRecordBitmap.<init>`**
— i.e. building fresh `PersistentRoaringBitmap`/`RoaringArray`/`Container[]` objects when
persistence pages are re-materialized during compaction. This path is hit **5× by the 5
compactions** in the profiling window, amplifying its footprint. This is unrelated to the
transactional-commit clone path the fix addressed.

**Not a regression — a different, compaction-amplified allocator that happens to share the
"RoaringBitmap" category label.** No action needed on the COW fix itself; if RoaringBitmap alloc
during compaction becomes a priority, the lever is `collectChangedPages`/`ValueToRecordBitmap`
construction, not the clone path.

## New CPU landscape — near three-way tie at the top (~13-14% each)
1. **FrontCoded string dictionary decode — 13.82%** (`commonPrefix` 4.88%, `decodeAt` 1.98%,
   `decodeAllBytes` 1.40%). Genuine absolute increase (+27%, 1320→1670 samples) — the only category
   that got worse in absolute terms, not just share.
2. **I/O syscalls — 13.72%** (`read` 7.61%, `__write` 2.80%, `llseek` 1.07%). Dominated by the 5
   full-collection compaction rewrites + WAL. Compaction-driven, not steady-state.
3. **GC — 13.42%** (residual; `G1ParScanThreadState::trim_queue_to_threshold` 8.21%).

Categories whose *share* rose but are flat or down in *absolute* terms (not real regressions):
OffsetIndex (≈0% abs change, share doubled only because the denominator shrank), streams (+2%
abs, flat), other/JIT (+5% abs, flat). Kryo (+22% abs share but the *allocation* target is fixed —
this is now serialization *write* CPU, plausibly compaction-driven) and InvertedIndex (+52% abs,
page re-serialization, plausibly compaction-driven) grew but aren't clearly steady-state problems.

## Alloc landscape — dominant site identified
Total volume: **130.64 GB → 105.17 GB (−20%)**.

**`TrunkIncorporationTransactionStage` (the STM merge of a committed transaction into the shared
index) accounts for 70.55% (74.2 GB) of ALL allocation.** FrontCoded string decode is the largest
component within it (steady-state, not persistence). The persistence sub-path
`collectChangedPages` is 35.61% (37.45 GB) and is the piece amplified 5× by compaction (this
overlaps with the RoaringBitmap finding above — `ValueToRecordBitmap.<init>` lives here).

| Category | Baseline % (GB) | New % (GB) |
|---|---|---|
| FrontCoded | 29.0 (37.9) | 32.01 (33.66) — still #1, 27.36% is steady-state trunk-incorporation decode, only 4.65% is persistence |
| RoaringBitmap | 12.2 (15.9) | 20.09 (21.13) — see root-cause above |
| InvertedIndex | 4.0 (5.2) | 10.05 (10.56) |
| STM | 4.9 (6.4) | 9.42 (9.91) |
| streams | 5.0 (6.5) | 8.47 (8.91) |
| Kryo/OutBuf | 14.5 (18.9) | **2.90 (3.05)** — fixed |
| OffsetIndex | 2.8 (3.7) | 5.76 (6.06) |
| WAL-read | — | 1.74 (1.83) |

## FrontCoded — still #1 allocator, aggressive-arena decision reconfirmed
No new fix was shipped beyond the already-baselined "safe" ThreadLocal scratch fix (`fac417755`).
The aggressive arena redesign was evaluated earlier and explicitly not shipped (near-zero
additional wall-clock gain). **This profile reconfirms that decision**: the revival precondition
("FrontCoded dominant **and** GC dominant") is not met — GC is now tamed (13.4%, down from 31.1%).
Absolute FrontCoded bytes even dropped modestly (37.9GB→33.7GB) despite no new work, likely a
side effect of reduced GC pressure changing survivorship/promotion patterns. **Do not revisit**
unless GC becomes dominant again.

## Net assessment
All 3 targeted allocation fixes shipped as designed and verified working on their actual targets:
- CRC32C: clean win, −84% CPU share, no caveats.
- Kryo pooling: clean win, −84% absolute bytes, no caveats.
- RoaringBitmap COW: mechanism proven correct (clone-path defrost cost negligible); the category's
  overall growth is a *different*, compaction-amplified allocator (`collectChangedPages`/
  `ValueToRecordBitmap`) unrelated to what the fix touched — a legitimate follow-up target, not a
  fix failure.

Total allocation volume −20%, total CPU work −38%, GC CPU share 31%→13% (no longer #1). New CPU
landscape is a near three-way tie between FrontCoded (genuine, steady-state), I/O (compaction-
driven), and residual GC — no new mystery bottleneck emerged; everything traces to either already-
understood FrontCoded decode cost or compaction-induced re-serialization.

## Artifacts
`write-churn/` (sibling directory):
`churn-cpu.jfr`/`.collapsed`, `churn-alloc.jfr`/`.collapsed`, `test-cpu.log`, `test-alloc.log`,
`categorize.py` (category attribution script, validated against the pre-existing baseline file).
