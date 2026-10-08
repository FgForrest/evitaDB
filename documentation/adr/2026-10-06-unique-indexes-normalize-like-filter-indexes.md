---
title: Standalone unique indexes key every value exactly as the filter index does
date: 2026-10-06
updated: 2026-10-06 20:55
status: accepted
kind: fix
issues: [1712, 1713]
prs: [1722]
areas: [evita_engine/src/main/java/io/evitadb/index/attribute/UniqueIndexBPlusTreeSupport.java, evita_engine/src/main/java/io/evitadb/index/attribute/OwnerUniqueIndex.java, evita_engine/src/main/java/io/evitadb/index/attribute/GlobalUniqueIndex.java, evita_engine/src/main/java/io/evitadb/index/attribute/UniqueIndex.java, evita_engine/src/main/java/io/evitadb/index/attribute/FilterIndex.java, evita_engine/src/main/java/io/evitadb/index/bPlusTree/ValueColumnFactory.java, evita_engine/src/main/java/io/evitadb/index/component/loader/AttributeIndexLoader.java, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog/Migration_2026_3.java, evita_store/evita_store_server/src/main/java/io/evitadb/store/index/serializer/ComparableLocaleSerializer.java]
supersedes: []
superseded-by: []
relates: [2026-09-04-millisecond-temporal-precision, 2026-10-06-unique-indexes-keep-no-record-set, 2026-09-21-cardinality-counter-normalized-keys, 2026-08-10-stored-value-normalization-split]
---

# Standalone unique indexes key every value exactly as the filter index does

The catalog's `GlobalUniqueIndex` (`uniqueGlobally` / `uniqueGloballyWithinLocale`) and a collection's
`OwnerUniqueIndex` (a localized attribute unique across locales) now run every value through
`FilterIndex#getNormalizer(type, indexedDecimalPlaces)` and keep it in the leaf column
`ValueColumnFactory#forFilterKey` selects, on every entry point: register, unregister, lookup, and the restore of
both persisted shapes. There is no per-type exception. Two values the filter index treats as one are one unique value:
the same instant at two offsets, a precomposed and a decomposed Unicode spelling, two decimals equal at the
attribute's indexed decimal places, a `Currency` or `Locale` and its comparable wrapper. Storage parts persist each
key as a value of the declared type, so the on-disk format is unchanged. `Migration_2026_3` re-keys the parts
already on disk and refuses the upgrade when two owners collapse onto one key.

## Why

The two standalone indexes stored the value exactly as it was written and compared it in natural order (with an
exact value-and-scale order for `BigDecimal`), while every other structure of the same attribute - the filter tree,
the unique view folded onto it, the cardinality counter - keyed the value through the filter normalizer. The query
translators probe with the normalizer's output. The result was a family of defects, one per type whose normalizer is
not the identity:

- **#1713** — `attributeEquals` / `attributeInSet` on a temporal attribute held in a standalone tree threw
  `ClassCastException` (an `Instant` probe against `OffsetDateTime` / `LocalDateTime` keys).
- **#1712** — a `String` written in NFC was never found, because the probe is NFD; and the trees kept NFC and NFD
  spellings of one text as two unique values.
- **`Currency` / `Locale`** — every write threw "expected to be Comparable", since the check ran on the raw value.
- **`BigDecimal`** — uniqueness was judged on the exact value and scale, while the filter tree and the folded unique
  view of the very same attribute judge it at the indexed decimal places.

A `uniqueGlobally` value is checked twice: against the collection's filter tree (normalized) and against the catalog
tree (raw). So uniqueness depended on whether the competing entity lived in the same collection.

### Previous state

The 2026-09-04 record placed the temporal `Instant` remap in `ValueColumnFactory#forFilterKey` only and kept unique
trees on `forKey`'s raw key space, on the grounds that the unique trees held raw values; it declined to normalize
legacy unique temporal values because a temporal unique identifier seemed unrealistic. Issue #1713 showed the raw key
space was not only a legacy gap but broke every lookup.

## Options considered

### Option A — one key space with the filter index, declared-type persistence (chosen)

`UniqueIndexBPlusTreeSupport` converts every value with the filter normalizer at the attribute's
`indexedDecimalPlaces` (`#toKey`, `#toDistinctKeys`, `#toPersistedKeys`), checks the `Comparable` contract on the
**key**, and builds the tree through `forFilterKey`. Parts persist `#toDeclaredValue(key)`: an `OffsetDateTime` /
`LocalDateTime` at UTC, a `BigDecimal` at the indexed scale, the bare `Currency` / `Locale`, the NFD `String`.

- **Pros:** one notion of equality for an attribute everywhere; no format change, no uid bump, no reader; temporal and
  decimal keys ride in primitive columns.
- **Cons:** a normalizer call per register, unregister and lookup; a violation message names the canonical value (UTC
  instant, NFD text, scaled decimal) rather than the spelling the client sent.

### Option B — probe the unique trees with the raw value (closed PR #1714, declined)

- **Pros:** smallest change.
- **Rejected because:** it kept the offset-sensitive, spelling-sensitive equality. The catalog tree and the
  collection's filter tree then disagree on what one value is, and a lookup succeeds only with the exact spelling
  that was written. Revisit only if a value's spelling is ever meant to be part of its identity, which no other
  surface supports.

### Option C — a per-type whitelist of normalized types (declined)

The first rework normalized only `OffsetDateTime` / `LocalDateTime` / `LocalTime` (`isKeyNormalized`).

- **Rejected because:** it drifts from the filter rule by construction. Every type left out (String, BigDecimal,
  Currency, Locale, ranges) kept its own defect, and every later normalizer change would need a second edit nobody
  is forced to make. The uniform rule has no list to forget.

### Option D — dual-probe for String (look up the NFD and the raw spelling) (declined)

- **Rejected because:** it answers differently from the filter tree - the unique tree still holds two spellings as
  two values, so duplicates stay possible and uniqueness depends on which spelling arrived first.

### Option E — keep boxed declared keys, compare temporal values with `OffsetDateTime.timeLineOrder()` (declined)

- **Pros:** consistent equality for temporal values without converting keys.
- **Rejected because:** it keeps boxed keys (about 91 B more per temporal key than the primitive column, measured on
  the earlier rework) and solves one type only; the stored representative depends on write order.

### Legacy data: normalize on reload, collapsing a colliding pair last-wins (declined)

The 2026-09-04 record prescribed this if the gap were ever fixed: keep the later of two keys collapsing into one
and log the dropped one.

- **Pros:** every catalog opens.
- **Rejected because:** two persisted values that now name one key belong to two owners, and nothing in the index
  says which should keep the value; dropping one silently loses a uniqueness claim the user made. The maintainer
  chose to react loudly. The repair lives in `Migration_2026_3`, which refuses before writing anything and names
  every pair; the engine only detects such a pair on load, at no cost to a load that succeeds, and refuses it.

## Decision

**Chosen: Option A, with a migration step.** It is the only option under which the filter tree, the folded unique
view, the collection's uniqueness check and the catalog's uniqueness check agree on what one value is, for every type,
without touching the on-disk format. **BigDecimal consequence (accepted by the maintainer):** uniqueness is judged at
the attribute's `indexedDecimalPlaces` - `1.0` and `1.00` are one unique value, and so are `1.235` and `1.236` at two
decimal places (both `1.24`, `HALF_UP`). That is how the filter index and the folded unique view already compared them.

## Key technical details

- **Entry points.** `UniqueIndexBPlusTreeSupport` (key space, column, fold, collision message);
  `OwnerUniqueIndex` / `GlobalUniqueIndex` (every entry point converts); `UniqueIndex#toPersistedValue` (the canonical
  persisted value, public for the migration).
- **The scale.** Both indexes freeze `indexedDecimalPlaces` at creation; `AttributeIndex` / `CatalogIndex` refuse a
  write when the schema declares another scale, like the filter index (`FilterIndex#assertIndexedDecimalPlacesUnchanged`).
  A unique part persists no scale: on load `AttributeIndexLoader#resolveUniqueIndexedDecimalPlaces` and
  `DefaultCatalogPersistenceService` read it from the schema, which reproduces the keys because the persisted
  `BigDecimal` already carries that scale.
- **Collision on load.** SINGLE: `OwnerUniqueIndex#seedTree` sees a bucket count that did not grow (O(1));
  `GlobalUniqueIndex#seedTree` catches the tree's "already present". PAGED: the bulk load's ascending check refuses,
  and only then are the keys scanned to name both owners. A successful load does no extra work. Only a part written
  before this change (by a dev build already at protocol 7, or a part migration did not reach) can trigger it.
- **Translators.** `AttributeEqualsTranslator` / `AttributeInSetTranslator` probe the unique path with the same
  normalized value as the filter path; the old exact-`BigDecimal` exception is gone. The indexes re-normalize
  idempotently, so the probe form does not change any result.
- **Filter arrays of `Currency` / `Locale`.** `FilterIndex#verifyValueArray` turned such elements into
  `String.valueOf(..)` (since 2023) instead of normalizing them, so the filter tree held `"CZK"` while every probe and
  the folded uniqueness check used `ComparableCurrency` - a `ClassCastException`. It now keys the elements through the
  normalizer, the same rule. `ComparableLocaleSerializer#read` recursed into itself instead of reading the `Locale` it
  wrote, so a persisted `ComparableLocale` (a paged index over a `Locale` attribute) could not be read back; fixed,
  byte format unchanged.

## Verification

- `UniqueAttributeNormalizationFunctionalTest` (48 cases): 8 types (`OffsetDateTime`, `LocalDateTime`, `LocalTime`,
  `String`, `BigDecimal`, `BigDecimalNumberRange`, `Currency`, `Locale`) × both standalone indexes × scalar and array:
  lookup by the other spelling (equals / inSet, collection and catalog-wide), refusal of the other spelling for another
  entity (another collection for `uniqueGlobally`), rewrite, release on update and removal, fold of two spellings in
  one array, reload, paged reload. On dev before the change 27 of the 38 non-paged cases that ran failed (the
  paged case then failed on a fixture error).
- `UniqueIndexKeySpaceTest` (48 cases): the same per index, plus declared-type persistence, SINGLE and PAGED restore,
  and refusal to load two spellings of one value naming both owners.
- Prior red tests now green: `TemporalUniqueAttributeLookupFunctionalTest`, `TemporalUniqueAttributeInstantKeyTest`,
  `UniqueStringAttributeCanonicalLookupFunctionalTest`, `ComparableWrapperArrayFilterFunctionalTest`.
- `Migration_2026_3_UniqueRekeyTest`: canonical parts untouched, each type rewritten, collisions reported, global
  same-entry fold versus owner refusal.
- `Migration_2026_3_UniqueUpgradeRoundTripTest` (4 cases) runs the real boot-time v6→v7 upgrade over a protocol-6
  catalog built in code - no release-written binaries are kept in git. The current engine writes the data; offline,
  every standalone unique part is rewritten into the raw spellings and the order 2026.2 kept, the catalog header is
  stamped with protocol 6 and published by a new bootstrap record. Inline and paged, for `String`, `BigDecimal` and
  `OffsetDateTime` in the catalog file (`uniqueGlobally`) and in a collection file (localized, unique across
  locales): every equivalent spelling finds the value and is refused for another owner after the upgrade, every
  persisted key is canonical, and a second boot answers the same. A catalog with six colliding pairs (three per file) is refused with every pair
  named and its files byte-identical. With the identity canonicalizer in the migration all 4 cases fail (raw
  `1.235` left in the catalog `price` part; the paged catalog fails to load with `Bulk-loaded keys must be strictly
  ascending`; both collision catalogs reach the engine's load-time refusal instead of the migration's). With the
  collision pre-pass disabled both collision cases fail: the upgrade completes without complaint.
- Counterfactuals, each run red and reverted: no key conversion in `UniqueIndexBPlusTreeSupport#toKey` (32 of 48
  functional cases red), persisting raw keys instead of declared values (5 functional, 28 unit red), no bucket-count
  check in `OwnerUniqueIndex#seedTree` (6 load-refusal cases red), identity canonicalizer in the migration (3 of 4
  transform tests red), the old `ComparableLocaleSerializer#read` (serializer test and the `Locale` reloads red). The
  pre-fix `FilterIndex` array path failed all 6 `Currency[]` / `Locale[]` lookups. Restoring the translators' exact
  `BigDecimal` probe changes no result, because both indexes normalize the probe themselves.
- Full functional module: 24,757 tests, 0 failures, 1 error (`ExportS3ServiceTest`, no Docker in the sandbox).

## Consequences & open follow-ups

- **Behaviour change for users:** equivalent spellings are one unique value, and `BigDecimal` uniqueness is judged at
  the indexed decimal places. A catalog holding two such values for different owners refuses the upgrade with every
  pair named; fix the data on the release that wrote it.
- **Catalogs written by a development build at protocol 7** skip `Migration_2026_3`; a raw key there is normalized on
  load, and a colliding pair refuses to load. Re-index such a catalog.
- **Stored `Currency[]` / `Locale[]` filter keys** written as strings by any earlier version are not re-keyed by the
  migration; those trees were already unusable for lookups, and a re-index repairs them.
- The phantom left in a collection by a refused `uniqueGlobally` write (found on the earlier rework) is tracked
  separately.

## Related work

- [2026-09-04-millisecond-temporal-precision](2026-09-04-millisecond-temporal-precision.md) — its `forKey` bullet
  (unique trees keep raw temporal values on a boxed column) and its "decided not to fix" bullet on legacy unique
  temporal values are superseded here; the millisecond rule itself stands and is what makes the `Instant` key exact.
- [2026-10-06-unique-indexes-keep-no-record-set](2026-10-06-unique-indexes-keep-no-record-set.md) — same two classes;
  its "a unique value occurs once whatever the locale" rule is what the refusals here run through.
- [2026-09-21-cardinality-counter-normalized-keys](2026-09-21-cardinality-counter-normalized-keys.md) — the same
  normalize-before-keying fix for the cardinality counter, and the `Migration_2026_3` step this one joins.
- [2026-08-10-stored-value-normalization-split](2026-08-10-stored-value-normalization-split.md) — the normalizer
  this record makes the single key rule.

## Timeline

- **2026-10-05** — #1712 and #1713 reported; temporal and NFC lookup failures proven.
- **2026-10-06** — raw-value probe (PR #1714) closed unmerged; uniform rule decided, including `BigDecimal` at the
  indexed scale; implemented with the `Migration_2026_3` step.
