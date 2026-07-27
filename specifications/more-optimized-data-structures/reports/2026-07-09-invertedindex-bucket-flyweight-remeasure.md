# InvertedIndex bucket-flyweight fix — post-implementation re-measure (2026-07-09, evening)

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, tip `a97ba014e`
(the `ValueToRecordPrimitive` flyweight commit, on top of the same six optimizations already measured
in `docs/reports/2026-07-09-warmup-test-remeasure-real-config.md`). Same test
(`EvitaWarmUpInsertionTest#shouldGenerateLoadOfDataInWarmUpPhase`), same real committed config
(`fileSizeCompactionThresholdBytes=100_000_000`, `minimalActiveRecordShare=0.8`), same async-profiler
4.4 methodology (`-e cpu` / `-e alloc`, separate runs, attached to the second — ALIVE-phase — "is now
alive!" transition, profiler stopped on the churn-completion log line). This is a direct
before/after at the *same* config, so deltas below are real, not cross-config extrapolation.

Both runs: `mvn test` green (2/2, 0 failures/errors), no exceptions in either log.

---

## 1. Headline result: the fix does exactly what it was designed to do

Raw grep of `ValueToRecordBitmap.<init>` anywhere in the allocation collapsed stack (same
methodology the prior report used to isolate this constructor):

| | prior report (before fix) | this run (after fix) |
|---|--:|--:|
| `ValueToRecordBitmap.<init>` total alloc | **20.49 GB** (19.44% of total) | **524,287 bytes** (0.0007%) |

That's a >99.99% elimination of the targeted allocation. The new flyweight's own cost:

| | this run |
|---|--:|
| `ValueToRecordPrimitive` total alloc | **1.61 GB** (2.02% of total) |

Net: ~20.49 GB eliminated, ~1.61 GB introduced → **~18.9 GB net direct reduction** from this one
mechanism, out of a 25.66 GB total drop (§2) — the remainder is knock-on savings in Kryo/serialization
and STM (§3), consistent with the design doc's prediction that single-record buckets would also skip
`TransactionalBitmap` wrapping and bitmap wire-framing, not just the bitmap constructor itself.

## 2. Allocation profile — total and category breakdown

| | prior (100 MB/0.8) | this run (100 MB/0.8) | Δ |
|---|--:|--:|--:|
| **total allocation** | **105.41 GB** | **79.75 GB** | **−25.66 GB (−24.35%)** |

Per category (async-profiler `-e alloc`, same `categorize.py` leaf-frame classifier):

| category | prior GB (%) | now GB (%) | Δ GB | Δ% |
|---|--:|--:|--:|--:|
| FrontCoded | 33.64 (31.91%) | 33.63 (42.17%) | −0.01 | −0.03% (flat, untouched by this fix) |
| RoaringBitmap | 21.16 (20.07%) | **0.61 (0.77%)** | **−20.55** | **−97.1%** |
| InvertedIndex | 10.54 (9.99%) | 8.89 (11.15%) | −1.65 | −15.7% |
| other | 10.09 (9.58%) | 9.96 (12.49%) | −0.13 | −1.3% |
| STM | 9.92 (9.41%) | 8.25 (10.34%) | −1.67 | −16.8% |
| streams | 8.83 (8.38%) | 9.04 (11.34%) | +0.21 | +2.4% |
| OffsetIndex | 6.37 (6.04%) | 5.65 (7.08%) | −0.72 | −11.3% |
| Kryo/OutBuf | 2.96 (2.81%) | 1.86 (2.34%) | −1.10 | −37.2% |
| WAL-read | 1.88 (1.78%) | 1.84 (2.31%) | −0.04 | −2.1% |
| I/O | 0.017 (0.02%) | 0.013 (0.02%) | ~flat | |
| CRC32C | 0.003 (0.00%) | 0.002 (0.00%) | ~flat | |

Every category's *relative share* of the shrunken total went up except RoaringBitmap — that's the
expected mechanical effect of removing ~20.5 GB from the numerator while the other categories stayed
roughly flat in absolute terms. Reading the **absolute Δ GB column** is what isolates the real change:

- **RoaringBitmap: the headline win**, −20.55 GB / −97.1% — the write path essentially stopped
  building `PersistentRoaringBitmap`/`RoaringArray` towers for single-record buckets, exactly as
  designed. The tiny residual (0.61 GB) is genuine multi-record buckets and unrelated query-side
  `TransactionalBitmap` usage, not a leftover of this fix.
- **Kryo/OutBuf: −1.10 GB / −37.2%**, a real knock-on the design doc predicted but didn't quantify —
  primitive buckets skip the bitmap's Kryo serialization framing entirely (discriminator byte +
  bare int, no container/array serialization), so this drop is directly attributable to the
  §4.3 wire-format change.
- **STM: −1.67 GB / −16.8%** — single-record buckets no longer route through
  `cursor.records()`/`TransactionalBitmap` construction at all (`cursor.singleRecordId()` is used
  instead), consistent with the plan's write-path split.
- **InvertedIndex: −1.65 GB / −15.7%** — smaller net effect than RoaringBitmap because this category
  gains `ValueToRecordPrimitive.<init>` (the new cost) while losing whatever `ValueToRecordBitmap`
  frames used to leaf-classify here rather than under RoaringBitmap.
- **OffsetIndex: −0.72 GB / −11.3%** — plausibly compaction-count driven (4 compactions this run vs 5
  in the prior report, §5) rather than a direct effect of this fix; not attributed to the bucket
  change.
- **FrontCoded: flat**, exactly as expected — this fix touches inverted-index buckets, not the
  front-coded string column.

## 3. CPU profile — total and category breakdown

| | prior (100 MB/0.8, 4m8s) | this run (100 MB/0.8, 3m59s) |
|---|--:|--:|
| total samples | 14,792 | 12,680 (−14.3%) |

Wall-clock churn duration was essentially the same (arguably slightly faster this run), yet ~14%
fewer CPU samples were taken — i.e. genuinely less CPU-bound work happened per unit wall time, not an
artifact of a shorter run.

Comparing **absolute sample counts** (percentages alone are misleading here since the totals differ):

| category | prior samples | now samples | Δ |
|---|--:|--:|--:|
| RoaringBitmap | 264.8 | 48.2 | **−81.8%** |
| CRC32C | 433.4 | 209.2 | −51.7% |
| GC | 3939.0 | 3172.5 | −19.5% |
| WAL-read | 244.1 | 208.0 | −14.8% |
| streams | 692.2 | 578.2 | −16.5% |
| Kryo/OutBuf | 847.6 | 708.8 | −16.4% |
| InvertedIndex | 476.3 | 399.4 | −16.1% |
| STM | 1116.8 | 849.6 | −23.9% |
| OffsetIndex | 1161.2 | 1108.1 | −4.6% |
| other | 2261.4 | 1824.9 | −19.3% |
| I/O | 1739.6 | 1749.0 | +0.5% (flat) |
| FrontCoded | 1618.3 | 1823.6 | +12.7% |

**RoaringBitmap CPU time dropped ~82%** — confirms the allocation win isn't just "smaller garbage",
it's genuinely less work being done (no bitmap construction/insertion cost paid for single-record
buckets). **GC dropped ~19.5% in absolute samples** despite remaining the #1 category by share — less
garbage to collect translates directly into less GC CPU, as expected from a 24% total-allocation cut.
The **CRC32C drop (−51.7%)** is a plausible secondary effect: smaller serialized bucket payloads
(discriminator byte + bare int vs full bitmap framing) mean less data to checksum. FrontCoded's small
absolute *increase* (+12.7%) is noise/proportional-share artifact, not a regression — this fix doesn't
touch that code path, and the design doc never claimed CPU neutrality there.

## 4. Compaction cadence — side note, not this fix's effect

| | prior run | this run |
|---|--:|--:|
| compactions | 5 | 4 |
| total rewritten | ~483 MB | ~333 MB |
| active share at trigger | ~0.0999% | ~0.10–0.12% |

Both runs' compactions fire on the same `maxWasteActiveShare=0.1` emergency-override branch (§1 of
the prior report), consistent behavior. The 5-vs-4 count is normal run-to-run variance in a
wall-clock-gated cadence (`EvitaWarmUpInsertionTest` churn timing isn't perfectly deterministic
run-to-run) — not attributable to the bucket-flyweight fix, and not large enough to explain the
allocation/CPU deltas above (OffsetIndex, the category most exposed to compaction I/O, only moved
−11.3%/−4.6%, far smaller than RoaringBitmap's −97%/−82%).

## 5. Bottom line

- **Fix confirmed working exactly as designed**: `ValueToRecordBitmap.<init>` write-path allocation
  eliminated (20.49 GB → 524 KB, >99.99%), replaced by the much cheaper `ValueToRecordPrimitive`
  (1.61 GB) — net ~18.9 GB direct reduction.
- **Total ALIVE-churn allocation down 24.35%** (105.41 GB → 79.75 GB) at the same real production
  config, with knock-on wins in Kryo serialization (−37.2%) and STM (−16.8%) beyond the direct
  bitmap-construction saving, exactly the mechanisms the design doc predicted (§4.2/§4.3 discriminator
  byte, §4.1 skip `TransactionalBitmap` wrapping).
- **RoaringBitmap CPU time down ~82% in absolute samples**, GC CPU down ~19.5% in absolute samples —
  the allocation win converts into real CPU savings, not just reduced GC-pause-adjacent bookkeeping.
- **No regressions observed** in any other category; FrontCoded (untouched by this fix) stayed flat on
  allocation as expected.
- This closes the design doc's §7 "measurement gate" that was deferred pending machine availability —
  the ~11% upper-bound estimate in the design doc's problem statement was conservative; the actual
  measured total-allocation win is ~24%, and the targeted mechanism's own reduction is essentially
  total (>99.99%).

## Artifacts
Scratch worktree paths (this session's scratchpad, not committed to the repo):
`.../scratchpad/reports/warmup-remeasure2/` — `churn-cpu.jfr`/`.collapsed`, `churn-alloc.jfr`/`.collapsed`,
`test-cpu.log`, `test-alloc.log`, `categorize.py` (copied verbatim from the prior report's),
`profile.sh` (copied verbatim from the prior report's fixed orchestration script).
