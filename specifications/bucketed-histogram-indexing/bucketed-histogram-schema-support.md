# Bucketed (Histogram) Schema Support — Implementation Plan

**Issue:** [#8 — Compute dynamic set of attribute histogram for references](https://github.com/FgForrest/evitaDB/issues/8)

**Scope:** This plan covers adding `bucketed` and `bucketedPartially` properties to the reference schema
layer: contracts, DTOs, builders, mutations, `ClassSchemaAnalyzer`, external APIs (gRPC, GraphQL, REST),
Kryo schema serializers, and WAL mutation serializers. It does **not** cover index maintenance,
expression evaluation at indexing time, query-time histogram computation, or the eventual GraphQL
`histogramStatistics` output format — those are follow-up assignments.

**Guiding principle:** Mirror the existing `faceted` / `facetedPartially` pattern as closely as possible.
The new `SetReferenceSchemaBucketedMutation` follows identical semantics to
`SetReferenceSchemaFacetedMutation`: it always carries the **entire state** of both bucketed scopes and
bucketed-partially expressions — no additive behavior, no Set+Set combining. For reflected references,
`null` means "inherited"; for non-reflected references, `null` means "don't change."

---

## Data Model — Key Design Decisions

### What `@Histogram` carries

The `@Histogram` annotation (`Reference.bucketed()` / `ScopeReferenceSettings.bucketed()`) contains
two fields:

| Field | Type | Semantics |
|---|---|---|
| `nameOfTheIndex` | `String` | Name of the histogram index (empty = derived from context) |
| `value` | `@Expression` | Expression computing the histogram bucket value for each referenced entity |

The `Reference.bucketedPartially()` / `ScopeReferenceSettings.bucketedPartially()` annotation
carries the **condition** expression — analogous to `facetedPartially` — deciding which entities
participate in the histogram.

### Per-scope bucketed state

A reference is "bucketed" in a scope if it has a histogram definition for that scope. Each scope can
independently carry:

1. **Histogram definition** — name + value expression (presence implies bucketed = true)
2. **Condition expression** — optional filter (analogous to facetedPartially)

### New records for mutation transport

```
ScopedHistogramIndexDefinition(
    scope: Scope,
    nameOfTheIndex: String,
    valueExpression: Expression   // nullable — null means no value expression
)

ScopedBucketedPartially(
    scope: Scope,
    expression: Expression        // nullable — null means clear condition
)
```

Both records follow the same patterns as `ScopedFacetedPartially`.

### DTO storage in `ReferenceSchema`

```
Map<Scope, HistogramIndexDefinition> bucketedInScopes
Map<Scope, Expression>                  bucketedPartiallyInScopes
```

Where `HistogramIndexDefinition` is an immutable record:

```
HistogramIndexDefinition(
    nameOfTheIndex: String,
    valueExpression: Expression   // nullable
)
```

The presence of a scope key in `bucketedInScopes` means the reference is bucketed in that scope.
The `bucketedPartiallyInScopes` map is independent — only scopes with explicit condition expressions
appear.

### Validation rule

Same as faceted: any scope marked as bucketed must also be indexed
(`ReferenceIndexType != NONE`). This is enforced in `validateScopeSettings()`.

---

## Layer 1: Schema Contracts & Editors

**Module:** `evita_api`

### 1.1 Create `HistogramIndexDefinition` record

**File:** `evita_api/.../schema/dto/HistogramIndexDefinition.java` (new)

```java
public record HistogramIndexDefinition(
    @Nonnull String nameOfTheIndex,
    @Nullable Expression valueExpression
) implements Serializable { }
```

### 1.2 Add getters to `ReferenceSchemaContract`

**File:** `evita_api/.../schema/ReferenceSchemaContract.java`

Following the three-tier pattern (any-scope → default-scope → specific-scope → all-scopes):

| Method | Return | Semantics |
|---|---|---|
| `isBucketedInAnyScope()` | `boolean` | Any scope bucketed? Default impl streams Scope.values() |
| `isBucketed()` | `boolean` | Bucketed in DEFAULT_SCOPE? Default delegates to `isBucketedInScope` |
| `isBucketedInScope(Scope)` | `boolean` | Abstract — check specific scope |
| `getBucketedInScopes()` | `Set<Scope>` | All scopes where bucketed |
| `getHistogramIndexDefinition(Scope)` | `@Nullable HistogramIndexDefinition` | Histogram config for scope |
| `getHistogramIndexDefinitions()` | `Map<Scope, HistogramIndexDefinition>` | All definitions |
| `getBucketedPartially()` | `@Nullable Expression` | Condition in DEFAULT_SCOPE. Default delegates |
| `getBucketedPartiallyInScope(Scope)` | `@Nullable Expression` | Abstract — condition for scope |
| `getBucketedPartiallyInScopes()` | `Map<Scope, Expression>` | All conditions. Default empty |

### 1.3 Add editor methods to `ReferenceSchemaEditor`

**File:** `evita_api/.../schema/ReferenceSchemaEditor.java`

| Method | Semantics |
|---|---|
| `bucketed(String nameOfTheIndex, Expression valueExpr)` | Default scope, delegates to `bucketedInScope` |
| `bucketedInScope(Scope, String nameOfTheIndex, Expression valueExpr)` | Enable bucketing in scope with histogram config |
| `nonBucketed()` | Disable in all scopes. Default delegates to `nonBucketed(Scope.values())` |
| `nonBucketed(Scope...)` | Disable in specific scopes |
| `bucketedPartially(Expression)` | Condition in DEFAULT_SCOPE. Delegates to `bucketedPartiallyInScope` |
| `bucketedPartiallyInScope(Scope, Expression)` | Condition for specific scope |
| `nonBucketedPartially()` | Clear condition in DEFAULT_SCOPE |
| `nonBucketedPartially(Scope...)` | Clear condition in specific scopes |

### 1.4 Reflected reference note

Histogram definitions are **not** inherited by reflected references (see
`conditional-bucket-indexing.md` Section 1.6 for rationale). No `isBucketedInherited()`
or `withBucketedInherited()` methods are needed — reflected references must configure
their own `bucketedInScope()` / `bucketedPartiallyInScope()` explicitly.

### Checklist

- [ ] `HistogramIndexDefinition` record created
- [ ] Three-tier getters added to `ReferenceSchemaContract`
- [ ] Editor fluent methods added to `ReferenceSchemaEditor`

---

## Layer 2: Schema DTOs

**Module:** `evita_api`

### 2.1 `ReferenceSchema.java`

**File:** `evita_api/.../schema/dto/ReferenceSchema.java`

Add fields (next to `facetedInScopes` / `facetedPartiallyInScopes`):

```java
@Getter protected final Map<Scope, HistogramIndexDefinition> bucketedInScopes;
@Getter @Nonnull protected final Map<Scope, Expression> bucketedPartiallyInScopes;
```

Add to constructor, wrap with `CollectionUtils.toUnmodifiableMap()`.

Implement contract getters:
- `isBucketedInScope(scope)` → `bucketedInScopes.containsKey(scope)`
- `getBucketedInScopes()` → `bucketedInScopes.keySet()` (wrapped as unmodifiable `EnumSet`)
- `getHistogramIndexDefinition(scope)` → `bucketedInScopes.get(scope)`
- `getBucketedPartiallyInScope(scope)` → `bucketedPartiallyInScopes.get(scope)`

Add static converter methods:
- `toBucketedHistogramMap(ScopedHistogramIndexDefinition[])` → `Map<Scope, HistogramIndexDefinition>`
- `toBucketedPartiallyMap(ScopedBucketedPartially[])` → `Map<Scope, Expression>` (reuse pattern from `toFacetedPartiallyMap`)

Update **all three** `_internalBuild()` overloads to accept bucketed parameters:
1. Simple overload (line ~377): pass defaults (`Collections.emptyMap()`)
2. Map-based overload (line ~416): accept `Map<Scope, HistogramIndexDefinition>` + `Map<Scope, Expression>`
3. Array-based overload (line ~460): accept `ScopedHistogramIndexDefinition[]` + `ScopedBucketedPartially[]`, convert to maps

Update `validateScopeSettings()` — bucketed scopes also require the reference to be indexed.

Update `equals()`, `hashCode()`, `toString()` to include both bucketed fields.

### 2.2 `ReflectedReferenceSchema.java`

**File:** `evita_api/.../schema/dto/ReflectedReferenceSchema.java`

No inheritance flag is needed — reflected references do not inherit bucketed definitions
(see `conditional-bucket-indexing.md` Section 1.6). Bucketed maps are always explicit.

Update all `_internalBuild()` overloads and `withBucketed(...)` / `withBucketedPartially(...)` factory methods.

### Checklist

- [ ] Fields added to `ReferenceSchema` with immutable wrapping
- [ ] Contract getters implemented
- [ ] Static converter methods (`toBucketedHistogramMap`, `toBucketedPartiallyMap`)
- [ ] All three `_internalBuild()` overloads updated
- [ ] `validateScopeSettings()` extended for bucketed scopes
- [ ] `equals()` / `hashCode()` / `toString()` updated
- [ ] `withBucketed()` / `withBucketedPartially()` factory methods on reflected DTO

---

## Layer 3: Builders

**Module:** `evita_api`

### 3.1 `AbstractReferenceSchemaBuilder.java`

**File:** `evita_api/.../schema/builder/AbstractReferenceSchemaBuilder.java`

Add shared bucketed methods (mirroring `facetedPartiallyInScope`, `nonFacetedPartially`,
`applyNonFacetedMutation`, `filterPartiallyToScopes`, `toScopedFacetedPartiallyArray`):

- `bucketedPartiallyInScope(Scope, Expression)` — merge current bucketed state + new partially
- `nonBucketedPartially(Scope...)` — remove condition expressions
- `applyNonBucketedMutation(Set<Scope>)` — shared tail logic emitting `SetReferenceSchemaBucketedMutation`
- `filterBucketedPartiallyToScopes(Map, Set)` — filter partially map to retained scopes
- `toScopedHistogramIndexDefinitionArray(Map<Scope, HistogramIndexDefinition>)` — static converter
- `toScopedBucketedPartiallyArray(Map<Scope, Expression>)` — static converter

### 3.2 `ReferenceSchemaBuilder.java`

**File:** `evita_api/.../schema/builder/ReferenceSchemaBuilder.java`

Implement editor methods:

- `bucketedInScope(Scope, String nameOfTheIndex, Expression valueExpr)` — compute complete state,
  auto-promote scope to indexed if needed, emit `SetReferenceSchemaBucketedMutation` (and optionally
  `SetReferenceSchemaIndexedMutation` if not already indexed)
- `nonBucketed(Scope...)` — filter bucketed scopes, emit mutation

In constructor: extract bucketed state from `baseSchema` for `CreateReferenceSchemaMutation`.

### 3.3 `ReflectedReferenceSchemaBuilder.java`

**File:** `evita_api/.../schema/builder/ReflectedReferenceSchemaBuilder.java`

Override bucketed methods with reflected-reference-aware logic (no inheritance — explicit only):

- `bucketedInScope(...)` — compute explicit state
- `bucketedPartiallyInScope(...)` — compute explicit state
- `nonBucketed(...)` — handle reflected reference availability
- `nonBucketedPartially(...)` — handle empty state

### Checklist

- [ ] `AbstractReferenceSchemaBuilder` shared methods added
- [ ] `ReferenceSchemaBuilder.bucketedInScope()` with auto-indexed promotion
- [ ] `ReferenceSchemaBuilder.nonBucketed()` implemented
- [ ] Constructor passes bucketed state to `CreateReferenceSchemaMutation`
- [ ] `ReflectedReferenceSchemaBuilder` overrides implemented

---

## Layer 4: Mutations

**Module:** `evita_api`

### 4.1 Create scoped wrapper records

**File:** `evita_api/.../schema/mutation/reference/ScopedHistogramIndexDefinition.java` (new)

```java
public record ScopedHistogramIndexDefinition(
    @Nonnull Scope scope,
    @Nonnull String nameOfTheIndex,
    @Nullable Expression valueExpression
) implements Serializable {
    public static final ScopedHistogramIndexDefinition[] EMPTY = new ScopedHistogramIndexDefinition[0];
    // Compact constructor validates scope + nameOfTheIndex not null
    // Override equals/hashCode (no arrays, but Expression needs check)
}
```

**File:** `evita_api/.../schema/mutation/reference/ScopedBucketedPartially.java` (new)

```java
public record ScopedBucketedPartially(
    @Nonnull Scope scope,
    @Nullable Expression expression
) implements Serializable {
    public static final ScopedBucketedPartially[] EMPTY = new ScopedBucketedPartially[0];
    // Compact constructor validates scope not null
}
```

### 4.2 Create `SetReferenceSchemaBucketedMutation`

**File:** `evita_api/.../schema/mutation/reference/SetReferenceSchemaBucketedMutation.java` (new)

Fields:
```java
@Getter @Nullable private final ScopedHistogramIndexDefinition[] bucketedInScopes;
@Getter @Nullable private final ScopedBucketedPartially[] bucketedPartiallyInScopes;
```

Constructor hierarchy (3 overloads, mirroring `SetReferenceSchemaFacetedMutation`):
1. Simple constructor: `(String name, @Nullable Boolean bucketed)` — for toggle on/off
2. Scoped histogram constructor: `(String name, @Nullable ScopedHistogramIndexDefinition[] bucketedInScopes)`
3. Full constructor (`@SerializableCreator`): both arrays

`mutate()` logic:
- **Reflected references:** Apply `withBucketed()` / `withBucketedPartially()` factory methods on
  `ReflectedReferenceSchema`, handling null (inherited) semantics exactly like faceted
- **Non-reflected references:** Build new `ReferenceSchema` via `_internalBuild()`, merging
  non-null fields over existing state. If `bucketedInScopes` changes but `bucketedPartiallyInScopes`
  is null, filter out condition expressions for scopes no longer bucketed

`combineWith()` logic:
- Same mutation name → later replaces existing (return `new MutationCombinationResult<>(null, this)`)
- With `CreateReferenceSchemaMutation` → absorb using ternary replacement semantics
- With `CreateReflectedReferenceSchemaMutation` → absorb using ternary replacement semantics

`toString()` — descriptive string including bucketed scope info.

### 4.3 Update `CreateReferenceSchemaMutation`

**File:** `evita_api/.../schema/mutation/reference/CreateReferenceSchemaMutation.java`

Add fields:
```java
@Getter @Nonnull private final ScopedHistogramIndexDefinition[] bucketedInScopes;
@Getter @Nonnull private final ScopedBucketedPartially[] bucketedPartiallyInScopes;
```

Update all constructor overloads (simple → scoped → full):
- Simple: default to `ScopedHistogramIndexDefinition.EMPTY` / `ScopedBucketedPartially.EMPTY`
- Full (`@SerializableCreator`): null-safe defaulting

Update `mutate()` — pass bucketed arrays to `ReferenceSchema._internalBuild()`.

Update `combineWith()` — when detecting changed bucketed state, create `SetReferenceSchemaBucketedMutation`
(same pattern as the existing `createCombinedFacetedMutation` for faceted).

### 4.4 Update `CreateReflectedReferenceSchemaMutation`

**File:** `evita_api/.../schema/mutation/reference/CreateReflectedReferenceSchemaMutation.java`

Add nullable fields:
```java
@Getter @Nullable private final ScopedHistogramIndexDefinition[] bucketedInScopes;
@Getter @Nullable private final ScopedBucketedPartially[] bucketedPartiallyInScopes;
```

Add helper `createCombinedBucketedMutation()` (mirroring `createCombinedFacetedMutation()`):
- Returns single `SetReferenceSchemaBucketedMutation` carrying both properties
- Handles null (inherited) semantics

Update all constructors, `mutate()`, `combineWith()`.

### Checklist

- [ ] `ScopedHistogramIndexDefinition` record created with `EMPTY` constant and validation
- [ ] `ScopedBucketedPartially` record created with `EMPTY` constant and validation
- [ ] `SetReferenceSchemaBucketedMutation` created with 3 constructor overloads
- [ ] `SetReferenceSchemaBucketedMutation.mutate()` — reflected + non-reflected branches
- [ ] `SetReferenceSchemaBucketedMutation.combineWith()` — replace + absorb into Create/CreateReflected
- [ ] `CreateReferenceSchemaMutation` updated — fields, constructors, `mutate()`, `combineWith()`
- [ ] `CreateReflectedReferenceSchemaMutation` updated — fields, `createCombinedBucketedMutation()`, constructors, `mutate()`, `combineWith()`
- [ ] `serialVersionUID` updated on all modified mutation classes

---

## Layer 4b: ClassSchemaAnalyzer

**Module:** `evita_api`
**File:** `evita_api/.../schema/ClassSchemaAnalyzer.java`

### In `defineReference()` (non-reflected references)

After the existing faceted processing block, add bucketed processing. Two cases:

**Default scope (no `ScopeReferenceSettings`):**
```
Read reference.bucketed().nameOfTheIndex() and reference.bucketed().value().value()
If either is non-empty → call editor.bucketed(nameOfTheIndex, valueExpression)
Read reference.bucketedPartially().value()
If non-empty → call editor.bucketedPartially(ExpressionFactory.parse(expr))
```

**Per-scope (`ScopeReferenceSettings[]` defined):**
```
For each ScopeReferenceSettings:
  Read scopeSettings.bucketed().nameOfTheIndex() and scopeSettings.bucketed().value().value()
  If either is non-empty → call editor.bucketedInScope(scope, nameOfTheIndex, valueExpression)
  Read scopeSettings.bucketedPartially().value()
  If non-empty → call editor.bucketedPartiallyInScope(scope, ExpressionFactory.parse(expr))
```

Add assertion: when `scope` array is defined, `reference.bucketed()` at the top level must be empty
(same pattern as the assertion for `reference.faceted()`).

### In `defineReflectedReference()` (reflected references)

The `@ReflectedReference` annotation does **not** have `bucketed` / `bucketedPartially` attributes
(these are only on `@Reference`). Reflected references inherit bucketed settings from the target
reference by default. No `ClassSchemaAnalyzer` changes needed for reflected references — the
inheritance resolution happens in the `ReflectedReferenceSchema` DTO constructor.

**Note:** If `@ReflectedReference` is later extended with bucketed override attributes (like
`InheritableBoolean` for faceted), the analyzer would need updating. For now, reflected references
always inherit bucketed settings.

### Checklist

- [ ] `defineReference()` extended — default scope bucketed processing
- [ ] `defineReference()` extended — per-scope bucketed processing
- [ ] Assertion: top-level `bucketed()` must be empty when `scope[]` is defined
- [ ] No changes needed for `defineReflectedReference()` (inherits automatically)

---

## Layer 5: External API Core — Descriptors & Converters

**Module:** `evita_external_api_core`

### 5.1 Create descriptors

**File:** `...schemaApi/model/ScopedHistogramIndexDefinitionDescriptor.java` (new)

Following `ScopedFacetedPartiallyDescriptor` pattern — extends `ScopedDataDescriptor`:
- `NAME_OF_THE_INDEX` property (non-null String)
- `VALUE_EXPRESSION` property (nullable String)
- `THIS` and `THIS_INPUT` ObjectDescriptors

**File:** `...schemaApi/model/ScopedBucketedPartiallyDescriptor.java` (new)

Following `ScopedFacetedPartiallyDescriptor` pattern:
- `EXPRESSION` property (nullable String)
- `THIS` and `THIS_INPUT` ObjectDescriptors

### 5.2 Update `ReferenceSchemaDescriptor.java`

Add two new `PropertyDescriptor` entries:
- `BUCKETED` — `nonNullListRef(ScopedHistogramIndexDefinitionDescriptor.THIS)`
- `BUCKETED_PARTIALLY` — `nonNullListRef(ScopedBucketedPartiallyDescriptor.THIS)`

Add them to `THIS_SPECIFIC` and `THIS_GENERIC` static property lists.

### 5.3 Create mutation descriptor

**File:** `...mutation/reference/SetReferenceSchemaBucketedMutationDescriptor.java` (new)

Properties:
- `BUCKETED_IN_SCOPES` — `nullableListRef(ScopedHistogramIndexDefinitionDescriptor.THIS)`
- `BUCKETED_PARTIALLY_IN_SCOPES` — `nullableListRef(ScopedBucketedPartiallyDescriptor.THIS)`
- Input variants using `THIS_INPUT`

### 5.4 Update existing mutation descriptors

- `CreateReferenceSchemaMutationDescriptor.java` — add bucketed fields
- `CreateReflectedReferenceSchemaMutationDescriptor.java` — add nullable bucketed fields

### 5.5 Create mutation converter

**File:** `...mutation/reference/SetReferenceSchemaBucketedMutationConverter.java` (new)

Extend `ReferenceSchemaMutationConverter<SetReferenceSchemaBucketedMutation>`.

- `convertFromInput()` — parse bucketed histogram entries using `PropertyObjectListMapper`,
  parse bucketed-partially entries similarly
- `convertToOutput()` — serialize histogram definitions and expressions to string form.
  Must override because `Expression` is not natively supported by `Output.toSerializableValue()`

Add shared helper methods in `ReferenceSchemaMutationConverter` base class:
- `serializeBucketedHistogram()` — convert `ScopedHistogramIndexDefinition[]` to List of Maps
- `parseBucketedHistogram()` — convert input objects to `ScopedHistogramIndexDefinition[]`
- `serializeBucketedPartially()` — convert `ScopedBucketedPartially[]` to List of Maps
- `parseBucketedPartially()` — convert input objects to `ScopedBucketedPartially[]`

### 5.6 Update existing mutation converters

- `CreateReferenceSchemaMutationConverter` — add bucketed fields parsing/serialization
- `CreateReflectedReferenceSchemaMutationConverter` — add nullable bucketed fields

### Checklist

- [ ] `ScopedHistogramIndexDefinitionDescriptor` created with `THIS` and `THIS_INPUT`
- [ ] `ScopedBucketedPartiallyDescriptor` created with `THIS` and `THIS_INPUT`
- [ ] `ReferenceSchemaDescriptor` updated with BUCKETED and BUCKETED_PARTIALLY properties
- [ ] `SetReferenceSchemaBucketedMutationDescriptor` created
- [ ] `CreateReferenceSchemaMutationDescriptor` updated
- [ ] `CreateReflectedReferenceSchemaMutationDescriptor` updated
- [ ] `SetReferenceSchemaBucketedMutationConverter` created with output override
- [ ] Existing mutation converters updated
- [ ] Typed `getOptionalProperty(name, Class)` overload used for array properties

---

## Layer 6a: gRPC

**Module:** `evita_external_api_grpc`

### Proto definitions

**`GrpcEvitaDataTypes.proto`:**
```protobuf
message GrpcScopedHistogramIndexDefinition {
    GrpcEntityScope scope = 1;
    string nameOfTheIndex = 2;
    google.protobuf.StringValue valueExpression = 3;
}

message GrpcScopedBucketedPartially {
    GrpcEntityScope scope = 1;
    google.protobuf.StringValue expression = 2;
}
```

**`GrpcReferenceSchemaMutations.proto`:**
- Add `GrpcSetReferenceSchemaBucketedMutation` message
- Update `GrpcCreateReferenceSchemaMutation` — add bucketed fields
- Update `GrpcCreateReflectedReferenceSchemaMutation` — add bucketed fields

**`GrpcEntitySchema.proto`:**
- Update `GrpcReferenceSchema` — add bucketed and bucketedPartially fields

### Converters

**`SetReferenceSchemaBucketedMutationConverter.java`** (new) — bidirectional conversion

**`EntitySchemaConverter.java`** — add:
- `parseBucketedHistogram(List<GrpcScopedHistogramIndexDefinition>)` helper
- `parseBucketedPartially(List<GrpcScopedBucketedPartially>)` helper
- Update `convert(GrpcReferenceSchema)` and reverse direction

Update existing converters for `CreateReferenceSchemaMutation` and `CreateReflectedReferenceSchemaMutation`.

### Checklist

- [ ] Proto messages added with correct field numbers
- [ ] Java stubs regenerated (build `evita_external_api_grpc`)
- [ ] `SetReferenceSchemaBucketedMutationConverter` created
- [ ] `EntitySchemaConverter` helpers added
- [ ] Existing mutation converters updated
- [ ] Empty/default handling is backward compatible

---

## Layer 6b: GraphQL

**Module:** `evita_external_api_graphql`

### Type registration

**`CommonEvitaSchemaSchemaBuilder.java`:**
- Register `ScopedHistogramIndexDefinitionDescriptor.THIS` and `THIS_INPUT`
- Register `ScopedBucketedPartiallyDescriptor.THIS` and `THIS_INPUT`

**`SystemGraphQLSchemaBuilder.java`:**
- Register same types (separate type registry!)
- Register `SetReferenceSchemaBucketedMutationDescriptor`

### Data fetchers

**`ReferenceSchemasBucketedDataFetcher.java`** (new) — returns `List<Map<String, Object>>` for
bucketed histogram definitions per scope. Converts `Map<Scope, HistogramIndexDefinition>` to
list of maps with "scope", "nameOfTheIndex", "valueExpression" keys.

**`ReferenceSchemasBucketedPartiallyDataFetcher.java`** (new) — returns `List<Map<String, Object>>`
for condition expressions. Same pattern as `ReferenceSchemaFacetedPartiallyDataFetcher`.

Register both data fetchers in `EntitySchemaSchemaBuilder`.

### Checklist

- [ ] Types registered in `CommonEvitaSchemaSchemaBuilder` (Catalog API)
- [ ] Types registered in `SystemGraphQLSchemaBuilder` (System API)
- [ ] `ReferenceSchemasBucketedDataFetcher` created and registered
- [ ] `ReferenceSchemasBucketedPartiallyDataFetcher` created and registered

---

## Layer 6c: REST

**Module:** `evita_external_api_rest`

### Type registration

**`EntitySchemaObjectBuilder.java`:**
- Register `ScopedHistogramIndexDefinitionDescriptor` and `ScopedBucketedPartiallyDescriptor` types

### JSON serialization

**`SchemaJsonSerializer.java`:**
- Add `serializeBucketedHistogram(ReferenceSchemaContract)` — converts
  `getHistogramIndexDefinitions()` map to JSON array of objects with "scope",
  "nameOfTheIndex", "valueExpression" fields
- Add `serializeBucketedPartially(ReferenceSchemaContract)` — converts
  `getBucketedPartiallyInScopes()` map to JSON array (same pattern as `serializeFacetedPartially()`)

**`EntitySchemaJsonSerializer.java`:**
- Call both new serializer methods when writing reference schemas

### REST test DTO helpers

**`CatalogRestSchemaEndpointFunctionalTest.java`:**
- Update `createReferenceSchemaDto()` to include bucketed fields
- Add `createBucketedHistogramDto()` and `createBucketedPartiallyDto()` helpers

### Checklist

- [ ] Types registered in `EntitySchemaObjectBuilder`
- [ ] `serializeBucketedHistogram()` added to `SchemaJsonSerializer`
- [ ] `serializeBucketedPartially()` added to `SchemaJsonSerializer`
- [ ] REST test DTO helpers updated

---

## Layer 7: Kryo Schema Serializers

**Module:** `evita_store_server`

### 7.1 Register new types

**`SchemaKryoConfigurer.java`:**
- Register `HistogramIndexDefinition.class` (after existing registrations, before assertion)

### 7.2 Backward compatibility decision tree

Since `ReferenceSchema` and `ReflectedReferenceSchema` already have `_2026_1` backward-compat
serializers (from the facetedPartially addition), and we're still in the same dev cycle:

- **If `serialVersionUID` hasn't changed since the existing `_2026_1` serializer was created:**
  → Update existing `_2026_1` backward-compat to also NOT read the bucketed fields.
  → Update current serializer to write/read bucketed fields.
- **If `serialVersionUID` has already changed again (a second change in this cycle):**
  → Same: just update the current serializer. No new backward-compat file.

The key rule: we only maintain backward compatibility with the latest release, and `_2026_1`
already handles that.

### 7.3 Update `ReferenceSchemaSerializer`

Add after faceted serialization:
```java
// Write:
writeBucketedHistogramMap(kryo, output, referenceSchema.getHistogramIndexDefinitions());
writeFacetedPartiallyMap(kryo, output, referenceSchema.getBucketedPartiallyInScopes());
// (reuse writeFacetedPartiallyMap for bucketed-partially — same Map<Scope, Expression> shape)

// Read:
final Map<Scope, HistogramIndexDefinition> bucketedInScopes = readBucketedHistogramMap(kryo, input);
final Map<Scope, Expression> bucketedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);
```

Add new helper methods to `EntitySchemaSerializer`:
- `writeBucketedHistogramMap(Kryo, Output, Map<Scope, HistogramIndexDefinition>)`
- `readBucketedHistogramMap(Kryo, Input)` → `Map<Scope, HistogramIndexDefinition>`

### 7.4 Update `ReflectedReferenceSchemaSerializer`

Add after faceted serialization — no inheritance flag needed, always write/read directly:
```java
// Write:
writeBucketedHistogramMap(kryo, output, referenceSchema.getHistogramIndexDefinitions());
writeFacetedPartiallyMap(kryo, output, referenceSchema.getBucketedPartiallyInScopes());

// Read:
final Map<Scope, HistogramIndexDefinition> bucketedInScopes = readBucketedHistogramMap(kryo, input);
final Map<Scope, Expression> bucketedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);
```

### 7.5 Update backward-compat serializers

Update `ReferenceSchemaSerializer_2026_1.read()` and `ReflectedReferenceSchemaSerializer_2026_1.read()`
to pass `Collections.emptyMap()` / `null` for the new bucketed parameters when calling `_internalBuild()`.

### Checklist

- [ ] `HistogramIndexDefinition` registered in `SchemaKryoConfigurer`
- [ ] Current `ReferenceSchemaSerializer` updated with bucketed read/write
- [ ] Current `ReflectedReferenceSchemaSerializer` updated with bucketed read/write + inheritance
- [ ] `EntitySchemaSerializer` helper methods added (`writeBucketedHistogramMap`, `readBucketedHistogramMap`)
- [ ] Backward-compat `_2026_1` serializers updated to pass defaults for bucketed fields
- [ ] Tests run to verify serial version hash chain

---

## Layer 8: WAL Mutation Serializers

**Module:** `evita_store_server`

### 8.1 Create `SetReferenceSchemaBucketedMutationSerializer`

**File:** `...wal/schema/reference/SetReferenceSchemaBucketedMutationSerializer.java` (new)

Follow `SetReferenceSchemaFacetedMutationSerializer` pattern:

```java
// Write:
writeScopedHistogramIndexDefinitionArray(kryo, output, mutation.getBucketedInScopes());
writeScopedBucketedPartiallyArray(kryo, output, mutation.getBucketedPartiallyInScopes());

// Read:
final ScopedHistogramIndexDefinition[] bucketedInScopes = readScopedHistogramIndexDefinitionArray(kryo, input);
final ScopedBucketedPartially[] bucketedPartiallyInScopes = readScopedBucketedPartiallyArray(kryo, input);
```

Add helper methods (in the serializer or in `MutationSerializationFunctions`):
- `writeScopedHistogramIndexDefinitionArray()` — nullable array with presence flag; for each entry: Scope +
  String (nameOfTheIndex) + nullable Expression (valueExpression)
- `readScopedHistogramIndexDefinitionArray()` — mirror
- `writeScopedBucketedPartiallyArray()` — same pattern as `writeScopedFacetedPartiallyArray`
- `readScopedBucketedPartiallyArray()` — mirror

### 8.2 Register in `WalKryoConfigurer`

Register `SetReferenceSchemaBucketedMutation.class` with `SerialVersionBasedSerializer` wrapping
the new serializer. No backward-compat needed for a brand-new mutation class.

Also register `ScopedHistogramIndexDefinition.class` and `ScopedBucketedPartially.class` if Kryo needs them
directly (check if the WAL serializer handles them inline or via Kryo registration).

### 8.3 Update existing WAL serializers

**`CreateReferenceSchemaMutationSerializer.java`:**
- Add bucketed fields after faceted fields:
  ```java
  writeScopedHistogramIndexDefinitionArray(kryo, output, mutation.getBucketedInScopes());
  writeScopedBucketedPartiallyArray(kryo, output, mutation.getBucketedPartiallyInScopes());
  ```
- Update read accordingly

**`CreateReflectedReferenceSchemaMutationSerializer.java`:**
- Add nullable bucketed fields (same pattern as faceted with presence flags)

### 8.4 Backward-compat WAL serializers

Update existing `_2026_1` backward-compat serializers for `CreateReferenceSchemaMutation` and
`CreateReflectedReferenceSchemaMutation` to pass `ScopedHistogramIndexDefinition.EMPTY` /
`ScopedBucketedPartially.EMPTY` / `null` for the new bucketed fields.

### Checklist

- [ ] `SetReferenceSchemaBucketedMutationSerializer` created
- [ ] Helper serialization methods added
- [ ] Registered in `WalKryoConfigurer`
- [ ] `CreateReferenceSchemaMutationSerializer` updated
- [ ] `CreateReflectedReferenceSchemaMutationSerializer` updated
- [ ] Backward-compat `_2026_1` serializers updated for bucketed defaults

---

## Testing

### Test Categories

| Category | File / Location | What to Test |
|---|---|---|
| DTO construction | `schema/dto/ReferenceSchemaTest` | Build with bucketed fields, getter behavior, equality |
| Builder | `schema/builder/ReferenceSchemaBuilderTest` | Fluent API produces correct mutations, auto-indexed promotion |
| Set mutation | `schema/mutation/reference/SetReferenceSchemaBucketedMutationTest` (new) | Apply to non-reflected, apply to reflected (inherited + explicit), `combineWith()` all branches |
| Create mutation | `schema/mutation/reference/CreateReferenceSchemaMutationTest` | Updated with bucketed fields, `combineWith()` generates bucketed diff |
| Create reflected mutation | `schema/mutation/reference/CreateReflectedReferenceSchemaMutationTest` | Bucketed inheritance in `combineWith()` |
| Scoped records | `schema/mutation/reference/ScopedHistogramIndexDefinitionTest` (new) | Validation, equality |
| ClassSchemaAnalyzer | `schema/ClassSchemaAnalyzerTest` | Annotation-driven bucketed + bucketedPartially processing, default scope and per-scope variants |
| Core converter | `schemaApi/resolver/mutation/reference/SetReferenceSchemaBucketedMutationConverterTest` (new) | Round-trip input → mutation → output |
| gRPC converter | `grpc/.../SetReferenceSchemaBucketedMutationConverterTest` (new) | gRPC ↔ domain round-trip |
| gRPC assertions | `grpc/testUtils/GrpcAssertions.java` | Update assertion helpers for bucketed fields |
| GraphQL | `graphql/.../schemaApi/...` | Query returns bucketed fields correctly |
| REST | `rest/.../schemaApi/...` | JSON output includes bucketed fields |
| REST DTO helpers | `CatalogRestSchemaEndpointFunctionalTest` | `createReferenceSchemaDto()` updated |

### Key test scenarios

1. **Basic bucketed definition** — create reference with `bucketed("idx", expr)`, verify schema
2. **Per-scope bucketed** — LIVE scope has histogram, ARCHIVED does not
3. **Bucketed + condition** — bucketed with `bucketedPartially` condition expression
4. **Auto-indexed promotion** — calling `bucketedInScope()` on non-indexed scope auto-indexes
5. **Reflected explicit** — reflected reference explicitly sets bucketed settings (no inheritance)
6. **Mutation combine** — `SetReferenceSchemaBucketedMutation` absorbs into `CreateReferenceSchemaMutation`
7. **Mutation idempotency** — applying same mutation twice produces no change
8. **Validation** — bucketed scope without indexed scope throws

### Checklist

- [ ] DTO construction and getters tested
- [ ] Builder fluent API tested
- [ ] `SetReferenceSchemaBucketedMutation` — all `mutate()` and `combineWith()` branches
- [ ] `CreateReferenceSchemaMutation` — bucketed fields in `mutate()` and `combineWith()`
- [ ] `CreateReflectedReferenceSchemaMutation` — bucketed inheritance
- [ ] `ClassSchemaAnalyzer` — annotation processing for both default and per-scope
- [ ] Core converter round-trip
- [ ] gRPC converter round-trip
- [ ] GraphQL query functional test
- [ ] REST endpoint functional test
- [ ] REST DTO helpers updated

---

## Parallelization Guidance

```
Layers 1 → 2 → 3 → 4 + 4b  (strictly sequential)
                      ↓
           ┌──────────┼──────────┐
           ↓          ↓          ↓
       Layer 5     Layer 7    Layer 8
           ↓       (Kryo)     (WAL)
      ┌────┼────┐
      ↓    ↓    ↓
     6a   6b   6c
    gRPC  GQL  REST
```

After Layer 4 + 4b complete, groups A (5 → 6a/6b/6c), B (7), and C (8) can run concurrently.
Testing spans all layers and should run after all groups complete.

---

## Files Created (New)

| File | Module |
|---|---|
| `HistogramIndexDefinition.java` | `evita_api` |
| `ScopedHistogramIndexDefinition.java` | `evita_api` |
| `ScopedBucketedPartially.java` | `evita_api` |
| `SetReferenceSchemaBucketedMutation.java` | `evita_api` |
| `ScopedHistogramIndexDefinitionDescriptor.java` | `evita_external_api_core` |
| `ScopedBucketedPartiallyDescriptor.java` | `evita_external_api_core` |
| `SetReferenceSchemaBucketedMutationDescriptor.java` | `evita_external_api_core` |
| `SetReferenceSchemaBucketedMutationConverter.java` (core) | `evita_external_api_core` |
| `SetReferenceSchemaBucketedMutationConverter.java` (gRPC) | `evita_external_api_grpc` |
| `ReferenceSchemasBucketedDataFetcher.java` | `evita_external_api_graphql` |
| `ReferenceSchemasBucketedPartiallyDataFetcher.java` | `evita_external_api_graphql` |
| `SetReferenceSchemaBucketedMutationSerializer.java` | `evita_store_server` |

## Files Modified (Existing)

| File | Module | Change |
|---|---|---|
| `ReferenceSchemaContract.java` | `evita_api` | Add bucketed getters |
| `ReferenceSchemaEditor.java` | `evita_api` | Add bucketed editor methods |
| `ReferenceSchema.java` | `evita_api` | Add fields, constructors, `_internalBuild()`, validation, equals/hashCode |
| `ReflectedReferenceSchema.java` | `evita_api` | Add explicit bucketed support, factory methods (no inheritance) |
| `AbstractReferenceSchemaBuilder.java` | `evita_api` | Add shared bucketed methods |
| `ReferenceSchemaBuilder.java` | `evita_api` | Add `bucketedInScope()`, `nonBucketed()` |
| `ReflectedReferenceSchemaBuilder.java` | `evita_api` | Add inheritance-aware overrides |
| `CreateReferenceSchemaMutation.java` | `evita_api` | Add bucketed fields |
| `CreateReflectedReferenceSchemaMutation.java` | `evita_api` | Add bucketed fields |
| `ClassSchemaAnalyzer.java` | `evita_api` | Process `bucketed` / `bucketedPartially` annotations |
| `ReferenceSchemaDescriptor.java` | `evita_external_api_core` | Add BUCKETED property descriptors |
| `CreateReferenceSchemaMutationDescriptor.java` | `evita_external_api_core` | Add bucketed properties |
| `CreateReflectedReferenceSchemaMutationDescriptor.java` | `evita_external_api_core` | Add bucketed properties |
| `CreateReferenceSchemaMutationConverter.java` | `evita_external_api_core` | Parse/serialize bucketed |
| `CreateReflectedReferenceSchemaMutationConverter.java` | `evita_external_api_core` | Parse/serialize bucketed |
| `ReferenceSchemaMutationConverter.java` | `evita_external_api_core` | Add shared bucketed helpers |
| `GrpcEvitaDataTypes.proto` | `evita_external_api_grpc` | Add bucketed messages |
| `GrpcReferenceSchemaMutations.proto` | `evita_external_api_grpc` | Add bucketed mutation + update creates |
| `GrpcEntitySchema.proto` | `evita_external_api_grpc` | Add bucketed fields to reference schema |
| `EntitySchemaConverter.java` | `evita_external_api_grpc` | Add bucketed parsing helpers |
| `CommonEvitaSchemaSchemaBuilder.java` | `evita_external_api_graphql` | Register bucketed types |
| `SystemGraphQLSchemaBuilder.java` | `evita_external_api_graphql` | Register bucketed types |
| `EntitySchemaSchemaBuilder.java` | `evita_external_api_graphql` | Register data fetchers |
| `EntitySchemaObjectBuilder.java` | `evita_external_api_rest` | Register bucketed types |
| `SchemaJsonSerializer.java` | `evita_external_api_rest` | Add bucketed serialization |
| `EntitySchemaJsonSerializer.java` | `evita_external_api_rest` | Call bucketed serializers |
| `SchemaKryoConfigurer.java` | `evita_store_server` | Register `HistogramIndexDefinition` |
| `ReferenceSchemaSerializer.java` | `evita_store_server` | Add bucketed read/write |
| `ReflectedReferenceSchemaSerializer.java` | `evita_store_server` | Add bucketed read/write + inheritance |
| `ReferenceSchemaSerializer_2026_1.java` | `evita_store_server` | Pass defaults for bucketed |
| `ReflectedReferenceSchemaSerializer_2026_1.java` | `evita_store_server` | Pass defaults for bucketed |
| `EntitySchemaSerializer.java` | `evita_store_server` | Add bucketed helper methods |
| `WalKryoConfigurer.java` | `evita_store_server` | Register new mutation serializer |
| `CreateReferenceSchemaMutationSerializer.java` | `evita_store_server` | Add bucketed fields |
| `CreateReflectedReferenceSchemaMutationSerializer.java` | `evita_store_server` | Add bucketed fields |
| `CreateReferenceSchemaMutationSerializer_2026_1.java` | `evita_store_server` | Pass defaults for bucketed |
| `CreateReflectedReferenceSchemaMutationSerializer_2026_1.java` | `evita_store_server` | Pass defaults for bucketed |
| `CatalogRestSchemaEndpointFunctionalTest.java` | `evita_test` | Update DTO helpers |
| `GrpcAssertions.java` | `evita_test` | Update assertion helpers |
