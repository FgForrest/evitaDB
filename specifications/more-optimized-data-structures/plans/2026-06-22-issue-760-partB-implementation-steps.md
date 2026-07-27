# Issue #760 Part B — Rough Implementation Plan (step-by-step)

Derived from `2026-06-22-issue-760-partB-granular-storage-parts.md` (the design, v3). We execute
**one step at a time**; each step is independently shippable and must leave the build + tests green
before the next. Phase 1 (§9) is GO; Phase 2/3 (§3 framework) is **gated** on re-measuring after
Phase 1. "Rough" = early steps are detailed enough to start; gated steps are intentionally coarse.

## Global conventions (apply to every step)
- **Build with Maven ≥ 3.9.0** — this box's `mvn` is 3.8.7 and **cannot build the branch**
  (`git-commit-id-maven-plugin:10.0.0`). Use `/tmp/apache-maven-3.9.9/bin/mvn` (fetched this
  session) or install 3.9.x. Java 17 toolchain is configured.
- **Per step:** focused unit tests green → `/code-quality-pipeline` on touched files → only then
  next step. **No git commit/push without Johnny's explicit permission.**
- **Serializer evolution recipe** (used by most steps): keep the current serializer class (rename to
  `…Serializer_<oldtag>`), write the new format in the current serializer, **bump the
  StoragePart's `@Serial serialVersionUID`**, register the old one via
  `IndexStoragePartConfigurer.addBackwardCompatibleSerializer(<exact old UID literal>, oldSerializer)`.
  Lazy upgrade: legacy blob reads via old reader, re-emits new on next commit. **No
  `STORAGE_PROTOCOL_VERSION` bump.** Capture the old UID literal exactly (a wrong literal bricks
  reads).
- **Test pattern per serializer:** (a) round-trip new format; (b) lazy-upgrade (read a legacy blob →
  in-memory equal → write → read new); (c) the index's behavioural tests still green.
- **Definition of done (step):** behaviour unchanged, new tests green, full focused suite green,
  quality pipeline clean, size/churn improvement noted, build green on 3.9.x.

---

## Step 0 — shared scaffolding (small, once)
- [ ] Add an **int-array encoder util** (delta + `writeVarInt`; plus a `RoaringBitmap` path) where
  the index serializers can share it (no such compressor exists today; raw `writeInts` is 4 B/int).
- [ ] Add a reusable **versioned-serializer test helper** (round-trip + lazy-upgrade) to cut
  per-step boilerplate.
- [ ] Confirm clean build on Maven 3.9.x + the existing storage test suite green as a baseline.

## Phase 1 — §9 no-new-shape wins (GO; ordered low-risk first)

### Step 1 — ChainIndex slim format  (retires `ChainIndex.java:456` TODO)
- [ ] New `ChainIndexStoragePartSerializer` writing per chain: run PKs (delta-varint via Step 0) +
  head predecessor + head `ElementState`; **drop** the full `elementStates` map (recompute
  `inChainOfHead`/`predecessor`/`state` from runs at load). Old serializer → `_<tag>` backward reader.
- [ ] `ChainIndex.createStoragePart` builds slim form; loader reconstructs `elementStates` from runs.
- [ ] Bump `ChainIndexStoragePart` UID; register backward-compat.
- Tests: `ChainIndexTest` green; ~5× smaller; lazy-upgrade. (Large-chain *paging* is Phase 2 via the
  order-statistic plugin — not here.)

### Step 2 — drop redundant persisted bitmaps (Unique / GlobalUnique)
- [ ] New versioned serializers for `UniqueIndexStoragePart` + `GlobalUniqueIndexStoragePart` that
  **omit the record-id bitmap** (loader already reconstructs it from the value→id map) and varint
  the record id. UID bumps + backward-compat readers.
- Tests: round-trip, lazy-upgrade, unique-index behaviour green.

### Step 3 — delta-varint the monotone raw int arrays
- [ ] Adopt the Step-0 encoder in: `SortIndexStoragePartSerializer.sortedRecords`,
  `FacetIndexStoragePartSerializer` entity arrays, `HierarchyIndexStoragePartSerializer` children,
  `PriceListAndCurrencyRefIndexStoragePartSerializer.priceIds`. One versioned serializer + bwc each.
- Tests per part. (Cheap interim; *large* Sort/PriceRef get the real plugins in Phase 2/3.)

### Step 4 — EntityIndex bitmap eviction  (P0, sub-structure — biggest Phase-1 step)
- [ ] New `EntityIdsStoragePart` (holds `entityIds` + `entityIdsByLanguage`), new record-type byte
  (35+ free), PK = entityIndexPK (or `join(entityIndexPK, scope)`).
- [ ] `EntityIndexStoragePart` drops the inline bitmaps; loader fetches the sibling and wires it.
- [ ] Split `EntityIndex`'s coarse dirty boolean into `bitmapsDirty` + `manifestDirty` so a pure
  entity insert rewrites only the bitmaps part and a sub-index key change rewrites only the manifest.
- [ ] Backward-compat: legacy `EntityIndexStoragePart` (inline bitmaps) read by old serializer; next
  write splits into manifest + `EntityIdsStoragePart`.
- Tests: insert rewrites only bitmaps; sub-index change rewrites only manifest; round-trip +
  lazy-upgrade; `EntityIndexTest` green.
- Note: this does **not** fix per-insert churn of a *huge* global `entityIds` bitmap — that is the
  §3.6 RoaringBitmap container-chunk (Phase 3), gated.

### Step 5 — References-per-name  (sub-structure; broader blast radius — schedule carefully)
- [ ] Key `ReferencesStoragePart` by `join(entityPK, getId(referenceName))`; hoist repeated
  `referenceName` into a header; `EntityBody`'s ref-name set is the manifest.
- [ ] Coalesce tiny reference types into a fallback sub-part; thread `lastUsedPrimaryKey`/executor
  plumbing.
- Tests: a single reference delta rewrites one per-name part; round-trip + lazy-upgrade.
- Note: larger surface (mutation executor + loader) — may split into sub-steps.

### Step 6 — map-family Tier-1 shard (CHAMP)  (more than a serializer; bridge to Phase 2)
- [ ] Shard `GlobalUniqueIndex`, large `OwnerUniqueIndex`, `PriceSuperIndex.entityPrices`, and the
  cardinality maps by a **stable natural key** (e.g. entityId / value-hash bucket) into N parts sized
  to the band; a change rewrites one shard via the existing `PersistentTransactionalMap` serializer.
- [ ] Decide shard count policy (fixed N vs adaptive) + the small-map "stay whole" threshold.
- Tests: one-key change rewrites one shard; round-trip + lazy-upgrade; load reassembles the map.

---

## GATE — re-measure (Johnny's standing reminder)
- [ ] Rebuild server (re-apply the histogram-load skip in `HistogramIndexMapLoader` — **temporary,
  revert after**; it was reverted at the end of the analysis session), reload senesi, JDWP-measure
  the **new** per-type size distribution + `OffsetLocationChampMap` entry count + heap.
- [ ] Decide, per **next-worst** structure (ReferenceTypeCardinality, GlobalUnique worst-case,
  PriceSuper `priceRecords`, EntityIndex `entityIds`): address now vs defer. Reason worst-case, not
  senesi-only.
- [ ] Decide whether the §3 page-tree is justified at all, or whether Phase 1 flattened the tail.

---

## Phase 2 — §3 framework infra (GATED; rough; FilterIndex first)
Build the shared infra once, prove on FilterIndex:
- [ ] `persistentPageId` (int) field threaded through every node ctor / path-copy / split / merge /
  promotion branch of the chosen tree (constructor-plumbing project, §4); **left-half-inherits** on
  split; allocate at **commit-copy time** from a counter outside transactional memory (abort-safe);
  **derive high-water at load** as `max(pageSeq)`; 32-bit guard + renumber escape.
- [ ] Root keeps its existing record type + PK with an **internal-vs-leaf discriminator** in its
  serializer; non-root nodes get new page record types; `LeafStreamKey` compressor key + **Kryo
  header registration** (`CatalogHeaderKryoConfigurer`, fixed UID).
- [ ] **Root-vs-prior-root reference diff** as the primary dirty mechanism (§6); content-based
  augmentation for steals (force `keyAt(0)`-changed nodes + parent); **freed-page channel** added to
  `TrappedChanges` (it is add-only today), freed modelled as `(pageSeq, class)`.
- [ ] **Byte-packed pages** (measure serialized bytes via `ObservableOutput`; not `VALUE_BLOCK_SIZE`)
  + **packed spine** (don't persist internal nodes 1:1 — they're sub-floor); hot-oversized-bucket
  decision (accept oversized leaf vs split bitmap across pages — §3.3 open).
- [ ] **Loader-driven recursion** (`AttributeIndexLoader.fetchFilter`) + **version-pinned** multi-page
  walk for time-machine.
- [ ] Prove on **FilterIndex** → retires `InvertedIndex.java:574`, `RangeIndex.java:267`,
  `ValueToRecordBitmap.java:94`. Gate on the measured location-map/heap delta.

## Phase 3 — remaining plugins (GATED; rough)
- [ ] Shared **RangeIndex** B+ plugin (reused by Histogram + Price validity).
- [ ] **Order-statistic plugin** for SortIndex large + ChainIndex `elements` large.
- [ ] **Flat-ordered-array chunk** for PriceSuper `priceRecords` + PriceRef `priceIds`.
- [ ] **RoaringBitmap container-chunk** for EntityIndex `entityIds` + Facet/Hierarchy at scale.
- [ ] (Out of scope) CHAMP node-paging Tier 2; **AssociatedData not optimized at all.**

---

### Status anchors
- Design doc: `docs/plans/2026-06-22-issue-760-partB-granular-storage-parts.md` (v3).
- Real-data baseline: §2 of the design doc (senesi, via JDWP).
- Nothing implemented yet; working tree clean except pre-existing
  `PriceListAndCurrencyPriceSuperIndex.java` (M) + untracked docs/clojure, docs/scala.
