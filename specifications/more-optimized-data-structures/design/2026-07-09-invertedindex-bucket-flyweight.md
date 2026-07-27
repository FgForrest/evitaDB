# InvertedIndex leaf-page buckets — use the compact `ValueToRecordPrimitive` flyweight for single-record buckets on the write path

Issue: #760 (ALIVE churn allocation refinement, follow-up to the RoaringBitmap frozen-array COW
fix). Not a correctness gap — `ValueToRecordBitmap` is already correct for every bucket shape; this
targets a **construction cost** paid even when a much cheaper, already-existing representation
would do.

Status: IMPLEMENTED and COMMITTED (`a97ba014e`), measurement gate CONFIRMED. Steps 1-4 (producer
split, storage-part type widening, discriminator-byte serializers for `BucketLeafPagePartSerializer`
+ the bespoke `SortIndexLeafPagePartSerializer`, loader/`fromPersistedPages` widening) are done and
compile clean. Test plan (§7) largely covered: producer element-type unit test, per-family serializer
round-trips (single/multi/mixed/empty/compound, `HistogramIndexLeafPagePartSerializerTest` added new
since none existed before), the primitive-smaller-than-bitmap byte-count check, and the real-loader
round-trip — but that last one only exists for SORT (`SortIndexOwnerPagingRoundTripTest`);
FILTER/HISTOGRAM's paged-reload path is exercised only transitively (registered-Kryo unit round-trip +
the functional sweep below, whose datasets are mostly too small to force PAGED). Full regression
sweep (`attribute|indexing|storage|serialization`, unitAndFunctional profile): 6827 tests, 0
failures, 0 errors, 25 skipped. §7's measurement gate (async-profiler re-measure of
`EvitaWarmUpInsertionTest` at the real 100 MB/0.8 config) now run and confirmed —
`docs/reports/2026-07-09-invertedindex-bucket-flyweight-remeasure.md`: `ValueToRecordBitmap.<init>`
write-path allocation eliminated (20.49 GB → 524 KB, >99.99%), total ALIVE-churn allocation down
24.35% (105.41 GB → 79.75 GB, well above the ~11% upper-bound estimate below), RoaringBitmap CPU time
down ~82% in absolute samples, no regressions in any other category.

Author dialogue: Johnny + Claude. Problem originally surfaced in
`docs/reports/2026-07-09-warmup-test-remeasure-real-config.md` (RoaringBitmap category, 20.07% of
total ALIVE-churn allocation) with an initial mechanism mis-attribution to file compaction,
corrected the same day after direct code reading (`InvertedIndex.collectChangedPages` fires
**per commit**, gated per-B+-tree-leaf dirty flag — not per `OffsetIndex.compact()`). Investigated
in depth (own reading + a dedicated research pass) before drafting this plan; findings below are
first-hand code, not summary.

## 1. Problem & scope

`InvertedIndex.collectChangedPages()` (`InvertedIndex.java:980-1000`) builds a `LeafPage` — a
nested record, `InvertedIndex.java:1036`: `record LeafPage(int pageSequence, ValueToRecordBitmap[]
buckets)` — for every changed B+-tree leaf, on **every commit** that dirties the tree:

```java
(pageSequence, handle) -> {
    final BucketCursor cursor = handle.cursor();
    final List<ValueToRecordBitmap> pageBuckets = new ArrayList<>();
    while (cursor.next()) {
        final Serializable value = (Serializable) cursor.value();
        pageBuckets.add(
            cursor.isSingle()
                ? new ValueToRecordBitmap(value, cursor.singleRecordId())
                : new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records())
        );
    }
    return new LeafPage(pageSequence, pageBuckets.toArray(ValueToRecordBitmap[]::new));
}
```

Every bucket — including `cursor.isSingle()` ones — is force-built as a `ValueToRecordBitmap`. For
a single record, that constructor (`ValueToRecordBitmap(Serializable, int...)`, the varargs single-id
overload) builds a full `BaseBitmap → PersistentRoaringBitmap → RoaringArray → ArrayContainer` tower
for **one integer**. A fresh async-profiler allocation pass (real committed test config, 100 MB/0.8,
`docs/reports/2026-07-09-warmup-test-remeasure-real-config.md`) attributes **up to ~11% of total
ALIVE-churn allocation** to the leaf types this single-record chain produces (`RoaringArray` 4.25% +
`PersistentRoaringBitmap` 3.15% + `Container[]` 2.13% + `ArrayContainer` 1.56%, all under
`collectChangedPages` stacks), out of **35.55% of total allocation happening somewhere under
`collectChangedPages`** overall.

**Caveat, stated plainly so this plan isn't oversold**: the ~11% figure is an *upper bound*, not a
proven single-record-only cost. The same profile shows `TransactionalBitmap` at 2.09% under the
same `collectChangedPages` stacks — that's the multi-record (`cursor.records()`) branch, which this
plan does **not** touch, and its presence means at least some of the `RoaringArray`/`Container[]`
allocation attributed above could also originate from multi-record bucket handling rather than
single-record construction exclusively (a bitmap tower's leaf types are the same regardless of which
branch built it — the profile's leaf-type attribution can't distinguish the two branches by itself).
**The actual single-vs-multi bucket ratio for this workload has not been measured.** A cheap
instrumented counter (increment on `cursor.isSingle()` vs the `else` branch inside
`collectChangedPages`, log the ratio for one run) would close this gap in minutes and should be run
before or during implementation to confirm how much of the ~11% this plan actually recovers — for a
`unique`/`filterable` schema mix like this test's, singles are plausibly the majority (a `unique`
attribute is single-record by definition; a `filterable` one may or may not be, depending on
value cardinality), but "plausibly" is not "measured," and the doc should not be read as a promise of
capturing the full 11%. The rest of the 35.55% under `collectChangedPages` (`byte[]`/`char[]`/
`String`-family value-encoding costs, plus the multi-record `TransactionalBitmap` cost) is out of
scope for this plan regardless.

**A cheaper representation already exists and is already used — one call away, on the read path
only.** `InvertedIndex.materializeBucket` (`InvertedIndex.java:250-257`), used by the query/formula
path, already does the right split:

```java
private static ValueToRecord materializeBucket(@Nonnull BucketCursor cursor) {
    final Serializable value = (Serializable) cursor.value();
    if (cursor.isSingle()) {
        return new ValueToRecordPrimitive(value, cursor.singleRecordId());   // ~10x lighter
    }
    return new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records());
}
```

`collectChangedPages` (the write/flush path) never got the same treatment. This plan closes that
gap.

**Not the whole allocation story.** A separate, independent inefficiency also exists: dirtiness is
tracked per B+-tree leaf (256 buckets each), so a commit that touches one bucket rebuilds up to 256.
That is a bigger, more invasive fix (touches the B+-tree's dirty-bit mechanism) and is **out of
scope for this plan** — noted here only so it isn't mistaken for solved once this plan lands.

## 2. Why this is lower-risk than it might look — the read path already proves it out

Three facts, each independently verified by direct code reading, converge to make this a
well-precedented change rather than a novel one:

**2.1 — No aliasing / MVCC risk.** `LeafPage`/`FilterIndexLeafPagePart`/`HistogramIndexLeafPagePart`/
`SortIndexLeafPagePart` are throwaway serialization DTOs: built fresh inside `collectChangedPages`'s
lambda, immediately handed to `sink.addChangeToStore(...)` and Kryo-serialized this same commit, then
discarded. They are never retained as live index state (unlike the `PersistentRoaringBitmap.clone()`
case addressed by `docs/design/2026-07-08-roaringbitmap-cloning.md`, where the clone becomes the next
MVCC version).

**2.2 — The loader is already polymorphism-agnostic.** `InvertedIndex.fromPersistedPages`
(`InvertedIndex.java:487-526`), which reconstructs the live B+-tree from persisted leaf pages, reads
only `bucket.getValue()`/`bucket.getRecordIds()`/`.size()`/`.getFirst()`/`.getArray()` — all off the
`ValueToRecord`/`Bitmap` interface surface, zero `ValueToRecordBitmap`-specific calls:

```java
final Bitmap recordIds = bucket.getRecordIds();
final Comparable value = (Comparable) bucket.getValue();
if (recordIds.size() == 1) {
    pageTree.addRecord(value, recordIds.getFirst());
} else {
    pageTree.addRecord(value, recordIds.getArray());
}
```

It doesn't care whether the source was originally single- or multi-record — it re-derives that from
`recordIds.size() == 1` and re-splits into the tree's own column representation independently.
Widening its parameter type from `ValueToRecordBitmap[][]` to `ValueToRecord[][]` needs **zero
logic change** to this method's body — `ValueToRecordPrimitive.getRecordIds()` already returns a
`SingleRecordBitmap` of size 1, satisfying the existing branch unchanged.

**2.3 — The polymorphic array type is already load-bearing production code, just on the other side.**
`InvertedIndexSubSet` (`evita_engine/src/main/java/io/evitadb/index/invertedIndex/InvertedIndexSubSet.java`)
already stores and returns `ValueToRecord[]` (not `ValueToRecordBitmap[]`) as its `histogramBuckets`
field, populated via `materializeBucket`'s split — its own javadoc: *"Each element may be either the
multi-record `ValueToRecordBitmap` or the compact single-record `ValueToRecordPrimitive`... a
single-record bucket stays a bare-`int` primitive."* Three production consumers already read this
polymorphic array directly (`ReevaluateExpressionExecutor.java:759,910,1139`). **`LeafPage`/
`collectChangedPages` — the write path — is the only remaining holdout still hardcoded to the heavy
type.** This plan brings the write side in line with a pattern the read side has used all along.

## 3. Blast radius — three sibling page families, one shared producer

`LeafPage` has exactly one producer (`InvertedIndex.collectChangedPages`) and **three** consumers —
not just `FilterIndex`. All three read `page.buckets()` off their own embedded `InvertedIndex`
instance and wrap it in their own storage-part type:

| consumer | storage-part | serializer |
|---|---|---|
| `FilterIndex.java:1180` | `FilterIndexLeafPagePart` (`ValueToRecordBitmap[] buckets` field) | `FilterIndexLeafPagePartSerializer extends BucketLeafPagePartSerializer<...>` (shared base) |
| `HistogramIndex.java:319` | `HistogramIndexLeafPagePart` (same field shape) | `HistogramIndexLeafPagePartSerializer extends BucketLeafPagePartSerializer<...>` (shared base) |
| `OwnerSortIndex.java:474` | `SortIndexLeafPagePart` (same field + `comparatorBaseLength`) | `SortIndexLeafPagePartSerializer` — **bespoke**, does NOT extend the shared base; unwraps compound sort values component-by-component and writes `bucket.getRecordIds()` directly, bypassing `ValueToRecordBitmapSerializer` entirely (compound `ComparableArray` values aren't Kryo-registered, so `writeClassAndObject` would garble them) |

All three source pages from the identical `collectChangedPages` producer, so **fixing the producer
fixes the allocation for all three index kinds in one place** — but the two serializer families
(shared `BucketLeafPagePartSerializer` for Filter/Histogram, bespoke for Sort) each need their own
read/write strategy update, done in parallel, not sequentially dependent.

## 4. The fix

### 4.1 `collectChangedPages` — mirror `materializeBucket`'s split

```java
(pageSequence, handle) -> {
    final BucketCursor cursor = handle.cursor();
    final List<ValueToRecord> pageBuckets = new ArrayList<>();
    while (cursor.next()) {
        final Serializable value = (Serializable) cursor.value();
        pageBuckets.add(
            cursor.isSingle()
                ? new ValueToRecordPrimitive(value, cursor.singleRecordId())
                : new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records())
        );
    }
    return new LeafPage(pageSequence, pageBuckets.toArray(ValueToRecord[]::new));
}
```

`LeafPage.buckets` widens from `ValueToRecordBitmap[]` to `ValueToRecord[]`.

### 4.2 Storage-part classes — type widening only, no behavior change

`FilterIndexLeafPagePart`, `HistogramIndexLeafPagePart`, `SortIndexLeafPagePart`: widen the
`buckets` field + constructor parameter + `@Getter` return type from `ValueToRecordBitmap[]` to
`ValueToRecord[]`. Pure carriers — no logic inside these classes reads bucket internals.

### 4.3 Serializer strategy — recommend a page-internal discriminator byte, not a new Kryo registration

Two viable mechanisms were found; recommending the lower-ceremony one.

**Recommended: a single discriminator byte per bucket, hand-rolled inside the existing
`writePayload`/`readPayload` loop.** No precedent for exactly this in the codebase, but it's the
natural extension of the existing "plain `writeVarInt` length prefix + loop" shape
`BucketLeafPagePartSerializer` already uses (`InvertedIndex`'s own wire framing, not a Kryo
mechanism) — and critically, it **does not touch the global Kryo class-registration list**
(`IndexStoragePartConfigurer.java`), keeping the change fully self-contained to the two serializer
files being edited:

```java
// BucketLeafPagePartSerializer.writePayload
for (final ValueToRecord bucket : buckets) {
    if (bucket instanceof ValueToRecordPrimitive primitive) {
        output.writeByte(0);
        kryo.writeClassAndObject(output, primitive.getValue());
        output.writeVarInt(primitive.getRecordId(), true);
    } else {
        output.writeByte(1);
        kryo.writeObject(output, (ValueToRecordBitmap) bucket);
    }
}

// readPayload
for (int i = 0; i < bucketCount; i++) {
    buckets[i] = input.readByte() == 0
        ? new ValueToRecordPrimitive((Serializable) kryo.readClassAndObject(input), input.readVarInt(true))
        : kryo.readObject(input, ValueToRecordBitmap.class);
}
```

This also shrinks the **on-disk** footprint for single-record buckets, not just the transient
in-memory cost: today a single-record bucket's `ValueToRecordBitmap` is serialized as a full
`TransactionalBitmap`/`PersistentRoaringBitmap` (roaring container framing overhead even for one
element); the primitive form is one discriminator byte + the value + one varint record id. A
secondary, smaller win on top of the allocation fix — worth confirming with a quick byte-count check
during implementation, not a headline claim here.

**Alternative (documented, not recommended): Kryo `writeClassAndObject`/`readClassAndObject` on the
bucket itself**, requiring `ValueToRecordPrimitive` to be registered in `IndexStoragePartConfigurer.java`
(currently unregistered — confirmed by grep, zero hits). This is the codebase's own idiom for
polymorphism elsewhere (`ValueToRecordBitmapSerializer`'s own value field, `TrieNodeSerializer`'s
generic payload), and per the file's existing convention ("brand-new record type with no
backward-compatible reader... appended last to keep the preceding registration ids stable",
`IndexStoragePartConfigurer.java:190-196`) a new append-only registration is low-risk on this
unreleased branch. Rejected as the primary recommendation only because it's marginally more ceremony
(a shared, sensitive global list) for no behavioral benefit over the self-contained discriminator
byte — but either is acceptable; flagging the choice for Johnny rather than presenting it as settled.

**`SortIndexLeafPagePartSerializer` (bespoke)**: apply the identical discriminator-byte strategy
independently inside its own hand-rolled loop (it already writes `bucket.getRecordIds()` directly
rather than delegating to `ValueToRecordBitmapSerializer`, so the primitive branch is even simpler
there — write the discriminator, the unwrapped comparable value exactly as today, then either the
bitmap object or the bare record-id varint).

### 4.4 Loader classes — signature widening only

`AttributeIndexLoader.java:384` (`loadInvertedIndex`), `AttributeIndexLoader.java:519`
(`fetchSort`), `HistogramIndexMapLoader.java:233` (`reloadOwnerFilterIndex`): change
`final ValueToRecordBitmap[][] perPageBuckets = new ValueToRecordBitmap[...][];` to
`ValueToRecord[][]`. `InvertedIndex.fromPersistedPages`'s parameter widens to `ValueToRecord[][]` —
per §2.2, its body needs **no other change**.

### 4.5 What does NOT change
- `ValueToRecordBitmap`, `ValueToRecordPrimitive`, `ValueToRecord`: untouched (the fix is entirely
  about *which* type gets constructed and persisted, not the types themselves).
- `materializeBucket`/`InvertedIndexSubSet`/the query-side read path: already correct, untouched.
- `ValueToRecordBitmap`'s two extra interfaces (`TransactionalObject<ValueToRecordBitmap, Void>`,
  `TransactionalCreatorMaintainer`) beyond `ValueToRecord`: confirmed (by grep — zero callers of
  `getStateCopyWithCommittedChanges`/`.makeClone()` on an individual bucket anywhere in the
  codebase; the only `getStateCopyWithCommittedChanges` caller targets the whole
  `TransactionalBucketBPlusTree`, not a bucket) to be vestigial on the bucket-instance level, so
  widening the array element type to the narrower `ValueToRecord` interface loses no exercised
  capability.

## 5. Risks

| risk | mitigation |
|---|---|
| **Wire format change** (new discriminator byte, changed layout) | The paged leaf-page format is brand-new to this branch (2026, unreleased) — no existing on-disk data to preserve compatibility with. Confirmed: `ValueToRecordPrimitive` has zero existing Kryo footprint (never persisted before), so there's no legacy reader to maintain either way. |
| **Missed consumer** — a 4th call site somewhere still assumes `ValueToRecordBitmap[]` | §3/§4's inventory (4 producer/storage-part/loader triples + 2 serializer strategies) was built from a dedicated exhaustive grep pass (producers, storage-part fields, serializer read/write sites, loader sites, the reconstructor) — cross-referenced against known **false-positive** sibling families that share superficially similar names but are unrelated (`ReferenceTypeCardinalityIndex.LeafPage`, `OwnerUniqueIndex.LeafPage`, `GlobalUniqueIndex.LeafPage`, `ChainIndex.ChainLeafPage`, `HistogramContract.Bucket[]` — all separate, unrelated types, do not touch). Compilation itself will catch anything missed (widening a field type is not silently compatible with a narrower consumer). |
| **`SortIndexLeafPagePartSerializer`'s bespoke path diverges from the shared-base fix** | Called out explicitly in §4.3 — apply the same discriminator-byte idea there independently; do not assume fixing the shared base covers it. |
| **Correctness of the primitive-branch round-trip** — a subtly wrong discriminator/read order silently swaps a value or record id | Round-trip test per bucket kind (§6) — write then immediately read back and assert bucket-for-bucket equality, for both single- and multi-record buckets, across all three page families. |

## 6. Backward compatibility

None required — same reasoning as the CRC32C bare-long companion plan
(`docs/design/2026-07-09-crc32c-bare-long-cumulative-checksum.md`) and the compaction-cadence plan:
intra-dev change on an unreleased-format branch, no `serialVersionUID` bump, no legacy reader.

## 7. Test plan

- **Unit — `InvertedIndex`**: `collectChangedPages` on a leaf with a mix of single- and multi-record
  buckets produces a `LeafPage` whose `buckets()` array contains `ValueToRecordPrimitive` for
  singles and `ValueToRecordBitmap` for multi — direct assertion on the produced array's element
  types, not just round-trip behavior.
- **Unit — serializer round-trip** (`FilterIndexLeafPagePartSerializerTest` already exists per the
  mapper research — extend it; add equivalents for Histogram/Sort if not already covered): write a
  `LeafPage`/storage-part with both bucket kinds, read it back, assert `getValue()`/`getRecordIds()`
  equality bucket-for-bucket for both kinds, and (bonus check, §4.3) assert the primitive-branch
  serialized byte count is smaller than today's full-bitmap encoding for a single-record bucket.
- **Functional — real-loader round-trip** (mirroring the existing pattern noted in project memory
  for other leaf-page work — "real-loader round-trip test", not just serializer-level): flush a
  filter/histogram/sort index with single- and multi-record buckets, reload the catalog, verify
  query results are identical before/after reload.
- **Regression**: full `attribute`/`indexing`/`storage` tagged sweep (`FilterIndex`, `HistogramIndex`,
  `SortIndex`/`OwnerSortIndex`, `AttributeIndexLoader`, `HistogramIndexMapLoader` test suites) — 0F/0E
  gate, matching this branch's established convention.
- **Measurement gate**: first, add the single-vs-multi bucket counter (§1's caveat) and log the
  actual ratio for this workload — that number, not the ~11% upper bound, is the real predictor of
  this plan's payoff. Then re-run async-profiler `-e alloc` on `EvitaWarmUpInsertionTest` (real
  committed config, per `docs/reports/2026-07-09-warmup-test-remeasure-real-config.md`'s
  methodology). Expect the single-record-attributable share of the `RoaringArray`/`PersistentRoaringBitmap`/
  `Container[]`/`ArrayContainer` allocation under `collectChangedPages` stacks to drop sharply,
  scaled by the measured single-record ratio — not necessarily the full ~11% upper bound (§1); the
  `TransactionalBitmap` share (multi-record path) and value-encoding costs under the same stacks are
  untouched by this plan and should stay roughly flat, which itself is a useful confirmation the fix
  landed where intended and nowhere else.

## 8. Step-by-step

1. **`InvertedIndex.collectChangedPages`** (§4.1) — the producer; land and unit-test in isolation
   first (§7's first bullet) since every downstream consumer depends on it emitting the right types.
2. **Storage-part classes** (§4.2) — `FilterIndexLeafPagePart`, `HistogramIndexLeafPagePart`,
   `SortIndexLeafPagePart` — type widening, compile-driven (the compiler will flag every site still
   assuming `ValueToRecordBitmap[]`).
3. **Serializers** (§4.3) — `BucketLeafPagePartSerializer` (shared, covers Filter+Histogram) and
   `SortIndexLeafPagePartSerializer` (bespoke) in parallel; each gets its own round-trip test before
   moving on.
4. **Loaders + `InvertedIndex.fromPersistedPages`** (§4.4) — signature widening, no logic change
   expected; the real-loader round-trip test (§7) is the gate that proves this.
5. **Full regression sweep + measurement gate** (§7).
