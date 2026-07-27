# Family-wide audit — internal-node block-size confusion in the transactional B+ trees

*Follow-up to `HYPOTHESIS-leaf-twin-for-fable5.md` (confirmed root cause §"ROOT CAUSE CONFIRMED"). Working tree
2026-07-16, branch `dev`. Scope: every B+ tree implementation in the repo, audited for the same
`valueBlockSize` ↔ `internalNodeBlockSize` confusion, plus adjacent invariant breaks.*

## 1. Verdict in one paragraph

The confirmed `splitInternalNode` midpoint bug is **one of four sibling defects of a single confusion**
("internal nodes are sized/split by `valueBlockSize`"), replicated **identically across all four transactional
B+ trees** (`AbstractIntKeyedBPlusTree` → Element/IntToLong, `TransactionalLongBPlusTree`,
`TransactionalObjectBPlusTree`, `TransactionalBucketBPlusTree`). The two non-transactional B+ trees
(`UnorderedLookupTree`, `CumulativeWeightBPlusTree`) are **clean** — they compute split midpoints from the
node's *actual* child count (`total / 2`), which is the correct, capacity-independent approach. Steal/merge
(`consolidate`) paths are also clean — they select `min/maxBlock` per node type correctly. Crucially, the
defects **mask each other on the incremental path** (all internal nodes born by splits get `valueBlockSize`
capacity, which is self-consistent with the wrong midpoint), so *only trees whose spine was bulk-assembled from
persisted pages* (capacity `internalNodeBlockSize`) crash or corrupt when they later overflow an internal node
— which is exactly why the unit suites, and the live production process (whose indexes were built in-memory by
the reindex), never hit it, while the WAL replay from the T0 *disk* snapshot crashes at v67.

## 2. The four defects (all four trees each)

Let `V = valueBlockSize`, `I = internalNodeBlockSize`. All production configs derive `I = V/2 − 1`
(64→31, 256→127, 512→255), so the buggy midpoint `mid = (V+1)/2` is always **exactly `I + 1`**.

### D1 — split midpoint from `valueBlockSize` (the confirmed crash)
`final int mid = (this.valueBlockSize + 1) / 2;` in `splitInternalNode`:
- `AbstractIntKeyedBPlusTree.java:371` (shared by Element + IntToLong trees)
- `TransactionalLongBPlusTree.java:1316`
- `TransactionalObjectBPlusTree.java:990`
- `TransactionalBucketBPlusTree.java:2329`

On a bulk-assembled internal node (capacity `I`, arrays `I`/`I+1`), the right-half range is
`[mid .. capacity) = [I+1 .. I)` → `System.arraycopy(..., length = −1)` →
`ArrayIndexOutOfBoundsException` — a deterministic hard crash on the **first** internal-node split after any
paged/bulk load. This is the v67 replay crash, and the geometry is identical in ALL FOUR trees — including the
Bucket tree (see §4).

### D2 — new root after an *internal* split allocated at `valueBlockSize` capacity
`createInternalNode(this.valueBlockSize, …)` / `new BPlusInternalTreeNode(this.valueBlockSize, …)`:
- `AbstractIntKeyedBPlusTree.java:410-411`
- `TransactionalLongBPlusTree.java:1352-1353`
- `TransactionalObjectBPlusTree.java:1030-1031`
- `TransactionalBucketBPlusTree.java:2363-2364`

### D3 — new root after a *leaf* split allocated at `valueBlockSize` capacity
- `TransactionalElementBPlusTree.java:1001-1002`
- `TransactionalIntToLongBPlusTree.java:506-507`
- `TransactionalLongBPlusTree.java:1252-1253`
- `TransactionalObjectBPlusTree.java:928-929`
- `TransactionalBucketBPlusTree.java:2268-2269`

D2+D3 mean **every internal node born on the incremental path has capacity `V`, not `I`** (split offspring
inherit `originKeys.length`, so the oversizing propagates to the whole spine). Consequences: (a) the
configured internal fan-out (`I`, javadoc'd as "maximum number of keys in an internal node") is silently
ignored — internals hold up to `V` keys; (b) it is precisely what makes D1 invisible on incremental trees
(`mid=(V+1)/2` is the correct midpoint *for capacity `V`*); (c) mixed-capacity spines arise the moment an
assembled tree grows.

### D4 — bulk assembly packs internal nodes to FULL capacity, violating "no node is full at rest"
`buildSpine` partitions with `maxChildren = internalNodeBlockSize + 1` (= children-array capacity):
- `TransactionalElementBPlusTree.java:653` (+ `buildInternalNode:683-684`)
- `TransactionalLongBPlusTree.java:745,762-766`
- `TransactionalBucketBPlusTree.java:1853,1884-1886`

The incremental invariant is *insert-then-split-immediately-when-full*, so nodes at rest are never full — and
`adaptToLeafSplit` **asserts** `!isFull()` (`AbstractIntKeyedInternalNode.java:584-587`, Bucket equivalent).
Whenever a level's node count is an exact multiple of `I+1` (e.g. 32/128/256 pages), assembly produces
internal nodes at full capacity; the first child split under such a node trips the premise assert
(`GenericEvitaInternalError: "Internal node must not be full…"`) **even after D1 is fixed**. Same root
confusion family, different error shape: assembled-at-capacity → D4 assert; assembled at capacity−1 →
one `adaptToLeafSplit` fills it → D1 arraycopy crash; below that → a few splits absorb, then D1.

## 3. Reachability matrix

| Tree | Bulk/assemble path (capacity `I` internals) | Production owners (V/I) | Exposure today |
|---|---|---|---|
| Element (int-keyed) | `assembleFromSingleLeafTrees:309` | price super/list-currency indexes (64/31) | **CRASH proven** (replay v67, JDWP) |
| Long | `assembleFromLeaves:375` / `…SingleLeafTrees:419` | RangeIndex (512/255) | crash on first internal split after paged load |
| Bucket | `assembleFromSingleLeafTrees:1461` | InvertedIndex/FilterIndex (256/127), UniqueIndexBPlusTreeSupport (256/127) | crash on first internal split after paged load |
| IntToLong | none (incremental only; `TransactionalUnorderedIntArray` value index) | ChainIndex/SortIndex lookup | D1 latent (shared `:371` code); D2/D3 active (oversized internals) |
| Object | none (incremental only) | TrafficRecordingIndex (64/31 defaults) | D1 latent; D2/D3 active (oversized internals) |

Not affected: `UnorderedLookupTree` (spine split `total/2` of actual children, single blockSize semantics),
`CumulativeWeightBPlusTree` (same), all `consolidate`/steal/merge paths (correct per-type thresholds),
`adaptToLeafSplit` (guarded by the not-full assert — no partial-mutation hazard).

## 4. Correction to the hypothesis: the Bucket tree does NOT "mis-partition instead of crashing"

The hypothesis conjectured the Bucket tree's partition code differs and would turn the oversized `mid` into a
silent page mis-order (the senesi stale-twin). **Code reading refutes this**: `TransactionalBucketBPlusTree`'s
`splitInternalNode:2325-2385` is shape-identical to the int-keyed one (same range constructor semantics,
`keyEnd = leftInternal.getKeys().length`), and with the InvertedIndex geometry (256/127, mid=128) the
right-half arraycopy length is −1 → the **same hard crash**, not a mis-partition. A silent (degenerate but
ordered) split would require capacity == mid, which the constructor asserts away for every real config
(`I` odd, `I = V/2−1 < (V+1)/2`).

**Implication:** the persisted OffsetDateTime stale-leaf-page twin CANNOT be a direct product of D1 — a live
process whose spine was incremental (capacity 256) splits validly; one whose spine was disk-assembled
(capacity 127) crashes rather than mis-orders. The twin therefore still needs its own root cause — the
original §4 flush-layer candidates (dirty-flag desync, shared-tree double flush, stage/publish handshake,
`pageListChanged` skip, warm-up concurrency, non-transactional producer) are **re-opened for the twin**, and
the §6(b) WAL-replay arbiter remains the decisive experiment — currently blocked at v67 by D1, so D1 must be
fixed first before the twin replay can run to completion.

## 5. Regression tests (proof of existence — VERIFIED RED 2026-07-16)

Strategy: the bug family only manifests when an `I`-capacity internal node meets the incremental split path,
so every crash test **mixes `assembleFromSingleLeafTrees` (bulk spine) with subsequent `insert`s** until an
internal node overflows; fan-out tests pin D2/D3 on purely incremental growth. Tests are written TDD-style —
they assert the *correct post-fix behaviour*. **Verified: all 11 fail on current code with exactly the
predicted signatures; the other 499 tests in the five classes stay green** (surefire: 510 run / 11 failures /
0 errors).

| File (all in `evita_functional_tests`, pkg `io.evitadb.index.bPlusTree`) | Test | Defect | Observed failure |
|---|---|---|---|
| TransactionalElementBPlusTreeTest | `shouldSurviveInternalNodeSplitAfterAssembly` (8,3,3,1) | D1 | AIOOBE `arraycopy: length -1` |
| TransactionalElementBPlusTreeTest | `shouldSurviveInternalNodeSplitAtProductionBlockSizes` (64,31,31,15 — the senesi price-index geometry) | D1 | AIOOBE `arraycopy: length -1` |
| TransactionalElementBPlusTreeTest | `shouldRespectInternalNodeBlockSizeOnIncrementalGrowth` | D2/D3 | internal holds 6 keys > 3 |
| TransactionalElementBPlusTreeTest | `shouldSplitChildUnderFullyPackedAssembledInternalNode` | D4 | `GenericEvitaInternalError` "must not be full" |
| TransactionalLongBPlusTreeTest | `shouldSplitInternalNodeAfterLeafSplitPushesParentToCapacity` (8,3,3,1) | D1 | AIOOBE `arraycopy: length -1` |
| TransactionalLongBPlusTreeTest | `shouldRespectInternalNodeBlockSizeThroughIncrementalGrowth` | D2/D3 | internal holds 6 keys > 3 |
| TransactionalBucketBPlusTreeTest | `shouldSplitAssembledParentInternalNodeWhenChildLeafOverflows` (8,3,3,1) | D1 | AIOOBE `arraycopy: length -1` |
| TransactionalBucketBPlusTreeTest | `shouldKeepInternalNodeKeyCountWithinBlockSizeDuringIncrementalGrowth` | D2/D3 | internal holds 6 keys > 3 |
| TransactionalBucketBPlusTreeTest | `shouldSplitChildLeafUnderAnAssembledInternalNode` | D4 | `GenericEvitaInternalError` "must not be full" |
| TransactionalObjectBPlusTreeTest | `shouldRespectInternalNodeBlockSizeDuringIncrementalGrowth` | D2/D3 | internal holds 6 keys > 3 |
| TransactionalIntToLongBPlusTreeTest | `shouldRespectInternalNodeBlockSizeDuringIncrementalGrowth` | D2/D3 | internal holds 6 keys > 3 |

The Bucket-tree D1 test is also the **empirical settlement of §4**: the Bucket tree crashes with the same
AIOOBE — it does NOT silently mis-partition — so the persisted stale-twin definitively has a different
producer than the split-midpoint bug.

## 6. Fix outline (✅ APPLIED 2026-07-16 — 510/0/0 green)

**Status: implemented and green.** The robust fix below was applied to all four transactional trees + the shared
`AbstractIntKeyedBPlusTree`. All 11 RED regression tests now pass and the full 5-class suite is
**510 testcases / 0 failures / 0 errors** (Element 38, IntToLong 113, Long 131, Object 120, Bucket 108). One
test-construction bug was fixed alongside: `shouldSurviveInternalNodeSplitAtProductionBlockSizes` used
`elementsPerLeaf = 5` with `minValueBlockSize = 31`, making its assembled (non-root) source pages structurally
under-occupied — the pre-fix AIOOBE crash had masked it; raised to `elementsPerLeaf = minValueBlockSize`.

What was applied (matching the outline that follows):
- **D1** — `splitInternalNode` midpoint derived from actual occupancy: `keyCount = internal.keyCount(); mid = (keyCount + 1) / 2`, with the right node's end bounds taken from occupancy (`keyCount` / `keyCount + 1`) instead of the left node's array capacity. Robust for any node capacity.
- **D2 / D3** — every new internal root (after an internal split and after a leaf split) allocated at `internalNodeBlockSize`, not `valueBlockSize`.
- **D4** — `buildSpine` packs `maxChildren = internalNodeBlockSize` (one free slot below the `internalNodeBlockSize + 1` children capacity), so an assembled node is never born full and the first child split can still `adaptToLeafSplit`.
- Bucket-tree confirmed by reading: its `splitInternalNode` moves keys + child pointers only; the columnar bucket store lives in the leaves and is untouched by an internal-node split. Its leaf split (payload-column partition) was not modified.

**Fan-out perf re-check — ✅ DONE 2026-07-16: KEEP the tuned block sizes (InvertedIndex/FilterIndex 256,
RangeIndex 512); the fix is perf-neutral by construction.** D2/D3 made the incremental-path internal
fan-out honor `internalNodeBlockSize` (= V/2−1) instead of the bug's effective `valueBlockSize`, so
internals are correctly narrower. A structural height probe on the fixed engine (reproducing the pre-fix
geometry by passing `internalNodeBlockSize = V−1`, the largest legal odd value) shows **root→leaf descent
depth is identical pre↔post at every scale except one narrow cardinality band per tree** — leaf-count ∈
`(I+1, V]`, i.e. between the corrected and buggy single-root capacities — where a descent gains exactly
**one cache-resident node-hop**:
- InvertedIndex(256): band ≈ N 16k–33k distinct values (measured N=24k: 187 leaves, depth 1→2).
- RangeIndex(512): band ≈ N 66k–131k distinct thresholds (measured N=100k: 390 leaves, depth 1→2).

Why keep the sizes: the tuning's decision variables are all invariant to this fix. `rangeScan`/`fullScan`
(which set the knee) are **leaf-locality** driven and leaf size is untouched → byte-identical; `pointLookup`
was measured **flat across block-size sweeps that changed depth by whole levels** (see the InvertedIndex
README) → a ≤1-level change in one band is a strict subset of variation the tuning already proved
insensitive to. The post-fix depth is the *correct* shape (the pre-fix single-root was the bug's accidental
widening). No JMH re-run needed — a measurement would re-confirm the README's noise floor at real cost.

**Documented future micro-option (do NOT pull now):** setting the index config
`internalNodeBlockSize = V−1` (255 / 511, odd) would run wide internals on the *correct* code path and
erase even the one-band hop — but it is churn on a correctness-critical fix for a negligible gain and
needlessly re-widens internals toward the exact geometry the corruption lived in. Revisit only if RangeIndex
descent ever surfaces in a profile.

**✅ Production WAL replay confirmed clean (2026-07-16, post-fix — the decisive experiment ran).** A
contamination-free `data_repro/replay_clean` was re-staged (pristine T0 from `data_repro/T0_full` + the
untruncated production `senesi_0.wal`, md5 `97d099d7`, versions → 333; the original `data_repro/replay` had
been polluted by a prior pre-fix boot that flushed `product`/`media` at 14:20) and booted on the rebuilt
server (`evita_server/run-server-replay-clean.sh`, D1–D4 engine shaded in). Outcome:

- Catalog loaded, **273 transactions v60 → v333 replayed in 35 s, `lastFinalized = 333`**.
- REST catalog listing: `senesi` → **`catalogState: ALIVE`, `version: 333`, `unusable: false`**, all 18
  entity types; REST + GraphQL initial loading complete; server serving on `:5555`.
- Full 43 k-line boot log: **zero ERROR, zero AIOOBE, zero corruption / poison / stale-leaf**. The only
  Exception is the benign boot-time 24-byte WAL tail-trim.

**The v67 Element price-tree AIOOBE is gone** on the real incident WAL — D1–D4 resolves the boot-blocker.
The OffsetDateTime stale-leaf-page **twin did not surface** on this incident WAL either, so for THIS
production incident both symptoms are resolved. The twin's *producer* (§4) was never separately
root-caused, so this run does not prove it universally impossible — but it is quiet on the incident data.

**✅ Cold-reload arbiter also clean (the §6(b) restart test ran).** The server was gracefully shut down —
flushing the replayed state to disk (the catalog bootstrap grew 57 → 114 B: a second checkpoint record at
`catalogVersion = 333`; `senesi_0.catalog` rewritten) — then **restarted on the same modified
`replay_clean`**. The restart read `catalogVersion = 333` from the bootstrap and cold-loaded the flushed
leaf pages directly (no WAL to replay), re-running load-time cross-leaf assembly + the DirtyScopeValidator
against exactly the pages the replay wrote:

- Catalog loaded in 1m; REST listing `senesi` → **`catalogState: ALIVE`, `version: 333`, `unusable: false`**.
- Reload log: **zero error, zero corruption** — not even a WAL tail-trim (the WAL was already
  complete-transaction-aligned).

So the replay's trunk-incorporation flush does **not** mint a stale-leaf twin — the leaves stay undamaged on
cold reload. Per the §6(b) decision table, *replay clean AND reload clean ⇒ the persisted production twin
came from a **non-transactional producer** (WARM_UP / compaction)* — which matches the earlier
checksum-proven WARM_UP-writer finding and keeps the twin's own root cause a separate, still-open track
from this (now-resolved) boot-blocker.

---

### Original fix outline (retained for reference)

The hypothesis' proposed fix (`mid = (internalNodeBlockSize + 1) / 2`) is **incomplete on its own**: with
D2/D3 unfixed, incremental spines have capacity-`V` internals, and a fixed-constant mid would mis-balance
those (and still crash nothing, but produce `minInternalNodeBlockSize` underflows). The robust fix is the
pattern the two clean trees already use — **derive the split point from the node's actual occupancy**
(`mid = (keyCount + 1) / 2` at the moment of split), AND fix D2/D3 to allocate new internal roots at
`internalNodeBlockSize`, AND fix D4 to pack assembly at most `internalNodeBlockSize` children per parent
(`maxChildren = I`, keeping one free slot), so assembled and incremental spines share one invariant set.
All four trees + the shared abstract must change together; the D1 regression tests above then double as the
fix's acceptance tests.
