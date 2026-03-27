# Conditional Bucketed Histogram Indexing — Implementation Plan

**Issue:** [#8 — Compute dynamic set of attribute histogram for references](https://github.com/FgForrest/evitaDB/issues/8)

**Scope:** This plan covers index maintenance for bucketed (histogram) reference data:
adding/removing/updating histogram entries in `FilterIndex` instances stored in
`ReducedGroupEntityIndex` (grouped references) and `ReferencedTypeEntityIndex` (ungrouped
references). It covers trigger infrastructure, cross-entity re-evaluation, value resolution,
and all data-change scenarios including schema changes. It does **not** cover query-time
histogram computation or external API output formats.

**Prerequisites:**
- Schema layer: `bucketed-histogram-schema-support.md`
- Multi-histogram schema change: `multi-histogram-schema-change.md`
- Reference implementation: `specifications/faceted-partially-indexing/conditional-facet-indexing.md`

**Guiding principle:** Uses the conditional facet indexing (`facetedPartially`) architecture
as a starting point but extends it with value-resolution metadata and FilterIndex-based
bucket operations. Shared infrastructure: FilterBy re-evaluation, deferred queue, trigger
registry, session-optional query planning. Histogram-specific extensions: (a) value
resolution metadata (`HistogramValueDescriptor`), (b) FilterIndex writes instead of
FacetIndex writes, (c) multi-histogram-per-reference handling, and (d) `BigDecimal`
normalization.

---

## 1. Architecture Overview

### 1.1 The Business Case

Entity Product has reference `parameterValues` → ParameterValue, grouped by Parameter.
Parameter has attribute `inputWidgetType`:

- `inputWidgetType == 'CHECKBOX'` → reference is **faceted** (existing `facetedPartially`)
- `inputWidgetType == 'INTERVAL'` → reference is **bucketed** for histogram using
  ParameterValue's `basicUnitValue`

These are mutually exclusive per group. Within a single reference definition,
different groups get different index treatment based on the condition expression.

### 1.2 Two Expressions

| Expression | Role | Example |
|---|---|---|
| **Condition** (`bucketedPartially`) | Boolean: whether reference participates in histogram | `$reference.groupEntity?.attributes['inputWidgetType'] == 'INTERVAL'` |
| **Value** (`HistogramIndexDefinition.valueExpression`) | Identifies which attribute's value to store in histogram FilterIndex | `$reference.referencedEntity?.attributes['basicUnitValue']` |

The condition expression is **shared** across all histogram definitions on the same
reference in the same scope. Each histogram definition has its own value expression.

**Histogram name consistency across scopes:** The `Map<Scope, Map<String, HistogramIndexDefinition>>`
structure allows the same histogram name (e.g., "valueHistogram") to have **different**
value expressions in different scopes (LIVE vs ARCHIVED). This is **intentional** — it
mirrors how other per-scope configurations work (e.g., `facetedPartiallyInScopes` can
have different condition expressions per scope). The histogram name identifies the index
slot, not the expression semantics.

### 1.3 Where Histogram Data Lives

| Reference Type | Index Location | Rationale |
|---|---|---|
| Grouped reference | `ReducedGroupEntityIndex` | Natural partitioning by group; each group's histogram is independent; mirrors facets |
| Ungrouped reference | `ReferencedTypeEntityIndex` | No group index exists; single histogram for entire reference type |

### 1.4 Mutual Exclusivity

`facetedPartially` and `bucketedPartially` conditions on the same reference are expected
to be mutually exclusive per group. The engine does not validate mutual exclusivity at
schema time — it is the user's responsibility.

### 1.5 Explicit Exclusions

- **Parent entity attributes**: Value and condition expressions must not reference
  `$entity.parentEntity.attributes[...]`. `PARENT_ENTITY_ATTRIBUTE` and
  `PARENT_ENTITY_REFERENCE_ATTRIBUTE` dependency types are not supported for histograms.
  This is validated at schema load time.
- **Query-time computation**: How `HistogramDataCruncher` consumes the FilterIndex data
  is a separate plan. The index structure defined here is compatible with the existing
  histogram pipeline: `FilterIndex.getHistogramOfAllRecords()` returns
  `InvertedIndexSubSet` with `ValueToRecordBitmap[]` that `HistogramDataCruncher`
  already accepts.

### 1.6 Reflected Reference Handling

Histogram definitions are **not** inherited by reflected references. This is a deliberate
design decision that differs from the `facetedInherited` pattern:

1. **Semantic invalidity of inheritance:** `HistogramIndexDefinition.valueExpression` uses
   paths like `$reference.referencedEntity?.attributes['basicUnitValue']` that resolve to
   a specific entity type. In a reflected reference, `referencedEntity` points to the
   **opposite** entity type — inheriting the expression would attempt to read an attribute
   that likely doesn't exist on the other entity, and even if it did, the semantic meaning
   would be wrong. The `bucketedPartially` condition expression has the same
   direction-specific problem. This is fundamentally different from `facetedInherited`,
   which only inherits a boolean "is faceted" flag per scope — no entity-type-specific
   expressions are involved.

2. **Explicit configuration required:** Reflected references must define their own
   `bucketedInScope()` / `bucketedPartiallyInScope()` configurations explicitly if they
   need histogram indexing. A reflected reference with no explicit bucketed configuration
   simply has no histogram indexes — it does not fall back to the declaring side's
   definitions.

3. **Cross-entity mutations:** When an attribute change fires a cross-entity trigger,
   the executor processes all entity collections that have references (declared or
   reflected) to the mutated entity type. The `CatalogExpressionTriggerRegistry` stores
   triggers by `ownerEntityType` — during `rebuildForEntityType()`, reflected references
   contribute triggers keyed to the **reflecting** entity type (the one whose indexes
   need updating), not the declaring type. Each direction's triggers are independent.

---

## 2. Index Structure

### 2.1 Histogram FilterIndex in ReducedGroupEntityIndex

Add new fields alongside the existing attribute filter indexes and cardinality tracking:

```
ReducedGroupEntityIndex:
  existing:
    attributeIndex             → AttributeIndex (reference attributes)
    facetIndex                 → FacetIndex
    cardinalityIndexes         → TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex>
    referencedPrimaryKeysIndex → TransactionalMap<Integer, TransactionalBitmap>
    pkCardinalities            → TransactionalMap<Integer, Integer>

  new:
    histogramIndexes           → TransactionalMap<String, FilterIndex>
    histogramCardinalities     → TransactionalMap<String, AttributeCardinalityIndex>
```

**Key design:** `histogramIndexes` is keyed by histogram name (from
`HistogramIndexDefinition.nameOfTheIndex`). Each `FilterIndex` maps histogram values
to bitmaps of **owner entity primary keys** (Products).

**FilterIndex value type: original numeric type.** Histogram values are stored in the
attribute's **original numeric type** (`Byte`, `Short`, `Integer`, `Long`, or `BigDecimal`)
to minimize memory overhead. Conversion to `BigDecimal` is deferred to query-time histogram
computation (`HistogramDataCruncher` uses `toBigDecimalConverter` at read time). The
`FilterIndex` constructor receives the attribute's `plainType` class (e.g., `Short.class`,
`Integer.class`) and uses the corresponding normalizer and comparator from
`FilterIndex.getNormalizer()` and `FilterIndex.getComparator()`.

**Type preservation pipeline:** Raw attribute values pass through to the histogram
FilterIndex without type conversion. The only normalization applied is for `BigDecimal`
values, where `stripTrailingZeros()` is called for consistent equality semantics. For
integral types (`Byte`, `Short`, `Integer`, `Long`), values are stored as-is — their
`equals()` is straightforward and requires no normalization. This pipeline is applied
identically in both local triggers and cross-entity executors:

1. Read raw value from source (already in the attribute's declared type)
2. For `BigDecimal` only: apply `stripTrailingZeros()`; for integral types: pass as-is
3. Use this value for **both** the cardinality key **and** FilterIndex operations

**Supported source types:** `Byte`, `Short`, `Integer`, `Long`, `BigDecimal` and their
array variants (`Byte[]`, `Short[]`, `Integer[]`, `Long[]`, `BigDecimal[]`) — these are
the only numeric types in `EvitaDataTypes.SUPPORTED_QUERY_DATA_TYPES`. `Float` and
`Double` are **not** supported — `EvitaDataTypes.toSupportedType()` normalizes them to
`BigDecimal` at storage entry, so they never appear in attribute FilterIndexes.

**Cardinality key consistency.** `AttributeCardinalityKey` is a Java record using
auto-generated `equals()`. For integral types (`Byte`, `Short`, `Integer`, `Long`),
`equals()` is straightforward — `Short(50).equals(Short(50))` is always `true`. For
`BigDecimal`, `equals()` is **scale-sensitive** (`BigDecimal("50").equals(BigDecimal("5E+1"))`
is `false`), so `stripTrailingZeros()` must be applied before both cardinality and
FilterIndex operations. The pipeline above ensures this.

**Comparator:** The histogram `FilterIndex` uses the natural ordering comparator returned
by `FilterIndex.getComparator()` for the attribute's plain type. All supported numeric
types implement `Comparable` with natural ordering, so bucket deduplication works correctly.

**HistogramValueDescriptor carries `plainType`.** The `plainType` field
(`Class<? extends Serializable>`) is resolved at schema load time from the source
attribute schema and stored in `HistogramValueDescriptor`. It is used for:
- Lazy creation of `FilterIndex` and `AttributeCardinalityIndex` with the correct type
- Converting the `??` default value to the target type via `NumberUtils.convertToNumericType()`

Non-numeric types (`String`, `Boolean`, `DateTimeRange`, etc.) are never stored — the
schema-time validation in Section 3.5 step 6 rejects non-numeric source attributes.

**Array attribute semantics:** Array-typed numeric attributes (e.g., `Integer[]`) are
supported. Each array element produces a **separate histogram entry** for the same
owner PK. For example, if PV#5 has `prices = [10, 20, 30]`, the owner entity (Product)
appears in histogram buckets 10, 20, and 30 independently.

- **Cross-entity executor:** Works naturally — the source FilterIndex already stores
  individual elements (not arrays). `FilterIndex.addRecord()` iterates array elements
  during the original attribute indexing, so `ValueToRecordBitmap[]` contains
  per-element entries. The executor's JOIN logic handles this without modification.
- **Local triggers:** Must detect array values and iterate elements explicitly. When
  reading the raw attribute value from storage and it is an array (`instanceof
  Serializable[]`), iterate each element through the normalization pipeline and
  perform cardinality tracking + FilterIndex insert per element. This mirrors the
  existing pattern in `ReducedGroupEntityIndex.insertFilterAttribute()` (line ~469).
- **Cardinality tracking:** Each `(normalizedElement, ownerPK)` pair is tracked
  independently. An array `[10, 10, 20]` produces cardinality 2 for `(10, ownerPK)`
  and cardinality 1 for `(20, ownerPK)`. The FilterIndex contains ownerPK in both
  buckets 10 and 20 (cardinality > 0 for both).

**Cardinality tracking:** Multiple references from different entities can share the same
group. An entity can reference multiple ParameterValues with the same `basicUnitValue`
in the same group. Additionally, when `ReferenceSchemaContract.getCardinality()` allows
duplicates (e.g., `ZERO_OR_MORE`), a single entity can reference the **same**
ParameterValue multiple times — each duplicate reference increments the cardinality
count for that `(value, ownerPK)` pair.

The `histogramCardinalities` map (keyed by histogram name) tracks how many times each
`(value, ownerPK)` pair has been indexed. Only on transitions to/from zero are actual
FilterIndex adds/removes performed. This mirrors the existing `cardinalityIndexes`
pattern for reference attributes.

**Invariant assertion:** Each owner PK must appear **at most once** per value bucket in
the histogram FilterIndex bitmap. The cardinality gating mechanism (add to bitmap only
on 0→1 transition, remove only on 1→0 transition) guarantees this. As a defensive
measure, `insertHistogramValue()` must assert that the PK is not already present in the
target bucket's bitmap before adding. If the assertion fails, throw
`EvitaInternalError` (not user-facing — this indicates a cardinality tracking bug, not
invalid user input). This mirrors the assertion pattern in existing FilterIndex
operations.

**Why a separate map instead of reusing `cardinalityIndexes`:** Since both facet and
histogram are new implementation (no backward compatibility), the `cardinalityIndexes`
structure could be designed from scratch to accommodate both. However, a separate map
is still preferred because `cardinalityIndexes` is keyed by `AttributeIndexKey` and
iterated uniformly in `getAttributeIndexStorageKeyStream()` (hardcoded
`AttributeIndexType.CARDINALITY`) and `getModifiedStorageParts()`. Mixing histogram
entries in would require either
adding a discriminator to `AttributeIndexKey` (wide blast radius — used across the
codebase) or maintaining a parallel set of histogram keys (negating the savings).
A separate map also makes histogram removal (Section 7.2) trivial — clear the map
by histogram name instead of scanning for matching keys. The cost is one additional
`TransactionalMap` with identical lifecycle wiring.

**Cardinality key semantics:** `AttributeCardinalityIndex` stores
`AttributeCardinalityKey(recordId=ownerPK, value=histogramValue)` → count. The
`addRecord(value, ownerPK)` returns `true` when cardinality transitions 0→1 (add to
FilterIndex). The `removeRecord(value, ownerPK)` returns `true` when cardinality
reaches 0 (remove from FilterIndex).

### 2.2 Histogram FilterIndex in ReferencedTypeEntityIndex

For ungrouped references, add the same fields to `ReferencedTypeEntityIndex`:

```
ReferencedTypeEntityIndex:
  existing:
    cardinalityIndexes → TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex>

  new:
    histogramIndexes      → TransactionalMap<String, FilterIndex>
    histogramCardinalities → TransactionalMap<String, AttributeCardinalityIndex>
```

**Cardinality tracking for ungrouped references:** Cardinality tracking is necessary even
without groups. An entity can reference multiple entities that share the same histogram
value (e.g., Product references PV#5 and PV#8, both with `basicUnitValue=50`). The
cardinality index tracks how many times each `(value, ownerPK)` pair has been indexed.
Only when the count drops to zero is the ownerPK removed from that value's bucket in
the FilterIndex. This prevents premature removal when one reference is deleted but
another with the same value still exists.

### 2.2.1 ReducedEntityIndex — No Histogram Fields

`ReducedEntityIndex` (one instance per referenced entity PK in ungrouped references)
does **not** carry histogram fields. Ungrouped histograms live only in
`ReferencedTypeEntityIndex` because histograms aggregate across all referenced entities
— a per-entity split would fragment the data and prevent meaningful histogram
computation.

### 2.3 FilterIndex Lifecycle

- **Creation:** Lazily on first insert. When the first histogram value is added for a
  given histogram name, create the `FilterIndex` and corresponding
  `AttributeCardinalityIndex`. Track the new `AttributeCardinalityIndex` via the
  `CardinalityChangeTracker` (same as existing cardinality index lifecycle).
- **Removal:** When the `AttributeCardinalityIndex` becomes empty, remove both the
  cardinality index and the `FilterIndex` from their respective maps. Track the removed
  `AttributeCardinalityIndex` via `CardinalityChangeTracker`.
- **Empty FilterIndex:** Pruned when empty (same as attribute FilterIndex lifecycle in
  `AttributeIndex`). No empty FilterIndex instances are kept in the map.

### 2.4 Storage — Reusing Existing Patterns

**FilterIndex data:** Reuse `FilterIndexStoragePart`. The `AttributeIndexKey` for a
histogram FilterIndex uses:
- `referenceName` = the reference name (e.g., `"parameterValues"`)
- `attributeName` = the histogram name (e.g., `"valueHistogram"`)
- `locale` = `null` (histograms are not localized)

**Cardinality data:** Reuse `AttributeCardinalityIndexStoragePart` with the same
`AttributeIndexKey`. This mirrors how `ReducedGroupEntityIndex` already persists its
`cardinalityIndexes` — no new storage part class needed.

**Storage key differentiation:** Add `AttributeIndexType.HISTOGRAM` enum value.
`AttributeIndexType` is a nested enum within `AttributeIndexStoragePart` in `evita_engine`
(`evita_engine/.../spi/store/catalog/persistence/storageParts/index/AttributeIndexStoragePart.java`).
No cross-module coordination is required — both WBS-B02 (index structure) and WBS-B03
(storage) work within the same `evita_engine` module for this enum.
This ensures `computeUniquePartId()` in `AttributeIndexStoragePart` produces distinct
storage PKs for histogram FilterIndex entries vs. attribute FilterIndex entries that
happen to share the same `AttributeIndexKey`. The compressed key includes
`(attributeKey, indexType)`, so `FILTER` and `HISTOGRAM` entries for the same key
produce different storage PKs.

**Schema-time validation (bidirectional):** Validate that histogram index names do not
collide with reference attribute names within the same reference + scope. This must be
enforced in **both** directions:
- **Adding a histogram:** reject if histogram name matches an existing reference attribute
  name. Enforced in the histogram definition schema mutation.
- **Adding a reference attribute:** reject if attribute name matches an existing histogram
  name. Enforced in the reference attribute schema mutation.

This is a safety net even though `AttributeIndexType` differentiation prevents storage
collisions — the validation prevents user confusion. Validated in the `ReferenceSchema`
constructor alongside existing scope consistency checks. This cross-check must also be
documented in the multi-histogram schema change spec (`multi-histogram-schema-change.md`)
for alignment. The validation runs on every schema build, catching collisions from both
programmatic builders and deserialized schemas.

**Storage key stream:** Add histogram FilterIndex keys to
`getAttributeIndexStorageKeyStream()` in both `ReducedGroupEntityIndex` and
`ReferencedTypeEntityIndex`, using `AttributeIndexType.HISTOGRAM`.

**Modified storage parts:** Add histogram FilterIndex and cardinality dirty-check to
`getModifiedStorageParts()` in both index classes, following the existing pattern for
`cardinalityIndexes`.

**Deserialization dispatch:** The serializer must reconstruct `histogramIndexes` and
`histogramCardinalities` from persisted storage parts. During deserialization:
- Read the `AttributeIndexType` discriminator from each stored entry
- If `AttributeIndexType.FILTER`: populate `attributeIndex` (existing path)
- If `AttributeIndexType.HISTOGRAM`: populate `histogramIndexes` map (keyed by
  `attributeKey.attributeName()`, which holds the histogram name)
- If `AttributeIndexType.CARDINALITY`: populate `cardinalityIndexes` (existing path)
- A new `HISTOGRAM_CARDINALITY` type (or reuse `CARDINALITY` with histogram key
  differentiation) populates `histogramCardinalities`

The `computeUniquePartId()` scheme uses the same bit-packing as attribute entries:
`(attributeKey hash, indexType ordinal)`. Adding `HISTOGRAM` to the enum ordinals
is sufficient — no changes to the packing scheme itself.

### 2.5 Transactional Memory

Both `histogramIndexes` and `histogramCardinalities` participate in the transactional
memory lifecycle:
- `createLayer()` / `removeLayer()` / `removeTransactionalMemoryOfReferencedProducers()`
- `createCopyWithMergedTransactionalMemory()` includes both maps
- `createCopyForNewCatalogAttachment()` carries both maps forward

Follow the exact same pattern as `cardinalityIndexes` in `ReducedGroupEntityIndex`.

**Deep copy semantics for `createCopyForNewCatalogAttachment()`:**
`FilterIndex` contains `InvertedIndex` which is a `TransactionalLayerProducer`.
The copy constructor must:
- Create new `TransactionalMap` wrappers for `histogramIndexes` and
  `histogramCardinalities`
- **Deep-copy** each `FilterIndex` within `histogramIndexes` (not just wrap the map) —
  each `FilterIndex` has its own transactional layer that must be independently managed
- **Deep-copy** each `AttributeCardinalityIndex` within `histogramCardinalities`

This mirrors how `cardinalityIndexes` is handled in the existing
`ReducedGroupEntityIndex.createCopyForNewCatalogAttachment()` implementation — follow
that exact pattern for the histogram maps.

---

## 3. Value Resolution

### 3.1 Design Principle

At schema load time, parse the value expression, validate it, and extract metadata that
identifies which attribute's FilterIndex to read from. No arithmetic transforms in v1 —
store raw attribute values. If transforms are needed in the future, they are a query-time
concern (applied to histogram bucket labels, not index data).

### 3.2 Supported Value Expression Forms

Only the following forms are accepted. Anything else is **rejected at schema load time
with a clear error**:

| Form | Example | Resolution |
|---|---|---|
| Referenced entity attribute | `$reference.referencedEntity?.attributes['basicUnitValue']` | Read from referenced entity's FilterIndex |
| Reference attribute | `$reference.attributes['someValue']` | Read from reference attribute FilterIndex |
| With null coalesce | `... ?? 0.0` | Default value when attribute is null |

**Rejected forms (with error):**
- Arithmetic expressions (`* 2`, `+ 10`)
- Multi-attribute expressions (`attr1 * attr2`)
- Conditional expressions (`if ... then ...`)
- Entity-level attributes (`$entity.attributes[...]`)
- Parent entity attributes (`$entity.parentEntity.attributes[...]`)
- Group entity attributes (`$reference.groupEntity?.attributes[...]`) — as value source

**Error message templates:**
- Multi-attribute: `"Histogram value expression for reference '%s', histogram '%s' references multiple attributes (%s). Only single-attribute expressions are supported."`
- Non-numeric type: `"Histogram value expression for reference '%s', histogram '%s' references non-numeric attribute '%s' (type: %s). Only numeric types from EvitaDataTypes are supported (Byte, Short, Integer, Long, BigDecimal)."`
- Non-filterable: `"Histogram value expression for reference '%s', histogram '%s' references attribute '%s' which is not filterable. Source attributes must be filterable to guarantee a FilterIndex exists."`
- Unsupported form: `"Histogram value expression for reference '%s', histogram '%s' uses unsupported expression form. Only $reference.referencedEntity?.attributes['x'] and $reference.attributes['x'] (with optional ?? default) are supported."`
- Parent entity: `"Histogram expressions must not reference parent entity attributes ($entity.parentEntity.attributes[...]). Reference '%s', histogram '%s'."`

**Rationale for rejection:** These forms cannot be resolved from a single FilterIndex
lookup. Supporting them would require per-entity expression evaluation, which is not
feasible for mass cross-entity updates.

### 3.3 Constraint: Source Attribute Must Be Filterable

The attribute referenced by the value expression **must** be marked as filterable in the
source entity's schema. This guarantees a `FilterIndex` exists for the attribute,
enabling index-based mass updates without per-entity storage reads.

Validated at schema load time by the `HistogramValueDescriptorFactory`. The factory
receives `schemaResolver` to access the referenced entity schema (for
`REFERENCED_ENTITY_ATTRIBUTE`). If the referenced attribute is not filterable, the
schema mutation is rejected with an error.

**Cross-schema dependency timing:** If the histogram schema mutation is applied
**before** the referenced entity type exists (e.g., Product references ParameterValue,
but ParameterValue schema hasn't been created yet), the `schemaResolver` returns null.
In this case, **reject the mutation eagerly** with a clear error: the referenced entity
type must exist before a histogram value expression can reference its attributes.

This is consistent with evitaDB's existing pattern: reference schema creation already
requires the referenced entity type to exist. The histogram definition builds on this
prerequisite — by the time `bucketedPartially` and histogram definitions are added to
a reference, the referenced entity type's schema is guaranteed to exist. No deferred
validation mechanism is needed.

**Reverse validation:** Removing filterability from an attribute while any histogram's
value expression references it must also be rejected. Enforce in the attribute schema
mutation that removes filterability: scan all reference schemas (across all entity
types, not just the current one) for histogram definitions whose
`HistogramValueDescriptor.sourceAttributeName` matches the attribute being
de-filterable-ized. If any match, reject the mutation with an error explaining the
dependency. This is covered by **WBS-B09** (Schema Change Handling).

### 3.4 HistogramValueDescriptor Record

```
record HistogramValueDescriptor(
    HistogramValueSource source,          // REFERENCE_ATTRIBUTE or REFERENCED_ENTITY_ATTRIBUTE
    @Nullable String sourceEntityType,   // entity type owning the FilterIndex (null for REFERENCE_ATTRIBUTE)
    String sourceAttributeName,  // attribute name in the source FilterIndex
    Class<? extends Serializable> plainType,  // original numeric type (Byte, Short, Integer, Long, BigDecimal)
    boolean arrayType,           // true if source attribute is array-typed (e.g., Integer[])
    @Nullable Number defaultValue    // from ?? operator, converted to plainType (null means skip null values)
)

enum HistogramValueSource {
    REFERENCE_ATTRIBUTE,         // $reference.attributes['x']
    REFERENCED_ENTITY_ATTRIBUTE  // $reference.referencedEntity?.attributes['x']
}
```

Built at schema load time by analyzing the value expression AST via `AccessedDataFinder`.
Immutable, stateless, safe for concurrent access.

**Design alternative considered:** Caching `HistogramValueDescriptor` directly on
`HistogramIndexDefinition` (an immutable record in `evita_api`) would eliminate the
separate factory and make resolution metadata available everywhere the definition is.
However, this was rejected because `HistogramIndexDefinition` lives in `evita_api`
and resolution requires schema lookups (`schemaResolver`) — introducing this dependency
into `evita_api` would violate module boundaries.

### 3.5 Resolution Factory

Create `HistogramValueDescriptorFactory`:

```
static HistogramValueDescriptor build(
    Expression valueExpression,
    ReferenceSchemaContract referenceSchema,
    Function<String, EntitySchemaContract> schemaResolver
) → HistogramValueDescriptor
```

Steps:
1. Use `AccessedDataFinder.findAccessedPaths(valueExpression)` to extract paths
2. Validate: exactly one attribute path (reject multi-attribute)
3. Validate: path does not reference parent entity, group entity attributes, or entity-level attributes
4. Classify path as `REFERENCE_ATTRIBUTE` or `REFERENCED_ENTITY_ATTRIBUTE`
5. Extract attribute name from path
6. Resolve source entity schema; validate source attribute is filterable; validate source
   attribute **plain type** (via `attributeSchema.getPlainType()`) is numeric (one of:
   `Byte`, `Short`, `Integer`, `Long`, `BigDecimal`). Reject with clear error if
   non-numeric (e.g., `String`, `Boolean`, `DateTimeRange`). Both scalar types (e.g.,
   `Integer`) and their array variants (e.g., `Integer[]`) are accepted — use
   `getPlainType()` which unwraps arrays to their component type.
   Note: `Float` and `Double` are excluded — `EvitaDataTypes` normalizes them to
   `BigDecimal` at storage entry, so they never appear as attribute types in schemas
7. Determine whether the source attribute is array-typed (via
   `attributeSchema.getType().isArray()`). Store this in `HistogramValueDescriptor`
   as `boolean arrayType` — used by local triggers to know whether to iterate elements
8. Extract default value from `??` operator at the top level (if present)
9. Return immutable `HistogramValueDescriptor`

### 3.6 Runtime Usage

**Local triggers (per-entity):** Read the attribute value directly from the entity's
storage part (via `ExistingAttributeValueSupplier` or the mutation's new value).
Values are stored in the attribute's original numeric type (no type conversion).
For `BigDecimal`, strip trailing zeros. Apply default if null.

**Cross-entity mass updates:** Use `HistogramValueDescriptor` metadata to locate the
source `FilterIndex`:
- For `REFERENCED_ENTITY_ATTRIBUTE`: get the referenced entity type's index, then
  `getFilterIndex(null, attributeSchema, null)` for the source attribute
- For `REFERENCE_ATTRIBUTE`: get the FilterIndex from the current index (attribute
  index on the `ReferencedTypeEntityIndex` or `ReducedGroupEntityIndex`)

The source FilterIndex provides `ValueToRecordBitmap[]` mapping values to entity PKs,
which the executor JOINs with reference cardinality data (see Section 5).

---

## 4. Trigger Infrastructure

### 4.1 HistogramExpressionTrigger Interface

Keep the marker-interface pattern consistent with `FacetExpressionTrigger`. Add only
the minimal metadata needed to identify the histogram:

```
interface HistogramExpressionTrigger extends ExpressionIndexTrigger {
    /** Name of the histogram index from HistogramIndexDefinition */
    String getHistogramIndexName();

    /** Pre-built resolution metadata for locating source FilterIndex */
    HistogramValueDescriptor getValueDescriptor();
}
```

The inherited `evaluate()` method handles the **condition** expression (same as facets).
The `getValueDescriptor()` provides the source attribute metadata for value lookups.

No `evaluateValue()` method on the trigger. Value computation is:
- Local triggers: resolved from storage parts by the mutator directly (attribute read)
- Cross-entity: resolved from source FilterIndex by the executor using `getValueDescriptor()`

### 4.2 AbstractExpressionIndexTriggerImpl + HistogramExpressionTriggerImpl

**Extract common base class.** `FacetExpressionTriggerImpl` contains ~250 lines of
expression/proxy/FilterBy infrastructure (all 13 fields, both constructors, all getter
implementations, `evaluate()`, and `convertResult()`) that histogram triggers need
identically. Extract `AbstractExpressionIndexTriggerImpl` to eliminate this duplication:

```
AbstractExpressionIndexTriggerImpl implements ExpressionIndexTrigger:
    // All 13 fields: ownerEntityType, referenceName, scope, mutatedEntityType,
    // dependencyType, dependentReferenceName, dependentAttributes,
    // localEntityAttributes, localReferenceAttributes, localAssociatedData,
    // usesParent, expression, proxyDescriptor, filterByConstraint
    // Both constructors (cross-entity and local-only)
    // All ExpressionIndexTrigger method implementations
    // evaluate() and convertResult()

FacetExpressionTriggerImpl extends AbstractExpressionIndexTriggerImpl
    implements FacetExpressionTrigger:
    // Empty body — pure type marker

HistogramExpressionTriggerImpl extends AbstractExpressionIndexTriggerImpl
    implements HistogramExpressionTrigger:
    // Additional fields:
    //   histogramIndexName: String
    //   valueResolution: HistogramValueDescriptor
    // Corresponding getters
```

**Constructors** for `HistogramExpressionTriggerImpl`:

**Cross-entity constructor:**
- Delegates all `ExpressionIndexTrigger` fields to `super()`
- Plus: `histogramIndexName`, `valueResolution`

**Local-only constructor:**
- Delegates local fields to `super()` (no filterByConstraint)
- Plus: `histogramIndexName`, `valueResolution`

This eliminates ~200 lines of duplication and creates a clean extension point for
future trigger types (e.g., sortable conditional indexing).

### 4.3 HistogramExpressionTriggerFactory

```
static List<HistogramExpressionTrigger> buildTriggersForReference(
    String ownerEntityType,
    ReferenceSchemaContract referenceSchema,
    Function<String, EntitySchemaContract> schemaResolver
)
```

Steps:
1. Get `bucketedPartiallyInScopes` and `getAllHistogramIndexDefinitions()`
2. If no histogram definitions → return empty list
3. For each scope where bucketed:
   a. If `bucketedPartially` exists for scope: analyze condition expression paths,
      build condition proxy descriptor and pre-translate to `FilterBy`
   b. If `bucketedPartially` is null for scope: **unconditional bucketing** — all
      references are bucketed. The trigger's `expression` field is null, `evaluate()`
      returns `true` unconditionally (null-check pattern inherited from
      `AbstractExpressionIndexTriggerImpl`), and `getFilterByConstraint()` returns `null`
      (see Section 5.3 step 3 for handling). Only value-dependency triggers are created.
      **Null condition check ownership:** The trigger handles this internally. Callers
      must NOT add their own null-expression check — always call `trigger.evaluate()`
      and let the trigger decide.
   c. For each histogram definition `(name, valueExpression)` in scope:
      - Build `HistogramValueDescriptor` via factory
      - Analyze value expression paths for cross-entity dependencies
      - Combine condition dependency paths (if any) + value dependency paths
   d. Create triggers per unique `DependencyKey`

**Dual dependency registration:** A single histogram definition produces triggers
registered under potentially different keys in `CatalogExpressionTriggerRegistry`:

| Dependency | Registry Key | Fires When |
|---|---|---|
| Condition on group attribute | `("parameter", GROUP_ENTITY_ATTRIBUTE)` | Parameter.inputWidgetType changes |
| Value on referenced entity attribute | `("parameterValue", REFERENCED_ENTITY_ATTRIBUTE)` | PV.basicUnitValue changes |

When `bucketedPartially` is null, only value-dependency triggers are registered.
No condition-dependency triggers exist because there is no condition to re-evaluate.

**Same-entity dependencies:** When condition and value both depend on the same entity
type and dependency type (e.g., both read attributes from the referenced entity), they
share a `DependencyKey` and produce a single trigger. The executor handles this uniformly
(see Section 5) — it always re-evaluates condition AND re-resolves value, regardless of
which attribute triggered the mutation.

### 4.4 CatalogExpressionTriggerRegistry Extension

**Cross-entity trigger index: shared storage with type filtering.**
The existing `triggerIndex` in `CatalogExpressionTriggerRegistryImpl` stores
`Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>>` — both
`FacetExpressionTrigger` and `HistogramExpressionTrigger` coexist in the same lists
without type discrimination. No separate data structure is needed.

The existing `getTriggersForAttribute()` returns `List<ExpressionIndexTrigger>` which
already includes both types. Consumers use `instanceof` to filter:

```java
List<ExpressionIndexTrigger> allTriggers = registry.getTriggersForAttribute(
    entityType, depType, attrName
);
// Facet triggers:
allTriggers.stream().filter(t -> t instanceof FacetExpressionTrigger).toList();
// Histogram triggers:
allTriggers.stream().filter(t -> t instanceof HistogramExpressionTrigger).toList();
```

**Alternatively**, add a single generic lookup method to reduce boilerplate:

```java
<T extends ExpressionIndexTrigger> List<T> getTriggersForAttribute(
    String mutatedEntityType, DependencyType dependencyType,
    String attributeName, Class<T> triggerType
)
```

This scales to future trigger types without new methods. Either approach is acceptable.

**Similarly** for entity removal (no attribute filter):

```java
<T extends ExpressionIndexTrigger> List<T> getTriggersFor(
    String mutatedEntityType, DependencyType dependencyType,
    Class<T> triggerType
)
```

The existing untyped `getTriggersFor()` already returns all triggers for the given
key — entity removal simply filters the result by type.

**Local trigger index for histograms: separate structure required.**
The existing `localTriggerIndex` is typed as
`Map<String, Map<String, Map<Scope, FacetExpressionTrigger>>>` — it stores
`FacetExpressionTrigger` directly (not the base type). Adding a parallel structure:

```
localHistogramTriggerIndex:
  Map<String,                          // ownerEntityType
    Map<String,                        // referenceName
      Map<Scope,                       // scope
        Map<String,                    // histogramName
          HistogramExpressionTrigger>  // single trigger per name
        >
      >
    >
  >
```

The key depth is 4 (vs. 3 for facets) because multiple histogram definitions
can exist per `(ownerEntityType, referenceName, scope)`.

Add lookup method:

```java
@Nonnull
Map<String, HistogramExpressionTrigger> getLocalHistogramTriggers(
    @Nonnull String ownerEntityType,
    @Nonnull String referenceName,
    @Nonnull Scope scope
)
// Returns: histogramName → single trigger (one per histogram name)
// Returns empty map if no histogram triggers defined
```

Each histogram name maps to exactly **one** local trigger. The trigger factory merges
condition + value dependency metadata into a single trigger per histogram name. This
prevents duplicate processing during local evaluation (Section 6.2) — iterating by
histogram name guarantees each histogram is evaluated exactly once.

**Lifecycle methods that must handle `localHistogramTriggerIndex`:**

- **`insertLocalTrigger()`**: Currently hardcoded to `FacetExpressionTrigger`. Add a
  parallel `insertLocalHistogramTrigger(Map<...>, HistogramExpressionTrigger)` that
  inserts into the 4-deep map under `(ownerEntityType, referenceName, scope,
  histogramName)` using `putIfAbsent` semantics (same as facet local triggers).
- **`removeTriggersOwnedBy()`**: Must also iterate `localHistogramTriggerIndex` and
  remove entries where `ownerEntityType` matches. Same iteration pattern as the
  existing facet cleanup.
- **`rebuildForEntityType()`**: Deep-copy `localHistogramTriggerIndex` (same pattern
  as `deepCopyLocalIndex`), remove old entries, insert new histogram triggers.
- **`deepCopyLocalIndex()`**: Add a parallel `deepCopyLocalHistogramIndex()` that
  deep-copies the 4-deep map into mutable structures for rebuild.

Update `rebuildForEntityType()` to process `HistogramExpressionTrigger` instances
alongside facet triggers — separate them by `instanceof` and route to the
appropriate local index.

### 4.5 Integration Point: Where Triggers Are Built

`HistogramExpressionTriggerFactory.buildTriggersForReference()` is called from two
locations. Both must be updated:

**1. Cold-start initialization: `buildFromSchemas()`**

`CatalogExpressionTriggerRegistryImpl.buildFromSchemas()` (line ~139) currently only
calls `FacetExpressionTriggerFactory.buildTriggersForReference()`. Its own comment
states: *"When histogram trigger support is added, this method must be extended to
also call the histogram trigger factory."* Update to call both factories:

```java
// In buildFromSchemas(), for each reference:
allTriggers.addAll(FacetExpressionTriggerFactory.buildTriggersForReference(...));
allTriggers.addAll(HistogramExpressionTriggerFactory.buildTriggersForReference(...));
```

**2. Schema change: `Catalog.rebuildExpressionTriggerRegistryForEntityType()`**

`Catalog.rebuildExpressionTriggerRegistryForEntityType()` (line ~1894) currently
builds triggers only from `FacetExpressionTriggerFactory`. The `newTriggers` list
passed to `rebuildForEntityType()` must include results from **both** factories:

```java
List<ExpressionIndexTrigger> allTriggers = new ArrayList<>();
for (ReferenceSchemaContract ref : entitySchema.getReferences().values()) {
    allTriggers.addAll(FacetExpressionTriggerFactory.buildTriggersForReference(
        entityType, ref, schemaResolver));
    allTriggers.addAll(HistogramExpressionTriggerFactory.buildTriggersForReference(
        entityType, ref, schemaResolver));
}
registry.rebuildForEntityType(entityType, allTriggers);
```

**General flow during catalog initialization / schema change:**
1. `Catalog.updateSchema()` → detects entity schema changed
2. Calls `Catalog.rebuildExpressionTriggerRegistryForEntityType(entityType)`
3. Builds trigger list from both factories for each reference
4. Calls `registry.rebuildForEntityType(entityType, allTriggers)`
5. Registry deep-copies indexes, removes old triggers for entity type, inserts new
   triggers (routing by `instanceof` to `triggerIndex` and `localHistogramTriggerIndex`)

---

## 5. Cross-Entity Re-Evaluation

### 5.1 Unified ReevaluateExpressionMutation

Replace both `ReevaluateExpressionMutation` and the previously proposed
`ReevaluateHistogramExpressionMutation` with a single unified mutation:

```java
record ReevaluateExpressionMutation(
    @Nonnull String referenceName,
    int mutatedEntityPK,
    @Nonnull DependencyType dependencyType,
    @Nonnull Scope scope
) implements IndexMutation
```

**Rationale:** Both mutation types have **identical** fields (4 fields). A unified
mutation eliminates:
- A separate mutation record class
- A separate executor class registration in `IndexMutationExecutorRegistry`
- A separate WAL serializer
- Duplicate source-side detection methods
- Deduplication complexity where the same attribute change fires **both** a facet and
  a histogram mutation for the same `(referenceName, scope)`

The single executor iterates **all** triggers from the registry for the given key
`(referenceName, dependencyType, scope)`, dispatching to facet vs. histogram handling
based on `instanceof`. This also naturally handles the case where a single attribute
change affects both facet and histogram expressions on the same reference — one
mutation, one affected-PK resolution, then per-type condition evaluation and apply
steps (facet condition → FacetIndex writes, histogram condition → FilterIndex writes).
Note: `facetedPartially` and `bucketedPartially` are **independent** condition
expressions, so condition evaluation cannot be shared — see Section 5.3 step 3–4.

**Implementation:** Replace `ReevaluateExpressionMutation` with
`ReevaluateExpressionMutation`. Replace `ReevaluateExpressionExecutor` with the
unified `ReevaluateExpressionExecutor` (see Section 5.3). Since both facet and
histogram are new implementation, no backward compatibility is needed — no old WAL
serializer registration, no migration path.

**No `oldValue`/`newValue` fields (v1 design decision):** Carrying old/new values
would enable O(1) direct bucket operations for value-trigger removals. However, this
optimization was deferred from v1 because:
- It introduces ambiguity when multiple histogram definitions share a mutation but have
  different value sources
- It adds dual code paths in the executor (point-lookup vs. scan)
- The savings are minimal: B (distinct histogram values) is typically 10-200, and
  RoaringBitmap `contains()` is O(1), so scanning all buckets costs microseconds

Instead, the executor always uses bucket scanning for removal (see Section 5.3 step 5).

### 5.2 Source-Side Detection

Extend `EntityIndexLocalMutationExecutor.popIndexImplicitMutations()`:

**Single loop — no parallel methods needed.** The existing `collectEntityAttributeTriggers`
(line ~501) and `collectReferenceAttributeTriggers` (line ~543) already iterate
`List<ExpressionIndexTrigger>` from `registry.getTriggersForAttribute()`, which returns
**both** facet and histogram triggers in the same list (see Section 4.4). With the
unified `ReevaluateExpressionMutation`, these existing methods work with **minimal
changes** — the only modification is renaming the mutation class:

```java
// Existing loop in collectEntityAttributeTriggers — MODIFIED (rename only):
for (ExpressionIndexTrigger trigger : triggers) {
    // Deduplicate: one mutation per (referenceName, scope) combination
    // (unchanged logic — same deduplication by key)
    create ReevaluateExpressionMutation(
        trigger.getReferenceName(), entityPK, depType, scope
    );
}
```

**No new methods needed.** The existing loop already handles both trigger types
because it creates mutations at the `ExpressionIndexTrigger` level (base type), not
at the facet/histogram level. Deduplication by `(referenceName, scope)` naturally
coalesces triggers from both types into a single mutation.

For `collectReferenceAttributeTriggers`, the same applies — the existing filter
`trigger.getDependentReferenceName() == mutatedRefName` works for both types.

**Deduplication:** Multiple definitions (facet + histogram) on the same reference/scope
produce a single mutation. The executor iterates all triggers internally and dispatches
by type.

**Entity removal:** Uses the existing `getTriggersFor(entityType, depType)` (untyped —
returns all trigger types). Fire all triggers unconditionally:
```java
for (DependencyType depType : ALL_CROSS_ENTITY_DEPENDENCY_TYPES) {
    List<ExpressionIndexTrigger> triggers = registry.getTriggersFor(entityType, depType);
    // Create one ReevaluateExpressionMutation per unique (referenceName, scope)
}
```

**Dispatch path:** The unified `ReevaluateExpressionMutation` is included in the
`IndexImplicitMutations` returned from `popIndexImplicitMutations()`. `Catalog`
dispatches these to target entity collections via the existing `applyMutations()` call.
`IndexMutationExecutorRegistry.INSTANCE` maps `ReevaluateExpressionMutation.class` →
the unified executor (replacing the old `ReevaluateExpressionMutation` mapping).

### 5.3 Target-Side Execution: Unified ReevaluateExpressionExecutor

Rename `ReevaluateExpressionExecutor` → `ReevaluateExpressionExecutor`.
Stateless singleton implementing `IndexMutationExecutor<ReevaluateExpressionMutation>`.
Register in `IndexMutationExecutorRegistry`.

**Multi-phase algorithm: shared PK resolution, per-type condition evaluation.**

The unified executor processes all trigger types in a single pass. Step 1 partitions
triggers by type. Step 2 (PK resolution) is shared — it depends on reference topology,
not on condition expressions. Steps 3–5 handle facet triggers with the facet condition.
Steps 6–9 handle histogram triggers with the histogram condition.

**Generalized terminology:** Rename `AffectedFacetGroup` → `AffectedReferenceGroup`,
`AffectedFacetEntry` → `AffectedReferenceEntry` to reflect their use by both facet
and histogram processing. The `facetPK` field becomes `referencedEntityPK`.

1. **Look up ALL triggers for the key:**
   Get all `ExpressionIndexTrigger` instances for `(referenceName, dependencyType, scope)`
   from the registry (untyped — returns both facet and histogram triggers).
   If none, return (schema may have changed concurrently).
   Partition into `facetTriggers` and `histogramTriggers` via `instanceof`.

2. **Resolve affected owner PKs** (shared — existing logic, renamed):

   **For grouped references:**
   - Get `ReferencedTypeEntityIndex` via
     `target.getIndex(new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, referenceName))`
   - If index is null, return (reference may have been removed concurrently).
   - If `dependencyType` is `GROUP_ENTITY_ATTRIBUTE` / `GROUP_ENTITY_REFERENCE_ATTRIBUTE`:
     * `mutatedEntityPK` IS the group PK
     * Get the specific group's `ReducedGroupEntityIndex` via
       `referencedTypeIndex.getGroupIndex(mutatedEntityPK)` (returns single index for group PK)
     * If group index is null, return (group may have been removed concurrently).
     * Iterate `referencedPrimaryKeysIndex` to extract per-referenced-entity owner PK bitmaps →
       produce `(referencedEntityPK, groupPK=mutatedEntityPK, ownerPKs)` tuples
   - If `dependencyType` is `REFERENCED_ENTITY_ATTRIBUTE` / `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`:
     * `mutatedEntityPK` IS the referenced entity PK (e.g., PV#5)
     * Iterate **all** `ReducedGroupEntityIndex` instances via
       `referencedTypeIndex.getAllReferenceIndexes()` (returns `int[]` of group PKs),
       then `referencedTypeIndex.getGroupIndex(groupPK)` for each
     * In each: check `referencedPrimaryKeysIndex` for `mutatedEntityPK`
     * Collect `(referencedEntityPK=mutatedEntityPK, groupPK, ownerPKs)` tuples
     * **Multiple groups edge case:** A single referenced entity (e.g., PV#5) may appear
       in **multiple** group indexes (e.g., group #42 AND group #99). The iteration over
       all group indexes handles this naturally. Per-group cardinality tracking ensures
       correctness.
     * **Performance note:** This is O(G) index lookups where G = total number of groups
       for the reference type (can be thousands in e-commerce — parameters grouped by
       parameter type). This is the **same** O(G) behavior as the existing facet executor's
       `resolveForReferencedEntityAttribute()` — a known limitation, not a new one. A
       future optimization could add a reverse index (referenced PK → group PKs) to
       reduce to O(G_containing_PK), but this is not needed in v1.

   **For ungrouped references:**
   - Get `ReferencedTypeEntityIndex` via
     `target.getIndex(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName))`
   - If index is null, return.
   - Use `referencedTypeIndex.getAllReferenceIndexes()` to get referenced entity PKs
   - For each, get `ReducedEntityIndex` and call `getAllPrimaryKeys()` for owner PKs

3. **Evaluate facet condition + apply facet changes:**
   If `facetTriggers` is non-empty:
   a. Get pre-translated `FilterBy` from the facet trigger (the `facetedPartially`
      condition). Parameterize with `mutatedEntityPK`, evaluate against current indexes
      → `facetTruePKs` bitmap. If `FilterBy` is null (unconditional faceting):
      `facetTruePKs = allAffectedOwnerPKs`.
   b. Compute facet add/remove sets:
      ```
      facetShouldBeIndexed    = AND(allAffectedOwnerPKs, facetTruePKs)
      facetShouldNotBeIndexed = AND_NOT(allAffectedOwnerPKs, facetTruePKs)
      ```
   c. Apply facet changes using `facetShouldBeIndexed` / `facetShouldNotBeIndexed`:
      existing facet add/remove logic in `GlobalEntityIndex` and reduced indexes.
      This is the current `ReevaluateExpressionExecutor` code, preserved as-is.

   Uses the same session-optional query planning infrastructure as the existing
   facet executor.

4. **Evaluate histogram condition + compute histogram add/remove sets:**
   If `histogramTriggers` is non-empty:
   a. Get pre-translated `FilterBy` from the histogram trigger (the `bucketedPartially`
      condition — **different** from the facet condition). Parameterize with
      `mutatedEntityPK`, evaluate against current indexes → `histogramTruePKs` bitmap.
      If `FilterBy` is null (unconditional bucketing):
      `histogramTruePKs = allAffectedOwnerPKs`.
   b. Compute histogram add/remove sets:
      ```
      histogramShouldBeIndexed    = AND(allAffectedOwnerPKs, histogramTruePKs)
      histogramShouldNotBeIndexed = AND_NOT(allAffectedOwnerPKs, histogramTruePKs)
      ```

   **Why per-type condition evaluation is required:** `facetedPartially` and
   `bucketedPartially` are independent condition expressions. The business case in
   Section 1.1 demonstrates this: `inputWidgetType == 'CHECKBOX'` (facet) vs
   `inputWidgetType == 'INTERVAL'` (histogram). Using the wrong condition would
   produce incorrect add/remove sets. Only the affected PK resolution (step 2) is
   shared — it depends on reference topology, not on condition expressions.

5. **Apply histogram changes — remove for `histogramShouldNotBeIndexed`:**
   If `histogramTriggers` is empty, skip steps 5–7.
   For each owner PK in `histogramShouldNotBeIndexed`, for each histogram trigger:
   - Scan histogram FilterIndex buckets to find which bucket contains the owner PK
     (O(B) where B = distinct values; RoaringBitmap `contains()` is O(1), so this is
     B bitmap checks — microseconds for typical B=10-200)
   - Decrement cardinality via `histogramCardinalities[histogramName].removeRecord(value, ownerPK)`
   - If cardinality reaches 0: remove owner PK from that bucket in FilterIndex
   - If FilterIndex becomes empty: remove from `histogramIndexes` map

6. **Apply histogram changes — add/update for `histogramShouldBeIndexed`:**

   First remove any existing entries for these owner PKs (they may have stale values):
   same removal logic as step 5. Remove-before-add is intentional for simplicity.

   Then add current values. For each histogram trigger, get `HistogramValueDescriptor`:

   **If `REFERENCED_ENTITY_ATTRIBUTE`:**
   a. **Cross-collection FilterIndex access:** The executor needs the source entity
      type's `GlobalEntityIndex` to resolve attribute values. `IndexMutationTarget`
      must be extended with:

      ```java
      @Nullable FilterIndex getSourceFilterIndex(
          @Nonnull String entityType,
          @Nonnull String attributeName
      )
      ```

      Implementation: delegates to
      `catalogIndex.getGlobalEntityIndex(sourceEntityType)`, then
      `globalIndex.getFilterIndex(null, sourceAttributeSchema, null)`.
      Returns `null` if the entity type or attribute FilterIndex doesn't exist.

      **Why extend `IndexMutationTarget`:** The existing interface only exposes index
      access within the **same** entity collection (`getOrCreateIndex()`,
      `getIndexIfExists()`). The histogram executor needs **cross-collection** access
      for `REFERENCED_ENTITY_ATTRIBUTE` value resolution (reading ParameterValue's
      FilterIndex while executing within the Product collection). This is the only
      new method needed.

   b. Get source FilterIndex via `target.getSourceFilterIndex(sourceEntityType, sourceAttributeName)`.
      **If null, bail out** — source attribute's filterability may have been removed
      concurrently. Log at DEBUG level and return.
   c. Get `ValueToRecordBitmap[]` from source FilterIndex
   d. **Value handling:** The source FilterIndex stores values in the original attribute
      type (e.g., `Integer`, `Long`, `BigDecimal`). For each bucket
      `(value, referencedEntityPKs)`:
      - Check `value instanceof Number` (skip non-numeric values)
      - Pass the value as-is to the histogram FilterIndex (no type conversion)
      - Intersect `referencedEntityPKs` with the set of referenced PKs in this group
      - For matched referenced PKs: look up owner PKs from `referencedPrimaryKeysIndex`
      - Intersect owner PKs with `histogramShouldBeIndexed`
      - For each resulting owner PK: update cardinality, add to histogram FilterIndex
   e. Handle default value: for referenced PKs not found in any source bucket,
      if `defaultValue` is non-null, use it; otherwise skip.
      **Complement computation:** Collect encountered referenced PKs into a
      `RoaringBitmapWriter`. Compute `missingPKs = AND_NOT(allReferencedPKsInGroup,
      encounteredPKs)`. For each PK in `missingPKs`: look up owner PKs, intersect
      with `histogramShouldBeIndexed`, index using `defaultValue`.
      If `defaultValue` is null (no `??` operator), skip entirely.

   **If `REFERENCE_ATTRIBUTE`:**
   a. Get FilterIndex for `sourceAttributeName` from the reference attribute index:
      - **Grouped references:** from the `ReducedGroupEntityIndex` being processed
        (it holds reference attribute FilterIndexes per group)
      - **Ungrouped references:** from the `ReferencedTypeEntityIndex`
        (it holds reference attribute FilterIndexes for the entire reference type)
   b. **If null, bail out** (attribute FilterIndex may not exist).
   c. Get `ValueToRecordBitmap[]`
   d. For each bucket `(value, ownerPKs)`:
      - Check `value instanceof Number` (skip non-numeric values)
      - Pass the value as-is to the histogram FilterIndex (no type conversion)
      - Intersect with `histogramShouldBeIndexed`
      - For each resulting owner PK: update cardinality, add to histogram FilterIndex

7. **Defensive null guards (concurrent schema change resilience):**
   At any point where an index or FilterIndex lookup returns null, the executor must
   bail out gracefully (return, log at DEBUG). Schema changes can happen concurrently:
   - Step 2: `ReferencedTypeEntityIndex` may not exist (reference removed)
   - Step 6a/6b: source FilterIndex may not exist (filterability removed)
   - Step 6d: referenced entity may not exist in source FilterIndex

   A single pattern: `if (resource == null) { log.debug(...); return; }` at each
   lookup point. No exceptions thrown — the next transaction will have consistent state.

**Complexity:** O(B × intersectionCost × groupSize) per group, where B is the number
of distinct values in the source FilterIndex (typically 10-200 for e-commerce histograms).

**IndexMutationTarget integration:** The executor calls
`ReducedGroupEntityIndex.insertHistogramValue()` / `removeHistogramValue()` and
`ReferencedTypeEntityIndex.insertHistogramValue()` / `removeHistogramValue()` directly
(same as how the facet part calls `addFacet()` / `removeFacet()` on the index).
The only `IndexMutationTarget` extension needed is `getSourceFilterIndex()` for
cross-collection value resolution.

---

## 6. Local Triggers

### 6.1 Integration Point: ReferenceIndexMutator

Extend `ReferenceIndexMutator` to handle histogram indexing alongside facet indexing.
The local trigger fires when the owner entity's reference is inserted, removed, or
its attributes change.

### 6.2 Reference Insertion

When a new reference is created (e.g., Product gains reference to PV #5 in group #42):

1. Look up local histogram triggers:
   `registry.getLocalHistogramTriggers(ownerType, refName, scope)`
   Returns `Map<String, HistogramExpressionTrigger>` — one trigger per histogram name.
   Iterate by histogram name (no deduplication needed — one trigger per name).
2. For each `(histogramName, trigger)` entry:
   a. Evaluate condition: `trigger.evaluate(ownerPK, referenceKey, storageAccessor, schemaResolver)`.
      Returns `true` unconditionally when no condition expression exists (handled
      internally by the trigger — see Section 4.3 step 3b).
   b. If condition is true:
      - Read attribute value directly from storage (via `storageAccessor`), **not** from
        the source FilterIndex. Rationale: at local trigger time the referenced entity's
        indexes may not yet reflect the current transaction's mutations. Reading from
        storage ensures the value is consistent with the current transaction state.
        * For `REFERENCE_ATTRIBUTE`: read from the reference's own storage part via
          `ExistingAttributeValueSupplier` (same pattern as existing reference attribute
          reads in `ReferenceIndexMutator`).
        * For `REFERENCED_ENTITY_ATTRIBUTE`: read from referenced entity's storage part.
          `EntityIndexLocalMutationExecutor` must be extended with a cross-entity
          attribute reader: `readReferencedEntityAttribute(String entityType, int entityPK,
          String attributeName)`. This method delegates to
          `catalog.getCollectionForEntityOrThrow(entityType)` to obtain the entity
          collection, then reads the attribute from the entity's `AttributeStoragePart`
          via the collection's `StorageContainerAccessor`. The `Catalog` reference is
          already available to the executor through the `CatalogContract` passed during
          `EntityCollection.applyMutations()`. If the referenced entity does not exist,
          the method returns `null` (handled by default value logic below).
      - **Array handling:** If `valueResolution.arrayType()` is true and the raw value
        is `instanceof Serializable[]`, iterate each element through the normalization
        pipeline below and perform cardinality + FilterIndex insert per element. This
        mirrors `ReducedGroupEntityIndex.insertFilterAttribute()` (line ~469).
      - **Scalar handling:** Pass value as-is (for `BigDecimal`, strip trailing zeros);
        apply default if null and `defaultValue` is set.
      - **Attribute deleted / null handling:** If the raw attribute value is `null` (attribute
        not set, or entity does not exist) and `defaultValue` is non-null, use
        `defaultValue`. If `defaultValue` is also null, skip — no histogram entry is
        created. This means "attribute deleted" is handled identically to "attribute never
        set": the histogram entry is simply absent.
      - If value is non-null after normalization (or for each non-null array element):
        * **Grouped references:** Get/create histogram FilterIndex in the group's
          `ReducedGroupEntityIndex` via `executor.getOrCreateIndex(new EntityIndexKey(
          EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, referenceName, groupPK))`
        * **Ungrouped references:** Get/create histogram FilterIndex in
          `ReferencedTypeEntityIndex` via `executor.getOrCreateIndex(new EntityIndexKey(
          EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName))`
        * Update cardinality: `histogramCardinalities[name].addRecord(value, ownerPK)`
        * If cardinality transitions 0→1: add `value → ownerPK` to histogram FilterIndex
   c. If condition is false: no-op (reference not bucketed)

### 6.3 Reference Removal

When a reference is removed:

1. Look up local histogram triggers
2. For each histogram trigger:
   a. Scan histogram FilterIndex buckets for the owner PK to find the current value
   b. If found:
      - Decrement cardinality: `histogramCardinalities[name].removeRecord(value, ownerPK)`
      - If cardinality reaches 0: remove `ownerPK` from the value's bucket in FilterIndex
      - If FilterIndex becomes empty: remove from `histogramIndexes` map
      - If cardinality index becomes empty: remove from `histogramCardinalities` map

No condition evaluation needed — always remove unconditionally (same as facet removal).

### 6.4 Reference Attribute Change (Owner Entity Mutated)

When a reference attribute changes, it may affect the **value** expression, the
**condition** expression, or both. Handle each case:

**Case A — Value expression depends on the changed attribute:**

1. Look up local histogram triggers
2. For each trigger whose `valueResolution.source == REFERENCE_ATTRIBUTE` and
   `sourceAttributeName` matches the changed attribute:
   a. Get old value from `ExistingAttributeValueSupplier` (in original numeric type)
   b. Get new value from mutation (in original numeric type)
   c. If old value equals new value: skip
   d. Old value present:
      - Decrement cardinality for old value
      - If cardinality → 0: remove ownerPK from old value's bucket
   e. New value present (or default):
      - Increment cardinality for new value
      - If cardinality 0 → 1: add ownerPK to new value's bucket

**Case B — Condition expression depends on the changed attribute:**

If the `bucketedPartially` condition expression references a reference attribute
(e.g., `$reference.attributes['someFlag']`), changing that attribute must trigger
full condition re-evaluation:

1. Look up local histogram triggers whose condition expression depends on
   the changed attribute (check `trigger.getDependentAttributes()` for the
   attribute name)
2. For each such trigger:
   a. Evaluate condition with current (post-mutation) values
   b. If condition transitions **true → false**: remove histogram entry (Section 6.3 logic)
   c. If condition transitions **false → true**: add histogram entry (Section 6.2 logic)
   d. If condition remains true: no-op (value didn't change via this path; if the same
      attribute is also the value source, Case A above handles it)

**Both cases apply simultaneously** when the same attribute is referenced by both the
condition and value expressions. The condition re-evaluation (Case B) takes priority:
if the condition becomes false, the entry is removed regardless of the value change.

### 6.5 Group Change

When a reference's group changes (e.g., PV #5 moves from group #42 to group #99):

1. **Remove from old group:** Same as reference removal (Section 6.3) in old group's
   `ReducedGroupEntityIndex`
2. **Add to new group:** Same as reference insertion (Section 6.2) in new group's
   `ReducedGroupEntityIndex` — evaluate condition against new group, read value
   if condition is true

### 6.6 Deferred Evaluation

Histogram re-evaluations use the **same deferred queue** as facet re-evaluations.
Rename `deferredFacetReEvaluations` to `deferredExpressionReEvaluations` in
`EntityIndexLocalMutationExecutor`. Add histogram lambdas to this list alongside
facet lambdas. Both are flushed at the same point (`finishLocalMutationExecutionPhase`)
and have no ordering dependency.

---

## 7. Schema Change Handling

### 7.1 Histogram Definition Added to Existing Reference

When a histogram definition is added to a reference that already has indexed data:

1. The schema mutation (`SetReferenceSchemaBucketedMutation`) is a schema-level mutation
   in `evita_api`. The actual index rebuild happens in the **engine layer**: when
   `EntityCollection.updateSchema()` applies the mutation, the engine-side handler
   (analogous to how `SetReferenceSchemaFacetedMutation` is processed in
   `EntityCollection.applyMutations()` → `EntityIndexLocalMutationExecutor`) detects the
   new histogram definition and initiates the bulk rebuild. The rebuild logic should live
   in `EntityIndexLocalMutationExecutor` or a dedicated helper method called from it.
2. Iterate all affected entities via `GlobalEntityIndex.getAllPrimaryKeys()` — same
   approach used by the facet system when `facetedPartially` is enabled on an existing
   reference
3. For each entity:
   a. For each reference of the affected type in the affected scope:
      - Evaluate condition expression
      - If true: read attribute value, add to histogram FilterIndex in the group index
4. This is a **synchronous bulk operation** within the schema mutation transaction.
   It does not use the deferred queue — the entire rebuild completes before the schema
   mutation returns. Transaction boundaries are governed by the enclosing catalog
   transaction (same as facet bulk rebuilds).
5. For large catalogs (millions of entities), the rebuild iterates all PKs in a single
   pass without batching — same as the facet system's bulk rebuild. Progress reporting
   is not implemented in v1. If performance is a concern for very large catalogs, the
   facet and histogram bulk rebuilds should be optimized together in a future iteration.

### 7.2 Histogram Definition Removed

When a histogram definition is removed:

1. For each `ReducedGroupEntityIndex` (or `ReferencedTypeEntityIndex` for ungrouped):
   - Remove the `FilterIndex` from `histogramIndexes` for the histogram name
   - Remove the `AttributeCardinalityIndex` from `histogramCardinalities`
   - Track removed cardinality indexes via `CardinalityChangeTracker`
2. No per-entity evaluation needed — just clear the index structures

### 7.3 Value Expression Changed

When the value expression of an existing histogram definition changes:

1. Remove all entries from the histogram FilterIndex (same as 7.2 but keep the
   histogram definition)
2. Rebuild: iterate all entities and re-index (same as 7.1)

### 7.4 Condition Expression Changed

When `bucketedPartially` changes:

1. Re-evaluate condition for all entities with references of this type
2. Add/remove histogram entries based on new condition results
3. This mirrors how `facetedPartially` schema changes trigger re-evaluation

---

## 8. Data Change Scenarios

### 8.1 Group Entity Attribute Changes

**Scenario:** Parameter #42's `inputWidgetType` changes CHECKBOX → INTERVAL.

**Flow:**
1. Source (Parameter collection): detects attribute change
2. Registry lookup finds BOTH `FacetExpressionTrigger` AND `HistogramExpressionTrigger`
   for the same `(referenceName, scope)`
3. Creates a **single** `ReevaluateExpressionMutation` (unified — see Section 5.1)
4. Target executor: resolves affected PKs once, evaluates condition once, then:
   - Facet branch: removes facets for group #42
   - Histogram branch: adds histogram entries for group #42
5. Operations are independent — affect separate data structures (FacetIndex vs FilterIndex)

### 8.2 Referenced Entity Attribute Changes

**Scenario:** PV #5's `basicUnitValue` changes 50 → 75.

**Flow:**
1. Source (ParameterValue collection): detects attribute change
2. Creates `ReevaluateExpressionMutation(mutatedEntityPK=5, REFERENCED_ENTITY_ATTRIBUTE)`
3. Target executor: resolve affected groups → remove old entries → re-read current
   value from source FilterIndex (via `getSourceFilterIndex()`) → add to correct bucket

### 8.3 Reference Attribute Changes

**Scenario:** Product's reference attribute `someValue` changes 10 → 20.

**Flow:** Local trigger (Section 6.4) — old value from `ExistingAttributeValueSupplier`,
new value from mutation. Remove from bucket 10, add to bucket 20.

### 8.4 Entity Scope Change

**Flow:** `removeEntityFromIndexes(entity, oldScope)` removes all histogram entries,
then `addEntityToIndexes(entity, newScope)` re-indexes to new scope.

**Histogram cleanup mechanism:** `removeEntityFromIndexes` (line ~1548 in
`EntityIndexLocalMutationExecutor`) calls `unindexReferences()` which invokes
`ReferenceIndexMutator` removal methods for each reference on the entity. The histogram
removal in `ReferenceIndexMutator` (Section 6.3) fires unconditionally for each
reference being removed — this naturally cleans up all histogram entries. Individual
histogram entries are removed per-reference via cardinality decrement and FilterIndex
removal (not by destroying entire index instances). The `ReducedGroupEntityIndex` /
`ReferencedTypeEntityIndex` instances themselves survive scope changes — only the
entity's data within them is removed.

**No histogram-specific scope change code needed** beyond what Section 6.3 already
describes. The existing `unindexReferences` → `ReferenceIndexMutator.removeReference()`
call chain handles histograms once Section 6.3's histogram removal logic is implemented.

### 8.5 Entity Created / Removed

**Created:** Local triggers fire for each reference (Section 6.2).

**Removed:** `removeEntityFromIndexes()` removes all data. For cross-entity removal
(referenced entity deleted): the source entity's own index removals execute **before**
the cross-entity trigger reaches the target collection. By the time the histogram
executor runs, the deleted entity's values are already absent from the source FilterIndex.

This is handled correctly by the remove-before-re-add pattern in Section 5.3 step 7:
1. Executor removes old histogram entries for `histogramShouldBeIndexed` PKs (scan finds old
   values, removes them)
2. Executor attempts to re-add from source FilterIndex — deleted entity has no values
3. If `defaultValue` is non-null: indexes using default. If null: no entry added.
4. Net result: old histogram entries are cleaned up, replaced by default or nothing.

This ordering dependency is **by design**, not an accidental side effect. The executor
does not assume the source FilterIndex contains the deleted entity's values.

### 8.6 Referenced Entity Created After Product (Eventual Consistency)

**Flow:**
1. Product references PV #5, but PV #5 doesn't exist yet
2. At local trigger time: referenced entity storage part unavailable → value is null →
   no histogram entry (or default if `??` specified)
3. PV #5 is created with `basicUnitValue=50`
4. PV #5's attribute creation fires `REFERENCED_ENTITY_ATTRIBUTE` cross-entity trigger
5. Product executor: re-evaluates condition (true), reads current value from source
   FilterIndex (50), adds histogram entry

### 8.7 Reference Created / Removed

**Created:** Local trigger (Section 6.2).

**Removed:** Local trigger (Section 6.3) — unconditional removal.

### 8.8 Reference Group Change

**Flow:**
1. Remove from old group (Section 6.3) — histogram entries and facet entries
2. Add to new group (Section 6.2) — evaluate condition against new group,
   add histogram entries if condition true, add facet if facet condition true

### 8.9 Group Added / Removed from Reference

**Group added:** Same as reference insertion in the new group.

**Group removed:** Same as reference removal from the old group.

---

## 9. Implementation WBS

### WBS-B01: Value Resolution (evita_engine)

1. Create `HistogramValueSource` enum
2. Create `HistogramValueDescriptor` record
3. Verify `AccessedDataFinder` supports value expression path extraction (specifically
   `$reference.referencedEntity?.attributes[...]` and `$reference.attributes[...]` forms).
   Extend if needed — the facet system only uses `AccessedDataFinder` for condition
   expressions; value expressions may exercise different path patterns.
4. Create `HistogramValueDescriptorFactory.build()`:
   - `AccessedDataFinder` path extraction
   - Single-attribute validation
   - Path classification (REFERENCE_ATTRIBUTE / REFERENCED_ENTITY_ATTRIBUTE)
   - Filterable attribute validation
   - Numeric type validation (Byte, Short, Integer, Long, BigDecimal only)
   - Default value extraction from `??`
   - Rejection of unsupported expression forms with clear errors
5. Unit tests for factory: valid forms, invalid forms, error messages

### WBS-B02: Index Structure (evita_engine)

1. Add `histogramIndexes` and `histogramCardinalities` to `ReducedGroupEntityIndex`
2. Update all constructors (empty, from-persisted, transactional-copy). Since both
   facet and histogram are new implementation (no backward compatibility), histogram
   maps are **mandatory constructor parameters**, not optional additions to existing
   constructors. Design all three constructor variants to accept histogram maps cleanly.
3. Update `isEmpty()`, `resetDirty()`
4. Update `getAttributeIndexStorageKeyStream()` — include histogram keys with
   `AttributeIndexType.HISTOGRAM`
5. Update `getModifiedStorageParts()` — include histogram FilterIndex and cardinality
   dirty-check
6. Update transactional memory lifecycle methods
7. Add public methods: `getHistogramFilterIndex(String)`,
   `insertHistogramValue(String, Number, int, Class<? extends Serializable>)`,
   `removeHistogramValue(String, Serializable, int)` — with cardinality gating
8. Repeat for `ReferencedTypeEntityIndex`
9. Add `AttributeIndexType.HISTOGRAM` enum value (nested enum in
   `AttributeIndexStoragePart` in `evita_engine` — same module as the index classes).
10. Schema-time validation: histogram name vs attribute name collision
11. Update `toString()` in both index classes to include histogram index names,
    bucket counts, and cardinality index sizes for debugging visibility
12. Add `DEBUG`-level logging at key decision points for production debugging:
    - Condition evaluation result: `"Histogram '{}' condition for ref '{}' ownerPK={}: {}"`
    - Value resolution: `"Histogram '{}' value for ref '{}' ownerPK={}: raw={}, normalized={}"`
    - Cardinality transitions: `"Histogram '{}' cardinality {}→{} for value={}, ownerPK={}"`
    - Cross-entity trigger fire: `"Histogram re-evaluation triggered for ref '{}' by {}={} dep={}"`
    Use SLF4J with `log.isDebugEnabled()` guard to avoid argument formatting overhead.
13. Unit tests for index operations, cardinality gating, transactional lifecycle

### WBS-B03: Storage (evita_engine + evita_store_server)

1. Create new versioned Kryo serializers for `ReducedGroupEntityIndex` storage part
   (e.g., `ReducedGroupEntityIndexStoragePartSerializer_2025_X`) that write histogram
   FilterIndex and cardinality data. The new serializer reads empty histogram maps
   when deserializing old-format data (backward compatibility). The old serializer
   must remain registered for reading existing persisted data.
2. Create new versioned Kryo serializer for `ReferencedTypeEntityIndex` storage part
   similarly, following the existing `_2025_5` serializer naming pattern.
3. If the new fields change the storage part format, bump the storage format version.
4. Update `EntityCollection` storage part reconstruction to load histogram data
5. Integration tests for persist + reconstruct round-trip, including deserialization
   of old-format data (without histogram fields) into the new schema

### WBS-B04: Trigger Infrastructure (evita_engine)

1. Extract `AbstractExpressionIndexTriggerImpl` from `FacetExpressionTriggerImpl`:
   - Move all 13 fields, both constructors, all getter implementations,
     `evaluate()`, and `convertResult()` to abstract superclass
   - `FacetExpressionTriggerImpl extends AbstractExpressionIndexTriggerImpl` (empty body)
2. Extend `HistogramExpressionTrigger` with `getHistogramIndexName()`,
   `getValueDescriptor()`
3. Create `HistogramExpressionTriggerImpl extends AbstractExpressionIndexTriggerImpl`
   (cross-entity + local-only constructors, adds `histogramIndexName` + `valueResolution`)
4. Create `HistogramExpressionTriggerFactory.buildTriggersForReference()`
5. Unit tests for trigger factory: dual-dependency, same-entity dependency,
   condition-only, value-only

### WBS-B05: Registry Extension (evita_engine)

1. Add generic `<T> getTriggersForAttribute(..., Class<T>)` method (or instanceof-based
   filtering — implementor's choice per Section 4.4)
2. Add `getLocalHistogramTriggers()` method (returns map of name → single trigger)
3. Add `localHistogramTriggerIndex` data structure (4-deep map, Section 4.4)
4. Add `insertLocalHistogramTrigger()`, update `removeTriggersOwnedBy()` for histogram
   local triggers, add `deepCopyLocalHistogramIndex()`
5. Update `rebuildForEntityType()` to process histogram triggers (route by instanceof)
6. Update `buildFromSchemas()` to call `HistogramExpressionTriggerFactory` alongside
   `FacetExpressionTriggerFactory` (Section 4.5)
7. Update `Catalog.rebuildExpressionTriggerRegistryForEntityType()` to build triggers
   from both factories (Section 4.5)
8. Unit tests for registry lookup, rebuild, concurrent access

### WBS-B06: Local Trigger Integration (evita_engine)

1. Extend `ReferenceIndexMutator` reference insertion → histogram evaluation
2. Extend reference removal → unconditional histogram removal
3. Extend reference attribute change → histogram value update
4. Extend group change → remove from old group, add to new group
5. Rename `deferredFacetReEvaluations` → `deferredExpressionReEvaluations`,
   add histogram lambdas to shared queue
6. Add `readReferencedEntityAttribute()` to `EntityIndexLocalMutationExecutor` for
   cross-entity attribute reads during local trigger evaluation (Section 6.2)
7. Integration tests for all local trigger paths

### WBS-B07: Unified Cross-Entity Executor (evita_engine)

1. Replace `ReevaluateExpressionMutation` with `ReevaluateExpressionMutation`
   (same 4 fields, unified mutation for both facet and histogram; no backward
   compatibility needed — both are new implementation)
2. Replace `ReevaluateExpressionExecutor` with `ReevaluateExpressionExecutor`:
   - Preserve existing facet logic (steps 1–5 of Section 5.3)
   - Add histogram processing branch (steps 6–8 of Section 5.3)
   - Partition triggers by instanceof: facet vs histogram
   - Shared: affected PK resolution (step 2)
   - Per-type: condition evaluation (facet condition in step 3, histogram condition
     in step 4 — independent expressions)
   - Histogram-specific: bucket scan removal, FilterIndex JOIN addition,
     value type handling (Section 5.3 step 6d)
   - Session-optional query planning (reuse existing infrastructure)
3. Rename `AffectedFacetGroup` → `AffectedReferenceGroup`,
   `AffectedFacetEntry` → `AffectedReferenceEntry`, `facetPK` → `referencedEntityPK`
4. Extend `IndexMutationTarget` with `getSourceFilterIndex(String entityType,
   String attributeName)` for cross-collection access (Section 5.3 step 7a)
5. Register unified executor in `IndexMutationExecutorRegistry`
   (replaces old `ReevaluateExpressionMutation` mapping)
6. Add defensive null guards at all index/FilterIndex lookup points (Section 5.3 step 7)
7. Integration tests for all cross-entity cases including unconditional bucketing

### WBS-B08: Source-Side Detection (evita_engine)

1. Update `collectEntityAttributeTriggers` and `collectReferenceAttributeTriggers`
   to use renamed `ReevaluateExpressionMutation` (minimal change — mutation class
   rename only, loop logic unchanged since triggers coexist in same registry)
2. Deduplication: one mutation per (referenceName, scope) — already handled by
   existing deduplication logic
3. Entity removal: use untyped `getTriggersFor()` with `instanceof` filtering
4. Integration tests

### WBS-B09: Schema Change Handling (evita_engine)

1. Handle histogram definition added to existing data (bulk index rebuild)
2. Handle histogram definition removed (index cleanup)
3. Handle value expression changed (remove + rebuild)
4. Handle condition expression changed (re-evaluate all)
5. **Reverse filterability validation:** Implement check that prevents removing
   filterability from an attribute referenced by a histogram value expression.
   Enforce in the attribute schema mutation: scan all reference schemas across
   all entity types for matching `HistogramValueDescriptor.sourceAttributeName`.
   Reject mutation with clear error if any match.
6. Integration tests for schema mutations on populated data

### WBS-B10: WAL Serialization (evita_engine)

1. Add Kryo serializer for `ReevaluateExpressionMutation` (unified)
2. Register in WAL mutation serializer registry (no backward compatibility needed —
   both facet and histogram are new implementation)
3. Round-trip serialization tests

### WBS-B11: Comprehensive Testing (evita_test)

1. End-to-end integration tests for all data change scenarios (Section 8)
2. Fuzzy test: random mutations (reference add/remove, attribute changes,
   group changes, scope changes) → verify histogram FilterIndex consistency
   against brute-force computation after each batch
   - **Acceptance criteria:** After each batch of N random mutations (N=50-200),
     the histogram FilterIndex contents must exactly match the brute-force
     recomputation (iterate all entities, evaluate conditions, collect values).
     Zero mismatches across 1000+ batches.
3. Performance test: large fan-out scenario (10,000 Products, group attribute change)
   - **Acceptance criteria:** A single group attribute change affecting 10,000 Products
     completes within 500ms wall-clock time (measured on CI hardware). This threshold
     is based on the existing facet re-evaluation performance baseline.
4. Reflected reference tests: verify that reflected references require explicit histogram
   definitions and do not inherit from the declaring side

---

## 10. Execution Order

```
WBS-B01 (Value Resolution)        ← foundation: parse and validate expressions
    ↓
WBS-B02 (Index Structure)         ← data structures in indexes
    ↓
WBS-B03 (Storage)                 ← persistence
    ↓
WBS-B04 (Trigger Infrastructure)  ← trigger impl + factory
    ↓
WBS-B05 (Registry Extension)      ← trigger registration
    ↓
WBS-B06 (Local Triggers)          ← inline indexing during reference mutations
    ↓
WBS-B07 (Cross-Entity Executor)   ← re-evaluation when dependencies change
    ↓
WBS-B08 (Source-Side Detection)    ← trigger detection and dispatch
    ↓
WBS-B09 (Schema Changes)          ← add/remove/modify histogram on existing data
    ↓
WBS-B10 (WAL Serialization)       ← mutation persistence
    ↓
WBS-B11 (Testing)                 ← comprehensive test coverage
```

Parallelizable pairs: WBS-B01 + WBS-B02, WBS-B07 + WBS-B08.

**WBS-B03 ↔ WBS-B02 coupling:** The storage format (WBS-B03) depends on whether
histogram cardinality uses a separate `histogramCardinalities` map or reuses
`cardinalityIndexes` with `AttributeIndexType` discrimination (Section 2.1). This
decision is finalized in WBS-B02 and directly affects the serializer implementation
in WBS-B03. Ensure the WBS-B02 data structure decisions are reviewed before starting
WBS-B03.

Incremental tests should be added with each prior WBS.
