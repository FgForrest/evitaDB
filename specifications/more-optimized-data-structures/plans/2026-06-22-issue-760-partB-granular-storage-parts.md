# Issue #760 Part B — Granular Storage Parts: Design (v2, adversarially reviewed)

Status: DESIGN, **v2** — corrected and re-scoped after a 6-way adversarial review against the
current code (review run `wf_1cb93f42-b64`, 2026-06-22). Author dialogue: Johnny + Claude.

**Outcome of review (read this first):**
- **GO now — §9 "no-new-shape" wins.** Independent, low-risk, single-serializer / sub-structure
  changes on existing PKs with the established backward-compat pattern. They capture the bulk of
  the win for the bimodal tail and de-risk everything else.
- **NO-GO (until amended + re-measured) — the §3 page-tree.** v1 rested on five mechanisms the
  code does **not** support as written (below). v2 fixes them on paper, narrows §3 to the **three
  genuine B+ tree families only**, and gates building it on re-measuring the residual tail after
  §9 ships. The strategic spine ("one shape that degenerates", bimodal calibration, lazy
  backward-compat) survived review; the §3 *mechanics* did not and are rewritten here.

**The five v1 blockers (all confirmed against code), and how v2 resolves them:**
1. **"Non-B+-tree" targets, re-examined (v3).** The review flagged PriceSuperIndex, SortIndex and
   ChainIndex as "not B+ trees." On closer inspection **SortIndex *is* tree-backed** —
   `sortedRecords` → `TransactionalUnorderedIntArray` → an order-statistic B+ tree
   (`UnorderedLookupTree`) + a primitive B+ tree value index
   (`TransactionalUnorderedIntArray.java:82,86`) — so a large sort index pages like Filter.
   **ChampMap-backed maps** (PriceSuper's `entityPrices`) are path-copying tries that *could* be
   node-paged but are handled here by **Tier-1 sharding** (Tier 2 = node-paging the trie, **out of
   scope**). Only **ChainIndex** (arrays + two maps) is a genuine §9 case (slim format). → §3 is
   reframed (§3.0) as a *persisted persistent-structure* framework, not "B+ trees only."
2. **Split has no "surviving original".** `splitLeafNode` allocates two fresh nodes and destroys
   the original (`splitLeafNode` builds `new` left+right then `leaf.removeLayer()`). → v2 §4 uses
   an explicit **left-half-inherits** rule, threaded through constructors.
3. **Dirty collector hooked at the wrong stage.** The transactional layer is removed at commit
   (`EntityCollection.createCopyWithMergedTransactionalMemory` removes layer **before** the index
   merge) while `getModifiedStorageParts` runs later at flush on a different object
   (`DataStoreChanges.popTrappedUpdates`). → v2 makes **root-vs-prior-root diff the primary**
   dirty mechanism (runs at flush over committed state).
4. **No freed-page channel.** `TrappedChanges` is add-only — split/merge-abandoned pages can't be
   freed through it. → v2 §6 calls out a required engine change (a tombstone channel) and models
   freed pages as `(pageSeq, class)`.
5. **Root can't be two record types.** The type registry is a strict class↔byte bijection and the
   loader fetches the root hard-typed; the root byte is pinned. → v2 keeps the root as its
   existing class with an **internal-vs-leaf discriminator inside its own serializer**; only
   non-root nodes get new types.

---

## 1. Problem restated

Append-only file offset index: `(recordType byte, primaryKey long) -> FileLocation`
(`RecordKey.java:40`, `FileLocation.java:40`). Re-writing a key appends and orphans the old
bytes; dead bytes accumulate until compaction rewrites the **whole file** (active share < 0.5
**and** file > 100 MB; `StorageOptions` `DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE=0.5`,
`DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD=104_857_600`). Fixed per-record overhead is
**21 B** (`StorageRecord.OVERHEAD_SIZE = 13 + 8`; the class javadoc "22B" is a typo to fix).
Payloads are DEFLATE-compressed (`storage.compress=true`, verified on senesi; production must
keep it on — `DEFAULT_COMPRESS=false`).

Dirty is tracked **per whole sub-index** today (one `TransactionalBoolean`), so a one-attribute
change rewrites the entire index part. The in-memory trees already path-copy only the touched
leaf + ancestors (`createCopyWithMergedTransactionalMemory`), sharing the rest by reference —
Part B makes the on-disk shape mirror that (issue rule #2).

---

## 2. Real-data calibration (live senesi, current format, compressed)

Measured via JDWP by walking each collection's live `OffsetIndex.getEntries()` (record headers
only; no payload deserialisation) and grouping `recordLength` by `recordType -> class`. senesi is
our largest dataset (catalogVersion 232).

| StoragePart | count | totalMB | p50 | p99 | max | >32 KB | >256 KB |
|---|--:|--:|--:|--:|--:|--:|--:|
| FilterIndex | 29,668 | 20.2 | 104 B | 2.9 KB | **1.5 MB** | **52** | 12 |
| SortIndex | 18,038 | 8.5 | 78 B | 1.5 KB | **1.1 MB** | 24 | 6 |
| ReferenceTypeCardinality | 33 | 1.8 | 3 KB | 1.2 MB | **1.2 MB** | 8 | 1 |
| EntityIndex | 41,057 | 9.0 | 189 B | 760 B | 127 KB | 3 | 0 |
| PriceSuperIndex | 254 | 1.2 | 1.5 KB | 27 KB | 27 KB | 0 | 0 |
| ChainIndex | 28,105 | 3.6 | 64 B | 128 B | 643 KB | 8 | 2 |
| UniqueIndex | 6,549 | 3.7 | 142 B | 3.5 KB | 632 KB | 10 | 2 |
| PriceRefIndex | 65,618 | 12.7 | 175 B | 534 B | 4.6 KB | 0 | 0 |
| Attributes (entity) | 222,489 | 61.5 | 228 B | 1.2 KB | 17 KB | 0 | 0 |
| AssociatedData (entity) | 157,009 | 47.2 | 102 B | 1.6 KB | 17 KB | 0 | 0 |

**Takeaways:**
1. Index parts are **strongly bimodal**: tiny median, thin >32 KB tail owns the churn (FilterIndex
   52 parts >32 KB up to 1.5 MB; SortIndex 24; a few EntityIndex/Unique/Chain; ReferenceType-
   Cardinality few-but-huge).
2. **Only a few hundred parts** would ever be split. **But the page count, not the part count, is
   what hits the location map** (corrected from v1): each paged part becomes tens–thousands of page
   *records*, each one `OffsetLocationChampMap` entry, **multiplied by the number of retained
   catalog versions** for a page churned every commit. Rough budget: the FilterIndex tail today
   ≈ a few thousand extra entries; at 100× scale, hundreds of thousands. This must be quantified
   and gated against Part A's −18 % CHAMP heap win, not waved off (§8.1). It's a strong argument
   for **byte-packed pages** (fewer, larger records) over strict node=page.
3. ~700 k entity-data parts are already sub-KB and granular — **do not touch them**.
4. Real data corrects scope: **PriceRefIndex (65 k parts, all < 8 KB) is NOT a target**;
   Facet/Hierarchy/Histogram/Cardinality are small → skip/defer.

---

## 3. Core design — persist the B+ tree as a copy-on-write page tree

> One shape that degenerates: an index is a paged B+ tree of 1..N nodes; the **root reuses the
> existing StoragePart record type** and is a *leaf when small* (one record — today's behavior) or
> *internal when grown*. No second format, no size branch in the reader.

### 3.0 Scope — a "persisted persistent-structure" framework (node-shape plugins)
Part A converted every churny index into a **path-copying persistent structure**, so the node-page
philosophy (persist nodes, rewrite only the path-copied ones, derive the redundant upper data,
lazy backward-compat) generalises beyond plain B+ trees. §3 is therefore a small framework whose
**shared infra** (root-diff dirty §6, freed-page channel §6, byte-packed pages §3.2, version-pinned
load §5, pageSeq lifecycle §4) is common, with **one node-shape plugin per structure** supplying a
per-node serializer and its "derivable" fields:

- **Plain B+ tree plugin** — `InvertedIndex` bucket tree (`TransactionalBucketBPlusTree`, behind
  Filter/Histogram) and `RangeIndex` long tree (`TransactionalLongBPlusTree`, the shared range-leaf
  scheme §6, consumed by Filter/Histogram/Price-validity). Derivable: internal separators =
  child first-key.
- **Order-statistic B+ tree plugin** — covers TWO indexes whose bulk is the count-augmented
  `UnorderedLookupTree` (`TransactionalUnorderedIntArray = UnorderedLookupTree + TransactionalIntToLongBPlusTree value index`,
  `TransactionalUnorderedIntArray.java:82,86`; `UnorderedLookupTree` already has `bulkLoad` `:219`):
  **SortIndex** (`sortedRecords`) and **ChainIndex** (all elements live in ONE order-statistic
  array, runs described by small descriptor maps — `ChainIndex.java:85-87,117`). A large sort index
  or a long chain pages like Filter: page the position-tree leaves + value-index leaves; only
  path-copied nodes rewrite. Derivable: separators **and** per-child counts (recomputed at load;
  order-keys are stable). (The §9 delta-varint / slim format stays the cheap interim for
  *small/medium* sort/chain indexes that don't warrant the machinery.)
- **CHAMP / map plugin — Tier-1 sharding only (Tier 2 out of scope).** All map-backed indexes are
  `PersistentTransactionalMap` over a path-copying `ChampMap` trie
  (`BitmapIndexedMapNode{dataMap,nodeMap,content[]}`, `ChampMap.java:714`): **GlobalUniqueIndex**
  (catalog-wide value→entity — the prime large-map worst case), **UniqueIndex** (`OwnerUniqueIndex`
  value→recordId), **PriceSuperIndex.entityPrices** (entityId→prices), and the **cardinality**
  parts (Attribute/Group/ReferenceType). For Part B we **shard** a large map by its natural key
  into N parts sized to the band — a change rewrites one shard via the existing
  `PersistentTransactionalMap` serializer, no per-node identity, no new plugin. **Out of scope
  (documented escalation, deferred):** if a single shard still blows the band, the CHAMP trie *can*
  be node-paged (each `BitmapIndexedMapNode` → a page of `(dataMap, nodeMap, inline entries, child
  page-ids)`; path-copy → ~log₃₂ N nodes). That carries its own lifecycle subtleties (node
  *promote*/*demote* instead of split/merge; logical identity = hash-prefix path, which doesn't fit
  the 32-bit `pageSeq` slot, so it needs allocated/64-bit ids). **Revisit only if a shard is
  measured above the band — not built now.**
- **Flat-ordered-array plugin** and **RoaringBitmap plugin** — needed by a few structures whose
  bulk is neither a tree nor a map; see §3.6.

See **§3.6 for the completeness sweep** of every StoragePart that can grow large (independent of
what senesi happened to show), each mapped to a plugin or explicitly deferred/atomic.

### 3.1 Node identity & record types
- **Root node** keeps the **existing record type and PK** (e.g. `FilterIndexStoragePart`,
  PK `join(entityIndexPK, getId(AttributeKeyWithIndexType))`, `AttributeIndexStoragePart.java:57`).
  The registry is a strict class↔byte bijection (`OffsetIndexRecordTypeRegistry`), so the root byte
  is pinned to that class forever. The root's serializer gains an **internal-vs-leaf discriminator
  byte**: leaf-shaped payload when the index fits one node (degenerate small case = one record), or
  an internal-node payload (child page-id list) when grown. **The manifest is unchanged** in
  membership, but **the loader changes** (§5) — it must, on reading an internal root, recurse into
  child pages.
- **Non-root nodes** are new record types addressed by `PK = join(streamId, pageSeq)`:
  - one shared **internal-spine** record type (family-agnostic: an ordered child-`pageSeq` list);
  - one **leaf** record type per family (Filter-leaf, Range-leaf).
  - `streamId` = a compressed int via `KeyCompressor` for a new dedicated key record
    `LeafStreamKey(entityIndexPK, attributeKey, indexType)` — **one dictionary entry per
    sub-index**, not per page. Constraints: must be a dedicated `Comparable` record, never a
    `String` (compressor asserts `!(key instanceof String)`); **must be Kryo-registered in
    `CatalogHeaderKryoConfigurer` with a fixed UID, appended after existing registrations** to
    preserve `index++` ordering (the compressor dictionary is persisted whole in the catalog
    header). Restart-stable `equals/hashCode/compareTo`.
  - `pageSeq` = a per-stream, advance-only, **never-reused** 32-bit id (lifecycle §4).

### 3.2 Page sizing is in serialized BYTES, not block size (corrected)
v1's "node=page with `VALUE_BLOCK_SIZE` as the knob" is wrong twice and is dropped:
- `VALUE_BLOCK_SIZE` is **runtime-only** (the persisted form is rebuilt on load), so it must not
  become a persisted-format parameter; and it bounds bucket **count** (256), **not bytes**.
- A single low-cardinality FilterIndex bucket holds an unbounded `RoaringBitmap` — that is exactly
  the measured **1.5 MB** tail, and it lives in **one** leaf. So node=page does **not** bound a
  monolith, and raising the block size makes it worse.

Therefore **pages are cut by real serialized bytes** (`ObservableOutput` already tracks position —
no JOL, production-safe). The writer streams a node's entries and starts a new page record when the
running byte count crosses the target. Consequences:
- A **hot oversized bucket** (its bitmap alone > the band) is the genuinely hard case. Options
  (decide before building Filter): (a) accept an oversized single-bucket leaf-page (it still
  isolates that bucket's churn — a 1.5 MB rewrite only when *that* value changes, vs the whole
  index today); (b) split one bucket's `RoaringBitmap` across continuation pages. (a) is simpler
  and already a big win; (b) is a later refinement. **Open decision.**
- **Internal/spine pages are tiny** (~child-count × ~2 B varint ≈ a few hundred B) — below the 4 KB
  floor (§3.5). So **do not persist internal nodes 1:1**; **pack the spine** (many internal nodes
  per spine record, or one compact spine record per level) so spine records also amortise the 21 B
  overhead. This keeps the location-map entry count down too (§8.1).

### 3.3 Split fan-out & scale (corrected numbers)
Per-family fan-out differs: **FilterIndex internal fan-out = 128** (InvertedIndex passes
`MIN_VALUE_BLOCK_SIZE=127` as the internal-node block size), **RangeIndex = 256**. So internal
nodes are ~0.8–1.1 % of nodes (not 0.4 %), and a 100× FilterIndex is **depth 4**. A leaf split
rewrites the split leaf → 2 + the affected spine record(s) up the path only on overflow — `O(depth)`
*packed* records, never all data pages, no monolithic header. This holds to ~100×+; the spine
simply gains levels. The win vs today (rewrite the whole 1.5 MB part on one change) is large at all
scales; the honest cost is the page/spine records + their location-map entries (§8.1) and the
delete-path amplification (§3.4).

### 3.4 Delete-path is not symmetric with insert (corrected)
`consolidate()` does `stealFromLeft`/`stealFromRight` keeping **both** siblings alive while
mutating both **and** the parent separator, and a merge abandons a node. So a delete can dirty
**2–3 pages** (donor + recipient + parent), and a steal changes a surviving node's `keyAt(0)`.
Dirty detection must be **content-based** (§6), not instance-based, or steals are missed
(corruption) and unchanged-but-rebuilt promotion nodes are falsely rewritten. §3.3's "≈1 leaf + 1
spine" is the insert path; budget the delete path separately under a delete-heavy mix.

### 3.5 Sweet spot
Target **8–32 KiB serialized (compressed)** per leaf page; **4 KiB hard floor** for *leaf* pages;
spine records are packed (§3.2) so they also clear the floor. Rationale unchanged: appends are
buffered/sequential (small writes don't hurt), but each record is a permanent location-map entry +
a full ~4 KB random page read at load (no readahead) and sub-4 KB often fails DEFLATE's store gate.

### 3.6 Completeness — every StoragePart that can grow large (worst-case, not senesi-observed)

Reasoned from each part's **backing structure**, ignoring what senesi happened to contain. "Worst
case" = the schema/data shape that makes the part big even if senesi's max was small.

| StoragePart | backing | worst-case large when… | treatment |
|---|---|---|---|
| FilterIndex / Histogram | bucket B+ tree + RangeIndex | high-card attribute over many entities | **B+ plugin** (§3.0); hot single-bucket bitmap = §3.3 open item |
| RangeIndex (shared) | long B+ tree | many distinct range thresholds | **B+ plugin** (§3.0), build once |
| SortIndex | order-statistic B+ tree + value index | sortable attribute over millions of rows | **order-statistic plugin** (§3.0); §9 delta-varint for small |
| ChainIndex | ONE order-statistic array + descriptor maps | one long predecessor-chain over millions | **order-statistic plugin** for `elements` (§3.0) **+** §9 slim removes the derivable predecessor/state maps |
| **GlobalUniqueIndex** | ChampMap (value→entity), catalog-wide | a globally-unique high-card attr (url/code) over the whole catalog — **the prime catalog-wide offender** | **CHAMP Tier-1 shard** (§3.0); drop derivable bitmap |
| **UniqueIndex** | ChampMap (value→recordId) + bitmap | high-card unique attr over millions | **CHAMP Tier-1 shard** (§3.0); drop derivable bitmap |
| Attribute/Group/ReferenceType **Cardinality** | maps (+ RefType per-pk bitmaps) | high fan-out reference/group/attr | **CHAMP Tier-1 shard** (§3.0) |
| **PriceSuperIndex.priceRecords** | flat sorted `PriceRecordContract[]` (by internalPriceId) — **the authoritative bulk, NOT covered by the entityPrices shard** | a price list with millions of prices | **Flat-ordered-array plugin** — chunk by stable internalPriceId range (sorted ⇒ stable boundaries; each chunk a page). *Newly identified gap.* |
| PriceSuperIndex.entityPrices | ChampMap (entityId→prices) | many priced entities | **CHAMP Tier-1 shard** (§3.0) |
| **PriceRefIndex.priceIds** | `TransactionalBitmap`/array of price ids | a ref index over a huge superset | **RoaringBitmap plugin** (container-chunk) or shard; §9 Roaring-encode for small |
| **EntityIndex.entityIds** (post-eviction, §9) | `TransactionalBitmap` (RoaringBitmap) | the GLOBAL index over millions of entities; rewritten on **every** entity insert | **RoaringBitmap plugin** — persist per high-16-bit **container range**; an insert rewrites one container page, not the whole bitmap. *Newly identified gap (eviction alone doesn't fix per-insert churn).* same for `entityIdsByLanguage` per locale |
| **FacetIndex** | Map<reference, FacetReferenceIndex(group→facet→entity bitmaps)> | a reference with millions of facet-entity pairs | already map-keyed per reference; **split per (reference, group)** + entity sets as Roaring (container-chunk if huge) |
| **HierarchyIndex** | roots/orphans `TransactionalIntArray` + `levelIndex` Map<parent, children array> + itemIndex | a hierarchy with millions of nodes (deep category tree / hierarchical entity collection) | **shard `levelIndex` by parent-pk range**; chunk large children arrays / roots |
| CatalogIndex | tiny manifest | — bounded (≤ per scope) | skip |
| EntityBody | per-entity scalar header | — bounded small | skip |
| **AssociatedData** | ONE opaque user value per part | a single huge document/blob value (MB–GB) | **OUT OF SCOPE — not optimized in any way** (decision, Johnny 2026-06-22). One opaque user value; index granularity cannot help and blob/stream chunking is explicitly **excluded**, not deferred. |
| Attributes | per-entity-per-locale set | one entity with thousands of attributes / huge string values | bounded by ONE entity (the query read unit); accept, or per-attribute split later — pathological, low priority |
| Prices | per-entity prices | one entity in thousands of price-list×currency combos | bounded by one entity; could split per-price-list — low priority |
| References | per-entity references | a hub entity with millions of refs | **References-per-name** (§9); a single huge reference-name still large → chunk per-name later |
| EntitySchema / CatalogSchema | one Kryo blob | a schema with thousands of attributes/references/compounds | bounded + **low-churn** (schema mutation only); accept, or per-attribute-schema split later |

**Net new gaps this sweep found (beyond v2):** (1) the **PriceSuper `priceRecords` flat bulk
array** (the entityPrices shard does *not* cover it — needs the flat-ordered-array chunk plugin);
(2) the **EntityIndex global `entityIds` RoaringBitmap** churns on every insert even after eviction
— needs container-range chunking; (3) **GlobalUniqueIndex/UniqueIndex/cardinality** are all the
same CHAMP map family and fold under Tier-1 sharding; (4) **Facet/Hierarchy** at scale need
sub-structure sharding; (5) **AssociatedData** is an
arbitrarily large *opaque* part that index granularity cannot help — **out of scope, not optimized
in any way** (decision; blob chunking is excluded, not deferred).

So the framework needs **two more thin plugin shapes** beyond the three trees — *flat-ordered-array
range-chunk* (priceRecords, priceIds) and *RoaringBitmap container-chunk* (entityIds, facet/price
bitmaps) — both simpler than the tree plugins (no separators, stable key/container-range
boundaries). All are still gated behind §9 + the "re-measure the tail" decision; none is built on
faith.

---

## 4. `pageSeq` lifecycle (rewritten)

Each node class gains a **4-byte `int persistentPageId`** (`pageSeq`), distinct from the runtime
`id` (`SEQUENCE.nextId()`, fresh per instance, unusable as on-disk identity). **This is a
constructor-plumbing project across the tree classes** (each split/merge/path-copy site builds
`new` nodes whose constructors must forward the id) — not a one-field add; scope Phase 2 effort
accordingly.

- **Allocation timing:** assign `pageSeq` **only at commit-copy time**, from an **advance-only
  counter held outside transactional memory**, so a transaction **abort/rollback** consumes no id
  and cannot later alias. (v1's "mint during in-tx split" is abort-unsafe.)
- **Split:** the post-split half retaining the original's `keyAt(0)` (the **left** half)
  **inherits** the original's `pageSeq`; the **right** half is minted fresh. Specify this at all
  split sites (leaf + internal). (v1's "original keeps its id" is wrong — there is no surviving
  original instance.)
- **Path-copy:** the copy carries the source's `pageSeq` through the constructor.
- **Merge:** the absorbed node's `pageSeq` is abandoned (dead page record, vacuumed); **steal**
  keeps both ids but both nodes + parent are dirty (§3.4).
- **Root:** the root keeps its existing StoragePart PK; on root-split the old root content moves to
  a fresh child page and the root PK is rewritten as the new top — the root PK never changes.
- **High-water mark:** **derive it at load** as `max(pageSeq)` over the stream's pages (the
  location map is fully read at startup — a free reduction). This is crash-safe **by construction**
  and needs no separately-ordered persisted counter (v1's ordered-header-write invariant has no
  enforcer and is dropped). Legacy load (no pages) → high-water 0.
- **32-bit guard:** `pageSeq` packs into 32 bits via `join`; assert `pageSeq < Integer.MAX_VALUE`
  with a **full-tree re-emit/renumber** escape hatch for a pathological long-lived hot stream
  (never-reused ids on a hot stream are the risk). Alternative: widen to a real 64-bit page id and
  drop `join` packing — decide in Phase 2.

---

## 5. Load / reconstruction (loader-driven, version-pinned)

The Kryo serializer reads one record's bytes and cannot fetch children — so **the loader drives
the recursion**: `AttributeIndexLoader.fetchFilter` (and the range loader) read the root, and if it
is internal, fetch each child page by `join(streamId, childPageSeq)` via
`service.getStoragePart(...)`, recursing to leaves, then wire parent→children and set separators
from each child's first key (the `verifyInternalNodeKeys` invariant). Re-attach `persistentPageId`
from the `pageSeq` each page was read at. This is a direct structural rebuild, O(total nodes), **no
new bottom-up bulk-load needed** (persisting the spine replaces it).

- **Per-family separator invariant** must be proven for FilterIndex (bucket tree, incl. lazy
  overflow bitmaps and front-coded string / scaled-int columns) and RangeIndex before relying on
  separator-from-first-key.
- **Time-machine / historical reads:** the multi-page walk must read **every node at one pinned
  catalog version** (a single `OffsetIndex` Roots snapshot), because the live page set differs per
  version after split/merge. Thread the pinned version through the recursion; prove that for any
  retained version V every page reachable from root-at-V is live at V.

---

## 6. Dirty-page enumeration (root-diff primary; freed-page channel required)

- **Primary = root-vs-prior-root reference diff.** At `getModifiedStorageParts` time (flush, over
  committed state), walk the new committed root; descend only into children **not reference-equal**
  to the prior persisted root's child at that slot (untouched subtrees are shared by reference, so
  this is cheap and correct against the real commit timing). Emit the changed nodes as page
  records. The index already holds the current root; retain the **prior** root as one field.
  (v1's option-A commit-time collector is hooked at the wrong stage — the layer is gone by flush.)
- **Content-based augmentation for steals:** force any node whose `keyAt(0)` changed (and its
  parent) into the dirty set, since a steal mutates a surviving sibling in place (§3.4).
- **Freed pages need a new channel (engine change).** `TrappedChanges` is **add-only**; removals
  go through a separate buffer path (`TransactionalDataStoreMemoryBuffer.trapRemoveByPrimaryKey`).
  So Phase 2 must add a removal/tombstone channel reachable from `getModifiedStorageParts`, and
  model freed pages as **`(pageSeq, pageClass)`** (the `RecordKey` needs the class→byte, not
  `pageSeq` alone). Update the "TrappedChanges unchanged" claim accordingly.
- **Owner/View/Unique shared-dirty:** Filter/Unique views delegate dirty to the shared
  `InvertedIndex` (`FilterIndexView.java:110`). The root PK must derive from the **owner's**
  `AttributeKeyWithIndexType`, so owner/view/unique resolve the **same** root and the same page set
  — avoiding the `FilterIndexView.getId()==1L` collision class (memory
  `filterindexview-getid-1L-histogram-cache-collision`).

---

## 7. Backward compatibility (lazy, no forced migration)

Per-class UID evolution (`SerialVersionBasedSerializer`, `IndexStoragePartConfigurer`): keep the
current fat serializer as `.addBackwardCompatibleSerializer(<oldUID>, oldFat)`, make the new
node-format serializer current, bump the StoragePart's `@Serial serialVersionUID`. **Clarification
(corrected):** the UID switch only swaps the **root record's byte format** (legacy-fat vs new
node) — a one-record decode. It does **not** itself emit N pages; the legacy fat root is read whole
by the old reader, and the index is re-emitted as a page tree on the **next commit** via
`createStoragePart` (lazy upgrade), with the legacy fat root's bytes orphaned then. The **loader
recursion** (§5) is versioned independently of the Kryo serializer. No `STORAGE_PROTOCOL_VERSION`
bump (`PersistenceService.java:58`, currently 6) — that's only for whole-protocol breaks. Capture
each pre-bump UID exactly (a wrong literal bricks reads — the same failure class as the unreleased
histogram).

---

## 8. Risks / open holes

1. **Location-map / heap blow-up (quantify before building).** Cost = **paged-node-count ×
   retained-version-count** for the churning subset, **not** paged-part-count. Could erode Part A's
   −18 % CHAMP win. Mitigations: byte-packed pages (§3.2), packed spine, `int` (not `long`)
   `persistentPageId`. **Gate the design on a measured heap delta at 1×/10×/100×.**
2. **Compaction still whole-file.** Granularity lowers the dead-byte *rate*, not the mechanism;
   for senesi only pickupPoint (133 MB) and product (64 MB→) ever cross the 100 MB trigger, so for
   sub-100 MB collections the payoff is per-commit write cost + time-machine retention, not disk
   reclamation.
3. **Hot oversized bucket** (1.5 MB bitmap in one bucket) — node=page can't bound it; choose
   accept-oversized-leaf vs split-bitmap-across-pages (§3.2). **Open.**
4. **Delete-path amplification** (steal/merge dirty 2–3 pages) — budget under a delete-heavy mix;
   may argue for larger byte-packed pages.
5. **Constructor-plumbing blast radius** — `persistentPageId` threaded through every node ctor /
   path-copy / split / merge / promotion branch across the tree classes; correctness-critical.
6. **Freed-page channel** is a real engine change (§6), not free.
7. **Separator-from-first-key invariant** must be proven per family incl. bucket-tree lazy overflow
   bitmaps and front-coded/scaled-int columns (and the merge-aliasing bug class, memory
   `bucket-tree-merge-overflow-aliasing-bug`).
8. **Time-machine cross-version consistency** of the multi-page walk (§5).
9. **`pageSeq` abort-safety + 32-bit exhaustion** (§4) — counter outside tx memory, renumber escape.
10. **Contiguity at load** — N reads, no readahead; issue reads in `pageSeq` order; maybe teach
    compaction to cluster a stream's pages (separate file-layout decision).
11. **Net storage for the mid-size tail** (8–32 KB indexes that become root + a couple pages) —
    confirm the byte threshold keeps them effectively single-record.

---

## 9. No-new-shape wins (GO NOW — independent, low risk)

Zero page-tree machinery (ordinary serializers, existing PKs, established backward-compat). Ship
these first, then re-measure the residual tail to decide whether §3 is even needed:
- **ChainIndex slim format** (`ChainIndex.java:456`): drop derivable `elementStates`
  (`inChainOfHead`/`predecessor`/`state` recomputed from runs), delta-varint run PKs → ~5× shrink;
  one versioned serializer. (senesi: median chain 64 B, ~8 big chains.) *Large chains* page via the
  §3.0 order-statistic plugin (`elements` is that tree); slim is the small/medium interim.
- **Drop redundant persisted bitmaps:** `UniqueIndex`/`GlobalUnique` record-id bitmap is
  reconstructable from the value→id map (loader already does); varint the record id.
- **Delta-varint / Roaring the monotone raw `int[]`** (4 B/int today): `SortIndex.sortedRecords`
  (small/medium — *large* → §3.0 order-statistic plugin), `Facet` entity arrays, `Hierarchy`
  children, `PriceRef` `priceIds` (*large* → §3.6 RoaringBitmap container-chunk).
- **EntityIndex bitmap eviction** (P0, sub-structure): move `entityIds` + `entityIdsByLanguage`
  into a sibling `EntityIdsStoragePart`; split the coarse dirty boolean into `bitmapsDirty` +
  `manifestDirty` so entity inserts stop rewriting the manifest and sub-index key changes stop
  rewriting the bitmaps. No page tree.
- **References-per-name** (sub-structure): key `ReferencesStoragePart` by
  `join(entityPK, getId(referenceName))` for hub entities; hoist the repeated `referenceName`.
- **Map family — CHAMP Tier-1 shard** (§3.0): shard `GlobalUniqueIndex`, `OwnerUniqueIndex`,
  `PriceSuperIndex.entityPrices`, and the cardinality parts by their natural key so one edit
  rewrites one shard via the existing `PersistentTransactionalMap` serializer. (PriceSuper is a
  churn problem — max 27 KB; GlobalUnique is the catalog-wide *size* worst case.)
- **PriceSuperIndex.priceRecords** (§3.6 flat-ordered-array): the authoritative price bulk is a
  separate sorted array NOT covered by the entityPrices shard — chunk by internalPriceId range when
  large.
- **EntityIndex.entityIds container-chunk** (§3.6 RoaringBitmap): after eviction, the global
  entity-id bitmap still churns on every insert; persist per high-16-bit container so an insert
  rewrites one container page.

---

## 10. Per-part verdict (real-data gated)

- **B+ plugin (§3.0):** FilterIndex (P0, byte-packed leaves for the >32 KB tail; small indexes stay
  single-record), shared RangeIndex scheme (P0, build once), Histogram (rides Filter, P2/unreleased).
- **Order-statistic plugin (§3.0):** SortIndex large (P1), ChainIndex `elements` large (P1) — §9
  slim/delta-varint is the small/medium interim for both.
- **CHAMP Tier-1 shard (§3.0):** GlobalUniqueIndex (P1, catalog-wide size worst case),
  UniqueIndex large (P1), PriceSuperIndex `entityPrices` (P1, churn), Attribute/Group/ReferenceType
  cardinality (P2, gated). CHAMP node-paging (Tier 2) = out of scope.
- **Flat-array / RoaringBitmap chunk (§3.6):** PriceSuperIndex `priceRecords` (P1, the price bulk —
  NOT covered by the entityPrices shard), EntityIndex `entityIds` container-chunk (P1, per-insert
  churn after eviction), PriceRefIndex `priceIds` large (P2), Facet per-(reference,group) +
  Hierarchy `levelIndex` shard (P2, gated).
- **No-new-shape (§9, GO first):** EntityIndex bitmap eviction (P0), ChainIndex slim (P1),
  References-per-name (P1), delta-varint monotone arrays, Unique/GlobalUnique redundant-bitmap drop.
- **Out of scope (firm):** **AssociatedData** — opaque per-value part, **not optimized in any way**
  (decision; no blob chunking). single-entity huge Attributes/Prices (bounded by one entity), large
  schema (low-churn) — noted, not chased.
- **Skip (bounded):** EntityBody, CatalogIndex, PriceRefIndex small.

---

## 11. Sequencing

0. **Baseline measured** (§2). Re-measure after each phase: per-commit bytes written, dead-byte
   rate, cold-load wall-time vs part count, `OffsetLocationChampMap` entry count + heap.
1. **Phase 1 — §9 no-new-shape wins (GO).** Each is one versioned serializer / sub-structure split
   + backward-compat registration.
2. **Re-measure the residual >32 KB tail + the "next-worst" structures.** Decide whether §3 is
   worth building, or whether §9 + the bitmap evictions already flattened the tail enough.
   **Standing reminder (Johnny):** before this work concludes, re-measure the *next-worst*
   structures after the P0 offenders — ReferenceTypeCardinality, GlobalUnique (worst-case, not
   senesi), PriceSuper `priceRecords`, EntityIndex `entityIds` — and decide per structure whether
   it is worth addressing further or deferring. Worst-case reasoning over senesi numbers, not
   senesi alone.
3. **Phase 2 (only if justified) — §3 page-tree infra**, scoped to the 3 B+ tree families, built in
   this order: `persistentPageId` plumbing + commit-copy allocation + load-time high-water; root
   internal/leaf discriminator + non-root page types + `LeafStreamKey` (Kryo header reg);
   root-diff dirty (§6) + freed-page channel; loader recursion (§5) + version pinning. Prove on
   **FilterIndex first** (retires `InvertedIndex.java:574`, `RangeIndex.java:267`,
   `ValueToRecordBitmap.java:94`).
4. **Phase 3** — shared RangeIndex scheme reused by Histogram/Price validity, then any remaining
   gated extras.

---

## 12. Two environment bugs surfaced during analysis (separate from Part B)

- The branch requires **Maven ≥ 3.9.0** (a `dev` merge bumped `git-commit-id-maven-plugin` to
  10.0.0); 3.8.x cannot build it.
- A **pre-bump catalog (e.g. senesi) is unloadable** — `HistogramIndexStoragePart`'s UID was bumped
  with no backward-compatible reader (intentional fail-loud for an unreleased feature,
  `IndexStoragePartConfigurer.java:154`). Worth a conscious call that *any* histogram-bearing dev
  catalog must be regenerated.

---

### Appendix — review provenance
v2 incorporates `wf_1cb93f42-b64` (6 adversarial reviewers + synthesis; doc soundness pre-review
4.5/10, all five blockers code-confirmed). Citations in this doc were the load-bearing ones; a
couple of v1 `file:line` references were off (the tree classes live under `index/bPlusTree/`; node
`id` fields are on the node classes, not the tree) — treat exact lines as indicative and re-verify
at implementation time.
