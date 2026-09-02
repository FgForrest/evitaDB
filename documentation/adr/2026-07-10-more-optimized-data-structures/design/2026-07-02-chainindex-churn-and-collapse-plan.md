# ChainIndex — granular persistence + collapse-cliff removal (revised plan)

**Status:** design ready, adversarially reviewed, awaiting go. Produced from a 4-agent research round
(2026-07-01) followed by a 6-agent adversarial review (2026-07-02) that CONFIRMED the diagnosis, REFUTED three
of the original prescriptions and added one new fix package. All `file:line` anchors verified against this
branch at commit `9996bf9ea`.

## Goal

`ChainIndex` is the LAST attribute sub-index on the monolithic `createStoragePart` path
(`AttributeIndex.java:1120-1123`; UNIQUE/FILTER/SORT already emit granular leaf pages via
`appendStorageParts`). Three liabilities block "safely update 10M+ records in this index":

- **L1 — persistence churn.** `createStoragePart` (`ChainIndex.java:455-514`) rebuilds ALL runs (`int[][]`)
  plus a full per-element `ChainElementState` map into ONE monolithic byte-24 part per dirty commit. The Kryo
  serializer is already slim (per run: varint length + `writeInts` run pks at FIXED 4B/int + head predecessor
  int + head-state varint; non-head predecessors reconstructed positionally on read —
  `ChainIndexStoragePartSerializer.java:68-121`), so the wire cost is ~4 B/element: **~4 MB/commit @1M,
  ~40 MB/commit @10M** — plus a transient flush-time heap spike of ~70 B/element for the `elementStates`
  HashMap the serializer immediately discards (**~0.5–0.7 GB allocation per flush @10M**,
  `ChainIndex.java:464-503`).
- **L2 — in-memory collapse cliff.** `findRun` (`ChainIndex.java:840-851`) linearly scans ALL C chains, each
  probe an O(log N) `indexOf` → **O(C·log N) per call**. `collapse` (`:710-748`) restarts its full scan after
  EVERY merge (`new ArrayList<>(chains.keySet())` per pass at `:714`, `break` at `:744`) and runs on every
  effective mutation (`:313`, `:599`, `:613`) → **O(C³·log N)** achievable worst case. Review sharpening: in
  fork/star states (many split-successor runs pointing at present-but-non-tail predecessors,
  `attachElement:661-662`) **every no-merge pass is already Θ(C²·log N)** — the cliff needs no merge cascade
  at all. Fragmentation triggers are (a) predecessors that stay ABSENT for many ops (odds-then-evens build:
  C→N/2, Θ(N²·log N) total) and (b) interior forks / duplicate-predecessor stars. NOTE (refuted claim):
  plain descending insertion (predecessor always arriving after its successor) does NOT fragment — the
  just-inserted singleton is a tail and merges eagerly, C≤2, O(N·log N) total.
- **L3 — read/flush path (NEW, missed by the original plan).** The ALIVE-state trunk flush runs **with the
  transaction bound to the thread** (`TransactionManager.commitChangesToSharedCatalog` wraps
  `commitCatalogChanges` in `Transaction.executeInTransactionIfProvided`, which binds at
  `Transaction.java:134`; `TransactionTrunkFinalizer.commitCatalogChanges:103-110` calls
  `catalogToUpdate.flush` at `:107` BEFORE `getStateCopyWithCommittedChanges`). Inside a transaction
  `UnorderedLookupTree.getArray()` re-flattens the WHOLE tree on every call (`UnorderedLookupTree.java:438-459`;
  `memoizedArray` only serves the no-transaction branch, invalidated at `:1358-1361`), so
  `createStoragePart`'s per-chain `getSubArray` (`ChainIndex.java:471`) pays **O(C·N) on the commit critical
  path today**. On the query side: `ChainIndexChanges.reset()` fires on every mutation (`ChainIndex.java:298`,
  `:315`); the memo fields `ChainIndexChanges.unorderedLookup`/`.recordIds` are **dead — read at
  `ChainIndexChanges.java:152-155`/`:187-190` but never assigned** — so asc+desc suppliers rebuild everything
  TWICE; the real O(N·log N) hot spot is the **boxed-comparator sort** in the `UnorderedLookup(int[])`
  constructor (`UnorderedLookup.java:100-115`, `IntArrayWrapper extends AbstractList<Integer>` at `:492`;
  ~230M boxed comparisons @10M); and every read-write transaction's FIRST sorted chain query rebuilds the full
  supplier stack even with zero chain mutations (fresh tx layer, `ChainIndex.java:578-590`). At 10M this
  read-side cost is co-dominant with L1.

## Scale reality (severity gate)

ChainIndex instances are per attribute per (reduced) entity index. Per-category reference-scoped
`Predecessor`/`ReferencedEntityPredecessor` chains are many-and-small (C and N tiny — current code fine). A
single 10M-element chain requires whole-collection manual ordering (entity-scoped `Predecessor`) — plausible,
not dominant. The realistic mass-C killer is **bulk import of a global chain in shuffled predecessor order**
(every insert an orphan, C→N, quadratic warm-up). Phase 0 measures real maximum chain sizes before the
biggest budgets are spent.

## Settled questions

- **(a) churn quantified:** see L1 numbers above (derived from the serializer wire format).
- **(b) is an inconsistent index ever flushed?** YES — routine and designed. Any committed transaction with a
  forward reference leaves C>1 and dirty; `createStoragePart` has no consistency gate; the serializer test
  round-trips multi-run and circular states through the production dispatcher
  (`ChainIndexStoragePartSerializerTest.java:150-179`). This definitively kills Design A (below). Additional
  nail: a single CIRCULAR chain counts as CONSISTENT (`isConsistent()` = `chains.size() <= 1`,
  `ChainIndex.java:229-231`) yet its head is a pure operation-history artifact — so even a "consistent-only"
  gate would not save a replay-based format.
- **Reload tie-order is ALREADY nondeterministic today:** `chains` is HashMap-backed; `createStoragePart`
  persists runs in map-iteration order (`:467`) and `getUnorderedLookup`'s stable sort breaks (state, length)
  ties by that order (`:249-260`). Any new reload oracle must compare per-run content + (state, length) tiers,
  never the exact concatenated array, in inconsistent states.

## Rejected options (with review verdicts)

- **Design A — page `predecessors` only, reconstruct order by replay.** REJECTED. Fork-winner and
  circular-head choices are operation-history artifacts living only in the physical order
  (`attachElement:640-659`, `computeHeadState:862-881`); reload yields a different valid order. Inconsistent
  flush is routine (settled (b)), and single circular chains are history-dependent even when "consistent".
  (Corrected rationale: replay would NOT trip `getConsistencyReport` — it validates structure-vs-state
  coherence, which a replayed state self-satisfies — and no pre/post-reload exact-order oracle exists today;
  the defect is restart-visible sort-order instability, a product-level regression.)
- **Design C — additionally page the predecessors map as a second int→int stream.** REJECTED as specified.
  Its "no growing header" claim is false (run boundaries + head identity are exactly what a pk→pred map cannot
  express, so the root keeps per-run data anyway), and the second stream persists ~80 MB @10M of data that is
  positionally reconstructible for free (the slim serializer already proves it,
  `ChainIndexStoragePartSerializer.java:117-120`).
- **Design D — shard the flat `int[][]` into fixed blocks.** STRUCK. Fixed blocks over a POSITIONAL array
  suffer O(N) block invalidation on any mid-array insert (`attachElement:644` `addOnIndex(predecessorPos+1)`
  is the normal path — every downstream element shifts). Dirty-range tracking cannot fix content that
  genuinely moved.
- **Per-chain storage parts (one part per head pk).** REJECTED. Steady state is ONE chain (= monolithic
  again) and head pks are unstable keys (promote re-keys at `:689-691`, merge deletes at `:783`).
- **Bounded collapse (cap merges per op).** REJECTED — leaves committed state fragmented when data IS
  consistent; query-visible order regression.
- **Deferred/batched collapse (collapse-on-observe).** REJECTED as a cliff fix — the final cascade stays
  cubic with the current algorithm, C grows during the txn slowing every `attachElement`, and mutating from
  read paths is a design wart. Becomes pointless once Phase 3 lands.
- **Structural replacement (drop materialized order; derive per version from predecessors).** DEFERRED
  long-term note only — moves Design A's fidelity problem into the LIVE index, needs the same multimap as
  Fix B anyway, and likely regresses memory (boxed maps vs the compact int-array order-statistic tree).

## The program

### Phase 0 — measurement gates (before the big budgets)

1. Fragmenting-workload JMH: **fork/star** (N elements declaring the same/duplicate predecessors) and
   **odds-then-evens** (long-absent predecessors). NOT descending order (refuted trigger — stays C≤2). This
   baselines the collapse cliff for Phases 2–3.
2. Real max chain size on production-shaped datasets (**scratch copies only** — never open
   a real production catalog directory with feature-branch code). If real chains cap at ~10⁵, Phases 1–3 may suffice
   and Phase 4 can be gated on demand.

### Phase 1 — Fix C (flatten-once) + Fix D (read-path package); no format change

**Fix C** — replace per-chain `getSubArray` with one `elements.getArray()` flatten + `System.arraycopy` run
slices at the four sites: `getUnorderedLookup` (`:267`), `createStoragePart` (`:471`), `getConsistencyReport`
(`:369`), `toString` (`:558`). This fixes the O(C·N) in-transaction cost on the COMMIT CRITICAL PATH (L3).
Caveats: outside a transaction `getArray()` returns the shared `memoizedArray` BY REFERENCE
(`UnorderedLookupTree.java:458`) — every slice must be a copy, never an alias (especially any C==1 shortcut in
`createStoragePart`); method bodies are mutation-free between `indexOf` and the slice on one thread/view, so
no TOCTOU. `reconstructState`/`getElementState` still call `findRun` per element — diagnostics stay
O(C·log N)/element until Phase 3 (accepted).

**Fix D** — read-path package (new; the SortIndexChanges precedent, `SortIndexChanges.java:85-97/156-170`):

1. Assign the dead memo fields in `ChainIndexChanges` (`this.unorderedLookup`, `this.recordIds`) inside the
   `orElseGet` chains — two one-liners; stops asc+desc double rebuild and makes the savepoint memento
   meaningful.
2. Primitive-path `UnorderedLookup` construction: a constructor taking precomputed arrays, computed via the
   `IntIntHashMap` + primitive-sort pattern of `TransactionalUnorderedIntArray.getPositions` (`:149-168`) —
   kills the ~230M boxed comparisons @10M. When `isConsistent()`, the lookup array IS `elements.getArray()`
   verbatim.
3. Version-keyed supplier memoization so a mutation-free RW transaction's first sorted query falls through to
   the committed instance's memo instead of rebuilding in a fresh tx layer (`ChainIndex.java:578-590`).
4. Normalize the asymmetric supplier cache identities (`ChainIndexChanges.java:159` uses
   `predecessors.getId()`, `:195` uses `chainIndex.getId()`) — same family as the FilterIndexView
   `getId()==1L` histogram-cache collision fixed earlier on this branch.
5. Known benign race to keep in mind (not fixed here): `createStoragePart` nulls `this.chainIndexChanges` on
   the shared pre-merge instance (`:462`) while concurrent readers of the current version may hold it —
   rebuildable cache, wasted recompute only.

### Phase 2 — single-pass collapse (free win, zero new state)

The restart-after-every-merge in `collapse` is provably unnecessary TODAY: `mergeRunAfter` only DESTROYS
tails (the target's old tail becomes interior; the follower's tail was already a tail) and recomputes state
only for the merged target — collapsibility of untouched runs never increases mid-collapse. One linear pass
over the successor-head snapshot (keeping the null-descriptor skip at `:716`) reaches the fixpoint. Deleting
the `break`+restart (`:714`, `:744`) drops the worst case **O(C³·log N) → O(C²·log N)** with zero new state
and zero STM risk.

Validation: randomized differential test old-vs-new collapse comparing SEMANTIC state (run contents + head
states + `predecessors`), not exact array order — merge order may differ in inconsistent states and
relocate-shorter direction depends on lengths at merge time.

### Phase 3 — findRun replacement + work-queue collapse (the cliff killer)

**3a. findRun: head-bitmap augmentation of `UnorderedLookupTree` (favored) vs the floor reverse index
(fallback).** Decide head-to-head; the review strongly favors the augmentation:

- **Head-bitmap augmentation:** one 64-bit bitmap per leaf (`DEFAULT_BLOCK_SIZE=64` fits one `long`,
  `UnorderedLookupTree.java:91`) marking run heads, plus per-child head COUNTS on internal nodes (mirroring
  the existing record-count augmentation, `:307-320`, `:755-761`). `findRun(position)` becomes ONE
  order-statistic descent — "nearest marked head at position ≤ P" — returning `(headPk, exact headPos)` in
  O(log N). Marks travel with records automatically through split (shift the bitmap with the moved half) and
  steal/merge (whole subtrees move, only aggregates transfer): **zero invisible coherence sites, no new
  transactional member, savepoints ride the existing node layers.** ChainIndex calls `markHead`/`unmarkHead`
  at the ~7 chains-map mutation sites (create `:629`/`:637`/`:658`; remove `:686`; promote `:690-691`;
  split-create `:700-701`; merge-remove `:783`) plus the two constructors. Cost: invasive edits to the shared
  ~1900-line tree (used by the SortIndex family too, per rg) — the augmentation must be near-zero-cost when
  unused (nullable/lazily-allocated head structures) or gated.
- **Floor reverse index (Fix A as originally proposed) — fallback only.** The review found it UNSOUND as
  specified: order-keys are PER-CONTAINER (up to 64 records share one — the floor is ambiguous and needs
  head-SET values + O(64·log N) tie-breaks) and NOT stable (container split re-stamps moved records
  `UnorderedLookupTree.java:787-794`; `respaceOrderKeys` re-stamps EVERY record `:970-1000`;
  `relocateBlockAfter/Before` re-inserts mint new keys `ChainIndex.java:793-818`) — all invisible at the
  ChainIndex level. A sound version must chain into the `OrderKeyConsumer` channel
  (`TransactionalUnorderedIntArray.java:140-144`; new TUIA API, old-key read BEFORE the primary overwrite),
  with `TransactionalLongBPlusTree.lesserOrEqualEntryIterator` as the floor substrate (`:627-633`,
  `:715-721`, boxes values). Substantially more coherence risk than the augmentation.

**3b. work-queue collapse (Fix B, amended).** Replace the scan with a queue processed to fixpoint:

- Substrate: transactional `predecessorPk → heads` multimap — `TransactionalMap<Integer, TransactionalBitmap>`
  per the `EntityIndex.java:134/250` precedent. MUST be Snapshotable-compliant: since the #569 merge, every
  root-entity mutation runs under a per-entity savepoint and `TransactionalLayerMaintainer` throws for any
  non-`Snapshotable` layer touched under one (`TransactionalLayerMaintainer.java:427-434`, `:479-486`).
  TransactionalMap/TransactionalBitmap already comply; no custom layers.
- **Seeds — four, not three** (the review's critical addition): (1) the new orphan head, (2) the promoted
  head, (3) any successor whose predecessor just appeared, and **(4) the newly exposed run tail after a
  tail/middle detach** (`detachElement:684-702` turns the prior element into a tail; a pre-existing orphan
  pointing at it becomes collapsible — found by a multimap lookup at the newly exposed tail pk). Without seed
  4 the queue terminates early and leaves the index uncollapsed relative to today — an observable regression.
- Complexity: O((seeds + merges)·(log N + relocation)); the at-rest invariant (zero collapsible pairs after
  every mutation) bounds seeds to O(1) per mutation. Relocation stays O(min(run)·log N) per merge — inherent
  to keeping physical order.
- New structures populated in the bulk constructor must replicate the **transaction-unbind footgun**
  (`TransactionalUnorderedIntArray.java:104-122`): index load can happen mid-transaction, and constructor-time
  data must land in BASE state or a later `TransactionalMap` delete-cleanup silently empties it.
- STM wiring: enroll in `removeLayer` (`ChainIndex.java:527-533`), `createCopyWithMergedTransactionalMemory`
  (`:537-549`), and the merged-copy private constructor (`:209-222`).
- **Test-oracle audit (required):** the queue changes merge ORDER, and in inconsistent states merge order
  determines physical run order → persisted bytes and `getUnorderedLookup` output. Exact-array oracles that
  will fight: `ChainIndexTest:556-576` (split-after-middle-removal exact arrays), `LongRunningChainIndexTest`
  `:163/:174/:288/:335` (exact order) and `:391` (bounded live-subchain count — a scaling oracle Phase 3 MUST
  keep green), `LongRunningSavepointChainIndexTest:58-61` (exact pre-savepoint order after rollback — extend
  it to assert the new derived structures are coherent after rollback; that suite exists to catch precisely
  this desync class). Where an oracle over-constrains history-dependent order, convert to per-run content +
  (state, length)-tier assertions.
- A `getConsistencyReport` cross-check of multimap/head-marks vs `chains` is cheap insurance — add it.

### Phase 4 — Design B persistence (granular leaf paging), amended

Give `UnorderedLookupTree` the `PagedLeafHandle`/`PageStreamRegistry` SPI and page the `elements` order;
reconstruct `chains`, `valueIndex` and non-head predecessors on load. All amendments below are review
findings — the original Design B sketch is NOT sufficient.

1. **Leaf-capacity knob (mandatory).** ULT's 64-record leaves would mean ~156K pages @10M and the PAGED root
   re-emits its full ordered page list every flush (`OwnerSortIndex.doAppendStorageParts:483-489` pattern) —
   ~0.5–0.6 MB root bytes per single-element commit even at C=1 (**~80× better than today, not the ~10⁵×
   naively expected**). Persisted pages must group many leaves (target 1024–4096 records/page ≈ the branch's
   ~16 KiB page gate) or the root list must be delta-encoded. Decide by measurement.
2. **Run boundaries ride IN the leaf pages, not the root** (kills the worst-case-C root term and converges
   with Phase 3): each persisted page carries its ordered run pks + a head bitset + per marked head its
   `(predecessorPk, state)`. The PAGED root then carries ONLY `highWaterPageSequence` + the ordered page list
   — no O(C) header. (The original "per-run header in root" degenerates to O(N) in the mass-orphan bulk-load
   regime — exactly the confirmed inconsistent-flush + collapse-cliff regime.)
3. **One-leaf-per-page reload is a CORRECTNESS requirement, not an optimization.** `bulkLoad` packs records
   into FULLY-FILLED containers (`UnorderedLookupTree.java:229-249`) — different boundaries than the
   split-history leaves that were persisted. Combining `PageStreamRegistry.restoredFrom`'s cleared-dirty
   baseline (`PageStreamRegistry.java:224-235`) with re-chunked leaves means a later PARTIAL flush interleaves
   pages with mismatched boundaries on disk — silent corruption on the following load. Reload must assemble
   one tree leaf per persisted page (mirror `TransactionalBucketBPlusTree.assembleFromSingleLeafTrees:1325-1348`
   + `InvertedIndex.fromPersistedPages:483-515`). Add a **first-post-load zero-emission test**.
4. **Dirty-flag discipline unique to ULT:** order-keys are ephemeral (NOT persisted; re-minted `i·orderKeyGap`
   at load, `valueIndex` rebuilt — accept the known load-cost family, measure it). Therefore
   `setOrderKey` (`:1477`) must NOT set the leaf dirty flag, or the rare `respaceOrderKeys` pass
   (`:970-1000`, re-stamps EVERY container) forces a full page-rewrite storm. Dirty is set only by content
   mutators (`setCount:1499`, `getRecordIdsForUpdate:1524`). The structural `pageSequence` field must be
   copied in BOTH leaf rebuild branches of the STM merge (`LeafNode` merge branches `:1577-1604`).
5. **Page-lifecycle kit** (every existing paged family needed all of it):
   - `ChainIndexLeafPagePart extends AbstractLeafPagePart` — reuses `LeafStreamKey` with
     `new AttributeKeyWithIndexType(key, AttributeIndexType.CHAIN)` (identity already fits); byte id 43 (next
     free in `IndexStoragePartRegistry`); serializer appended LAST in `IndexStoragePartConfigurer`
     (`index < 700`).
   - `ChainIndexLeafPageRemoval implements DeferredRemovalStoragePart` — mandatory; the append-only
     OffsetIndex copies unreferenced pages forward on every compaction forever.
   - Whole-index page cleanup when a chain index empties mid-life (`AttributeIndex.java:867-870`
     addRemovedItem path).
   - Monolithic→PAGED upgrade for catalogs written by released 2026.1/2025.5: the PAGED root MUST reuse the
     SAME storage-part PK as the monolithic byte-24 part (same `computeUniquePartId`) so the old record is
     superseded naturally; verify no orphan remains after the first paged flush.
   - SINGLE/PAGED discriminator on `ChainIndexStoragePart` with the released SINGLE shape byte-identical
     (BWC policy: intra-dev root change gated behind the discriminator → NO serialVersionUID bump, NO reader;
     brand-new leaf/removal types need no reader).
   - Owner-resident `PageStreamRegistry` on the backing tree, published + carried BY REFERENCE through
     `ChainIndex.createCopyWithMergedTransactionalMemory` (`:537-549`); publishStaged/discardStaged commit
     handshake. Registry allocation happens only at flush time — savepoint-exempt.
   - Loader: PAGED branch in `AttributeIndexLoader.fetchChain` (`:543-577`) resolving the streamId via the
     read-only compressor; move CHAIN from `createStoragePart` to `appendStorageParts` in
     `AttributeIndex.getModifiedStorageParts`; SINGLE-collapse path emits removals for `livePageSequences()`
     BEFORE `forgetPageStream()`.
   - Tests: `EntityIndexRoundTripTest` hand-rolls the chain fetch and must be updated (stale comment at
     `:404` references helpers that moved to `AttributeIndexLoader`); extend `EntityIndexManifestInvariantTest`
     CHAIN assertions (`:179`, `:295-335`); reload oracles tier/content-based per the nondeterminism note.

**Fallback if Phase 4's measured churn disappoints:** delta journal (III-A transfer from
`2026-06-29-sortindex-10m-churn-ideation.md:225-239`) — persist per-commit `(pk, predecessorPk | REMOVE)`
tuples over the last slim snapshot; replaying through the REAL `upsertPredecessor`/`removePredecessor`
reproduces the exact operation-history-dependent state (solves the fidelity problem that killed Design A) at
O(1) bytes/op. Costs: new journal-part machinery with no existing SPI, load = snapshot + replay (Phase 3 is a
hard prerequisite for replay cost), workload-dependent load time. Spike only on demonstrated need.

## Execution order & gating

Phases 1–2 are independent of 3–4 and ship on their own (no format change, low risk). Phase 3 is prerequisite
for safe 10M *mutation* throughput regardless of persistence; Phase 4 is prerequisite for 10M *commit-churn*
reduction. Phase 0 measurements decide whether Phase 4 is scheduled immediately or gated (small real-world
chains → Phases 1–3 may suffice). All work per-phase: fresh critical review before implementation,
`/code-quality-pipeline` after, no commits without explicit permission.

## Phase 0 results (2026-07-02, empirical)

Two sub-agents executed Phase 0. Harness (untracked): `evita_test/evita_functional_tests/src/test/java/
io/evitadb/index/attribute/ChainIndexCliffReport.java`, run against the committed-branch `ChainIndex` (clean),
in-memory, no engine boot, per-run 45 s deadline / ~4 min total. Numbers below are single-shot wall times.

### 0.1 — collapse-cliff baseline (all predictions CONFIRMED)

| Workload | trigger | growth (total) | per-op last/first | maxC | endC | reading |
|---|---|---|---|---|---|---|
| in-order | coherent | ~linearithmic (1M in 709 ms, per-op ~0.6 µs) | ~1× | 1 | 1 | steady-state is FINE even at 1M |
| odds-then-evens | long-absent preds | ~N² (10k=1.8 s, 20k=7.9 s, 45k partial@45 s) | ~1.6× | N/2 | 1 | merge-heavy (fully collapses) |
| fork/star | duplicate preds | ~N² (10k=6.5 s, 20k=26 s, 30k partial@60 s) | **24× and rising** | N−1 | **N−1** | **ZERO merges, yet per-op explodes** |
| random churn | mixed | **~N³** (1k=1.1 s, 4k partial@70 s) | **2334×** (61 ms/op @4k) | — | — | worst case; near-cubic |

Read-path (L3) at 1M in-order: `getUnorderedLookup` 27 ms + supplier build 17 ms per rebuild, fired on every
mutation via `reset()` — O(N), non-trivial at scale.

**Confirmed:** (a) the cliff is real and strictly order-dependent (in-order linearithmic vs fragmenting
super-quadratic); (b) **fork/star degrades per-op ~24× with `endC == N−1`, i.e. NOTHING ever merges** — the
cost is purely the per-candidate `findRun` scan (the Θ(C²·logN)-per-pass floor), not merge cascades; (c)
random churn is near-cubic (single op = 61 ms at N=4k); (d) L3 rebuild is O(N)-ish.

**Refinement that reorders the fixes:** Phase 2 (single-pass collapse) removes only the *restart-after-merge*
factor, so it helps the **merge-heavy** regime (odds-then-evens) but gives **near-zero benefit to fork/star and
the fork-dominated part of random churn** (no merges to restart on). Only Phase 3's `findRun` replacement
(head-bitmap augmentation) removes the Θ(C²) per-pass scan that the fork/star data isolates. Therefore Phase 2
is a cheap partial win but **Phase 3 is the actual cliff killer**; do not expect Phase 2 alone to tame
fork-heavy bulk import.

### 0.2 — realistic scale (read-only research)

`ChainIndex` is per attribute per (reduced) index: `referenceKey == null` → one whole-collection chain,
non-null → many small per-reference chains (N≈10²–10³, C≈1). The cliff needs a single large chain, so only the
entity-scoped whole-collection `Predecessor` case can trigger it — and that case is built/reordered coherently
in practice (`LongRunningChainIndexTest:491-495` bounds live subchains to units/tens across 200k moves; in-order
build keeps C=1). **Fragmentation (C→N/2, the cliff) is a bulk-import / shuffled-load concern, not
steady-state.** No real dataset is confirmed to declare a `Predecessor` attribute; no safe scratch dataset copy
exists (only the untouchable 2.7 GB real production catalog).

### Phase 0 verdict & gating

- **Phases 1–2 ship unconditionally** (low-risk, no format change): Fix C, Fix D, single-pass collapse.
- **Phase 3 is the cliff killer** and is justified for safe bulk-import throughput on large whole-collection
  chains — the fork/star zero-merge result proves the work-queue (Fix B) alone is insufficient; the `findRun`
  replacement is mandatory. Schedule Phase 3 whenever a large whole-collection `Predecessor` chain is in scope.
- **Phase 4 (persistence) stays GATED** on confirming a real single chain with **N ≳ 10⁵ under steady-state
  mutation** — now measured, see 0.2b.

### 0.2b — gate catalog measured (gate RESOLVED → defer Phase 4)

Chain sizes read directly from persisted `ChainIndexStoragePart` records on a disposable copy
(`/var/tmp/catalog-bench`, original never booted) via a new untracked extractor
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/ChainSizeExtractor.java`. The gate catalog **does**
use Predecessor ordering (the earlier plaintext grep missed it — Kryo encodes attribute *types* by registration
id): chain-indexed attributes are `order` (entity `Predecessor`) and `orderInCategory` / `orderInGroup` /
`orderInParameter` (reference `ReferencedEntityPredecessor`).

- 4,575 chain-index records across 9 collections. Chain-size (total N) buckets: ≤10²: 4390 · 10²–10³: 174 ·
  10³–10⁴: 10 · **10⁴–10⁵: 1** · **≥10⁵: 0**.
- **Largest single chain = 32,911** (`PickupPoint | order | ref=null`, GlobalEntityIndex); Product global
  `order` = 6,996; largest reference-scoped chain = 847. **No chain reaches 10⁵.**
- **Gate verdict: N < 10⁵ → DEFER Phase 4.** Churn at 33k is ~130 KB/commit — modest. Phase 4 is confirmed
  deferred with real evidence (re-open only if a whole-collection `Predecessor` chain grows past ~10⁵).

**But fragmentation-at-rest is REAL in a shipped catalog** (contradicts the "bulk-import-only" framing):
PickupPoint global `order` sits at **C = 32,910 over N = 32,911** (`maxRun = 2`); Product global `order` at
C = 2,997 / N = 6,996. So `findRun`'s O(C·logN) scan (~5×10⁵ work/probe on PickupPoint) is paid on a live index
today — **Phase 3 has concrete production relevance, not just bulk-import theory.** This does not move the Phase 4
verdict (N stays < 10⁵).

**Sub-question RESOLVED (head-state analysis): the fragmentation is GENUINE and un-collapsible — no collapse
gap / no shipped-catalog bug.** Persisted per-chain head-states: whole-collection population = 10,746 HEAD +
69,481 SUCCESSOR, of which **collapsible (predecessor present & a tail) ≈ 0** (literally 0 whole-collection, 4
reference-scoped) — essentially every SUCCESSOR head's declared predecessor is **permanently ABSENT** from the
index (the predecessor entity has no `order` value / isn't in this ordering). PickupPoint = 10,495 HEAD + 22,415
absent-predecessor SUCCESSOR; Product global `order` = one real 1,096-long run + ~2,996 orphan singletons. So the
index is already as collapsed as it legitimately can be.

Consequences for the plan:
- **This REFINES the L2 model.** The doc's "absent-predecessor" trigger was framed as a *transient* backlog
  (odds-then-evens: predecessors eventually arrive → collapse merges). In real data the absent predecessors are
  *permanent*, so **high C is a legitimate STEADY-STATE shape**, not just a bulk-import transient — which makes
  `findRun`'s O(C·logN) a permanent steady-state cost on such indexes. Phase 3a's relevance is thus steady-state,
  not merely import-time.
- **Phase 3a (`findRun` head-bitmap augmentation) is the sole effective lever** for this data; it also removes the
  same O(C) factor from `getUnorderedLookup`'s per-chain work, and Fix D (read-path memoization) matters because
  high-C rebuilds are otherwise recomputed on every mutation→query cycle.
- **Phase 2 (single-pass collapse) is a cheap tidy/safety win but will NOT reduce real production cost** (nothing
  collapsible; nothing to restart-on). It remains worth doing for the merge-heavy transient regime but is not the
  payoff for permanent-fragmentation data. Fork/star (present-non-tail) is negligible in real data (0–1
  whole-collection; 349 ≈ 6% of reference-scoped successors).
- **No latent correctness/perf bug to surface** — collapse would merge ~4 pairs catalog-wide.

## Phase 3a — concrete implementation design (findRun head-bitmap augmentation)

Grounded in a full read of `UnorderedLookupTree.java` (1929 lines) at HEAD after Phase 1.

### Invariant
**HM:** a record's head-bit is set in the tree ⟺ that record ∈ `ChainIndex.chains.keySet()` (it is a chain head).
`findRun(position)` returns the run whose head is the greatest head-position ≤ `position`.

### Blast-radius constraint (load-bearing)
`TransactionalUnorderedIntArray` (→ `UnorderedLookupTree`) backs BOTH `ChainIndex.elements` AND the SortIndex
family's `sortedRecords` (SortIndex/SortIndexView/OwnerSortIndex). The augmentation MUST be zero-cost when unused:
gate ALL head work behind a per-tree `boolean headAware` (constructor param). ChainIndex builds its
`TransactionalUnorderedIntArray` with `headAware=true`; SortIndex keeps the existing (`false`) constructors.
When `!headAware`: internal `headCounts` stays `null` (no alloc), leaf `headMask` is never touched (stays 0), and
the query/mutation head-methods throw if called. SortIndex pays only predictable `if (headAware)` branch skips.

### Data structures (gated)
- `LeafNode`: add `long headMask` (bit i = record at slot i is a head). Primitive field, COW exactly like
  `orderKey`: `getHeadMask()/setHeadMask()` via the layer; include in `createLayer`, `snapshot/restore`,
  `createCopyWithMergedTransactionalMemory`, `LeafNodeMemento`. 8 B/leaf always present (cheap; only maintained
  when headAware). BLOCK≤64 ⇒ one long suffices.
- `InternalNode`: add `int[] headCounts` (per-child head count in subtree), parallel to `counts`. Allocated only
  when headAware (else `null`). COW like `counts`: `getHeadCounts()/getHeadCountsForUpdate()`; include (null-tolerant)
  in `createLayer`, `snapshot/restore` (clone if non-null), `createCopyWithMergedTransactionalMemory`,
  `InternalNodeMemento`.
- `UnorderedLookupTree`: add `final boolean headAware`; thread through all 4 ctors + carry through
  `createCopyWithMergedTransactionalMemory`.

### Query methods (new, require headAware)
- `int headRank(int position)` — # heads in `[0..position]`: descend by record-counts to position's leaf summing
  left-children `headCounts`; in leaf add `Long.bitCount(headMask & lowInclusiveMask(leafOffset))`. Guard the
  `1L<<64` UB: `leafOffset>=63 ? -1L : ((1L<<(leafOffset+1))-1)`.
- `HeadLocation selectHead(int rank)` — 1-indexed: descend choosing child whose cumulative `headCounts` brackets
  `rank`, accumulating record-count prefix for `headPos`; in leaf find the k-th set bit → localOffset;
  return `(headPos = recordPrefix + localOffset, recordId = recordIds[localOffset])`.
- `HeadLocation findHeadCovering(int position)` = `selectHead(headRank(position))`. (position 0 is always a head ⇒
  headRank ≥ 1 for all valid positions.) Two O(logN) descents; may fold to one later, not needed now.
- `record HeadLocation(int headPosition, int recordId)` (on `TransactionalUnorderedIntArray`).

### Mutation methods (new/changed, gated)
- `markHead(orderKey, recordId)` / `unmarkHead(orderKey, recordId)` — **idempotent** (descend, read bit, act only
  if state differs; propagate `headCount ±1` up the cursor). Idempotency makes the merge fixup unconditional and
  robust to relocate's remove+reinsert reset.
- `insertIntoContainer`: shift `headMask` at `offset` (keep low bits, shift `[offset,count)` up 1, new bit 0). No
  head-count change (new record never a head).
- `removeByOrderKey`: read `removedBit = (headMask>>>offset)&1`; drop bit `offset`, shift `(offset,count)` down 1;
  if `removedBit` then `propagateHeadCountDelta(cursor,-1)`. (⇒ `remove` AUTO-CLEARS a removed head — callers need
  no pre-unmark.)
- `splitContainer`: `right.headMask = (container.headMask >>> leftCount)`; `container.headMask &= (1L<<leftCount)-1`;
  `rightHeadCount = Long.bitCount(right.headMask)`; thread `rightHeadCount` through `propagateSplit` (parent left-child
  `headCounts[ci] -= rightHeadCount`, insert right with `rightHeadCount`, new-root sets `headCounts[0/1]`).
- Internal structural ops mirror `counts` for `headCounts` **exactly**: `insertIntoInternal`, `splitInternal`
  (+`promotedHeadCount`), `stealChildFromLeft/Right` (`movedHeadCount`, gp fixups), `mergeInternals` (append +
  fold), `removeChildAt`, new-root in `propagateSplit`, `bulkLoad` (all-zero), + a `subtreeHeadCount()` helper for
  the new-root path. `verifyConsistency` also checks `headCounts` when headAware.

### ChainIndex integration
- `findRun(position)` → `final HeadLocation h = elements.findHeadCovering(position); return new RunRef(h.recordId(),
  h.headPosition(), chains.get(h.recordId()).length());` (length stays sourced from `chains`, O(1); kills the O(C) scan).
- Head-transition sites mirror the `chains` map (idempotent marks; `remove` auto-clears the removed record):
  - `attachElement`: `markAsHead(primaryKey)` in head / orphan / split cases (NOT the extend-tail case — head unchanged).
  - `detachElement`: `markAsHead(newHead)` on head-promote; `markAsHead(suffixHead)` on middle-split. (Removed record
    auto-cleared by `remove`; tail-shrink needs no mark.)
  - `mergeRunAfter`: after relocate + chains update, unconditionally `markAsHead(targetHeadPk)` +
    `unmarkAsHead(followerHeadPk)` (idempotent covers all adjacent/relocate branches).
  - array ctor: after bulk build, `markAsHead(chain[0])` for each chain (C·O(logN); construction only). merge-copy
    ctor: marks ride through the tree's own merge — no explicit marking.
- ChainIndex builds `elements` via the `headAware=true` `TransactionalUnorderedIntArray` ctors.

### Transactional / savepoint correctness
Head fields ride the EXISTING COW/merge/memento machinery (headMask like `orderKey`; headCounts like `counts`), so
per-transaction path-copy, commit merge, and #569 per-entity savepoints already cover them once the fields are added
to `createLayer`/`snapshot`/`restore`/`createCopyWithMergedTransactionalMemory`/mementos. No new transactional member.

### Edge cases
`1L<<64` UB (guard at leafOffset 63); empty tree / single-leaf root (headRank/selectHead handle depth-0);
`headCounts==null` when `!headAware` (never read on that path); relocate remove+reinsert resets moved head bits
(idempotent merge fixup restores correct state).

### Test plan
Reuse ChainIndex suites (exact-order oracles unchanged — findRun is internal). Add: (1) tree-level headRank/selectHead/
findHeadCovering property test vs brute-force over random head sets incl. cross-leaf-boundary runs at small blockSize
(force splits/steals/merges); (2) headAware=false regression — SortIndex trees never allocate headCounts / mutate
headMask (assert via consistency + a targeted probe); (3) savepoint test marking heads then rollback/commit; (4) the
existing SortIndex* + LongRunning(Savepoint)ChainIndexTest suites must stay green (zero-cost gating proof).

### Review amendments (BLOCKING fixes — adversarial review 2026-07-02, verdict GO after these)
- **R1 (BLOCKING) — mask width vs transient 65-record container.** `insertIntoContainer` grows a container to
  `blockSize+1` BEFORE splitting; with `blockSize=64` a head at slot 63 shifts into nonexistent bit 64 and is lost
  (and remove's `>>> (offset+1)` is `>>> 64` UB at offset 63). **Fix: headAware trees use `blockSize=63`** (transient
  max = 64 = mask width; at-rest ≤ 63 ⇒ remove offset ≤ 62). ChainIndex constructs its tree as `(blockSize=63,
  headAware=true)`; SortIndex keeps 64. Only affects container packing (≈1.6% more containers), NOT logical order, so
  ChainIndex/serializer oracles are unaffected. The split shift is already safe (`leftCount=total/2 ≤ 32`).
- **R4 — array-ctor marking must run inside the bulk-load unbind window.** `new TransactionalUnorderedIntArray(int[])`
  bulk-loads with the transaction unbound (TUIA:110-121) so data lands in BASE; a post-hoc `markAsHead` would run
  re-bound and land in a discardable layer. **Fix: a `bulkLoadWithHeads(recordIds, sortedHeadPositions, consumer)`
  variant sets masks/headCounts during the O(N) bottom-up build**; ChainIndex passes head positions (chain
  concatenation offsets) to a headAware TUIA ctor. (Also replaces C·O(logN) post-marking with O(N).)
- **R5 — gate STATIC helpers on the node's own `headCounts != null`, not `this.headAware`.** `insertIntoInternal`,
  `splitInternal`, `stealChildFromLeft/Right`, `mergeInternals`, `removeChildAt` are static and can't read the tree
  flag; they mirror head-counts iff the operated node has non-null `headCounts` (uniform within a tree). Read head
  values from the node arrays (`movedHeadCount`, `absorbedHeadCount`, `promotedHeadCount`); only `insertIntoInternal`
  + `propagateSplit` new-root need a threaded `childHeadCount`/`subtreeHeadCount`. Every copy/merge/memento site
  (~14: leaf ctor/createLayer/snapshot/restore/3× merge/memento; internal ctor/createLayer/snapshot/restore/4× merge/
  memento; tree private ctor + merge) must carry the head field and null-guard the `.clone()`.
- **R6 — boundary test at PRODUCTION blockSize.** Small-blockSize property tests can't reach the 64/65 edge. Add a
  `blockSize=63` full-container test (head at top slot → middle insert → force split → assert head survives via
  `findHeadCovering`) + the headAware=false no-alloc/no-mutate regression + the savepoint mark→rollback/commit test.

### Implementation sequencing
- **Step A** — full `UnorderedLookupTree` augmentation + `TransactionalUnorderedIntArray` plumbing + tree unit tests
  (`UnorderedLookupTreeTest`), gated headAware; verify tree tests + ALL SortIndex* suites green (zero-cost proof).
- **Step B** — wire `ChainIndex` (headAware+blockSize=63 ctors, `findRun` rewrite, mark sites, bulkLoadWithHeads);
  verify ChainIndex + LongRunning(Savepoint)ChainIndex green.
- **Step C** — `/code-quality-pipeline` over Phase 3a. No commit without permission.

## Fix B — concrete implementation design (work-queue collapse)

Grounded in the post-Phase-1+3a `ChainIndex.java` (1025 lines) and the `EntityIndex`
`TransactionalMap<Locale, TransactionalBitmap>` STM precedent (`EntityIndex.java:311` construction,
`:307-311` merge re-wrap, `TransactionalMap.java:210-216` recursive `removeLayer`).

### Goal
Replace `collapse()`'s O(C)-per-mutation work (`new ArrayList<>(this.chains.keySet())` snapshot at `:741`
+ restart-after-every-merge `break` at `:771`) with a targeted work-queue seeded only by the events that can
create a *newly collapsible* pair. Turns steady-state collapse from **O(C) → O(seeds) per mutation**: the real
gate catalog's `PickupPoint | order` index (C = 32,910 permanent orphans) currently rescans all 32,910 chains on
*every* single-element mutation (finding zero merges) and allocates a 32,910-element `ArrayList` each time — Fix B
makes it O(1). Also kills the fork/star bulk-import Θ(C²)-per-pass warm-up. **Supersedes Phase 2** (single-pass
collapse folds in as the merge-heavy special case).

### Collapsibility-flip model (why the seeds are complete — the load-bearing argument)
A SUCCESSOR-state head `H` is *collapsible* iff `predecessors.get(H) = P`, `P != HEAD_PK`, `P` is present, and `P`
is the **tail** of a run other than `H`'s. `H`'s collapsibility flips to true only via:
- **(a)** `H` newly becomes a head while `P` is already present-and-a-tail. Attach's tail-extend branch
  (`:664-676`) absorbs "pred present & tail" *without* creating a new head, so a freshly attached head never
  satisfies (a) at creation. The only (a) case is the **promoted head** (`detachElement` head-removal `:712-716`)
  once the subsequent re-attach makes its predecessor a tail.
- **(b)** `P` newly becomes **present** (absent→present) — i.e. the element `P` is attached this op.
- **(c)** `P` newly becomes a **tail** (interior→tail) — `P`'s positional successor is removed (`detach` exposes
  `P`), or `P` is freshly attached as a singleton/extension tail.
- **cascade**: each merge exposes the merged run's new tail `T` (= follower's tail); waiters on `T` get a fresh
  (c) event.

Every attach lands the new element as a **tail** of its run (head/orphan/split → singleton tail; tail-extend →
new tail). So the productive triggers per op are: the attached pk (covers (b)+(c) for itself and, via its waiters,
(b) for others), the promoted head (covers (a)), and the detach-exposed tail (covers (c)). A uniform
`enqueueTrigger(t)` that enqueues **t itself and every element declaring t as predecessor** discharges all four
seeds.

### Substrate — `successorsByPredecessor` (materialized inverse of `predecessors`)
`final TransactionalMap<Integer, TransactionalBitmap> successorsByPredecessor` — key = predecessor pk, value =
bitmap of every element pk whose stored predecessor equals it. The **exact inverse of `predecessors`**, NOT
filtered to heads.

**Why the full inverse (Option A) over a heads-only multimap (Option B, as the umbrella plan sketched):** Option A
is a pure function of `predecessors` (invariant `successorsByPredecessor == inverse(predecessors \ HEAD_PK)`), so
maintenance is two mechanical ops at the two public predecessor-mutation entry points — no coupling to the ~8
state-dependent head-transition sites, no stale-entry accumulation, and `getConsistencyReport` verifies it in one
pass. Option B is leaner at rest (O(C) vs O(N)) but couples to every head create/destroy/state-change and needs
lookup-time filtering to shed stale entries — far more desync surface for a data structure whose collapse
correctness is guarded by fuzz + savepoints. The O(N) memory is an increment over the already-O(N) boxed
`predecessors` map, negligible for the many-small-chains common case (N≈10²–10³, C≈1) and ≤~33k roaring ids for
the largest real chain. Correctness-over-memory is the right trade here.

Constructed with the 3-arg `TransactionalMap(delegate, TransactionalBitmap.class, TransactionalBitmap::new)` ctor
(`EntityIndex.java:311`) so STM recurses into the bitmap values on merge/removeLayer automatically.

### Maintenance (centralised in the two public entry points + ctor)
- `upsertPredecessor` insert branch (`existingPredecessor == null`): `linkSuccessor(predecessor.predecessorPk(),
  primaryKey)`.
- `upsertPredecessor` update branch (real change — the equal-pred early-return at `:298-300` guards it):
  `unlinkSuccessor(existingPredecessor, primaryKey); linkSuccessor(predecessor.predecessorPk(), primaryKey)`.
  Both old and new predecessors are in scope here (`existingPredecessor` read at `:294`), so this is the clean
  injection point — no plumbing through `detach/attach`.
- `removePredecessor`: after `predecessors.remove`, `unlinkSuccessor(existingPredecessor, primaryKey)`.
- Array ctor (`:168`): build a plain `Map<Integer, TransactionalBitmap>` inverse from `elementStates` (bucket
  values = `new TransactionalBitmap(sortedSuccessorPks)`) and hand it to the 3-arg TransactionalMap ctor — lands
  in **BASE** state (mirrors the `predecessors` base-load at `:211`; **no `.put()` after construction**, so the
  transaction-unbind footgun cannot empty it).
- `linkSuccessor(pred, pk)`: skip when `pred == HEAD_PK` (a true head never collapses via its predecessor, so its
  inverse entry is never looked up — keeps the map lean); else
  `successorsByPredecessor.computeIfAbsent(pred, p -> new TransactionalBitmap()).add(pk)`.
  **NB (review R1, BLOCKING):** the mapping function MUST be the lambda `p -> new TransactionalBitmap()`, NOT the
  method reference `TransactionalBitmap::new` — as a `Function<Integer, TransactionalBitmap>` the method reference
  binds the `TransactionalBitmap(int...)` varargs ctor and would seed each bucket with its own predecessor key.
  Use scalar `add(int)` (boolean), not the `addAll(int...)` varargs, to avoid a 1-element array alloc on the hot
  path.
- `unlinkSuccessor(pred, pk)`: skip `HEAD_PK`; else fetch the bitmap, scalar `remove(pk)`; when it becomes empty,
  `successorsByPredecessor.remove(pred)` **and** `removeTransactionalMemoryLayerIfExists(bitmap)` (static import
  from `Transaction`, mirroring `EntityIndex.removeLanguage:474-477`) so the emptied bitmap's changes layer is
  released — keeps the map as sparse as the at-rest chain set.
- **Ordering:** `link/unlinkSuccessor` run in `upsertPredecessor`/`removePredecessor` (where the pre-change
  `existingPredecessor` is in scope), so they execute *after* the nested `collapse(...)`. This is safe: the buckets
  they touch (`waiters[oldPred]`, `waiters[newPred]`) are disjoint from the buckets that operation's collapse
  consults (`waiters[pk]`, `waiters[promotedHead]`, `waiters[exposedTail]`, `waiters[followerTail]` — none of which
  is `pk`'s own predecessor), and the merge decision reads authoritative `predecessors`, never the inverse. The
  inverse is consistent again by the time the public method returns, so the `getConsistencyReport` cross-check
  (only ever called at rest) always sees `inverse(predecessors)`.

### collapse(triggers) rewrite (replaces `:737-775`)
```java
private void collapse(@Nonnull IntArrayList triggers) {
    final IntArrayDeque queue = new IntArrayDeque();
    final IntHashSet queued = new IntHashSet();               // dedup: bound the work, avoid re-poll churn
    for (int i = 0; i < triggers.size(); i++) enqueueTrigger(queue, queued, triggers.get(i));
    while (!queue.isEmpty()) {
        final int headPk = queue.removeFirst();
        queued.remove(headPk);                                // allow a later cascade to re-touch it
        final ChainDescriptor d = this.chains.get(headPk);
        if (d == null || d.state() != ElementState.SUCCESSOR) continue;   // not a follower any more
        final Integer predRef = this.predecessors.get(headPk);
        isPremiseValid(predRef != null, ...);
        final int pred = predRef;
        if (pred == HEAD_PK) continue;
        final int predPos = this.elements.indexOf(pred);
        if (predPos == Integer.MIN_VALUE) continue;           // predecessor absent
        final RunRef predRun = findRun(predPos);              // O(log N) since Phase 3a
        if (predRun.headPk() == headPk) continue;             // circular guard
        if (predPos == predRun.headPos() + predRun.length() - 1) {        // predecessor is a tail
            final int followerTailPk =                        // merged run's future tail = follower's tail
                this.elements.get(this.elements.indexOf(headPk) + d.length() - 1);
            mergeRunAfter(predRun.headPk(), headPk);
            enqueueTrigger(queue, queued, followerTailPk);    // cascade
        }
    }
}
private void enqueueTrigger(IntArrayDeque queue, IntHashSet queued, int t) {
    if (queued.add(t)) queue.addLast(t);                                  // t as a follower candidate
    final TransactionalBitmap waiters = this.successorsByPredecessor.get(t);
    if (waiters != null) {
        final OfInt it = waiters.iterator();
        while (it.hasNext()) { final int w = it.nextInt(); if (queued.add(w)) queue.addLast(w); }
    }
}
```
**Termination:** every merge strictly decreases the run count C (two runs → one), so ≤ initial-C merges; between
merges the queue drains a finite `queued` set. **Fork/star:** insert X (pred present-not-tail) → trigger {X},
`waiters[X]=∅` → poll X, pred-not-tail → skip → O(log N)/op, cliff gone. **Real PickupPoint steady state**
(all-absent-predecessor orphans): a single mutation seeds O(1); each seed's pred is absent → skip → O(1)/mutation
vs. today's 32,910-chain scan + list alloc.

### Seed assembly at the three call sites
`detachElement` returns `DetachOutcome(int promotedHead, int exposedTail)` (`Integer.MIN_VALUE` = N/A):
- singleton removal → `(MIN, MIN)`.
- head promote (`position == headPos`, len > 1) → `(newHead, MIN)`.
- tail/middle removal (`position > headPos`) → `(MIN, this.elements.get(position - 1))` **after** `remove` — the
  prefix's new tail. (The middle-split `suffixHead` needs no separate seed: its predecessor is the detached pk, so
  in `updateElement` it is reached via `waiters[pk]` when pk re-attaches; in `removePredecessor` pk is gone so
  suffixHead is a permanent orphan — correctly not seeded.)

- `insertElement(pk, pred)`: `attachElement(...); collapse([pk])`.
- `updateElement(pk, pred)`: `final DetachOutcome o = detachElement(pk); ...put pred...; attachElement(...);
  collapse([pk] + o.promotedHead? + o.exposedTail?)`.
- `removePredecessor(pk)`: `final DetachOutcome o = detachElement(pk); collapse(o.promotedHead? + o.exposedTail?)`
  (pk is gone; its own follower candidacy is moot).

### STM wiring
- Field initialised in empty ctor (`:150`) + array ctor (`:168`, BASE inverse) with the 3-arg TransactionalMap.
- `removeLayer` (`:545`): add `this.successorsByPredecessor.removeLayer(transactionalLayer);` (one call — recurses
  into bitmap values, `TransactionalMap.java:210-216`).
- `createCopyWithMergedTransactionalMemory` (`:555`): pass
  `transactionalLayer.getStateCopyWithCommittedChanges(this.successorsByPredecessor)` to the private merge ctor.
- Private merge ctor (`:214`): receives `Map<Integer, TransactionalBitmap>`; re-wrap each value into a fresh
  `new TransactionalBitmap(value)` and build the 3-arg TransactionalMap (`EntityIndex.java:307-311` pattern).
- **Savepoints (#569):** `TransactionalMap<Integer, TransactionalBitmap>` and `TransactionalBitmap` are already
  `Snapshotable`, so the field rides the existing per-entity savepoint machinery with no custom layer — validated
  by `LongRunningSavepointChainIndexTest`.

### getConsistencyReport cross-check (cheap desync insurance)
Add a verification block: every `(E → P)` in `predecessors` with `P != HEAD_PK` must have `E ∈
successorsByPredecessor.get(P)`, and every bucket entry must correspond to a live `predecessors` mapping. Desync
surfaces immediately in the existing fuzz + savepoint suites (which already call `getConsistencyReport`).

### Test-oracle audit
- **ChainIndexTest** exact-array oracles (`getUnorderedLookup().getArray()`): `getUnorderedLookup` re-sorts runs by
  (state, length) and slices, so a CONSISTENT-ending test (single chain, C=1 — the majority) is order-invariant and
  unaffected. INCONSISTENT-ending collapse/window oracles (`ChainCollapseTest` ~:515/:546,
  `InconsistentWindowCharacterizationTest`, `CircularDependencyTest`) can shift only under **non-confluence**
  (duplicate-predecessor forks, where which fork-child wins is already HashMap-order-dependent today). Audit each
  break: confirm it is a valid alternative fixpoint (same element multiset, no remaining collapsible pair, not
  BROKEN) and relax to per-run-content + (state, length) tiers per the nondeterminism note; if nothing breaks, the
  suite simply doesn't exercise non-confluence.
- **LongRunningChainIndexTest:** coherent single-chain oracles (`assertArrayEquals` + CONSISTENT at :163/:174/:335)
  are order-invariant. The **bounded-subchain oracle (:391-494)** directly asserts the work-queue reaches *full*
  collapse (C stays units/tens) — this is the primary anti-under-collapse guard; MUST stay green.
- **LongRunningSavepointChainIndexTest:** validates the new structure snapshots/restores under #569 savepoints.
- **NEW differential/fixpoint test:** random op sequence → run new collapse; assert (a) element multiset unchanged,
  (b) `getConsistencyReport().state() != BROKEN`, (c) **fixpoint**: a brute-force scan mirroring the OLD collapse
  finds NO further merge → proves seed-completeness (no under-collapse regression vs. today).

### Implementation sequencing
- **Step A** — add `successorsByPredecessor` + maintenance (`link/unlinkSuccessor`) + STM wiring + array-ctor BASE
  inverse + `getConsistencyReport` cross-check, but **leave `collapse()` as the old scan**. Compile;
  ChainIndexTest + LongRunning green; the inverse invariant holds under fuzz. (Decouples "new structure correct"
  from "new algorithm correct".)
- **Step B** — swap `collapse()` to the seeded work-queue + `DetachOutcome` + the three call sites + the
  differential/fixpoint test + oracle audit. ChainIndexTest + LongRunning(Savepoint) green.
- **Step C** — `/code-quality-pipeline` over Fix B. No commit without permission.

## Phase 4 (P3-amended) — concrete implementation design (2026-07-02, approved by Johnny)

**Decision record.** Johnny overrides the §0.2b defer: implement paged persistence NOW, measure before/after
(baseline JMH done: `evita_test/evita_performance_tests/.../spike/ChainIndexPersistenceChurnBenchmark.java` —
monolithic flush ≈ 4 B/elem disk but **~425 B/elem ALLOCATED** ⇒ ~4.25 GB alloc/commit @10M; DISCONNECTED
super-linear via `indexOf`-per-chain). Delta-journal REJECTED (Johnny): per-commit tiny parts waste OffsetIndex
record overhead + I/O (cannot batch across commits — durability), and the periodic re-snapshot is an O(N)
alloc/write spike — paging is smooth O(touched pages) forever. Granularity decision = **P3: page-sized leaves**
(over P2 leaf-grouping, which has identical write amplification but adds the two-level boundary-stability
corruption family; over P1 63-record parts, which fail the 4 KiB SSD criterion and re-emit a 159K-entry root
@10M). A fresh critical review (2026-07-02) of the original Phase 4 section produced corrected anchors, two new
BLOCKING landmines (A, G below) and the step breakdown this section incorporates; its "premise false" finding
stands: **no family ever paged a `UnorderedLookupTree`** — OwnerSortIndex pages an `InvertedIndex` and
*reconstructs* the positional array (`OwnerSortIndex.java:191-216`); the ULT paging SPI is from-scratch work.

### P3 core: ChainIndex's tree uses page-sized leaves
**F1 (BLOCKING, review): `DEFAULT_BLOCK_SIZE = 64` is the PHYSICAL array capacity, not a logical cap**
(`UnorderedLookupTree.java:88-91` "Node arrays are always allocated to this fixed size"; `LeafNode.recordIds =
new int[DEFAULT_BLOCK_SIZE+1]` :1841; ctor HARD-throws blockSize>64/>63-headAware :192-198; LeafNode/InternalNode
are STATIC nested :1803/:2075 — no instance blockSize). ONE blockSize drives leaf capacity AND internal fan-out
AND minChildren=(blockSize+1)/2. So a per-instance `blockSize=1024` throws in the ctor, and the naive "bump the
static constant" re-sizes EVERY SortIndex leaf to 16 KB → **destroys the zero-cost claim.** This decoupling — not
the headMask — is the bulk of the P3 risk and IS step 3.
- **Introduce a per-instance `leafCapacity`, DECOUPLED from internal `blockSize` fan-out (fan-out stays ~64).**
  Only LEAF arrays (`recordIds`, order-keys, head-mask words) are sized by `leafCapacity`; internal-node child
  arrays and split/minChildren math keep the ~64 fan-out. Thread `leafCapacity` through the static `LeafNode`
  ctors (:1841/:1857), the 3 fresh-leaf sites (`new LeafNode(true)` :292/:604/:1088), `createLayer` (:1963) and
  `assembleFromLeafPages`. ChainIndex paged trees set `leafCapacity = PAGE_RECORDS = 1024`; SortIndex leaves stay
  65-wide → **zero-cost genuinely preserved.** **One tree leaf == one persisted page** ⇒ review landmine 3
  (one-leaf-per-page reload) holds *by construction*; root page list @10M ≈ 9.8K entries (~40 KB/commit) vs 159K.
- **PAGE_RECORDS = 1024 (review-recommended; not 4096).** Target is CHURN: a touched page re-emits in full →
  1024 rec ≈ 4 KiB (SSD-page-aligned) vs 4096 ≈ 16 KiB = **4× less write/alloc per touched page**, and COW/
  memmove/indexOf per op are 4× cheaper. Root list is a non-bottleneck at either (both ≫16× under the 159K
  problem). 512 = 2 KiB is sub-page → 1024 is the floor. Confirm via churn JMH; if sweeping try {1024, 2048},
  do NOT default 4096 (its only edge — smaller root — is immaterial).
- **headMask generalization:** single `long` → `long[ceil((leafCapacity+1)/64)]` (**F2: the `+1` is mandatory** —
  a container transiently holds `leafCapacity+1` records pre-split and `insertHeadSlot` up-shifts a bit to index
  `leafCapacity` :1040-1043; plain `ceil(cap/64)` under-allocates one word at 1024 AND 4096 → AIOOBE/dropped-bit
  corruption). headAware=false trees allocate NO mask words (null) — SortIndex zero-cost. Generalize markHead/
  unmarkHead/headRank/selectHead/findHeadCovering + the multi-word **split shift** (partition by leftCount up to
  leafCapacity/2 :1096-1104 — the genuinely hard `long[]` bit, harder than the `Long.bitCount` headRank loop).
  Leaves are NEVER merged/stolen (delete removes only EMPTY containers :134-136) → no merge/steal mask shift.
- Live-op cost (M5, accepted): within-leaf position lookup is a LINEAR O(leafCapacity) scan (`indexInContainer`
  :450/:1716-1730, used by indexOf/markHead/insert/remove; computeHeadState CIRCULAR does 2×) → 63→1024 is ~16×
  longer per scan (still L1-resident µs) + insert memmove O(leafCapacity). COW of a touched leaf ≈ leafCapacity
  ints (~4 KB @1024) per leaf per tx (per-tx FIRST-touch, not per-op :1953-1956) — noise vs 4.25 GB today.
  Favors the smaller page; churn JMH confirms net win.

### Page payload (landmine A fix — persist NO derived facts)
`ChainIndexLeafPagePart` = `pageSequence` + ordered `recordIds[]` + head bitset (the leaf's headMask bits) +
per-marked-head `predecessorPk[]` (aligned with set bits). **NO chain state, NO run length** — a head's
state/length can be flipped by a mutation in a DIFFERENT leaf (its own leaf stays byte-clean ⇒ not re-emitted ⇒
stale on disk). Both are recomputed at load: `length` = distance to next head mark across concatenated pages
(runs may span pages — boundaries derive from head marks globally, not per-page); `state` = `computeHeadState`
from the persisted head `predecessorPk`. `predecessorPk` IS dirty-safe: changing it always mutates the head's
own leaf (predecessors.put(headPk) relocates/re-marks it).

### ULT paging SPI (from scratch, gated)
- Per-tree `boolean paged` gate (headAware precedent — near-zero-cost when false; SortIndex family never sets it).
- Per-`LeafNode`: `int pageSequence` (UNASSIGNED default) + `boolean dirty`; threaded through COW, STM merge,
  savepoint snapshot/restore/`LeafNodeMemento` (`UnorderedLookupTree.java:1975-2057`,
  `createCopyWithMergedTransactionalMemory :2000`) exactly like orderKey/headMask.
- **Dirty discipline:** content mutators (`setCount :1921`, `getRecordIdsForUpdate :1946`, record add/remove,
  head mark/unmark) set dirty; **`setOrderKey :1877` must NOT** (order-keys are ephemeral, re-minted at load;
  otherwise one `respaceOrderKeys :1322` pass re-emits every page — the storm landmine).
- SPI surface (mirror `TransactionalBucketBPlusTree`'s): `leafPageHandles()` ascending, `collectChangedPages()`,
  `livePageSequences()`, `forgetPageStream()`, boundary-stable `assembleFromLeafPages(...)` (one leaf per page,
  stamp pageSequence, dirty=false — do NOT bulkLoad/repack), all surfaced through `TransactionalUnorderedIntArray`.
- Owner-resident `PageStreamRegistry` (one BUCKET stream) carried BY REFERENCE through ChainIndex merge
  (`ChainIndex.java:691-705`) + TUIA + ULT merged-copy ctors; `publishStaged`/`discardStaged` commit handshake;
  registry is flush-time-only ⇒ savepoint-exempt (per-leaf pageSequence/dirty are NOT — they ride mementos).

### Root part + BWC
`ChainIndexStoragePart` gains SINGLE/PAGED discriminator: PAGED = `highWaterPageSequence` + ordered
`pageSequences[]` (no element data); SINGLE = existing shape. Serializer: nested boolean discriminator
(`SortIndexStoragePartSerializer:146-161` pattern) — intra-dev change ⇒ **NO serialVersionUID bump, NO new
reader** (released `_2026_1`/`_2025_5` readers untouched; dispatch is by stored serialVersionUID via
`SerialVersionBasedSerializer:99-116`). **M6: byte 24 is the storage-part TYPE** (`ChainIndexStoragePart`,
`IndexStoragePartRegistry:49`), NOT the PK. The PAGED root stays a `ChainIndexStoragePart` (type 24) with the
SAME `computeUniquePartId` (packs entityIndexId + compressorId of `AttributeKeyWithIndexType(attr, CHAIN)`) →
the first paged flush of an upgraded catalog supersedes the old monolithic record — verify no orphan remains.
New leaf types: `ChainIndexLeafPagePart extends AbstractLeafPagePart` + `ChainIndexLeafPageRemoval implements
DeferredRemovalStoragePart` (no serializer). **Register in TWO places (M6):** `IndexStoragePartRegistry` byte id
**43** (highest existing = 42, `:67`) AND `IndexStoragePartConfigurer` Kryo (next free index **636**, `index <
700`) — the byte id is how OffsetIndex resolves the record type; omitting it breaks reads.

### Flush & load
- Move CHAIN from `createStoragePart` to `appendStorageParts` in `AttributeIndex.getModifiedStorageParts`
  (`AttributeIndex.java:1120-1123`), mirroring `OwnerSortIndex.doAppendStorageParts:461-499`: small index ⇒
  SINGLE inline part; large ⇒ dirty pages + removals for freed pages + PAGED root. PAGED→SINGLE collapse emits
  `livePageSequences()` removals BEFORE `forgetPageStream()` (`OwnerSortIndex:491-498` pattern).
- Loader: PAGED branch in `AttributeIndexLoader.fetchChain` (`:543-572`; template `fetchSort :496-522`) →
  `ChainIndex.fromPersistedPages`. **F3 (MAJOR, review): reconstruction ORDER is load-bearing** — `computeHeadState`
  reads BOTH `elements.indexOf(...)` AND `predecessors.get(headPk)` and premise-FAILS if the predecessor entry is
  absent (`ChainIndex.java:1110-1129`, esp. :1112). Rebuilding chains before predecessors THROWS. Correct order:
  (1) assemble `elements` 1:1 from pages (stamp pageSequence, dirty=false); (2) populate `predecessors` over the
  FULLY-assembled array — heads: persisted `predecessorPk`; non-heads: positional predecessor = previous record
  (a page-boundary non-head takes the LAST record of the previous page); (3) THEN `chains` via `computeHeadState`
  per head, with `length` = distance to next head mark across concatenated pages and **last run `length` = size −
  lastHeadPos** (no next head); (4) derive `successorsByPredecessor` (array-ctor logic `ChainIndex.java:235-248`);
  set head marks. `PageStreamRegistry.restoredFrom` baseline. **First-post-load zero-emission test is mandatory.**

### Landmine G — whole-index empty-drop page leak (fix here, latent everywhere)
When an emptied ChainIndex is dropped from the sub-index map, `addRemovedItem` (`AttributeIndex.java:867-871`)
only schedules `removeLayer` — NO storage removals are emitted and the dropped index's `appendStorageParts`
never runs again ⇒ its live pages leak forever (append-only OffsetIndex copies them forward on every
compaction). Fix: flush-time dropped-PAGED-key diff in `AttributeIndex.getModifiedStorageParts` emitting
`livePageSequences()` removals for dropped keys (EntityIndex manifest-diff `:869-874` pattern). NOTE: the same
latent leak exists in the SHIPPED paged families (Filter/OwnerUnique/OwnerSort) — implement the mechanism for
CHAIN, design it to generalize, file the family-wide fix as follow-up.

### Step order (F4-reordered: each step compilable + full suite green at its boundary)
1. [mech] Root discriminator + serializer (nested boolean; no UID bump). Standalone.
2. [mech] `ChainIndexLeafPagePart` + `ChainIndexLeafPageRemoval`; register TWO places — `IndexStoragePartRegistry`
   byte **43** AND `IndexStoragePartConfigurer` index **636** (M6). Payload per landmine-A. Standalone.
3. [HARD — RISKIEST, F1] ULT: **per-instance `leafCapacity` decoupled from internal `blockSize` fan-out** (size
   ONLY leaf `recordIds`/order-keys/head-mask words by leafCapacity; static LeafNode ctors :1841/:1857 + 3 fresh
   sites + createLayer + assembleFromLeafPages take it; fan-out/minChildren stay ~64); headMask single→
   `long[ceil((leafCapacity+1)/64)]` (F2 `+1`) incl. the multi-word split shift; `paged` gate; per-leaf
   pageSequence+dirty through COW/merge/memento; dirty discipline (setOrderKey must NOT dirty); `leafPageHandles`/
   `collectChangedPages`/`livePageSequences`/`forgetPageStream`; surface via TUIA. **Prove SortIndex zero-cost:
   SortIndex leaves stay 65-wide, null mask; full SortIndex*+UnorderedLookupTree*+Stm suites green.**
4. [HARD] Boundary-stable `assembleFromLeafPages` + `ChainIndex.fromPersistedPages` in the **F3 order** (elements
   → predecessors → chains/computeHeadState → successorsByPredecessor → head marks) + restoredFrom baseline +
   zero-emission test.
5. [mech] Loader `fetchChain` PAGED branch (was step 7 — MUST precede emit so write+reload tests stay green: F4).
6. [careful] `ChainIndex.appendStorageParts` + registry-by-ref merge + SINGLE⇄PAGED transitions + move CHAIN
   dispatch in AttributeIndex (`:1120-1123`).
7. [careful] Landmine-G dropped-PAGED-key removal diff + tests.
8. Tests: paged⇄single round-trip, empty-drop reclaim, savepoint over pageSequence/dirty,
   `EntityIndexRoundTripTest` chain fetch (stale comment ~:404), `EntityIndexManifestInvariantTest` CHAIN
   (:179, :295-335), reload oracles exact-array (paged reload is MORE deterministic than monolithic), churn
   JMH re-run (same benchmark, flushToBytes seam → appendStorageParts sum).
