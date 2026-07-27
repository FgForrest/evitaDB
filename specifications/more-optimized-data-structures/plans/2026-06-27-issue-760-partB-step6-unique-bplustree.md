# #760 Part B — Step 6: front-coded B+ tree backing for unfolded unique indexes

**Status:** PLAN (awaiting Johnny's review before implementation)
**Date:** 2026-06-27
**Supersedes:** the earlier "Step 6 = CHAMP Tier-1 shard" plan in `2026-06-25-decodoma-gate-analysis.md`.

---

## 1. Goal & empirical justification

The #1 churn wall (decodoma re-measure gate) is `GlobalUniqueIndex`: 4 catalog-wide parts at
167–249 KB on only 18.7 K products → ~133 MB single part @10M, **fully rewritten on every
single-entity unique-attribute edit** (one `TransactionalBoolean dirty` gates a whole-map re-serialize).

A measurement probe (`UniqueKeyFrontCodingProbe`, offline-read of `data/decodoma_cz`) settled the
mechanism choice on **real data**:

| unfolded String unique attr (scope) | keys | avg len | adj. shared prefix | RAW→FC disk | boxed→FC mem |
|---|---|---|---|---|---|
| urlInactive cs (LIVE) | 11,237 | 38 B | 23.0 ch | 2.08× | 4.15× |
| url cs (ARCHIVED) | 11,766 | 44 B | 26.7 ch | 2.13× | 3.97× |
| urlInactive cs (ARCHIVED) | 15,024 | 40 B | 21.6 ch | 1.86× | 3.64× |
| url cs (LIVE) | 7,613 | 45 B | 29.4 ch | 2.36× | 4.37× |

**All 4 unfolded String global-unique attributes are URL slug columns**, strongly prefix-heavy
(adjacent sorted keys share 21–29 leading chars; global LCP only 1–3). Aggregate **2.06× disk /
3.96× memory** win from front-coding. A CHAMP hash shard fixes *only* churn — its hash order scatters
the URLs, so it would still pay the full boxed memory. Only an **ordered** structure unlocks the prefix
sharing, and evitaDB already has it: `FrontCodedStringColumn` (restart-16) is auto-selected by
`ValueColumnFactory.forKey(String.class, …)` for the bucket B+ tree that `InvertedIndex`/`FilterIndex`
already run on. So a B+ tree backing delivers all three at once: churn fix (§3 leaf-paging), ~2× disk,
~4× memory.

Cost: lookup O(1) CHAMP → O(log₃₂ n) tree with a front-coded-leaf binary search — the *same* cost
`FilterIndex` already pays on these very `url` attributes.

---

## 2. Scope — what changes, what does NOT

**In scope (the two structures that still pay the monolithic wall and store keys uncompressed):**

1. **`GlobalUniqueIndex`** (catalog-wide, kryo type 609 / record-type byte 31) — never folded; always
   holds its own `uniqueValueToEntityTuple` (`PersistentTransactionalMap<Serializable, EntityWithTypeTuple>`).
2. **standalone `OwnerUniqueIndex`** (per-collection, kryo type 602 / byte 21, `dataPresent=true`) — used
   only for unique attributes that CANNOT fold into the shared filter tree (global-unique-localized /
   non-filterable). Holds `uniqueValueToRecordId` (`PersistentTransactionalMap<Serializable, Integer>`)
   + a `recordIds` bitmap rebuilt from the map.

**Explicitly out of scope:**
- **`UniqueIndexView`** (folded variant): unique-AND-filterable attributes are slim VIEW parts
  (`dataPresent=false`) whose value→record data lives in the shared `FilterIndex` `InvertedIndex` tree —
  already §3-paged AND already front-coded. Untouched.
- The CHAMP cardinality/side maps that are NOT the value→record map (e.g. `entitiesPerType`,
  `idToLocaleIndex`, `recordIds`) stay as-is; they are rebuilt from the tree on load exactly as they are
  rebuilt from the CHAMP map today.

---

## 3. In-memory backing change

### 3.1 OwnerUniqueIndex — reuse the bucket tree AS-IS (zero bucket-tree changes)

`OwnerUniqueIndex` maps unique value → a single `int` record id. That is exactly what
`TransactionalBucketBPlusTree`'s leaf already stores: `ValueColumn<M> keys` (front-coded for String) +
`int[] records` (one pk per key) + a lazy `overflow` bitmap column that stays **null** for cardinality-1
keys. Unique ⇒ cardinality 1 ⇒ `overflow` never allocates.

- `uniqueValueToRecordId` (CHAMP) → `TransactionalBucketBPlusTree` built via
  `ValueColumnFactory.forKey(plainType, comparator)`.
- `recordIds` bitmap: keep, rebuilt by streaming the tree's record column on load (same as today from the
  map's `values()`).
- `getRecordIdByUniqueValue(value)` → tree point lookup. `register/unregisterUniqueKey` → tree
  `addRecord` / remove. `dirty` flag stays for the non-paged metadata; per-leaf dirty drives paging.

Risk: **low** — same code FilterIndex exercises continuously; cardinality-1 keeps `overflow` null.

### 3.2 GlobalUniqueIndex — needs a 3-tuple payload (the design fork)

`GlobalUniqueIndex` maps unique value → `EntityWithTypeTuple { entityType:int (interned), entityPrimaryKey:int, locale:int (interned) }`. `entityType` and `locale` **vary per entry** (one catalog-wide attribute
spans multiple entity types and locales) and `locale` is used to filter at lookup
(`getEntityReferenceByUniqueValue`). The bucket leaf payload is FIXED at `int[] records` (+ overflow
bitmap) — there is no generic per-key value column (`ValueColumn` is key-only). So the tuple does not fit
as-is. Three options:

- **Opt-1 — extend the shared bucket leaf** with one lazy packed-`int` payload column
  `typeLocale[i] = (entityTypeId << 16) | localeId` alongside `records[i]=pk` (entityType/locale are
  small interned domains → 16 bits each is ample; assert on overflow). Threaded through every
  split/merge/steal/copy path, lazy/null when unused (FilterIndex never sets it).
  - *Pros:* least new code; one tree type.
  - *Cons:* touches the most performance-critical, most-tested tree (the one with the prior
    `copyOverflowRange` merge-aliasing bug — see `bucket-tree-merge-overflow-aliasing-bug`). Any defect
    here also risks FilterIndex/InvertedIndex. Highest blast radius.

- **Opt-2 (RECOMMENDED) — dedicated front-coded unique-tuple tree on the shared base.** A small
  `TransactionalUniqueBPlusTree` built on `AbstractTransactionalBPlusTree`, leaf = front-coded
  `ValueColumn<String/Comparable> keys` + `int[] pk` + `int[] typeLocale` (packed). Reuses the base,
  cursor/leaf-page machinery, and `FrontCodedStringColumn`; isolates GlobalUnique from the
  FilterIndex-critical bucket tree.
  - *Pros:* risk-isolated from the hot path; reuses the shared base + columns (matches Johnny's "shared
    base, don't hack the hot tree / no hand-rolled standalone tree" stance); payload shape is explicit.
  - *Cons:* more new code than Opt-1 (a leaf type + its STM split/merge — but derived from the base).

- **Opt-3 — value→pk bucket tree as-is + side `pk→(type,locale)` structure.** Reuses the bucket tree
  untouched but reintroduces a second persisted map — partially defeats the memory goal and adds a
  second lookup. *Rejected* unless Opt-1/2 prove worse.

**Recommendation:** OwnerUnique = §3.1 (bucket tree as-is); GlobalUnique = **Opt-2** (dedicated
base-derived front-coded tuple tree). Decide Opt-1 vs Opt-2 at review — Opt-1 is less code but bets on the
hot tree; Opt-2 is more code but isolates the FilterIndex-critical path.

> Note on the win: the measured 2–4× gain is **entirely GlobalUnique** (all URL slugs were catalog-wide
> global-unique). Standalone OwnerUnique may be empty/rare in decodoma — its win is structural, not yet
> measured here. Implementation still does OwnerUnique first (simplest) to prove the paging wiring before
> the GlobalUnique tuple work.

---

## 4. Persistence — clone the §3 leaf-paging pattern

Mirror exactly what `InvertedIndex`/`FilterIndex` (`e5f57f7a0`) and `PriceSuperIndex` already do.

### 4.1 Emission (on commit/flush)
Add `collectChangedPages()` to each backed index, copied from
`InvertedIndex.collectChangedPages()` (InvertedIndex.java:981–1028):
- `tree.leafPageHandles()` → for each handle, allocate a page sequence for fresh leaves via
  `PageStreamRegistry.allocate(stream)`, emit only `fresh || handle.isDirty()` leaves (materialize the
  leaf's buckets/tuples), `clearDirty()`, compute `freedPageSequences`, `stage(nextLive)`, return
  `PagedEmission{changedPages, orderedPageSequences, highWater, freed}`.
- `PageStreamRegistry` lives owner-resident on the committed index, carried by reference through commit
  merges (same as InvertedIndex).

### 4.2 Root storage parts gain a `paged` discriminator (BWC-clean — see §6)
Mirror `FilterIndexStoragePart.paged(...)`:
- **`GlobalUniqueIndexStoragePart`**: add `boolean paged`, `int highWaterPageSequence`,
  `int[] leafPageSequences`. Non-paged = legacy inline map (still read/written for un-migrated catalogs);
  paged = empty map + leaf-page-sequence list.
- **`UniqueIndexStoragePart`** (owner branch only; the view/`dataPresent=false` branch is unchanged): same
  three fields.

### 4.3 New leaf-page parts + serializers (one per backed index)
Clone `FilterIndexLeafPagePart` + `FilterIndexLeafPagePartSerializer`:
- **`UniqueIndexLeafPagePart`** — write-path identity `(entityIndexPrimaryKey, attributeKeyWithIndexType)`,
  read-path `(streamId, pageSequence)`, payload `value + pk` per bucket. `computeUniquePartIdAndSet` →
  `keyCompressor.getId(new LeafStreamKey(entityIndexPk, attrKey))`; PK = `NumberUtils.join(streamId,
  pageSequence)`.
- **`GlobalUniqueIndexLeafPagePart`** — identity `(scope, attributeKey)` (catalog-wide; resolve a
  `GlobalUniqueLeafStreamKey(scope, attributeKey)` via the catalog key compressor), payload
  `value + pk + entityTypeId + localeId` per bucket.
- Serializers: `streamId` varint, `pageSequence` varint, bucket count varint, then per bucket the
  value (`kryo.writeObject`) + the primitive payload (varints). PK derived, never stored (read side
  recomputes `computeUniquePartId(streamId, pageSequence)`).

### 4.4 Kryo registration
Register both new leaf-page parts in `IndexStoragePartConfigurer` at the **next free index after the
current highest** (read the file at impl time — the §3/Step-4/price leaf parts occupy the 628–634 band;
next free is ~635, `< 700` assert). New parts ⇒ no backward-compatible reader (they did not exist in any
released minor).

### 4.5 Load (assembly from leaves)
Mirror `InvertedIndex.fromPersistedPages` + `AttributeIndexLoader` paged branch (lines 335–362) and
`PriceSuperIndexLoader`:
- **OwnerUnique** — in `AttributeIndexLoader.fetchUnique` (the standalone branch, ~lines 220–237): if
  `part.isPaged()`, resolve `streamId` from `LeafStreamKey`, fetch each
  `UniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)`, build per-page single-leaf trees,
  `assembleFromSingleLeafTrees(pageTrees, pageSequences)`, `restore` the `PageStreamRegistry`
  (highWater + live set), construct the tree-backed `OwnerUniqueIndex`.
- **GlobalUnique** — the catalog-level loader (in `DefaultCatalogPersistenceService`, where
  `GlobalUniqueIndex` is reconstructed from `GlobalUniqueIndexStoragePart`): same paged assembly, payload
  carries the tuple; rebuild `entitiesPerType` + `idToLocaleIndex` from the assembled tree exactly as the
  ctor does from the map today.

---

## 5. New / changed files (inventory)

**New:**
- `…/storageParts/index/UniqueIndexLeafPagePart.java` + `…/serializer/UniqueIndexLeafPagePartSerializer.java`
- `…/storageParts/index/GlobalUniqueIndexLeafPagePart.java` + `…/serializer/GlobalUniqueIndexLeafPagePartSerializer.java`
- `GlobalUniqueLeafStreamKey` (+ key-compressor registration) — catalog-wide stream identity
- (Opt-2) `TransactionalUniqueBPlusTree` leaf/tree on `AbstractTransactionalBPlusTree`

**Changed:**
- `GlobalUniqueIndex.java` — CHAMP → tree; `collectChangedPages()`; paged `createStoragePart`; paged ctor/loader path.
- `OwnerUniqueIndex.java` — CHAMP → bucket tree; `collectChangedPages()`; paged `createStoragePart`.
- `GlobalUniqueIndexStoragePart.java` / `UniqueIndexStoragePart.java` — `paged` discriminator + page fields + `paged(...)` factory.
- their serializers — read/write the discriminator (legacy monolithic branch retained).
- `IndexStoragePartConfigurer.java` — register 2 new leaf-page parts.
- `AttributeIndexLoader.java` (OwnerUnique paged branch) + `DefaultCatalogPersistenceService` (GlobalUnique paged branch).
- (Opt-1 only) `TransactionalBucketBPlusTree` leaf — lazy packed-int payload column.

---

## 6. Backward compatibility & migration

Per Johnny's policy (`serialversionuid-bump-policy`): intra-dev (2026.2-SNAPSHOT) format changes need NO
new BWC reader; only RELEASED-minor boundaries do. Existing BWC readers: GlobalUnique → `_2024_11`;
Unique → `_2025_5`, `_2026_1`. The current serializers are already 2026.2-dev.

**Approach (mirrors §3 FilterIndex exactly):** add a `paged` boolean discriminator to the *current*
serializer rather than a new format/UID. The current serializer reads BOTH:
- legacy monolithic part (existing on-disk decodoma v6 catalogs) → inline map branch, unchanged;
- new paged part → page-sequence branch.

So **no new BWC reader, and no mandatory migration**: existing monolithic GlobalUnique/Unique parts keep
loading; they convert to paged lazily on the next real write to that index (paging is a runtime-commit
path, identical to how §3 left migration-written FilterIndex parts monolithic until the next write). An
eager `Migration_2026_2`-style rewrite is OPTIONAL and can be added later if we want decodoma's existing
parts paged without a write (not required for correctness).

---

## 7. Lookup-cost note

`getEntityReferenceByUniqueValue` / `getRecordIdByUniqueValue` go O(1) CHAMP → O(log₃₂ n) descent +
front-coded-leaf binary search (≤15 sequential decodes per restart block). `FilterIndex` already pays
this on the same `url` attributes; uniqueness enforcement is not a hot loop relative to query filtering.

---

## 8. Test matrix

- **Unit (functional):** tree-backed `OwnerUniqueIndex` + `GlobalUniqueIndex` correctness — register /
  unregister / duplicate-rejection / array attrs / locale filter / `getRecordIds` / `entitiesPerType`
  rebuild. Reuse existing unique-index tests; they must stay green unchanged (same public contract).
- **STM correctness:** transactional commit/rollback parity for the new tree path (the bucket-tree STM
  suites are the template; Opt-1 must add the merge/steal/overflow-aliasing cases that bit before).
- **BWC / load:** open existing monolithic decodoma_cz GlobalUnique/Unique parts → assert values; write
  one edit → assert paged parts emitted; reload → assert identical. (The `UniqueKeyFrontCodingProbe`
  offline-open is the harness seed.)
- **Soak:** `SharedRgeiRandomizedSoakTest` / `SharedRgeiPriceStrandTest` stay green (the reduced-index
  reload path now also exercises paged unique parts).
- **Size re-measure:** re-run the probe post-change → confirm on-disk + in-memory drop ≈ the predicted
  2×/4× on the url attrs.
- **JMH (deferred, after-only):** point-lookup + register/unregister on tree-backed vs CHAMP-backed unique
  index (bundle with the deferred B+ tree base-extraction sweep — "do it in the end").

---

## 9. Delivery order (incremental, green at each gate, stop before commit)

1. **OwnerUnique paged path** (bucket tree as-is) — proves the full emission→part→serializer→loader wiring
   on the simple single-int payload. Green gate (unit + STM + soak).
2. **GlobalUnique** (Opt-2 dedicated tuple tree, or Opt-1 if chosen) — the measured win. Green gate +
   BWC load of decodoma + size re-measure.
3. **(optional)** eager migration to page existing parts; JMH sweep.

Await Johnny's go per step; no commit without `/commit`; build with `-am`; `docs/` stays untracked.
