---
title: Cut commit-merge latency and write-path allocation by pruning the trunk merge, not inverting it
date: 2026-07-27
updated: 2026-08-30 02:15
status: accepted
kind: optimization
issues: [760]
prs: [1317, 1298]
areas: [evita_engine/core/transaction, evita_engine/index, evita_engine/core/buffer, evita_engine/index/bPlusTree]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-08-05-schema-handling-write-path-optimizations, 2026-08-10-catalog-and-collection-statistics, 2026-08-24-fulltext-search-lucene-vs-inhouse]
---

# Write-path performance tuning — commit-merge latency and allocation

A second wave of issue #760, distinct from the granular-storage-parts line: this one targeted
**commit-time merge latency and write-path allocation**, measured by replaying a real production
catalog's write-ahead log rather than synthetic benchmarks. Eight rounds took the same 300-transaction
slice from **537 s → 198 s** on throughput and from **1257 s → ~412 s** serialized, with the
small-transaction visibility floor cut from **~3.0 s to ~300 ms**. Two correctness bugs found in the
same seam shipped separately in PR #1298.

## Why

evitaDB makes a committed transaction visible by walking down from the catalog through every
container to every leaf index, rebuilding the committed state as it goes. On the production catalog
used for measurement (~1 M products) that walk visited **140 000 – 1 100 000 producers per
transaction, of which ~1.0 % had any change at all**.

The consequence was a latency floor that ignored transaction size: a **one-mutation commit took
~2.9 s to become visible**, and `visible_ms ≈ 2771 + 6.98 × mutations` — 99.9 % of a small commit's
latency was size-independent overhead. For a write node serving `WAIT_FOR_CHANGES_VISIBLE` clients,
that floor is the product constraint.

The constraint that made it non-obvious: **the walk exists to *discover* what changed**, and the
information was already available — `TransactionalLayerMaintainer` is keyed by exactly the producers
holding a diff layer (~4 600 per transaction). The waste is the traversal, not the copying.
`TransactionalMap`, `RangeIndex` and the B+ tree internal nodes already return themselves when
nothing changed, so any framing as "stop rebuilding 460 000 objects" is wrong — it is "stop
*visiting* 460 000 objects to find 4 600", at one key allocation plus one map lookup each, ~99 % of
which learn nothing. That bookkeeping measured **~25 % of the trunk thread's wall time**.

### Previous state

Every commit ran `createCopyWithMergedTransactionalMemory` over the full forest. Clean reduced
indexes could not be carried forward by reference because their price chain captured the scope's
`GlobalEntityIndex` through a stored `SuperIndexResolver`, and the GLOBAL is rebuilt on nearly every
write commit — so a carried index would point at a retired GLOBAL. `attachToCatalog` had many
implementors, each re-shelling and re-attaching per version. String comparison went through
`java.text.Collator` directly, re-decomposing both operands through ICU on every call, backed by an
8192-slot collation cache whose default was set for a far smaller corpus.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-07-20 | Size the collation-key cache from heap (`maxMemory/50/256`, clamped `[8192, 1<<20]`) instead of a fixed 8192 | The pivot working set of a binary descent is the whole corpus, not the probe; at 8192 slots nearly every comparison recomputed a full ICU key. 1 M slots measured 2.02×, and 4 M measured worse — the cap was right, only the default was wrong | `reports/2026-07-27-wal-replay-rounds.md` (rounds 1–2) |
| 2026-07-20 | Cache collation keys per locale rather than persisting them | Comparing cached key bytes with `Arrays.compareUnsigned` is 60×–3000× cheaper with zero allocation; persisting costs ~2.5× string bytes on disk plus a new serializer and a BWC reader | `reports/2026-07-22-warmup-upsert-and-collation.md` |
| 2026-07-20 | Invert the `removePrice` containment probe | It materialized the entire price B+ tree into a fresh array on every price removal to answer one boolean — O(N) time and allocation for a question about the entity's own handful of prices | rounds report (round 1) |
| 2026-07-20 | Make dirty-scope validation register probe **keys**, not node objects | Structural: it makes the shared-array corruption class unrepresentable instead of defended against, and returns `setPeek`'s base branch to a zero-alloc `Arrays.fill` | `reports/2026-07-20-bplustree-correctness-and-reclaim-leaks.md`, commit `e72507e1f` |
| 2026-07-23 | Reuse the B+ tree descent cursor; stripe the collator pool | `CumulativeWeightBPlusTree$Cursor.<init>` was 19.1 % of all sampled allocation — two 64-slot arrays per insert/remove/updateWeight | rounds report (round 3) |
| 2026-07-24 | **Prune clean subtrees from the commit merge**; carry the rest across the catalog version by reference | Preserves the existing top-down control flow, needs no parent links, and the pattern already shipped twice in-tree — a rollout, not an invention | `reports/2026-07-24-trunk-merge-and-index-carry.md` |
| 2026-07-24 | Re-shell clean reduced indexes through a **dedicated carry-by-reference shell constructor** | The first version re-shelled through the persisted-state reconstruction constructor: ~4 µs × 179 086 indexes = 510–740 ms per small tx. Sharing every sub-structure *and* the immutable baseline runs at ~0.3 µs | rounds report (round 4) |
| 2026-07-24 | Extend the prune to the index-map-diff case via a `ValueMerger` hook on `MapChanges.createMergedMap` | 70 % of big transactions bypassed the prune entirely and paid the full walk to apply a median delta of **0.0058 % of the map** | rounds report (round 5) |
| 2026-07-25 | Pass the GLOBAL price index **in** as a parameter instead of storing it | A stored pointer is a version pin: it forced every clean reduced index to be re-shelled and re-wired every commit purely to refresh it. The caller always knows its catalog version, so pushing context in at call time is version-correct by construction | `reports/2026-07-23-price-index-and-attach-retirement.md` |
| 2026-07-25 | Retire `attachToCatalog` to a single implementor (`EntityCollection`) | Instance sharing across versions was already the norm for `GlobalEntityIndex` and its subtree; shells existed only to satisfy the single-use attach | same |
| 2026-07-25 | Memoize the per-scope GLOBAL resolution in a `Scope.ordinal()`-indexed array | `wireReducedIndexSuperIndexes` resolved it per reduced index — ~251 k allocations and lookups per commit for `Product` alone | rounds report (round 6) |
| 2026-07-26 | Replace `EntityCollection.indexes`/`indexesByPrimaryKey` with a CHAMP map | Path-copies only changed keys instead of rebuilding the whole map per commit; two increments worth −19.9 % then −14.9 % visible median | trunk-merge report |
| 2026-07-26 | Delete three allocation sites (`SortedIntArrayCodec` eager message concat, `Attributes.<init>` stream pipeline, `SortIndexChanges.computePreviousRecord` full flatten) | Each had a single dominant caller, so each was one concrete change rather than a diffuse cost | rounds report (round 7) |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| **Full bottom-up inversion of the merge cascade** | There are no parent links — the cascade is containers iterating children, and nothing maps a producer to its container. Adding them reintroduces the back-reference lifecycle hazard the attach-retirement work removed. All ~35 composite `createCopyWithMergedTransactionalMemory` implementations are bespoke monolithic constructors, so there is no shared substitution seam to invert. A producer can also be reachable from more than one parent, making "the parent" ill-defined | Nodes gain parent links for an unrelated reason **and** a shared substitution seam appears. Note the motivating estimate was wrong: an inverted cascade still rebuilds every *ancestor*, so the real reduction is 12–20×, not the 100× originally claimed |
| **Plan B — dynamic GLOBAL resolution through a stable indirection** | It is the cleanest route on paper (true sharing, eliminating both dispatch and allocation) but it reverses the deliberate attach-retirement decision and removes the `wireOrVerifySuperIndexes` identity check guarding stale super pointers from reaching queries — a large blast radius in the corruption-prone transactional seam. Later restated as "dead on MVCC grounds" | Explicit owner approval only. Do not revisit casually |
| **Persisting collation-key bytes on disk (option A)** | ~5 key-bytes per character ⇒ ~2.5× string bytes on disk, plus a new `_2026_x` serializer and a backward-compatible reader | Query-time collation later dominates. Storing the key *alongside the value in the sort index* remains the one open collation lever — see follow-ups |
| **Fixing the collator's decomposition mode (option D)** | **Empirically refuted**, having been the leading hypothesis: JDK 17's default decomposition for `cs`/`en`/`de`/`fr` is already `NO_DECOMPOSITION`, and flipping it changes neither time nor allocation | Never — the JDK already does what it proposed |
| **Memoizing only the probe key per search (option C)** | One `getCollationKey` costs **2.3× a full compare**, so memoizing just the probe loses outright | Never |
| **Copy-on-write shrink in `setPeek`'s base branch** | It worked, but cost a block-size array allocation (~272 B) on every base-branch shrink — on a path being allocation-profiled. Superseded the same day by the probe-key rework, which makes the bug class unrepresentable *and* keeps the shrink zero-alloc | Superseded, not merely declined |
| **"Don't blank the vacated tail at all"** | Free, but closed only the read hazard. The old code left arrays still shared after a shrink, so a later insert into the emptied node would write through into a co-holder's array — never observed, but it follows from the object model. The asymmetry between the two hazards was the deciding argument | Superseded by the probe-key rework, which closes both |
| **`markValueMutated` contract for the CHAMP index map** | `pruneMergeIndexes` already computes the exact delta at merge time, it is already in a local variable, proven correct, and carries no forgotten-mark hazard | Only if the explicit key-set entry point becomes unworkable; the single dirty-key funnel would be `DataStoreChanges#captureDirtyIndexKeys` |
| **Skipping the wiring walk for a wholesale-carried collection** | `PriceRefIndex.wireOrVerifySuperIndexes` is not uniformly a verification — with a null resolver it performs the *first* wiring, exactly the state a re-shelled index is left in. The loop is load-bearing on the dirty path, and `attachToCatalog` is shared with disk load and the warm-up copy path | The wiring and verification roles are separated into distinct entry points |
| **A provably-correct change removing dead work in the baseline/reclaim seam** | Measured **−0.2 %**, below a ~3 % noise floor, for +141/−58 lines across 10 files — and it introduced an invariant *nothing enforces* ("`collectManifest` must be side-effect free") in the seam behind the stale-twin bugs. A future contributor could break it with every test still green | The contract gets *enforcement*, not documentation |
| **Tuning storage knobs (`syncWrites`, `flushFrequencyInMillis`, `minimalActiveRecordShare`, G1↔ParallelGC)** | All measured no-ops for this workload — disk I/O is ~1 % of both hot threads and fsync is ~7 ms/tx against a multi-second trunk phase | On I/O-constrained hardware, where the balance differs |

## Key technical details

- **The dirty-set signal is `DataStoreChanges.popTrappedUpdates`**, which snapshots the dirty
  index-key set before draining it — the same set the flush persists, i.e. ground truth. Flush runs
  strictly *before* the merge, so the snapshot is fresh for the batch. **This ordering is
  load-bearing**; reversing it silently un-prunes.
- **`MapChanges.createMergedMap` consults a `ValueMerger` for every surviving key**, so there is no
  unpruned route left and `EntityCollection` has one merge call site.
- **Layer disposal must use `removeTransactionalMemoryLayerIfExists`, never `removeLayer`** — the
  latter descends into every value.
- **Correctness rests on loud backstops, by design.** `verifyLayerWasFullySwept` throws
  `StaleTransactionMemoryException` if any diff layer goes unconsumed, so a mis-identified clean index
  fails the commit rather than silently losing data. Every experiment kept it enabled. A stale super
  pointer hits the `wireOrVerifySuperIndexes` premise assert; an unexpected index kind hits an explicit
  premise assert.
- **Combination-level super-index identity was never an invariant.** `createCopyWithMergedTransactionalMemory`
  returns `this` for a clean combination, but the B+ tree's O(Δ) merge reuses the same
  `PriceRecordContract` objects — so a reduced index pointing at a superseded wrapper still resolved
  identical records. An assert demanding combination identity was **removed rather than weakened**,
  because it asserted something that has never been true.
- **The prune does not change complexity.** Every commit stays O(#indexes), because the GLOBAL is
  dirty nearly every transaction and the price capture forces a re-shell of every reduced index in
  that scope. It extends the *cheap constant* (~0.3 µs) to commits that previously paid the expensive
  one (~12 µs).
- `SuperIndexResolver`, `wireSuperIndexes` and the carry-by-reference shell constructors are **gone**
  (verified absent from the tree). What replaced `wireSuperIndex` is `restorePriceRecordsFrom(...)`,
  which does only the disk-load price-record-tree rebuild and keeps the load-bearing
  `priceRecords == null` guard — load correctness, not version wiring.
- **The measurement harness was generalized and committed**: `WalReplayBenchmark`/`WalReplayState`
  (`io.evitadb.performance.walreplay`), parameterized by `evita.replay.catalogName`. The methodology —
  what each instrument can and cannot answer, how a run silently lies — lives in the
  `wal-replay-profiling` skill (`.claude/skills/wal-replay-profiling/`), not here.

## Verification

Per-round numbers are in `reports/2026-07-27-wal-replay-rounds.md`. Headline, same
300-transaction slice throughout:

| axis | before | after |
|---|---|---|
| throughput wall | 537 s | **198 s** |
| serialized wall | 1257 s | **~412 s** |
| small-tx visibility median | ~2 998 ms | **~301 ms** |
| big-tx visibility median | 4433 ms | **2511 ms** |
| `gc.alloc.rate.norm` (round 7) | 387.10 GB/op | **252.92 GB/op** (−34.7 %) |
| GC STW | 39.5 s | **10.8 s** |

Functional regression at the attach-retirement step: **20 599 tests, 0 failures, 0 errors**; at the
B+ tree correctness step: **20 588 tests, 0 failures, 0 errors**. `ReducedIndexCatalogVersionCarryTest`
was proven to *discriminate* — it fails if the carry is reverted. `ElementLeafSharedArrayShrinkTest`
was sabotage-verified.

Two measurement rules were followed and are worth keeping: **acceptance metrics were pre-registered**
in rounds 5 and 6, including which metric would *not* move — in both cases the flat metric would
otherwise have read as failure. And the `removePrice` fix, being semantics-preserving, could not have
a fail-first test; its three regression tests were validated by passing against the *pre-fix*
implementation while failing against a plausible wrong optimization.

## Consequences & open follow-ups

**Corrected against the tree on 2026-07-31** — the source documents listed four open follow-ups; two
have since been fixed and one was superseded. This is why the ADR exists.

- ~~B+ tree shared-array shrink fixed for only one of ten node classes~~ — **closed**. Superseded by
  `e72507e1f` (PR #1298), which reworked dirty-scope validation to register probe *keys* rather than
  node objects, making the corruption class unrepresentable family-wide rather than defended against
  per class. A weaker residual observation stands: `TransactionalObjectBPlusTree` and
  `TransactionalIntToLongBPlusTree` still have no dirty-scope validator at all, so they have no
  independent tripwire if a future change reintroduces object-keyed observation.
- ~~Dropped-index storage-part reclaim leak has no shipped fix~~ — **closed, and more broadly than the
  design anticipated**. `dc9837128` (PR #1298) fixed it via a shared manifest diff on `EntityIndex`
  plus six deferred-removal part families, and additionally closed a *second* channel the analysis had
  not identified as fixed: **churn-vanish**, where a sub-index empties while its owner survives and its
  stable-keyed root is never superseded. That one fires on ordinary attribute churn and is the more
  frequent of the two.
- ~~JMH read-benchmark pool exhaustion (`maxOpenedReadHandles(12)` hardcoded)~~ — **closed** by
  `d2953ee4f` in PR #1268; the value is now `availableProcessors() * 4`.
- **Storing the collation key alongside the value in the sort index** — **still open**, verified absent
  from `SortIndex`. Cache sizing is spent as a lever: the slot count is heap-derived and already pinned
  at its 1 048 576 maximum under `-Xmx24g`. This is the only remaining collation lever, it is
  correctness-critical (a collation bug silently corrupts sort order into wrong query results), and it
  may touch the persisted Kryo format.
- **Trunk re-apply, ~38 % of remaining application CPU** — **still open**. Every mutation is applied
  twice by design: once into the session's transactional-memory layer, then again when the trunk stage
  replays it. Removing it means carrying the isolated run's index diff forward instead of replaying
  it — a large, MVCC-sensitive surface and its own project.
- **The final census leaves two subsystems at a third of all application work**, failing in opposite
  ways: collation is CPU-bound (22.8 % app CPU, 11.3 % allocation), the `FrontCodedStringColumn`
  family is allocation-bound (20.8 % allocation, 11.5 % CPU).
- **The dataset is gone.** The catalog snapshot and WAL slice every measurement was taken against were
  deleted — a large production export, ephemeral by design. Re-measuring needs a fresh export; the
  `wal-replay-profiling` skill documents the property names and directory layout it expects.
- **A design proposal was left unacted on**: splitting the transactional-memory interfaces and keying
  the layer registry by primitive `long` ids instead of wrapper-object identity —
  `design/transactional-layer-key-refactoring.md`.

## Related work

- **`2026-07-10-more-optimized-data-structures`** — the sibling #760 line:
  granular storage-part decomposition, SortIndex/FilterIndex slimming, RoaringBitmap vendoring. Same
  issue, different code areas, overlapping cast of index classes (`SortIndex`,
  `FrontCodedStringColumn`). The collation cache built here is the one that line's SortIndex work
  interacts with.
- **PR #1298** (`pending-fixes-2026-07-20`) — carried both correctness fixes found while pursuing this
  line, separately from the performance PR.
- **`2026-08-24-fulltext-search-lucene-vs-inhouse`** — this line's finding that a commit re-shells
  every reduced index (~179 K on the production catalog measured here) is why the trigram substring
  index's value-id allocator is scoped **per shared value tree** rather than being one catalog-global
  hot point. A downstream consumer of this record's write-path cost model.

## Supporting material

Five consolidated reports, each merging several working documents by theme, plus the spike index.
Everything else from the original 32 working documents was absorbed into this record; raw profiler
output was not carried over once the dataset it measured was gone.

- `reports/2026-07-20-bplustree-correctness-and-reclaim-leaks.md` — the two correctness bugs, their
  root causes, the audit table of all ten B+ tree node classes, and the reclaim-leak seam comparison.
  **Its "what shipped" section is superseded by this record's follow-ups.**
- `reports/2026-07-22-warmup-upsert-and-collation.md` — how the collation problem was found and the
  four-option dossier that settled it, including the option that was empirically refuted.
- `reports/2026-07-23-price-index-and-attach-retirement.md` — the attach retirement, the latent
  locale-sequence corruption bug found during it, and the honest accounting of the safety check the
  change removed.
- `reports/2026-07-24-trunk-merge-and-index-carry.md` — the design verdicts, the CHAMP read-side gate
  measurements, and a cautionary record of three concrete profiling errors behind a NO-GO that was
  later retracted.
- `reports/2026-07-27-wal-replay-rounds.md` — the measurement line itself, rounds 0–7, and the
  durable methodological lessons.
- `SPIKE_BENCHMARKS.md` — what each spike in `evita_test/evita_performance_tests/.../spike/` measures
  and what it concluded.
- `design/transactional-layer-key-refactoring.md` — the unacted-on proposal above.

## Timeline

- **2026-07-19 → 07-20** — two correctness bugs found in the transactional-memory seam while pursuing
  performance; both shipped in PR #1298 (merged 2026-07-20)
- **2026-07-20** — rounds 0–1: collation cache sizing (2.02×) and the `removePrice` O(N) defect (1.34×)
- **2026-07-21 → 07-23** — rounds 2–3: heap-derived cache default, phase split, cursor reuse
- **2026-07-24 → 07-25** — rounds 4–6: the commit-merge prune, its extension to the map-diff case, and
  attach-time GLOBAL resolution
- **2026-07-26** — round 7: three allocation sites deleted
- **2026-07-27** — PR #1317 merged
- **2026-07-31** — working documents consolidated into this record; open follow-ups re-verified against
  the tree, two found already closed
