# SortIndex granular paging — I-B + I-C implementation design

**Status:** design, awaiting Johnny's go to implement. Decisions locked (2026-06-29 research +
`sortindex-churn-feasibility` workflow): **target = on-disk byte-23 churn only** (heap/II-B off the table);
**ship I-B + I-C together** as one coherent change set; **no JMH gate** (both keep the read model → risk is
correctness, validated by tests, not throughput). Supersedes the menu in
`2026-06-29-sortindex-10m-churn-ideation.md` for these two options.

> `[VERIFY]` = re-confirm against code during implementation.

---

## 1. The unifying insight (why I-B and I-C are one change)

A sort index by value, with records ascending-by-id *within* each value, **is an inverted index**
(`value → ascending record postings`); the flat `sortedRecords` array is just the concatenation of those
postings in value order. So:

- **`sortedRecords` is fully derivable** from any `value → records` tree by concatenating buckets in value
  order. Within-block order is deterministic ascending id (`SortIndexStoragePartSerializer.java:51-58`
  confirms it; `InvertedIndex` buckets are ascending id) → **byte-for-byte exact reconstruction**.
- **Cardinality** = bucket size (view mode already does this, `SortIndexView.getValueCardinality`).

Therefore both variants converge on the same shape: back the value side onto an `InvertedIndex`, drop the
persisted positional `sortedRecords`, and **rebuild `sortedRecords` + `positionTree` + `valueIndex` at load**
by concatenating buckets. The only difference:

| | value→records tree | on disk |
|---|---|---|
| **SortIndexView** (I-B) | the **shared** `InvertedIndex` (FilterIndex), already §3-paged | slim metadata-only part; `sortedRecords` reconstructed from the shared tree |
| **OwnerSortIndex** (I-C) | its **own** `InvertedIndex`, newly §3-paged | the owned tree's PAGED leaf pages + a root; `sortedRecords` reconstructed from the owned tree |

`positionTree`/`valueIndex` are **never persisted** (rebuilt at load via the existing
`TransactionalUnorderedIntArray(int[])` ctor, which repopulates `valueIndex` through `OrderKeyConsumer` —
`TransactionalUnorderedIntArray.java:101-122,141-143`). This refines the brief's "third tree": `valueIndex`
exists in memory but is pure derived state, never on disk.

**Read path is untouched** — `SortIndexChanges` still materialises the Layer-2 arrays
(`sortedRecordIds[]`/`recordPositions[]`/`allRecords`) from the reconstructed `sortedRecords`, so
`MergedSortedRecordsSupplierSorter` is byte-identical. The settled "merge-join loses on selective sorts"
finding does not apply (we keep the materialised array).

**Accepted tradeoff (consistent with the churn-only decision):** owner mode now holds records twice in
memory — once in the positional `sortedRecords` façade, once in the owned `InvertedIndex` postings
(compact RoaringBitmaps). Johnny accepted heap cost in favour of churn. A future heap pass (I-C-3, §9) could
drop the in-memory positional façade and derive Layer-2 arrays straight from buckets; out of scope now.

---

## 2. P0 prerequisite — `SortIndex.appendStorageParts`

Today SORT is the lone holdout still using the whole-part `createStoragePart` at
`AttributeIndex.getModifiedStorageParts` (`AttributeIndex.java:1115-1118`); UNIQUE and FILTER already use
`appendStorageParts` (`:1100-1113`). Add:

```java
public abstract void appendStorageParts(int entityIndexPrimaryKey, TrappedChanges trappedChanges);
```

to `SortIndex`, implemented per variant, and switch the `AttributeIndex` loop to call it. This mirrors
`UniqueIndex`/`FilterIndex` exactly (SINGLE inline root for a small index, PAGED leaf pages + removals +
PAGED root for a large one, slim for a view). Remove the now-unused `SortIndex.createStoragePart` once no
caller remains `[VERIFY]` migration/tests don't depend on it; the deprecated by-key `createStoragePart` was
already purged for Unique/Filter (`deprecated-createstoragepart-removal`), and SORT now joins that path —
this lifts the "KEPT Sort/Chain (SINGLE-only)" exception noted there.

---

## 3. I-B — view mode (smaller, do first)

**In memory:** unchanged — `SortIndexView` already sources values/cardinality from the shared
`InvertedIndex` (`SortIndexView.java`).

**Persist:** a slim part — `entityIndexPK`, `attributeIndexKey`, `comparatorBase`, `indexedDecimalPlaces`,
and the existing view marker. **Stop writing the positional `sortedRecords` int[]** (today the view branch
still writes it raw, `SortIndexStoragePartSerializer.java:129-134`).

**Load:** the loader builds the `SortIndexView` and reconstructs `sortedRecords` by concatenating the shared
tree's buckets in value order:
```
for bucket in sharedInverted.getValueIterator():   // ascending value order
    append bucket.getRecordIds() (ascending id)     // -> sortedRecords
```
then `new TransactionalUnorderedIntArray(sortedRecords)` rebuilds `positionTree`+`valueIndex`.

**Hard prerequisite — loader ordering:** the shared `InvertedIndex` (FILTER) for the key MUST be fully
loaded before the SORT view is reconstructed. `[VERIFY]` `AttributeIndexLoader` loads `sharedValueIndex`
before `sortIndex`, and `SortIndex.create(...,sharedSupplier)` resolves a populated tree at load. If the
order isn't guaranteed, reconstruct lazily on first supplier build instead of eagerly at load.

---

## 4. I-C — owner mode (the larger half)

Replace `OwnerSortIndex`'s value side (`sortedValues : TransactionalObjectBPlusTree<value,cardinality>`)
with an **owned `InvertedIndex`** (`value → records` bitmap), making owner structurally symmetric with view.
(Recommended shape **I-C-2** of §9.)

**In memory:**
- `OwnerSortIndex` owns an `InvertedIndex` built with the sort `comparator` (with direction + NULLS baked
  in, as the bucket order = final sort order) and the sort `normalizer`/`plainType`.
- The value-side hooks delegate to it: `onFirstRecordForValue` → `inverted.addRecord(value, id)`;
  `getValueCardinality`/`preRemovalCardinality` → `inverted.cardinalityOf(value)`; `valueCursor`/
  `valueReverseCursor` → `inverted.getValueIterator()`/`getValueReverseIterator()`; `valueCount` →
  `getBucketCount`; `valuePresent` → `cardinalityOf > 0`. (These are exactly the `SortIndexView`
  implementations — large code-share; consider hoisting them to a shared mixin.)
- `sortedRecords` (positionTree+valueIndex) stays as the live positional façade for reads/mutation.

**Persist:** the owned `InvertedIndex` via the **existing §3 paging** (`InvertedIndex` already implements
`PagedLeafHandle` + `collectChangedPages` + `assembleFromSingleLeafTrees` + `PageStreamRegistry.restoredFrom`,
`InvertedIndex.java:208,463-517,973-977`). The owner `SortIndexStoragePart` becomes a PAGED root holding the
owned tree's high-water + ordered live page-sequence list + sort metadata; the leaf pages are the owned
tree's `*LeafPagePart`s. **Drop** the flat `sortedRecords`, `sortedRecordsValues`, and sparse cardinality
columns from disk (all reconstructable). A small owner index stays SINGLE/inline.

**Load:** assemble the owned `InvertedIndex` from its page stream (or inline), then reconstruct
`sortedRecords`+`positionTree`+`valueIndex` by bucket concatenation exactly as I-B.

**Compounds / direction / nulls** `[VERIFY]`: the owned `InvertedIndex` must accept `ComparableArray` keys
under the combined comparator (it is comparator-ordered, not natural-order — established by the shared-tree
work), must not assume ascending-natural semantics beyond the supplied comparator (owner sort uses only
ordered iteration + cardinality, never range `getRecords(moreThanEq,…)`), and must handle the NULLS-first/last
key the comparator wrappers introduce.

---

## 5. Storage parts / serializer / registry

- **`SortIndexStoragePart`** gains a PAGED/SINGLE/SLIM discriminator (mirror
  `ReferenceTypeCardinalityIndexStoragePart` / `GlobalUniqueIndexStoragePart`). SINGLE = small owner inline;
  PAGED = owner root (high-water + live page-seq list + metadata); SLIM = view (metadata only).
- **Owner leaf pages** reuse the InvertedIndex leaf-page part type if it is value-agnostic enough, else add
  `SortIndexLeafPagePart` + `SortIndexLeafPageRemoval` + `SortIndexLeafStreamKey` + serializers, following
  the Step-5 file set. `[VERIFY]` whether the existing inverted bucket-tree leaf part can be reused directly
  (preferred — fewer new types) or needs a sort-keyed twin (compound/comparator keys).
- Registry: new bytes in `IndexStoragePartRegistry` + serializer registrations in
  `IndexStoragePartConfigurer` for any new part types; Kryo registration for a new stream key.

---

## 6. BWC / migration

- **serialVersionUID:** byte-23 SORT last changed **intra-dev (2026.2, unreleased since 2026.1)** → per
  `serialversionuid-bump-policy`, **NO UID bump, NO new bwc reader.** Retain the released readers
  `SortIndexStoragePartSerializer_2025_5` / `_2026_1`. `[VERIFY]` the current format has not shipped in a
  released minor since 2026.1 (if it has, this becomes a released-boundary change needing a bump + reader,
  as Step 5 did).
- **Self-heal:** the released readers produce a legacy part carrying the full flat arrays; the loader's
  legacy branch builds the owned/shared `InvertedIndex` from those arrays (owner) or ignores them (view,
  rebuilds from the shared tree). On the next reflush the new slim/paged shape is written. Mirrors the
  foldable-unique self-heal precedent.
- **Loader branch:** `AttributeIndexLoader` SORT path dispatches legacy-flat (build tree from arrays) vs
  new-SINGLE/PAGED/SLIM (assemble tree, reconstruct positional façade).

---

## 7. Tests

1. Owner paging round-trip (real OffsetIndex): SINGLE, PAGED, freed-page-on-merge, PAGED→SINGLE collapse —
   clone `ReferenceTypeCardinalityIndexPagingRoundTripTest`.
2. View slim round-trip: persist slim → reload → `sortedRecords` reconstructed byte-for-byte equals
   pre-flush (`getArray()` identity).
3. Reconstruction fidelity: for owner and view, reassembled `sortedRecords`/`positions`/`allRecords`
   identical to pre-flush; `valueIndex` coherent.
4. Churn assertion (instrumented flush): single-record commit at a large index writes pages (KB), not the
   whole part — the de-amplification proof.
5. BWC golden: a released-minor (2025.5 / 2026.1) byte-23 part self-heals to slim/paged on reflush;
   `EvitaBackwardCompatibilityTest` 4/4.
6. Existing `SortIndexTest` / `SortIndexViewModeTest` / `AttributeIndexTest` / order-by functional + soak
   green unchanged.

---

## 8. Sequencing

1. **P0** — `SortIndex.appendStorageParts` + `AttributeIndex` switch; SINGLE shape only (behaviour-neutral,
   establishes the path). Green gate.
2. **I-B** — view slim + reconstruct-from-shared + loader ordering. Smallest, de-risks the reconstruction.
   Green gate.
3. **I-C** — owned `InvertedIndex` + owner PAGED persistence + reconstruct-from-owned + BWC. Green gate.
4. Full sweep (functional `attribute & sort`, reference-engine, BWC 4/4, soak) + churn assertion.

---

## 9. Open design choice (recommend I-C-2)

- **I-C-2 (recommended):** owner owns an `InvertedIndex`, keeps the positional `sortedRecords` façade in
  memory. Max reuse of §3 paging + the `SortIndexView` value-side hooks; accepts ~1× extra records in owner
  heap (fine per the churn-only call).
- **I-C-1 (rejected):** keep `sortedValues` AND add a separate owned posting tree only for persistence —
  double maintenance, no upside over I-C-2.
- **I-C-3 (future, out of scope):** back owner fully onto the `InvertedIndex`, drop the in-memory positional
  façade, derive Layer-2 arrays from buckets at supplier build. Removes the extra heap copy but rewrites the
  mutation path (`addRecordInternal`/`computePreviousRecord`) and the producer model — revisit only if the
  owner heap copy ever becomes a measured problem.

---

## 10. Must-verify checklist (before/during impl)

- `[V1]` `AttributeIndexLoader` loads FILTER (`sharedValueIndex`) before SORT; `sharedSupplier` resolves a
  populated tree at view load (§3).
- `[V2]` `InvertedIndex` accepts `ComparableArray` keys + the combined/ DESC / NULLS comparator and never
  assumes ascending-natural beyond the comparator for the iteration/cardinality surface owner uses.
- `[V3]` Whether the existing inverted bucket-tree leaf part can back the owned owner stream directly, or a
  sort-keyed leaf part is required.
- `[V4]` byte-23 format has not shipped in a released minor since 2026.1 (governs UID-bump need).
- `[V5]` No caller of `SortIndex.createStoragePart` survives after the switch (migration/tests).
