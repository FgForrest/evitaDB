# WBS-08: Source-Side Detection and `popIndexImplicitMutations` — Cross-Entity Trigger Firing

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Implement the source-side detection logic within `EntityIndexLocalMutationExecutor` that detects
when a mutation on the current entity could affect facet indexing in OTHER entity collections.
The source side is responsible only for detecting that a relevant change occurred, consulting the
`CatalogExpressionTriggerRegistry`, and creating `ReevaluateFacetExpressionMutation` instances
wrapped in `EntityIndexMutation` envelopes. The source does NOT evaluate expressions, does NOT
determine add/remove direction, and does NOT resolve affected PKs in the target collection.

## Scope

### In Scope

- Implementation of `popIndexImplicitMutations()` method on `EntityIndexLocalMutationExecutor`
- The `IndexImplicitMutations` record that carries `EntityIndexMutation[]` (record definition
  owned by WBS-05; this WBS implements the logic that populates it)
- Detection of which attributes changed in the current mutation batch
- Old value != new value optimization (skip when attribute value did not actually change)
- Consultation of `CatalogExpressionTriggerRegistry` to find matching triggers keyed by the
  mutated entity's type
- Creation of `ReevaluateFacetExpressionMutation` for each matching trigger
- Grouping mutations by target entity type and wrapping in `EntityIndexMutation`
- Handling `EntityRemoveMutation` on referenced/group entities as a cross-entity trigger source
  (Domain 2b/2c) — entity removal changes all accessed properties, so all dependent expressions
  must be re-evaluated
- Defining the dispatch contract: `popIndexImplicitMutations()` returns `IndexImplicitMutations`
  that the `LocalMutationExecutorCollector` dispatch loop (WBS-10) consumes

### Out of Scope

- Expression evaluation (handled by WBS-07 target-side executor)
- PK resolution of affected entities in the target collection (handled by WBS-07)
- Add/remove direction determination (handled by WBS-07)
- `CatalogExpressionTriggerRegistry` internals (handled by WBS-04)
- `ReevaluateFacetExpressionMutation` and `EntityIndexMutation` type definitions (handled by
  WBS-05)
- `IndexMutationExecutorRegistry` and `ReevaluateFacetExpressionExecutor` (handled by WBS-07)
- Schema mutation triggers (Domain 2d — full re-index on `facetedPartially` expression change)

## Dependencies

### Depends On

- **WBS-04** — provides `CatalogExpressionTriggerRegistry` which this task consults to find
  triggers keyed by `(entityType, dependencyType, attributeName)`
- **WBS-05** — provides the mutation types: `ReevaluateFacetExpressionMutation`,
  `EntityIndexMutation`, `IndexImplicitMutations` record, and `DependencyType` enum

### Depended On By

- **WBS-10** — `LocalMutationExecutorCollector` dispatch integration routes the output of
  `popIndexImplicitMutations()` to target collections

## Technical Context

### Existing precedent: how `popImplicitMutations` works

The source-side detection follows the same architectural pattern as the existing
`popImplicitMutations()` mechanism in `ContainerizedLocalMutationExecutor`:

1. Apply explicit mutations to both entity indexes and storage
2. `popImplicitMutations()` generates `ImplicitMutations` (local + external)
3. Local mutations are applied to the same entity's index and storage executors
4. External mutations recursively call `applyMutations()` on other entity collections
5. Implicit mutations are NOT written to WAL — they are regenerated on replay
6. `LocalMutationExecutorCollector` handles nesting via a `level` counter

The new `popIndexImplicitMutations()` adds a parallel post-processing step that produces a
different record type (`IndexImplicitMutations` instead of `ImplicitMutations`) with a separate
dispatch path.

### Source-side creation pipeline (5 steps)

The `popIndexImplicitMutations()` method on `EntityIndexLocalMutationExecutor` performs:

1. **Detect changed attributes** — identifies which attributes were modified in the current
   mutation batch
2. **Old != new check** — for each changed attribute, verifies that the old value is actually
   different from the new value. If equal, skips (optimizes no-change scenarios; same pattern as
   existing attribute index updates)
3. **Consult `CatalogExpressionTriggerRegistry`** — finds matching triggers keyed by the mutated
   entity's type. The registry returns triggers that identify which OTHER entity schemas have
   `facetedPartially` expressions depending on this entity's data
4. **Create `ReevaluateFacetExpressionMutation`** — for each matching trigger, creates a mutation
   instance. The source does NOT evaluate the expression and does NOT determine the add/remove
   direction. It only signals that a relevant change occurred
5. **Group by target entity type** — mutations are grouped by target entity type and wrapped in
   `EntityIndexMutation` envelopes

### `EntityRemoveMutation` as cross-entity trigger source

Entity removal is classified as a cross-entity trigger source in Domain 2b and 2c:

- **Domain 2b (referenced entity removal):** When an `EntityRemoveMutation` is applied to a
  referenced entity and any expression uses `$reference.referencedEntity.*`, all accessed
  properties change (null-safe paths return `null`, non-null-safe paths may throw). All
  dependent expressions must be re-evaluated.
- **Domain 2c (group entity removal):** When an `EntityRemoveMutation` is applied to a group
  entity and any expression uses `$reference.groupEntity?.*`, group entity removal changes
  all accessed properties (null-safe `?.` returns `null`). All dependent expressions must be
  re-evaluated.

Entity removal creates the same `ReevaluateFacetExpressionMutation` as attribute changes. The
target-side executor handles the actual expression evaluation and determines the result.

### Domain mutation classification tables (cross-entity triggers)

| Domain | Mutation | Triggers re-evaluation when... |
|--------|----------|-------------------------------|
| 2b | Attribute change on referenced entity | Expression uses `$reference.referencedEntity.attributes['x']` |
| 2b | `EntityRemoveMutation` on referenced entity | Expression uses `$reference.referencedEntity.*` |
| 2c | Attribute change on group entity | Expression uses `$reference.groupEntity?.attributes['x']` |
| 2c | `EntityRemoveMutation` on group entity | Expression uses `$reference.groupEntity?.*` |
| 2d | `facetedPartially` expression changes | Must re-index ALL entities for that reference type |

### AD 4: Source detects, target decides

The source `EntityIndexLocalMutationExecutor` detects relevant attribute changes (old != new),
consults `CatalogExpressionTriggerRegistry`, and creates `ReevaluateFacetExpressionMutation`
instances. The source does NOT evaluate the expression and does NOT determine add/remove
direction — it only signals that a relevant change occurred. PK resolution, expression evaluation
(via `FilterBy` query), and add/remove decisions happen entirely on the target side.

### Fan-out examples (source side only)

**Purely cross-entity expression:**
Group entity G (parameterGroup "Color", PK=99) changes `inputWidgetType` from `'CHECKBOX'` to
`'RADIO'`. Expression: `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

Source executor (ParameterGroup's `EntityIndexLocalMutationExecutor`):
1. Detects that attribute `inputWidgetType` changed on entity PK 99
2. Checks old value (`'CHECKBOX'`) != new value (`'RADIO'`) — actual change confirmed
3. Consults `CatalogExpressionTriggerRegistry` — finds a trigger for Product/"parameter"
   depending on `GROUP_ENTITY_ATTRIBUTE` / `inputWidgetType`
4. Creates a `ReevaluateFacetExpressionMutation` (does NOT evaluate the expression or determine
   add/remove direction):

```java
new EntityIndexMutation(
    "product",                             // target collection
    new IndexMutation[] {
        new ReevaluateFacetExpressionMutation(
            "parameter",                   // reference with the expression
            99,                            // group entity PK that changed
            DependencyType.GROUP_ENTITY_ATTRIBUTE,
            Scope.DEFAULT_SCOPE
        )
    }
)
```

**Mixed-dependency expression:**
Same scenario, but expression is:
`$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`

Source side (ParameterGroup executor): Identical to above — detects change, confirms old != new,
creates `ReevaluateFacetExpressionMutation`. Does NOT evaluate the expression. The `$entity`
part of the mixed expression is the target's concern.

**Multi-source cross-entity expression:**
Expression: `$reference.groupEntity?.attributes['status'] == 'ACTIVE' || $reference.referencedEntity.attributes['status'] == 'PREVIEW'`

This expression depends on TWO cross-entity sources. The registry has entries for BOTH:
- `("ParameterGroup", GROUP_ENTITY_ATTRIBUTE, "status")` -> trigger
- `("Parameter", REFERENCED_ENTITY_ATTRIBUTE, "status")` -> trigger

When group entity PK=99 changes `status` from `'ACTIVE'` to `'INACTIVE'`:
Source side: Creates `ReevaluateFacetExpressionMutation` targeting Product. Only the trigger
matching the mutated entity type fires; the other trigger fires independently if/when the
other entity type changes.

### Dispatch in `LocalMutationExecutorCollector` (implementation owned by WBS-10)

Two separate dispatch loops, each calling the appropriate executor method and consuming
the executor-specific record. The code below illustrates the intended dispatch pattern
for context — the actual implementation is WBS-10's responsibility:

```java
// Step 5a: container implicit mutations (existing — unchanged)
ImplicitMutations containerImplicit = changeCollector.popImplicitMutations(
    localMutations, generateImplicitMutations
);
for (LocalMutation<?, ?> localMutation : containerImplicit.localMutations()) {
    entityIndexUpdater.applyMutation(localMutation);
    changeCollector.applyMutation(localMutation);
}
for (EntityMutation externalMutation : containerImplicit.externalMutations()) {
    ServerEntityMutation sem = (ServerEntityMutation) externalMutation;
    this.catalog.getCollectionForEntityOrThrowException(externalMutation.getEntityType())
        .applyMutations(session, externalMutation, ...);
}

// Step 5b: index trigger mutations (NEW — separate dispatch path, separate record)
//   The index executor consults CatalogExpressionTriggerRegistry, checks old != new
//   for relevant attributes, and creates ReevaluateFacetExpressionMutation instances
//   wrapped in EntityIndexMutation per target schema. The target collection dispatches
//   each to the appropriate IndexMutationExecutor.
IndexImplicitMutations indexImplicit = entityIndexUpdater.popIndexImplicitMutations(
    localMutations
);
for (EntityIndexMutation indexMutation : indexImplicit.indexMutations()) {
    this.catalog.getCollectionForEntityOrThrowException(indexMutation.getEntityType())
        .applyIndexMutations(indexMutation);
}
```

**Why container first:** Container mutations may create/update references that the index
triggers need to see. Index triggers must operate on fully consistent storage state.

### De-duplication

Since the target-side executor performs idempotent index operations, de-duplication happens
naturally. If both a local trigger and a cross-entity trigger fire for the same entity, the
second operation is a no-op at the index level. Multiple triggers for the same target collection
with identical `FilterBy` constraints each run their own `evaluateFilter()` independently — this
is an optimization opportunity for the future but not a correctness issue.

## Key Interfaces

### `IndexImplicitMutations` record

```java
// in EntityIndexLocalMutationExecutor (or a new dedicated interface)
record IndexImplicitMutations(
    @Nonnull EntityIndexMutation[] indexMutations
) { }
```

### `popIndexImplicitMutations()` method signature

```java
// on EntityIndexLocalMutationExecutor
@Nonnull
IndexImplicitMutations popIndexImplicitMutations(
    @Nonnull List<? extends LocalMutation<?, ?>> inputMutations
);
```

`EntityIndexLocalMutationExecutor` does NOT implement `ConsistencyCheckingLocalMutationExecutor`.
It exposes its own dedicated method with its own dedicated return type.

### `ImplicitMutations` record (existing, unchanged)

```java
// in ConsistencyCheckingLocalMutationExecutor (existing — no changes)
record ImplicitMutations(
    @Nonnull LocalMutation<?, ?>[] localMutations,
    @Nonnull EntityMutation[] externalMutations
) { }
```

## Acceptance Criteria

1. `EntityIndexLocalMutationExecutor.popIndexImplicitMutations()` detects attribute changes in
   the current mutation batch and produces `IndexImplicitMutations` containing the appropriate
   `EntityIndexMutation` envelopes
2. The old != new optimization skips triggers when an attribute is set to its current value
3. `CatalogExpressionTriggerRegistry` is consulted with the mutated entity's type to find
   matching triggers across all dependency types (`REFERENCED_ENTITY_ATTRIBUTE`,
   `GROUP_ENTITY_ATTRIBUTE`)
4. Each matching trigger produces a `ReevaluateFacetExpressionMutation` carrying the reference
   name, the mutated entity PK, the `DependencyType`, and the `Scope`
5. Mutations are grouped by target entity type and wrapped in `EntityIndexMutation` envelopes
6. The source side does NOT evaluate expressions, does NOT determine add/remove direction, and
   does NOT resolve affected PKs in the target collection
7. `EntityRemoveMutation` on referenced/group entities produces `ReevaluateFacetExpressionMutation`
   just like attribute changes (Domain 2b/2c)
8. The `popIndexImplicitMutations()` output contract is compatible with the WBS-10 dispatch
   loop: `IndexImplicitMutations.indexMutations()` provides `EntityIndexMutation` envelopes
   that can be routed to target collections. The actual dispatch implementation is WBS-10's
   responsibility (calling `popIndexImplicitMutations()` AFTER `popImplicitMutations()`)
9. When a multi-source expression depends on multiple cross-entity sources (e.g., both
   `GROUP_ENTITY_ATTRIBUTE` and `REFERENCED_ENTITY_ATTRIBUTE`), each source fires independently
   when its data changes — the registry has entries for BOTH dependency types
10. `IndexImplicitMutations` are NOT written to WAL — they are regenerated on replay (same
    pattern as existing `ImplicitMutations`)

## Implementation Notes

- The `popIndexImplicitMutations()` method must track which attributes were modified during the
  current mutation batch. The executor should cache old attribute values before mutation and
  compare with new values after mutation, consistent with how existing attribute index updates
  detect actual changes.
- The method receives `inputMutations` (the list of explicit mutations applied in the batch) to
  determine which attributes were touched. This allows filtering to only the relevant attributes
  rather than scanning all attributes.
- For `EntityRemoveMutation`, the source must fire triggers for ALL dependency types that the
  removed entity participates in (both as referenced entity and as group entity). Entity removal
  effectively changes all accessed properties to `null`.
- The `CatalogExpressionTriggerRegistry` lookup key is `(mutatedEntityType, dependencyType,
  attributeName)`. For entity removal, the source should fire for all registered attribute names
  under the relevant dependency types for the removed entity's type.
- Mutation ordering within a single entity is guaranteed by `LocalMutation#compareTo` — attribute
  mutations sort before reference mutations, and `ContainerizedLocalMutationExecutor` caches
  attribute values locally. This ensures that when multiple mutations are applied to the same
  entity (e.g., `[SetAttribute('code','ABC'), InsertReference('param',5)]`), the attribute change
  is visible when the reference mutation's expression evaluation occurs.
- The `level` counter in `LocalMutationExecutorCollector` handles nesting — implicit mutations
  can themselves trigger further implicit mutations. The same nesting mechanism applies to
  index implicit mutations.
- **Localized attribute over-firing:** The registry lookup in step 3.3c uses
  `attributeKey.attributeName()`, discarding the `Locale` component of the `AttributeKey`.
  This means if the same attribute name has different values in different locales, a change in
  locale `en` will also fire triggers whose expressions only reference locale `cs`. This is
  intentional over-firing that is safe because the target-side executor performs idempotent
  index operations — re-evaluating an expression that did not actually change results in a
  no-op. Precise per-locale filtering would require the trigger to declare which locales it
  reads, which adds complexity not warranted at this stage. The old-value cache DOES use the
  full `AttributeKey` (including locale) as the map key, so the old-vs-new comparison is
  locale-correct — only actual value changes fire triggers. The over-firing only occurs when
  multiple locales of the same attribute name are registered as trigger sources.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### `EntityIndexLocalMutationExecutor` — structure and state

- **Path:** `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java`
- **Declaration:** `public class EntityIndexLocalMutationExecutor implements LocalMutationExecutor` (line 114)
- **Does NOT** implement `ConsistencyCheckingLocalMutationExecutor` — confirmed
- **Key fields:**
  - `private final String entityType` (line 118) — initialized from `schemaAccessor.get().getName()` at line 202
  - `@Getter private final WritableEntityStorageContainerAccessor containerAccessor` (line 123) — this IS the `ContainerizedLocalMutationExecutor` passed from `EntityCollection.applyMutations()`
  - `private final Supplier<EntitySchema> schemaAccessor` (line 140) — bound to `EntityCollection::getInternalSchema`
  - `@Nullable private List<? extends LocalMutation<?, ?>> localMutations` (line 180) — set during `prepare()`, null for removals
  - `private EntityStoragePartExistingDataFactory storagePartExistingDataFactory` (line 160) — memoized, provides old attribute values
  - `private final LinkedList<ToIntBiFunction<IndexType, Target>> entityPrimaryKey` (line 128) — stack of PK resolvers
  - `private Scope memoizedScope` (line 184) — cached entity scope
- **Constructor** (lines 186-206): receives `containerAccessor`, `entityPrimaryKey` (int), `entityIndexCreatingAccessor`, `catalogIndexCreatingAccessor`, `schemaAccessor`, `priceInternalIdSupplier`, `undoOnError`, `fullEntitySupplier`. Does NOT receive `Catalog` or `CatalogExpressionTriggerRegistry` — a new constructor parameter is needed.
- **No `getEntityType()` getter** — the `entityType` field is `private final` with no accessor. However, since `popIndexImplicitMutations()` will be on the same class, it has direct access.
- **`getEntitySchema()`** (line 816): package-private, returns `this.schemaAccessor.get()`
- **`getPrimaryKeyToIndex(IndexType, Target)`** (line 834): package-private, returns entity PK from the stack. For the "normal" case `getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW)` returns the entity PK.

##### Mutation application flow and old-value timing

The execution order in `LocalMutationExecutorCollector.execute()` (lines 250-253) is critical:
```
for (LocalMutation<?, ?> localMutation : localMutations) {
    entityIndexUpdater.applyMutation(localMutation);  // INDEX first
    changeCollector.applyMutation(localMutation);       // STORAGE second
}
```

**Key insight:** When `entityIndexUpdater.applyMutation()` runs for an `AttributeMutation`, the `ContainerizedLocalMutationExecutor` has NOT yet applied that mutation to its storage parts. Therefore, `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier().getAttributeValue(key)` returns the **old** value at that moment.

However, by the time `popIndexImplicitMutations()` would be called (after ALL mutations and after `popImplicitMutations()`), the storage parts already contain the **new** values. Therefore, the old-value caching must happen DURING `applyMutation()`, not during `popIndexImplicitMutations()`.

**Approach for old-value caching:** During `EntityIndexLocalMutationExecutor.applyMutation()`, when processing an `AttributeMutation`, capture the old value from `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier().getAttributeValue(key)` and store it in a map (`Map<AttributeKey, Serializable>` or `Map<String, Serializable>` for attribute names only). This map is consulted in `popIndexImplicitMutations()` to compare old vs. new values.

The new value can be obtained from the mutation itself:
- `UpsertAttributeMutation.getAttributeValue()` — the new value
- `RemoveAttributeMutation` — the new value is null (attribute removed)
- `ApplyDeltaAttributeMutation.getAttributeValue()` — the delta value (the new absolute value can be computed, but for trigger purposes, any delta implies a change — including delta=0, which is safe over-firing handled idempotently by the target-side executor)

For the old != new check, the simplest approach is:
1. During `applyMutation()` for `AttributeMutation`: capture old value from storage, store `(attributeName, oldValue)` in a map
2. During `popIndexImplicitMutations()`: for each captured attribute name, compare old value with the new value. The new value is available from the mutation (for `UpsertAttributeMutation`) or is `null` (for `RemoveAttributeMutation`)

##### `prepare()` and `EntityRemoveMutation` handling

- **Upserts:** `prepare(localMutations)` is called (line 240 in `LocalMutationExecutorCollector`), which stores the mutation list in `this.localMutations`
- **Removals:** `prepare()` is NOT called (line 225-236). Instead, `computeLocalMutationsForEntityRemoval(entity)` generates `RemoveAttributeMutation`, `RemoveReferenceMutation`, etc. These are applied one by one in the same loop.
- **Removal detection:** `this.containerAccessor.isEntityRemovedEntirely()` returns `true` when the entity is being removed (set in `ContainerizedLocalMutationExecutor` constructor based on `entityMutation instanceof EntityRemoveMutation` — line 877/891)

##### How `EntityCollection` creates the executor

`EntityCollection.applyMutations()` (lines 2452-2489) creates both executors:
```java
final ContainerizedLocalMutationExecutor changeCollector = new ContainerizedLocalMutationExecutor(...);
final EntityIndexLocalMutationExecutor entityIndexUpdater = new EntityIndexLocalMutationExecutor(
    changeCollector,          // containerAccessor
    entityPrimaryKey,
    this.entityIndexCreator,
    this.catalog.getCatalogIndexMaintainer(),
    this::getInternalSchema,
    this::nextInternalPriceId,
    undoOnError,
    () -> localMutationExecutorCollector.getFullEntityContents(changeCollector).entity()
);
```

The `CatalogExpressionTriggerRegistry` is available via `this.catalog.getExpressionTriggerRegistry()` (WBS-04, Group 6.2). To pass it to the executor, add a new constructor parameter: `@Nonnull Supplier<CatalogExpressionTriggerRegistry> triggerRegistrySupplier`. Using a `Supplier` is consistent with the existing `schemaAccessor` pattern and ensures the registry is read at the correct transactional point.

##### `ExistingAttributeValueSupplier` — old-value retrieval mechanism

- **Interface:** `evita_engine/src/main/java/io/evitadb/index/mutation/index/dataAccess/ExistingAttributeValueSupplier.java`
- **Key method:** `Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey)` — returns the existing (old) attribute value
- **Implementation:** `EntityStoragePartAccessorAttributeValueSupplier` (line 50) reads from `containerAccessor.getAttributeStoragePart()` which returns the attribute storage part from the `ContainerizedLocalMutationExecutor`
- **Timing:** The storage part is fetched lazily and cached. When called during `entityIndexUpdater.applyMutation()`, the `changeCollector` has NOT yet applied its mutation, so the old value is returned. When called after all mutations, the new value is returned.
- **Created via:** `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier()` (line 328 shows usage)

##### `ContainerizedLocalMutationExecutor.popImplicitMutations()` — existing pattern

- **Path:** `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java`, line 1162
- **Signature:** `ImplicitMutations popImplicitMutations(List<? extends LocalMutation<?, ?>> inputMutations, EnumSet<ImplicitMutationBehavior> implicitMutationBehavior)`
- **Pattern:** Creates a `MutationCollector`, populates it with local and external mutations based on consistency checks (mandatory attributes, reflected references), then returns `mutationCollector.toImplicitMutations()`
- **For entity removal** (line 1195-1207): calls `propagateReferencesToEntangledEntities()` with `CreateMode.REMOVE_ALL_EXISTING` to generate external mutations for reflected reference cleanup
- **The `MutationCollector`** (line 46 of `MutationCollector.java`): package-private class with `addLocalMutation()`, `addExternalMutation()`, and `toImplicitMutations()` methods. External mutations are de-duplicated by `EntityReference` key using a `Map`.

##### `LocalMutationExecutorCollector` — dispatch integration point

- **Path:** `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java`
- **Class:** `class LocalMutationExecutorCollector` (line 82) — package-private
- **Fields:** `Catalog catalog` (line 90), `List<LocalMutationExecutor> executors` (line 102), `int level` (line 115)
- **`execute()` method** (lines 178-348): orchestrates the full mutation lifecycle:
  1. Registers executors (line 191-192): `this.executors.add(entityIndexUpdater)`, `this.executors.add(changeCollector)`
  2. Increments `level` (line 222) — nesting counter for recursive implicit mutations
  3. For upserts: calls `entityIndexUpdater.prepare(localMutations)` (line 240)
  4. Applies mutations in loop (lines 250-253): index then storage, per mutation
  5. `changeCollector.finishLocalMutationExecutionPhase()` (line 255)
  6. `changeCollector.popImplicitMutations()` (line 258) — existing container implicit mutations
  7. Applies local implicit mutations to both executors (lines 262-265)
  8. Dispatches external implicit mutations to other collections (lines 267-280)
  9. Checks consistency (line 282-284)
  10. Decrements `level` in `finally` block (line 304); calls `finish()` only at level 0

**New step 5b insertion point:** After step 6-8 (container implicit mutations) and before step 9 (consistency check), add `entityIndexUpdater.popIndexImplicitMutations(localMutations)` and dispatch the resulting `EntityIndexMutation` envelopes. This requires the `LocalMutationExecutorCollector` to access `this.catalog.getCollectionForEntityOrThrowException()` — already available since the class has a `Catalog catalog` field (line 90).

**Nesting concern:** When implicit mutations from step 7-8 recursively call `applyMutations()` on other collections, those recursive calls will themselves produce index implicit mutations. The `level` counter already handles this — at `level > 0`, mutations are nested (implicit). The index implicit mutations from nested calls are dispatched within the nested call's scope, which is correct behavior.

##### `EntityRemoveMutation` — removal processing

- **Path:** `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityRemoveMutation.java`
- **`computeLocalMutationsForEntityRemoval(Entity entity)`** (line 90): generates `RemoveParentMutation`, `RemoveReferenceMutation`, `RemoveAttributeMutation`, `RemoveAssociatedDataMutation`, `SetPriceInnerRecordHandlingMutation`, `RemovePriceMutation` for the full entity
- In `LocalMutationExecutorCollector.execute()` (line 225-237): for `EntityRemoveMutation`, the full entity body is fetched, local mutations are computed from it, and then processed in the normal loop
- **Detection in executor:** `this.containerAccessor.isEntityRemovedEntirely()` returns `true` — the executor can check this in `popIndexImplicitMutations()` to know the entity is being removed
- **For removal triggers:** The executor should call `registry.getTriggersFor(entityType, dependencyType)` (WITHOUT attribute filtering) to get ALL triggers, since entity removal changes ALL properties. This fires for both `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` dependency types.

##### Mutation grouping — one at a time or batch

Mutations are applied one at a time in the loop (line 250-253). The `inputMutations` list contains all mutations for the entity. The executor processes them individually via `applyMutation()`. The `popIndexImplicitMutations()` method runs ONCE after all individual mutations are applied, receiving the full `inputMutations` list to analyze which attributes were touched.

##### How old-value caching works in existing code

The existing `AttributeIndexMutator.executeAttributeUpsert()` (line 161) already retrieves old values during processing:
```java
final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
```
This is called during `entityIndexUpdater.applyMutation()` — at which point the storage still has the old value. The pattern is: read old value, remove old index entry, insert new index entry.

For the source-side detection, the same `existingValueSupplier.getAttributeValue()` call can be used during `applyMutation()` to capture old values. The new value comes from the mutation itself. The comparison happens in `popIndexImplicitMutations()`.

---

#### Detailed Task List

##### Group 1: Registry access from executor — constructor parameter

- [ ] **1.1** Add a new field `@Nullable private final Supplier<CatalogExpressionTriggerRegistry> triggerRegistrySupplier` to `EntityIndexLocalMutationExecutor` (after line 156, alongside `fullEntitySupplier`). Use `@Nullable` because in test scenarios or when the feature is disabled, the supplier may not be provided. When null, `popIndexImplicitMutations()` returns an empty `IndexImplicitMutations`. JavaDoc must explain the field's purpose and why it is nullable.

- [ ] **1.2** Add the `@Nullable Supplier<CatalogExpressionTriggerRegistry> triggerRegistrySupplier` parameter to the constructor (after `fullEntitySupplier`, line 194). Assign it to the field. No validation needed (null is allowed).

- [ ] **1.3** Update the constructor call in `EntityCollection.applyMutations()` (line 2466-2475) to pass a new argument: `() -> this.catalog.getExpressionTriggerRegistry()`. The lambda captures `this.catalog` which is already available. This follows the existing `Supplier<EntitySchema> schemaAccessor` pattern.

- [ ] **1.4** Update all existing test usages of the `EntityIndexLocalMutationExecutor` constructor to pass `null` for the new parameter (or a mock supplier in tests that exercise the trigger logic). Search for `new EntityIndexLocalMutationExecutor(` across the test modules to find all call sites.

##### Group 2: Old-value caching during `applyMutation()`

- [ ] **2.1** Add a new field `@Nullable private Map<AttributeKey, Serializable> cachedOldAttributeValues` to `EntityIndexLocalMutationExecutor` (after `memoizedScope`, line 184). Lazily initialized. The map stores `AttributeKey -> oldValue` pairs captured during `applyMutation()` for entity-level attributes. Value is `null` when the attribute did not exist before the mutation.

- [ ] **2.2** In `EntityIndexLocalMutationExecutor.applyMutation()`, within the `AttributeMutation` branch (line 326-348), add old-value capture BEFORE the existing index update logic:
  ```java
  } else if (localMutation instanceof AttributeMutation attributeMutation) {
      // NEW: capture old value for trigger detection (before index update)
      if (this.triggerRegistrySupplier != null) {
          captureOldAttributeValue(attributeMutation.getAttributeKey());
      }
      // existing code follows...
  ```
  The capture must happen BEFORE `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier()` is called for index updates, because both read from the same storage part accessor. Since the storage hasn't been mutated yet (storage mutation happens after index mutation in the loop), the old value is still available.

- [ ] **2.3** Implement `private void captureOldAttributeValue(@Nonnull AttributeKey attributeKey)`:
  1. If `this.cachedOldAttributeValues == null`, create `new HashMap<>(8)` (use `CollectionUtils.createHashMap(8)` per code style)
  2. Use `putIfAbsent` to avoid overwriting an already-captured old value (in case the same attribute is mutated multiple times in one batch — only the original old value matters)
  3. Retrieve old value via `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier().getAttributeValue(attributeKey)` — map `Optional<AttributeValue>` to `attributeValue.value()` (the `Serializable` payload), or store a sentinel `null` if the attribute did not exist

- [ ] **2.4** Handle the sentinel value for "attribute did not exist" — since `HashMap` cannot distinguish between "key mapped to null" and "key not present", use `containsKey` check in `captureOldAttributeValue()` before `putIfAbsent`, or use a private sentinel object `private static final Serializable ATTRIBUTE_DID_NOT_EXIST = new Serializable() {}` and store it when the attribute was absent. In `popIndexImplicitMutations()`, treat the sentinel as `null` for comparison purposes.

  **Simplification note:** In practice, `ExistingAttributeValueSupplier.getAttributeValue()` already filters out dropped attributes (via `Droppable::exists`), so `Optional.empty()` means "attribute did not exist or was dropped." The `AttributeValue.value()` field is `@Nullable` in the record definition, but non-dropped attributes typically have non-null values. A simpler implementation may use `null` in the map to mean "absent" and rely on the fact that existing attribute values are non-null `Serializable` — however, since the `@Nullable` annotation on `AttributeValue.value()` does not guarantee this, the sentinel approach is the safer choice. Implementers may simplify if analysis of all mutation paths confirms non-null values for non-dropped attributes.

##### Group 3: `popIndexImplicitMutations()` implementation

- [ ] **3.1** The `IndexImplicitMutations` record and the stub `popIndexImplicitMutations()` method are created by **WBS-05** (Group 6). This task assumes they already exist. Add a static constant for the empty case (if not already present from WBS-05): `private static final IndexImplicitMutations EMPTY_INDEX_IMPLICIT_MUTATIONS = new IndexImplicitMutations(new EntityIndexMutation[0]);`

- [ ] **3.2** Implement `@Nonnull public IndexImplicitMutations popIndexImplicitMutations(@Nonnull List<? extends LocalMutation<?, ?>> inputMutations)`:
  1. **Early return if no registry:** If `this.triggerRegistrySupplier == null`, return `EMPTY_INDEX_IMPLICIT_MUTATIONS`
  2. **Get registry:** `final CatalogExpressionTriggerRegistry registry = this.triggerRegistrySupplier.get()`
  3. **Determine entity PK:** `final int entityPK = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW)`
  4. **Branch on removal vs. attribute change** (mutually exclusive — only one branch runs):
     - If `this.containerAccessor.isEntityRemovedEntirely()` — entity removal path (Group 4).
       This branch bypasses the per-attribute old-vs-new scanning entirely. Entity removal
       changes ALL properties, so the removal path fires ALL registered triggers for the
       entity type without consulting `cachedOldAttributeValues` or scanning `inputMutations`.
     - Otherwise — attribute change path (this group, step 3.3). Only runs for non-removal
       mutations where specific attributes were modified.
  5. **Clear cached old values** at the end: `this.cachedOldAttributeValues = null` (reset for potential reuse)

- [ ] **3.3** Attribute change path within `popIndexImplicitMutations()`:
  1. If `this.cachedOldAttributeValues == null` or empty, return `EMPTY_INDEX_IMPLICIT_MUTATIONS` (no attributes were mutated)
  2. Create a mutable `Map<String, List<IndexMutation>> mutationsByTargetType` (keyed by target entity type)
  3. For each entry `(attributeKey, oldValue)` in `this.cachedOldAttributeValues`:
     a. Determine the new value: scan `inputMutations` for the matching entity-level `AttributeMutation` (note: `ReferenceAttributeMutation` is wrapped in `ReferenceMutation` and falls under a different branch in `applyMutation()`, so it never appears in `cachedOldAttributeValues` and is not scanned here). For `UpsertAttributeMutation`, get `getAttributeValue()`; for `RemoveAttributeMutation`, new value is null; for `ApplyDeltaAttributeMutation`, any delta implies a change (skip the old==new check). Note: for `EntityRemoveMutation`, the `inputMutations` list is the computed list from `computeLocalMutationsForEntityRemoval()` containing `RemoveAttributeMutation` instances, but this code path never runs for removals — the removal branch (Group 4) handles that case.
     b. Compare old and new values: `if (Objects.equals(oldValue, newValue)) continue;` — the old==new optimization
     c. Query registry: `registry.getTriggersForAttribute(this.entityType, dependencyType, attributeKey.attributeName())` — call for EACH `DependencyType` value (`REFERENCED_ENTITY_ATTRIBUTE`, `GROUP_ENTITY_ATTRIBUTE`)
     d. For each returned trigger, create a `ReevaluateFacetExpressionMutation(trigger.getReferenceName(), entityPK, trigger.getDependencyType(), trigger.getScope())`
     e. Add to `mutationsByTargetType` grouped by `trigger.getOwnerEntityType()`
  4. Convert `mutationsByTargetType` to `EntityIndexMutation[]` — one envelope per target entity type, containing all mutations for that target
  5. Return `new IndexImplicitMutations(envelopes)`

- [ ] **3.4** Implement the grouping-and-wrapping helper method `@Nonnull private EntityIndexMutation[] groupByTargetEntityType(@Nonnull Map<String, List<IndexMutation>> mutationsByTargetType)`:
  1. If map is empty, return empty array
  2. Allocate `EntityIndexMutation[]` of size `mutationsByTargetType.size()`
  3. For each entry, create `new EntityIndexMutation(targetEntityType, mutations.toArray(IndexMutation[]::new))`
  4. Return the array

##### Group 4: `EntityRemoveMutation` trigger path

- [ ] **4.1** In the removal branch of `popIndexImplicitMutations()`:
  1. Create `Map<String, List<IndexMutation>> mutationsByTargetType`
  2. Query registry with `registry.getTriggersFor(this.entityType, DependencyType.REFERENCED_ENTITY_ATTRIBUTE)` — all triggers, no attribute filter (entity removal changes ALL properties)
  3. Query registry with `registry.getTriggersFor(this.entityType, DependencyType.GROUP_ENTITY_ATTRIBUTE)` — same for group entity dependency
  4. For each trigger from BOTH queries, create `ReevaluateFacetExpressionMutation(trigger.getReferenceName(), entityPK, trigger.getDependencyType(), trigger.getScope())`
  5. Group by `trigger.getOwnerEntityType()` and wrap in `EntityIndexMutation` envelopes using the helper from 3.4
  6. Return the result

- [ ] **4.2** Ensure no duplicate triggers when the same trigger appears under both dependency types — the registry keys by `(entityType, dependencyType)` so each query returns a distinct set. However, if the same expression depends on both `referencedEntity` and `groupEntity` of the same entity type (unlikely but possible), the registry would have separate trigger entries. This is correct — each fires independently, and the target-side executor handles de-duplication naturally (idempotent operations).

##### Group 5: Dispatch contract for `LocalMutationExecutorCollector` (implementation in WBS-10)

> **Note:** The actual dispatch loop implementation in `LocalMutationExecutorCollector.execute()`
> is owned by **WBS-10**. This group documents the contract and expected dispatch behavior so
> that WBS-08's `popIndexImplicitMutations()` output is well-defined for WBS-10 consumption.
> The code snippet below is illustrative — it shows how WBS-10 will consume the output.

- [ ] **5.1** Verify that the `popIndexImplicitMutations()` return type (`IndexImplicitMutations`) and its `indexMutations()` array provide everything WBS-10 needs for dispatch. The expected dispatch pattern (implemented by WBS-10):
  ```java
  // Step 5b: index trigger mutations (implemented by WBS-10)
  final IndexImplicitMutations indexImplicit =
      entityIndexUpdater.popIndexImplicitMutations(localMutations);
  for (EntityIndexMutation indexMutation : indexImplicit.indexMutations()) {
      this.catalog.getCollectionForEntityOrThrowException(
              indexMutation.entityType()
          )
          .applyIndexMutations(indexMutation);
  }
  ```

- [ ] **5.2** The `EntityCollection.applyIndexMutations(EntityIndexMutation)` method does not exist yet — it will be created in **WBS-10** (which also owns the `IndexMutationTarget` interface and executor registry wiring). If WBS-10 is not yet implemented and compilation requires a call target, WBS-10 should provide a stub `applyIndexMutations()` method on `EntityCollection`.

- [ ] **5.3** Verify that `EntityIndexMutation.entityType()` correctly identifies the target collection for routing. This is a WBS-05 contract verified here.

- [ ] **5.4** Document the ordering constraint for WBS-10: `popIndexImplicitMutations()` must be called AFTER `popImplicitMutations()` (container first, then index triggers). The `level` counter does not need changes. If `applyIndexMutations()` on the target collection triggers further mutations on other entities, those will be at `level + 1` and handled by the existing nesting mechanism.

##### Group 6: Scope handling

- [ ] **6.1** The `ReevaluateFacetExpressionMutation` carries a `Scope` field. The scope comes from the trigger (which is derived from `ReferenceSchema.facetedPartiallyInScopes()` — a `Map<Scope, Expression>`). Each trigger is scope-specific. The executor gets the scope from the trigger, not from the current entity.

- [ ] **6.2** Verify that `getScope()` on the executor (line 214) returns the correct scope for the mutated entity. This scope is NOT used for the trigger mutation — the trigger's own scope is used. Document this distinction in comments.

##### Group 7: Edge cases

- [ ] **7.1** Handle the case where `inputMutations` contains multiple `UpsertAttributeMutation` for the same attribute key (unlikely in a single mutation batch but technically possible). The old-value cache uses `putIfAbsent` (Group 2.3), so only the first old value is captured. The new value should be taken from the LAST mutation for that attribute key in the list. Implement this by scanning `inputMutations` in forward order and keeping the last `AttributeMutation` per key.

  **Alternative approach:** Instead of scanning `inputMutations` for the new value, read the post-mutation value directly from `getStoragePartExistingDataFactory().getEntityAttributeValueSupplier().getAttributeValue(attributeKey)`. By the time `popIndexImplicitMutations()` runs, all mutations have been applied to storage, so this returns the new value. This eliminates the mutation list scanning and naturally handles the multiple-mutations-per-key case. The implementer should evaluate which approach is simpler and more robust — the storage-read approach avoids edge cases with mutation scanning but couples the new-value retrieval to the storage accessor's timing guarantees.

- [ ] **7.2** Handle `ReferenceAttributeMutation` — attributes on references (e.g., `$reference.attributes['x']`). These are NOT cross-entity triggers — they are local mutations on the owning entity's reference data. The cross-entity triggers only fire for entity-level attributes on referenced/group entities. Verify that `ReferenceAttributeMutation` is NOT captured in the old-value cache. In the `applyMutation()` method, `ReferenceAttributeMutation` falls under the `ReferenceMutation` branch (line 307), NOT the `AttributeMutation` branch (line 326), so it is naturally excluded.

- [ ] **7.3** Handle `SetEntityScopeMutation` — when an entity changes scope, this does NOT trigger cross-entity re-evaluation (scope changes affect the local entity's indexing, not cross-entity expressions). No action needed — scope mutations are handled in a separate branch (line 351) and do not produce attribute changes.

- [ ] **7.4** Handle empty trigger registry — when no expressions depend on the mutated entity type, both `getTriggersFor()` and `getTriggersForAttribute()` return empty lists. The method returns `EMPTY_INDEX_IMPLICIT_MUTATIONS`. No error or logging needed.

### Test Cases

#### Test class: `EntityIndexLocalMutationExecutorTriggerTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutorTriggerTest.java`

**Setup:** Unit test extending `AbstractMutatorTestBase` (or equivalent). The executor is
constructed with a mock `CatalogExpressionTriggerRegistry` supplier. The mock
`ContainerizedLocalMutationExecutor` (via `containerAccessor`) is pre-loaded with attribute
storage parts to provide old values. Entity type is `"parameterGroup"` (source entity).
Triggers returned by the mock registry target entity type `"product"` with reference name
`"parameter"`.

##### Category 1: Attribute change detection — basic trigger firing

- [ ] `pop_index_implicit_mutations_returns_mutation_when_attribute_value_changes` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `UpsertAttributeMutation("inputWidgetType", "RADIO")`. Registry has a
  `GROUP_ENTITY_ATTRIBUTE` trigger for `("parameterGroup", "inputWidgetType")` targeting
  `"product"/"parameter"`. Call `popIndexImplicitMutations()`. Expect: one
  `EntityIndexMutation` with `entityType = "product"` containing one
  `ReevaluateFacetExpressionMutation` with `referenceName = "parameter"`,
  `mutatedEntityPK = entityPK`, `dependencyType = GROUP_ENTITY_ATTRIBUTE`, and
  `scope = LIVE`.

- [ ] `pop_index_implicit_mutations_returns_mutation_for_referenced_entity_attribute` —
  Pre-load attribute `status` with old value `"ACTIVE"`. Apply
  `UpsertAttributeMutation("status", "INACTIVE")`. Registry has a
  `REFERENCED_ENTITY_ATTRIBUTE` trigger for `("parameterGroup", "status")` targeting
  `"product"/"parameter"`. Call `popIndexImplicitMutations()`. Expect: one
  `ReevaluateFacetExpressionMutation` with `dependencyType = REFERENCED_ENTITY_ATTRIBUTE`.

- [ ] `pop_index_implicit_mutations_fires_for_attribute_removal` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `RemoveAttributeMutation("inputWidgetType")`. Registry has a matching trigger. Call
  `popIndexImplicitMutations()`. Expect: one `ReevaluateFacetExpressionMutation` (old was
  `"CHECKBOX"`, new is `null` — values differ).

- [ ] `pop_index_implicit_mutations_fires_for_attribute_creation` —
  No pre-existing attribute `inputWidgetType` (old value is `null`). Apply
  `UpsertAttributeMutation("inputWidgetType", "CHECKBOX")`. Registry has a matching trigger.
  Call `popIndexImplicitMutations()`. Expect: one `ReevaluateFacetExpressionMutation` (old was
  `null`, new is `"CHECKBOX"` — values differ).

- [ ] `pop_index_implicit_mutations_fires_for_delta_attribute_mutation` —
  Pre-load numeric attribute `priority` with old value `5`. Apply
  `ApplyDeltaAttributeMutation("priority", 3)`. Registry has a matching trigger. Call
  `popIndexImplicitMutations()`. Expect: trigger fires (any delta implies a change; the
  old-new comparison is skipped or always returns "changed" for delta mutations).

- [ ] `pop_index_implicit_mutations_fires_for_zero_delta_attribute_mutation` —
  Pre-load numeric attribute `priority` with old value `5`. Apply
  `ApplyDeltaAttributeMutation("priority", 0)`. Registry has a matching trigger. Call
  `popIndexImplicitMutations()`. Expect: trigger fires. **Design decision:** A delta of 0
  does not actually change the value (`5 + 0 = 5`), but the implementation treats ANY
  `ApplyDeltaAttributeMutation` as an implicit change to avoid the complexity of computing
  the absolute new value from the delta. This is safe over-firing — the target-side executor
  is idempotent. If precise delta=0 optimization is desired in the future, the new value can
  be computed as `oldValue + delta` and compared with `oldValue`.

##### Category 2: Old == new optimization — skip when value unchanged

- [ ] `pop_index_implicit_mutations_skips_when_upsert_value_equals_existing` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `UpsertAttributeMutation("inputWidgetType", "CHECKBOX")`. Registry has a matching trigger.
  Call `popIndexImplicitMutations()`. Expect: empty `IndexImplicitMutations` (no triggers
  fire because old equals new).

- [ ] `pop_index_implicit_mutations_skips_when_removing_nonexistent_attribute` —
  No pre-existing attribute `inputWidgetType` (old value is `null`). Apply
  `RemoveAttributeMutation("inputWidgetType")`. Registry has a matching trigger. Call
  `popIndexImplicitMutations()`. Expect: empty result (old `null` equals new `null`).

- [ ] `pop_index_implicit_mutations_compares_by_value_equality_not_identity` —
  Pre-load attribute `code` with old value `new String("ABC")`. Apply
  `UpsertAttributeMutation("code", new String("ABC"))` (different `String` instance, same
  value). Registry has a matching trigger. Call `popIndexImplicitMutations()`. Expect: empty
  result (`Objects.equals()` returns `true`).

##### Category 3: Registry consultation

- [ ] `pop_index_implicit_mutations_returns_empty_when_no_triggers_registered` —
  Pre-load and change attribute `inputWidgetType`. Registry returns empty list for all
  queries against `"parameterGroup"`. Call `popIndexImplicitMutations()`. Expect: empty
  `IndexImplicitMutations`.

- [ ] `pop_index_implicit_mutations_queries_registry_for_both_dependency_types` —
  Pre-load and change attribute `status`. Registry has triggers under BOTH
  `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` for `("parameterGroup",
  "status")`. Call `popIndexImplicitMutations()`. Expect: `ReevaluateFacetExpressionMutation`
  instances for BOTH dependency types.

- [ ] `pop_index_implicit_mutations_queries_registry_with_correct_attribute_name` —
  Pre-load and change attributes `inputWidgetType` and `status`. Registry has a trigger ONLY
  for `inputWidgetType`, NOT for `status`. Call `popIndexImplicitMutations()`. Expect: only
  ONE `ReevaluateFacetExpressionMutation` (for `inputWidgetType`); the irrelevant attribute
  `status` produces no trigger.

- [ ] `pop_index_implicit_mutations_fires_triggers_for_multiple_changed_attributes` —
  Pre-load attributes `inputWidgetType` (old value `"CHECKBOX"`) and `status` (old value
  `"ACTIVE"`). Apply `UpsertAttributeMutation("inputWidgetType", "RADIO")` and
  `UpsertAttributeMutation("status", "INACTIVE")`. Registry has matching triggers for BOTH
  attributes. Call `popIndexImplicitMutations()`. Expect: `ReevaluateFacetExpressionMutation`
  instances for BOTH attribute changes.

##### Category 4: `ReevaluateFacetExpressionMutation` creation correctness

- [ ] `pop_index_implicit_mutations_creates_mutation_with_correct_reference_name` —
  Trigger specifies `referenceName = "parameter"`. Verify the produced
  `ReevaluateFacetExpressionMutation` carries `referenceName = "parameter"`.

- [ ] `pop_index_implicit_mutations_creates_mutation_with_correct_entity_pk` —
  Executor is constructed with entity PK = 99. Verify the produced
  `ReevaluateFacetExpressionMutation` carries `mutatedEntityPK = 99`.

- [ ] `pop_index_implicit_mutations_creates_mutation_with_scope_from_trigger` —
  Trigger specifies `scope = ARCHIVED`. Verify the produced mutation carries
  `scope = ARCHIVED` (NOT the executor's own entity scope). This confirms the scope comes from
  the trigger, not from the mutated entity.

- [ ] `pop_index_implicit_mutations_creates_mutation_with_dependency_type_from_trigger` —
  Trigger specifies `dependencyType = GROUP_ENTITY_ATTRIBUTE`. Verify the produced mutation
  carries `dependencyType = GROUP_ENTITY_ATTRIBUTE`.

##### Category 5: Grouping by target entity type

- [ ] `pop_index_implicit_mutations_groups_mutations_into_single_envelope_per_target` —
  Registry returns TWO triggers for the same target `"product"` (e.g., two references
  `"parameter"` and `"brand"` on Product both depend on the same source entity type). Apply
  one attribute change. Call `popIndexImplicitMutations()`. Expect: ONE `EntityIndexMutation`
  with `entityType = "product"` containing TWO `ReevaluateFacetExpressionMutation` instances.

- [ ] `pop_index_implicit_mutations_creates_separate_envelopes_for_different_targets` —
  Registry returns triggers for TWO different targets: `"product"` and `"offer"`. Apply one
  attribute change. Call `popIndexImplicitMutations()`. Expect: TWO `EntityIndexMutation`
  envelopes — one with `entityType = "product"`, one with `entityType = "offer"`.

- [ ] `pop_index_implicit_mutations_creates_correct_envelope_entity_type` —
  Verify that each `EntityIndexMutation.entityType()` matches `trigger.getOwnerEntityType()`,
  NOT the source entity's type.

##### Category 6: `EntityIndexMutation` wrapping

- [ ] `pop_index_implicit_mutations_wraps_mutations_in_entity_index_mutation` —
  Apply attribute change with a matching trigger. Verify the returned
  `IndexImplicitMutations.indexMutations()` is a non-empty `EntityIndexMutation[]` and each
  envelope's `mutations()` array contains `IndexMutation` instances that are
  `ReevaluateFacetExpressionMutation`.

- [ ] `pop_index_implicit_mutations_returns_empty_array_when_no_changes` —
  Apply NO attribute mutations (e.g., only an `AssociatedDataMutation`). Call
  `popIndexImplicitMutations()`. Expect: `IndexImplicitMutations` with an empty
  `indexMutations` array.

##### Category 7: Entity removal as trigger source

- [ ] `pop_index_implicit_mutations_fires_all_triggers_on_entity_removal` —
  Mark entity as removed (`containerAccessor.isEntityRemovedEntirely() == true`). Registry
  has triggers under BOTH `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` for
  `"parameterGroup"`. Call `popIndexImplicitMutations()`. Expect:
  `ReevaluateFacetExpressionMutation` instances for ALL triggers under BOTH dependency types,
  WITHOUT attribute-name filtering.

- [ ] `pop_index_implicit_mutations_removal_fires_without_old_new_comparison` —
  Mark entity as removed. Registry has triggers. No old-value cache is populated (removal does
  not need per-attribute old-new comparison). Call `popIndexImplicitMutations()`. Expect:
  triggers fire unconditionally (entity removal changes all properties to `null`).

- [ ] `pop_index_implicit_mutations_removal_groups_by_target_entity_type` —
  Mark entity as removed. Registry returns triggers for TWO different target entity types
  (`"product"` and `"offer"`). Call `popIndexImplicitMutations()`. Expect: TWO
  `EntityIndexMutation` envelopes with correct target types.

- [ ] `pop_index_implicit_mutations_removal_carries_correct_entity_pk` —
  Executor PK = 42. Mark entity as removed. Verify produced
  `ReevaluateFacetExpressionMutation` carries `mutatedEntityPK = 42`.

- [ ] `pop_index_implicit_mutations_removal_returns_empty_when_no_triggers` —
  Mark entity as removed. Registry returns empty lists for all dependency types. Call
  `popIndexImplicitMutations()`. Expect: empty `IndexImplicitMutations`.

##### Category 8: Null registry supplier

- [ ] `pop_index_implicit_mutations_returns_empty_with_null_supplier` —
  Construct executor with `null` trigger registry supplier. Apply attribute mutations. Call
  `popIndexImplicitMutations()`. Expect: empty `IndexImplicitMutations`, no NPE.

- [ ] `pop_index_implicit_mutations_removal_returns_empty_with_null_supplier` —
  Construct executor with `null` trigger registry supplier. Mark entity as removed. Call
  `popIndexImplicitMutations()`. Expect: empty `IndexImplicitMutations`, no NPE.

##### Category 9: Multiple triggers per attribute

- [ ] `pop_index_implicit_mutations_fires_multiple_triggers_for_same_attribute` —
  Registry returns THREE triggers for `("parameterGroup", GROUP_ENTITY_ATTRIBUTE,
  "inputWidgetType")` — e.g., three different references on `"product"` all depend on the
  same attribute. Apply one attribute change. Expect: three
  `ReevaluateFacetExpressionMutation` instances in the result.

- [ ] `pop_index_implicit_mutations_fires_triggers_from_both_dependency_types_independently` —
  Expression depends on both `referencedEntity.attributes['status']` AND
  `groupEntity?.attributes['status']` — registry has entries for BOTH
  `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` under
  `("parameterGroup", "status")`. Change attribute `status`. Expect: TWO
  `ReevaluateFacetExpressionMutation` instances — one per dependency type.

##### Category 10: Scope handling

- [ ] `pop_index_implicit_mutations_creates_separate_mutations_for_different_scopes` —
  Registry returns two triggers for the same reference `"parameter"` but with different scopes
  (`LIVE` and `ARCHIVED`). Apply attribute change. Expect: TWO
  `ReevaluateFacetExpressionMutation` instances with `scope = LIVE` and `scope = ARCHIVED`
  respectively.

- [ ] `pop_index_implicit_mutations_scope_comes_from_trigger_not_entity` —
  Entity is in `LIVE` scope. Trigger specifies `scope = ARCHIVED`. Apply attribute change.
  Expect: produced mutation carries `scope = ARCHIVED` (trigger's scope), NOT `LIVE` (entity's
  scope).

##### Category 11: Old-value caching correctness

- [ ] `pop_index_implicit_mutations_caches_old_value_before_index_update` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `UpsertAttributeMutation("inputWidgetType", "RADIO")` via `executor.applyMutation()`. Then
  call `popIndexImplicitMutations()`. Verify the old-new comparison uses `"CHECKBOX"` as the
  old value (captured during `applyMutation()` before storage was mutated), NOT the post-
  mutation value.

- [ ] `pop_index_implicit_mutations_handles_multiple_mutations_same_attribute` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `UpsertAttributeMutation("inputWidgetType", "RADIO")` followed by
  `UpsertAttributeMutation("inputWidgetType", "DROPDOWN")` in the same batch. The old-value
  cache uses `putIfAbsent`, so the captured old value is `"CHECKBOX"`. The new value is taken
  from the LAST mutation: `"DROPDOWN"`. Expect: trigger fires (old `"CHECKBOX"` != new
  `"DROPDOWN"`).

- [ ] `pop_index_implicit_mutations_multiple_mutations_same_attr_final_equals_original` —
  Pre-load attribute `inputWidgetType` with old value `"CHECKBOX"`. Apply
  `UpsertAttributeMutation("inputWidgetType", "RADIO")` followed by
  `UpsertAttributeMutation("inputWidgetType", "CHECKBOX")` in the same batch. Old value =
  `"CHECKBOX"`, final new value = `"CHECKBOX"`. Expect: empty result (old == new optimization
  applies to the net change).

- [ ] `pop_index_implicit_mutations_clears_cached_values_after_pop` —
  Apply attribute mutation, call `popIndexImplicitMutations()`, then apply a DIFFERENT
  attribute mutation and call `popIndexImplicitMutations()` again. The second call must NOT
  see cached values from the first batch. Verify each call produces results based only on its
  own batch.

##### Category 12: Edge cases — mutation type filtering

- [ ] `pop_index_implicit_mutations_ignores_reference_attribute_mutations` —
  Apply `ReferenceAttributeMutation` (attribute on a reference, not an entity-level
  attribute). Registry has triggers. Call `popIndexImplicitMutations()`. Expect: empty result.
  `ReferenceAttributeMutation` falls under the `ReferenceMutation` branch in
  `applyMutation()`, NOT the `AttributeMutation` branch, so no old-value caching occurs.

- [ ] `pop_index_implicit_mutations_ignores_associated_data_mutations` —
  Apply `AssociatedDataMutation`. Call `popIndexImplicitMutations()`. Expect: empty result
  (associated data mutations do not trigger cross-entity re-evaluation).

- [ ] `pop_index_implicit_mutations_ignores_price_mutations` —
  Apply `UpsertPriceMutation`. Call `popIndexImplicitMutations()`. Expect: empty result
  (price mutations are not cross-entity attribute triggers).

- [ ] `pop_index_implicit_mutations_ignores_parent_mutations` —
  Apply `SetParentMutation`. Call `popIndexImplicitMutations()`. Expect: empty result
  (parent mutations are not cross-entity attribute triggers).

- [ ] `pop_index_implicit_mutations_ignores_scope_mutations` —
  Apply `SetEntityScopeMutation`. Call `popIndexImplicitMutations()`. Expect: empty result
  (scope changes do not trigger cross-entity re-evaluation).

- [ ] `pop_index_implicit_mutations_handles_localized_attribute_key` —
  Pre-load localized attribute `name` (locale = `en`) with old value `"Widget"`. Apply
  `UpsertAttributeMutation` with `AttributeKey("name", Locale.ENGLISH)` and new value
  `"Gadget"`. Registry has a trigger matching attribute name `"name"`. Expect: trigger fires.
  Verify that the `AttributeKey` locale does not prevent matching.

#### Test class: `LocalMutationExecutorCollectorIndexDispatchTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/collection/LocalMutationExecutorCollectorIndexDispatchTest.java`

**Setup:** Integration-level test using mock `EntityCollection`, mock `Catalog`, a real or
partially-mocked `LocalMutationExecutorCollector`, a mock `EntityIndexLocalMutationExecutor`,
and a mock `ContainerizedLocalMutationExecutor`. The mock index executor's
`popIndexImplicitMutations()` returns pre-built `IndexImplicitMutations`.

##### Category 13: Dispatch ordering and routing

- [ ] `execute_calls_pop_index_implicit_mutations_after_pop_implicit_mutations` —
  Instrument both `popImplicitMutations()` and `popIndexImplicitMutations()` with call-order
  tracking. Execute a mutation. Verify `popImplicitMutations()` is called BEFORE
  `popIndexImplicitMutations()`.

- [ ] `execute_calls_pop_index_implicit_mutations_before_consistency_check` —
  Instrument `popIndexImplicitMutations()` and `verifyConsistency()` with call-order
  tracking. Execute a mutation. Verify `popIndexImplicitMutations()` is called BEFORE
  `verifyConsistency()`.

- [ ] `execute_routes_entity_index_mutation_to_correct_target_collection` —
  `popIndexImplicitMutations()` returns an `IndexImplicitMutations` with one
  `EntityIndexMutation(entityType = "product", ...)`. Verify that
  `catalog.getCollectionForEntityOrThrowException("product")` is called and
  `applyIndexMutations()` is invoked on the returned `EntityCollection` mock.

- [ ] `execute_routes_multiple_envelopes_to_respective_collections` —
  `popIndexImplicitMutations()` returns two envelopes: one for `"product"`, one for
  `"offer"`. Verify that `applyIndexMutations()` is called on BOTH the Product and Offer
  collection mocks with the correct envelope.

- [ ] `execute_handles_empty_index_implicit_mutations` —
  `popIndexImplicitMutations()` returns empty `IndexImplicitMutations`. Verify that NO calls
  to `getCollectionForEntityOrThrowException()` are made for index dispatch (no-op).

- [ ] `execute_dispatches_index_mutations_for_entity_removal` —
  Execute an `EntityRemoveMutation`. `popIndexImplicitMutations()` returns non-empty result.
  Verify dispatch occurs normally (removal path also invokes index trigger dispatch).

- [ ] `execute_index_mutations_are_not_written_to_wal` —
  Verify that `EntityIndexMutation` envelopes produced by `popIndexImplicitMutations()` are
  NOT added to `entityMutations` list and are NOT written to WAL. Only the root-level
  `EntityMutation` is recorded.
