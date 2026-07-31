# SortIndex I-C — paged OWNER-mode sort index (shape I-C-2)

**Status:** plan ready, awaiting critical review + go. P0 + I-B DONE/green/uncommitted. This doc is the concrete
I-C implementation plan (from the Plan agent, 2026-06-29) + the open decisions.

## Goal
Eliminate the per-commit whole-part rewrite for OWNER-mode sort indexes (`OwnerSortIndex`: sort-only single
attributes + ALL sortable compounds). Today the owner persists the full flat `sortedRecords` int[] + distinct
values + sparse cardinalities, all rewritten on any dirty commit (~40MB @10M). I-C-2: replace the value side
(`sortedValues : TransactionalObjectBPlusTree<value,cardinality>`) with an OWNED `InvertedIndex` (value→records),
persist via the EXISTING §3 leaf-page paging, drop the flat arrays, reconstruct `sortedRecords` at load by bucket
concatenation (reuse `SortIndexView.reconstructSortedRecords` from I-B). Accept ~1× extra owner heap (positional
façade stays resident).

## Resolved design questions
- **[V2] normalizer/comparator:** base `addRecord` normalizes via `effectiveNormalizer()` BEFORE the value-side
  hook (and wraps compounds into `ComparableArray`), so the owned `InvertedIndex` is built with an IDENTITY
  normalizer (`v -> (Serializable) v`) + `this.comparator` (combined, direction+NULLS). `effectiveNormalizer()`
  STAYS mode-specific (owner→`this.normalizer`, view→shared); `effectiveComparator()` can unify to
  `valueTreeOrNull()!=null ? valueTreeOrNull().getComparator() : this.comparator`. `ComparableArray` satisfies the
  `Comparable` key bound and is ordered only by the supplied comparator (its `compareTo` fails fast).
- **plainType for owned tree:** `comparatorBase.length==1 ? comparatorBase[0].type() : ComparableArray.class`.
  Comparator is never natural-order (Nulls*Wrapper), so `ValueColumnFactory` gates out numeric/temporal columns →
  single String gets front-coded column, everything else (scaled-BigDecimal Integers, compounds) gets boxed. Loader
  must pass the SAME plainType (derive from comparatorBase). Zero-risk fallback: `Comparable.class` always (boxed,
  loses only String front-coding).
- **[V3] leaf part:** NEW `SortIndexLeafPagePart` + `SortIndexLeafPageRemoval` + NEW serializer that UNWRAPS
  `ComparableArray` components (mirror `SortIndexStoragePartSerializer.writeComparableValue/readComparableValue`),
  carrying `comparatorBaseLength`. REUSE existing `LeafStreamKey` with `new AttributeKeyWithIndexType(key, SORT)`
  (disjoint stream id from FILTER). NO `ComparableArray` Kryo registration needed. Rationale: avoids registering
  ComparableArray, avoids conflating FILTER/SORT record classes, matches existing SORT serialization convention.
- **SINGLE on-disk shape:** KEEP today's flat-array delta-encoded representation for SINGLE (small/single-leaf
  owner) — keeps `Migration_2026_2` working unchanged, reuses the efficient serializer; PAGED only for multi-leaf
  owners where churn matters.

## OPEN DECISION — serialVersionUID
Plan agent recommends BUMP `SortIndexStoragePart` UID (fail-loud on stale 2026.2-dev), NO new reader, keep
`_2025_5`/`_2026_1`. **BUT** standing policy ([[serialversionuid-bump-policy]]): NO bump/reader for intra-dev
(2026.2 unreleased) format changes. byte-23 already has `_2026_1` reader (shipped in released 2026.1 via the slim
serializer commit `503c851b6`); current head = unreleased 2026.2-dev. I-C changes 2026.2-dev → 2026.2-dev' (still
intra-dev). RESOLUTION (default): FOLLOW POLICY → NO bump, NO reader. Stale 2026.2-dev catalogs misread (acceptable:
disposable dev catalogs, not in the BWC test which uses released 2025.5/2026.1). Revisit if Johnny prefers fail-loud.

## File-by-file
NEW: (1) `SortIndexLeafPagePart` (mirror `FilterIndexLeafPagePart`, +`comparatorBaseLength`); (2)
`SortIndexLeafPageRemoval` (mirror `FilterIndexLeafPageRemoval`, no serializer); (3) `SortIndexLeafPagePartSerializer`
(mirror `FilterIndexLeafPagePartSerializer` but unwrap compound value via writeComparableValue/readComparableValue).
MOD: (4) `SortIndexStoragePart` +PAGED/SINGLE discriminator (`paged`, `highWaterPageSequence`, `leafPageSequences`),
`paged(...)` factory, `isPaged()`, keep all ctors defaulting SINGLE; (5) `SortIndexStoragePartSerializer` head
write/read discriminator (PAGED→highWater+leafPageSeqs; !paged→existing valuesPresent path); (6) `SortIndex` base:
add `valueTreeOrNull()` abstract; hoist value-side READS to base over `valueTreeOrNull()` (getValueCardinality/
valuePresent/valueCount/valueCursor/valueReverseCursor/getSortedRecordValues/preRemovalCardinality/
effectiveComparator/valuePresentForRemoval via `sortedRecords.indexOf`), hoist `InvertedIndexValueCursor`; keep
`effectiveNormalizer()` abstract; write hooks gain `recordId`; add `resetValueSideDirty()`; `appendStorageParts`→
`doAppendStorageParts` abstract; (7) `OwnerSortIndex`: field `sortedValues`→`ownedTree:InvertedIndex` (identity
normalizer); hooks→`ownedTree.addRecord/removeRecord(value,recordId)`; `buildTree` bridge (legacy flat arrays→tree);
`copyWithMergedValueSide`→`getStateCopyWithCommittedChanges(ownedTree)` (auto publishStaged); `doAppendStorageParts`
mirror `OwnerUniqueIndex.appendStorageParts`/`FilterIndex.appendBucketAxis` (PAGED: collectChangedPages→leaf parts +
removals + paged root; SINGLE: remove prior pages + forgetPageStream + flat-array root); static `fromPersistedPages`
(InvertedIndex.fromPersistedPages → reconstructSortedRecords → adopt ctor); delete Tree*Cursor; (8) `SortIndexView`:
`valueTreeOrNull()`→sharedTree, drop hoisted reads, hooks→`(value,recordId)` no-ops, `doAppendStorageParts`→slim,
`reconstructSortedRecords` unchanged; (9) `AttributeIndexLoader.fetchSort`: view branch unchanged; owner PAGED→read
leaf pages by `LeafStreamKey(SORT)`→`OwnerSortIndex.fromPersistedPages`; owner SINGLE/legacy→flat-array buildTree
bridge; (10) `IndexStoragePartConfigurer` register `SortIndexLeafPagePart` (new id, append last); (11)
`IndexStoragePartRegistry` add `byte 42 SortIndexLeafPagePart`; (12) `Migration_2026_2` NO change (re-verify).
TESTS: (13) NEW `SortIndexOwnerPagingRoundTripTest` clone `ReferenceTypeCardinalityIndexPagingRoundTripTest`
(SINGLE/PAGED/freed-page-on-merge/PAGED→SINGLE collapse/reconstruction/compound-owner/churn); (14) NEW
`SortIndexLeafPagePartSerializerTest` (scalar+compound); (15) UPDATE `SortIndexStoragePartSerializerTest` (PAGED+SINGLE
discriminator); (16) UPDATE `SortIndexTest`/`SortIndexViewModeTest`/`EntityIndexRoundTripTest`/`Migration_2026_2_Test`.

## Critical-review corrections (GO-WITH-CHANGES, 2026-06-29) — FOLD IN
- **C1 (step split):** step 1 = SCAFFOLDING ONLY (add abstract `doAppendStorageParts`, `valueTreeOrNull()`,
  `resetValueSideDirty()`; hooks gain `recordId` ignored by owner; **owner KEEPS its B+tree read overrides**; view reads
  hoist immediately since it already wraps an InvertedIndex). step 2 = swap owner→InvertedIndex AND hoist owner reads TO
  the base TOGETHER. As originally worded step 1 was not behaviour-neutral (`valueTreeOrNull()` is an InvertedIndex the
  owner lacks until step 2).
- **C2 (defensive):** do NOT hoist `getValueCardinality` to a base `Math.max(cardinalityOf,1)` — owner must KEEP its
  throw-on-miss guard (`OwnerSortIndex` broken-invariant), view floors to 1. Keep mode-specific (abstract) — CLAUDE.md
  "never silently skip unexpected states".
- **C3 (loader legacy):** SINGLE/legacy owner load must ADOPT the persisted `sortedRecords` DIRECTLY (current rehydrate
  ctor), NOT reconstruct-from-tree. A migration-collapsed legacy part (2 raw BigDecimals → 1 scaled int) merges into one
  owned-tree bucket, so reconstruct-from-tree would shift the byte layout. Reconstruct-from-tree is correct ONLY for the
  new PAGED path (collapse already merged at write time).
- **C4 (invariant):** `OwnerSortIndex` must NOT own its own `PageStreamRegistry` — the auto-publish works ONLY because
  the registry lives on the owned `InvertedIndex` (committed via `getStateCopyWithCommittedChanges(ownedTree)` →
  `InvertedIndex.createCopyWithMergedTransactionalMemory:916` publishes). Mirroring OwnerUniqueIndex's explicit registry
  would make publish silently never fire.
- **C5 (NIT):** plainType = `comparatorBase.length==1 ? comparatorBase[0].type() : Comparable.class` (compound
  `ComparableArray.class` is cosmetic — ValueColumnFactory falls to boxed either way; only single String front-codes).
- **C6 (note):** bulk/warm-up has no `createCopy` → no auto-publish, identical to every existing paged index (sequences
  live on leaf nodes, live catalog reloads fresh). Owner introduces no bulk publish path of its own.
- **UID — DECIDED (Johnny 2026-06-29): NO BUMP (follow policy).** Keep `SortIndexStoragePart` serialVersionUID
  `5192847362910473829L`; keep `_2025_5`/`_2026_1` readers. Stale unreleased 2026.2-dev catalogs may misread (accepted —
  disposable dev catalogs); released catalogs unaffected; BWC test green.

## Sequence (gated)
1. SCAFFOLDING ONLY (C1): abstract `doAppendStorageParts`/`valueTreeOrNull`/`resetValueSideDirty`, hooks+recordId
   (owner ignores), owner KEEPS B+tree reads, view reads hoist. GATE: SortIndexTest/SortIndexViewModeTest/EntityIndexRoundTripTest green.
2. Swap owner value side → InvertedIndex AND hoist owner reads to base (C1); keep `getValueCardinality` mode-specific
   throw-on-miss (C2); SINGLE-only persist. GATE: same green.
3. New parts+serializers + PAGED emission (UID per policy=no bump). GATE: serializer round-trips green.
4. Loader PAGED/SINGLE/legacy + fromPersistedPages. GATE: SortIndexOwnerPagingRoundTripTest green.
5. BWC + migration. GATE: `_2026_1`/`_2025_5` self-heal + Migration_2026_2_Test + EvitaBackwardCompatibilityTest 4/4.
6. Full regression: indexing|storage|serialization functional + broad.

## Precedents
`OwnerUniqueIndex` (near-exact standalone-owner paging precedent), `FilterIndex.appendBucketAxis`, Step-5
`ReferenceTypeCardinalityIndex*` file set, `InvertedIndex.fromPersistedPages`/`collectChangedPages`/`livePageSequences`/
`forgetPageStream`.

## Open decisions for Johnny
1. UID bump (policy=no-bump-intra-dev DEFAULT vs Plan's fail-loud bump).
2. plainType (recommended real-type/ComparableArray vs zero-risk Comparable.class-always).
3. SINGLE on-disk shape (recommended keep-flat-array vs uniform inline ValueToRecordBitmap[]).
4. Heap ~1× doubling acceptance (already accepted by Johnny per churn-only decision).

## Phase B critical review (2026-06-29) — VERDICT GO-WITH-CHANGES — FOLD IN
No hard correctness blocker. Plan faithfully mirrors FilterIndex.appendBucketAxis / OwnerUniqueIndex.appendStorageParts /
Step-5 RefTypeCardinality. MUST-FIX (blocking):
- **B1 (discriminator encoding).** SORT has THREE states (owner-SINGLE, owner-PAGED, view-slim) + a PRE-EXISTING
  `valuesPresent` boolean + NO UID bump — unlike RefTypeCard (2 states, no bool, UID bumped because its inline format
  shipped in released 2026.1). Do NOT copy RefTypeCard literally. **DECISION (gated encoding, Johnny-default):** write the
  new `paged` boolean ONLY in the `valuesPresent==false` branch. Layout: `writeBool(valuesPresent)`; if true → existing
  owner-SINGLE body (values/cardinalities/sortedRecords) UNCHANGED ⇒ owner-SINGLE bytes byte-identical to current head;
  else → `writeBool(paged)`; if paged → highWaterPageSequence + leafPageSequences; else → view-slim (empty). Trailing
  `indexedDecimalPlaces` varint emitted in BOTH outer branches (as today, serializer ~L137). Released/BWC safe regardless
  (released uids dispatch to frozen `_2025_5`/`_2026_1`, never reach new branch). Gated shrinks stale-2026.2-dev blast
  radius to ~0: owner-SINGLE re-reads CORRECTLY; only intra-dev view-slim + brand-new owner-PAGED change bytes; failures
  stay LOUD (premise violation) not OOM. `_2025_5`/`_2026_1` readers stay byte-for-byte UNTOUCHED.
- **B2 (fromPersistedPages parity).** OwnerSortIndex.fromPersistedPages must pass to InvertedIndex.fromPersistedPages the
  IDENTICAL identity normalizer (`v->(Serializable)v`), the SAME plainType derivation (`comparatorBase.length==1 ?
  comparatorBase[0].type() : Comparable.class`) and the COMBINED comparator (dir+NULLS) used by `createOwnedTree`. Persisted
  leaf values are already normalized; fromPersistedPages replays raw; a non-identity normalizer double-normalizes, wrong
  plainType picks wrong key column (ClassCast / lost front-coding).
- **B3 (leaf part + bespoke serializer).** `SortIndexLeafPagePart` MUST carry `comparatorBaseLength` (field). Bespoke
  `SortIndexLeafPagePartSerializer` MANDATORY (not optional): ValueToRecordBitmapSerializer.write uses
  `kryo.writeClassAndObject` on the bucket value and ComparableArray is registered NOWHERE in Kryo → reusing
  FilterIndexLeafPagePartSerializer (writeObject) garbles compounds. Unwrap via writeComparableValue/readComparableValue
  (SortIndexStoragePartSerializer ~L225-267), driven by comparatorBaseLength (Kryo serializers are stateless/global).
- **B4 (collapse order).** PAGED→SINGLE collapse: enumerate `ownedTree.livePageSequences()` and emit a
  SortIndexLeafPageRemoval for each BEFORE `ownedTree.forgetPageStream()` (mirror FilterIndex.appendBucketAxis else-branch /
  OwnerUniqueIndex.appendStorageParts:357-361). Forget-before-enumerate leaks every prior leaf page into the OffsetIndex.
  Drive off the OWNED TREE, never a SortIndex-local registry (C4).

SHOULD-FIX: loader fetchSort branches on `part.isPaged()` for owner ONLY (legacy/SINGLE keep adopt-direct
`getSortedRecords()`, AttributeIndexLoader ~L498-500); PAGED branch mirrors loadInvertedIndex (~L372-399) resolving stream
id via `LeafStreamKey(eipk, new AttributeKeyWithIndexType(key, SORT))`. Doc nit: IB-IC §5 "Kryo registration for new stream
key" is SUPERSEDED — REUSE LeafStreamKey (LeafStreamKeySerializer round-trips any AttributeKeyWithIndexType+StreamKind);
reuse StreamKind.BUCKET (disjointness from indexType=SORT, not kind).

VERIFIED-SOUND: C4 auto-publish (copyWithMergedValueSide:438-439 → getStateCopyWithCommittedChanges(ownedTree) →
InvertedIndex.createCopyWithMergedTransactionalMemory publishStaged @916; owner owns NO registry); C6 bulk/warm-up
reload-fresh; C3/Q2 reconstruct==façade byte-for-byte (computePreviousRecord inserts ascending-within-block, same codepath
as green I-B view reconstruct; migration-collapse legacy excluded because non-paged→adopt-direct); Q4 stream-id disjoint +
compound handled; Q5 byte 42 free (41=RefTypeCard last), configurer append-last before `index<700` assert, LeafPageRemoval
(DeferredRemovalStoragePart) needs NO registry/configurer entry; [V1] FILTER-before-SORT load order; no-bump released/BWC safe.

OPEN→RESOLVED/ACTION: (a) **Warm-up/reload soak** — owner-PAGED is a NEW paged producer on the same stage→publish
handshake as the [[strand-rootcause-persistence-baseline]] bug; ADD a test exercising owner-paged SORT across warm-up flush
+ reload (not covered by listed round-trip tests). (b) **Encoding** — DECIDED gated (above). (c) **Migration_2026_2** —
"no change" holds: migrated owner parts are non-paged, write valuesPresent=true → never paged branch; confirm with one-line
test note.
