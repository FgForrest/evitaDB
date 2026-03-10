# WBS-09: Local Trigger Integration in `ReferenceIndexMutator`

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Extend `ReferenceIndexMutator` to evaluate facet-indexing expressions inline during local
mutation processing, so that each facet add/remove decision is governed by the expression
defined in the reference schema. The existing `isFaceted()` guard must be widened to
`isFaceted() && (noExpression || trigger.evaluate(pk, refKey, accessor))`, and the re-evaluation
decision matrix (was/now faceted) must be applied whenever a local mutation touches data
referenced by the expression.

## Scope

### In Scope

- Extending the `isFaceted()` guard in `ReferenceIndexMutator` to incorporate expression
  evaluation via `ExpressionIndexTrigger.evaluate()`.
- Handling all 10 local-trigger mutations that can cause re-evaluation (see Technical Context).
- Implementing the re-evaluation decision matrix (was faceted / now faceted -> action).
- Determining "was faceted before" via `FacetIdIndex.records.contains(entityPK)`.
- Obtaining the trigger instance through `EntityIndexLocalMutationExecutor.getTriggerFor(referenceName, scope)`.
- Passing `WritableEntityStorageContainerAccessor` to `evaluate()` for storage part access.
- Ensuring expression evaluation correctness regardless of mutation ordering (the
  `WritableEntityStorageContainerAccessor` reflects all previously applied mutations, so
  the expression always sees up-to-date storage part data).
- Scope-aware evaluation (`facetedPartiallyInScopes`): evaluating per-scope, analogous to
  `isFacetedInScope()`.
- Ensuring conditional logic only wraps facet add/remove, NOT the entire reference indexing
  flow (references must still be indexed for filtering even when expression evaluates to `false`).

### Out of Scope

- Cross-entity trigger processing (WBS for `ReevaluateFacetExpressionMutation` / executor).
- Construction of `ExpressionIndexTrigger` itself (WBS-03).
- `CatalogExpressionTriggerRegistry` and fan-out infrastructure.
- Schema evolution / full re-index when expression changes.
- `getFilterByConstraint()` path (cross-entity only).

## Dependencies

### Depends On

| WBS   | Artifact                                                                                       |
|-------|------------------------------------------------------------------------------------------------|
| WBS-03 | Provides `ExpressionIndexTrigger` with `evaluate(int entityPK, ReferenceKey refKey, WritableEntityStorageContainerAccessor accessor)` method. Also provides the pre-built Proxycian proxy infrastructure and state recipes used inside `evaluate()`. |

### Depended On By

| WBS   | Reason                                                                                          |
|-------|-------------------------------------------------------------------------------------------------|
| (cross-entity WBS) | Cross-entity triggers reuse the same decision matrix logic; local integration validates the pattern first. |

## Technical Context

### Local Trigger Mutation Table (Domain 2a)

All 10 mutations that trigger local re-evaluation of facet expressions:

| Mutation                           | Triggers re-evaluation when...                                |
|------------------------------------|---------------------------------------------------------------|
| `UpsertAttributeMutation`          | Expression uses `$entity.attributes['x']`                     |
| `UpsertReferenceAttributeMutation` | Expression uses `$reference.attributes['x']`                  |
| `UpsertAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `RemoveAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `SetParentMutation`                | Expression uses `$entity.parent`                              |
| `RemoveParentMutation`             | Expression uses `$entity.parent`                              |
| `InsertReferenceMutation`          | Always -- new facet candidate, must evaluate expression       |
| `RemoveReferenceMutation`          | Always -- facet removal (existing logic, no expression needed)|
| `SetReferenceGroupMutation`        | Expression uses `$reference.groupEntity`                      |
| `RemoveReferenceGroupMutation`     | Expression uses `$reference.groupEntity`                      |

These are local triggers -- handled within the same entity's mutation processing, inline with
existing `ReferenceIndexMutator` logic.

### The `isFaceted()` Guard Extension

The current `isFaceted()` check gates ONLY facet operations. With conditional faceting, the
guard becomes:

```
isFaceted() && (noExpression || trigger.evaluate(pk, refKey, accessor))
```

- `noExpression` -- reference schema has no expression defined; behaves as before (all
  references are faceted).
- `trigger.evaluate(...)` -- evaluates the expression for this specific entity/reference pair
  using Proxycian proxies backed by storage parts.

### Initial Reference Insert (Domain 3a)

When a new reference is inserted (`InsertReferenceMutation`) and the reference schema has an
expression:

- Expression evaluates to `true` --> add facet as normal.
- Expression evaluates to `false` --> skip facet add, BUT still index the reference itself for
  filtering. The conditional logic only wraps facet add/remove, not the entire reference
  indexing flow.

### Re-evaluation Decision Matrix (Domain 3b)

Applies to both local and cross-entity triggers. For local triggers, the matrix is applied
inline within `ReferenceIndexMutator`:

| Was faceted? | Now faceted? | Action                   |
|--------------|--------------|--------------------------|
| yes          | no           | Remove from facet index  |
| no           | yes          | Add to facet index       |
| yes          | yes          | no-op                    |
| no           | no           | no-op                    |

**How to determine "was faceted before":** Check `FacetIdIndex.records.contains(entityPK)`.
If the entity PK exists in the facet bitmap, it was faceted. No need to store the boolean
separately. This check is used at both generation time (cross-entity) and evaluation time
(local).

### Two Mechanisms for the Same Logic

- **Local triggers** (this WBS): inline guard in `ReferenceIndexMutator` calls
  `addFacetToIndex()` / `removeFacetInIndex()` directly based on expression evaluation result
  and the was/now decision matrix.
- **Cross-entity triggers** (separate WBS): source executor creates
  `ReevaluateFacetExpressionMutation` wrapped in `EntityIndexMutation`, dispatched to target
  collection via `ReevaluateFacetExpressionExecutor`, which translates the expression to a
  parameterized `FilterBy` query, evaluates against indexes, compares with current facet state,
  and calls `addFacet()` / `removeFacet()` on the collection's `EntityIndex` instances.

### Mutation Ordering Within a Single Entity

When multiple mutations are applied to the same entity (e.g.,
`[SetAttribute('code','ABC'), InsertReference('param',5)]`), the reference insert evaluates
the expression which may use `$entity.attributes['code']`. The framework does NOT sort
mutations by type -- `LocalMutation#compareTo` exists for CDC ordering, not execution ordering.
Mutations are applied in the order provided by the caller. However, this does not affect
expression correctness: `EntityIndexLocalMutationExecutor` reads storage parts via
`WritableEntityStorageContainerAccessor`, which reflects all previously applied mutations in
the batch. So regardless of mutation order, the expression sees the latest committed storage
part state from all preceding mutations.

### `evaluate()` Implementation Notes

`evaluate()` (local triggers only):

1. Fetches only the `StorageParts` the recipe requires.
2. Creates state records from fetched storage parts.
3. Instantiates pre-built proxy classes (built at schema load time, not on the hot path).
4. Wires nested proxies (for group/referenced entity access).
5. Computes the expression and casts the result to `boolean`.

This is the **two-phase lifecycle** (AD 11): proxy classes and state recipes are built at
schema load time (expensive but amortized). At trigger time, only state records are created and
pre-built proxy classes instantiated (cheap -- approximately 2 allocations per entity). No class
generation on the hot path.

### Access Patterns in `ReferenceIndexMutator`

- `ReferenceIndexMutator` methods are **static** and receive `EntityIndexLocalMutationExecutor`
  as an argument.
- The trigger instance is obtained via `executor.getTriggerFor(referenceName, scope)`.
- `ContainerizedLocalMutationExecutor` already implements `EntityStoragePartAccessor` with
  cross-entity access. The same `WritableEntityStorageContainerAccessor` passed to
  `ExpressionIndexTrigger.evaluate()` supports fetching any entity type's storage parts.

### Scope Awareness

Expressions can differ per scope (`facetedPartiallyInScopes`). The `ReevaluateFacetExpressionMutation`
carries `Scope`. Must evaluate per-scope, same as `isFacetedInScope()`. Scope transitions
(entity moved between scopes) are handled naturally: entity removed from old scope indexes,
inserted into new scope, expression evaluated inline.

### Transactional Consistency (Domain 3c)

Expression evaluation and facet index update must be atomic within the same transaction. The
existing transactional infrastructure (`TransactionalBitmap`, etc.) handles this.

### Relevant Hidden Traps

| #  | Trap                                                                              | Severity | Mitigation                                                                                                     |
|----|-----------------------------------------------------------------------------------|----------|----------------------------------------------------------------------------------------------------------------|
| 1  | **Circular fetch during mutation** -- fetching entity data while mutating it       | High     | Use storage parts + pending mutations via `WritableEntityStorageContainerAccessor`, not query engine            |
| 4  | **Expression evaluation performance** -- local: Proxycian proxies with ~2 allocations per entity, pre-built at schema time | Medium | Two-mode evaluation: `evaluate()` for local (per-entity), `getFilterByConstraint()` for cross-entity           |
| 5  | **Scope awareness** -- expression can differ per scope; mutation carries `Scope`   | Medium   | Must evaluate per-scope, same as `isFacetedInScope()`                                                          |
| 6  | **Null safety in expression** -- group entity might not exist yet when expression references it | Medium | `NullSafeAccessStep` handles `?.` but non-null-safe paths will throw -- document requirement                   |
| 9  | **Cross-transaction visibility** -- trigger on entity B must see B's new data when evaluating for entity A | Medium | Collector calls container `popImplicitMutations` first, then index `popImplicitMutations` -- verify `DataStoreMemoryBuffer` visibility |
| 11 | **Reference still indexed for filtering even when not faceted** -- must not skip reference indexing | Low  | Ensure conditional logic only wraps facet add/remove, not the entire reference indexing flow                    |

### Architectural Decisions

- **AD 8 -- Single entity hop limitation:** Expressions can only reference immediate
  entity/reference/group/referenced entity data, no multi-hop chains.
- **AD 9 -- Proxycian proxy wrapping:** Contract types (`EntityContract`, `ReferenceContract`)
  are implemented via Proxycian-generated proxies that delegate directly to storage part data.
  Avoids full entity assembly pipeline and the `Reference.getReferencedEntity()` ->
  `Optional.empty()` problem. Same pattern as `ReferencedTypeEntityIndex.createThrowingStub()`.
- **AD 10 -- Composable partial implementations:** Each partial is a
  `PredicateMethodClassification` that implements a related set of contract methods. Partials
  are selected per expression based on `AccessedDataFinder` path analysis. A catch-all partial
  throws `ExpressionEvaluationException` for unused methods, providing fail-fast safety against
  `@Delegate` delegation to unimplemented methods.
- **AD 11 -- Two-phase lifecycle:** Proxy classes and state recipes are built at schema load
  time (expensive but amortized). At trigger time, only state records are created and pre-built
  proxy classes instantiated (cheap -- ~2 allocations per entity). No class generation on the
  hot path.

## Key Interfaces

| Interface / Class                          | Role                                                                                     |
|--------------------------------------------|------------------------------------------------------------------------------------------|
| `ReferenceIndexMutator`                    | Static methods that apply reference mutations to indexes; to be extended with expression guard |
| `EntityIndexLocalMutationExecutor`         | Passed to `ReferenceIndexMutator`; provides `getTriggerFor(referenceName, scope)`         |
| `ExpressionIndexTrigger`                   | Encapsulates expression + proxy infrastructure; `evaluate(pk, refKey, accessor) -> boolean` |
| `WritableEntityStorageContainerAccessor`   | Provides storage part access (including pending mutations) to `evaluate()`                |
| `FacetIdIndex`                             | `records.contains(entityPK)` determines "was faceted before"                              |
| `ContainerizedLocalMutationExecutor`       | Implements `EntityStoragePartAccessor`; caches attribute values for mutation ordering      |
| `LocalMutation#compareTo`                  | Used for CDC ordering; does NOT control execution ordering of mutations within a single entity |

## Acceptance Criteria

1. When a reference schema defines a facet expression, `ReferenceIndexMutator` evaluates it
   via `ExpressionIndexTrigger.evaluate()` before adding/removing a facet.
2. The guard `isFaceted() && (noExpression || trigger.evaluate(pk, refKey, accessor))` is
   applied at every facet add/remove call site.
3. For `InsertReferenceMutation` with expression evaluating to `false`: the reference is still
   indexed for filtering, but the facet is NOT added to the facet index.
4. For all 10 local-trigger mutations: when the mutation touches data used by the expression,
   the expression is re-evaluated and the was/now decision matrix is applied.
5. "Was faceted before" is determined via `FacetIdIndex.records.contains(entityPK)`, not a
   separate stored flag.
6. Expression evaluation is correct regardless of mutation ordering within the same entity:
   the `WritableEntityStorageContainerAccessor` reflects all previously applied mutations,
   so the expression always sees up-to-date storage part data.
7. Per-scope evaluation is supported: each scope's expression is evaluated independently.
8. Expression evaluation uses `WritableEntityStorageContainerAccessor` (not the query engine)
   to avoid circular fetch during mutation (Trap #1).
9. Null-safe access (`?.`) in expressions does not throw; non-null-safe paths throw
   `ExpressionEvaluationException` when the target is null (Trap #6).
10. Transactional atomicity: expression evaluation and the resulting facet index update occur
    within the same transaction boundary.

## Implementation Notes

- `ReferenceIndexMutator` methods are static. The `EntityIndexLocalMutationExecutor` argument
  is the natural place to expose `getTriggerFor(referenceName, scope)`, which returns either
  the cached `ExpressionIndexTrigger` or `null` (no expression defined).
- The `WritableEntityStorageContainerAccessor` already supports cross-entity storage part
  fetching -- the same accessor instance used by `ContainerizedLocalMutationExecutor` is passed
  through to `evaluate()`.
- The `evaluate()` call is lightweight at trigger time (~2 allocations per entity) because
  proxy classes and state recipes were pre-built at schema load time (AD 11).
- Each re-evaluation mutation (e.g., `UpsertAttributeMutation` changing an attribute used by
  the expression) must check all references of the current entity that have expressions
  depending on that attribute. The trigger's recipe knows which data paths are accessed, so
  only affected references need re-evaluation.
- Be careful that `removeFacetInIndex()` is only called when the entity was previously faceted
  (`FacetIdIndex.records.contains(entityPK)` returns `true`) and the expression now evaluates
  to `false`. Conversely, `addFacetToIndex()` is only called when the entity was not previously
  faceted and the expression now evaluates to `true`.
- Index operations are idempotent, which provides a safety net against double re-evaluation
  (Trap #10), but the executor can skip redundant operations via a
  `Set<(entityPK, referenceName)>` of already-processed pairs for efficiency.
- **Error handling for `evaluate()` exceptions:** If `evaluate()` throws an unexpected exception
  (not just `ExpressionEvaluationException` from null-safe violations), the exception should
  propagate up and abort the current mutation processing. The facet state remains unchanged
  (the `undoActionConsumer` mechanism unwinds any partial changes within the same mutation).
  No special catch-and-continue logic is needed -- the transactional infrastructure ensures
  atomicity, and a failing expression evaluation indicates a bug or schema misconfiguration
  that should be surfaced immediately rather than silently swallowed.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### `isFaceted()` private guard method — single definition, four call sites

The `isFaceted()` private static method is defined at line 687-697 in `ReferenceIndexMutator.java`:

```java
private static boolean isFaceted(
    @Nonnull ReferenceKey referenceKey,
    @Nonnull ReferenceSchemaContract referenceSchema,
    @Nonnull Scope scope,
    @Nonnull EntityIndexLocalMutationExecutor executor
) {
    final ReferenceSchemaContract referenceSchemaToUse = getReferenceSchemaFor(
        referenceKey, referenceSchema, executor
    );
    return referenceSchemaToUse.isFacetedInScope(scope);
}
```

It is called at exactly four locations in `ReferenceIndexMutator.java`, always paired with `shouldIndexFacetToTargetIndex()`:

| Call site method | Line | Guard pattern |
|---|---|---|
| `addFacetToIndex()` | 1133-1136 | `shouldIndexFacetToTargetIndex(...) && isFaceted(referenceKey, referenceSchema, scope, executor)` |
| `setFacetGroupInIndex()` | 1178-1180 | `shouldIndexFacetToTargetIndex(...) && isFaceted(referenceKey, referenceSchema, scope, executor)` |
| `removeFacetInIndex()` | 1285-1287 | `shouldIndexFacetToTargetIndex(...) && isFaceted(referenceKey, referenceSchema, scope, executor)` |
| `removeFacetGroupInIndex()` | 1328-1330 | `shouldIndexFacetToTargetIndex(...) && isFaceted(referenceKey, referenceSchema, scope, executor)` |

##### Direct `isFacetedInScope()` calls — two additional guard sites

Two methods bypass `isFaceted()` and call `referenceKeySchema.isFacetedInScope(scope)` directly:

| Method | Line | Context |
|---|---|---|
| `indexAllFacets()` | 1659 | `reference.exists() && referenceKeySchema.isFacetedInScope(scope)` — iterates all references when populating a new reduced index |
| `removeAllFacets()` | 1888 | `reference.exists() && referenceKeySchema.isFacetedInScope(scope)` — iterates all references when depopulating a removed reduced index |

These two methods iterate **all** references of the entity (not a single reference), adding/removing facets en masse when a new reduced index is created or destroyed. Expression evaluation must be applied to each individual reference within the loop.

##### `addFacetToIndex()` — full method signature (line 1123-1143)

```java
static void addFacetToIndex(
    @Nonnull EntityIndex index,
    @Nonnull ReferenceSchemaContract referenceSchema,
    @Nonnull ReferenceKey referenceKey,
    @Nullable Integer groupId,
    int entityPrimaryKey,
    @Nonnull EntityIndexLocalMutationExecutor executor,
    @Nullable Consumer<Runnable> undoActionConsumer
)
```

Called from:
- `referenceInsertGlobal()` (line 824) — global index, during `InsertReferenceMutation`
- `referenceInsertPerComponent()` (line 929-931) — reduced index, during `InsertReferenceMutation`
- `updateReferencesInReferenceIndex()` in `EntityIndexLocalMutationExecutor` (line 2078-2086) — cross-reference propagation of `InsertReferenceMutation` to other reduced indexes

##### `setFacetGroupInIndex()` — full method signature (line 1169-1207)

```java
static void setFacetGroupInIndex(
    int entityPrimaryKey,
    @Nonnull EntityIndex index,
    @Nonnull ReferenceSchemaContract referenceSchema,
    @Nonnull ReferenceKey referenceKey,
    @Nonnull Integer groupId,
    @Nonnull EntityIndexLocalMutationExecutor executor
)
```

Called from `EntityIndexLocalMutationExecutor.updateReferences()` (line 1691-1705) for global and entity reduced indexes, and from `updateReferencesInReferenceIndex()` (line 2058-2066) for cross-reference propagation.

##### `removeFacetInIndex()` — full method signature (line 1276-1294)

```java
static void removeFacetInIndex(
    @Nonnull EntityIndex index,
    @Nonnull ReferenceSchemaContract referenceSchema,
    @Nonnull ReferenceKey referenceKey,
    int entityPrimaryKey,
    @Nonnull EntityIndexLocalMutationExecutor executor,
    @Nullable Consumer<Runnable> undoActionConsumer
)
```

Called from:
- `referenceRemovalGlobal()` (line 1002) — global index, during `RemoveReferenceMutation`
- `updateReferencesInReferenceIndex()` (line 2088-2095) — cross-reference propagation of `RemoveReferenceMutation`

Note: `referenceRemovalPerComponent()` does NOT call `removeFacetInIndex()` — reduced index per-component removal removes the PK from the index entirely (lines 1069-1101), which implicitly removes facets.

##### `removeFacetGroupInIndex()` — full method signature (line 1320-1358)

```java
static void removeFacetGroupInIndex(
    int entityPrimaryKey,
    @Nonnull EntityIndex index,
    @Nonnull ReferenceSchemaContract referenceSchema,
    @Nonnull ReferenceKey referenceKey,
    @Nonnull EntityIndexLocalMutationExecutor executor
)
```

Called from `EntityIndexLocalMutationExecutor.updateReferences()` (line 1729-1742) for global and entity reduced indexes, and from `updateReferencesInReferenceIndex()` (line 2067-2074) for cross-reference propagation.

##### `EntityIndexLocalMutationExecutor` — trigger and accessor access

- **File:** `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java`
- **Constructor** (line 186-206): receives `WritableEntityStorageContainerAccessor containerAccessor` as first argument
- **`getContainerAccessor()`** (line 123, `@Getter`): returns `WritableEntityStorageContainerAccessor` — this is the accessor to pass to `evaluate()`
- **`getEntitySchema()`** (line 816-817): returns `this.schemaAccessor.get()` — provides access to `ReferenceSchemaContract` and thus to `getFacetedPartiallyInScope(scope)` to determine if an expression exists
- **`getScope()`** (line 215-225): returns the memoized scope of the current entity
- **`getPrimaryKeyToIndex(IndexType, Target)`**: returns the entity PK for the given context
- **`getReferencesStoragePart()`** (line 572-576): returns `ReferencesStoragePart` for the entity

The executor does NOT currently hold trigger references. Per WBS-03, triggers are obtained via a `getTriggerFor(referenceName, scope)` method to be added to `EntityIndexLocalMutationExecutor`. The trigger can be cached per `(referenceName, scope)` pair during a single mutation execution.

##### `ReferenceKey` construction pattern

`ReferenceKey` is passed directly into all `ReferenceIndexMutator` methods. In `EntityIndexLocalMutationExecutor.updateReferences()` (line 1681):
```java
final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
```

The `entityPrimaryKey` (entity PK, `epk`) is obtained via:
```java
final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
```

Both values are available at every call site — no additional plumbing needed.

##### `FacetIdIndex` — "was faceted before" determination

- **File:** `evita_engine/src/main/java/io/evitadb/index/facet/FacetIdIndex.java`
- **`records` field** (line 56): `TransactionalBitmap` — contains all entity PKs that refer to this facet
- **`Bitmap.contains(int recordId)`** (line 108 in `Bitmap.java`): returns `true` if the PK is present

Navigation path from `EntityIndex` to the check:
1. `index.getFacetingEntities()` → `Map<String, FacetReferenceIndex>` (delegated from `FacetIndex`, line 127-128 in `EntityIndex`)
2. `facetReferenceIndex.getNotGroupedFacets()` → `FacetGroupIndex` (or `.getGroupedFacets()` for grouped)
3. `facetGroupIndex.getFacetIdIndex(referenceKey.primaryKey())` → `FacetIdIndex` (line 182 in `FacetGroupIndex`)
4. `facetIdIndex.getRecords().contains(entityPK)` → boolean

The existing `isFacetPresentInGroup()` helper (line 1238-1256 in `ReferenceIndexMutator`) already implements a similar navigation, but checks presence at the group bucket level rather than at the entity PK level within a specific facet. A new helper method `wasFaceted(EntityIndex, ReferenceKey, int entityPK)` should be created following the same pattern.

##### `ContainerizedLocalMutationExecutor` relationship

- **File:** `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java`
- `ContainerizedLocalMutationExecutor` implements `ConsistencyCheckingLocalMutationExecutor` (which extends `LocalMutationExecutor`)
- It is the **storage-part mutation executor** (applies mutations to storage containers), separate from `EntityIndexLocalMutationExecutor` (which applies mutations to indexes)
- Both executors are created in `LocalMutationExecutorCollector.applyMutations()` and receive the same `WritableEntityStorageContainerAccessor` instance
- **Key observation:** `ContainerizedLocalMutationExecutor.applyMutation()` applies storage changes AFTER index changes in the execution flow (line 250-252 in `LocalMutationExecutorCollector`): first `entityIndexUpdater.applyMutation(localMutation)`, then `changeCollector.applyMutation(localMutation)`. This means when `EntityIndexLocalMutationExecutor` processes a mutation, the storage parts have NOT yet been updated with THIS mutation's changes. However, ALL PREVIOUS mutations in the list have been applied to storage. This is the correct behavior for expression evaluation: the expression sees the state BEFORE the current mutation but AFTER all preceding mutations.

##### Mutation ordering — `LocalMutation#compareTo` (line 128-146 in `LocalMutation.java`)

The `compareTo` implementation sorts by:
1. **Priority** (`getPriority()`) — higher priority first. Both `UpsertAttributeMutation` and `InsertReferenceMutation` return `PRIORITY_UPSERT = 0L`.
2. **Class simple name** (alphabetical) — `InsertReferenceMutation` ("I") sorts BEFORE `UpsertAttributeMutation` ("U").

**CRITICAL FINDING:** Mutations are NOT sorted by the framework. `EntityUpsertMutation.getLocalMutations()` (line 223-225) returns the list in the order provided by the caller. The `LocalMutationExecutorCollector.applyMutations()` (line 250) iterates them in that order. The `compareTo` method exists for CDC ordering, not for execution ordering.

**Implication for expression evaluation:** When a user sends `[UpsertAttribute('code','ABC'), InsertReference('param',5)]`, the attribute mutation is applied first ONLY if the caller ordered them that way. The framework does NOT guarantee attribute-before-reference ordering. However, this is mitigated by the storage-part caching mechanism:

- `EntityIndexLocalMutationExecutor` reads storage parts via `WritableEntityStorageContainerAccessor`
- `ContainerizedLocalMutationExecutor` applies each mutation to the storage parts BEFORE the next mutation's index update sees them (the sequence per mutation is: index update -> storage update, but for the NEXT mutation: the storage already has the previous update)
- When `InsertReferenceMutation` triggers expression evaluation, the `WritableEntityStorageContainerAccessor` already has the updated attribute values from any preceding `UpsertAttributeMutation`

The WBS-09 document's statement "attribute mutations sort before reference mutations via `LocalMutation#compareTo`" is **incorrect** for execution ordering. The actual ordering depends on the caller. However, the expression evaluation still works correctly because it reads from the `WritableEntityStorageContainerAccessor` which reflects all previously applied mutations.

##### All call sites from `EntityIndexLocalMutationExecutor` into `ReferenceIndexMutator` facet methods

These are the sites in `EntityIndexLocalMutationExecutor` that need awareness of expression guards:

| Method in `EntityIndexLocalMutationExecutor` | Line | ReferenceIndexMutator method called | Mutation context |
|---|---|---|---|
| `updateReferences()` | 1691 | `setFacetGroupInIndex()` — global index | `SetReferenceGroupMutation` |
| `updateReferences()` | 1701-1705 | `setFacetGroupInIndex()` — entity reduced index | `SetReferenceGroupMutation` |
| `updateReferences()` | 1729 | `removeFacetGroupInIndex()` — global index | `RemoveReferenceGroupMutation` |
| `updateReferences()` | 1738-1741 | `removeFacetGroupInIndex()` — entity reduced index | `RemoveReferenceGroupMutation` |
| `updateReferences()` | 1809-1811 | `referenceInsertGlobal()` → `addFacetToIndex()` | `InsertReferenceMutation` |
| `updateReferences()` | 1821-1827 | `referenceInsertPerComponent()` → `addFacetToIndex()` | `InsertReferenceMutation` |
| `updateReferences()` | 1834-1837 | `referenceRemovalGlobal()` → `removeFacetInIndex()` | `RemoveReferenceMutation` |
| `updateReferences()` | 1846-1852 | `referenceRemovalPerComponent()` (no direct facet call) | `RemoveReferenceMutation` |
| `updateReferencesInReferenceIndex()` | 2059-2066 | `setFacetGroupInIndex()` — cross-reference reduced | `SetReferenceGroupMutation` |
| `updateReferencesInReferenceIndex()` | 2068-2074 | `removeFacetGroupInIndex()` — cross-reference reduced | `RemoveReferenceGroupMutation` |
| `updateReferencesInReferenceIndex()` | 2078-2086 | `addFacetToIndex()` — cross-reference reduced | `InsertReferenceMutation` |
| `updateReferencesInReferenceIndex()` | 2088-2095 | `removeFacetInIndex()` — cross-reference reduced | `RemoveReferenceMutation` |

##### Key file paths

| File | Path |
|---|---|
| `ReferenceIndexMutator` | `evita_engine/src/main/java/io/evitadb/index/mutation/index/ReferenceIndexMutator.java` |
| `EntityIndexLocalMutationExecutor` | `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java` |
| `FacetIdIndex` | `evita_engine/src/main/java/io/evitadb/index/facet/FacetIdIndex.java` |
| `FacetGroupIndex` | `evita_engine/src/main/java/io/evitadb/index/facet/FacetGroupIndex.java` |
| `FacetReferenceIndex` | `evita_engine/src/main/java/io/evitadb/index/facet/FacetReferenceIndex.java` |
| `FacetIndex` | `evita_engine/src/main/java/io/evitadb/index/facet/FacetIndex.java` |
| `FacetIndexContract` | `evita_engine/src/main/java/io/evitadb/index/facet/FacetIndexContract.java` |
| `EntityIndex` | `evita_engine/src/main/java/io/evitadb/index/EntityIndex.java` |
| `ContainerizedLocalMutationExecutor` | `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java` |
| `LocalMutationExecutorCollector` | `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java` |
| `LocalMutation` | `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutation.java` |
| `Bitmap` | `evita_engine/src/main/java/io/evitadb/index/bitmap/Bitmap.java` |
| `ReferenceSchemaContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java` |
| `ReferenceIndexMutator test` | `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/index/ReferenceIndexMutatorTest.java` |

---

#### Detailed Task List

##### Group 1: Trigger access infrastructure in `EntityIndexLocalMutationExecutor`

- [ ] **1.1** Add a `getTriggerFor(String referenceName, Scope scope)` method to `EntityIndexLocalMutationExecutor` that returns `@Nullable FacetExpressionTrigger`. Implementation: look up the `ReferenceSchemaContract` via `getEntitySchema().getReferenceOrThrowException(referenceName)`, check if `referenceSchema.getFacetedPartiallyInScope(scope) != null`. If no expression, return `null`. Otherwise, construct/retrieve the trigger from a trigger supplier injected at construction time. The trigger supplier is a `BiFunction<String, Scope, FacetExpressionTrigger>` (or a dedicated functional interface) passed as a constructor argument, abstracting the trigger lookup mechanism (which is provided by WBS-03/WBS-04).

- [ ] **1.2** Add a `@Nullable BiFunction<String, Scope, FacetExpressionTrigger> triggerSupplier` parameter to `EntityIndexLocalMutationExecutor` constructor. When `null`, expression evaluation is disabled (backward compatible — no expression triggers installed). Update all constructor call sites (in `LocalMutationExecutorCollector` and any test factories) to pass `null` initially. The actual supplier will be wired in by the trigger registry integration WBS.

- [ ] **1.3** Add per-execution memoization for trigger lookups. Add a `Map<String, Map<Scope, FacetExpressionTrigger>>` field (lazily initialized) to cache results of `getTriggerFor()` within a single entity mutation execution. This avoids redundant trigger resolution when multiple references of the same type are processed.

##### Group 2: "Was faceted" check helper

- [ ] **2.1** Create a private static helper method `wasFaceted(EntityIndex index, ReferenceKey referenceKey, int entityPrimaryKey)` in `ReferenceIndexMutator` returning `boolean`. Implementation: navigate `index.getFacetingEntities().get(referenceKey.referenceName())` to get `FacetReferenceIndex`, then check the ungrouped bucket first (`getNotGroupedFacets()`) for a `FacetIdIndex` matching `referenceKey.primaryKey()`, and if not found, check grouped buckets (`getGroupedFacets()`). For each `FacetGroupIndex`, call `getFacetIdIndex(referenceKey.primaryKey())` and check `facetIdIndex.getRecords().contains(entityPrimaryKey)`. Return `false` if any step yields `null`. The existing `isFacetPresentInGroup()` helper (line 1238-1256) provides a similar navigation pattern for a known group; `wasFaceted()` differs in that it searches across all groups since the group is not known a priori. This encapsulates the "was faceted before" determination from the WBS specification.

- [ ] **2.2** Add JavaDoc to the helper explaining its role in the re-evaluation decision matrix: the method determines whether the entity was previously in the facet index for a given reference, enabling the was/now matrix logic (`was=true, now=false` -> remove; `was=false, now=true` -> add; otherwise no-op).

##### Group 3: Expression-aware guard extension for `addFacetToIndex()`

- [ ] **3.1** Extend the `addFacetToIndex()` method (line 1123-1143) to incorporate expression evaluation. After the existing `isFaceted()` check passes, check if a `FacetExpressionTrigger` exists for `(referenceKey.referenceName(), scope)` via `executor.getTriggerFor()`. If no trigger exists (null), proceed as before (unconditional faceting). If a trigger exists, call `trigger.evaluate(entityPrimaryKey, referenceKey, executor.getContainerAccessor())`. Only call `index.addFacet()` if `evaluate()` returns `true`.

- [ ] **3.2** Ensure the guard extension does NOT change behavior when no expression is defined — the `getTriggerFor()` returning `null` must be treated identically to the current unconditional path. This preserves full backward compatibility.

##### Group 4: Expression-aware guard extension for `setFacetGroupInIndex()`

- [ ] **4.1** Extend the `setFacetGroupInIndex()` method (line 1169-1207) to incorporate expression evaluation. After the existing `isFaceted()` check passes, if a trigger exists, evaluate it. If `evaluate()` returns `false`, the entity should not be faceted at all — skip both the remove-old-group and add-new-group operations. If `evaluate()` returns `true`, proceed with the existing group reassignment logic.

- [ ] **4.2** Handle the re-evaluation edge case: the entity may currently be faceted (was=true) but the expression now evaluates to false due to the group change (now=false). In this case, instead of reassigning the group, remove the facet entirely. Apply the decision matrix: check `wasFaceted()`, evaluate expression, then act accordingly.

##### Group 5: Expression-aware guard extension for `removeFacetInIndex()`

- [ ] **5.1** Extend the `removeFacetInIndex()` method (line 1276-1294) with expression awareness. For `RemoveReferenceMutation`, the existing behavior is correct: if the reference is being removed entirely, the facet should always be removed (no expression evaluation needed — the reference no longer exists). However, add a guard to only call `removeFacetInIndexInternal()` if the entity was actually faceted (`wasFaceted()` returns true). This prevents unnecessary removal attempts when the expression previously evaluated to false and the facet was never added.

##### Group 6: Expression-aware guard extension for `removeFacetGroupInIndex()`

- [ ] **6.1** Extend the `removeFacetGroupInIndex()` method (line 1320-1358) to incorporate expression evaluation. Similar to `setFacetGroupInIndex()`: if a trigger exists and `evaluate()` returns `false`, the entity should not be faceted — skip the group removal and re-add operations. If the entity was faceted and should no longer be (was=true, now=false), just remove it. Apply the full decision matrix.

##### Group 7: Expression-aware guard extension for `indexAllFacets()` and `removeAllFacets()`

- [ ] **7.1** Extend the `indexAllFacets()` method (line 1637-1673) to evaluate the expression per-reference in the loop. At line 1659, where the current check is `reference.exists() && referenceKeySchema.isFacetedInScope(scope)`, add an additional expression evaluation gate: if a trigger exists for `(referenceKey.referenceName(), scope)`, call `trigger.evaluate(entityPrimaryKey, referenceKey, executor.getContainerAccessor())`. Only add the facet if the evaluation returns `true`.

- [ ] **7.2** Extend the `removeAllFacets()` method (line 1868-1895) similarly. At line 1888, add the expression check. Only attempt facet removal if the entity was actually faceted (which it might not be if the expression previously evaluated to false). Use `wasFaceted()` to guard the removal.

##### Group 8: `InsertReferenceMutation` handling — conditional facet add

- [ ] **8.1** In `EntityIndexLocalMutationExecutor.updateReferences()` for `InsertReferenceMutation` (line 1803-1828): the `referenceInsertGlobal()` call (line 1809) delegates to `addFacetToIndex()` which will now have the expression guard from Group 3. No changes needed at this call site — the guard is in `addFacetToIndex()` itself. Verify that the reference is still indexed for filtering even when the expression evaluates to false (the `referenceInsertPerComponent()` call at line 1821 does PK registration and attribute indexing BEFORE calling `addFacetToIndex()` at line 929 — this naturally ensures the reference is indexed for filtering regardless of facet expression result).

- [ ] **8.2** In `EntityIndexLocalMutationExecutor.updateReferencesInReferenceIndex()` for `InsertReferenceMutation` (line 2077-2086): the `addFacetToIndex()` call at line 2078 handles cross-reference facet propagation. The expression guard from Group 3 applies here as well. Verify that the `entityPrimaryKey` and `referenceKey` are correct for expression evaluation in this cross-reference context.

##### Group 9: `RemoveReferenceMutation` handling — conditional facet remove

- [ ] **9.1** In `EntityIndexLocalMutationExecutor.updateReferences()` for `RemoveReferenceMutation` (line 1829-1860): the `referenceRemovalGlobal()` call (line 1834) delegates to `removeFacetInIndex()` which will now have the `wasFaceted()` guard from Group 5. No changes needed at this call site. Verify behavior: if the entity was never faceted (expression evaluated to false at insert time), the removal should be a no-op.

- [ ] **9.2** In `updateReferencesInReferenceIndex()` for `RemoveReferenceMutation` (line 2087-2095): same as above, the guard in `removeFacetInIndex()` handles this.

##### Group 10: Re-evaluation for non-reference local mutations

- [ ] **10.1** Identify all local mutation types that can cause re-evaluation of facet expressions by changing data the expression reads. Per the Local Trigger Mutation Table in this WBS: `UpsertAttributeMutation` (entity attributes), `UpsertReferenceAttributeMutation` (reference attributes), `UpsertAssociatedDataMutation`, `RemoveAssociatedDataMutation`, `SetParentMutation`, `RemoveParentMutation`, `SetReferenceGroupMutation`, `RemoveReferenceGroupMutation`.

- [ ] **10.2** For `SetReferenceGroupMutation` and `RemoveReferenceGroupMutation`: these already flow through `setFacetGroupInIndex()` and `removeFacetGroupInIndex()` which will have expression guards from Groups 4 and 6. The expression is evaluated at the point of facet modification. The re-evaluation decision matrix is applied within those methods. No additional re-evaluation dispatch needed.

- [ ] **10.3** For non-reference mutations (`UpsertAttributeMutation`, `UpsertAssociatedDataMutation`, `RemoveAssociatedDataMutation`, `SetParentMutation`, `RemoveParentMutation`): these do NOT currently flow through any facet modification path. When such a mutation changes data used by a facet expression, the expression result may change, requiring re-evaluation. This is a NEW code path. In `EntityIndexLocalMutationExecutor.applyMutation()`, after applying the primary effect of each such mutation, check if any reference expressions depend on the mutated data. If so, for each affected reference, evaluate the expression, apply the was/now decision matrix, and call `addFacetToIndex()` or `removeFacetInIndex()` as needed. **Implementation note:** Consider extracting a shared re-evaluation dispatch helper (e.g., `dispatchFacetReEvaluation(executor, entityPK, dependencyType)`) before implementing per-mutation-type dispatch in Tasks 10.4-10.7, to consolidate the common pattern of iterating affected references, evaluating expressions, and applying the decision matrix.

- [ ] **10.4** Implement the re-evaluation dispatch for entity-level attribute mutations (line 326-348 in `applyMutation()`). After the existing attribute update logic, iterate all references that have expressions depending on entity attributes. For each such reference and each scope: evaluate the expression, check `wasFaceted()`, apply decision matrix. This requires the trigger to expose `getDependentAttributes()` or a method to check if a given attribute name is relevant.

- [ ] **10.5** Implement the re-evaluation dispatch for `UpsertReferenceAttributeMutation`. This is already a `ReferenceMutation` and flows through `updateReferences()` at line 1752 as `ReferenceAttributeMutation`. After the existing attribute update logic, if the reference has a facet expression and the mutated attribute is used by the expression, re-evaluate and apply the decision matrix on the global index and all relevant reduced indexes.

- [ ] **10.6** Implement the re-evaluation dispatch for associated data mutations (line 349-350 in `applyMutation()`). Currently a no-op for index updates. If any reference expression depends on associated data, re-evaluate. Note: this is expected to be rare in practice.

- [ ] **10.7** Implement the re-evaluation dispatch for parent mutations (line 305-306 in `applyMutation()`). After the existing hierarchy placement update, if any reference expression depends on `$entity.parent`, re-evaluate all affected references.

##### Group 11: Re-evaluation decision matrix implementation

- [ ] **11.1** Create a private static helper method `applyFacetDecisionMatrix(EntityIndex index, ReferenceSchemaContract referenceSchema, ReferenceKey referenceKey, Integer groupId, int entityPrimaryKey, boolean wasFaceted, boolean nowFaceted, EntityIndexLocalMutationExecutor executor, Consumer<Runnable> undoActionConsumer)` in `ReferenceIndexMutator`. Implementation:
  - `was=true, now=false`: call `removeFacetInIndexInternal()` (remove from facet index)
  - `was=false, now=true`: call `index.addFacet()` (add to facet index)
  - `was=true, now=true`: no-op (still faceted, nothing changed)
  - `was=false, now=false`: no-op (still not faceted, nothing changed)

- [ ] **11.2** Add JavaDoc documenting the decision matrix table and its invariants: idempotency of index operations (calling add when already present or remove when already absent is safe due to the bitmap's idempotent semantics, but we skip them for efficiency).

##### Group 12: Scope-aware evaluation

- [ ] **12.1** Ensure all expression evaluation calls pass the correct scope. The scope is always available from `index.getIndexKey().scope()` (at the `ReferenceIndexMutator` level) or `executor.getScope()` (at the `EntityIndexLocalMutationExecutor` level). Each scope may have a different expression (or no expression), so `getTriggerFor(referenceName, scope)` must be scope-specific.

- [ ] **12.2** When an entity has references in multiple scopes (e.g., live and archived), each scope's expression is evaluated independently. The decision matrix is applied per-scope. This is naturally handled by the existing per-scope index traversal in `ReferenceIndexMutator`.

### Test Cases

Tests are organized across three test classes. `ReferenceIndexMutatorExpressionGuardTest` covers
the extended `isFaceted()` guard and the six facet-modifying methods. `ReferenceIndexMutatorDecisionMatrixTest`
covers the re-evaluation decision matrix and the `wasFaceted()` helper. `ReferenceIndexMutatorReEvaluationTest`
covers re-evaluation triggered by non-reference local mutations, scope-aware behavior, and
backward compatibility.

All test method names use descriptive `snake_case` as required by the project test conventions.

---

#### Class: `ReferenceIndexMutatorExpressionGuardTest`

Extends `AbstractMutatorTestBase`. Sets up a reference schema with a facet expression via
a mock `FacetExpressionTrigger`. Uses `EntityIndexLocalMutationExecutor` constructed with
a `triggerSupplier` that returns a controllable mock trigger.

##### Category: `addFacetToIndex()` expression guard (Task 3.1, 3.2)

- [ ] `add_facet_to_index_should_add_facet_when_no_expression_defined` -- trigger supplier returns `null` for the reference; verify facet is added to the index (backward-compatible unconditional path).
- [ ] `add_facet_to_index_should_add_facet_when_expression_evaluates_to_true` -- trigger returns `true`; verify facet is added to the `FacetIdIndex` for the entity PK.
- [ ] `add_facet_to_index_should_skip_facet_when_expression_evaluates_to_false` -- trigger returns `false`; verify facet is NOT added to the `FacetIdIndex`, but the method completes without error.
- [ ] `add_facet_to_index_should_pass_correct_arguments_to_evaluate` -- verify `trigger.evaluate()` is called with the correct `entityPrimaryKey`, `ReferenceKey`, and `WritableEntityStorageContainerAccessor` obtained from `executor.getContainerAccessor()`.
- [ ] `add_facet_to_index_should_still_index_reference_for_filtering_when_expression_is_false` -- after `addFacetToIndex()` skips facet due to expression=false, verify the entity PK is still present in the reduced index (reference is indexed for filtering).

##### Category: `setFacetGroupInIndex()` expression guard (Task 4.1, 4.2)

- [ ] `set_facet_group_should_reassign_group_when_no_expression_defined` -- no trigger; verify group reassignment proceeds as normal (backward compatible).
- [ ] `set_facet_group_should_reassign_group_when_expression_evaluates_to_true` -- trigger returns `true`; verify old group is removed and new group is set in the facet index.
- [ ] `set_facet_group_should_skip_group_operations_when_expression_evaluates_to_false_and_was_not_faceted` -- trigger returns `false` and entity was never faceted; verify no group add/remove operations occur (no-op).
- [ ] `set_facet_group_should_remove_facet_when_expression_flips_to_false_due_to_group_change` -- entity was faceted (was=true), group change causes expression to evaluate to `false` (now=false); verify facet is removed entirely instead of reassigning the group.
- [ ] `set_facet_group_should_add_facet_when_expression_flips_to_true_due_to_group_change` -- entity was NOT faceted (was=false), group change causes expression to evaluate to `true` (now=true); verify facet is added with the new group.

##### Category: `removeFacetInIndex()` expression guard (Task 5.1)

- [ ] `remove_facet_should_remove_when_entity_was_faceted` -- entity was in the facet index; verify `RemoveReferenceMutation` removes the facet regardless of expression (no expression evaluation for full removal).
- [ ] `remove_facet_should_be_noop_when_entity_was_not_faceted` -- entity was never faceted (expression previously evaluated to `false`); verify removal is a no-op (no exception, no index modification).
- [ ] `remove_facet_should_not_evaluate_expression_for_full_reference_removal` -- verify `trigger.evaluate()` is NOT called during `RemoveReferenceMutation` processing; the facet is always removed if it was present.

##### Category: `removeFacetGroupInIndex()` expression guard (Task 6.1)

- [ ] `remove_facet_group_should_remove_group_when_no_expression_defined` -- no trigger; verify group removal proceeds as normal (backward compatible).
- [ ] `remove_facet_group_should_remove_group_when_expression_evaluates_to_true` -- trigger returns `true`; verify group is removed and facet remains in the ungrouped bucket.
- [ ] `remove_facet_group_should_remove_facet_entirely_when_was_faceted_and_expression_now_false` -- entity was faceted with a group (was=true), expression now evaluates to `false` (now=false); verify facet is removed entirely (not just the group).
- [ ] `remove_facet_group_should_be_noop_when_was_not_faceted_and_expression_still_false` -- entity was not faceted, expression still `false`; verify no-op.

##### Category: `indexAllFacets()` expression guard (Task 7.1)

- [ ] `index_all_facets_should_evaluate_expression_per_reference_in_loop` -- entity has 3 references of the same type; expression returns `true` for ref PKs 10 and 30, `false` for ref PK 20; verify only PKs 10 and 30 are added to the facet index.
- [ ] `index_all_facets_should_add_all_facets_when_no_expression_defined` -- no trigger; verify all references that are `isFacetedInScope()` are added to the facet index (backward compatible).
- [ ] `index_all_facets_should_add_no_facets_when_expression_is_false_for_all` -- expression returns `false` for every reference; verify no facets are added but references are still in the reduced index.

##### Category: `removeAllFacets()` expression guard (Task 7.2)

- [ ] `remove_all_facets_should_only_remove_previously_faceted_references` -- entity has 3 references; only 2 were faceted (expression was `true` at insert time); verify only the 2 previously faceted entries are removed, no error for the non-faceted one.
- [ ] `remove_all_facets_should_remove_all_when_no_expression_defined` -- no trigger; verify all faceted references are removed (backward compatible).
- [ ] `remove_all_facets_should_be_noop_when_no_references_were_faceted` -- expression was `false` for all references at insert time; verify removal loop does nothing.

---

#### Class: `ReferenceIndexMutatorDecisionMatrixTest`

Extends `AbstractMutatorTestBase`. Focuses on the `wasFaceted()` helper and the
`applyFacetDecisionMatrix()` logic.

##### Category: `wasFaceted()` helper (Task 2.1, 2.2)

- [ ] `was_faceted_should_return_true_when_entity_pk_present_in_facet_id_index` -- add entity PK to the facet index for a given reference, then call `wasFaceted()`; verify it returns `true`.
- [ ] `was_faceted_should_return_false_when_entity_pk_absent_from_facet_id_index` -- empty facet index; verify `wasFaceted()` returns `false`.
- [ ] `was_faceted_should_return_false_when_reference_not_in_facet_reference_index` -- facet reference index has no entry for the given reference name; verify `wasFaceted()` returns `false` (null safety).
- [ ] `was_faceted_should_return_false_when_facet_id_index_does_not_exist_for_reference_pk` -- `FacetReferenceIndex` exists but has no `FacetIdIndex` for the specific `referenceKey.primaryKey()`; verify returns `false`.
- [ ] `was_faceted_should_handle_grouped_facets` -- entity PK is in a grouped `FacetGroupIndex`; verify `wasFaceted()` returns `true` (checks both grouped and ungrouped buckets).
- [ ] `was_faceted_should_handle_ungrouped_facets` -- entity PK is in the ungrouped `FacetGroupIndex`; verify `wasFaceted()` returns `true`.

##### Category: Decision matrix all four combinations (Task 11.1, 11.2)

- [ ] `decision_matrix_was_true_now_false_should_remove_facet` -- entity was faceted, expression now evaluates to `false`; verify `removeFacetInIndexInternal()` is invoked (facet removed from index).
- [ ] `decision_matrix_was_false_now_true_should_add_facet` -- entity was NOT faceted, expression now evaluates to `true`; verify `addFacet()` is invoked on the index (facet added).
- [ ] `decision_matrix_was_true_now_true_should_be_noop` -- entity was faceted and expression still evaluates to `true`; verify neither add nor remove is called.
- [ ] `decision_matrix_was_false_now_false_should_be_noop` -- entity was NOT faceted and expression still evaluates to `false`; verify neither add nor remove is called.
- [ ] `decision_matrix_should_pass_correct_group_id_when_adding` -- when was=false, now=true, verify the facet is added with the correct `groupId` from the reference's current group assignment.
- [ ] `decision_matrix_should_use_facet_id_index_contains_for_was_check` -- verify that the "was faceted" determination uses `FacetIdIndex.records.contains(entityPK)` and NOT a separate stored flag.

##### Category: `InsertReferenceMutation` conditional facet add (Task 8.1, 8.2)

- [ ] `insert_reference_with_expression_true_should_add_facet_to_global_index` -- expression evaluates to `true`; verify facet is present in the global entity index's facet data structure.
- [ ] `insert_reference_with_expression_true_should_add_facet_to_reduced_index` -- expression evaluates to `true`; verify facet is present in the reduced entity index's facet data structure.
- [ ] `insert_reference_with_expression_false_should_not_add_facet_to_global_index` -- expression evaluates to `false`; verify facet is NOT present in the global entity index, but the entity PK IS present in the global index's primary key set.
- [ ] `insert_reference_with_expression_false_should_not_add_facet_to_reduced_index` -- expression evaluates to `false`; verify facet is NOT present in the reduced entity index, but the entity PK IS present in the reduced index.
- [ ] `insert_reference_with_expression_false_should_still_index_reference_attributes` -- expression evaluates to `false`; verify reference attributes (filterable, sortable, unique) are still indexed in the reduced index.
- [ ] `insert_reference_with_expression_false_should_still_create_reduced_index` -- expression evaluates to `false`; verify the reduced entity index is created (reference is indexed for filtering) even though no facet is added.
- [ ] `insert_reference_cross_reference_propagation_should_respect_expression` -- when `updateReferencesInReferenceIndex()` propagates the insert to a cross-reference reduced index, verify expression is evaluated and facet is conditionally added.

##### Category: `RemoveReferenceMutation` handling (Task 9.1, 9.2)

- [ ] `remove_reference_should_remove_facet_when_was_faceted` -- entity was previously faceted (expression was `true` at insert); verify `RemoveReferenceMutation` removes the facet from the global index.
- [ ] `remove_reference_should_not_fail_when_was_not_faceted` -- entity was never faceted (expression was `false` at insert); verify `RemoveReferenceMutation` completes without error and no facet removal is attempted.
- [ ] `remove_reference_should_remove_from_cross_reference_index_when_was_faceted` -- verify facet removal in `updateReferencesInReferenceIndex()` also uses the `wasFaceted()` guard.

---

#### Class: `ReferenceIndexMutatorReEvaluationTest`

Extends `AbstractMutatorTestBase`. Covers re-evaluation triggered by local mutations that
change data used by the expression, scope-aware evaluation, null safety, and backward
compatibility.

##### Category: Entity attribute mutation re-evaluation (Task 10.3, 10.4)

- [ ] `entity_attribute_change_should_add_facet_when_expression_flips_true` -- entity has a reference with expression depending on `$entity.attributes['code']`; attribute `code` is upserted causing expression to flip from `false` to `true`; verify facet is added to the index.
- [ ] `entity_attribute_change_should_remove_facet_when_expression_flips_false` -- entity has a faceted reference; attribute change causes expression to flip from `true` to `false`; verify facet is removed from the index.
- [ ] `entity_attribute_change_should_be_noop_when_expression_result_unchanged_true` -- attribute changes but expression still evaluates to `true`; verify no add/remove operations (was=true, now=true no-op).
- [ ] `entity_attribute_change_should_be_noop_when_expression_result_unchanged_false` -- attribute changes but expression still evaluates to `false`; verify no add/remove operations (was=false, now=false no-op).
- [ ] `entity_attribute_change_should_reevaluate_all_affected_references` -- entity has 3 references of different types; attribute change affects expression of 2 reference types; verify only those 2 are re-evaluated, the third is untouched.
- [ ] `entity_attribute_change_should_not_reevaluate_when_attribute_not_used_by_expression` -- attribute `code` is changed but no expression depends on it; verify no re-evaluation occurs.

##### Category: Reference attribute mutation re-evaluation (Task 10.5)

- [ ] `reference_attribute_change_should_add_facet_when_expression_flips_true` -- expression depends on `$reference.attributes['priority']`; `UpsertReferenceAttributeMutation` changes `priority` causing expression to flip from `false` to `true`; verify facet is added.
- [ ] `reference_attribute_change_should_remove_facet_when_expression_flips_false` -- `priority` change causes expression to flip from `true` to `false`; verify facet is removed.
- [ ] `reference_attribute_change_should_reevaluate_on_global_and_reduced_indexes` -- verify re-evaluation applies the decision matrix on both the global entity index and all relevant reduced indexes.

##### Category: Associated data mutation re-evaluation (Task 10.6)

- [ ] `associated_data_upsert_should_trigger_reevaluation_when_used_by_expression` -- expression depends on `$entity.associatedData['description']`; `UpsertAssociatedDataMutation` changes `description`; verify facet is added or removed per decision matrix.
- [ ] `associated_data_remove_should_trigger_reevaluation_when_used_by_expression` -- `RemoveAssociatedDataMutation` removes associated data used by expression; verify re-evaluation and decision matrix application.
- [ ] `associated_data_change_should_not_trigger_reevaluation_when_not_used_by_expression` -- associated data not referenced by any expression; verify no re-evaluation occurs.

##### Category: Parent mutation re-evaluation (Task 10.7)

- [ ] `set_parent_should_trigger_reevaluation_when_expression_uses_parent` -- expression depends on `$entity.parent`; `SetParentMutation` sets a parent; verify re-evaluation and decision matrix application.
- [ ] `remove_parent_should_trigger_reevaluation_when_expression_uses_parent` -- `RemoveParentMutation` removes parent; verify re-evaluation and decision matrix application.
- [ ] `parent_mutation_should_not_trigger_reevaluation_when_not_used_by_expression` -- expression does not depend on `$entity.parent`; verify no re-evaluation.

##### Category: Group assignment mutation re-evaluation (Task 10.2)

- [ ] `set_reference_group_should_trigger_reevaluation_when_expression_uses_group` -- expression depends on `$reference.groupEntity`; `SetReferenceGroupMutation` sets a group; verify facet add/remove follows the decision matrix (re-evaluation within `setFacetGroupInIndex()`).
- [ ] `remove_reference_group_should_trigger_reevaluation_when_expression_uses_group` -- `RemoveReferenceGroupMutation` removes group; verify re-evaluation within `removeFacetGroupInIndex()`.

##### Category: Scope-aware evaluation (Task 12.1, 12.2)

- [ ] `scope_aware_evaluation_should_use_scope_specific_trigger` -- reference is faceted in both LIVE and ARCHIVE scopes with different expressions; verify `getTriggerFor(referenceName, scope)` is called with the correct scope for each index.
- [ ] `scope_aware_evaluation_should_produce_different_facet_state_per_scope` -- expression evaluates to `true` in LIVE scope and `false` in ARCHIVE scope; verify facet is present in LIVE index but absent in ARCHIVE index for the same entity/reference.
- [ ] `scope_aware_evaluation_should_reevaluate_per_scope_independently` -- attribute change triggers re-evaluation; verify each scope is re-evaluated independently and decision matrix is applied per scope.
- [ ] `scope_transition_should_evaluate_expression_in_new_scope` -- entity moves from LIVE to ARCHIVE scope; verify expression is evaluated in the ARCHIVE scope when indexing into the new scope's indexes.

##### Category: Null safety (Trap #6, AC 9)

- [ ] `null_safe_access_in_expression_should_not_throw_when_group_entity_absent` -- expression uses `$reference.groupEntity?.name`; group entity does not exist; verify `evaluate()` returns `false` (or the configured null-safe default) without throwing an exception.
- [ ] `non_null_safe_access_should_throw_expression_evaluation_exception_when_target_is_null` -- expression uses `$reference.groupEntity.name` (no `?.`); group entity does not exist; verify `ExpressionEvaluationException` is thrown.
- [ ] `null_safe_expression_with_null_attribute_should_evaluate_gracefully` -- expression uses `$entity.attributes['optionalAttr']?.length()`; attribute is null; verify no exception and expression evaluates to `false`.

##### Category: No-expression passthrough / backward compatibility (Task 3.2)

- [ ] `no_trigger_supplier_should_behave_identically_to_current_codebase` -- `EntityIndexLocalMutationExecutor` constructed with `null` trigger supplier; perform `InsertReferenceMutation`, `RemoveReferenceMutation`, `SetReferenceGroupMutation`, `RemoveReferenceGroupMutation`; verify all facet operations proceed unconditionally, identically to the pre-expression codebase.
- [ ] `trigger_supplier_returning_null_for_reference_should_skip_evaluation` -- trigger supplier is non-null but returns `null` for the specific reference name; verify facet operations proceed unconditionally (no expression evaluation call).
- [ ] `no_expression_should_not_trigger_reevaluation_on_attribute_change` -- no expression defined; `UpsertAttributeMutation` changes an attribute; verify no re-evaluation dispatch occurs (no facet add/remove triggered).
- [ ] `no_expression_should_not_trigger_reevaluation_on_associated_data_change` -- no expression defined; `UpsertAssociatedDataMutation` changes associated data; verify no re-evaluation.
- [ ] `no_expression_should_not_trigger_reevaluation_on_parent_change` -- no expression defined; `SetParentMutation` sets a parent; verify no re-evaluation.

##### Category: Trigger access infrastructure (Task 1.1, 1.2, 1.3)

- [ ] `get_trigger_for_should_return_trigger_from_supplier` -- verify `executor.getTriggerFor("brand", Scope.LIVE)` returns the `FacetExpressionTrigger` provided by the supplier.
- [ ] `get_trigger_for_should_return_null_when_no_expression_in_schema` -- reference schema has no facet expression; verify `getTriggerFor()` returns `null`.
- [ ] `get_trigger_for_should_return_null_when_supplier_is_null` -- executor constructed with `null` supplier; verify `getTriggerFor()` returns `null` without error.
- [ ] `get_trigger_for_should_memoize_results_per_execution` -- call `getTriggerFor("brand", Scope.LIVE)` twice within the same executor; verify the supplier is only called once (memoization).
- [ ] `get_trigger_for_should_memoize_independently_per_reference_and_scope` -- call `getTriggerFor("brand", Scope.LIVE)` and `getTriggerFor("category", Scope.LIVE)` and `getTriggerFor("brand", Scope.ARCHIVE)`; verify supplier is called exactly 3 times (independent cache entries).

##### Category: Transactional atomicity (AC 10)

- [ ] `expression_evaluation_and_facet_update_should_be_atomic_within_transaction` -- within a single transaction, expression evaluates to `true` and facet is added; verify both operations complete as part of the same transactional boundary (no intermediate visible state where expression is evaluated but facet is not yet added).

##### Category: Mutation ordering correctness (AC 6)

- [ ] `attribute_mutation_applied_before_reference_insert_should_be_visible_to_expression` -- apply `UpsertAttributeMutation('code', 'ABC')` followed by `InsertReferenceMutation('param', 5)` in the same entity mutation batch; expression depends on `$entity.attributes['code']`; verify the expression sees `'ABC'` (the updated value from the preceding mutation via `WritableEntityStorageContainerAccessor`).
- [ ] `reference_insert_before_attribute_mutation_should_see_old_attribute_value` -- apply `InsertReferenceMutation('param', 5)` followed by `UpsertAttributeMutation('code', 'ABC')` (reversed order); expression depends on `$entity.attributes['code']`; verify the expression sees the old attribute value (before `'ABC'` is applied), demonstrating that ordering matters and the accessor reflects previously-applied-mutation state.

##### Category: Storage part access (Trap #1, AC 8)

- [ ] `expression_evaluation_should_use_container_accessor_not_query_engine` -- verify that `evaluate()` receives `WritableEntityStorageContainerAccessor` (not any query-engine component) to avoid circular fetch during mutation.
