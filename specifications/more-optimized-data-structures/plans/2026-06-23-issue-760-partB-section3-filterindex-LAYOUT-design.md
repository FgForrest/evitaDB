# Issue #760 Part B §3 — FilterIndex tree-native on-disk layout (design v2, post-review)

Status: **v2 reviewed — Steps 0–1 SOUND/buildable; Steps 2–4 need a v3 commit-path revision (B1/B2 below).**

## ⚠️ v2 REVIEW VERDICT (2026-06-23, second round) — converging; concrete fixes identified
Steps 0–1 verified buildable now; the commit-path (Steps 2–4) still mis-models evitaDB's copy-on-commit:
- **B1 (rewrite §3):** the prior-page-set CANNOT "carry forward on the index instance in dirtyEntityIndexes" —
  that instance is DISCARDED every commit. `popTrappedUpdates` clears `dirtyEntityIndexes`
  (`DataStoreChanges.java:113-114`); commit builds BRAND-NEW merged indexes in a NEW EntityCollection
  (`EntityCollection.java:1723-1726`); the merge ctor re-derives baselines via `captureOriginalsFromComponents()`
  (`EntityIndex.java:130,904-917`), NOT the preserve-originals ctor. **Fix:** make the page-hash baseline an
  IndexComponent-style baseline **recaptured on the freshly-merged committed instance** (the existing
  `captureOriginalsFromComponents` idiom) — or thread prior hashes through the merge ctor (compaction-reattach
  pattern). It is "recaptured at commit", not "carried forward".
- **B2 (re-scope dirty):** "hash every page at flush" is a whole-tree O(buckets) scan that re-imposes the CPU cost
  the work removes. **Fix:** drive dirty from the tree's PATH-COPY set (nodes `createCopyWithMergedTransactionalMemory`
  actually rebuilt — internal `:2086-2128`, leaf `:2921-2977`); use content-hash ONLY to suppress false dirties
  on that small set, not a whole-tree pass.
- **G5 (don't over-build):** freed-page tombstone = REUSE existing `DataStoreChanges.trapRemoveStoragePart` /
  `RemovedStoragePart` (`:211-223,371`) keyed `(streamId,pageSeq)` — NOT a new channel.
- **G2:** `FilterIndexStoragePart` @Serial already bumped (3847290165472938104) with a `_2026_1` reader → 2026.1
  IS a release boundary; adding SINGLE/PAGED without a bump is clean ONLY if no discriminator-less 2026.2-dev
  format sits on a real catalog (senesi MUST regenerate — operational fact).
- **G3:** record-type byte space is GLOBAL (entity+catalog+index registries); index bytes 20–34 used — verify new
  bytes don't collide across ALL registries, not just the index range.
- **G1/G4 nits:** migration fanning one part→many new-typed records works but is in-tree-unprecedented (budget it);
  `KeyCompressor` only requires `Comparable` (does NOT forbid String — §2.2 justification was wrong, approach
  still fine). Doc nit: tree package is `io.evitadb.index.bPlusTree`.
- **VERIFIED SOUND:** flush-before-merge timing; **tree-assembly-from-pages IS feasible** (private ctor `:606` is
  the seam, already used by createCopyWithMergedTransactionalMemory to rebuild bottom-up; nodes carry no parent
  back-ref); separator-recompute (verifyInternalNodeKeys:224); `_2026_1` reader yields the flat array; pageSeq
  allocator on the persistence service + high-water-in-root is atomic in one flush; cursor is allocation-lean.
- **Bottom line:** Steps 0–1 (assembly API + pageSeq allocator) buildable NOW; Steps 2–4 gated on the v3
  commit-path revision (recapture-on-merge baseline + path-copy-set dirty).

---

Status: DESIGN for review. **Supersedes** `2026-06-23-issue-760-partB-section3-filterindex-impl.md` (that draft
was rejected by adversarial review — see its appended verdict). Incorporates the review's blockers (B1/B2/B3,
G1/G3/G4/G7) and Johnny's direction (2026-06-23):

> "the materialization logic as is implemented now needs to be thrown out — it was originally the data layout
> before we changed the InvertedIndex and other data structures to more write-friendly memory data structures.
> Align the data layout to this new shape. Design a new storage layout as optimal as needed, providing we can
> convert old→new using a Migration procedure."

**Scope (Johnny):** FilterIndex first, structured as a reusable framework. Migration converts from the **2026.1
released** layout; unreleased 2026.2-dev catalogs are regenerated (bwc policy).

## 1. Core principle — persist the tree AS a tree

Today `FilterIndexStoragePart` stores a flat `ValueToRecordBitmap[]` **materialized** from the bucket B+ tree on
write (`InvertedIndex.java:573-577`, the `:574` "remove once granular" TODO) and the tree is **rebuilt by
replaying `addRecord`** per bucket on load (`InvertedIndex.java:411-434`, driven by
`AttributeIndexLoader.fetchFilter:271`). This flat array is a **pre-Part-A vestige**. We throw it out: the
**`TransactionalBucketBPlusTree`'s own nodes become the persisted unit**, so a transaction writes only the leaf
pages it actually changed + the affected spine path — directly killing the measured 67%/194 KB-per-tx churn.

This removes the materialize/rebuild boundary entirely (review B1): the writer stops calling
`getValueToRecordBitmap()`, the steady-state loader stops calling `addRecord` in a loop, and the migration is
the *only* place the old flat array is ever read.

## 2. On-disk layout

### 2.1 Record types
- **Root** = the existing `FilterIndexStoragePart` record (class + PK `join(entityIndexPK, getId(AttributeKey))`
  unchanged; registry bijection preserved). Its serializer gains a **shape discriminator**:
  - `SINGLE` — the whole tree fits one record (small index): store the leaf contents inline (degenerate, today's
    common case — p50 ≈ 104 B). One record, no `pageSeq` pages. This is the bulk of parts (heap gate: 93% never
    split).
  - `PAGED` — the tree spans pages: store `indexedDecimalPlaces`, the `streamId`, the **root spine** (the ordered
    child-`pageSeq` list of the top internal node, or the single root-leaf's `pageSeq`), and the RangeIndex
    pointer (§7). Internal separators are **NOT stored** — recomputed from child first-keys at load.
- **Non-root pages**, keyed `PK = join(streamId, pageSeq)`:
  - **bucket-leaf page** — one leaf node's columnar contents (the value column + per-value record bitmaps), cut by
    serialized bytes (§2.3).
  - **spine page** — packed internal nodes (ordered child-`pageSeq` lists); many internal nodes per record so
    spine records clear the 4 KB floor (§3.2 of the design doc).
- New record types registered in `IndexStoragePartConfigurer` + `OffsetIndexRecordTypeRegistry` (free bytes
  confirmed available). No bwc reader for the *new* types (they are new in 2026.2-dev).

### 2.2 Stream keying
- **`streamId`** = `KeyCompressor` id of a dedicated `LeafStreamKey(entityIndexPK, attributeKey, indexType)` —
  ONE compressor dictionary entry per sub-index (the per-page `pageSeq` rides in the long PK, not the
  compressor; verified). `LeafStreamKey` is a `Comparable` (never `String`), restart-stable
  `equals/hashCode/compareTo`, **Kryo-registered in `CatalogHeaderKryoConfigurer` appended after existing
  registrations** (the dictionary is persisted whole — order-sensitive; verified band 700-799 `index++`).

### 2.3 Page sizing & the hot bucket
- Pages cut by **serialized bytes** (target 16 KiB; 4 KiB floor for leaves; spine packed). `ObservableOutput`
  already tracks position — production-safe, no JOL.
- **Accept-oversized-leaf** (review-confirmed safe; heap gate: max bucket 43 KB, 9 parts/0.03%): a single bucket
  whose serialized bitmap exceeds the band is its own leaf page. No bitmap-splitting (deferred refinement). Its
  churn is still isolated to that one page.

## 3. Write path (commit) — grounded in the real flush timing (fixes review B2 + G7)

Confirmed timing: `TransactionTrunkFinalizer.commitCatalogChanges` runs `flush@107` **before**
`getStateCopyWithCommittedChanges@109`. Dirty parts are emitted at flush via
`DataStoreChanges.popTrappedUpdates()` over `dirtyEntityIndexes` / `trappedChanges`, operating on the
**transactional** index instance (layer still live) — NOT a committed merged copy. So:

- **Prior-page-set retention lives on the transactional index instance.** Each persisted sub-index keeps the set
  it last flushed: `pageSeq → contentHash` (a cheap hash of the serialized page bytes). This set is carried
  forward across transactions on the index instance held in `dirtyEntityIndexes`.
- **At flush**, walk the current tree's live nodes (via `cursor()` for leaves + the spine), serialize each page,
  hash it, and **diff by content** against the retained set:
  - new `pageSeq` (not in prior set) or changed `contentHash` → emit the page record; allocate a `pageSeq` for a
    brand-new page (§4).
  - `pageSeq` present last time, absent now → emit a **freed-page tombstone** (`TrappedChanges` is add-only, so
    freed pages need a dedicated `(streamId, pageSeq)` removal channel — a real engine addition, design §6).
  - unchanged `contentHash` → emit nothing.
  - then replace the retained set with the new one.
- **Why content-diff, not instance identity (G7):** commit path-copies *every* node on a mutated path to a new
  instance (`createCopyWithMergedTransactionalMemory` always returns new nodes), so instance identity would
  falsely mark unchanged-but-rebuilt spine nodes dirty (storage bloat). Content-hash equality suppresses those
  false dirties. (A steal *does* change a surviving sibling's `keyAt(0)` → its content hash changes → correctly
  emitted; the review confirmed steals are not the hazard — false dirties are.)
- This replaces the whole-array materializers at `InvertedIndex.java:574` and `RangeIndex.java:267`.

## 4. `pageSeq` lifecycle (fixes review B3 + G1)

- **Allocator**: a per-stream, advance-only, never-reused 32-bit counter held on the **persistence service**
  (outside transactional memory), allocated at flush-emit time. An aborted tx never reaches flush → burns no id
  (abort-safe). A flush that allocates then crashes before durable write burns an id harmlessly (advance-only).
- **High-water**: persisted **explicitly** in the root `PAGED` record (max allocated `pageSeq` for the stream) —
  NOT derived as `max(pageSeq)` over live pages, because a freed/tombstoned max page would let `max(live)`
  return a reused id still referenced by a retained older catalog version (review G1). Crash-safe: the root is
  written in the same flush as its pages.
- **Create-from-scratch cases enumerated (B3):** `pageSeq` applies to non-root pages only (the root IS the
  storage-part PK). So:
  - leaf split (`splitLeafNode:1117/1131`): the page being split keeps its `pageSeq` for the left half; the right
    half gets a fresh `pageSeq`.
  - internal split / new-root (`splitInternalNode:1211/1223`, new root `:1150/:1241`): the storage part flips
    `SINGLE→PAGED` (or gains a spine level); each new internal/leaf node that is a *page* gets a fresh `pageSeq`;
    no "root pageSeq" exists.
  - single-child collapse (`:1011-1017`): the promoted child keeps its `pageSeq`.
  - empty collapse (`:1018-1028`): the root reverts to `SINGLE` (an empty/small leaf inline); its former child
    pages are tombstoned.

## 5. Load path — bulk-assemble from pages (replaces addRecord replay)

- `AttributeIndexLoader.fetchFilter` reads the root. `SINGLE` → build the tree inline (small). `PAGED` → read the
  child pages **by `pageSeq` in order** (no readahead, design §10), instantiate each leaf node **directly from its
  bucket-leaf page** (columnar value + bitmaps — no `addRecord`), build internal nodes from spine pages,
  **recomputing separators from child first-keys** (the invariant the review verified sound: every
  split/steal/merge derives separators from `getLeftBoundaryKey()`, enforced by
  `TransactionalBucketBPlusTree.verifyInternalNodeKeys:235`).
- Needs a **new tree-assembly API** on `TransactionalBucketBPlusTree` — "construct from pre-built leaf + spine
  pages" — since today it exposes only `addRecord` (insertion) and `cursor()` (walk). This API also assigns each
  loaded node its persisted `persistentPageId` so the next write's content-diff has a stable identity.
- The multi-page walk **pins one catalogVersion** (time-machine consistency, design §8).

## 6. Migration 2026.1 → new layout

- A migration step reads the legacy flat-array `FilterIndexStoragePart` via the existing `_2026_1` bwc reader,
  builds the bucket tree using the **existing `addRecord` replay** (fine here — it's a one-time upgrade, not the
  steady-state path), assigns `pageSeq`s, and writes the new `SINGLE`/`PAGED` records + the stream's high-water.
  Modeled on the existing `Migration_2026_2.rekey*` part-rewriting precedent; runs on upgrade from the last
  released minor. 2026.2-dev catalogs (current senesi) are regenerated.
- This is the ONLY place the old flat-array path survives.

## 7. RangeIndex (review G4) — phased

`FilterIndexStoragePart.rangeIndex` is `@Nullable` (only `Range`-typed attributes) and is a **different tree**
(`TransactionalLongBPlusTree`, fan-out 256) with the **same** materialize boundary (`RangeIndex.java:267`).
- **Phase 1 (this design):** keep the RangeIndex **whole** — persist it as a single sub-record pointed to from
  the `PAGED` root (or inline in `SINGLE`). Bounds scope; RangeIndex is the smaller half of the 194 KB and not
  every filter has one.
- **Phase 2 (follow-up):** paginate the long-tree as a second leaf family reusing the framework (range-leaf page
  type + its own assembly). Defer until FilterIndex paging is proven.

## 8. Framework extension points (FilterIndex-first, reusable)

Generic pieces, reusable by Sort/Chain (order-statistic tree) and maps (CHAMP shard) later: the page-record
types (leaf/spine), the `LeafStreamKey`/`streamId`/`pageSeq` keying, the **content-diff dirty channel +
freed-page tombstone**, the **explicit high-water**, and the **tree-assembly-from-pages** API. Each structure
plugs in by supplying its own leaf-page serializer + assembly + "derivable upper data" rule. Documented; not
built beyond FilterIndex.

## 9. Build order (each step compiles + offline-testable; flip the on-disk format only at the end)

0. **Tree-assembly-from-pages API** + leaf/spine page serializers + `persistentPageId` field threaded through the
   ~20 node ctor/copy sites (copy-preserve; split allocates). Offline unit tests (round-trip a page, assemble a
   tree, separator recompute). *No engine wiring yet.*
1. **`pageSeq` allocator** on the persistence service + explicit high-water in the root record. Abort/crash tests.
2. **Write path**: content-diff dirty emission + retained page-set on the transactional index instance, wired
   into `popTrappedUpdates`; freed-page tombstone channel; retire the materializers. Dirty-set tests
   (steal/merge/no-op/false-dirty-suppression).
3. **Load path**: bulk-assemble from pages + root discriminator; cold-load identity + time-machine pin tests.
4. **Migration** 2026.1 → new; upgrade test from a real 2026.1 catalog.
5. **RangeIndex** Phase 1 (whole), then Phase 2 (paginate) as follow-up.

Ship Steps 0–1 with no format change; flip the format (write `PAGED` roots + pages) only after 2–4 are proven,
behind a finalize checkpoint.

## 10. Test strategy
- Unit: page round-trip; tree-assembly identity; `persistentPageId` copy-stable/split-fresh; pageSeq
  abort-safety + explicit high-water; content-diff dirty (steal, merge+tombstone, no-op, false-dirty
  suppression).
- Integration: cold-load deep-equals the in-memory tree; time-machine pinned walk; migration from a real 2026.1
  catalog yields a byte-faithful tree.
- Perf/churn: per-commit bytes on the 194 KB part (target ≥6× drop); location-map entry delta within the gate
  envelope (+~7% at 16 KB); delete-heavy mix within the O(depth) budget.
- Property/fuzz: reuse the bucket-tree generative harness (incl. the `bucket-tree-merge-overflow-aliasing-bug`
  seed) round-tripping insert/delete/steal/merge through persist+reload.

## 11. Remaining risks (carry into the build)
- Time-machine cross-version page consistency (pinned-version walk must not mix versions).
- Contiguity at load (N random page reads, no readahead) — `pageSeq`-ordered reads; maybe cluster a stream's
  pages at compaction later.
- Net storage of the mid-size tail (8–32 KB parts) — confirm the byte threshold keeps them `SINGLE`.
- pageSeq 32-bit exhaustion — renumber escape documented, not built.
- Spine packing must clear the 4 KB floor.
- The content-hash for dirty detection must be cheap + collision-safe enough (use the full serialized-byte
  equality on hash collision).
