# WBS-03: `ExpressionIndexTrigger` and `FacetExpressionTrigger` — Trigger Construction and Evaluation

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Implement the `ExpressionIndexTrigger` generic base interface, the `FacetExpressionTrigger` marker subtype, and the `DependencyType` enum that together form the trigger abstraction for conditional index maintenance. These types encapsulate an expression's pre-built evaluation infrastructure (Proxycian proxies, state recipe, pre-translated `FilterBy` constraint tree) and expose two evaluation modes: per-entity local evaluation and index-based cross-entity query evaluation. The concrete implementation is built once at schema load/change time and registered in `CatalogExpressionTriggerRegistry` for cross-entity dispatch.

## Scope

### In Scope

- `ExpressionIndexTrigger` interface with all methods (`getOwnerEntityType`, `getReferenceName`, `getScope`, `getDependencyType`, `getDependentAttributes`, `getFilterByConstraint`, `evaluate`)
- `FacetExpressionTrigger` marker interface extending `ExpressionIndexTrigger`
- `HistogramExpressionTrigger` marker interface extending `ExpressionIndexTrigger` (placeholder for future conditional histogram indexing)
- `DependencyType` enum with `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` values
- Concrete implementation of `FacetExpressionTrigger` that holds `Expression`, proxy class factories, state recipe, and pre-translated `FilterBy` constraint tree
- Construction logic that builds triggers from `ReferenceSchema.facetedPartiallyInScopes` at schema load/change time, using `AccessedDataFinder` for path analysis and `ExpressionToQueryTranslator` for `FilterBy` construction
- Both evaluation modes:
  - `evaluate()` — local per-entity evaluation via Proxycian proxies backed by StorageParts
  - `getFilterByConstraint()` — returns pre-translated `FilterBy` for cross-entity index-based evaluation
- Handling of `ReflectedReferenceSchema` inherited expressions during trigger construction
- Registration of built triggers in `CatalogExpressionTriggerRegistry` under `(mutatedEntityType, dependencyType)` keys

### Out of Scope

- `CatalogExpressionTriggerRegistry` implementation (WBS-04 or separate WBS)
- `IndexMutationExecutorRegistry` and executor dispatch (separate WBS)
- `ReevaluateFacetExpressionMutation` and `ReevaluateFacetExpressionExecutor` (separate WBS)
- `IndexMutationTarget` role interface on `EntityCollection` (separate WBS)
- `ExpressionToQueryTranslator` implementation (separate WBS, prerequisite)
- Full re-index when `facetedPartially` expression changes on an existing schema (deferred)
- Histogram-specific trigger metadata or behavior (future, beyond placeholder interface)

## Dependencies

### Depends On

- **WBS-01** — provides the `FilterBy` constraint tree translation infrastructure (`ExpressionToQueryTranslator`) that produces the pre-translated constraint stored in the trigger
- **WBS-02** — provides the Proxycian proxy infrastructure (ByteBuddy-generated proxy classes, `ByteBuddyDispatcherInvocationHandler` per root variable, `AccessedDataFinder` path analysis, partial selection, state recipe construction) that the trigger holds and uses for local evaluation

### Depended On By

- **WBS-04 / CatalogExpressionTriggerRegistry** — stores and indexes trigger instances for cross-entity lookup; depends on the trigger interfaces defined here
- **Cross-entity executor WBS** — `ReevaluateFacetExpressionExecutor` calls `getFilterByConstraint()` on trigger instances to obtain the `FilterBy` template
- **ReferenceIndexMutator integration WBS** — inline local evaluation calls `evaluate()` on trigger instances during mutation processing
- **EntityCollection schema wiring WBS** — builds trigger instances and registers them in the registry

## Technical Context

### Trigger Hierarchy

The trigger is a **function derived from a `ReferenceSchema`'s expression**. It encapsulates the expression together with pre-built Proxycian proxy infrastructure and a pre-translated `FilterBy` constraint tree. It has two roles:

- **Local evaluation** (inline in `ReferenceIndexMutator`): evaluates the expression per entity using Proxycian proxies backed by storage parts — used during local trigger processing
- **Cross-entity query** (used by `ReevaluateFacetExpressionExecutor`): provides a pre-translated `FilterBy` constraint that the executor runs against the target collection's indexes to determine which entities currently satisfy the expression — used during cross-entity trigger processing (no per-entity evaluation, pure index queries)

The concept is generic — facet conditional indexing is just one incarnation. Future incarnations (e.g., conditional histogram indexing for reference attributes) follow the same pattern: expression evaluation -> conditional add/remove of index data. The base interface captures the common evaluation contract; specific subtypes add type-safety and domain-specific metadata.

```
ExpressionIndexTrigger (generic base — expression evaluation)
+-- FacetExpressionTrigger (conditional facet indexing)
+-- HistogramExpressionTrigger (conditional histogram indexing, future)
```

### The Dependency Graph

Expressions can depend on data at various levels, determining whether triggers are local or cross-entity:

```
Expression depends on:
+-- $entity.attributes['x']                                      -> local trigger (same entity mutation)
+-- $entity.associatedData['x']                                  -> local trigger (same entity mutation)
+-- $entity.parent                                               -> local trigger (same entity mutation)
+-- $reference.attributes['x']                                   -> local trigger (same reference mutation)
+-- $reference.referencedEntity.attributes['x']                  -> cross-entity trigger (referenced entity attribute change)
+-- $reference.referencedEntity.references['r']*.attributes['x'] -> cross-entity trigger (referenced entity reference mutation)
+-- $reference.groupEntity?.attributes['x']                      -> cross-entity trigger (group entity attribute change)
+-- $reference.groupEntity?.references['r']*.attributes['x']     -> cross-entity trigger (group entity reference mutation)
                                                                    ^ THESE ARE THE FAN-OUT CASES
```

Local dependencies (owner entity attributes, reference attributes) are handled inline in `ReferenceIndexMutator` without the cross-entity trigger mechanism. Cross-entity dependencies require `DependencyType` classification and registry-based dispatch:
- `REFERENCED_ENTITY_ATTRIBUTE` / `GROUP_ENTITY_ATTRIBUTE` — for entity-attribute dependencies
- `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` / `GROUP_ENTITY_REFERENCE_ATTRIBUTE` — for reference-attribute dependencies on the target entity (see AD-22 in parent document)

### Single Entity Hop Limitation

Expressions are limited to **single entity hop** — they can navigate from the owner entity to at most one other entity (the referenced entity or group entity). Once there, the expression may read that entity's own properties **including its references and their attributes** (e.g., `$reference.referencedEntity.references['x']*.attributes['A']`), but may NOT follow those references to reach a third entity (e.g., `$reference.referencedEntity.references['x'].referencedEntity...` is invalid).

**Rationale:**
- Without this, we would face transitive closure explosion (A->B->C->D...)
- Cycle detection would require graph analysis at evaluation time
- Multi-hop cascades have non-deterministic ordering
- With single-hop, the dependency graph is a **bipartite graph** (entity <-> target/group entity), which is tractable

## Key Interfaces

### `ExpressionIndexTrigger` — generic base

Module: `io.evitadb.index.mutation` (`evita_engine`)

```java
/**
 * Generic base for expression-based index triggers. Each trigger wraps
 * a parsed {@link Expression} together with pre-built Proxycian proxy classes,
 * a state recipe derived from {@link AccessedDataFinder} path analysis, and
 * a pre-translated {@link FilterBy} constraint tree for index-based evaluation.
 *
 * Built at schema load/change time. Supports two evaluation modes:
 * - **Per-entity evaluation** via {@link #evaluate} — used for local triggers
 *   (inline in {@code ReferenceIndexMutator})
 * - **Index-based query evaluation** via {@link #getFilterByConstraint()} —
 *   used for cross-entity triggers (the executor runs the constraint against
 *   indexes, no per-entity storage access needed)
 *
 * The {@link FilterBy} constraint is built at schema time by
 * {@link ExpressionToQueryTranslator}. If the expression cannot be translated
 * (e.g., dynamic attribute paths, direct cross-to-local comparisons), an
 * exception is thrown at schema load time — non-translatable expressions
 * are rejected.
 *
 * Does NOT:
 * - resolve affected entity PKs (the IndexMutationExecutor's job on the target side)
 * - modify indexes (the IndexMutationExecutor's job)
 * - wrap results into EntityIndexMutation (the source executor's job)
 */
public interface ExpressionIndexTrigger {

    /**
     * Entity type owning the reference with the expression (the target
     * collection — e.g., "product").
     */
    @Nonnull
    String getOwnerEntityType();

    /**
     * Name of the reference carrying the conditional expression
     * (e.g., "parameter").
     */
    @Nonnull
    String getReferenceName();

    /**
     * Scope to which this trigger applies. A reference with expressions
     * in multiple scopes produces one trigger per scope.
     */
    @Nonnull
    Scope getScope();

    /**
     * How the mutated entity relates to the owner entity:
     * {@code GROUP_ENTITY_ATTRIBUTE}, {@code REFERENCED_ENTITY_ATTRIBUTE},
     * {@code GROUP_ENTITY_REFERENCE_ATTRIBUTE}, or
     * {@code REFERENCED_ENTITY_REFERENCE_ATTRIBUTE}.
     * Returns {@code null} for local-only triggers (expressions that reference
     * only {@code $entity.*} and {@code $reference.attributes['x']}) — these
     * are handled inline in {@code ReferenceIndexMutator} and do not need
     * cross-entity registry entries.
     */
    @Nullable
    DependencyType getDependencyType();

    /**
     * For reference-level dependencies ({@code REFERENCED_ENTITY_REFERENCE_ATTRIBUTE},
     * {@code GROUP_ENTITY_REFERENCE_ATTRIBUTE}), returns the reference name on
     * the target entity whose mutations should trigger re-evaluation. For example,
     * if the expression accesses
     * {@code $reference.referencedEntity.references['brand']*.attributes['order']},
     * this method returns {@code "brand"}.
     *
     * Returns {@code null} for entity-attribute dependencies and local-only triggers.
     */
    @Nullable
    String getDependentReferenceName();

    /**
     * Attribute names on the mutated entity (group or referenced) that
     * this expression reads. For entity-attribute dependencies, these are
     * entity attribute names. For reference-attribute dependencies, these
     * are reference attribute names on the reference identified by
     * {@link #getDependentReferenceName()}.
     * Used by the detection step to skip triggers whose dependent
     * attributes were not changed by the current mutation.
     */
    @Nonnull
    Set<String> getDependentAttributes();

    /**
     * Returns the full expression pre-translated to an evitaDB
     * {@link FilterBy} constraint tree. Built at schema load time by
     * {@link ExpressionToQueryTranslator}.
     *
     * At trigger time, the executor **parameterizes** this constraint by
     * injecting a PK-scoping clause for the specific mutated entity:
     * - {@code GROUP_ENTITY_ATTRIBUTE}: adds
     *   {@code groupHaving(entityPrimaryKeyInSet(mutatedPK))}
     *   within the {@code referenceHaving} clause
     * - {@code REFERENCED_ENTITY_ATTRIBUTE}: adds
     *   {@code entityHaving(entityPrimaryKeyInSet(mutatedPK))}
     *   within the {@code referenceHaving} clause
     *
     * This scoping ensures the query only matches references to the
     * SPECIFIC changed entity, not all entities of the same type.
     *
     * The parameterized constraint is then evaluated against the target
     * collection's current indexes via
     * {@link IndexMutationTarget#evaluateFilter(FilterBy)}.
     */
    @Nonnull
    FilterBy getFilterByConstraint();

    /**
     * Evaluates the expression for a specific owner entity and reference.
     * Used for **local triggers** only (inline in {@code ReferenceIndexMutator}).
     *
     * Instantiates pre-built Proxycian proxy classes backed by StoragePart
     * data (fetched per the pre-computed state recipe), binds them as
     * expression variables ($entity, $reference), and computes the
     * expression result.
     *
     * NOT used for cross-entity triggers — those use
     * {@link #getFilterByConstraint()} for index-based evaluation instead.
     *
     * @param ownerEntityPK   primary key of the entity owning the reference
     * @param referenceKey    identifies the specific reference instance
     * @param storageAccessor accessor for fetching required StorageParts
     * @return true if the index entry should exist, false otherwise
     */
    boolean evaluate(
        int ownerEntityPK,
        @Nonnull ReferenceKey referenceKey,
        @Nonnull WritableEntityStorageContainerAccessor storageAccessor
    );
}
```

### `FacetExpressionTrigger` — conditional facet indexing

```java
/**
 * Trigger for conditional facet indexing. Derived from
 * {@link ReferenceSchemaContract#getFacetedPartiallyInScope(Scope)}.
 * When the expression evaluates to true, the reference is facet-indexed;
 * when false, it is not.
 */
public interface FacetExpressionTrigger extends ExpressionIndexTrigger {
    // Type marker — no additional methods for now.
    // Future: may carry facet-specific metadata (e.g., facet group info).
}
```

### `HistogramExpressionTrigger` — conditional histogram indexing (future)

```java
/**
 * Trigger for conditional histogram indexing on reference attributes.
 * When the expression evaluates to true, reference attribute histogram
 * data is maintained for this reference; when false, it is excluded.
 */
public interface HistogramExpressionTrigger extends ExpressionIndexTrigger {
    // Type marker — may carry histogram-specific metadata
    // (e.g., which attributes to histogram, bucket count).
}
```

### `DependencyType` — cross-entity relationship classification

Module: `io.evitadb.index.mutation` (`evita_engine`)

```java
/**
 * Classifies the relationship between a mutated entity and the owner entity
 * in a cross-entity expression trigger. Used as a registry key in
 * {@link CatalogExpressionTriggerRegistry} to look up which triggers should
 * fire when a given entity type's data changes.
 *
 * Only cross-entity relationships are represented here. Local dependencies
 * ({@code $entity.attributes['x']}, {@code $reference.attributes['x']}) are
 * handled inline in {@code ReferenceIndexMutator} and do not require this enum.
 */
public enum DependencyType {

    /**
     * The mutated entity is the **referenced entity** of the reference.
     * Expression path: {@code $reference.referencedEntity.attributes['x']}
     *
     * The trigger fires when an attribute on the referenced entity changes
     * (or the entity is removed) and the expression reads that attribute.
     * Fan-out is typically 1:1 (one referenced entity per reference instance).
     */
    REFERENCED_ENTITY_ATTRIBUTE,

    /**
     * The mutated entity is the **referenced entity** of the reference,
     * and the dependency is on a **reference attribute** of that entity.
     * Expression path: {@code $reference.referencedEntity.references['r']*.attributes['x']}
     *
     * The trigger fires when a reference mutation on the referenced entity
     * affects reference 'r' (insert, remove, or attribute 'x' change).
     * {@code getDependentReferenceName()} returns 'r',
     * {@code getDependentAttributes()} returns {'x'}.
     */
    REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,

    /**
     * The mutated entity is the **group entity** of the reference.
     * Expression path: {@code $reference.groupEntity?.attributes['x']}
     *
     * The trigger fires when an attribute on the group entity changes
     * (or the entity is removed).
     * Fan-out can be significant — one group entity may be shared across
     * many references (and thus many owner entities).
     */
    GROUP_ENTITY_ATTRIBUTE,

    /**
     * The mutated entity is the **group entity** of the reference,
     * and the dependency is on a **reference attribute** of that entity.
     * Expression path: {@code $reference.groupEntity?.references['r']*.attributes['x']}
     *
     * The trigger fires when a reference mutation on the group entity
     * affects reference 'r' (insert, remove, or attribute 'x' change).
     * {@code getDependentReferenceName()} returns 'r',
     * {@code getDependentAttributes()} returns {'x'}.
     * Fan-out can be significant (same as GROUP_ENTITY_ATTRIBUTE).
     */
    GROUP_ENTITY_REFERENCE_ATTRIBUTE
}
```

## MECE Mutation Classification

The mutation categories are MECE with respect to **where the mutation occurs** (owner entity vs. referenced entity vs. group entity). A single expression can reference data from multiple categories simultaneously.

### 2a. Mutations on the entity owning the reference (LOCAL triggers)

| Mutation                           | Triggers re-evaluation when...                                |
|------------------------------------|---------------------------------------------------------------|
| `UpsertAttributeMutation`          | Expression uses `$entity.attributes['x']`                     |
| `UpsertReferenceAttributeMutation` | Expression uses `$reference.attributes['x']`                  |
| `UpsertAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `RemoveAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `SetParentMutation`                | Expression uses `$entity.parent`                              |
| `RemoveParentMutation`             | Expression uses `$entity.parent`                              |
| `InsertReferenceMutation`          | Always — new facet candidate, must evaluate expression        |
| `RemoveReferenceMutation`          | Always — facet removal (existing logic, no expression needed) |
| `SetReferenceGroupMutation`        | Expression uses `$reference.groupEntity`                      |
| `RemoveReferenceGroupMutation`     | Expression uses `$reference.groupEntity`                      |

These are **local triggers** — handled within the same entity's mutation processing, inline with existing `ReferenceIndexMutator` logic, using `evaluate()`.

### 2b. Mutations on the referenced entity (CROSS-ENTITY trigger)

| Mutation                                       | Triggers re-evaluation when...                                |
|------------------------------------------------|---------------------------------------------------------------|
| Attribute change on referenced entity          | Expression uses `$reference.referencedEntity.attributes['x']` |
| Reference mutation on referenced entity (`InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation` for reference 'r') | Expression uses `$reference.referencedEntity.references['r']*.attributes['x']` |
| `EntityRemoveMutation` on referenced entity    | Expression uses `$reference.referencedEntity.*` — entity removal changes all accessed properties (null-safe paths return `null`, non-null-safe paths may throw). All dependent expressions must be re-evaluated. |

Entity A's facet indexing depends on entity B's data. Uses `DependencyType.REFERENCED_ENTITY_ATTRIBUTE` for entity-attribute dependencies, or `DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` for reference-attribute dependencies (see AD-22 in parent document).

### 2c. Mutations on the group entity (CROSS-ENTITY trigger, fan-out)

| Mutation                                       | Triggers re-evaluation when...                                |
|------------------------------------------------|---------------------------------------------------------------|
| Attribute change on group entity               | Expression uses `$reference.groupEntity?.attributes['x']`     |
| Reference mutation on group entity (`InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation` for reference 'r') | Expression uses `$reference.groupEntity?.references['r']*.attributes['x']` |
| `EntityRemoveMutation` on group entity         | Expression uses `$reference.groupEntity?.*` — group entity removal changes all accessed properties (null-safe `?.` returns `null`). All dependent expressions must be re-evaluated. |

Uses `DependencyType.GROUP_ENTITY_ATTRIBUTE` for entity-attribute dependencies, or `DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE` for reference-attribute dependencies. **Fan-out problem:** one change on a group entity can cascade to thousands of entities.

### 2d. Schema mutations

| Mutation                              | Effect                                             |
|---------------------------------------|----------------------------------------------------|
| `facetedPartially` expression changes | Must re-index ALL entities for that reference type |
| Reference becomes faceted/unfaceted   | Already handled by existing logic                  |

Full re-index on expression change is deferred — for this iteration, the `facetedPartially` expression is treated as effectively immutable after initial schema creation.

## Acceptance Criteria

1. **`ExpressionIndexTrigger` interface** exists in `io.evitadb.index.mutation` (`evita_engine`) with all specified methods: `getOwnerEntityType()`, `getReferenceName()`, `getScope()`, `getDependencyType()`, `getDependentAttributes()`, `getFilterByConstraint()`, `evaluate()`.

2. **`FacetExpressionTrigger`** marker interface extends `ExpressionIndexTrigger` in the same package.

3. **`HistogramExpressionTrigger`** marker interface extends `ExpressionIndexTrigger` in the same package (future placeholder, no concrete implementation required yet).

4. **`DependencyType`** enum exists with `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` values, each with complete JavaDoc.

5. **Concrete `FacetExpressionTrigger` implementation** holds:
   - The parsed `Expression`
   - Pre-built proxy class factories (`ByteBuddyDispatcherInvocationHandler` per root variable)
   - State recipe (which `StoragePart`s to fetch for each proxy level)
   - Pre-translated `FilterBy` constraint tree (from `ExpressionToQueryTranslator`)

6. **`evaluate()` implementation** correctly:
   - Fetches only the StorageParts the recipe requires from `storageAccessor`
   - Creates state records and instantiates pre-built proxy classes
   - Wires nested proxies for group/referenced entity access
   - Binds variables (`$entity`, `$reference`) and computes the expression result
   - Returns `boolean` (true = index entry should exist)

7. **`getFilterByConstraint()` implementation** returns the pre-translated `FilterBy` constraint, built once at schema load time (not per invocation).

8. **Module boundary respected**: `ExpressionIndexTrigger` and all implementations live in `evita_engine`, never in `evita_api`. `ReferenceSchema.facetedPartiallyInScopes` in `evita_api` is the declarative contract; triggers are operational engine internals.

9. **Construction from schema**: Triggers are buildable from `ReferenceSchema.facetedPartiallyInScopes` entries, including:
   - Direct reference schemas
   - `ReflectedReferenceSchema` instances that inherit `facetedPartially` from the source
   - One trigger per scope when a reference has expressions in multiple scopes

10. **`getDependentAttributes()` correctly populated**: Contains the set of attribute names on the mutated entity (group or referenced) that the expression reads, as discovered by `AccessedDataFinder`.

11. **"No affectedPKsAccessor here" principle enforced**: The trigger does NOT resolve affected owner entity PKs. PK resolution is delegated to the `IndexMutationExecutor` on the target side.

12. **Non-translatable expressions rejected at schema load time**: If `ExpressionToQueryTranslator` cannot translate the expression (e.g., dynamic attribute paths), an exception is thrown during trigger construction — not deferred to trigger time.

## Implementation Notes

### Concrete Implementation Internals

The concrete implementation of `FacetExpressionTrigger` holds:

- **`Expression`** — the parsed expression tree
- **Proxy class factories** — one `ByteBuddyDispatcherInvocationHandler` per root variable (`$entity`, `$reference`) and one per nested entity level (group entity, referenced entity). These are pre-generated ByteBuddy classes, ready to be instantiated with a state record.
- **State recipe** — a descriptor of which storage parts to fetch from `WritableEntityStorageContainerAccessor` for each proxy level. Derived from `AccessedDataFinder` path analysis -> partial selection -> storage part union.
- **Pre-translated `FilterBy` constraint tree** — built once at schema load time by `ExpressionToQueryTranslator`. Returned by `getFilterByConstraint()`.

### Schema Load Time Construction Sequence

When a schema is loaded or changed and contains a `facetedPartially` expression:

```
1. Parse expression -> ExpressionNode tree
2. AccessedDataFinder.findAccessedPaths(expression) -> List<List<PathItem>>
3. Map paths to partials (EntityContract/ReferenceContract partial interfaces)
4. Union partials per root variable -> final set of PredicateMethodClassification[]
5. Compose ByteBuddyDispatcherInvocationHandler for EntityContract proxy (if needed)
6. Compose ByteBuddyDispatcherInvocationHandler for ReferenceContract proxy (if needed)
7. Compose ByteBuddyDispatcherInvocationHandler for nested entity proxies (if needed)
8. ByteBuddy generates proxy classes (cached — one-time cost per unique partial set)
9. Build state recipe: which storage parts to fetch for each proxy
10. ExpressionToQueryTranslator translates expression to FilterBy constraint tree
11. Build FacetExpressionTrigger instance with all above artifacts
12. Register trigger in CatalogExpressionTriggerRegistry under (mutatedEntityType, dependencyType) keys
```

**No classes are generated at trigger time.** Only state records are created and pre-built proxy classes instantiated.

### Trigger Time Evaluation (Local Path via `evaluate()`)

When a local trigger fires, the `evaluate()` method:

1. **Fetches only the storage parts the recipe requires** from `storageAccessor` — e.g., if the expression only accesses `$entity.attributes['code']`, only `EntityBodyStoragePart` and the global `AttributesStoragePart` are fetched
2. **Creates state records** — lightweight records holding references to fetched parts and schema objects
3. **Instantiates pre-built proxy classes** with those states — just object allocation + state assignment
4. **Wires nested proxies** — for expressions accessing `$reference.groupEntity?.attributes['x']`: fetch group entity's storage parts, create entity proxy state, instantiate entity proxy, create reference proxy state with `groupEntity = entityProxy`, instantiate reference proxy
5. **Computes** the expression result and converts to `boolean`:
   - If the result is `Boolean`: use it directly
   - If the result is `null`: treat as `false` (missing/null data means the condition is not met)
   - If the result is any other type (String, Integer, etc.): throw `ExpressionEvaluationException` with a message indicating the expression returned a non-boolean type (e.g., "Expression for reference 'parameter' returned String instead of Boolean")

Total allocations for a group entity expression: 2 state records + 2 proxy instances = **4 objects**, regardless of entity property count.

### Cross-Entity Evaluation (via `getFilterByConstraint()`)

**Note:** `getFilterByConstraint()` is only available on triggers with a non-null `getDependencyType()` (cross-entity triggers). For local-only triggers (`getDependencyType() == null`), calling `getFilterByConstraint()` throws `UnsupportedOperationException` — local-only expressions are evaluated exclusively via `evaluate()` inline in `ReferenceIndexMutator`.

The cross-entity path does NOT call `evaluate()`. Instead:

1. The executor retrieves the trigger and calls `getFilterByConstraint()` to get the pre-translated `FilterBy` template
2. The executor **parameterizes** the constraint by injecting PK-scoping:
   - `GROUP_ENTITY_ATTRIBUTE`: adds `groupHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause
   - `REFERENCED_ENTITY_ATTRIBUTE`: adds `entityHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause
3. The parameterized constraint is evaluated against the target collection's indexes via `IndexMutationTarget.evaluateFilter(FilterBy)`

### ReflectedReferenceSchema Handling

`ReflectedReferenceSchema` can inherit `facetedPartially` from the source `ReferenceSchema`. During trigger construction:

- `EntityCollection` processes both direct and reflected reference schemas
- For reflected schemas that inherit `facetedPartially`, the trigger is built from the inherited expression and registered under the reflected schema's entity type
- When the source schema's `facetedPartially` expression changes, all reflected schemas that inherit it must have their triggers rebuilt — `CatalogExpressionTriggerRegistry.rebuildForEntityType()` for the source entity type must cascade to all entity types with reflected references that inherit from the changed source

### Caching via DataStoreMemoryBuffer

Storage parts are fetched through `WritableEntityStorageContainerAccessor` backed by `DataStoreMemoryBuffer`:

- **Within a single entity:** Multiple expressions across different references access the same storage parts — the buffer serves them from cache after the first fetch
- **Cross-entity access:** Group entity storage parts shared across multiple owner entities are fetched once and cached
- **Transaction-scoped:** Cache bounded to the transaction — no persistent memory overhead

### Mutation Ordering Guarantee

Cross-entity triggers fire AFTER the source entity's mutations complete (same as `popImplicitMutations` external mutations):

- Entity B's storage already reflects the NEW attribute value
- `WritableEntityStorageContainerAccessor` on entity A will see B's updated data
- Expression evaluation produces correct result based on new state

### AD-12 Context: Registry-Based Executor Dispatch

> **Reference:** Architecture Decision 12 in the [parent analysis document](../conditional-facet-indexing.md), section "Architecture Decisions" — "Registry-based executor dispatch — stateless static singleton".

The trigger sits within a four-layer architecture (AD-12):

1. **`IndexMutationTarget`** — role interface on `EntityCollection`, limits executor access to index lookup, schema retrieval, trigger access (`getTrigger`), and filter evaluation (`evaluateFilter`)
2. **`ExpressionIndexTrigger`** — this WBS; the generic base with subtypes `FacetExpressionTrigger` and `HistogramExpressionTrigger`
3. **`IndexMutationExecutorRegistry`** — static singleton mapping mutation types to executor singletons
4. **`CatalogExpressionTriggerRegistry`** — catalog-level inverted index mapping `(mutatedEntityType, dependencyType)` to trigger lists

### AD-13 Context: Cross-Schema Wiring at Schema Load Time

> **Reference:** Architecture Decision 13 in the [parent analysis document](../conditional-facet-indexing.md), section "Architecture Decisions" — "Cross-schema wiring at schema load time".

When a `ReferenceSchema`'s `facetedPartially` expression is set/changed, `EntityCollection` (in `evita_engine`):

1. Reads the `Expression` from `ReferenceSchema.facetedPartiallyInScopes`
2. Uses `AccessedDataFinder` to analyze paths
3. Builds `FacetExpressionTrigger` instances
4. Registers them in `CatalogExpressionTriggerRegistry` under the appropriate `(mutatedEntityType, dependencyType)` keys

Schema B "maintains" triggers for schema A by virtue of being the registry key — the ownership is inverted.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### Key class locations

| Class / Interface | Absolute path |
|---|---|
| `ReferenceSchemaContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java` |
| `ReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReferenceSchema.java` |
| `ReflectedReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java` |
| `Scope` enum | `evita_common/src/main/java/io/evitadb/dataType/Scope.java` |
| `ReferenceIndexMutator` | `evita_engine/src/main/java/io/evitadb/index/mutation/index/ReferenceIndexMutator.java` |
| `EntityIndexLocalMutationExecutor` | `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java` |
| `EntityCollection` | `evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java` |
| `ConsistencyCheckingLocalMutationExecutor` | `evita_engine/src/main/java/io/evitadb/index/mutation/ConsistencyCheckingLocalMutationExecutor.java` |
| `MutationCollector` | `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/MutationCollector.java` |
| `ContainerizedLocalMutationExecutor` | `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java` |
| `LocalMutationExecutorCollector` | `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java` |
| `WritableEntityStorageContainerAccessor` | `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/accessor/WritableEntityStorageContainerAccessor.java` |
| `ReferenceKey` (record) | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/ReferenceKey.java` |
| `Expression` | `evita_common/src/main/java/io/evitadb/dataType/expression/Expression.java` |
| `ExpressionEvaluationContext` | `evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionEvaluationContext.java` |
| `ReferencedTypeEntityIndex` (proxy pattern) | `evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java` |
| `AccessedDataFinder` | `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/AccessedDataFinder.java` |
| `ExpressionNodeVisitor` | `evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionNodeVisitor.java` |
| `FilterBy` constraint | `evita_query/src/main/java/io/evitadb/api/query/filter/FilterBy.java` |
| `QueryConstraints` factory | `evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java` |
| `Catalog` | `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java` |
| `ReferenceIndexMutatorTest` | `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/index/ReferenceIndexMutatorTest.java` |

##### `ReferenceSchemaContract` — facetedPartially methods

`ReferenceSchemaContract` (line 75-418 in `evita_api/.../schema/ReferenceSchemaContract.java`) exposes:

- `getFacetedPartially()` — default method, delegates to `getFacetedPartiallyInScope(Scope.DEFAULT_SCOPE)` (line 383-385)
- `getFacetedPartiallyInScope(Scope)` — returns `@Nullable Expression` for the given scope (line 394-395)
- `getFacetedPartiallyInScopes()` — default method returning `Collections.emptyMap()` (line 405-407), overridden in `ReferenceSchema` to return the actual `Map<Scope, Expression>`
- `isFacetedInScope(Scope)` — boolean, returns whether reference is faceted in that scope (line 365)

`ReferenceSchema` stores `facetedPartiallyInScopes` as `@Getter @Nonnull protected final Map<Scope, Expression>` (line 110). `getFacetedPartiallyInScope(Scope)` returns `this.facetedPartiallyInScopes.get(scope)` (line 811).

##### `Scope` enum

`Scope` (in `evita_common`) has two values: `LIVE` and `ARCHIVED` (lines 37-46). Constants: `DEFAULT_SCOPE = LIVE`, `DEFAULT_SCOPES = {LIVE}`, `NO_SCOPE = {}`. A reference with `facetedPartially` expressions in both scopes produces **two triggers**, one per scope.

##### `ReferenceIndexMutator` — `isFaceted()` guard pattern

`ReferenceIndexMutator` is a `public interface` (line 139) with all `static` methods serving as a namespace for reference index mutation logic.

The `isFaceted()` private static method (lines 687-697) is the key guard:
```java
private static boolean isFaceted(
    ReferenceKey referenceKey, ReferenceSchemaContract referenceSchema,
    Scope scope, EntityIndexLocalMutationExecutor executor
) {
    final ReferenceSchemaContract referenceSchemaToUse = getReferenceSchemaFor(
        referenceKey, referenceSchema, executor
    );
    return referenceSchemaToUse.isFacetedInScope(scope);
}
```

This method is called in all facet-modifying methods (`addFacetToIndex` line 1135, `setFacetGroupInIndex` line 1180, `removeFacetInIndex` line 1287, `removeFacetGroupInIndex` line 1330) always paired with `shouldIndexFacetToTargetIndex()`.

**Integration point for WBS-03:** When `isFaceted()` returns true AND a `facetedPartially` expression exists for the scope, the trigger's `evaluate()` must be called to determine whether the specific reference should be facet-indexed. The `isFaceted()` method itself does NOT need modification — it remains a necessary precondition. The trigger evaluation is an ADDITIONAL gate after `isFaceted()`.

##### `EntityIndexLocalMutationExecutor` — trigger access

`EntityIndexLocalMutationExecutor` (line 114) holds:
- `Supplier<EntitySchema> schemaAccessor` (line 140) — provides the current entity schema
- `WritableEntityStorageContainerAccessor containerAccessor` (line 123) — provides storage part access
- `EntitySchema getEntitySchema()` (line 816-817) — returns `this.schemaAccessor.get()`

The executor does NOT currently hold trigger references. To support local trigger evaluation, the executor needs access to triggers either:
1. Via the schema (if triggers are schema-attached) — this is the preferred approach since `getEntitySchema()` is already available everywhere
2. Via a separate trigger accessor injected at construction time

The executor's constructor (lines 186-206) takes `Supplier<EntitySchema>`, `WritableEntityStorageContainerAccessor`, `IndexMaintainer<EntityIndexKey, EntityIndex>`, and other infrastructure. Triggers would be accessed via `getEntitySchema().getReference(refName).getFacetedPartiallyInScope(scope)` to get the `Expression`, then the trigger (built from that expression) would be looked up from the registry.

##### `EntityCollection` — schema lifecycle management

`EntityCollection` (line 180-184) manages schema via:
- `AtomicReference<EntitySchemaDecorator> schema` — transactional schema reference
- `updateSchema()` (lines 943-990) — applies `EntitySchemaMutation` to the schema, calls `exchangeSchema()` (line 976) which calls `this.catalog.entitySchemaUpdated(updatedSchema)` (line 2070)
- `refreshReflectedSchemas()` (lines 1981-2048) — processes `ReflectedReferenceSchema` instances, calling `withReferencedSchema()` to resolve inherited properties including `facetedPartially`

**Trigger construction hook:** The `exchangeSchema()` method (lines 2060-2071) is the natural hook for trigger rebuilding. After the schema exchange succeeds, triggers derived from the old schema should be replaced with triggers built from the new schema. The sequence would be:
1. `exchangeSchema()` completes
2. Iterate all reference schemas in the new `EntitySchema`
3. For each reference with `facetedPartiallyInScopes` entries, build `FacetExpressionTrigger` instances
4. Register triggers in `CatalogExpressionTriggerRegistry`

##### `ReflectedReferenceSchema` — faceted inheritance

`ReflectedReferenceSchema` (sealed subclass of `ReferenceSchema`) inherits `facetedPartially` when `facetedInherited == true`. Key methods:
- Constructor (line 660): passes `facetedPartiallyInScopes` to `super()` (line 674)
- `withReferencedSchema(originalReference)` (line 1696): when `facetedInherited`, uses `originalReference.getFacetedPartiallyInScopes()` (line 1740) instead of local `this.facetedPartiallyInScopes`
- `withFacetedPartially()` (line 1520): creates a copy with new expressions
- Internal `_internalBuild()` (line 222): when `facetedInScopes == null` (inherited), populates `facetedPartiallyInScopes` from `reflectedReference.getFacetedPartiallyInScopes()` (line 563)
- `filterFacetedPartiallyForScopes()` (line 1820): utility to filter expressions by active scopes

**Trigger construction implication:** When building triggers for a `ReflectedReferenceSchema`, the trigger must use the **resolved** `facetedPartiallyInScopes` (which may be inherited from the source). This is handled transparently by calling `getFacetedPartiallyInScopes()` on the schema, which already resolves inheritance.

##### External mutation dispatch pattern (template for cross-entity triggers)

`popImplicitMutations()` in `ContainerizedLocalMutationExecutor` (line 1162) returns `ImplicitMutations(localMutations, externalMutations)`. `LocalMutationExecutorCollector.applyMutations()` (lines 257-281) then:
1. Applies local mutations to the entity's own indexes (lines 262-265)
2. For each external mutation, calls `catalog.getCollectionForEntityOrThrowException().applyMutations()` (lines 267-279)

This pattern of producing and applying external mutations via `MutationCollector.addExternalMutation()` is the exact template for cross-entity trigger dispatch. The trigger infrastructure would produce external mutations (e.g., `ReevaluateFacetExpressionMutation`) that get applied to the target collection.

##### Existing proxy pattern — `ReferencedTypeEntityIndex.createThrowingStub()`

`ReferencedTypeEntityIndex.createThrowingStub()` (lines 182-211) demonstrates:
1. Define a **state record**: `private record ReferencedTypeEntityIndexProxyStateThrowing(EntitySchemaContract entitySchema) implements Serializable`
2. Define `PredicateMethodClassification` constants matching specific methods
3. Compose via `ByteBuddyDispatcherInvocationHandler` with classifications in priority order
4. Generate proxy via `ByteBuddyProxyGenerator.instantiate()` with interface array, constructor param types, and constructor param values

This is the exact pattern WBS-02 follows for the expression proxy infrastructure, which WBS-03 then holds as pre-built artifacts.

##### Module and package placement (determined by WBS-01 and WBS-02)

Per WBS-01, the translator lives in `io.evitadb.index.mutation.expression` (`evita_engine` module). Per WBS-02, the proxy infrastructure lives in `io.evitadb.core.expression.proxy` (`evita_engine` module).

WBS-03 interfaces and implementations should live in:
- `io.evitadb.index.mutation` — for `ExpressionIndexTrigger`, `FacetExpressionTrigger`, `HistogramExpressionTrigger`, `DependencyType` (co-located with `ConsistencyCheckingLocalMutationExecutor`)
- `io.evitadb.index.mutation.expression` — for the concrete `FacetExpressionTriggerImpl` implementation class, co-located with `ExpressionToQueryTranslator` from WBS-01

The `io.evitadb.index.mutation` package is NOT exported in `module-info.java` (engine-internal), which is correct for trigger types.

##### Key design decisions from WBS-01 and WBS-02 that WBS-03 depends on

1. **WBS-01 provides `ExpressionToQueryTranslator.translate(Expression, String referenceName): FilterBy`** — called at schema load time, producing the `FilterBy` template stored in the trigger. Placed in `io.evitadb.index.mutation.expression`.

2. **WBS-02 provides per-expression proxy class factories and state recipes** — `ByteBuddyDispatcherInvocationHandler` for `EntityContract`/`ReferenceContract` proxies, state records (`EntityProxyState`, `ReferenceProxyState`), path-to-partial mapping. Placed in `io.evitadb.core.expression.proxy`.

3. **WBS-01 corrects `entityGroupHaving` -> `groupHaving`** — the actual evitaDB constraint is `QueryConstraints.groupHaving()` / `GroupHaving`, not `entityGroupHaving()`.

4. **Both WBS-01 and WBS-02 confirm AD 14** — all trigger infrastructure lives in `evita_engine`, not `evita_api`.

---

#### Detailed Task List

##### Group 1: `DependencyType` enum

- [x] **1.1** Create `DependencyType` enum in `evita_engine/src/main/java/io/evitadb/index/mutation/DependencyType.java`. Two values: `REFERENCED_ENTITY_ATTRIBUTE` (the mutated entity is the referenced entity, expression path `$reference.referencedEntity.attributes['x']`, fan-out typically 1:1) and `GROUP_ENTITY_ATTRIBUTE` (the mutated entity is the group entity, expression path `$reference.groupEntity?.attributes['x']`, fan-out can be significant). Include complete JavaDoc on the enum and each constant, explaining the cross-entity relationship, the expression paths that produce each type, and the fan-out characteristics.

##### Group 2: `ExpressionIndexTrigger` interface

- [x] **2.1** Create `ExpressionIndexTrigger` interface in `evita_engine/src/main/java/io/evitadb/index/mutation/ExpressionIndexTrigger.java` in package `io.evitadb.index.mutation`. Define all seven methods:
  - `@Nonnull String getOwnerEntityType()` — entity type owning the reference (e.g., "product")
  - `@Nonnull String getReferenceName()` — name of the reference carrying the expression (e.g., "parameter")
  - `@Nonnull Scope getScope()` — scope this trigger applies to (`LIVE` or `ARCHIVED`)
  - `@Nullable DependencyType getDependencyType()` — how the mutated entity relates to the owner entity; returns `null` for local-only triggers
  - `@Nonnull Set<String> getDependentAttributes()` — attribute names on the mutated entity (group or referenced) that the expression reads
  - `@Nonnull FilterBy getFilterByConstraint()` — pre-translated FilterBy template, built once at schema load time
  - `boolean evaluate(int ownerEntityPK, @Nonnull ReferenceKey referenceKey, @Nonnull WritableEntityStorageContainerAccessor storageAccessor)` — local per-entity evaluation via Proxycian proxies

- [x] **2.2** Write complete JavaDoc for `ExpressionIndexTrigger` covering: its role as generic base for expression-based index triggers, the two-mode evaluation architecture (local via `evaluate()`, cross-entity via `getFilterByConstraint()`), the construction at schema load time, and the "no affectedPKsAccessor here" principle (PK resolution is the `IndexMutationExecutor`'s job, not the trigger's).

##### Group 3: `FacetExpressionTrigger` and `HistogramExpressionTrigger` marker interfaces

- [x] **3.1** Create `FacetExpressionTrigger` marker interface in `evita_engine/src/main/java/io/evitadb/index/mutation/FacetExpressionTrigger.java`, extending `ExpressionIndexTrigger`. No additional methods. JavaDoc: "Trigger for conditional facet indexing. Derived from `ReferenceSchemaContract.getFacetedPartiallyInScope(Scope)`. When the expression evaluates to true, the reference is facet-indexed; when false, it is not."

- [x] **3.2** Create `HistogramExpressionTrigger` marker interface in `evita_engine/src/main/java/io/evitadb/index/mutation/HistogramExpressionTrigger.java`, extending `ExpressionIndexTrigger`. No additional methods. JavaDoc: "Trigger for conditional histogram indexing on reference attributes. Future placeholder — no concrete implementation required yet."

##### Group 4: Concrete `FacetExpressionTriggerImpl` implementation

- [x] **4.1** Create `FacetExpressionTriggerImpl` class in `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerImpl.java` *(package changed from spec: co-located with WBS-02 proxy infrastructure)*, implementing `FacetExpressionTrigger`. The class should be package-private (or public if needed for test access), immutable, and thread-safe. Fields:
  - `@Nonnull String ownerEntityType` — from `EntitySchema.getName()`
  - `@Nonnull String referenceName` — from `ReferenceSchemaContract.getName()`
  - `@Nonnull Scope scope` — the scope this trigger applies to
  - `@Nonnull DependencyType dependencyType` — classified from `AccessedDataFinder` path analysis
  - `@Nonnull Set<String> dependentAttributes` — attribute names on the mutated entity that the expression reads
  - `@Nonnull Expression expression` — the parsed expression AST
  - `@Nonnull FilterBy filterByConstraint` — pre-translated by `ExpressionToQueryTranslator`
  - Proxy class factories from WBS-02 (types TBD by WBS-02 implementation — e.g., `ByteBuddyDispatcherInvocationHandler` per root variable)
  - State recipe from WBS-02 (descriptor of which storage parts to fetch for each proxy level)

- [x] **4.2** Implement `evaluate()` method on `FacetExpressionTriggerImpl`:
  1. Fetch only the storage parts the state recipe requires from `storageAccessor` (following WBS-02's `createEvaluationContext` pattern)
  2. Create state records (e.g., `EntityProxyState`, `ReferenceProxyState` from WBS-02)
  3. Instantiate pre-built proxy classes with those states
  4. Wire nested proxies for group/referenced entity access (if the expression accesses `$reference.groupEntity?.attributes['x']` or `$reference.referencedEntity.attributes['x']`)
  5. Create `ExpressionEvaluationContext` with bound variables (`$entity`, `$reference`)
  6. Compute expression result via `expression.compute(context)` and convert to `boolean`:
     - If result is `Boolean`: use directly
     - If result is `null`: return `false` (missing data means condition not met)
     - If result is any other type: throw `ExpressionEvaluationException` (e.g., "Expression for reference 'parameter' returned String instead of Boolean")
  7. Return the boolean result

- [x] **4.3** Implement `getFilterByConstraint()` as a simple getter returning the pre-translated `FilterBy` — no computation, the constraint was built at schema load time.

- [x] **4.4** Implement all remaining getter methods (`getOwnerEntityType()`, `getReferenceName()`, `getScope()`, `getDependencyType()`, `getDependentAttributes()`) as simple final-field getters.

##### Group 5: Trigger construction from schema — `FacetExpressionTriggerFactory`

- [x] **5.1** Create `FacetExpressionTriggerFactory` class in `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java` *(package changed from spec: co-located with FacetExpressionTriggerImpl)*. This is a stateless utility class with static methods that **builds** (but does NOT register) `FacetExpressionTrigger` instances from `ReferenceSchemaContract` data. Registration in `CatalogExpressionTriggerRegistry` is a separate concern handled by the caller (Task 7.1's `buildAndRegisterTriggers` method). Follows the `AccessedDataFinder` pattern (static factory method, no public constructor).

- [x] **5.2** Implement `static List<FacetExpressionTrigger> buildTriggersForReference(String ownerEntityType, ReferenceSchemaContract referenceSchema)`:
  1. Call `referenceSchema.getFacetedPartiallyInScopes()` to get the `Map<Scope, Expression>`
  2. If empty, return empty list (no triggers needed — reference is faceted unconditionally or not at all)
  3. For each `(Scope, Expression)` entry:
     a. Call `AccessedDataFinder.findAccessedPaths(expression)` to get `List<List<PathItem>>`
     b. Classify paths to determine `DependencyType` — if any path starts with `$reference.referencedEntity`, the type is `REFERENCED_ENTITY_ATTRIBUTE`; if any starts with `$reference.groupEntity`, the type is `GROUP_ENTITY_ATTRIBUTE`. If BOTH are present in the same expression, create one trigger per dependency type (the same expression may need to fire from both sides).
     c. Extract `dependentAttributes` from paths — for `REFERENCED_ENTITY_ATTRIBUTE`, collect attribute names from `$reference.referencedEntity.attributes['x']` paths; for `GROUP_ENTITY_ATTRIBUTE`, collect from `$reference.groupEntity?.attributes['x']` paths. If the expression only uses local paths (`$entity.*`, `$reference.attributes['x']`), the trigger has `DependencyType` = none and is purely local — skip cross-entity trigger creation (local triggers are handled inline in `ReferenceIndexMutator`).
     d. Build proxy class factories and state recipe via WBS-02's infrastructure
     e. Call `ExpressionToQueryTranslator.translate(expression, referenceName)` to get the `FilterBy` constraint. If translation throws `NonTranslatableExpressionException`, propagate as schema validation error.
     f. Construct `FacetExpressionTriggerImpl` with all collected artifacts
  4. Return the list of built triggers

- [x] **5.3** Handle expressions with ONLY local dependencies (i.e., expressions that reference only `$entity.*` and `$reference.attributes['x']`, with NO cross-entity paths). These expressions still need trigger instances for local evaluation (the `evaluate()` path), but they do NOT need cross-entity registry entries or a `DependencyType` value. **Design decision: skip cross-entity trigger construction for local-only expressions.** The factory builds a `FacetExpressionTrigger` for local evaluation (with `getDependencyType()` returning `null`, `getDependentAttributes()` returning an empty set, and `getFilterByConstraint()` throwing `UnsupportedOperationException`), but does NOT register it in `CatalogExpressionTriggerRegistry`. Instead, `ReferenceIndexMutator` obtains the trigger directly from `EntityIndexLocalMutationExecutor.getTriggerFor(referenceName, scope)` (which is backed by the entity schema's trigger map, not the cross-entity registry) and calls `evaluate()` inline. This means:
  - `getDependencyType()` becomes `@Nullable` on the `ExpressionIndexTrigger` interface (returns `null` for local-only triggers)
  - `getFilterByConstraint()` throws `UnsupportedOperationException` for local-only triggers (no cross-entity evaluation path exists)
  - `getDependentAttributes()` returns an empty set for local-only triggers (no cross-entity attributes to track)
  - The factory returns local-only triggers in the result list, but the caller (Task 7.1) skips registry registration for triggers where `getDependencyType() == null`

- [x] **5.4** Handle mixed-dependency expressions — expressions that combine local paths (`$entity.*`, `$reference.attributes['x']`) with cross-entity paths (`$reference.groupEntity?.*`, `$reference.referencedEntity.*`). The expression is translated to a single `FilterBy` tree (per WBS-01 AD 18 — no expression partitioning). **Clarification on Task 5.2b vs 5.4 boundary:** Task 5.2b handles expressions that reference BOTH `$reference.groupEntity?.*` AND `$reference.referencedEntity.*` (two cross-entity dependency types in a single expression) — these produce two triggers, one per `DependencyType`, each with its own `dependentAttributes` set (per 5.2c), but both carrying the same full `FilterBy` tree. Task 5.4 handles expressions that mix cross-entity paths with local paths — the `DependencyType` is determined by the cross-entity portion only, and `dependentAttributes` contains only cross-entity attributes. For an expression like `$reference.groupEntity?.attributes['a'] && $reference.referencedEntity.attributes['b'] && $entity.attributes['c']`, the behavior combines both rules: two triggers are produced (per 5.2b), one with `GROUP_ENTITY_ATTRIBUTE` / `dependentAttributes={"a"}` and one with `REFERENCED_ENTITY_ATTRIBUTE` / `dependentAttributes={"b"}`, both carrying the full `FilterBy` tree (which includes the local `$entity.attributes['c']` condition).

##### Group 6: Trigger construction for `ReflectedReferenceSchema`

- [x] **6.1** Ensure `buildTriggersForReference()` works transparently with `ReflectedReferenceSchema`. Since `ReflectedReferenceSchema.getFacetedPartiallyInScopes()` already resolves inherited expressions (returning the source schema's expressions when `facetedInherited == true`), the factory method receives the correct resolved expressions without special-casing. Verify this in a test.

- [x] **6.2** Document that when a source schema's `facetedPartially` expression changes, all reflected schemas that inherit from it will have their triggers rebuilt. The cascade works through:
  1. Source schema change triggers `EntityCollection.updateSchema()` -> `exchangeSchema()` -> `catalog.entitySchemaUpdated()`
  2. `refreshReflectedSchemas()` (line 1981) updates all `ReflectedReferenceSchema` instances via `withReferencedSchema(originalReference)`
  3. The trigger rebuild logic (in the schema load time hook) rebuilds triggers for the owning entity type, which picks up the new inherited expressions

##### Group 7: Schema load time integration — trigger build hook

- [ ] **7.1** Create a `buildAndRegisterTriggers(EntitySchema schema)` method (either on `EntityCollection` or as a static utility) that iterates all reference schemas in the entity schema, calls `FacetExpressionTriggerFactory.buildTriggersForReference()` for each, and registers the results in `CatalogExpressionTriggerRegistry` (out of scope for this WBS — WBS-04). The method should:
  1. Clear any existing triggers for this entity type in the registry
  2. For each `ReferenceSchemaContract` in `schema.getReferences().values()`:
     a. Call `buildTriggersForReference(schema.getName(), referenceSchema)`
     b. For each returned trigger with `getDependencyType() != null` (cross-entity triggers), register it under `(mutatedEntityType, dependencyType)` keys in the cross-entity registry
     c. For each returned trigger with `getDependencyType() == null` (local-only triggers), store it in the entity schema's local trigger map (for retrieval via `EntityIndexLocalMutationExecutor.getTriggerFor()`) but do NOT register in the cross-entity registry
  3. Handle both direct `ReferenceSchema` and `ReflectedReferenceSchema` instances uniformly (inheritance is already resolved)

- [ ] **7.2** Identify the hook point in `EntityCollection.exchangeSchema()` (line 2060-2071) where trigger rebuilding should be invoked. After the schema exchange succeeds and `this.catalog.entitySchemaUpdated(updatedSchema)` is called, add a call to trigger rebuilding. **Note:** the actual wiring of this hook is out of scope for WBS-03 (it belongs to the schema wiring WBS), but the method must be designed to be callable from this point.

- [ ] **7.3** Ensure non-translatable expressions are rejected at schema load time. If `ExpressionToQueryTranslator.translate()` throws `NonTranslatableExpressionException` during trigger construction, the exception must propagate up through `buildAndRegisterTriggers()` -> `exchangeSchema()` -> `updateSchema()`, causing the schema change to be rejected. The existing `try/catch` in `updateSchema()` (lines 978-983) already reverts the schema on `RuntimeException`, so no additional error handling is needed — just let the exception propagate.

##### Group 8: Integration readiness

- [ ] **8.1** Document the integration contract for downstream WBS tasks:
  - `ReferenceIndexMutator` integration (WBS-09): the `isFaceted()` guard remains unchanged; a new check for `facetedPartially` expression is added AFTER `isFaceted()` returns true; the trigger's `evaluate()` is called to determine whether to add/remove the facet
  - `CatalogExpressionTriggerRegistry` (WBS-04): triggers are registered under `(mutatedEntityType, dependencyType)` composite keys; the registry provides `getTriggers(String entityType, DependencyType type): List<ExpressionIndexTrigger>`
  - Cross-entity executor: retrieves triggers from the registry, calls `getFilterByConstraint()`, parameterizes the `FilterBy` with PK-scoping, evaluates against the target collection's indexes

### Test Cases

#### Test Class 1: `DependencyTypeTest` — DELETED

**Deleted per review:** Tests verified Java enum infrastructure (valueOf, ordinal), providing no value. `DependencyType` is tested transitively via `FacetExpressionTriggerFactoryTest` and `FacetExpressionTriggerImplTest`.

---

#### Test Class 2: `ExpressionIndexTriggerContractTest` — DELETED

**Deleted per review:** Reflective interface signature tests provide no value — type hierarchy is verified transitively by `FacetExpressionTriggerImplTest.TypeMarkerTest` (`shouldBeInstanceOfFacetExpressionTrigger`, `shouldBeInstanceOfExpressionIndexTrigger`).

---

#### Test Class 3: `FacetExpressionTriggerImplTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerImplTest.java` *(package changed from spec)*

**22 tests implemented, organized in 8 `@Nested` groups. Uses real objects (no mocks).**

##### Category: Construction and getter correctness (`ConstructionAndGetterTest`)

- [x] `shouldReturnOwnerEntityType` — verifies `getOwnerEntityType()` returns `"product"`
- [x] `shouldReturnReferenceName` — verifies `getReferenceName()` returns `"parameter"`
- [x] `shouldReturnLiveScope` / `shouldReturnArchivedScope` — verifies both scope values *(spec asked for one test, implemented as two)*
- [x] `shouldReturnReferencedEntityAttributeDependencyType` — verifies `REFERENCED_ENTITY_ATTRIBUTE`
- [x] `shouldReturnGroupEntityAttributeDependencyType` — verifies `GROUP_ENTITY_ATTRIBUTE`
- [x] `shouldReturnDependentAttributes` — verifies `Set.of("status", "code")`

##### Category: FilterBy constraint handling (`FilterByConstraintTest`)

- [x] `shouldReturnSameFilterByInstanceOnRepeatedCalls` — merged from spec's `should_return_same_FilterBy_instance_on_every_call` + `should_not_recompute_FilterBy_on_each_invocation` *(1000-iteration loop removed — no value for final-field getter)*
- [x] `shouldReturnNonNullFilterBy` — verifies non-null FilterBy
- [x] `shouldThrowForLocalOnlyTriggerGetFilterBy` — verifies `UnsupportedOperationException` with message containing reference name

##### Category: Immutability (`ImmutabilityTest`)

- [x] `shouldReturnUnmodifiableDependentAttributesSet` — verifies `UnsupportedOperationException` on `add()`
- [x] `shouldDefensivelyCopyInputSet` — verifies external mutation doesn't affect trigger
- ~~`should_be_usable_from_multiple_threads_concurrently`~~ — **DELETED per review:** immutability is a structural property (all fields `final`, `Set.copyOf()`), not testable at runtime. `@ThreadSafe` annotation on `ExpressionIndexTrigger` makes the requirement explicit.

##### Category: `evaluate()` — expression returns true (`ConstantExpressionEvaluationTest` + `EntityAttributeEvaluationTest`)

- [x] `shouldReturnTrueForConstantTrueExpression` — constant `true` expression
- [x] `shouldEvaluateEntityAttributeExpressionToTrue` — `$entity.attributes['status'] == 'ACTIVE'` with real StorageParts containing `status = "ACTIVE"`

##### Category: `evaluate()` — expression returns false

- [x] `shouldReturnFalseForConstantFalseExpression` — constant `false` expression
- [x] `shouldEvaluateEntityAttributeExpressionToFalse` — `$entity.attributes['status'] == 'ACTIVE'` with `status = "INACTIVE"`

##### Category: `evaluate()` — non-boolean and error results (`NonBooleanResultErrorTest`)

- [ ] `should_treat_null_expression_result_as_false` — **NOT IMPLEMENTED** — needs expression that evaluates to null (accessing non-existent attribute)
- [x] `shouldThrowForStringExpressionResult` — verifies `ExpressionEvaluationException` with message containing `"String"`
- [x] `shouldThrowForIntegerExpressionResult` — verifies `ExpressionEvaluationException` with message containing type name
- [ ] `should_propagate_ExpressionEvaluationException_from_catch_all_partial` — **NOT IMPLEMENTED** — requires specific CatchAllPartial proxy setup

##### Category: `evaluate()` — storage part interaction

- [ ] `should_fetch_only_required_storage_parts_from_accessor` — **NOT IMPLEMENTED** — requires mock/spy on storage accessor to count calls
- [ ] `should_handle_null_valued_attribute_in_storage_parts` — **NOT IMPLEMENTED** — needs null-attribute storage part setup

##### Category: `evaluate()` — nested proxy wiring

- [ ] `should_wire_group_entity_proxy_for_group_entity_expression` — **NOT IMPLEMENTED** — requires multi-level StoragePart setup for group entity proxy chain
- [ ] `should_wire_referenced_entity_proxy_for_referenced_entity_expression` — **NOT IMPLEMENTED** — requires multi-level StoragePart setup for referenced entity proxy chain

##### Category: `evaluate()` — storage accessor failure modes

- [ ] `should_throw_when_required_storage_part_is_missing` — **NOT IMPLEMENTED** — requires null StoragePart return from accessor
- [ ] `should_handle_missing_referenced_entity_storage_parts_gracefully` — **NOT IMPLEMENTED** — requires deleted referenced entity scenario

##### Category: `FacetExpressionTrigger` marker type (`TypeMarkerTest`)

- [x] `shouldBeInstanceOfFacetExpressionTrigger` — verifies `instanceof FacetExpressionTrigger`
- [x] `shouldBeInstanceOfExpressionIndexTrigger` — verifies `instanceof ExpressionIndexTrigger`

---

#### Test Class 4: `FacetExpressionTriggerFactoryTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactoryTest.java` *(package changed from spec)*

**14 tests implemented, organized in 7 `@Nested` groups. Uses real schema objects via `InternalEntitySchemaBuilder` (no mocks).**

##### Category: Empty / no-expression references (`NoExpressionTest`)

- [x] `shouldReturnEmptyListWhenNoFacetedPartially` — reference with no `facetedPartially` expressions
- [x] `shouldReturnEmptyListWhenFacetedWithoutPartial` — reference is faceted but has no partial expression

##### Category: Single-scope trigger construction — covered by `LocalOnlyExpressionTest`

- [x] `shouldBuildLocalOnlyTriggerForEntityAttribute` — single LIVE scope, verifies ownerEntityType, referenceName, scope, null dependencyType *(covers spec's `should_build_one_trigger_for_LIVE_scope`, `should_set_ownerEntityType_from_parameter`, `should_set_referenceName_from_schema`)*
- [x] `shouldThrowOnFilterByForLocalOnlyTrigger` — verifies `UnsupportedOperationException` for local-only
- [x] `shouldBuildLocalOnlyTriggerForReferenceAttribute` — `$reference.attributes['order'] > 0` is local-only *(covers `should_produce_no_cross_entity_trigger_for_reference_attribute_only_expression`)*
- [x] `shouldBuildLocalOnlyTriggerForConstantExpression` — `true` expression produces local-only trigger *(covers `should_produce_no_cross_entity_trigger_for_local_only_expression`)*

##### Category: Multi-scope trigger construction (`MultipleScopesTest`)

- [x] `shouldBuildTriggersForMultipleScopes` — LIVE + ARCHIVED with different expressions, verifies independent triggers per scope *(covers `should_build_two_triggers_for_both_LIVE_and_ARCHIVED_scopes` + `should_produce_independent_triggers_per_scope`)*

##### Category: DependencyType classification (`ReferencedEntityTriggerTest` + `GroupEntityTriggerTest`)

- [x] `shouldBuildReferencedEntityAttributeTrigger` — `$reference.referencedEntity.attributes['code']` → `REFERENCED_ENTITY_ATTRIBUTE`, dependent attributes `{"code"}`, non-null FilterBy *(covers classification + single dependent attribute extraction)*
- [x] `shouldBuildGroupEntityAttributeTrigger` — `$reference.groupEntity?.attributes['status']` → `GROUP_ENTITY_ATTRIBUTE`, dependent attributes `{"status"}`, non-null FilterBy

##### Category: Dependent attributes extraction

- [x] `shouldCollectMultipleDependentAttributes` — `$reference.referencedEntity.attributes['code'] && ...attributes['status']` → `{"code", "status"}`
- [x] `shouldProduceCrossEntityTriggerForMixedExpression` — mixed local + cross-entity: only cross-entity trigger produced, dependent attributes contain only cross-entity attribute *(covers `should_not_include_local_attributes_in_dependent_attributes` + `should_classify_mixed_expression_by_cross_entity_portion`)*

##### Category: Mixed-dependency expressions (`DualAndMixedDependencyTest`)

- [x] `shouldBuildTwoTriggersForDualDependency` — `referencedEntity.attributes['code'] && groupEntity.attributes['status']` → 2 triggers, one per DependencyType, each with correct dependent attributes and non-null FilterBy

##### Category: Non-translatable expression rejection (`ImmutabilityAndErrorTest`)

- [x] `shouldPropagateNonTranslatableException` — arithmetic in cross-entity expression (`$reference.referencedEntity.attributes['price'] + 5 > 10`) throws `NonTranslatableExpressionException` *(covers both `should_throw_at_construction_time` and `should_propagate_unwrapped`)*
- [x] `shouldReturnUnmodifiableTriggerList` — returned list is unmodifiable

##### Category: ReflectedReferenceSchema inheritance — NOT IMPLEMENTED

- [ ] `should_build_correct_trigger_from_reflected_schema_with_inherited_expression` — **NOT IMPLEMENTED** — tested implicitly via Group 6.1 (factory calls `getFacetedPartiallyInScopes()` which resolves inheritance), but no explicit factory-level test with `ReflectedReferenceSchema`
- [ ] `should_use_reflected_schema_entity_type_not_source_entity_type` — **NOT IMPLEMENTED**
- [ ] `should_produce_empty_list_when_reflected_schema_does_not_inherit_facetedPartially` — **NOT IMPLEMENTED**

---

#### Test Class 5: `TriggerSchemaIntegrationTest` — NOT IMPLEMENTED

> **Status:** NOT IMPLEMENTED — requires `buildAndRegisterTriggers()` from Group 7 (depends on WBS-04 `CatalogExpressionTriggerRegistry`). The `should_construct_trigger_without_CatalogExpressionTriggerRegistry` test is already covered by `FacetExpressionTriggerImplTest` which constructs and evaluates triggers directly without a registry.

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/expression/TriggerSchemaIntegrationTest.java`

##### Category: Schema load time construction

- [ ] `should_build_triggers_for_all_references_in_entity_schema` — **BLOCKED** by Group 7 (`buildAndRegisterTriggers()`)
- [ ] `should_skip_references_without_facetedPartially` — **BLOCKED** by Group 7
- [ ] `should_produce_correct_trigger_count_for_multi_scope_multi_reference_schema` — **BLOCKED** by Group 7

##### Category: Error propagation at schema load time

- [ ] `should_reject_schema_with_non_translatable_expression` — **BLOCKED** by Group 7 (`exchangeSchema()` integration)
- [ ] `should_accept_schema_with_valid_translatable_expression` — **BLOCKED** by Group 7

##### Category: Trigger independence from registry

- [x] `should_construct_trigger_without_CatalogExpressionTriggerRegistry` — **COVERED** by existing `FacetExpressionTriggerImplTest` which constructs `FacetExpressionTriggerImpl` directly, calls `evaluate()` and `getFilterByConstraint()`, all without a registry

##### Category: ReflectedReferenceSchema cascade awareness

- [ ] `should_build_triggers_for_reflected_schema_inheriting_from_source` — **BLOCKED** by Group 7 (`buildAndRegisterTriggers()`)
- [ ] `should_rebuild_reflected_triggers_when_source_expression_changes` — **BLOCKED** by Group 7

---

#### Test Class 6: `TriggerEvaluationCachingTest` — NOT IMPLEMENTED

> **Status:** NOT IMPLEMENTED — requires `DataStoreMemoryBuffer` integration which is out of WBS-03 scope. Caching behavior is a system-level concern that will be verified by integration tests in downstream WBS tasks.

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/expression/TriggerEvaluationCachingTest.java`

##### Category: Storage part caching via DataStoreMemoryBuffer

- [ ] `should_fetch_storage_part_once_for_multiple_evaluate_calls_on_same_entity` — **BLOCKED** by `DataStoreMemoryBuffer` integration (out of WBS-03 scope)
- [ ] `should_cache_group_entity_storage_parts_across_owner_entities` — **BLOCKED** by `DataStoreMemoryBuffer` integration

---

#### Test Class 7: `TriggerScopeHandlingTest` — COVERED by existing tests

> **Status:** NOT IMPLEMENTED as a separate class — scope isolation is already covered by `FacetExpressionTriggerFactoryTest.MultipleScopesTest` (`shouldBuildTriggersForMultipleScopes`) which verifies per-scope trigger independence with different expression types per scope (local-only LIVE + cross-entity ARCHIVED).

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/expression/TriggerScopeHandlingTest.java`

##### Category: Per-scope trigger isolation

- [x] `should_produce_distinct_trigger_per_scope_with_same_expression` — **COVERED** by `FacetExpressionTriggerFactoryTest.MultipleScopesTest.shouldBuildTriggersForMultipleScopes`
- [x] `should_produce_distinct_triggers_with_different_expressions_per_scope` — **COVERED** by `FacetExpressionTriggerFactoryTest.MultipleScopesTest.shouldBuildTriggersForMultipleScopes` (LIVE = local-only, ARCHIVED = cross-entity)
- [ ] `should_not_share_FilterBy_between_scopes_when_expressions_differ` — **NOT IMPLEMENTED** — low value given the above coverage
- [ ] `should_share_FilterBy_between_scopes_when_expressions_are_identical` — **NOT IMPLEMENTED** — low value (FilterBy is always built independently per trigger)

##### Category: Scope values exhaustiveness

- [ ] `should_handle_all_Scope_enum_values` — **NOT IMPLEMENTED** — low value (Scope enum has only 2 values, both tested)

---

## ⚠️ TOBEDONE JNO

### Blocked by WBS-04: Group 7 — `buildAndRegisterTriggers()`

Tasks 7.1–7.3 require `CatalogExpressionTriggerRegistry` from WBS-04. The trigger construction (factory) and evaluation (impl) layers are complete and tested, but the schema-level orchestration cannot be implemented until WBS-04 provides the registry.

- [ ] Task 7.1: Implement `buildAndRegisterTriggers()` method — iterates all references in an `EntitySchema`, calls `FacetExpressionTriggerFactory.buildTriggersForReference()` for each, and registers the resulting triggers in the catalog-wide `CatalogExpressionTriggerRegistry`
- [ ] Task 7.2: Wire trigger rebuild into `EntityCollection.exchangeSchema()` — whenever a schema change is applied, triggers for affected references must be rebuilt and re-registered
- [ ] Task 7.3: Verify non-translatable expression rejection propagates through `exchangeSchema()` → `updateSchema()` — a `NonTranslatableExpressionException` thrown during trigger construction must prevent the schema change from being committed

### Blocked by WBS-04: Group 8 — Integration readiness documentation

- [ ] Task 8.1: Write integration readiness documentation — document how downstream WBS tasks (executor, index maintenance) consume triggers built by this WBS

### Blocked by WBS-04: Test Class 5 — `TriggerSchemaIntegrationTest`

7 of 8 spec test cases require `buildAndRegisterTriggers()` from Group 7 (1 already covered by existing `FacetExpressionTriggerImplTest`):

- [ ] `should_build_triggers_for_all_references_in_entity_schema` — needs `buildAndRegisterTriggers()` to iterate entity schema references
- [ ] `should_skip_references_without_facetedPartially` — needs `buildAndRegisterTriggers()` to filter references
- [ ] `should_produce_correct_trigger_count_for_multi_scope_multi_reference_schema` — needs `buildAndRegisterTriggers()` for schema-wide count verification
- [ ] `should_reject_schema_with_non_translatable_expression` — needs `exchangeSchema()` integration to test error propagation
- [ ] `should_accept_schema_with_valid_translatable_expression` — needs `exchangeSchema()` integration
- [ ] `should_build_triggers_for_reflected_schema_inheriting_from_source` — needs `buildAndRegisterTriggers()` to handle both source and reflected entity types
- [ ] `should_rebuild_reflected_triggers_when_source_expression_changes` — needs `buildAndRegisterTriggers()` for rebuild verification

### Blocked by DataStoreMemoryBuffer: Test Class 6 — `TriggerEvaluationCachingTest`

These tests require `DataStoreMemoryBuffer` integration which is out of WBS-03 scope — caching behavior is a system-level concern verified by integration tests in downstream WBS tasks:

- [ ] `should_fetch_storage_part_once_for_multiple_evaluate_calls_on_same_entity` — needs mock storage accessor + `DataStoreMemoryBuffer` to count and cache fetches
- [ ] `should_cache_group_entity_storage_parts_across_owner_entities` — needs per-transaction caching scope that `DataStoreMemoryBuffer` provides

### Issue: Cross-entity path without attribute access silently dropped

`FacetExpressionTriggerFactory.classifyPaths()` drops a cross-entity dependency when `extractDependentAttribute()` returns `null` (e.g., `$reference.referencedEntity.primaryKey > 5`). This is correct by design — `primaryKey` is immutable and does not need change-tracking — but the silent drop could confuse developers debugging expression triggers.

- [ ] Add `log.warn()` in `FacetExpressionTriggerFactory.classifyPaths()` when a cross-entity path has no extractable dependent attribute — file: `FacetExpressionTriggerFactory.java`, lines 169–182

### Issue: `Remove+Create` mutation conversion may lose `facetedPartially` data

`CreateReferenceSchemaMutation.combineWith(RemoveReferenceSchemaMutation)` decomposes the Create into individual Set mutations. When the Create has both `facetedInScopes` and `facetedPartiallyInScopes`, it can emit two separate `SetReferenceSchemaFacetedMutation` instances (one for scopes, one for expressions). The second replaces the first via Set+Set combining, losing the scopes. Pre-existing issue, newly reachable with `facetedPartially`.

- [ ] Fix `CreateReferenceSchemaMutation.combineWith(RemoveReferenceSchemaMutation)` to emit a single `SetReferenceSchemaFacetedMutation` carrying both `facetedInScopes` and `facetedPartiallyInScopes` — file: `CreateReferenceSchemaMutation.java`, lines 308–327

### Not implemented spec tests from Test Class 7 — `TriggerScopeHandlingTest`

Core scope isolation is already covered by `FacetExpressionTriggerFactoryTest.MultipleScopesTest`, so these have low incremental value:

- [ ] `should_not_share_FilterBy_between_scopes_when_expressions_differ` — verifies FilterBy identity differs per scope; low value since FilterBy is always built independently per trigger
- [ ] `should_share_FilterBy_between_scopes_when_expressions_are_identical` — verifies structural equality of FilterBy when expressions match; low value since translator builds each independently
- [ ] `should_handle_all_Scope_enum_values` — guards against future `Scope` additions; low value since `Scope` has only 2 values (LIVE, ARCHIVED), both tested

### Not implemented spec tests from Test Class 3 — `FacetExpressionTriggerImplTest`

- [ ] `convertResult(null)` branch — needs an expression that evaluates to `null` (e.g., accessing a non-existent attribute); requires constructing a `StoragePart` where the attribute is absent so the proxy returns `null`
- [ ] `evaluate()` with reference attribute expression (`$reference.attributes['order'] > 0`) — only entity attributes are tested through `evaluate()`; reference attributes use the same proxy mechanism but through a different path (`ReferenceContract` proxy instead of `EntityContract`)

### Not implemented spec tests from Test Class 4 — `FacetExpressionTriggerFactoryTest`

- [ ] Reflected reference schema tests: `should_build_correct_trigger_from_reflected_schema_with_inherited_expression`, `should_use_reflected_schema_entity_type_not_source_entity_type`, `should_produce_empty_list_when_reflected_schema_does_not_inherit_facetedPartially` — factory-level tests with `ReflectedReferenceSchema` input; the factory itself is agnostic to reflected vs. non-reflected (it calls `getFacetedPartiallyInScopes()` which handles inheritance transparently), so the incremental value is low
- [ ] Exception message content verification for `NonTranslatableExpressionException` — the `shouldPropagateNonTranslatableException` test verifies the exception type but not the message content; adding message assertions would guard against regressions in error reporting

### Not implemented spec test from Test Class 4 — `FacetExpressionTriggerFactoryTest`

- [ ] `localizedAttributes` path in `FacetExpressionTriggerFactory.isAttributesProperty()` — no test exercises the `LOCALIZED_ATTRIBUTES_PROPERTY` branch; would require an expression accessing `$entity.localizedAttributes[...]` which is a valid but rare path in practice
