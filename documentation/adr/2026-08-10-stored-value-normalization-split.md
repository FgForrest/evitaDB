---
title: LocalDateTime is a first-class schema type, and its UTC-anchored Instant encoding lives in the index normalizer
date: 2026-08-10
updated: 2026-09-03 10:45
status: accepted
kind: fix
issues: [1403]
prs: [1404, 1405]
areas: [evita_common/src/main/java/io/evitadb/dataType, evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/attribute, evita_engine/src/main/java/io/evitadb/index/attribute, evita_engine/src/main/java/io/evitadb/index/bPlusTree]
supersedes: []
superseded-by: []
relates: [2026-08-05-schema-handling-write-path-optimizations, 2026-09-03-content-sized-value-tree-columns]
---

# `LocalDateTime` keeps its declared type end to end; the UTC anchoring that makes it cheap to index moves into `FilterIndex.getNormalizer`

An attribute declared as `LocalDateTime` could not be written at all in 2026.2 — the value was rewritten to an
`OffsetDateTime` inside the mutation constructor, above the layer that validates it against the schema. The rewrite
itself was worth keeping, but as an *index encoding*, not as a change of the public type. It now lives in
`FilterIndex.getNormalizer`, which is where the codebase already performs exactly this remap for `OffsetDateTime`.
`LocalDateTime` is a first-class schema type again — declared or auto-evolved — while the index still stores it as a
packed `Instant`.

## Why

A production full reindex failed on every entity carrying a `LocalDateTime` attribute:

```
InvalidMutationException: Invalid type: `class java.time.OffsetDateTime`! Attribute
`initialPublishedDate` in schema `Product` was already stored as type class java.time.LocalDateTime.
```

The attribute comes from a client interface getter returning `LocalDateTime`, so `ClassSchemaAnalyzer` registers that
type verbatim — and then no value could ever satisfy it. The quieter half of the same defect: when the attribute was
*not* declared, `AttributeSchemaEvolvingMutation` derives the type from `getAttributeValue().getClass()`, so a
`LocalDateTime` value silently produced an `OffsetDateTime` attribute definition. No exception, and a schema that no
longer matches the interface it was generated from.

### Previous state

Until 2026.2 the four `UpsertAttributeMutation` constructors assigned `this.value = value` verbatim. Commit
`016d93255` (2026-03-27, conditional bucket indexing) changed all four to
`EvitaDataTypes.toSupportedTypeOrItsArray(value)`. The motivation was sound — `Float`/`Double` must normalize to
`BigDecimal` or histogram bucket keys disagree with the stored value. `toSupportedType` also rewrites `LocalDateTime`
to `OffsetDateTime` at UTC (a branch present since the 2023 initial commit, on a method whose own JavaDoc says it is
"used at query entry points"), and that rewrite rode along into the write path.

## Options considered

### Option A — split the normalizer, and encode in the index (chosen)

Two independent moves. **(1)** `UpsertAttributeMutation` calls a new `toSupportedStoredTypeOrItsArray`, which performs
every other normalization (`Float`/`Double` → `BigDecimal`, non-`@SupportedEnum` → `String` name, rejection of
unsupported types) but leaves `LocalDateTime` alone, so the schema records what the client declared. **(2)** the UTC
anchoring moves to `FilterIndex.getNormalizer` + `ValueColumnFactory.normalizedTypeOf`, so the index stores an
`Instant` and the tree selects the packed `InstantValueColumn`.

- **Pros:** the two concerns are separated and named — public type vs. index encoding. The encoding lands on the one
  seam every consumer already reads (all four filter translators, `HistogramIndex`, `AttributeIndexLoader`,
  `AttributeIndex`, `OwnerFilterIndex`, and `SortIndexView` via `shared.getNormalizer()`), so it is two lines rather
  than a boundary threaded through storage and reads.
- **Cons:** two public normalizer entry points that differ in one branch; and `getNormalizer` must stay in lockstep
  with `normalizedTypeOf` in a different package.

### Option B — delete the `LocalDateTime` branch from `toSupportedType` outright (declined)

One line. `SUPPORTED_QUERY_DATA_TYPES` contains `LocalDateTime`, so a method whose job is "unsupported → supported"
arguably must not rewrite it. Both gates that could have blocked this are clear: EvitaQL has a literal form for a bare
`LocalDateTime`, and the gRPC wire path encodes at `DEFAULT_ZONE_OFFSET`, which *is* `ZoneOffset.UTC`.

- **Pros:** removes the contract inconsistency at its root; no second entry point.
- **Cons:** changes behaviour for every caller, not just the broken one.
- **Rejected because:** on the query path the rewrite is **inert** — every attribute constraint coerces its value to
  the declared type via `toTargetType`, and both directions preserve the wall clock (`atOffset(UTC)` out,
  `toLocalDateTime()` back) — so deleting it fixes nothing there. What it *would* change is the other caller,
  `ComplexDataObjectConverter`: associated-data map keys would start being written as `LocalDateTime` where existing
  catalogs hold `OffsetDateTime`, a persistence-format change bought for no behavioural gain. It becomes the better
  option once `ComplexDataObjectConverter` no longer relies on `toSupportedType` for key normalization.

### Option C — anchor at the server's default time zone instead of UTC (declined)

The original design instinct: resolve the offset dynamically so that changing the server time zone moves stored local
times with it.

- **Pros:** feels responsive to deployment configuration; superficially "more correct" than a hardcoded UTC.
- **Rejected because:** a region zone is not a constant offset, which breaks three properties UTC gives for free.
  **(i)** The mapping stops being a bijection — `02:30` does not exist on a spring-forward day and occurs twice on
  fall-back, so `atZone` silently picks one. **(ii)** Round-trip stops being idempotent: write, change the zone, read
  back, get a different wall clock; rows written either side of the change are interpreted under different rules with
  nothing on disk recording which. **(iii)** Decisively for evitaDB: the normalized value is **persisted in the filter
  and sort indexes**, so a config change would silently invalidate every index built on those attributes, with no
  detection and no migration — and across a DST boundary local ordering and instant ordering genuinely differ, so
  `attributeNatural` could return a different order depending on when the index was built. Oracle is the empirical
  precedent: it refuses to change the database time zone once `TIMESTAMP WITH LOCAL TIME ZONE` columns hold data
  (ORA-30079). It would only become viable if the offset were transmitted per session, like PostgreSQL's `timestamptz`
  — a client-supplied zone, not a server-global one.

### Option D — encode at the mutation or storage boundary (declined)

Keep the conversion near where 2026.2 put it, but derive the schema type from the original value and decode on read.

- **Pros:** the physical `AttributeValue` payload itself would hold the encoded form.
- **Rejected because:** it reintroduces exactly the coupling that caused this bug — an encoding above the schema check
  — and it is strictly more work: `AttributesStoragePart.upsertAttribute` and `AttributeIndexMutator` both align the
  value to `attributeDefinition.getType()` via `toTargetType`, so with the schema saying `LocalDateTime` they would
  coerce straight back and both would need to learn the encoding, plus a decode on the read path.

## Decision

**Chosen: Option A.** The encoding instinct behind the original code was right — `LocalDateTime` really is the one
temporal type that indexes badly (see below) — it was simply installed one layer too high, which turned an internal
representation choice into a change of the public type. Putting it in `getNormalizer` gets the identical physical
representation while the schema, the mutation and the client boundary all keep speaking `LocalDateTime`. Option C is
the one most likely to be re-proposed and is the one to read this record for.

## Key technical details

- **Invariant:** a value on its way into storage must never be rewritten away from the data type its attribute schema
  declares. `AttributeSchemaEvolvingMutation` compares with `attributeSchema.getType().isInstance(attributeValue)` —
  strict type identity, not convertibility — and derives the schema type from the value's class when auto-evolving, so
  any normalization applied above it silently becomes schema policy.
- **Lockstep:** `FilterIndex.getNormalizer` and `ValueColumnFactory.normalizedTypeOf` must agree on which declared
  types normalize to `Instant`. They live in different packages; `InstantValueColumnTest` pins the pairing.
- The anchoring is `LocalDateTime.toInstant(ZoneOffset.UTC)`. A **constant** offset makes it a lossless bijection and
  monotonic with `LocalDateTime`'s natural order, so bucket lookup and ordered iteration are unchanged. The normalizer
  is idempotent, like its siblings — probes are normalized more than once along a lookup path.
- The encoding is invisible to clients: the value handed back comes from `AttributesStoragePart`, not from the index.
- **`LocalDate` and `LocalTime` are deliberately left out of the temporal branch.** Each fits losslessly in a single
  `long` (`toEpochDay` / `toNanoOfDay`) and already has a `LongKeyCodec`, so they select the 8-byte `LongValueColumn`.
  Routing them through `Instant` would be a *downgrade* to 12 bytes plus an all-but-unused `int[]`. Do not "fix" this
  for symmetry.
- `SortIndex.createNormalizerFor` needs no change: it normalizes only `String`/`Locale`/`Currency`/`BigDecimal` and
  never handled `OffsetDateTime` either. Sort-side temporal folding happens only in view mode, which adopts the shared
  inverted index's normalizer.

## Verification

- `LocalTemporalAttributeTypeFidelityFunctionalTest` — written first, against the unfixed build: **5 of the 11 methods
  then in the class failed, and all five were `LocalDateTime`**. The declared-schema case reproduced the production
  `InvalidMutationException` verbatim; the auto-evolution case failed with `expected: <java.time.LocalDateTime> but was:
  <java.time.OffsetDateTime>`. `LocalDate` and `LocalTime` passed before *and* after — confirming-negatives pinning the
  defect to one branch rather than to local temporal types as a family. A counterfactual run (fix reverted, class
  otherwise unchanged) re-confirmed the whole class fails without it.
- Read-back assertions compare against the exact value written, not merely "no exception" — a fix that stopped the
  throw while leaving a wall-clock-shifting conversion in place would still fail. `attributeNatural`,
  `attributeEquals`, `attributeBetween`, `attributeGreaterThanEquals` and `attributeLessThan` are asserted per type,
  covering every translator that reads `getNormalizer`. The localized case is covered separately, since it takes the
  third `UpsertAttributeMutation` constructor and the `isLocalized` branch of schema verification.
- `FilterIndexTest.TemporalNormalization` pins the encoding itself: UTC instant, idempotence, order preservation, and
  `LocalDate`/`LocalTime` passing through untouched. `InstantValueColumnTest` pins the column selection on both sides —
  `LocalDateTime` → `InstantValueColumn`, `LocalDate`/`LocalTime` → `LongValueColumn`.
- `FilterIndexLegacyLocalDateTimeSerializerTest` — round-trips a 2026.1-shaped part through the legacy serializer
  and rehydrates it exactly as `AttributeIndexLoader` does. The rehydration case fails with `ClassCastException`
  without the re-anchoring, which is what makes it a real guard rather than a restatement.
- `EvitaDataTypesTest` — the pre-existing "Conversion to supported type" suite (8 tests, including
  `shouldConvertLocalDateTimeToOffsetDateTime`) still passes unchanged, pinning the query-path contract as deliberately
  retained; new "Conversion to supported stored type" suite adds 6.
- JDWP session against the unfixed build, breaking at `UpsertAttributeMutation:58` and
  `AttributeSchemaEvolvingMutation:118`:

  ```
  value.getClass().getName()                           = "java.time.LocalDateTime"   (2026-05-20T12:19:26)
  this.value.getClass().getName()                      = "java.time.OffsetDateTime"  (2026-05-20T12:19:26Z)
  attributeSchema.getType().getName()                  = "java.time.LocalDateTime"
  attributeValue.getClass().getName()                  = "java.time.OffsetDateTime"
  attributeSchema.getType().isInstance(attributeValue) = false
  ```

## Consequences & open follow-ups

- Indexed `LocalDateTime` attributes move off `BoxedObjectColumn` onto `InstantValueColumn`: ~12 bytes per key instead
  of a reference plus three objects (`LocalDateTime` holds a `LocalDate` and a `LocalTime`), and no boxing on lookup
  hot paths. That is a structural estimate from the field layouts, **not a measurement** — nobody has run a heap
  comparison. Only `filterable`/`sortable` attributes are affected, since only those are indexed.
- The declared type now wins in **both** directions: a bare `LocalDateTime` written to an attribute declared
  `OffsetDateTime` is rejected with `InvalidMutationException` rather than silently coerced to UTC. That is a
  restoration, not a new rule — `v2026.1.19` rejected it too (all four `UpsertAttributeMutation` constructors
  assigned the value verbatim); only the broken 2026.2 accepted it, by rewriting before the check. A client relying
  on that coercion must convert explicitly, and should prefer `atZone(zone).toOffsetDateTime()` over
  `atOffset(UTC)` unless it really means a UTC wall clock. Covered by
  `LocalTemporalAttributeTypeFidelityFunctionalTest#shouldRejectLocalDateTimeValueForOffsetDateTimeAttribute`.
- `attributeHistogram` still rejects any non-numeric attribute (`AttributeHistogramTranslator` asserts
  `Number.class.isAssignableFrom(...)`), so no temporal type — `OffsetDateTime` included — can produce one. Unchanged
  here, and the reason there is no histogram coverage for `LocalDateTime`.
- The UTC anchoring is **not** documented for users: it is an internal encoding with no observable effect, and
  documenting it would only invite people to reason about a representation they never see.
  `documentation/user/en/use/data-types.md` previously claimed local date times are "always converted to OffsetDateTime
  using the evitaDB server system default timezone" — wrong on both counts — and now states only that `LocalDateTime`
  is a first-class attribute type whose wall clock round-trips. The adjacent `<LS to="c">` block still carries the old
  claim for the C# driver and was left alone; nobody verified what that driver actually does.
- **A 2026.1 catalog does need its `LocalDateTime` filter indexes migrated** — an earlier revision of this record
  claimed the opposite, having checked only that no *2026.2* catalog could hold such keys. Bucket keys are persisted
  **already normalized** (`InvertedIndex` applies the normalizer in `addRecord`; `AttributeIndexLoader` feeds
  `part.getHistogramPoints()` into the tree verbatim), so changing a normalizer *is* an on-disk format change for that
  attribute type. 2026.1 had no `LocalDateTime` branch and persisted raw wall-clock values, while the current tree
  picks `InstantValueColumn` for that declared type and hard-casts — so loading such a catalog died with
  `ClassCastException`. `FilterIndexStoragePartSerializer_2026_1` (registered for the 2026.1 `FilterIndexStoragePart`
  uid in `IndexStoragePartConfigurer`) now re-anchors those values at UTC on read. It is self-healing: the next write
  persists `Instant` keys through the current serializer, after which the legacy reader is never consulted again.
- `Migration_2026_2` already re-keys `String` (NFD) and `BigDecimal` (scaled-int) filter parts eagerly at upgrade
  time, which is the same class of problem. `LocalDateTime` is handled in the BWC *reader* instead, deliberately: the
  reader covers every read path unconditionally — including any part a migration sweep does not visit — and it is
  self-healing, whereas the eager route would have to enumerate parts for a type far rarer than `String`. The two
  compose: a part re-keyed by the migration is read through this same reader first, so it is already anchored.
- Other index kinds were checked and are **not** affected. Unique indexes select their leaf column through the same
  `ValueColumnFactory.forKey` and keep raw values, which looks like the same trap, but a `unique` `OffsetDateTime` and
  a `unique` `LocalDateTime` attribute each write 200 distinct values end-to-end without error — so the reading was
  wrong and the empirical result governs. `HistogramIndex` is 2026.2-only (no `_2026_1` reader) and `SortIndex` does
  not use `ValueColumnFactory` at all.
- **Invariant for the next person:** any future change to `FilterIndex.getNormalizer` for a type that has already been
  persisted is an on-disk format change, and needs a matching conversion in the BWC reader for the format that wrote
  it. The normalizer is not merely a runtime detail.
- 2026.2 was still in testing when this surfaced, so no catalog in the wild carries an attribute auto-evolved to
  `OffsetDateTime` by the defect. Should a test catalog have one, its schema and index remain internally consistent —
  only the declared type is wrong, and re-evolving it is enough.

## Related work

- [2026-08-05-schema-handling-write-path-optimizations](2026-08-05-schema-handling-write-path-optimizations.md)
  — same write path through attribute mutations; that record optimizes how schemas are resolved per mutation, this one
  constrains what a mutation may do to the value on the way through.

## Timeline

- **2026-03-27** — `016d93255` applies the query-path normalizer in all four `UpsertAttributeMutation` constructors,
  incidental to conditional bucket indexing
- **2026-08-10** — regression reported from a production full reindex, reproduced, confirmed under JDWP, and fixed;
  the UTC anchoring re-sited in the index normalizer after review
