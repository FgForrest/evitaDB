# Issue #760 Part B §3 — FilterIndex tree-native on-disk layout (design v3)

Status: **v3 — commit-path (Steps 2–4) rewritten against the real copy-on-commit model with file:line
citations; Steps 0–1 / load / migration / RangeIndex phasing / tests carried forward from v2 (verified
sound).** Supersedes `2026-06-23-issue-760-partB-section3-filterindex-LAYOUT-design.md` (v2).

This revision exists because the second review (the "⚠️ v2 REVIEW VERDICT" block in v2) found the v2 commit
model still mis-described evitaDB's flush/merge ordering and over-built two mechanisms that already exist. v3
grounds every commit-model claim in the actual code. Each of the five verdict fixes (B1, B2, G5, G2/G3, doc
nits) is applied concretely below.

Johnny's direction (2026-06-23) is unchanged:

> "the materialization logic as is implemented now needs to be thrown out — it was originally the data layout
> before we changed the InvertedIndex and other data structures to more write-friendly memory data structures.
> Align the data layout to this new shape. Design a new storage layout as optimal as needed, providing we can
> convert old→new using a Migration procedure."

**Scope (Johnny):** FilterIndex first, structured as a reusable framework. Migration converts from the **2026.1
released** layout; unreleased 2026.2-dev catalogs are regenerated (bwc policy).

---

## ⚠️ 3rd REVIEW VERDICT (2026-06-23) — GO-WITH-FIXES; one Step-2 BLOCKER

A third adversarial review re-verified every §14 claim against the live source (line numbers had shifted after the
Step 0 edit). Outcome: the commit-model grounding (A1/A3/A4/A5/A6/A7) is **accurate**, and Step 0 (tree assembly)
+ Step 1 (`PageStreamRegistry`) are **correct, residence-agnostic, and tested green** — they stand. One **BLOCKER**
was found and is now **RESOLVED by decision** (Johnny 2026-06-23 chose Option 2 — see §3.2/§3.2.1):

- **BLOCKER — §3.2 `isDirtyForFlush()` predicate is incomplete (overflow-bitmap escape).** The leaf-merge rebuilds a
  leaf on *any of three* triggers (verified at `TransactionalBucketBPlusTree.java`: leaf own layer `~:3087`;
  **a changed overflow `TransactionalBitmap`** `newOverflow != null` `~:3078` — a *separate* `TransactionalLayerProducer`;
  split/merge-promotion `~:3096`). v3's §3.2 option-1 predicate keyed only off the **leaf's own** layer + split/merge
  flag → it MISSES a leaf whose record-set changed only through an overflow bitmap that mutated **without the leaf
  acquiring its own layer**. This is reachable: `getRecordsEqualTo` (`~:752`) hands out the *live* multi-bucket
  `TransactionalBitmap`, so a caller can mutate it directly. (In today's InvertedIndex all mutations route through leaf
  `addRecord`/`removeRecord`, which DO create a leaf layer `~:2278`, so the gap is currently latent — but the predicate
  must not depend on that invariant.) **Fix (folded into §3.2 below):** the predicate must mirror **all three** merge
  triggers — including "any `overflow[i]` has a live transactional layer / commits to a new instance" — and the §12
  equivalence test MUST include an overflow-only mutation case. The more robust alternative (option 2: drive emission
  from the merge's actual instance-identity rebuilt set) is re-elevated as the recommended resolution.

- **CONCERNS applied to the Step 0/1 code already:** `PageStreamRegistry.stage`/`restore` now also assert `pageSeq >= 0`;
  `baseline()` documents its publish-staleness; `assembleFromLeaves` documents that it aliases (reuses) the supplied
  leaf instances; Step 0 tests gained a `minInternal > 1` config and the production 256/127/127/63 config.

- **Doc nits (corrected inline below):** §7 RangeIndex tree block size is **512**, not 256 (256 is the InvertedIndex
  bucket tree); the `OffsetIndexRecordTypeRegistry` is a **derived union** of the three `StoragePartRegistry`s (it
  asserts global uniqueness at startup), not a separate namespace — so 35/36 being free across the three implies free
  there too; §11 item 0 over-claimed that `isDirtyForFlush()` + the `pageSeq` node field ship in Step 0 (they do NOT —
  Step 0 delivered only `enumerateLeaves`/`assembleFromLeaves`; the predicate and pageSeq threading are Step-2 work).

---

## 0. The real commit model (the foundation v2 got wrong)

Everything in §3–§6 hangs on the exact ordering of flush vs. merge, so this section nails it down first.

A catalog commit runs `TransactionTrunkFinalizer.commitCatalogChanges`
(`evita_engine/.../core/transaction/TransactionTrunkFinalizer.java:103-120`):

```
107   this.catalogToUpdate.flush(catalogVersion, lastProcessedTransaction);                 // (A) FLUSH
109   final Catalog newCatalog = this.lastTransactionLayer.getStateCopyWithCommittedChanges(...); // (B) MERGE
```

So **(A) flush happens BEFORE (B) merge**, and the two operate on *different object graphs*:

- **(A) flush** drains dirty parts via `DataStoreChanges.popTrappedUpdates()`
  (`evita_engine/.../core/buffer/DataStoreChanges.java:108-130`). It walks `dirtyEntityIndexes`
  (`:119-121`) — the **transactional** index instances, the ones with their STM layer still live — and calls
  `index.getModifiedStorageParts(trappedChanges)` (`:120`) on each. Then it **resets**
  `this.dirtyEntityIndexes = new HashMap<>(64)` (`:113`), so those transactional instances are *forgotten*.
  `EntityIndex.getModifiedStorageParts` walks the registered `IndexComponent`s and emits their parts
  (`evita_engine/.../index/EntityIndex.java:825-852`).

- **(B) merge** builds a **brand-new committed index graph**. For each index,
  `createCopyWithMergedTransactionalMemory` allocates a fresh instance over the committed tree (e.g.
  `ReducedEntityIndex.createCopyWithMergedTransactionalMemory`
  `evita_engine/.../index/ReducedEntityIndex.java:246-262` → the 8-arg "from data" ctor `:108-131`, whose
  final step is `captureOriginalsFromComponents()` `:130`). That recapture re-derives the change-detection
  baseline by running every component once against a throwaway manifest
  (`EntityIndex.java:904-917`). The **preserve-originals ctor** (`ReducedEntityIndex.java:199-224`, which
  deliberately does NOT recapture, comment `:221-223`) is used ONLY by `createCopyForNewCatalogAttachment`
  (`:228-242`) — i.e. compaction/reattachment, not the steady-state commit.

**Consequences that drive the whole design:**

1. The transactional index instance is **discarded every commit** (`dirtyEntityIndexes` is replaced at
   `DataStoreChanges.java:113`). Therefore **no per-page baseline can "carry forward on the index instance"**
   — v2's central error. The baseline must be **recaptured on the merged instance** at (B), exactly like
   `captureOriginalsFromComponents` does for attribute/price/facet/histogram keys.
2. Page emission (the dirty diff) happens at (A) on the **transactional** instance; baseline recapture happens
   at (B) on the **merged** instance. So the prior-baseline a flush diffs against is the one **recaptured by
   the previous commit's merge** — a clean per-commit handshake, see §3.
3. `getModifiedStorageParts` is handed a `TrappedChanges` accumulator and may add ANY `StoragePart`, including
   removals — see §6 (G5).

---

## 1. Core principle — persist the tree AS a tree

Today `FilterIndex.createStoragePart` builds a `FilterIndexStoragePart` from a flat `ValueToRecordBitmap[]`
**materialized** from the bucket tree on every dirty commit (`FilterIndex.java:1103-1115`, calling
`this.invertedIndex.getValueToRecordBitmap()` at `:1107`). `getValueToRecordBitmap()` itself walks the whole
tree and rebuilds the entire bucket array (`InvertedIndex.java:572-589`, with the explicit
`//TODO JNO (#760)` at `:574-576` flagging this as the churn to remove). On load the tree is rebuilt by
replaying `addRecord` per bucket. This flat array is a **pre-Part-A vestige**.

We throw it out: the **`TransactionalBucketBPlusTree`'s own nodes become the persisted unit**, so a transaction
writes only the leaf pages it actually changed + the affected spine path — directly killing the measured
67%/194 KB-per-tx churn. The writer stops calling `getValueToRecordBitmap()`, the steady-state loader stops
replaying `addRecord`, and the migration (§5) is the *only* place the old flat array is ever read.

*(Doc-nit fix: the tree lives in `io.evitadb.index.bPlusTree`, e.g.
`evita_engine/.../index/bPlusTree/TransactionalBucketBPlusTree.java`. v2 said `.bucket`.)*

---

## 2. On-disk layout (carried from v2, sound)

### 2.1 Record types
- **Root** = the existing `FilterIndexStoragePart` record (class + PK `join(entityIndexPK, getId(AttributeKey))`
  unchanged; registry bijection preserved). Its serializer gains a **shape discriminator**:
  - `SINGLE` — the whole tree fits one record (small index): store the leaf contents inline (degenerate,
    today's common case — p50 ≈ 104 B; heap gate: 93% of trees never split). One record, no pages.
  - `PAGED` — the tree spans pages: store `indexedDecimalPlaces`, the `streamId`, the **root spine** (the
    ordered child-`pageSeq` list of the top internal node, or the single root-leaf's `pageSeq`), the explicit
    high-water `pageSeq` (§4), and the RangeIndex pointer (§7). Internal separators are **NOT stored** —
    recomputed from child first-keys at load (`verifyInternalNodeKeys` invariant, §5).
- **Non-root pages**, keyed `PK = join(streamId, pageSeq)`:
  - **bucket-leaf page** — one leaf node's columnar contents (the value column + per-value record bitmaps), cut
    by serialized bytes (§2.3).
  - **spine page** — packed internal nodes (ordered child-`pageSeq` lists); many internal nodes per record so
    spine records clear the 4 KB floor.
- **Two new record types** (leaf-page, spine-page) registered in `IndexStoragePartConfigurer` +
  `OffsetIndexRecordTypeRegistry`. Byte assignment is constrained by §2.4 (G3). No bwc reader for the *new*
  types (they are new in 2026.2-dev).

### 2.2 Stream keying
- **`streamId`** = `KeyCompressor` id of a dedicated `LeafStreamKey(entityIndexPK, attributeKey, indexType)` —
  ONE compressor dictionary entry per sub-index (the per-page `pageSeq` rides in the long PK, not the
  compressor). `LeafStreamKey` is `Comparable` (the only `KeyCompressor` requirement — see the doc-nit below),
  restart-stable `equals/hashCode/compareTo`, **Kryo-registered in `CatalogHeaderKryoConfigurer` appended after
  existing registrations** (the dictionary is persisted whole — order-sensitive; band 700-799 `index++`).
- **Doc-nit fix:** v2 §2.2 justified `LeafStreamKey` by claiming `KeyCompressor` "forbids String". It does not —
  `KeyCompressor` only requires `Comparable`, and String is a valid key. The reason for a dedicated
  `LeafStreamKey` is *semantic compactness* (one stable dictionary entry encoding the full sub-index identity,
  rather than a stringly-typed concatenation), not a type restriction. The approach stands; the justification
  is corrected.

### 2.3 Page sizing & the hot bucket
- Pages cut by **serialized bytes** (target 16 KiB; 4 KiB floor for leaves; spine packed). `ObservableOutput`
  already tracks position — production-safe, no JOL.
- **Accept-oversized-leaf** (heap gate: max bucket 43 KB, 9 parts/0.03%): a single bucket whose serialized
  bitmap exceeds the band is its own leaf page. No bitmap-splitting (deferred). Its churn is still isolated to
  that one page.

### 2.4 Record-type byte space is GLOBAL (G3) — verified
The record-type byte is a single global namespace shared across all three `StoragePartRegistry`
implementations, not a per-registry range. Verified occupied bytes:
- entity registry `EntityStoragePartRegistry.java:50-55` → bytes **1–6**;
- index registry `IndexStoragePartRegistry.java:45-59` → bytes **20–34**;
- catalog registry `CatalogStoragePartRegistry.java:47-49` → bytes **50–52**.

The two new page record types must take bytes that collide with **none** of these registries (free bands:
7–19, 35–49, 53+). Pick from **35–36** (contiguous with the index block) and add a test asserting global
uniqueness across the merged `listStorageParts()` of all three registries — not just the index range. (v2's
"free bytes confirmed available" was checked only against the index registry.)

---

## 3. Write path (commit) — recapture-on-merge baseline + path-copy-set dirty (fixes B1 + B2)

This is the section v2 got wrong. The model below matches §0's flush-before-merge ordering exactly.

### 3.1 Where the per-page baseline lives (B1)

The baseline is a `Map<Integer pageSeq, long contentHash>` per persisted sub-index stream. It must NOT live on
the transactional index instance (that instance is dropped at `DataStoreChanges.java:113`). Two options were on
the table; **we choose the persistence-service-resident map**, because it solves B1 *and* the
"don't-re-hash-on-merge" interaction in one move:

- The `pageSeq` allocator already lives on the **persistence service**, outside transactional memory (§4). Park
  the per-stream `pageSeq→contentHash` baseline **right next to the allocator**, keyed by `streamId`. The
  persistence service survives across commits and is the same object both flush (A) and merge (B) can reach.
- **Flush (A)** computes the diff against this map and, as a side effect, produces the **new**
  `pageSeq→contentHash` for exactly the pages it touched (new/changed pages it just serialized + hashed;
  unchanged pages keep their prior hash; freed pages are dropped). It stages this updated map but does **not**
  publish it until the flush is known durable.
- **Merge (B)** does NOT re-hash anything. `captureOriginalsFromComponents`
  (`EntityIndex.java:904-917`) runs the FilterIndex/InvertedIndex component once to recapture the
  *EntityIndexStoragePart-level* manifest keys as today; the **page-hash baseline is simply the map the flush
  already staged** — publish-on-commit swaps the staged map in as the live baseline for the next commit. The
  merged `InvertedIndex` instance (`InvertedIndex.createCopyWithMergedTransactionalMemory`
  `InvertedIndex.java:812-831`, which wraps the committed tree with `dirty=false`) therefore carries **no
  page-hash state of its own** — it doesn't need to, because the baseline is stream-keyed on the persistence
  service, and the merged tree's nodes already match the just-flushed pages by construction.

This is the precise resolution of the review's interaction concern: **recapture on the merged copy does NOT
re-hash every page**, because the hash set is the by-product of flush, threaded through the persistence service
(which both A and B share), not recomputed from the merged tree.

> Why not thread the hashes through the merge ctor (the compaction-reattach pattern,
> `ReducedEntityIndex.java:199-224`)? That pattern carries the *EntityIndex manifest* baseline (attr/price/facet
> sets) which is small and index-instance-scoped. The page-hash map is stream-scoped and must survive even when
> a sub-index's transactional instance is the discarded one; binding it to the persistence service's stream
> registry (where the `pageSeq` allocator and high-water already live) keeps a single source of truth and
> avoids growing every EntityIndex merge ctor with a page-hash parameter that only FilterIndex/RangeIndex use.

### 3.2 What pages to emit — drive from the tree's path-copy set, NOT a whole-tree hash scan (B2)

v2 said "hash every page at flush and diff" — an O(buckets) whole-tree pass that re-imposes the CPU cost we are
removing. The fix uses the fact that the merge **already** rebuilds only mutated nodes and tells us which ones
by **instance identity**.

`TransactionalBucketBPlusTree.createCopyWithMergedTransactionalMemory` returns a node instance per node:
- **internal node** (`TransactionalBucketBPlusTree.java:2069-2128`): returns a **new** `BPlusInternalTreeNode`
  when any child changed (`newChildren != null`, `:2099-2106`), when it had a transactional layer
  (`layer != null`, `:2107-2114`), or when it was a split/merge-created non-participating node being promoted
  (`!this.transactionalLayer`, `:2115-2125`); otherwise returns **`this`** unchanged (`:2126-2127`).
- **leaf node** (`:2900-2977`): symmetric — new `BPlusLeafTreeNode` when an overflow bitmap changed
  (`:2944-2952`), when it had a layer (`:2953-2961`), or when it was a split/merge node being promoted
  (`:2962-2973`); otherwise **`this`** (`:2974-2976`).

So **`merged != source` ⇔ the node was path-copied/rebuilt this commit**. The set of pages to (re)emit is
exactly the rebuilt-node set, which is `O(depth + #mutated-leaves)`, not `O(buckets)`.

**How the rebuilt set is surfaced to page emission.** The merge above happens at (B), but emission happens at
(A) on the transactional instance — and at (A) the tree still carries its STM layers, so we know the mutated
set *without* needing the merged graph. Two equivalent ways to capture it; we adopt the first:

1. **Capture during the flush-time walk.** At flush, the FilterIndex component walks the tree's live nodes via the
   existing `cursor()` for leaves plus the spine, with a cheap `isDirtyForFlush()` node predicate so it visits only
   the spine + dirty leaves and skips clean subtrees wholesale. **The predicate MUST mirror ALL THREE conditions the
   merge uses to rebuild a node** (3rd-review correction — the earlier two-condition form was incomplete):
   - the node has its own live transactional layer: `Transaction.getTransactionalMemoryLayerIfExists(node) != null`
     (merge internal `~:2241`, leaf `~:3087`);
   - **(leaf only) any `overflow[i]` record-set bitmap changed** — i.e. some `overflow[i]` has a live transactional
     layer / commits to a different instance (merge leaf `newOverflow != null`, `~:3078`). This is the condition the
     earlier form MISSED: a multi-bucket's `TransactionalBitmap` can mutate **without the leaf node itself acquiring a
     layer** (e.g. via the live bitmap handed out by `getRecordsEqualTo`, `~:752`), and the merge still rebuilds the
     leaf. The predicate must therefore also scan the leaf's overflow column for per-bitmap layers;
   - the node is a freshly allocated split/merge node being promoted: `!node.transactionalLayer` (merge internal
     `~:2249`, leaf `~:3096`).
   This is the path-copy set, observed pre-merge. The §12 equivalence test MUST include an **overflow-only mutation**
   case (mutate a multi-bucket's bitmap, assert `isDirtyForFlush()` flags its leaf) — that is the case the old
   predicate got wrong.
2. **DECISION (Johnny, 2026-06-23): Option 2 — drive emission from the merge's instance-identity rebuilt set.**
   `createCopyWithMergedTransactionalMemory` already returns, per node, either a NEW instance (rebuilt this commit) or
   `this` unchanged. The set `{node : merged != source}` IS the dirty page set — *definitionally* equal to the merge's
   rebuild set, with zero lock-step-drift risk (§13). Option 1's hand-maintained predicate is **rejected**. The
   mechanism for crossing the (A)flush → (B)merge boundary is §3.2.1 below.

### 3.2.1 Mechanism for Option 2 across the (A)/(B) boundary

The tension: page bytes are emitted at (A) `getModifiedStorageParts` (on the transactional instance), but the
instance-identity rebuilt set is produced at (B) `getStateCopyWithCommittedChanges`. Resolution — **make the page
emission perform the structural merge itself, once, at (A), and hand its result to (B) so the merge is not done
twice:**

- Add a tree method `collectCommittedPages(transactionalLayer, pageSink)` (working name) that walks the root via the
  SAME per-node `getStateCopyWithCommittedChanges` recursion `createCopyWithMergedTransactionalMemory` uses, and for
  every node where `committed != source` serializes the committed node into a page and reports `(pageSeq, bytes)` to
  the sink; nodes where `committed == source` are skipped (clean). This produces BOTH the committed root node (to be
  the next tree's root) AND the dirty-page set in one pass.
- At (A), FilterIndex's `getModifiedStorageParts` calls `collectCommittedPages`, emits the reported pages (+ the root
  record + freed-page tombstones for `pageSeq`s that vanished vs. the §3.1 baseline), and stages the new
  `pageSeq→contentHash` baseline.
- At (B), the merge REUSES the committed root the (A) pass already computed instead of recomputing it — either by
  caching the committed tree on the transactional layer during (A), or by having `collectCommittedPages` return the
  committed `TransactionalBucketBPlusTree` that `createCopyWithMergedTransactionalMemory` would have built. (Confirm
  in Step 2 that the existing merge plumbing can accept a precomputed committed tree; if not, the fallback is to run
  the identity walk twice — correctness-clean, ~2× the structural-merge cost on the changed path only, still
  O(depth+mutated-leaves), not O(tree).)
- **`pageSeq` identity for the diff:** each node must carry its persisted `pageSeq` so a rebuilt node reuses the same
  `pageSeq` as the page it supersedes (a split allocates a fresh one for the new sibling; a freed node's `pageSeq` is
  tombstoned). This is the deferred Step-0→Step-2 node-field threading: add a `pageSeq` field on leaf+internal nodes,
  copy-preserved by every node-copy site, assigned at load (§5) and at allocation.

This keeps the dirty set definitionally correct (it is the merge) while paying the structural-merge cost at most once
(or twice in the fallback), and never an O(tree) hash scan. The §12 test still asserts the emitted set equals the
merge's rebuilt set across steal/merge/split/no-op/overflow-only-mutation.

**Content-hash is the false-dirty filter ONLY, over the small path-copy set — never a whole-tree pass.** Within
the path-copy set, a rebuilt spine node may be byte-identical to its prior page (e.g. a child changed but the
parent's child-`pageSeq` list and recomputed separators are unchanged). For each node in the path-copy set we
serialize it, hash the bytes, and compare to the stream's prior `contentHash` for that `pageSeq` (§3.1): equal
→ skip (false dirty suppressed); differ or new `pageSeq` → emit. Because the set is `O(depth + mutated-leaves)`,
the hashing cost is bounded by the change size, not the tree size.

> A *steal* changes a surviving sibling leaf's `keyAt(0)`, so that sibling's serialized bytes (and its parent's
> recomputed separator) genuinely change → it is in the path-copy set AND its content hash differs → correctly
> emitted. The hazard the review flagged is the opposite — rebuilt-but-unchanged spine nodes — which content
> equality suppresses.

### 3.3 Emission, per page, at `getModifiedStorageParts`

`getModifiedStorageParts(TrappedChanges)` (the accumulator handed in at `DataStoreChanges.java:120`) receives,
for FilterIndex:
- **changed / new page** → `trappedChanges.addChangeToStore(<leaf-or-spine page record>)`; a brand-new page
  first gets a fresh `pageSeq` from the allocator (§4).
- **freed page** → a `RemovedStoragePart` tombstone via the existing path (§6).
- **root** → the `SINGLE`/`PAGED` `FilterIndexStoragePart` (always emitted when the index is dirty, carrying the
  updated root spine + high-water).

This replaces the whole-array materializer at `FilterIndex.java:1103-1115` / `InvertedIndex.java:572-589` and
the symmetric `RangeIndex` materializer (§7).

---

## 4. `pageSeq` lifecycle (carried from v2, sound)

- **Allocator**: a per-stream, advance-only, never-reused 32-bit counter held on the **persistence service**
  (outside transactional memory, alongside the §3.1 baseline map), allocated at flush-emit time. An aborted tx
  never reaches flush → burns no id (abort-safe). A flush that allocates then crashes before durable write
  burns an id harmlessly (advance-only).
- **High-water**: persisted **explicitly** in the root `PAGED` record (max allocated `pageSeq` for the stream),
  NOT derived as `max(pageSeq)` over live pages — a freed/tombstoned max page would let `max(live)` return a
  reused id still referenced by a retained older catalog version. Crash-safe: the root is written in the same
  flush as its pages.
- **Create-from-scratch cases:** `pageSeq` applies to non-root pages only (the root IS the storage-part PK):
  - leaf split: the split page keeps its `pageSeq` for the left half; the right half gets a fresh `pageSeq`.
  - internal split / new-root: the root flips `SINGLE→PAGED` (or gains a spine level); each new internal/leaf
    *page* gets a fresh `pageSeq`; no "root pageSeq" exists.
  - single-child collapse: the promoted child keeps its `pageSeq`.
  - empty collapse: the root reverts to `SINGLE`; its former child pages are tombstoned (§6).

---

## 5. Load path — bulk-assemble from pages (carried from v2, sound)

- `AttributeIndexLoader.fetchFilter` reads the root. `SINGLE` → build the tree inline (small). `PAGED` → read
  the child pages **by `pageSeq` in order** (no readahead), instantiate each leaf node **directly from its
  bucket-leaf page** (columnar value + bitmaps — no `addRecord`), build internal nodes from spine pages,
  **recomputing separators from child first-keys** (the invariant every split/steal/merge already preserves via
  `getLeftBoundaryKey()`, enforced by `TransactionalBucketBPlusTree.verifyInternalNodeKeys`).
- Needs a **tree-assembly-from-pages API** on `TransactionalBucketBPlusTree` ("construct from pre-built leaf +
  spine pages") since today it exposes only `addRecord` and `cursor()`. The private bottom-up ctor seam
  (the one `createCopyWithMergedTransactionalMemory` itself uses to rebuild nodes; nodes carry no parent
  back-ref) is the construction point. This API also assigns each loaded node its persisted `pageSeq` so the
  next write's path-copy diff has a stable identity.
- The multi-page walk **pins one catalogVersion** (time-machine consistency).

---

## 6. Freed pages reuse the existing removal idiom (fixes G5)

v2 framed freed-page tombstones as "a real engine addition / a dedicated `(streamId, pageSeq)` removal channel
because `TrappedChanges` is add-only". **That is wrong — the removal idiom already exists and `TrappedChanges`
is not add-only for removals.** Verified end to end:

- `DataStoreChanges.RemovedStoragePart` is a `StoragePart` record carrying `(containerType, storagePartPK)`
  (`DataStoreChanges.java:371-387`). It is the existing way to say "delete this part".
- The existing removal entry point `DataStoreChanges.trapRemoveStoragePart` (`:211-223`) stores a
  `RemovedStoragePart` into the trapped-changes map, and `popTrappedUpdates` drains those into the same
  `TrappedChanges` accumulator (`:122-127`); on read it is interpreted as a delete (`getStoragePart` returns
  null for it, `:159` / `:186`).
- **Crucially, the writer that flushes ENTITY-INDEX parts already dispatches `RemovedStoragePart` to a real
  delete.** FilterIndex parts are entity-index parts, flushed via `EntityCollection.flush`
  (`evita_engine/.../core/collection/EntityCollection.java:1877`) through `this.persistenceService` (an
  `EntityCollectionPersistenceService`, impl `DefaultEntityCollectionPersistenceService`). That writer iterates
  the `TrappedChanges` and routes `instanceof RemovedStoragePart` → `storagePartPersistenceService.removeStoragePart(...)`,
  everything else → `putStoragePart(...)`
  (`evita_store/.../catalog/DefaultEntityCollectionPersistenceService.java:554-565`).

> **⚠️ Idiom-fit gap found (flag for review).** The *catalog-side* writer
> `DefaultCatalogPersistenceService.flushTrappedUpdates` (`:2794-2819`) does **NOT** dispatch removals — it
> blindly calls `putStoragePart` on every drained part (`:2807`), with no `instanceof RemovedStoragePart`
> branch. So the `RemovedStoragePart` tombstone idiom only works for parts that flush through the
> entity-collection writer. FilterIndex/RangeIndex are entity-index parts → routed through the entity-collection
> writer → **G5 is sound for this design's scope.** But the §10 framework-reuse claim does NOT automatically
> extend to *catalog-level* indexes (`CatalogIndexStoragePart`, `GlobalUniqueIndexStoragePart`): if/when the
> framework is reused for those, the catalog writer must first grow the same `instanceof RemovedStoragePart`
> dispatch. Recorded so a later structure-reuse PR doesn't silently leak freed catalog-index pages.

Therefore a freed FilterIndex page emits, from `getModifiedStorageParts`, simply:

```java
trappedChanges.addChangeToStore(
    new RemovedStoragePart(<leaf-or-spine page record class>, join(streamId, pageSeq))
);
```

`TrappedChanges.addChangeToStore` accepts any `StoragePart` (and `RemovedStoragePart` is one), so this rides the
existing add-and-drain path; the existing `flushTrappedUpdates` loop deletes it. **No new channel, no engine
addition.** (The page record's own serializer is never invoked for a tombstone — the writer keys deletion off
`containerType` + PK only.)

The §3.1 baseline map is updated to drop the freed `pageSeq` as part of the staged new map; the freed `pageSeq`
is never reused (high-water is advance-only, §4), so a retained older catalog version still resolving that PK is
safe.

---

## 7. RangeIndex (carried from v2, sound) — phased

`FilterIndexStoragePart.rangeIndex` is `@Nullable` (only `Range`-typed attributes) and is a **different tree**
(`TransactionalLongBPlusTree`, block size 512) with the **same** materialize boundary it shares with FilterIndex.
- **Phase 1 (this design):** keep the RangeIndex **whole** — persist it as a single sub-record pointed to from
  the `PAGED` root (or inline in `SINGLE`). Bounds scope; RangeIndex is the smaller half of the 194 KB and not
  every filter has one. Its dirty detection in Phase 1 stays the existing whole-part-on-dirty emission (it is
  one record), so B2's path-copy machinery does not apply to it yet.
- **Phase 2 (follow-up):** paginate the long-tree as a second leaf family reusing the framework (range-leaf page
  type + its own assembly + the same path-copy-set dirty + content-hash false-dirty filter). Defer until
  FilterIndex paging is proven.

---

## 8. Operational backstop (fixes G2)

- `FilterIndexStoragePart` `@Serial` is **already** `3847290165472938104L`
  (`FilterIndexStoragePart.java:51`) and ships a `FilterIndexStoragePartSerializer_2026_1` bwc reader
  (`evita_store/.../index/serializer/FilterIndexStoragePartSerializer_2026_1.java`). **2026.1 is a release
  boundary.** Adding the `SINGLE`/`PAGED` discriminator to the *current* (2026.2-dev) serializer without a new
  `@Serial` bump is clean **only because no discriminator-less 2026.2-dev format may sit on a real catalog** —
  the 2026.2-dev format is unreleased and senesi (the only live 2026.2-dev catalog) **regenerates** under the
  bwc policy. This is an operational fact the build order depends on, stated here explicitly so the third review
  can confirm it: there is no released minor between 2026.1 and 2026.2-dev whose on-disk FilterIndex format
  would be left unreadable. The migration (§9) handles 2026.1 → new; 2026.2-dev is wiped and rebuilt.

---

## 9. Migration 2026.1 → new layout (carried from v2, sound)

- A migration step reads the legacy flat-array `FilterIndexStoragePart` via the existing `_2026_1` bwc reader,
  builds the bucket tree using the **existing `addRecord` replay** (fine here — one-time upgrade, not the
  steady-state path), assigns `pageSeq`s, and writes the new `SINGLE`/`PAGED` records + the stream's high-water.
  Modeled on the `Migration_2026_2.rekey*` part-rewriting precedent
  (`evita_store/.../catalog/Migration_2026_2.java`); runs on upgrade from the last released minor.
- Fanning one legacy part → many new-typed records is in-tree-unprecedented (a migration step normally rewrites
  1→1); budget the extra write-amplification + the page-record-type registration into the migration step.
- This is the ONLY place the old flat-array path survives. 2026.2-dev catalogs are regenerated (§8).

---

## 10. Framework extension points (FilterIndex-first, reusable)

Generic pieces, reusable by Sort/Chain (order-statistic tree) and maps (CHAMP shard) later: the page-record
types (leaf/spine), the `LeafStreamKey`/`streamId`/`pageSeq` keying, the **persistence-service-resident
`pageSeq→contentHash` baseline + allocator + high-water**, the **path-copy-set dirty detection + content-hash
false-dirty filter**, the **freed-page tombstone via `RemovedStoragePart`**, and the
**tree-assembly-from-pages** API. Each structure plugs in by supplying its own leaf-page serializer + assembly +
"derivable upper data" rule (separators recomputed, etc.). Documented; not built beyond FilterIndex.

**Reuse caveat (see §6 gap note):** the freed-page `RemovedStoragePart` tombstone works only for parts flushed
through the entity-collection writer (`DefaultEntityCollectionPersistenceService:557`). Catalog-level indexes
flush through `DefaultCatalogPersistenceService.flushTrappedUpdates:2807`, which currently ignores removals — so
reusing this framework for `CatalogIndexStoragePart`/`GlobalUniqueIndexStoragePart` first requires teaching the
catalog writer the same `instanceof RemovedStoragePart` dispatch.

---

## 11. Build order (each step compiles + offline-testable; flip the on-disk format only at the end)

0. **Tree-assembly-from-pages API** (`enumerateLeaves` + `assembleFromLeaves`) with offline round-trip + spine /
   separator-recompute tests. **DONE + green** (`TransactionalBucketBPlusTree`, nested `PageAssembly` tests incl.
   `minInternal > 1` and the production 256/127/127/63 config). *No engine wiring.*
   — DEFERRED out of Step 0 into Step 2 (they are write-path concerns, not assembly): the `pageSeq` field threaded
   through the node ctor/copy sites (copy-preserve; split allocates) and the `isDirtyForFlush()` node predicate
   (§3.2). Step 0 added no `pageSeq` field and no predicate.
1. **`pageSeq` allocator + `pageSeq→contentHash` baseline map + explicit high-water.** **DONE + green** as a
   standalone, residence-agnostic component (`PageStreamRegistry` in `evita_store_server`,
   `io.evitadb.store.index.page`; 13 tests: advance-only never-reused allocation, explicit high-water, freed-page
   non-reuse, stage→publish-on-commit / discard-on-abort handshake, cold-load restore, premise guards). NOT yet
   parked on / wired into the persistence service — that residence is the B1 question Step 2 settles.
2. **Write path**: path-copy-set dirty emission (§3.2) wired into `getModifiedStorageParts`/`popTrappedUpdates`;
   content-hash false-dirty filter; freed-page `RemovedStoragePart` tombstone (§6); recapture/handshake the
   baseline at commit (§3.1); retire the materializers. Dirty-set tests (steal/merge/no-op/false-dirty
   suppression/tombstone).
3. **Load path**: bulk-assemble from pages + root discriminator; cold-load identity + time-machine pin tests.
4. **Migration** 2026.1 → new; upgrade test from a real 2026.1 catalog; global record-type-byte uniqueness test
   (§2.4).
5. **RangeIndex** Phase 1 (whole), then Phase 2 (paginate) as follow-up.

Ship Steps 0–1 with no format change; flip the format (write `PAGED` roots + pages) only after 2–4 are proven,
behind a finalize checkpoint.

---

## 12. Test strategy (carried from v2)
- Unit: page round-trip; tree-assembly identity; `pageSeq` copy-stable/split-fresh; `pageSeq` abort-safety +
  explicit high-water; **path-copy-set == merge's rebuilt-node set** (assert the flush-time `isDirtyForFlush()`
  walk yields exactly the nodes `createCopyWithMergedTransactionalMemory` would rebuild, for steal / merge /
  split / no-op); content-hash false-dirty suppression; freed-page tombstone deletes on reload.
- Integration: cold-load deep-equals the in-memory tree; time-machine pinned walk; migration from a real 2026.1
  catalog yields a byte-faithful tree; global record-type byte uniqueness across all three registries.
- Perf/churn: per-commit bytes on the 194 KB part (target ≥6× drop); location-map entry delta within the gate
  envelope (+~7% at 16 KB); delete-heavy mix within the O(depth) budget; **confirm flush CPU is O(change size),
  not O(tree size)** (the explicit B2 regression guard).
- Property/fuzz: reuse the bucket-tree generative harness (incl. the `bucket-tree-merge-overflow-aliasing-bug`
  seed) round-tripping insert/delete/steal/merge through persist+reload.

---

## 13. Remaining risks (carry into the build)
- Time-machine cross-version page consistency (pinned-version walk must not mix versions).
- Contiguity at load (N random page reads, no readahead) — `pageSeq`-ordered reads; maybe cluster a stream's
  pages at compaction later.
- Net storage of the mid-size tail (8–32 KB parts) — confirm the byte threshold keeps them `SINGLE`.
- `pageSeq` 32-bit exhaustion — renumber escape documented, not built.
- Spine packing must clear the 4 KB floor.
- The content-hash for false-dirty suppression must be cheap + collision-safe enough (fall back to full
  serialized-byte equality on hash collision).
- **The `isDirtyForFlush()` predicate (§3.2) must stay in lock-step with the merge's rebuild conditions**
  (`TransactionalBucketBPlusTree.java:2099-2127` / `:2944-2976`). If the merge's rebuild logic changes, the
  predicate (and its test in §12) must change with it — a drift here under-emits pages (silent data loss) or
  over-emits (storage bloat). This coupling is the single most fragile point of the design.

---

## 14. What the next (3rd) review should check
1. **B1 — baseline residence.** Is parking the `pageSeq→contentHash` map on the persistence service (next to
   the allocator/high-water, §3.1) the right home, or should it ride the merge ctor like the compaction-reattach
   baseline (`ReducedEntityIndex.java:199-224`)? Confirm the publish-on-commit handshake (flush stages, commit
   publishes) is crash-consistent with the existing `pageSeq` high-water write.
2. **B2 — path-copy observability.** Is capturing the dirty set at flush via `isDirtyForFlush()` (option 1,
   §3.2) actually equivalent to the merge's rebuilt-node set? The merge rebuilds on three conditions
   (child-changed / had-layer / split-merge-promotion, `:2099-2127`). Verify a node with a layer that mutated
   then reverted (net no-op) is still surfaced and then correctly suppressed by the content hash — and that a
   split node that ends content-identical to a prior page is handled (it gets a fresh `pageSeq`, so it is a new
   page, not a false dirty — confirm that is intended).
3. **G5 — tombstone routing (resolved for scope; gap recorded).** Verified: FilterIndex parts flush via
   `EntityCollection.flush:1877` → `DefaultEntityCollectionPersistenceService.flushTrappedUpdates:554-565`,
   which DOES dispatch `RemovedStoragePart`. The catalog-side writer `DefaultCatalogPersistenceService:2807`
   does NOT (it `putStoragePart`s everything). Confirm the reviewer agrees this is fine for FilterIndex
   (entity-index) and that the §6/§10 gap note (catalog-level reuse needs the catalog writer patched first) is
   the right place to stop for this PR.
4. **G3 — byte assignment.** Confirm bytes 35–36 are free in ALL registries and that
   `OffsetIndexRecordTypeRegistry` (the runtime record-type registry, distinct from the Kryo
   `StoragePartRegistry`) has matching free slots.
5. **G2 — operational.** Confirm there is genuinely no released minor between 2026.1 and 2026.2-dev with a
   discriminator-less FilterIndex format on any retained catalog, and that senesi regeneration is the only live
   2026.2-dev catalog (§8).
6. **§5 assembly seam.** Confirm the private bottom-up node ctor is a safe public-ish assembly entry (no parent
   back-ref, separator recompute via `verifyInternalNodeKeys`) and that loading assigns `pageSeq` to every node
   so the first post-load commit's path-copy diff is stable.
7. **§7 RangeIndex Phase 1.** Confirm keeping RangeIndex whole (single record, existing dirty emission) does not
   reintroduce a materialize-the-world cost comparable to what we removed for FilterIndex (it should be the
   smaller half; quantify).
