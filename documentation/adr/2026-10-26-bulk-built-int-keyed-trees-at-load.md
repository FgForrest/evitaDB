---
title: Bulk-build the price and record-id trees each load rebuilds as full leaves; keep the other B+ tree ideas out
date: 2026-10-26
updated: 2026-10-26 16:20
status: accepted
kind: optimization
issues: [1802, 1808]
prs: []
areas: [evita_engine/index/bPlusTree, evita_engine/index/price, evita_engine/index/array]
supersedes: []
superseded-by: []
relates:
  - 2026-08-01-bplustree-cursor-free-insert-path
  - 2026-09-03-content-sized-value-tree-columns
  - 2026-09-04-long-keyed-tree-content-sizing
  - 2026-08-26-warm-up-per-entity-mutation-atomicity
---

# Bulk-build the price and record-id trees each load rebuilds as full leaves; keep the other B+ tree ideas out

Two kinds of int-keyed B+ tree are not persisted page by page: every catalog load rebuilds them from scratch, one
`insert` per record. Those inserts leave leaves half full. The price record tree of every reference price index and the
record id → order key tree inside every sort and chain index are now built bottom-up from their sorted input, as full
leaves (63 of 64) under a spine that the same builder assembles. On two production catalogs this takes 102–108 MB
(1.7–2.1 %) out of the index heap and loads 2.6–4.7 % faster, and a replay of 401 production transactions on the
bulk-built trees runs 3.7 % faster. The change came out of an evaluation of nine B+ tree layout ideas taken from the
bbtree research implementation (github.com/XiangpengHao/bbtree). This record also carries why the other eight lost,
so nobody re-proposes them without new evidence.

## Why

The two trees are rebuilt on the hot path of every cold start, and the shapes the rebuild left behind were an accident
of the insert path, not a choice:

- **Price record trees** (`TransactionalElementBPlusTree`, 64-entry leaves) of reference price indexes are rebuilt by
  `AbstractPriceListAndCurrencyPriceIndex#newPriceRecordTree` when the index arrives from disk without its tree. The
  input is ascending by internal price id, so each split at 64 leaves a 32-entry left leaf that is never touched again:
  every leaf but the last ends exactly half full.
- **Record-id trees** (`TransactionalIntToLongBPlusTree`, field `valueIndex` of `TransactionalUnorderedIntArray`) are
  filled by `accept` in sort order, i.e. in random record-id order: leaves end 50–70 % full, and a leaf that grew past
  half keeps its 64-slot arrays, because the commit trim shrinks only at 4:1.

Content-sized columns (`2026-09-03`, `2026-09-04`) had already stopped empty logical slots from costing memory, so
what was left to win is the fixed per-leaf overhead, plus that physical slack of the record-id leaves. That is why the
fill of these two trees still mattered when fill in general no longer did.

### Previous state

One insert per record at load, for both trees. The super index's own price tree is different: it reloads its persisted
pages through `TransactionalElementBPlusTree#assembleFromSingleLeafTrees`, which already assembled a spine over
existing leaves. Its spine code is what the new shared builder generalises.

## Options considered

### Option A — bulk build with full leaves, insert fallback for unexpected input (chosen)

Leaves of `capacity − 1` entries, the remainder topped up from the previous leaf when it would underflow (64 records →
`[33, 31]`), every node built as an insert-built tree built outside a transaction would be. Non-ascending price input
takes the old insert loop and is logged at ERROR.

- **Pros:** the largest saving (every leaf full); one builder for both trees; no persisted-format, API or
  incremental-algorithm change.
- **Cons:** the first insert into the middle of a full leaf splits it at once, so later writes split more often (see
  *Verification*: +3,428 leaf splits over 401 transactions); the spine is born one split from full.

### Option B — bulk build leaving headroom in each leaf (declined)

- **Pros:** fewer splits when existing entities later gain a price or enter a sort index.
- **Cons:** more leaves, more memory.
- **Rejected because:** headroom bought nothing measurable. Leaves filled to 56 of 64 gave 6 % more leaves and 3.5 MB
  more heap than full leaves, and the transaction replay on full leaves ran faster, not slower, than on the insert-built
  half-full ones.

### Option C — a spine packed to about three quarters (declined)

- **Pros:** internal splits after load would stay near the insert-built rate (record-id trees: 52 internal splits over
  the 401 transactions insert-built, 414 on a full spine).
- **Cons:** about +9 MB of internal nodes (arithmetic, not measured).
- **Rejected because:** the extra internal splits did not show in the replay latency, and 9 MB is a tenth of the
  saving.

### Option D — refuse to load on non-ascending price input (declined)

- **Pros:** a defect upstream would be impossible to miss.
- **Cons:** the defect would cost the catalog its availability.
- **Rejected because:** nothing on disk is at risk — the tree is an in-memory rebuild, and the insert loop builds it
  correctly from any order (`.claude/rules/durability-model.md`: a refusal must protect a publication, and this one
  protects none). Ascending order is assumed everywhere in this code, so a violation is logged at ERROR, once per
  build, naming the index and the first offending ids; the load continues. A strict mode is a follow-up once the
  ERROR has never been seen in production.

## Decision

**Chosen: Option A.** It takes the most memory out for the least code: one builder serves both trees and replaces the
Element tree's private spine code, and no reader can tell a bulk-built tree from an insert-built one. The split cost
that full leaves imply was measured on production writes and came out negative. Option B or C would win if a later
workload shows middle inserts into freshly loaded trees dominating write latency — the per-entity savepoint benchmark
in *Verification* is the place to see that first.

## Key technical details

- `AbstractIntKeyedBPlusTree` — `bulkLeafSizes`, `buildSpine`, `buildInternalNode` and the per-tree
  `createAssembledInternalNode` hook, shared by both trees and by the super index's page reload.
- `TransactionalElementBPlusTree.bulkBuild` and `AbstractPriceListAndCurrencyPriceIndex#newPriceRecordTree` — the
  price path: one `O(n)` ascending check, then the bulk build or the unchanged insert loop.
- `TransactionalIntToLongBPlusTree#bulkBuild` and the private collector in `TransactionalUnorderedIntArray` — the
  record-id path for `bulkLoad`, `bulkLoadWithHeads` and `assembleFromLeafPages`: pack `(recordId << 32) | ordinal`,
  sort, compact **last write wins** on a duplicate id (as `insert` does, logged at ERROR), release the collector, build.
  Incremental `accept` is unchanged.
- **Bulk build only when no transaction is bound, and every node `transactionalLayer = true`** — the shape an
  insert-built tree has outside a transaction. With a transaction bound, the insert path runs, because inside one it
  also layers `root`, `size` and the root leaf, which a bulk build would not reproduce. Price trees are rebuilt at cold
  load in `EntityCollection#addIndex`, outside any transaction, never in the commit merge (which carries the merged tree
  forward and would throw on a rebuild, `PriceListAndCurrencyPriceRefIndex#restorePriceRecordsFrom`).
- **The super index's SINGLE/PAGED decision is `priceRecords.isRootInternal()`** and did not move only because both
  builders produce two leaves at exactly 64 records. A change to the remainder rule moves that boundary.
- **Price ids do not arrive only at the right edge.** A reference index receives an entity's existing, older price ids
  when the entity gains the reference, so middle inserts into a full price leaf are a normal event; only the super
  index's input is ascending throughout. This corrects `2026-08-01`, which called the Element workload ascending "by
  documented contract".
- **Every node a price-tree split creates inside a transaction joins the transactional layer** (#1808), as the
  record-id tree's always did. Full leaves made this load-bearing: the first insert into a bulk-built leaf's middle
  splits it. Split nodes changed in place were invisible to a savepoint opened later in the same transaction, so a
  failed entity's price edits survived its per-entity rollback, were flushed into the super index's leaf pages and,
  after a reload, became the index's own. The in-place rule dated from a commit merge that re-inserted price records
  into a finalized transaction; both price indexes now carry the tree through the merge instead
  (`getStateCopyWithCommittedChanges`), so nothing needs it.
- Transient memory during a load: about 28 B per record of the index being loaded, per loader thread, released before
  the leaves are built. The price path allocates nothing beyond the leaves.

## Verification

Measured on two production catalogs: **catalog A**, an e-commerce catalog (~172,000 products, 249,722 indexes)
captured with its next 401 real transactions (13,873 mutations), and **catalog B**, a second production catalog
(574,026 indexes, 6 GB on disk). Heap figures are the indexes' own `getHeapSizeInBytes` estimates, comparable between
arms, not with a heap dump. Every arm ran on one jar behind a switch; timing arms ran on a quiet box (CPU busy ≤ 15 %
over 20 s; measured 4–8 %), in off, on, on, off order.

| | catalog A | catalog B |
|---|--:|--:|
| index heap, insert-built | 4,923.7 MB | 6,211.6 MB |
| price trees bulk-built | −46.8 MB | −48.6 MB |
| record-id trees bulk-built | −55.8 MB | −59.6 MB |
| both | **−102.5 MB (−2.08 %)** | **−108.3 MB (−1.74 %)** |
| price tree leaves | 1,378,991 → 948,475 | 1,431,367 → 981,620 |
| record-id tree leaves | 351,692 → 241,971 | 600,601 → 494,353 |
| load wall-clock (two runs each) | 57.6 / 58.8 → 56.3 / 57.1 s (−2.6 %) | 31.7 / 32.1 → 30.3 / 30.6 s (−4.7 %) |

Replay of catalog A's 401 transactions on the loaded catalog (two runs per arm):

| | insert-built | bulk-built | change |
|---|--:|--:|--:|
| replay wall-clock | 104.7 / 104.0 s | 100.7 / 100.4 s | −3.7 % |
| transaction start → visible, summed | 104.1 s | 100.3 s | −3.7 % (same-arm runs within 1 %) |
| changes visible (merge + publish), summed | 67.0 s | 64.6 s | −3.6 % |
| index heap after the replay | 4,888.8 MB | 4,786.3 MB | −102.5 MB |

Deterministic counters over the same replay: leaf splits +3,428 (record-id 1,138 → 3,132, price 6,160 → 7,594),
internal splits +520 (record-id 52 → 414, price 238 → 396), steals + merges net −282, leaf pages written 5,140 in both.
The replay bypasses both savepoint kinds (`2026-08-26`), so it prices a split without its savepoint memento;
`IntKeyedBPlusTreeMiddleInsertBenchmark` (ordinary transactional middle inserts with the per-entity savepoint active,
bulk-built against insert-built, both trees) covers that half. It is compiled but has not been run yet.

Read side: a storefront query mix (70 query kinds × 100 queries, generated from the recipes in
`documentation/user/en/solve/`, identical results in every run) shows no effect beyond its noise floor: −0.1 % overall,
13 runs with every candidate run between two insert-built runs. That floor is about ±4 % per run, because each run is a
fresh JVM; an earlier 8-run mix had suggested −8 % on sorted listings, and the 13-run mix did not reproduce it.

Tests, all in `evita_functional_tests`:
- `TransactionalElementBPlusTreeBulkBuildTest`, `TransactionalIntToLongBPlusTreeBulkBuildTest` — bulk-built against
  insert-built trees for sizes 0, 1, 2, 62–65, 127–129, 1,000 and 100,000 (signed extremes included); exact leaf
  sizing and the remainder top-up; commits, isolation, abort and warm-up savepoint rollback after a split and a borrow;
  the first middle split of a 2,016-key spine; savepoint rollback of leaves split earlier in the same transaction
  (#1808).
- `TransactionalUnorderedIntArrayBulkBuildTest` — every bulk load path, duplicates (last write wins, one ERROR),
  reserved ids, chain heads after a warm-up rollback.
- `PriceRecordTreeBulkBuildTest` — bulk selection, the non-ascending fallback and its single ERROR, no ERROR on valid
  input, the bound-transaction insert path.
- `PriceListAndCurrencyPriceSuperIndexPagingTest`, `PriceListAndCurrencyPriceSuperIndexTest` — the 63/64 SINGLE/PAGED
  boundary through the SINGLE constructor, page identities across reload → mutate → flush → reload, the savepoint case
  of #1808 through the index's own add/remove paths.

Each was watched failing under a counterfactual before it was counted: every bulk-built node non-participating,
a broken remainder rule, first-write-wins compaction, a spine packed to 32 children, the ordering check removed, and
the pre-#1808 split flag. Gates on the final branch: the full functional suite (29,241 tests, 0 failures, one
Docker-only error), the long-running tree, array and price suites (33 tests, 0 failures), a whole-reactor test-compile.

## Rejected outright

From the same evaluation (`exp/bbtree-*` branches, never merged; every idea behind its own switch, measured on catalogs
A and B in one night).

- **Lower leaf minimum to 1/8.** *Rejected because* steals + merges are 0.86 % of leaf removals in production writes,
  and cutting two thirds of them moved page writes by −0.4 %; a shrunken tree can then stay PAGED where the half-full
  minimum would collapse it inline (eight functional tests fail on that shape). *Revisit if* a delete-heavy workload
  shows rebalancing as a real share of write time.
- **1/8 minimum + end-biased split.** *Rejected because* its gain is fill after ascending inserts in a warm-up rebuild
  (−35 MB, −21.5 % pages written), and almost all of it sits in the price and record-id trees, which every load
  rebuilds anyway, now full; on bucket trees it changes 1 % of the leaves. No latency effect, and page count is not
  bytes written. The minimum is checked only by the consistency report, not at load, so it is not a one-way door.
  *Revisit if* a bytes-written measurement shows a real I/O saving on the trees that persist pages 1:1 (bucket, long,
  super index).
- **Quarter-step column growth and trim.** *Rejected because* −30.4 MB (−0.58 %) after go-live cost +30 % column grow
  events and +2.5 % replay apply time; with the end-biased split the savings added up (−72.7 MB), but that one lost
  too.
- **Decode a front-coded restart block once per search.** *Rejected because* lookups were flat (±5 %): the last probes
  are not where the time goes.
- **SIMD in-node search (Vector API), including a read-only variant.** *Rejected because* lookups gain −10 … −50 % in
  isolation, but ascending inserts lose +29 … +59 % on JDK 21: C2 fails to intrinsify some `VectorSupport::compare`
  sites on the insert path, so masks become objects (+77 … +112 % allocation). Searching with SIMD and inserting scalar
  would avoid that, but the storefront mix with SIMD on shows −2.2 % overall, inside its ±4 % floor. *Revisit if* a
  production profile shows in-node search as a real share of query time, or once the Vector API is final; re-measure
  inserts with `-prof gc` first, the allocation column shows whether the intrinsics hold.
- **8-byte inline keys in front-coded leaves.** *Rejected because* diverse-key lookups gain −44 … −61 % in isolation
  (little on long shared prefixes) for +42.5 / +47.5 MB (+0.8 %) of heap, and the storefront mix shows −1.6 % overall,
  inside its floor. *Revisit if* a profile shows string-key comparisons in unique or sort lookups as a real share of
  query time.
- **Last-leaf append cache.** *Rejected because* with 99.4 % hits, 43 % of a rebuild's leaf inserts skipped their
  descent, yet the rebuild took 1,284.1 s against 1,283.4 s; inside transactions its checks cost +5 %. *Revisit if* a
  rebuild profile shows descents.
- **Inline keys in bucket internal nodes.** *Rejected because* flat everywhere, with no heap change.
- **Leaf block 512.** *Rejected because* rebuild +19 %, replay +11 %, visible p95 +20 %, for −0.17 % heap.
- **Leaf block 128.** *Rejected because* rebuild −3.1 %, but a replay was a wash, heap +0.5 % and pages written in a
  replay +28 %. *Revisit if* per-leaf overhead changes materially.

Rejected at analysis, never built:

- **Inline collation-key windows in leaves.** *Rejected because* the per-locale collation-key cache already cut
  collation from 26 % to 12 % of warm-up CPU (`2026-07-27-write-path-performance-tuning`); little is left to win.
  *Revisit if* query-time collation dominates a localized lookup profile.
- **Fence-key prefix stripping, tree-wide shared prefixes.** *Rejected because* it is valid only where stored order is
  byte order; a localized collation breaks "every key between two fences shares their prefix", and front coding
  compresses prefixes without assuming any order.
- **Separator keys as leaf heads, shortest separators.** *Rejected because* internal nodes hold about 1/128 of the keys
  at our block sizes; their memory is noise.
- **Async batch lanes with software prefetch.** *Rejected because* the JVM has no portable prefetch and multi-value
  lookups are few and small per query. *Revisit if* a workload does thousands of independent lookups per call.
- **Optimistic latches, type-stable pages, an own allocator, aligned nodes.** *Rejected because* readers run on
  immutable MVCC snapshots and the GC owns memory: there is nothing to latch and nothing to reclaim by hand.
- **Memory-mapped file maps.** *Rejected because* they need off-heap trees, and reload is already an `O(n)` page bulk
  load with no comparisons. *Revisit if* an off-heap index design is ever on the table.
- **Coverage-guided fuzzing.** *Rejected because* differential long-running tests against a `TreeMap` at block size 3,
  with the consistency report, already cover the tree family; a corpus-guided fuzzer is an optional extra.
  *Revisit if* a defect class keeps escaping the random tests.

## Consequences & open follow-ups

- **Strict mode for non-ascending price input.** Once the ERROR has never been seen in production, the fallback can
  become a refusal of that one index build; until then it costs nothing and protects nothing.
- **Three more trees are rebuilt by inserts at load**, found in review and deliberately not touched because they are not
  int-keyed and this builder does not serve them: `RangeIndex` inline range snapshots including the price validity
  index (Long tree, ascending thresholds, never paged), `TrigramPostingStore` (Long tree, 512-slot leaves, discovery
  order, `TrigramPostingAccumulator`), and the traffic recording's Object trees (`DiskRingBuffer`). Measure their leaf
  census before porting the builder.
- **Run the per-entity savepoint benchmark** (`IntKeyedBPlusTreeMiddleInsertBenchmark`) in a quiet window; it has
  not run yet. If middle inserts into full leaves cost a real share of an ordinary transaction, Option B or C gets
  reopened.
- **Unexplained, not caused by this change:** a census right after go-live counts 22.1 M bucket entries, the same
  catalog reloaded 16.0 M. Every comparison above stays within one state, so no verdict depends on it.
- **Measurement lesson:** an end-to-end query mix that starts a fresh JVM per run cannot resolve effects under about
  5 % with two runs per arm; plan six or more, interleaved, before ranking read-side changes on it.

## Related work

- `2026-08-01-bplustree-cursor-free-insert-path` — its "ascending by documented contract" holds for the super index
  only; corrected here, and its record points here.
- `2026-09-03-content-sized-value-tree-columns`, `2026-09-04-long-keyed-tree-content-sizing` — why fill now buys only
  per-leaf overhead, and why the record-id trees' physical slack was the exception.
- `2026-08-26-warm-up-per-entity-mutation-atomicity` — WAL replay opts out of both savepoint kinds, which is why the
  replay could not price a split's memento.

## Timeline

- **2026-10-25** — bbtree ideas analysed for portability; nine of them built behind switches and measured overnight.
- **2026-10-26** — record-id trees added, storefront query mix built and run, #1802 designed, reviewed and implemented;
  the adversarial review found the savepoint defect of #1808, fixed on the same branch.
