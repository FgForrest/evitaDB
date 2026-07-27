# Hypothesis for analysis — persisted stale leaf-page twin in a PAGED InvertedIndex (evitaDB, senesi)

*Self-contained brief for a fresh strong-model analysis. Build `2026.2.RC1-SNAPSHOT`. All line numbers are
against the working tree at the time of writing.*

## ✅✅ ROOT CAUSE CONFIRMED (JDWP, 2026-07-16) — supersedes the §4 hypothesis below

**One conceptual bug, replicated across all four B+ tree implementations: `splitInternalNode` computes the
internal-node split midpoint from `valueBlockSize` instead of `internalNodeBlockSize`.**

Found by replaying the real production WAL (T0=v60 → truncated at v333) against the clean T0 catalog with all
tiers ON. Replay does NOT reproduce the OffsetDateTime twin directly — instead it **crashes deterministically at
the 7th transaction (v67)** with `ArrayIndexOutOfBoundsException: arraycopy: length -1` in the **price super
index** Element tree. JDWP at the throw (`java.lang.ArrayIndexOutOfBoundsException` breakpoint) captured:

- Frame `AbstractIntKeyedBPlusTree.splitInternalNode:371`: `final int mid = (this.valueBlockSize + 1) / 2;`
  → with `valueBlockSize = 64`, `mid = 32`.
- The internal node being split (`TransactionalElementBPlusTree$BPlusInternalTreeNode`): `keys = int[31]`,
  `children = BPlusTreeNode[32]`, `peek = 30`. Its capacity is **`internalNodeBlockSize = 31`**, NOT
  `valueBlockSize`. (Tree fields confirmed live: `valueBlockSize=64`, `internalNodeBlockSize=31`,
  `minInternalNodeBlockSize=15`.)
- Right-half split call `createInternalNode(originKeys, originChildren, keyStart=mid=32,
  keyEnd=leftInternal.getKeys().length=31, childrenStart=32, childrenEnd=32)` → in
  `AbstractIntKeyedInternalNode.<init>:160`, `System.arraycopy(originKeys, 32, this.keys, 0, keyEnd-keyStart = 31-32 = -1)`
  → `length -1`.

**The same `mid = (valueBlockSize + 1) / 2` internal-node-split line exists in ALL FOUR trees:**
`AbstractIntKeyedBPlusTree:371` (Element/IntToLong), `TransactionalLongBPlusTree:1316`,
`TransactionalObjectBPlusTree:990`, and **`TransactionalBucketBPlusTree:2329`** — the tree that backs the
`OffsetDateTime` InvertedIndex. Leaf splits correctly use `mid = valueBlockSize / 2` (leaves DO have
`valueBlockSize` capacity); only the **internal**-node split uses the wrong size.

**Why two different symptoms from one bug:**
- Int-keyed trees (Element/Long): the right-half range `[mid .. keyLen)` goes negative when `mid > keyLen` →
  hard `arraycopy -1` crash (what v67 hits).
- Bucket tree (OffsetDateTime): its `splitInternalNode` (line 2325+) partitions slightly differently, so the
  oversized `mid` mis-partitions the internal node rather than crashing → the persisted leaf-page list / page
  boundaries end up mis-ordered → **stale leaf-page twin** on reload (the boot-blocker). *(Fable 5: confirm the
  Bucket partition code turns `mid=32` on a 31-capacity node into the observed page mis-order rather than a
  crash — read `TransactionalBucketBPlusTree.java:2325-2360`.)*

**Why tests never caught it:** an internal node only splits once it accumulates `internalNodeBlockSize` (31)
keys, i.e. the tree has ~31×64 ≈ 2000+ leaves. Unit-test trees are far smaller and never split an internal
node; production senesi price/attribute/OffsetDateTime indexes (millions of records) do. Likely a regression
from when `internalNodeBlockSize` was introduced as a separate (smaller) size from `valueBlockSize` without
updating the four `splitInternalNode` midpoints.

**Proposed fix:** in each of the four `splitInternalNode` methods, compute the midpoint from the internal-node
block size, e.g. `final int mid = (this.internalNodeBlockSize + 1) / 2;` (=16). Verify: (a) the left/right
partition ranges stay within `[0, internalNodeBlockSize]`; (b) `minInternalNodeBlockSize` underflow-merge
paths are consistent; (c) add a regression test that builds a tree deep enough to split an internal node
(≥ ~2000 keys) for each tree type — the size class the existing suites miss. This also closes the deferred
1.4 (a flush-time `assertCrossLeafOrder` remains worthwhile defense-in-depth, but the true fix is the mid).

**Empirical caveats / open items for Fable 5 to close:**
- The v67 crash is in the RECOVERY/replay path; production *reached* v364 via the LIVE path, so confirm the
  live split path hits the same `splitInternalNode` (it does — same shared method, driven by `insert`), i.e.
  the bug is not replay-specific. The Bucket-tree twin IS in persisted (live-produced) data, which already
  proves the live path corrupts.
- Whether the KeyCompressor `hreflang`=6509 runtime symptom is independent (likely) or a downstream effect.

---
*(Original hypothesis below — retained for context; §4 candidates are SUPERSEDED by the confirmed root cause.)*

## 0. One-paragraph summary

On restart, a production evitaDB catalog (`senesi`) **refuses to load** because the persisted, granularly-paged
`InvertedIndex` for a `java.time.OffsetDateTime` product attribute has two leaf pages whose key ranges overlap:
the load-time assembler finds `leaf-page sequence 9`'s last key (`2026-07-16T07:31:30Z`) does **not** sort before
`leaf-page sequence 10`'s first key (`2026-07-14T21:59:13Z`), yet the persisted root lists 9 immediately before
10. This is a **stale leaf-page twin**: one persisted page holds content from an earlier tree state than the
root's ordered-page-sequence list expects. The in-memory tree that produced it was almost certainly *sound*
(it passed the op-time / pre-commit / post-merge validators); the corruption lives in the **granular
page-persistence (flush) layer**, whose output is NOT cross-leaf-validated before it reaches disk. We have a
deterministic on-disk reproduction. We need the exact flush-layer mechanism, and the fix.

## 1. Confirmed facts

- **Exact error (reproduced locally, byte-identical to production):**
  ```
  Error while loading entity collection `Product` for catalog `senesi`:
  GenericEvitaInternalError: Corrupted persisted inverted index for type `java.time.OffsetDateTime`:
    leaf-page sequence 9 overlaps its successor leaf-page sequence 10 —
    its last key (2026-07-16T07:31:30Z) does not sort before the first key (2026-07-14T21:59:13Z) of the next
    leaf page. This is a stale leaf-page twin or other index corruption.
    at TransactionalBucketBPlusTree.assertCrossLeafBoundaries(TransactionalBucketBPlusTree.java:1542)
    at InvertedIndex.fromPersistedPages(InvertedIndex.java:532)
    at AttributeIndexLoader.loadInvertedIndex(AttributeIndexLoader.java:386)
    ... readEntityIndex ... Catalog.loadCatalog
  ```
- **Reproduction:** booting the T+X backup snapshot (catalog version 364) throws this deterministically.
- **T0 loads clean — VERIFIED** (Johnny booted the T0 snapshot on a test system; no corruption). A T0 snapshot
  is catalog version < 364, ~77 min earlier, freshly reindexed by the current engine. ⇒ the twin was
  **produced by current code during the T0→T+X window of live transactional upserts** — NOT a pre-existing
  artifact of an old build or the reindex.
- **Geometry:** seq 9 (list position *i*) holds a NEWER last key (Jul-16) than seq 10 (position *i+1*) holds as
  its first key (Jul-14, two days older). The affected attribute is an `OffsetDateTime` (a frequently-updated
  timestamp — heavy churn ⇒ many leaf splits / steals / merges).
- **The tree:** `OffsetDateTime` filterable attribute ⇒ `FilterIndex` → `InvertedIndex` whose bucket tree is a
  `TransactionalBucketBPlusTree` (comparator-keyed). Large inverted indexes persist in the **PAGED** shape: one
  storage record per leaf page (`FilterIndexLeafPagePart`) + a root listing the ordered live page sequences.
- **Separately** (likely unrelated, noted for completeness): the same window produced transient
  `KeyCompressor` "no key for id 6509 (`hreflang`)" read errors, but an offline scan proved T+X's persisted
  entity data is self-consistent (compressor caught up) — that symptom is runtime/self-healing, NOT this
  boot-blocker. Treat it as a separate issue unless a shared flush-race root cause emerges.

## 2. The persistence architecture (what a fix must respect)

Granular paging lives in `io.evitadb.index.page.PageStreamRegistry` (+ `PageEmission`) and each index's
`collectChangedPages()`.

- **Advance-only allocator:** page sequences are never reused; each stream has a monotonic `highWater`
  (`PageStreamRegistry.allocate`). A "live-page set" `{pageSequence}` records the pages currently on disk.
- **Flush emit — `PageStreamRegistry.collectChangedPages(streamId, handles, pageBuilder)`
  (PageStreamRegistry.java:324-363):** walks the tree's leaf handles **in ascending key order**
  (`tree.leafPageHandles()`), and for each leaf:
  - builds `orderedPageSequences[idx++] = handle.getPageSequence()` (a fresh/split-born leaf,
    `UNASSIGNED_PAGE_SEQUENCE`, is `allocate()`d and stamped first);
  - **re-emits the leaf's page payload iff `freshLeaf || handle.isDirty()`, then `handle.clearDirty()`**;
  - `freedPageSequences` = published-live pages absent from this commit's live set (dropped by a merge) → to be
    removed from storage;
  - `stage()`s the next live-page set; `pageListChanged = anyFreshLeaf || freedPageSequences.length > 0`.
- **Commit handshake:** flush (`collectChangedPages`) runs FIRST and stages; then the transactional
  commit-merge `InvertedIndex.createCopyWithMergedTransactionalMemory` (InvertedIndex.java:923-952) calls
  `pageStreamRegistry.publishStaged()` and carries the registry BY REFERENCE into the committed copy —
  **but only inside `if (isDirty)` where `isDirty` is the INDEX-LEVEL `this.dirty` (line 927-929); when false
  it returns `this` with NO publish (line 950).**
- **Load rebuild — `InvertedIndex.fromPersistedPages` (InvertedIndex.java:490-536):** builds one leaf per
  persisted page in `orderedPageSequences` order, stamps each with its persisted sequence, then
  `assembleFromSingleLeafTrees` → `assertCrossLeafBoundaries` verifies each leaf's last key strictly precedes
  the next leaf's first key. **This is the ONLY cross-leaf check on the paged output** (load time).

Per-leaf dirty flag (`BPlusLeafTreeNode`, TransactionalBucketBPlusTree.java): transaction-aware
(`isDirty()`:3714, `clearDirty()`:3726, field set at :3650/:3652). It IS set by every leaf-content mutation I
audited: `setPeek()` sets it (3649-3653) and steals call `donor.setPeek(...)` (3826/3877) — so **both** the
receiver (`this.dirty` at 3806/3859) and the donor (via its `setPeek`) are flagged on a steal. `mergeWithLeft`
(3902) flags the survivor and empties the donor via `setPeek(-1)`.

## 3. Why the existing validators did NOT catch it (the gap)

PR #1284 built a full **in-memory** validation net, all validating the TREE:
- **Tier A** — op-time boundary asserts on every `insert`/`upsert` (tail-fence, head-predecessor, separator
  belt, split assert).
- **Tier B** — pre-WAL `validatePreCommitDirtyLeafScopes` (kill-switch `evita.bPlusTree.preCommitValidation`,
  default on).
- **Tier C** — post-trunk-merge, at the end of `createCopyWithMergedTransactionalMemory`.
- **Phase 1 / 3.1** — load-time `assertCrossLeafBoundaries` (the one that fired) + intra-leaf order.

Item **1.4 — validate cross-leaf order at FLUSH, before pages reach disk — was deliberately DEFERRED**, on an
explicit induction: *"a tree that only ever mutated through asserted ops cannot emit an overlapping page list at
flush."* This incident is a **counterexample**: the twin was caught only at load, so it bypassed A/B/C ⇒ the
in-memory tree at commit time was (almost certainly) sound, and the corruption was introduced by the flush
emit / stage-publish machinery itself — i.e. the paging layer does NOT faithfully mirror the tree, breaking the
1.4 induction's premise.

**⚠️ This "in-memory sound ⇒ flush-layer bug" is an INFERENCE, not a fact** — it is directly falsifiable and is
being tested (§6). Replay the T0→T+X WAL with all tiers ON: if any tier (A/B/C) fires during replay, the twin
IS in-memory-detectable and the real question becomes "which path bypassed the tier in production" (kill-switch
off? warm-up / bulk-load path? compaction? an op the tier does not cover?) — and §4's flush-layer candidates
1–5 are aimed at the wrong layer. Do not sink deep effort into candidates 1–5 until this experiment has run.

## 4. Analysis — what a persisted twin requires

On disk we have: `orderedPageSequences = [.., 9, 10, ..]`, `content(9).lastKey = Jul16`,
`content(10).firstKey = Jul14`. For a *sound* in-memory tree to persist this, at the producing flush **at least
one** of these held:

- **(A) A leaf whose content changed was NOT re-emitted** — its `isDirty` was false at emit time (or the flush
  didn't run for it), so its STALE page persisted while the rest of the tree (and/or the root order) advanced.
- **(B) The root's `orderedPageSequences` was persisted stale / out of step** with the pages actually written.

The naive "(A) donor of a steal isn't flagged" is **refuted** — `setPeek` flags the donor (§2). So the mechanism
is subtler. Ranked candidates:

### Candidate 1 — index-level `this.dirty` vs per-leaf dirty desync gates the WHOLE flush off (STRONG)
`collectChangedPages` is only called when the caller sees the index dirty ("A clean (non-dirty) index must not
call this — the caller gates on `isDirty()`", InvertedIndex.java:990), and `createCopyWithMergedTransactionalMemory`
only `publishStaged()`s + merges the tree when the **index-level** `this.dirty` is true (line 929). If a commit
mutates leaf content (per-leaf dirty set) while the **index-level** `this.dirty` ends up false — or vice-versa —
the flush/publish and the tree-merge can diverge: pages staged-but-not-published, or a merged tree whose changed
leaf was never re-emitted. **Question:** can the index-level `this.dirty` and the per-leaf dirty flags ever
disagree at commit time (e.g. a mutation that touches a leaf via a path that doesn't also flip the index-level
`TransactionalBoolean`, or a savepoint/rollback that reverts one but not the other)?

### Candidate 2 — shared tree, multiple role-views, one flush handshake (STRONG)
A single `InvertedIndex`/bucket tree is co-owned by role-views (filter / sort / unique); the `FilterIndex` view
"drives its persistence decision off the shared tree it wraps instead of its own (non-committed) [dirty]"
(InvertedIndex.java:908-909). Call sites of `collectChangedPages()` include `FilterIndex` (1177), `OwnerSortIndex`
(469), `HistogramIndex` (315). **Question:** in a commit where two co-owners of the same bucket tree both reach
persistence, can the second flush see an already-cleared per-leaf dirty (cleared by the first flush's
`clearDirty`) and thus skip a leaf that the second owner still needed re-emitted — or can the two owners
stage/publish the same stream inconsistently (double stage, one publish)? Is `collectChangedPages` for a shared
tree ever run more than once per commit, or from more than one owner?

### Candidate 3 — stage/publish handshake across commits (MEDIUM)
`publishStaged()` fires only inside the `isDirty` branch of the merge (line 938). If commit *N* stages a live-set
but the merge takes the `else` (index-level not dirty) branch, the staged set is never published; the comment
claims "a stale staged map is harmlessly replaced by the next commit's stage", but a subsequent commit that
skips flush (clean gate) won't re-stage. **Question:** can `livePages` (used by `freedPageSequences` on the NEXT
flush) go stale such that a page that should be freed isn't, or a re-emit decision is taken against a wrong
baseline — leaving an orphaned/stale page in `orderedPageSequences`?

### Candidate 4 — `pageListChanged` optimization skips a needed root rewrite (MEDIUM)
`pageListChanged = anyFreshLeaf || freedPageSequences.length > 0` (PageStreamRegistry.java:359). A caller "with a
pure page-list root can skip re-emitting it" when false. A steal reorders keys but not the sequence order, so the
skip is *nominally* safe — **but** is there any restructure (a split that reuses a sequence? a
merge+immediate-split? a rebalance that empties then repopulates a leaf?) where the leaf ORDER or the
sequence↔content mapping changes while `anyFreshLeaf` and `freed` are both false, so a stale root persists?

### Candidate 5 — concurrency (warm-up writer race / concurrent flush) (KEEP LIVE)
Round-1 attributed in-memory twins to a "warm-up writer race." Production may drive some upserts through a
warm-up / bulk path (dirty cleared on the committed instance in place, TransactionalBucketBPlusTree.java:3724).
**Question:** can a flush walk (`leafPageHandles()` + per-leaf read + `clearDirty`) interleave with a concurrent
mutation on the same tree, so the emit captures a leaf's pre-mutation content but post-mutation dirty-clear
(or vice-versa)?

### Candidate 6 — a NON-transactional producer: compaction or warm-up bulk load (KEEP LIVE)
Not every persist goes through a WAL transaction. **Compaction** (the OffsetIndex rewrite that reclaims dead
records — observed in-window on a sibling collection, `parameterValue` shrank 91 KB) and **warm-up / bulk
load** re-emit or copy pages outside the transactional commit-merge path. If the twin's producer is one of
these, the WAL-transaction replay in §6 will come up **clean** — which must be read as "the producer is
non-transactional", NOT "not reproducible". (The product collection GREW rather than compacted in-window, so
compaction of the product OffsetDateTime index is lower-probability here, but the clean-replay misread is the
trap to avoid.) **Question:** does compaction / snapshot-copy or the warm-up flush path ever emit the paged
leaf list without the per-leaf dirty + stage/publish handshake the transactional path relies on?

## 5. Specific questions for the analysis

1. Trace one commit that inserts a NEW high `OffsetDateTime` key into a full leaf that triggers a split and/or a
   steal/merge with the neighbor whose boundary is Jul-14/Jul-16. At the flush emit, which leaves are
   `isDirty`, what `orderedPageSequences` is written, and which page payloads are (re)written? Find the ordering
   where seq 9 keeps a stale Jul-16 page while seq 10 gets a fresh Jul-14 page under an unchanged root.
2. Is the index-level `this.dirty` guaranteed set whenever ANY per-leaf dirty is set within the same commit?
   Point to the exact site(s) that set the index-level flag vs the per-leaf flag.
3. For a shared bucket tree with multiple role-view owners, is `collectChangedPages()` invoked exactly once per
   commit, by exactly one owner? If not, is `clearDirty` / `stage` / `publishStaged` idempotent and
   order-independent across owners?
4. Does any restructure change the leaf order or the sequence↔content mapping while `pageListChanged` stays
   false?
5. Is the deferred **1.4 flush-time `assertCrossLeafOrder`** the correct defense-in-depth (fail at the producing
   flush instead of the next load) AND a diagnostic to localize the producing commit — independent of the root
   cause?

## 6. How to get ground truth (empirical arbiter)

We hold the T+X **WAL** covering the entire T0→364 window (the exact production transactions) and the verified-
clean T0 snapshot. Three steps, cheapest first:

**(a) Forensic neighbourhood dump (cheap, discriminates candidates now).** The load crash is deterministic;
dump leaf-page sequences **8–11** (not just the 9/10 boundary): each page's first+last key AND whether their
**record-id sets overlap**. This is one JDWP breakpoint at `assertCrossLeafBoundaries:1542` (inspect the
`leaves` list) or an offline page-dump. It distinguishes: full mis-order ("9 is entirely after 10") vs a
steal-shaped boundary-only overlap vs a duplicate page (same record-ids in both) — discriminating candidates
1–5 far harder than more code-reading.

**(b) Falsification test — replay T0→T+X WAL with ALL tiers ON.** This is the decisive experiment, not merely a
localizer:
- **A tier (A/B/C) fires during replay** ⇒ the twin is IN-MEMORY-detectable ⇒ this is a **validator-coverage
  gap**, not a flush-layer bug; candidates 1–5 are mis-aimed. Investigate why production bypassed the tier
  (kill-switch, warm-up path, uncovered op).
- **No tier fires AND a post-replay reload throws** ⇒ **flush-layer thesis confirmed**; candidates 1–5 are
  well-directed.
- **No tier fires AND reload is clean** ⇒ the producer is **non-transactional** (Candidate 6: compaction /
  warm-up); pivot there. Do NOT read this as "not reproducible".

**(c) Localize + tripwire.** Implement the deferred **1.4 flush-time `assertCrossLeafOrder`** regardless: it
fires at the producing flush (no reboot-bisect) and is the likely defense-in-depth half of the fix. JDWP at the
tripwire (or the firing tier) confirms which candidate is real. Fix = 1.4 validation + the specific
paging-layer (or tier-coverage) correction the trace reveals.

## 7. Repro assets (local)

- `data_repro/TX_extract/` — bootable T+X snapshot (throws the assertion). `evita_server/run-server-repro.sh`.
- `data_repro/tx_wal_probe/senesi/` — T+X `wal_0` + `wal_1` (versions up to 364) for replay.
- `data_repro/T0_boot/` — T0 bootstrap (version correlation). `data/senesi` — pristine T0 baseline (do not mutate).
- Prior offline scanner: `evita_test/evita_performance_tests/.../spike/CompressorDesyncScanner.java`.
- Round-1 stale-twin fix record: `specifications/stale-leaf-page-twin-other-indexes/RESULTS.md`.
