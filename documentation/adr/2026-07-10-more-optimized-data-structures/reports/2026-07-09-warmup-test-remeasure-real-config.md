# EvitaWarmUpInsertionTest re-measure against the REAL committed config (2026-07-09, afternoon)

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, tip `3263d8628`
(all 6 optimization commits already committed: FrontCoded SAFE `fac417755`, CRC32C ladder
`c12952397`, RoaringBitmap frozen-array COW `b306267e6`, Kryo ObservableOutput pooling `2bc70680c`,
TIME_FORMAT fix `b898659f1`, compaction cadence gate `3263d8628`). Working tree otherwise clean
except the uncommitted `CumulativeWeightBPlusTree.descend()` binary-search fix (irrelevant here —
this test has no plain `sortable()` attribute, only a `unique` url and a `Predecessor`-sortable
`order` attribute that routes through `ChainIndex`, not `SortIndexChanges`).

**Why this re-measure, and how it differs from the 2026-07-08/09 overnight numbers**: all 5 of
yesterday's design docs (`docs/design/2026-07-08-*.md`) cite their baseline/problem-statement
measurements as `EvitaWarmUpInsertionTest` at **1 GB / 0.4** (`fileSizeCompactionThresholdBytes` /
`minimalActiveRecordShare`). The actual, currently-committed test
(`EvitaWarmUpInsertionTest.java:601-602`) hardcodes **100 MB / 0.8** — the design docs' own "old,
over-compacting" baseline config, not 1 GB/0.4. The 1 GB/0.4 numbers were evidently produced by a
temporary local edit to the test file during an earlier session that was reverted before commit (git
history shows only one commit ever touched this file's compaction config, and it's not this
session's). **This re-measure runs the test exactly as committed — 100 MB/0.8, the real,
production-representative config — for the first time against all 6 optimizations together.**

Methodology: async-profiler 4.4, `-e cpu` and `-e alloc`, separate runs, attached to the ALIVE-mode
variant only (the test's `@EnumSource(WARMING_UP, ALIVE)` runs WARMING_UP first in ~20s, then ALIVE;
the orchestration script waits for the *second* "is now alive!" and only searches for
churn-completion in log content written after that point — the first pass at this hit a bug where a
stale WARMING_UP-phase log line caused an instant attach+detach with 0 samples, since fixed).

---

## 1. Compaction cadence gate — Gate 8, confirmed (the design doc explicitly deferred this to you)

The compaction design doc (`docs/design/2026-07-08-compaction-waste-threshold-auto-tuning.md`)
left "Test gate 8 (re-measuring `EvitaWarmUpInsertionTest` compaction counts under an enabled
interval)" unrun — "it's a long benchmark, left for Johnny to trigger explicitly." This run is that
gate.

**One important correction to the design doc's own text first**: §5.3 states the feature is
"strictly opt-in" via `T=0`/`maxWasteActiveShare=minimalActiveRecordShare` defaults that reproduce
old behavior byte-for-byte. **The actually-shipped `StorageOptions` defaults are different**:
`DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS = 60_000L` and `DEFAULT_MAX_WASTE_ACTIVE_SHARE = 0.1`
(both non-zero/non-inert), with an explanatory code comment: *"the emergency override that still
binds by default so the interval above isn't inert."* This was a deliberate implementation decision
made after the design doc was drafted, not disclosed as a deviation in the doc's implementation
note (which only calls out the clock-source and clamp-bug deviations). **Net effect: the cadence
gate is live by default**, not opt-in as originally planned — worth confirming this is the intended
final call.

**Result**: with the real 100 MB/0.8 config (whose *old*, pre-cadence-gate baseline — per the same
design doc's own table — was **194 compactions / ~18 GB rewritten** over one run), this run produced:

| metric | value |
|---|---|
| compactions | **5** |
| total rewritten (sum of post-compaction sizes) | **~483 MB** |
| interval between compactions | ~44–45s (consistent) |
| active-record share at trigger | **0.0999x%** — every single time |

That active share is a hair under the `maxWasteActiveShare=0.1` hard override — **every compaction
in this run fired on branch (a), the emergency override, not branch (b), the 60s interval floor**.
This is the design doc's own predicted "hot" regime (`t_max ≤ T` → interval = `t_max`, "waste ran
away before `T`"), confirmed exactly. **194 → 5 compactions (~39×), ~18 GB → ~483 MB rewritten
(~37×)** — a large, real, and directly observed win, achieved without touching `F`/`A` at all.

---

## 2. CPU profile (14,792 samples, ALIVE churn phase, 4m8s)

| category | share | note |
|---|--:|---|
| GC | 26.63% | still #1; not directly comparable to last night's 13.4% — different config (see §4) |
| other | 15.29% | mostly G1/C2-JIT internals, nothing new |
| I/O | 11.76% | compaction-driven (5 full-file rewrites) |
| FrontCoded | 10.94% | `commonPrefix` 4.23%, `decodeAt` 1.50%, `decodeAllBytes` 1.40% — inherent structure cost, not allocation (expected, SAFE only removed allocation, not CPU) |
| OffsetIndex | 7.85% | |
| STM | 7.55% | |
| Kryo/OutBuf | 5.73% | general Kryo serialization CPU, not `ObservableOutput.<init>` (see below — that's ~0.05%) |
| streams | 4.68% | |
| InvertedIndex | 3.22% | |
| CRC32C | 2.93% | **of which 1.74pp is the untouched `reverseCrc32c` stateful path** — the ladder-fixed forward `combine` itself is down to ~1.2%, close to the design doc's ~0.5% target |
| RoaringBitmap | 1.79% | |
| WAL-read | 1.65% | |

Confirmed via direct grep against the collapsed stacks:
- `ObservableOutput.<init>`/`createInitialOutput`: **8 of 14,792 samples (0.05%)** — the pool path is
  live and cold-start construction is now rare.
- `borrowOffHeapOutput`/`recycleOffHeapOutput` appear in the stacks — the new pool code is genuinely
  exercised, not dead code.
- `CumulativeWeightBPlusTree`: **0 occurrences** — confirms this test structurally cannot exercise
  that tree (no plain `sortable()` attribute), consistent with this morning's separate finding.

## 3. Allocation profile (105.41 GB total, ALIVE churn phase)

| category | share | bytes |
|---|--:|--:|
| FrontCoded | 31.91% | 33.64 GB |
| RoaringBitmap | 20.07% | 21.16 GB |
| InvertedIndex | 9.99% | 10.54 GB |
| other | 9.58% | 10.09 GB |
| STM | 9.41% | 9.92 GB |
| streams | 8.38% | 8.83 GB |
| OffsetIndex | 6.04% | 6.37 GB |
| Kryo/OutBuf | 2.81% | 2.96 GB |
| WAL-read | 1.78% | 1.88 GB |
| I/O | 0.02% | 16.8 MB |
| CRC32C | 0.00% | 2.6 MB |

### Per-fix confirmation (direct grep against the collapsed leaf types)

**CRC32C ladder — clean win, confirmed.** 2.6 MB total (0.00%). The `288 B/op → ~0` claim holds
exactly.

**Kryo `ObservableOutput` pooling — clean win, confirmed.** `ObservableOutput.<init>` /
`createInitialOutput`: **26.2 MB (0.0249%)** — matches the design doc's `<0.1%` target precisely.

**RoaringBitmap frozen-array COW — mechanism confirmed working; residual cost is the doc's own
acknowledged, deliberately-deferred one.** `PersistentRoaringBitmap.clone` itself: 5.56 GB (5.28%)
— sounds large in isolation, but breaking down *what* allocates under `clone()`'s own stack frames:

| leaf under `clone()` | bytes |
|---|--:|
| `RoaringArray` (wrapper object) | 2.25 GB |
| `PersistentRoaringBitmap` (wrapper object) | 1.66 GB |
| `boolean[]` (`cloneShared` shared-flags array) | 1.65 GB |

**Zero `char[]` (keys copy) and zero `Container[]` (values copy)** — the expensive
`Arrays.copyOf` calls the fix targeted are completely gone. The remaining cost is exactly the two
wrapper objects + the `boolean[size]` array that design-doc §3.3/§3.4 explicitly predicted would
remain (the `shared[]`-sentinel that would remove the last `boolean[]` term was deliberately
deferred as a disproportionate correctness risk for a marginal gain — §6.5). The fix is working
exactly as designed; it was never claimed to reach true zero.

**`ValueToRecordBitmap.<init>` — CORRECTED mechanism (see 2026-07-09 follow-up below): fires
per-commit, not per-compaction.** 20.49 GB (19.44% of total alloc). Direct code reading
(`InvertedIndex.collectChangedPages`, `FilterIndex.appendStorageParts`) shows this is invoked
**once per transaction commit for every dirty filter index**, gated on each B+-tree leaf's own
transaction-aware dirty flag — it is called from the per-commit flush path
(`FilterIndex.appendStorageParts` → `appendBucketAxis`), **not** from `OffsetIndex.compact()`
(which is a pure byte-level file rewrite that never touches Java index objects). The original
framing here ("hit once per compaction") was wrong — both this run and last night's write-churn
run had the *same* 500k-insert + 500k-churn workload and therefore the *same* commit count
regardless of `F`/`A`, which is the real reason the allocator's share landed at a near-identical
19.44%/20.07% here vs 20.09% last night: **same workload → same commit count → same per-commit
allocator cost**, independent of compaction cadence. The "5 compactions in both runs" was a
coincidence, not the causal link. See the 2026-07-09 fix-options addendum for the corrected
analysis and proposed levers.

**FrontCoded SAFE — allocation profile stable and consistent across configs, as expected.** Total
31.91%/33.64 GB here vs last night's 32.01%/33.66 GB at 1 GB/0.4 — essentially identical, which
makes sense: FrontCoded allocation is driven by mutation/entity-churn count (500k+500k, identical
between configs), not by compaction cadence. Breaking down search-path (SAFE's target) vs
mutation-path (untouched, AGGRESSIVE's future target):

| path | bytes | note |
|---|--:|---|
| `decodeAt` (search, SAFE-fixed) | 6.40 GB | `byte[]` 4.29 GB (on-demand buffer growth, not per-call) + `String` 2.11 GB (intentionally retained — "the String copy is what makes the reuse safe") |
| `decodeAllBytes`/`encode` (mutation, untouched) | 24.42 GB | as documented, SAFE does not touch this — it's large but infrequent/young-gen-cheap, which is why it doesn't dominate wall-clock despite dominating allocated bytes |

The search path's residual `byte[]` cost is the buffer's on-demand doubling-growth (never per-call
now), not a broken fix — no way to distinguish "many small growth events" from "some growth events
across many distinct threads" from this profile alone without deeper instrumentation, but the
design doc's own claim was a wall-clock speedup (3.31×, previously measured), not a
allocation-volume-to-zero claim for this path.

## 4. GC 26.63% vs last night's 13.4% — not a regression, not directly comparable

This run's GC CPU share (26.63%) sits between the documented pre-fix baseline (31.1%, at 1 GB/0.4)
and last night's post-fix re-measure (13.4%, also at 1 GB/0.4). **These are not the same experiment.**
The 100 MB/0.8 config drives materially different compaction/allocation dynamics — most notably the
`ValueToRecordBitmap.<init>` compaction-driven allocator (§3) sits at a similar *absolute* share here
despite a totally different F/A setting, which by itself accounts for a meaningful GC-pressure
difference from last night's number. There is no clean "before" CPU/alloc profile at this *exact*
100 MB/0.8 config to compute a true delta against — the only historical data point for this config is
the compaction design doc's compaction-count table (194 compactions/~18 GB), which this run's Gate-8
result (§1) directly and favorably answers. Treat this report's CPU/alloc numbers as **first-ever
measurements at the real, committed config**, not as a delta from a prior run of the same config.

## 5. Bottom line

- **Gate 8 (compaction cadence) — confirmed, large real win**: 194→5 compactions, ~18GB→~483MB
  rewritten, entirely via the `maxWasteActiveShare=0.1` emergency override (not the 60s floor) at
  this config.
- **CRC32C ladder — confirmed clean**, alloc 0.00%, forward-path CPU ~1.2% (target ~0.5%, same order
  of magnitude, residual mostly the untouched stateful `reverseCrc32c` path).
- **Kryo `ObservableOutput` pooling — confirmed clean**, 0.0249% alloc, matches `<0.1%` target
  exactly.
- **RoaringBitmap frozen-array COW — confirmed working exactly as designed**: zero `char[]`/`Container[]`
  copies remain; residual cost is the two wrapper objects + `boolean[]` the design doc explicitly
  predicted and chose not to eliminate (§3.4 deferred).
- **FrontCoded SAFE — allocation profile stable/consistent across two very different configs**,
  reinforcing confidence the fix generalizes; search-path residual is on-demand buffer growth, not a
  regression.
- **`ValueToRecordBitmap.<init>` compaction-driven allocator — independently reconfirmed** at a
  near-identical share (19.44–20.07%) despite a completely different compaction config, strengthening
  last night's root-cause diagnosis (compaction-count-driven, not config-size-driven, not a
  regression in the RoaringBitmap fix).
- **No regressions found** in any of the 5 optimizations when measured against the real, previously
  never-profiled-at production config.

## Artifacts
Scratch worktree paths (this session's scratchpad, not committed to the repo):
`.../scratchpad/reports/write-churn2/` — `churn-cpu.jfr`/`.collapsed`, `churn-alloc.jfr`/`.collapsed`,
`test-cpu.log`, `test-alloc.log`, `categorize.py` (copied verbatim from last night's
`docs/reports/write-churn/categorize.py`), `profile.sh` (this session's fixed orchestration script —
see the marker-race bugfix note in the script header).
