# FrontCodedStringColumn H2 — production re-measure (write churn, query path, tuned config)

Three separate measurements, run in sequence, each answering a different question the Phase 2 JMH
micro (`docs/reports/2026-07-09-frontcoded-h2-jmh-phase2.md`) could not: does the byte-compare fast
path actually fire in production, does it help queries, and how much of the write-path cost is
`FrontCodedStringColumn` itself versus storage-layer overhead (compaction/fsync) unrelated to H2.

All three use the H2 worktree (`760-frontcoded-h2-bmp-bytecompare`, branched from `202a335da` —
H1+H3+copyRangeTo, committed) unless noted as the "pre-H2" control, which uses the main repo's actual
committed state (same commit, no H2 diff). A real methodology bug was caught and fixed mid-session:
`~/.m2`'s installed `evita_engine` jar had been left holding the H2 build from an earlier fix-up
`install`, which would have silently made the "pre-H2" comparison run H2 code too — caught via
`javap` before trusting the numbers, and the jar was reinstalled from the main repo to restore a
clean environment.

## 1. Write-churn ALIVE-phase alloc profile (default config) — mechanism confirmed, category win small

Same methodology as prior FrontCoded reports (`EvitaWarmUpInsertionTest`, ALIVE phase, `-e alloc`,
2nd-occurrence attach, scoped to the worktree via `-am` so it never touches `~/.m2`).

| | pre-H2 (copyRangeTo) | post-H2 (default config) | Δ |
|---|--:|--:|--:|
| total alloc | 51.53 GiB | 50.53 GiB | −1.9% |
| FrontCoded | 8.72 GiB (16.92%) | 7.89 GiB (15.61%) | **−9.6%** |
| ALIVE duration | 3m57s | 4m04s | ~flat |
| compactions | 4 | 4 | matched |

Far short of the ~58-74% category swings H1/copyRangeTo delivered. **Root cause, found by tracing
the caller chain** (not just "which FrontCoded method", but "who calls it"):

- `findKeyPosition` → **0 GiB** String allocation. Its own allocation is just the one-time probe
  `getBytes(UTF_8)` (0.109 GiB) + the `InsertionPosition` return value (0.076 GiB). The byte-compare
  fast path fires for essentially every eligible call — the mechanism works exactly as designed.
- The remaining 4.75 GiB (60% of FrontCoded) traces **100%** through `keyAt()` ← `decodeAt()` ←
  `SingleLeafBucketCursor.value()` — a cursor value-read path used during mutation (materializing the
  current key for `addRecord`/`removeRecord`'s internal bookkeeping), not the search path at all.

So the ~6.5 GB the original ALIVE-churn profile attributed to `decodeAt` (and the plan assumed was
`findKeyPosition`'s cost) was misattributed — that report's leaf-frame categorization couldn't
distinguish `findKeyPosition`'s calls from `keyAt`'s calls, since both route through
`decodeAt → new String`. In this workload `keyAt` via cursor `.value()` was doing almost all of it,
and H2 was never scoped to touch that (the plan explicitly listed `keyAt`/`appendKey`/`asBoxedArray`
as out-of-scope cold paths).

## 2. Query-path equality lookup — real, substantial win

Built `FrontCodedTreeQueryBenchmark` (new, `evita_test/evita_performance_tests/.../spike/`): drives
the actual production `TransactionalBucketBPlusTree` — same shape as `GlobalUniqueIndex` (block size
256, matching `UniqueIndexBPlusTreeSupport`'s real constants exactly) — through 100k
`getLongRecordEqualTo` equality lookups, i.e. exactly what `attributeEquals("code", ...)` on a unique
String attribute does at query time. Ran identically pre-H2 and post-H2 (same file, only the
`evita_engine` jar differs; verified with `javap` before each run).

| | pre-H2 | post-H2 | Δ |
|---|--:|--:|--:|
| hit ns/op | 549.4 | 489.2 | **−11.0%** |
| hit B/op | 781.7 | 352.0 | **−55.0%** |
| miss ns/op | 585.9 | 520.5 | **−11.2%** |
| miss B/op | 785.3 | 264.0 | **−66.4%** |

Consistent with the model: pre-H2, `findKeyPosition` does ~8 binary-search hops over a 256-entry
leaf, each allocating a `String` (~90-100B for these key lengths) ≈ 780B — matches the observed B/op
almost exactly. Post-H2, that collapses to just the one-time probe encode (~260-350B). This is the
clean, isolated measurement of exactly what H2 targets, with none of §1's cursor-value contamination.

## 3. Tuned config (compaction disabled, sync off, 10s flush) — isolates storage overhead from H2

Per Johnny's request: re-ran §1's write-churn profile with
`fileSizeCompactionThresholdBytes=50GB`, `minimalActiveRecordShare=0.0001`, `syncWrites=false`,
`TransactionOptions.flushFrequencyInMillis=10s` (temporarily edited into the worktree's
`EvitaWarmUpInsertionTest#getEvitaConfiguration`, not committed).

| | default config (§1) | tuned config | Δ |
|---|--:|--:|--:|
| ALIVE duration | 4m04s | **2m24s** | **−41.0%** |
| compactions | 4 | **0** | eliminated |
| total alloc | 50.53 GiB | 48.96 GiB | −3.1% |
| FrontCoded | 7.89 GiB (15.61%) | 7.85 GiB (16.02%) | ~flat (−0.5%) |
| OffsetIndex | 5.44 GiB (10.76%) | 3.75 GiB (7.65%) | **−31.1%** |

**FrontCoded's allocation is essentially unchanged by the tuning** (confirms it's a genuine per-op
cost, not a compaction artifact) — and the caller-chain breakdown reproduces §1's finding exactly:
`findKeyPosition` still allocates ~0.185 GiB of its own (probe + result), `keyAt` via cursor `.value()`
still accounts for 98.6% of the rest (4.58 of 4.65 GiB). This was not a compaction-driven confound.

**What the tuning actually removes is wall-clock time, not allocation**: −41% duration against only
−3.1% total allocation. Compaction's cost in the default config is dominated by I/O pauses and
`OffsetIndex` rewrite churn (−31.1% there), not by extra bytes allocated elsewhere. This isolates
"pure per-operation logic cost" (2m24s / 500k ops ≈ 288 μs/op) from "storage-layer tax"
(≈200 μs/op average, eliminated here) — useful as a ceiling for what further storage-layer work
(compaction cadence, fsync batching) could still recover, separately from anything FrontCoded-related.

## 4. Net assessment

- **H2's mechanism is correct and verified three independent ways**: the JMH micro (1.3-1.9× ns/op,
  ~8× B/op — Phase 2 report), the real-tree query benchmark (§2, −55% to −66% B/op / −11% ns/op on
  equality lookups), and the write-churn caller-chain trace (§1/§3, `findKeyPosition` allocates zero
  String in both configs).
- **The write-churn category number understates the win** because that workload's dominant
  `FrontCoded` allocator is `keyAt` via cursor `.value()`, a mutation-path method H2 was never scoped
  to touch — not a flaw in H2, a mismatch between what that particular benchmark exercises and what H2
  targets.
- **Query-path (§2) is where H2's benefit is real and directly visible**: any code path doing
  attribute-equality lookups against a natural-order, BMP-safe String tree (unique-index lookups,
  `attributeEquals` filters) gets the full benefit.
- **Tuned-config (§3) confirms FrontCoded's cost is orthogonal to compaction** — ruling out "maybe the
  small write-churn win is because compaction was masking it" as an alternative explanation.
