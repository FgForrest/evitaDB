---
title: Decompose index storage into granular paged parts and slim the index data structures
date: 2026-07-10
updated: 2026-08-23 15:40
status: accepted
kind: optimization
issues: [760, 1252]
prs: [1268]
areas: [evita_engine/index, evita_engine/store, evita_common/dataType/bPlusTree, evita_roaring_bitmap]
supersedes: []
superseded-by: []
relates: [2026-07-27-write-path-performance-tuning, 2026-08-01-bplustree-cursor-free-insert-path, 2026-07-10-atomic-entity-mutation-partial-rollback]
---

# Granular storage parts and slimmer index data structures

The first wave of issue #760: replace monolithic per-index storage parts with **granular, paged
parts** so a commit persists only the pages that changed, slim the index structures themselves
(SortIndex, FilterIndex, InvertedIndex, ChainIndex, HistogramIndex), and vendor RoaringBitmap so its
copy-on-write behaviour could be changed at all. 142 commits, merged as PR #1268.

## Why

Every index wrote its entire state as one storage part on every commit that touched it. For a
large catalog that means re-serializing megabytes to record a handful of changed entries — the cost
scales with index *size* rather than with change *size*, so ordinary churn on a big catalog pays a
flat, large price per commit. The same monolithic shape drives compaction: parts that are rewritten
in full are copied forward in full.

Underneath that sat a second problem the first one hid: the index structures themselves were built
for read-side simplicity, holding whole bitmaps and flat arrays where a churning workload needs
structural sharing. And the third-party `RoaringBitmap` at the bottom of all of it clones eagerly on
every transactional copy — behaviour that cannot be changed from outside the library.

### Previous state

One storage part per index, rewritten wholesale. `RoaringBitmap` came from the upstream artifact,
so a transactional copy deep-cloned the `RoaringArray` and its `Container[]`. `SortIndex` held its
full sorted record array; `InvertedIndex` materialised a bitmap per value even where a single record
was involved; `ChainIndex` kept one monolithic materialised order.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-06-27 | **Vendor RoaringBitmap v1.6.12** into `evita_roaring_bitmap` rather than wrap or fork upstream | Copy-on-write had to be pushed *into* `RoaringArray` itself; that is not reachable from outside the library. Vendoring also let the JPMS surface be tightened and the buffer package dropped | `design/` + the `roaring-bitmap-sync` skill's ledger |
| 2026-06-27 | Rename the vendored package and classes (`org.roaringbitmap` → `io.evitadb.roaringbitmap`, `RoaringBitmap` → `PersistentRoaringBitmap`) | Prevents classpath ambiguity with the upstream artifact and makes it unmistakable which implementation is in use | commits `fa208679b`, `9ce15905b` |
| 2026-06-27 | Array-level copy-on-write for `RoaringArray` backing arrays | The eager clone was the dominant cost of every transactional bitmap copy | commit `b306267e6` |
| 2026-07-03 | Granular **per-leaf page** storage for inverted and range indexes | Persist only pages that changed; the leaf page is the natural unit because it is what the B+ tree already mutates | commit `e5f57f7a0` |
| 2026-07-05 | Granular paged `HistogramIndex` storage with a shared serializer and part dedup | Same shape as the inverted/range work, with dedup because histogram parts repeat heavily across locales | commit `fa01ba65f` |
| 2026-07-05 | Make STM savepoint `snapshot()`/`restore()` **delta-bounded via an undo journal** | Snapshot cost was proportional to index size, so per-entity rollback hit a cliff; the journal makes it proportional to the change | commit `e15657865` |
| 2026-07-06 | **Tree-direct sort** — resolve positions from the B+ tree instead of materialising the sorted array | Removes the flatten that dominated large-K sort selections | commits `dd56f6d8e`, `cd2177c9c`, `d7a548b09` |
| 2026-07-07 | Demote multi-bitmap buckets to **single-record form at commit** | Most inverted-index buckets hold exactly one record; a full bitmap per bucket is pure overhead | commit `4cd7922a6` |
| 2026-07-07 | Paged persistence and churn/collapse optimizations for `ChainIndex`, keeping the **materialised order** and paging it positionally | See the rejected designs below — every alternative either lost restart-stable sort order or persisted data that is positionally reconstructible for free | `design/2026-07-02-chainindex-churn-and-collapse-plan.md` |
| 2026-07-08 | Observed-interval **compaction cadence gate** with a max-waste override | Compaction was firing on a fixed cadence regardless of how much waste had accumulated | commit `3263d8628` |
| 2026-07-08 | Recycle per-transaction off-heap WAL `ObservableOutput` buffers | Per-transaction buffer allocation was visible on the write path | commit `2bc70680c` |
| 2026-07-08 | Replace the CRC32C combine **matrix rebuild with a static GF(2) ladder**, then a bare-long cumulative checksum | The matrix was rebuilt per combine; the ladder is constant and the bare-long form removes the `forceValue` calls entirely | commits `c12952397`, `2ab3b0ccb` |
| 2026-07-08 | `ThreadLocal` for both FrontCoded scratch variants — **not** `com.esotericsoftware.kryo.util.Pool` | Measured: the Kryo pool's acquire/release overhead exceeds the scratch it protects at this granularity | `design/2026-07-08-front-coded-stringcolumn-aggressive-reuse.md` |
| 2026-07-09 | Flat-buffer `copyRangeTo` / splice and structural-share duplicate for `FrontCodedStringColumn`; BMP-safe byte-compare fast path for `findKeyPosition` | The column was re-encoding on every range copy and comparing through full string decode | commits `202a335da`, `55ed58adb`, `34792fc73` |
| 2026-07-09 | Compact `ValueToRecordPrimitive` flyweight for single-record `InvertedIndex` leaf-page buckets | The single-record case is the common case; the flyweight removes the wrapper entirely | commit `a97ba014e` |
| 2026-07-09 | Binary-search child descent in `CumulativeWeightBPlusTree` | `descend()` linear-scanned up to 63 separators per internal node while `leafInsertionIndex()` in the same file already binary-searched | commit `b397c62b1` |
| 2026-07-10 | **Adaptive size-based `fromArray` dispatch**, pushed down into `fromArray` itself | Chosen over the two-phase OR it was consulted against — see below | `design/2026-07-10-orformula-single-record-batching-consult-RESPONSE.md`, commit `207c99327` |
| 2026-07-10 | Bulk-construct B+ tree leaf columns on paged index load | Loading page-by-page through the ordinary insert path re-did work the page already encoded | commit `1d3e2d215` |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| **ChainIndex Design A — page `predecessors` only, reconstruct order by replay** | Fork-winner and circular-head choices are operation-history artifacts that live *only* in the physical order; a replay yields a different valid order, so restart changes visible sort order — a product-level regression. (The originally-stated rationale was wrong and was corrected: replay would *not* trip `getConsistencyReport`, which validates structure-vs-state coherence that a replayed state self-satisfies) | Never, unless order stability across restart stops being a product guarantee |
| **ChainIndex Design C — page the predecessors map as a second int→int stream** | Its "no growing header" claim is false — run boundaries and head identity are exactly what a pk→pred map cannot express, so the root keeps per-run data anyway — and it persists ~80 MB at 10 M records of data that is positionally reconstructible for free | The header data becomes expressible in the map form |
| **ChainIndex Design D — shard the flat `int[][]` into fixed blocks** | Fixed blocks over a *positional* array suffer O(N) block invalidation on any mid-array insert, which is the normal path. Dirty-range tracking cannot fix content that genuinely moved | The array stops being positional |
| **Per-chain storage parts (one part per head pk)** | Steady state is one chain, so it degenerates to monolithic again — and head pks are unstable keys (promote re-keys them, merge deletes them) | Steady state becomes genuinely multi-chain *and* head identity becomes stable |
| **Bounded collapse (cap merges per operation)** | Leaves committed state fragmented when the data *is* consistent, producing a query-visible order regression | Never in this form |
| **Deferred / batched collapse (collapse-on-observe)** | The final cascade stays cubic with the current algorithm, the pending count grows during the transaction slowing every `attachElement`, and mutating from a read path is a design wart | Pointless once paged persistence landed — do not revisit |
| **Structural replacement — drop the materialised order, derive per version from predecessors** | Moves Design A's fidelity problem into the LIVE index, needs the same multimap as the chosen fix anyway, and likely regresses memory (boxed maps vs a compact int-array order-statistic tree) | Long-term note only |
| **Flat array for the churning structure** | Cheapest memory (4 B/record) and marginally fastest reads, but ruled out for a structure under churn — mid-array inserts move everything downstream | The structure stops churning |
| **Delta-journal for paged persistence** (Johnny's call) | Per-commit tiny parts waste OffsetIndex capacity | — |
| **Two-phase OR as the lead fix** | Correct and it would work, but the priority was inverted: the size-adaptive `fromArray` dispatch was assumed riskier when the codebase had **already benchmarked, threshold-tuned and equivalence-tested that exact dispatch** in `BaseBitmap(int...)`. A factual error compounded it — `fromArray` never radix-sorts (`doPartialRadixSort()` is an opt-in flag neither `fromArray` nor `buildWriter()` sets), so phase-1's batched `addMany` would largely degenerate into per-value `add()` *plus* an 8 KB buffer buying nothing | `or()`/unwrap CPU still registers after the dispatch fix — and then build it **without** the phase-1 writer |
| **`com.esotericsoftware.kryo.util.Pool` for FrontCoded scratch** | Acquire/release overhead exceeds the scratch it protects at this granularity | — |

## Key technical details

- **The vendored bitmap is a hard fork with a sync ledger, not a snapshot.** Upstream changes are
  replayed deliberately via the `roaring-bitmap-sync` skill, which records the base commit and
  triages each upstream commit against the vendored subset. The buffer package and
  `FastRankRoaringBitmap` were stripped; a JPMS `module-info`, NOTICE and attribution were added.
- **Granularity unit is the B+ tree leaf page**, because that is what the tree already mutates.
  A page that did not change emits nothing; an abandoned page (from split/merge) gets a tombstone.
- **`TrappedChanges` is add-only** — it had no freed-page channel, which is why split/merge-abandoned
  pages needed explicit tombstoning rather than falling out naturally.
- **Positional reconstruction is the ChainIndex invariant**: the predecessors map is *not* persisted
  because it is derivable from the persisted positional order for free. Any future change that makes
  order non-positional invalidates that.
- **`CumulativeWeightBPlusTree.descend()`'s linear scan was diagnosed as a 45× regression and was
  not** — implementing and measuring it showed ~1.6×. The remaining gap was raw collation cost,
  which the sibling write-path line then attacked. Recorded because the original "45×" figure
  circulated in working notes and must not be carried forward.
- Spike/benchmark sources live in their normal location,
  `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/` (10 classes added).

## Verification

142 commits merged as PR #1268 on 2026-07-10, including a dedicated generational rollback and
savepoint **fuzz suite covering all index types** (`19bad8f54`) and a ported upstream RoaringBitmap
test suite for the vendored module — 512 tests green on the V2 oracle alone (`7535ac181`,
`55b5bca53`).

Per-change measurements are in `reports/`: FrontCoded remeasures (phase 1 H1/H3, H2 JMH phase 2, H2
production, `copyRangeTo` flatbuffer), the InvertedIndex bucket-flyweight remeasure, SortIndex cache
E2E findings, the warmup-test remeasure under real config, and the write-and-query throughput
remeasure that supersedes the mid-session recommendation list.

**One measurement caveat that must travel with these numbers:** the write benchmarks run
`-wi 0 -i 1` (forced by `Level.Iteration` state), so absolute ops/s figures are single-shot. Treat
direction and rough magnitude as solid and exact multipliers as approximate.

## Consequences & open follow-ups

**Re-verified against the tree on 2026-07-31.** The source README listed three open follow-ups and
noted two more that had turned out to be already fixed. **All three of the open ones are now closed** —
which is the whole reason this record exists rather than the plan folder it replaces.

- ~~JMH read-benchmark pool exhaustion — `maxOpenedReadHandles(12)` hardcoded, fix "drafted but never
  committed"~~ — **closed**. `EvitaCatalogSetup` now uses `Runtime.getRuntime().availableProcessors() * 4`,
  shipped as `d2953ee4f` inside this very PR.
- ~~WAL-purge catalog-file race (`FileNotFoundException`), filed as #1203~~ — **closed** by PR #1219,
  merged 2026-06-04.
- ~~`EntityIndexManifestInvariantTest` manifest gating asymmetry — `getUniqueIndexes()` unions view keys
  ungated while `collectKeys()` gates on `sharedValueIndex.containsKey`~~ — **closed**.
  `AttributeIndex.getUniqueIndexes()` now carries the gate, with a comment stating it must match
  `collectKeys()` exactly.
- Two items the source README had already corrected stay corrected: the stranded-price-id reduced-index
  data-loss bug shipped in `b3f25b4b1`, and RangeIndex's constant-`1L` formula-cache staleness (#37)
  shipped in `7fa7648d2` including the leaf-granular refinement.
- **Still open — `InvertedIndex.collectChangedPages` → `ValueToRecordBitmap.<init>` allocation during
  compaction.** Rebuilds fresh bitmap/array/container objects every time a page is re-materialised,
  once per compaction. Two angles were sketched (pool the wrapper objects across re-materialisations
  within one pass, as already proven for the transactional clone path; or defer/batch construction)
  but neither was measured. Worth a design pass before committing to pooling.
- **The raw measurement data behind `reports/` (~200 MB of `.jfr`/`.collapsed`/`.csv`/JMH JSON) was not
  carried over.** Regenerate via the same benchmark harnesses if a comparison is ever needed.

## Related work

- **`2026-07-27-write-path-performance-tuning`** — the sibling #760 line, targeting commit-merge
  latency and write-path allocation via production WAL replay rather than granular storage-part
  decomposition. Different code areas, same issue, overlapping cast of index classes (`SortIndex`,
  `FrontCodedStringColumn`). The collation cost this line's `descend()` investigation ran into is
  exactly what that line then fixed.
- **`.claude/skills/roaring-bitmap-sync/`** — the standing procedure for replaying upstream
  RoaringBitmap changes onto the module vendored here.
- **`2026-08-01-bplustree-cursor-free-insert-path`** — freed the B+ tree descent built here from its
  per-descent cursor allocation. It borrows this folder's `reports/` for its own census (see
  *Supporting material*), because it is one decision and does not warrant a directory of its own.

## Supporting material

- `reports/2026-07-31-bplustree-optimization-portability-census.md` — belongs to the sibling record
  `2026-08-01-bplustree-cursor-free-insert-path`, kept here because it measures this folder's B+ tree
  family. Answers **which of the recent single-tree optimizations port to the other four trees, and at
  what measured size** — including the ones that were *not* ported, whose per-arm numbers are the only
  reason not to re-propose them.
- `reports/` (15 further files) — the measured conclusions: FrontCoded remeasures, InvertedIndex
  bucket-flyweight remeasure, SortIndex cache E2E findings and benchmark baseline-vs-optimized, warmup
  remeasure under real config, write-and-query throughput remeasure (**the authoritative list — it
  supersedes `optimization-recommendations.md`, which is kept only as a chronological record of how
  the investigation evolved**), attrfilter `fromArray` fix results, async-profiler attrfilter summary,
  `write-churn-findings.md`, and the unique/ALIVE churn diagnosis showing `FrontCodedStringColumn`
  `byte[]` churn made unique-attribute ALIVE commits 3.4× slower than range/chain.
- `design/` (23 files) — the rejected-design rationale summarised in the table above, in the detail
  needed to avoid re-deriving it: ChainIndex churn/collapse, the OrFormula consult and its response,
  SortIndex paging and committed-snapshot cache, FrontCoded allocation attacks, Kryo/`ObservableOutput`
  buffer pooling, RoaringBitmap cloning, CRC32C combine caching, dense-walk AndNot, InvertedIndex
  bucket flyweight, compaction auto-tuning, plus the PriceSuper page-chunk design, the B+ tree
  shared-base extraction plan and the decodoma dataset gate analysis.

Deliberately **not** carried over: the 17 `plans/` files (step-by-step implementation instructions for
work that shipped — the code is now the truth), and `reports/raw-analysis/` (profiler `.txt` dumps
whose conclusions the reports already carry).

## Timeline

- **2026-06-22 → 06-26** — storage-part decomposition and index-slimming design
- **2026-06-27** — RoaringBitmap vendored, renamed, and given copy-on-write
- **2026-07-03 → 07-07** — granular paged persistence lands for inverted, range, histogram and chain
  indexes; savepoint snapshots made delta-bounded
- **2026-07-08 → 07-09** — the allocation and CPU pass over FrontCoded, CRC32C, InvertedIndex buckets
  and the B+ tree descent
- **2026-07-10** — adaptive `fromArray` dispatch; PR #1268 merged
- **2026-07-31** — working documents consolidated into this record; all three listed open follow-ups
  re-verified and found closed
