# WBS-02: Proxycian Proxy Infrastructure — Expression to Proxy Chain with Storage-Backed State

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Build a lightweight Proxycian-based proxy infrastructure that generates `EntityContract` and `ReferenceContract` proxy classes at schema load time, composed from reusable partials selected by expression path analysis, and instantiates them cheaply at trigger time backed by raw storage part data — avoiding the full entity assembly pipeline and its associated allocations.

## Scope

### In Scope

- Composable partial implementations for `EntityContract` and `ReferenceContract`
- Path-to-partial mapping from `AccessedDataFinder` output
- Schema load time proxy class generation and state recipe construction
- Trigger time proxy instantiation and wiring (including nested entity proxies)
- `CatchAllPartial` safety net for `@Delegate`-based DTO delegation
- `ExpressionEvaluationContext` creation via `FacetExpressionTrigger.createEvaluationContext()`
- Caching of storage parts via `DataStoreMemoryBuffer`
- Integration with existing `ObjectPropertyAccessor` / `ObjectElementAccessor` SPI registrations

### Out of Scope

- Cross-entity trigger detection, registry, and dispatch (WBS for Domain 2)
- `ExpressionToQueryTranslator` and `FilterBy`-based cross-entity evaluation mode
- `HistogramExpressionTrigger` subtype (future follow-up)
- Schema evolution / full re-index when expression changes on existing data
- New accessor registrations (none needed; all already exist)
- `PricesPartial` implementation (marked future in the analysis)

## Dependencies

### Depends On

- **`AccessedDataFinder`** — must produce `List<List<PathItem>>` from an expression. Already exists in the expression evaluator module.
- **Proxycian / ByteBuddy** — runtime proxy class generation. Already a project dependency.
- **`WritableEntityStorageContainerAccessor`** — interface for fetching storage parts during mutation processing. Already exists.
- **`DataStoreMemoryBuffer`** — read-through cache layer for storage parts. Already exists.
- **Storage part classes** — `EntityBodyStoragePart`, `AttributesStoragePart`, `AssociatedDataStoragePart`, `ReferencesStoragePart`, `PricesStoragePart`. Already exist.
- **Expression evaluator SPI accessors** — `EntityContractAccessor`, `ReferenceContractAccessor`, `AttributesContractAccessor`, `ReferencesContractAccessor`, `AssociatedDataContractAccessor`. Already registered via ServiceLoader.

### Depended On By

- **Domain 2 trigger infrastructure** — `FacetExpressionTrigger.evaluate()` (local mode) uses the proxy infrastructure to create evaluation contexts for per-entity expression evaluation.
- **`ReevaluateFacetExpressionExecutor`** — calls `createEvaluationContext()` to obtain bound `$entity` / `$reference` variables for expression evaluation.
- **Future `HistogramExpressionTrigger`** — will reuse the same partial/proxy infrastructure with histogram-specific state recipes.

## Technical Context

### What data does the expression need?

The expression can reference any property accessible through the expression language's variable bindings (`$entity`, `$reference`) and accessor chain. `EntityContract` extends `AttributesContract`, `AssociatedDataContract`, `PricesContract`, `ReferencesContract`, `WithEntitySchema`, and `EntityClassifierWithParent` — so expressions can potentially access all of these.

#### Full data path table

| Path Pattern | Data Source | Storage Part(s) Needed |
|---|---|---|
| `$entity.primaryKey` | Owner entity identity | `EntityBodyStoragePart` |
| `$entity.attributes['x']` | Owner entity attribute | `AttributesStoragePart` (global) |
| `$entity.localizedAttributes['x']` | Owner entity localized attribute | `AttributesStoragePart` (per-locale) |
| `$entity.associatedData['x']` | Owner entity associated data | `AssociatedDataStoragePart` (global) |
| `$entity.localizedAssociatedData['x']` | Owner entity localized assoc. data | `AssociatedDataStoragePart` (per-locale) |
| `$entity.references['r']` | Owner entity references | `ReferencesStoragePart` |
| `$entity.parent` | Owner entity parent | `EntityBodyStoragePart` (parent PK) |
| `$reference.referencedPrimaryKey` | Reference key | Directly available from mutation context |
| `$reference.attributes['x']` | Reference attribute | `ReferencesStoragePart` (reference entry) |
| `$reference.localizedAttributes['x']` | Reference localized attribute | `ReferencesStoragePart` (reference entry) |
| `$reference.referencedEntity.*` | Referenced entity's properties | Target entity's storage parts (cross-collection) |
| `$reference.groupEntity?.*` | Group entity's properties | Group entity's storage parts (cross-collection) |

Nested paths on referenced/group entity follow the same pattern as `$entity.*` — the expression can access any property supported by `EntityContract` on the nested entity.

### Accessor registrations — already exist

The expression evaluator uses `ObjectPropertyAccessor` and `ObjectElementAccessor` SPI implementations, registered via ServiceLoader in `META-INF/services`. All needed registrations already exist:

| Accessor | Type | Handles | Contract methods called |
|---|---|---|---|
| `EntityContractAccessor` | Property | `EntityContract` | `getPrimaryKey()`; wraps entity into DTOs for attributes, associated data, references |
| `ReferenceContractAccessor` | Property | `ReferenceContract` | `getReferencedPrimaryKey()`, `getReferencedEntity()`, `getGroupEntity()`; wraps into DTOs for attributes |
| `AttributesContractAccessor` | Element | `AttributesContract` | `getAttributeSchema(String)`, `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeLocales()` |
| `ReferencesContractAccessor` | Element | `ReferencesContract` | `getSchema()`, `getReferences(String)` |
| `AssociatedDataContractAccessor` | Element | `AssociatedDataContract` | `getAssociatedDataSchema(String)`, `getAssociatedData(String)`, `getAssociatedData(String, Locale)`, `getAssociatedDataLocales()` |

No new accessor registrations are needed. Accessors work at the contract interface level — any implementing class (including Proxycian proxies) works automatically via `ObjectAccessorRegistry`'s type hierarchy traversal.

### DTO delegation pattern

Each accessor wraps the contract into a scoped evaluation DTO (e.g., `EntityAttributesEvaluationDto`) that uses `@Delegate EntityContract`. This means the DTO delegates ALL `EntityContract` methods to the underlying object. The proxy must implement every method the DTO delegation might reach — not just the methods the accessor calls directly. Unused methods must throw a controlled exception (see CatchAllPartial below).

### Why Proxycian proxies over full entity construction

Constructing full `Entity`/`Reference` objects from storage parts requires building heavyweight wrapper objects — `EntityAttributes` (builds `LinkedHashMap` from `AttributeValue[]`), `References` (builds `HashMap` + duplicate detection), `AssociatedData`, `Prices` — even when the expression only needs a single attribute value. For a fan-out of 1000 entities, this means ~5000 intermediate allocations.

Additionally, the base `Reference` class returns `Optional.empty()` for both `getReferencedEntity()` and `getGroupEntity()` (`Reference.java:303-304`, `327-328`). Only `ReferenceDecorator` populates these, but constructing a decorator requires the full entity assembly pipeline.

Instead, lightweight Proxycian proxy classes (using ByteBuddy) implement only the contract methods the specific expression actually calls. Each method classification delegates to the raw storage part data without intermediate wrapper objects.

**Advantages:**

- **Avoids circular dependency** — uses `WritableEntityStorageContainerAccessor` to fetch storage parts, not the query engine
- **Minimal allocations** — ~2 objects per entity (proxy instance + state record) instead of ~5-6 (Entity + EntityAttributes + LinkedHashMap + References + AssociatedData + Prices)
- **Fail-fast safety** — `@Delegate` in DTOs delegates ALL contract methods to the proxy. Unused methods throw a controlled `ExpressionEvaluationException` with the exact method name, immediately surfacing missing implementations in tests. This is safer than constructing an `Entity` with empty wrappers that silently return wrong data (e.g., empty collections for parts not loaded from storage)
- **Established pattern** — same approach as `ReferencedTypeEntityIndex.createThrowingStub()` (`ReferencedTypeEntityIndex:182-211`), already proven in the codebase

### Composable partial implementations

Each **partial** is a `PredicateMethodClassification` (or group thereof) that implements a related set of contract methods backed by specific storage part data. Partials are reusable building blocks — composed per expression at schema load time based on `AccessedDataFinder` path analysis.

#### EntityContract partials

| Partial | Contract Methods Implemented | State Needed |
|---|---|---|
| **SchemaPartial** | `getSchema()`, `getType()` | `EntitySchemaContract` (always available) |
| **PrimaryKeyPartial** | `getPrimaryKey()` | `EntityBodyStoragePart` |
| **EntityAttributePartial** | `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeSchema(String)`, `getAttributeLocales()`, `attributesAvailable()` | `AttributesStoragePart` (global + locale-specific) |
| **AssociatedDataPartial** | `getAssociatedData(String)`, `getAssociatedData(String, Locale)`, `getAssociatedDataSchema(String)`, `getAssociatedDataLocales()`, `associatedDataAvailable()` | `AssociatedDataStoragePart` (global + locale-specific) |
| **ReferencesPartial** | `getReferences(String)`, `getReference(String, int)`, `referencesAvailable()` | `ReferencesStoragePart` |
| **ParentPartial** | `getParent()`, `parentAvailable()` | `EntityBodyStoragePart` |
| **PricesPartial** *(future)* | `getPriceForSale(...)`, `getAllPricesForSale()`, `pricesAvailable()` | `PricesStoragePart` |
| **CatchAllPartial** *(always present)* | All other methods | (none) — throws `ExpressionEvaluationException` |

**Implementation notes:**

- `getAttribute(String)` uses the existing `AttributesStoragePart.findAttribute(AttributeKey)` method, which performs a **binary search** on the sorted `AttributeValue[]` array (via `Arrays.binarySearch()`). This is O(log n), already implemented and tested, and avoids the `LinkedHashMap` allocation entirely
- `getAttributeSchema(String)` delegates to `EntitySchemaContract.getAttribute(String)` — no storage part needed, just the schema reference
- `getReferences(String)` filters `Reference[]` from `ReferencesStoragePart` by reference name
- **SchemaPartial is always included** — `ReferencesContractAccessor` calls `getSchema()` to resolve cardinality, and `AttributesContractAccessor` calls `getAttributeSchema()` which delegates to schema

#### ReferenceContract partials

| Partial | Contract Methods Implemented | State Needed |
|---|---|---|
| **ReferenceIdentityPartial** | `getReferenceKey()`, `getReferencedPrimaryKey()`, `getReferencedEntityType()`, `getReferenceCardinality()`, `getReferenceSchema()`, `getReferenceName()` | `ReferenceKey` + `ReferenceSchemaContract` |
| **ReferenceAttributePartial** | `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeSchema(String)`, `getAttributeLocales()`, `attributesAvailable()` | Reference's `AttributeValue[]` (from `ReferencesStoragePart` entry) |
| **GroupReferencePartial** | `getGroup()` | `GroupEntityReference` from reference data |
| **ReferencedEntityPartial** | `getReferencedEntity()` — returns `Optional.of(nestedEntityProxy)` | Referenced entity's storage parts — nested EntityContract proxy |
| **GroupEntityPartial** | `getGroupEntity()` — returns `Optional.of(nestedEntityProxy)` | Group entity's storage parts — nested EntityContract proxy |
| **CatchAllPartial** *(always present)* | All other methods | (none) — throws `ExpressionEvaluationException` |

**Implementation notes:**

- **ReferenceIdentityPartial is always included** — needed for the reference to identify itself
- `getReferencedPrimaryKey()` and `getReferenceName()` are default methods on `ReferenceContract` that delegate to `getReferenceKey()`, so implementing `getReferenceKey()` covers them
- **ReferencedEntityPartial** and **GroupEntityPartial** create nested EntityContract proxies (see wiring below) — the nested proxy class is ALSO pre-built at schema time with its own set of partials, determined by what the expression accesses on the nested entity

#### CatchAllPartial — the safety net

Always present as the **last** classification in every proxy. Matches all methods not handled by selected partials:

```java
private static final PredicateMethodClassification<?, Void, ?> CATCH_ALL =
    new PredicateMethodClassification<>(
        "Unsupported expression method",
        (method, proxyState) -> true,  // matches everything not already matched
        (method, state) -> null,
        (proxy, method, args, methodContext, proxyState, invokeSuper) -> {
            throw new ExpressionEvaluationException(
                "Method " + method.getName() + "() is not available during expression " +
                "evaluation — the expression does not access this data.",
                "Cannot access " + method.getName() + "."
            );
        }
    );
```

This ensures that if the `@Delegate`-based DTOs forward a call to a method the expression does not need, we get an immediate, informative failure rather than silent wrong data.

### Path-to-partial mapping

`AccessedDataFinder` analyzes the expression and produces paths (lists of `PathItem`). Each path maps deterministically to a set of partials:

| Path prefix | Root proxy | Partial(s) selected |
|---|---|---|
| `$entity.primaryKey` | EntityContract | SchemaPartial + PrimaryKeyPartial |
| `$entity.attributes[...]` | EntityContract | SchemaPartial + EntityAttributePartial |
| `$entity.localizedAttributes[...]` | EntityContract | SchemaPartial + EntityAttributePartial |
| `$entity.associatedData[...]` | EntityContract | SchemaPartial + AssociatedDataPartial |
| `$entity.localizedAssociatedData[...]` | EntityContract | SchemaPartial + AssociatedDataPartial |
| `$entity.references[...]` | EntityContract | SchemaPartial + ReferencesPartial |
| `$entity.parent` | EntityContract | SchemaPartial + ParentPartial |
| `$reference.referencedPrimaryKey` | ReferenceContract | ReferenceIdentityPartial |
| `$reference.attributes[...]` | ReferenceContract | ReferenceIdentityPartial + ReferenceAttributePartial |
| `$reference.referencedEntity.*` | ReferenceContract | ReferenceIdentityPartial + ReferencedEntityPartial — **recurse** for nested entity partials |
| `$reference.groupEntity?.*` | ReferenceContract | ReferenceIdentityPartial + GroupEntityPartial — **recurse** for nested entity partials |

### Partial union logic

When an expression uses multiple paths, partials are **unioned** — e.g., an expression using both `$entity.attributes['code']` and `$entity.associatedData['desc']` gets one EntityContract proxy with SchemaPartial + EntityAttributePartial + AssociatedDataPartial + CatchAllPartial.

### Schema load time: build proxy classes and state recipe (9-step process)

When a schema is loaded or changed and contains a `facetedPartially` expression, the system performs full pre-analysis:

```
1. Parse expression -> ExpressionNode tree
2. AccessedDataFinder.findAccessedPaths(expression) -> List<List<PathItem>>
3. Map paths to partials (per path-to-partial mapping table above)
4. Union partials per root variable -> final set of PredicateMethodClassification[]
5. Compose ByteBuddyDispatcherInvocationHandler for EntityContract proxy (if needed)
6. Compose ByteBuddyDispatcherInvocationHandler for ReferenceContract proxy (if needed)
7. Compose ByteBuddyDispatcherInvocationHandler for nested entity proxies (if needed)
8. ByteBuddy generates proxy classes (cached -- one-time cost per unique partial set)
9. Build state recipe: which storage parts to fetch for each proxy
```

### FacetExpressionTrigger stored outputs

The result of schema load time analysis is stored in the `FacetExpressionTrigger` as:

- **Proxy class factories** — one per root variable and one per nested entity level. These are the pre-generated ByteBuddy classes, ready to be instantiated with a state record.
- **State recipe** — a descriptor of which storage parts to fetch from `WritableEntityStorageContainerAccessor` for each proxy level. Derived directly from the selected partials.

**No classes are generated at trigger time.** Only state records are created and pre-built proxy classes instantiated.

### Trigger time instantiation: `createEvaluationContext`

When a trigger fires, a single factory method on `FacetExpressionTrigger` creates the complete evaluation context. This method:

1. **Fetches only the storage parts the recipe requires** from `storageAccessor` — e.g., if the expression only accesses `$entity.attributes['code']`, only `EntityBodyStoragePart` and the global `AttributesStoragePart` are fetched, not `ReferencesStoragePart`, `AssociatedDataStoragePart`, or `PricesStoragePart`
2. **Creates state records** — lightweight records holding references to fetched parts and schema objects
3. **Instantiates pre-built proxy classes** with those states — just object allocation + state assignment
4. **Wires nested proxies** — for expressions accessing `$reference.groupEntity?.attributes['x']`:
   - Fetch group entity's storage parts, create entity proxy state, instantiate entity proxy
   - Create reference proxy state with `groupEntity = entityProxy`, instantiate reference proxy
5. **Returns** `ExpressionEvaluationContext` with bound variables (`$entity`, `$reference`)

### Wiring example

Expression: `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

```
Step 1: Fetch group entity's EntityBodyStoragePart + AttributesStoragePart
        -> create EntityProxyState(groupSchema, bodyPart, attrValues)
        -> instantiate pre-built GroupEntityProxy class -> groupEntityInstance

Step 2: Create ReferenceProxyState(refSchema, refKey, group, groupEntity=groupEntityInstance)
        -> instantiate pre-built ReferenceProxy class -> referenceInstance

Step 3: Return context: { $reference = referenceInstance }
```

Total allocations: 2 state records + 2 proxy instances = **4 objects**, regardless of how many entity properties exist. Compare with full `Entity` construction: Entity + EntityAttributes + LinkedHashMap + References + HashMap + AssociatedData + Prices = **7+ objects** with intermediate collection building.

### DataStoreMemoryBuffer caching

Storage parts are fetched through `WritableEntityStorageContainerAccessor` backed by `DataStoreMemoryBuffer`, which provides a read-through cache layer:

- **Within a single entity's mutation processing:** Multiple expressions (across different references of the same entity) likely access the same storage parts — the buffer serves them from cache after the first fetch
- **Cross-entity access:** When evaluating `$reference.groupEntity?.attributes['x']` for multiple owner entities that share the same group, the group entity's storage parts are fetched once and cached for subsequent evaluations
- **Transaction-scoped:** Cache is bounded to the transaction — discarded after mutation processing completes, so no persistent memory overhead

**To be verified by integration tests:** Confirm that `DataStoreMemoryBuffer` makes entity B's pending changes visible to entity A's accessor when both are in the same transaction (relevant for cross-entity triggers). This is a correctness prerequisite for the cross-entity trigger mechanism.

**Thread safety:** Proxy instances are created and consumed within a single mutation processing thread (the transaction's processing thread). They are not shared across threads and do not require synchronization. The `DataStoreMemoryBuffer` is transaction-scoped and also single-threaded. No thread-safety measures are needed on the proxy instances or state records.

### Performance characteristics

- **Local triggers (per-entity evaluation):** Proxycian proxies with **~2 allocations per entity** (proxy instance + state record), pre-built at schema time
- **Full entity construction comparison:** ~5-6 allocations (Entity + EntityAttributes + LinkedHashMap + References + AssociatedData + Prices) with intermediate collection building
- **Schema load time cost:** One-time ByteBuddy class generation per unique partial set (cached)
- **Trigger time cost:** State record creation + proxy instantiation only — no class generation, no path analysis, no partial selection on the hot path

### Relevant architecture decisions

- **AD 9:** Proxycian proxy wrapping — contract types (`EntityContract`, `ReferenceContract`) are implemented via Proxycian-generated proxies that delegate directly to storage part data. Avoids full entity assembly pipeline and the `Reference.getReferencedEntity()` returning `Optional.empty()` problem. Same pattern as `ReferencedTypeEntityIndex.createThrowingStub()`.
- **AD 10:** Composable partial implementations — each partial is a `PredicateMethodClassification` that implements a related set of contract methods. Partials are selected per expression based on `AccessedDataFinder` path analysis. A catch-all partial throws `ExpressionEvaluationException` for unused methods, providing fail-fast safety against `@Delegate` delegation to unimplemented methods.
- **AD 11:** Two-phase lifecycle — proxy classes and state recipes are built at schema load time (expensive but amortized). At trigger time, only state records are created and pre-built proxy classes instantiated (cheap — ~2 allocations per entity). No class generation on the hot path.

### Hidden trap #4 — Expression evaluation performance

Local triggers use Proxycian proxies with ~2 allocations per entity, pre-built at schema time. Cross-entity triggers use no per-entity evaluation — pure index-based `FilterBy` queries. The mitigation is two-mode evaluation: `evaluate()` for local (per-entity), `getFilterByConstraint()` for cross-entity (index-based query).

## Key Interfaces

### `FacetExpressionTrigger.createEvaluationContext`

```java
ExpressionEvaluationContext createEvaluationContext(
    int entityPK,
    @Nonnull ReferenceKey referenceKey,
    @Nonnull WritableEntityStorageContainerAccessor storageAccessor
)
```

This is the main entry point at trigger time. It takes a `ReferenceKey` (not `ReferenceContract`) since the caller has the key available from the mutation context. Internally, it fetches the `Reference` entry from `ReferencesStoragePart` via `findReferenceOrThrowException(ReferenceKey)` to obtain reference-level data (attributes, group). It then fetches additional storage parts per the pre-built state recipe, creates state records, instantiates pre-built proxy classes, wires nested proxies, and returns the `ExpressionEvaluationContext` with bound `$entity` and `$reference` variables. This method is called by `ExpressionIndexTrigger.evaluate()` (see parent analysis), which has a matching `ReferenceKey` parameter.

### Partial classification pattern

Each partial follows the `PredicateMethodClassification` pattern:

1. **Predicate** — matches specific contract methods by name/signature
2. **State extractor** — extracts required state from the proxy's state record
3. **Method implementation** — implements the contract method using raw storage part data

Partials are ordered: specific partials first, `CatchAllPartial` last. The first matching predicate wins (ByteBuddy dispatcher semantics).

### Always-included partials

- **EntityContract proxies:** SchemaPartial is always included (needed by `ReferencesContractAccessor` for cardinality and `AttributesContractAccessor` for schema lookup)
- **ReferenceContract proxies:** ReferenceIdentityPartial is always included (needed for reference self-identification)

## Acceptance Criteria

1. Each partial listed in the EntityContract and ReferenceContract tables is implemented as a `PredicateMethodClassification` that delegates to raw storage part data without intermediate wrapper objects
2. `AccessedDataFinder` paths are correctly mapped to partial sets per the path-to-partial mapping table
3. Multiple paths produce a union of partials (no duplicates, correct composition)
4. Proxy classes are generated at schema load time (steps 1-9) and cached by ByteBuddy
5. State recipes correctly identify which storage parts to fetch for each proxy level
6. `createEvaluationContext()` fetches only the storage parts specified by the recipe — no over-fetching
7. Nested entity proxies (for `$reference.referencedEntity.*` and `$reference.groupEntity?.*`) are correctly wired with their own partial sets
8. `CatchAllPartial` throws `ExpressionEvaluationException` with the method name for any unhandled method
9. `@Delegate`-based DTOs can successfully delegate to proxy instances for all accessed methods
10. `DataStoreMemoryBuffer` caching works correctly: same storage part is not fetched twice within a transaction
11. Integration test verifies `DataStoreMemoryBuffer` cross-entity visibility (entity B's pending changes visible to entity A's accessor in the same transaction)
12. Binary search via `AttributesStoragePart.findAttribute(AttributeKey)` in `EntityAttributePartial` performs correctly for attribute lookup
13. Total allocation count per entity evaluation matches the ~2 objects target (proxy instance + state record) for simple expressions
14. Wiring example scenario (`$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`) produces exactly 4 allocations (2 state records + 2 proxy instances)

## Implementation Notes

- Follow the established pattern from `ReferencedTypeEntityIndex.createThrowingStub()` (`ReferencedTypeEntityIndex:182-211`)
- `getAttribute(String)` should delegate to `AttributesStoragePart.findAttribute(AttributeKey)` which uses binary search on the sorted `AttributeValue[]` array — do NOT build a `LinkedHashMap`
- `getReferencedPrimaryKey()` and `getReferenceName()` are default methods on `ReferenceContract` that delegate to `getReferenceKey()` — implementing `getReferenceKey()` in `ReferenceIdentityPartial` covers them automatically
- Proxy infrastructure lives in `evita_engine`, not `evita_api` (AD 14) — `evita_api` holds only the raw `Expression` as declarative data
- ByteBuddy caches generated classes per unique partial set — different expressions that require the same set of partials share the same proxy class

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### Key class locations

| Class / Interface | Absolute path |
|---|---|
| `AccessedDataFinder` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/AccessedDataFinder.java` |
| `PathItem` (sealed interface) | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/PathItem.java` |
| `VariablePathItem` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/VariablePathItem.java` |
| `IdentifierPathItem` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/IdentifierPathItem.java` |
| `ElementPathItem` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/ElementPathItem.java` |
| `ReferencedTypeEntityIndex` (proxy pattern reference) | `evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java` |
| `EntityContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java` |
| `ReferenceContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java` |
| `AttributesContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java` |
| `AssociatedDataContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java` |
| `ReferencesContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferencesContract.java` |
| `PricesContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/PricesContract.java` |
| `WithEntitySchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/WithEntitySchema.java` |
| `EntityClassifierWithParent` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityClassifierWithParent.java` |
| `Versioned` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/Versioned.java` |
| `Droppable` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/Droppable.java` |
| `Reference` (base class) | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/Reference.java` |
| `EntityStoragePartAccessor` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/accessor/EntityStoragePartAccessor.java` |
| `WritableEntityStorageContainerAccessor` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/accessor/WritableEntityStorageContainerAccessor.java` |
| `EntityBodyStoragePart` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/entity/EntityBodyStoragePart.java` |
| `AttributesStoragePart` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/entity/AttributesStoragePart.java` |
| `ReferencesStoragePart` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/entity/ReferencesStoragePart.java` |
| `AssociatedDataStoragePart` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/entity/AssociatedDataStoragePart.java` |
| `PricesStoragePart` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/entity/PricesStoragePart.java` |
| `ExpressionEvaluationException` | `evita_common/src/main/java/io/evitadb/exception/ExpressionEvaluationException.java` |
| `ExpressionEvaluationContext` | `evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionEvaluationContext.java` |
| `EntityContractAccessor` | `evita_api/src/main/java/io/evitadb/api/query/expression/object/accessor/entity/EntityContractAccessor.java` |
| `ReferenceContractAccessor` | `evita_api/src/main/java/io/evitadb/api/query/expression/object/accessor/entity/ReferenceContractAccessor.java` |
| `AttributesContractAccessor` | `evita_api/src/main/java/io/evitadb/api/query/expression/object/accessor/entity/AttributesContractAccessor.java` |
| `ReferencesContractAccessor` | `evita_api/src/main/java/io/evitadb/api/query/expression/object/accessor/entity/ReferencesContractAccessor.java` |
| `AssociatedDataContractAccessor` | `evita_api/src/main/java/io/evitadb/api/query/expression/object/accessor/entity/AssociatedDataContractAccessor.java` |
| `Expression` | `evita_common/src/main/java/io/evitadb/dataType/expression/Expression.java` |
| `module-info.java` (evita_engine) | `evita_engine/src/main/java/module-info.java` |

##### Key method signatures and behavior

**`AccessedDataFinder.findAccessedPaths(ExpressionNode)`**
- Static factory method, returns `List<List<PathItem>>` where each inner list is a path from root variable to leaf access
- `PathItem` is a sealed interface with three implementations: `VariablePathItem` (e.g., `$entity`), `IdentifierPathItem` (e.g., `references`), `ElementPathItem` (e.g., `brand` from `['brand']`)
- Paths are compacted: if path A is a prefix of path B, only B is retained
- Example: `$entity.references['brand'].attributes['order']` produces `[VariablePathItem("entity"), IdentifierPathItem("references"), ElementPathItem("brand"), IdentifierPathItem("attributes"), ElementPathItem("order")]`

**`EntityStoragePartAccessor` methods (parent of `WritableEntityStorageContainerAccessor`)**
- `getEntityStoragePart(String entityType, int entityPrimaryKey, EntityExistence expects)` -> `EntityBodyStoragePart`
- `getAttributeStoragePart(String entityType, int entityPrimaryKey)` -> `AttributesStoragePart` (global)
- `getAttributeStoragePart(String entityType, int entityPrimaryKey, Locale locale)` -> `AttributesStoragePart` (localized)
- `getAssociatedDataStoragePart(String entityType, int entityPrimaryKey, AssociatedDataKey key)` -> `AssociatedDataStoragePart`
- `getReferencesStoragePart(String entityType, int entityPrimaryKey)` -> `ReferencesStoragePart`
- `getPriceStoragePart(String entityType, int entityPrimaryKey)` -> `PricesStoragePart`

**`EntityBodyStoragePart` key fields**
- `int primaryKey` (getter)
- `Integer parent` (nullable getter) -- used by `ParentPartial`
- `Set<Locale> locales` (unmodifiable getter)
- `Set<Locale> attributeLocales` (unmodifiable getter)

**`AttributesStoragePart` key fields and methods**
- `AttributeValue[] attributes` -- sorted array, supports binary search via `findAttribute(AttributeKey)`
- `Locale getLocale()` -- null for global, non-null for locale-specific parts
- `EntityAttributesSetKey attributeSetKey` -- composite key of `entityPrimaryKey` + `locale`

**`ReferencesStoragePart` key fields and methods**
- `Reference[] getReferences()` -- sorted array of all references for the entity
- `getReferencedIds(String referenceName)` -- returns int[] of referenced entity PKs for a given reference name
- `findReferenceOrThrowException(ReferenceKey)` -- finds single reference by key
- References include inline `AttributeValue[]` accessible via `Reference`'s `@Delegate Attributes<AttributeSchemaContract> attributes`

**`AssociatedDataStoragePart` key fields**
- `AssociatedDataValue value` -- single associated data value per part
- `EntityAssociatedDataKey associatedDataKey` -- composite key of `entityPrimaryKey` + `associatedDataName` + `locale`

**`Reference` class (base implementation of `ReferenceContract`)**
- `getReferencedEntity()` returns `Optional.empty()` (line 303-304) -- this is the key problem the proxy solves
- `getGroupEntity()` returns `Optional.empty()` (line 327-328) -- same problem
- `getGroup()` returns `Optional.ofNullable(this.group)` -- `GroupEntityReference` is available from stored data
- `getReferenceKey()` returns `ReferenceKey` -- `getReferencedPrimaryKey()` and `getReferenceName()` are default methods delegating to it
- Inline `Attributes<AttributeSchemaContract> attributes` via `@Delegate`

**`EntityContract` interface hierarchy**
- Extends: `EntityClassifierWithParent` (provides `getType()`, `getPrimaryKey()`, `getParentEntity()`), `AttributesContract<EntityAttributeSchemaContract>`, `AssociatedDataContract`, `PricesContract`, `ReferencesContract`, `Versioned`, `Droppable`, `WithEntitySchema`
- Key methods for proxy: `getPrimaryKey()`, `getSchema()`, `getParent()`, `parentAvailable()`, `getType()`, `getScope()`, `getAllLocales()`, `getLocales()`, plus all inherited from sub-contracts

**`ReferenceContract` interface**
- Extends: `AttributesContract<AttributeSchemaContract>`, `Droppable`, `ContentComparator<ReferenceContract>`
- Key methods: `getReferenceKey()`, `getReferenceName()` (default), `getReferencedPrimaryKey()` (default), `getReferencedEntity()`, `getReferencedEntityType()`, `getReferenceCardinality()`, `getGroup()`, `getGroupEntity()`, `getReferenceSchema()`, `getReferenceSchemaOrThrow()`

**Accessor DTO delegation pattern (critical for proxy compatibility)**
- `EntityContractAccessor.EntityAttributesEvaluationDto` -- `record(@Delegate EntityContract delegate, boolean requestedLocalizedAttributes) implements AttributesContract<EntityAttributeSchemaContract>` -- delegates ALL `EntityContract` methods to the proxy
- `EntityContractAccessor.EntityAssociatedDataEvaluationDto` -- same pattern for `AssociatedDataContract`
- `EntityContractAccessor.EntityReferencesEvaluationDto` -- same pattern for `ReferencesContract`
- `ReferenceContractAccessor.ReferenceAttributesEvaluationDto` -- `record(@Delegate ReferenceContract delegate, boolean requestedLocalizedAttributes) implements AttributesContract<AttributeSchemaContract>`

**`AttributesContractAccessor` methods called on the proxy**
- `getAttributeSchema(String)` -- to validate attribute existence
- `getAttribute(String)` -- for non-localized attributes
- `getAttribute(String, Locale)` -- for localized attributes (iterated over `getAttributeLocales()`)
- `getAttributeLocales()` -- to discover available locales for localized attribute access

**`ReferencesContractAccessor` methods called on the proxy**
- `getSchema()` -- to look up `ReferenceSchemaContract` by name and resolve cardinality
- `getReferences(String)` -- to retrieve references by name

**`AssociatedDataContractAccessor` methods called on the proxy**
- `getAssociatedDataSchema(String)` -- to validate existence
- `getAssociatedData(String)` -- for non-localized
- `getAssociatedData(String, Locale)` -- for localized (iterated over `getAssociatedDataLocales()`)
- `getAssociatedDataLocales()` -- to discover available locales

##### Existing proxy pattern to follow

`ReferencedTypeEntityIndex.createThrowingStub()` (lines 182-211) establishes the pattern:

1. Define a **state record** as a private record holding proxy-scoped data:
   ```java
   private record ReferencedTypeEntityIndexProxyStateThrowing(
       @Nonnull EntitySchemaContract entitySchema
   ) implements Serializable {}
   ```

2. Define **`PredicateMethodClassification` constants** as `private static final` fields. Each has four parts:
   - Description string (for debugging)
   - Predicate: `(method, proxyState) -> boolean` -- matches methods this classification handles. Uses `ReflectionUtils.isMethodDeclaredOn()` or `ReflectionUtils.isMatchingMethodPresentOn()` for precise matching
   - State extractor: `(method, state) -> extractedState` -- returns null when no extracted state is needed
   - Implementation: `(proxy, method, args, methodContext, proxyState, invokeSuper) -> returnValue`

3. **Compose handler** via `ByteBuddyDispatcherInvocationHandler` constructor, passing state record + classifications in priority order (specific first, catch-all last)

4. **Generate proxy** via `ByteBuddyProxyGenerator.instantiate()`, passing:
   - The handler
   - Array of interfaces/classes to implement
   - Constructor parameter types
   - Constructor parameter values

Type parameters of `PredicateMethodClassification<P, M, S>`: `P` = proxy type, `M` = method context type (typically `Void`), `S` = state record type.

##### Target module and package

All proxy infrastructure classes should live in `evita_engine` module under a new package:
`io.evitadb.core.expression.proxy`

This package sits within `evita_engine/src/main/java/io/evitadb/core/expression/proxy/`.

Rationale: The `evita_engine` module already depends on `proxycian.bytebuddy` (confirmed in `module-info.java` line 120), has access to storage parts and schema contracts, and the WBS document specifies AD 14: "Proxy infrastructure lives in `evita_engine`, not `evita_api`". The `core/expression/` namespace groups expression-evaluation-related engine code logically.

#### Detailed Task List

##### Group 1: State records and shared infrastructure

- [ ] **1.1** Create `EntityProxyState` record -- `evita_engine/.../core/expression/proxy/EntityProxyState.java`. Fields: `EntitySchemaContract schema`, `EntityBodyStoragePart bodyPart` (nullable -- only fetched when `PrimaryKeyPartial` or `ParentPartial` is needed), `AttributesStoragePart[] attributesParts` (nullable -- global + locale-specific, only when `EntityAttributePartial` is needed), `AssociatedDataStoragePart[] associatedDataParts` (nullable -- only when `AssociatedDataPartial` is needed), `ReferencesStoragePart referencesPart` (nullable -- only when `ReferencesPartial` is needed). Must implement `Serializable`.

- [ ] **1.2** Create `ReferenceProxyState` record -- `evita_engine/.../core/expression/proxy/ReferenceProxyState.java`. Fields: `ReferenceSchemaContract referenceSchema`, `ReferenceKey referenceKey`, `int version` (from `Reference.version()` -- used by `ReferenceVersionAndDroppablePartial`), `AttributeValue[] attributes` (reference-level attributes from the `Reference` entry in `ReferencesStoragePart`), `Set<Locale> attributeLocales`, `GroupEntityReference group` (nullable), `SealedEntity referencedEntity` (nullable -- populated by nested proxy when `ReferencedEntityPartial` is active), `SealedEntity groupEntity` (nullable -- populated by nested proxy when `GroupEntityPartial` is active). Must implement `Serializable`.

- [ ] **1.3** Create `CatchAllPartial` class -- `evita_engine/.../core/expression/proxy/CatchAllPartial.java`. Contains a `public static final PredicateMethodClassification` constant named `INSTANCE` that matches all methods (`(method, proxyState) -> true`) and throws `ExpressionEvaluationException` with the method name. Reused by both entity and reference proxy compositions. Also contains an `OBJECT_METHODS` classification that delegates `Object.class` methods (toString, hashCode, equals) to `invokeSuper.call()`, matching the pattern from `ReferencedTypeEntityIndex`. **Ordering within `CatchAllPartial`:** When composing the classification list, `OBJECT_METHODS` must appear **before** `INSTANCE` in the list. ByteBuddy uses first-match semantics, so `OBJECT_METHODS` must have priority over the catch-all `INSTANCE`. The full ordering is: all specific partials first, then `CatchAllPartial.OBJECT_METHODS`, then `CatchAllPartial.INSTANCE` last.

##### Group 2: EntityContract partials

- [ ] **2.1** Create `EntitySchemaPartial` class -- `evita_engine/.../core/expression/proxy/entity/EntitySchemaPartial.java`. `PredicateMethodClassification` matching `getSchema()` and `getType()` on `EntityContract`/`WithEntitySchema`. `getSchema()` returns `EntitySchemaContract` from `EntityProxyState.schema()`. `getType()` returns `schema.getName()`. Note: the proxy's `getSchema()` returns the `EntitySchemaContract` instance as stored in the state -- this may be a non-sealed schema implementation, which differs from `Entity.getSchema()` which typically returns a `SealedEntitySchema`. This is acceptable in the expression evaluation context, as the accessors only call `getAttribute(String)` and similar methods on the schema contract interface.

- [ ] **2.2** Create `EntityPrimaryKeyPartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityPrimaryKeyPartial.java`. `PredicateMethodClassification` matching `getPrimaryKey()` on `EntityContract`. Returns `bodyPart.getPrimaryKey()` from `EntityProxyState`.

- [ ] **2.3** Create `EntityAttributePartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityAttributePartial.java`. Multiple `PredicateMethodClassification` instances covering: `getAttribute(String)` -- delegates to `AttributesStoragePart.findAttribute(AttributeKey)` on the global `AttributesStoragePart` from `EntityProxyState.attributesParts[0]` (binary search on sorted array); `getAttribute(String, Locale)` -- finds locale-specific `AttributesStoragePart` from `attributesParts` array, then delegates to `findAttribute(AttributeKey)` (binary search); `getAttributeSchema(String)` -- delegates to `schema.getAttribute(String)` (returns `Optional`); `getAttributeLocales()` -- returns union of locales from all locale-specific attribute parts; `attributesAvailable()` -- returns `true`; `getAttributeValue(String)` and `getAttributeValue(String, Locale)` -- linear scan returning `Optional<AttributeValue>`; `getAttributeValues()` and `getAttributeValues(String)` -- iterate attribute arrays.

- [ ] **2.4** Create `EntityAssociatedDataPartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityAssociatedDataPartial.java`. `PredicateMethodClassification` instances covering: `getAssociatedData(String)` -- finds matching `AssociatedDataStoragePart` from `EntityProxyState.associatedDataParts`, returns `value.value()`; `getAssociatedData(String, Locale)` -- same with locale matching; `getAssociatedDataSchema(String)` -- delegates to `schema.getAssociatedData(String)`; `getAssociatedDataLocales()` -- returns union of locales from associated data parts; `associatedDataAvailable()` -- returns `true`.

- [ ] **2.5** Create `EntityReferencesPartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityReferencesPartial.java`. `PredicateMethodClassification` instances covering: `getReferences(String)` -- filters `Reference[]` from `ReferencesStoragePart.getReferences()` by reference name (linear scan, collecting matches into a list); `getReference(String, int)` -- finds specific reference by name + PK; `referencesAvailable()` -- returns `true`.

- [ ] **2.6** Create `EntityParentPartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityParentPartial.java`. `PredicateMethodClassification` instances covering: `getParent()` -- returns `OptionalInt.of(bodyPart.getParent())` or `OptionalInt.empty()` if null; `parentAvailable()` -- returns `true`; `getParentEntity()` -- returns `Optional` wrapping an `EntityReferenceWithParent` built from the parent PK.

- [ ] **2.7** Create `EntityVersionAndDroppablePartial` class -- `evita_engine/.../core/expression/proxy/entity/EntityVersionAndDroppablePartial.java`. Always-included `PredicateMethodClassification` matching `version()`, `dropped()`, `getScope()`, `getAllLocales()`, `getLocales()` on `EntityContract`. Returns sensible defaults from `EntityProxyState`: version from `bodyPart.getVersion()`, `dropped()` returns `false` (storage parts exist), `getScope()` from `bodyPart.getScope()`, locales from `bodyPart.getLocales()`. **Note on remaining `EntityContract` methods:** `EntityContract` also inherits `differsFrom(EntityContract)` from `ContentComparator<EntityContract>` and `estimateSize()` -- both are `default` methods on `EntityContract` with working implementations, so they do not need explicit partial handling. The `exists()` method from `Droppable` is also a default method (delegates to `dropped()`). Any other methods from the full `EntityContract` interface surface not covered by selected partials will fall through to `CatchAllPartial`, which throws `ExpressionEvaluationException`.

##### Group 3: ReferenceContract partials

- [ ] **3.1** Create `ReferenceIdentityPartial` class -- `evita_engine/.../core/expression/proxy/reference/ReferenceIdentityPartial.java`. `PredicateMethodClassification` instances covering: `getReferenceKey()` -- returns `ReferenceProxyState.referenceKey()`; `getReferencedEntityType()` -- returns `referenceSchema.getReferencedEntityType()`; `getReferenceCardinality()` -- returns `referenceSchema.getCardinality()`; `getReferenceSchema()` -- returns `Optional.of(referenceSchema)`; `getReferenceSchemaOrThrow()` -- returns `referenceSchema`. Note: `getReferencedPrimaryKey()` and `getReferenceName()` are default methods on `ReferenceContract` that delegate to `getReferenceKey()` -- no explicit implementation needed.

- [ ] **3.2** Create `ReferenceAttributePartial` class -- `evita_engine/.../core/expression/proxy/reference/ReferenceAttributePartial.java`. `PredicateMethodClassification` instances covering: `getAttribute(String)` -- binary search of `ReferenceProxyState.attributes()` array (using `Arrays.binarySearch()` on the sorted `AttributeValue[]`, consistent with `AttributesStoragePart.findAttribute()`); `getAttribute(String, Locale)` -- same with locale matching; `getAttributeSchema(String)` -- delegates to `referenceSchema.getAttribute(String)`; `getAttributeLocales()` -- returns `ReferenceProxyState.attributeLocales()`; `attributesAvailable()` -- returns `true`.

- [ ] **3.3** Create `GroupReferencePartial` class -- `evita_engine/.../core/expression/proxy/reference/GroupReferencePartial.java`. `PredicateMethodClassification` matching `getGroup()` -- returns `Optional.ofNullable(ReferenceProxyState.group())`.

- [ ] **3.4** Create `ReferencedEntityPartial` class -- `evita_engine/.../core/expression/proxy/reference/ReferencedEntityPartial.java`. `PredicateMethodClassification` matching `getReferencedEntity()` -- returns `Optional.ofNullable(ReferenceProxyState.referencedEntity())`. The `SealedEntity` value is a nested EntityContract proxy, wired at trigger time.

- [ ] **3.5** Create `GroupEntityPartial` class -- `evita_engine/.../core/expression/proxy/reference/GroupEntityPartial.java`. `PredicateMethodClassification` matching `getGroupEntity()` -- returns `Optional.ofNullable(ReferenceProxyState.groupEntity())`. The `SealedEntity` value is a nested EntityContract proxy, wired at trigger time.

- [ ] **3.6** Create `ReferenceVersionAndDroppablePartial` class -- `evita_engine/.../core/expression/proxy/reference/ReferenceVersionAndDroppablePartial.java`. Always-included `PredicateMethodClassification` matching `version()` and `dropped()` on `ReferenceContract`. `dropped()` returns `false` (the proxy exists because the reference is live). `version()` returns the version from the `Reference` object obtained from `ReferencesStoragePart` (available via `Reference.version()`), rather than a hardcoded default. This avoids masking bugs in tests that verify version values and is consistent with `EntityVersionAndDroppablePartial` which reads from the storage part. The `ReferenceProxyState` record (Task 1.2) should include an `int version` field populated from the `Reference` entry.

##### Group 4: State recipe builder (path-to-partial mapping)

- [ ] **4.1** Create `StoragePartRecipe` record -- `evita_engine/.../core/expression/proxy/StoragePartRecipe.java`. Immutable descriptor of which storage parts to fetch at trigger time. Fields: `boolean needsEntityBody`, `boolean needsGlobalAttributes`, `Set<Locale> neededAttributeLocales` (empty set means "all locales" when the expression accesses localized attributes), `boolean needsReferences`, `Set<String> neededAssociatedDataNames` (set of associated data names extracted from expression paths like `$entity.associatedData['desc']`), `Set<Locale> neededAssociatedDataLocales` (locales for localized associated data -- empty means "all locales" when the expression accesses localized associated data via `$entity.localizedAssociatedData['x']`), `boolean needsPrices` (future), `StoragePartRecipe nestedReferencedEntityRecipe` (nullable -- for `$reference.referencedEntity.*`), `StoragePartRecipe nestedGroupEntityRecipe` (nullable -- for `$reference.groupEntity?.*`). **Associated data locale resolution:** `AccessedDataFinder` produces paths like `$entity.associatedData['desc']` (non-localized) and `$entity.localizedAssociatedData['desc']` (localized). For non-localized paths, the `AssociatedDataKey` is constructed with `locale=null`. For localized paths, the locale is not known at schema load time -- the `ExpressionProxyInstantiator` constructs `AssociatedDataKey` instances at trigger time by combining each name with each locale from `neededAssociatedDataLocales` (or from `EntityBodyStoragePart.getLocales()` when "all locales" is indicated). Each key is then passed to `EntityStoragePartAccessor.getAssociatedDataStoragePart(entityType, pk, AssociatedDataKey)` individually.

- [ ] **4.2** Create `PathToPartialMapper` class -- `evita_engine/.../core/expression/proxy/PathToPartialMapper.java`. Takes `List<List<PathItem>>` from `AccessedDataFinder.findAccessedPaths()` and produces: (a) `Set<PredicateMethodClassification>` for EntityContract proxy, (b) `Set<PredicateMethodClassification>` for ReferenceContract proxy, (c) sets for any nested entity proxies, (d) `StoragePartRecipe` for each proxy level. Implements the path-to-partial mapping table from the WBS document. Handles path prefix matching: first item must be a `VariablePathItem` with value `entity` or `reference`; second item is an `IdentifierPathItem` whose value selects the partial (`primaryKey`, `attributes`, `localizedAttributes`, `associatedData`, `localizedAssociatedData`, `references`, `parent`, `referencedPrimaryKey`, `referencedEntity`, `groupEntity`). Implements union logic: multiple paths contributing the same partial type are deduplicated. Always includes `SchemaPartial` + `EntityVersionAndDroppablePartial` + `CatchAllPartial` for entity proxies and `ReferenceIdentityPartial` + `ReferenceVersionAndDroppablePartial` + `CatchAllPartial` for reference proxies. Handles recursive descent for nested entity paths (paths continuing after `referencedEntity` or `groupEntity`).

##### Group 5: Schema load time proxy class composition

- [ ] **5.1** Create `ExpressionProxyFactory` class -- `evita_engine/.../core/expression/proxy/ExpressionProxyFactory.java`. Main entry point for schema-load-time proxy class preparation. Public method `buildProxyDescriptor(ExpressionNode expression, EntitySchemaContract entitySchema, ReferenceSchemaContract referenceSchema)` that: (1) calls `AccessedDataFinder.findAccessedPaths(expression)`, (2) calls `PathToPartialMapper` to get partials + recipe, (3) composes `ByteBuddyDispatcherInvocationHandler` for each proxy level, (4) calls `ByteBuddyProxyGenerator` to pre-generate proxy classes, (5) returns an `ExpressionProxyDescriptor`.

- [ ] **5.2** Create `ExpressionProxyDescriptor` record -- `evita_engine/.../core/expression/proxy/ExpressionProxyDescriptor.java`. Immutable output of `ExpressionProxyFactory.buildProxyDescriptor()`. Fields: `ByteBuddyDispatcherInvocationHandler entityHandler` (nullable -- null when expression does not access `$entity`), `ByteBuddyDispatcherInvocationHandler referenceHandler` (nullable -- null when expression does not access `$reference`), `ByteBuddyDispatcherInvocationHandler nestedReferencedEntityHandler` (nullable), `ByteBuddyDispatcherInvocationHandler nestedGroupEntityHandler` (nullable), `StoragePartRecipe entityRecipe` (nullable), `StoragePartRecipe referenceRecipe` (nullable). This descriptor is stored in `FacetExpressionTrigger` and reused at every trigger time invocation.

##### Group 6: Trigger time instantiation

- [ ] **6.1** Create `ExpressionProxyInstantiator` class -- `evita_engine/.../core/expression/proxy/ExpressionProxyInstantiator.java`. Trigger-time factory that uses a pre-built `ExpressionProxyDescriptor` to: (1) fetch storage parts per the `StoragePartRecipe` from `EntityStoragePartAccessor`, (2) build `EntityProxyState` and/or `ReferenceProxyState` records, (3) instantiate pre-built proxy classes via `ByteBuddyProxyGenerator.instantiate()`, (4) wire nested proxies (for `referencedEntity`/`groupEntity` paths, first build the nested entity proxy, then inject it into `ReferenceProxyState`), (5) return the proxy instances. Public method signature: `createProxies(ExpressionProxyDescriptor descriptor, int entityPK, ReferenceContract reference, EntityStoragePartAccessor storageAccessor, EntitySchemaContract entitySchema, ReferenceSchemaContract referenceSchema)` returning a record with `EntityContract entityProxy` (nullable) and `ReferenceContract referenceProxy` (nullable). **Note on nested entity proxies:** The root `entityProxy` is typed as `EntityContract`, but nested entity proxies (for `$reference.referencedEntity.*` and `$reference.groupEntity?.*`) must implement `SealedEntity` (see Task 7.3) since `ReferenceContract.getReferencedEntity()` and `getGroupEntity()` return `Optional<SealedEntity>`. Internally, the instantiator creates the nested proxies as `SealedEntity` instances and stores them in `ReferenceProxyState.referencedEntity` / `ReferenceProxyState.groupEntity` (which are typed as `SealedEntity` per Task 1.2). The upcast from the ByteBuddy-generated proxy to `SealedEntity` is guaranteed because the nested proxy class includes `SealedEntity.class` in its interfaces array.

- [ ] **6.2** Implement `createEvaluationContext()` method on `FacetExpressionTrigger` (this class does not exist yet; it will be created in WBS-03 but the method signature and delegation to `ExpressionProxyInstantiator` should be designed here). Define the method contract: takes `int entityPK`, `ReferenceKey referenceKey`, `WritableEntityStorageContainerAccessor storageAccessor`; returns `ExpressionEvaluationContext` with bound `$entity` and `$reference` variables. Internally calls `ExpressionProxyInstantiator.createProxies()` and wraps results in an `ExpressionEvaluationContext` implementation with variable bindings. **Relationship with `ExpressionIndexTrigger.evaluate()`:** The parent analysis defines `evaluate(int ownerEntityPK, ReferenceKey referenceKey, WritableEntityStorageContainerAccessor storageAccessor)` on `ExpressionIndexTrigger`. The `evaluate()` method calls `createEvaluationContext()` internally. Both methods take `ReferenceKey` (not `ReferenceContract`), because the caller (e.g., `ReferenceIndexMutator`) has a `ReferenceKey` available from the mutation context. The `createEvaluationContext()` implementation fetches the `Reference` entry from `ReferencesStoragePart` using `ReferencesStoragePart.findReferenceOrThrowException(ReferenceKey)` to obtain the reference's `AttributeValue[]` data needed by `ReferenceAttributePartial`.

- [ ] **6.3** Create `ExpressionVariableContext` class -- `evita_engine/.../core/expression/proxy/ExpressionVariableContext.java`. Implements `ExpressionEvaluationContext`. Holds a `Map<String, Object>` of variable bindings (e.g., `"entity" -> entityProxy`, `"reference" -> referenceProxy`). Methods: `getVariable(String)` returns `Optional.ofNullable(map.get(variableName))` (matching the `@Nonnull Optional<Object>` return type declared by `ExpressionEvaluationContext`); `getVariableNames()` streams the keys; `getThis()` returns `Optional.empty()`; `withThis(Object)` creates a copy with `this` set; `getRandom()` returns a shared `Random` instance.

##### Group 7: Nested entity proxy wiring

- [ ] **7.1** Implement nested entity proxy wiring logic in `ExpressionProxyInstantiator` -- when `StoragePartRecipe.nestedReferencedEntityRecipe()` is non-null: fetch the referenced entity's storage parts using `storageAccessor.getEntityStoragePart(referencedEntityType, referencedPrimaryKey, ...)` and relevant attribute/associated data parts per the nested recipe; build `EntityProxyState` for the referenced entity; instantiate the nested entity proxy class from `ExpressionProxyDescriptor.nestedReferencedEntityHandler()`; pass the resulting proxy as `referencedEntity` field in `ReferenceProxyState`.

- [ ] **7.2** Implement group entity proxy wiring -- same as 7.1 but for `nestedGroupEntityRecipe`. Fetch group entity's storage parts using the group entity type from `ReferenceSchemaContract.getReferencedGroupType()` and the group PK from `GroupEntityReference.getPrimaryKey()`. Handle nullable group (if `reference.getGroup()` is empty, the group entity proxy is null).

- [ ] **7.3** Ensure `SealedEntity` compatibility -- the proxy generated for nested entities must implement `SealedEntity` (since `ReferenceContract.getReferencedEntity()` and `getGroupEntity()` return `Optional<SealedEntity>`). The `ByteBuddyProxyGenerator.instantiate()` call for nested entity proxies must include `SealedEntity.class` in the interfaces array. **`SealedEntity` extends `SealedInstance<SealedEntity, EntityBuilder>`**, which adds three abstract methods not present on `EntityContract`: `openForWrite()`, `withMutations(LocalMutation...)`, and `withMutations(Collection<LocalMutation>)`. These methods have no default implementation and **must be absorbed by `CatchAllPartial`**, which will throw `ExpressionEvaluationException` for any of them. Note that `EntityContract` also extends `ContentComparator<EntityContract>` (adding `differsFrom()`) and inherits `estimateSize()` -- both are default methods on `EntityContract` and will use their default implementations unless intercepted by a partial, so they do not require explicit handling.

##### Group 8: Module configuration

- [ ] **8.1** Update `evita_engine/src/main/java/module-info.java` -- add `exports io.evitadb.core.expression.proxy` (and sub-packages if needed) so that downstream modules (test modules, future WBS-03 trigger infrastructure) can access the proxy factory.

- [ ] **8.2** Verify Proxycian dependency -- confirm that `proxycian.bytebuddy` module requirement already declared in `module-info.java` (line 120 -- confirmed) covers `ByteBuddyProxyGenerator`, `ByteBuddyDispatcherInvocationHandler`, and `PredicateMethodClassification` imports. No POM changes expected.

### Test Cases

Test classes live in `evita_engine/src/test/java/io/evitadb/core/expression/proxy/`.

---

#### `CatchAllPartialTest`

**Category: safety net behavior**

- [ ] `catch_all_throws_expression_evaluation_exception_with_method_name` -- invoke an arbitrary `EntityContract` method on a proxy composed only with `CatchAllPartial`; expect `ExpressionEvaluationException` whose message contains the method name
- [ ] `catch_all_exception_message_includes_human_readable_context` -- invoke `getAttribute("code")` on a catch-all-only entity proxy; verify the exception public message mentions "not available during expression evaluation"
- [ ] `catch_all_does_not_intercept_object_methods` -- invoke `toString()`, `hashCode()`, and `equals()` on a catch-all-only proxy; verify they delegate to the default Object implementation (via `invokeSuper`) and do NOT throw `ExpressionEvaluationException`
- [ ] `catch_all_matches_every_unhandled_method` -- compose a proxy with `EntitySchemaPartial` + `CatchAllPartial`; invoke `getPrimaryKey()` (unhandled); expect `ExpressionEvaluationException`. Then invoke `getSchema()` (handled); expect success
- [ ] `catch_all_is_last_in_classification_order` -- compose a proxy with `EntityPrimaryKeyPartial` + `CatchAllPartial` where both could theoretically match `getPrimaryKey()`; verify the specific partial wins and returns the correct value (ByteBuddy first-match semantics)

---

#### `EntitySchemaPartialTest`

**Category: schema access**

- [ ] `get_schema_returns_entity_schema_from_state` -- create entity proxy with `EntitySchemaPartial`; call `getSchema()`; verify it returns the exact `EntitySchemaContract` instance from `EntityProxyState`
- [ ] `get_type_returns_schema_name` -- create entity proxy with `EntitySchemaPartial`; call `getType()`; verify it returns `entitySchema.getName()`
- [ ] `unhandled_methods_throw_when_only_schema_partial_present` -- create entity proxy with only `EntitySchemaPartial` + `CatchAllPartial`; call `getPrimaryKey()`; expect `ExpressionEvaluationException`

---

#### `EntityPrimaryKeyPartialTest`

**Category: entity identity**

- [ ] `get_primary_key_returns_value_from_body_storage_part` -- create entity proxy with `EntityPrimaryKeyPartial`; supply `EntityBodyStoragePart` with PK=42; call `getPrimaryKey()`; verify result is 42
- [ ] `get_primary_key_with_different_values` -- test with PK=0, PK=Integer.MAX_VALUE, and a typical PK; verify each returns correctly

---

#### `EntityAttributePartialTest`

**Category: non-localized attribute access**

- [ ] `get_attribute_returns_value_by_binary_search` -- create proxy with `EntityAttributePartial`; supply `AttributesStoragePart` with global attributes `[("code", "ABC"), ("name", "Widget")]`; call `getAttribute("code")`; verify result is `"ABC"` (delegates to `AttributesStoragePart.findAttribute(AttributeKey)` which uses binary search on the sorted array)
- [ ] `get_attribute_returns_null_for_missing_attribute` -- call `getAttribute("nonExistent")` on a proxy with attributes `[("code", "ABC")]`; verify result is null
- [ ] `get_attribute_handles_various_value_types` -- supply attributes with `String`, `Integer`, `BigDecimal`, and `Boolean` values; verify each is returned with correct type

**Category: localized attribute access**

- [ ] `get_attribute_with_locale_returns_localized_value` -- supply locale-specific `AttributesStoragePart` for `Locale.ENGLISH` with `("name", "Widget")`; call `getAttribute("name", Locale.ENGLISH)`; verify result is `"Widget"`
- [ ] `get_attribute_with_locale_returns_null_for_wrong_locale` -- supply attributes for `Locale.ENGLISH` only; call `getAttribute("name", Locale.GERMAN)`; verify result is null
- [ ] `get_attribute_locales_returns_union_of_all_locale_parts` -- supply `AttributesStoragePart` for `Locale.ENGLISH` and `Locale.GERMAN`; call `getAttributeLocales()`; verify result is `{ENGLISH, GERMAN}`

**Category: schema delegation**

- [ ] `get_attribute_schema_delegates_to_entity_schema` -- supply `EntitySchemaContract` mock that has attribute schema for `"code"`; call `getAttributeSchema("code")`; verify it returns the schema from the entity schema
- [ ] `get_attribute_schema_returns_empty_for_unknown_attribute` -- call `getAttributeSchema("unknown")`; verify `Optional.empty()`

**Category: availability flag**

- [ ] `attributes_available_returns_true` -- call `attributesAvailable()` on a proxy with `EntityAttributePartial`; verify it returns `true`

---

#### `EntityAssociatedDataPartialTest`

**Category: non-localized associated data access**

- [ ] `get_associated_data_returns_value_by_name` -- supply `AssociatedDataStoragePart` with `("description", "A widget")`; call `getAssociatedData("description")`; verify result is `"A widget"`
- [ ] `get_associated_data_returns_null_for_missing_key` -- call `getAssociatedData("nonExistent")`; verify null

**Category: localized associated data access**

- [ ] `get_associated_data_with_locale_returns_localized_value` -- supply locale-specific `AssociatedDataStoragePart` for `Locale.ENGLISH`; call `getAssociatedData("description", Locale.ENGLISH)`; verify correct value
- [ ] `get_associated_data_locales_returns_available_locales` -- supply parts for two locales; call `getAssociatedDataLocales()`; verify both are returned

**Category: schema delegation**

- [ ] `get_associated_data_schema_delegates_to_entity_schema` -- call `getAssociatedDataSchema("description")`; verify delegation to `entitySchema.getAssociatedData("description")`

**Category: availability flag**

- [ ] `associated_data_available_returns_true` -- call `associatedDataAvailable()`; verify `true`

---

#### `EntityReferencesPartialTest`

**Category: reference retrieval**

- [ ] `get_references_filters_by_name` -- supply `ReferencesStoragePart` containing references `[("brand", 1), ("brand", 2), ("category", 10)]`; call `getReferences("brand")`; verify result has exactly 2 references with PKs 1 and 2
- [ ] `get_references_returns_empty_collection_for_missing_name` -- call `getReferences("nonExistent")`; verify empty collection
- [ ] `get_reference_finds_by_name_and_pk` -- call `getReference("brand", 1)`; verify correct reference is returned
- [ ] `get_reference_returns_empty_for_wrong_pk` -- call `getReference("brand", 999)`; verify `Optional.empty()`

**Category: availability flag**

- [ ] `references_available_returns_true` -- call `referencesAvailable()`; verify `true`

---

#### `EntityParentPartialTest`

**Category: parent access**

- [ ] `get_parent_returns_parent_pk_when_present` -- supply `EntityBodyStoragePart` with parent=5; call `getParent()`; verify `OptionalInt.of(5)`
- [ ] `get_parent_returns_empty_when_null` -- supply `EntityBodyStoragePart` with parent=null; call `getParent()`; verify `OptionalInt.empty()`
- [ ] `parent_available_returns_true` -- call `parentAvailable()`; verify `true`

---

#### `EntityVersionAndDroppablePartialTest`

**Category: version and lifecycle**

- [ ] `version_returns_body_part_version` -- supply `EntityBodyStoragePart` with version=3; call `version()`; verify result is 3
- [ ] `dropped_returns_false` -- call `dropped()`; verify `false` (proxy existence implies the entity is live)
- [ ] `get_scope_returns_body_part_scope` -- supply body part with `Scope.LIVE`; call `getScope()`; verify `Scope.LIVE`
- [ ] `get_all_locales_returns_body_part_locales` -- supply body part with locales `{ENGLISH, GERMAN}`; call `getAllLocales()`; verify both are present

---

#### `ReferenceIdentityPartialTest`

**Category: reference identity**

- [ ] `get_reference_key_returns_key_from_state` -- create reference proxy with `ReferenceIdentityPartial`; supply `ReferenceKey("brand", 42)`; call `getReferenceKey()`; verify result
- [ ] `get_referenced_primary_key_delegates_to_reference_key` -- call `getReferencedPrimaryKey()` (default method); verify it returns 42 via delegation to `getReferenceKey().primaryKey()`
- [ ] `get_reference_name_delegates_to_reference_key` -- call `getReferenceName()` (default method); verify it returns `"brand"` via delegation to `getReferenceKey().referenceName()`
- [ ] `get_referenced_entity_type_returns_schema_value` -- supply `ReferenceSchemaContract` with referenced entity type `"Brand"`; call `getReferencedEntityType()`; verify result
- [ ] `get_reference_cardinality_returns_schema_value` -- supply schema with `Cardinality.ZERO_OR_MORE`; call `getReferenceCardinality()`; verify result
- [ ] `get_reference_schema_returns_optional_of_schema` -- call `getReferenceSchema()`; verify `Optional.of(referenceSchema)`
- [ ] `get_reference_schema_or_throw_returns_schema` -- call `getReferenceSchemaOrThrow()`; verify it returns the same schema instance

---

#### `ReferenceAttributePartialTest`

**Category: reference attribute access**

- [ ] `get_attribute_returns_value_from_reference_attribute_array` -- supply `AttributeValue[]` with `("order", 5)`; call `getAttribute("order")`; verify result is 5
- [ ] `get_attribute_returns_null_for_missing_attribute` -- call `getAttribute("nonExistent")`; verify null
- [ ] `get_attribute_with_locale_returns_localized_value` -- supply locale-specific attribute values; call `getAttribute("name", Locale.ENGLISH)`; verify correct value
- [ ] `get_attribute_schema_delegates_to_reference_schema` -- call `getAttributeSchema("order")`; verify delegation to `referenceSchema.getAttribute("order")`
- [ ] `get_attribute_locales_returns_locales_from_state` -- supply `attributeLocales = {ENGLISH}`; call `getAttributeLocales()`; verify result
- [ ] `attributes_available_returns_true` -- verify `attributesAvailable()` returns `true`

---

#### `GroupReferencePartialTest`

**Category: group access**

- [ ] `get_group_returns_group_entity_reference_when_present` -- supply `GroupEntityReference("parameterGroup", 7, 1, false)`; call `getGroup()`; verify `Optional.of(groupRef)`
- [ ] `get_group_returns_empty_when_null` -- supply null group; call `getGroup()`; verify `Optional.empty()`

---

#### `ReferencedEntityPartialTest`

**Category: referenced entity access**

- [ ] `get_referenced_entity_returns_nested_proxy_when_wired` -- wire a nested `SealedEntity` proxy into `ReferenceProxyState.referencedEntity`; call `getReferencedEntity()`; verify `Optional.of(nestedProxy)`
- [ ] `get_referenced_entity_returns_empty_when_not_wired` -- supply null `referencedEntity`; call `getReferencedEntity()`; verify `Optional.empty()`

---

#### `GroupEntityPartialTest`

**Category: group entity access**

- [ ] `get_group_entity_returns_nested_proxy_when_wired` -- wire a nested `SealedEntity` proxy into `ReferenceProxyState.groupEntity`; call `getGroupEntity()`; verify `Optional.of(nestedProxy)`
- [ ] `get_group_entity_returns_empty_when_not_wired` -- supply null `groupEntity`; call `getGroupEntity()`; verify `Optional.empty()`

---

#### `ReferenceVersionAndDroppablePartialTest`

**Category: reference lifecycle**

- [ ] `version_returns_value_from_reference` -- call `version()` on reference proxy; verify it returns the version from the `Reference` entry in `ReferencesStoragePart` (e.g., supply a reference with version=3, verify result is 3)
- [ ] `dropped_returns_false` -- call `dropped()` on reference proxy; verify `false`

---

#### `PathToPartialMapperTest`

**Category: single-path mapping**

- [ ] `entity_primary_key_path_maps_to_schema_and_primary_key_partials` -- input path `[$entity, primaryKey]`; verify output includes `SchemaPartial`, `PrimaryKeyPartial`, `EntityVersionAndDroppablePartial`, and `CatchAllPartial`
- [ ] `entity_attributes_path_maps_to_schema_and_attribute_partials` -- input path `[$entity, attributes, ElementPathItem("code")]`; verify output includes `SchemaPartial` + `EntityAttributePartial`
- [ ] `entity_localized_attributes_path_maps_to_attribute_partial` -- input path `[$entity, localizedAttributes, ElementPathItem("name")]`; verify output includes `EntityAttributePartial` (same partial as non-localized, just different storage part fetch)
- [ ] `entity_associated_data_path_maps_to_associated_data_partial` -- input path `[$entity, associatedData, ElementPathItem("desc")]`; verify `AssociatedDataPartial`
- [ ] `entity_localized_associated_data_path_maps_to_associated_data_partial` -- input path `[$entity, localizedAssociatedData, ElementPathItem("desc")]`; verify `AssociatedDataPartial`
- [ ] `entity_references_path_maps_to_references_partial` -- input path `[$entity, references, ElementPathItem("brand")]`; verify `ReferencesPartial`
- [ ] `entity_parent_path_maps_to_parent_partial` -- input path `[$entity, parent]`; verify `ParentPartial`
- [ ] `reference_primary_key_path_maps_to_identity_partial` -- input path `[$reference, referencedPrimaryKey]`; verify `ReferenceIdentityPartial`
- [ ] `reference_attributes_path_maps_to_identity_and_attribute_partials` -- input path `[$reference, attributes, ElementPathItem("order")]`; verify `ReferenceIdentityPartial` + `ReferenceAttributePartial`
- [ ] `reference_referenced_entity_path_triggers_nested_entity_proxy` -- input path `[$reference, referencedEntity, attributes, ElementPathItem("name")]`; verify `ReferenceIdentityPartial` + `ReferencedEntityPartial` for the reference proxy, plus a nested entity proxy descriptor with `SchemaPartial` + `EntityAttributePartial`
- [ ] `reference_group_entity_path_triggers_nested_entity_proxy` -- input path `[$reference, groupEntity, attributes, ElementPathItem("inputWidgetType")]`; verify `GroupEntityPartial` + nested entity proxy with attribute partial

**Category: union logic**

- [ ] `multiple_entity_paths_produce_union_of_partials` -- input paths `[$entity, attributes['code']]` and `[$entity, associatedData['desc']]`; verify single entity proxy with `SchemaPartial` + `EntityAttributePartial` + `AssociatedDataPartial` + `CatchAllPartial` (no duplicates)
- [ ] `duplicate_partial_types_are_deduplicated` -- input paths `[$entity, attributes['code']]` and `[$entity, attributes['name']]`; verify only one `EntityAttributePartial` instance
- [ ] `entity_and_reference_paths_produce_separate_proxy_descriptors` -- input paths `[$entity, attributes['code']]` and `[$reference, attributes['order']]`; verify both entity and reference proxy descriptors are produced

**Category: always-included partials**

- [ ] `schema_partial_always_included_for_entity_proxy` -- input any entity path; verify `EntitySchemaPartial` is always in the entity proxy's partial set
- [ ] `version_and_droppable_partial_always_included_for_entity_proxy` -- verify `EntityVersionAndDroppablePartial` is always present
- [ ] `identity_partial_always_included_for_reference_proxy` -- input any reference path; verify `ReferenceIdentityPartial` is always present
- [ ] `version_and_droppable_partial_always_included_for_reference_proxy` -- verify `ReferenceVersionAndDroppablePartial` is always present
- [ ] `catch_all_always_last_for_entity_proxy` -- verify `CatchAllPartial` is the last classification in the entity proxy composition
- [ ] `catch_all_always_last_for_reference_proxy` -- verify `CatchAllPartial` is the last classification in the reference proxy composition

**Category: edge cases**

- [ ] `empty_path_list_produces_no_proxy_descriptors` -- input empty list; verify no entity or reference proxy is created
- [ ] `unknown_variable_name_throws_illegal_argument_exception` -- input path with `VariablePathItem("unknown")`; verify it throws `IllegalArgumentException` (expressions are validated at schema load time, so an unknown variable name indicates a bug in path analysis or expression validation and should fail fast)

---

#### `StoragePartRecipeTest`

**Category: recipe construction**

- [ ] `primary_key_partial_sets_needs_entity_body_flag` -- verify `needsEntityBody == true` when `PrimaryKeyPartial` is selected
- [ ] `parent_partial_sets_needs_entity_body_flag` -- verify `needsEntityBody == true` when `ParentPartial` is selected
- [ ] `attribute_partial_sets_needs_global_attributes_flag` -- verify `needsGlobalAttributes == true` when `EntityAttributePartial` is selected
- [ ] `localized_attribute_path_sets_needed_attribute_locales` -- verify `neededAttributeLocales` contains the relevant locale(s)
- [ ] `references_partial_sets_needs_references_flag` -- verify `needsReferences == true`
- [ ] `associated_data_partial_sets_needed_associated_data` -- verify `neededAssociatedData` contains the relevant keys
- [ ] `nested_referenced_entity_recipe_is_populated` -- when a `$reference.referencedEntity.*` path is present, verify `nestedReferencedEntityRecipe` is non-null with correct flags
- [ ] `nested_group_entity_recipe_is_populated` -- same for `$reference.groupEntity?.*`
- [ ] `recipe_excludes_unnecessary_storage_parts` -- for an expression accessing only `$entity.attributes['code']`, verify `needsReferences == false`, `neededAssociatedData` is empty, and `needsPrices == false`

---

#### `ExpressionProxyFactoryTest`

**Category: end-to-end proxy class generation**

- [ ] `build_proxy_descriptor_for_simple_attribute_expression` -- parse `$entity.attributes['code'] == 'ABC'`; call `buildProxyDescriptor()`; verify returned `ExpressionProxyDescriptor` has a non-null `entityHandler`, null `referenceHandler`, and correct `entityRecipe`
- [ ] `build_proxy_descriptor_for_reference_attribute_expression` -- parse `$reference.attributes['order'] > 5`; verify non-null `referenceHandler`, null `entityHandler`
- [ ] `build_proxy_descriptor_for_mixed_entity_and_reference_expression` -- parse `$entity.attributes['active'] == true && $reference.referencedPrimaryKey > 0`; verify both handlers are non-null
- [ ] `build_proxy_descriptor_for_nested_group_entity_expression` -- parse `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`; verify `nestedGroupEntityHandler` is non-null
- [ ] `build_proxy_descriptor_for_nested_referenced_entity_expression` -- parse `$reference.referencedEntity.attributes['name'] != null`; verify `nestedReferencedEntityHandler` is non-null
- [ ] `build_proxy_descriptor_caches_proxy_classes_for_same_partial_set` -- call `buildProxyDescriptor()` twice with different expressions that select the same partial set; verify the generated proxy class is the same instance (ByteBuddy caching)

---

#### `ExpressionProxyInstantiatorTest`

**Category: proxy instantiation**

- [ ] `create_proxies_instantiates_entity_proxy_from_descriptor` -- supply a pre-built `ExpressionProxyDescriptor` with entity handler + recipe; mock `EntityStoragePartAccessor` to return an `EntityBodyStoragePart` and `AttributesStoragePart`; call `createProxies()`; verify the returned `entityProxy` is non-null and implements `EntityContract`
- [ ] `create_proxies_instantiates_reference_proxy_from_descriptor` -- same pattern for reference proxy; verify it implements `ReferenceContract`
- [ ] `create_proxies_returns_null_entity_proxy_when_no_entity_handler` -- supply descriptor with null `entityHandler`; verify `entityProxy` is null
- [ ] `create_proxies_returns_null_reference_proxy_when_no_reference_handler` -- supply descriptor with null `referenceHandler`; verify `referenceProxy` is null

**Category: storage part fetching**

- [ ] `create_proxies_fetches_only_recipe_specified_parts` -- supply a recipe with `needsEntityBody=true, needsGlobalAttributes=true, needsReferences=false`; verify `storageAccessor.getReferencesStoragePart()` is NEVER called while `getEntityStoragePart()` and `getAttributeStoragePart()` ARE called
- [ ] `create_proxies_fetches_locale_specific_attribute_parts` -- supply recipe with `neededAttributeLocales={ENGLISH, GERMAN}`; verify `getAttributeStoragePart(type, pk, ENGLISH)` and `getAttributeStoragePart(type, pk, GERMAN)` are called

**Category: proxy method delegation**

- [ ] `instantiated_entity_proxy_returns_correct_primary_key` -- verify `entityProxy.getPrimaryKey()` returns the PK from the body storage part
- [ ] `instantiated_entity_proxy_returns_correct_attribute_value` -- verify `entityProxy.getAttribute("code")` returns the value from the attribute storage part
- [ ] `instantiated_reference_proxy_returns_correct_reference_key` -- verify `referenceProxy.getReferenceKey()` returns the key from state
- [ ] `instantiated_reference_proxy_throws_for_unhandled_method` -- call an unhandled method (e.g., `estimateSize()`) on the reference proxy; expect `ExpressionEvaluationException`

---

#### `NestedEntityProxyWiringTest`

**Category: referenced entity wiring**

- [ ] `referenced_entity_proxy_is_wired_into_reference_proxy` -- for expression `$reference.referencedEntity.attributes['name']`: build descriptor, create proxies; call `referenceProxy.getReferencedEntity()`; verify it returns `Optional` containing a `SealedEntity` proxy; call `getAttribute("name")` on the nested proxy; verify correct value
- [ ] `referenced_entity_proxy_fetches_its_own_storage_parts` -- verify the nested entity proxy's storage parts are fetched using the referenced entity's type and PK (not the owner entity's)
- [ ] `referenced_entity_proxy_has_independent_partial_set` -- the nested entity proxy should have its own set of partials determined by what the expression accesses on it; e.g., only `EntityAttributePartial` if only attributes are accessed

**Category: group entity wiring**

- [ ] `group_entity_proxy_is_wired_into_reference_proxy` -- for expression `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`: build descriptor, create proxies; call `referenceProxy.getGroupEntity()`; verify it returns `Optional` containing a `SealedEntity` proxy with correct attribute value
- [ ] `group_entity_proxy_uses_group_entity_type_and_pk` -- verify storage parts are fetched using `referenceSchema.getReferencedGroupType()` and `groupEntityReference.getPrimaryKey()`
- [ ] `group_entity_proxy_is_null_when_reference_has_no_group` -- when `reference.getGroup()` is empty, verify `getGroupEntity()` returns `Optional.empty()`

**Category: SealedEntity compatibility**

- [ ] `nested_entity_proxy_implements_sealed_entity_interface` -- verify the nested proxy's class implements `SealedEntity`; verify `instanceof SealedEntity` returns true
- [ ] `nested_entity_proxy_catch_all_handles_sealed_entity_methods` -- call a `SealedEntity`-specific method not covered by partials; expect `ExpressionEvaluationException` from `CatchAllPartial`

**Category: allocation count**

- [ ] `simple_entity_expression_produces_two_allocations` -- for expression `$entity.attributes['code'] == 'ABC'`, verify exactly 2 new objects are created at trigger time: 1 `EntityProxyState` + 1 entity proxy instance
- [ ] `wiring_example_produces_four_allocations` -- for expression `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`, verify exactly 4 new objects at trigger time: 2 state records (EntityProxyState for group entity + ReferenceProxyState) + 2 proxy instances (group entity proxy + reference proxy)

---

#### `DtoDelegationCompatibilityTest`

**Category: EntityAttributesEvaluationDto delegation**

- [ ] `entity_attributes_dto_delegates_get_attribute_to_proxy` -- wrap entity proxy in `EntityAttributesEvaluationDto`; call `getAttributeSchema("code")` then `getAttribute("code")` through the DTO; verify correct values are returned
- [ ] `entity_attributes_dto_delegates_get_attribute_locales_to_proxy` -- wrap entity proxy with localized attributes; call `getAttributeLocales()` through the DTO; verify correct locales
- [ ] `entity_attributes_dto_localized_access_iterates_locales_and_calls_get_attribute_with_locale` -- wrap entity proxy (with attributes for EN and DE) in `EntityAttributesEvaluationDto(proxy, true)`; exercise the `AttributesContractAccessor.get()` flow; verify the accessor calls `getAttribute(name, locale)` for each locale and returns a Map

**Category: EntityAssociatedDataEvaluationDto delegation**

- [ ] `entity_associated_data_dto_delegates_get_associated_data_to_proxy` -- wrap entity proxy in `EntityAssociatedDataEvaluationDto`; call `getAssociatedData("description")` through the DTO; verify correct value
- [ ] `entity_associated_data_dto_delegates_get_associated_data_locales_to_proxy` -- verify `getAssociatedDataLocales()` returns locales from the proxy

**Category: EntityReferencesEvaluationDto delegation**

- [ ] `entity_references_dto_delegates_get_schema_to_proxy` -- wrap entity proxy in `EntityReferencesEvaluationDto`; call `getSchema()` through the DTO; verify it returns the `EntitySchemaContract` (used by `ReferencesContractAccessor` for cardinality lookup)
- [ ] `entity_references_dto_delegates_get_references_to_proxy` -- call `getReferences("brand")` through the DTO; verify correct references returned

**Category: ReferenceAttributesEvaluationDto delegation**

- [ ] `reference_attributes_dto_delegates_get_attribute_to_proxy` -- wrap reference proxy in `ReferenceAttributesEvaluationDto`; call `getAttribute("order")` through the DTO; verify correct value
- [ ] `reference_attributes_dto_delegates_get_attribute_schema_to_proxy` -- call `getAttributeSchema("order")` through the DTO; verify delegation to reference schema

**Category: full accessor chain integration**

- [ ] `attributes_contract_accessor_works_with_entity_proxy_via_dto` -- create entity proxy -> wrap in `EntityAttributesEvaluationDto` -> pass to `AttributesContractAccessor.get(dto, "code")`; verify the full chain returns the correct attribute value
- [ ] `attributes_contract_accessor_works_with_reference_proxy_via_dto` -- create reference proxy -> wrap in `ReferenceAttributesEvaluationDto` -> pass to `AttributesContractAccessor.get(dto, "order")`; verify correct value
- [ ] `references_contract_accessor_works_with_entity_proxy_via_dto` -- create entity proxy -> wrap in `EntityReferencesEvaluationDto` -> pass to `ReferencesContractAccessor.get(dto, "brand")`; verify correct references
- [ ] `associated_data_contract_accessor_works_with_entity_proxy_via_dto` -- create entity proxy -> wrap in `EntityAssociatedDataEvaluationDto` -> pass to `AssociatedDataContractAccessor.get(dto, "description")`; verify correct value

**Category: unhandled delegation safety**

- [ ] `dto_delegation_of_unhandled_method_throws_expression_evaluation_exception` -- wrap entity proxy (with only `EntityAttributePartial`) in `EntityReferencesEvaluationDto`; call `getReferences("brand")` through DTO; verify `ExpressionEvaluationException` is thrown by `CatchAllPartial` because `ReferencesPartial` was not included

---

#### `ExpressionVariableContextTest`

**Category: variable binding and retrieval**

- [ ] `get_variable_returns_bound_entity_proxy` -- bind `"entity"` -> entityProxy; call `getVariable("entity")`; verify the returned object is the entity proxy
- [ ] `get_variable_returns_bound_reference_proxy` -- bind `"reference"` -> referenceProxy; verify correct retrieval
- [ ] `get_variable_returns_null_for_unbound_name` -- call `getVariable("unknown")`; verify null or empty result
- [ ] `get_variable_names_returns_all_bound_names` -- bind `"entity"` and `"reference"`; call `getVariableNames()`; verify both names are present

**Category: cloning and immutability**

- [ ] `with_this_creates_copy_with_this_set` -- call `withThis(someObject)`; verify the returned context has `getThis()` returning `someObject` and original context remains unchanged
- [ ] `get_this_returns_empty_by_default` -- verify `getThis()` returns empty/null on a freshly constructed context

**Category: random source**

- [ ] `get_random_returns_shared_random_instance` -- call `getRandom()` twice; verify the same `Random` instance is returned

---

#### `ExpressionProxyPerformanceTest`

**Category: schema load time performance**

- [ ] `proxy_class_generation_is_cached_across_identical_partial_sets` -- generate proxy class for partial set A; generate again for the same set; verify no new class is generated (ByteBuddy cache hit)

**Category: trigger time allocation**

- [ ] `entity_only_expression_allocates_at_most_two_objects` -- instrument or count allocations for `createProxies()` with a simple `$entity.attributes['code']` expression; verify at most 2 new objects (1 state record + 1 proxy)
- [ ] `reference_with_group_entity_expression_allocates_at_most_four_objects` -- for `$reference.groupEntity?.attributes['x']`; verify at most 4 objects (2 state records + 2 proxies)

**Category: attribute lookup performance**

- [ ] `binary_search_is_faster_than_hashmap_for_small_attribute_counts` -- benchmark `getAttribute("code")` via `findAttribute(AttributeKey)` binary search on sorted arrays of size 1, 5, 10, 20, 30 attributes vs. HashMap lookup; verify binary search is competitive (informational benchmark, not a hard assertion -- use JUnit `@Tag("performance")` to allow selective execution)
