# WBS-06: `IndexMutationTarget`, `IndexMutationExecutor`, `IndexMutationExecutorRegistry` — Target-Side Dispatch Infrastructure

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Provide the target-side dispatch infrastructure that receives `IndexMutation` instances
from the cross-entity pipeline and routes each one to the correct stateless executor.
Three collaborating abstractions form the dispatch path:

1. **`IndexMutationTarget`** — a role interface that gives executors a narrow, safe view of `EntityCollection` (index lookup, schema retrieval, trigger access, query-based filter evaluation) without exposing the full collection API.
2. **`IndexMutationExecutor<M>`** — a stateless strategy interface: one implementation per concrete `IndexMutation` type, performing the full processing pipeline (resolve affected PKs, evaluate expression, apply index changes).
3. **`IndexMutationExecutorRegistry`** — a static singleton mapping `Class<? extends IndexMutation>` to the corresponding `IndexMutationExecutor<?>`, with a single `dispatch(mutation, target)` entry point.

Together these create a thin, zero-allocation dispatch path: `EntityCollection` iterates `IndexMutation` instances and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`. No container executor is instantiated, no switch/case logic lives in the collection, and no transactional reinstantiation occurs.

## Scope

### In Scope

- `IndexMutationTarget` role interface (6 methods) in package `io.evitadb.index.mutation` (evita_engine).
- `EntityCollection implements IndexMutationTarget` — delegation of all 6 methods to existing collection internals.
- `applyIndexMutations(EntityIndexMutation)` method on `EntityCollection` — the thin dispatch loop.
- `IndexMutationExecutor<M extends IndexMutation>` strategy interface.
- `IndexMutationExecutorRegistry` class — static singleton, immutable executor map, `dispatch()` method.
- Wiring the initial executor entry: `ReevaluateFacetExpressionMutation.class` mapped to `ReevaluateFacetExpressionExecutor` instance.

### Out of Scope

- The `IndexMutation` / `EntityIndexMutation` type hierarchy (WBS-05).
- The concrete `ReevaluateFacetExpressionExecutor` implementation logic (expression evaluation, PK resolution, add/remove facet operations) — covered in a downstream WBS.
- `ExpressionIndexTrigger` and `CatalogExpressionTriggerRegistry` — covered in their own WBS tasks.
- `ReferenceIndexMutator` source-side logic (mutation production and emission).

## Dependencies

### Depends On

- **WBS-05** — provides the `IndexMutation` marker interface and `EntityIndexMutation` container type that this infrastructure dispatches over.

### Depended On By

- All concrete executor WBS tasks (e.g., `ReevaluateFacetExpressionExecutor`) — they implement `IndexMutationExecutor<M>` and are registered in `IndexMutationExecutorRegistry`.
- `EntityCollection` integration WBS — consumes the `applyIndexMutations()` entry point.
- Future mutation types (histogram expression reevaluation, etc.) — extend the registry with new entries.

## Technical Context

### `IndexMutationTarget` — role interface for collection access

Executors need access to the target collection's indexes and schema but must not see the full `EntityCollection` surface. The role interface restricts the blast radius:

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Limited view of an {@link EntityCollection} exposed to {@link IndexMutationExecutor}
 * implementations. Restricts access to index lookup, schema retrieval, expression
 * trigger access, and query-based filter evaluation — prevents executors from reaching
 * into collection internals (mutations, persistence, cache, etc.).
 *
 * {@link EntityCollection} implements this interface, so the dispatcher simply
 * passes {@code this} to the executor — zero extra allocations.
 */
public interface IndexMutationTarget {

    /**
     * Returns the entity index for the given key, creating it if absent.
     * Used by executors that need to ensure a target index exists
     * (e.g., creating a {@code ReducedEntityIndex} for a new reference).
     */
    @Nonnull
    EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey key);

    /**
     * Returns the entity index for the given key, or null if it doesn't exist.
     * Primary lookup method for executors — used to find
     * {@code ReferencedTypeEntityIndex}, {@code GlobalEntityIndex}, etc.
     */
    @Nullable
    EntityIndex getIndexIfExists(@Nonnull EntityIndexKey key);

    /**
     * Returns the entity index by its storage primary key, or null if not found.
     * Used to resolve the {@code int[]} storage PKs returned by
     * {@link ReferencedTypeEntityIndex#getAllReferenceIndexes(int)} into actual
     * {@code ReducedGroupEntityIndex} / {@code ReducedEntityIndex} instances.
     */
    @Nullable
    EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey);

    /**
     * Returns the current entity schema for this collection.
     * Used by executors to look up {@link ReferenceSchemaContract} for
     * the reference being modified.
     */
    @Nonnull
    EntitySchema getEntitySchema();

    /**
     * Returns the expression trigger for the given reference name, dependency type,
     * and scope. Used by the executor to access the pre-translated {@link FilterBy}
     * constraint for expression evaluation against indexes.
     *
     * @return the trigger, or null if no conditional expression is defined
     */
    @Nullable
    ExpressionIndexTrigger getTrigger(
        @Nonnull String referenceName,
        @Nonnull DependencyType dependencyType,
        @Nonnull Scope scope
    );

    /**
     * Evaluates a {@link FilterBy} constraint against this collection's current
     * indexes and returns the matching entity PK bitmap. Used by executors to
     * determine which entities currently satisfy the expression.
     *
     * Delegates to the collection's existing query evaluation infrastructure
     * against {@code GlobalEntityIndex}.
     */
    @Nonnull
    Bitmap evaluateFilter(@Nonnull FilterBy filterBy);
}
```

### `EntityCollection implements IndexMutationTarget` — delegation table

`EntityCollection` implements this interface directly, so the dispatcher passes `this` to the executor — zero extra allocations. Each method delegates to an existing method already present on the collection:

| Interface method | Delegates to |
|---|---|
| `getOrCreateIndex(key)` | `this.entityIndexCreator.getOrCreateIndex(key)` |
| `getIndexIfExists(key)` | `this.entityIndexCreator.getIndexIfExists(key)` |
| `getIndexByPrimaryKeyIfExists(pk)` | existing `getIndexByPrimaryKeyIfExists(pk)` (already present on EntityCollection) |
| `getEntitySchema()` | `this.getInternalSchema()` |
| `getTrigger(refName, depType, scope)` | lookup in cached trigger map built from `ReferenceSchema` at schema load time |
| `evaluateFilter(filterBy)` | thin delegation to query evaluation infrastructure against `GlobalEntityIndex` |

### `applyIndexMutations()` — thin dispatch loop

The target collection is a thin dispatcher: it receives an `EntityIndexMutation` containing concrete `IndexMutation` instances and dispatches each one to the appropriate `IndexMutationExecutor` via the static singleton registry, passing `this` (typed as `IndexMutationTarget`) as the collection context.

```java
// EntityCollection implements IndexMutationTarget

/**
 * Dispatches {@link IndexMutation} instances to their registered
 * {@link IndexMutationExecutor}. Passes {@code this} as {@link IndexMutationTarget}
 * so executors can access indexes, schema, triggers, and query evaluation
 * without seeing the full collection API surface.
 */
void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation) {
    for (IndexMutation mutation : entityIndexMutation.mutations()) {
        IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this);
    }
}
```

### `IndexMutationExecutor<M>` — stateless strategy interface

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Stateless strategy interface for executing a concrete {@link IndexMutation}.
 * Each implementation handles exactly one mutation type and performs
 * the full processing pipeline:
 *
 * 1. Resolves affected owner entity PKs from the collection's own indexes
 *    ({@code ReferencedTypeEntityIndex} -> {@code ReducedGroupEntityIndex} /
 *    {@code ReducedEntityIndex})
 * 2. Gets the pre-translated {@link FilterBy} from the trigger, parameterizes
 *    it with the mutated entity PK, and evaluates it against current indexes
 *    to determine which affected entities currently satisfy the expression
 * 3. Compares the query result with current facet state and performs the
 *    actual index modifications (add/remove facet) for affected entities
 *
 * Executor instances are stateless singletons — all collection-specific
 * state is received via the {@link IndexMutationTarget} parameter. This
 * means the {@link IndexMutationExecutorRegistry} and all its executors
 * can be a static singleton, avoiding reinstantiation when
 * {@link EntityCollection} creates transactional copies.
 *
 * Registered in {@link IndexMutationExecutorRegistry} keyed by the
 * concrete mutation class. The target {@link EntityCollection} dispatches
 * to the executor — no switch/case or orchestration logic in the collection.
 *
 * @param <M> the concrete IndexMutation subtype this executor handles
 */
public interface IndexMutationExecutor<M extends IndexMutation> {

    /**
     * Executes the mutation against the given target collection.
     * Resolves affected PKs, evaluates the expression via FilterBy query,
     * and performs index operations. The executor is stateless — all
     * collection context comes from the {@code target} parameter.
     *
     * @param mutation the concrete mutation to execute
     * @param target   limited view of the target EntityCollection
     */
    void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target);
}
```

### `IndexMutationExecutorRegistry` — static singleton

The registry is a static singleton with an immutable executor map. All executors are stateless singletons themselves. This means the registry survives `EntityCollection.createCopyWithMergedTransactionalMemory()` without reinstantiation — it is never a field on `EntityCollection`, just a static constant.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Static singleton registry mapping concrete {@link IndexMutation} types to their
 * stateless {@link IndexMutationExecutor} implementations. Both the registry and
 * its executors hold no instance state — all collection-specific context is passed
 * via {@link IndexMutationTarget} at dispatch time.
 *
 * This design avoids reinstantiation when {@link EntityCollection} creates
 * transactional copies (which happens on every committed transaction).
 * Adding a new mutation type requires only: a new mutation record, a new
 * stateless executor class, and one entry in the map below.
 */
public class IndexMutationExecutorRegistry {

    public static final IndexMutationExecutorRegistry INSTANCE =
        new IndexMutationExecutorRegistry(
            Map.of(
                ReevaluateFacetExpressionMutation.class,
                    new ReevaluateFacetExpressionExecutor()
                // future: ReevaluateHistogramExpressionMutation.class,
                //         new ReevaluateHistogramExpressionExecutor()
            )
        );

    private final Map<Class<? extends IndexMutation>,
                      IndexMutationExecutor<?>> executors;

    private IndexMutationExecutorRegistry(
        @Nonnull Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors
    ) {
        this.executors = Map.copyOf(executors);
    }

    /**
     * Looks up the executor for the given mutation type and executes it
     * against the target collection.
     */
    @SuppressWarnings("unchecked")
    public <M extends IndexMutation> void dispatch(
        @Nonnull M mutation,
        @Nonnull IndexMutationTarget target
    ) {
        final IndexMutationExecutor<M> executor =
            (IndexMutationExecutor<M>) this.executors.get(mutation.getClass());
        Assert.notNull(executor,
            "No executor registered for " + mutation.getClass().getName());
        executor.execute(mutation, target);
    }
}
```

### Handler hierarchy diagram (visual summary — see AD 12 below for detailed prose description)

```
IndexMutationTarget (role interface — implemented by EntityCollection)
  getOrCreateIndex(), getIndexIfExists(), getIndexByPrimaryKeyIfExists(), getEntitySchema()
  getTrigger(referenceName, dependencyType, scope)  <- trigger access for FilterBy retrieval
  evaluateFilter(FilterBy)                          <- query-based expression evaluation

IndexMutationExecutor<M> (stateless strategy — execute(M, IndexMutationTarget))
+-- ReevaluateFacetExpressionExecutor  handles: ReevaluateFacetExpressionMutation
    (future: ReevaluateHistogramExpressionExecutor handles: ReevaluateHistogramExpressionMutation)

ExpressionIndexTrigger (expression evaluation + FilterBy constraint)
  evaluate()                <- local triggers (inline in ReferenceIndexMutator)
  getFilterByConstraint()   <- cross-entity triggers (full expression as FilterBy template)
  getDependentAttributes(), getDependencyType(), getOwnerEntityType(), ...

IndexMutationExecutorRegistry (static singleton — INSTANCE)
  Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>>
  dispatch(mutation, target) -> lookup + execute
```

### Architectural decisions

**AD 6 — Thin dispatch path:** `EntityCollection implements IndexMutationTarget` (role interface limiting access to index lookup, schema retrieval, trigger access, and query evaluation). `applyIndexMutations()` iterates the `IndexMutation` instances nested in `EntityIndexMutation` and delegates each to `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`. Zero allocations — `this` is the target. No container executor is created.

**AD 12 — Registry-based executor dispatch — stateless static singleton:** Clear separation of concerns across four layers:

1. **`IndexMutationTarget`** — role interface implemented by `EntityCollection`. Limits executor access to index lookup (`getOrCreateIndex`, `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`), schema retrieval (`getEntitySchema`), trigger access (`getTrigger` — for `FilterBy` constraint retrieval), and query-based filter evaluation (`evaluateFilter` — for index-based expression evaluation). Executors never see the full collection API. Zero allocations — `EntityCollection` passes `this`.
2. **`ExpressionIndexTrigger`** — generic base interface for expression-driven index triggers. Subtypes: `FacetExpressionTrigger` (conditional facet indexing), `HistogramExpressionTrigger` (conditional histogram indexing, future). Two evaluation modes: `evaluate()` for local triggers (per-entity, Proxycian proxies), `getFilterByConstraint()` for cross-entity triggers (full expression as `FilterBy` template, parameterized at trigger time, evaluated against indexes).
3. **`IndexMutationExecutorRegistry`** — static singleton mapping concrete `IndexMutation` types (`ReevaluateFacetExpressionMutation`) to stateless `IndexMutationExecutor<M>` singletons (`ReevaluateFacetExpressionExecutor`). Both the registry and its executors hold no instance state — they survive `EntityCollection` transactional copy without reinstantiation. Extensible — new mutation types require only a new mutation record + executor class + registry entry.
4. **`CatalogExpressionTriggerRegistry`** — catalog-level inverted index. Maps `(mutatedEntityType, dependencyType)` to `List<ExpressionIndexTrigger>`. Inverts the ownership: expression defined in schema A, indexed under schema B. Rebuilt on schema changes (`rebuildForEntityType()` returns new instance — immutability principle).

**AD 17 — `IndexMutationExecutorRegistry` lifecycle — static singleton:** The registry is a `static final` field with an immutable executor map. Executors are stateless singletons that receive all collection context via `IndexMutationTarget` (a role interface implemented by `EntityCollection`). This avoids reinstantiation during `EntityCollection.createCopyWithMergedTransactionalMemory()` — the registry and its executors are never fields on `EntityCollection`, just a static constant accessed at dispatch time. `EntityCollection` accesses it as `IndexMutationExecutorRegistry.INSTANCE`.

### Extensibility model

Adding a new mutation type requires exactly three artifacts — no changes to dispatch infrastructure:

1. **New mutation record** — a new `record` implementing `IndexMutation` (e.g., `ReevaluateHistogramExpressionMutation`).
2. **New stateless executor class** — implementing `IndexMutationExecutor<NewMutation>`.
3. **One registry entry** — add the `NewMutation.class -> new NewExecutor()` mapping to the `Map.of(...)` in `IndexMutationExecutorRegistry.INSTANCE`.

The dispatch loop in `EntityCollection.applyIndexMutations()`, the `IndexMutationTarget` interface, and the `IndexMutationExecutorRegistry.dispatch()` method remain unchanged.

## Key Interfaces

| Interface / Class | Package | Responsibility |
|---|---|---|
| `IndexMutationTarget` | `io.evitadb.index.mutation` | Role interface: 6 methods giving executors a narrow view of `EntityCollection` (index lookup, schema, trigger access, filter evaluation) |
| `IndexMutationExecutor<M>` | `io.evitadb.index.mutation` | Stateless strategy: single `execute(M, IndexMutationTarget)` method, one impl per mutation type |
| `IndexMutationExecutorRegistry` | `io.evitadb.index.mutation` | Static singleton: maps `Class<? extends IndexMutation>` to `IndexMutationExecutor<?>`, provides `dispatch()` |
| `EntityCollection` (modified) | `io.evitadb.core.collection` | Implements `IndexMutationTarget`; adds `applyIndexMutations()` dispatch loop |

## Acceptance Criteria

1. **`IndexMutationTarget` interface** exists in `io.evitadb.index.mutation` with all 6 methods: `getOrCreateIndex`, `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`, `getEntitySchema`, `getTrigger`, `evaluateFilter`.
2. **`EntityCollection` implements `IndexMutationTarget`** — each method delegates to the corresponding existing collection internal (see delegation table). No new fields are required.
3. **`IndexMutationExecutor<M>` interface** exists with `void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target)`.
4. **`IndexMutationExecutorRegistry`** class exists as a static singleton (`static final INSTANCE`) with:
   - Private constructor accepting an immutable map.
   - `dispatch(M, IndexMutationTarget)` method performing unchecked cast lookup and delegation.
   - Initial entry mapping `ReevaluateFacetExpressionMutation.class` to a `ReevaluateFacetExpressionExecutor` instance.
5. **`applyIndexMutations(EntityIndexMutation)`** method on `EntityCollection` iterates all `IndexMutation` instances and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`.
6. **Zero allocations** in the dispatch path — `EntityCollection` passes `this` as `IndexMutationTarget`; no wrapper objects created.
7. **Static singleton survives transactional copy** — the registry is never a field on `EntityCollection`, so `createCopyWithMergedTransactionalMemory()` does not trigger reinstantiation.
8. **Compilation** — all new types compile cleanly within the `evita_engine` module with no circular dependencies.
9. **JavaDoc** — all public types and methods have comprehensive JavaDoc explaining their role in the dispatch pipeline.

## Implementation Notes

- The `dispatch()` method uses an `@SuppressWarnings("unchecked")` cast from `IndexMutationExecutor<?>` to `IndexMutationExecutor<M>`. This is type-safe because the registry enforces that each key's class matches its value's generic parameter at registration time (both are set in the same `Map.of(...)` literal).
- `Map.copyOf(executors)` in the constructor produces an unmodifiable map — the registry is immutable after construction.
- `Assert.notNull(executor, ...)` in `dispatch()` provides a fail-fast guarantee: if a mutation type is dispatched without a registered executor, the system throws immediately rather than silently dropping the mutation.
- The `evaluateFilter(FilterBy)` method on `IndexMutationTarget` delegates to the collection's existing query evaluation infrastructure against `GlobalEntityIndex`. This is the same path used by regular evitaDB queries — no new query engine is needed.
- The `getTrigger(referenceName, dependencyType, scope)` method returns `null` when no conditional expression is defined, allowing executors to short-circuit (no-op) without throwing.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

**`EntityCollection` — class declaration and interfaces:**
- Path: `evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java`
- Declaration (line 180): `public final class EntityCollection implements TransactionalLayerProducer<DataStoreChanges, EntityCollection>, EntityCollectionContract, DataStoreReader, CatalogRelatedDataStructure<EntityCollection>`
- Adding `IndexMutationTarget` to the `implements` clause is a straightforward 1-line change — no conflicts with existing interfaces

**`EntityCollection` — existing methods that `IndexMutationTarget` delegates to:**

| `IndexMutationTarget` method | Existing EntityCollection method/mechanism | Line | Signature |
|---|---|---|---|
| `getOrCreateIndex(EntityIndexKey)` | `this.entityIndexCreator.getOrCreateIndex(key)` — inner class `EntityIndexMaintainer` (line 2692) | 2699 | `@Nonnull EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey)` |
| `getIndexIfExists(EntityIndexKey)` | `this.getIndexByKeyIfExists(key)` — delegates to `this.dataStoreBuffer.getIndexIfExists(entityIndexKey, this.indexes::get)` | 1436 | `@Nullable EntityIndex getIndexByKeyIfExists(@Nonnull EntityIndexKey entityIndexKey)` |
| `getIndexByPrimaryKeyIfExists(int)` | `this.getIndexByPrimaryKeyIfExists(pk)` — already a public method | 1444 | `@Nullable EntityIndex getIndexByPrimaryKeyIfExists(int entityIndexPrimaryKey)` |
| `getEntitySchema()` | `this.getInternalSchema()` — returns `EntitySchema` (the dto variant) | 1428 | `@Nonnull EntitySchema getInternalSchema()` |
| `getTrigger(...)` | **Does not exist yet** — must be implemented when `ExpressionIndexTrigger` and `CatalogExpressionTriggerRegistry` are available (downstream WBS). Stub returns `null`. | N/A | N/A |
| `evaluateFilter(FilterBy)` | **Does not exist yet** — must be implemented using the existing query planning infrastructure | N/A | See below |

**`EntityCollection.EntityIndexMaintainer` — inner class (line 2692):**
- `private class EntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex>`
- `getOrCreateIndex(EntityIndexKey)` at line 2699 — creates `GlobalEntityIndex`, `ReferencedTypeEntityIndex`, `ReducedEntityIndex`, or `ReducedGroupEntityIndex` based on `EntityIndexType`
- `getIndexIfExists(EntityIndexKey)` at line 2767 — delegates to `EntityCollection.this.getIndexByKeyIfExists(entityIndexKey)`
- `getIndexByPrimaryKey(int)` at line 2776 — retrieves by PK, throws if not found
- `removeIndex(EntityIndexKey)` at line 2809
- The `entityIndexCreator` field (line 199) is `private final EntityIndexMaintainer entityIndexCreator = new EntityIndexMaintainer()`

**`EntityIndex` — abstract base class:**
- Path: `evita_engine/src/main/java/io/evitadb/index/EntityIndex.java`
- Package: `io.evitadb.index`
- Hierarchy: `EntityIndex` -> `GlobalEntityIndex`, `ReferencedTypeEntityIndex`, `AbstractReducedEntityIndex` -> `ReducedEntityIndex`, `ReducedGroupEntityIndex`
- Key field: `@Getter protected final int primaryKey` — the storage PK used for `getIndexByPrimaryKeyIfExists()`
- Key field: `@Getter protected final EntityIndexKey indexKey`

**`EntityIndexKey` — record:**
- Path: `evita_engine/src/main/java/io/evitadb/index/EntityIndexKey.java`
- Declaration: `public record EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope scope, @Nullable Serializable discriminator)`
- `EntityIndexType` values: `GLOBAL`, `REFERENCED_ENTITY_TYPE`, `REFERENCED_ENTITY`, `REFERENCED_HIERARCHY_NODE` (deprecated), `REFERENCED_GROUP_ENTITY_TYPE`, `REFERENCED_GROUP_ENTITY`

**`ReferencedTypeEntityIndex` — reverse lookup index:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java`
- Key method (line 281): `@Nonnull public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey)` — returns storage PKs of `ReducedEntityIndex`/`ReducedGroupEntityIndex` instances associated with a referenced entity PK
- Key method (line 389): `@Nonnull public Bitmap getIndexPrimaryKeys(@Nonnull RoaringBitmap referencedEntityPrimaryKeys)` — bulk version
- Key method (line 297): `@Nonnull public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys)` — reverse lookup
- Discriminator for `REFERENCED_ENTITY_TYPE` / `REFERENCED_GROUP_ENTITY_TYPE` keys is `String` (reference name)
- Method `getReferenceName()` (line 250): `(String) Objects.requireNonNull(getIndexKey().discriminator())`

**`ReducedEntityIndex`:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReducedEntityIndex.java`
- Extends `AbstractReducedEntityIndex`
- Handles `EntityIndexType.REFERENCED_ENTITY` — per-reference-entity indexes
- Discriminator: `RepresentativeReferenceKey` (referenceName + referenced entity PK + representative attributes)

**`ReducedGroupEntityIndex`:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReducedGroupEntityIndex.java`
- Extends `AbstractReducedEntityIndex` with cardinality tracking
- Handles `EntityIndexType.REFERENCED_GROUP_ENTITY` — per-group-entity indexes
- Discriminator: `RepresentativeReferenceKey` (referenceName + group entity PK + representative attributes)

**`GlobalEntityIndex`:**
- Path: `evita_engine/src/main/java/io/evitadb/index/GlobalEntityIndex.java`
- Extends `EntityIndex`, implements `VoidTransactionMemoryProducer<GlobalEntityIndex>`
- The main "full-scan" index containing all entity data
- Accessed via `EntityIndexKey(EntityIndexType.GLOBAL, scope)` (no discriminator, null)
- `EntityCollection.getGlobalIndex()` at line 1452 — asserts existence and casts

**`IndexMaintainer<K, T>` — existing role interface pattern:**
- Path: `evita_engine/src/main/java/io/evitadb/index/IndexMaintainer.java`
- Methods: `getOrCreateIndex(K)`, `getIndexIfExists(K)`, `getIndexByPrimaryKey(int)`, `removeIndex(K)`
- `EntityCollection.EntityIndexMaintainer` implements `IndexMaintainer<EntityIndexKey, EntityIndex>`
- This is the closest existing pattern to `IndexMutationTarget` — a role interface providing narrow access to index operations

**Query evaluation infrastructure — how to implement `evaluateFilter(FilterBy)`:**
- `QueryPlanningContext` (line 97 of `QueryPlanningContext.java`) requires: `Catalog`, `EntityCollection`, `EvitaSession`, `EvitaRequest`, indexes map, indexesByPk map, `CacheSupervisor`
- `FilterByVisitor` (line 141 of `FilterByVisitor.java`) translates `FilterConstraint` tree to `Formula` tree
- Static method `FilterByVisitor.createFormulaForTheFilter(...)` at line ~340 — creates a `FilterByVisitor`, executes a `FilterBy` constraint against specified indexes, and returns a `Formula`
- `Formula.compute()` returns `Bitmap` — the final result
- Full query flow: `EntityCollection.createQueryContext()` -> `QueryPlanner.planQuery()` -> `FilterByVisitor` -> `Formula` tree -> `Formula.compute()` -> `Bitmap`
- For `evaluateFilter(FilterBy)`, we need a simplified path: construct a minimal `EvitaRequest` with only a `FilterBy`, create a `QueryPlanningContext`, run `FilterByVisitor` against `GlobalEntityIndex`, compute the formula. This is a **non-trivial delegation** that will need careful implementation.
- Alternative approach: `FilterByVisitor` has `executeInContextAndIsolatedFormulaStack()` (line 1120) which allows executing a filter constraint against a specific set of indexes and returning a formula. This might be usable for a lightweight evaluation path.
- The `evaluateFilter()` implementation requires access to `Catalog` (for catalog schema), `EvitaSession` or a mock session, and index maps. Since `EntityCollection` holds all of these (`this.catalog`, `this.indexes`, `this.indexesByPrimaryKey`, `this.cacheSupervisor`), the delegation is feasible but involves creating temporary `QueryPlanningContext` / `FilterByVisitor` instances.

**Package placement for new types:**
- `IndexMutationTarget`: `io.evitadb.index.mutation` (evita_engine) — same package as `ConsistencyCheckingLocalMutationExecutor`
- `IndexMutationExecutor<M>`: `io.evitadb.index.mutation` (evita_engine)
- `IndexMutationExecutorRegistry`: `io.evitadb.index.mutation` (evita_engine)
- All three in `evita_engine/src/main/java/io/evitadb/index/mutation/`

**Module exports:**
- `io.evitadb.index.mutation` is NOT currently exported in `evita_engine/src/main/java/module-info.java` (verified at lines 64-81)
- `ConsistencyCheckingLocalMutationExecutor` in this package is imported by `EntityCollection` (same `evita_engine` module), so no export needed for same-module access
- `IndexMutationTarget`, `IndexMutationExecutor`, `IndexMutationExecutorRegistry` will all be used within `evita_engine` only (the dispatch loop is in `EntityCollection` which is in the same module)
- No module-info change is required unless test modules in different Java modules need to reference these types directly

**`EntityCollection.createCopyWithMergedTransactionalMemory()` (line 1665):**
- Creates a new `EntityCollection` via constructor — copies `pkSequence`, `indexPkSequence`, `pricePkSequence`, `catalogPersistenceService`, `cacheSupervisor`, `trafficRecorder`, and merged `indexes` / `schema`
- The `entityIndexCreator` field is `new EntityIndexMaintainer()` (line 199) — always fresh, never copied
- Confirmed: static singleton `IndexMutationExecutorRegistry.INSTANCE` survives this copy — it is never a field on `EntityCollection`

**`EntitySchema` vs `EntitySchemaContract`:**
- `EntityCollection.getInternalSchema()` returns `EntitySchema` (the concrete dto class at `io.evitadb.api.requestResponse.schema.dto.EntitySchema`)
- `IndexMutationTarget.getEntitySchema()` should return `EntitySchema` (not the interface `EntitySchemaContract`) to match `getInternalSchema()` return type. Note: while `EntitySchemaContract.getReference(String)` also returns `Optional<ReferenceSchemaContract>` without needing a cast, the concrete `EntitySchema` DTO provides the mutable/internal view with direct access to `ReferenceSchema` instances (the concrete DTO type, not just the contract interface). This is the real benefit — executors operating at the engine level need the concrete DTO, not the contract abstraction.

#### Detailed Task List

**Group 1: `IndexMutationTarget` role interface**

- [ ] Create `IndexMutationTarget.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public interface with 6 methods:
  - `@Nonnull EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey key)` — for executors creating new indexes
  - `@Nullable EntityIndex getIndexIfExists(@Nonnull EntityIndexKey key)` — primary index lookup
  - `@Nullable EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey)` — resolve storage PKs from `ReferencedTypeEntityIndex.getAllReferenceIndexes(int)`
  - `@Nonnull EntitySchema getEntitySchema()` — current entity schema for reference schema lookup
  - `@Nullable ExpressionIndexTrigger getTrigger(@Nonnull String referenceName, @Nonnull DependencyType dependencyType, @Nonnull Scope scope)` — trigger access for `FilterBy` retrieval (returns `null` when no conditional expression is defined)
  - `@Nonnull Bitmap evaluateFilter(@Nonnull FilterBy filterBy)` — evaluates a `FilterBy` constraint against current indexes and returns matching entity PK bitmap
- [ ] Add comprehensive JavaDoc to the interface and each method — explain the role interface pattern (limits executor access, zero allocations since `EntityCollection` passes `this`), reference the delegation table, explain why executors should not see the full `EntityCollection` API surface
- [ ] Import types: `io.evitadb.index.EntityIndex`, `io.evitadb.index.EntityIndexKey`, `io.evitadb.api.requestResponse.schema.dto.EntitySchema`, `io.evitadb.index.bitmap.Bitmap`, `io.evitadb.api.query.filter.FilterBy`, `io.evitadb.dataType.Scope`, `javax.annotation.Nonnull`, `javax.annotation.Nullable`
- [ ] **Implementation order note:** `ExpressionIndexTrigger` and `DependencyType` are defined in WBS-03. **Implement WBS-03 before WBS-06** to ensure these types are available. This eliminates the need for temporary stubs or deferred methods — the interface can include all 6 methods from the start. If WBS-03 is not yet complete, defer the `getTrigger()` method until it is, and start with 5 methods.

**Group 2: `EntityCollection implements IndexMutationTarget`**

- [ ] Modify `EntityCollection.java` class declaration (line 180) to add `IndexMutationTarget` to the `implements` clause: `public final class EntityCollection implements TransactionalLayerProducer<DataStoreChanges, EntityCollection>, EntityCollectionContract, DataStoreReader, CatalogRelatedDataStructure<EntityCollection>, IndexMutationTarget`
- [ ] Add import: `import io.evitadb.index.mutation.IndexMutationTarget;`
- [ ] Implement `getOrCreateIndex(EntityIndexKey)` — one-liner delegation: `return this.entityIndexCreator.getOrCreateIndex(key);`
- [ ] Implement `getIndexIfExists(EntityIndexKey)` — one-liner delegation: `return this.getIndexByKeyIfExists(key);` (delegates to existing method at line 1436)
- [ ] Implement `getIndexByPrimaryKeyIfExists(int)` — already exists as a public method at line 1444 with the exact signature `@Nullable public EntityIndex getIndexByPrimaryKeyIfExists(int entityIndexPrimaryKey)`. The interface method is already satisfied by this existing method — no new code needed, just ensure the method is annotated with `@Override`.
- [ ] Implement `getEntitySchema()` — one-liner delegation: `return this.getInternalSchema();`
- [ ] Implement `getTrigger(String, DependencyType, Scope)` — initial stub returning `null` until the trigger infrastructure (downstream WBS) is available. Add a `// TODO` comment referencing the trigger WBS task.
- [ ] Implement `evaluateFilter(FilterBy)` — delegate to the existing query evaluation infrastructure. **Recommended approach:** use `FilterByVisitor.createFormulaForTheFilter()` (static method at ~line 340 of `FilterByVisitor.java`), which accepts a `QueryPlanningContext`, `FilterBy`, `List<EntityIndex>`, and additional parameters, creates a `FilterByVisitor` internally, executes the filter constraint, and returns a `Formula`. Then call `formula.compute()` to obtain the `Bitmap`. The implementation steps:
  1. Construct a minimal `EvitaRequest` using `EvitaRequest.fromQuery(Query.query(collection(entityType), filterBy))` — only the `FilterBy` and entity type are needed, no `OrderBy` or `Require`
  2. Create a `QueryPlanningContext` using `EntityCollection.createQueryContext()` (line ~1480) — this method already accepts an `EvitaRequest` and builds the context from the collection's existing fields (`this.catalog`, `this.indexes`, `this.indexesByPrimaryKey`, `this.cacheSupervisor`). For the `EvitaSession` parameter, use the catalog's internal session or create a minimal read-only session via `this.catalog.createReadOnlySession()` (the session is only needed for schema access and is not used for transactional state in the filter evaluation path)
  3. Call `FilterByVisitor.createFormulaForTheFilter(queryContext, filterBy, List.of(globalEntityIndex), ...)` — pass the `GlobalEntityIndex` as the single target index
  4. Call `formula.compute()` to get the resulting `Bitmap`
  This is the most complex delegation — the key challenge is constructing the `QueryPlanningContext` with a valid session. Since `EntityCollection` already holds references to `this.catalog`, `this.cacheSupervisor`, and the index maps, all required constructor arguments are available.
- [ ] Add JavaDoc to each implemented method referencing the delegation target and explaining the role interface contract
- [ ] No new fields are required on `EntityCollection` — all delegations use existing fields (`entityIndexCreator`, `dataStoreBuffer`, `indexes`, `catalog`, `schema`)

**Group 3: `IndexMutationExecutor<M>` strategy interface**

- [ ] Create `IndexMutationExecutor.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public interface with generic type parameter `<M extends IndexMutation>` and single method: `void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target)`
- [ ] Add comprehensive JavaDoc — explain: stateless strategy, one implementation per concrete `IndexMutation` type, three-phase processing pipeline (resolve PKs, evaluate expression, apply index changes), stateless singletons receiving collection context via `IndexMutationTarget`, registered in `IndexMutationExecutorRegistry`
- [ ] Import types: `io.evitadb.index.mutation.IndexMutation` (from WBS-05), `javax.annotation.Nonnull`

**Group 4: `IndexMutationExecutorRegistry` static singleton**

- [ ] Create `IndexMutationExecutorRegistry.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — **public** class (must be accessible from `EntityCollection` in `io.evitadb.core.collection`, which is a different package within the same module) with:
  - `public static final IndexMutationExecutorRegistry INSTANCE` — initialized with `Map.of(...)` containing initial entries
  - Private constructor accepting `@Nonnull Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors` — stores `Map.copyOf(executors)` for immutability
  - `public <M extends IndexMutation> void dispatch(@Nonnull M mutation, @Nonnull IndexMutationTarget target)` method — lookup executor by `mutation.getClass()`, unchecked cast with `@SuppressWarnings("unchecked")`, `Assert.notNull(executor, ...)` for fail-fast, then `executor.execute(mutation, target)`
  - Initial map entry: `ReevaluateFacetExpressionMutation.class -> new ReevaluateFacetExpressionExecutor()`. Note: `ReevaluateFacetExpressionExecutor` is defined in a downstream WBS (WBS-07). If WBS-07 is not yet complete, leave the map entry commented out with a `// TODO: add when WBS-07 is implemented` reference — consistent with the implementation order strategy described in Group 1.
- [ ] Add comprehensive JavaDoc — explain: static singleton lifecycle, immutable executor map, survives `EntityCollection.createCopyWithMergedTransactionalMemory()`, extensibility model (new mutation type = new record + new executor + one registry entry)
- [ ] Import types: `io.evitadb.index.mutation.IndexMutation` (from WBS-05), `io.evitadb.utils.Assert`, `javax.annotation.Nonnull`, `java.util.Map`

**Group 5: `applyIndexMutations()` dispatch method on `EntityCollection`**

- [ ] Add method `void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation)` to `EntityCollection` — iterates `entityIndexMutation.mutations()` and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)` for each one
- [ ] Add import: `import io.evitadb.index.mutation.EntityIndexMutation;` (from WBS-05), `import io.evitadb.index.mutation.IndexMutationExecutorRegistry;`
- [ ] Add JavaDoc explaining: this is the thin dispatch loop, passes `this` as `IndexMutationTarget`, zero allocations, no switch/case logic
- [ ] This method is called by the dispatch infrastructure in `LocalMutationExecutorCollector` (WBS-09) — it is not called from within `EntityCollection` itself. The method must be `public` because `LocalMutationExecutorCollector` is in package `io.evitadb.index.mutation.storagePart`, which is a different package from `EntityCollection`'s `io.evitadb.core.collection`.

**Group 6: Compilation verification**

- [ ] Verify all new types compile cleanly within the `evita_engine` module — run `mvn compile -pl evita_engine` (or via IntelliJ MCP build)
- [ ] Verify no circular dependencies between `io.evitadb.index.mutation` (new types) and `io.evitadb.core.collection` (EntityCollection) — `IndexMutationTarget` is in `io.evitadb.index.mutation`, `EntityCollection` implements it from `io.evitadb.core.collection`. `IndexMutationExecutorRegistry` is in `io.evitadb.index.mutation` and is accessed statically from `EntityCollection`. This is a one-directional dependency: `core.collection` -> `index.mutation`. No circular dependency.
- [ ] Verify that `EntityCollection.createCopyWithMergedTransactionalMemory()` (line 1665) does not need changes — the registry is a static singleton, not a field, so it is never copied.

### Test Cases

Test location: `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/`

---

#### Test Class: `IndexMutationTargetTest`

**Category: Interface contract verification**

- [ ] `should_declare_expected_methods` — verify `IndexMutationTarget` interface declares at least the 6 expected public abstract methods (`getOrCreateIndex`, `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`, `getEntitySchema`, `getTrigger`, `evaluateFilter`) by name. Do NOT assert an exact method count — future legitimate additions (e.g., a 7th method) would break a hard count assertion. If `getTrigger` is deferred, adjust the expected set accordingly.
- [ ] `should_be_assignable_from_entity_collection` — verify `IndexMutationTarget.class.isAssignableFrom(EntityCollection.class)` returns `true`, confirming `EntityCollection` implements the role interface.
- [ ] `should_declare_nonnull_and_nullable_annotations_on_methods` — reflectively verify nullability annotations on the interface methods: `@Nonnull` on parameters and return types where specified, `@Nullable` on return types for `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`, and `getTrigger`. These annotation checks are valuable because the compiler does not enforce annotation semantics. Note: reflection-based return type assertions (e.g., that `getEntitySchema()` returns `EntitySchema`) duplicate what the compiler enforces and are optional.

**Category: `EntityCollection` delegation correctness**

- [ ] `getOrCreateIndex_should_delegate_to_entityIndexCreator` — call `getOrCreateIndex(key)` on an `EntityCollection` instance (cast to `IndexMutationTarget`); verify the result matches calling `entityIndexCreator.getOrCreateIndex(key)` directly. Use a valid `EntityIndexKey` (e.g., `REFERENCED_ENTITY_TYPE` with a reference name).
- [ ] `getIndexIfExists_should_delegate_to_getIndexByKeyIfExists` — insert an index into the collection, then call `getIndexIfExists(key)` via the `IndexMutationTarget` interface; verify the returned index matches the one retrieved via `getIndexByKeyIfExists(key)`.
- [ ] `getIndexIfExists_should_return_null_for_nonexistent_key` — call `getIndexIfExists(key)` with a key that has no corresponding index; verify `null` is returned.
- [ ] `getIndexByPrimaryKeyIfExists_should_return_existing_index` — create an index with a known storage PK, then call `getIndexByPrimaryKeyIfExists(pk)` via the `IndexMutationTarget` interface; verify the returned index matches.
- [ ] `getIndexByPrimaryKeyIfExists_should_return_null_for_unknown_pk` — call `getIndexByPrimaryKeyIfExists(9999)` for a PK that has no corresponding index; verify `null` is returned.
- [ ] `getEntitySchema_should_delegate_to_getInternalSchema` — call `getEntitySchema()` via `IndexMutationTarget` interface; verify the result is identical (same object reference) to calling `getInternalSchema()` directly on the `EntityCollection`.
- [ ] `getTrigger_should_return_null_when_no_trigger_infrastructure_exists` — call `getTrigger("someReference", dependencyType, Scope.LIVE)` on the initial stub implementation; verify `null` is returned (stub behavior until trigger WBS is integrated).
- [ ] `evaluateFilter_should_return_bitmap_for_valid_filter` — set up an `EntityCollection` with a `GlobalEntityIndex` containing known entity PKs, call `evaluateFilter(filterBy)` with a `FilterBy` that matches a subset; verify the returned `Bitmap` contains exactly the expected PKs.
- [ ] `evaluateFilter_should_return_empty_bitmap_when_no_entities_match` — call `evaluateFilter(filterBy)` with a `FilterBy` that matches no entities; verify the returned `Bitmap` is empty.

**Category: Zero-allocation verification**

- [ ] `entity_collection_passes_this_as_target` — verify that when `EntityCollection` dispatches mutations, the `IndexMutationTarget` reference received by the executor is the same object as the `EntityCollection` itself (identity check via `assertSame`). Confirms zero wrapper allocation.

---

#### Test Class: `IndexMutationExecutorRegistryTest`

**Category: Singleton lifecycle**

- [ ] `INSTANCE_should_not_be_null` — verify `IndexMutationExecutorRegistry.INSTANCE` is not `null`.
- [ ] `INSTANCE_should_be_same_reference_across_multiple_accesses` — access `IndexMutationExecutorRegistry.INSTANCE` multiple times and verify `assertSame` — confirms true singleton identity.
- [ ] `INSTANCE_should_survive_entity_collection_transactional_copy` — **Note:** this test requires significant infrastructure setup (catalog, persistence service, cache supervisor, etc.) to construct an `EntityCollection` and call `createCopyWithMergedTransactionalMemory()`. Consider implementing this as an integration test rather than a unit test. Alternatively, the simpler `registry_should_not_be_a_field_on_entity_collection` test below provides equivalent validation of the static singleton design with far less setup.
- [ ] `registry_should_not_be_a_field_on_entity_collection` — reflectively verify that `EntityCollection` has no instance field of type `IndexMutationExecutorRegistry`. Confirms the registry is accessed only statically. This is the primary validation of the static singleton lifecycle — if the registry is not a field, it cannot be affected by transactional copies.

**Category: Executor map immutability**

- [ ] `executor_map_should_be_unmodifiable` — reflectively access the internal `executors` map field; verify that attempting to `put()` a new entry throws `UnsupportedOperationException`. Confirms `Map.copyOf()` immutability.
- [ ] `executor_map_should_contain_ReevaluateFacetExpressionMutation_entry` — verify that dispatching a `ReevaluateFacetExpressionMutation` does not throw (i.e., a registered executor exists). Alternatively, reflectively verify the map contains the expected key.

**Category: Dispatch correctness**

- [ ] `dispatch_should_route_to_correct_executor_for_registered_mutation` — create a test `IndexMutation` subtype and a counting `IndexMutationExecutor` stub; build a test registry containing the mapping; call `dispatch(testMutation, mockTarget)`; verify the stub executor's `execute()` was called exactly once with the correct mutation and target arguments.
- [ ] `dispatch_should_pass_exact_mutation_instance_to_executor` — dispatch a specific mutation instance; verify the executor receives the same object reference (identity check), not a copy.
- [ ] `dispatch_should_pass_exact_target_instance_to_executor` — dispatch with a specific `IndexMutationTarget` mock; verify the executor receives the same target reference (identity check).
- [ ] `dispatch_should_throw_for_unregistered_mutation_type` — call `dispatch()` with a mutation whose class has no registered executor; verify an exception is thrown with a message containing the unregistered class name. Confirms the `Assert.notNull` fail-fast behavior.
- [ ] `dispatch_should_handle_multiple_registered_mutation_types` — build a registry with two different mutation type entries; dispatch each type and verify each routes to its own executor independently without cross-contamination.

**Category: Type safety**

- [ ] `dispatch_should_perform_unchecked_cast_without_ClassCastException` — dispatch a `ReevaluateFacetExpressionMutation` to its registered `ReevaluateFacetExpressionExecutor`; verify no `ClassCastException` is thrown, confirming the `@SuppressWarnings("unchecked")` cast is type-safe at runtime.

---

#### Test Class: `IndexMutationExecutorTest`

**Category: Interface contract**

- [ ] `should_declare_single_execute_method` — reflectively verify `IndexMutationExecutor` interface declares exactly one method named `execute` with parameters `(IndexMutation subtype, IndexMutationTarget)`.
- [ ] `should_be_a_functional_interface` — verify `IndexMutationExecutor` has exactly one abstract method, making it eligible as a functional interface (even if not annotated with `@FunctionalInterface`).

**Category: Stateless executor behavior**

- [ ] `test_executor_should_receive_mutation_and_target` — create a test implementation of `IndexMutationExecutor<TestMutation>`; call `execute(mutation, target)` with known arguments; verify both arguments are received correctly inside the executor.
- [ ] `test_executor_should_be_reusable_across_multiple_invocations` — invoke the same executor instance with different mutation/target pairs; verify each invocation receives the correct arguments. Confirms stateless, singleton-safe behavior.
- [ ] `test_executor_should_be_reusable_across_different_targets` — invoke the same executor instance with the same mutation type but different `IndexMutationTarget` mocks; verify each call receives the correct target. Confirms no target state is cached.

---

#### Test Class: `ApplyIndexMutationsTest`

**Category: Dispatch loop behavior**

- [ ] `should_dispatch_all_mutations_in_entity_index_mutation` — create an `EntityIndexMutation` containing 3 `IndexMutation` instances; call `applyIndexMutations(entityIndexMutation)` on `EntityCollection`; verify the registry's `dispatch()` is invoked exactly 3 times, once per mutation (requires a spy or counting executor).
- [ ] `should_dispatch_mutations_in_iteration_order` — create an `EntityIndexMutation` with mutations [A, B, C]; call `applyIndexMutations()`; verify the executor receives them in the order A, B, C. Confirms iteration-order preservation.
- [ ] `should_handle_entity_index_mutation_with_single_mutation` — create an `EntityIndexMutation` with exactly 1 `IndexMutation`; call `applyIndexMutations()`; verify dispatch is called exactly once.
- [ ] `should_handle_entity_index_mutation_with_empty_mutation_list` — create an `EntityIndexMutation` with zero `IndexMutation` instances; call `applyIndexMutations()`; verify dispatch is never called and no exception is thrown.
- [ ] `should_pass_this_as_IndexMutationTarget_to_each_dispatch` — register a capturing executor; call `applyIndexMutations()` on an `EntityCollection`; verify the `IndexMutationTarget` argument passed to each `dispatch()` call is the same `EntityCollection` instance (identity check via `assertSame`).

**Category: Error propagation**

- [ ] `should_propagate_exception_from_dispatch_on_unregistered_type` — create an `EntityIndexMutation` containing a mutation type with no registered executor; call `applyIndexMutations()`; verify the exception from `Assert.notNull` in `dispatch()` propagates up unchanged.
- [ ] `should_stop_dispatching_after_first_failure` — create an `EntityIndexMutation` with [registered, unregistered, registered] mutations; call `applyIndexMutations()`; verify the first mutation is dispatched, the second throws, and the third is never dispatched (fail-fast, no swallowing).

---

#### Test Class: `IndexMutationTargetEvaluateFilterTest`

**Category: Filter evaluation delegation**

- [ ] `should_evaluate_simple_attribute_equals_filter` — set up an `EntityCollection` with entities having indexed attributes in `GlobalEntityIndex`; call `evaluateFilter(filterBy(attributeEquals("code", "ABC")))`; verify the returned `Bitmap` contains only the PKs of entities matching the attribute value.
- [ ] `should_evaluate_conjunctive_filter` — set up entities with multiple indexed attributes; call `evaluateFilter(filterBy(and(attributeEquals("a", 1), attributeEquals("b", 2))))`; verify the bitmap contains only entities satisfying both conditions.
- [ ] `should_return_all_pks_for_filter_matching_all_entities` — set up entities and call `evaluateFilter()` with a filter that matches every entity; verify the returned bitmap size equals the total entity count.
- [ ] `should_return_empty_bitmap_for_filter_matching_no_entities` — call `evaluateFilter()` with an impossible filter condition; verify the returned bitmap is empty (size 0).
- [ ] `should_evaluate_against_GlobalEntityIndex` — verify the filter evaluation operates against the `GlobalEntityIndex` specifically, not against `ReducedEntityIndex` or `ReferencedTypeEntityIndex`. This can be validated by ensuring that entities present in `GlobalEntityIndex` but absent from reduced indexes still appear in the result.
