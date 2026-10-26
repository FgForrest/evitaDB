---
title: Cut every temporal value to whole milliseconds as it enters, and carry every temporal index key in one long column
date: 2026-09-04
updated: 2026-10-26 20:45
status: accepted
kind: feature
issues: [1486, 1696]
prs: []
areas: [evita_common/src/main/java/io/evitadb/dataType, evita_api/src/main/java/io/evitadb/api/query, evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure, evita_engine/src/main/java/io/evitadb/index/bPlusTree, evita_engine/src/main/java/io/evitadb/index/attribute, evita_engine/src/main/java/io/evitadb/index/range, evita_engine/src/main/java/io/evitadb/index/invertedIndex, evita_store/evita_store_server/src/main/java/io/evitadb/store/index, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_store/evita_store_entity/src/main/java/io/evitadb/store/entity/serializer, evita_external_api/evita_external_api_grpc, documentation/user]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-08-10-stored-value-normalization-split, 2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-05-schema-handling-write-path-optimizations, 2026-09-21-cardinality-counter-normalized-keys, 2026-10-08-unique-indexes-normalize-like-filter-indexes, 2026-10-20-single-owner-partitions-as-registry-rows, 2026-08-24-fulltext-search-lucene-vs-inhouse]
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
- **Changed on 2026-10-05 (`58916f5a1c`, merged with PR #1738): a value outside the window is clamped, not
  rejected.**
  - `EvitaDataTypes#toSupportedType` clamps it to the nearest edge of the window, on the write path and the
    query path alike. The edges are the first millisecond of the lowest supported second and the last whole
    millisecond of the highest one. An `OffsetDateTime` keeps its offset; a `LocalDateTime` is clamped as the
    wall clock at UTC.
  - gRPC clamps outbound values into the protobuf `Timestamp` range, and decodes only that range's exact
    edges as `MIN` / `MAX`.
  - **Why the rejection had to go:** it refused C# `DateTime.MaxValue` / `MinValue`, which is what an
    uninitialised C# `DateTime` holds, and Java's `OffsetDateTime.MAX` / `LocalDateTime.MIN`. 2026.2
    accepted all of them, and the gRPC converter decoded every moment of year 9999 or year 1 as such a
    sentinel.
  - Dropping the check alone was not an option: `Instant#toEpochMilli` overflows on the sentinel, so the
    failure would only have moved into the index keys. The commit message lists the counterfactuals.
  - **The clamp covered values entering the database, not keys already stored.** `FilterIndex#getNormalizer`
    truncates a persisted temporal key but does not clamp it (`FilterIndex:448-456`). A red test confirmed what
    the code suggested: on a 2026.2 catalog holding a sentinel, a filter index overflowed `LongKeyCodec.INSTANT`
    at load, after protocol 7 had been published, and a unique index threw inside the migration. **Fixed on
    2026-10-20 (#1696)** - see *Stored values beyond the window* below.

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

- **Originally chosen — backward-compatible readers plus a passive load-time repair.** They covered every read
  path unconditionally, including parts an eager sweep would not visit; once the index was next flushed, nothing
  collided again. The bucket-key rule was replaced on 2026-10-26, as described below.
- **Originally declined — an eager migration script.** *Rejected because:* it must enumerate every affected storage
  part up front, and this change touches four of them across two modules (attribute filter parts, histogram
  parts, and both price-index parts). A missed part is a silently wrong index rather than a loud failure.
  `Migration_2026_2` already does eager re-keying for `String` and `BigDecimal`, so both routes exist in the
  codebase and compose; the reader was chosen for coverage, not for novelty.

**Changed on 2026-10-26 for attribute filter and histogram BUCKET trees:** the passive key repair moves
into `Migration_2026_3` (protocol 6 → 7), through `LegacyInvertedIndexCorrection`. The migration already enumerates
every filter tree of every published entity index for the supported-window clamp, and now also enumerates every
histogram root. It clamps, canonicalizes with the type-level function, merges collisions and writes each affected
tree once, before the cardinality audit. It re-sorts paged `DateTimeRange` keys only when the entire sequence is
strictly ascending under the released second-granularity comparator. Any other paged tree whose stored keys do not
ascend strictly - an inversion, or a stored key a page repeats from its predecessor - remains untouched and fails
the subsequent load as corruption, so stale twins are never silently healed. Inline restore re-inserts distinct
stored keys in comparator order and rejects duplicates; it does not validate their persisted order. The original
objections to an eager sweep no longer apply: the enumeration exists, and missed collisions or inversions in paged
trees fail loudly.

The read-only refusal pass sorts the buckets of every folded unique tree the correction would merge by
clamp-then-type-function keys using the loader comparator before comparing owners. A legacy range reorder can bring
together keys that were not persisted as neighbors. A tree the correction leaves to the loader is not checked: a
stale twin is corruption, which no change of unique values could repair. After all unique and representative-key refusals pass, the collection write loop plans and drains one
tree at a time before the cardinality audit and partition conversion. Retaining replacement plans for the whole
catalog was rejected because bitmap-backed replacement buckets can exceed the compact loaded catalog's heap.
Only folded unique trees need a second bucket read. A later planning failure aborts publication; earlier
unpublished appends and deferred part removals leave the previous published state reachable.

Protocol 7 and `Migration_2026_3` have not shipped, so the migration itself is extended; catalogs written by
protocol-7 SNAPSHOT builds need no compatibility path. This follows the owner-sort reversal of 2026-10-12 and the
#1696 option-B rule: legacy handling belongs in migrations and versioned readers.

**Changed on 2026-10-26 for range thresholds:** versioned filter and histogram readers now convert inline
`DateTimeRange` companions using the root's own declared plain type, including array component types. All supported
filter formats require a persisted type; only the unsupported 2024.5 serializer ever stored a null type. The
referenced-id rewrite in `Migration_2025_6` copies reader-converted thresholds unchanged. Paged companions span
unversioned leaf records, so `Migration_2026_3`
converts every marked temporal range axis, even when its bucket keys are canonical. It merges collapsed border
points, allocates fresh pages above the persisted high-water, retires every old page and writes one replacement
root for both bucket and range changes. All writes drain before the cardinality audit and virtual-partition
conversion, which loads full entity indexes. Publication remains the final step. The loaders perform no
conversion and refuse a marked paged temporal root as an unmigrated catalog. Price validity was already converted
by its versioned readers: it is always temporal and inline in the price root.

**Rejected alternative — also convert inline range companions in the migration.** This would centralize both
layouts in one sweep. **Rejected because** a record that can convert itself belongs in its serialVersionUID-versioned
reader; the migration takes the conversions spanning records. Converting both in the reader and migration would
multiply real thresholds by 1000 twice. `LegacyRangeIndexConversion` in `evita_store_server` owns the conversion;
`RangeIndex` keeps only a generic `supersedePersistedPages` hook for carrying the old stream baseline into a fresh tree.

### Stored values beyond the window: who clamps them (#1696, 2026-10-20)

A 2026.2 catalog can hold `OffsetDateTime.MAX` / `MIN` and `LocalDateTime.MAX` / `MIN` everywhere a temporal
value goes - filter, unique and owner sort indexes, cardinality counters, and the entity and reference bodies -
and likely does: its gRPC converter decoded every moment of year 9999 / year 1 as such a sentinel. The indexes
were never in question: `Migration_2026_3` clamps their keys, by the write path's rule, in front of the
normalizer. The fork was the bodies. Clamping only the indexes leaves a body holding the raw sentinel, and every
path that feeds a body value to an index then overflows: removal, entity deletion, a scope move such as
ARCHIVED to LIVE, a schema change that makes the attribute indexed.

- **Chosen - D, a versioned body reader.** `AttributeValue`'s `serialVersionUID` is bumped and
  `AttributeValueSerializer_2026_2` reads the old one: the same layout, with an out-of-window temporal value
  (scalar or array element) clamped on read. It covers every path that reads a body, in every scope, at no
  upgrade cost, and it is the seam the codebase already uses for bodies (`EntityBodyStoragePartSerializer_2024_11`,
  `ReferencesStoragePartSerializer_2025_6`). Its cost is permanence: compaction copies untouched records byte for
  byte (`OffsetIndex#copySnapshotTo`), so a body keeps the old version until its entity is next written, and the
  reader cannot be retired with the migration. Each value of an old body pays one type check on read.
- **Declined - A, the migration rewrites the bodies.** *Rejected because:* a scan of every attribute and
  reference part of every collection declaring a temporal attribute, at upgrade, with a cost nobody measured, and
  body-writing machinery no migration step has - to reach the state D reaches by reading. Revisit if the reader's
  permanence ever blocks something.
- **Declined - B, clamp in `FilterIndex#getNormalizer` and the unique canonicalization.** The smallest complete
  fix, about six lines. *Rejected because:* it is permanent core-path code for legacy data, which the
  maintainer's rule keeps in migrations and versioned readers, and bodies would keep returning the raw sentinel
  while new writes return the clamped edge.
- **Declined - C, rewrite only the bodies the clamped index entries name.** *Rejected because:* incomplete. An
  entity in a scope that does not index the attribute has no index entry, keeps its raw body, and overflows the
  moment it moves into a scope that does.

Two shapes cannot be clamped without losing information, so the upgrade refuses them before anything is
published (the catalog stays on protocol 6):
- two owners of a unique attribute whose different values load as one key - clamped onto one edge of the
  window, or truncated onto one millisecond - in a standalone unique index and in a unique attribute folded
  onto its filter tree, which the unique pre-pass skips. The folded check walks the tree of every
  `OffsetDateTime` and `LocalDateTime` attribute whether or not it holds a key beyond the window, and of every
  unique `LocalTime` attribute. Values that meet only through the truncation were a gap older than #1696: the
  former load repair merged the two buckets, uniting their records, and the folded unique view answered a lookup
  with the first of them, so the other owner silently lost its value. Leaving that gap to a later change was the
  alternative. *Rejected because:* it keeps a silent winner on the folded path, the outcome
  [2026-10-08-unique-indexes-normalize-like-filter-indexes](2026-10-08-unique-indexes-normalize-like-filter-indexes.md)
  replaced with a loud refusal on the standalone one, and the check is the same walk the clamp already makes;
- a reference partition keyed by an out-of-window representative value. The key is persisted as a whole and
  names storage parts, so re-keying it means renaming them. The message tells a gRPC client to use a year before
  9999, which 2026.2 would turn into the sentinel again.

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
  `OffsetDateTime` values. **Superseded on 2026-10-06** for the unique trees: they now key every value
  through the filter normalizer and take their column from `forFilterKey` - see
  [2026-10-08-unique-indexes-normalize-like-filter-indexes](2026-10-08-unique-indexes-normalize-like-filter-indexes.md).
  The split itself stands for `forKey`'s remaining raw-key caller.
- **Range sentinels are now `Long.MIN_VALUE` / `Long.MAX_VALUE`**, the same constants all five `NumberRange`
  subtypes and `addValidity`'s always-valid price already use. `toComparableLong` saturates one step inside
  them, so an extreme *closed* bound (`OffsetDateTime.MAX` is legal) can never be read back as an open one.
- **The legacy threshold conversion is not injective.** `LegacyRangeIndexConversion` maps legacy open
  sentinels onto `Long.MIN_VALUE` / `Long.MAX_VALUE`, where border points already sit, and unions colliding
  `starts`/`ends` bitmaps. **Changed on 2026-10-26:** versioned readers perform inline conversion, gated on the
  persisted plain type; the storage migration performs paged conversion. Numeric range bounds remain unchanged.
- **Only paged temporal companions carry a migration marker.** `isSecondGranularityRangePages` is transient
  provenance from a legacy root reader after checking its persisted type. It identifies unversioned pages that
  the protocol 6 → 7 migration must replace. Inline companions arrive converted and carry no marker, including
  supported 2025.5 filter records, which always declare a type.
- **The migration repairs recognized legacy keys; loads reject corruption.** Originally the
  filter and histogram reload merged sub-millisecond buckets and re-sorted recognized second-granularity range
  sequences. **Changed on 2026-10-26:** `LegacyInvertedIndexCorrection`, called only by `Migration_2026_3`, now
  performs that repair before publication. Colliding buckets union their records across leaf boundaries; surviving
  buckets retain their original value ids and absorbed ids retire. Rewritten paged trees receive fresh pages above
  the persisted high-water, a new root and removals of every old leaf. Canonical trees emit no parts. Production
  loads apply no function to stored bucket keys and never silently merge duplicates. Unrecognized paged inversions
  still reach the existing corruption diagnostics; the second-granularity guard is not relaxed.
- **The migration writes every bucket-key repair; none stays pending on load.** **Changed on 2026-10-12 for owner
  sort indexes:** `Migration_2026_3#rewriteLegacyOwnerSortIndexes` writes the corrected tree immediately through
  `LegacyOwnerSortIndexCorrection`. **Changed on 2026-10-26 for filter and histogram BUCKET trees:** their key
  repair is also fully written by the migration, with no dirty restore state. Ported coverage lives in
  `Migration_2026_3_InvertedIndexCorrectionTest` and `Migration_2026_3_FilterRewritePersistenceTest` (a later write,
  a sibling written first, a leaf split, a paged `DateTimeRange` on both axes and another restart).
  `IndexComponent#announceKeys` stays: commit-time merges can carry dirty trees by reference. Baseline capture
  must not collect and discard those ordinary pending changes;
  `EntityIndexBaselineCaptureTest` pins this independent contract.
- **The merge target must be the array the page retains.** The old page-by-page collapse could write a cross-page
  merge through the pre-copy array of a trimmed page, silently losing the absorbed records. **Changed on
  2026-10-26:** the migration merges into the retained bucket list before materializing any page arrays, so no
  abandoned page copy can receive a later merge. The combined intra-page / cross-page cases and surviving ids are
  retained in `Migration_2026_3_InvertedIndexCorrectionTest`.
- **The upgrade's clamp must sit in front of every normalizer that meets a stored temporal key, owner sort
  included** (`LegacyOwnerSortIndexCorrection#createClampingNormalizer`, `Migration_2026_3`). Owner sort
  indexes load without overflowing, so skipping them looks safe - but once the body reader clamps, a removal
  probes the sort index with the clamped value and fails "Value … is not present in the sort index". The
  cardinality audit clamps both the counter and the bucket for the same reason: one side alone reports damage
  that does not exist. **Changed on 2026-10-26:** filter and histogram bucket keys are clamped, canonicalized and
  merged in one migration rewrite, before the cardinality audit; no load-time key canonicalizer remains. A rewritten
  legacy `DateTimeRange` root retains its already-converted inline companion; every marked paged companion is
  converted by the migration, including companions of roots whose bucket keys need no correction. A root requiring
  both changes is replaced once; no threshold conversion remains in the production load path.
- **The body reader and the migration keep two clamp dispatchers on purpose.** `LegacyOwnerSortIndexCorrection`
  leaves with `Migration_2026_3`; `AttributeValueSerializer_2026_2` cannot. Sharing one would tie the permanent
  class to the removable one. Both delegate to `EvitaDataTypes#clampToSupportedWindow`, the write path's rule.

## Verification

Full suite green: `Tests run: 22665, Failures: 0, Errors: 1` under `-P unitAndFunctional,largeMachine`; the
single error is `ExportS3ServiceTest` reporting "Could not find a valid Docker environment", which is
environmental and pre-existing.

New and extended coverage, by name: `AttributeMillisecondPrecisionFunctionalTest`, `EvitaDataTypesTest`,
`BaseConstraintTest`, `UpsertAttributeMutationTest`, `InitialAttributesBuilderTest`, `DateTimeRangeTest`,
`LongValueColumnTest`, `Migration_2026_3_InvertedIndexCorrectionTest`, `UniqueIndexTest`, `GlobalUniqueIndexTest`,
`AttributeIndexLoaderTest`, `LegacyRangeThresholdScaleSerializerTest`, `EvitaDataTypesConverterTest`,
`LeafIndexHeapSizeTest`, `ColumnHeapSizeTest`.

**Changed on 2026-10-26 — threshold conversion ownership.** `LegacyRangeThresholdScaleSerializerTest` covers
all three inline filter readers, the histogram reader, scalar and array routing, numeric controls, open-bound merges
and unchanged price validity conversion. `Migration_2025_6_RangeThresholdTest` pins typed 2025.5 records
through the referenced-id rewrite without converting their thresholds again. `Migration_2026_3_RangeThresholdTest`
asserts concrete millisecond thresholds through protocol 7 and another restart, fresh pages above high-water,
old-page retirement, cross-page sentinel merges and conversion before single-owner partition demotion.
Both loader suites refuse an unmigrated
paged temporal companion by name. `Migration_2026_3_FilterRewritePersistenceTest` also checks unchanged physical
addresses for canonical millisecond roots and pages.

**Stored values beyond the window (#1696).** `Migration_2026_3_OutOfWindowTemporalKeyTest` (93 tests) builds a
2026.2-shaped catalog - the indexes rewritten to the raw sentinels, the bodies written through Kryo with the
2026.2 `serialVersionUID` - for `OffsetDateTime` and `LocalDateTime`, `MAX` and `MIN`. It covers filter
(inline, paged, across leaf pages), unique (owner and global), owner sort (plain, paged and compound), reference
attributes, arrays, ARCHIVED to LIVE, entity removal after the upgrade, the audit, and both refusals - the
folded-unique one also for a clamped key that meets another owner's in-window key only after truncation, and
for two owners whose `OffsetDateTime`, `LocalDateTime` or `LocalTime` values differ only below the millisecond
(with the standalone owner unique index as the control). On the folded path that case was red, "nothing was
thrown", for `OffsetDateTime` before the detector compared unclamped keys and for `LocalTime` before the walk
admitted its trees; the standalone control was green throughout.
`AttributeValueSerializer_2026_2Test` (9) pins the reader, null array elements included. Before the fix, 20 of
the original 38 tests were red. Each clamp and guard was proven by a counterfactual (removed, the expected tests
red, restored). Without the body reader's clamp, 40 of the 65 tests at that point went red, mostly `long overflow`
on removal. On the finished branch the functional suite ran `Tests run: 29398, Failures: 0, Errors: 1` (the same
Docker-only `ExportS3ServiceTest`), and `EvitaBackwardCompatibilityTest` passed 5 of 5 over the released 2025.1 to
2026.2 datasets - the 2026.2 one upgraded from storage protocol 6 to 7 without a refusal.

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
- **Decided not to fix — legacy `unique` / `uniqueGlobally` temporal values are never retroactively
  truncated.** *(Superseded on 2026-10-06: the unique trees now normalize every value, `Migration_2026_3`
  re-keys the persisted ones, and a colliding pair refuses the upgrade loudly instead of last-wins - see
  [2026-10-08-unique-indexes-normalize-like-filter-indexes](2026-10-08-unique-indexes-normalize-like-filter-indexes.md).)* Unique indexes store raw values and `AttributeValueSerializer` reads them verbatim, so a value
  written before this change keeps its sub-millisecond digits while every probe against it is cut to
  milliseconds. A new write landing in the same millisecond therefore does **not** collide, and the unique
  index durably holds two entries that this record's own rule says are one value. The damage, should it ever
  surface, is specific: a lookup by the legacy entity's own value returns the *other* entity, and the legacy
  entity can no longer be saved at all, because its own rewrite truncates onto the twin and throws
  `UniqueValueViolationException`. The scope is narrower than it sounds — an attribute that is unique **and**
  filterable was considered safe, its unique role being a view folded onto the FILTER tree that the former reload
  repair covered, so only globally-unique and unique-but-not-filterable temporal attributes were thought exposed;
  a catalog created after this change cannot contain such a pair at all. *(Corrected on 2026-10-20, #1696: the folded
  attribute was not safe. The former reload repair merged the two buckets into one holding both owners, and the
  folded view answered a lookup with the first of them. `Migration_2026_3` now refuses such a pair before its own
  bucket correction can merge it - see
  "Stored values beyond the window" above.)*
  **Rejected because** no known dataset declares a temporal attribute as a unique identifier, which puts the
  realistic exposure at roughly zero, while the fix is not small: a normalizer at the unique-index boundary
  applied at *four* entry points — register, unregister, lookup and reload — on `OwnerUniqueIndex` as well as
  `GlobalUniqueIndex`, and covering `LocalTime` or the sibling truncation gap reappears on unique trees. The
  cost is out of proportion to the benefit. Revisit only if a catalog is found that declares a temporal
  attribute unique.
  **If it ever is implemented, the rule is last-wins with a warning.** Of two legacy keys collapsing into one
  millisecond the larger raw key is by construction the later timestamp, so "last" is well defined without any
  write-order information the index does not carry; the loser is dropped and logged. Failing the load with a
  diagnostic naming both entities was the alternative and lost: it refuses to open a catalog that was valid
  under the previous contract, which is a worse outcome than a warning for a collision this rare.
  **The correction must merge before the loader sees the page.** Canonicalising stored keys *without* merging
  neighbours that become equal hands `bulkLoadPage` two identical adjacent keys, which its "keys must be strictly
  ascending and distinct" invariant refuses. The migration now performs both operations before writing the tree;
  production loads restore those keys verbatim and reject duplicates.
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
- **Temporal schema *defaults* are still neither truncated nor range-checked.** Still live, and the one
  remaining hole in the "stated once, enforced at the boundary" claim this record makes.
  `EvitaDataTypes#toTargetType` returns an already-correctly-typed value at its first line, so
  `withDefaultValue(OffsetDateTime.MAX)` is accepted in silence and then throws on the first unrelated
  entity upsert that applies the default. Sites: `ModifyAttributeSchemaDefaultValueMutation` (111, 208,
  247), `AbstractAttributeSchemaBuilder:125`, and schema *construction* itself at `AttributeSchema:541` —
  the last easy to miss, being neither a mutation nor a builder. It reaches gRPC through the converter.
  **The fix carries a compatibility dimension**: `mutate` re-runs on WAL replay, so range-checking
  unconditionally would make a previously accepted schema refuse to load. Three options, none yet chosen —
  (a) truncate and range-check everywhere, accepting the replay risk; (b) truncate always but range-check
  only newly supplied values; (c) leave it, and document that a default is validated when first applied.
  (b) is the standing lean.
  **Changed on 2026-10-05 (`58916f5a1c`): the throw is gone; the gap is not.** Read from the code, not run:
  - All five sites above still go through `EvitaDataTypes#toTargetType`, so the schema keeps a default
    exactly as given.
  - Applying a default to an entity builds an `UpsertAttributeMutation` (`ContainerizedLocalMutationExecutor:2308`,
    and `:2902` for a reference attribute), and that mutation's constructor now truncates and clamps the
    value. `withDefaultValue(OffsetDateTime.MAX)` therefore no longer fails the first upsert.
  - What remains is a schema reporting a default that none of its entities hold.
  - With no range check left, a replayed schema can no longer be refused, so options (a)-(c) now differ only
    in whether the schema stores the truncated and clamped value.
- **`MAX_SUPPORTED_EPOCH_SECOND` contradicts the limit this record documents.** It is
  `Long.MAX_VALUE / 1000 - 1` (`EvitaDataTypes:161`), so `Instant.ofEpochMilli(Long.MAX_VALUE)` is
  rejected although it is exactly representable as epoch milliseconds. Write and query reject alike, so
  there is no probe-versus-stored asymmetry and nothing can be silently wrong — but the wording here, and
  the exception message at `:1154`, both promise a millisecond limit the check does not implement.
  **Changed on 2026-10-05 (`58916f5a1c`):**
  - Nothing is rejected any more, and the exception message is gone.
  - The window still stops short of what epoch milliseconds can express. The bounds are
    `MIN_SUPPORTED_EPOCH_SECOND = Long.MIN_VALUE / 1000 + 1` and
    `MAX_SUPPORTED_EPOCH_SECOND = Long.MAX_VALUE / 1000 - 1` (`EvitaDataTypes:159`, `:163`), which leaves
    808 ms out at the top and 1,808 ms at the bottom.
  - `Instant.ofEpochMilli(Long.MAX_VALUE)` is therefore clamped to `LATEST_SUPPORTED_INSTANT` (`:173-174`)
    instead of being refused, and every value in those last fractions of a second lands on the edge.
  - Write and query clamp alike, so there is still no probe-versus-stored asymmetry.
- **`InitialAttributesBuilder#mutateAttribute` keeps the read-back mismatch that `f2db956ca` fixed for
  `setAttribute`.** At `:287` a value entering through a custom `AttributeMutation` is not normalized on
  accept, so a read-back taken before `upsertVia` can differ from what would be stored. Reaching it needs
  a hand-written mutation, which is why it outlived the sweep that fixed its sibling.
- **Associated data bypasses truncation entirely, and that is a decision rather than an oversight.**
  `UpsertAssociatedDataMutation` (52, 54) and `ComplexDataObjectConverter:124` never truncate. Associated
  data is never indexed, so there is no probe-versus-stored mismatch to create and nothing here can go
  silently wrong; the price is that "temporal precision is millisecond" is not true of associated data.
  Recorded so the inconsistency is not later mistaken for a site that was missed.
- **`AttributeValueSerializer_2026_2` outlives `Migration_2026_3`.** No release can assume that every body
  2026.2 wrote has been rewritten, so the reader stays registered for as long as such catalogs are supported -
  like `AttributeCardinalityIndexStoragePartSerializer_2026_2`, and for the same reason.
- **Parked, by the maintainer's decision: a `DateTimeRange` closed at a bound beyond the window.** 2026.2
  compared range bounds in seconds, so a closed bound `OffsetDateTime.MAX` and the legacy open sentinel are both
  about 3.16e16, and the storage-side conversion maps both onto `OPEN_TO_THRESHOLD`. Decoded from the body, the closed
  bound gives `SATURATED_TO_THRESHOLD`, so a removal finds no point and silently leaves the record's end point in
  the range index (`RangeIndex#removeRecord`). gRPC never produced this shape - its range converter passes real
  epoch seconds - only a Java client writing `OffsetDateTime.MAX` as a closed bound. Stored thresholds cannot
  tell the two bounds apart; only the bodies can, at the cost of the full scan option D avoids. Not to be solved;
  listed on #1696.
- **Hypotheses that were checked and found harmless**, listed so they are not re-proposed: reload
  cross-page aliasing (fixed by `f9bb05c02`); value-id compaction,
  where survivors keep their original ids; the unnecessary-repair path, where `collapsed` stays false and
  the index is never marked dirty; the `forKey`/`forFilterKey` split, whose production callers are
  correctly separated (`InvertedIndex:323` versus `UniqueIndexBPlusTreeSupport:151`) — the unique-index
  gap above is a legacy canonicalization problem, **not** a key-class routing error; and WAL replay and
  reflected-reference defaults, both of which rebuild an `UpsertAttributeMutation` and therefore do reach
  normalization.

## Related work

- [2026-09-03-content-sized-value-tree-columns](2026-09-03-content-sized-value-tree-columns.md) — the sibling
  half of issue #1486 and the reason this one was findable: once every column was sized to its live content,
  the second parallel array in `InstantValueColumn` was the largest remaining oddity in the family.
- [2026-08-10-stored-value-normalization-split](2026-08-10-stored-value-normalization-split.md) — same
  normalizer, same `ValueColumnFactory`. Its *decision* stands; one factual conclusion in its consequences —
  that unique indexes were checked and unaffected — was wrong, and is corrected in place with a pointer here.
- [2026-07-18-paged-index-corruption-and-flush-failure-boundary](2026-07-18-paged-index-corruption-and-flush-failure-boundary/README.md)
  — sets the fail-fast rule for a corrupt paged index. Recognized legacy bucket keys are corrected by the migration
  before load; production loads retain that fail-fast rule. The guard is described in *Key technical details*.
- [2026-08-05-schema-handling-write-path-optimizations](2026-08-05-schema-handling-write-path-optimizations.md)
  — the same write path through attribute mutations that the boundary truncation sits on.
- [2026-09-21-cardinality-counter-normalized-keys](2026-09-21-cardinality-counter-normalized-keys.md) — the
  migration now corrects stored bucket keys before the cardinality audit. An earlier revision concluded that
  temporal cardinality counters were unauditable from parts; that is no longer true. The audit normalizes the
  stored bucket as well as the counter key, so both sides land in one key space whatever wrote them, and
  `OffsetDateTime` is audited like every other collision-prone type.

## Timeline

- **2026-09-03** — question raised about `InstantValueColumn`'s nanosecond array; precision audit across the
  five client surfaces; boundary truncation, the epoch-millisecond column and the three pre-existing defects
  land (`7057bd973`, `03ca79a1e`, `f2db956ca`).
- **2026-09-03** — `DateTimeRange` moved to millisecond comparison, offset-independent sentinels and the
  deletion of the `meta` array (`9a3e90c93`).
- **2026-09-04** — adversarial review round: the merge-target aliasing in the former page-by-page bucket collapse
  (`f9bb05c02`) and the missing released-2026.2 histogram reader (`908387993`).
- **2026-09-04** — measured against a restored production catalog backup; record written.
- **2026-10-05** — a temporal value outside the window is clamped to its edge instead of being rejected, on
  both paths and at the gRPC boundary (`58916f5a1c`, merged with PR #1738 on 2026-10-20).
- **2026-10-20** - #1696: the stored sentinels are clamped too. `Migration_2026_3` clamps every stored temporal
  key and refuses the two shapes it cannot clamp; `AttributeValueSerializer_2026_2` clamps the bodies on read.
  Reviewed by an advisor and Codex before implementation; options A-D above. The same change refuses folded
  unique values that meet only through the truncation, a gap the consequence above had called safe.
- **2026-10-26** - filter and histogram BUCKET key repair moved from production restore paths into the existing,
  unreleased protocol 6 → 7 migration; guarded range reordering, cross-page record unions and retained value ids
  now reach disk before publication. Unique loads still convert declared-type persisted values to type-level keys.
- **2026-10-26** - inline temporal range companions moved to their versioned readers, paged companions to
  `Migration_2026_3`; loaders retain no threshold conversion. Price validity remains reader-side, and ordinary
  commit-time pending changes still require baseline key announcements.
