# Issue #760 Part B — Structural §9 storage-part restructuring — Design Plan

These steps slim/de-amplify the largest and highest-churn index storage parts by changing their
STRUCTURE (sibling sub-parts, per-name splitting, map sharding) — unlike the shipped serializer-only
Phase 1a + SortIndex slim. **They change in-memory shape and write/dirty plumbing, so they carry more
risk and are GATED on a heap/churn re-measure + Johnny's oversight. This is a design doc — do NOT
implement without the gate opening.** Build only with `/tmp/apache-maven-3.9.9/bin/mvn`.

Source-grounded by a planning pass (2026-06-23). `[ASSUMPTION]` marks anything not verified in source.

## 0. What was confirmed against current source
- **EntityIndexStoragePart inlines the bitmaps.** `entityIds` (`Bitmap`) + `entityIdsByLanguage`
  (`Map<Locale, TransactionalBitmap>`) are fields (`EntityIndexStoragePart.java:74,78`) written/read
  directly in `EntityIndexStoragePartSerializer.write/read` (`:81-89, 135-143`). NOT a sibling today →
  Step A is genuinely structural.
- **IndexComponent / ComponentLoader is the exact sibling-part precedent.** Write:
  `IndexComponent.collectModifiedStorageParts` (`IndexComponent.java:74`) driven by
  `EntityIndex.getModifiedStorageParts` looping `this.components` (`EntityIndex.java:825-852`). Read:
  `IndexReloadPlan` + `ComponentLoader` (`HierarchyIndexLoader.java`), wired per subclass via
  `reloadPlan()` (`GlobalEntityIndex.java:253-289`), dispatched in
  `DefaultEntityCollectionPersistenceService.readEntityIndex` (`:809-838`).
- **Sibling parts key by `(recordType byte, entityIndexId long)`** and reuse the parent index PK:
  `HierarchyIndexStoragePart.getStoragePartPK()`/`computeUniquePartIdAndSet()` both return
  `entityIndexPrimaryKey` (`HierarchyIndexStoragePart.java:90-96`). A new EntityIds sibling can reuse
  the entityIndexId as its PK; the new registry byte disambiguates.
- **Blocker #5 (class↔byte bijection) real:** `IndexStoragePartRegistry.listStorageParts()`
  (`:43-61`) hands a fixed byte per class (next free `(byte) 35`). A split root keeps its class+byte;
  only the NEW sibling gets a new byte.
- **Blocker #3 (commit-time dirty diff) handled** via the manifest reference-diff in
  `getModifiedStorageParts` (`EntityIndex.java:838-851`) comparing `original*` snapshots
  (`captureOriginalsFromComponents()`, `:904-917`) vs the live manifest. A new component plugs in.
- **GlobalUnique deferred varint:** `GlobalUniqueIndexStoragePartSerializer.write` writes
  `value.locale()` via `output.writeInt(...)` (`:72`), reads via `input.readInt()` (`:96`). The locale
  int uses `-1` NO_LOCALE sentinel `[ASSUMPTION]` → needs zig-zag (`writeVarInt(v,false)`), not
  unsigned varint.
- **Serializer-evolution** = `SerialVersionBasedSerializer` + `.addBackwardCompatibleSerializer(oldUid,
  reader)` chains (`IndexStoragePartConfigurer.java:67-181`), used 8×+ in this branch.

**Reframe of Step B:** reference attribute data is ALREADY split into separate storage parts per
attribute key (`AttributeIndexStorageKey`; `ReferenceAttributeIndex.java:61`) — there is no monolithic
"all references" blob in `EntityIndexStoragePart`. The granularity unit is the whole
`ReducedEntityIndex` (one per reference-key value) and its manifest. Step B is therefore "shrink/de-
amplify the per-reference manifest churn", likely a near-no-op after Step A — gate on the re-measure.

## 1. Prioritized roadmap
- **Step A — EntityIndex bitmap eviction (P0).** Evict `entityIds`/`entityIdsByLanguage` to a sibling
  `EntityIdsStoragePart` (same entityIndexId PK, own dirty flag). Win = **on-disk churn / write
  amplification** on the 41,057-part type (+ tail bytes on the 127KB-max manifests); heap ≈ 0 (bitmaps
  stay resident). Risk medium; R1 (split dirty flag) highest.
- **Step B — per-reference manifest de-amplification (P1).** Likely satisfied structurally already;
  residual = manifest key-set encoding slim. Gate go/no-go on the post-A re-measure. Risk low-medium.
- **Step C — map Tier-1 shard + GlobalUnique varint (P1/P2).** PriceListAndCurrencySuperIndex (254
  parts, rewritten on ANY price edit → churn) + ReferenceTypeCardinalityIndex (33 parts, 1.2MB max →
  bytes). GlobalUnique varint (zig-zag locale) is a cheap serializer-only rider. Price-shard is HIGH
  risk (ChampMap/PriceRecord[] not a B+ tree, touches query hot path).

**Recommended sequence: A → C(GlobalUnique varint only) → B → C(price/cardinality shard).** The
GlobalUnique varint is cheapest, ships independently, and dry-runs the serializer-evolution before the
harder shard work.

## 2. Step A — implementation-ready detail
### 2.1 New sibling `EntityIdsStoragePart`
`evita_engine/.../storageParts/index/EntityIdsStoragePart.java`: fields `int entityIndexPrimaryKey`,
`Bitmap entityIds`, `Map<Locale, TransactionalBitmap> entityIdsByLanguage`; fresh `@Serial`;
`getStoragePartPK()`→`(long) entityIndexPrimaryKey`; `computeUniquePartIdAndSet`→`entityIndexPrimaryKey`
(mirror `HierarchyIndexStoragePart.java:90-96`); `@ToString(of="entityIndexPrimaryKey")`.

### 2.2 Registration (3 sites)
1. `IndexStoragePartRegistry.java:60`: `new StoragePartRecord((byte) 35, EntityIdsStoragePart.class)`.
2. `IndexStoragePartConfigurer.java` (after `:180`, before the `index < 700` assert `:182`): register a
   `SerialVersionBasedSerializer<>(new EntityIdsStoragePartSerializer(), EntityIdsStoragePart.class)`
   with `index++`. No bwc reader (brand-new type).
3. New `EntityIdsStoragePartSerializer`: write `entityIndexPrimaryKey` varint, then the bitmap +
   locale-map exactly as `EntityIndexStoragePartSerializer.java:81-89` does today (lift verbatim).

### 2.3 EntityIndexStoragePart change + bwc
- Keep `EntityIndexStoragePart.class` + its registry byte `(byte) 20` (root keeps identity, blocker #5).
- New current `EntityIndexStoragePartSerializer`: DROP the bitmap + locale-map writes/reads. Bump
  `EntityIndexStoragePart.serialVersionUID` to a fresh literal.
- Preserve the current serializer as `EntityIndexStoragePartSerializer_2026_2` (write THROWS per the
  read-only convention), register in the bwc chain (`IndexStoragePartConfigurer.java:73-81`) keyed by
  the CONFIRMED current UID **`EntityIndexStoragePart.serialVersionUID = -3842757193845629481L`**
  (`EntityIndexStoragePart.java:56`; review-confirmed — not yet in the chain, so no `_2026_2` collision;
  existing chain has `_2024_11`/`_2025_6`×2/`_2026_1`). The old reader still produces a full part
  carrying the bitmaps (lazy-upgrade source).
- `EntityIndexStoragePartDeprecated` (`:48`) extends the part and is used only to read
  `internalPriceIdSequence` (`DefaultEntityCollectionPersistenceService.java:886-890`). Keep the part's
  constructor accepting bitmaps (so the deprecated subclass + bwc reader compile); the NEW serializer
  simply doesn't persist them. **R3: enumerate all `getEntityIds()/getEntityIdsByLanguage()` callers
  before removing from the serializer** `[ASSUMPTION — not exhaustively done]`.

### 2.4 In-memory EntityIndex
- Bitmaps stay live `TransactionalBitmap`/`TransactionalMap` fields (`EntityIndex.java:123,127`) — no
  query-path change.
- Add `EntityIdsComponent implements IndexComponent` wrapping them. Its `collectModifiedStorageParts`
  emits `EntityIdsStoragePart` only when ITS OWN dirty flag is set; `resetDirty()`/`removeLayer(...)`
  mirror the other components. Register FIRST in `EntityIndex.registerBaseComponents()` (`:924-928`)
  for stable flush order (the `EntityIndexReloadPlanSymmetryTest`, `:192`).
- `EntityIndex.createStoragePart` (`:970-987`): drop `this.entityIds, this.entityIdsByLanguage` from
  the `EntityIndexStoragePart` ctor call — prefer a new ctor overload WITHOUT bitmaps to avoid
  accidental persistence.

### 2.5 Split dirty flag (HIGHEST-RISK, R1)
Today: one `dirty` flag on `EntityIndex` (`:119`) + the manifest reference-diff. **Verified by review:**
all five `dirty.setToTrue()` sites (`EntityIndex.java:367,378,424,445,450`) are PURE bitmap mutations
(`entityIds.add/remove`, `entityIdsByLanguage` ops) and there are no other `setToTrue` sites — so
bitmap mutations are cleanly separable (no site conflates bitmap + manifest). The manifest reference-
diff `original*` snapshots (`:158-182`, captured `:904-917`) cover hierarchy/attribute/price/facet/
histogram, NEVER the bitmaps — dropping bitmaps from the manifest does not break the diff.

Plan: give `EntityIdsComponent` its OWN `TransactionalBoolean dirty`; reroute the 5 bitmap sites to flip
the COMPONENT's flag instead of `EntityIndex.dirty`. `getModifiedStorageParts` emits the manifest on the
manifest-diff condition; the component emits `EntityIdsStoragePart` on its own flag → independent.

**CRITICAL (review finding, HIGH) — `this.dirty` is ALSO read to bump the index version.** All four
`createCopyWithMergedTransactionalMemory` methods compute
`wasDirty = getStateCopyWithCommittedChanges(this.dirty)` then `this.version + (wasDirty ? 1 : 0)`:
`GlobalEntityIndex.java:301,304`; `ReducedEntityIndex.java:251,254`; `ReducedGroupEntityIndex.java:858,864`;
`ReferencedTypeEntityIndex.java:713,716`. If the 5 bitmap sites stop flipping `this.dirty`, a pure
add-PK / add-locale tx no longer bumps `this.version` (the loader reads `manifest.getVersion()`,
`GlobalEntityIndex.java:272`) → the index version FREEZES across bitmap-only commits, breaking version
monotonicity. **Required fix:** in all four methods compute
`wasDirty = thisDirtyCommitted || entityIdsComponentDirtyCommitted` (OR of both flags). Add a
version-bump assertion to the §2.8(5) dirty-tracking test. Getting R1 wrong = lost bitmap writes OR no
de-amplification OR frozen version — invisible to round-trip tests; the dirty-tracking test is the hard
gate.

### 2.6 Loader + reload finalizers
- New `EntityIdsLoader implements ComponentLoader`: fetch `EntityIdsStoragePart` by entityIndexId.
  Present → bundle bitmaps. Absent → OLD-format catalog: fall back to
  `context.entityIndexStoragePart().getEntityIds()/getEntityIdsByLanguage()` (bwc reader populated
  them). Throw ONLY if BOTH sibling absent AND manifest carries no bitmaps (true corruption — R2).
- New `LoadedComponentBundle.EntityIds` record. Add `.add(new EntityIdsLoader())` to EACH subclass
  `reloadPlan()` and pull bitmaps from the bundle instead of `manifest.getEntityIds()`:
  `GlobalEntityIndex.java:253-289` (`:273-274`), `ReducedEntityIndex`, `ReducedGroupEntityIndex`,
  `ReferencedTypeEntityIndex`. The symmetry test (`:192`) enforces the component↔loader pairing.

### 2.7 Migration
- **No eager migration for the steady-state path** — lazy upgrade: old manifests read by the bwc
  `_2026_2` reader (bitmaps inline); next flush writes the slim manifest + a fresh
  `EntityIdsStoragePart`; stale inline bitmaps die on the next compaction.
- `Migration_2026_2` reads `EntityIndexStoragePart` only to enumerate attribute keys
  (`Migration_2026_2.java:184-205`), NOT the bitmaps → unaffected. Do NOT add eviction to it.
- **CRITICAL (review finding, HIGH) — `Migration_2025_6` is a SECOND WRITER of the bitmaps.** The
  protocol-3→4 upgrade (`Migration_2025_6.java:233-247`, active via
  `DefaultCatalogPersistenceService.java:3336`) reads `getEntityIds()/getEntityIdsByLanguage()`
  (`:239-240`) and re-persists via `putStoragePart(new EntityIndexStoragePart(..., bitmaps, ...))`
  (`:235-247`). After Step A's slim serializer, that `putStoragePart` writes the manifest WITHOUT the
  bitmaps and WITHOUT emitting a sibling → **any catalog upgraded 3→4 after Step A ships loses all
  entityIds/entityIdsByLanguage on disk** (the loader fallback then finds nothing → R2 corruption).
  Lazy upgrade does NOT cover this (the migration writes a fresh part through the NEW serializer, not an
  old reader populating from an old manifest). **Required fix:** make `Migration_2025_6` ALSO write an
  `EntityIdsStoragePart` sibling alongside the slim manifest (or route its part write through a path
  that persists the bitmaps). Add an upgrade-path test.
- **No storage-protocol bump for A** (old + new manifests coexist). Confirm no strict-equal
  protocol-version gate `[ASSUMPTION]`.

### 2.8 Tests for Step A
1. Serializer round-trip: `EntityIdsStoragePart` identity + slim `EntityIndexStoragePart` identity.
2. BWC golden: old-format manifest (bitmaps inline) read through the bwc chain → bitmaps recovered.
3. Lazy-upgrade reload: old manifest only (no sibling) → `readEntityIndex` falls back to manifest
   bitmaps; flush → a sibling now exists + the new manifest carries no bitmaps.
4. Behavioural: existing `GlobalEntityIndex`/`ReducedEntityIndex` round-trip tests pass unchanged.
5. **Dirty-tracking proof (key new test):** a tx mutating ONLY an attribute key set → `TrappedChanges`
   has `EntityIndexStoragePart` but NOT `EntityIdsStoragePart`; a tx adding only an entity id → has
   `EntityIdsStoragePart` but NOT a new manifest. Validates R1.

## 3. Steps B and C — outline
### Step B
Confirm each `ReducedEntityIndex` already persists independently (distinct PKs → yes). After Step A the
per-reference manifest is already bitmap-free. **Re-measure before doing more — likely a no-op.** If
still needed: slim the manifest key-set encoding (`EntityIndexStoragePartSerializer.java:91-96` writes
the attribute-index set one entry at a time; per-name grouping/delta could shrink it). New type only if
a sub-split is added (blocker #5 → byte `(byte) 36`; blocker #3 → add an `original*` snapshot).

### Step C
- **GlobalUnique varint (do first, cheap):** `GlobalUniqueIndexStoragePartSerializer.java:72,96`
  `writeInt`/`readInt` → zig-zag `writeVarInt(locale,false)`/`readVarInt(false)`. New serializer + bwc
  reader keyed by the current `GlobalUniqueIndexStoragePart` UID; add to the chain at
  `IndexStoragePartConfigurer.java:121-126`. Round-trip + bwc golden incl. a `-1` locale.
- **Price super shard:** `PriceListAndCurrencySuperIndexStoragePart` =
  `entityIndexPrimaryKey + priceIndexKey + RangeIndex validityIndex + PriceRecordContract[]`
  (`PriceListAndCurrencySuperIndexStoragePartSerializer.java:51-83`); NOT a B+ tree (blocker #1).
  Shard = split `priceRecords[]` (+ matching validity ranges) into N siblings keyed by
  `(entityIndexId, priceIndexKey, shardId)`, each with its own dirty flag. New type + byte. HIGH risk
  (query hot path) — gate on churn measurement + full functional + perf suite.
- **ReferenceTypeCardinality shard:** the 1.2MB outlier — shard the big internal map by key range/hash.
  On-disk-bytes win.

## 4. Re-measure GATE
Extend `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/SenesiSlimSizeProbe.java`
(iterates OffsetIndex entries by recordType byte, per-type byte totals + top-N tables):
1. **On-disk bytes** — add `EntityIndexStoragePart` + new `EntityIdsStoragePart` to the measured set.
2. **Heap** — Step A gives ≈0 heap win (bitmaps stay resident); measure to confirm `[ASSUMPTION: probe
   measures on-disk only; heap needs a new path e.g. JOL]`.
3. **Churn / rewrite-frequency** — the genuinely new metric: over a representative tx workload, count
   bytes each part TYPE writes (records appended × payload). Validates A and C-price `[ASSUMPTION: no
   churn harness exists; needs a tx-replay driver]`.

Win attribution: A → churn (primary) + tail bytes; B → churn (gate go/no-go); C-GlobalUnique → bytes
(small); C-price → churn; C-cardinality → bytes. GATE rule: ship a step only if its named metric
improves past a pre-agreed threshold with NO query-latency regression (full functional + perf suite).

## 5. Risk register
| # | Risk | Category | Severity | Mitigation |
|---|------|----------|----------|------------|
| R1 | Split-dirty-flag: bitmap not persisted (data loss) OR manifest rewritten anyway (no win) OR **index version frozen on bitmap-only commits** (the `createCopyWithMergedTransactionalMemory` `wasDirty` reader the first pass missed) | correctness + dirty-tracking | **HIGHEST** | 5 sites verified bitmap-only (`:367,378,424,445,450`); reroute to component flag AND OR the component flag into `wasDirty` in all 4 subclasses (`GlobalEntityIndex.java:301,304` etc.); 2.8(5) test must assert version bumps on bitmap-only tx |
| R8 | **`Migration_2025_6` (protocol 3→4) re-writes the part through the new slim serializer → bitmaps silently dropped, no sibling written → data loss on upgrade** | correctness + migration | **HIGH** | Make `Migration_2025_6.java:233-247` also write an `EntityIdsStoragePart` sibling; add an upgrade-path test |
| R2 | Lazy-upgrade reads a manifest with no inline bitmaps AND no sibling | bwc + loader | High | `EntityIdsLoader` throws on "both absent" (defensive rule); golden bwc test |
| R3 | A `getEntityIds()` consumer outside the reload finalizers breaks when the field stops being persisted | correctness | Medium | Enumerate all callers before removing from the serializer `[ASSUMPTION]` |
| R4 | Registry byte / serializer index collision | bwc | Medium | Use `(byte) 35`; keep the `index < 700` assert |
| R5 | Reload-plan symmetry test fails if component added without matching loader | build | Low | Add component + loader together |
| R6 | GlobalUnique unsigned varint on `-1` sentinel → bloat/corruption | bwc | Low | Zig-zag; bwc golden with `-1` locale |
| R7 | C-price-shard touches query hot path (not a B+ tree) | correctness + perf | High (Step C only) | Gate on churn measurement; full functional + perf suite |

**Highest-risk: R1.** It can silently lose data or silently deliver zero benefit, invisible to ordinary
round-trip tests — only the targeted dirty-tracking test catches it.

## Critical files
- evita_engine/.../index/EntityIndex.java
- evita_engine/.../storageParts/index/EntityIndexStoragePart.java
- evita_store/.../index/serializer/EntityIndexStoragePartSerializer.java
- evita_store/.../index/IndexStoragePartConfigurer.java
- evita_store/.../index/service/IndexStoragePartRegistry.java
Supporting: `GlobalEntityIndex.java` (+ 3 other reload plans), `IndexComponent.java`,
`IndexReloadPlan.java`, `HierarchyIndexLoader.java`, `DefaultEntityCollectionPersistenceService.java:809-838`,
`GlobalUniqueIndexStoragePartSerializer.java`, `SenesiSlimSizeProbe.java` (the gate).
