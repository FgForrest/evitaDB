---
title: Cut every temporal value to whole milliseconds as it enters, and carry every temporal index key in one long column
date: 2026-09-04
updated: 2026-09-04 07:10
status: accepted
kind: feature
issues: [1486]
prs: []
areas: [evita_common/src/main/java/io/evitadb/dataType, evita_api/src/main/java/io/evitadb/api/query, evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure, evita_engine/src/main/java/io/evitadb/index/bPlusTree, evita_engine/src/main/java/io/evitadb/index/attribute, evita_engine/src/main/java/io/evitadb/index/range, evita_engine/src/main/java/io/evitadb/index/invertedIndex, evita_store/evita_store_server/src/main/java/io/evitadb/store/index, evita_external_api/evita_external_api_grpc, documentation/user]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-08-10-stored-value-normalization-split, 2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-05-schema-handling-write-path-optimizations]
---

# Temporal precision is millisecond, stated once and enforced at the boundary

Every `OffsetDateTime`, `LocalDateTime` and `LocalTime` is truncated to whole milliseconds the moment
it enters the database — as an attribute value and as a query-constraint argument alike — and
`DateTimeRange` compares its boundaries at the same precision. One rule now covers every temporal type,
it is written down in the user documentation, and it is what lets a temporal index key ride a single
`long`: `InstantValueColumn` and its parallel nanosecond array are deleted, and `RangeValueColumn`'s
third `long[]` of zone offsets goes with them.

## Why

The question that started this was narrow: why does `InstantValueColumn` carry an `int[]` of nanoseconds
beside its `long[]` of epoch-seconds? The answer is arithmetic — epoch-**nanoseconds** in a 64-bit `long`
span only ±292 years, so the pair was the cheapest way to keep nanosecond precision at all.

The answer to *whether that precision was ever delivered* turned out to be no. Nothing under
`documentation/user/en/` stated any temporal precision guarantee, and the APIs disagreed with each other
in silence:

| surface | precision actually delivered, before this change |
|---|---|
| GraphQL (`OffsetDateTimeCoercing`, both directions) | milliseconds |
| REST (`ObjectJsonSerializer`) | milliseconds |
| EvitaQL (`EvitaDataTypes.formatValue`) | milliseconds |
| embedded Java API | nanoseconds |
| gRPC | nanoseconds |

GraphQL truncates in both directions; for REST and EvitaQL the truncation sits on the outbound path, which is
enough — whatever a client sent, milliseconds is what it could ever read back.

So the database paid for nanoseconds in every index, in a bespoke two-array column class with its own
lockstep invariant, in order to deliver a guarantee that three of its five client surfaces silently broke
and none of its documentation made. The memory was never the real driver — a precision rule a user can
state without exceptions is.

### Previous state

`EvitaDataTypes.toSupportedType` accepted temporal values verbatim. `FilterIndex#getNormalizer` mapped
declared `OffsetDateTime` / `LocalDateTime` attributes onto `Instant` keys, which
`ValueColumnFactory` routed to `InstantValueColumn`: a `long[]` of epoch-seconds and an `int[]` of nanos
held at one shared physical length, with every lock-free reader obliged to take the min of the two array
bounds. `DateTimeRange` derived its comparison longs with `toEpochSecond()`, and open bounds were
`LocalDateTime.MIN/MAX.atOffset(otherBound.getOffset()).toEpochSecond()` — a sentinel whose value depended
on the *other* bound's zone offset, which is why `RangeValueColumn` carried a third `long[]` recording both
offsets purely so a sentinel could be reconstructed.

## Options considered

### Option A — truncate at the data-type boundary, both write and query paths (chosen)

`EvitaDataTypes.toSupportedType` is the one private method that both `UpsertAttributeMutation` (write) and
`BaseConstraint` (query) already funnel through. Truncation happens there, so a stored value and a probe
derived from the same instant are cut identically and still meet.

- **Pros:** one rule, one site, and it holds for every value whether or not the attribute is indexed. A
  value that cannot be expressed as epoch milliseconds in a `long` is rejected at the boundary with an
  error naming the value and the range, rather than deep inside an index. The array overload delegates
  per element, so arrays come free.
- **Cons:** a breaking change for embedded-Java and gRPC clients that wrote sub-millisecond values; they
  read them back truncated.

### Option B — truncate only in the index normalizer (declined)

Leave stored attribute values alone and cut only what `FilterIndex#getNormalizer` turns into a key.

- **Pros:** no breaking change on read-back; strictly smaller blast radius; the memory saving is identical,
  because the saving lives entirely in the index.
- **Cons:** what you read back would no longer be what the index matched.
- **Rejected because:** the stored value and the query probe would be cut at different precisions, so a
  filter written with the value it stored could silently match nothing. Worse, the acceptable *range* of a
  temporal value would then depend on whether the attribute happened to be indexed — the same value legal
  in one schema and rejected in another. Revisit only if the read-back contract is redefined so that a
  stored value is explicitly not the value returned.

### Option C — keep nanoseconds and pack the column better (declined)

- **Pros:** no client-visible change at all.
- **Rejected because:** the guarantee being preserved was never delivered — three of five client surfaces
  already truncated, and no documentation promised it. Paying two arrays per temporal index for a
  precision no portable client could observe is the cost without the benefit.

### `DateTimeRange`: leave it at second granularity, or move it to milliseconds

Ranges were the one temporal type where truncation buys **no memory at all** — they already compared at
whole seconds, which is coarser than milliseconds. Leaving them alone was the smaller change.

- **Rejected because:** the value of this work is a rule a user can state in one sentence. "Milliseconds,
  except ranges, which are seconds" is not that rule, and the exception would have to be carried in the
  documentation, in every client's expectations and in every future discussion of temporal behaviour
  forever. Moving ranges to milliseconds makes them *strictly more precise* than before, so the change is
  in the safe direction. It then also removed the offset-dependent sentinel, which is what let the `meta`
  array go — a memory saving that was a consequence of the uniformity decision, not its motive.

### Backward compatibility: reader, or migration sweep

Catalogs released in the 2026.2 line hold values written under the old rules.

- **Chosen — backward-compatible readers plus a passive load-time repair.** They cover every read path
  unconditionally, including parts an eager sweep would not visit, and they are self-healing: once the
  index is next flushed, nothing collides again.
- **Declined — an eager migration script.** *Rejected because:* it must enumerate every affected storage
  part up front, and this change touches four of them across two modules (attribute filter parts, histogram
  parts, and both price-index parts). A missed part is a silently wrong index rather than a loud failure.
  `Migration_2026_2` already does eager re-keying for `String` and `BigDecimal`, so both routes exist in the
  codebase and compose; the reader was chosen for coverage, not for novelty.

## Decision

**Chosen: Option A, applied to every temporal type without exception, with backward-compatible readers.**

Truncating at the boundary is the only option under which the value you read back, the key the index holds
and the probe a query carries are all the same thing. Everything else follows from that single rule: with
sub-millisecond digits gone, epoch-**milliseconds** fit one `long` with ±292 million years of room, so
`InstantValueColumn` has no reason to exist and `LongKeyCodec.INSTANT` joins the `LOCAL_DATE` and
`LOCAL_TIME` constants already there.

For Option B to win, the contract would have to change so that a stored value is explicitly not the value
returned. For Option C to win, evitaDB would have to first deliver nanosecond precision through GraphQL,
REST and EvitaQL — at which point the guarantee would be real and worth its two arrays.

## Key technical details

- **The one truncation site** is `EvitaDataTypes.toSupportedType`. `BaseConstraint`'s first pass used to
  ask whether an argument's *type* was supported — and a temporal type is supported, so query arguments
  would have kept their nanoseconds while stored values lost theirs. **A test that writes V and queries
  with V passes either way**, so this would have shipped looking green. The pass now asks
  `EvitaDataTypes#requiresNormalization`, which reports a supported type whose *value* is still not normal.
- **`FilterIndex#getNormalizer` truncates as well, and that is not redundant.** The boundary covers what
  enters through the API; the normalizer makes the index key millisecond-exact whatever the value's
  provenance, which is what keeps the codec a bijection on the domain it actually sees.
- **`ValueColumnFactory` has two key spaces and they are not interchangeable.** `forFilterKey` serves the
  normalized keys a filter index builds; `forKey` serves raw ones, where the tree holds values exactly as
  the caller stored them. The temporal remap (declared `OffsetDateTime`/`LocalDateTime` keyed as `Instant`)
  belongs in `forFilterKey` **only** — in `forKey` it hands a unique index a column keyed by a class its
  values are never converted to. A unique temporal attribute therefore keeps a boxed column and forgoes
  the eight-byte representation; that is correct, not regrettable, because the tree really does hold
  `OffsetDateTime` values.
- **Range sentinels are now `Long.MIN_VALUE` / `Long.MAX_VALUE`**, the same constants all five `NumberRange`
  subtypes and `addValidity`'s always-valid price already use. `toComparableLong` saturates one step inside
  them, so an extreme *closed* bound (`OffsetDateTime.MAX` is legal) can never be read back as an open one.
- **The legacy threshold rescale is not injective, and the load must not pretend otherwise.** Persisted
  `RangeIndex` thresholds are untyped seconds; multiplying by 1000 maps every legacy open-from sentinel onto
  `Long.MIN_VALUE`, where the index's own border point already sits, and two sentinels written at different
  offsets onto the same long. Colliding runs have their `starts`/`ends` bitmaps unioned; without that the
  load fails outright with "Range values are not monotonic".
- **Routing the rescale lives in `AttributeIndexLoader#loadRangeIndex`, not in the storage-part
  serializers**, for two reasons that will look arbitrary otherwise: a range-PAGED filter index keeps
  thresholds in leaf-page records the root serializer never reads, and `Migration_2025_6` rewrites a legacy
  part through the *current* serializer, which would relabel second thresholds as millisecond ones
  irreversibly.
- **A backward-compatible reader's flag is necessary and never sufficient.** A threshold is an untyped
  `long` shared with every `NumberRange` subtype, so the reader says *this part was written in seconds* and
  the persisted value type decides whether to rescale. Both `HistogramIndexMapLoader` and
  `AttributeIndexLoader` gate on `DateTimeRange.class.equals(plainType)`.
- **The reload repair is deliberately a repair, where the neighbouring rule is fail-fast.** A pre-change
  catalog can hold two buckets whose instants differ below the millisecond and now encode to one key;
  `bulkLoadPage`'s ascending check compares *boxed* keys so the twins pass it, and
  `assertCrossLeafBoundaries`, comparing *decoded* keys, then reports index corruption. Colliding buckets
  merge across page boundaries, value ids compact with the surviving bucket keeping the id it was written
  with, and **a leaf the repair changed must give up its page identity** — a bulk-loaded leaf is clean, so
  a merged leaf would otherwise keep its identity, never be flushed, and a wholly absorbed page would drop
  out of the root's list while its records lived only in memory. This does not contradict
  `2026-07-18-paged-index-corruption-and-flush-failure-boundary`: there, nothing persisted identifies which
  of two overlapping leaves superseded the other, so adopting either resurrects deliberately-removed
  records; here the merge is unambiguous, because two buckets that encode to one key must become one bucket.
- **The merge target must be the array the page retains.** `InvertedIndex.collapseCollidingBuckets` retains
  a page that lost a bucket as an `Arrays.copyOf` **copy**, so a `targetPage` still pointing at the page's
  local array is an orphan — a later cross-page merge writes into it and the persisted copy keeps the
  un-merged bucket. Silent, permanent record loss. The assignment is marked as upholding that invariant at
  the site.

## Verification

Full suite green: `Tests run: 22665, Failures: 0, Errors: 1` under `-P unitAndFunctional,largeMachine`; the
single error is `ExportS3ServiceTest` reporting "Could not find a valid Docker environment", which is
environmental and pre-existing.

New and extended coverage, by name: `AttributeMillisecondPrecisionFunctionalTest`, `EvitaDataTypesTest`,
`BaseConstraintTest`, `UpsertAttributeMutationTest`, `InitialAttributesBuilderTest`, `DateTimeRangeTest`,
`LongValueColumnTest`, `InvertedIndexSubMillisecondReloadTest`, `UniqueIndexTest`, `GlobalUniqueIndexTest`,
`AttributeIndexLoaderTest`, `LegacyRangeThresholdScaleSerializerTest`, `EvitaDataTypesConverterTest`,
`LeafIndexHeapSizeTest`, `ColumnHeapSizeTest`.

**Per-tree budgets.** A one-key temporal index: **440 B → 408 B**, against a 424 B budget. A one-key range
index: **512 B → 456 B**.

**Whole-catalog measurement**, on a restored backup of a production e-commerce catalog (862,478 value trees),
baseline `ffca596dc` against this line of work. Reduced value trees **545.2 MB → 537.9 MB, −7.4 MB**,
attributable in full:

| value type | trees | baseline | after | delta |
|---|---:|---:|---:|---:|
| `DateTimeRange` | 92,223 | 56.079 MB | 51.858 MB | **−4.222 MB** — exactly 48.0 B/tree, the `meta` array |
| `Instant` | 9,346 | 32.842 MB | 29.688 MB | **−3.154 MB** |
| Boolean / Integer / Long / String | 760,909 | 596.411 MB | 596.411 MB | ±0.000 MB |

**Two opposite mechanisms, worth keeping in mind before modelling the next one.** Range trees average
**1.0 key**, so the per-tree array header dominates and the saving is per *tree*. Scalar temporal trees
average **74.9 keys** (9,346 trees, 700,103 keys — 1.08 % of trees), so the 4 B/key payload dominates and the
saving is per *key*. A per-tree model of the scalar case predicts the wrong number even when it lands on the
right total.

The census counts reduced indexes only (`GLOBAL` is walked as an owner candidate, never counted); the global
side adds an estimated ~0.51 MB, and that estimate is a floor, because an attribute indexed only globally
produces no census row at all.

**The measurement is also the compatibility test.** The catalog it ran against is a *released* 2026.2 backup:
it opens, all six structural invariants pass, and it shuts down clean. No test in the suite can do this — every
catalog a test reads was written by the current writer.

## Consequences & open follow-ups

- **Breaking change.** An embedded-Java or gRPC client that wrote sub-millisecond temporal values reads them
  back truncated. GraphQL and REST clients are unaffected, having been truncated already. `DateTimeRange`
  equality and `hashCode` derive from the comparison longs, so two ranges less than a second apart used to
  compare equal and no longer do — strictly more precise, but it is a behaviour change.
- **Open — legacy `unique` / `uniqueGlobally` temporal values are never retroactively truncated.** Unique
  indexes store raw values and `AttributeValueSerializer` reads them verbatim, so a new write truncating to
  the same millisecond as a legacy nanosecond value does **not** collide, and uniqueness is silently
  unenforced across that pair. Three options are on the table and none is chosen yet: truncate on read in the
  unique load path (cheapest, self-healing, and the current lean), document it as a migration-boundary
  limitation, or reindex unique attributes on upgrade. Whichever wins, it is a decision about an *existing*
  catalog only — a catalog created after this change cannot contain such a pair.
- **Three defects were found here that predate this work**, and are recorded because each shows a live trap
  rather than a typo: the `forKey`/`forFilterKey` remap above; `InitialAttributesBuilder.setAttribute`
  normalizing at `buildChangeSet()` instead of accept time, which made a read-back before `upsertVia` differ
  from what would be stored and made `setAttribute(name, someFloat)` on a schema-less attribute fail outright;
  and `EvitaDataTypesConverter.toDateTimeRange` rebuilding each bound from `getSeconds()` alone while the
  writer had always sent `getNanos()` — invisible while ranges compared at seconds, a real loss afterwards.
- **The lesson this line of work paid for six times: verify shipped state, never assert it.** A comment
  claiming the paged layout "has never shipped" appeared verbatim in three tree classes, twice in
  `ChainIndex`, and once in a test whose own javadoc contradicted it eighteen lines later. The same conflation
  bumped `HistogramIndexStoragePart`'s uid without a reader, which broke *every* released 2026.2 catalog
  carrying a histogram index — found by opening a production backup, not by the suite. `git tag --contains
  <sha>` is the only trustworthy evidence, and "the feature is unreleased" is not the claim that "its storage
  part is absent from released catalogs".
- **Eight comments in `IndexStoragePartConfigurer` still say "a brand-new record type with no
  backward-compatible reader" where they mean "format unchanged".** Reported, not rewritten — they are
  harmless as written but will mislead the next person to audit uids.

## Related work

- [2026-09-03-content-sized-value-tree-columns](2026-09-03-content-sized-value-tree-columns.md) — the sibling
  half of issue #1486 and the reason this one was findable: once every column was sized to its live content,
  the second parallel array in `InstantValueColumn` was the largest remaining oddity in the family.
- [2026-08-10-stored-value-normalization-split](2026-08-10-stored-value-normalization-split.md) — same
  normalizer, same `ValueColumnFactory`. Its *decision* stands; one factual conclusion in its consequences —
  that unique indexes were checked and unaffected — was wrong, and is corrected in place with a pointer here.
- [2026-07-18-paged-index-corruption-and-flush-failure-boundary](2026-07-18-paged-index-corruption-and-flush-failure-boundary/README.md)
  — sets the fail-fast rule for a corrupt paged index; the load-time repair here is a deliberate, argued
  exception, and the argument is in *Key technical details*.
- [2026-08-05-schema-handling-write-path-optimizations](2026-08-05-schema-handling-write-path-optimizations.md)
  — the same write path through attribute mutations that the boundary truncation sits on.

## Timeline

- **2026-09-03** — question raised about `InstantValueColumn`'s nanosecond array; precision audit across the
  five client surfaces; boundary truncation, the epoch-millisecond column and the three pre-existing defects
  land (`7057bd973`, `03ca79a1e`, `f2db956ca`).
- **2026-09-03** — `DateTimeRange` moved to millisecond comparison, offset-independent sentinels and the
  deletion of the `meta` array (`9a3e90c93`).
- **2026-09-04** — adversarial review round: the merge-target aliasing in `collapseCollidingBuckets`
  (`f9bb05c02`) and the missing released-2026.2 histogram reader (`908387993`).
- **2026-09-04** — measured against a restored production catalog backup; record written.
