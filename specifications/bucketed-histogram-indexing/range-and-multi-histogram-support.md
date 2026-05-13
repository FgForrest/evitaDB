# Range-Typed Histograms and Multi-Histogram Schema Support

**Issue:** Follow-up to [#8 — Compute dynamic set of attribute histogram for references](https://github.com/FgForrest/evitaDB/issues/8)

**Prerequisites:**
- [`bucketed-histogram-schema-support.md`](bucketed-histogram-schema-support.md) — schema layer
- [`conditional-bucket-indexing.md`](conditional-bucket-indexing.md) — indexing pipeline & triggers

**Scope.** This plan extends the existing bucketed-histogram machinery in three orthogonal directions:

1. **Calculation** — make the histogram pipeline aware of `io.evitadb.dataType.Range` source attributes (`DateTimeRange`, `NumberRange<Byte/Short/Integer/Long/BigDecimal>`) so that a product whose attribute value spans `[from, to]` participates in every bucket whose threshold falls inside that interval (inclusive). Applies to both attribute histograms and reference histograms; the price histogram is untouched.
2. **Indexing** — back range-typed histogram data with the existing `io.evitadb.index.range.RangeIndex` companion that today already underpins range-typed `FilterIndex` instances, instead of (or in addition to) the scalar `FilterIndex`/`InvertedIndex` path.
3. **Schema** — promote `@Reference.bucketed()` / `@ScopeReferenceSettings.bucketed()` from a single `@Histogram` element to an array, and add a per-histogram secondary condition expression (`bucketedPartially` on `@Histogram` itself), AND-ed with the reference-level `bucketedPartially`.

**Guiding principles.**

- **No new index family.** Range support reuses the `RangeIndex` already created for range-typed `FilterIndex` (see `FilterIndex` lines 222–223). The cardinality-gated wiring established by `HistogramIndex` / `SimpleHistogramIndex` / `LocalizedHistogramIndex` is preserved as-is; only the per-locale leaf gains an optional `RangeIndex`.
- **Drop-in for the cruncher.** Range histograms still feed `AttributeHistogramComputer` via a `ValueToRecordBitmap[]` produced from a linear sweep over the `RangeIndex.ranges` thresholds. `HistogramDataCruncher` and the equal-width slicing remain unchanged.
- **Schema mutations remain idempotent.** Multi-histogram is already plumbed through `Map<Scope, Map<String, HistogramIndexDefinition>>` (see `ReferenceSchema.bucketedInScopes`); only the annotation surface and `ClassSchemaAnalyzer` plus the new per-histogram `bucketedPartially` need work.

---

## Current state — what already works

A quick audit of `dev` (commit `e318f10d1`) confirms the design points that this plan does NOT touch:

| Concern | Current state | Source |
|---|---|---|
| Multiple histogram defs per reference per scope on DTO side | `Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes` | `ReferenceSchema:117` |
| Per-scope `bucketedPartially` (reference-level gate) | `Map<Scope, Expression> bucketedPartiallyInScopes` | `ReferenceSchema:131` |
| Per-histogram-name index storage on the engine side | `TransactionalMap<String, HistogramIndex> histogramIndexes` | `ReferencedTypeEntityIndex:185`, `ReducedGroupEntityIndex:123` |
| Cardinality-gated insert/remove with type-erased value | `HistogramIndex.insertValue / removeValue` | `HistogramIndex:103,117` |
| Range support in `FilterIndex` (companion `RangeIndex`) | `FilterIndex.rangeIndex` auto-created for `Range`-typed attributes | `FilterIndex:106,223` |
| `RangeIndex` query primitives | `getRecordsFrom`, `getRecordsTo`, `getRecordsEnvelopingInclusive`, `getRecordsWithRangesOverlapping`, `getAllRecords` | `RangeIndex:251,274,291,391,415` |

What is **broken / missing** today:

- `@Reference.bucketed()` and `@ScopeReferenceSettings.bucketed()` return a single `@Histogram`, not `@Histogram[]`. `ClassSchemaAnalyzer` therefore cannot declare more than one histogram per reference per scope, even though the DTO maps allow it.
- `@Histogram` carries `nameOfTheIndex` and `value` only — there is no per-histogram condition. The only gate is the reference-level `bucketedPartially`, which forces all histograms on the same reference to share a single condition.
- `HistogramIndex` is keyed on `Class<? extends Serializable> valueType` but its `SimpleHistogramIndex` / `LocalizedHistogramIndex` implementations cannot accept `Range`-typed values today because the histogram value is narrowed to `Number` at the `insertValue` boundary (`HistogramIndex.insertValue` signature is `@Nonnull Number value`).
- `HistogramValueDescriptorFactory` (per `conditional-bucket-indexing.md` Section 3.5 step 6) restricts the source attribute plain type to `Byte / Short / Integer / Long / BigDecimal`. `Range` subtypes are silently rejected.
- `HistogramDataCruncher` operates on `int` thresholds via `ToIntFunction` (`HistogramDataCruncher.java:80,84,88`); each scalar bucket maps to a single record. Range source attributes — where one record spans many buckets — need a pre-flattened `ValueToRecordBitmap[]` built outside the cruncher.

---

## Part 1 — Histogram calculation for `Range` source attributes

### 1.1 Bucket-membership semantics

For a histogram backed by a `Range`-typed attribute:

- Distinct bucket keys are the **union of `from` / `to` endpoints** of every record's range. The histogram's `[globalMin, globalMax]` window is naturally the smallest `from` and largest `to` across all records — i.e., the first and last non-sentinel bucket the sweep emits.
- A record with range `[a, b]` is a member of bucket `V` iff `a ≤ V ≤ b` (closed on both sides — matches `Range.overlaps` semantics in `evita_common`).

#### Sweep algorithm

The membership semantics correspond to a forward sweep over the transactional `RangeIndex.ranges` array. The algorithm is:

```
activeSet ← empty RoaringBitmap
buckets   ← growing list of ValueToRecordBitmap

for point in ranges.iterator():                  // transaction-aware view, NOT getArray()
    threshold ← point.threshold
    if threshold == Long.MIN_VALUE ||            // skip sentinels (always present per
       threshold == Long.MAX_VALUE:              // RangeIndex.java:155-178)
        continue

    activeSet.or(point.starts)                   // record entered range at threshold
    buckets.add(ValueToRecordBitmap(             // emit BEFORE removing ends — closed-
        toBucketKey(threshold),                  // on-both-sides semantics
        activeSet.clone()))                      // snapshot (active set keeps mutating)
    activeSet.andNot(point.ends)                 // record exits range after threshold

return buckets.toArray()                         // sorted ascending by threshold invariant
```

Key invariants:

- **Sentinel skip is mandatory.** `RangeIndex` always carries `Long.MIN_VALUE` / `Long.MAX_VALUE` endpoints — the no-arg constructor inserts them at `RangeIndex.java:170-171`, and the seeded-array constructor asserts their presence at lines 155-156. Emitting them would inject phantom buckets and overflow the cruncher's int-arithmetic.
- **Transaction-aware iteration is mandatory.** `RangeIndex.getRanges()` returns the committed snapshot via `TransactionalComplexObjArray.getArray()` — stale inside an open transaction. Use `ranges.iterator()` (the same pattern as `RangeIndex.contains`, line 226).
- **Emission ordering enforces closed intervals.** `add starts → emit → remove ends` puts each record into every threshold it covers, including both endpoints. The inverse ordering would silently miss boundary records.
- **Point ranges (`from == to`) are emitted once.** In that case the same `TransactionalRangePoint` carries the record in both `starts` and `ends` — the algorithm above emits one bucket containing the record (added then immediately removed in the next iteration's `andNot`, but the snapshot in the current bucket already includes it).
- **Bucket-key type matches the source attribute.** `toBucketKey(long)` maps the threshold to the source's natural numeric type: `Long` → `Long`, `Integer` → `Integer.valueOf((int) threshold)`, `Byte/Short` likewise, `BigDecimal` → see §1.4 for the precise-value side table. The cruncher then folds these through `AttributeHistogramComputer.createNumberToIntegerConverter` exactly like scalar histograms.

### 1.2 Why a sweep and not per-bucket envelope queries

The existing `RangeIndex.getRecordsEnvelopingInclusive(threshold)` would let us answer "what records overlap T?" in O(log N) — but calling it once per distinct threshold is O(D · log N) where D = distinct endpoints. The internal `ranges` array already enumerates exactly those thresholds with starts/ends attached, so a single forward pass yields the full `ValueToRecordBitmap[]` in **O(N)** total. No new public API needed on `RangeIndex` other than read-only iteration of `ranges` (it is already exposed via `getRanges()`).

### 1.3 New computation entry point

Add a sibling to `FilterIndex.getHistogramOfAllRecords()` on `FilterIndex` that produces `ValueToRecordBitmap[]` from the `rangeIndex` companion via the sweep above. Suggested name: `getRangeHistogramOfAllRecords()`. Returns the same `InvertedIndexSubSet` type — the rest of the pipeline (`AttributeHistogramComputer.computeNarrowedHistogramBuckets`, `HistogramDataCruncher`, `ReferenceHistogramAccumulator`) consumes it transparently.

**Memoization.** Mirror the existing `memoizedAllRecordsFormula` pattern (`FilterIndex.java:578-588`): cache the swept `InvertedIndexSubSet` on the leaf, invalidate on `dirty.setToTrue()`. Steady-state queries against the same leaf then pay zero allocation. Without memoization, a 10 000-product / dense-overlap workload allocates O(N) fresh `RoaringBitmap` snapshots per query (perf agent finding #B2).

`AttributeHistogramComputer.computeNarrowedHistogramBuckets()` (lines 437–456) currently maps `FilterIndex::getHistogramOfAllRecords` over the list of source `FilterIndex` instances. Dispatch on `filterIndex.getRangeIndex() != null`:

```java
.map(fi -> fi.getRangeIndex() != null
    ? fi.getRangeHistogramOfAllRecords()
    : fi.getHistogramOfAllRecords())
```

**Dispatch-site audit.** Every caller of `FilterIndex.getHistogramOfAllRecords()` on a range-typed leaf must be routed through `getRangeHistogramOfAllRecords()`. The default path returns `InvertedIndex` buckets keyed by raw `Range` objects (a side effect of the dual write — see §2.2), which would crash `HistogramDataCruncher` in the numeric converter. Known call sites to audit: `AttributeHistogramComputer.computeNarrowedHistogramBuckets`, any direct use under `ReferenceHistogramAccumulator`, and `FilterIndex.getAllRecordsFormula` (line 578) when invoked on a histogram leaf.

### 1.4 Bucket-key type for the cruncher

The sweep emits `ValueToRecordBitmap` keyed by the source attribute's **natural numeric type**, not by the raw `long` threshold. `AttributeHistogramComputer.createNumberToIntegerConverter` (lines 200–230 of `AttributeHistogramComputer.java`) then folds the typed value into `int` for the cruncher exactly like scalar histograms:

| Source type | Bucket key emitted by sweep | Cruncher path |
|---|---|---|
| `NumberRange<Byte>` | `Byte` from `threshold` | existing `Byte → int` cast (line 205) |
| `NumberRange<Short>` | `Short` from `threshold` | existing `Short → int` cast (line 207) |
| `NumberRange<Integer>` | `Integer` from `threshold` | existing `Integer → int` cast (line 209) |
| `NumberRange<Long>` | `Long` from `threshold` | existing `Long.intValue()` + overflow check (lines 210–217) — bounds outside `int` range throw, **same constraint as scalar `Long` histograms today** |
| `NumberRange<BigDecimal>` | `BigDecimal` from `getPreciseFrom()` / `getPreciseTo()` (the `long` threshold is lossy for BigDecimal) | existing BigDecimal converter using `AttributeSchemaContract.getIndexedDecimalPlaces()` × `stripTrailingZeros().scaleByPowerOfTen(...)` (lines 218–221) |

For `NumberRange<BigDecimal>` source attributes, the `long` threshold is lossy. The sweep recovers precise endpoints by looking them up in the dual-write `InvertedIndex` shadow (§2.2), whose bucket keys are the raw `Range` instances. No extra storage is added; the sweep walks the `InvertedIndex`'s sorted buckets in parallel with the `RangeIndex` threshold stream and emits `BigDecimal`-keyed `ValueToRecordBitmap`s.

**`DateTimeRange` is not a histogram source type.** Histograms are numeric-only; the `HistogramValueDescriptorFactory` plain-type validator rejects `DateTimeRange` along with all non-numeric types. Reject explicitly with a clear error.

**No cruncher widening needed.** The cruncher's existing `int`-based arithmetic is reused unchanged (see §1.6). The sweep is responsible for producing typed bucket keys; the converter does the rest.

### 1.5 Boundary PK resolution

`ReferenceHistogramAccumulator.resolveBoundaryPks` (lines 502+) currently extracts `rawMin` / `rawMax` from the cacheable result and queries the source `FilterIndex` for records equal to those bounds. For range histograms, "boundary PK" means a record whose range covers the boundary threshold.

**Primary strategy — read from the sweep result (O(1)).** `rawMin` and `rawMax` are themselves thresholds emitted by the sweep (they are the first and last non-sentinel `ValueToRecordBitmap` buckets). The active set carried in those buckets is already "all records whose range covers this threshold". Read the bucket's `recordIds` directly and pick the lowest PK (tiebreaker: numerically smallest `entityPK`). Zero additional index traversal.

**Fallback — `getRecordsEnvelopingInclusive`.** When `HistogramDataCruncher.LongestSpaceRange` reshapes `rawMin` / `rawMax` into synthetic anchors that do not coincide with any swept threshold (rare but legitimate), fall back to `RangeIndex.getRecordsEnvelopingInclusive(rawMin)` for the lower bound and `getRecordsEnvelopingInclusive(rawMax)` for the upper. This costs an O(N) formula-tree materialization per boundary (perf agent finding #B4), bounded to two calls per request.

**Tiebreaker is explicit: lowest entityPK from the resulting bitmap.** The base spec's lowest-PK rule applies; widest-range vs. narrowest-range disambiguation is out of scope.

### 1.6 What does NOT change

- `HistogramDataCruncher` itself — same algorithm, same equal-width slicing, same `int`-based threshold arithmetic. The sweep emits typed bucket keys; `AttributeHistogramComputer.createNumberToIntegerConverter` continues to fold them into `int`.
- The shape of `ValueToRecordBitmap[]` — still `(value, recordIds)` pairs, sorted by value.
- The `AttributeHistogramRequest` / `AttributeHistogramComputer` cache key — `FilterIndex.getId()` already identifies each per-scope, per-locale histogram leaf uniquely.
- `ReferenceHistogramAccumulator.histogramFilterIndexFor(...)` — the dispatch on `ReducedGroupEntityIndex` vs. `ReferencedTypeEntityIndex` is unchanged; both already return the `FilterIndex` leaf.

### 1.7 Checklist

- [ ] Extend `HistogramValueDescriptorFactory` plain-type validator to accept `NumberRange<Byte | Short | Integer | Long | BigDecimal>`; reject `DateTimeRange` and non-numeric ranges with the existing error template.
- [ ] Extend `HistogramValueDescriptor` with `boolean rangeType` (mirrors `boolean arrayType`).
- [ ] Add `FilterIndex.getRangeHistogramOfAllRecords()` that materializes `ValueToRecordBitmap[]` via the sentinel-skipping, transaction-aware sweep over `rangeIndex.ranges`. Memoize on the leaf, invalidate on `dirty.setToTrue()`.
- [ ] `AttributeHistogramComputer.computeNarrowedHistogramBuckets` — dispatch on `getRangeIndex() != null`.
- [ ] Audit every other call site of `FilterIndex.getHistogramOfAllRecords()` for range-typed leaves; route through `getRangeHistogramOfAllRecords()`.
- [ ] `ReferenceHistogramAccumulator.resolveBoundaryPks` — read `rawMin` / `rawMax` boundary PKs directly from the sweep-emitted bucket when threshold matches; fall back to `getRecordsEnvelopingInclusive` otherwise.
- [ ] Tests: unit test for the sweep (sentinel skip, point ranges, snapshot semantics) against synthetic ranges; integration test combining scalar and range references in the same query; concurrent transactional-layer composition test.

---

## Part 2 — Indexing: hook a `RangeIndex` into the histogram leaves

### 2.1 Where the range data lives

Both grouped (`ReducedGroupEntityIndex`) and ungrouped (`ReferencedTypeEntityIndex`) histogram paths funnel through `HistogramIndex.insertValue(Locale, Number, int)`. For range-typed values, `Number` is the wrong abstraction — a `Range` is `Serializable` but not `Number`.

**Decision:** widen `HistogramIndex.insertValue` to `@Nonnull Serializable value` (the matching `removeValue` already uses `Serializable`). The implementing leaves (`SimpleHistogramIndex` / `LocalizedHistogramIndex`) inspect `valueType` once at construction time, decide whether the per-locale `FilterIndex` needs a `RangeIndex` companion, and route inserts to the right path. Cardinality wiring, value-bucket emptiness checks, and transactional layer mechanics are unchanged.

Alternatives considered and rejected:
- A parallel `insertRangeValue(Locale, Range<?>, int)` method — adds API surface to every call site for a value-type concern that's already known at construction.
- Pre-decomposing ranges into `(from, to)` at the call site — loses the spanning information; the leaf can no longer drive the sweep at query time.

### 2.2 Leaf `FilterIndex` construction for range histograms

`FilterIndex` already builds its `rangeIndex` whenever the constructor-supplied `attributeType` extends `Range`:

```java
this.rangeIndex = Range.class.isAssignableFrom(plainType) ? new RangeIndex() : null;
```

(`FilterIndex:223`). `SimpleHistogramIndex` / `LocalizedHistogramIndex` already pass the histogram's `valueType` into the `FilterIndex` constructor, so if we plumb the right class (`IntegerNumberRange.class`, `DateTimeRange.class`, …) at schema-resolution time, the `RangeIndex` companion is created automatically.

The histogram leaf's `FilterIndex.addRecord(recordId, value)` will then take the existing range-aware branch (`FilterIndex:388-414`): it inserts the range as a `(from, to)` pair into `rangeIndex` AND adds the raw `Range` value to the `InvertedIndex` under the natural `Range` ordering. This is the **same dual-write shape** used by standard range-typed attributes today — not waste, but a load-bearing pairing:

- `RangeIndex` side: drives the query-time sweep (§1.1) and supports `addRecord` / `removeRecord` keyed by `(from, to)`.
- `InvertedIndex` side: stores raw `Range` instances keyed by `Range` natural ordering. This is what makes **scan-based removal** in the cross-entity executor work for ranges. The base spec (`conditional-bucket-indexing.md` §5.3 step 5) scans `FilterIndex` buckets to find which one contains the owner PK; each ownerPK appears in exactly one `InvertedIndex` bucket (the one keyed by its specific `Range`). That bucket's key gives back the exact `(from, to)` needed for the paired `RangeIndex.removeRecord` call. Without the shadow, removal would require either a linear scan of `RangeIndex.ranges` per PK or carrying `oldValue` through every mutation — both worse than the dual write.

**Read-side caveat.** The `InvertedIndex` shadow's bucket keys are `Range` instances, not numbers — every histogram-pipeline reader must dispatch through `getRangeHistogramOfAllRecords()` for range-typed leaves. Covered by §1.3.

### 2.3 Cardinality keying

`AttributeCardinalityIndex` uses `AttributeCardinalityKey(value, recordId)` with `equals()` on the value. `NumberRange` implements value-based `equals()` over both bounds (and the inner numeric type), so two records with identical `NumberRange<Integer>(10, 50)` share one cardinality key — cardinality gating works identically to the scalar case.

For `NumberRange<BigDecimal>` specifically, the existing scalar-BigDecimal rule applies: call `stripTrailingZeros()` on both bounds before constructing the cardinality key (or before the `Range` is passed into `FilterIndex.addRecord`). Two `BigDecimal` values with different scales but equal numeric value (`"50"` vs `"5E+1"`) would otherwise produce distinct cardinality keys and leak entries on remove.

### 2.4 Trigger value-resolution path

`ReferenceIndexMutator` and `EntityIndexLocalMutationExecutor` (local triggers) and the cross-entity `ReevaluateExpressionExecutor` both read raw attribute values via storage parts (`ExistingAttributeValueSupplier` / source-`FilterIndex.getValueToRecordBitmap()`).

For range-typed source attributes:

- **Local triggers (Section 6.2 of `conditional-bucket-indexing.md`).** The raw value read from storage is a `Range` (or `Range[]` for array attributes). `HistogramValueDescriptor.arrayType` already handles array fan-out; add the case `Range[]` → iterate and call `insertValue(locale, eachRange, ownerPK)`. For scalar `Range`, pass through.
- **Cross-entity executor (Section 5.3 step 6).** Reads from the source `FilterIndex.getValueToRecordBitmap()`. For a `Range`-typed source attribute, each bucket's `value` is the raw `Range`. Pass through unchanged — the histogram leaf's `RangeIndex` companion will split it into a `(from, to)` pair on insert.
- **Defensive dispatch (replaces the loose `instanceof Number` guard).** Drop the `instanceof Number` check entirely. Dispatch on `HistogramValueDescriptor.plainType`: numeric scalar types route to the existing scalar path; `Range` plain types route to the new range path. Anything else throws `GenericEvitaInternalError` per the project's defensive-design rule (CLAUDE.md "Never silently skip unexpected states"). The `valueDescriptor` is built at schema load time and is the authoritative source of truth for the expected value type.

### 2.5 Cross-entity rebuild cost (parity with scalar)

Cross-entity bulk rebuild reads `ValueToRecordBitmap[]` from the source `FilterIndex` and pushes values into the histogram leaf. If the source attribute is range-typed, the source `FilterIndex.getValueToRecordBitmap()` returns one bucket per distinct **`Range` object** — not per endpoint. Each `Range` flows into the histogram leaf's `FilterIndex.addRecord`, which performs the standard `RangeIndex.addRecord(from, to, recordId)` plus inverted-index insert.

**Cost shape parity with scalar reference indexing.** A single source attribute mutation rippling to N owner entities performs N histogram-leaf inserts. For range-typed sources, each insert costs ~2× the scalar insert (one `TransactionalRangePoint` allocation per endpoint plus the inverted-index touch — perf agent finding #A1, #A3). The cardinality of operations and the cross-entity executor's traversal shape match the existing discrete-value reference reevaluation; no special batching or new bulk API is introduced in v1.

Performance regression gate (WBS-B17 step 7): a single attribute change rippling to 10 000 owner entities must complete under the 500 ms ceiling inherited from the base spec's facet baseline. Tune-in / batched-rebuild API on `RangeIndex` is deferred as a future optimization.

### 2.6 Storage parts

Histogram leaves persist via `HistogramIndexStoragePart` (`evita_engine/.../spi/store/catalog/persistence/storageParts/index/HistogramIndexStoragePart.java`), which already carries a `@Nullable RangeIndex rangeIndex` field (line 90) — written by `SimpleHistogramIndex.getModifiedStorageParts` (lines 154–169). No new storage part class is needed for range histograms; the existing serializer registration handles `rangeIndex != null` transparently.

### 2.7 Boundary case — schema removal of filterability

`conditional-bucket-indexing.md` Section 3.3 (Reverse validation) already covers the rule that an attribute referenced by a histogram value expression cannot lose its `filterable` flag. Extend the check to also reject removal of filterability for `Range`-typed source attributes (no separate code path; the check is name-based, not type-based).

### 2.8 Checklist

- [ ] Widen `HistogramIndex.insertValue` from `Number` to `Serializable`; update both `SimpleHistogramIndex` and `LocalizedHistogramIndex`.
- [ ] Update `ReducedGroupEntityIndex.insertHistogramValue` / `ReferencedTypeEntityIndex.insertHistogramValue` signatures (also `Number → Serializable`).
- [ ] Pass the histogram's actual `valueType` (`Class<? extends Serializable>` — may now be a `Range` subtype) into the per-locale `FilterIndex` constructor. Verify the auto-`RangeIndex` branch kicks in.
- [ ] Local trigger value-read path: handle `Range` and `Range[]` from storage analogously to `Number` / `Number[]`.
- [ ] Cross-entity executor (`ReevaluateExpressionExecutor` step 6) — replace the `instanceof Number` guard with explicit dispatch on `HistogramValueDescriptor.plainType`; throw `GenericEvitaInternalError` on type mismatch.
- [ ] Apply `stripTrailingZeros()` to `BigDecimal` range bounds at the local-trigger boundary (cardinality keying).
- [ ] Tests: insert a range, verify both `RangeIndex` and `InvertedIndex` get the entry; remove it, verify cardinality drops, verify both structures shrink; verify scan-based removal recovers the exact `Range` value via the InvertedIndex shadow.

---

## Part 3 — Schema: multiple histograms per reference + per-histogram condition

### 3.1 What changes shape

**`@Histogram` annotation gains a per-histogram condition.**

```java
public @interface Histogram {
    String nameOfTheIndex() default "";
    Expression value() default @Expression;
    Expression bucketedPartially() default @Expression;   // NEW
}
```

**`@Reference.bucketed()` and `@ScopeReferenceSettings.bucketed()` become arrays.**

```java
// Reference.java
Histogram[] bucketed() default {};   // was: Histogram bucketed() default @Histogram;

// ScopeReferenceSettings.java
Histogram[] bucketed() default {};
```

The reference-level `bucketedPartially()` (already on both annotations) stays, and is interpreted as the **primary gate** AND-ed with each histogram's own (secondary) `bucketedPartially`.

### 3.2 DTO shape

`HistogramIndexDefinition` is a 3-component `NamedContract` record:

```java
public record HistogramIndexDefinition(
    @Nonnull String nameOfTheIndex,
    @Nonnull Map<NamingConvention, String> nameVariants,
    @Nullable Expression valueExpression
) implements NamedContract { ... }
```

Extend it with the new per-histogram condition as a 4th component:

```java
public record HistogramIndexDefinition(
    @Nonnull String nameOfTheIndex,
    @Nonnull Map<NamingConvention, String> nameVariants,
    @Nullable Expression valueExpression,
    @Nullable Expression bucketedPartially       // NEW — secondary gate
) implements NamedContract { ... }
```

Keep the existing compact-constructor validation (`!nameOfTheIndex.isBlank()` and friends). Update the `of(...)` factory (line 87 of the existing record) and every internal caller. `ReferenceSchema.bucketedInScopes : Map<Scope, Map<String, HistogramIndexDefinition>>` and `ReferenceSchema.bucketedPartiallyInScopes : Map<Scope, Expression>` are kept exactly as they are; the maps already encode multi-histogram.

### 3.3 Effective condition at trigger build time

`HistogramExpressionTriggerFactory.buildTriggersForReference` (`conditional-bucket-indexing.md` Section 4.3) currently builds one trigger per `(scope, histogramName, DependencyKey)`. With per-histogram conditions, the **effective** condition for a histogram is:

```
effective = reference.bucketedPartially(scope) AND histogram.bucketedPartially
```

- If both sides are null → unconditional bucketing (existing behavior).
- If one side is null → use the other directly (no synthesized AND).
- If both are non-null → AND-combine via a new helper on `evita_query` `ExpressionFactory`:

  ```java
  public static Expression and(@Nullable Expression left, @Nullable Expression right) {
      if (left == null) return right;
      if (right == null) return left;
      return new Expression(
          new ConjunctionOperator(left.getOperand(), right.getOperand())
      );
  }
  ```

  `Expression` extends `UnaryExpressionNode` and exposes its inner `ExpressionNode` via `getOperand()` — the helper unwraps both sides and wraps a `ConjunctionOperator` (`evita_query/.../expression/bool/ConjunctionOperator.java`). Spell out the unwrap/rewrap because there is no existing `Expression.and` to reuse.

The combined `Expression` is what gets pre-translated to a `FilterBy` and what drives `evaluate()` at trigger time. **Important:** dependency analysis must be re-run on the combined expression — pass the combined `Expression.getOperand()` (an `ExpressionNode`) to `AccessedDataFinder.findAccessedPaths(...)` (`evita_query/.../visitor/AccessedDataFinder.java:87`). A per-histogram condition can introduce additional `DependencyKey`s beyond what the reference-level condition contributes, so the trigger may register under more keys.

The `bucketedPartially` field on `HistogramIndexDefinition` is the **raw** secondary condition. The combined effective expression is materialized only inside the factory; it is not stored on the DTO. This keeps schema mutations idempotent — same input → same DTO — and avoids re-serializing the AND-combined expression every time.

### 3.4 `ClassSchemaAnalyzer`

`bucketed-histogram-schema-support.md` Layer 4b describes single-`@Histogram` processing. Update to:

- **Default scope:** iterate `reference.bucketed()` (now an array). For each element, call `editor.bucketedInScope(scope, nameOfTheIndex, valueExpr, bucketedPartiallyExpr)`. Empty array → no histograms.
- **Per-scope:** iterate `scopeSettings.bucketed()` analogously.
- **Assertion:** when `scope[]` is non-empty, `reference.bucketed()` at the top level must be empty (same pattern as the assertion for `reference.faceted()`).
- **Name uniqueness:** within a single `(reference, scope)`, the histogram names from the array must be unique.

  **Place the check in `ReferenceSchema.toBucketedHistogramMap(ScopedHistogramIndexDefinition[])`**, not only in `ClassSchemaAnalyzer`. The analyzer is just one of five ingestion paths (analyzer / builder / gRPC / REST / GraphQL / WAL replay) — centralizing the uniqueness assertion in the DTO converter is the single chokepoint that every path traverses. Throw `SchemaAlteringException` (or `EvitaInvalidUsageException`) on duplicate `(scope, nameOfTheIndex)` with a clear error pointing to the offending name. The analyzer keeps a defensive duplicate check too, for earlier failure during class scanning.

### 3.5 Editor API

Add a fluent variant on `ReferenceSchemaEditor`:

```java
ReferenceSchemaBuilder bucketedInScope(
    Scope scope,
    String nameOfTheIndex,
    Expression valueExpression,
    @Nullable Expression bucketedPartially      // NEW
);
```

Keep the three-argument overload (no per-histogram condition) as a convenience. Mirror on `ReflectedReferenceSchemaBuilder`.

### 3.6 Mutations

`SetReferenceSchemaBucketedMutation` already carries `ScopedHistogramIndexDefinition[]` and `ScopedBucketedPartially[]`. Extend `ScopedHistogramIndexDefinition`:

```java
public record ScopedHistogramIndexDefinition(
    @Nonnull Scope scope,
    @Nonnull String nameOfTheIndex,
    @Nullable Expression valueExpression,
    @Nullable Expression bucketedPartially       // NEW
) implements Serializable { ... }
```

Bump `serialVersionUID` on:
- `ScopedHistogramIndexDefinition`
- `HistogramIndexDefinition`
- `SetReferenceSchemaBucketedMutation`
- `CreateReferenceSchemaMutation`
- `CreateReflectedReferenceSchemaMutation`
- `ReferenceSchema`
- `ReflectedReferenceSchema`

Update the current Kryo + WAL serializers to read/write the new field. **No `_2026_1` backward-compat work is required:** reference histograms (including the schema layer) are entirely scoped to release 2026.2 — no 2026.1 binary carries any of the affected fields. Existing `_2026_1` serializer siblings for unrelated mutations remain untouched.

### 3.7 External APIs (gRPC / GraphQL / REST)

- **`GrpcEvitaDataTypes.proto`** — add `google.protobuf.StringValue bucketedPartially` to `GrpcScopedHistogramIndexDefinition` and to `GrpcHistogramIndexDefinition` (if a non-scoped variant exists).
- **`ScopedHistogramIndexDefinitionDescriptor`** — add `BUCKETED_PARTIALLY` property (nullable String, parsed as `Expression`).
- **REST `SchemaJsonSerializer.serializeBucketedHistogram`** — include `bucketedPartially` per histogram.
- **GraphQL** — add the new field to the `BucketedHistogramDefinition` output and input types.

### 3.8 Source-incompatible annotation change

`@Reference.bucketed()` and `@ScopeReferenceSettings.bucketed()` change return type from `Histogram` to `Histogram[]`. Java does not auto-promote `bucketed = @Histogram(...)` to `bucketed = {@Histogram(...)}` — every existing caller must rewrite the annotation argument to use array braces. The change is **source-incompatible**, but acceptable because reference-histogram support is brand-new in 2026.2 — no public clients depend on the prior shape.

- Update every in-repo caller in the same commit (e.g., `GetterBasedEntityWithFacetedPartiallyAndBucketed.java`).
- Release-note entry: "`@Reference(bucketed = @Histogram(...))` must be written as `@Reference(bucketed = {@Histogram(...)})`. Empty annotation form `bucketed = @Histogram` is no longer the default — empty array `{}` means no histograms."
- Empty `@Histogram` elements inside a non-empty array are a hard error: `nameOfTheIndex().isEmpty()` rejects with a clear validation message. No implicit name derivation inside an array.

No data migration is required — reference histogram persistence is also new in 2026.2; nothing pre-existing carries the affected fields.

### 3.9 Checklist

- [ ] `@Histogram` — add `Expression bucketedPartially() default @Expression`.
- [ ] `@Reference.bucketed()` — change return type to `Histogram[]`.
- [ ] `@ScopeReferenceSettings.bucketed()` — change return type to `Histogram[]`.
- [ ] `HistogramIndexDefinition` — add `@Nullable Expression bucketedPartially` field.
- [ ] `ScopedHistogramIndexDefinition` — add `@Nullable Expression bucketedPartially` field.
- [ ] `ClassSchemaAnalyzer` — iterate the new array; assert uniqueness of histogram names within `(reference, scope)`.
- [ ] `ReferenceSchemaEditor` / `ReferenceSchemaBuilder` / `ReflectedReferenceSchemaBuilder` — new 4-arg `bucketedInScope` overload.
- [ ] `SetReferenceSchemaBucketedMutation` / `CreateReferenceSchemaMutation` / `CreateReflectedReferenceSchemaMutation` — carry the new field through.
- [ ] `HistogramExpressionTriggerFactory` — AND-combine reference-level and per-histogram conditions via the new `ExpressionFactory.and` helper; re-run `AccessedDataFinder.findAccessedPaths(combined.getOperand())` on the combined expression to gather dependency keys.
- [ ] Add `ExpressionFactory.and(Expression, Expression)` helper to `evita_query` with null-side fast paths.
- [ ] Current Kryo schema serializers — read/write the new field (no `_2026_1` work; 2026.2-scoped feature).
- [ ] Current WAL mutation serializers — same.
- [ ] gRPC proto + GraphQL types + REST JSON serializer + REST DTO helpers — add the new field.
- [ ] Move histogram-name uniqueness assertion into `ReferenceSchema.toBucketedHistogramMap`; keep the defensive analyzer-side check.
- [ ] Tests: two histograms on one reference, one passing condition only, AND-combine semantics with reference-level `bucketedPartially`, name-collision rejection from every ingestion path, reflected reference (explicit only, no inheritance — same as base spec).

---

## Cross-part interactions

- **Multi-histogram + range source.** Two histograms on the same reference can target different attributes — one scalar, one range. They live in independent `HistogramIndex` slots (`Map<String, HistogramIndex>`), each with its own `valueType` and its own per-locale `FilterIndex`. The per-histogram condition makes this combination natural: histogram A's `bucketedPartially` selects scalar-typed products, histogram B's selects range-typed ones, and the reference-level gate AND-applies to both.
- **Multi-histogram + array source.** Independent of this spec — `HistogramValueDescriptor.arrayType` already handles array fan-out per histogram.
- **Per-histogram condition + cross-entity trigger.** A per-histogram condition can introduce dependencies the reference-level condition does not have. Trigger registration must walk the combined expression's `AccessedDataFinder` paths, not just the reference-level ones. The `CatalogExpressionTriggerRegistry` already keys triggers by `(ownerEntityType, dependencyType, attrName)` — additional keys from per-histogram conditions just produce additional registrations; the local-trigger map remains keyed by histogram name (one trigger per name as the base spec already specifies).
- **Overlapping `bucketedPartially` predicates within one scope.** Two histograms on the same reference whose per-histogram `bucketedPartially` predicates overlap will index the same owner PK twice — potentially desired (different value expressions on the same set) but also potentially a footgun. The engine does not validate exclusivity (mirrors the facet/bucket exclusivity stance of `conditional-bucket-indexing.md` §1.4). At trigger build time, log at INFO level when two histograms in the same scope share any `DependencyKey` — visible warning for the user, non-blocking.

---

## Implementation WBS

Numbering continues from `conditional-bucket-indexing.md`.

### WBS-B12: `@Histogram` annotation + schema array shape (evita_api)

1. Add `bucketedPartially` to `@Histogram`.
2. Change `@Reference.bucketed()` and `@ScopeReferenceSettings.bucketed()` to `Histogram[]`.
3. Add `@Nullable Expression bucketedPartially` to `HistogramIndexDefinition` and `ScopedHistogramIndexDefinition`.
4. Update `ReferenceSchemaEditor` / `ReferenceSchemaBuilder` / `ReflectedReferenceSchemaBuilder`.
5. Update `ClassSchemaAnalyzer` — array iteration + name uniqueness check.
6. Bump `serialVersionUID` on every affected DTO / mutation class.

### WBS-B13: Mutation, serializer, external API plumbing (evita_api / evita_external_api_* / evita_store_server)

1. Carry the new field through `SetReferenceSchemaBucketedMutation`, `CreateReferenceSchemaMutation`, `CreateReflectedReferenceSchemaMutation`.
2. Update current Kryo schema serializers (no `_2026_1` — 2026.2-scoped).
3. Update current WAL mutation serializers (no `_2026_1`).
4. gRPC proto + converter, GraphQL types + data fetcher, REST JSON serializer.
5. Centralize histogram-name uniqueness assertion in `ReferenceSchema.toBucketedHistogramMap` (all ingestion paths converge here).

### WBS-B14: Trigger factory combines conditions (evita_engine + evita_query)

1. Add `ExpressionFactory.and(Expression, Expression)` helper in `evita_query` with null-side fast paths (wraps a `ConjunctionOperator` over the unwrapped `ExpressionNode` operands).
2. `HistogramExpressionTriggerFactory` — build the AND-combined effective expression via the helper.
3. `AccessedDataFinder.findAccessedPaths(combined.getOperand())` — walk combined expression for dependency paths; trigger registers under the union of dependency keys.
4. Per-trigger pre-translated `FilterBy` reflects the AND-combine.
5. INFO-level log at build time when two histograms in the same scope share any `DependencyKey` (advisory only).
6. Tests: AND-combine semantics, dependency-key fan-out, null-side fast paths, overlap log emission.

### WBS-B15: `Range` value type support in histogram leaves (evita_engine)

1. Widen `HistogramIndex.insertValue` from `Number` to `Serializable`.
2. Widen `ReducedGroupEntityIndex.insertHistogramValue` / `ReferencedTypeEntityIndex.insertHistogramValue` accordingly.
3. Verify `FilterIndex` constructor auto-creates `RangeIndex` for `Range`-typed `valueType` when called from histogram leaves.
4. `HistogramValueDescriptorFactory` — accept `NumberRange<Byte | Short | Integer | Long | BigDecimal>`. Reject `DateTimeRange` and any non-numeric range with a clear error.
5. `HistogramValueDescriptor.rangeType` flag.
6. Local trigger value-read path — handle `Range` and `Range[]`. Apply `stripTrailingZeros()` to `BigDecimal` range bounds before cardinality keying.
7. Cross-entity executor — replace `instanceof Number` guard with explicit dispatch on `HistogramValueDescriptor.plainType`; throw `GenericEvitaInternalError` on type mismatch (defensive design rule).

### WBS-B16: Range-aware histogram computation (evita_engine)

1. `FilterIndex.getRangeHistogramOfAllRecords()` — single sentinel-skipping, transaction-aware sweep over `rangeIndex.ranges` (use `ranges.iterator()`, not `getArray()`); emit closed-interval buckets keyed by the source's natural numeric type. Memoize on the leaf, invalidate on `dirty.setToTrue()`.
2. `AttributeHistogramComputer.computeNarrowedHistogramBuckets` — dispatch on `getRangeIndex() != null`; audit other call sites of `getHistogramOfAllRecords()` for range-typed leaves and route through the new method.
3. `ReferenceHistogramAccumulator.resolveBoundaryPks` — primary strategy: read the boundary PK from the sweep-emitted bucket when `rawMin` / `rawMax` matches a swept threshold (O(1)); fallback: `getRecordsEnvelopingInclusive`. Lowest-PK tiebreaker.
4. Tests: sweep correctness (sentinel skip, point ranges, snapshot semantics), mixed scalar/range references in one query, boundary PK selection for range sources, memoization invalidation on dirty.

### WBS-B17: End-to-end and integration tests (evita_test)

1. Two histograms on one reference, one scalar one range, with different per-histogram conditions.
2. Reference-level + per-histogram condition AND-combine in cross-entity and local triggers.
3. Fuzzy test for range-typed histograms — random ranges, verify histogram leaf sweep against brute-force computation after each batch of N=50–200 mutations.
4. Nested transactional-layer test: concurrent inserts into a localized range histogram on different locales; commit; verify merged state.
5. Sweep-correctness test: explicit assertions on sentinel skipping, point ranges (`from == to`), and closed-interval semantics.
6. Round-trip test: insert a range, recover the exact `(from, to)` via the InvertedIndex shadow during scan-based removal, confirm `RangeIndex.removeRecord` cleans both structures.
7. Performance regression gate — 10 000 products with overlapping ranges; histogram computation under 500 ms (parity with facet reevaluation baseline).
8. Scope of 2026.2: no `_2026_1` compatibility test required.

---

## Execution order

```
WBS-B12 (annotation + DTO + analyzer)
    ↓
WBS-B13 (mutations + serializers + external API)
    ↓
WBS-B14 (trigger factory AND-combine)
    ↓
WBS-B15 (Serializable widening + Range plumbing)
    ↓
WBS-B16 (sweep + cruncher dispatch + boundary PKs)
    ↓
WBS-B17 (integration + migration tests)
```

Parallelizable: WBS-B14 ↔ WBS-B15 (factory AND-combine is independent of value-type widening). WBS-B16 depends on WBS-B15 only for the `rangeType` flag; otherwise independent.

---

## Files touched (high-level)

### Schema layer (`evita_api`)

- `Histogram.java` — add `bucketedPartially`.
- `Reference.java`, `ScopeReferenceSettings.java` — array return type for `bucketed()`.
- `HistogramIndexDefinition.java` — add `bucketedPartially`.
- `ScopedHistogramIndexDefinition.java` — add `bucketedPartially`.
- `ReferenceSchemaEditor.java`, `ReferenceSchemaBuilder.java`, `ReflectedReferenceSchemaBuilder.java`, `AbstractReferenceSchemaBuilder.java`.
- `ClassSchemaAnalyzer.java`.
- `SetReferenceSchemaBucketedMutation.java`, `CreateReferenceSchemaMutation.java`, `CreateReflectedReferenceSchemaMutation.java`.

### Engine (`evita_engine`)

- `HistogramIndex.java`, `SimpleHistogramIndex.java`, `LocalizedHistogramIndex.java` — `Number → Serializable` on insert.
- `ReducedGroupEntityIndex.java`, `ReferencedTypeEntityIndex.java` — insert signature widening.
- `FilterIndex.java` — new `getRangeHistogramOfAllRecords()`.
- `AttributeHistogramComputer.java` — dispatch.
- `ReferenceHistogramAccumulator.java` — range boundary PK resolution.
- `HistogramValueDescriptor.java`, `HistogramValueDescriptorFactory.java` — accept `Range` types, add `rangeType` flag.
- `HistogramExpressionTriggerFactory.java` — AND-combine effective expression.
- `ReferenceIndexMutator.java`, `EntityIndexLocalMutationExecutor.java` — `Range`/`Range[]` value-read path.
- `ReevaluateExpressionExecutor.java` — relax numeric guard.

### Storage (`evita_store_server`)

- Current Kryo schema serializers: `ReferenceSchemaSerializer`, `ReflectedReferenceSchemaSerializer` (no `_2026_1` work; feature is 2026.2-scoped).
- Current WAL mutation serializers: `SetReferenceSchemaBucketedMutationSerializer`, `CreateReferenceSchemaMutationSerializer`, `CreateReflectedReferenceSchemaMutationSerializer` (no `_2026_1` work).

### External APIs

- `evita_external_api_core` — `ScopedHistogramIndexDefinitionDescriptor`, `SetReferenceSchemaBucketedMutationConverter`, base helpers.
- `evita_external_api_grpc` — `GrpcEvitaDataTypes.proto`, `EntitySchemaConverter`, mutation converters.
- `evita_external_api_graphql` — `BucketedHistogramDefinition` types, data fetchers.
- `evita_external_api_rest` — `SchemaJsonSerializer` bucketed serialization, REST test DTO helpers.

### Tests (`evita_test`)

- Schema-level tests for multi-histogram and per-histogram condition.
- Engine tests for `getRangeHistogramOfAllRecords`, cardinality gating with `Range` values.
- Integration tests in the histogram producer module covering scalar + range mix.
- No `_2026_1` compatibility test required — feature is 2026.2-scoped.
