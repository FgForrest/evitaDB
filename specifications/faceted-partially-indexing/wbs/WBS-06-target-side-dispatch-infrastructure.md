# WBS-06: `IndexMutationTarget`, `IndexMutationExecutor`, `IndexMutationExecutorRegistry` — Target-Side Dispatch Infrastructure

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Provide the target-side dispatch infrastructure that receives `IndexMutation` instances
from the cross-entity pipeline and routes each one to the correct stateless executor.
Three collaborating abstractions form the dispatch path:

1. **`IndexMutationTarget`** — a role interface that gives executors a narrow, safe view of `EntityCollection` (index lookup, schema retrieval, trigger access, query-based filter evaluation) without exposing the full collection API.
2. **`IndexMutationExecutor<M>`** — a stateless strategy interface: one implementation per concrete `IndexMutation` type, performing the full processing pipeline (resolve affected PKs, evaluate expression, apply index changes).
3. **`IndexMutationExecutorRegistry`** — a static singleton mapping `Class<? extends IndexMutation>` to the corresponding `IndexMutationExecutor<?>`, with a single `dispatch(mutation, target)` entry point.

Together these create a thin, zero-allocation dispatch path: `EntityCollection` iterates `IndexMutation` instances and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator)`. The `entityIndexCreator` field (private inner class `EntityIndexMaintainer`) implements both `IndexMaintainer` and `IndexMutationTarget`, isolating callers from the full `EntityCollection` API surface. No container executor is instantiated, no switch/case logic lives in the collection, and no transactional reinstantiation occurs.

## Scope

### In Scope

- `IndexMutationTarget` role interface (6 methods) in package `io.evitadb.index.mutation` (evita_engine).
- `EntityIndexMaintainer` (private inner class of `EntityCollection`) implements `IndexMutationTarget` — delegation of all 6 methods to enclosing `EntityCollection` internals, providing API surface isolation.
- `applyIndexMutations(EntityIndexMutation)` method on `EntityCollection` — the thin dispatch loop passing `this.entityIndexCreator` as `IndexMutationTarget`.
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
 * Implemented by the private {@code EntityIndexMaintainer} inner class within
 * {@link EntityCollection}. This inner class also implements
 * {@code IndexMaintainer<EntityIndexKey, EntityIndex>}, providing dual-role
 * isolation: callers cannot cast the target back to {@code EntityCollection}.
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

### `EntityIndexMaintainer implements IndexMutationTarget` — delegation table

The private inner class `EntityIndexMaintainer` implements this interface. The dispatcher passes `this.entityIndexCreator` to the executor — zero extra allocations (the field already exists). Each method delegates to the enclosing `EntityCollection`'s internals:

| Interface method | Delegates to |
|---|---|
| `getOrCreateIndex(key)` | index creation logic within `EntityIndexMaintainer` (same as `IndexMaintainer` contract) |
| `getIndexIfExists(key)` | `EntityCollection.this.getIndexByKeyIfExists(key)` |
| `getIndexByPrimaryKeyIfExists(pk)` | `EntityCollection.this.getIndexByPrimaryKeyIfExists(pk)` |
| `getEntitySchema()` | `EntityCollection.this.getInternalSchema()` |
| `getTrigger(refName, depType, scope)` | lookup in cached trigger map built from `ReferenceSchema` at schema load time |
| `evaluateFilter(filterBy)` | thin delegation to query evaluation infrastructure against `GlobalEntityIndex` |

**Isolation rationale:** `EntityCollection` does NOT implement `IndexMutationTarget` directly. Passing `EntityCollection` itself would be one cast away from accessing internal methods (mutations, persistence, cache) that executors should never see. Passing the inner class instance prevents this — callers cannot cast it to `EntityCollection`.

### `applyIndexMutations()` — thin dispatch loop

The target collection is a thin dispatcher: it receives an `EntityIndexMutation` containing concrete `IndexMutation` instances and dispatches each one to the appropriate `IndexMutationExecutor` via the static singleton registry, passing `this.entityIndexCreator` (which implements `IndexMutationTarget`) as the collection context.

```java
// EntityCollection — dispatches via entityIndexCreator (implements IndexMutationTarget)

/**
 * Dispatches {@link IndexMutation} instances to their registered
 * {@link IndexMutationExecutor}. Passes {@code this.entityIndexCreator}
 * (which implements {@link IndexMutationTarget}) so executors can access
 * indexes, schema, triggers, and query evaluation without seeing the full
 * {@link EntityCollection} API surface.
 */
void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation) {
    for (IndexMutation mutation : entityIndexMutation.mutations()) {
        IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator);
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
IndexMutationTarget (role interface — implemented by EntityIndexMaintainer inner class)
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

**AD 6 — Thin dispatch path:** `EntityIndexMaintainer` (private inner class of `EntityCollection`) implements `IndexMutationTarget` (role interface limiting access to index lookup, schema retrieval, trigger access, and query evaluation). `applyIndexMutations()` iterates the `IndexMutation` instances nested in `EntityIndexMutation` and delegates each to `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator)`. Zero allocations — `entityIndexCreator` already exists as a field. No container executor is created. Callers cannot cast the target back to `EntityCollection`.

**AD 12 — Registry-based executor dispatch — stateless static singleton:** Clear separation of concerns across four layers:

1. **`IndexProvider<K, T>`** — shared super-interface declaring `getOrCreateIndex(K)`, `getIndexIfExists(K)`, and `getIndexByPrimaryKeyIfExists(int)`. Extended by both `IndexMaintainer` (which adds `removeIndex` and a default throwing `getIndexByPrimaryKey`) and `IndexMutationTarget`. Eliminates duplicate method declarations across the two interfaces.
1. **`IndexMutationTarget extends IndexProvider<EntityIndexKey, EntityIndex>`** — role interface implemented by the private `EntityIndexMaintainer` inner class (not `EntityCollection` directly). Adds executor-specific operations: schema retrieval (`getEntitySchema`), trigger access (`getTrigger` — for `FilterBy` constraint retrieval), and query-based filter evaluation (`evaluateFilter` — for index-based expression evaluation). Executors never see the full collection API. Zero allocations — `EntityCollection` passes `this.entityIndexCreator` (which already exists as a field). Callers cannot cast the target back to `EntityCollection`.
2. **`ExpressionIndexTrigger`** — generic base interface for expression-driven index triggers. Subtypes: `FacetExpressionTrigger` (conditional facet indexing), `HistogramExpressionTrigger` (conditional histogram indexing, future). Two evaluation modes: `evaluate()` for local triggers (per-entity, Proxycian proxies), `getFilterByConstraint()` for cross-entity triggers (full expression as `FilterBy` template, parameterized at trigger time, evaluated against indexes).
3. **`IndexMutationExecutorRegistry`** — static singleton mapping concrete `IndexMutation` types (`ReevaluateFacetExpressionMutation`) to stateless `IndexMutationExecutor<M>` singletons (`ReevaluateFacetExpressionExecutor`). Both the registry and its executors hold no instance state — they survive `EntityCollection` transactional copy without reinstantiation. Extensible — new mutation types require only a new mutation record + executor class + registry entry.
4. **`CatalogExpressionTriggerRegistry`** — catalog-level inverted index. Maps `(mutatedEntityType, dependencyType)` to `List<ExpressionIndexTrigger>`. Inverts the ownership: expression defined in schema A, indexed under schema B. Rebuilt on schema changes (`rebuildForEntityType()` returns new instance — immutability principle).

**AD 17 — `IndexMutationExecutorRegistry` lifecycle — static singleton:** The registry is a `static final` field with an immutable executor map. Executors are stateless singletons that receive all collection context via `IndexMutationTarget` (extends `IndexProvider` — implemented by the `EntityIndexMaintainer` inner class). This avoids reinstantiation during `EntityCollection.createCopyWithMergedTransactionalMemory()` — the registry and its executors are never fields on `EntityCollection`, just a static constant accessed at dispatch time. `EntityCollection` accesses it as `IndexMutationExecutorRegistry.INSTANCE`.

### Extensibility model

Adding a new mutation type requires exactly three artifacts — no changes to dispatch infrastructure:

1. **New mutation record** — a new `record` implementing `IndexMutation` (e.g., `ReevaluateHistogramExpressionMutation`).
2. **New stateless executor class** — implementing `IndexMutationExecutor<NewMutation>`.
3. **One registry entry** — add the `NewMutation.class -> new NewExecutor()` mapping to the `Map.of(...)` in `IndexMutationExecutorRegistry.INSTANCE`.

The dispatch loop in `EntityCollection.applyIndexMutations()`, the `IndexMutationTarget` interface, the `EntityIndexMaintainer` implementation, and the `IndexMutationExecutorRegistry.dispatch()` method remain unchanged.

## Key Interfaces

| Interface / Class | Package | Responsibility |
|---|---|---|
| `IndexProvider<K, T>` | `io.evitadb.index` | Shared super-interface: 3 index lookup methods (`getOrCreateIndex`, `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`) — extended by both `IndexMaintainer` and `IndexMutationTarget` |
| `IndexMaintainer<K, T>` | `io.evitadb.index` | Extends `IndexProvider`: adds `removeIndex` + default throwing `getIndexByPrimaryKey` |
| `IndexMutationTarget` | `io.evitadb.index.mutation` | Extends `IndexProvider<EntityIndexKey, EntityIndex>`: 3 executor-specific methods (`getEntitySchema`, `getTrigger`, `evaluateFilter`) |
| `IndexMutationExecutor<M>` | `io.evitadb.index.mutation` | Stateless strategy: single `execute(M, IndexMutationTarget)` method, one impl per mutation type |
| `IndexMutationExecutorRegistry` | `io.evitadb.index.mutation` | Static singleton: maps `Class<? extends IndexMutation>` to `IndexMutationExecutor<?>`, provides `dispatch()` |
| `EntityCollection` (modified) | `io.evitadb.core.collection` | `EntityIndexMaintainer` inner class implements both `IndexMaintainer` and `IndexMutationTarget`; adds `applyIndexMutations()` dispatch loop |

## Acceptance Criteria

1. **`IndexProvider<K, T>` interface** exists in `io.evitadb.index` with 3 methods: `getOrCreateIndex`, `getIndexIfExists`, `getIndexByPrimaryKeyIfExists`. **`IndexMutationTarget` interface** exists in `io.evitadb.index.mutation`, extends `IndexProvider<EntityIndexKey, EntityIndex>`, and declares 3 additional methods: `getEntitySchema`, `getTrigger`, `evaluateFilter`. **`IndexMaintainer<K, T>`** extends `IndexProvider<K, T>` with a default throwing `getIndexByPrimaryKey` and abstract `removeIndex`.
2. **`EntityIndexMaintainer` (private inner class of `EntityCollection`) implements `IndexMutationTarget`** — each method delegates to the enclosing `EntityCollection`'s internals (see delegation table). `EntityCollection` does NOT implement `IndexMutationTarget` directly — this ensures API surface isolation. No new fields are required.
3. **`IndexMutationExecutor<M>` interface** exists with `void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target)`.
4. **`IndexMutationExecutorRegistry`** class exists as a static singleton (`static final INSTANCE`) with:
   - Private constructor accepting an immutable map.
   - `dispatch(M, IndexMutationTarget)` method performing unchecked cast lookup and delegation.
   - Initial entry mapping `ReevaluateFacetExpressionMutation.class` to a `ReevaluateFacetExpressionExecutor` instance.
5. **`applyIndexMutations(EntityIndexMutation)`** method on `EntityCollection` iterates all `IndexMutation` instances and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator)`.
6. **Zero allocations** in the dispatch path — `EntityCollection` passes `this.entityIndexCreator` (which already exists as a field and implements `IndexMutationTarget`); no wrapper objects created.
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
- `EntityCollection` does NOT implement `IndexMutationTarget` — the inner class `EntityIndexMaintainer` implements it instead, providing API surface isolation

**`EntityCollection` — existing methods that `IndexMutationTarget` delegates to:**

| `IndexMutationTarget` method | Existing EntityCollection method/mechanism | Line | Signature |
|---|---|---|---|
| `getOrCreateIndex(EntityIndexKey)` | `this.entityIndexCreator.getOrCreateIndex(key)` — inner class `EntityIndexMaintainer` (line 2692) | 2699 | `@Nonnull EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey)` |
| `getIndexIfExists(EntityIndexKey)` | `this.getIndexByKeyIfExists(key)` — delegates to `this.dataStoreBuffer.getIndexIfExists(entityIndexKey, this.indexes::get)` | 1436 | `@Nullable EntityIndex getIndexByKeyIfExists(@Nonnull EntityIndexKey entityIndexKey)` |
| `getIndexByPrimaryKeyIfExists(int)` | `this.getIndexByPrimaryKeyIfExists(pk)` — already a public method | 1444 | `@Nullable EntityIndex getIndexByPrimaryKeyIfExists(int entityIndexPrimaryKey)` |
| `getEntitySchema()` | `this.getInternalSchema()` — returns `EntitySchema` (the dto variant) | 1428 | `@Nonnull EntitySchema getInternalSchema()` |
| `getTrigger(...)` | **Does not exist yet** — must be implemented when `ExpressionIndexTrigger` and `CatalogExpressionTriggerRegistry` are available (downstream WBS). Stub returns `null`. | N/A | N/A |
| `evaluateFilter(FilterBy)` | **Does not exist yet** — must be implemented using the existing query planning infrastructure | N/A | See below |

**`EntityCollection.EntityIndexMaintainer` — inner class:**
- `private class EntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex>, IndexMutationTarget`
- `getOrCreateIndex(EntityIndexKey)` — creates `GlobalEntityIndex`, `ReferencedTypeEntityIndex`, `ReducedEntityIndex`, or `ReducedGroupEntityIndex` based on `EntityIndexType`
- `getIndexIfExists(EntityIndexKey)` — delegates to `EntityCollection.this.getIndexByKeyIfExists(entityIndexKey)`
- `getIndexByPrimaryKeyIfExists(int)` — delegates to `EntityCollection.this.getIndexByPrimaryKeyIfExists(pk)` (satisfies both `IndexProvider` and the default `getIndexByPrimaryKey` on `IndexMaintainer`)
- `getIndexByPrimaryKey(int)` — inherited default from `IndexMaintainer`, delegates to `getIndexByPrimaryKeyIfExists` and throws if null
- `removeIndex(EntityIndexKey)` — removes from both key and PK maps
- `getEntitySchema()` — delegates to `EntityCollection.this.getInternalSchema()`
- `getTrigger(...)` — stub returning `null` (pending trigger infrastructure)
- `evaluateFilter(FilterBy)` — throws `UnsupportedOperationException` (pending session context from WBS-10)
- The `entityIndexCreator` field is `private final EntityIndexMaintainer entityIndexCreator = new EntityIndexMaintainer()`

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

**`IndexProvider<K, T>` — shared super-interface for index lookup:**
- Path: `evita_engine/src/main/java/io/evitadb/index/IndexProvider.java`
- Methods: `getOrCreateIndex(K)`, `getIndexIfExists(K)`, `getIndexByPrimaryKeyIfExists(int)` — all nullable/read-oriented
- Extended by both `IndexMaintainer<K, T>` and `IndexMutationTarget` — eliminates duplicate method declarations

**`IndexMaintainer<K, T> extends IndexProvider<K, T>` — index maintenance role interface:**
- Path: `evita_engine/src/main/java/io/evitadb/index/IndexMaintainer.java`
- Adds: `removeIndex(K)` (abstract) + `getIndexByPrimaryKey(int)` (default — delegates to `getIndexByPrimaryKeyIfExists` and throws if null)
- `EntityCollection.EntityIndexMaintainer` implements `IndexMaintainer<EntityIndexKey, EntityIndex>`

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

- [x] Create `IndexMutationTarget.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public interface with 6 methods:
  - `@Nonnull EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey key)` — for executors creating new indexes
  - `@Nullable EntityIndex getIndexIfExists(@Nonnull EntityIndexKey key)` — primary index lookup
  - `@Nullable EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey)` — resolve storage PKs from `ReferencedTypeEntityIndex.getAllReferenceIndexes(int)`
  - `@Nonnull EntitySchema getEntitySchema()` — current entity schema for reference schema lookup
  - `@Nullable ExpressionIndexTrigger getTrigger(@Nonnull String referenceName, @Nonnull DependencyType dependencyType, @Nonnull Scope scope)` — trigger access for `FilterBy` retrieval (returns `null` when no conditional expression is defined)
  - `@Nonnull Bitmap evaluateFilter(@Nonnull FilterBy filterBy)` — evaluates a `FilterBy` constraint against current indexes and returns matching entity PK bitmap
- [x] Add comprehensive JavaDoc to the interface and each method — explain the role interface pattern (limits executor access, zero allocations since `EntityCollection` passes `this.entityIndexCreator`), reference the delegation table, explain why executors should not see the full `EntityCollection` API surface
- [x] Import types: `io.evitadb.index.EntityIndex`, `io.evitadb.index.EntityIndexKey`, `io.evitadb.api.requestResponse.schema.dto.EntitySchema`, `io.evitadb.index.bitmap.Bitmap`, `io.evitadb.api.query.filter.FilterBy`, `io.evitadb.dataType.Scope`, `javax.annotation.Nonnull`, `javax.annotation.Nullable`
- [x] **Implementation order note:** `ExpressionIndexTrigger` and `DependencyType` are defined in WBS-03. **Implement WBS-03 before WBS-06** to ensure these types are available. This eliminates the need for temporary stubs or deferred methods — the interface can include all 6 methods from the start. If WBS-03 is not yet complete, defer the `getTrigger()` method until it is, and start with 5 methods.

**Group 2: `EntityIndexMaintainer implements IndexMutationTarget`**

- [x] Modify the `EntityIndexMaintainer` private inner class declaration to add `IndexMutationTarget` to its `implements` clause: `private class EntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex>, IndexMutationTarget`
- [x] `EntityCollection` does NOT implement `IndexMutationTarget` — the inner class provides API surface isolation (callers cannot cast the target back to `EntityCollection`)
- [x] `getOrCreateIndex(EntityIndexKey)` — already implemented (satisfies both `IndexMaintainer` and `IndexMutationTarget`)
- [x] `getIndexIfExists(EntityIndexKey)` — already implemented (satisfies both `IndexMaintainer` and `IndexMutationTarget`)
- [x] Implement `getIndexByPrimaryKeyIfExists(int)` — one-liner delegation: `return EntityCollection.this.getIndexByPrimaryKeyIfExists(indexPrimaryKey);`
- [x] Implement `getEntitySchema()` — one-liner delegation: `return EntityCollection.this.getInternalSchema();`
- [x] Implement `getTrigger(String, DependencyType, Scope)` — initial stub returning `null` until the trigger infrastructure (downstream WBS) is available.
- [x] Implement `evaluateFilter(FilterBy, Scope)` — delegate to the enclosing `EntityCollection`'s query evaluation infrastructure. Uses `FilterByVisitor.createFormulaForTheFilter()`, which accepts a `QueryPlanningContext`, `FilterBy`, `List<EntityIndex>`, and additional parameters, creates a `FilterByVisitor` internally, executes the filter constraint, and returns a `Formula`. Then calls `formula.compute()` to obtain the `Bitmap`. *(Signature changed from `(FilterBy)` to `(FilterBy, Scope)` — scope injection ensures `EvitaRequest.getScopes()` returns the correct scope for ARCHIVED entities. Session is threaded from `LocalMutationExecutorCollector.execute()` via `applyIndexMutations()`.)*
- [x] Add JavaDoc to the inner class and each new method explaining the dual-role pattern and delegation targets
- [x] No new fields are required on `EntityCollection` — all delegations use existing fields via `EntityCollection.this.*`

**Group 3: `IndexMutationExecutor<M>` strategy interface**

- [x] Create `IndexMutationExecutor.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public interface with generic type parameter `<M extends IndexMutation>` and single method: `void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target)`
- [x] Add comprehensive JavaDoc — explain: stateless strategy, one implementation per concrete `IndexMutation` type, three-phase processing pipeline (resolve PKs, evaluate expression, apply index changes), stateless singletons receiving collection context via `IndexMutationTarget`, registered in `IndexMutationExecutorRegistry`
- [x] Import types: `io.evitadb.index.mutation.IndexMutation` (from WBS-05), `javax.annotation.Nonnull`

**Group 4: `IndexMutationExecutorRegistry` static singleton**

- [x] Create `IndexMutationExecutorRegistry.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — **public** class (must be accessible from `EntityCollection` in `io.evitadb.core.collection`, which is a different package within the same module) with:
  - `public static final IndexMutationExecutorRegistry INSTANCE` — initialized with `Map.of(...)` containing initial entries
  - Package-private constructor accepting `@Nonnull Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors` — stores `Map.copyOf(executors)` for immutability (package-private instead of private to allow test registry construction)
  - `public <M extends IndexMutation> void dispatch(@Nonnull M mutation, @Nonnull IndexMutationTarget target)` method — lookup executor by `mutation.getClass()`, unchecked cast with `@SuppressWarnings("unchecked")`, `Assert.notNull(executor, ...)` for fail-fast, then `executor.execute(mutation, target)`
  - Initial map entry: `ReevaluateFacetExpressionMutation.class -> new ReevaluateFacetExpressionExecutor()`. Note: `ReevaluateFacetExpressionExecutor` is defined in a downstream WBS (WBS-07). If WBS-07 is not yet complete, leave the map entry commented out with a `// TODO: add when WBS-07 is implemented` reference — consistent with the implementation order strategy described in Group 1.
- [x] Add comprehensive JavaDoc — explain: static singleton lifecycle, immutable executor map, survives `EntityCollection.createCopyWithMergedTransactionalMemory()`, extensibility model (new mutation type = new record + new executor + one registry entry)
- [x] Import types: `io.evitadb.index.mutation.IndexMutation` (from WBS-05), `io.evitadb.utils.Assert`, `javax.annotation.Nonnull`, `java.util.Map`

**Group 5: `applyIndexMutations()` dispatch method on `EntityCollection`**

- [x] Add method `void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation)` to `EntityCollection` — iterates `entityIndexMutation.mutations()` and calls `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator)` for each one
- [x] Add import: `import io.evitadb.index.mutation.EntityIndexMutation;` (from WBS-05), `import io.evitadb.index.mutation.IndexMutationExecutorRegistry;`
- [x] Add JavaDoc explaining: this is the thin dispatch loop, passes `this.entityIndexCreator` as `IndexMutationTarget`, zero allocations, no switch/case logic
- [x] This method is called by the dispatch infrastructure in `LocalMutationExecutorCollector` (WBS-09) — it is not called from within `EntityCollection` itself. The method must be `public` because `LocalMutationExecutorCollector` is in package `io.evitadb.index.mutation.storagePart`, which is a different package from `EntityCollection`'s `io.evitadb.core.collection`.

**Group 6: Compilation verification**

- [x] Verify all new types compile cleanly within the `evita_engine` module — run `mvn compile -pl evita_engine` (or via IntelliJ MCP build)
- [x] Verify no circular dependencies between `io.evitadb.index.mutation` (new types) and `io.evitadb.core.collection` (EntityCollection) — `IndexMutationTarget` is in `io.evitadb.index.mutation`, `EntityCollection` implements it from `io.evitadb.core.collection`. `IndexMutationExecutorRegistry` is in `io.evitadb.index.mutation` and is accessed statically from `EntityCollection`. This is a one-directional dependency: `core.collection` -> `index.mutation`. No circular dependency.
- [x] Verify that `EntityCollection.createCopyWithMergedTransactionalMemory()` (line 1665) does not need changes — the registry is a static singleton, not a field, so it is never copied.

### Test Cases

Test location: `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/`

---

#### Test Class: `IndexMutationTargetTest`

> **Implementation note:** The originally planned `IndexMutationTargetContractTest` (reflection-based interface method/annotation tests) was created, evaluated during code quality review, and **deleted** — these tests duplicate what the compiler already enforces and provide no long-term regression protection. The subsequent `EntityCollectionIndexMutationTargetTest` was also **deleted** during a second code quality review — its tests verified type hierarchy relationships (via reflection on private fields) that the compiler already enforces, providing zero regression value.

**Category: Interface contract verification**

- [x] `should_declare_expected_methods` — **Deleted** — reflection-based method existence checks duplicate compiler enforcement; the compiler will fail if any method is missing from the interface or the implementing class.
- [x] `should_NOT_be_assignable_from_entity_collection` — **Deleted** — compiler already enforces this (if `EntityCollection` implemented `IndexMutationTarget`, it would be visible in the class declaration); reflection-based type hierarchy assertion with zero regression value.
- [x] `entityIndexCreator_should_implement_both_interfaces` — **Deleted** — compiler enforces that the field type implements both interfaces (the `applyIndexMutations` method passes `this.entityIndexCreator` where `IndexMutationTarget` is expected — any type mismatch would be a compile error). Reflection on private fields tests implementation details, not behavior.
- [x] `should_declare_nonnull_and_nullable_annotations_on_methods` — **Deleted** — reflection-based annotation checks duplicate what the source code already declares; annotations don't change at runtime and are better validated by static analysis tools or code review.

**Category: `EntityCollection` delegation correctness**

- [x] `getOrCreateIndex_should_delegate_to_entityIndexCreator` — **DELETED** — one-liner delegation; requires heavyweight `EntityCollection` setup; exercised by integration tests
- [x] `getIndexIfExists_should_delegate_to_getIndexByKeyIfExists` — **DELETED** — trivial delegation; requires full collection setup
- [x] `getIndexIfExists_should_return_null_for_nonexistent_key` — **DELETED** — tests existing `EntityCollection` method, not new code
- [x] `getIndexByPrimaryKeyIfExists_should_return_existing_index` — **DELETED** — trivial delegation; requires full collection setup
- [x] `getIndexByPrimaryKeyIfExists_should_return_null_for_unknown_pk` — **DELETED** — tests existing `EntityCollection` method
- [x] `getEntitySchema_should_delegate_to_getInternalSchema` — **DELETED** — getter delegation; tests Java method call semantics
- [x] `getTrigger_should_return_null_when_no_trigger_infrastructure_exists` — **Deleted** — tests a stub that returns `null`; will be replaced by real trigger tests when trigger infrastructure is wired (downstream WBS). Testing that `return null` returns `null` has no regression value.
- [x] `evaluateFilter_should_return_bitmap_for_valid_filter` — **DELETED** — integration test requiring full `EntityCollection` + session; covered by E2E test plan
- [x] `evaluateFilter_should_return_empty_bitmap_when_no_entities_match` — **DELETED** — integration test; covered by E2E test plan

**Category: Zero-allocation verification**

- [x] `entity_collection_passes_entityIndexCreator_as_target` — **DELETED** — obvious from source code (`this.entityIndexCreator`); requires heavyweight setup for trivial verification

---

#### Test Class: `IndexMutationExecutorRegistryTest`

**Category: Singleton lifecycle**

- [x] `INSTANCE_should_not_be_null` — **Deleted** — trivially tautological; the field is initialized at class load time with `new IndexMutationExecutorRegistry(Map.of())`. If null, every test and the entire runtime would fail.
- [x] `INSTANCE_should_be_same_reference_across_multiple_accesses` — **Deleted** during code quality review — trivially true for `static final` fields; the JVM guarantees identity for class constants.
- [x] `INSTANCE_should_survive_entity_collection_transactional_copy` — **DELETED** — `static final` singleton; survival guaranteed by JVM class loading model
- [x] `registry_should_not_be_a_field_on_entity_collection` — **Deleted** — architectural reflection assertion on an unrelated class; no runtime regression value, brittle on harmless refactorings.

**Category: Executor map immutability**

- [x] `executor_map_should_be_unmodifiable` — **Deleted** — tests `Map.copyOf()` Java stdlib behavior via reflection on a private field; not application logic.
- [x] `executor_map_should_contain_ReevaluateFacetExpressionMutation_entry` — **IMPLEMENTED** as `shouldContainReevaluateFacetExpressionMutationEntry` in `IndexMutationExecutorRegistryTest`

**Category: Dispatch correctness**

- [x] `dispatch_should_route_to_correct_executor_for_registered_mutation` — **Implemented** in `IndexMutationExecutorRegistryTest.shouldRouteToCorrectExecutorForRegisteredMutation()`.
- [x] `dispatch_should_pass_exact_mutation_instance_to_executor` — **Implemented** in `IndexMutationExecutorRegistryTest.shouldPassExactMutationInstanceToExecutor()`.
- [x] `dispatch_should_pass_exact_target_instance_to_executor` — **Implemented** in `IndexMutationExecutorRegistryTest.shouldPassExactTargetInstanceToExecutor()`.
- [x] `dispatch_should_throw_for_unregistered_mutation_type` — **Implemented** in `IndexMutationExecutorRegistryTest.shouldThrowForUnregisteredMutationType()` — verifies `EvitaInvalidUsageException` with class name in message.
- [x] `dispatch_should_handle_multiple_registered_mutation_types` — **Implemented** in `IndexMutationExecutorRegistryTest.shouldHandleMultipleRegisteredMutationTypes()` — uses `TestMutationA` and `TestMutationB`.

**Category: Type safety**

- [x] `dispatch_should_perform_unchecked_cast_without_ClassCastException` — **Deleted** — marginal value; the design guarantees type safety by construction (map key is the class, value's generic parameter matches). The dispatch correctness tests already exercise the cast path with real mutations.

---

#### Test Class: `IndexMutationExecutorTest`

> **Implementation note:** The originally planned `IndexMutationExecutorContractTest` was created, evaluated during code quality review, and **deleted** — these tests verify JVM lambda semantics and basic interface method signatures, which are guaranteed by the Java compiler and provide no regression protection. The `CountingExecutor` in `IndexMutationExecutorRegistryTest` already exercises the executor interface behavior in the context of actual dispatch.

**Category: Interface contract**

- [x] `should_declare_single_execute_method` — **Deleted** — reflective method verification duplicates compiler enforcement.
- [x] `should_be_a_functional_interface` — **Deleted** — functional interface eligibility is a JVM-level guarantee for single-abstract-method interfaces; testing it adds no value.

**Category: Stateless executor behavior**

- [x] `test_executor_should_receive_mutation_and_target` — **Covered** by `IndexMutationExecutorRegistryTest.DispatchCorrectness.shouldPassExactMutationInstanceToExecutor()` and `shouldPassExactTargetInstanceToExecutor()` — the `CountingExecutor` in the registry test verifies that mutations and targets are received correctly.
- [x] `test_executor_should_be_reusable_across_multiple_invocations` — **Covered** by `IndexMutationExecutorRegistryTest.DispatchCorrectness.shouldHandleMultipleRegisteredMutationTypes()` — the same `CountingExecutor` instance receives multiple dispatches.
- [x] `test_executor_should_be_reusable_across_different_targets` — **Covered** by the same test infrastructure — the `CountingExecutor` accumulates both mutations and targets across invocations.

---

#### Test Class: `ApplyIndexMutationsTest`

> **Implementation note:** The entire test class was **deleted** during code quality review. The Mockito partial mock of `EntityCollection` (using `CALLS_REAL_METHODS`) with a null `entityIndexCreator` field is fundamentally broken — tests only "pass" because the loop body either never executes (empty array) or throws before accessing the null target (unregistered mutation). The `applyIndexMutations()` method is a trivial 3-line for-each loop; its dispatch correctness is already verified by `IndexMutationExecutorRegistryTest`. Real end-to-end dispatch tests require WBS-07.

**Category: Dispatch loop behavior**

- [x] `should_dispatch_all_mutations_in_entity_index_mutation` — **DELETED** — tests Java for-each loop semantics; dispatch correctness tested by `IndexMutationExecutorRegistryTest`
- [x] `should_dispatch_mutations_in_iteration_order` — **DELETED** — Java array iteration order is guaranteed by JVM
- [x] `should_handle_entity_index_mutation_with_single_mutation` — **DELETED** — trivial subset of dispatch tests
- [x] `should_handle_entity_index_mutation_with_empty_mutation_list` — **Deleted** — tests Java for-each semantics (iterating empty array does nothing); the Mockito partial mock of `EntityCollection` with null `entityIndexCreator` is fragile and only "works" because the loop body never executes.
- [x] `should_pass_this_as_IndexMutationTarget_to_each_dispatch` — **DELETED** — identity obvious from source code; covered by `IndexMutationExecutorRegistryTest.shouldPassExactTargetInstanceToExecutor`

**Category: Error propagation**

- [x] `should_propagate_exception_from_dispatch_on_unregistered_type` — **Deleted** — tests a transient state (empty registry); will start *failing* when WBS-07 registers the executor. The Mockito partial mock with null `entityIndexCreator` only works because `dispatch()` throws before accessing the null target. Already covered by `IndexMutationExecutorRegistryTest.shouldThrowForUnregisteredMutationType()`.
- [x] `should_stop_dispatching_after_first_failure` — **Deleted** during code quality review — this test verifies standard Java for-loop exception semantics (loop terminates on uncaught exception), which is guaranteed by the JVM. The loop body is a single method call with no try/catch, making this behavior obvious from the code.

---

#### Test Class: `IndexMutationTargetEvaluateFilterTest`

> **Implementation note:** `evaluateFilter(FilterBy)` is currently stubbed with `UnsupportedOperationException` — it requires `EvitaSession` context which is not available through the `IndexMutationTarget` interface. The `EntityCollectionIndexMutationTargetTest` (including the `EvaluateFilterStub` nested class) was **deleted** during code quality review — testing that a stub throws `UnsupportedOperationException` has no long-term regression value. All filter evaluation tests below are deferred until WBS-10 provides session context.

**Category: Filter evaluation delegation**

- [x] `should_evaluate_simple_attribute_equals_filter` — **DELETED** — `evaluateFilter` now implemented; tests query evaluation pipeline, not dispatch infrastructure; covered by E2E suite
- [x] `should_evaluate_conjunctive_filter` — **DELETED** — tests `FilterByVisitor` conjunction handling; covered by E2E suite
- [x] `should_return_all_pks_for_filter_matching_all_entities` — **DELETED** — boundary case of query evaluation; covered by E2E suite
- [x] `should_return_empty_bitmap_for_filter_matching_no_entities` — **DELETED** — covered by E2E suite
- [x] `should_evaluate_against_GlobalEntityIndex` — **DELETED** — tests standard query resolution path; covered by E2E suite

