# Issue #760 Part B — Phase 1 Step 4: EntityIndex bitmap eviction

Status: DESIGN (planning only — no source changed). Author dialogue: Johnny + Claude, 2026-06-24.
Scope: a §9 "no-new-shape" granularity win (master design doc
`docs/plans/2026-06-22-issue-760-partB-granular-storage-parts.md` §9, line 388–391; §10 line 420).
Dirty-flag discipline pattern: `docs/plans/2026-06-24-issue-760-partB-identity-change-detection.md` §0.

---

## 0. Problem (grounded in current code)

Today one `EntityIndexStoragePart` record per `EntityIndex` holds BOTH:

- **(a) the cold manifest** — `primaryKey`, `version`, `entityIndexKey`, the sub-index reference sets
  (`attributeIndexes`, `priceIndexes`, `hierarchyIndex`, `facetIndexes`, `histogramIndexes`)
  (`EntityIndexStoragePart.java:55–127`), and
- **(b) the hot bitmaps** — `entityIds` (the all-entities `TransactionalBitmap`,
  `EntityIndex.java:123`) and `entityIdsByLanguage` (`Map<Locale, TransactionalBitmap>`,
  `EntityIndex.java:127`), serialized inline at `EntityIndexStoragePartSerializer.java:81–89`.

`EntityIndex` tracks dirtiness with **one** coarse `TransactionalBoolean dirty` (`EntityIndex.java:119`).
Every `insertPrimaryKeyIfMissing` / `removePrimaryKey` / `upsertLanguage` / `removeLanguage`
(`EntityIndex.java:364–455`) sets it. `getModifiedStorageParts` (`EntityIndex.java:825–852`) then
emits the **whole** `EntityIndexStoragePart` — manifest + both bitmaps — whenever `dirty` is true OR
any manifest-set diverges from the captured `original*` baseline. Senesi has 41,057 EntityIndex parts
totalling 9.0 MB (master doc §2). Because every entity insert/delete touches `entityIds`, every such
commit rewrites the entire part, even though the manifest (the bulky reference sets) rarely changes.

**Goal:** split the record so (1) the hot bitmaps live in a sibling part rewritten only when a bitmap
changes, and (2) a manifest-only change (new sub-index key) does not rewrite the bitmaps, and a
bitmap-only change (entity insert) does not rewrite the manifest.

This step does NOT do the §3.6 per-container-range chunking of `entityIds` (master doc line 228, 402);
it only evicts the bitmaps into their own part. Per-insert container churn is a separate, later lever.

---

## DECISIONS FOR APPROVAL

Each decision states a recommendation, the rationale, and the trade-off. Numbers map to the brief.

### Decision 1 — Keying of the new `EntityIdsStoragePart`

**Recommendation: key it by the owning EntityIndex `primaryKey` directly (PK = `(long) primaryKey`),
exactly as `EntityIndexStoragePart` and `HierarchyIndexStoragePart` already do. NO `KeyCompressor`,
NO `LeafStreamKey`.**

- `EntityIndexStoragePart.getStoragePartPK()` returns `(long) primaryKey`
  (`EntityIndexStoragePart.java:131–138`); `HierarchyIndexStoragePart` does the identical thing
  (`HierarchyIndexStoragePart.java:90–97`). Both are sibling parts of one EntityIndex and both reuse
  the index PK verbatim — they never collide because each storage part **class** has its own
  container-type byte (`IndexStoragePartRegistry.java:45,53`: EntityIndex=20, Hierarchy=28), and the
  store keys parts by `(containerType, primaryKey)`.
- So `EntityIdsStoragePart` gets a new container-type byte (next free = **37**, after
  RangeIndexLeafPagePart=36) and PK = `(long) entityIndexPrimaryKey`. One bitmaps part per index,
  1:1 with the manifest.
- **Why not the §3 `LeafStreamKey`/`join(streamId,pageSeq)` machinery** (the brief offers it as a
  candidate): that machinery exists because a FilterIndex emits *many* page records per sub-index, so
  it must pack a stream id + a page sequence into one 64-bit PK (`LeafStreamKey.java:38–51`). The
  bitmaps part is **exactly one record per index**, so there is no page sequence to pack and no need
  for a compressor dictionary entry. Reusing the index PK is strictly simpler and matches the four
  existing sibling parts.
- **Trade-off:** none material. The only thing to verify is that the store treats class-37 PKs as a
  separate keyspace from class-20 (it does — same mechanism Hierarchy/Facet already rely on).

### Decision 2 — On-disk format change to `EntityIndexStoragePart` (remove the two bitmap fields)

**Recommendation: change the format IN PLACE — remove `entityIds` + `entityIdsByLanguage` from the
CURRENT serializer's write/read, do NOT bump `@Serial serialVersionUID`, do NOT add a new bwc reader
for the change. The current UID is a 2026.2-dev value.**

Verification of the policy gate (memory `serialversionuid-bump-policy`):

- Current UID = `-3842757193845629481L` (`EntityIndexStoragePart.java:56`). `git show a706adcca`
  confirms it was introduced **2026-04-15** by the histogram-BWC fix, replacing the **released-2026.1**
  UID `-5960890423106351315L` (which is precisely the value registered as the `_2026_1` bwc reader,
  `IndexStoragePartConfigurer.java:79`). The histogram feature is unreleased (master doc §12,
  line 457–460; `IndexStoragePartConfigurer.java:171–177`). Therefore `-3842757193845629481L` has
  shipped only on dev = **2026.2-dev**, never in a release → change in place, no bump.
- **The subtle part — how the THREE existing released-minor bwc readers feed the split world.** They
  currently read `entityIds` inline; after the split the *current* serializer no longer does, but the
  bwc readers are UNCHANGED and still read inline (they decode released bytes that DO contain the
  bitmaps). The adaptation therefore lives in the **loader**, not the serializers. Each reader's
  output, by UID:

  | registered UID | reader | returns | carries bitmaps? |
  |---|---|---|---|
  | `-6245538251957498672L` | `EntityIndexStoragePartSerializer_2024_11` | `EntityIndexStoragePartDeprecated` | YES (inline) |
  | `5424554446828324138L` | `EntityIndexStoragePartSerializer_2025_6` | `EntityIndexStoragePartDeprecated` | YES (inline) |
  | `6028764096012501468L` | `EntityIndexStoragePartSerializer_2025_6` | `EntityIndexStoragePartDeprecated` | YES (inline) |
  | `-5960890423106351315L` | `EntityIndexStoragePartSerializer_2026_1` | `EntityIndexStoragePart` | YES (inline) |
  | `-3842757193845629481L` (current) | `EntityIndexStoragePartSerializer` (new) | `EntityIndexStoragePart` | **NO (evicted)** |

  All four legacy readers return an `EntityIndexStoragePart` (the deprecated subclass IS an
  `EntityIndexStoragePart`, `EntityIndexStoragePartDeprecated.java:48`) whose `getEntityIds()` /
  `getEntityIdsByLanguage()` are populated from the inline legacy bytes. The split-aware **loader**
  (Decision 3) reads bitmaps from the sibling `EntityIdsStoragePart` *iff it exists*, otherwise falls
  back to `manifest.getEntityIds()`/`getEntityIdsByLanguage()` — which is exactly the legacy path. So
  a legacy catalog loads with zero serializer changes; on its next commit the index re-emits the
  manifest (without bitmaps) **and** a fresh `EntityIdsStoragePart`, lazily upgrading. The legacy fat
  bytes are then orphaned (and vacuumed by compaction).
- **Trade-off:** the in-place change makes the *current dev* on-disk format incompatible with itself
  pre-change. That is the standard, accepted intra-dev-minor behavior (any dev catalog written by an
  earlier 2026.2-dev build that already lacks histogram-aware handling is regenerated). It does NOT
  affect any released catalog, because released catalogs route to the bwc readers by their own UID.

### Decision 3 — Loader reconstruction

**Recommendation: in `readEntityIndex` (`DefaultEntityCollectionPersistenceService.java:820–849`),
after fetching the manifest, attempt to fetch the sibling `EntityIdsStoragePart` by the same PK;
resolve the effective `(entityIds, entityIdsByLanguage)` pair from it when present, else fall back to
the manifest's inline bitmaps (legacy path). Thread the resolved pair through `LoadContext` so the
four subclass finalizers read it from the context instead of from the manifest.**

- The four finalizers currently pull bitmaps straight off the manifest:
  `GlobalEntityIndex.java:273–274`, `ReducedEntityIndex.java:162–163`,
  `ReducedGroupEntityIndex.java:259–260`, `ReferencedTypeEntityIndex.java:323–324`.
- Add two fields to `LoadContext` (`LoadContext.java:56–64`): `@Nonnull Bitmap entityIds` and
  `@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage`. `readEntityIndex` populates them
  from the sibling-or-fallback resolution. The four finalizers change
  `manifest.getEntityIds()` → `context.entityIds()` and
  `manifest.getEntityIdsByLanguage()` → `context.entityIdsByLanguage()`.
- The legacy single-record path needs no special branch: a legacy manifest (deprecated or `_2026_1`)
  has the bitmaps inline and the sibling part is absent, so the fallback returns the manifest bitmaps.
- **Why route through `LoadContext` rather than have each finalizer fetch the sibling itself:** the
  fetch is identical for all four subclasses, must use the pinned `catalogVersion`/`entityIndexId`,
  and the fallback rule must be applied once and consistently. Centralizing it in `readEntityIndex`
  keeps the four finalizers symmetric and avoids four copies of the fallback logic. (`getEntityIds()`
  on the manifest stays a valid accessor — it just becomes empty on freshly-written 2026.2 manifests;
  the finalizers stop reading it.)
- **Trade-off:** one extra `getStoragePart` per index at boot for split catalogs (reload is cold-path,
  master doc notes this is acceptable, `LoadContext.java:41–43`). Acceptable.

### Decision 4 — Dirty-flag split mechanism

**Recommendation: split the single coarse `TransactionalBoolean dirty` into two plain
transaction-aware fields — `bitmapsDirty` and `manifestDirty` — using the SAME mechanism the index
already uses (two `TransactionalBoolean`s), NOT the §3 per-leaf `boolean dirty` pattern.**

- The §3 per-leaf `boolean dirty` (identity-change-detection §0) is a plain boolean precisely because
  the leaf's transactional diff layer *is* a cloned leaf instance, so the boolean is already
  isolated. `EntityIndex` has no such per-instance clone for these flags; today it uses
  `TransactionalBoolean` (`EntityIndex.java:119,227`) for exactly this reason. Keep that tool, just
  use two of them. (A plain boolean on `EntityIndex` would leak across transactions.)
- **`bitmapsDirty` set sites** (replace `this.dirty.setToTrue()` in the bitmap mutators):
  `insertPrimaryKeyIfMissing` (`EntityIndex.java:367`), `removePrimaryKey` (`:378`),
  `upsertLanguage` (`:424`), `removeLanguage` (`:445` and `:450`).
- **`manifestDirty` set sites:** the version field already changes on any committed change, but the
  manifest emit is *also* driven by the `original*`-vs-current diff in `getModifiedStorageParts`. There
  is no current explicit "manifest dirty" mutator — the manifest changes only when a sub-index
  appears/disappears, which is detected structurally by the `Objects.equals(original*, current*)`
  comparisons (`EntityIndex.java:838–844`). So `manifestDirty` is **not** primarily a set-by-mutator
  flag; the manifest emit condition stays the structural diff. The one thing that must move OFF the
  bitmap path is: today a pure entity-insert flips the single `dirty`, which forces a manifest rewrite
  via the `this.dirty.isTrue()` term at `:838`. After the split that term is removed from the manifest
  decision (see emit logic below), so an entity-insert no longer rewrites the manifest.
- **Emit logic in `getModifiedStorageParts` (`EntityIndex.java:825–852`), rewritten:**
  1. Run the component walk as today (`:826–837`) to build the manifest + the four/five live key sets.
  2. **Manifest part** — emit `EntityIndexStoragePart` (now WITHOUT bitmaps) iff the structural diff
     fires: `originalHierarchyIndexEmpty != hierarchyIndexEmpty || !equals(originalAttributeIndexes,…)
     || …histogram` (the existing terms at `:839–843`) **OR** `manifestDirty.isTrue()`. Drop the
     `this.dirty.isTrue()` term — bitmap churn must no longer force a manifest rewrite.
  3. **Bitmaps part** — emit `EntityIdsStoragePart` iff `bitmapsDirty.isTrue()` (see Decision 5 for
     the empty/removal handling).
- `resetDirty` (`EntityIndex.java:855–860`) resets both flags. `removeTransactionalMemoryOfReferencedProducers`
  (`:868–875`) removes both flags' layers.
- **Open sub-point for Johnny:** whether to keep `version` bumping on bitmap-only changes. Today
  `version` increments on any change; the manifest carries `version`. If a bitmap-only commit must not
  rewrite the manifest, then either (a) `version` does not bump on bitmap-only changes, or (b) the
  manifest is allowed to lag the bitmaps part's version. **Recommendation: let the bitmaps part carry
  its OWN version** (it already needs `version` for the storage record), and let the manifest version
  bump only on manifest changes. The two versions are independent record versions; nothing reads them
  cross-referentially at load. This must be checked against `Migration_2026_2`/`Migration_2025_6`
  which read the manifest's `version` (see Risks).

### Decision 5 — Empty / degenerate cases

**Recommendation:**

- **An index with no entities** (`entityIds` empty AND `entityIdsByLanguage` empty): do NOT write an
  `EntityIdsStoragePart` at all — the loader's fallback yields an empty bitmap + empty map, which is
  the correct empty state. A freshly-created index that never gained an entity simply has no bitmaps
  part. This mirrors how `HierarchyIndexStoragePart`/`FacetIndexStoragePart` are absent for indexes
  with no hierarchy/facet data (the manifest's `hierarchyIndex` boolean / `facetIndexes` set gate the
  loader fetches).
- **`entityIdsByLanguage` empty but `entityIds` non-empty:** write the bitmaps part with an empty
  locale map (normal — the global index of a non-localized collection).
- **An index that HAD bitmaps and becomes empty** (last entity removed): the bitmaps part must be
  **removed**, not written empty, so compaction can reclaim it. Emit a `RemovedStoragePart` (or the
  `EntityIdsStoragePart` with a removal marker) for container-type 37 + this PK. Because the bitmaps
  part PK is the plain index PK (Decision 1, no compressor), a **plain `RemovedStoragePart`** suffices
  — there is NO need for the `DeferredRemovalStoragePart` machinery (that exists only for
  compressor-resolved PKs, `DeferredRemovalStoragePart.java:28–33`). The flush drain already handles
  `RemovedStoragePart` (`DefaultEntityCollectionPersistenceService.java:558–563`).
  - To know *whether to remove*, the index must know it PREVIOUSLY had a bitmaps part. Track an
    `originalBitmapsPresent` boolean captured at construction/load time (analogous to
    `originalHierarchyIndexEmpty`): true if the index was loaded with a non-empty bitmaps set or a
    sibling part existed. On a bitmaps-dirty commit where the new state is empty AND
    `originalBitmapsPresent`, emit the removal; otherwise emit/skip the write per the empty rule above.
- **Trade-off:** the "never emit empty needlessly" rule means a brand-new empty index writes no
  bitmaps part — correct and frugal. The removal-on-emptied case adds one boolean of state and one
  branch; cheap and necessary for compaction hygiene.

### Decision 6 — Test plan

See §3 below for the full list. Headline: a new `EntityIdsStoragePartSerializer` round-trip test, a
loader test proving (split-catalog) and (legacy single-record fallback) both rebuild identical
indexes, and dirty-split unit tests proving (entity-insert rewrites bitmaps only) and
(sub-index-add rewrites manifest only). The existing `EvitaBackwardCompatibilityTest` (4 datasets,
2025.1/2025.3/2025.6/2026.1) is the load-time gate; existing EntityIndex round-trip coverage stays.

---

## 1. Implementation steps (file-by-file, ordered)

### Step A — New storage part `EntityIdsStoragePart`
New file
`evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/index/EntityIdsStoragePart.java`.
- Fields: `int primaryKey` (the owning EntityIndex PK), `int version`, `Bitmap entityIds`,
  `Map<Locale, TransactionalBitmap> entityIdsByLanguage`.
- `getStoragePartPK()` / `computeUniquePartIdAndSet(KeyCompressor)` both return `(long) primaryKey`
  (mirror `EntityIndexStoragePart.java:131–138`).
- `@Serial serialVersionUID` = a fresh literal (brand-new class, no bwc concern).
- Full JavaDoc per project rules.

### Step B — Trim `EntityIndexStoragePart`
`EntityIndexStoragePart.java`:
- Remove fields `entityIds` (`:74`) and `entityIdsByLanguage` (`:78`), their getters, the two
  constructor params (`:109–110`) and assignments (`:120–121`).
- Keep `@Serial serialVersionUID` UNCHANGED (`-3842757193845629481L`) per Decision 2.
- **Caller fan-out** of the constructor: `EntityIndex.createStoragePart` (`EntityIndex.java:971–987`)
  drops the two bitmap args; `EntityIndexStoragePartSerializer.read` (`:183–190`) drops them;
  `EntityIndexStoragePartSerializer_2026_1.read` (`:128–135`) — see Step D; the deprecated subclass
  `super(...)` call (`EntityIndexStoragePartDeprecated.java:75–79`) — see Step D;
  `Migration_2025_6.java:235–240` and `Migration_2026_2.java:184` — see Risks/Step G.

### Step C — Current serializer (in-place format change)
`EntityIndexStoragePartSerializer.java`:
- `write`: delete the `entityIds` write (`:81–82`) and the `entityIdsByLanguage` loop (`:84–89`).
- `read`: delete the `entityIds` read (`:135`) and the locale loop (`:137–143`); pass nothing for
  bitmaps to the trimmed `EntityIndexStoragePart` constructor (`:183–190`).
- No UID change, no new bwc reader registered.

### Step D — Keep the bwc readers reading inline; adapt their output type
The three released-minor readers (`_2024_11`, `_2025_6`, `_2026_1`) and the deprecated subclass still
need to PARSE inline bitmaps (released bytes contain them) but can no longer STORE them on the trimmed
`EntityIndexStoragePart`. Two viable shapes — **recommend (i):**
- **(i) Bwc readers parse-and-discard into the manifest's transient carrier.** Since the loader's
  fallback (Decision 3) needs the legacy bitmaps, the readers must surface them. Simplest: keep a
  *transient, non-serialized* `legacyEntityIds`/`legacyEntityIdsByLanguage` carrier ONLY on
  `EntityIndexStoragePartDeprecated` and add the same to whatever `_2026_1` returns. But `_2026_1`
  returns a plain `EntityIndexStoragePart`, which we just trimmed.
  → Cleanest concrete plan:
  - Keep `EntityIndexStoragePartDeprecated` carrying the bitmaps (it already extends the part; add the
    two bitmap fields onto the **deprecated subclass** instead of the base). `_2024_11`/`_2025_6`
    construct it with bitmaps.
  - For `_2026_1`: change it to ALSO return `EntityIndexStoragePartDeprecated` (it currently returns
    the base, `_2026_1:128`), so the 2026.1 inline bitmaps ride on the deprecated carrier too. The
    deprecated subclass's extra fields (`internalPriceIdSequence`, `referenceTypeCardinalityIndex`)
    are nullable/empty for the 2026.1 path — verify the loader never requires them for 2026.1.
  - The loader fallback (Decision 3) then reads bitmaps via
    `manifest instanceof EntityIndexStoragePartDeprecated dep ? dep.getEntityIds() : <empty>` (for the
    legacy path) — i.e. legacy bitmaps come off the deprecated carrier; modern manifests have none and
    the sibling part supplies them.
- **(ii)** Alternatively keep the bitmaps on the base `EntityIndexStoragePart` as `@Nullable`,
  non-serialized-by-the-current-serializer fields, populated only by bwc readers. Rejected: leaves a
  dead nullable axis on the modern part and risks a future writer serializing it.

  **This is the single subtlest piece — flag for Johnny's review.** The recommendation moves the
  legacy bitmap carrier entirely onto `EntityIndexStoragePartDeprecated` and routes `_2026_1` through
  it.

### Step E — Register the new container type
- `IndexStoragePartRegistry.java:44–62`: append
  `new StoragePartRecord((byte) 37, EntityIdsStoragePart.class)` after RangeIndexLeafPagePart=36.
- `IndexStoragePartConfigurer.java`: register `EntityIdsStoragePart` with a
  `SerialVersionBasedSerializer<>(new EntityIdsStoragePartSerializer(this.keyCompressor), …)` —
  **appended LAST** (after RangeIndexLeafPagePart at `:189–193`) to keep all preceding `index++`
  ordering stable. New file
  `evita_store/evita_store_server/src/main/java/io/evitadb/store/index/serializer/EntityIdsStoragePartSerializer.java`
  (write/read `primaryKey`, `version`, `entityIds` via `kryo.writeObject`, the locale map — copy the
  exact bitmap+locale codec from `EntityIndexStoragePartSerializer.java:81–89,135–143`). No
  `KeyCompressor` actually needed for the key, but keep the constructor signature uniform.
- **No `CatalogHeaderKryoConfigurer` change** — that is only for `KeyCompressor` dictionary keys like
  `LeafStreamKey`; this part needs none (Decision 1).

### Step F — `EntityIndex` dirty split + emit + bitmaps part production
`EntityIndex.java`:
- Replace `TransactionalBoolean dirty` (`:119`) with `bitmapsDirty` + `manifestDirty` (both
  `TransactionalBoolean`), initialized in all four constructors (`:227, :281, :347`, and capture in the
  preserve-originals ctor). Add `boolean originalBitmapsPresent` baseline (Decision 5).
- Repoint the four bitmap mutators to `bitmapsDirty.setToTrue()` (`:367, :378, :424, :445, :450`).
- Rewrite `getModifiedStorageParts` (`:825–852`) per Decision 4 emit logic: emit trimmed manifest on
  structural-diff-or-`manifestDirty`; emit `EntityIdsStoragePart` (or `RemovedStoragePart`) on
  `bitmapsDirty` per Decision 5.
- `createStoragePart` (`:971–987`): drop the two bitmap args. Add a `createBitmapsPart()` building the
  `EntityIdsStoragePart` from `this.entityIds`, `this.entityIdsByLanguage`, `this.version`,
  `this.primaryKey`.
- `resetDirty` (`:855–860`) and `removeTransactionalMemoryOfReferencedProducers` (`:868–875`): handle
  both new flags.

### Step G — Loader + context + finalizers
- `LoadContext.java:56–64`: add `entityIds` + `entityIdsByLanguage` record components + JavaDoc.
- `DefaultEntityCollectionPersistenceService.readEntityIndex` (`:820–849`): after the manifest fetch,
  fetch `EntityIdsStoragePart` by `(catalogVersion, entityIndexId, EntityIdsStoragePart.class)`;
  resolve the effective bitmaps (sibling-or-legacy-fallback per Decision 3); pass them into the new
  `LoadContext`.
- Finalizers: `GlobalEntityIndex.java:273–274`, `ReducedEntityIndex.java:162–163`,
  `ReducedGroupEntityIndex.java:259–260`, `ReferencedTypeEntityIndex.java:323–324` — swap
  `manifest.getEntityIds()/getEntityIdsByLanguage()` for `context.entityIds()/entityIdsByLanguage()`.
- `Migration_2025_6.java:235–240` and `Migration_2026_2.java:184`: these construct/read
  `EntityIndexStoragePart` with bitmaps. Migration_2025_6 builds a NEW part — must be updated to write
  the bitmaps into an `EntityIdsStoragePart` too (or be left to the lazy-upgrade path; **decide with
  Johnny** — a migration that rewrites the manifest should also emit the sibling, else the migrated
  catalog has bitmaps only inline on a now-trimmed serializer and would lose them). **This is a
  load-bearing migration interaction — surface it.**

---

## 2. Why this is safe / lazy-upgrade flow

1. **Released catalog (2024.11 / 2025.6 / 2026.1)** loads: manifest routes to a bwc reader by its UID,
   which parses inline bitmaps onto the deprecated carrier; the sibling part is absent; loader
   fallback uses the carrier bitmaps. Identical in-memory result to today.
2. **First commit after load** re-emits the trimmed manifest (current UID, no bitmaps) **and** a fresh
   `EntityIdsStoragePart`. Legacy fat bytes orphaned → vacuumed by compaction.
3. **Steady state (2026.2 native):** entity insert → `bitmapsDirty` → rewrite only the (small) bitmaps
   part. Sub-index add → structural diff → rewrite only the manifest. The two never co-trigger from a
   single-axis change.

No `STORAGE_PROTOCOL_VERSION` bump (stays 6, `PersistenceService.java:58`) — this is a per-part split,
not a whole-protocol break (master doc §7, line 341).

---

## 3. Test plan

- **Serializer round-trip:** new `EntityIdsStoragePartSerializerTest` — write→read identity for
  empty-locale-map, multi-locale, and large `entityIds`. (Kryo, not Java ObjectStream — memory
  `no-java-serialization-roundtrip-tests`.)
- **Trimmed manifest serializer:** extend the existing EntityIndexStoragePart serializer test to assert
  the modern serializer writes/reads NO bitmap section, and that each bwc reader
  (`_2024_11`/`_2025_6`/`_2026_1`) still parses inline bitmaps onto the deprecated carrier.
- **Loader:** new `readEntityIndex` test (or extend the existing EntityIndex reload coverage) proving:
  (a) split catalog — manifest + sibling rebuild an index equal to the original; (b) legacy
  single-record — manifest-only (no sibling) rebuilds with the legacy inline bitmaps. Cover all four
  subclasses (Global / Reduced / ReducedGroup / ReferencedType).
- **Dirty split (unit, on `EntityIndex`/a subclass):**
  - entity insert/remove → `getModifiedStorageParts` emits exactly an `EntityIdsStoragePart`, NOT a
    manifest part;
  - sub-index appearance (e.g. add a filter attribute) → emits exactly a manifest part, NOT a bitmaps
    part;
  - index emptied (last entity removed) → emits a `RemovedStoragePart` for the bitmaps when
    `originalBitmapsPresent`, and nothing when it was never present;
  - no-op commit → emits nothing.
- **E2E / soak:** `EvitaBackwardCompatibilityTest` (longRunning, 2025.1/2025.3/2025.6/2026.1) is the
  cross-version load gate. The existing index/STM soaks (the Step-3 537-test focused suite +
  paged-persistence e2e) exercise commit/reload churn and must stay green.

---

## 4. Risks / rollback

1. **`version` semantics across the split (Decision 4 open sub-point).** If migrations or
   recovery read the manifest's `version` and assume it advances on every change, a manifest that lags
   bitmap-only commits could surprise them. Audit `Migration_2025_6`/`Migration_2026_2` and any
   `getVersion()` consumer before finalizing the two-version model. Mitigation: keep manifest `version`
   bumping on bitmap changes too (cheaper to reason about; the manifest is still NOT rewritten on a
   bitmap-only commit because the emit gate is structural-diff-or-`manifestDirty`, not version).
2. **Bwc carrier routing (Step D).** Routing `_2026_1` through `EntityIndexStoragePartDeprecated` must
   not break `fetchLastAssignedInternalPriceIdFromGlobalIndex`
   (`DefaultEntityCollectionPersistenceService.java:887–902`), which filters on the deprecated class.
   2026.1 data has no `internalPriceIdSequence`; ensure the field is null/optional for that path.
3. **Migration emitting the sibling (Step G).** A migration that rewrites the manifest with the trimmed
   serializer MUST also emit the `EntityIdsStoragePart`, or the migrated catalog loses its bitmaps.
   Decide whether to let the lazy-upgrade path handle it or to emit explicitly in the migration.
4. **Empty/removal correctness (Decision 5).** The `originalBitmapsPresent` baseline must be set on
   ALL load/construct paths (including the preserve-originals copy ctor, `EntityIndex.java:330–359`),
   or an emptied index leaks an orphan bitmaps part.
5. **Rollback:** the change is contained to the EntityIndex part + its serializer/loader + one new part
   class + registry/configurer registration. Reverting restores the inline-bitmap format; because no
   released-minor reader changed and no UID was bumped, a revert is clean against released catalogs.
   Only intra-2026.2-dev catalogs written with the split would need regeneration (standard dev policy).

---

## 5. Net effect (expected)

Senesi: 41,057 EntityIndex parts, 9.0 MB today, rewritten in full on every entity insert. After the
split, an entity insert rewrites only the bitmaps part (the manifest's bulky reference sets stay put),
and a schema/sub-index change rewrites only the (small) manifest. The win is per-commit write volume
and dead-byte rate, not a single-shot size reduction — consistent with master doc §9's framing. The
follow-on §3.6 `entityIds` container-range chunking (per-insert bitmap churn) remains a separate,
later lever and is explicitly out of scope here.
