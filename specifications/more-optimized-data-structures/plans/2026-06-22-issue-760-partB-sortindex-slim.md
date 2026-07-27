# Issue #760 Part B — SortIndex slim format (delta-within-block) — Detailed Plan

Follow-up to Phase 1a (`2026-06-22-issue-760-partB-phase1a-detailed.md`). Implements the SortIndex
slimming that Phase 1a deferred (block-sorted ⇒ needs delta-WITHIN-block, plus the serializer carries
a `valuesPresent` marker + trailing `indexedDecimalPlaces`). Same serializer-evolution pattern,
fully verifiable offline. **No commit without Johnny's permission. Build with
`/tmp/apache-maven-3.9.9/bin/mvn` only.**

## ⛔ REVIEW OUTCOME — NO-GO (NOT implemented; gated on the GATE re-measure)

An adversarial review verified this plan against source and returned **NO-GO**. During the autonomous
overnight run I therefore **did not implement it** (forcing a NO-GO change onto a load-bearing on-disk
format unattended is exactly what the review gate exists to prevent). Three findings, all
code-proven — resolve before any implementation, and only after the re-measure (M1) justifies it:

- **B1 (blocker, easy):** `valueCardinalities` is a **SPARSE** map — only values with cardinality > 1
  are stored (`SortIndexStoragePart` field javadoc; `OwnerSortIndex.materializeCardinalities` writes
  only `if (cardinality > 1)`). So `cardinalities.get(sortedRecordValues[i])` is **null for every
  singleton block** (the common case), and this plan's "assert each non-null" is backwards. Fix:
  `cardinalities.getOrDefault(value, 1)` on both write and read (the convention used everywhere else,
  e.g. `OwnerSortIndex.buildTree`, `Migration_2026_2`). The length identity is
  `sum over sortedRecordValues of getOrDefault(value,1) == sortedRecords.length`, NOT
  `sum(cardinalities.values())`.
- **B2 (blocker, serious — breaks "serializer-only"):** `Migration_2026_2.rekeySortOnlyIndex`
  collapses distinct raw BigDecimals that scale to the same int, **summing their cardinalities but
  leaving `sortedRecords` unchanged**. The merged block can be **non-ascending** across the former
  boundary (value `1.001`→`[5,9]`, `1.002`→`[2,7]`, both → scaled `100` ⇒ merged block `[5,9,2,7]`).
  Block-delta's ascending-assert would then **throw during migration** (or, asserts off, silently
  corrupt). Existing `Migration_2026_2_Test.shouldMergeCollidingSortValues` only checks the value
  array + cardinality map, never `sortedRecords` ordering — so it would NOT catch this.
  - *Possible serializer-only sidestep* (keeps the migration untouched): add a single per-part
    boolean `blockDeltaEncoded` — if **all** blocks are ascending, write `true` + block-delta; if any
    block is non-ascending (the rare migration-collapsed part), write `false` + RAW whole array. One
    boolean of overhead; the rare bad part degrades to raw instead of throwing. This also likely
    surfaces a **pre-existing latent question**: does a non-ascending persisted block break
    within-block binary search on load today, or does load rebuild/re-sort the block? Investigate
    before relying on the sidestep.
- **M1 (major — the real gate):** the **large** `sortedRecords` parts are probably **view-mode**
  (both-filterable-and-sortable attributes: name, price, timestamps), which this design leaves RAW —
  so block-delta (owner-mode only) likely slims the parts that matter least. **Re-measure the
  owner/view split of the large SortIndex parts at the GATE before implementing.** There is a real
  chance the right call is to defer SortIndex entirely (as Phase 1a already did) or to instead pursue
  view-mode slimming (which needs the shared FilterIndex bucket sizes at serialize time).

**Recommendation:** keep SortIndex deferred until (1) the GATE re-measure shows owner-mode parts are
worth it, and (2) B1 + B2 are resolved (B2 via the migration re-sort fix *or* the serializer-only
`blockDeltaEncoded` fallback). The rest of this document is the (corrected-in-principle) design, kept
for when that gate opens.

## Goal
Shrink the persisted `sortedRecords` `int[]` of `SortIndexStoragePart`. It is **not** globally sorted
— it is a concatenation of per-value **blocks**, each block ascending by record id. So global delta
is wrong; we delta **within each block** and reset at block boundaries.

## Verified current format (from source)
`SortIndexStoragePart` `@Serial = 5192847362910473829L`. Serializer
`evita_store/evita_store_server/.../store/index/serializer/SortIndexStoragePartSerializer.java`.
Existing bwc readers in `IndexStoragePartConfigurer` (lines ~100-101): `_2025_5 =
6163295675316818632L`, `_2026_1 = -7076092972784353868L` — **keep both**.

Current `write()` wire order (after the 8-byte UID prefix):
1. `entityIndexPrimaryKey` : `writeInt`
2. `storagePartPK` : `writeVarLong(true)`
3. `attributeIndexKey` id : `writeVarInt(true)`
4. `comparatorBase.length` : `writeVarInt(true)`, then per source `writeClass(type)` +
   `writeObject(orderDirection)` + `writeObject(orderBehaviour)`
5. **`sortedRecords.length` : `writeVarInt(true)`, then `writeInts(sortedRecords,0,len)` (RAW 4-byte)**
6. **`valuesPresent` : `writeBoolean`** (`= sortedRecordValues.length > 0`)
7. if `valuesPresent`: `sortedRecordValues.length` varint + per value `writeClassAndObject`
   (single comparator) or N `writeClassAndObject` (compound `ComparableArray`); then
   `cardinalities.size()` varint + per entry { key (writeClassAndObject ×N) , `writeVarInt(count,true)` }
8. `indexedDecimalPlaces` : `writeVarInt(true)` (TRAILING)

`read()` mirrors; when `!valuesPresent` it sets `sortedRecordValues = new Serializable[0]`,
`cardinalities = Map.of()`.

### Block structure (verified in `SortIndex.java`)
- `sortedRecords` field javadoc: "divided in record-id blocks that respect the index value ordering;
  record ids within the same block are sorted naturally by their integer id."
- Block **order** follows `sortedRecordValues` (the distinct values in comparator order).
- Block **length** for block `i` = `cardinalities.get(sortedRecordValues[i])` (always ≥ 1).
- `sum(block lengths) == sortedRecords.length`.
- **Owner vs view:** owner-mode (sort-only / compound) parts carry values + cardinalities
  (`valuesPresent == true`); view-mode (filterable+sortable) parts omit them (re-derived from the
  shared `FilterIndexStoragePart` at load) — so a view part does **not** contain the block lengths.

## Design (conservative: win where safe, never regress)

New slim wire order — **move the marker + values + cardinalities BEFORE `sortedRecords`** so the
reader knows the block lengths before decoding the array:
1. `entityIndexPrimaryKey`, `storagePartPK`, `attributeIndexKey`, `comparatorBase` — unchanged.
2. **`valuesPresent` : `writeBoolean`**
3. if `valuesPresent`: values section + cardinalities section — **byte-identical encoding to today**,
   just relocated earlier.
4. **`sortedRecords`:**
   - **Owner mode (`valuesPresent == true`): block-delta.** Derive `blockLengths[i] =
     cardinalities.get(sortedRecordValues[i])`; assert each non-null and `sum == sortedRecords.length`;
     write `sortedRecords.length` (varint, for a cheap read-side sanity check), then for each block
     `SortedIntArrayCodec.writeAscendingRun(output, sortedRecords, offset, blockLengths[i])`.
   - **View mode (`valuesPresent == false`): RAW.** `writeVarInt(len,true)` +
     `writeInts(sortedRecords,0,len)` — identical to today. No win, **no regression**, zero risk.
5. `indexedDecimalPlaces` : `writeVarInt(true)` — unchanged, trailing.

`read()`: read header + comparatorBase; read marker; if marker read values + cardinalities (as today);
read `sortedRecords.length`; if marker, derive `blockLengths` from
`cardinalities.get(sortedRecordValues[i])` and fill the array block-by-block via
`SortedIntArrayCodec.readAscendingRun(input, dst, offset, blockLengths[i])`; else `readInts(len)`.
Read `indexedDecimalPlaces`. Construct the **same** `SortIndexStoragePart` (loader untouched).

### New codec methods (extend `SortedIntArrayCodec`)
- `static void writeAscendingRun(Output out, int[] arr, int from, int len)` — zig-zag first
  (`arr[from]`), then `len-1` unsigned gaps; **no count prefix** (length is known from the
  cardinality). Debug-assert non-decreasing over `[from, from+len)`. `len == 0` writes nothing.
- `static void readAscendingRun(Input in, int[] dst, int from, int len)` — inverse; fills
  `dst[from .. from+len)`. `len == 0` is a no-op.
The existing count-prefixed `writeAscendingInts/readAscendingInts` (Phase 1a) stay for
Facet/Hierarchy/PriceRef.

## Recipe (same as Phase 1a)
1. Copy current serializer → `SortIndexStoragePartSerializer_2026_2` (read + **working** write,
   verbatim current format; class javadoc: reads the pre-slimming 2026.2-dev format, retained for
   backward compatibility, write kept only for lazy-upgrade tests).
2. Rewrite the current `SortIndexStoragePartSerializer` to the new slim format above.
3. Bump `SortIndexStoragePart` `@Serial` to a fresh long literal distinct from
   `5192847362910473829L`, `6163295675316818632L`, `-7076092972784353868L`.
4. `IndexStoragePartConfigurer`: keep `_2025_5` + `_2026_1` lines; add
   `.addBackwardCompatibleSerializer(5192847362910473829L, new SortIndexStoragePartSerializer_2026_2(this.keyCompressor))`.

## Tests (extend `SortIndexStoragePartSerializerTest`, 181 L)
- Owner **low-cardinality** (few values, long ascending blocks → big win) round-trip.
- Owner **high-cardinality** (mostly singleton blocks) round-trip (degrades to per-element; must not
  corrupt).
- **Compound** sort (`comparatorBase.length > 1`, `ComparableArray` values + cardinality keys) — make
  sure block-length lookup via `cardinalities.get(value)` works (ComparableArray equals/hashCode).
- **View mode** (`valuesPresent == false`) round-trip — raw path unchanged.
- **Empty** sort index; single record; a block with a huge first id (≥ 2^28) to exercise zig-zag.
- **Scaled-int BigDecimal** values + `indexedDecimalPlaces` preserved (mirror the existing test).
- **Lazy-upgrade**: decode a `_2026_2`-written blob (and a `_2026_1` blob) via the production
  dispatcher; assert the reconstructed `SortIndexStoragePart` equals the original (use the Phase-1a
  `StoragePartSerializerTestSupport`).
- Behavioural: `SortIndexTest` (+ any owner/view sort test) green.

## Risks / open points (for the critical review)
1. **Reorder correctness** — moving marker+values+cardinalities before `sortedRecords`. Confirm
   nothing else depends on the old order; confirm `read()` reconstructs identical fields.
2. **Block-length derivation** — `cardinalities.get(sortedRecordValues[i])`: relies on value
   equals/hashCode (incl. `ComparableArray` and scaled-int values). Guard null + `sum == length`.
3. **Value order vs map order** — block order is `sortedRecordValues` order, NOT cardinalities
   map-iteration order. Use the values array for ordering.
4. **View-mode parts may be the large ones** — filterable+sortable attributes are common, so the big
   parts could be view-mode (raw, no win). Accept for now (no regression); block-delta still wins the
   owner-mode (sort-only / compound) parts. Magnitude to be confirmed at the GATE re-measure.
5. **Huge-PK singleton blocks** — zig-zag of a first id ≥ 2^28 is 5 bytes vs raw 4 (+1 byte). Only
   bites singleton blocks with very large ids; multi-element blocks always win. Rare; accepted.
6. **`indexedDecimalPlaces` placement** — keep trailing & untouched.
7. **bwc** — keep `_2025_5` + `_2026_1`; new UID distinct; `_2026_2` reader byte-identical to current.

## Out of scope
SortIndex view-mode block-delta (needs the shared filter's block info at serialize time — not
available in the part). Structural §9 steps (EntityIndex eviction, References-per-name, map shard) —
gated on the re-measure + Johnny's oversight.
