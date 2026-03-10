# WBS-10: `LocalMutationExecutorCollector` Integration — Two-Loop Dispatch

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Extend `LocalMutationExecutorCollector` to support the new index-trigger dispatch path alongside
the existing container-mutation dispatch path. After explicit mutations are applied and the
existing `popImplicitMutations()` loop has run (Step 5a — container implicit mutations), the
collector must call `popIndexImplicitMutations()` on the `EntityIndexLocalMutationExecutor`
(Step 5b — index trigger mutations) and route the resulting `EntityIndexMutation` envelopes to
their respective target `EntityCollection` instances via `applyIndexMutations()`. The two loops
run sequentially — container first, then index — to guarantee that storage state is fully
consistent before cross-entity triggers read it.

## Scope

### In Scope

- Adding the Step 5b dispatch loop to `LocalMutationExecutorCollector` that calls
  `entityIndexUpdater.popIndexImplicitMutations(localMutations)` and iterates the returned
  `IndexImplicitMutations.indexMutations()` array
- Routing each `EntityIndexMutation` to the correct target collection via
  `this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())`
- Calling `applyIndexMutations(EntityIndexMutation)` on each target `EntityCollection`
- Implementing `applyIndexMutations()` on `EntityCollection` as a thin dispatcher that iterates
  nested `IndexMutation` instances and dispatches each to
  `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`
- Ensuring the `EntityCollection` implements `IndexMutationTarget` so it can be passed to
  executors
- Ensuring correct ordering: Step 5a (container) completes fully before Step 5b (index) begins
- Ensuring the `level` counter in `LocalMutationExecutorCollector` correctly tracks nesting
  depth when cross-entity triggers are processed (level N for owner entity, level N+1 for
  triggered mutations on other entities)
- Integration with the existing `MutationCollector` class for collecting container mutations
  (no changes to `MutationCollector` itself, but the collector must coexist with the new
  index dispatch path)

### Out of Scope

- Implementation of `popIndexImplicitMutations()` on `EntityIndexLocalMutationExecutor` — see
  WBS-08 (this WBS consumes its output)
- Implementation of `IndexMutationExecutor` interface — see WBS-06; `ReevaluateFacetExpressionExecutor`
  concrete executor — see WBS-07
- `CatalogExpressionTriggerRegistry` trigger registration and lookup — see WBS-04
- Local trigger integration within `ReferenceIndexMutator` — see WBS-09
- Mutation type hierarchy (`MutationContract`, `IndexMutation`, `EntityIndexMutation`,
  `ReevaluateFacetExpressionMutation`, `IndexImplicitMutations`) — see WBS-05
- WAL serialization (index mutations are never written to WAL — regenerated on replay)
- Future `IndexMutation` subtypes (e.g., `ReevaluateHistogramExpressionMutation`)
- Async fan-out (synchronous dispatch is sufficient — see AD 16)

## Dependencies

### Depends On

- **WBS-05** (Mutation Type Hierarchy): provides `EntityIndexMutation`, `IndexMutation`,
  `IndexImplicitMutations`, and `ReevaluateFacetExpressionMutation` record types that this
  WBS dispatches
- **WBS-08** (EntityIndexLocalMutationExecutor): provides the
  `popIndexImplicitMutations(List<? extends LocalMutation<?, ?>>)` method that the collector
  calls in Step 5b. Without this method, there is nothing to dispatch.
- **WBS-09** (Local Trigger Integration): provides the local trigger integration in
  `ReferenceIndexMutator` that fires during Step 5a. The local trigger must complete before
  Step 5b runs cross-entity triggers, ensuring correct ordering and idempotent de-duplication.
- **WBS-06** (IndexMutationTarget + IndexMutationExecutorRegistry + IndexMutationExecutor): provides
  the target-side dispatch infrastructure — `IndexMutationTarget` role interface,
  `IndexMutationExecutorRegistry` singleton, and `IndexMutationExecutor` strategy interface —
  that `applyIndexMutations()` dispatches to. Without the registry, the thin dispatcher on
  `EntityCollection` has no executor to call.

### Depended On By

- End-to-end integration tests that verify the full mutation pipeline from explicit mutation
  through implicit container mutations through index trigger mutations
- Any future `IndexMutation` subtypes that follow the same dispatch path (register executor,
  return from `popIndexImplicitMutations()`, dispatched by this collector integration)

## Technical Context

### Existing `popImplicitMutations` Flow (6 Steps)

The existing mutation processing pipeline in `LocalMutationExecutorCollector` follows this
established pattern:

1. **Apply explicit mutations** to both entity indexes (`EntityIndexLocalMutationExecutor`) and
   storage (`ContainerizedLocalMutationExecutor`)
2. **`popImplicitMutations()`** on the container executor generates `ImplicitMutations`
   containing local mutations (same-entity) and external mutations (cross-entity)
3. **Local implicit mutations** are applied to the same entity's index and storage executors
4. **External implicit mutations** recursively call `applyMutations()` on other entity
   collections (e.g., reflected reference synchronization)
5. **Implicit mutations are NOT written to WAL** — they are regenerated deterministically on
   replay from the explicit mutations
6. **`LocalMutationExecutorCollector` handles nesting via a `level` counter** — recursive calls
   from external mutations increment the level, preventing infinite recursion and ensuring
   correct ordering

### New Step 5b — Index Trigger Dispatch

After Step 5a (container implicit mutations) completes, a new Step 5b dispatches index trigger
mutations through a completely separate path:

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
    this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())
        .applyIndexMutations(indexMutation);
}
```

### Why Container First

Container mutations (Step 5a) may create or update references that the index triggers (Step 5b)
need to see. Index triggers must operate on fully consistent storage state. Specifically:

- Entity B's storage must already reflect the NEW attribute value before a cross-entity trigger
  reads it
- `WritableEntityStorageContainerAccessor` on entity A will see B's updated data via
  `DataStoreMemoryBuffer` within the same transaction
- Expression evaluation produces correct results based on new state, not stale state

This ordering guarantee means: cross-entity triggers fire AFTER the source entity's mutations
complete (same as `popImplicitMutations` external mutations).

### Implicit Mutation Records — Two Separate Types

Each executor produces its own dedicated record — no shared type carrying unused fields:

**`ImplicitMutations`** — existing record, unchanged, only used by
`ContainerizedLocalMutationExecutor`:

```java
// in ConsistencyCheckingLocalMutationExecutor (existing — no changes)
record ImplicitMutations(
    @Nonnull LocalMutation<?, ?>[] localMutations,
    @Nonnull EntityMutation[] externalMutations
) { }
```

**`IndexImplicitMutations`** — new record, only used by `EntityIndexLocalMutationExecutor`:

```java
// in EntityIndexLocalMutationExecutor (or a new dedicated interface)
record IndexImplicitMutations(
    @Nonnull EntityIndexMutation[] indexMutations
) { }
```

`EntityIndexLocalMutationExecutor` does **not** implement
`ConsistencyCheckingLocalMutationExecutor`. It exposes its own dedicated method:

```java
@Nonnull
IndexImplicitMutations popIndexImplicitMutations(
    @Nonnull List<? extends LocalMutation<?, ?>> inputMutations
);
```

This separation keeps the two executor families independent — container executors produce
`ImplicitMutations` (entity + local mutations), index executors produce
`IndexImplicitMutations` (index mutations only).

### Processing Inside Target `EntityCollection`

The target collection is a **thin dispatcher** — it receives an `EntityIndexMutation`
containing concrete `IndexMutation` instances and dispatches each one to the appropriate
`IndexMutationExecutor` via the static singleton registry, passing `this` (typed as
`IndexMutationTarget`) as the collection context.

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

This method is intentionally thin — no schema evolution, no WAL writing, no conflict key
computation, no entity return. The executor does all the real work: resolving affected entity
PKs from local indexes, translating the expression to a parameterized `FilterBy` query,
evaluating it against current indexes, comparing with current facet state, and performing the
actual add/remove operations.

### Level-Based Nesting in `LocalMutationExecutorCollector`

`LocalMutationExecutorCollector` uses a `level` counter to handle nesting:

- **Level N**: the owner entity's explicit and implicit mutations are processed
- **Level N+1**: cross-entity triggers (from Step 5a external mutations or Step 5b index
  mutations) are processed on the target entity
- The `level` counter prevents infinite recursion and ensures correct processing order

For de-duplication ordering: `LocalMutationExecutorCollector` processes the owner entity first
(level N), then cross-entity triggers fire during post-processing (level N+1), so the local
trigger (from WBS-09) always completes before the cross-entity reevaluation runs. This means
if both a local trigger and a cross-entity trigger produce the same mutation for the same
entity, the local trigger fires first, and the cross-entity trigger's redundant operation is
a no-op at the index level.

### Mutation Ordering Guarantee

Cross-entity triggers fire AFTER the source entity's mutations complete. This means:

- Entity B's storage already reflects the NEW attribute value
- `WritableEntityStorageContainerAccessor` on entity A will see B's updated data
- Expression evaluation produces correct result based on new state

**Must be verified by integration tests:** Confirm that `DataStoreMemoryBuffer` makes B's
pending changes visible to A's accessor when both are in the same transaction.

### De-Duplication via Idempotency (AD 15)

Index operations (add/remove facet) are idempotent. If both a local trigger and a cross-entity
trigger produce the same mutation for the same entity, the second operation is a no-op at the
index level. Executors can optionally maintain a `Set<(entityPK, referenceName)>` to skip
redundant PK resolution and StoragePart fetches for performance, but this is an optimization,
not a correctness requirement.

### Synchronous Fan-Out (AD 16)

Cross-entity trigger processing via `popIndexImplicitMutations()` followed by
`applyIndexMutations()` runs synchronously. The fan-out is bounded by the number of entities
referencing the changed group/referenced entity. Each operation is a bitmap add/remove — O(1).
The expensive part (PK resolution) reads already-materialized indexes.

Async processing would add ordering guarantees, conflict detection, and transaction boundary
complexity for negligible gain. If profiling reveals a hot spot, the executor can be internally
optimized (bulk bitmap ops) without changing the pipeline architecture.

### Cross-Transaction Visibility (Hidden Trap #9)

When a trigger on entity B must see B's new data when evaluating an expression for entity A,
the collector's ordering handles this: container `popImplicitMutations` runs first (updates B's
storage in the transaction-scoped `DataStoreMemoryBuffer`), then index
`popIndexImplicitMutations` runs (generates triggers that read B's data from the same buffer).
This relies on `DataStoreMemoryBuffer` making pending changes visible within the same
transaction — a property that must be verified by integration tests.

### De-Duplication of Owner Entity (Hidden Trap #10)

When the owner entity is mutated in the same transaction as a cross-entity trigger fires
(double re-evaluation), index operations are idempotent. The executor can skip redundant
operations via a `Set<(entityPK, referenceName)>` of already-processed pairs. Since the local
trigger (level N, WBS-09) completes before the cross-entity trigger (level N+1, this WBS), the
ordering is deterministic.

### Integration with TransactionManager / ServerEntityMutation Pipeline

The `LocalMutationExecutorCollector` is invoked within the `ServerEntityMutation` processing
pipeline. External mutations produced by Step 5a are cast to `ServerEntityMutation` and routed
through `applyMutations()` on the target collection. The new Step 5b index mutations follow a
different path — `applyIndexMutations()` — that bypasses the full `ServerEntityMutation`
pipeline entirely (no storage changes, no WAL, no conflict keys, no entity return).

This means index mutations:
- Are never passed from outside the engine
- Are never written to WAL (regenerated deterministically on replay)
- Are processed entirely by the target `EntityCollection` via the thin dispatcher
- Do not create executors for container/storage — only index executors participate

### EntityIndexMutation vs EntityMutation Processing Comparison

| Aspect                    | `EntityMutation` (Step 5a)                   | `EntityIndexMutation` (Step 5b)          |
|---------------------------|----------------------------------------------|------------------------------------------|
| Creates executors         | Both container + index                       | Only index                               |
| Storage changes           | Yes                                          | No                                       |
| Schema evolution          | Yes                                          | No                                       |
| Conflict keys             | Yes                                          | No                                       |
| WAL                       | Root-level only                              | Never                                    |
| Returns updated entity    | Optional                                     | Never                                    |
| Dispatch method           | `applyMutations()`                           | `applyIndexMutations()`                  |

## Key Interfaces

| Interface / Type                           | Module          | Role in This WBS                                              |
|--------------------------------------------|-----------------|---------------------------------------------------------------|
| `LocalMutationExecutorCollector`           | `evita_engine`  | Modified: adds Step 5b dispatch loop after Step 5a            |
| `EntityCollection`                         | `evita_engine`  | Modified: adds `applyIndexMutations()` thin dispatcher        |
| `IndexMutationTarget`                      | `evita_engine`  | Implemented by `EntityCollection` — passed to executors       |
| `EntityIndexLocalMutationExecutor`         | `evita_engine`  | Called: `popIndexImplicitMutations()` consumed by Step 5b     |
| `IndexMutationExecutorRegistry`            | `evita_engine`  | Called: `dispatch(mutation, target)` from thin dispatcher     |
| `EntityIndexMutation`                      | `evita_engine`  | Transport envelope routed by Step 5b loop                     |
| `IndexImplicitMutations`                   | `evita_engine`  | Return type of `popIndexImplicitMutations()`                  |
| `IndexMutation`                            | `evita_engine`  | Iterated within `EntityIndexMutation.mutations()`             |
| `ImplicitMutations`                        | `evita_engine`  | Existing return type of `popImplicitMutations()` — unchanged  |
| `ContainerizedLocalMutationExecutor`       | `evita_engine`  | Existing: called in Step 5a — unchanged                       |
| `ConsistencyCheckingLocalMutationExecutor` | `evita_engine`  | Existing: defines `ImplicitMutations` record — unchanged      |
| `Catalog`                                  | `evita_engine`  | Used: `getCollectionForEntityOrThrowException()` for routing  |
| `MutationCollector`                        | `evita_engine`  | Existing: collects container mutations — unchanged            |

## Acceptance Criteria

1. `LocalMutationExecutorCollector` calls `entityIndexUpdater.popIndexImplicitMutations(localMutations)` after the existing `popImplicitMutations()` loop completes.
2. The returned `IndexImplicitMutations.indexMutations()` array is iterated, and each `EntityIndexMutation` is routed to the correct target `EntityCollection` via `catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())`.
3. `applyIndexMutations(EntityIndexMutation)` is called on each target collection.
4. `EntityCollection.applyIndexMutations()` iterates `entityIndexMutation.mutations()` and dispatches each `IndexMutation` to `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`.
5. `EntityCollection` implements `IndexMutationTarget` and passes `this` as the target parameter to executors.
6. Step 5a (container implicit mutations) completes fully before Step 5b (index trigger mutations) begins — verified by code structure and integration test.
7. The `level` counter correctly tracks nesting depth: owner entity at level N, cross-entity triggers at level N+1.
8. Empty `indexMutations` arrays (no triggers fired) result in no routing calls — the loop simply does not execute.
9. Multiple `EntityIndexMutation` envelopes targeting the same collection are each dispatched independently (no batching requirement at the collector level).
10. The existing `popImplicitMutations()` path (Step 5a) is completely unchanged — no modifications to `ImplicitMutations`, `MutationCollector`, or `ContainerizedLocalMutationExecutor`.
11. Cross-transaction visibility: B's pending storage changes (from Step 5a) are visible to A's expression evaluation (in Step 5b) within the same transaction via `DataStoreMemoryBuffer`.
12. De-duplication: if both a local trigger (WBS-09, during Step 5a) and a cross-entity trigger (this WBS, Step 5b) produce the same facet add/remove for the same entity, the second operation is a no-op due to index operation idempotency.
13. All new and modified methods have comprehensive JavaDoc.
14. Integration test confirms the full pipeline: explicit mutation -> Step 5a container implicit -> Step 5b index implicit -> target collection dispatch -> executor invocation.

## Implementation Notes

- **Minimal change to existing code:** The primary change to `LocalMutationExecutorCollector` is adding the Step 5b loop after the existing Step 5a loop. The existing container mutation processing remains completely untouched.
- **`applyIndexMutations()` is package-private:** Unlike `applyMutations()` which is part of the public API contract, `applyIndexMutations()` is an engine-internal dispatch method. It should have the narrowest possible visibility — package-private within `io.evitadb.core.collection`, consistent with the existing package-private overload of `applyMutations()`. Since `EntityCollection` is `final`, protected visibility is not applicable.
- **Thread safety:** `applyIndexMutations()` does not need synchronization. It runs within the same transactional scope as `applyMutations()`, which is single-threaded per transaction. The method is only called from `LocalMutationExecutorCollector.execute()`, which is itself single-threaded within a transaction's mutation processing.
- **No WAL integration:** Index mutations are never written to WAL. They are regenerated deterministically on replay when the explicit mutations are replayed through the same pipeline. This is the same pattern as existing implicit mutations.
- **Error handling:** If `getCollectionForEntityOrThrowException()` throws (target collection does not exist), this is a programming error in the trigger registry — the trigger references an entity type that does not exist. The exception should propagate and fail the transaction.
- **Partial Step 5b rollback:** If the Step 5b loop partially executes (e.g., the first `EntityIndexMutation` envelope dispatches successfully but the second throws), the index modifications made by the first envelope's executor (bitmap add/remove on the target collection's transactional indexes) are reverted by the STM (Software Transactional Memory) rollback at the transaction level — not by any Step 5b-specific undo logic. Implementers do NOT need to track or manually undo partial Step 5b side effects. The transactional layer guarantees all-or-nothing semantics for the entire mutation batch.
- **Null safety:** `popIndexImplicitMutations()` must never return null — it returns an `IndexImplicitMutations` with an empty array if no triggers fired. The collector should not need a null check.
- **Performance consideration:** The Step 5b loop is expected to be lightweight in the common case (no triggers registered, empty array). The `popIndexImplicitMutations()` method should return an empty singleton when no triggers match, avoiding allocation.
- **Registry pattern in `applyIndexMutations()`:** The thin dispatcher on `EntityCollection` uses `IndexMutationExecutorRegistry.INSTANCE` (a static singleton). This avoids reinstantiation when `EntityCollection` creates transactional copies. New mutation types can be added without modifying existing code — just register a new mutation record + executor class.
- **Module placement:** `LocalMutationExecutorCollector` is in `io.evitadb.core.collection` (`evita_engine`). `EntityCollection` is in the same package. `applyIndexMutations()` can be package-private. The `IndexMutationTarget` interface and `IndexMutationExecutorRegistry` are in `io.evitadb.index.mutation` (`evita_engine`).
- **`ServerEntityMutation` cast is NOT needed for Step 5b:** In Step 5a, external mutations are cast to `ServerEntityMutation` before calling `applyMutations()`. In Step 5b, `EntityIndexMutation` is a plain record — no casting or wrapping is needed. The routing is simpler: get collection, call `applyIndexMutations()`.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### `LocalMutationExecutorCollector` — full structure and `execute()` flow

- **Path:** `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java`
- **Declaration:** `class LocalMutationExecutorCollector` (line 82) — package-private, annotated `@RequiredArgsConstructor`
- **Fields:**
  - `private final Catalog catalog` (line 90) — provides `getCollectionForEntityOrThrowException(String)` for routing
  - `private final EntityCollectionPersistenceService persistenceService` (line 94)
  - `private final DataStoreReader dataStoreReader` (line 98)
  - `private final List<LocalMutationExecutor> executors` (line 102) — accumulates executors for commit/rollback
  - `private final List<EntityMutation> entityMutations` (line 106) — root-level mutations for WAL
  - `private EntityWithFetchCount fullEntityBody` (line 111) — cached full entity for removals
  - `private int level` (line 115) — nesting depth counter, starts at 0
  - `private RuntimeException exception` (line 119) — stores first exception for deferred throw

- **`execute()` method** (lines 178-348) — full lifecycle orchestration:
  1. **Register executors** (lines 191-192): `this.executors.add(entityIndexUpdater)` and `this.executors.add(changeCollector)`
  2. **WAL decision** (lines 199-217): Level 0 → `addToWAL=true`, `changeCollector.setTrapChanges(false)`, record traffic; Level > 0 → `addToWAL=false`, `changeCollector.setTrapChanges(true)`, no traffic recording
  3. **Increment level** (line 222): `this.level++`
  4. **Compute local mutations** (lines 224-245): For `EntityRemoveMutation` → fetch full body, compute removal mutations, optionally wrap with conflict keys; For `EntityUpsertMutation` → get local mutations, call `entityIndexUpdater.prepare(localMutations)`
  5. **Apply mutations loop** (lines 250-253): `entityIndexUpdater.applyMutation(localMutation)` then `changeCollector.applyMutation(localMutation)` per mutation
  6. **Finish local phase** (line 255): `changeCollector.finishLocalMutationExecutionPhase()`
  7. **Step 5a — container implicit mutations** (lines 257-281): Guarded by `if (!generateImplicitMutations.isEmpty())`:
     - `changeCollector.popImplicitMutations(localMutations, generateImplicitMutations)` → `ImplicitMutations`
     - Apply local implicit mutations to both executors (lines 262-265)
     - Dispatch external implicit mutations to other collections (lines 267-280) — casts to `ServerEntityMutation`, routes via `this.catalog.getCollectionForEntityOrThrowException()`, calls `applyMutations()` on target collection, passing `this` (same collector) as the `localMutationExecutorCollector` parameter
  8. **Consistency check** (lines 282-284): `if (checkConsistency) changeCollector.verifyConsistency()`
  9. **Exception handling** (lines 291-301): Catches `RuntimeException`, stores in `this.exception`
  10. **Level decrement** (line 304): `--this.level` in `finally` block; calls `finish()` only at level 0

##### Exact insertion point for Step 5b

**After line 280, before line 282.** The new Step 5b dispatch loop goes after the closing brace of the `if (!generateImplicitMutations.isEmpty())` block (line 281) and before `if (checkConsistency)` (line 282).

**Critical design question: should Step 5b be inside the `if (!generateImplicitMutations.isEmpty())` guard?**

No. The `generateImplicitMutations` flag controls CONTAINER implicit mutations (reflected references, default attributes). Index trigger mutations are a separate concern driven by the `CatalogExpressionTriggerRegistry`, not by the `ImplicitMutationBehavior` enum. Step 5b should run unconditionally (outside the guard) because:
1. Index triggers fire based on attribute value changes, which happen during the explicit mutation phase (step 5), regardless of whether container implicit mutations are enabled.
2. When replaying from WAL with `generateImplicitMutations = empty` (e.g., `ServerEntityRemoveMutation` from `TransactionManager`), index triggers still need to fire because they regenerate index state.
3. The `popIndexImplicitMutations()` method already handles the "no triggers" case by returning an empty `IndexImplicitMutations` — so calling it unconditionally is cheap.

However, there is a subtlety: `ServerEntityRemoveMutation.getImplicitMutationsBehavior()` returns `EnumSet.of(GENERATE_REFLECTED_REFERENCES)` which is NOT empty, so Step 5a always runs for removal mutations that come through `ServerEntityMutation`. The only case where `generateImplicitMutations` is empty is the plain `EntityRemoveMutation` path (line 1896-1905 in `EntityCollection`), which passes `EnumSet.noneOf(ImplicitMutationBehavior.class)`. In that path, index triggers should still fire.

**Conclusion:** Step 5b runs unconditionally after Step 5a's guard block.

##### How `EntityCollection` creates the collector — entry points

`LocalMutationExecutorCollector` is created at 6 call sites in `EntityCollection`, all following the same pattern:

```java
new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader)
```

Call sites:
- `applyMutationInternal()` line 1892 — `ServerEntityRemoveMutation` path
- `applyMutationInternal()` line 1903 — plain `EntityRemoveMutation` path
- `deleteEntityInternal()` line 1931
- `changeEntityScopeInternal()` line 1966
- `upsertEntityInternal()` line 2364 — `ServerEntityUpsertMutation` path
- `upsertEntityInternal()` line 2376 — plain `EntityUpsertMutation` path

At all sites, `this.catalog` is the `Catalog` instance on the `EntityCollection`. The collector stores this as `private final Catalog catalog` (line 90).

For recursive calls (external mutations from Step 5a), the SAME collector instance is passed as the `localMutationExecutorCollector` parameter to `EntityCollection.applyMutations()` (line 277 in the collector). This ensures the `level` counter tracks nesting depth across collections.

##### How the collector routes to target collections

The collector uses `this.catalog.getCollectionForEntityOrThrowException(entityType)` (line 269) which returns `EntityCollection`. This method (defined in `Catalog.java` line 973-978) simply looks up the entity type in `this.entityCollections` map. It returns `EntityCollection` directly — no casting needed.

For Step 5b, the same routing pattern applies: `this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())` returns the target `EntityCollection`, on which `applyIndexMutations()` is called.

##### Nesting behavior — Step 5b runs AFTER all Step 5a recursion at current level

The critical ordering guarantee: when Step 5a's external mutations (line 267-280) recursively call `applyMutations()` on other collections, those recursive calls:
1. Increment `level` to N+1
2. Execute their own Step 5 (apply mutations)
3. Execute their own Step 5a (container implicit mutations, possibly with further recursion to N+2)
4. Execute their own Step 5b (index trigger mutations) — if added
5. Decrement `level` back to N

Only after ALL external mutations from Step 5a complete (line 280) does control return to the current level, where Step 5b runs at level N. This means:
- Step 5b at level N runs AFTER all Step 5a external mutations (including their own Step 5a and Step 5b at level N+1) have fully completed
- Storage state is fully consistent when Step 5b evaluates triggers
- Any index triggers generated at level N+1 (during nested external mutations) are dispatched within that nested scope — they do NOT bubble up to level N

**Important: Step 5b dispatch routes `EntityIndexMutation` to target collections via `applyIndexMutations()`, which does NOT call `applyMutations()` and does NOT participate in the `level` counter.** The `applyIndexMutations()` method is a thin dispatcher — it iterates `IndexMutation` instances and dispatches each to `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`. No nesting, no storage changes, no WAL, no implicit mutation generation.

If in the future `applyIndexMutations()` were to produce further cross-entity triggers (cascade), they would need their own nesting mechanism. For the current design, this is not needed — `ReevaluateFacetExpressionExecutor` only reads indexes and performs bitmap add/remove, it does not generate further mutations.

##### `ServerEntityMutation` pipeline — how it drives the collector

The `ServerEntityMutation` interface (line 40-73 of `ServerEntityMutation.java`) carries:
- `shouldApplyUndoOnError()` — false for WAL replay
- `shouldVerifyConsistency()` — false for WAL replay
- `getImplicitMutationsBehavior()` — controls which implicit mutations Step 5a generates

Two implementations:
- `ServerEntityUpsertMutation` (extends `EntityUpsertMutation`) — carries configurable `implicitMutations` enum set
- `ServerEntityRemoveMutation` (extends `EntityRemoveMutation`) — always returns `EnumSet.of(GENERATE_REFLECTED_REFERENCES)`

Both override `verifyOrEvolveSchema()` to return `Optional.empty()` (schema already verified during initial transaction application).

In the `TransactionManager.processAndApplyTransaction()` (lines 1320-1337), WAL replay wraps mutations in `ServerEntityUpsertMutation(mutation, EnumSet.allOf(ImplicitMutationBehavior.class), false, false)` or `ServerEntityRemoveMutation(mutation, false, false)`. These are passed to `Catalog.applyMutation()` → `EntityCollection.applyMutation()` → `applyMutationInternal()` → `applyMutations()`.

**Step 5b impact on WAL replay:** During WAL replay, `undoOnError=false` and `checkConsistency=false`. Step 5b should still run because index triggers are regenerated deterministically from the explicit mutations. The same attribute changes that triggered index mutations during the original transaction will trigger them again during replay, ensuring index consistency.

##### `TransactionManager` — no direct interaction with collector

`TransactionManager` does not reference `LocalMutationExecutorCollector` at all. It interacts with mutations at the `Catalog.applyMutation()` level, which delegates to `EntityCollection.applyMutation()`, which creates its own collector. The transaction boundaries are managed by `Transaction.getTransaction()` and the STM (Software Transactional Memory) layer.

The commit path in `LocalMutationExecutorCollector.commit()` (lines 396-413) iterates executors, calls `executor.commit()`, then registers mutations to WAL via `Transaction.getTransaction().ifPresent(it -> it.registerMutation(mutation))`. This happens at level 0 only. Step 5b does not affect this — index mutations are never registered to WAL.

##### `DataStoreMemoryBuffer` — cross-transaction visibility

`TransactionalDataStoreMemoryBuffer` (line 57) reads from the transactional memory layer first, then falls back to persistent storage. When entity B's storage parts are updated during Step 5a's external mutations (which trap changes at level > 0), those changes are visible within the same transaction to entity A's subsequent reads. This is because both entities share the same transactional memory layer within the transaction.

However, Step 5b uses `applyIndexMutations()` on the TARGET collection, which creates NO new `ContainerizedLocalMutationExecutor` — it only dispatches to `IndexMutationExecutorRegistry`. The executor on the target side reads indexes directly (not storage parts), so cross-transaction visibility for storage parts is NOT relevant for Step 5b dispatch. What matters is that indexes are consistent — and they are, because Step 5a's local implicit mutations (which update indexes) complete before Step 5b runs.

##### `EntityCollection` class structure — visibility for new methods

- `EntityCollection` is `public final class` in package `io.evitadb.core.collection` (line 180-184)
- `LocalMutationExecutorCollector` is in the same package (`io.evitadb.core.collection`)
- `applyMutations()` (the package-private overload at line 2442) is already package-private
- `applyIndexMutations()` can be package-private (same visibility as `applyMutations()`)
- `EntityCollection` currently implements `TransactionalLayerProducer`, `EntityCollectionContract`, `DataStoreReader`, `CatalogRelatedDataStructure`
- Adding `implements IndexMutationTarget` requires the interface to be accessible from `evita_engine` module — both `EntityCollection` and the proposed `IndexMutationTarget` are in `evita_engine`, so module visibility is fine
- `IndexMutationTarget` will be in `io.evitadb.index.mutation` package (per WBS-06) — `EntityCollection` already imports from this package

##### `IndexMutationTarget` and `IndexMutationExecutorRegistry` — not yet created

Neither `IndexMutationTarget` nor `IndexMutationExecutorRegistry` exist in the Java codebase. They exist only in WBS documentation. They will be created by WBS-06 (target-side dispatch infrastructure). This WBS (WBS-10) depends on WBS-06 providing these types before `applyIndexMutations()` can dispatch to executors.

**Optional implementation note — stub strategy:** If WBS-06 is not yet implemented when WBS-10 is being coded, `applyIndexMutations()` can be implemented as a thin method that iterates `entityIndexMutation.mutations()` but does nothing with each mutation (no dispatch call). Once WBS-06 delivers the registry and executor, the dispatch call is added. Alternatively, the method can log a warning or throw an `UnsupportedOperationException` as a temporary placeholder. If WBS tasks are implemented in dependency order, this paragraph is not needed.

##### `EntityIndexLocalMutationExecutor.popIndexImplicitMutations()` — consumed by Step 5b

Per WBS-08 research, this method will be added to `EntityIndexLocalMutationExecutor` (line 114 of `EntityIndexLocalMutationExecutor.java`). It returns `IndexImplicitMutations` (a record containing `EntityIndexMutation[]`). The method:
1. Detects which attributes changed during the mutation batch
2. Compares old vs. new values (old-value caching happens during `applyMutation()`)
3. Consults `CatalogExpressionTriggerRegistry` for matching triggers
4. Creates `ReevaluateFacetExpressionMutation` instances grouped by target entity type

The `IndexImplicitMutations` record and `EMPTY_INDEX_IMPLICIT_MUTATIONS` singleton are defined per WBS-05/WBS-08. When no triggers match, the empty singleton is returned, making the Step 5b loop a no-op.

##### `localMutations` variable availability for Step 5b

The `localMutations` variable (declared at line 224 as `List<? extends LocalMutation<?, ?>>`) is in scope at the Step 5b insertion point. It was computed either from `computeLocalMutationsForEntityRemoval()` (line 227 for removals) or from `entityMutation.getLocalMutations()` (line 239 for upserts). This list is the same one passed to `changeCollector.popImplicitMutations(localMutations, ...)` in Step 5a. Step 5b uses it as `entityIndexUpdater.popIndexImplicitMutations(localMutations)`.

##### `entityIndexUpdater` variable availability for Step 5b

The `entityIndexUpdater` parameter (type `EntityIndexLocalMutationExecutor`) is available throughout the `execute()` method scope — it's a method parameter declared at line 186. No additional lookup is needed.

---

#### Detailed Task List

##### Group 1: Step 5b dispatch loop in `LocalMutationExecutorCollector`

- [ ] **1.1** Add the Step 5b dispatch loop in `LocalMutationExecutorCollector.execute()`, after line 281 (closing brace of the `if (!generateImplicitMutations.isEmpty())` block) and before line 282 (`if (checkConsistency)`). The loop calls `entityIndexUpdater.popIndexImplicitMutations(localMutations)` and iterates the returned `EntityIndexMutation[]`, routing each to the target collection via `this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType()).applyIndexMutations(indexMutation)`. The loop is NOT guarded by `if (!generateImplicitMutations.isEmpty())` — it runs unconditionally. Add a line comment explaining why Step 5b runs after Step 5a: storage must be fully consistent before cross-entity triggers read it.

- [ ] **1.2** Add necessary imports to `LocalMutationExecutorCollector`:
  - `io.evitadb.index.mutation.EntityIndexMutation` (WBS-05 delivers this)
  - `io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor.IndexImplicitMutations` (WBS-05/WBS-08 delivers this) — or the standalone type if `IndexImplicitMutations` is placed outside the executor class

- [ ] **1.3** Add comprehensive JavaDoc comment block above the Step 5b loop explaining:
  - This is the index trigger dispatch (Step 5b), separate from container implicit mutations (Step 5a)
  - The ordering guarantee: container mutations complete first, ensuring storage consistency
  - `EntityIndexMutation` is a routing envelope; individual `IndexMutation` instances are dispatched by the target collection
  - Index mutations are never written to WAL — they are regenerated deterministically on replay
  - The dispatch is synchronous and bounded by the number of affected entities (per AD 16)

##### Group 2: `applyIndexMutations()` on `EntityCollection`

- [ ] **2.1** Add a package-private method `void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation)` to `EntityCollection`. This is a thin dispatcher that iterates `entityIndexMutation.mutations()` and dispatches each `IndexMutation` to `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`. If WBS-06 is not yet implemented, implement as a stub that iterates mutations but does not dispatch (add a `// TODO: dispatch via IndexMutationExecutorRegistry when WBS-06 is complete` comment).

- [ ] **2.2** Add comprehensive JavaDoc to `applyIndexMutations()` explaining:
  - This method dispatches engine-internal `IndexMutation` instances to their registered `IndexMutationExecutor`
  - It passes `this` typed as `IndexMutationTarget` so executors can access indexes, schema, triggers, and query evaluation without seeing the full collection API surface
  - Unlike `applyMutations()`, this method creates no executors, no storage changes, no WAL entries, no schema evolution, no conflict keys, no entity return
  - The method is intentionally thin — all real work happens in the executor
  - Called by `LocalMutationExecutorCollector` during Step 5b dispatch

- [ ] **2.3** Add `import io.evitadb.index.mutation.EntityIndexMutation` and `import io.evitadb.index.mutation.IndexMutation` to `EntityCollection`.

##### Group 3: `EntityCollection` implements `IndexMutationTarget`

- [ ] **3.1** Once WBS-06 delivers the `IndexMutationTarget` interface, add `implements IndexMutationTarget` to the `EntityCollection` class declaration (line 180-184). If WBS-06 is not yet implemented, defer this task — the `applyIndexMutations()` method can exist without the interface marker, and the interface implementation can be added later.

- [ ] **3.2** Implement any methods required by `IndexMutationTarget` on `EntityCollection`. Per WBS-06 design, `IndexMutationTarget` exposes only what executors need: access to entity indexes, entity schema, and trigger registry. The exact methods depend on WBS-06's final interface design. Likely methods include:
  - `@Nullable EntityIndex getIndexIfExists(EntityIndexKey)` — wraps existing `this.indexes.get(key)` (returns null if not found)
  - `@Nonnull EntityIndex getOrCreateIndex(EntityIndexKey)` — wraps existing index lookup with creation semantics
  - `EntitySchema getInternalSchema()` — already exists (private, may need visibility change or delegation)
  - Access to `CatalogExpressionTriggerRegistry` — via `this.catalog.getExpressionTriggerRegistry()`

##### Group 4: Nesting and level behavior verification

- [ ] **4.1** Verify that Step 5b does NOT need to modify the `level` counter. The `level` counter is incremented at the start of `execute()` (line 222) and decremented in `finally` (line 304). Step 5b runs within the same `execute()` call — it does not create a new nesting level. The `applyIndexMutations()` method on the target collection does NOT call `execute()` and does NOT increment `level`. Therefore, no changes to `level` handling are needed.

- [ ] **4.2** Verify that Step 5b at level N+1 (during recursive external mutation processing) runs correctly. When an external mutation from Step 5a at level N causes a recursive `applyMutations()` call on another collection, that call runs at level N+1, has its own Step 5a and Step 5b. The level N Step 5b runs only after ALL external mutations (and their nested Step 5a/5b) at level N+1 complete. This is guaranteed by the sequential loop structure — the external mutation for-loop (lines 267-280) completes before Step 5b begins.

- [ ] **4.3** Document that if `applyIndexMutations()` on a target collection were to produce further cross-entity triggers in the future (cascade), a new nesting mechanism would be needed. For the current design, `ReevaluateFacetExpressionExecutor` only reads indexes and performs bitmap add/remove — it does not generate further mutations. Add a code comment at the Step 5b insertion point noting this assumption.

##### Group 5: `EntityIndexMutation` routing correctness

- [ ] **5.1** Verify that `this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())` uses the correct method. The `entityType()` accessor on `EntityIndexMutation` (a record) returns the `String entityType` field. `Catalog.getCollectionForEntityOrThrowException(String)` (line 973-978 of `Catalog.java`) looks up the entity type in `this.entityCollections` map and throws `CollectionNotFoundException` if missing.

- [ ] **5.2** Verify error handling: if `getCollectionForEntityOrThrowException()` throws because the target collection does not exist, the exception propagates into the `catch (RuntimeException ex)` block (line 291) which stores it in `this.exception`. This fails the entire mutation batch, which is the correct behavior — a trigger referencing a non-existent entity type is a programming error in the trigger registry configuration.

- [ ] **5.3** Verify that multiple `EntityIndexMutation` envelopes targeting the same collection are each dispatched independently. The Step 5b loop iterates `indexImplicit.indexMutations()` sequentially — each call to `applyIndexMutations()` is independent. No batching or deduplication at the collector level.

##### Group 6: WAL and `entityMutations` list — no changes needed

- [ ] **6.1** Verify that Step 5b does NOT add any mutations to `this.entityMutations`. The `entityMutations` list (line 106) is populated only at line 247 (`if (addToWAL) this.entityMutations.add(entityMutation)`) for root-level entity mutations. Index mutations are never written to WAL — they are regenerated on replay. No code change needed.

- [ ] **6.2** Verify that the `commit()` method (lines 396-413) is unaffected by Step 5b. The commit method calls `executor.commit()` for each executor in `this.executors` and registers entity mutations to WAL. Index mutations dispatched in Step 5b do not add executors to the list (they use `applyIndexMutations()` which has no executor lifecycle), and do not add mutations to `entityMutations`. Therefore, `commit()` is unchanged.

- [ ] **6.3** Verify that the `rollback()` method (lines 370-383) is unaffected by Step 5b. The rollback method calls `executor.rollback()` for each executor. Since Step 5b does not add executors, rollback is unchanged. If an exception occurs during Step 5b dispatch, it is caught by the existing `catch` block (line 291) and the rollback at level 0 will clean up all registered executors.

##### Group 7: `trapChanges` and storage interaction

- [ ] **7.1** Verify that Step 5b does not interact with `trapChanges`. The `setTrapChanges()` method is called on `changeCollector` (the `ContainerizedLocalMutationExecutor`), not on `entityIndexUpdater`. Step 5b calls `entityIndexUpdater.popIndexImplicitMutations()` which only reads internal state — it does not modify storage parts. The `applyIndexMutations()` method on the target collection modifies only indexes (bitmap add/remove), not storage parts. Therefore, `trapChanges` is irrelevant for Step 5b.

##### Group 8: Traffic recording interaction

- [ ] **8.1** Verify that Step 5b does not need traffic recording. The `MutationApplicationRecord` (line 197) records only root-level entity mutations (created at level 0, line 204-210). Index mutations are internal engine operations and should not be recorded in traffic. The `record.finish()` call (line 288) happens after Step 5b, so if Step 5b throws, the record is finished with an exception (line 300). No changes needed.

##### Group 9: Error handling edge cases

- [ ] **9.1** Verify behavior when `applyIndexMutations()` throws on the target collection. The exception should propagate into the `catch (RuntimeException ex)` block (line 291) of `execute()`, be stored in `this.exception`, and eventually cause rollback at level 0. No special handling needed — the existing exception mechanism covers this.

- [ ] **9.2** Verify behavior when `popIndexImplicitMutations()` throws. Same as 9.1 — the exception propagates to the existing catch block. The method should not throw under normal circumstances (it returns an empty result when no triggers match), but a bug in the trigger registry or attribute value caching could cause an unexpected exception.

- [ ] **9.3** Verify that if Step 5a's external mutations throw (causing nested `execute()` to fail), Step 5b at the current level is skipped because the exception short-circuits the try block. The existing exception handling in the `catch` block ensures this — once an exception is stored, the code does not continue to Step 5b. However, this needs verification: the `for` loop over external mutations (lines 267-280) does NOT have its own try-catch, so an exception in any external mutation will immediately jump to the outer catch block, skipping Step 5b entirely. This is correct behavior — if external mutations fail, index triggers should not fire on inconsistent state.

##### Group 10: JavaDoc and documentation

- [ ] **10.1** Add comprehensive JavaDoc to all new and modified methods:
  - The Step 5b dispatch code block in `execute()`
  - `EntityCollection.applyIndexMutations()`
  - Any helper methods if created

- [ ] **10.2** Update the class-level JavaDoc of `LocalMutationExecutorCollector` (lines 72-80) to mention the two-loop dispatch: Step 5a (container implicit mutations) and Step 5b (index trigger mutations).

- [ ] **10.3** Add line comments at key points:
  - Before the Step 5b loop: explain ordering guarantee (container first, then index)
  - At the `applyIndexMutations()` call: explain that this bypasses the full `ServerEntityMutation` pipeline
  - At the routing call: explain that `EntityIndexMutation` carries the target entity type

### Test Cases

Tests are organized into two test classes: a unit-level class for `LocalMutationExecutorCollector`
and `EntityCollection` dispatch logic (using mocks for upstream dependencies from WBS-05/07/08),
and an integration-level class that verifies the full pipeline end-to-end.

---

#### Test Class 1: `LocalMutationExecutorCollectorStep5bTest`

**Package:** `io.evitadb.core.collection`
**Scope:** Unit tests for the Step 5b dispatch loop, ordering guarantees, nesting, WAL exclusion,
error propagation, and edge cases. Uses mocked `EntityIndexLocalMutationExecutor`,
`ContainerizedLocalMutationExecutor`, `Catalog`, and `EntityCollection` to isolate the collector
logic.

##### Category A — Step 5b Dispatch Loop Insertion

- [ ] `step5b_calls_popIndexImplicitMutations_after_step5a_completes`
  Invoke `execute()` with an `EntityUpsertMutation` carrying attribute mutations. Verify that
  `entityIndexUpdater.popIndexImplicitMutations(localMutations)` is called exactly once, and that
  the call occurs AFTER `changeCollector.popImplicitMutations()` has returned (use mock invocation
  ordering verification).

- [ ] `step5b_iterates_returned_entityIndexMutations_and_routes_to_target_collection`
  Configure `popIndexImplicitMutations()` to return an `IndexImplicitMutations` containing two
  `EntityIndexMutation` envelopes targeting different entity types ("ProductA", "ProductB").
  Verify that `catalog.getCollectionForEntityOrThrowException()` is called once per entity type,
  and `applyIndexMutations()` is called on the correct target `EntityCollection` instance for each.

- [ ] `step5b_routes_multiple_envelopes_targeting_same_collection_independently`
  Configure `popIndexImplicitMutations()` to return two `EntityIndexMutation` envelopes both
  targeting entity type "Product". Verify that `applyIndexMutations()` is called twice on the
  same target collection — once per envelope. No batching or merging occurs.

- [ ] `step5b_passes_correct_localMutations_list_to_popIndexImplicitMutations`
  Invoke `execute()` with an `EntityUpsertMutation` whose `getLocalMutations()` returns a known
  list. Capture the argument passed to `popIndexImplicitMutations()` and assert it is the exact
  same list instance (not a copy) that was passed to `changeCollector.popImplicitMutations()`.

##### Category B — Container-First Ordering

- [ ] `step5a_container_implicit_mutations_complete_before_step5b_begins`
  Configure Step 5a to produce both local implicit mutations and external mutations. Attach
  side-effect recorders to `changeCollector.applyMutation()` (for local implicit),
  `catalog.getCollectionForEntityOrThrowException().applyMutations()` (for external), and
  `entityIndexUpdater.popIndexImplicitMutations()` (for Step 5b). Assert that the Step 5b call
  timestamp/order is strictly after ALL Step 5a local and external mutation applications.

- [ ] `step5a_external_mutation_side_effects_visible_before_step5b_runs`
  Configure Step 5a to produce an external mutation that updates entity B's storage. Configure
  Step 5b to return an `EntityIndexMutation` targeting entity A. Verify via invocation ordering
  that entity B's `applyMutations()` completes before `popIndexImplicitMutations()` is called,
  ensuring B's storage is consistent when Step 5b triggers read it.

##### Category C — EntityIndexMutation Routing to Target Collection

- [ ] `step5b_routes_entityIndexMutation_using_entityType_accessor`
  Create an `EntityIndexMutation` with `entityType()` returning "Category". Verify that
  `catalog.getCollectionForEntityOrThrowException("Category")` is called, and the returned
  collection receives the `applyIndexMutations()` call with the same `EntityIndexMutation` instance.

- [ ] `step5b_throws_when_target_collection_does_not_exist`
  Configure `catalog.getCollectionForEntityOrThrowException()` to throw
  `CollectionNotFoundException` for the target entity type. Invoke `execute()` and verify the
  exception propagates — stored in the collector's exception field and re-thrown after the
  `finally` block. This confirms that a trigger referencing a non-existent entity type fails the
  transaction.

##### Category D — Nesting Behavior (Level Counter)

- [ ] `step5b_runs_at_same_level_as_step5a_without_incrementing_level`
  Add a side-effect to `popIndexImplicitMutations()` that captures the current `level` value
  (via reflection or a test spy). Verify that Step 5b runs at the same level as Step 5a — no
  additional increment/decrement around the Step 5b loop.

- [ ] `nested_execute_at_level_n_plus_1_runs_own_step5b_before_returning_to_level_n`
  Configure Step 5a at level 0 to produce an external mutation on entity B. When entity B's
  `applyMutations()` is called (level 1), configure its own Step 5b to return index mutations.
  Verify that entity B's Step 5b executes and completes within the level-1 scope, BEFORE control
  returns to level 0's Step 5b.

- [ ] `level_counter_correctly_tracks_depth_through_nested_step5a_and_step5b`
  Configure a three-level nesting chain: entity A (level 0) -> external mutation on B (level 1)
  -> external mutation on C (level 2). Each level has its own Step 5b. Verify via spy that:
  (1) C's Step 5b runs at level 2, (2) B's Step 5b runs at level 1 after C fully completes,
  (3) A's Step 5b runs at level 0 after B fully completes.

- [ ] `applyIndexMutations_does_not_participate_in_level_counter`
  Verify that calling `applyIndexMutations()` on a target collection does NOT increment the
  `level` counter on the collector. The thin dispatcher is not a nested `execute()` call — it
  dispatches to `IndexMutationExecutorRegistry` directly without re-entering the collector.

##### Category E — Empty IndexImplicitMutations

- [ ] `step5b_with_empty_indexMutations_array_makes_no_routing_calls`
  Configure `popIndexImplicitMutations()` to return `EMPTY_INDEX_IMPLICIT_MUTATIONS` (empty
  array). Verify that `catalog.getCollectionForEntityOrThrowException()` is never called from
  the Step 5b loop, and no `applyIndexMutations()` calls are made.

- [ ] `step5b_with_no_triggers_registered_returns_empty_and_is_noop`
  Configure the executor with no registered triggers. Invoke `execute()` with a mutation that
  changes an attribute. Verify that `popIndexImplicitMutations()` returns an empty result and
  the Step 5b loop body does not execute.

##### Category F — `applyIndexMutations()` Thin Dispatcher

- [ ] `applyIndexMutations_iterates_all_nested_indexMutations_and_dispatches_each`
  Create an `EntityIndexMutation` containing three `IndexMutation` instances (e.g., three
  `ReevaluateFacetExpressionMutation` records). Call `applyIndexMutations()` on an
  `EntityCollection` instance. Verify that `IndexMutationExecutorRegistry.INSTANCE.dispatch()`
  is called exactly three times — once per `IndexMutation` — with the correct mutation and
  `this` (the collection as `IndexMutationTarget`) as arguments.

- [ ] `applyIndexMutations_passes_collection_as_indexMutationTarget`
  Call `applyIndexMutations()` on an `EntityCollection`. Capture the second argument passed to
  `IndexMutationExecutorRegistry.INSTANCE.dispatch()`. Verify it is the same `EntityCollection`
  instance cast to `IndexMutationTarget`.

- [ ] `applyIndexMutations_with_empty_mutations_array_dispatches_nothing`
  Create an `EntityIndexMutation` with an empty `mutations()` array. Call
  `applyIndexMutations()`. Verify that `IndexMutationExecutorRegistry.INSTANCE.dispatch()` is
  never called.

- [ ] `applyIndexMutations_does_not_create_executors_or_modify_storage`
  Call `applyIndexMutations()` and verify that no `ContainerizedLocalMutationExecutor` or
  `EntityIndexLocalMutationExecutor` is created, no storage parts are written, no schema
  evolution occurs, and no entity is returned.

##### Category G — Cross-Entity Trigger After Local Trigger

- [ ] `local_trigger_at_level_n_completes_before_cross_entity_trigger_at_step5b`
  Configure a scenario where Step 5a's local implicit mutations include a facet index update
  (from WBS-09 local trigger) and Step 5b produces a cross-entity trigger for the same entity.
  Verify via invocation ordering that the local trigger's bitmap modification completes before
  the cross-entity trigger's `applyIndexMutations()` runs.

- [ ] `duplicate_facet_operation_from_local_and_cross_entity_trigger_is_idempotent`
  Configure both a local trigger (during Step 5a, via `ReferenceIndexMutator`) and a cross-entity
  trigger (during Step 5b) to produce the same facet add operation for the same entity PK and
  reference name. Verify that after both execute, the bitmap contains the value exactly once and
  no error is thrown.

##### Category H — WAL Exclusion

- [ ] `step5b_does_not_add_mutations_to_entityMutations_list`
  Invoke `execute()` at level 0 with Step 5b returning non-empty index mutations. After
  `execute()` completes, verify that `entityMutations` list contains only the root-level
  `EntityMutation` — no `EntityIndexMutation` instances are present.

- [ ] `commit_does_not_register_index_mutations_to_wal`
  Invoke `execute()` producing index mutations, then call `commit()`. Verify that
  `Transaction.getTransaction().registerMutation()` is called only for the original entity
  mutation, never for any `EntityIndexMutation` or `IndexMutation`.

- [ ] `step5b_index_mutations_regenerated_on_wal_replay`
  Simulate WAL replay by invoking `execute()` with a `ServerEntityUpsertMutation` where
  `undoOnError=false` and `checkConsistency=false`. Verify that Step 5b still runs —
  `popIndexImplicitMutations()` is called and index mutations are dispatched. This confirms
  that index triggers are regenerated deterministically on replay.

##### Category I — Transaction Boundary

- [ ] `step5b_runs_within_same_transaction_as_step5a`
  Invoke `execute()` within an active `Transaction`. Verify that Step 5b's
  `applyIndexMutations()` calls on target collections occur within the same transactional scope
  — no new transaction is created, and the `DataStoreMemoryBuffer` shares the same transactional
  memory layer. Concretely, capture the `DataStoreMemoryBuffer` instance identity used by
  Step 5a (via the `ContainerizedLocalMutationExecutor`) and verify it is the same instance
  accessible to the executor during Step 5b dispatch.

- [ ] `step5b_failure_causes_rollback_of_entire_transaction_at_level_zero`
  Configure `applyIndexMutations()` on a target collection to throw a `RuntimeException`. Invoke
  `execute()` at level 0. Verify that the exception is caught, stored in the collector's
  exception field, `finish()` is called (level reaches 0), `rollback()` is invoked on all
  registered executors, and the exception is re-thrown to the caller.

- [ ] `step5b_at_nested_level_stores_exception_without_calling_finish`
  Configure Step 5b to throw at level 1 (during a nested external mutation). Verify that the
  exception is stored but `finish()` is NOT called (level decrements to 1, not 0). Control
  returns to level 0, which calls `finish()` and propagates the stored exception.

##### Category J — Error Propagation

- [ ] `exception_in_popIndexImplicitMutations_propagates_to_collector_exception_field`
  Configure `entityIndexUpdater.popIndexImplicitMutations()` to throw a `RuntimeException`.
  Verify that the exception is caught by the existing `catch` block, stored in `this.exception`,
  and re-thrown after the `finally` block completes. The `level` counter is correctly decremented.

- [ ] `exception_in_step5a_external_mutation_skips_step5b_entirely`
  Configure Step 5a's external mutation dispatch to throw a `RuntimeException` during
  `applyMutations()` on the target collection. Verify that `popIndexImplicitMutations()` is
  NEVER called — the exception short-circuits the try block before reaching Step 5b. This is
  correct: if external mutations fail, index triggers should not fire on inconsistent state.

- [ ] `exception_in_step5b_does_not_suppress_prior_step5a_exception`
  Configure Step 5a to succeed but store a suppressed exception. Then configure Step 5b to throw
  a new exception. Verify that both exceptions are preserved — the Step 5b exception is added as
  a suppressed exception on the original, or vice versa, matching the existing
  `this.exception.addSuppressed(ex)` pattern.

- [ ] `exception_in_one_entityIndexMutation_skips_remaining_envelopes`
  Configure `popIndexImplicitMutations()` to return three `EntityIndexMutation` envelopes. The
  second envelope's `applyIndexMutations()` throws. Verify that the third envelope is never
  dispatched — the exception exits the for-loop immediately. The first envelope's side effects
  remain (no rollback of individual dispatches within Step 5b).

##### Category K — `generateImplicitMutations` Guard Independence

- [ ] `step5b_runs_when_generateImplicitMutations_is_empty`
  Invoke `execute()` with `generateImplicitMutations = EnumSet.noneOf(ImplicitMutationBehavior.class)`.
  Verify that Step 5a is skipped (no `popImplicitMutations()` call) but Step 5b still runs —
  `popIndexImplicitMutations()` is called unconditionally. This covers the plain
  `EntityRemoveMutation` path.

- [ ] `step5b_runs_when_generateImplicitMutations_is_non_empty`
  Invoke `execute()` with `generateImplicitMutations = EnumSet.allOf(ImplicitMutationBehavior.class)`.
  Verify both Step 5a and Step 5b run in sequence.

##### Category L — Entity Removal Path

- [ ] `step5b_fires_for_entity_remove_mutation_with_removal_local_mutations`
  Invoke `execute()` with an `EntityRemoveMutation`. Verify that
  `popIndexImplicitMutations(localMutations)` is called with the removal-computed local mutations
  (from `computeLocalMutationsForEntityRemoval()`), and that any returned index mutations are
  dispatched to target collections.

- [ ] `step5b_fires_for_server_entity_remove_mutation`
  Invoke `execute()` with a `ServerEntityRemoveMutation` (which has
  `generateImplicitMutations = EnumSet.of(GENERATE_REFLECTED_REFERENCES)`). Verify that both
  Step 5a and Step 5b run, and that `popIndexImplicitMutations()` receives the removal-computed
  local mutations.

##### Category M — Traffic Recording Non-Interference

- [ ] `step5b_does_not_affect_traffic_recording`
  Invoke `execute()` at level 0 with traffic recording enabled. Verify that the
  `MutationApplicationRecord` is created and `record.finish()` is called AFTER Step 5b completes
  (not before). Index mutations are not recorded in the traffic record.

- [ ] `step5b_exception_causes_record_finishWithException`
  Configure Step 5b to throw. Verify that `record.finishWithException(ex)` is called with the
  Step 5b exception, matching the existing error-recording pattern.

---

#### Test Class 2: `LocalMutationExecutorCollectorStep5bIntegrationTest`

**Package:** `io.evitadb.core.collection`
**Scope:** Integration tests that verify the full pipeline with real (or minimally mocked)
`EntityCollection`, `Catalog`, `EntityIndexLocalMutationExecutor`, and
`ContainerizedLocalMutationExecutor` instances. These tests require WBS-05, WBS-06, WBS-07, WBS-08,
and WBS-09 to be implemented.

##### Category N — Full Pipeline Integration

- [ ] `attribute_change_triggers_step5b_index_mutation_dispatched_to_target_collection`
  Set up entity B with an attribute referenced by a trigger registered in
  `CatalogExpressionTriggerRegistry`. Mutate B's attribute value. Verify the full pipeline:
  explicit mutation applied -> Step 5a container implicit mutations generated and applied ->
  Step 5b calls `popIndexImplicitMutations()` which returns `EntityIndexMutation` targeting
  collection A -> `applyIndexMutations()` is called on collection A -> executor receives the
  `ReevaluateFacetExpressionMutation`.

- [ ] `no_trigger_registered_produces_empty_step5b_and_no_dispatch`
  Set up entity B with no triggers registered for any of its attributes. Mutate an attribute.
  Verify that `popIndexImplicitMutations()` returns the empty singleton and no target collection
  is looked up or dispatched to.

- [ ] `nested_external_mutation_triggers_step5b_at_inner_level`
  Set up entity A with a reflected reference to entity B. Mutate entity A, causing Step 5a to
  generate an external mutation on B. Entity B has a trigger that fires during its own Step 5b,
  targeting entity C. Verify the full nesting chain: A level 0 Step 5a -> B level 1 Step 5a ->
  B level 1 Step 5b dispatches to C -> A level 0 Step 5b runs after B fully completes.

- [ ] `entity_removal_triggers_step5b_for_all_dependent_expressions`
  Set up entity B referenced by multiple expressions across different entity types. Remove
  entity B. Verify that `popIndexImplicitMutations()` detects the removal and produces
  `EntityIndexMutation` envelopes for each dependent entity type, and each is dispatched to the
  correct target collection.

- [ ] `step5b_runs_even_when_step5a_is_skipped_due_to_empty_generateImplicitMutations`
  Use the plain `EntityRemoveMutation` path (not `ServerEntityRemoveMutation`) which passes
  `EnumSet.noneOf(ImplicitMutationBehavior.class)`. Verify that Step 5a is skipped but Step 5b
  still runs and correctly dispatches any index mutations.

##### Category O — Cross-Transaction Visibility

- [ ] `target_collection_indexes_reflect_step5a_updates_when_step5b_reads_them`
  Within a single transaction: mutate entity B's attribute (Step 5a updates B's indexes and
  storage). Then Step 5b fires a trigger that reads B's indexes from entity A's target
  collection. Verify that the index data read during Step 5b reflects B's updated state, not
  the pre-mutation state. This confirms `DataStoreMemoryBuffer` / transactional memory layer
  visibility.

- [ ] `pending_storage_changes_from_step5a_visible_to_executor_in_step5b`
  Within a single transaction: Step 5a external mutations update entity B's storage parts (via
  `TransactionalDataStoreMemoryBuffer`). Step 5b dispatches a trigger that needs to read B's
  storage. Verify the executor sees the updated storage parts, not stale data.

##### Category P — De-Duplication via Idempotency

- [ ] `local_trigger_and_cross_entity_trigger_same_facet_add_produces_single_bitmap_entry`
  Set up entity A where both a local trigger (WBS-09, fires during Step 5a in
  `ReferenceIndexMutator`) and a cross-entity trigger (this WBS, fires during Step 5b) produce
  a facet add for the same entity PK and reference name. Verify that the bitmap contains the
  PK exactly once after both triggers complete, and no error or exception is thrown.

- [ ] `local_trigger_fires_before_cross_entity_trigger_ensuring_deterministic_order`
  Same setup as above. Verify via side-effect ordering that the local trigger's add operation
  executes first (during Step 5a at level N), and the cross-entity trigger's add operation
  executes second (during Step 5b at level N or level N+1). The second operation is a no-op
  because the bitmap already contains the value.

##### Category Q — WAL Replay Correctness

- [ ] `wal_replay_regenerates_step5b_index_mutations_deterministically`
  Perform a mutation that produces Step 5b index mutations. Commit the transaction. Then replay
  the WAL (using `ServerEntityUpsertMutation` with `undoOnError=false`, `checkConsistency=false`).
  Verify that during replay, the same `popIndexImplicitMutations()` call produces the same
  `EntityIndexMutation` envelopes, and they are dispatched to the same target collections. This
  confirms that index state is consistent after WAL replay.

- [ ] `wal_replay_with_server_entity_remove_mutation_triggers_step5b`
  Replay a `ServerEntityRemoveMutation` from WAL. Verify that Step 5b runs and produces the
  same removal-related index mutations as the original transaction.

##### Category R — Error Handling Integration

- [ ] `target_collection_not_found_fails_transaction_with_descriptive_error`
  Register a trigger that references entity type "NonExistent". Mutate an attribute that fires
  the trigger. Verify that `getCollectionForEntityOrThrowException("NonExistent")` throws
  `CollectionNotFoundException`, the exception propagates through the collector, and the
  transaction is rolled back.

- [ ] `executor_exception_during_step5b_dispatch_fails_transaction`
  Register a valid trigger. Configure the `IndexMutationExecutor` to throw during dispatch.
  Verify that the exception propagates from `applyIndexMutations()` through the collector's
  catch block, rolls back all registered executors at level 0, and is re-thrown to the caller.
