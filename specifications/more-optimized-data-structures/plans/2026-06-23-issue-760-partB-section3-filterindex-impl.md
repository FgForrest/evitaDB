# Issue #760 Part B §3 — FilterIndex page-tree: focused implementation plan

Status: **NOT buildable as written — superseded pending revision.** Adversarial review (2026-06-23) found a
load-bearing architectural blocker this draft omits. This draft turns the design doc
(`2026-06-22-issue-760-partB-granular-storage-parts.md`, §3/§4/§5/§6) into a concrete, ordered build scoped to
**FilterIndex only** (its `InvertedIndex` bucket tree + the attached `RangeIndex`), but see the review verdict
at the bottom before acting on any step.

## ⚠️ CRITICAL REVIEW VERDICT (2026-06-23) — NOT buildable as written
A skeptical code-verified review found:
- **B1 (blocker, omitted entirely):** the persisted unit is NOT the tree — `FilterIndexStoragePart.histogramPoints`
  is a flat `ValueToRecordBitmap[]` (`FilterIndexStoragePart.java:69`) **materialized from the tree at write**
  (`FilterIndex.java:1107`→`InvertedIndex.getValueToRecordBitmap()` ~573) and the tree is **rebuilt node-by-node
  by replaying `addRecord` in value order at load** (`AttributeIndexLoader.java:271-277`→`InvertedIndex` ctor
  411-434). So on-disk node structure (and any `persistentPageId`) has NO relationship to the in-memory tree
  after a load — it is destroyed and re-synthesized every reload. The `InvertedIndex.java:574` TODO ("materializes
  the whole bucket array … remove once StorageParts become granular") IS this boundary. **Replacing this
  materialize/rebuild boundary is the dominant cost and is a NEW Step 0** — the de-risk's "#5 plumbing = MEDIUM,
  ~20 sites in one file" badly under-scoped the feature; threading `persistentPageId` through tree nodes is
  inert until the boundary is replaced.
- **B2 (blocker):** dirty-detection timing is inverted. Flush runs BEFORE merge on the transactional object with
  its layer still live (`TransactionTrunkFinalizer:107` flush vs `:109` merge); no prior root is retained
  (`FilterIndex` holds only invertedIndex+rangeIndex; `OwnerFilterIndex` only a `dirty` boolean). The §6
  "root-diff over committed state at flush" model does not match reality — must retain prior page set on the
  transactional instance and diff the in-transaction tree.
- **B3 (blocker):** "left-half-inherits" is undefined for the create-from-scratch cases (new-root 1150,
  single-child collapse 1011, empty collapse 1023 build brand-new nodes); pageSeq for these must be enumerated.
- **Gaps:** G1 high-water `max(pageSeq)` unsafe under tombstones + multi-version retention; G3 the discriminator
  byte is NOT byte-identical → reconcile with the no-bwc-within-dev policy (within-dev format change, regenerate,
  no reader); G4 RangeIndex is a different tree (`TransactionalLongBPlusTree`), nullable, same materialize
  boundary → a real second sub-task, not a freebie; G7 the steal dirty rationale is INVERTED (path-copy makes
  everything a new instance, so the hazard is FALSE dirties on unchanged subtrees, not MISSED steals).
- **Verified SOUND:** separator-from-first-key invariant (derived from `getLeftBoundaryKey()` everywhere,
  enforced by `verifyInternalNodeKeys:235`); registry bijection has free bytes; KeyCompressor forbids String +
  ordered-append safe; delete cascade is O(depth); heap gate (#1) stands.

**Top-3 to fix before coding:** (1) add Step 0 = replace the materialize/rebuild boundary so tree pages are the
persisted unit; (2) re-anchor dirty detection to the real flush-before-merge timing with prior-page-set retention
on the transactional instance; (3) enumerate pageSeq for all create-from-scratch cases + fix high-water under
tombstones. The full review is in the conversation transcript.

---


## 0. Goal & success criteria

**Goal:** persist the `InvertedIndex` bucket B+ tree (and `RangeIndex`) behind `FilterIndexStoragePart` as a
copy-on-write **page tree**, so a transaction rewrites only the changed leaf page(s) + the affected spine path,
not the whole part.

**Why (measured):** `FilterIndexStoragePart` = 67% of update churn; one ~194 KB part rewritten whole per tx
(`InvertedIndex.java:574` / `RangeIndex.java:267` materialize the entire array every commit). FilterIndex has
NO §9 slim shortcut (its bulk is already-compact RoaringBitmaps), so granularity is the only lever.

**Success criteria:**
1. A single-value mutation on a >16 KB filter index persists ~1 leaf page (8–32 KB) + its spine path, NOT the
   whole part. Verified by a per-commit-bytes-written probe (≥6× reduction on the measured 194 KB part).
2. Round-trip identity: any tree state writes→reads back bit-identically across split/merge/steal/oversized.
3. Cold-load reconstructs the identical tree (walk pages in `pageSeq` order, rebuild internal separators).
4. Backward compatible: existing single-record `FilterIndexStoragePart` parts still load (lazy upgrade).
5. Location-map heap delta within the measured envelope (≤~1.15× entries at 1×; see gate).
6. Abort/crash safe: an aborted tx allocates no permanent `pageSeq`; a crash mid-flush is recoverable.
7. No regression in `FilterIndexStoragePartSerializerTest` + filtering/indexing suites.

**Out of scope:** SortIndex/Chain (order-statistic plugin, later), maps (CHAMP shard), Histogram (rides
FilterIndex later), the shared RangeIndex scheme reuse (Phase 3). This plan paginates RangeIndex inline as a
second leaf family but does not yet factor out the shared scheme.

## De-risk results (all gates cleared — see memory `partb-granular-storageparts-analysis`)

- **#1 heap/location-map (PASS):** real senesi, 16 KB band → only 7% of parts split, +3,238 entries (1.109×,
  ~0.1 MiB est). 100× linear → +324k entries (~10 MiB, retention=1). Risk #3 hot-oversized-bucket is a
  non-issue: max bucket 43,246 B, only 9 parts (0.03%) exceed 16 KB → **accept-oversized-leaf** chosen.
- **#5 plumbing (MEDIUM, bounded):** `TransactionalBucketBPlusTree.java`, ~20 node construction/copy sites in
  one file. Nodes already carry a per-instance `id`; we add a **copy-stable** `persistentPageId`.
- **#4 delete-path (acceptable):** steal = 3 pages, merge cascades O(depth) ≈ ≤4 levels; all ≪ whole-part
  rewrite. Separators already derived from `getLeftBoundaryKey()` → supports the derivable-separator invariant.
- Correctness hazard (known): per-instance `id` ⇒ instance-identity dirty detection misses steals ⇒ MUST use
  content/root-diff dirty (§6).

## 1. Data model & record types

- **Root** keeps the existing `FilterIndexStoragePart` class + PK (`join(entityIndexPK, getId(AttributeKey))`).
  Its serializer gains a leading **shape discriminator byte**: `LEAF` (degenerate small case — today's whole
  payload, one record) or `INTERNAL` (an ordered child-`pageSeq` list + the scale + range-root pointer). The
  registry (`OffsetIndexRecordTypeRegistry`) is a strict class↔byte bijection, so the root byte stays pinned to
  `FilterIndexStoragePart`.
- **Non-root pages** = new record types addressed by `PK = join(streamId, pageSeq)`:
  - one shared **internal-spine** record type (family-agnostic ordered child-`pageSeq` list, **packed** — many
    internal nodes per record, §3.2, to clear the 4 KB floor);
  - one **bucket-leaf** record type (the `ValueToRecordBitmap` run for a leaf page);
  - one **range-leaf** record type (the `RangeIndex` long-tree leaf).
- **`streamId`** = compressed int via `KeyCompressor` for a new dedicated key record
  `LeafStreamKey(entityIndexPK, attributeKey, indexType)` — ONE dictionary entry per sub-index (not per page).
  Constraints: dedicated `Comparable` (never a `String` — compressor asserts), restart-stable
  `equals/hashCode/compareTo`, **Kryo-registered in `CatalogHeaderKryoConfigurer` with a fixed UID appended
  after existing registrations** (the compressor dictionary is persisted whole in the header — order matters).
- Fan-out (existing): FilterIndex internal = 128, RangeIndex = 256. Pages are cut by **serialized bytes**
  (target 16 KB, floor 4 KB for leaves; spine packed), not by block size.

## 2. Build order (each step compiles + has its own tests; ship behind the lazy reader so trees stay readable)

### Step A — `persistentPageId` plumbing (in `TransactionalBucketBPlusTree.java`)
Add an `int persistentPageId` to `BPlusInternalTreeNode` and `BPlusLeafTreeNode` (parallel to the existing
`long id`). It is a **ctor parameter**, not auto-generated. Semantics:
- **createLayer** (leaf 2879 / internal 2053) and **createCopyWithMergedTransactionalMemory** (leaf
  2945/2954/2966 / internal 2100/2108/2119): **preserve** the source `persistentPageId` (same logical page).
- **splits** (`splitLeafNode` 1117/1131, `splitInternalNode` 1211/1223, new-root 1150/1241, root init 601,
  root-collapse 1023): **left half inherits** the parent id; the new right half gets a **freshly allocated**
  `pageSeq` (Step B). New root gets a fresh id; the old root keeps its id as a child.
- The 6 ctors (leaf 2261/2289/2328, internal 1499/1534/1554) thread the param through.
- **Tests:** a property test asserting (a) copy/commit preserves `persistentPageId`, (b) split allocates exactly
  one new id and the left keeps the old, (c) ids are unique among live nodes, (d) ids never reused after a merge.

### Step B — `pageSeq` allocator (abort-safe, advance-only, never-reused)
- A per-stream counter held OUTSIDE transactional memory (so an aborted tx leaves no permanent allocation),
  allocated at commit-copy time (§4). Persist the high-water by DERIVING it at load = `max(pageSeq)` seen
  across the stream's pages (crash-safe, no ordered write). 32-bit; document the renumber escape on exhaustion.
- **Tests:** abort a tx after a split → no permanent id burned; reopen → high-water = max persisted; forced
  wraparound guard throws.

### Step C — per-node serializers + the root discriminator
- New serializers: `BucketLeafPageSerializer` (the `ValueToRecordBitmap` run, byte-cut), `RangeLeafPageSerializer`,
  `SpinePageSerializer` (packed child-`pageSeq` list + separators-derivable note). Register the new record types
  in `IndexStoragePartConfigurer` + `OffsetIndexRecordTypeRegistry`.
- Extend `FilterIndexStoragePartSerializer`: write the discriminator byte; `LEAF` shape == today's payload
  (so small indexes are byte-identical to the current format — criterion 4); `INTERNAL` shape writes
  scale + range-root pointer + the child `pageSeq` list.
- **Accept-oversized-leaf** (risk #3 decision): a single bucket whose serialized bitmap exceeds the band is its
  own leaf page; do NOT split a bitmap across pages (deferred refinement).
- **Tests:** round-trip each page type; round-trip a whole INTERNAL-root tree; assert a small index still emits
  the LEAF shape byte-identically to the pre-change serializer (golden bytes).

### Step D — content/root-diff dirty enumeration + freed-page channel (§6)
- Dirty pages = the set of nodes whose persisted form differs from the prior committed root's reachable set
  (reference-diff of the persistent structure from the new root vs prior root — NOT instance identity, which
  misses steals). Emit (write) the changed/new pages; emit a **tombstone** for freed `pageSeq` (merge abandons a
  page) via a new add-only channel keyed `(streamId, pageSeq)` — `TrappedChanges` is add-only so freed pages need
  this separate channel.
- **Tests:** steal changes a surviving sibling's `keyAt(0)` → that sibling IS in the dirty set (the regression
  the per-instance id would miss); merge → the abandoned page gets a tombstone; an unchanged subtree emits no
  pages; a no-op tx emits nothing.

### Step E — loader recursion + version pinning (§5)
- The loader (`AttributeIndexLoader.fetchFilter`) reads the root; on `INTERNAL` it recurses child `pageSeq`
  pages and rebuilds the tree, **recomputing internal separators from child first-keys** (the invariant Step C
  preserves; supported by `mergeWithLeft@1789` using `getLeftBoundaryKey`). The multi-page walk PINS one
  catalogVersion (time-machine consistency, §8). Issue reads in `pageSeq` order (no readahead, §10).
- **Tests:** cold-load reconstructs a tree identical to the in-memory original (deep equals over buckets);
  time-machine read at an old version walks only that version's pages.

### Step F — wire the InvertedIndex/RangeIndex commit path; retire the materializers
- Replace the whole-array materialization at `InvertedIndex.java:574` and `RangeIndex.java:267` with the
  dirty-page emission. Keep `createStoragePart` producing the root (LEAF when small, INTERNAL when grown).
- **Tests:** the per-commit-bytes probe (criterion 1) on the 194 KB part; full `FilterIndexStoragePartSerializerTest`
  + filtering/indexing suites green; a churn micro-bench (update one value → bytes written) shows ≥6× drop.

## 3. Backward compatibility & rollback
- Lazy: an existing single-record part loads via the LEAF shape (or the `_2025_5`/`_2026_1` bwc readers
  unchanged); it is only rewritten as a page tree when it next grows past the band and is committed. No forced
  migration. The discriminator byte is the only new on-disk element for small parts.
- Rollback: the change is additive (new record types + a discriminator). Reverting before any catalog has
  written INTERNAL roots is clean; once written, a downgrade needs the old engine to read INTERNAL — so gate the
  on-disk format flip behind a finalize checkpoint after Steps A–E are proven.

## 4. Test strategy summary
- Unit: per-node round-trip, discriminator round-trip, pageId copy-stability/split-allocation, pageSeq
  abort-safety + high-water derivation, dirty-set content-diff (steal/merge/no-op), loader reconstruction.
- Integration: cold-load identity, time-machine pinned walk, lazy-upgrade of a legacy single-record part.
- Perf/churn: per-commit bytes on the 194 KB part (≥6× drop), location-map entry delta within the gate envelope,
  delete-heavy mix amplification within the O(depth) budget.
- Property/fuzz: generative insert/delete/steal/merge sequences (reuse the existing bucket-tree generative
  harness — incl. the seed that found `bucket-tree-merge-overflow-aliasing-bug`) round-tripping through persist.

## 5. Remaining risks not fully retired by the de-risk (carry into the build)
- **Time-machine cross-version page consistency** (§8) — the pinned-version walk must never mix versions.
- **Contiguity at load** (§10) — N random page reads, no readahead; mitigate by `pageSeq`-ordered reads, maybe
  later teach compaction to cluster a stream's pages.
- **Net storage of the mid-size tail** (8–32 KB parts that become root + a couple pages) — confirm the byte
  threshold keeps them effectively single-record (criterion 4).
- **pageSeq 32-bit exhaustion** — renumber escape documented, not built.
- **Spine packing** must clear the 4 KB floor (criterion: spine records are packed, not 1:1).

## 6. Sequencing note
Steps A–C are tree-local + serializer (low blast radius, fully unit-testable offline). Steps D–F touch the
engine commit/loader path (higher blast radius) and are where correctness review must concentrate. Ship A–C
behind the unchanged readers first; do not flip the on-disk format (write INTERNAL roots) until D–F are proven
and a finalize checkpoint is approved.
