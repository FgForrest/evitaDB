# Write-path performance tuning — commit/merge latency and allocation (issue #760)

Design, planning, and measurement documents from a second wave of issue #760 work, distinct from
the granular-storage-parts sub-goal covered by `../more-optimized-data-structures/`: this line
targets **commit-time merge latency and write-path allocation**, driven by real production WAL
replay against a "senesi" catalog snapshot rather than synthetic benchmarks. Consolidated from
32 working documents written 2026-07-20 → 07-27, carried over from the
`760-prune-commit-merge-small-tx-latency` branch before its uncommitted scratch was cleared.

**Staleness note**, same convention as the sibling spec: many documents were written mid-round and
describe work as "uncommitted" or "awaiting Johnny's go". By the time this was consolidated, all of
it had merged (PR #1317). Treat status language in these files as a historical snapshot — check
`git log` for what actually shipped.

## Layout

- **`MEASUREMENT_RECIPE.md`** — how to reproduce any of this: build invariants, fixture
  reconstruction, what each instrument (JMH `-prof gc`, async-profiler `alloc`/`cpu`) can and
  cannot answer, collapsed-profile reading rules, four ways a run silently lies, JMH pitfalls
  specific to this harness, and the server-side diagnostic launch configuration.
- **`SPIKE_BENCHMARKS.md`** — what each spike in `evita_test/evita_performance_tests/.../spike/`
  that came out of this line measures, and what it concluded.
- **`design/transactional-layer-key-refactoring.md`** — a proposal to split the transactional-memory
  interfaces and key the layer registry by primitive `long` ids instead of wrapper-object identity;
  not yet acted on.
- **`reports/`** — five consolidated write-ups, each merging several working documents by theme
  (see below), covering the full 2026-07-20 → 07-27 line.

### Reports, in reading order

1. **`2026-07-20-bplustree-correctness-and-reclaim-leaks.md`** — two correctness bugs found while
   pursuing this line: a shared-array shrink corrupting a B+ tree co-holder leaf (fixed for one of
   ten affected node classes; the other nine are an audited, unfixed risk), and a permanent on-disk
   storage leak when a flushed reduced index is later emptied and dropped (root-caused and
   test-confirmed; fix not yet implemented — three candidate seams recorded).
2. **`2026-07-22-warmup-upsert-and-collation.md`** — the WARM_UP bulk-upsert line: de-streaming the
   mutation path, then the discovery that ICU collation was re-decomposing every string on every
   B+ tree comparison, ending in a shared collation-key cache. This is the cache the WAL-replay line
   later resized for its biggest single win.
3. **`2026-07-23-price-index-and-attach-retirement.md`** — retiring `attachToCatalog` down to a
   single implementor, a latent locale-sequence corruption bug found during that audit, and
   inverting the price-index/GLOBAL dependency direction so clean reduced indexes stop needing a
   version-pin refresh on every commit.
4. **`2026-07-24-trunk-merge-and-index-carry.md`** — the design verdicts behind the commit-merge
   prune: full bottom-up inversion is a **NO** (no parent links, no shared substitution seam),
   clean-subtree pruning is a **YES** (the pattern already shipped twice), the C1 carry-vs-wiring
   conflict and its resolution (Plan A chosen, Plan B ruled dead), CHAMP for the entity index maps,
   and a profiling NO-GO that was later retracted — kept as a cautionary record of three concrete
   profiling errors.
5. **`2026-07-27-senesi-wal-replay-rounds.md`** — the measurement line itself, round 0 through 7:
   537 s → 198 s on throughput, 1257 s → ~412 s serialized with the small-transaction visibility
   floor cut from ~3 s to ~300 ms, plus the durable methodological lessons (size a finding before
   ranking it, a profile share is not a realizable saving, GC cost tracks survivors not allocation
   volume, pre-register the metric that will *not* move).

## Cross-reference

See `../more-optimized-data-structures/` for the sibling #760 sub-goal (granular storage-part
decomposition, SortIndex/FilterIndex slimming, RoaringBitmap vendoring) — different code areas,
same issue, overlapping cast of index classes (`SortIndex`, `FrontCodedStringColumn`).

## What is not here

The senesi dataset (catalog snapshot + WAL slice) that every measurement in this spec was taken
against has been **deleted** — it was a large production export, ephemeral by design. Re-measuring
anything here requires a fresh export from the live deployment; see `MEASUREMENT_RECIPE.md` §2.

Raw profiler output (collapsed stacks, JMH JSON, run logs) for the final round is archived outside
Git at `backups/profiles/` (gzipped, ~14 MB) rather than committed — the same convention the sibling
spec uses for its own ~200 MB of raw data. Everything before the final round was analyzed inline in
its working document and the raw files were not retained.

## Known open follow-ups (not yet resolved as of this consolidation)

- **The B+ tree shared-array-shrink defect is fixed for one of ten affected node classes.** The
  other nine (`IntToLong`/`Object`/`Long`/`Bucket` leaf and internal nodes) share the same
  construction and were audited but not individually reproduced or fixed. Two of them
  (`TransactionalObjectBPlusTree`, `TransactionalIntToLongBPlusTree`) have **no dirty-scope
  validator at all**, so the same corruption there would fail silently. See report 1.
- **The dropped-index storage-part reclaim leak has no shipped fix.** Root-caused, empirically
  confirmed with a failing regression test (`RemovedReferenceIndexReclaimTest`), three candidate
  seams designed and compared — but the seam choice was left to the implementer. See report 1.
- **Storing the collation key alongside the value in the sort index** (removing collation from the
  compare path entirely, rather than caching it) is the only remaining collation lever once cache
  sizing is exhausted. It is correctness-critical and may touch the persisted Kryo format. See
  reports 2 and 5.
- **Trunk re-apply (~38 % of remaining application CPU)** — every mutation is applied twice by
  design (session-isolated indexes, then the trunk's shared ones). Removing it means carrying the
  isolated run's index diff forward instead of replaying it: a large, MVCC-sensitive surface and its
  own project. See report 5.
