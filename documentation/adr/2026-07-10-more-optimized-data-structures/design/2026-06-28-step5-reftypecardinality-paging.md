# Step 5 — RefTypeCardinality granular paging (design + implementation)

**Status:** APPROVED 2026-06-29 (Option A, LongPayload bucket-tree backing, GlobalUnique paging template) — implementation underway.
**Author drafted:** overnight 2026-06-28, after all correctness gates passed at HEAD `8baa4e572`.
**Priority:** #2 churn wall per the decodoma re-measure gate (~144 MB single part @10M products,
fully rewritten on every reference change). #1 (GlobalUnique) shipped in #14/#15.

---

## 1. What this index actually stores

`io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex` (used only by `ReferencedTypeEntityIndex`)
holds **two** members, both persisted monolithically by `ReferenceTypeCardinalityIndexStoragePart`
(record byte 32) via `ReferenceTypeCardinalityIndexStoragePartSerializer`:

1. `cardinalities : PersistentTransactionalMap<Long, Integer>` — the bulk and the churn wall.
   - Keys are composed **signed longs**:
     - `+pack(indexPrimaryKey, 0)` — per-index-PK running total (positive).
     - `-1 * pack(indexPrimaryKey, referencedEntityPrimaryKey)` — per-tuple count (negative).
   - Values are small positive `int` cardinalities.
   - Serialized as `count` then `(writeVarLong key, writeVarInt value)*`.
2. `referencedPrimaryKeysIndex : TransactionalMap<Integer, TransactionalBitmap>` — for each referenced
   entity PK, the bitmap of reduced-index PKs referencing it. Serialized as `size` then
   `(varInt key, writeObject bitmap)*`. **Secondary** in size (per the gate, `cardinalities` dominates).

**Key structural fact:** this is the *only* churn-wall index backed by a flat `Map`, not a B+ tree.
All six already-paged indexes (Inverted, Range, Filter, Unique, OwnerUnique, GlobalUnique, PriceSuper)
page a **tree** through `PageStreamRegistry`. So Step 5 needs a backing decision before paging applies.

---

## 2. The design fork

| Option | Idea | New code | Reuse | Cost |
|---|---|---|---|---|
| **A (recommended)** | Re-back `cardinalities` onto the **LongPayload bucket tree** (`LongValueColumn` key + `LongRecordColumn` payload) and page it **exactly like GlobalUnique** | LeafPagePart/Removal/StreamKey + 2 serializers + registry byte + paging methods | **Maximal** — GlobalUnique pages this exact tree shape today; it is a near-clone | one structure swap (map→tree); **no boxing** — count is stored as a primitive `long` |
| B | Keep the CHAMP map, shard its *persistence* into N key-range storage parts | A brand-new map-sharding scheme | None — no precedent in the codebase | Invents a second paging mechanism; hash/range bucketing; harder reload boundary-stability |

### Recommendation: **Option A, backed by the LongPayload bucket tree.**

We already have the tree this needs, and it is already paged. `GlobalUniqueIndex`
(`evita_engine/.../index/attribute/GlobalUniqueIndex.java`) backs its value column on a
**`LongPayloadBucketTree`** — the `TransactionalBucketBPlusTree` configured with a `LongValueColumn` key
(primitive `long[]`, selected by `ValueColumnFactory.forKey(...)` for an integral key) and a
`LongRecordColumn` payload (primitive `long[]`, the single 8-byte record). It pages that tree through
`PageStreamRegistry` — `createEmptyTree` / `fromPersistedPages` / `appendStorageParts` /
`collectChangedPages` + a leaf-page part — and keeps a small companion map (`localeIndex`) **inline on the
root**. That is *exactly* the cardinalities + `referencedPrimaryKeysIndex` layout.

Our `cardinalities` keys are composed **signed longs** and the values are small `int` counts that fit
trivially in a `long`. So `cardinalities` becomes a `LongPayloadBucketTree` (`Long` key → packed `long`
count): a single reference change touches at most two keys (`pack(idxPK,0)` and `-pack(idxPK,refPK)`), so it
rewrites **1–2 ~KB leaf pages** instead of the whole ~144 MB part — the same win shape as GlobalUnique #15.
There is **no value boxing**: the count lives in a primitive `long[]` leaf column, so the old A-now/A-later
boxing trade-off is gone — the primitive tree we would have had to build already exists.

`referencedPrimaryKeysIndex` stays **inline on the root** in both SINGLE and PAGED shapes (mirrors how
GlobalUnique keeps `localeIndex` inline — only the dominant value column pages out). The gate showed the
cardinalities map is the bulk, so this is correct for now; if a later re-measure shows the bitmap map is also
fat under reference churn, it gets its own follow-up (it is `Integer→Bitmap`, a different shape).

### Note on the payload
GlobalUnique packs a `(value, entity)` tuple into the `long` payload via its own `packTuple`. Cardinalities
are simpler: the payload **is** the count, stored directly as a `long` (widened from the `int`), read back
verbatim. So this is structurally *easier* than GlobalUnique, not harder.

---

## 3. Blast radius (Option A), templated on GlobalUnique #15

New files (clone the GlobalUnique equivalents — `GlobalUniqueIndexLeafPagePart`, its serializer, its stream key):
- `ReferenceTypeCardinalityLeafPagePart` (values = `long[]` keys + `long[]` packed counts) + serializer.
- `ReferenceTypeCardinalityLeafPageRemoval` (DeferredRemovalStoragePart — no record byte) + reuse.
- `ReferenceTypeCardinalityLeafStreamKey` (identity = entityIndexPK + referenceName) + serializer.
- Register the leaf-page record byte **41** (40 was just taken by `UniqueIndexLeafPagePart` in #16) in
  `IndexStoragePartRegistry`; leaf-page Kryo in `IndexStoragePartConfigurer`; stream-key Kryo where the
  other per-entity-index stream keys live.

Modified:
- `ReferenceTypeCardinalityIndex`: `cardinalities` map → `LongPayloadBucketTree` (`Long` key → packed `long`
  count, built via `createEmptyTree` with `ValueColumnFactory.forKey(Long.class)` + `RecordColumnFactory.LONG`);
  carry a `PageStreamRegistry` by-ref through the STM merge (copy GlobalUnique's
  `createCopyWithMergedTransactionalMemory` shape); add `appendStorageParts` / `collectChangedPages` /
  `fromPersistedPages` / `isPaged`. Keep the public `addRecord`/`removeRecord`/cardinality-read API
  byte-identical (they call `tree.addLongRecord` / `tree.getLongRecordEqualTo` / `tree.removeLongRecord`
  instead of the map).
- `ReferenceTypeCardinalityIndexStoragePart`: add the PAGED/SINGLE discriminator + `paged(...)` factory +
  `highWaterPageSequence` + `leafPageSequences`; keep `referencedPrimaryKeysIndex` inline on the root.
- `ReferenceTypeCardinalityIndexStoragePartSerializer`: SINGLE branch keeps the current `(varLong,varInt)*`
  cardinality column (now sourced from the tree via an inline snapshot) + the inline bitmap map; PAGED branch
  writes only the page metadata. **UID BUMP + BWC reader REQUIRED** — unlike GlobalUnique's net-new paging, this
  storage part shipped in released minor **2026.1** with the inline map format, so adding the PAGED/SINGLE
  discriminator boolean is a released-boundary format change (the serialVersionUID policy's "across released
  minors" case). The original UID `8276690113370094734L` was bumped to `4729183650284716093L`, and
  `ReferenceTypeCardinalityIndexStoragePartSerializer_2026_1` (registered at the old UID via
  `addBackwardCompatibleSerializer`) reads the legacy inline map into the SINGLE columns. **Caught by the BWC
  test failing on dataset 2026.1** (`corrupted after reading` + cascading `TransactionalBitmap` UID errors) — the
  2026.1 catalog is already at protocol v6 so `Migration_2025_6` never re-runs to rewrite byte-32. 2024.11/2025.5
  are migrated and need no extra reader.
- Loader branch in `DefaultCatalogPersistenceService` (wherever the cardinality part is read) → assemble from
  pages when PAGED.

Tests (clone GlobalUniqueIndexPagingRoundTripTest / OwnerUniqueIndexPagingRoundTripTest):
- A round-trip test through the **real OffsetIndex** (the #16 lesson: a leaf-page part missing its registry
  byte silently never round-trips — assert the new byte 41 dispatches).
- A freed-page-on-merge test (remove keys to force a leaf merge, assert `…LeafPageRemoval` emission + freed/live
  disjointness + reload byte-identity vs an oracle).

---

## 4. Gate / acceptance
- engine+store install; the cardinality + reference-indexing + catalog-persistence + new paging round-trip
  suites 0F/0E.
- `EvitaBackwardCompatibilityTest` 4/4 (the SINGLE shape must still read old catalogs — format unchanged for
  small indexes).
- A decodoma re-measure (needs the lost `DecodomaSizeProbe` rebuilt) to confirm byte-32 max-part collapses
  from ~270 KB to single-leaf size — do this to quantify, not as a blocker.

## 5. Why I did not start coding this overnight
Step 5 is a structural step; the Part B workflow (and the re-measure gate memory) record **"await Johnny's go
per step."** The backing choice (A vs B, and A-now vs A-later on boxing) is yours to confirm. Everything above
is ready to execute the moment you greenlight — Option A is a port of RangeIndex paging and should move fast.
