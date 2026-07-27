# Issue #760 Part B — SortIndex slim format — Implementation Plan (GATE OPEN)

> **CORRECTION (2026-06-23, post-implementation):** SortIndex's pre-slim format
> (`serialVersionUID = 5192847362910473829L`) was a **2026.2-dev** format (NOT in `release_2026-1`,
> which carries `-7076…`). Per the project policy ([[serialversionuid-bump-policy]]) we do NOT bump the
> UID or add a backward-compatible reader for a format change WITHIN the current (unreleased) dev
> version. So the recipe below is AMENDED: the serializer was changed **in place** keeping
> `@Serial = 5192847362910473829L`; there is **no `SortIndexStoragePartSerializer_2026_2`** and **no UID
> bump**. The bwc chain keeps only the released-minor readers `_2025_5` (6163295675316818632L) +
> `_2026_1` (-7076092972784353868L). Ignore steps 1/3 of the recipe and the `_2026_2` test below.

Supersedes the design+verdict in `2026-06-22-issue-760-partB-sortindex-slim.md`. The GATE re-measure
(2026-06-23, `SenesiSlimSizeProbe` on real senesi) **cleared the M1 blocker**: all 18,038 persisted
`SortIndexStoragePart` parts are **owner-mode (0 view-mode)**, the largest included — so block-delta
(owner-only) targets real, slim-able data. SortIndex slim is now **GO**, with B1 + B2 resolved below.

**No commit without Johnny's permission. Build ONLY with `/tmp/apache-maven-3.9.9/bin/mvn`.** Same
serializer-evolution pattern as the committed Phase 1a (`4c669a1`); fully verifiable offline.

## Verified current state (from source, 2026-06-23)
- `SortIndexStoragePart` `@Serial = 5192847362910473829L` (evita_engine `.../storageParts/index/`).
- Current serializer `SortIndexStoragePartSerializer` ALREADY carries the `valuesPresent` owner/view
  marker and writes `sortedRecords` RAW via `output.writeInts(arr,0,len)`. There is **no** `_2026_2`
  reader yet (only `_2025_5` + `_2026_1`).
- Configurer (`IndexStoragePartConfigurer` ~L97-103): current serializer + bwc
  `_2025_5 = 6163295675316818632L`, `_2026_1 = -7076092972784353868L` — **keep both**.
- `valueCardinalities` is **SPARSE** — only values with cardinality > 1 are stored (the "cardinality 1
  is implied" convention; confirmed in `SortIndexStoragePart` field javadoc and
  `Migration_2026_2` L626 `getOrDefault(rawValue, 1)`). → block length is
  `cardinalities.getOrDefault(value, 1)`, NOT `cardinalities.get(value)`. **(B1)**
- `Migration_2026_2.rekeySortOnlyIndex` (L384-442): re-keys raw-`BigDecimal` sort-only parts to scaled
  int, **summing** cardinalities of values that collapse to the same scaled int, while keeping
  `part.getSortedRecords()` **unchanged** (L434). A collapsed block is therefore the concatenation of
  two internally-ascending runs and can be **non-ascending across the seam**. **(B2)**

## Current write() wire order (after the 8-byte UID prefix)
1. `entityIndexPrimaryKey` : `writeInt`
2. `storagePartPK` : `writeVarLong(true)`
3. `attributeIndexKey` id : `writeVarInt(true)`
4. `comparatorBase`: length `writeVarInt(true)`, then per source `writeClass(type)` +
   `writeObject(orderDirection)` + `writeObject(orderBehaviour)`
5. **`sortedRecords.length` `writeVarInt(true)` + `writeInts(sortedRecords,0,len)` (RAW)**
6. `valuesPresent` : `writeBoolean` (= `sortedRecordValues.length > 0`)
7. if `valuesPresent`: values section (single → N `writeClassAndObject`; compound → N×base
   `writeClassAndObject` over `ComparableArray`) + cardinalities section (`size` varint, per entry
   key(s) + `writeVarInt(count,true)`)
8. `indexedDecimalPlaces` : `writeVarInt(true)` (trailing)

## New slim wire order (NEW @Serial; old format read by `_2026_2`)
Steps 1-4 unchanged. Then:
5. **`valuesPresent` : `writeBoolean`** (moved up — the reader needs block lengths before decoding).
6. if `valuesPresent`: values section + cardinalities section — **byte-identical encoding to today**,
   only relocated earlier.
7. **`sortedRecords`:**
   - **Owner mode (`valuesPresent == true`):**
     - derive `blockLengths[i] = cardinalities.getOrDefault(sortedRecordValues[i], 1)` **(B1)**;
       assert `sum(blockLengths) == sortedRecords.length` (else throw — damaged part).
     - compute `allAscending` = every block's run is **non-decreasing** within `[offset, offset+len)`
       using the predicate `arr[k] >= arr[k-1]` — **byte-for-byte the same `>=` predicate the codec's
       internal assert uses** (`SortedIntArrayCodec` non-decreasing assert), so the RAW-fallback
       decision and the codec assert can never disagree (review finding, major).
     - `blockDeltaEncoded` : `writeBoolean` (= `allAscending`) **(B2)**.
     - `sortedRecords.length` : `writeVarInt(true)` (cheap read-side sanity check).
     - if `blockDeltaEncoded`: for each block
       `SortedIntArrayCodec.writeAscendingRun(output, sortedRecords, offset, blockLengths[i])`.
     - else (rare migration-collapsed part): `writeInts(sortedRecords,0,len)` RAW.
   - **View mode (`valuesPresent == false`):** `sortedRecords.length` `writeVarInt(true)` +
     `writeInts(...)` RAW — identical to today, no `blockDeltaEncoded` boolean (reader knows from the
     already-read `valuesPresent`). No win, **no regression**.
8. `indexedDecimalPlaces` : `writeVarInt(true)` — unchanged, trailing.

`read()`: header + comparatorBase; read `valuesPresent`; if set, read values + cardinalities (as
today); read `sortedRecords`:
- if `valuesPresent`: read `blockDeltaEncoded`; read `length`; if `blockDeltaEncoded`, derive
  `blockLengths` from `cardinalities.getOrDefault(sortedRecordValues[i], 1)`, **assert
  `sum(blockLengths) == length`** (defensive throw on mismatch — damaged part, NOT a silent ignore;
  review finding, minor), and fill the array block-by-block via
  `SortedIntArrayCodec.readAscendingRun(input, dst, offset, len)`; else `readInts(length)`.
- else: `readInts(length)`.
Read `indexedDecimalPlaces`. Construct the **same** `SortIndexStoragePart` (loader untouched).

## New codec methods (extend `SortedIntArrayCodec`)
- `static void writeAscendingRun(Output out, int[] arr, int from, int len)` — zig-zag `arr[from]`,
  then `len-1` unsigned gaps; **no count prefix** (length known from cardinality). `len == 0` writes
  nothing. Debug-assert non-decreasing over `[from, from+len)`.
- `static void readAscendingRun(Input in, int[] dst, int from, int len)` — inverse; fills
  `dst[from .. from+len)`. `len == 0` no-op.
The existing count-prefixed `writeAscendingInts/readAscendingInts` stay for Facet/Hierarchy/PriceRef.

## Recipe (same as Phase 1a)
1. Copy current `SortIndexStoragePartSerializer` → `SortIndexStoragePartSerializer_2026_2`
   (`@Deprecated(forRemoval = true)`; `read()` = current format verbatim — it MUST keep using the
   **8-arg canonical `SortIndexStoragePart` constructor** that carries `indexedDecimalPlaces`, NOT the
   legacy 6/7-arg constructors that default places to 0, else lazy-upgraded parts silently lose their
   frozen scale (review finding, minor); `write()` **THROWS**
   `new UnsupportedOperationException("This serializer is deprecated and should not be used.")` — the
   read-only convention locked in this branch).
2. Rewrite the current `SortIndexStoragePartSerializer` to the slim format above.
3. Bump `SortIndexStoragePart` `@Serial` to a fresh long literal distinct from
   `5192847362910473829L`, `6163295675316818632L`, `-7076092972784353868L`.
4. `IndexStoragePartConfigurer`: keep `_2025_5` + `_2026_1`; add
   `.addBackwardCompatibleSerializer(5192847362910473829L, new SortIndexStoragePartSerializer_2026_2(this.keyCompressor))`.

## Tests
- Codec (`SortedIntArrayCodecTest`): run round-trips — empty (len 0), single, long ascending block,
  block with first id ≥ 2^28 (5-byte zig-zag), and a large-but-realistic positive span. NOTE the
  gap `current - previous` is computed in `int` (inherited from `writeAscendingInts`); a span across
  the full `Integer.MIN..MAX` overflows it. Sort record ids are real positive entity PKs with small
  spans, so this cannot occur — the test must assert the REALISTIC range, NOT expect a full-range gap
  to round-trip (review finding, minor; `Integer.MIN_VALUE` is only a sentinel in
  `SortIndexChanges.computePreviousRecord`, never a stored id).
- Serializer (`SortIndexStoragePartSerializerTest`): owner low-card (few values, long blocks → win);
  owner high-card (all singletons → degrades to per-element, must not corrupt); compound (base > 1,
  `ComparableArray` values + cardinality keys); **view-mode (`valuesPresent == false`) raw**; empty;
  single record; scaled-int BigDecimal values + `indexedDecimalPlaces` preserved; **B2: a part whose
  cardinalities imply a non-ascending merged block → `blockDeltaEncoded == false` RAW fallback round-
  trips** (build the part directly so the block is non-ascending); lazy-upgrade: decode a golden
  `_2026_2`-wire blob (and a `_2026_1` blob) via the production dispatcher and assert the reconstructed
  part equals the original (reuse `StoragePartSerializerTestSupport` golden-encoder pattern). NOTE
  `SortIndexStoragePart` has no custom `equals` → compare field-by-field (sortedRecords, values,
  cardinalities, comparatorBase, indexedDecimalPlaces, keys).
- **Migration→slim-write→reload E2E (highest-risk path, review finding):** take a `_2026_2`/`_2026_1`
  golden raw-`BigDecimal` sort part whose scaled-int collapse produces a NON-ascending merged block,
  run it through `Migration_2026_2.rekeySortOnlyIndex` (or `rekeySortedValuesToScaledInt` + a real
  `putStoragePart`/reload), and assert the reloaded `sortedRecords` are byte-identical and the part
  took the `blockDeltaEncoded == false` RAW path. A unit round-trip of a hand-built non-ascending part
  is NOT enough — exercise the actual migration write through the slim serializer.
- Behavioural: `SortIndexTest` + any owner/view sort test green.

## Risk register
1. **Reorder correctness** — marker+values+cardinalities now precede `sortedRecords`. New @Serial; old
   wire read only by `_2026_2`. Confirm `read()` reconstructs identical fields (round-trip tests).
2. **B1** — sparse cardinalities: `getOrDefault(value, 1)` on BOTH derive sites; never `get()`.
3. **B2** — non-ascending merged block: the per-block ascending check + `blockDeltaEncoded` RAW
   fallback. The check is WITHIN each block (cross-block boundaries are irrelevant — delta resets per
   block). Open the migration-collapsed case explicitly in a test.
4. **Value order vs map order** — block order follows `sortedRecordValues`, NOT cardinalities
   map-iteration order. Use the values array.
5. **Huge-PK singleton blocks** — zig-zag of a first id ≥ 2^28 is 5 B vs raw 4 B (+1 B). Only bites
   singleton blocks with very large ids; multi-element blocks always win. Rare; accepted.
6. **`indexedDecimalPlaces`** — keep trailing & untouched.
7. **bwc** — keep `_2025_5` + `_2026_1`; new UID distinct; `_2026_2` read() byte-identical to current
   write(); `_2026_2` write() throws.

## Out of scope
View-mode block-delta (needs the shared filter's block info at serialize time — not in the part).
Structural §9 steps (gated on a HEAP re-measure + Johnny's oversight).
