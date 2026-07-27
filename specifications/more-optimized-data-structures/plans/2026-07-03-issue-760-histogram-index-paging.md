# Issue #760 — Granular leaf-page paging for HistogramIndex — Implementation Plan

Status: PLAN (2026-07-03). Folds the last remaining monolithic large index family — `HistogramIndex` —
into the copy-on-write leaf-page persistence used by the other eight families. Grounded in a read-only
trace of the existing paging machinery (FilterIndex/InvertedIndex/RangeIndex + PageStreamRegistry) and
the HistogramIndex anatomy. Build with the project toolchain (Maven 3.9.x, Java 17). No commit/push
without Johnny's explicit permission.

## 1. Thesis (why this is small)

`HistogramIndex` is **not** a bespoke bucket store. Its subclasses embed the exact structures that are
already paged:

- `SimpleHistogramIndex` = one `OwnerFilterIndex filterIndex` + one `AttributeCardinalityIndex cardinality`.
- `LocalizedHistogramIndex` = `TransactionalMap<Locale, OwnerFilterIndex>` + `TransactionalMap<Locale, AttributeCardinalityIndex>`
  (one sub-histogram per locale; empty locales pruned via deferred removal).

`OwnerFilterIndex` extends `FilterIndex`; its `InvertedIndex` buckets sit in a `TransactionalBucketBPlusTree`
and its optional `RangeIndex` is the real `io.evitadb.index.range.RangeIndex`. Both already expose
`collectChangedPages()` and `fromPersistedPages(...)` and already own their per-stream `PageStreamRegistry`.

Today `HistogramIndex.getModifiedStorageParts` (SimpleHistogramIndex.java:166, LocalizedHistogramIndex.java:307)
materializes the **whole** bucket tree via `InvertedIndex.getValueToRecordBitmap()` and re-serializes the
whole `RangeIndex` + whole `cardinality` into a single `HistogramIndexStoragePart` on any change — the exact
route `FilterIndex` documents as "small index only" (InvertedIndex.java:660-665).

**The plan: make the histogram emit its buckets + range through the embedded index's `collectChangedPages()`
paged path, under histogram-scoped identity — a direct mirror of `FilterIndex.appendStorageParts`
(FilterIndex.java:1122-1247).**

## 2. Scope — buckets + range paged, cardinality evicted (both in this effort)

A histogram part bundles three payloads; **all three are addressed in this effort** (Johnny, 2026-07-03):

| Payload | Backing | Treatment |
|---|---|---|
| buckets (`ValueToRecordBitmap[]`) | `InvertedIndex` → bucket B+ tree | **paged** — `InvertedIndex.collectChangedPages()` |
| `rangeIndex` (range attrs only) | `RangeIndex` → long B+ tree | **paged (when present)** — `RangeIndex.collectChangedPages()` |
| `cardinalityIndex` | `AttributeCardinalityIndex` → `PersistentTransactionalMap` (CHAMP) | **evicted to a sibling part** with its own dirty flag (`EntityIdsStoragePart`-style) |

Buckets + range are the dominant, B+-tree-backed mass — paged leaf-by-leaf so only changed leaves re-serialize.
Cardinality is a CHAMP map (cannot leaf-page without out-of-scope Tier-2 node-paging), and it changes on nearly
every reference add/remove (it gates bucket boundary-crossings), so leaving it inline in the paged root would
re-emit the root on every cardinality delta and cap the root-skip. It is therefore **evicted to a sibling
`HistogramCardinalityStoragePart`** (own dirty flag, keyed by the histogram identity). Net effect: the three
payloads are fully decoupled — a bucket-content change emits only the changed bucket leaf (root skipped when the
page list is stable), a range change only the range leaf, a cardinality-only change only the sibling — and the
paged root shrinks to identity + page-lists.

## 3. Design

### 3.1 Identity & streams
Each `(entityIndexPrimaryKey, histogramName, locale, axis)` is one page stream. New compressed key:

```
HistogramLeafStreamKey(int entityIndexPrimaryKey, String histogramName, @Nullable Locale locale, StreamKind kind)
  StreamKind { BUCKET, RANGE }        // discriminator so bucket & range page-seqs never collide in the packed PK
  implements Comparable<HistogramLeafStreamKey>, Serializable   // never a String (KeyCompressor asserts)
```

Model on `ReferenceTypeCardinalityLeafStreamKey` (the non-attribute family that does **not** use
`AbstractLeafPagePart`/`LeafStreamKey`, which are hardwired to `AttributeKeyWithIndexType`). Page PK =
`NumberUtils.pack(streamId, pageSequence)` where `streamId = keyCompressor.getId(HistogramLeafStreamKey)`,
resolved store-side in `computeUniquePartIdAndSet`.

pageSequence allocation + high-water + live-set come **for free** from the embedded `OwnerFilterIndex`'s own
`InvertedIndex`/`RangeIndex` `PageStreamRegistry` instances (each locale has its own `OwnerFilterIndex`, so
per-locale streams are naturally independent). `InvertedIndex.BUCKET_PAGE_STREAM` / `RangeIndex.RANGE_PAGE_STREAM`
remain the registry-local stream constants; only the on-disk `streamId` is histogram-scoped.

### 3.2 Root part (`HistogramIndexStoragePart`)
Add the paged discriminator, mirroring `FilterIndexStoragePart` (two independent axis triples):

- identity/fixed (unchanged): `entityIndexPrimaryKey`, `histogramName`, `locale`, `valueType`, `indexedDecimalPlaces`
- bucket axis: `boolean paged`, `int highWaterPageSequence`, `int[] leafPageSequences` (or inline `histogramPoints` when SINGLE)
- range axis: `boolean rangePaged`, `int rangeHighWaterPageSequence`, `int[] rangeLeafPageSequences` (or inline `rangeIndex` when SINGLE)
- **`cardinalityIndex` removed** — evicted to the sibling `HistogramCardinalityStoragePart` (§3.6)
- static `paged(...)` factory + keep the existing SINGLE ctor.

`indexedDecimalPlaces` stays frozen into the root and read back verbatim (never re-derived — the histogram
name ≠ source attribute name).

### 3.6 Cardinality sibling (`HistogramCardinalityStoragePart`)
New sibling part, one per `(entityIndexPrimaryKey, histogramName, locale)`, keyed by the **same**
`HistogramIndexKey(histogramName, locale)` compressor key as the root (`computeUniquePartId =
pack(entityIndexPrimaryKey, keyCompressor.getId(HistogramIndexKey))`) — same low-32 identity, distinct record
type byte disambiguates it from the root. Holds the whole `AttributeCardinalityIndex` (lift the existing
cardinality (de)serialization verbatim from `HistogramIndexStoragePartSerializer` lines 80-89). It is **evicted,
not paged** — it rewrites wholly when cardinality changes, but with its **own dirty flag**
(`AttributeCardinalityIndex.isDirty()`, which already exists) it is decoupled from the bucket/range axes.

Emission: the histogram emits the sibling only when its cardinality is dirty (independently of the root/leaf
emission). Load: `HistogramIndexMapLoader` fetches the sibling by the same PK and reattaches it to the rebuilt
`OwnerFilterIndex`; a legacy inline value is not possible (feature unreleased → no old catalogs).

Removal (review E2 — corrected): the sibling is a **distinct record type** (byte 46) from the root (byte 34), and
OffsetIndex removal is per `(recordType, PK)` — sharing the low-32 identity does **not** make the root's removal
cover the sibling, and its PK needs store-side compressor resolution so a plain engine `RemovedStoragePart(class,
PK)` cannot address it. It therefore needs a **dedicated `HistogramCardinalityStoragePartRemoval`
(`DeferredRemovalStoragePart`, PK resolved store-side from `HistogramIndexKey`)**, emitted by the empty-drop diff
(§3.5) whenever a locale or whole histogram is dropped. (The cited `EntityIdsStoragePart` is likewise reclaimed by
an *explicit* `RemovedStoragePart`, not a manifest key-diff — `EntityIndex.java:892-898`.)

### 3.3 Emit path
Rework `HistogramIndex.getModifiedStorageParts` (Simple + Localized) to the paged fold, per `(histogram, locale)`:

```
// CRITICAL (review H1): the filter axes must run ONLY when the InvertedIndex is dirty. A cardinality-only
// change (a non-boundary-crossing reference add/remove) never touches the InvertedIndex, so calling
// inv.collectChangedPages() on a clean index violates its contract (InvertedIndex.java:967-968) and — in the
// SINGLE branch — would forgetPageStream + rewrite the whole inline histogram on every cardinality commit.
final boolean filterDirty = ownerFilterIndex.isDirty();   // true iff buckets OR the embedded rangeIndex changed
if (filterDirty) {
    InvertedIndex inv = ownerFilterIndex.getInvertedIndex();
    if (inv.isPaged()) {                                   // buckets.isRootInternal() → ≥2 leaves
        PageEmission<LeafPage> e = inv.collectChangedPages();
        for (LeafPage p : e.changedPages())  sink.add(new HistogramIndexLeafPagePart(streamKey(BUCKET), p.pageSequence(), p.buckets()));
        for (int freed : e.freedPageSequences()) sink.addRemoval(new HistogramIndexLeafPageRemoval(streamKey(BUCKET), freed));
        bucketAxis = paged(highWater=e.highWater(), pages=e.orderedPageSequences(), listChanged=e.pageListChanged());
    } else { removeAllPriorPages(BUCKET); inv.forgetPageStream(); bucketAxis = inline(inv.getValueToRecordBitmap()); }
    // symmetric block for rangeIndex via RangeIndex.collectChangedPages() when rangeIndex != null
} else { bucketAxis = rangeAxis = PAGE_STABLE; }           // buckets untouched → emit no leaves, root byte-identical
if (cardinality.isDirty()) sink.add(new HistogramCardinalityStoragePart(eixId, name, locale, cardinality));  // §3.6, independent gate
if (!filterDirty || (bucketAxis.pageStable && rangeAxis.pageStable)) return;   // root-skip — cardinality never forces a root re-emit
sink.add(HistogramIndexStoragePart.paged(identity, bucketAxis, rangeAxis));   // no cardinality in the root
```
Note: unlike `FilterIndex.appendStorageParts` (which can `return` early on `!isDirty()`), the histogram cannot —
the cardinality sibling may need emitting while the filter axes are clean. Gate the axes and the sibling separately.

This is `FilterIndex.appendStorageParts` (lines 1122-1247) retargeted to histogram parts. **Optional refactor:**
extract the per-axis "PageEmission → leaf parts + removals + descriptor" fold into a shared helper parameterized
by part/removal factories, reused by both FilterIndex and HistogramIndex (reduces duplication; adds a small SPI —
decide at implementation time, not required for correctness).

Driver: `HistogramIndexMapComponent.collectModifiedStorageParts` already forwards to each histogram's
`getModifiedStorageParts` — unchanged.

### 3.4 Load path
`HistogramIndexMapLoader.load` (currently reads one `HistogramIndexStoragePart` per `(name, locale)`,
HistogramIndexMapLoader.java:74-110): add the paged branch mirroring `AttributeIndexLoader.loadInvertedIndex`
/ `loadRangeIndex`:

```
if (!part.isPaged())   inv = new InvertedIndex(valueType, part.getHistogramPoints(), ...);   // today's route
else {
    int streamId = keyCompressor.getId(new HistogramLeafStreamKey(eixId, name, locale, BUCKET));
    for (seq : part.getLeafPageSequences()) buckets[i] = getStoragePart(cv, pack(streamId, seq), HistogramIndexLeafPagePart.class).getBuckets();
    inv = InvertedIndex.fromPersistedPages(valueType, part.getLeafPageSequences(), buckets, part.getHighWaterPageSequence(), ...);
}
// symmetric for rangeIndex via RangeIndex.fromPersistedPages when rangePaged
cardinality = getStoragePart(cv, pack(eixId, keyCompressor.getId(HistogramIndexKey(name, locale))), HistogramCardinalityStoragePart.class).getCardinality();  // §3.6 sibling
// CRITICAL (review E1): the loader is in io.evitadb.index.component.loader, a DIFFERENT package from the
// package-private OwnerFilterIndex canonical ctor that accepts a pre-built InvertedIndex. The public
// replay-from-buckets ctor rebuilds a FRESH tree with a FRESH empty registry → NOT boundary-stable (first
// post-reload commit re-emits everything). So add a PUBLIC OwnerFilterIndex.fromPersistedPages(...) factory.
ownerFilterIndex = OwnerFilterIndex.fromPersistedPages(indexKey, inv, rangeIndex, valueType, indexedDecimalPlaces);
rebuild histogram from ownerFilterIndex + cardinality;
```

`fromPersistedPages` builds one single-leaf tree per page (boundary-stable), stitches the spine, stamps page
sequences, and seeds the registry via `restoredFrom(...)` which clears each reloaded leaf's dirty flag → the
first post-restart commit re-emits nothing unless a leaf is genuinely mutated. High-water is read from the root,
never re-derived as `max(live)`. `plainType` for `InvertedIndex.fromPersistedPages` is derived locally in the
loader (`valueType.isArray() ? valueType.getComponentType() : valueType`) — `FilterIndex.plainTypeOf` is
package-private.

### 3.5 Empty-drop reclaim (mandatory — review H2/H3, corrected)
Paged leaf pages + the cardinality sibling leak if their owner is dropped without a final `getModifiedStorageParts`.
Two drop levels, each hosted on a **persistent** object (NOT the stateless `HistogramIndexMapComponent`, which is
reconstructed every commit inside the parent ctor — a snapshot field there would be re-seeded *after* the drop and
diff to nothing):

- **whole-histogram drop** (a histogram removed from the parent's `TransactionalMap<String, HistogramIndex>`) —
  hosted on the **parent index** (`ReducedGroupEntityIndex` AND `ReferencedTypeEntityIndex`, both implement
  `HistogramCapableEntityIndex`; put the logic in a shared base/helper). The parent seeds a snapshot
  `Map<String, <per-histogram live pages + sibling identities>>` at construction from the **committed** histogram
  map, carries it through `createCopyWithMergedTransactionalMemory`, diffs snapshot-keys vs the live map after the
  component collects, emits removals for each dropped histogram (all locales × both axes leaf pages + the
  cardinality sibling), then re-snapshots at flush end. Requires a new aggregator
  **`HistogramIndex.currentLeafPageSequences()`** returning, per `(locale, StreamKind)`, the pending-live page
  sequences (staged-else-live) plus the `(locale)` set identifying each cardinality sibling.
- **per-locale drop** (LocalizedHistogramIndex prunes an empty locale while the histogram survives) — hosted on
  **`LocalizedHistogramIndex`** itself (it *is* persistent). Add a carried snapshot field (per-locale live pages +
  sibling), seeded in its from-persisted ctor, carried through its `createCopyWithMergedTransactionalMemory`
  (:332-344), diffed against surviving locales in `getModifiedStorageParts`, re-snapshotted at end. `SimpleHistogramIndex`
  has no locale map → only the whole-histogram (parent) level applies to it.

Removals emitted: `HistogramIndexLeafPageRemoval` (bucket + range) per orphaned page **and**
`HistogramCardinalityStoragePartRemoval` per dropped `(name, locale)`. This is the same sibling-leak class fixed
for the attribute families in commit `5c0ecee80`, but the snapshot MUST live on the persistent object, not the
throw-away component.

## 4. Touch list

**New engine classes** (`evita_engine/.../spi/store/catalog/persistence/storageParts/index/`):
1. `HistogramLeafStreamKey.java` (+ `StreamKind`) — model on `ReferenceTypeCardinalityLeafStreamKey`.
2. `HistogramIndexLeafPagePart.java` — carries `ValueToRecordBitmap[] buckets`; `implements StoragePart`;
   write-path ctor (identity) + read-path ctor (streamId+PK); `computeUniquePartIdAndSet` resolves via the stream key.
3. `HistogramRangeIndexLeafPagePart.java` — carries range points (mirror `RangeIndexLeafPagePart` payload) with
   `StreamKind.RANGE` identity. (Range payload shape ≠ bucket payload → distinct class; both keyed by the shared
   `HistogramLeafStreamKey`.)
4. `HistogramIndexLeafPageRemoval.java` — `implements DeferredRemovalStoragePart`; `removedContainerType()` →
   `HistogramIndexLeafPagePart` (and a range twin, or one removal carrying `StreamKind`). Model on `SortIndexLeafPageRemoval`.
5. Extend `HistogramIndexStoragePart.java` with the two axis triples + `paged(...)` factory; **remove the inline
   `cardinalityIndex`**; bump `serialVersionUID`.
6. `HistogramCardinalityStoragePart.java` (§3.6) — sibling holding the `AttributeCardinalityIndex`, keyed by
   `HistogramIndexKey(histogramName, locale)`; own `serialVersionUID`.
6b. `HistogramCardinalityStoragePartRemoval.java` (review E2) — `DeferredRemovalStoragePart`, `removedContainerType()`
   → `HistogramCardinalityStoragePart`, PK resolved store-side from `HistogramIndexKey`. Model on `SortIndexLeafPageRemoval`.

**Store-side** (`evita_store/evita_store_server/.../store/index/`):
7. `service/IndexStoragePartRegistry.java` — `(byte) 44 → HistogramIndexLeafPagePart`,
   `(byte) 45 → HistogramRangeIndexLeafPagePart`, `(byte) 46 → HistogramCardinalityStoragePart`
   (44/45/46 are the next free bytes; last used is 43).
8. `IndexStoragePartConfigurer.java` — register the three new serializers at the **tail** (`index++`, after the
   Chain leaf part) to keep prior ids stable; **no backward-compatible readers** (feature unreleased — §5). Keep the
   `index < 700` guard.
9. `serializer/HistogramIndexLeafPagePartSerializer.java`, `serializer/HistogramRangeIndexLeafPagePartSerializer.java`
   (model on `FilterIndexLeafPagePartSerializer` / `RangeIndexLeafPagePartSerializer`), and
   `serializer/HistogramCardinalityStoragePartSerializer.java` (lift the cardinality (de)serialization from the
   current `HistogramIndexStoragePartSerializer` lines 80-89).
10. `catalog/CatalogHeaderKryoConfigurer.java` — register `HistogramLeafStreamKey` (+ its serializer) at the tail
    (`index++`, guard `index < 800`) — required so the stream key gets a compressed id and round-trips in the header.
11. `serializer/HistogramLeafStreamKeySerializer.java`.
12. Update `serializer/HistogramIndexStoragePartSerializer.java` — write/read the two paged axis triples, **drop the
    cardinality block** (now in the sibling).

**Emit / load / reclaim**:
13. Rework `SimpleHistogramIndex.getModifiedStorageParts` + `LocalizedHistogramIndex.getModifiedStorageParts` to the
    paged fold + independent cardinality-sibling emission, **gated on `ownerFilterIndex.isDirty()` for the axes**
    (§3.3 review H1).
14. Add a **public `OwnerFilterIndex.fromPersistedPages(key, InvertedIndex, RangeIndex, valueType, decimals)` factory**
    (review E1) and use it in `HistogramIndexMapLoader.load` (paged branch + cardinality-sibling fetch, §3.4/§3.6).
15. Add **`HistogramIndex.currentLeafPageSequences()`** aggregator (per `(locale, StreamKind)` pending-live pages +
    sibling identities) — implemented in `SimpleHistogramIndex` (single null-locale entry) and `LocalizedHistogramIndex`
    (per-locale) (§3.5).
16. **Empty-drop plumbing on persistent objects** (review H2/H3): (a) whole-histogram snapshot+diff on the parent
    hosts `ReducedGroupEntityIndex` + `ReferencedTypeEntityIndex` (shared via `HistogramCapableEntityIndex` base/helper)
    — seed from committed map at ctor, carry through `createCopyWithMergedTransactionalMemory`, diff after the component
    collects, re-snapshot at flush end; (b) per-locale snapshot+diff carried on `LocalizedHistogramIndex` itself.
    Both emit `HistogramIndexLeafPageRemoval` (both axes) + `HistogramCardinalityStoragePartRemoval`.

## 5. Backward compatibility / serialVersionUID
The reference bucketed-histogram feature is **unreleased** (absent from 2024.11 / 2025.5 / 2026.1; origin commits
`016d93255`, `54f02b153`; `IndexStoragePartConfigurer` registers `HistogramIndexStoragePart` with **no** bwc
chain and a fail-loud UID). Per the project's serialVersionUID policy (no bwc reader for intra-dev format change),
this plan **bumps `HistogramIndexStoragePart.serialVersionUID`** and adds the new leaf-part types with **no
backward-compatible readers**. Any stale unreleased-dev catalog fails loud on load (already the intended behaviour).
No `STORAGE_PROTOCOL_VERSION` bump.

## 6. Tests
Mirror `SortIndexOwnerPagingRoundTripTest` / `OwnerUniqueIndexPagingTest` / `GranularFlushReloadRoundTripTest`:

1. **Round-trip** — SINGLE histogram; PAGED (multi-leaf) bucket-only; PAGED bucket+range; localized (multiple
   locales, independent streams). Flush → reload → structural equality (buckets, range, cardinality, scale).
2. **SINGLE↔PAGED transitions** — grow past one leaf → PAGED; shrink to one leaf → collapse (removals emitted for
   every prior live page, stream forgotten); regrow → fresh allocation from clean baseline.
3. **Granular emission** — a bucket-content change re-emits only the changed leaf and, with no page-list change and
   no cardinality delta, **skips the root**; a split allocates a new page and re-emits the root. Assert exact
   `TrappedChanges` membership.
4. **Empty-drop** — drop a locale / whole histogram while paged → a `HistogramIndexLeafPageRemoval` per orphaned
   page (both axes), no leaked pages. Assert against the snapshot diff.
5. **Reload dirty-suppression** — first commit after reload re-emits nothing (boundary stability).
6. **Range axis** — range-typed histogram pages range leaves independently of the bucket axis.
7. **Cardinality eviction** — a cardinality-only change (no bucket boundary crossing) emits **only** the
   `HistogramCardinalityStoragePart` and skips the root + leaves; a bucket-content change emits the changed bucket
   leaf and **not** the cardinality sibling. Assert exact `TrappedChanges` membership. Round-trip reattaches the
   sibling to the rebuilt `OwnerFilterIndex`.

Build order gotcha: `mvn -pl evita_engine install -DskipTests` before running `evita_test` module tests.

## 7. Risks
| # | Risk | Mitigation |
|---|---|---|
| R1 | Bucket/range page-seq confusion across axes | distinct record-type bytes (44 vs 45) already make the PKs non-colliding; the `StreamKind{BUCKET,RANGE}` discriminator additionally gives distinct stream ids per axis (recommended, not strictly required for PK safety — review H4) |
| R2 | Leaked leaf pages when a locale or whole histogram is dropped while paged | snapshot+diff `emitDroppedLeafPageRemovals` in the container (§3.5) — the `5c0ecee80` pattern |
| R3 | First post-reload commit re-emits every leaf (churn) | `fromPersistedPages` one-page-one-leaf + `restoredFrom` dirty-clear (inherited from InvertedIndex/RangeIndex) |
| R4 | Cardinality sibling orphaned when a locale/histogram is dropped | reclaim it in the same empty-drop diff as the root (§3.5); it shares the histogram manifest key |
| R5 | Kryo header id drift breaks existing dictionaries | append `HistogramLeafStreamKey` registration at the tail only |
| R6 | High-water re-derived as `max(live)` hands back a retired id to a retained version | persist high-water verbatim in the root (mirror FilterIndex) |

## 8. Out of scope
`AttributeCardinalityIndex` is evicted to a sibling (whole-rewrite on change, own dirty flag) but **not itself
leaf-paged** — it is a CHAMP map and CHAMP node-paging (Tier-2) remains out of scope for #760. If a single
cardinality sibling is ever measured as a churn wall at scale, Tier-2 node-paging would be a separate follow-up.

## Key reference files
- Template: `evita_engine/.../index/attribute/FilterIndex.java` (`appendStorageParts` 1122-1247),
  `.../index/invertedIndex/InvertedIndex.java` (`collectChangedPages`, `fromPersistedPages`, `isPaged`),
  `.../index/range/RangeIndex.java` (same trio), `.../index/page/{PageStreamRegistry,PageEmission}.java`.
- Non-attribute leaf template: `.../storageParts/index/{ReferenceTypeCardinalityLeafStreamKey,ReferenceTypeCardinalityIndexLeafPagePart,SortIndexLeafPageRemoval}.java`.
- Target: `.../index/{HistogramIndex,SimpleHistogramIndex,LocalizedHistogramIndex}.java`,
  `.../storageParts/index/{HistogramIndexStoragePart,HistogramIndexStorageKey,HistogramIndexKey}.java`,
  `.../index/component/{HistogramIndexMapComponent,loader/HistogramIndexMapLoader}.java`,
  `evita_store/.../store/index/serializer/HistogramIndexStoragePartSerializer.java`.
- Empty-drop template: `.../index/attribute/AttributeIndex.java` (snapshot/emitDroppedLeafPageRemovals) + commit `5c0ecee80`.
