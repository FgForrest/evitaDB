# WBS-04: `CatalogExpressionTriggerRegistry` — Cross-Schema Wiring and Trigger Lookup

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Implement the `CatalogExpressionTriggerRegistry` — a catalog-level inverted index that maps mutated entity types to `ExpressionIndexTrigger` instances that depend on their data. The registry enables cross-schema wiring so that when entity B (e.g., ParameterGroup) changes, the system can efficiently discover which other schemas (e.g., Product) have conditional expressions depending on B's data and need re-evaluation.

## Scope

### In Scope

- `CatalogExpressionTriggerRegistry` interface and its concrete implementation in `io.evitadb.core.catalog` (`evita_engine`)
- Inverted index data structure mapping `(mutatedEntityType, DependencyType)` to `List<ExpressionIndexTrigger>`
- `getTriggersFor(String mutatedEntityType, DependencyType dependencyType)` — broad lookup returning all triggers for a given entity type and dependency relationship
- `getTriggersForAttribute(String mutatedEntityType, DependencyType dependencyType, String attributeName)` — selective lookup filtering by dependent attribute name
- `rebuildForEntityType(String entityType, List<ExpressionIndexTrigger> newTriggers)` — immutable rebuild returning a new registry instance (copy-on-write); the caller builds the trigger list externally and passes it in
- Registration logic at schema load/change time: reading `Expression` from `ReferenceSchema.facetedPartiallyInScopes`, using `AccessedDataFinder` to discover dependencies, building `FacetExpressionTrigger` instances, and indexing them under the appropriate keys
- `ReflectedReferenceSchema` inheritance handling: processing both direct and reflected reference schemas; cascading trigger rebuild when a source schema's `facetedPartially` expression changes
- Cold start / initial catalog load: populating the registry by scanning all loaded `ReferenceSchema` definitions that carry `facetedPartiallyInScopes` expressions during catalog initialization
- Support for all `ExpressionIndexTrigger` subtypes (the registry is generic — stores `FacetExpressionTrigger`, `HistogramExpressionTrigger`, etc.)

### Out of Scope

- `ExpressionIndexTrigger` / `FacetExpressionTrigger` interface definitions and their concrete implementations (WBS for trigger hierarchy)
- `DependencyType` enum definition (companion WBS or part of trigger hierarchy WBS)
- `ReevaluateFacetExpressionMutation` creation and dispatching (detection step in `EntityIndexLocalMutationExecutor`)
- `ReevaluateFacetExpressionExecutor` and the target-side processing (executor WBS)
- `IndexMutationExecutorRegistry` wiring (executor registry WBS)
- `ExpressionToQueryTranslator` and `AccessedDataFinder` internals (expression analysis WBS)
- Expression evaluation (both local `evaluate()` and index-based `getFilterByConstraint()`)
- Affected entity PK resolution (target executor's responsibility)
- Schema evolution / full re-index on expression change

## Dependencies

### Depends On

- **WBS for `ExpressionIndexTrigger` hierarchy** — the registry stores `ExpressionIndexTrigger` instances; the interface and at least `FacetExpressionTrigger` must exist
- **WBS for `DependencyType` enum** — used as a registry key component (`REFERENCED_ENTITY_ATTRIBUTE`, `GROUP_ENTITY_ATTRIBUTE`)
- **`AccessedDataFinder`** — used at schema load/change time to classify expression dependencies and determine which attributes on which entity types are read by the expression
- **`ReferenceSchema.facetedPartiallyInScopes`** (`Map<Scope, Expression>`) — the declarative expression data field in `evita_api` that the registry reads to build triggers
- **`ReflectedReferenceSchema`** — the registry must handle reflected references that inherit `facetedPartially` from the source schema

### Depended On By

- **Detection step in `EntityIndexLocalMutationExecutor`** — post-processing calls `getTriggersFor()` / `getTriggersForAttribute()` to discover which cross-entity triggers need to fire when a mutation occurs
- **`Catalog`** — hosts the registry reference (as a `TransactionalReference`), orchestrates trigger building via `FacetExpressionTriggerFactory` and invokes `rebuildForEntityType(entityType, newTriggers)` on schema changes, and wires trigger implementations between collections at catalog initialization
- **Multi-schema group entity processing** — the registry is consulted to generate one `EntityIndexMutation` per target collection, grouped by `getOwnerEntityType()`

## Technical Context

### Full Interface Definition

The registry lives in `io.evitadb.core.catalog` (`evita_engine`):

```java
/**
 * Catalog-level inverted index that maps mutated entity types to the
 * {@link ExpressionIndexTrigger} instances that depend on their data.
 *
 * Built/rebuilt when entity schemas change. Consulted during the
 * post-processing step of `EntityIndexLocalMutationExecutor` to
 * determine which cross-entity triggers need to fire.
 *
 * The registry inverts the ownership:
 * - Schema A (Product) defines reference "parameter" with conditional
 *   expression depending on group entity ParameterGroup
 * - Registry stores: ("ParameterGroup", GROUP_ENTITY_ATTRIBUTE) → [trigger for
 *   Product/"parameter"]
 *
 * So "ParameterGroup maintains triggers that fire handlers in Product."
 */
public interface CatalogExpressionTriggerRegistry {

    /**
     * Finds all triggers that depend on the given entity type with the
     * specified dependency relationship. Called during post-processing
     * to discover which target collections need notification.
     *
     * @param mutatedEntityType the entity type being mutated
     *        (e.g., "parameterGroup")
     * @param dependencyType how the mutated entity relates to the owner
     * @return all matching triggers (empty list if none)
     */
    @Nonnull
    List<ExpressionIndexTrigger> getTriggersFor(
        @Nonnull String mutatedEntityType,
        @Nonnull DependencyType dependencyType
    );

    /**
     * More selective variant — returns only triggers whose
     * {@link ExpressionIndexTrigger#getDependentAttributes()} contains the
     * given attribute name. Avoids firing triggers when the changed attribute
     * is irrelevant to the expression.
     */
    @Nonnull
    List<ExpressionIndexTrigger> getTriggersForAttribute(
        @Nonnull String mutatedEntityType,
        @Nonnull DependencyType dependencyType,
        @Nonnull String attributeName
    );

    /**
     * Rebuilds the registry index for the given entity type based on the
     * provided list of new triggers. Called when an entity schema's reference
     * definitions change.
     *
     * The caller (typically {@code Catalog}) is responsible for building the
     * trigger list by invoking {@code FacetExpressionTriggerFactory} for each
     * reference schema on the specified entity type. This method is a
     * **pure function** — it does not access the catalog or any external state.
     *
     * **Immutability principle:** the rebuild constructs a **new** registry instance.
     * The original instance remains untouched and continues serving concurrent
     * readers until they switch to the new instance (same copy-on-write pattern
     * as {@code EntityCollection} transactional copies). This eliminates
     * concurrent mutation processing seeing stale or partially-built trigger state.
     *
     * @param entityType  the owner entity type whose triggers are being rebuilt
     * @param newTriggers the complete set of triggers built from the entity
     *                    type's current reference schemas (may be empty to
     *                    remove all triggers for that entity type)
     */
    @Nonnull
    CatalogExpressionTriggerRegistry rebuildForEntityType(
        @Nonnull String entityType,
        @Nonnull List<ExpressionIndexTrigger> newTriggers
    );
}
```

### Inverted Index Concept

The registry inverts the ownership of expression triggers:

- **Schema A** (e.g., Product) _defines_ a reference "parameter" with a conditional expression like `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`
- **Schema B** (e.g., ParameterGroup) is the _mutated entity type_ — the registry indexes the trigger under schema B's entity type
- **Registry key:** `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)`
- **Registry value:** `[trigger(ownerEntityType="product", referenceName="parameter", scope=..., dependentAttributes={"inputWidgetType"}, ...)]`

This inversion means "ParameterGroup maintains triggers that fire handlers in Product." When ParameterGroup entity 99 changes `inputWidgetType`, the detection step calls `registry.getTriggersForAttribute("parameterGroup", GROUP_ENTITY_ATTRIBUTE, "inputWidgetType")` and generates an `EntityIndexMutation` targeting "product".

### Generic Trigger Storage

The registry is generic — it stores all `ExpressionIndexTrigger` instances regardless of subtype (facet, histogram, etc.). The trigger hierarchy is:

```
ExpressionIndexTrigger (generic base — expression evaluation)
├── FacetExpressionTrigger (conditional facet indexing)
└── HistogramExpressionTrigger (conditional histogram indexing, future)
```

Callers can filter by trigger subtype (e.g., `instanceof FacetExpressionTrigger`) if they need type-specific processing.

### Example Wiring (Product / ParameterGroup Scenario)

1. Schema Product defines reference "parameter" with expression `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`
2. At schema load time, Product's `EntityCollection` reads the `Expression` from `ReferenceSchema.facetedPartiallyInScopes`
3. `AccessedDataFinder` discovers that the expression reads attribute `inputWidgetType` on the group entity
4. `EntityCollection` builds a `FacetExpressionTrigger` and registers it under:
   - key: `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)`
   - value: `[trigger(ownerEntityType="product", referenceName="parameter", ...)]`
5. When ParameterGroup entity 99 changes `inputWidgetType`, the detection step calls `registry.getTriggersForAttribute("parameterGroup", GROUP_ENTITY_ATTRIBUTE, "inputWidgetType")` and generates an `EntityIndexMutation` targeting "product"

### Immutability Principle (Copy-on-Write)

The registry follows the same copy-on-write immutability principle as `EntityCollection`. When `rebuildForEntityType(entityType, newTriggers)` is called:

- A **new** registry instance is constructed from the current index plus the provided triggers
- The original instance remains untouched and continues serving concurrent readers
- Readers switch to the new instance only after the rebuild is complete
- This eliminates the risk of concurrent mutation processing seeing stale or partially-built trigger state

This is captured in Architecture Decision item 19 ("CatalogExpressionTriggerRegistry immutability") in the parent analysis document's "Architecture Decisions / Decided" numbered list.

### ReflectedReferenceSchema Inheritance and Cascade

`ReflectedReferenceSchema` can inherit `facetedPartially` from the source `ReferenceSchema`. The registry must handle this:

- **Direct references:** trigger is built from the expression defined directly on the `ReferenceSchema`
- **Reflected references:** for reflected schemas that inherit `facetedPartially`, the trigger is built from the inherited expression and registered under the reflected schema's entity type
- **Cascade on change:** when the source schema's `facetedPartially` expression changes, all reflected schemas that inherit it must have their triggers rebuilt. The `rebuildForEntityType()` for the source entity type must cascade to all entity types with reflected references that inherit from the changed source

(Hidden trap #18 in the parent analysis.)

### Multi-Schema Group Entity Processing

Each schema's collection handles its own triggers independently:

- The post-processing step in `EntityIndexLocalMutationExecutor` calls `getTriggersForAttribute()` which returns triggers across **all** dependent schemas
- The detection step groups triggers by `getOwnerEntityType()` and generates one `EntityIndexMutation` per target collection
- Each target's `applyIndexMutations()` dispatches the nested mutations independently to its own executors
- No cross-collection coordination is needed — each mutation carries all context needed for independent execution

### De-Duplication of Local + Cross-Entity Triggers

Index operations (add/remove facet) are **idempotent**. If both a local trigger and a cross-entity trigger produce the same mutation for the same entity, the second operation is a no-op at the index level. For ordering:

- `LocalMutationExecutorCollector` processes the owner entity first (level N)
- Cross-entity triggers fire during post-processing (level N+1)
- The local trigger always completes before the cross-entity reevaluation runs

### De-Duplication of IndexMutations After `popMutations`

When a single expression produces multiple triggers (e.g., an expression accessing
`references['x'].attributes['A']` AND `references['y'].attributes['B']` on the referenced entity
creates two triggers — one per `DependencyKey` — both carrying the same full `FilterBy`), these
triggers can produce duplicate `IndexMutation` instances after `popMutations`. A deduplication
step must be introduced after `popMutations` (before dispatch to target collections) to avoid
processing the same mutation multiple times. While index operations are idempotent, the PK
resolution and `FilterBy` evaluation are not free — deduplication avoids redundant work.

See WBS-03a (AD-22) for context on why multiple triggers per expression are needed.

### Mutation Ordering Guarantee

Cross-entity triggers fire AFTER the source entity's mutations complete (same as `popImplicitMutations` external mutations). This means:

- Entity B's storage already reflects the NEW attribute value
- `WritableEntityStorageContainerAccessor` on entity A will see B's updated data
- Expression evaluation produces correct result based on new state

**Verification note:** integration tests must confirm that `DataStoreMemoryBuffer` makes B's pending changes visible to A's accessor when both are in the same transaction.

### Multiple Reference Types to Same Group (Hidden Trap #3)

When multiple reference types on the same (or different) entity schemas point to the same group entity type, the registry maps `(targetEntityType, dependencyType)` to **all** affected triggers. The expression must be re-evaluated per reference type, not just per entity. The composite key ensures that a change to a group entity fans out to every reference type that uses that group entity type.

### Initial Catalog Load / Cold Start

Data loaded from disk storage is already consistent with the schema (and its expressions) — indexes are persisted in their correct state. No expression re-evaluation is needed on load.

After `EntityCollection` instantiation, the catalog must **wire active trigger implementations between collections** according to their current schemas. This means populating the `CatalogExpressionTriggerRegistry` by scanning all loaded `ReferenceSchema` definitions that carry `facetedPartiallyInScopes` expressions — the same process as schema load time trigger building, just executed during catalog initialization.

New changes arriving after load go through WAL and the standard mutation pipeline, where `ReferenceIndexMutator`'s updated `isFaceted()` guard (with expression evaluation) handles them normally.

### Module Boundary

- `ReferenceSchema.facetedPartiallyInScopes` (`Map<Scope, Expression>`) lives in `evita_api` — declarative contract, pure data field, part of the public API
- `ExpressionIndexTrigger`, all trigger implementations, `DependencyType`, and `CatalogExpressionTriggerRegistry` live in `evita_engine` — operational engine internals, never part of the public API surface
- Triggers are built from the `Expression` at schema load/change time by `EntityCollection` (or a helper it delegates to) and registered in the registry
- `EntityCollection` has symmetric responsibility: it hosts both the **inbound** dispatcher (processes `IndexMutation` from other collections) and the **outbound** trigger infrastructure (generates `IndexMutation` from this collection's schema definitions)

## Key Interfaces

| Interface / Type | Package | Purpose |
|---|---|---|
| `CatalogExpressionTriggerRegistry` | `io.evitadb.core.catalog` | Catalog-level inverted index; maps `(mutatedEntityType, DependencyType)` to `List<ExpressionIndexTrigger>` |
| `ExpressionIndexTrigger` | `io.evitadb.index.mutation` | Generic base interface for expression-based index triggers (methods: `getOwnerEntityType()`, `getReferenceName()`, `getScope()`, `getDependencyType()`, `getDependentAttributes()`, `getFilterByConstraint()`, `evaluate()`) |
| `FacetExpressionTrigger` | `io.evitadb.index.mutation` | Type marker extending `ExpressionIndexTrigger` for conditional facet indexing |
| `HistogramExpressionTrigger` | `io.evitadb.index.mutation` | Type marker extending `ExpressionIndexTrigger` for conditional histogram indexing (future) |
| `DependencyType` | `io.evitadb.index.mutation` | Enum classifying cross-entity relationships: `REFERENCED_ENTITY_ATTRIBUTE`, `GROUP_ENTITY_ATTRIBUTE` |
| `AccessedDataFinder` | `evita_engine` | Analyzes expression paths to determine which entity types and attributes are read |

## Acceptance Criteria

1. **Interface compliance** — `CatalogExpressionTriggerRegistry` exposes all three methods (`getTriggersFor`, `getTriggersForAttribute`, `rebuildForEntityType`) with signatures matching the specification above. `rebuildForEntityType` accepts both the entity type and the pre-built trigger list.

2. **Inverted index correctness** — when a `ReferenceSchema` on entity type A references entity type B as group/referenced entity with a conditional expression, the trigger is indexed under B's entity type (not A's).

3. **Attribute-level filtering** — `getTriggersForAttribute()` returns only triggers whose `getDependentAttributes()` contains the specified attribute name, not all triggers for the entity type.

4. **Immutability** — `rebuildForEntityType()` returns a **new** registry instance; the original instance is unmodified and continues serving concurrent readers.

5. **ReflectedReferenceSchema support** — triggers are built for both direct and reflected reference schemas. When a source schema's expression changes, trigger rebuild cascades to all entity types with inheriting reflected references.

6. **Generic storage** — the registry stores all `ExpressionIndexTrigger` subtypes without type discrimination. Both `FacetExpressionTrigger` and `HistogramExpressionTrigger` instances coexist in the same index.

7. **Multiple reference types to same group** — when multiple reference types point to the same group entity type, all their triggers are returned by `getTriggersFor()` / `getTriggersForAttribute()`.

8. **Cold start wiring** — during catalog initialization, the registry is populated by scanning all loaded `ReferenceSchema` definitions with `facetedPartiallyInScopes` expressions, producing the same result as incremental schema-change-time registration.

9. **Empty results** — `getTriggersFor()` and `getTriggersForAttribute()` return empty lists (not null) when no triggers match.

10. **Thread safety** — the registry is safe for concurrent reads. Mutation is handled exclusively through `rebuildForEntityType()` producing a new instance (no in-place modification).

11. **DataStoreMemoryBuffer visibility** — integration tests verify that B's pending changes (within the same transaction) are visible to A's accessor during cross-entity trigger evaluation.

## Implementation Notes

- The concrete implementation should use an immutable map (or equivalent structure) keyed by `(String entityType, DependencyType)` with values of `List<ExpressionIndexTrigger>`. Consider a nested map: `Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>>` for O(1) lookup.
- `getTriggersForAttribute()` can be implemented as a filter over `getTriggersFor()` results, checking `trigger.getDependentAttributes().contains(attributeName)`. If performance becomes a concern (many triggers per entity type), consider a secondary index keyed by attribute name. **Behavioral note:** a trigger with an empty `dependentAttributes` set will never be returned by `getTriggersForAttribute()` (since `Set.contains()` on an empty set always returns `false`). This is the correct behavior — such a trigger has no cross-entity attribute dependencies and should only be retrievable via the broad `getTriggersFor()` lookup.
- `rebuildForEntityType(entityType, newTriggers)` must deep-copy all entries except those owned by the specified entity type, which are replaced with the provided `newTriggers`. The caller (`Catalog`) is responsible for scanning `ReferenceSchema` definitions, invoking `AccessedDataFinder`, and constructing trigger instances before calling this method.
- For `ReflectedReferenceSchema` cascade: `rebuildForEntityType("sourceEntityType")` should identify all entity types with reflected references inheriting from the source and rebuild their triggers as well. This may require access to the catalog's schema registry to discover inheriting reflected references.
- The detection step (caller of `getTriggersForAttribute()`) groups returned triggers by `getOwnerEntityType()` to generate one `EntityIndexMutation` per target collection.
- The registry does NOT resolve affected entity PKs — that is the target executor's responsibility using the target collection's own indexes (e.g., `ReducedGroupEntityIndex` for group entity reverse lookup).
- A reference with expressions in multiple scopes produces one trigger per scope (each trigger carries its `Scope` via `getScope()`).

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

> **Note:** Line numbers referenced below are accurate as of the current codebase state but may drift as other WBS items are implemented. When line numbers no longer match, locate the referenced code by method name or code pattern instead.

##### Key class locations

| Class / Interface | Absolute path |
|---|---|
| `Catalog` | `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java` |
| `EntityCollection` | `evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java` |
| `LocalMutationExecutorCollector` | `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java` |
| `ReferenceSchemaContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java` |
| `ReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReferenceSchema.java` |
| `ReflectedReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java` |
| `EntitySchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/EntitySchema.java` |
| `TransactionalReference` | `evita_engine/src/main/java/io/evitadb/index/reference/TransactionalReference.java` |
| `TransactionalMap` | `evita_engine/src/main/java/io/evitadb/index/map/TransactionalMap.java` |
| `AccessedDataFinder` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/AccessedDataFinder.java` |
| `PathItem` (sealed interface) | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/PathItem.java` |
| `Scope` enum | `evita_common/src/main/java/io/evitadb/dataType/Scope.java` |
| `CatalogRelatedDataStructure` | `evita_engine/src/main/java/io/evitadb/core/catalog/CatalogRelatedDataStructure.java` |
| `CatalogTest` | `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogTest.java` |

> **Note on `CatalogRelatedDataStructure`:** This interface was investigated but is not used for the registry. `CatalogRelatedDataStructure` is for data structures that need late initialization and catalog attachment propagation to child objects. The registry is a simpler case: a plain immutable POJO wrapped in `TransactionalReference`, with no children to propagate attachment to. Using `CatalogRelatedDataStructure` would add unnecessary lifecycle complexity.
| `module-info.java` (evita_engine) | `evita_engine/src/main/java/module-info.java` |

##### Catalog-level state management patterns

`Catalog` (line 179-180) is `final class Catalog implements CatalogContract, CatalogConsumersListener, TransactionalLayerProducer<DataStoreChanges, Catalog>`. It manages cross-collection state via:

- `TransactionalMap<String, EntityCollection> entityCollections` (line 194) — primary collection index by entity type
- `TransactionalMap<Integer, EntityCollection> entityCollectionsByPrimaryKey` (line 198) — secondary index by PK
- `TransactionalMap<String, EntitySchemaContract> entitySchemaIndex` (line 202) — schema lookup
- `TransactionalReference<CatalogSchemaDecorator> schema` (line 232) — catalog schema
- `TransactionalReference<Long> versionId` (line 185) — version counter

All are `final` fields initialized in constructors. `TransactionalReference<T>` (in `io.evitadb.index.reference`) wraps `AtomicReference<T>` with transactional layer support: within a transaction, writes go to a per-transaction layer; outside a transaction, writes go directly to the `AtomicReference`.

**Pattern for the registry:** The `CatalogExpressionTriggerRegistry` should be held as a `TransactionalReference<CatalogExpressionTriggerRegistry>` field on `Catalog`, following the exact pattern used for `schema` and `versionId`. Within a transaction, `rebuildForEntityType()` produces a new registry instance that is stored via `TransactionalReference.set()`, visible only to the current transaction. On commit, the transactional layer merges into the base reference. Outside transactions (warm-up state), `set()` writes directly to the `AtomicReference`.

##### Schema change propagation — how `EntityCollection.exchangeSchema()` notifies the catalog

The schema lifecycle follows this call chain (verified at lines 960-976 and 2060-2071 of `EntityCollection.java`):

1. `EntityCollection.updateSchema(EntitySchemaMutation[])` applies mutations to produce `updatedSchema`
2. `refreshReflectedSchemas(originalSchema, updatedSchema, updatedReferenceSchemas)` (line 970) resolves reflected reference inheritance — iterates updated references, and for each `ReflectedReferenceSchema`, calls `withReferencedSchema(originalReference)` to resolve inherited properties
3. If the schema version changed, `exchangeSchema(originalSchema, updatedSchema)` is called (line 976)
4. `exchangeSchema()` (lines 2060-2071) does:
   - `this.schema.compareAndExchange(...)` — atomic schema swap
   - `this.catalog.entitySchemaUpdated(updatedSchema)` — notifies catalog to update `entitySchemaIndex`

`Catalog.entitySchemaUpdated()` (line 1409-1411) simply does `this.entitySchemaIndex.put(entitySchema.getName(), entitySchema)`.

**Hook point for registry rebuild:** After `this.catalog.entitySchemaUpdated(updatedSchema)` in `exchangeSchema()` (line 2070), the catalog should rebuild the registry for the changed entity type. Alternatively, `entitySchemaUpdated()` itself could trigger the rebuild. The latter is cleaner — it centralizes the hook in `Catalog` rather than splitting it across `EntityCollection`.

##### Cross-collection schema cascade — `notifyAboutExternalReferenceUpdate()`

When entity A changes a reference schema that entity B has a `ReflectedReferenceSchema` for, the cascade works (lines 2007-2011):

1. `refreshReflectedSchemas()` finds non-reflected updated references where `isReferencedEntityTypeManaged() == true`
2. Calls `catalog.getCollectionForEntity(referencedEntityType).ifPresent(it -> ((EntityCollection) it).notifyAboutExternalReferenceUpdate(finalUpdatedSchema, updatedReference))`
3. `notifyAboutExternalReferenceUpdate()` (lines 2027-2051) iterates the target's schemas, finds `ReflectedReferenceSchema` instances matching the updated reference, calls `withReferencedSchema(updatedReferenceSchema)`, and calls `exchangeSchema()` on the target collection

This means when entity A's reference changes, entity B's reflected references are automatically updated AND `exchangeSchema()` fires on B — triggering the registry rebuild for B as well. **No additional cascade mechanism is needed in the registry itself** — the existing schema cascade already propagates through `notifyAboutExternalReferenceUpdate() -> exchangeSchema() -> entitySchemaUpdated()`.

##### Cold start / catalog initialization

During catalog loading (lines 488-498 of `Catalog.java`):

1. All `EntityCollection` instances are created and loaded from disk
2. `collection.attachToCatalog(null, catalog)` is called for each collection
3. `collection.initSchema()` is called for each collection — this resolves `ReflectedReferenceSchema` inheritance
4. After all `initSchema()` calls complete, all schemas are fully resolved

In the copy constructor (lines 711-784), the pattern is the same: collections are attached, then `initSchema()` is called if `initSchemas == true`.

**Registry initialization hook:** After all `initSchema()` calls complete (line 493-494), the catalog should build the initial registry by scanning all collections' schemas. This is a full rebuild — iterate all entity types, all their reference schemas, extract `facetedPartiallyInScopes` entries, and index them.

##### `EntitySchema.getReferences()` — iterating reference schemas

`EntitySchema.getReferences()` (line 879) returns `Map<String, ReferenceSchemaContract>` containing both direct `ReferenceSchema` and `ReflectedReferenceSchema` instances. After `initSchema()`, reflected schemas have their inheritance resolved via `withReferencedSchema()`.

`ReferenceSchemaContract` provides:
- `getName()` — reference name
- `getReferencedEntityType()` — the entity type this reference points to (e.g., "parameter")
- `getReferencedGroupType()` — the group entity type, nullable (e.g., "parameterGroup")
- `getFacetedPartiallyInScopes()` — `Map<Scope, Expression>`, empty if no conditional faceting
- `isFacetedInScope(Scope)` — boolean, faceted status per scope

##### `ReflectedReferenceSchema` — `facetedPartially` inheritance

`ReflectedReferenceSchema.withReferencedSchema()` (lines 1696-1784) resolves inheritance. For `facetedPartially`:
- Line 1739-1741: `this.facetedInherited ? originalReference.getFacetedPartiallyInScopes() : this.facetedPartiallyInScopes`
- When `facetedInherited == true`, the reflected schema's `getFacetedPartiallyInScopes()` returns the source schema's expressions

This means **trigger construction does NOT need special handling for reflected schemas** — calling `referenceSchema.getFacetedPartiallyInScopes()` on a fully-resolved `ReflectedReferenceSchema` already returns the inherited expressions. The cascade through `notifyAboutExternalReferenceUpdate()` ensures reflected schemas are updated when the source changes.

##### Immutability pattern — copy-on-write via `TransactionalReference`

The registry follows the same immutability pattern as `TransactionalReference<CatalogSchemaDecorator> schema`:

1. `TransactionalReference.set(newValue)` — within a transaction, stores to the transactional layer; outside, stores directly to `AtomicReference`
2. `TransactionalReference.get()` — within a transaction, reads from the transactional layer (if present); otherwise reads the base `AtomicReference`
3. On commit, `createCopyWithMergedTransactionalMemory()` merges the transactional layer into the base reference

The registry itself is a plain immutable POJO. `rebuildForEntityType()` constructs a **new instance** and returns it. The caller stores it via `TransactionalReference.set()`. No synchronization is needed inside the registry — all mutation happens at the reference-swap level.

##### Module boundary and package placement

- `io.evitadb.core.catalog` is exported in `module-info.java` (line 34) — `CatalogExpressionTriggerRegistry` interface should be here since it is referenced by `Catalog` (a public class in an exported package)
- The **concrete implementation** should also be in `io.evitadb.core.catalog` — it is instantiated by `Catalog` directly, and the package is already exported (no additional `module-info` changes needed)
- `ExpressionIndexTrigger`, `FacetExpressionTrigger`, `DependencyType` live in `io.evitadb.index.mutation` per WBS-03 — this package is NOT exported (engine-internal), which is correct
- Since `CatalogExpressionTriggerRegistry` references `ExpressionIndexTrigger` (from unexported `io.evitadb.index.mutation`), this is fine because both are within `evita_engine` — intra-module references don't require exports

##### `Catalog` constructor propagation

The Catalog has two constructor patterns:
1. **Primary constructor** (lines 505-697, not shown in full): from-scratch construction during `loadCatalog()` — initializes all fields, creates `TransactionalMap`/`TransactionalReference` wrappers
2. **Copy constructor** (lines 711-784): from a previous catalog version — copies persistent state, re-wraps collections, re-attaches to catalog

A new `TransactionalReference<CatalogExpressionTriggerRegistry>` field must be:
- Initialized in the primary constructor (initially empty registry)
- Propagated in the copy constructor (from `previousCatalogVersion`)
- Included in `removeLayer()` (line 1432-1442) — add `this.expressionTriggerRegistry.removeLayer(transactionalLayer)`, following the pattern of the `schema` field (also a `TransactionalReference` that is included in `removeLayer()`). Note: the `versionId` field is a `TransactionalReference` that is NOT in `removeLayer()`, but the registry should be because it can be set within a transaction and needs layer cleanup on rollback.
- Included in `createCopyWithMergedTransactionalMemory()` (line 1446+) — merge the registry's transactional layer

##### `LocalMutationExecutorCollector` — how cross-entity mutations reach target collections

`LocalMutationExecutorCollector.applyMutations()` (lines 250-281) processes external mutations via:
```
this.catalog.getCollectionForEntityOrThrowException(externalEntityMutations.getEntityType())
    .applyMutations(session, externalEntityMutations, ...)
```

This is the exact pattern the detection step (post-processing in `EntityIndexLocalMutationExecutor`) will use to access the registry — it already has a reference to `Catalog` via `EntityCollection.catalog`, and from there can call `catalog.getExpressionTriggerRegistry().getTriggersForAttribute(...)`.

##### Inverted index key design

The WBS specifies a composite key `(String mutatedEntityType, DependencyType)`. Looking at the usage patterns:

1. Detection step knows: `mutatedEntityType` (from the mutation being processed), `DependencyType` (from the mutation type — attribute change on referenced entity vs. group entity), and optionally `attributeName`
2. Lookup must be O(1) for `(entityType, dependencyType)` and then linear scan (or secondary index) for attribute filtering

**Recommended data structure:** `Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>>` — nested map keyed first by entity type (String), then by `DependencyType` enum. This gives O(1) for both levels. `getTriggersForAttribute()` filters the list by `trigger.getDependentAttributes().contains(attributeName)`. Since `DependencyType` is an enum with only 2 values, the inner map can be an `EnumMap<DependencyType, List<ExpressionIndexTrigger>>` for optimal performance.

##### Entity removal handling

When an entity is removed (`EntityRemoveMutation`), ALL dependent expressions must be re-evaluated (not just attribute-specific ones). The detection step should call `getTriggersFor(entityType, dependencyType)` (without attribute filtering) to get ALL triggers for that entity type. This is already specified in the interface — `getTriggersFor()` returns all triggers regardless of attribute.

---

#### Detailed Task List

##### Group 1: `CatalogExpressionTriggerRegistry` interface

- [ ] **1.1** Create `CatalogExpressionTriggerRegistry` interface in `evita_engine/src/main/java/io/evitadb/core/catalog/CatalogExpressionTriggerRegistry.java` in package `io.evitadb.core.catalog`. Define all three methods exactly as specified in the "Full Interface Definition" section:
  - `@Nonnull List<ExpressionIndexTrigger> getTriggersFor(@Nonnull String mutatedEntityType, @Nonnull DependencyType dependencyType)` — broad lookup returning all triggers for a given entity type and dependency relationship
  - `@Nonnull List<ExpressionIndexTrigger> getTriggersForAttribute(@Nonnull String mutatedEntityType, @Nonnull DependencyType dependencyType, @Nonnull String attributeName)` — selective lookup filtering by dependent attribute name
  - `@Nonnull CatalogExpressionTriggerRegistry rebuildForEntityType(@Nonnull String entityType, @Nonnull List<ExpressionIndexTrigger> newTriggers)` — immutable rebuild returning a new registry instance; the caller builds the trigger list and passes it in

- [ ] **1.2** Write complete JavaDoc for `CatalogExpressionTriggerRegistry` covering: its role as a catalog-level inverted index, the ownership inversion pattern (schema A defines the expression, but the trigger is indexed under schema B's entity type), the copy-on-write immutability principle, and the thread-safety guarantee (safe for concurrent reads, mutation only through `rebuildForEntityType()` producing a new instance).

- [ ] **1.3** Add an `EMPTY` singleton constant to the interface: `CatalogExpressionTriggerRegistry EMPTY = new CatalogExpressionTriggerRegistryImpl(Collections.emptyMap())` — or a dedicated empty implementation. This serves as the initial value before any schemas are loaded.

##### Group 2: Concrete registry implementation — data structure

- [ ] **2.1** Create `CatalogExpressionTriggerRegistryImpl` class in `evita_engine/src/main/java/io/evitadb/core/catalog/CatalogExpressionTriggerRegistryImpl.java` implementing `CatalogExpressionTriggerRegistry`. The class should be package-private, immutable, and `@ThreadSafe`. Primary field: `@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> triggerIndex` — a nested immutable map.

- [ ] **2.2** Constructor takes the pre-built `triggerIndex` map and stores a deep-immutable copy (using `Collections.unmodifiableMap()` on the outer map, `Collections.unmodifiableMap()` on each inner `EnumMap`, and `Collections.unmodifiableList()` on each trigger list).

- [ ] **2.3** Implement `getTriggersFor(String mutatedEntityType, DependencyType dependencyType)`:
  1. Look up `triggerIndex.get(mutatedEntityType)` — if null, return `Collections.emptyList()`
  2. Look up inner map `innerMap.get(dependencyType)` — if null, return `Collections.emptyList()`
  3. Return the (already unmodifiable) list

- [ ] **2.4** Implement `getTriggersForAttribute(String mutatedEntityType, DependencyType dependencyType, String attributeName)`:
  1. Call `getTriggersFor(mutatedEntityType, dependencyType)` to get the full list
  2. If empty, return `Collections.emptyList()`
  3. Filter the list: iterate and collect triggers where `trigger.getDependentAttributes().contains(attributeName)`
  4. Return the filtered list (may be empty). Use allocation-optimized loop per code-style rules (avoid streams). Pre-allocate the result list with the source list size as initial capacity (`new ArrayList<>(triggers.size())`) to avoid resize overhead; the small over-allocation is acceptable for this lookup path.

##### Group 3: Immutable rebuild mechanism — `rebuildForEntityType()`

- [ ] **3.1** `rebuildForEntityType()` is a **pure function** on the registry interface — it receives both the entity type and the pre-built trigger list as parameters, and returns a new registry instance. It does NOT internally access the catalog or any external state. The actual rebuild orchestration lives in `Catalog.rebuildExpressionTriggerRegistryForEntityType()`, which:
  1. Calls `FacetExpressionTriggerFactory.buildTriggersForReference()` (from WBS-03) for each reference schema on the specified entity type
  2. Calls `currentRegistry.rebuildForEntityType(entityType, newTriggers)` on the interface
  3. Stores the new registry via `this.expressionTriggerRegistry.set(newRegistry)`

- [ ] **3.2** Implement `rebuildForEntityType(String entityType, List<ExpressionIndexTrigger> newTriggers)` on `CatalogExpressionTriggerRegistryImpl` to perform the copy-and-replace:
  1. Deep-copy the current registry's trigger index
  2. Remove all entries where any trigger has `getOwnerEntityType().equals(entityType)` — this cleans out stale triggers from the rebuilt entity type. **Important:** iterate ALL keys (mutated entity types) because the rebuilt entity type's references may have produced triggers indexed under different mutated entity types (e.g., Product references ParameterGroup — rebuilding Product must remove the trigger from the "parameterGroup" key)
  3. Insert the new triggers under their respective `(mutatedEntityType, dependencyType)` keys (derived from each trigger's metadata — which is the mutated entity type, not the owner entity type)
  4. Wrap in unmodifiable collections and return new `CatalogExpressionTriggerRegistryImpl`

- [ ] **3.3** Handle the key inversion correctly in the rebuild: triggers from a `ReferenceSchema` on entity type A (owner) that reference entity type B (referenced/group entity) must be indexed under entity type B (the mutated entity type). The `mutatedEntityType` for a trigger is:
  - For `DependencyType.REFERENCED_ENTITY_ATTRIBUTE`: `referenceSchema.getReferencedEntityType()`
  - For `DependencyType.GROUP_ENTITY_ATTRIBUTE`: `referenceSchema.getReferencedGroupType()`
  The factory method in WBS-03 (`FacetExpressionTriggerFactory`) already classifies this — the registry just needs to use the trigger's dependency metadata to determine the index key.

- [ ] **3.4** **Self-referencing entity type edge case:** when an entity type references itself (e.g., Product references Product as its group entity), the owner entity type and the mutated entity type are the same. The rebuild algorithm handles this correctly because step 1 removes stale triggers by owner entity type, and step 3 inserts new triggers under the mutated entity type key. When both are the same, the old triggers are cleaned from the key and new triggers are inserted under the same key. Triggers from other owners under the same key are preserved (step 1 only removes triggers matching the owner). This must be verified by a dedicated test case (see Category 6).

##### Group 4: Full registry build from all schemas (cold start)

- [ ] **4.1** Create a static factory method `CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map<String, EntitySchemaContract> entitySchemaIndex): CatalogExpressionTriggerRegistry` that:
  1. Creates an empty mutable `Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>>`
  2. Iterates all entity schemas in `entitySchemaIndex.values()`
  3. For each entity schema, iterates `schema.getReferences().values()`
  4. For each reference schema, calls `FacetExpressionTriggerFactory.buildTriggersForReference(schema.getName(), referenceSchema)` (from WBS-03)
  5. For each returned trigger, determines the inverted key: `(mutatedEntityType, dependencyType)` — where `mutatedEntityType` is derived from the reference schema (not the owning entity type)
  6. Inserts the trigger into the map under that key
  7. Wraps in unmodifiable collections and returns new `CatalogExpressionTriggerRegistryImpl`

  **Extensibility note:** this method currently only builds facet triggers via `FacetExpressionTriggerFactory`. When `HistogramExpressionTrigger` support is added (for `bucketedPartially` expressions), this method must be extended to also call the histogram trigger factory for each reference schema and merge results into the same index. The registry data structure already supports this — it stores all `ExpressionIndexTrigger` subtypes in the same lists. No structural changes to the registry will be needed, only an additional factory call here and in `Catalog.rebuildExpressionTriggerRegistryForEntityType()`.

- [ ] **4.2** Handle references where `getReferencedGroupType()` is null — these references cannot produce `GROUP_ENTITY_ATTRIBUTE` triggers. Skip silently (no error).

- [ ] **4.3** Handle references where `getFacetedPartiallyInScopes()` returns an empty map — these references have no conditional faceting. Skip silently (the factory returns an empty list).

- [ ] **4.4** Handle multiple reference types on the same or different entity schemas pointing to the same group entity type: the map key `(mutatedEntityType, dependencyType)` naturally accumulates all triggers in a list. Verify in a test that `getTriggersFor("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns triggers from BOTH `Product.parameter` and `Product.altParameter` if both reference the same group entity type with conditional expressions.

##### Group 5: `ReflectedReferenceSchema` cascade — verifying existing propagation

- [ ] **5.1** Verify (in a test) that the existing cascade through `notifyAboutExternalReferenceUpdate() -> exchangeSchema() -> entitySchemaUpdated()` correctly triggers registry rebuilds when a source schema's `facetedPartially` expression changes. The test should:
  1. Set up entity A with a reference to entity B, with a `facetedPartially` expression
  2. Set up entity C with a `ReflectedReferenceSchema` that inherits `facetedPartially` from A's reference
  3. Change A's `facetedPartially` expression
  4. Verify that both A's and C's triggers are rebuilt in the registry

- [ ] **5.2** Document that the registry does NOT need its own cascade mechanism for `ReflectedReferenceSchema` — the existing `EntityCollection.notifyAboutExternalReferenceUpdate()` already cascades schema changes to all collections with reflected references. Each `exchangeSchema()` call triggers `entitySchemaUpdated()`, which triggers the registry rebuild for that entity type. The cascade is transitive and complete.

##### Group 6: Catalog integration — field, accessors, lifecycle hooks

- [ ] **6.1** Add a `TransactionalReference<CatalogExpressionTriggerRegistry> expressionTriggerRegistry` field to `Catalog` (after line 232, alongside the other `TransactionalReference` fields). Initialize with `new TransactionalReference<>(CatalogExpressionTriggerRegistry.EMPTY)`.

- [ ] **6.2** Add a public accessor `@Nonnull CatalogExpressionTriggerRegistry getExpressionTriggerRegistry()` to `Catalog` that returns `Objects.requireNonNull(this.expressionTriggerRegistry.get())`.

- [ ] **6.3** Propagate the `expressionTriggerRegistry` field in the copy constructor (lines 711-784): assign `this.expressionTriggerRegistry = new TransactionalReference<>(previousCatalogVersion.getExpressionTriggerRegistry())` — the new catalog version starts with the previous version's registry.

- [ ] **6.4** Add `this.expressionTriggerRegistry.removeLayer(transactionalLayer)` to `Catalog.removeLayer()` (after line 1441). **Note:** not all `TransactionalReference` fields are currently in `removeLayer()` — the `versionId` field is excluded, likely because it is managed exclusively through `setVersion()` within transactional context and merged via `getStateCopyWithCommittedChanges()` in `createCopyWithMergedTransactionalMemory()`. The registry should be in `removeLayer()` because, unlike `versionId`, the registry can be set within a transaction and must have its layer cleaned up if the transaction is rolled back. This follows the pattern of the `schema` field (also a `TransactionalReference`) which IS included in `removeLayer()`.

- [ ] **6.5** In `Catalog.createCopyWithMergedTransactionalMemory()` (line 1446+): ensure the registry's transactional layer is merged. Since `TransactionalReference` handles this automatically via `getStateCopyWithCommittedChanges()`, the registry reference just needs to be included in the new Catalog's field initialization (same as `versionId` and `schema`).

##### Group 7: Schema change hook — triggering rebuild on `entitySchemaUpdated()`

- [ ] **7.1** Modify `Catalog.entitySchemaUpdated(EntitySchemaContract entitySchema)` (line 1409-1411) to trigger a registry rebuild after updating the schema index:
  ```java
  public void entitySchemaUpdated(@Nonnull EntitySchemaContract entitySchema) {
      this.entitySchemaIndex.put(entitySchema.getName(), entitySchema);
      rebuildExpressionTriggerRegistryForEntityType(entitySchema.getName());
  }
  ```
  The `rebuildExpressionTriggerRegistryForEntityType()` method:
  1. Gets the current registry via `this.expressionTriggerRegistry.get()`
  2. Gets the updated entity schema via `this.entitySchemaIndex.get(entityType)`
  3. For each reference schema in the entity schema, calls `FacetExpressionTriggerFactory.buildTriggersForReference()` to build the trigger list
  4. Calls `currentRegistry.rebuildForEntityType(entityType, newTriggers)` — the interface method, which returns a new registry instance
  5. Stores the new registry via `this.expressionTriggerRegistry.set(newRegistry)`

- [ ] **7.2** Handle `entitySchemaRemoved(String entityType)` (line 1418-1420): when a collection is removed, all triggers owned by that entity type must be removed from the registry. Call `currentRegistry.rebuildForEntityType(entityType, Collections.emptyList())` to clean up.

- [ ] **7.3** Verify that `entitySchemaUpdated()` is called AFTER `refreshReflectedSchemas()` has resolved inheritance. Looking at the call chain: `updateSchema() -> refreshReflectedSchemas() -> exchangeSchema() -> entitySchemaUpdated()` — YES, by the time `entitySchemaUpdated()` fires, the schema passed as argument already has fully-resolved reflected references. The registry rebuild sees the correct inherited expressions.

##### Group 8: Cold start hook — initial registry build

- [ ] **8.1** After all `initSchema()` calls complete during catalog loading (line 493-494 of `Catalog.java`), call `buildInitialExpressionTriggerRegistry()`:
  ```java
  for (EntityCollection collection : initBulk.collections().values()) {
      collection.initSchema();
  }
  buildInitialExpressionTriggerRegistry();
  ```
  This method:
  1. Calls `CatalogExpressionTriggerRegistryImpl.buildFromSchemas(this.entitySchemaIndex)` to build the full registry
  2. Stores via `this.expressionTriggerRegistry.set(fullRegistry)`

- [ ] **8.2** Apply the same pattern in the copy constructor (lines 776-783): after all `initSchema()` calls, rebuild the registry. This handles the `goingLive()` transition where collections are re-created with new catalog state.

- [ ] **8.3** Ensure that during warm-up state (no transactions), `TransactionalReference.set()` writes directly to the `AtomicReference` (line 74-75 of `TransactionalReference.java` confirms: `if (layer == null) { this.value.set(value); }`) — no transactional overhead during initialization.

##### Group 9: Entity collection removal — cleaning up registry

- [ ] **9.1** When `Catalog.removeEntityCollection()` is called, triggers owned by the removed entity type must be purged from the registry. Add a registry rebuild call in the removal logic (around line 2033-2040 of `Catalog.java`).

- [ ] **9.2** When `Catalog.renameEntityCollectionInternal()` is called (lines 2101+), the renamed collection's triggers must be reconstructed under the new owner entity type name. The rename process works as follows:
  1. `doReplaceEntityCollectionInternal()` calls `updateSchema()` on the renamed collection with a `ModifyEntitySchemaNameMutation`, which updates the entity schema to use the new name
  2. `notifyEntityTypeRenamed()` cascades to other collections, updating their reference schemas that point to the renamed entity type. Each updated collection calls `exchangeSchema() -> entitySchemaUpdated()`, triggering a registry rebuild for that collection
  3. **However**, the renamed collection's own triggers (with `getOwnerEntityType() == oldName`) must be explicitly cleaned up. The rename handler must:
     - First, remove stale triggers from the old name: `currentRegistry.rebuildForEntityType(oldName, Collections.emptyList())`
     - Then, rebuild triggers for the new name: `updatedRegistry.rebuildForEntityType(newName, newTriggersFromUpdatedSchema)`
  4. **Trigger reconstruction:** Since `ExpressionIndexTrigger` instances are immutable with `getOwnerEntityType()` baked in, the `FacetExpressionTriggerFactory` must construct entirely new trigger instances with the new owner entity type — the old triggers cannot be reused.
  5. **References TO the renamed entity type** also need trigger updates: if entity "category" references the old name as group/referenced entity, after `notifyEntityTypeRenamed()` updates category's reference schema to point to the new name, the category's registry rebuild (triggered by `entitySchemaUpdated()`) will produce triggers indexed under the new mutated entity type name. The old triggers indexed under the old name are cleaned by step 3 above.

##### Group 10: Integration readiness

- [ ] **10.1** Verify that `CatalogExpressionTriggerRegistry` can be accessed from `EntityIndexLocalMutationExecutor` — the executor has access to `EntityCollection` which has `this.catalog`, and `catalog.getExpressionTriggerRegistry()` provides the registry. Document this access path.

- [ ] **10.2** Verify that the `buildFromSchemas()` method produces the same result as incremental rebuilds: set up schemas, build via `buildFromSchemas()`, then build via incremental `rebuildForEntityType()` for each entity type, and assert the two registries contain equivalent trigger sets.

- [ ] **10.3** Document the integration contract for downstream WBS tasks:
  - **Detection step (WBS for `EntityIndexLocalMutationExecutor` post-processing):** calls `catalog.getExpressionTriggerRegistry().getTriggersForAttribute(mutatedEntityType, dependencyType, attributeName)`, groups results by `getOwnerEntityType()`, generates one `EntityIndexMutation` per target collection
  - **Entity removal:** calls `catalog.getExpressionTriggerRegistry().getTriggersFor(removedEntityType, dependencyType)` (all triggers, not attribute-filtered) to re-evaluate all dependent expressions
  - **Schema change (WBS for executor registry):** schema mutations automatically trigger registry rebuild via `entitySchemaUpdated()` — no manual registry management needed by downstream consumers

### Test Cases

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogExpressionTriggerRegistryImplTest.java`

#### Category 1: Empty Registry Behavior

- [ ] `empty_registry_getTriggersFor_returns_empty_list` — calling `getTriggersFor()` on `EMPTY` registry with any entity type and any `DependencyType` returns an empty (non-null) list
- [ ] `empty_registry_getTriggersForAttribute_returns_empty_list` — calling `getTriggersForAttribute()` on `EMPTY` registry with any entity type, dependency type, and attribute name returns an empty (non-null) list
- [ ] `empty_registry_rebuildForEntityType_with_empty_triggers_returns_empty_registry` — calling `rebuildForEntityType(entityType, emptyList)` on `EMPTY` registry produces a new registry instance that also returns empty lists for all lookups

#### Category 2: `getTriggersFor()` — Broad Lookup

- [ ] `getTriggersFor_single_trigger_returns_singleton_list` — registry with one trigger indexed under `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns a list containing exactly that trigger for the matching key
- [ ] `getTriggersFor_multiple_triggers_same_key_returns_all` — registry with three triggers indexed under the same `(entityType, dependencyType)` key returns all three triggers in the result list
- [ ] `getTriggersFor_non_matching_entity_type_returns_empty` — registry with triggers for "parameterGroup" returns empty list when queried with "brand" (non-existent entity type)
- [ ] `getTriggersFor_non_matching_dependency_type_returns_empty` — registry with a trigger under `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns empty list when queried with `("parameterGroup", REFERENCED_ENTITY_ATTRIBUTE)`
- [ ] `getTriggersFor_different_entity_types_isolated` — registry with triggers for "parameterGroup" and "brand" returns only "parameterGroup" triggers when queried for "parameterGroup", and vice versa
- [ ] `getTriggersFor_both_dependency_types_independent` — registry with triggers under both `GROUP_ENTITY_ATTRIBUTE` and `REFERENCED_ENTITY_ATTRIBUTE` for the same entity type returns only the triggers matching the queried dependency type

#### Category 3: `getTriggersForAttribute()` — Attribute-Filtered Lookup

- [ ] `getTriggersForAttribute_matching_attribute_returns_trigger` — trigger with `dependentAttributes={"inputWidgetType"}` is returned when querying for attribute "inputWidgetType"
- [ ] `getTriggersForAttribute_non_matching_attribute_returns_empty` — trigger with `dependentAttributes={"inputWidgetType"}` is NOT returned when querying for attribute "displayOrder", even though `getTriggersFor()` would return it
- [ ] `getTriggersForAttribute_multiple_triggers_only_matching_returned` — registry has three triggers under the same key: trigger A depends on {"inputWidgetType"}, trigger B depends on {"displayOrder"}, trigger C depends on {"inputWidgetType", "displayOrder"}; querying for "inputWidgetType" returns only A and C
- [ ] `getTriggersForAttribute_trigger_with_multiple_dependent_attributes_matches_any` — trigger with `dependentAttributes={"attr1", "attr2", "attr3"}` is returned when querying for any one of "attr1", "attr2", or "attr3"
- [ ] `getTriggersForAttribute_entity_type_mismatch_returns_empty_despite_attribute_match` — trigger under "parameterGroup" with `dependentAttributes={"inputWidgetType"}` is NOT returned when querying for `("brand", GROUP_ENTITY_ATTRIBUTE, "inputWidgetType")`

#### Category 4: Returned List Immutability

- [ ] `getTriggersFor_returned_list_is_unmodifiable` — attempting to call `add()` or `remove()` on the list returned by `getTriggersFor()` throws `UnsupportedOperationException`
- [ ] `getTriggersForAttribute_returned_list_is_unmodifiable` — attempting to call `add()` on the list returned by `getTriggersForAttribute()` throws `UnsupportedOperationException`

#### Category 5: `rebuildForEntityType()` — Immutability and Copy-on-Write

- [ ] `rebuildForEntityType_returns_new_instance` — the registry returned by `rebuildForEntityType()` is a different object reference from the original (`assertNotSame`)
- [ ] `rebuildForEntityType_original_is_unmodified` — after rebuilding entity type "product" with new triggers, querying the ORIGINAL registry returns the old triggers (verifies true immutability, not just a new wrapper)
- [ ] `rebuildForEntityType_new_instance_reflects_changes` — after rebuilding entity type "product" with updated triggers, querying the NEW registry returns the updated triggers
- [ ] `rebuildForEntityType_preserves_other_entity_types` — registry has triggers for both "product" and "category"; rebuilding "product" preserves all "category" triggers unchanged in the new instance
- [ ] `rebuildForEntityType_with_empty_triggers_removes_entity_type` — rebuilding entity type "product" with an empty trigger list produces a registry where `getTriggersFor()` for all keys previously contributed by "product" returns empty lists

#### Category 6: Key Inversion Correctness in Rebuild

- [ ] `rebuildForEntityType_indexes_under_mutated_entity_type_not_owner` — entity type "product" (owner) has a reference to "parameterGroup" (group); after rebuild, triggers appear under key `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)`, NOT under key `("product", ...)`
- [ ] `rebuildForEntityType_cleans_stale_triggers_from_all_keys` — entity type "product" previously had triggers indexed under both "parameterGroup" and "brand" (two references to different entity types); rebuilding "product" with only the "parameterGroup" reference removes stale triggers from the "brand" key
- [ ] `rebuildForEntityType_does_not_remove_triggers_from_other_owners` — entity types "product" and "category" both have references to "parameterGroup"; rebuilding "product" only removes/replaces "product"-owned triggers from the "parameterGroup" key while preserving "category"-owned triggers
- [ ] `rebuildForEntityType_self_referencing_entity_type_handled_correctly` — entity type "product" references itself as a group entity (self-reference); rebuilding "product" correctly removes old "product"-owned triggers from the "product" key and inserts new triggers under the same key. Triggers from other owners (e.g., "category" also referencing "product" as group) under the "product" key are preserved (task 3.4)

#### Category 7: `buildFromSchemas()` — Cold Start Full Build

- [ ] `buildFromSchemas_empty_schema_index_produces_empty_registry` — passing an empty `Map<String, EntitySchemaContract>` produces a registry equivalent to `EMPTY`
- [ ] `buildFromSchemas_schema_without_facetedPartially_produces_no_triggers` — entity schema with references that have empty `getFacetedPartiallyInScopes()` produces no triggers in the registry
- [ ] `buildFromSchemas_single_reference_with_expression_in_live_scope` — entity "product" has reference "parameter" with a `facetedPartially` expression in `LIVE` scope; registry contains one trigger indexed under the referenced entity type with the correct scope
- [ ] `buildFromSchemas_reference_with_expressions_in_both_scopes_produces_two_triggers` — reference "parameter" has expressions in both `LIVE` and `ARCHIVED` scopes; registry contains two triggers (one per scope) under the same `(mutatedEntityType, dependencyType)` key. Additionally verify that both `getTriggersFor()` and `getTriggersForAttribute()` return BOTH scope-specific triggers in the same result list (scope is a property on the trigger, not a registry key dimension)
- [ ] `buildFromSchemas_multiple_schemas_multiple_references` — three entity schemas with various references (some conditional, some not); verify only conditional references produce triggers and each is indexed under the correct mutated entity type
- [ ] `buildFromSchemas_reference_without_group_type_skips_group_triggers` — reference with `getReferencedGroupType() == null` does not produce `GROUP_ENTITY_ATTRIBUTE` triggers (no error, no null key)
- [ ] `buildFromSchemas_produces_same_result_as_incremental_rebuilds` — build registry via `buildFromSchemas()` for a set of schemas; separately build an empty registry and incrementally call `rebuildForEntityType()` for each entity type; assert both registries contain equivalent trigger sets (Acceptance Criterion 8 / task 10.2)

#### Category 8: Multiple Reference Types to Same Group Entity

- [ ] `multiple_references_to_same_group_all_triggers_returned` — entity "product" has two references ("parameter" and "altParameter") both pointing to group type "parameterGroup" with conditional expressions; `getTriggersFor("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns triggers from BOTH references (task 4.4 / Acceptance Criterion 7)
- [ ] `multiple_references_to_same_group_attribute_filtering_works_per_trigger` — "parameter" depends on attribute "inputWidgetType", "altParameter" depends on attribute "displayOrder"; `getTriggersForAttribute("parameterGroup", GROUP_ENTITY_ATTRIBUTE, "inputWidgetType")` returns only the "parameter" trigger

#### Category 9: Cross-Schema References (Different Owner Entity Types, Same Target)

- [ ] `different_owners_same_target_both_triggers_returned` — entity "product" references group "parameterGroup" and entity "category" also references group "parameterGroup"; `getTriggersFor("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns triggers from both "product" and "category"
- [ ] `different_owners_same_target_rebuild_one_preserves_other` — rebuilding "product" does not affect "category"-owned triggers under the shared "parameterGroup" key

#### Category 10: `ReflectedReferenceSchema` Handling

- [ ] `reflected_reference_inheriting_expression_produces_trigger` — entity A defines reference to B with `facetedPartially`; entity C has a `ReflectedReferenceSchema` that inherits `facetedPartially` from A's reference (after `withReferencedSchema()` resolution); `buildFromSchemas()` produces triggers for BOTH A's direct reference and C's reflected reference
- [ ] `reflected_reference_with_own_expression_uses_own_not_inherited` — entity C has a `ReflectedReferenceSchema` with `facetedInherited == false` and its own `facetedPartiallyInScopes` expression; the trigger built for C uses C's own expression, not A's

#### Category 11: Schema Change Cascade via `entitySchemaUpdated()`

- [ ] `entitySchemaUpdated_triggers_registry_rebuild_for_changed_entity_type` — modifying entity type "product"'s reference schema and calling `entitySchemaUpdated()` results in the registry being rebuilt for "product"; verify via `getTriggersFor()` that the new trigger set reflects the schema change
- [ ] `entitySchemaUpdated_does_not_affect_other_entity_types` — rebuilding registry for "product" via schema update does not alter triggers owned by "category"
- [ ] `entitySchemaRemoved_purges_triggers_for_removed_entity_type` — after calling the removal hook for entity type "product", all triggers owned by "product" are gone from the registry (task 7.2 / 9.1)

#### Category 12: Entity Collection Removal and Rename

- [ ] `removeEntityCollection_cleans_registry` — removing entity collection "product" purges all triggers where `ownerEntityType == "product"` from every key in the registry (task 9.1)
- [ ] `renameCollection_reindexes_triggers_under_new_name` — renaming entity type from "product" to "item": (1) triggers previously owned by "product" are removed from the registry, (2) new triggers with `ownerEntityType == "item"` are built from the renamed schema and inserted, (3) `getTriggersFor()` with the old "product" owner yields no triggers, (4) queries keyed by the new entity type return the reconstructed trigger set. Triggers from other entity types that reference the renamed entity (e.g., as group) are also re-indexed under the new mutated entity type key via the schema cascade (task 9.2)

#### Category 13: Thread Safety

- [ ] `concurrent_reads_on_immutable_registry_are_safe` — spawn N threads that concurrently call `getTriggersFor()` and `getTriggersForAttribute()` on the same registry instance; all threads receive correct, consistent results with no exceptions (Acceptance Criterion 10)
- [ ] `rebuild_does_not_affect_concurrent_readers_of_original` — one thread rebuilds the registry (producing a new instance) while N other threads continuously read from the ORIGINAL instance; readers always see the old (pre-rebuild) data, never partially-built state
- [ ] `concurrent_rebuild_via_transactional_reference_and_read` — thread A calls `Catalog.rebuildExpressionTriggerRegistryForEntityType()` (which sets the `TransactionalReference`) while threads B and C concurrently read via `catalog.getExpressionTriggerRegistry()`; readers outside the transaction see the pre-rebuild registry, while the rebuilding thread's transaction sees the new registry. This verifies the end-to-end thread-safety story through the `TransactionalReference` wrapper.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogExpressionTriggerRegistryIntegrationTest.java`

#### Category 14: `TransactionalReference` Integration

- [ ] `within_transaction_set_registry_is_visible_to_same_transaction` — within a mock transaction, call `expressionTriggerRegistry.set(newRegistry)`; verify `expressionTriggerRegistry.get()` returns `newRegistry` within that transaction (task 10.5)
- [ ] `outside_transaction_set_updates_base_reference` — outside any transaction, call `expressionTriggerRegistry.set(newRegistry)`; verify `expressionTriggerRegistry.get()` immediately returns `newRegistry` (task 10.5 / 8.3)
- [ ] `committed_transaction_makes_registry_visible_to_subsequent_readers` — set a new registry within a transaction, commit, then verify a new reader (outside the transaction) sees the committed registry (task 10.5)

#### Category 15: Cold Start Initialization Lifecycle

- [ ] `catalog_initialization_builds_registry_after_all_initSchema_calls` — simulate catalog loading with multiple entity collections carrying conditional expressions; after initialization completes, the catalog's `getExpressionTriggerRegistry()` returns a fully-populated registry matching the expected triggers for all schemas (task 8.1)
- [ ] `catalog_copy_constructor_rebuilds_registry_after_initSchema` — simulate the copy constructor flow (as in `goingLive()` transition); verify the new catalog instance has a correctly-built registry reflecting all current schemas (task 8.2)

#### Category 16: Catalog Field Lifecycle

- [ ] `catalog_getExpressionTriggerRegistry_returns_non_null` — newly constructed `Catalog` returns a non-null registry from `getExpressionTriggerRegistry()` (the `EMPTY` singleton initially) (task 6.2)
- [ ] `catalog_removeLayer_includes_registry` — calling `Catalog.removeLayer()` removes the transactional layer from the `expressionTriggerRegistry` field (task 6.4)
- [ ] `catalog_copy_constructor_propagates_registry` — the copy constructor initializes `expressionTriggerRegistry` from the previous catalog version's registry value (task 6.3)

#### Category 17: Integration Readiness / Access Path Verification

- [ ] `registry_accessible_from_entity_collection_via_catalog` — verify that `EntityCollection` can reach the registry through `this.catalog.getExpressionTriggerRegistry()` and successfully call `getTriggersForAttribute()` (task 10.1)
- [ ] `entitySchemaUpdated_called_after_reflected_schema_resolution` — simulate the call chain `updateSchema() -> refreshReflectedSchemas() -> exchangeSchema() -> entitySchemaUpdated()`; verify the schema passed to the registry rebuild has fully-resolved reflected reference expressions (task 7.3)

#### Category 18: Edge Cases and Robustness

- [ ] `getTriggersFor_null_safe_on_absent_entity_type` — calling `getTriggersFor()` with an entity type that was never registered does not throw; returns empty list (Acceptance Criterion 9)
- [ ] `getTriggersForAttribute_with_trigger_having_empty_dependent_attributes` — a trigger with an empty `dependentAttributes` set is never returned by `getTriggersForAttribute()` regardless of the attribute name queried
- [ ] `rebuildForEntityType_idempotent_when_schema_unchanged` — rebuilding an entity type whose schema has not changed produces a registry with equivalent content to the original
- [ ] `entity_removal_uses_broad_lookup_not_attribute_filtered` — when simulating entity removal, verify that `getTriggersFor()` (not `getTriggersForAttribute()`) is used, returning ALL triggers for the removed entity type regardless of which attributes changed (task documented in "Entity removal handling" research section)
