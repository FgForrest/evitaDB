# WBS-11: Initial Catalog Load and Cold Start Wiring

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Wire active `facetedPartially` expression triggers between `EntityCollection` instances during
catalog initialization (cold start), so that cross-entity trigger infrastructure is fully
operational before any new mutations arrive through WAL replay or client requests.

## Scope

### In Scope

- Scanning all loaded `ReferenceSchema` definitions that carry `facetedPartiallyInScopes`
  expressions after `EntityCollection` instantiation during catalog startup.
- Populating `CatalogExpressionTriggerRegistry` with trigger entries derived from the scanned
  schemas — the same process used at schema load/change time, executed during catalog
  initialization.
- Processing both direct and reflected reference schemas: `ReflectedReferenceSchema` instances
  that inherit `facetedPartially` from the source `ReferenceSchema` must also have their
  triggers built and registered.
- Building `FacetExpressionTrigger` (and related `ExpressionIndexTrigger`) instances from the
  `Expression` objects stored in `ReferenceSchema.facetedPartiallyInScopes`, using
  `AccessedDataFinder` to analyze attribute paths and determine dependency types.
- Registering triggers in `CatalogExpressionTriggerRegistry` under the appropriate
  `(mutatedEntityType, dependencyType)` keys so that post-load mutations are routed correctly.
- Ensuring WAL replay after load goes through the standard mutation pipeline where
  `ReferenceIndexMutator`'s updated `isFaceted()` guard (with expression evaluation) handles
  new changes normally.

### Out of Scope

- **Re-evaluation of expressions on load** — data loaded from disk storage is already consistent
  with the schema (and its expressions); indexes are persisted in their correct state. No
  expression re-evaluation is needed on load.
- **Full re-index on `facetedPartially` expression change** — the expression is treated as
  effectively immutable after initial schema creation for this iteration. The re-index mechanism
  will be addressed in a separate issue (AD 20, Hidden trap #7).
- **Schema mutations that change `facetedPartially` expressions** — schema evolution is deferred.
  The expression is effectively immutable after creation.

## Dependencies

### Depends On

- **WBS-04** — provides `CatalogExpressionTriggerRegistry`, the catalog-level registry that this
  task populates during cold start.
- **WBS-10** — provides the collector integration (mutation collector pipeline) that the triggers
  feed into once wired.

### Depended On By

- All downstream tasks that assume cross-entity triggers are operational after catalog load.
  Without this wiring, mutations arriving after cold start would not generate cross-entity
  `ReevaluateFacetExpressionMutation` instances.

## Technical Context

### Data consistency on load

Data loaded from disk storage is already consistent with the schema and its expressions. Indexes
are persisted in their correct state. No expression re-evaluation is needed on load. New changes
arriving after load go through WAL and the standard mutation pipeline, where
`ReferenceIndexMutator`'s updated `isFaceted()` guard (with expression evaluation) handles them
normally.

### Error handling for registry build failures

If `buildFromSchemas()` throws an exception during cold start (Path 1) or `goLive()` (Path 2),
the failure should propagate as a catalog load failure. Possible causes include a corrupt schema
with an unparseable expression, or an `AccessedDataFinder` failure on a malformed expression.

- **Path 1 (cold start):** An exception thrown in `buildInitialExpressionTriggerRegistry()` within
  the `loadCatalog()` completion handler propagates to the error handler (line 499 of
  `Catalog.java`), which terminates all collections and the catalog, then calls the `onFailure`
  callback. This is the correct behavior — a catalog with an incomplete registry should not be
  served, as post-load mutations would silently skip cross-entity triggers.
- **Path 2 (`goLive()`):** An exception in `buildInitialExpressionTriggerRegistry()` within the
  copy constructor propagates out of `goLive()`, leaving the original WARMING_UP catalog intact.
  The caller receives the exception and can handle it appropriately.

The registry build failure should NOT be silently swallowed — an incomplete registry would cause
silent data corruption (missing facet index entries) that is difficult to diagnose later.

### Trigger wiring during catalog initialization

After `EntityCollection` instantiation, the catalog must wire active trigger implementations
between collections according to their current schemas. This means:

1. Scanning all loaded `ReferenceSchema` definitions with `facetedPartiallyInScopes` expressions
   across all entity collections.
2. For each such schema, using `AccessedDataFinder` to analyze the `Expression` and determine
   what attribute paths and entity types it depends on.
3. Building `FacetExpressionTrigger` instances from the analyzed expression.
4. Registering them in `CatalogExpressionTriggerRegistry` under `(targetEntityType,
   dependencyType)` keys — the same process as schema load time trigger building.

### ReflectedReferenceSchema inheritance

`ReflectedReferenceSchema` can inherit `facetedPartially` from the source `ReferenceSchema`.
Trigger registration must account for inherited expressions:

- When building triggers at catalog load time, `EntityCollection` processes both direct and
  reflected reference schemas. For reflected schemas that inherit `facetedPartially`, the
  trigger is built from the inherited expression and registered under the reflected schema's
  entity type.
- When the source schema's `facetedPartially` expression changes, all reflected schemas that
  inherit it must have their triggers rebuilt. The `CatalogExpressionTriggerRegistry` rebuild
  for the source entity type must cascade to all entity types with reflected references that
  inherit from the changed source. (Note: actual expression changes are out of scope per AD 20,
  but the cascade mechanism must be architecturally accounted for.)

### Cross-schema wiring (AD 13)

When a `ReferenceSchema`'s `facetedPartially` expression is set, `EntityCollection` (in
`evita_engine`) reads the `Expression` from `ReferenceSchema.facetedPartiallyInScopes`, uses
`AccessedDataFinder` to analyze paths, builds `FacetExpressionTrigger` instances, and registers
them in `CatalogExpressionTriggerRegistry` under the appropriate `(mutatedEntityType,
dependencyType)` keys. Schema B "maintains" triggers for schema A by virtue of being the
registry key. The cold start wiring replicates this exact process for all existing schemas.

### Module boundary: evita_api vs evita_engine (AD 14)

- `ReferenceSchema.facetedPartiallyInScopes` (`Map<Scope, Expression>`) lives in `evita_api` —
  it is the declarative contract, a pure data field, part of the public API.
- `ExpressionIndexTrigger` and all its implementations live in `evita_engine` alongside
  `EntityCollection` — they are operational engine internals that should never be part of the
  public API surface.
- Triggers are built from the `Expression` at schema load/change time by `EntityCollection`
  (or a helper it delegates to) and registered in `CatalogExpressionTriggerRegistry`.
- This placement gives `EntityCollection` symmetric responsibility: it hosts both the
  **inbound** dispatcher (`IndexMutationExecutorRegistry` — processes `IndexMutation` from
  other collections) and the **outbound** trigger infrastructure (`ExpressionIndexTrigger`
  instances — generates `IndexMutation` originating from this collection's schema definitions).

### Schema evolution — deferred (AD 20)

Full re-index on `facetedPartially` expression change is out of scope for this iteration. The
expression is treated as effectively immutable after initial schema creation. The re-index
mechanism will be addressed in a separate issue.

### Relevant hidden traps

- **Hidden trap #7 (Schema evolution)** — expression changes on existing data are out of scope.
  The cold start wiring assumes expressions have not changed since the data was persisted.
- **Hidden trap #18 (ReflectedReferenceSchema inheritance)** — reflected references inherit
  `facetedPartially` from the source schema. Trigger registration during cold start must process
  both direct and reflected schemas. Source expression change cascades trigger rebuild to all
  inheriting reflected schemas (cascade mechanism must be structurally supported even though
  expression changes are deferred).

## Key Interfaces

- **`CatalogExpressionTriggerRegistry`** — catalog-level registry populated during cold start;
  maps `(targetEntityType, dependencyType)` to lists of `ExpressionIndexTrigger` instances.
- **`EntityCollection`** — hosts symmetric inbound/outbound infrastructure; drives the schema
  scanning and trigger building during initialization.
- **`ReferenceSchema.facetedPartiallyInScopes`** — `Map<Scope, Expression>` read from each
  loaded reference schema to extract trigger expressions.
- **`ReflectedReferenceSchema`** — must be inspected for inherited `facetedPartially`
  expressions; triggers built from inherited expressions registered under the reflected entity
  type.
- **`AccessedDataFinder`** — analyzes `Expression` to determine attribute paths and dependency
  types for trigger construction.
- **`FacetExpressionTrigger` / `ExpressionIndexTrigger`** — trigger instances built from
  expressions and cached in the catalog-level registry.

## Acceptance Criteria

1. After catalog cold start, `CatalogExpressionTriggerRegistry` contains trigger entries for
   every `ReferenceSchema` (direct and reflected/inherited) that carries a
   `facetedPartiallyInScopes` expression.
2. Triggers registered during cold start are identical in behavior to those that would be
   registered if the same schemas were created at runtime via schema mutations.
3. WAL replay after catalog load correctly generates cross-entity
   `ReevaluateFacetExpressionMutation` instances for mutations matching registered triggers.
4. `ReflectedReferenceSchema` instances that inherit `facetedPartially` from their source schema
   have their triggers correctly built and registered under the reflected schema's entity type.
5. No expression re-evaluation occurs for data loaded from disk — only trigger wiring is
   performed.
6. The cold start wiring process handles the case where multiple entity collections reference
   each other (mutual cross-schema triggers) without ordering issues — specifically: (a) all
   triggers are registered regardless of `initSchema()` iteration order, (b) no exceptions are
   thrown during initialization, and (c) the final registry state is identical across different
   iteration orderings.

## Implementation Notes

- The trigger-building logic should be extracted into a reusable helper (shared between schema
  load/change time and cold start) to avoid duplication. `EntityCollection` or a dedicated
  factory delegates to this helper in both code paths.
- Cold start wiring must happen after all `EntityCollection` instances are instantiated but
  before any WAL replay or client mutation processing begins, ensuring the registry is fully
  populated before triggers are needed.
- The scanning order across entity collections does not matter because trigger registration is
  additive and idempotent — each collection contributes its own triggers independently.
- Trigger instances are built from `ReferenceSchema.facetedPartiallyInScopes` Expression at
  schema load time and cached in the catalog-level registry (AD 14). The cold start path must
  use the same caching strategy.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### Key class locations

| Class / Interface | Absolute path |
|---|---|
| `Catalog` | `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java` |
| `EntityCollection` | `evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java` |
| `LocalMutationExecutorCollector` | `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java` |
| `TransactionManager` | `evita_engine/src/main/java/io/evitadb/core/transaction/TransactionManager.java` |
| `Evita` | `evita_engine/src/main/java/io/evitadb/core/Evita.java` |
| `ReferenceSchemaContract` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java` |
| `ReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReferenceSchema.java` |
| `ReflectedReferenceSchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java` |
| `EntitySchema` | `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/EntitySchema.java` |
| `UnusableCatalog` | `evita_engine/src/main/java/io/evitadb/core/catalog/UnusableCatalog.java` |
| `ServerEntityUpsertMutation` | `evita_engine/src/main/java/io/evitadb/core/transaction/stage/mutation/ServerEntityUpsertMutation.java` |
| `ServerEntityRemoveMutation` | `evita_engine/src/main/java/io/evitadb/core/transaction/stage/mutation/ServerEntityRemoveMutation.java` |

##### Catalog initialization lifecycle — three code paths that require registry wiring

**Path 1 — Cold start from disk (`Catalog.loadCatalog()`):**

The `Catalog.loadCatalog()` static method (line 348) returns a `ProgressingFuture<Catalog>` with four phases:

1. **Phase 1** (line 363): Creates empty maps for `collections`, `collectionByPk`, `entitySchemaIndex`, and constructs the private `Catalog` via the loading constructor (line 596).
2. **Phase 2** (line 396): For each entity type file index, creates `EntityCollection` (line 417), calls `collection.attachToCatalog(null, catalog)` (line 432), puts the schema into `entitySchemaIndex` (line 433), then loads entity indexes for that collection. These run in parallel across entity types.
3. **Phase 3 — completion handler** (line 489): Runs sequentially AFTER all Phase 2 futures complete:
   ```
   for (EntityCollection collection : initBulk.collections().values()) {
       collection.initSchema();   // line 493
   }
   onSuccess.accept(catalogName, catalog);  // line 495
   ```
4. **Phase 4 — error handler** (line 499): Terminates all collections and catalog on failure.

The `onSuccess` callback in `Evita.loadCatalogInternal()` (line 1071-1091 of `Evita.java`) calls `catalog.processWriteAheadLog()` (line 1073). This means WAL replay runs AFTER all `initSchema()` calls complete but is triggered from the `onSuccess` callback.

**Registry insertion point for Path 1:** After the `initSchema()` loop at line 494, before `onSuccess.accept()` at line 495. This ensures the registry is fully populated before WAL replay begins.

**Path 2 — `goLive()` transition (WARMING_UP to ALIVE):**

`Catalog.goLive()` (line 1089) creates a new `Catalog` via the 8-parameter copy constructor (line 711) with `initSchemas=true`:
```
final Catalog newCatalog = new Catalog(
    1L, CatalogState.ALIVE, catalogIndex, archiveCatalogIndex,
    newCollections, this.persistenceService, this, true  // line 1115: initSchemas=true
);
```

The 8-parameter copy constructor (lines 711-784):
1. Copies persistent state from `previousCatalogVersion`
2. Builds `entityCollections`, `entityCollectionsByPrimaryKey`, `entitySchemaIndex` maps (lines 755-769)
3. Attaches every collection to this catalog (lines 772-774)
4. If `initSchemas==true`, calls `initSchema()` for each collection, then puts schema into index (lines 776-783)

**Registry insertion point for Path 2:** After the `initSchema()` loop ends at line 783, before the constructor returns at line 784. Since `goLive()` returns the new catalog which then replaces the old one in the live view (and `transactionManager.advanceVersion()` is called at line 1118), the registry must be populated before the constructor returns.

**Path 3 — Transaction commit (`createCopyWithMergedTransactionalMemory()`):**

`Catalog.createCopyWithMergedTransactionalMemory()` (line 1446) creates a new `Catalog` via the 6-parameter constructor (line 691), which delegates to the 8-parameter constructor with `initSchemas=false` (line 707). In this path, schemas are NOT re-initialized — they are carried forward from the transactional memory layer. The registry should be propagated from the previous catalog version, not rebuilt.

Per WBS-04 Group 6, the `CatalogExpressionTriggerRegistry` is held in a `TransactionalReference` on `Catalog`. For Path 3, the `TransactionalReference` handles propagation automatically — `getStateCopyWithCommittedChanges()` merges any transactional modifications (from schema changes during the transaction) into the new catalog version. No additional code is needed for this path.

##### `EntityCollection.initSchema()` — resolving reflected reference inheritance

`initSchema()` (lines 1086-1114 of `EntityCollection.java`) resolves `ReflectedReferenceSchema` instances by calling `reflectedReferenceSchema.withReferencedSchema(originalReference)`. When `facetedInherited == true` on the reflected schema, `withReferencedSchema()` (line 1739-1741 of `ReflectedReferenceSchema.java`) copies the source reference's `getFacetedPartiallyInScopes()` into the reflected schema.

Critical ordering: `initSchema()` calls `this.catalog.getCollectionForEntity()` (line 1097) to look up the target entity's schema. This works because ALL collections are already in the `entityCollections` map (populated in Phase 2 for Path 1, or lines 761-764 for Path 2) and attached to the catalog (lines 772-774 for Path 2) BEFORE `initSchema()` runs.

If any reference schemas are updated, `initSchema()` calls `exchangeSchema()` (line 1107), which calls `this.catalog.entitySchemaUpdated(updatedSchema)` (line 2070). During cold start, this updates the `entitySchemaIndex` map. Per WBS-04 Group 7, `entitySchemaUpdated()` will also trigger a registry rebuild. However, during cold start, the registry doesn't exist yet — the initial build happens AFTER all `initSchema()` calls. This means the WBS-04 hook in `entitySchemaUpdated()` must handle the case where the registry hasn't been initialized yet (i.e., the field is still `EMPTY`). Since `rebuildForEntityType()` on an `EMPTY` registry just adds the new triggers, this is naturally safe — no special handling needed.

##### WAL replay flow — verifying trigger pipeline is active before replay

The WAL replay call chain:
1. `Evita.loadCatalogInternal()` `onSuccess` callback (line 1073 of `Evita.java`): calls `catalog.processWriteAheadLog()`
2. `Catalog.processWriteAheadLog()` (line 1140): calls `transactionManager.processEntireWriteAheadLog()`
3. `TransactionManager.processEntireWriteAheadLog()` (line 375): calls `processTransactions()` with `alive=false`
4. `processTransactions()` (line 940): iterates WAL mutations, calls `replayMutationsOnCatalog()`
5. `replayMutationsOnCatalog()` (line 1299): wraps entity mutations in `ServerEntityUpsertMutation(mutation, EnumSet.allOf(ImplicitMutationBehavior.class), false, false)` or `ServerEntityRemoveMutation(mutation, false, false)` and calls `lastFinalizedCatalog.applyMutation(evita, serverMutation)` (lines 1320-1337)
6. `Catalog.applyMutation()` (line 1326): routes to `EntityCollection.applyMutation()`
7. `EntityCollection.applyMutationInternal()` (line 1881): creates `LocalMutationExecutorCollector` and calls `execute()` which runs the full mutation pipeline including Step 5a (container implicit mutations) and Step 5b (index trigger mutations, per WBS-10)

**Verification:** The registry is populated during Step 3 of Path 1 (after `initSchema()` loop), and WAL replay begins at Step 1 of the WAL chain (called from `onSuccess` callback, which fires AFTER the `initSchema()` loop). Therefore, the registry is fully populated before any WAL replay mutation is applied.

For `WARMING_UP` state catalogs loaded from disk: the same flow applies. The `processWriteAheadLog()` call happens in the same `onSuccess` callback regardless of catalog state. The only difference is the `DataStoreMemoryBuffer` type (`WarmUpDataStoreMemoryBuffer` vs `TransactionalDataStoreMemoryBuffer`), which doesn't affect registry wiring.

##### Reflected reference ordering during `initSchema()` — no circular dependency risk

During `initSchema()`, each collection resolves its own reflected references by looking up the target collection's schema. The order in which collections' `initSchema()` is called doesn't matter because:

1. Each collection processes its OWN reflected reference schemas
2. The target schema is looked up from the `entityCollections` map (already fully populated)
3. `withReferencedSchema()` resolves inheritance from the target's ORIGINAL (non-reflected) reference — not from another reflected reference
4. If entity A has a reflected reference to entity B's reference, and entity B has a reflected reference to entity A's reference, both can be resolved independently because each reads the other's non-reflected reference definition

The registry build after `initSchema()` is also order-independent: `CatalogExpressionTriggerRegistryImpl.buildFromSchemas()` (per WBS-04 task 4.1) iterates all entity schemas and their references — both direct and reflected — extracting `facetedPartiallyInScopes` from each. Since reflected schemas are already resolved at this point, `getFacetedPartiallyInScopes()` returns the correct (possibly inherited) expression.

##### `entitySchemaUpdated()` during `initSchema()` — interaction with WBS-04 hook

When `initSchema()` calls `exchangeSchema()`, which calls `entitySchemaUpdated()`, the WBS-04 hook will attempt to rebuild the registry for the updated entity type. During cold start, this happens BEFORE the initial `buildFromSchemas()` call.

Two design options:
1. **Let incremental rebuilds accumulate during `initSchema()`**, then skip the `buildFromSchemas()` call since each `initSchema()` already triggered a rebuild. Problem: the first `initSchema()` only sees one collection's schemas, not all.
2. **Guard the rebuild hook**: during cold start, skip the registry rebuild in `entitySchemaUpdated()` and rely on the bulk `buildFromSchemas()` call after all `initSchema()` calls complete. This is cleaner because `buildFromSchemas()` sees ALL schemas at once.

**Recommended approach:** Option 2. The `entitySchemaUpdated()` hook should check a flag (e.g., `registryInitialized`) or simply tolerate operating on an empty/partial registry. Since the full `buildFromSchemas()` runs after all `initSchema()` calls, any incremental rebuilds during `initSchema()` are redundant but harmless — they will be overwritten by the full build. The incremental rebuilds during `initSchema()` operate on an initially empty registry and progressively build it up, and the final `buildFromSchemas()` produces the authoritative registry. For simplicity, option 2 is not strictly necessary — option 1 (let incremental rebuilds accumulate) actually works correctly because each rebuild is additive and uses the current `entitySchemaIndex` state. However, the full `buildFromSchemas()` after the loop ensures a clean, consistent registry regardless of `initSchema()` ordering.

**Final recommendation:** Do NOT guard the hook. Let the incremental rebuilds happen during `initSchema()` (they are harmless), AND call `buildFromSchemas()` after the loop as a definitive reset. The `buildFromSchemas()` call is cheap (iterates schemas once) and guarantees correctness.

##### Copy constructor registry propagation — interaction with `initSchemas` flag

The 8-parameter copy constructor has two modes:
- `initSchemas=true` (used by `goLive()`): calls `initSchema()`, triggering `exchangeSchema()` -> `entitySchemaUpdated()` -> WBS-04 rebuild hook. After the loop, `buildFromSchemas()` should be called.
- `initSchemas=false` (used by `createCopyWithMergedTransactionalMemory()` via 6-param constructor): no `initSchema()` call. The registry is propagated from `previousCatalogVersion` via the `TransactionalReference`.

**`goLive()` with empty `previousCatalogVersion` registry:** When `goLive()` is called, the
`previousCatalogVersion` is the WARMING_UP catalog. If schemas were added directly in WARMING_UP
state (not through the cold start path), the WARMING_UP catalog's registry may still be `EMPTY`
(if the WBS-04 `entitySchemaUpdated()` hook was not yet wired, or if schemas were loaded before
the hook was active). This is safe because the copy constructor initializes the registry from
`previousCatalogVersion` (which may be `EMPTY`), but when `initSchemas=true`, the subsequent
`buildInitialExpressionTriggerRegistry()` call overwrites it with the definitive build from all
current schemas. The initial value from `previousCatalogVersion` is effectively a placeholder that
gets replaced.

For the `initSchemas=false` case, the constructor at line 721-783 does NOT currently copy any `TransactionalReference` fields from `previousCatalogVersion` — it creates new ones (e.g., `this.versionId = new TransactionalReference<>(catalogVersion)` at line 722, `this.schema = new TransactionalReference<>(...)` at line 749). The registry field must follow the same pattern: `this.expressionTriggerRegistry = new TransactionalReference<>(previousCatalogVersion.getExpressionTriggerRegistry())`.

This is already specified in WBS-04 task 6.3. WBS-11's responsibility is to verify that the registry content is correct when propagated — i.e., if any schema changes occurred during the transaction (via `entitySchemaUpdated()` hook), those changes are reflected in the `TransactionalReference`'s transactional layer and will be merged on commit.

##### `Catalog` fields — existing `TransactionalReference` pattern for the registry

Per WBS-04 research, the registry field is:
```java
private final TransactionalReference<CatalogExpressionTriggerRegistry> expressionTriggerRegistry;
```

Initialization in each constructor:
- **Public constructor** (line 511 — new catalog): `new TransactionalReference<>(CatalogExpressionTriggerRegistry.EMPTY)` — no schemas exist yet.
- **Loading constructor** (line 596 — from disk): `new TransactionalReference<>(CatalogExpressionTriggerRegistry.EMPTY)` — populated later by `buildFromSchemas()` after `initSchema()` loop in `loadCatalog()`.
- **Copy constructor** (line 711 — version update): `new TransactionalReference<>(previousCatalogVersion.getExpressionTriggerRegistry())` — propagated from previous version.

`TransactionalReference.set()` behavior (confirmed at `TransactionalReference.java`): within a transaction, writes to the transactional layer; outside a transaction (warm-up state), writes directly to the `AtomicReference`. During cold start, there is no active transaction, so `set()` writes directly — consistent with schema index updates during initialization.

##### WAL replay and mutation pipeline readiness verification

The mutation pipeline for WAL replay uses the standard `LocalMutationExecutorCollector.execute()` path. The collector is created at six call sites in `EntityCollection` (per WBS-10 research), all passing `this.catalog` which holds the `expressionTriggerRegistry` field.

Step 5b (WBS-10) in the collector calls `entityIndexUpdater.popIndexImplicitMutations(localMutations)` which consults `catalog.getExpressionTriggerRegistry()` to find matching triggers. For WAL replay:
- `ServerEntityUpsertMutation` passes `EnumSet.allOf(ImplicitMutationBehavior.class)` — all implicit mutations enabled, including Step 5a (container implicit) and Step 5b (index trigger).
- `ServerEntityRemoveMutation` passes `EnumSet.of(GENERATE_REFLECTED_REFERENCES)` — Step 5a runs for reflected references. Step 5b runs unconditionally (per WBS-10 design: Step 5b is NOT guarded by `generateImplicitMutations`).

Both paths activate the full trigger pipeline during WAL replay, which is correct — triggers regenerate index state deterministically.

##### `UnusableCatalog` — no wiring needed

`UnusableCatalog` (line 199) throws on all operations including `processWriteAheadLog()`. It represents a corrupted catalog that cannot be used. No registry wiring is needed.

---

#### Detailed Task List

##### Group 1: Cold start registry build in `Catalog.loadCatalog()`

- [ ] **1.1** In the `loadCatalog()` completion handler (line 489-497 of `Catalog.java`), add a call to `catalog.buildInitialExpressionTriggerRegistry()` after the `initSchema()` loop (after line 494) and before `onSuccess.accept()` (line 495). This ensures the registry is fully populated before WAL replay begins. The method is defined in WBS-04 task 8.1:
  ```java
  for (EntityCollection collection : initBulk.collections().values()) {
      collection.initSchema();
  }
  catalog.buildInitialExpressionTriggerRegistry();
  onSuccess.accept(catalogName, catalog);
  ```

- [ ] **1.2** Verify that `buildInitialExpressionTriggerRegistry()` uses the `entitySchemaIndex` map that was populated during Phase 2 (line 433) and updated by `initSchema()` -> `exchangeSchema()` -> `entitySchemaUpdated()` (line 2070). At this point, ALL schemas have resolved reflected references. The method calls `CatalogExpressionTriggerRegistryImpl.buildFromSchemas(this.entitySchemaIndex)` (per WBS-04 task 4.1) and stores the result via `this.expressionTriggerRegistry.set(fullRegistry)`.

- [ ] **1.3** Add a log statement at INFO level after the registry build: `log.info("Expression trigger registry initialized with {} triggers for catalog '{}'", triggerCount, catalogName)` — where `triggerCount` is the total number of triggers across all entity types. This aids debugging of cold start issues.

##### Group 2: Registry build in `goLive()` copy constructor

- [ ] **2.1** In the 8-parameter copy constructor (line 711-784 of `Catalog.java`), after the `initSchema()` / schema population loop ends at line 783, add a conditional registry build when `initSchemas == true`:
  ```java
  if (initSchemas) {
      buildInitialExpressionTriggerRegistry();
  }
  ```
  When `initSchemas == false` (used by `createCopyWithMergedTransactionalMemory()`), the registry is propagated from `previousCatalogVersion` via the `TransactionalReference` (see WBS-04 task 6.3) — no rebuild needed.

- [ ] **2.2** Verify that `goLive()` (line 1089) passes `initSchemas=true` to the 8-parameter constructor (confirmed at line 1115). The new ALIVE catalog will have a freshly-built registry reflecting all resolved schemas.

- [ ] **2.3** Verify that the registry field is initialized from `previousCatalogVersion` BEFORE the `initSchema()` loop runs (in the constructor field initialization section, around lines 721-735). The WBS-04 task 6.3 specifies: `this.expressionTriggerRegistry = new TransactionalReference<>(previousCatalogVersion.getExpressionTriggerRegistry())`. This means the initial value is the previous version's registry, which gets overwritten by `buildInitialExpressionTriggerRegistry()` if `initSchemas == true`.

##### Group 3: Reflected reference resolution ordering

- [ ] **3.1** Verify (by code inspection and test) that `initSchema()` ordering across collections does not affect the final registry content. Each collection's `initSchema()` resolves its OWN reflected references by looking up the target collection's non-reflected reference (line 1097-1102 of `EntityCollection.java`). There is no dependency between reflected references across collections — collection A's reflected reference looks up collection B's original reference, not another reflected reference.

- [ ] **3.2** Verify that after ALL `initSchema()` calls complete, calling `referenceSchema.getFacetedPartiallyInScopes()` on any resolved `ReflectedReferenceSchema` returns the correct inherited expression. The `withReferencedSchema()` method (line 1739-1741 of `ReflectedReferenceSchema.java`) copies the source's `facetedPartiallyInScopes` when `facetedInherited == true`. After `initSchema()` calls `exchangeSchema()` (line 1107 of `EntityCollection.java`), the schema stored in the schema index has resolved reflected references.

- [ ] **3.3** Verify that mutual cross-references (entity A references entity B, entity B references entity A) do not cause issues during `initSchema()`. Since each collection resolves its own reflected references independently, and the resolution reads the target's non-reflected reference (not another reflected one), mutual references are safe.

##### Group 4: WAL replay readiness verification

- [ ] **4.1** Verify that the `processWriteAheadLog()` call in `Evita.loadCatalogInternal()` (line 1073) runs AFTER `buildInitialExpressionTriggerRegistry()`. Call chain: `loadCatalog()` completion handler calls `initSchema()` loop -> `buildInitialExpressionTriggerRegistry()` -> `onSuccess.accept()` -> `Evita` callback -> `catalog.processWriteAheadLog()`. The ordering is guaranteed by sequential execution within the completion handler.

- [ ] **4.2** Verify that WAL replay mutations go through the standard `LocalMutationExecutorCollector.execute()` pipeline. The call chain is: `TransactionManager.replayMutationsOnCatalog()` (line 1299) wraps mutations in `ServerEntityUpsertMutation`/`ServerEntityRemoveMutation` -> `catalog.applyMutation()` (line 1321/1331) -> `EntityCollection.applyMutation()` -> `applyMutationInternal()` (line 1881) -> creates `LocalMutationExecutorCollector` (line 1892) -> `execute()`. The collector's Step 5b (WBS-10) calls `entityIndexUpdater.popIndexImplicitMutations()` which reads from `catalog.getExpressionTriggerRegistry()`.

- [ ] **4.3** Verify that Step 5b in the collector runs unconditionally during WAL replay (not guarded by `generateImplicitMutations`). Per WBS-10 research, Step 5b is placed after the `if (!generateImplicitMutations.isEmpty())` block, so it always executes. For `ServerEntityUpsertMutation`, `generateImplicitMutations` is `EnumSet.allOf(ImplicitMutationBehavior.class)` (line 1325 of `TransactionManager.java`). For `ServerEntityRemoveMutation`, it is `EnumSet.of(GENERATE_REFLECTED_REFERENCES)`. In both cases, Step 5b runs.

- [ ] **4.4** Verify that during WAL replay, the catalog is in the `lastFinalizedCatalog` reference (line 966 of `TransactionManager.java`) and the `expressionTriggerRegistry` field on that catalog instance has the fully-built registry from the cold start. The `processEntireWriteAheadLog()` method (line 375) creates a `TransactionTrunkFinalizer` from `latestCatalog` (line 971), and `replayMutationsOnCatalog()` calls `getLastFinalizedCatalog()` (line 1308) which returns the same catalog instance.

##### Group 5: Catalog state transition correctness

- [ ] **5.1** Verify that `goLive()` produces a new catalog with a correctly-built registry. The new ALIVE catalog is created by the copy constructor with `initSchemas=true` (task 2.1). After `goLive()`, `transactionManager.advanceVersion()` is called (line 1118), which sets the new catalog as the live version. All subsequent mutations go through this new catalog, whose registry is fully populated.

- [ ] **5.2** Verify that the loading constructor (line 596, used by `loadCatalog()`) initializes the registry field to `EMPTY`. The registry is populated LATER by `buildInitialExpressionTriggerRegistry()` in the `loadCatalog()` completion handler. This is safe because no mutations can arrive until WAL replay starts, which happens AFTER the completion handler.

- [ ] **5.3** Verify that the public constructor (line 511, used for brand-new catalogs) initializes the registry to `EMPTY`. New catalogs have no entity collections, so no triggers exist. The first schema mutation that adds a reference with `facetedPartiallyInScopes` will trigger the WBS-04 `entitySchemaUpdated()` hook, which rebuilds the registry incrementally.

- [ ] **5.4** Verify that `createCopyWithMergedTransactionalMemory()` (line 1446) correctly propagates the registry. It creates a new `Catalog` via the 6-parameter constructor (which delegates to the 8-parameter with `initSchemas=false`). **Prerequisite: WBS-04 task 6.3 must be implemented first** — it adds `this.expressionTriggerRegistry = new TransactionalReference<>(previousCatalogVersion.getExpressionTriggerRegistry())` to the 8-parameter copy constructor. Once that is in place, the registry `TransactionalReference` is initialized from `previousCatalogVersion.getExpressionTriggerRegistry()`, which includes any transactional modifications. `getStateCopyWithCommittedChanges()` (used for `versionId` at line 1451 and `schema` at line 1453) is NOT called for the registry because the copy constructor initializes it from the previous version's committed value. If any schema changes during the transaction triggered registry rebuilds via `entitySchemaUpdated()`, those rebuilds modified the `TransactionalReference`'s transactional layer, which is merged when the new `TransactionalReference` is created with the committed value.

##### Group 6: Verification of `buildInitialExpressionTriggerRegistry()` method on `Catalog`

> **Note:** The `buildInitialExpressionTriggerRegistry()` method and its JavaDoc are **implemented by WBS-04 tasks 8.1-8.2**. The tasks below verify that the WBS-04 deliverables meet WBS-11's cold start requirements and integrate correctly with the initialization paths defined in Groups 1-2.

- [ ] **6.1** Verify that `buildInitialExpressionTriggerRegistry()` (implemented by WBS-04 task 8.1) is a package-private method on `Catalog` that:
  1. Calls `CatalogExpressionTriggerRegistryImpl.buildFromSchemas(this.entitySchemaIndex)` — the `entitySchemaIndex` map at this point contains fully-resolved schemas (including inherited `facetedPartiallyInScopes` on reflected references).
  2. Stores the result via `this.expressionTriggerRegistry.set(fullRegistry)`.
  3. Logs the number of registered triggers at INFO level.

- [ ] **6.2** Verify that the JavaDoc on `buildInitialExpressionTriggerRegistry()` (implemented by WBS-04 task 8.2) covers:
  - This method performs the initial (cold start) population of the `CatalogExpressionTriggerRegistry`
  - Must be called AFTER all `EntityCollection.initSchema()` calls complete (so reflected references are resolved)
  - Must be called BEFORE any WAL replay or client mutations are processed
  - Called from `loadCatalog()` completion handler (Path 1) and from the copy constructor when `initSchemas==true` (Path 2 — `goLive()`)
  - Uses `entitySchemaIndex` which at call time contains schemas with resolved reflected reference inheritance

- [ ] **6.3** Verify that `buildFromSchemas()` (WBS-04 task 4.1) correctly handles the entity schema index — both direct `ReferenceSchema` and resolved `ReflectedReferenceSchema` are iterated via `schema.getReferences().values()`, and `getFacetedPartiallyInScopes()` returns the correct expression for both types. No special handling for reflected vs. direct references is needed at the registry level.

##### Group 7: Interaction between `entitySchemaUpdated()` hook and cold start

- [ ] **7.1** Verify that `entitySchemaUpdated()` calls during `initSchema()` do not cause issues. During cold start, `initSchema()` may call `exchangeSchema()` -> `entitySchemaUpdated()` -> WBS-04 rebuild hook. At this point, the registry is `EMPTY` (not yet built by `buildFromSchemas()`). The rebuild hook calls `rebuildForEntityType()` on the empty registry, which:
  1. Scans the updated entity schema's references for `facetedPartiallyInScopes` expressions
  2. Builds triggers from those expressions
  3. Inserts them into a copy of the (empty) registry
  4. Stores via `TransactionalReference.set()`

  This is harmless — it incrementally builds up the registry during `initSchema()`. The subsequent `buildFromSchemas()` call after the loop produces a clean, definitive registry that overwrites any partial state.

- [ ] **7.2** Verify that `TransactionalReference.set()` during cold start (no active transaction) writes directly to the `AtomicReference` (confirmed at `TransactionalReference.java` — `if (layer == null) { this.value.set(value); }`). No transactional overhead during initialization.

- [ ] **7.3** Verify that the `buildFromSchemas()` call after the `initSchema()` loop is necessary and not redundant. During `initSchema()`, only collections whose reflected references are updated trigger `entitySchemaUpdated()`. Collections with no reflected references (or reflected references that don't change) do NOT trigger the hook. Therefore, the incremental rebuilds may miss triggers from non-reflected references on those collections. The `buildFromSchemas()` call covers ALL entity types and ALL references, making it the authoritative build. Confirm this by writing a test (see Test Class 9, `build_from_schemas_after_init_schema_should_overwrite_partial_incremental_state`) that demonstrates the incremental rebuilds alone produce an incomplete registry.

##### Group 8: Entity collection removal and rename during warm-up

- [ ] **8.1** Verify that entity collection removal during warm-up state calls `entitySchemaRemoved()` (line 2041 of `Catalog.java`), which per WBS-04 task 7.2 triggers a registry cleanup (rebuild with empty trigger list). This ensures removed collections' triggers are purged from the registry.

- [ ] **8.2** Verify that entity schema rename (`ModifyEntitySchemaNameMutation`) correctly re-indexes triggers under the new entity type name. Per WBS-04 task 9.2, renaming requires a full rebuild for the renamed entity type. The existing `modifyEntitySchemaName()` flow (line 1921) calls `renameEntityCollectionInternal()` or `replaceEntityCollectionInternal()`, both of which update the `entityCollections` and `entitySchemaIndex` maps. The WBS-04 `entitySchemaUpdated()` hook fires with the new schema (under the new name), triggering a rebuild.

### Test Cases

All tests use JUnit 5. Integration tests use the standard `EvitaTestSupport` pattern with
`cleanTestSubDirectory()` for isolated storage directories and `try (final Evita evita = ...)`
for lifecycle management. Tests that require only schema/registry logic without persistence use
mock schemas built via `EntitySchema` / `ReferenceSchema` / `ReflectedReferenceSchema` DTOs.

---

#### Test Class 1: `CatalogColdStartRegistryTest`

Integration test verifying end-to-end cold start registry wiring via persist-and-reload cycles.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogColdStartRegistryTest.java`

**Category: basic cold start wiring (tasks 1.1, 1.2, 6.1 / AC 1)**

- [ ] `cold_start_should_populate_registry_with_triggers_for_all_conditional_references` — create a catalog with entity types Product, Parameter, and ParameterGroup where Product has a reference "parameter" with `facetedPartiallyInScopes` expression depending on ParameterGroup's attribute `inputWidgetType`; persist to disk, close, reopen; verify `catalog.getExpressionTriggerRegistry().getTriggersFor("parameterGroup", GROUP_ENTITY_ATTRIBUTE)` returns a non-empty list containing a trigger with `ownerEntityType == "product"` and `referenceName == "parameter"`
- [ ] `cold_start_should_populate_triggers_for_referenced_entity_attribute_dependency` — create a catalog where Product references Parameter with `facetedPartiallyInScopes` expression depending on Parameter's own attribute `active`; persist, reload; verify `getTriggersFor("parameter", REFERENCED_ENTITY_ATTRIBUTE)` returns a trigger with `ownerEntityType == "product"`
- [ ] `cold_start_should_populate_triggers_for_multiple_entity_types_with_conditional_references` — create a catalog with Product referencing ParameterGroup and Category referencing Brand, both with conditional expressions; persist, reload; verify registry contains triggers under both "parameterGroup" and "brand" keys with correct owner entity types
- [ ] `cold_start_should_produce_two_triggers_for_reference_with_expressions_in_both_scopes` — create a reference with `facetedPartially` expressions in both `LIVE` and `ARCHIVED` scopes; persist, reload; verify `getTriggersFor()` returns two triggers — one per scope
- [ ] `cold_start_should_skip_references_without_faceted_partially_expressions` — create entity types with regular (non-conditional) faceted references alongside conditional ones; persist, reload; verify registry contains triggers only for the conditional references

**Category: registry build timing and WAL readiness (tasks 4.1, 4.2, 4.3, 4.4 / AC 3, 5)**

- [ ] `cold_start_registry_should_be_populated_before_wal_replay` — create a catalog with conditional references, apply entity mutations that trigger cross-entity re-evaluation, persist mutations to WAL but force no trunk flush, close and reopen; verify the catalog loads successfully and WAL replay correctly updates the facet indexes (observable via the end state of the entity's facet index entries after reload — e.g., query for faceted entities and verify the expected entities are/are not present in facet results)
- [ ] `cold_start_should_not_reevaluate_expressions_for_existing_data` — create a catalog with conditional references and entities, persist to disk, reload; verify no expression evaluation occurs during loading (indexes loaded from disk are already consistent); no `ReevaluateFacetExpressionMutation` is generated during load itself, only during subsequent WAL replay or client mutations
- [ ] `wal_replay_upsert_mutation_should_generate_cross_entity_reevaluation` — set up a catalog where Product references ParameterGroup with a conditional expression on `inputWidgetType`; persist schemas and a ParameterGroup entity; close; reopen; via WAL replay of a mutation that changes `inputWidgetType` on the ParameterGroup entity, verify the facet index state reflects the re-evaluation (e.g., products that should now be faceted are present in facet query results, and those that should not are absent)
- [ ] `wal_replay_remove_mutation_should_generate_cross_entity_reevaluation` — same setup as above but the WAL contains an entity removal; verify facet indexes are updated accordingly (entities referencing the removed entity as a group/referenced entity have their facet status re-evaluated, observable via facet query results)

**Category: multi-scope correctness after cold start**

- [ ] `cold_start_multi_scope_mutation_should_trigger_reevaluation_in_correct_scope_only` — create a reference with `facetedPartially` expressions in both `LIVE` and `ARCHIVED` scopes (with different expressions); persist, reload; apply a mutation that changes an attribute relevant only to the `LIVE` scope expression; verify the `LIVE` scope facet index is updated but the `ARCHIVED` scope facet index remains unchanged (the trigger should fire only for the scope whose expression depends on the changed attribute)

**Category: logging and observability (task 1.3)**

- [ ] `cold_start_should_log_registry_trigger_count_at_info_level` — create a catalog with known number of conditional references, persist, reload; verify an INFO-level log message is emitted containing the trigger count and catalog name (capture via log appender or test logger)

---

#### Test Class 2: `CatalogGoLiveRegistryTest`

Integration test verifying registry construction during the `goLive()` (WARMING_UP to ALIVE)
transition.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogGoLiveRegistryTest.java`

**Category: goLive registry build (tasks 2.1, 2.2, 2.3, 5.1 / AC 2)**

- [ ] `go_live_should_build_registry_from_current_schemas` — create a catalog in WARMING_UP state, define entity types with `facetedPartiallyInScopes` references, call `goLive()`; verify the new ALIVE catalog's `getExpressionTriggerRegistry()` returns non-empty trigger lists matching the schema definitions
- [ ] `go_live_should_produce_registry_identical_to_cold_start` — create a catalog, add conditional schemas, persist, close; reopen (cold start); separately, create a fresh catalog in WARMING_UP, apply the same schemas, call `goLive()`; compare both registries and verify they contain equivalent trigger sets (same keys, same trigger properties)
- [ ] `go_live_registry_should_support_subsequent_mutations_triggering_reevaluation` — after `goLive()`, apply a mutation that changes an attribute referenced by a conditional expression; verify the trigger fires and produces `ReevaluateFacetExpressionMutation`
- [ ] `go_live_copy_constructor_should_initialize_registry_from_previous_version_before_rebuild` — verify that the 8-parameter copy constructor initializes `expressionTriggerRegistry` from `previousCatalogVersion` before the `initSchema()` loop runs, and the subsequent `buildInitialExpressionTriggerRegistry()` overwrites it with the definitive build

**Category: goLive with reflected references (tasks 2.1, 3.2)**

- [ ] `go_live_should_resolve_reflected_reference_inheritance_before_registry_build` — create entity A with a direct reference carrying `facetedPartially`, and entity B with a `ReflectedReferenceSchema` inheriting from A's reference; call `goLive()`; verify the registry contains triggers for both A's direct and B's inherited expression

---

#### Test Class 3: `CatalogReflectedReferenceRegistryTest`

Integration test verifying reflected reference inheritance resolution and its effect on registry
content during cold start.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogReflectedReferenceRegistryTest.java`

**Category: reflected reference inheritance (tasks 3.1, 3.2 / AC 4)**

- [ ] `reflected_reference_inheriting_faceted_partially_should_produce_trigger_after_cold_start` — entity Product has reference "parameter" to Parameter with `facetedPartiallyInScopes` expression; entity Parameter has a `ReflectedReferenceSchema` "products" pointing back to Product's reference with `facetedInherited == true`; persist, reload; verify the registry contains a trigger derived from Parameter's reflected reference in addition to Product's direct reference trigger
- [ ] `reflected_reference_with_own_expression_should_use_own_expression_not_inherited` — entity Parameter has a `ReflectedReferenceSchema` with `facetedInherited == false` and its own `facetedPartiallyInScopes` expression; persist, reload; verify the trigger for Parameter uses Parameter's own expression (different dependent attributes than the source)
- [ ] `reflected_reference_without_faceted_inherited_should_not_produce_spurious_triggers` — entity Parameter has a `ReflectedReferenceSchema` with `facetedInherited == false` and no `facetedPartiallyInScopes`; persist, reload; verify no trigger is registered for Parameter's reflected reference

**Category: initSchema ordering independence (tasks 3.1, 3.3 / AC 6)**

- [ ] `init_schema_ordering_should_not_affect_final_registry_content` — create a catalog with five entity types (A through E), each having reflected references to others with conditional expressions; in the test, explicitly call `initSchema()` in two known orderings (e.g., A-B-C-D-E then E-D-C-B-A) followed by `buildInitialExpressionTriggerRegistry()`; verify both orderings produce equivalent registry states (same keys, same trigger properties). This avoids relying on `HashMap` iteration non-determinism and directly proves ordering independence
- [ ] `init_schema_with_chain_of_reflected_references_should_resolve_correctly` — entity A references B with `facetedPartially`; entity B has a reflected reference to A; entity C references B with `facetedPartially`; entity C also has a reflected reference inheriting from B; persist, reload; verify all triggers resolve correctly regardless of which collection's `initSchema()` runs first

---

#### Test Class 4: `CatalogMutualCrossReferenceRegistryTest`

Integration test verifying that mutual (bidirectional) cross-entity references with conditional
expressions are handled without ordering issues or data loss.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogMutualCrossReferenceRegistryTest.java`

**Category: mutual cross-references (task 3.3 / AC 6)**

- [ ] `mutual_cross_references_should_both_produce_triggers_after_cold_start` — entity Product has reference to Category with `facetedPartiallyInScopes` expression depending on Category's attribute; entity Category has reference to Product with `facetedPartiallyInScopes` expression depending on Product's attribute; persist, reload; verify `getTriggersFor("category", ...)` returns the Product-owned trigger AND `getTriggersFor("product", ...)` returns the Category-owned trigger
- [ ] `mutual_cross_references_should_both_produce_triggers_after_go_live` — same schema setup as above but via WARMING_UP -> `goLive()` transition; verify both triggers are present
- [ ] `mutual_cross_references_with_reflected_references_should_resolve_without_circular_dependency` — entity A references B (with `facetedPartially`), entity B has a reflected reference inheriting from A, AND entity B references A (with its own `facetedPartially`), entity A has a reflected reference inheriting from B; persist, reload; verify all four triggers (two direct, two reflected) are present and correct
- [ ] `mutation_on_one_side_of_mutual_reference_should_trigger_reevaluation_on_other_side` — after cold start with mutual cross-references, apply a mutation changing an attribute on entity Product; verify the Category-owned trigger fires generating a `ReevaluateFacetExpressionMutation` targeting "category"; similarly, mutate Category and verify the Product-owned trigger fires

---

#### Test Class 5: `CatalogEmptyRegistryTest`

Integration test verifying correct behavior when the catalog has no conditional faceting
expressions.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogEmptyRegistryTest.java`

**Category: empty catalog (AC boundary cases)**

- [ ] `empty_catalog_cold_start_should_produce_empty_registry` — create a catalog with no entity types, persist, reload; verify `getExpressionTriggerRegistry()` returns the `EMPTY` sentinel and no errors occur during initialization
- [ ] `catalog_with_entities_but_no_conditional_references_should_produce_empty_registry` — create a catalog with Product and Category entity types having regular (unconditional) faceted references; persist, reload; verify the registry is effectively empty (returns empty lists for all queries)
- [ ] `empty_catalog_go_live_should_produce_empty_registry` — create an empty catalog in WARMING_UP state, call `goLive()`; verify the ALIVE catalog has an empty registry
- [ ] `new_catalog_should_have_empty_registry_before_any_schema_mutations` — create a brand-new catalog (public constructor path); verify `getExpressionTriggerRegistry()` returns the `EMPTY` singleton immediately

---

#### Test Class 6: `CatalogRegistryEquivalenceTest`

Integration test verifying that registries built via cold start (`buildFromSchemas()`) and via
incremental schema mutations at runtime produce equivalent results.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogRegistryEquivalenceTest.java`

**Category: cold start vs incremental equivalence (task 7.3 / AC 2)**

- [ ] `cold_start_registry_should_match_incremental_schema_mutation_registry` — create a catalog with multiple conditional references via schema mutations at runtime, capture the live registry state; then persist, close, reopen via cold start; compare the cold-start registry against the runtime-built registry and verify they contain equivalent trigger entries (same entity type keys, same dependency types, same trigger properties)
- [ ] `cold_start_registry_should_match_go_live_registry_for_same_schemas` — create identical schemas in two catalogs: one loaded from disk (cold start) and one transitioned via `goLive()`; verify both produce equivalent registries
- [ ] `incremental_rebuilds_during_init_schema_followed_by_build_from_schemas_should_be_idempotent` — during cold start, `initSchema()` calls may trigger incremental rebuilds via the `entitySchemaUpdated()` hook; the subsequent `buildFromSchemas()` call overwrites the partial state; verify the final registry is identical to one built by `buildFromSchemas()` alone (without incremental rebuilds)

---

#### Test Class 7: `CatalogCollectionRemovalRegistryTest`

Integration test verifying registry cleanup when entity collections are removed during warm-up or
runtime.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogCollectionRemovalRegistryTest.java`

**Category: collection removal during warm-up (tasks 8.1, 8.2)**

- [ ] `removing_entity_collection_during_warmup_should_purge_its_triggers_from_registry` — create a catalog in WARMING_UP state with entity types Product (with conditional reference to ParameterGroup) and Category; remove the Product entity collection; verify the registry no longer contains any triggers where `ownerEntityType == "product"`; ParameterGroup key should return empty if Product was the only contributor
- [ ] `removing_entity_collection_should_not_affect_triggers_from_other_collections` — create Product and Category, both with conditional references to ParameterGroup; remove only Product; verify Category's triggers under the "parameterGroup" key remain intact
- [ ] `removing_and_re_adding_entity_collection_should_rebuild_triggers` — remove entity collection Product, then redefine it with the same (or different) conditional references; verify the registry is updated correctly with the new triggers
- [ ] `removing_entity_collection_after_cold_start_should_purge_triggers` — persist a catalog with conditional references, reload via cold start, then remove an entity collection; verify the removed collection's triggers are purged from the registry

**Category: entity collection rename (task 8.2)**

- [ ] `renaming_entity_collection_should_re_index_triggers_under_new_name` — create a catalog with entity type Product having a conditional reference to ParameterGroup; rename Product to Item via `ModifyEntitySchemaNameMutation`; verify the registry no longer contains triggers with `ownerEntityType == "product"` and instead contains triggers with `ownerEntityType == "item"` under the "parameterGroup" key
- [ ] `renaming_entity_collection_used_as_dependency_should_re_index_triggers_under_new_key` — create a catalog where Product has a conditional reference depending on ParameterGroup's attribute; rename ParameterGroup to ParamGroup; verify `getTriggersFor("parameterGroup", ...)` returns empty and `getTriggersFor("paramGroup", ...)` returns the Product-owned trigger

**Category: collection removal persistence round-trip**

- [ ] `removed_collection_triggers_should_be_absent_after_persist_and_reload` — create a catalog with two entity types with conditional references, remove one entity type, persist, reload; verify the removed type's triggers are not in the cold-start registry

---

#### Test Class 8: `CatalogRegistryTransactionPropagationTest`

Integration test verifying that the registry propagates correctly through transaction commits and
catalog version updates.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogRegistryTransactionPropagationTest.java`

**Category: transaction commit propagation (tasks 5.2, 5.3, 5.4)**

- [ ] `transaction_commit_should_propagate_registry_to_new_catalog_version` — in an ALIVE catalog, perform a schema mutation that adds a conditional reference within a transaction; commit the transaction; verify the new catalog version (produced by `createCopyWithMergedTransactionalMemory()`) has the updated registry with the new trigger
- [ ] `schema_mutation_within_transaction_should_rebuild_registry_visible_only_to_that_transaction` — within a transaction, add a conditional reference via schema mutation; verify the registry within that transaction contains the new trigger; verify a concurrent reader outside the transaction sees the old registry (without the new trigger)
- [ ] `loading_constructor_should_initialize_registry_to_empty` — verify that a catalog created via the loading constructor (cold start path) has `EMPTY` as its initial registry value before `buildInitialExpressionTriggerRegistry()` is called
- [ ] `public_constructor_should_initialize_registry_to_empty` — verify a brand-new catalog (no entity collections) initializes the registry to `EMPTY`

**Category: TransactionalReference behavior during cold start (task 7.2)**

- [ ] `registry_set_during_cold_start_without_transaction_should_write_directly` — outside any transaction context (as during catalog initialization), call `expressionTriggerRegistry.set(newRegistry)`; verify `get()` immediately returns `newRegistry` (no transactional layer involved)

---

#### Test Class 9: `CatalogInitSchemaInteractionTest`

Integration test verifying the interaction between `initSchema()`, `entitySchemaUpdated()`, and
the registry build during cold start.

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/catalog/CatalogInitSchemaInteractionTest.java`

**Category: entitySchemaUpdated hook during initSchema (tasks 7.1, 7.3)**

- [ ] `entity_schema_updated_during_init_schema_on_empty_registry_should_not_cause_errors` — during cold start, `initSchema()` calls `exchangeSchema()` which calls `entitySchemaUpdated()`; the registry is `EMPTY` at this point; verify no exception is thrown and the incremental rebuild produces a partial registry
- [ ] `build_from_schemas_after_init_schema_should_overwrite_partial_incremental_state` — simulate the cold start sequence: incremental rebuilds during `initSchema()` produce a partial registry; the subsequent `buildFromSchemas()` call produces the authoritative registry; verify the final registry matches what `buildFromSchemas()` alone would produce
- [ ] `init_schema_for_collection_without_reflected_references_should_not_trigger_rebuild` — if a collection has no reflected references (or reflected references that do not change during `initSchema()`), `entitySchemaUpdated()` should not be called for that collection; verify the hook fires only for collections whose schemas actually change during `initSchema()`

**Category: resolved reflected reference schemas in registry build (task 6.3)**

- [ ] `build_from_schemas_should_use_fully_resolved_reflected_references` — after all `initSchema()` calls, the `entitySchemaIndex` contains schemas where `ReflectedReferenceSchema.getFacetedPartiallyInScopes()` returns the inherited expression; verify `buildFromSchemas()` correctly reads and indexes these inherited expressions
- [ ] `build_from_schemas_should_handle_mix_of_direct_and_reflected_references` — entity schema with both a direct reference (own `facetedPartially`) and a reflected reference (inherited `facetedPartially`); verify `buildFromSchemas()` produces triggers for both, indexed under the correct mutated entity type keys
