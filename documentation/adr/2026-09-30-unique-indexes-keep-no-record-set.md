---
title: Unique indexes keep no record-id set, and a unique value occurs once whatever the locale
date: 2026-09-30
updated: 2026-09-30 10:30
status: accepted
kind: refactor
issues: [1658]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/attribute/OwnerUniqueIndex.java, evita_engine/src/main/java/io/evitadb/index/attribute/GlobalUniqueIndex.java, evita_engine/src/main/java/io/evitadb/index/attribute/UniqueIndex.java, evita_engine/src/main/java/io/evitadb/core/collection/IndexCardinalityProjection.java, evita_api/src/main/java/io/evitadb/api/statistics]
supersedes: []
superseded-by: []
relates: [2026-09-25-attribute-is-null-in-reference-having, 2026-08-10-catalog-and-collection-statistics, 2026-09-03-content-sized-value-tree-columns]
---

# Unique indexes keep no record-id set, and a unique value occurs once whatever the locale

Two indexes kept a set of their owning records beside their value tree:
- the standalone collection-level `OwnerUniqueIndex` kept `recordIds`;
- the catalog-level `GlobalUniqueIndex` kept `entitiesPerType`.

Both sets were wrong, and after #1584 nothing but the statistics read them. They are removed together with
their accessors (`UniqueIndex#getRecordIds` / `#getRecordIdsFormula`, and `GlobalUniqueIndex#getRecordIds` /
`#getRecordIdsFormula`). The statistics now count owning records off the value tree.

Counting off the tree is exact only if the tree is exact. It was not: the collection index let a record repeat
a value in a second locale. So the change also enforces the documented contract, in both indexes: `unique` and
`uniqueGlobally` mean the value occurs once in the collection or catalog, even for the entity that already
holds it.

## Why

Both sets removed a record on the **first** of its values unregistered, and nothing re-added it while the
record still held other values. They undercounted in two ways:
- in a standalone index, a record keeps a value per locale;
- in a type-level reference index, the "record" is a partition that every owner referencing the entity
  contributes values to.

The catalog set was also read by `attributeIsNull` / `attributeIsNotNull`, which returned wrong answers. #1584
moved those reads onto the filter indexes. That left the statistics as the only reader, and they undercounted
too. A future caller asking "which records carry this value?" would silently get an incomplete set, which is
exactly how #1584 was found.

The value tree was not exact either. `OwnerUniqueIndex#assertUniqueKeyIsFree` let a record claim a value it
already owned, a leftover of the `HashMap` it replaced. Its key has no locale, so such a repeat arrives from
another locale. The tree then held one entry for two registrations. Removing either locale dropped the entry,
so the other locale's value could no longer be found. Its removal failed with
`Unique index for attribute ... not found!`, because the emptied index had already been dropped.

The catalog index already rejected the same case; the rejection only surfaced once a probe exercised it. Its
tuple carries the locale, so a second locale counts as a second owner. Its assertion did contain a cross-locale
exemption, together with an "overwrite" branch in `registerUniqueKeyValue`. Neither could run:
- the exemption was gated on `attributeKey.localized()`, which is `locale != null` and so false for the
  locale-less `UNIQUE_WITHIN_CATALOG` key;
- a `UNIQUE_WITHIN_CATALOG_LOCALE` index gets one key per locale, so every tuple it receives shares that locale.

### Previous state

`OwnerUniqueIndex` held a `TransactionalBitmap recordIds`, with a comment calling its imprecision "transient".
`isEmpty()` read the tree precisely because the bitmap could not be trusted.

`GlobalUniqueIndex` held a `TransactionalMap<Integer, TransactionalBitmap> entitiesPerType`. Neither set was
persisted: both load paths rebuilt them from the persisted `(value, record)` pairs, so a restart repaired the
undercount until the next removal.

## Options considered

### Option A: remove the sets and count off the tree (chosen)

`OwnerUniqueIndex#size()` and `GlobalUniqueIndex#getRecordCount()` walk the value tree with a cursor into a
transient bitmap: one for the owner index, one per entity type for the catalog index.

- **Pros:** no structure to keep in lockstep, so none to drift. One `TransactionalBitmap` fewer per standalone
  unique index, and one transactional layer fewer on every write to it. The API no longer offers a record set,
  so it cannot hand out a wrong one.
- **Cons:** the count is `O(values)` rather than `O(1)`.

### Option B: repair the sets (declined)

Keep the sets and make them exact, for example with a per-record value counter so that a record leaves only
when its last value goes.

- **Pros:** the count stays `O(1)`.
- **Cons:** a counter per record costs more memory than the bitmap it would repair. Every write pays to keep it
  up to date, only to feed a monitoring reading.
- **Rejected because:** the only consumer is `INDEX_CARDINALITY`, which is requested explicitly and never
  polled (see `EntityCollectionStatistics`). "Which records carry a value" is answered correctly by the filter
  indexes, which every unique attribute is written to and which remove per value. A second, parallel answer
  kept in the unique index could only drift from that one.
- **Revisit if** a hot path ever needs the record count of a unique index.

### Option C: lenient cross-locale uniqueness (declined)

Let an entity reuse its own value across its locales, and make the collection index count registrations per
value so that removal balances.

- **Pros:** accepts data that the collection index accepted before this change.
- **Cons:** it contradicts the documented contract. It would also split the two levels: a global attribute,
  which is also unique in its collection, would still be rejected by the catalog index.
- **Rejected because:** the documentation defines `unique` / `uniqueGlobally` as once per collection /
  catalog, and only the `*WithinLocale` variants as per locale. The rules must stay that clear.
- **Revisit if** a use case for per-entity reuse is raised; it would then be a new uniqueness type, not a
  relaxation of these two.

## Decision

**Chosen: Option A, with strict uniqueness in both indexes.** Both unique indexes now reject any registration
of a value that is already present, whoever owns it. With that, every value in a tree has exactly one owner,
and a walk of the tree is an exact count of the owning records.

The walk is cheap enough because the statistics are the only caller. Existing catalogs that already hold a
repeated value are not migrated. That decision was deliberate: the state was already broken on the first
removal.

## Key technical details

- **Strictness:** `OwnerUniqueIndex#assertUniqueKeyIsFree` and `GlobalUniqueIndex#assertUniqueKeyIsFree`
  throw whenever the value is present. The upsert path (`AttributeIndexMutator#executeAttributeUpsert`)
  unregisters a record's prior value before registering the new one, so an unchanged value never re-arrives. An
  array is folded onto its distinct values before registration, so a repeated element is claimed once.
- **Reference attributes were already strict across owners.** A probe was rejected with
  `UniqueValueViolationException` in each case: two owners referencing the same or a different target with the
  same value, in both the folded and the standalone index. The only re-registration in practice was the
  same-record case fixed here.
- **Ordering on unregister:** `GlobalUniqueIndex#unregisterUniqueKeyValue` now asserts ownership before it
  removes. It used to remove first and throw afterwards. On the warm-up path, which has no transaction to roll
  that back, a refused unregister therefore used to leave the value removed.
- **Counting:** `OwnerUniqueIndex#size()` and `GlobalUniqueIndex#getRecordCount()` walk `BucketCursor`s. A walk
  racing an in-place warm-up writer is bounded by the cursors' observable-live-run reads, the same guarantee the
  heap walks rely on (`2026-09-03-content-sized-value-tree-columns`). It reads a possibly slightly stale count
  and never fails. `UniqueIndexView#size()` still reads the shared filter view.
- **Membership questions go to the filter index.** `UniqueIndex` offers no record set on purpose. Tests that
  need the owners read them off the tree through `UniqueIndexTestSupport`.
- **Storage format unchanged:** neither set was ever persisted.

## Verification

- The collection-level same-value-two-locales case, `AttributeIndexingTest`
  `shouldFailToReuseUniqueValueInAnotherLocaleOfTheSameEntity`: failed before the fix ("Expected
  UniqueValueViolationException … nothing was thrown") and passes after it.
- The same rule at unit level: `UniqueIndexTest#shouldFailToRegisterValueTheSameRecordAlreadyOwns` and
  `GlobalUniqueIndexTest#shouldRejectSameValueInAnotherLocaleOfLocaleLessIndex`.
- The contrast case, `shouldAllowToReuseLocaleSpecificUniqueValueInAnotherLocaleOfTheSameEntity`: a
  `uniqueWithinLocale` value may repeat across one entity's locales.
- Exact counts:
  - at unit level, `UniqueIndexTest.RecordCountTest` and
    `GlobalUniqueIndexTest#shouldCountRecordForAsLongAsItHoldsAnyValue`;
  - end to end, `CheapScalarStatisticsTest#shouldCountUniqueRecordsExactlyAfterOneOfTheirValuesIsRemoved`. It
    removes one locale's value, and archives one owner of a shared type-level partition. `recordsCovered` stays
    1 in both cases.
- Counterfactual: the count tests run against `dev` with only the strictness fix applied, bitmaps still in place.
  All five fail at their intended assertion:
  - owner index: `expected: <2> but was: <1>`;
  - catalog index: `expected: <3> but was: <2>`;
  - a refused catalog unregister that had removed the owner's value: `expected: <PRODUCT: 1> but was: <null>`;
  - end to end, the locale case: `expected: <1> but was: <0>`;
  - end to end, the archived type-level partition, run with the locale assertion removed: `expected: <1> but
    was: <0>`.
- The affected classes: 635 tests in 55 classes, green.
- Whole-reactor `test-compile` with `-P unitAndFunctional,full`: green, 29 modules, the long-running and
  performance test modules included.
- Full functional suite: 24,964 tests, with 5 non-passing:
  - `ExportS3ServiceTest` needs Docker;
  - four external-API tests failed on transport timeouts under load (TLS session creation timed out, gRPC
    `UNAVAILABLE`). Rerun alone, all four classes are green (34 tests).

## Consequences & open follow-ups

- **A catalog stored before this change may hold an entity that repeats a value across the locales of a
  localized `unique()` attribute.** It loads, but it breaks the in-memory index on the first change to either
  value, and a re-upsert of it is now refused. This is deliberately not migrated.
- `UniqueValueViolationException` names the index's record id. In a reduced (reference) index that is the
  reduced index's own key, not the owner's primary key: the probe reported `existing entity PK: 3` for owner
  1. The message does not tell an operator which owner collided. Not addressed here.
- The statistics' "cost proportional to the schema" contract (`IndexCardinalityProjection`) has one stated
  exception now: the record count of a standalone unique index.

## Related work

- `2026-09-25-attribute-is-null-in-reference-having`: found both undercounting sets and moved the null tests off
  them. Its Consequences bullets are closed by this record.
- `2026-08-10-catalog-and-collection-statistics`: owns the `INDEX_CARDINALITY` readings whose record counts
  this record makes exact, and the warm-up tolerance rules the new walks follow.
- `2026-09-03-content-sized-value-tree-columns`: the cursor bound that makes a tree walk safe for a
  session-free management read.

## Timeline

- **2026-09-28**: #1658 filed from the #1584 investigation.
- **2026-09-30**: same-value-two-locales defect found by probe; strict semantics decided; sets removed.
